package dev.quantti.fplassistant.sync;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import dev.quantti.fplassistant.sync.CatalogueScheduleParser.FixtureRow;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.GameweekRow;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.PlayerRow;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.Snapshot;

/** Immutable source/finality contract for the incremental player-fixture path. */
public final class GameweekFinality {

    public static final int MAX_CHECKPOINT_PLAYER_IDS = 2_000;

    private GameweekFinality() {
    }

    public enum State {
        OPEN,
        FINAL_PENDING,
        FINALIZED
    }

    /** FPL event flags are separate observations: finished alone is not data-checked. */
    public enum EventProgress {
        UNKNOWN,
        OPEN,
        FINISHED,
        CHECKED
    }

    public enum FixtureProgress {
        OPEN,
        PROVISIONAL,
        FINISHED
    }

    public enum RowState {
        VALID,
        WITHDRAWN
    }

    public enum OptionalCoverageState {
        COMPLETE,
        PARTIAL,
        UNAVAILABLE,
        NOT_SUPPORTED,
        NO_ROWS
    }

    /** Monotonic fence advanced whenever checked-source or current-row evidence is invalidated. */
    public record CurrentInvalidation(long revision, Instant floor) {
        public CurrentInvalidation {
            if (revision < 0) throw new IllegalArgumentException("Invalid invalidation revision");
            Objects.requireNonNull(floor, "invalidation floor");
        }

        public CurrentInvalidation advance(Instant detectedAt) {
            Objects.requireNonNull(detectedAt, "detectedAt");
            return new CurrentInvalidation(Math.incrementExact(revision),
                    detectedAt.isAfter(floor) ? detectedAt : floor);
        }
    }

    public static EventProgress eventProgress(Boolean finished, Boolean dataChecked) {
        if (finished == null || dataChecked == null) return EventProgress.UNKNOWN;
        if (!finished) return EventProgress.OPEN;
        return dataChecked ? EventProgress.CHECKED : EventProgress.FINISHED;
    }

    /** A provisional fixture flag is never a substitute for {@code finished}. */
    public static FixtureProgress fixtureProgress(FixtureRow fixture) {
        Objects.requireNonNull(fixture, "fixture");
        Object provisional = fixture.fields().get("finished_provisional");
        if (!(provisional instanceof Boolean finishedProvisional)) {
            throw new IllegalArgumentException("Fixture has no validated finished_provisional flag");
        }
        if (fixture.finished()) return FixtureProgress.FINISHED;
        return finishedProvisional ? FixtureProgress.PROVISIONAL : FixtureProgress.OPEN;
    }

    /**
     * One validated source view for an event. An empty assigned-fixture list is meaningful only
     * when {@code fixtureManifestComplete} is true; that represents a genuinely blank gameweek.
     */
    public record SourceSnapshot(int seasonId, String seasonCode, int gameweek,
                                 Boolean eventFinished, Boolean dataChecked,
                                 boolean fixtureManifestComplete, List<FixtureRow> assignedFixtures) {
        public SourceSnapshot {
            if (seasonId <= 0 || seasonCode == null || seasonCode.isBlank()
                    || gameweek <= 0 || gameweek > Short.MAX_VALUE) {
                throw new IllegalArgumentException("Invalid gameweek source identity");
            }
            assignedFixtures = List.copyOf(assignedFixtures);
            Set<Integer> fixtureIds = new HashSet<>();
            for (FixtureRow fixture : assignedFixtures) {
                Objects.requireNonNull(fixture, "assigned fixture");
                if (fixture.seasonId() != seasonId || !seasonCode.equals(fixture.seasonCode())
                        || !Objects.equals(fixture.gameweek(), gameweek)
                        || !fixtureIds.add(fixture.id())) {
                    throw new IllegalArgumentException("Assigned fixtures must be unique and belong to this event");
                }
            }
        }

        /** Builds membership from a parser-validated full season snapshot, including a valid empty GW. */
        public static SourceSnapshot from(Snapshot schedule, int gameweek) {
            Objects.requireNonNull(schedule, "schedule");
            GameweekRow event = schedule.gameweeks().stream().filter(row -> row.id() == gameweek)
                    .findFirst().orElseThrow(() -> new IllegalArgumentException(
                            "Gameweek is absent from the validated source snapshot"));
            return from(event, schedule.fixtures(), true);
        }

        /** Builds membership from the full parsed season schedule, including a valid empty GW. */
        public static SourceSnapshot from(GameweekRow event, List<FixtureRow> seasonFixtures,
                                          boolean fixtureManifestComplete) {
            Objects.requireNonNull(event, "event");
            List<FixtureRow> assigned = new ArrayList<>();
            for (FixtureRow fixture : seasonFixtures) {
                if (fixture.seasonId() != event.seasonId()
                        || !fixture.seasonCode().equals(event.seasonCode())) {
                    throw new IllegalArgumentException("Fixture schedule belongs to another season");
                }
                if (Objects.equals(fixture.gameweek(), event.id())) assigned.add(fixture);
            }
            return new SourceSnapshot(event.seasonId(), event.seasonCode(), event.id(), event.finished(),
                    event.dataChecked(), fixtureManifestComplete, assigned);
        }

        public EventProgress eventProgress() {
            return GameweekFinality.eventProgress(eventFinished, dataChecked);
        }

        public boolean allAssignedFixturesFinished() {
            return fixtureManifestComplete && assignedFixtures.stream().allMatch(FixtureRow::finished);
        }

        public boolean finalSweepEligible() {
            return fixtureManifestComplete && eventProgress() == EventProgress.CHECKED
                    && allAssignedFixturesFinished();
        }

        /** Semantic revision: event flags and the complete sorted assigned-fixture population. */
        public String signature() {
            StringBuilder canonical = new StringBuilder();
            append(canonical, seasonId);
            append(canonical, seasonCode);
            append(canonical, gameweek);
            append(canonical, eventFinished);
            append(canonical, dataChecked);
            assignedFixtures.stream().sorted(java.util.Comparator.comparingInt(FixtureRow::id))
                    .forEach(fixture -> {
                        append(canonical, fixture.id());
                        append(canonical, FixtureVerification.verifiedHeader(fixture));
                        append(canonical, FixtureStatsFingerprint.of(fixture));
                    });
            return OwnedSyncStore.sha256(canonical.toString());
        }

        public boolean canResume(SweepCheckpoint checkpoint, CurrentInvalidation invalidation,
                                 String populationFingerprint) {
            Objects.requireNonNull(invalidation, "invalidation");
            return finalSweepEligible() && checkpoint != null
                    && signature().equals(checkpoint.sourceSignature())
                    && Objects.equals(populationFingerprint, checkpoint.populationFingerprint())
                    && checkpoint.invalidationRevision() == invalidation.revision()
                    && !checkpoint.sourceFloor().isBefore(invalidation.floor());
        }
    }

    public static String catalogueFingerprint(Snapshot snapshot, List<FixtureRow> fixtures) {
        Objects.requireNonNull(snapshot);
        return catalogueFingerprint(snapshot.players(), fixtures);
    }

    public static String catalogueFingerprint(List<PlayerRow> players, List<FixtureRow> fixtures) {
        Objects.requireNonNull(players);
        Set<Integer> teams = new HashSet<>();
        fixtures.forEach(fixture -> {
            teams.add(fixture.homeTeamId());
            teams.add(fixture.awayTeamId());
        });
        String canonical = players.stream().filter(player -> teams.contains(player.teamId()))
                .sorted(java.util.Comparator.comparingInt(PlayerRow::id))
                .map(player -> player.id() + ":" + player.teamId())
                .collect(java.util.stream.Collectors.joining("|"));
        return OwnedSyncStore.sha256(canonical);
    }

    /** Exact source-derived page population; numeric stats remain in PFS, not in this manifest. */
    public record RequiredPopulation(String bootstrapFingerprint,
                                     Map<Integer, Set<Integer>> cataloguePlayerIdsByFixture,
                                     Map<Integer, Set<Integer>> participantPlayerIdsByFixture,
                                     Map<Integer, Set<Integer>> knownPlayerIdsByFixture,
                                     Map<Integer, FixtureVerification.FixtureEvidence> participantEvidenceByFixture) {
        public RequiredPopulation(String bootstrapFingerprint,
                                  Map<Integer, Set<Integer>> cataloguePlayerIdsByFixture,
                                  Map<Integer, Set<Integer>> participantPlayerIdsByFixture,
                                  Map<Integer, Set<Integer>> knownPlayerIdsByFixture) {
            this(bootstrapFingerprint, cataloguePlayerIdsByFixture, participantPlayerIdsByFixture,
                    knownPlayerIdsByFixture, Map.of());
        }

        public RequiredPopulation {
            requireFingerprint(bootstrapFingerprint, "bootstrap fingerprint");
            cataloguePlayerIdsByFixture = immutableFixturePlayerIds(cataloguePlayerIdsByFixture);
            participantPlayerIdsByFixture = immutableFixturePlayerIds(participantPlayerIdsByFixture);
            knownPlayerIdsByFixture = immutableFixturePlayerIds(knownPlayerIdsByFixture);
            Objects.requireNonNull(participantEvidenceByFixture, "participant evidence");
            participantEvidenceByFixture = Map.copyOf(participantEvidenceByFixture);
            if (participantEvidenceByFixture.values().stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("Participant evidence cannot contain null values");
            }
            Set<Integer> ids = new HashSet<>();
            cataloguePlayerIdsByFixture.values().forEach(ids::addAll);
            participantPlayerIdsByFixture.values().forEach(ids::addAll);
            knownPlayerIdsByFixture.values().forEach(ids::addAll);
            if (ids.size() > MAX_CHECKPOINT_PLAYER_IDS) {
                throw new IllegalArgumentException("Required page population exceeds its bound");
            }
        }

        public Set<Integer> requiredPlayerIds() {
            Set<Integer> result = new TreeSet<>();
            requiredPlayerIdsByFixture().values().forEach(result::addAll);
            return Collections.unmodifiableSet(result);
        }

        public Map<Integer, Set<Integer>> requiredPlayerIdsByFixture() {
            Map<Integer, Set<Integer>> result = new java.util.TreeMap<>();
            mergePopulation(result, cataloguePlayerIdsByFixture);
            mergePopulation(result, participantPlayerIdsByFixture);
            mergePopulation(result, knownPlayerIdsByFixture);
            Map<Integer, Set<Integer>> immutable = new java.util.TreeMap<>();
            result.forEach((fixture, ids) -> immutable.put(fixture,
                    Collections.unmodifiableSet(new TreeSet<>(ids))));
            return Collections.unmodifiableMap(immutable);
        }

        public RequiredPopulation withAdditionalPopulation(Map<Integer, Set<Integer>> catalogue,
                                                           Map<Integer, Set<Integer>> participants,
                                                           Map<Integer, Set<Integer>> known) {
            return new RequiredPopulation(bootstrapFingerprint,
                    union(cataloguePlayerIdsByFixture, catalogue),
                    union(participantPlayerIdsByFixture, participants),
                    union(knownPlayerIdsByFixture, known), participantEvidenceByFixture);
        }

        private static void mergePopulation(Map<Integer, Set<Integer>> target,
                                            Map<Integer, Set<Integer>> source) {
            source.forEach((fixture, ids) -> target.computeIfAbsent(fixture, ignored -> new TreeSet<>())
                    .addAll(ids));
        }

        private static Map<Integer, Set<Integer>> union(Map<Integer, Set<Integer>> first,
                                                        Map<Integer, Set<Integer>> second) {
            Map<Integer, Set<Integer>> result = new java.util.TreeMap<>();
            mergePopulation(result, first);
            mergePopulation(result, second);
            return result;
        }

        public void validateFor(SourceSnapshot source) {
            Objects.requireNonNull(source, "source");
            Set<Integer> fixtureIds = source.assignedFixtures().stream().map(FixtureRow::id)
                    .collect(java.util.stream.Collectors.toSet());
            for (Map<Integer, Set<Integer>> category : List.of(cataloguePlayerIdsByFixture,
                    participantPlayerIdsByFixture, knownPlayerIdsByFixture)) {
                if (!fixtureIds.containsAll(category.keySet())) {
                    throw new IllegalArgumentException("Required population contains a fixture outside the source");
                }
            }
            if (!fixtureIds.containsAll(participantEvidenceByFixture.keySet())) {
                throw new IllegalArgumentException("Participant evidence contains a fixture outside the source");
            }
            Map<Integer, Set<Integer>> requiredByFixture = requiredPlayerIdsByFixture();
            participantEvidenceByFixture.forEach((fixtureId, evidence) -> {
                if (!requiredByFixture.getOrDefault(fixtureId, Set.of()).containsAll(evidence.positivePlayerIds())) {
                    throw new IllegalArgumentException("Positive appearances must be required history pages");
                }
            });
        }

        public String fingerprint(SourceSnapshot source) {
            validateFor(source);
            StringBuilder canonical = new StringBuilder();
            append(canonical, source.signature());
            append(canonical, bootstrapFingerprint);
            appendPopulation(canonical, "catalogue", cataloguePlayerIdsByFixture);
            appendPopulation(canonical, "participant", participantPlayerIdsByFixture);
            appendPopulation(canonical, "known", knownPlayerIdsByFixture);
            return OwnedSyncStore.sha256(canonical.toString());
        }

        public String finalizedFingerprint(SourceSnapshot source) {
            return OwnedSyncStore.sha256(fingerprint(source) + "|" + evidenceFingerprint(source));
        }

        public String evidenceFingerprint(SourceSnapshot source) {
            validateFor(source);
            StringBuilder canonical = new StringBuilder();
            participantEvidenceByFixture.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                append(canonical, entry.getKey());
                FixtureVerification.FixtureEvidence evidence = entry.getValue();
                append(canonical, evidence.complete());
                append(canonical, evidence.liveRead().startedAt());
                append(canonical, evidence.liveRead().completedAt());
                evidence.positivePlayerIds().stream().sorted().forEach(player -> {
                    append(canonical, player);
                    FixtureVerification.Appearance appearance = evidence.appearances().get(player);
                    append(canonical, appearance == null ? null : appearance.side());
                    append(canonical, appearance == null ? null : appearance.minutes());
                });
            });
            return OwnedSyncStore.sha256(canonical.toString());
        }

        public boolean hasCompleteAppearanceEvidence(SourceSnapshot source, Instant sourceFloor) {
            validateFor(source);
            for (FixtureRow fixture : source.assignedFixtures()) {
                FixtureVerification.FixtureEvidence evidence = participantEvidenceByFixture.get(fixture.id());
                if (evidence == null || !evidence.complete()
                        || evidence.liveRead().startedAt().isBefore(sourceFloor)
                        || evidence.appearances().keySet().size() != evidence.positivePlayerIds().size()
                        || evidence.appearances().values().stream().noneMatch(row ->
                                row.side() == FixtureVerification.Side.HOME)
                        || evidence.appearances().values().stream().noneMatch(row ->
                                row.side() == FixtureVerification.Side.AWAY)) return false;
            }
            return true;
        }

        private static Map<Integer, Set<Integer>> immutableFixturePlayerIds(Map<Integer, Set<Integer>> source) {
            Objects.requireNonNull(source, "fixture player ids");
            Map<Integer, Set<Integer>> copy = new java.util.TreeMap<>();
            source.forEach((fixtureId, playerIds) -> {
                if (fixtureId == null || fixtureId <= 0 || playerIds == null
                        || playerIds.stream().anyMatch(id -> id == null || id <= 0)) {
                    throw new IllegalArgumentException("Invalid fixture player population");
                }
                copy.put(fixtureId, Collections.unmodifiableSet(new TreeSet<>(playerIds)));
            });
            return Collections.unmodifiableMap(copy);
        }

        private static void appendPopulation(StringBuilder canonical, String label,
                                             Map<Integer, Set<Integer>> population) {
            append(canonical, label);
            append(canonical, population.size());
            population.forEach((fixtureId, playerIds) -> {
                append(canonical, "fixture");
                append(canonical, fixtureId);
                append(canonical, playerIds.size());
                playerIds.forEach(playerId -> append(canonical, playerId));
                append(canonical, "end-fixture");
            });
            append(canonical, "end-population");
        }
    }

    /** One current row pointer used to derive the gameweek's covered row revision. */
    public record RowRevision(int seasonId, String seasonCode, int fixtureId, int playerId,
                              RowState state, String fingerprint) {
        public RowRevision {
            if (seasonId <= 0 || seasonCode == null || seasonCode.isBlank()
                    || fixtureId <= 0 || playerId <= 0) {
                throw new IllegalArgumentException("Invalid current row identity");
            }
            Objects.requireNonNull(state, "state");
            if (state == RowState.VALID) requireFingerprint(fingerprint, "current row fingerprint");
            else if (fingerprint != null) {
                throw new IllegalArgumentException("Withdrawn rows cannot carry a current fingerprint");
            }
        }
    }

    /** Fingerprint the current row revisions for this event without copying metric values. */
    public static String rowRevisionFingerprint(SourceSnapshot source, List<RowRevision> rows) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(rows, "rows");
        Set<Integer> fixtureIds = source.assignedFixtures().stream()
                .map(FixtureRow::id).collect(java.util.stream.Collectors.toSet());
        List<RowRevision> covered = rows.stream().filter(row -> fixtureIds.contains(row.fixtureId()))
                .sorted(java.util.Comparator.comparingInt(RowRevision::fixtureId)
                        .thenComparingInt(RowRevision::playerId)).toList();
        StringBuilder canonical = new StringBuilder();
        Set<String> keys = new HashSet<>();
        for (RowRevision row : covered) {
            if (row.seasonId() != source.seasonId() || !row.seasonCode().equals(source.seasonCode())
                    || !keys.add(row.fixtureId() + ":" + row.playerId())) {
                throw new IllegalArgumentException("Current row revisions must be unique and belong to this season");
            }
            append(canonical, row.fixtureId());
            append(canonical, row.playerId());
            append(canonical, row.state());
            append(canonical, row.fingerprint());
        }
        return OwnedSyncStore.sha256(canonical.toString());
    }

    /**
     * A successful terminal receipt. It certifies mandatory rows and their exact normalized
     * revision for this source snapshot, while recording optional coverage rather than permanence.
     */
    public record FinalReceipt(String sourceSignature, String rowRevisionFingerprint,
                               long runGeneration, Instant sourceFloor, long invalidationRevision,
                               Instant completedAt, OwnedSyncStore.SourceRead sourceReadInterval,
                               boolean mandatoryManifestComplete,
                               Map<HistoryParser.Component, ComponentCoverage> optionalCoverage) {
        public FinalReceipt {
            requireFingerprint(sourceSignature, "source signature");
            requireFingerprint(rowRevisionFingerprint, "covered row revision fingerprint");
            if (runGeneration <= 0) throw new IllegalArgumentException("Invalid final run generation");
            Objects.requireNonNull(sourceFloor, "sourceFloor");
            if (invalidationRevision < 0) throw new IllegalArgumentException("Invalid receipt invalidation revision");
            Objects.requireNonNull(completedAt, "completedAt");
            Objects.requireNonNull(sourceReadInterval, "sourceReadInterval");
            if (completedAt.isBefore(sourceFloor)) {
                throw new IllegalArgumentException("Final completion precedes its source floor");
            }
            if (sourceReadInterval.finishedAt().isAfter(completedAt)) {
                throw new IllegalArgumentException("Final source read interval exceeds completion");
            }
            if (!mandatoryManifestComplete) {
                throw new IllegalArgumentException("An incomplete mandatory manifest cannot be finalized");
            }
            Objects.requireNonNull(optionalCoverage, "optionalCoverage");
            EnumMap<HistoryParser.Component, ComponentCoverage> copy =
                    new EnumMap<>(HistoryParser.Component.class);
            copy.putAll(optionalCoverage);
            if (!copy.keySet().equals(EnumSet.allOf(HistoryParser.Component.class))) {
                throw new IllegalArgumentException("Final receipt must state coverage for every optional component");
            }
            if (copy.values().stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("Final receipt coverage cannot contain null values");
            }
            optionalCoverage = Collections.unmodifiableMap(copy);
        }

        public boolean covers(String currentSourceSignature, String currentRowRevisionFingerprint,
                              CurrentInvalidation invalidation) {
            return invalidationRevision == invalidation.revision()
                    && !sourceFloor.isBefore(invalidation.floor())
                    && sourceSignature.equals(currentSourceSignature)
                    && rowRevisionFingerprint.equals(currentRowRevisionFingerprint);
        }
    }

    /** Coverage counts describe presence only; they contain no copied metric values. */
    public record ComponentCoverage(int sourceRows, int presentRows, boolean supported) {
        public ComponentCoverage {
            if (sourceRows < 0 || presentRows < 0 || presentRows > sourceRows
                    || !supported && presentRows != 0) {
                throw new IllegalArgumentException("Invalid optional-component coverage");
            }
        }

        public OptionalCoverageState state() {
            if (!supported) return OptionalCoverageState.NOT_SUPPORTED;
            if (sourceRows == 0) return OptionalCoverageState.NO_ROWS;
            if (presentRows == 0) return OptionalCoverageState.UNAVAILABLE;
            return presentRows == sourceRows ? OptionalCoverageState.COMPLETE : OptionalCoverageState.PARTIAL;
        }
    }

    /**
     * Per-event resume checkpoint. Completed and outstanding element-summary pages partition the
     * required IDs, and the checkpoint is reusable only against its exact eligible source revision.
     */
    public record SweepCheckpoint(String sourceSignature, String populationFingerprint, Instant sourceFloor,
                                  long invalidationRevision, Set<Integer> requiredPlayerIds,
                                  Set<Integer> completedPlayerIds, Set<Integer> outstandingPlayerIds) {
        public SweepCheckpoint {
            requireFingerprint(sourceSignature, "checkpoint source signature");
            requireFingerprint(populationFingerprint, "checkpoint population fingerprint");
            Objects.requireNonNull(sourceFloor, "sourceFloor");
            if (invalidationRevision < 0) throw new IllegalArgumentException("Invalid checkpoint invalidation revision");
            requiredPlayerIds = immutablePlayerIds(requiredPlayerIds);
            completedPlayerIds = immutablePlayerIds(completedPlayerIds);
            outstandingPlayerIds = immutablePlayerIds(outstandingPlayerIds);
            Set<Integer> accounted = new TreeSet<>(completedPlayerIds);
            if (!Collections.disjoint(completedPlayerIds, outstandingPlayerIds)) {
                throw new IllegalArgumentException("Checkpoint player IDs cannot be both completed and outstanding");
            }
            accounted.addAll(outstandingPlayerIds);
            if (!accounted.equals(requiredPlayerIds)) {
                throw new IllegalArgumentException("Completed and outstanding IDs must partition the required manifest");
            }
        }
    }

    /**
     * Applies a validated observation to durable state. An incomplete/malformed source view is not
     * a regression and therefore preserves prior state; a valid source regression reopens the GW.
     */
    public static State observe(SourceSnapshot source, String currentRowRevisionFingerprint,
                                State previousState, FinalReceipt receipt,
                                CurrentInvalidation invalidation) {
        Objects.requireNonNull(invalidation, "invalidation");
        if (source == null || !source.fixtureManifestComplete()
                || source.eventProgress() == EventProgress.UNKNOWN) {
            return previousState == null ? State.OPEN : previousState;
        }
        if (!source.finalSweepEligible()) return State.OPEN;
        if (previousState == State.OPEN) return State.FINAL_PENDING;
        if (receipt != null && receipt.covers(source.signature(), currentRowRevisionFingerprint, invalidation)) {
            return State.FINALIZED;
        }
        return State.FINAL_PENDING;
    }

    private static Set<Integer> immutablePlayerIds(Set<Integer> source) {
        Objects.requireNonNull(source, "player IDs");
        if (source.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Checkpoint player IDs cannot contain null");
        }
        TreeSet<Integer> copy = new TreeSet<>(source);
        if (copy.stream().anyMatch(id -> id <= 0) || copy.size() > MAX_CHECKPOINT_PLAYER_IDS) {
            throw new IllegalArgumentException("Invalid or oversized checkpoint player IDs");
        }
        return Collections.unmodifiableSet(copy);
    }

    private static void requireFingerprint(String value, String name) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid " + name);
        }
    }

    private static void append(StringBuilder target, Object value) {
        if (value == null) {
            target.append("-1:");
        } else {
            String text = value.toString();
            target.append(text.length()).append(':').append(text);
        }
        target.append(';');
    }
}
