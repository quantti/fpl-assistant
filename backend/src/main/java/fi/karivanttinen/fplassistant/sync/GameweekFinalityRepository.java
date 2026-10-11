package fi.karivanttinen.fplassistant.sync;

import java.sql.Array;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.FixtureRow;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.Season;
import fi.karivanttinen.fplassistant.sync.GameweekFinality.ComponentCoverage;
import fi.karivanttinen.fplassistant.sync.GameweekFinality.FinalReceipt;
import fi.karivanttinen.fplassistant.sync.GameweekFinality.RequiredPopulation;
import fi.karivanttinen.fplassistant.sync.GameweekFinality.RowRevision;
import fi.karivanttinen.fplassistant.sync.GameweekFinality.RowState;
import fi.karivanttinen.fplassistant.sync.GameweekFinality.SourceSnapshot;
import fi.karivanttinen.fplassistant.sync.GameweekFinality.State;
import fi.karivanttinen.fplassistant.sync.GameweekFinality.SweepCheckpoint;
import fi.karivanttinen.fplassistant.sync.HistoryParser.Component;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.Lease;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.SourceRead;

/** Short, fenced persistence boundary for checked-gameweek final sweep checkpoints and receipts. */
public final class GameweekFinalityRepository {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final OwnedSyncStore store;
    private final OwnedSyncTables tables;
    private final Clock clock;

    public GameweekFinalityRepository(JdbcTemplate jdbc, OwnedSyncStore store,
                                      OwnedSyncTables tables, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.store = Objects.requireNonNull(store);
        this.tables = Objects.requireNonNull(tables);
        this.clock = Objects.requireNonNull(clock);
    }

    public Optional<Progress> load(Lease lease, Season season, int gameweek) {
        return fenced(lease, season, () -> {
            if (gameweek < 1 || gameweek > 38) throw new IllegalArgumentException("Invalid gameweek");
            List<Integer> exists = jdbc.queryForList("SELECT 1 FROM " + tables.gameweekStatSync()
                    + " WHERE season_id=? AND gameweek=?", Integer.class, season.id(), gameweek);
            return exists.isEmpty() ? Optional.empty() : Optional.of(progress(lockGameweek(season, gameweek)));
        });
    }

    /** Reuses a finalized event only while its exact current rows and page watermarks remain valid. */
    public Set<Integer> currentFinalizedGameweeks(Lease lease, Season season,
                                                   CatalogueScheduleParser.Snapshot snapshot,
                                                   Set<Integer> candidates) {
        Objects.requireNonNull(snapshot);
        Objects.requireNonNull(candidates);
        return fenced(lease, season, () -> {
            Set<Integer> valid = new TreeSet<>();
            Instant detectedAt = max(snapshot.bootstrapRead().completedAt(), snapshot.fixturesRead().completedAt());
            for (int gameweek : candidates.stream().sorted().toList()) {
                SourceSnapshot source = SourceSnapshot.from(snapshot, gameweek);
                StoredGameweek row = lockGameweek(season, gameweek);
                if (row.state() != State.FINALIZED) continue;
                PopulationManifest manifest = row.populationManifest();
                SweepCheckpoint checkpoint = row.checkpoint();
                boolean proof = manifest != null && checkpoint != null && source.finalSweepEligible()
                        && source.signature().equals(row.sourceSignature())
                        && checkpoint.sourceSignature().equals(source.signature())
                        && checkpoint.invalidationRevision() == row.invalidationRevision()
                        && checkpoint.outstandingPlayerIds().isEmpty()
                        && checkpoint.completedPlayerIds().equals(checkpoint.requiredPlayerIds())
                        && Objects.equals(row.finalizedInvalidationRevision(), row.invalidationRevision())
                        && row.finalizedAt() != null && row.finalizedRunGeneration() != null
                        && row.finalizedSourceReadInterval() != null
                        && Objects.equals(manifest.population().bootstrapFingerprint(),
                                GameweekFinality.catalogueFingerprint(snapshot, source.assignedFixtures()))
                        && Objects.equals(row.finalizedPopulationFingerprint(),
                                manifest.population().finalizedFingerprint(source));
                CurrentRows current = proof ? currentRows(season, source) : null;
                Map<Integer, PageEvidence> pages = proof ? freshPageReceipts(season,
                        checkpoint.requiredPlayerIds(), checkpoint.sourceFloor(), lease.generation()) : Map.of();
                proof = proof && current.valid()
                        && Objects.equals(row.finalizedRowFingerprint(), current.fingerprint())
                        && pages.keySet().equals(checkpoint.requiredPlayerIds())
                        && validatesAppearanceRows(source, manifest.population(), checkpoint, current);
                if (proof) valid.add(gameweek);
                else invalidatePopulationRevision(season, gameweek, row, detectedAt);
            }
            return Set.copyOf(valid);
        });
    }

    /** Creates a bounded checkpoint or resumes it only for the exact source/population/floor revision. */
    public Progress beginOrResume(Lease lease, Season season, SourceSnapshot source,
                                  RequiredPopulation population, SourceRead sourceRead) {
        validateRequest(season, source, population, sourceRead);
        return fenced(lease, season, () -> {
            StoredGameweek row = lockGameweek(season, source.gameweek());
            requireEligible(row, source);
            PopulationManifest effectiveManifest = effectivePopulation(season, source, population,
                    row.populationManifest(), row.invalidationFloor(), row.checkpoint());
            RequiredPopulation effectivePopulation = effectiveManifest.population();
            String populationFingerprint = effectivePopulation.fingerprint(source);
            String finalizedFingerprint = effectivePopulation.finalizedFingerprint(source);
            boolean populationChanged = row.state() == State.FINALIZED
                    && row.finalizedPopulationFingerprint() != null
                    && !row.finalizedPopulationFingerprint().equals(finalizedFingerprint)
                    || row.checkpoint() != null
                    && !row.checkpoint().populationFingerprint().equals(populationFingerprint);
            if (populationChanged) {
                invalidatePopulationRevision(season, source.gameweek(), row, sourceRead.finishedAt());
                row = lockGameweek(season, source.gameweek());
            }
            if (row.state() == State.FINALIZED
                    && finalizedFingerprint.equals(row.finalizedPopulationFingerprint())) {
                CurrentRows current = currentRows(season, source);
                boolean pagesCurrent = row.checkpoint() != null && freshPageReceipts(season,
                        row.checkpoint().requiredPlayerIds(), row.checkpoint().sourceFloor(), lease.generation())
                        .keySet().equals(row.checkpoint().requiredPlayerIds());
                if (current.valid() && pagesCurrent
                        && Objects.equals(row.finalizedRowFingerprint(), current.fingerprint())
                        && validatesAppearanceRows(source, effectivePopulation, row.checkpoint(), current)) {
                    return progress(row);
                }
                invalidatePopulationRevision(season, source.gameweek(), row, sourceRead.finishedAt());
                row = lockGameweek(season, source.gameweek());
            }

            boolean compatible = row.checkpoint() != null && row.invalidationFloor() != null
                    && row.populationManifest() != null && row.attemptSourceReadInterval() != null
                    && row.initialSourceReadInterval() != null && source.canResume(row.checkpoint(),
                            new GameweekFinality.CurrentInvalidation(row.invalidationRevision(),
                                    row.invalidationFloor()), populationFingerprint);
            Instant floor = compatible ? row.checkpoint().sourceFloor()
                    : freshFloor(sourceRead.finishedAt(), row.invalidationFloor());
            Set<Integer> required = effectivePopulation.requiredPlayerIds();
            Map<Integer, PageEvidence> pages = freshPageReceipts(season, required, floor, lease.generation());
            Set<Integer> completed = new TreeSet<>(pages.keySet());
            if (!effectivePopulation.participantEvidenceByFixture().isEmpty()) {
                CurrentRows current = currentRows(season, source);
                completed = pagesWithAppearanceRows(source, effectivePopulation, current, completed);
            }
            SourceInterval pageInterval = interval(pages.values());
            SourceRead initialAttemptRead = compatible ? row.initialSourceReadInterval() : sourceRead;
            SourceRead attemptInterval = sourceInterval(compatible ? row.attemptSourceReadInterval() : null,
                    sourceRead, pageInterval, participantEvidenceInterval(effectivePopulation));
            persistAttempt(season, source.gameweek(), source.signature(), row.invalidationRevision(),
                    populationFingerprint, effectiveManifest, floor, required, completed,
                    difference(required, completed), pageInterval, initialAttemptRead, attemptInterval);
            store.includeIncrementalSourceRead(lease, attemptInterval);
            return progress(lockGameweek(season, source.gameweek()));
        });
    }

    /** Records a page only when its durable publisher receipt proves a fresh successful read. */
    public Progress recordCompletedPage(Lease lease, Season season, int gameweek, int playerId) {
        if (gameweek < 1 || gameweek > 38 || playerId <= 0) throw new IllegalArgumentException("Invalid page identity");
        return fenced(lease, season, () -> {
            StoredGameweek row = lockGameweek(season, gameweek);
            requireFinalPending(row);
            SweepCheckpoint checkpoint = requireCheckpoint(row);
            if (checkpoint.invalidationRevision() != row.invalidationRevision()
                    || !checkpoint.sourceSignature().equals(row.sourceSignature())
                    || checkpoint.sourceFloor().isBefore(row.invalidationFloor())) {
                throw new IllegalStateException("Page completion belongs to an obsolete gameweek revision");
            }
            if (!checkpoint.requiredPlayerIds().contains(playerId)) {
                throw new IllegalArgumentException("Player page is outside the required final-sweep manifest");
            }
            Map<Integer, PageEvidence> pages = freshPageReceipts(season,
                    checkpoint.requiredPlayerIds(), checkpoint.sourceFloor(), lease.generation());
            if (!pages.containsKey(playerId)) {
                throw new IllegalStateException("Player page has no fresh successful incremental receipt");
            }
            SourceInterval pageInterval = interval(pages.values());
            SourceRead attemptInterval = sourceInterval(row.attemptSourceReadInterval(), null, pageInterval, null);
            persistAttempt(season, gameweek, row.sourceSignature(), row.invalidationRevision(),
                    checkpoint.populationFingerprint(), row.populationManifest(), checkpoint.sourceFloor(),
                    checkpoint.requiredPlayerIds(),
                    pages.keySet(), difference(checkpoint.requiredPlayerIds(), pages.keySet()), pageInterval,
                    row.initialSourceReadInterval(), attemptInterval);
            store.includeIncrementalSourceRead(lease, attemptInterval);
            return progress(lockGameweek(season, gameweek));
        });
    }

    /** Commits FINALIZED only after fresh source, page, row and participant evidence agree. */
    public FinalReceipt finalizeSweep(Lease lease, Season season, SourceSnapshot endSource,
                                      RequiredPopulation endPopulation, SourceRead endSourceRead) {
        validateRequest(season, endSource, endPopulation, endSourceRead);
        return fenced(lease, season, () -> {
            StoredGameweek row = lockGameweek(season, endSource.gameweek());
            requireEligible(row, endSource);
            requireFinalPending(row);
            SweepCheckpoint checkpoint = requireCheckpoint(row);
            PopulationManifest effectiveManifest = effectivePopulation(season, endSource, endPopulation,
                    row.populationManifest(), row.invalidationFloor(), row.checkpoint());
            RequiredPopulation effectivePopulation = effectiveManifest.population();
            String populationFingerprint = effectivePopulation.fingerprint(endSource);
            String finalizedFingerprint = effectivePopulation.finalizedFingerprint(endSource);
            if (!checkpoint.populationFingerprint().equals(populationFingerprint)
                    || !effectivePopulation.hasCompleteAppearanceEvidence(endSource, checkpoint.sourceFloor())
                    || checkpoint.invalidationRevision() != row.invalidationRevision()
                    || row.invalidationFloor() == null || checkpoint.sourceFloor().isBefore(row.invalidationFloor())) {
                throw new IllegalStateException("Final sweep checkpoint no longer matches the current revision");
            }
            if (row.attemptSourceReadInterval() == null
                    || !endSourceRead.startedAt().isAfter(checkpoint.sourceFloor())
                    || endSourceRead.startedAt().isBefore(row.attemptSourceReadInterval().finishedAt())
                    || row.pageReadFinishedAt() != null
                        && endSourceRead.startedAt().isBefore(row.pageReadFinishedAt())) {
                throw new IllegalStateException("Final source re-read must follow the final source and page reads");
            }
            Map<Integer, PageEvidence> pages = freshPageReceipts(season,
                    checkpoint.requiredPlayerIds(), checkpoint.sourceFloor(), lease.generation());
            CurrentRows current = currentRows(season, endSource);
            Set<Integer> completed = pagesWithAppearanceRows(endSource, effectivePopulation,
                    current, pages.keySet());
            SourceInterval pageInterval = interval(pages.values());
            SourceRead finalSourceInterval = sourceInterval(row.attemptSourceReadInterval(), endSourceRead,
                    pageInterval, participantEvidenceInterval(effectivePopulation));
            persistAttempt(season, endSource.gameweek(), endSource.signature(), row.invalidationRevision(),
                    populationFingerprint, effectiveManifest, checkpoint.sourceFloor(), checkpoint.requiredPlayerIds(),
                    completed, difference(checkpoint.requiredPlayerIds(), completed), pageInterval,
                    row.initialSourceReadInterval(), finalSourceInterval);
            store.includeIncrementalSourceRead(lease, finalSourceInterval);
            if (completed.size() != checkpoint.requiredPlayerIds().size()) {
                throw new IllegalStateException("Final sweep still has outstanding player pages");
            }
            if (!current.valid()) throw new IllegalStateException("Current PFS core row evidence is invalid or tampered");
            if (!validatesAppearanceRows(endSource, effectivePopulation, checkpoint, current)) {
                throw new IllegalStateException("A positive fixture appearance lacks matching live/history row evidence");
            }
            SourceRead ownedRunInterval = store.incrementalSourceInterval(lease);
            if (ownedRunInterval.startedAt().isAfter(finalSourceInterval.startedAt())
                    || ownedRunInterval.finishedAt().isBefore(finalSourceInterval.finishedAt())) {
                throw new IllegalStateException("Owned run interval does not cover the final-sweep source interval");
            }
            Instant now = clock.instant();
            FinalReceipt receipt = new FinalReceipt(endSource.signature(), current.fingerprint(), lease.generation(),
                    checkpoint.sourceFloor(), checkpoint.invalidationRevision(), now, finalSourceInterval,
                    true, current.coverage());
            jdbc.update("""
                    UPDATE %s SET state='FINALIZED', finalized_source_signature=?,
                        finalized_population_fingerprint=?, finalized_row_revision_fingerprint=?,
                        finalized_source_floor=?, finalized_validation_read_started_at=?,
                        finalized_validation_read_finished_at=?, finalized_source_read_started_at=?,
                        finalized_source_read_finished_at=?, finalized_invalidation_revision=?,
                        finalized_run_generation=?, finalized_page_evidence=?::jsonb, finalized_at=?,
                        finalized_mandatory_manifest_complete=TRUE,
                        finalized_optional_component_coverage=?::jsonb, updated_at=?
                    WHERE season_id=? AND gameweek=?
                    """.formatted(tables.gameweekStatSync()), receipt.sourceSignature(), finalizedFingerprint,
                    receipt.rowRevisionFingerprint(), timestamp(receipt.sourceFloor()),
                    timestamp(endSourceRead.startedAt()), timestamp(endSourceRead.finishedAt()),
                    timestamp(finalSourceInterval.startedAt()), timestamp(finalSourceInterval.finishedAt()),
                    receipt.invalidationRevision(), receipt.runGeneration(), pageEvidenceJson(pages), timestamp(now),
                    coverageJson(receipt.optionalCoverage()), timestamp(now), season.id(), endSource.gameweek());
            return receipt;
        });
    }

    private <T> T fenced(Lease lease, Season season, java.util.function.Supplier<T> action) {
        return store.withinIncrementalCoreLease(lease, season.id(), season.code(), action);
    }

    private void validateRequest(Season season, SourceSnapshot source, RequiredPopulation population, SourceRead read) {
        Objects.requireNonNull(season);
        Objects.requireNonNull(source);
        Objects.requireNonNull(population);
        Objects.requireNonNull(read);
        if (source.seasonId() != season.id() || !source.seasonCode().equals(season.code())
                || !source.finalSweepEligible() || read.finishedAt().isAfter(clock.instant())) {
            throw new IllegalArgumentException("Final sweep needs a fresh eligible source snapshot for the playing season");
        }
        population.validateFor(source);
    }

    private PopulationManifest effectivePopulation(Season season, SourceSnapshot source,
                                                   RequiredPopulation requested,
                                                   PopulationManifest previous,
                                                   Instant invalidationFloor,
                                                   SweepCheckpoint checkpoint) {
        Set<Integer> fixtureIds = source.assignedFixtures().stream().map(FixtureRow::id)
                .collect(java.util.stream.Collectors.toSet());
        Map<Integer, String> identities = fixtureIdentities(source);
        Map<Integer, Set<Integer>> catalogue = mutable(requested.cataloguePlayerIdsByFixture(), fixtureIds);
        Map<Integer, Set<Integer>> participants = mutable(requested.participantPlayerIdsByFixture(), fixtureIds);
        Map<Integer, Set<Integer>> known = mutable(requested.knownPlayerIdsByFixture(), fixtureIds);

        ParticipantManifest durableParticipants = participantManifest(season, fixtureIds);
        if (previous != null) {
            boolean hasMatchingIdentity = false;
            for (int fixtureId : fixtureIds) {
                if (!Objects.equals(previous.fixtureIdentities().get(fixtureId), identities.get(fixtureId))) continue;
                hasMatchingIdentity = true;
                merge(catalogue, previous.population().cataloguePlayerIdsByFixture(), Set.of(fixtureId));
                merge(participants, previous.population().participantPlayerIdsByFixture(), Set.of(fixtureId));
                merge(known, previous.population().knownPlayerIdsByFixture(), Set.of(fixtureId));
            }
            if (hasMatchingIdentity) {
                merge(known, durableParticipants.playerIdsByFixture(),
                        matchingFixtures(previous, identities));
            }
        } else {
            merge(known, durableParticipants.playerIdsByFixture(), fixtureIds);
        }
        knownRows(season, source).forEach((fixture, ids) -> ids.forEach(player -> {
            if (!catalogue.getOrDefault(fixture, Set.of()).contains(player)
                    && !participants.getOrDefault(fixture, Set.of()).contains(player)
                    && !known.getOrDefault(fixture, Set.of()).contains(player)) {
                known.computeIfAbsent(fixture, ignored -> new TreeSet<>()).add(player);
            }
        }));

        Instant currentFloor = max(invalidationFloor,
                checkpoint == null ? null : checkpoint.sourceFloor());
        Map<Integer, FixtureVerification.FixtureEvidence> evidence = new java.util.TreeMap<>(
                requested.participantEvidenceByFixture());
        if (previous != null && checkpoint != null
                && checkpoint.sourceSignature().equals(source.signature())) {
            for (int fixtureId : matchingFixtures(previous, identities)) {
                FixtureVerification.FixtureEvidence retained = previous.population()
                        .participantEvidenceByFixture().get(fixtureId);
                if (!evidence.containsKey(fixtureId) && retained != null
                        && (currentFloor == null || !retained.liveRead().startedAt().isBefore(currentFloor))) {
                    evidence.put(fixtureId, retained);
                }
            }
        }
        RequiredPopulation effective = new RequiredPopulation(requested.bootstrapFingerprint(), catalogue,
                participants, known, evidence);
        return new PopulationManifest(effective, identities);
    }

    private ParticipantManifest participantManifest(Season season, Set<Integer> fixtureIds) {
        if (fixtureIds.isEmpty()) return new ParticipantManifest(Map.of());
        String pattern = OwnedSyncStore.INCREMENTAL_PARTICIPANT_RECEIPT_PREFIX + "%:player:%";
        String prefix = OwnedSyncStore.INCREMENTAL_PARTICIPANT_RECEIPT_PREFIX;
        Map<Integer, Set<Integer>> ids = new java.util.TreeMap<>();
        jdbc.query("SELECT publication_key FROM " + tables.receipt()
                + " WHERE season=? AND publication_key LIKE ? AND latest_generation IS NOT NULL",
                rs -> {
                    String key = rs.getString("publication_key");
                    int separator = key.indexOf(":player:", prefix.length());
                    if (separator < 0) return;
                    try {
                        int fixtureId = Integer.parseInt(key.substring(prefix.length(), separator));
                        int playerId = Integer.parseInt(key.substring(separator + ":player:".length()));
                        if (fixtureIds.contains(fixtureId) && fixtureId > 0 && playerId > 0) {
                            ids.computeIfAbsent(fixtureId, ignored -> new TreeSet<>()).add(playerId);
                        }
                    } catch (NumberFormatException invalidKey) {
                        throw new IllegalStateException("Invalid durable fixture participant key", invalidKey);
                    }
                }, season.code(), pattern);
        return new ParticipantManifest(immutable(ids));
    }

    private Map<Integer, Set<Integer>> knownRows(Season season, SourceSnapshot source) {
        List<Integer> fixtureIds = source.assignedFixtures().stream().map(FixtureRow::id).sorted().toList();
        if (fixtureIds.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(fixtureIds.size(), "?"));
        Map<Integer, Set<Integer>> result = new java.util.TreeMap<>();
        jdbc.query("""
                SELECT p.fixture_id,p.player_id FROM player_fixture_stats p
                WHERE p.season_id=? AND p.gameweek=? AND p.fixture_id IN (%s)
                  AND NOT EXISTS (SELECT 1 FROM %s c WHERE c.season_id=p.season_id
                      AND c.fixture_id=p.fixture_id AND c.player_id=p.player_id AND c.row_state='WITHDRAWN')
                UNION
                SELECT c.fixture_id,c.player_id FROM %s c JOIN player_fixture_stats p
                    ON p.season_id=c.season_id AND p.fixture_id=c.fixture_id AND p.player_id=c.player_id
                    AND p.gameweek=?
                WHERE c.season_id=? AND c.row_state='VALID' AND c.fixture_id IN (%s)
                """.formatted(placeholders, tables.playerFixtureCurrent(), tables.playerFixtureCurrent(), placeholders), rs -> {
            result.computeIfAbsent(rs.getInt("fixture_id"), ignored -> new TreeSet<>()).add(rs.getInt("player_id"));
        }, queryKnownParameters(season, source, fixtureIds).toArray());
        return immutable(result);
    }

    private List<Object> queryKnownParameters(Season season, SourceSnapshot source, List<Integer> fixtureIds) {
        List<Object> parameters = new ArrayList<>();
        parameters.add(season.id());
        parameters.add(source.gameweek());
        parameters.addAll(fixtureIds);
        parameters.add(source.gameweek());
        parameters.add(season.id());
        parameters.addAll(fixtureIds);
        return parameters;
    }

    static Map<Integer, String> fixtureIdentities(SourceSnapshot source) {
        Map<Integer, String> result = new java.util.TreeMap<>();
        for (FixtureRow fixture : source.assignedFixtures()) {
            String identity = fixture.seasonId() + "|" + fixture.seasonCode() + "|" + fixture.id() + "|"
                    + fixture.code() + "|" + fixture.homeTeamId() + "|" + fixture.awayTeamId();
            result.put(fixture.id(), OwnedSyncStore.sha256(identity));
        }
        return Map.copyOf(result);
    }

    private static Set<Integer> matchingFixtures(PopulationManifest previous, Map<Integer, String> identities) {
        Set<Integer> matching = new HashSet<>();
        identities.forEach((fixture, identity) -> {
            if (identity.equals(previous.fixtureIdentities().get(fixture))) matching.add(fixture);
        });
        return Set.copyOf(matching);
    }

    private static Map<Integer, Set<Integer>> mutable(Map<Integer, Set<Integer>> source, Set<Integer> fixtures) {
        Map<Integer, Set<Integer>> result = new java.util.TreeMap<>();
        source.forEach((fixture, ids) -> {
            if (fixtures.contains(fixture)) result.put(fixture, new TreeSet<>(ids));
        });
        return result;
    }

    private static void merge(Map<Integer, Set<Integer>> target, Map<Integer, Set<Integer>> source,
                              Set<Integer> allowedFixtures) {
        source.forEach((fixture, ids) -> {
            if (allowedFixtures.contains(fixture)) {
                target.computeIfAbsent(fixture, ignored -> new TreeSet<>()).addAll(ids);
            }
        });
    }

    private static String populationManifestJson(PopulationManifest manifest) {
        StringBuilder json = new StringBuilder("{\"bootstrapFingerprint\":\"")
                .append(manifest.population().bootstrapFingerprint()).append("\",\"catalogue\":");
        appendPopulationJson(json, manifest.population().cataloguePlayerIdsByFixture());
        json.append(",\"participants\":");
        appendPopulationJson(json, manifest.population().participantPlayerIdsByFixture());
        json.append(",\"known\":");
        appendPopulationJson(json, manifest.population().knownPlayerIdsByFixture());
        json.append(",\"fixtureIdentities\":{");
        boolean first = true;
        for (Map.Entry<Integer, String> fixture : new java.util.TreeMap<>(manifest.fixtureIdentities()).entrySet()) {
            if (!first) json.append(',');
            first = false;
            json.append('\"').append(fixture.getKey()).append("\":\"").append(fixture.getValue()).append('\"');
        }
        json.append("},\"participantEvidence\":{");
        first = true;
        for (Map.Entry<Integer, FixtureVerification.FixtureEvidence> entry
                : new java.util.TreeMap<>(manifest.population().participantEvidenceByFixture()).entrySet()) {
            if (!first) json.append(',');
            first = false;
            FixtureVerification.FixtureEvidence evidence = entry.getValue();
            json.append('"').append(entry.getKey()).append("\":{\"complete\":")
                    .append(evidence.complete()).append(",\"positive\":[");
            boolean firstPlayer = true;
            for (int player : evidence.positivePlayerIds().stream().sorted().toList()) {
                if (!firstPlayer) json.append(',');
                firstPlayer = false;
                json.append(player);
            }
            json.append("],\"appearances\":{");
            firstPlayer = true;
            for (Map.Entry<Integer, FixtureVerification.Appearance> player
                    : new java.util.TreeMap<>(evidence.appearances()).entrySet()) {
                if (!firstPlayer) json.append(',');
                firstPlayer = false;
                json.append('"').append(player.getKey()).append("\":{\"side\":\"")
                        .append(player.getValue().side()).append("\",\"minutes\":")
                        .append(player.getValue().minutes()).append('}');
            }
            json.append("},\"liveStartedAt\":\"").append(evidence.liveRead().startedAt())
                    .append("\",\"liveFinishedAt\":\"").append(evidence.liveRead().completedAt())
                    .append("\"}");
        }
        return json.append("}}").toString();
    }

    private static void appendPopulationJson(StringBuilder json, Map<Integer, Set<Integer>> population) {
        json.append('{');
        boolean firstFixture = true;
        for (Map.Entry<Integer, Set<Integer>> fixture : population.entrySet()) {
            if (!firstFixture) json.append(',');
            firstFixture = false;
            json.append('\"').append(fixture.getKey()).append("\":[");
            boolean firstPlayer = true;
            for (int playerId : fixture.getValue().stream().sorted().toList()) {
                if (!firstPlayer) json.append(',');
                firstPlayer = false;
                json.append(playerId);
            }
            json.append(']');
        }
        json.append('}');
    }

    static PopulationManifest populationManifest(String value) {
        if (value == null) return null;
        try {
            JsonNode root = JSON.readTree(value);
            RequiredPopulation population = new RequiredPopulation(root.path("bootstrapFingerprint").asText(),
                    parsePopulation(root.path("catalogue")), parsePopulation(root.path("participants")),
                    parsePopulation(root.path("known")), parseParticipantEvidence(root.path("participantEvidence")));
            JsonNode identitiesNode = root.path("fixtureIdentities");
            if (!identitiesNode.isObject()) throw new IllegalArgumentException("Invalid fixture identity manifest");
            Map<Integer, String> identities = new java.util.TreeMap<>();
            identitiesNode.fields().forEachRemaining(entry -> {
                int fixtureId = Integer.parseInt(entry.getKey());
                String fingerprint = entry.getValue().asText();
                if (fixtureId <= 0 || !fingerprint.matches("[0-9a-f]{64}")) {
                    throw new IllegalArgumentException("Invalid fixture identity manifest");
                }
                identities.put(fixtureId, fingerprint);
            });
            return new PopulationManifest(population, identities);
        } catch (java.io.IOException | NumberFormatException invalid) {
            throw new IllegalStateException("Invalid durable final-sweep population manifest", invalid);
        }
    }

    private static Map<Integer, FixtureVerification.FixtureEvidence> parseParticipantEvidence(JsonNode node) {
        if (node.isMissingNode()) return Map.of();
        if (!node.isObject()) throw new IllegalArgumentException("Invalid fixture participant evidence");
        Map<Integer, FixtureVerification.FixtureEvidence> result = new java.util.TreeMap<>();
        node.fields().forEachRemaining(entry -> {
            int fixtureId = Integer.parseInt(entry.getKey());
            JsonNode value = entry.getValue();
            Set<Integer> positive = parseIds(value.path("positive"));
            JsonNode appearancesNode = value.path("appearances");
            if (fixtureId <= 0 || !appearancesNode.isObject()
                    || !value.path("complete").isBoolean()) {
                throw new IllegalArgumentException("Invalid fixture participant evidence");
            }
            Map<Integer, FixtureVerification.Appearance> appearances = new java.util.TreeMap<>();
            appearancesNode.fields().forEachRemaining(player -> {
                int playerId = Integer.parseInt(player.getKey());
                JsonNode appearance = player.getValue();
                if (playerId <= 0 || !appearance.path("side").isTextual()
                        || !appearance.path("minutes").canConvertToInt()) {
                    throw new IllegalArgumentException("Invalid fixture appearance evidence");
                }
                appearances.put(playerId, new FixtureVerification.Appearance(
                        FixtureVerification.Side.valueOf(appearance.path("side").asText()),
                        appearance.path("minutes").intValue()));
            });
            var liveRead = new CatalogueScheduleParser.ReadInterval(
                    Instant.parse(value.path("liveStartedAt").asText()),
                    Instant.parse(value.path("liveFinishedAt").asText()));
            result.put(fixtureId, new FixtureVerification.FixtureEvidence(value.path("complete").asBoolean(),
                    positive, appearances, liveRead));
        });
        return Map.copyOf(result);
    }

    private static Set<Integer> parseIds(JsonNode node) {
        if (!node.isArray()) throw new IllegalArgumentException("Invalid fixture participant IDs");
        Set<Integer> result = new TreeSet<>();
        node.forEach(value -> {
            if (!value.canConvertToInt() || value.intValue() <= 0 || !result.add(value.intValue())) {
                throw new IllegalArgumentException("Invalid fixture participant ID");
            }
        });
        return result;
    }

    private static Map<Integer, Set<Integer>> parsePopulation(JsonNode node) {
        if (!node.isObject()) throw new IllegalArgumentException("Invalid fixture player manifest");
        Map<Integer, Set<Integer>> result = new java.util.TreeMap<>();
        node.fields().forEachRemaining(entry -> {
            int fixtureId = Integer.parseInt(entry.getKey());
            JsonNode values = entry.getValue();
            if (fixtureId <= 0 || !values.isArray()) throw new IllegalArgumentException("Invalid fixture player manifest");
            Set<Integer> players = new TreeSet<>();
            values.forEach(value -> {
                if (!value.canConvertToInt() || value.intValue() <= 0 || !players.add(value.intValue())) {
                    throw new IllegalArgumentException("Invalid fixture player manifest");
                }
            });
            result.put(fixtureId, players);
        });
        return result;
    }

    private StoredGameweek lockGameweek(Season season, int gameweek) {
        List<StoredGameweek> rows = jdbc.query("""
                SELECT state,event_finished,data_checked,fixture_manifest_complete,all_assigned_fixtures_finished,
                       source_signature,first_checked_at,current_invalidation_revision,current_invalidation_floor,
                       final_attempt_source_signature,final_attempt_source_floor,final_attempt_invalidation_revision,
                       final_attempt_population_fingerprint,final_attempt_population_manifest::text AS population_manifest,
                       final_attempt_source_read_started_at,final_attempt_initial_source_read_finished_at,
                       final_attempt_source_read_finished_at,
                       final_attempt_required_player_ids,final_attempt_completed_player_ids,
                       final_attempt_outstanding_player_ids,final_attempt_page_read_started_at,
                       final_attempt_page_read_finished_at,finalized_population_fingerprint,
                       finalized_row_revision_fingerprint,finalized_invalidation_revision,
                       finalized_source_read_started_at,
                       finalized_source_read_finished_at,finalized_run_generation,finalized_at,
                       finalized_optional_component_coverage::text AS coverage
                FROM %s WHERE season_id=? AND gameweek=? FOR UPDATE
                """.formatted(tables.gameweekStatSync()), (rs, n) -> {
            String attemptSignature = rs.getString("final_attempt_source_signature");
            SweepCheckpoint checkpoint = attemptSignature == null ? null : new SweepCheckpoint(attemptSignature,
                    rs.getString("final_attempt_population_fingerprint"), instant(rs, "final_attempt_source_floor"),
                    rs.getLong("final_attempt_invalidation_revision"), ids(rs.getArray("final_attempt_required_player_ids")),
                    ids(rs.getArray("final_attempt_completed_player_ids")),
                    ids(rs.getArray("final_attempt_outstanding_player_ids")));
            return new StoredGameweek(State.valueOf(rs.getString("state")), rs.getObject("event_finished", Boolean.class),
                    rs.getObject("data_checked", Boolean.class), rs.getBoolean("fixture_manifest_complete"),
                    rs.getBoolean("all_assigned_fixtures_finished"), rs.getString("source_signature"),
                    instant(rs, "first_checked_at"), rs.getLong("current_invalidation_revision"),
                    instant(rs, "current_invalidation_floor"), checkpoint,
                    populationManifest(rs.getString("population_manifest")),
                    sourceRead(rs, "final_attempt_source_read_started_at", "final_attempt_source_read_finished_at"),
                    sourceRead(rs, "final_attempt_source_read_started_at",
                            "final_attempt_initial_source_read_finished_at"),
                    instant(rs, "final_attempt_page_read_started_at"),
                    instant(rs, "final_attempt_page_read_finished_at"),
                    rs.getString("finalized_population_fingerprint"), rs.getString("finalized_row_revision_fingerprint"),
                    (Long) rs.getObject("finalized_invalidation_revision"),
                    sourceRead(rs, "finalized_source_read_started_at", "finalized_source_read_finished_at"),
                    (Long) rs.getObject("finalized_run_generation"), instant(rs, "finalized_at"), rs.getString("coverage"));
        }, season.id(), gameweek);
        if (rows.isEmpty()) throw new IllegalStateException("Gameweek source state has not been observed");
        return rows.getFirst();
    }

    private Progress progress(StoredGameweek row) {
        SourceRead interval = row.pageReadStartedAt() == null ? null
                : new SourceRead(row.pageReadStartedAt(), row.pageReadFinishedAt());
        return new Progress(row.state(), row.sourceSignature(), row.invalidationRevision(), row.invalidationFloor(),
                row.firstCheckedAt(), row.checkpoint(), interval, row.finalizedPopulationFingerprint(),
                row.finalizedRowFingerprint(), row.finalizedRunGeneration(), row.finalizedAt(), row.coverageJson(),
                row.attemptSourceReadInterval(), row.finalizedSourceReadInterval(), row.initialSourceReadInterval());
    }

    private void requireEligible(StoredGameweek row, SourceSnapshot source) {
        if (!source.finalSweepEligible() || row.sourceSignature() == null
                || !row.sourceSignature().equals(source.signature())
                || !Boolean.TRUE.equals(row.eventFinished()) || !Boolean.TRUE.equals(row.dataChecked())
                || !row.fixtureManifestComplete() || !row.allAssignedFixturesFinished() || row.state() == State.OPEN) {
            throw new IllegalStateException("Gameweek is not the currently checked and finished source revision");
        }
    }

    private static void requireFinalPending(StoredGameweek row) {
        if (row.state() != State.FINAL_PENDING || !Boolean.TRUE.equals(row.eventFinished())
                || !Boolean.TRUE.equals(row.dataChecked()) || !row.fixtureManifestComplete()
                || !row.allAssignedFixturesFinished()) {
            throw new IllegalStateException("Gameweek has no current checked final-sweep attempt");
        }
    }

    private SweepCheckpoint requireCheckpoint(StoredGameweek row) {
        if (row.checkpoint() == null || row.invalidationFloor() == null
                || row.populationManifest() == null || row.attemptSourceReadInterval() == null
                || row.initialSourceReadInterval() == null) {
            throw new IllegalStateException("No durable final-sweep checkpoint exists");
        }
        return row.checkpoint();
    }

    private void persistAttempt(Season season, int gameweek, String sourceSignature, long revision,
                                String populationFingerprint, PopulationManifest population,
                                Instant floor, Set<Integer> required,
                                Set<Integer> completed, Set<Integer> outstanding, SourceInterval pageInterval,
                                SourceRead initialSourceRead, SourceRead sourceReadInterval) {
        jdbc.update("""
                UPDATE %s SET state='FINAL_PENDING', final_attempt_source_signature=?,
                    final_attempt_source_floor=?, final_attempt_invalidation_revision=?,
                    final_attempt_population_fingerprint=?, final_attempt_population_manifest=?::jsonb,
                    final_attempt_source_read_started_at=?, final_attempt_initial_source_read_finished_at=?,
                    final_attempt_source_read_finished_at=?,
                    final_attempt_required_player_ids=?::integer[],
                    final_attempt_completed_player_ids=?::integer[], final_attempt_outstanding_player_ids=?::integer[],
                    final_attempt_page_read_started_at=?, final_attempt_page_read_finished_at=?, updated_at=?
                WHERE season_id=? AND gameweek=?
                """.formatted(tables.gameweekStatSync()), sourceSignature, timestamp(floor), revision,
                populationFingerprint, populationManifestJson(population),
                timestamp(sourceReadInterval.startedAt()), timestamp(initialSourceRead.finishedAt()),
                timestamp(sourceReadInterval.finishedAt()),
                arrayLiteral(required), arrayLiteral(completed), arrayLiteral(outstanding),
                pageInterval == null ? null : timestamp(pageInterval.startedAt()),
                pageInterval == null ? null : timestamp(pageInterval.finishedAt()), timestamp(clock.instant()),
                season.id(), gameweek);
    }

    private void invalidatePopulationRevision(Season season, int gameweek, StoredGameweek row, Instant detectedAt) {
        Instant floor = max(row.invalidationFloor(), OwnedSyncStore.postgresTimestampPrecision(detectedAt));
        long revision = Math.incrementExact(row.invalidationRevision());
        jdbc.update("""
                UPDATE %s SET state='FINAL_PENDING', current_invalidation_revision=?, current_invalidation_floor=?,
                    finalized_source_signature=NULL, finalized_population_fingerprint=NULL,
                    finalized_row_revision_fingerprint=NULL, finalized_source_floor=NULL,
                    finalized_validation_read_started_at=NULL, finalized_validation_read_finished_at=NULL,
                    finalized_source_read_started_at=NULL, finalized_source_read_finished_at=NULL,
                    finalized_invalidation_revision=NULL, finalized_run_generation=NULL,
                    finalized_page_evidence=NULL, finalized_at=NULL,
                    finalized_mandatory_manifest_complete=NULL, finalized_optional_component_coverage=NULL,
                    updated_at=? WHERE season_id=? AND gameweek=?
                """.formatted(tables.gameweekStatSync()), revision, timestamp(floor), timestamp(clock.instant()),
                season.id(), gameweek);
    }

    private Map<Integer, PageEvidence> freshPageReceipts(Season season, Set<Integer> playerIds,
                                                         Instant floor, long currentGeneration) {
        if (playerIds.isEmpty()) return Map.of();
        List<String> keys = playerIds.stream().sorted()
                .map(IncrementalPlayerFixturePublisher::pageReceiptKey).toList();
        String placeholders = String.join(",", java.util.Collections.nCopies(keys.size(), "?"));
        List<Object> parameters = new ArrayList<>();
        parameters.add(currentGeneration);
        parameters.add(season.code());
        parameters.addAll(keys);
        parameters.add(timestamp(floor));
        parameters.add(timestamp(clock.instant()));
        Map<Integer, PageEvidence> result = new HashMap<>();
        jdbc.query("""
                SELECT receipt.publication_key,receipt.latest_generation,
                       receipt.source_read_started_at,receipt.source_read_finished_at
                FROM %s receipt
                JOIN %s page_run ON page_run.generation=receipt.latest_generation
                    AND page_run.season=receipt.season AND page_run.kind='PLAYER_HISTORY'
                    AND page_run.producer_key='INCREMENTAL_CORE' AND page_run.runtime_mode='ACTIVE'
                    AND page_run.runtime_epoch>0
                    AND page_run.coalescing_key='incremental-core-import'
                    AND page_run.publication_key='incremental-core-import' AND page_run.generation<=?
                WHERE receipt.season=? AND receipt.publication_key IN (%s)
                  AND receipt.latest_generation IS NOT NULL
                  AND receipt.source_read_started_at>=?
                  AND receipt.source_read_finished_at>=receipt.source_read_started_at
                  AND page_run.requested_source_after<=receipt.source_read_started_at
                  AND page_run.source_read_started_at IS NOT NULL
                  AND page_run.source_read_finished_at IS NOT NULL
                  AND page_run.source_read_started_at<=receipt.source_read_started_at
                  AND page_run.source_read_finished_at>=receipt.source_read_finished_at
                  AND page_run.source_read_finished_at<=?
                """.formatted(tables.receipt(), tables.run(), placeholders), rs -> {
            int playerId = Integer.parseInt(rs.getString("publication_key")
                    .substring(IncrementalPlayerFixturePublisher.PAGE_RECEIPT_PREFIX.length()));
            result.put(playerId, new PageEvidence(rs.getLong("latest_generation"),
                    instant(rs, "source_read_started_at"), instant(rs, "source_read_finished_at")));
        }, parameters.toArray());
        Instant now = clock.instant();
        result.entrySet().removeIf(entry -> entry.getValue().finishedAt().isAfter(now));
        return Map.copyOf(result);
    }

    private CurrentRows currentRows(Season season, SourceSnapshot source) {
        List<Integer> fixtureIds = source.assignedFixtures().stream().map(FixtureRow::id).sorted().toList();
        if (fixtureIds.isEmpty()) return new CurrentRows(GameweekFinality.rowRevisionFingerprint(source, List.of()),
                emptyCoverage(), Map.of(), true);
        String placeholders = String.join(",", java.util.Collections.nCopies(fixtureIds.size(), "?"));
        List<Object> lockParameters = new ArrayList<>(fixtureIds.size() + 1);
        lockParameters.add(season.id());
        lockParameters.addAll(fixtureIds);
        jdbc.query("SELECT fixture_id,player_id FROM player_fixture_stats WHERE season_id=? AND fixture_id IN ("
                + placeholders + ") ORDER BY fixture_id,player_id FOR UPDATE", rs -> { },
                lockParameters.toArray());
        String proof = IncrementalPfsEvidenceSql.currentRowProof("c", "p", "s", "e", tables.receipt());
        List<RowRevision> revisions = new ArrayList<>();
        Map<FixturePlayer, CurrentPfsRow> rows = new HashMap<>();
        Map<Component, long[]> counts = new EnumMap<>(Component.class);
        for (Component component : Component.values()) counts.put(component, new long[2]);
        boolean[] valid = {true};
        jdbc.query("""
                SELECT c.fixture_id,c.player_id,c.row_state,c.physical_fingerprint,
                       p.gameweek,p.player_team_id,p.opponent_team_id,p.was_home,p.minutes,
                       e.component_presence,(%s) AS row_proof
                FROM %s c
                LEFT JOIN player_fixture_stats p ON p.season_id=c.season_id
                    AND p.fixture_id=c.fixture_id AND p.player_id=c.player_id
                LEFT JOIN %s e ON e.season_id=c.season_id AND e.fixture_id=c.fixture_id
                    AND e.player_id=c.player_id AND e.generation=c.evidence_generation
                LEFT JOIN season s ON s.id=c.season_id
                WHERE c.season_id=? AND c.season_code=? AND c.fixture_id IN (%s)
                ORDER BY c.fixture_id,c.player_id FOR UPDATE OF c
                """.formatted(proof, tables.playerFixtureCurrent(), tables.playerFixtureEvidence(), placeholders),
                rs -> {
                    int fixtureId = rs.getInt("fixture_id");
                    int playerId = rs.getInt("player_id");
                    RowState state = RowState.valueOf(rs.getString("row_state"));
                    if (state == RowState.WITHDRAWN) {
                        revisions.add(new RowRevision(season.id(), season.code(), fixtureId, playerId,
                                RowState.WITHDRAWN, null));
                        return;
                    }
                    Integer gameweek = rs.getObject("gameweek", Integer.class);
                    String fingerprint = rs.getString("physical_fingerprint");
                    boolean rowProof = rs.getBoolean("row_proof")
                            && gameweek != null && gameweek == source.gameweek()
                            && fingerprint != null && fingerprint.matches("[0-9a-f]{64}");
                    if (!rowProof) {
                        valid[0] = false;
                        fingerprint = "0".repeat(64);
                    }
                    revisions.add(new RowRevision(season.id(), season.code(), fixtureId, playerId,
                            RowState.VALID, fingerprint));
                    if (rowProof) {
                        rows.put(new FixturePlayer(fixtureId, playerId), new CurrentPfsRow(
                                rs.getInt("minutes"), rs.getInt("player_team_id"),
                                rs.getInt("opponent_team_id"), rs.getObject("was_home", Boolean.class)));
                    }
                    long mask = rs.getLong("component_presence");
                    for (Component component : Component.values()) {
                        counts.get(component)[0]++;
                        if ((mask & component.bit()) != 0) counts.get(component)[1]++;
                    }
                }, queryParameters(season, fixtureIds).toArray());
        Map<Component, ComponentCoverage> coverage = new EnumMap<>(Component.class);
        for (Component component : Component.values()) {
            long[] count = counts.get(component);
            coverage.put(component, new ComponentCoverage(Math.toIntExact(count[0]),
                    Math.toIntExact(count[1]), true));
        }
        return new CurrentRows(GameweekFinality.rowRevisionFingerprint(source, revisions),
                Map.copyOf(coverage), Map.copyOf(rows), valid[0]);
    }

    static boolean validatesAppearanceRows(SourceSnapshot source, RequiredPopulation population,
                                            SweepCheckpoint checkpoint, CurrentRows current) {
        return checkpoint != null
                && population.hasCompleteAppearanceEvidence(source, checkpoint.sourceFloor())
                && currentPositiveRowsMatchAppearances(source, population, current)
                && pagesWithAppearanceRows(source, population, current,
                        checkpoint.requiredPlayerIds()).equals(checkpoint.requiredPlayerIds());
    }

    private static boolean currentPositiveRowsMatchAppearances(SourceSnapshot source,
                                                                 RequiredPopulation population,
                                                                 CurrentRows current) {
        Map<Integer, FixtureRow> fixtures = new HashMap<>();
        source.assignedFixtures().forEach(fixture -> fixtures.put(fixture.id(), fixture));
        for (Map.Entry<FixturePlayer, CurrentPfsRow> entry : current.rows().entrySet()) {
            if (entry.getValue().minutes() <= 0) continue;
            FixtureRow fixture = fixtures.get(entry.getKey().fixtureId());
            FixtureVerification.FixtureEvidence evidence = population.participantEvidenceByFixture()
                    .get(entry.getKey().fixtureId());
            if (fixture == null || evidence == null || !evidence.complete()
                    || !appearanceRowMatches(fixture, evidence, entry.getKey().playerId(), current)) return false;
        }
        return true;
    }

    private static Set<Integer> pagesWithAppearanceRows(SourceSnapshot source, RequiredPopulation population,
                                                        CurrentRows current, Set<Integer> completed) {
        Set<Integer> result = new TreeSet<>(completed);
        Map<Integer, FixtureRow> fixtures = new HashMap<>();
        source.assignedFixtures().forEach(fixture -> fixtures.put(fixture.id(), fixture));
        population.participantEvidenceByFixture().forEach((fixtureId, evidence) -> {
            if (!evidence.complete()) return;
            FixtureRow fixture = fixtures.get(fixtureId);
            if (fixture == null) return;
            evidence.positivePlayerIds().forEach(playerId -> {
                if (!appearanceRowMatches(fixture, evidence, playerId, current)) result.remove(playerId);
            });
        });
        return Set.copyOf(result);
    }

    private static boolean appearanceRowMatches(FixtureRow fixture, FixtureVerification.FixtureEvidence evidence,
                                                int playerId, CurrentRows current) {
        FixtureVerification.Appearance appearance = evidence.appearances().get(playerId);
        CurrentPfsRow row = current.rows().get(new FixturePlayer(fixture.id(), playerId));
        if (appearance == null || row == null) return false;
        boolean home = appearance.side() == FixtureVerification.Side.HOME;
        int expectedTeam = home ? fixture.homeTeamId() : fixture.awayTeamId();
        int expectedOpponent = home ? fixture.awayTeamId() : fixture.homeTeamId();
        return row.minutes() == appearance.minutes() && row.playerTeamId() == expectedTeam
                && row.opponentTeamId() == expectedOpponent && Objects.equals(row.wasHome(), home);
    }

    private static List<Object> queryParameters(Season season, List<Integer> fixtureIds) {
        List<Object> result = new ArrayList<>();
        result.add(season.id());
        result.add(season.code());
        result.addAll(fixtureIds);
        return result;
    }

    private static Map<Component, ComponentCoverage> emptyCoverage() {
        Map<Component, ComponentCoverage> result = new EnumMap<>(Component.class);
        for (Component component : Component.values()) result.put(component, new ComponentCoverage(0, 0, true));
        return Map.copyOf(result);
    }

    private static String pageEvidenceJson(Map<Integer, PageEvidence> pages) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<Integer, PageEvidence> entry : new java.util.TreeMap<>(pages).entrySet()) {
            if (!first) json.append(',');
            first = false;
            PageEvidence page = entry.getValue();
            json.append('\"').append(entry.getKey()).append("\":{\"generation\":")
                    .append(page.generation()).append(",\"startedAt\":\"").append(page.startedAt())
                    .append("\",\"finishedAt\":\"").append(page.finishedAt()).append("\"}");
        }
        return json.append('}').toString();
    }

    private static String coverageJson(Map<Component, ComponentCoverage> coverage) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Component component : Component.values()) {
            if (!first) json.append(',');
            first = false;
            ComponentCoverage item = coverage.get(component);
            json.append('"').append(component.name()).append("\":{\"sourceRows\":")
                    .append(item.sourceRows()).append(",\"presentRows\":").append(item.presentRows())
                    .append(",\"supported\":").append(item.supported()).append('}');
        }
        return json.append('}').toString();
    }

    private static Set<Integer> ids(Array array) throws java.sql.SQLException {
        if (array == null) return Set.of();
        Set<Integer> result = new TreeSet<>();
        for (Object item : (Object[]) array.getArray()) result.add(((Number) item).intValue());
        return Set.copyOf(result);
    }

    private static String arrayLiteral(Set<Integer> ids) {
        return "{" + ids.stream().sorted().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(",")) + "}";
    }

    private static Set<Integer> difference(Set<Integer> required, Set<Integer> completed) {
        Set<Integer> result = new TreeSet<>(required);
        result.removeAll(completed);
        return Set.copyOf(result);
    }

    private static SourceRead participantEvidenceInterval(RequiredPopulation population) {
        if (population.participantEvidenceByFixture().isEmpty()) return null;
        Instant started = population.participantEvidenceByFixture().values().stream()
                .map(evidence -> evidence.liveRead().startedAt()).min(Instant::compareTo).orElseThrow();
        Instant finished = population.participantEvidenceByFixture().values().stream()
                .map(evidence -> evidence.liveRead().completedAt()).max(Instant::compareTo).orElseThrow();
        return new SourceRead(started, finished);
    }

    private static SourceInterval interval(java.util.Collection<PageEvidence> pages) {
        if (pages.isEmpty()) return null;
        Instant start = pages.stream().map(PageEvidence::startedAt).min(Instant::compareTo).orElseThrow();
        Instant finish = pages.stream().map(PageEvidence::finishedAt).max(Instant::compareTo).orElseThrow();
        return new SourceInterval(start, finish);
    }

    private static SourceRead sourceInterval(SourceRead previous, SourceRead source,
                                             SourceInterval pages, SourceRead extra) {
        Instant started = null;
        Instant finished = null;
        for (SourceRead interval : new SourceRead[] { previous, source, extra }) {
            if (interval == null) continue;
            started = started == null || interval.startedAt().isBefore(started) ? interval.startedAt() : started;
            finished = max(finished, interval.finishedAt());
        }
        if (pages != null) {
            started = started == null || pages.startedAt().isBefore(started) ? pages.startedAt() : started;
            finished = max(finished, pages.finishedAt());
        }
        if (started == null || finished == null) throw new IllegalArgumentException("A source interval is required");
        return new SourceRead(started, finished);
    }

    private static SourceRead sourceRead(java.sql.ResultSet rs, String started, String finished)
            throws java.sql.SQLException {
        Instant start = instant(rs, started);
        Instant end = instant(rs, finished);
        return start == null || end == null ? null : new SourceRead(start, end);
    }

    private static Instant freshFloor(Instant sourceFinished, Instant invalidationFloor) {
        return OwnedSyncStore.postgresTimestampPrecision(max(sourceFinished, invalidationFloor)).plusMillis(1);
    }

    private static Instant max(Instant first, Instant second) {
        if (first == null) return second;
        return second == null || first.isAfter(second) ? first : second;
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(OwnedSyncStore.postgresTimestampPrecision(value));
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Map<Integer, Set<Integer>> immutable(Map<Integer, Set<Integer>> source) {
        Map<Integer, Set<Integer>> result = new HashMap<>();
        source.forEach((fixture, players) -> result.put(fixture, Set.copyOf(players)));
        return Map.copyOf(result);
    }

    private record StoredGameweek(State state, Boolean eventFinished, Boolean dataChecked,
                                  boolean fixtureManifestComplete, boolean allAssignedFixturesFinished,
                                  String sourceSignature, Instant firstCheckedAt, long invalidationRevision,
                                  Instant invalidationFloor, SweepCheckpoint checkpoint,
                                  PopulationManifest populationManifest, SourceRead attemptSourceReadInterval,
                                  SourceRead initialSourceReadInterval, Instant pageReadStartedAt,
                                  Instant pageReadFinishedAt,
                                  String finalizedPopulationFingerprint, String finalizedRowFingerprint,
                                  Long finalizedInvalidationRevision, SourceRead finalizedSourceReadInterval,
                                  Long finalizedRunGeneration,
                                  Instant finalizedAt, String coverageJson) { }

    record PopulationManifest(RequiredPopulation population, Map<Integer, String> fixtureIdentities) {
        PopulationManifest {
            Objects.requireNonNull(population);
            fixtureIdentities = Map.copyOf(fixtureIdentities);
        }
    }

    record FixturePlayer(int fixtureId, int playerId) { }
    private record ParticipantManifest(Map<Integer, Set<Integer>> playerIdsByFixture) { }
    private record PageEvidence(long generation, Instant startedAt, Instant finishedAt) { }
    private record SourceInterval(Instant startedAt, Instant finishedAt) { }
    record CurrentRows(String fingerprint, Map<Component, ComponentCoverage> coverage,
                       Map<FixturePlayer, CurrentPfsRow> rows, boolean valid) { }
    record CurrentPfsRow(int minutes, int playerTeamId, int opponentTeamId, Boolean wasHome) { }

    public record Progress(State state, String sourceSignature, long invalidationRevision,
                           Instant invalidationFloor, Instant firstCheckedAt, SweepCheckpoint checkpoint,
                           SourceRead pageReadInterval, String finalizedPopulationFingerprint,
                           String finalizedRowRevisionFingerprint, Long finalizedRunGeneration,
                           Instant finalizedAt, String optionalCoverageJson,
                           SourceRead sourceReadInterval, SourceRead finalizedSourceReadInterval,
                           SourceRead initialSourceReadInterval) { }
}
