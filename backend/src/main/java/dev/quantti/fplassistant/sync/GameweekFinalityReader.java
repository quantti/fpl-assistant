package dev.quantti.fplassistant.sync;

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
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import dev.quantti.fplassistant.sync.CatalogueScheduleParser.FixtureRow;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.GameweekRow;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.PlayerRow;
import dev.quantti.fplassistant.sync.GameweekFinality.ComponentCoverage;
import dev.quantti.fplassistant.sync.GameweekFinality.FinalReceipt;
import dev.quantti.fplassistant.sync.GameweekFinality.RequiredPopulation;
import dev.quantti.fplassistant.sync.GameweekFinality.RowRevision;
import dev.quantti.fplassistant.sync.GameweekFinality.RowState;
import dev.quantti.fplassistant.sync.GameweekFinality.SourceSnapshot;
import dev.quantti.fplassistant.sync.GameweekFinality.State;
import dev.quantti.fplassistant.sync.GameweekFinality.SweepCheckpoint;
import dev.quantti.fplassistant.sync.OwnedSyncStore.SourceRead;

/** Read-only validation of current checked-gameweek source and row receipts. */
@Component
public final class GameweekFinalityReader {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final OwnedSyncTables tables;

    @Autowired
    public GameweekFinalityReader(JdbcTemplate jdbc, ObjectProvider<Clock> clock,
                                  @Value("${fpl.owned-sync.run-table-name:fpl_sync_run}") String run,
                                  @Value("${fpl.owned-sync.receipt-table-name:fpl_sync_receipt}") String receipt,
                                  @Value("${fpl.owned-sync.fixture-table-name:fpl_fixture_sync}") String fixtureSync,
                                  @Value("${fpl.owned-sync.evidence-table-name:fpl_player_fixture_evidence}") String evidence,
                                  @Value("${fpl.owned-sync.planner-runtime-table-name:fpl_sync_planner_runtime}") String plannerRuntime) {
        this(jdbc, clock.getIfAvailable(Clock::systemUTC),
                new OwnedSyncTables(run, run + "_payload", receipt, run + "_request_state",
                        run + "_request_attempt", fixtureSync, evidence, run + "_planner_slot",
                        plannerRuntime));
    }

    public GameweekFinalityReader(JdbcTemplate jdbc, Clock clock, OwnedSyncTables tables) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.clock = Objects.requireNonNull(clock);
        this.tables = Objects.requireNonNull(tables);
    }

    /** One bounded read for a set of gameweeks; callers can invoke it inside their snapshot transaction. */
    public Map<Integer, Result> read(int seasonId, String seasonCode, Set<Integer> gameweeks) {
        if (seasonId <= 0 || seasonCode == null || seasonCode.isBlank()) {
            throw new IllegalArgumentException("Invalid finality season");
        }
        Objects.requireNonNull(gameweeks);
        if (gameweeks.stream().anyMatch(gw -> gw == null || gw < 1 || gw > 38)) {
            throw new IllegalArgumentException("Invalid finality gameweek");
        }
        if (gameweeks.isEmpty()) return Map.of();

        Map<Integer, SourceSnapshot> sources = currentSources(seasonId, seasonCode, gameweeks);
        Map<Integer, Stored> stored = tableExists(tables.gameweekStatSync())
                ? storedRows(seasonId, seasonCode, gameweeks) : Map.of();
        Map<Integer, Result> result = new HashMap<>();
        for (int gameweek : gameweeks) {
            SourceSnapshot source = sources.get(gameweek);
            Stored row = stored.get(gameweek);
            boolean eligible = source != null && source.finalSweepEligible();
            State state = eligible ? State.FINAL_PENDING : State.OPEN;
            FinalReceipt receipt = null;
            if (source != null && row != null && validFinalReceipt(source, row, seasonId, seasonCode)) {
                state = State.FINALIZED;
                receipt = row.receipt();
            }
            result.put(gameweek, new Result(gameweek, state,
                    source != null && source.eventFinished(), source != null && source.dataChecked(),
                    source != null && source.allAssignedFixturesFinished(),
                    row == null ? null : row.firstCheckedAt(), receipt));
        }
        return Map.copyOf(result);
    }

    private Map<Integer, SourceSnapshot> currentSources(int seasonId, String seasonCode,
                                                         Set<Integer> gameweeks) {
        String placeholders = placeholders(gameweeks.size());
        List<Object> args = new ArrayList<>();
        args.add(seasonId);
        args.addAll(gameweeks);
        Map<Integer, List<FixtureRow>> fixtures = new HashMap<>();
        Map<Integer, GameweekRow> events = new HashMap<>();
        jdbc.query("""
                SELECT g.id AS event_id,g.finished AS event_finished,g.data_checked,
                       f.id AS fixture_id,f.code AS fixture_code,f.gameweek AS fixture_gameweek,
                       f.team_h,f.team_a,f.team_h_score,f.team_a_score,
                       f.team_h_difficulty,f.team_a_difficulty,f.kickoff_time,
                       f.started,f.finished,f.finished_provisional,f.minutes,f.stats::text AS stats
                FROM gameweek g
                LEFT JOIN fixture f ON f.season_id=g.season_id AND f.gameweek=g.id
                WHERE g.season_id=? AND g.id IN (%s)
                ORDER BY g.id,f.id
                """.formatted(placeholders), rs -> {
            int gameweek = rs.getInt("event_id");
            events.putIfAbsent(gameweek, new GameweekRow(seasonId, seasonCode, gameweek, "", null,
                    rs.getBoolean("event_finished"), rs.getBoolean("data_checked"), false, false, Map.of()));
            Integer fixtureId = nullableInteger(rs.getObject("fixture_id"));
            if (fixtureId != null) {
                Map<String, Object> fields = new java.util.LinkedHashMap<>();
                fields.put("finished_provisional", rs.getObject("finished_provisional", Boolean.class));
                fields.put("minutes", nullableInteger(rs.getObject("minutes")));
                fields.put("stats", rs.getString("stats"));
                fixtures.computeIfAbsent(gameweek, ignored -> new ArrayList<>()).add(new FixtureRow(
                        seasonId, seasonCode, fixtureId, rs.getInt("fixture_code"),
                        nullableInteger(rs.getObject("fixture_gameweek")), rs.getInt("team_h"), rs.getInt("team_a"),
                        null, null, null, null, nullableInteger(rs.getObject("team_h_score")),
                        nullableInteger(rs.getObject("team_a_score")),
                        nullableInteger(rs.getObject("team_h_difficulty")),
                        nullableInteger(rs.getObject("team_a_difficulty")), instant(rs, "kickoff_time"),
                        rs.getBoolean("started"), rs.getBoolean("finished"), fields));
            }
        }, args.toArray());

        Map<Integer, SourceSnapshot> result = new HashMap<>();
        events.forEach((gameweek, event) -> {
            try {
                result.put(gameweek, SourceSnapshot.from(event, fixtures.getOrDefault(gameweek, List.of()), true));
            } catch (IllegalArgumentException invalidSource) {
                // A malformed compatibility schedule cannot certify an earlier receipt.
            }
        });
        return Map.copyOf(result);
    }

    private Map<Integer, Stored> storedRows(int seasonId, String seasonCode, Set<Integer> gameweeks) {
        String placeholders = placeholders(gameweeks.size());
        List<Object> args = new ArrayList<>();
        args.add(seasonId);
        args.add(seasonCode);
        args.addAll(gameweeks);
        Map<Integer, Stored> result = new HashMap<>();
        jdbc.query("""
                SELECT gameweek,state,event_finished,data_checked,fixture_manifest_complete,
                       all_assigned_fixtures_finished,source_signature,first_checked_at,
                       current_invalidation_revision,current_invalidation_floor,
                       final_attempt_source_signature,final_attempt_source_floor,
                       final_attempt_invalidation_revision,final_attempt_population_fingerprint,
                       final_attempt_population_manifest::text AS population_manifest,
                       final_attempt_source_read_started_at,final_attempt_source_read_finished_at,
                       final_attempt_required_player_ids,final_attempt_completed_player_ids,
                       final_attempt_outstanding_player_ids,final_attempt_page_read_started_at,
                       final_attempt_page_read_finished_at,finalized_source_signature,
                       finalized_population_fingerprint,finalized_row_revision_fingerprint,
                       finalized_source_floor,finalized_validation_read_started_at,
                       finalized_validation_read_finished_at,finalized_source_read_started_at,
                       finalized_source_read_finished_at,finalized_invalidation_revision,
                       finalized_run_generation,finalized_at,finalized_mandatory_manifest_complete,
                       finalized_page_evidence::text AS finalized_page_evidence,
                       finalized_optional_component_coverage::text AS optional_coverage
                FROM %s WHERE season_id=? AND season_code=? AND gameweek IN (%s)
                """.formatted(tables.gameweekStatSync(), placeholders), rs -> {
            result.put(rs.getInt("gameweek"), stored(rs));
        }, args.toArray());
        return Map.copyOf(result);
    }

    private boolean validFinalReceipt(SourceSnapshot source, Stored row, int seasonId, String seasonCode) {
        try {
            return validFinalReceiptChecked(source, row, seasonId, seasonCode);
        } catch (IllegalArgumentException | NullPointerException invalidReceipt) {
            return false;
        }
    }

    private boolean validFinalReceiptChecked(SourceSnapshot source, Stored row,
                                             int seasonId, String seasonCode) {
        if (row.state() != State.FINALIZED || !source.finalSweepEligible()) return false;
        if (!tableExists(tables.playerFixtureCurrent()) || !tableExists(tables.playerFixtureEvidence())
                || !tableExists(tables.receipt()) || !tableExists(tables.run())) return false;
        SweepCheckpoint checkpoint = row.checkpoint();
        GameweekFinalityRepository.PopulationManifest manifest =
                GameweekFinalityRepository.populationManifest(row.populationManifest());
        if (checkpoint == null || manifest == null || row.state() != State.FINALIZED
                || !source.finalSweepEligible()
                || !Boolean.TRUE.equals(row.eventFinished()) || !Boolean.TRUE.equals(row.dataChecked())
                || !row.fixtureManifestComplete() || !row.allAssignedFixturesFinished()
                || !Objects.equals(row.eventFinished(), source.eventFinished())
                || !Objects.equals(row.dataChecked(), source.dataChecked())
                || !source.signature().equals(row.sourceSignature())
                || !source.signature().equals(row.finalAttemptSourceSignature())
                || !source.signature().equals(row.finalizedSourceSignature())
                || checkpoint.invalidationRevision() != row.invalidationRevision()
                || !Objects.equals(row.finalAttemptInvalidationRevision(), row.invalidationRevision())
                || !Objects.equals(row.finalizedInvalidationRevision(), row.invalidationRevision())
                || row.invalidationFloor() != null && checkpoint.sourceFloor().isBefore(row.invalidationFloor())
                || !checkpoint.outstandingPlayerIds().isEmpty()
                || !checkpoint.completedPlayerIds().equals(checkpoint.requiredPlayerIds())
                || !checkpoint.requiredPlayerIds().equals(manifest.population().requiredPlayerIds())
                || !Boolean.TRUE.equals(row.mandatoryComplete())
                || row.finalizedAt() == null || row.finalizedRunGeneration() == null
                || row.finalizedRunGeneration() <= 0 || row.finalizedSourceRead() == null
                || !validFinalRun(seasonCode, row.finalizedRunGeneration(), row.finalizedSourceRead(),
                        row.attemptSourceStartedAt(), row.finalizedAt())
                || !intervalsCover(row) || !validHash(row.finalizedRowFingerprint())) return false;

        RequiredPopulation population = manifest.population();
        if (!checkpoint.populationFingerprint().equals(row.attemptPopulationFingerprint())
                || !checkpoint.populationFingerprint().equals(population.fingerprint(source))
                || !Objects.equals(row.finalizedPopulationFingerprint(), population.finalizedFingerprint(source))
                || !manifest.fixtureIdentities().equals(GameweekFinalityRepository.fixtureIdentities(source))
                || !population.hasCompleteAppearanceEvidence(source, checkpoint.sourceFloor())) return false;

        CatalogueProof catalogue = currentCatalogue(seasonId, seasonCode, source.assignedFixtures());
        if (!population.bootstrapFingerprint().equals(catalogue.fingerprint())
                || !covers(population.cataloguePlayerIdsByFixture(), catalogue.playerIdsByFixture())) return false;
        GameweekFinalityRepository.CurrentRows current = currentRows(seasonId, seasonCode, source);
        if (!current.valid() || !row.finalizedRowFingerprint().equals(current.fingerprint())
                || !Objects.equals(row.optionalCoverage(), current.coverage())
                || !GameweekFinalityRepository.validatesAppearanceRows(source, population, checkpoint, current)
                || !populationCoversDurableRequiredIds(seasonCode, source, population, current)) return false;
        return freshPages(seasonCode, checkpoint, row.finalizedRunGeneration(), row.finalizedSourceRead(),
                row.finalizedPageEvidence(), row.pageInterval());
    }

    private GameweekFinalityRepository.CurrentRows currentRows(int seasonId, String seasonCode,
                                                                SourceSnapshot source) {
        List<Integer> fixtureIds = source.assignedFixtures().stream().map(FixtureRow::id).sorted().toList();
        if (fixtureIds.isEmpty()) {
            return new GameweekFinalityRepository.CurrentRows(
                    GameweekFinality.rowRevisionFingerprint(source, List.of()), emptyCoverage(), Map.of(), true);
        }
        String placeholders = placeholders(fixtureIds.size());
        String proof = IncrementalPfsEvidenceSql.currentRowProof("c", "p", "s", "e", tables.receipt());
        List<Object> args = new ArrayList<>();
        args.add(seasonId);
        args.add(seasonCode);
        args.addAll(fixtureIds);
        List<RowRevision> revisions = new ArrayList<>();
        Map<GameweekFinalityRepository.FixturePlayer, GameweekFinalityRepository.CurrentPfsRow> current =
                new HashMap<>();
        Map<HistoryParser.Component, long[]> counts = new EnumMap<>(HistoryParser.Component.class);
        for (HistoryParser.Component component : HistoryParser.Component.values()) counts.put(component, new long[2]);
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
                ORDER BY c.fixture_id,c.player_id
                """.formatted(proof, tables.playerFixtureCurrent(), tables.playerFixtureEvidence(), placeholders), rs -> {
            int fixtureId = rs.getInt("fixture_id");
            int playerId = rs.getInt("player_id");
            RowState state = RowState.valueOf(rs.getString("row_state"));
            if (state == RowState.WITHDRAWN) {
                revisions.add(new RowRevision(seasonId, seasonCode, fixtureId, playerId, state, null));
                return;
            }
            Integer gameweek = nullableInteger(rs.getObject("gameweek"));
            String fingerprint = rs.getString("physical_fingerprint");
            boolean rowProof = rs.getBoolean("row_proof") && Objects.equals(gameweek, source.gameweek())
                    && validHash(fingerprint);
            if (!rowProof) {
                valid[0] = false;
                fingerprint = "0".repeat(64);
            }
            revisions.add(new RowRevision(seasonId, seasonCode, fixtureId, playerId, state, fingerprint));
            if (rowProof) current.put(new GameweekFinalityRepository.FixturePlayer(fixtureId, playerId),
                    new GameweekFinalityRepository.CurrentPfsRow(rs.getInt("minutes"),
                            rs.getInt("player_team_id"), rs.getInt("opponent_team_id"),
                            rs.getObject("was_home", Boolean.class)));
            long mask = rs.getLong("component_presence");
            for (HistoryParser.Component component : HistoryParser.Component.values()) {
                counts.get(component)[0]++;
                if ((mask & component.bit()) != 0) counts.get(component)[1]++;
            }
        }, args.toArray());
        Map<HistoryParser.Component, ComponentCoverage> coverage = new EnumMap<>(HistoryParser.Component.class);
        for (HistoryParser.Component component : HistoryParser.Component.values()) {
            long[] count = counts.get(component);
            coverage.put(component, new ComponentCoverage(Math.toIntExact(count[0]),
                    Math.toIntExact(count[1]), true));
        }
        return new GameweekFinalityRepository.CurrentRows(
                GameweekFinality.rowRevisionFingerprint(source, revisions), Map.copyOf(coverage),
                Map.copyOf(current), valid[0]);
    }

    private boolean validFinalRun(String season, long generation, SourceRead finalSourceRead,
                                  Instant initialSourceStartedAt, Instant finalizedAt) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM %s final_run
                    WHERE final_run.generation=? AND final_run.season=?
                      AND final_run.kind='PLAYER_HISTORY' AND final_run.producer_key='INCREMENTAL_CORE'
                      AND final_run.runtime_mode='ACTIVE' AND final_run.runtime_epoch>0
                      AND final_run.coalescing_key='incremental-core-import'
                      AND final_run.publication_key='incremental-core-import'
                      AND final_run.state='COMPLETED' AND final_run.failed_at IS NULL
                      AND final_run.last_error IS NULL AND final_run.completed_at IS NOT NULL
                      AND final_run.completed_at>=? AND final_run.completed_at<=?
                      AND final_run.requested_source_after<=?
                      AND final_run.source_read_started_at IS NOT NULL
                      AND final_run.source_read_finished_at IS NOT NULL
                      AND final_run.source_read_started_at<=final_run.source_read_finished_at
                      AND final_run.source_read_started_at<=?
                      AND final_run.source_read_finished_at>=?
                      AND final_run.completed_at>=final_run.source_read_finished_at
                )
                """.formatted(tables.run()), Boolean.class,
                generation, season, Timestamp.from(finalizedAt), Timestamp.from(clock.instant()),
                initialSourceStartedAt == null ? null : Timestamp.from(initialSourceStartedAt),
                Timestamp.from(finalSourceRead.startedAt()), Timestamp.from(finalSourceRead.finishedAt())));
    }

    private boolean freshPages(String season, SweepCheckpoint checkpoint, long finalGeneration,
                               SourceRead finalSourceRead, String pageEvidenceJson, SourceRead expectedPageInterval) {
        Map<Integer, Page> original = parsePageEvidence(pageEvidenceJson);
        Set<Integer> required = checkpoint.requiredPlayerIds();
        if (!original.keySet().equals(required)) return false;
        if (required.isEmpty()) return expectedPageInterval == null;
        if (expectedPageInterval == null) return false;
        Instant earliest = original.values().stream().map(Page::startedAt).min(Instant::compareTo).orElseThrow();
        Instant latest = original.values().stream().map(Page::finishedAt).max(Instant::compareTo).orElseThrow();
        if (!expectedPageInterval.equals(new SourceRead(earliest, latest))) return false;

        String values = String.join(",", java.util.Collections.nCopies(required.size(),
                "(?::integer,?::bigint,?::timestamptz,?::timestamptz)"));
        List<Object> args = new ArrayList<>();
        for (int player : required.stream().sorted().toList()) {
            Page page = original.get(player);
            if (page.generation() > finalGeneration || page.startedAt().isBefore(checkpoint.sourceFloor())
                    || page.finishedAt().isAfter(finalSourceRead.finishedAt())) return false;
            args.add(player);
            args.add(page.generation());
            args.add(Timestamp.from(page.startedAt()));
            args.add(Timestamp.from(page.finishedAt()));
        }
        args.add(season);
        args.add(IncrementalPlayerFixturePublisher.PAGE_RECEIPT_PREFIX);
        args.add(finalGeneration);
        Instant now = clock.instant();
        args.add(Timestamp.from(now));
        args.add(Timestamp.from(now));
        args.add(Timestamp.from(now));
        args.add(Timestamp.from(now));
        args.add(Timestamp.from(now));
        args.add(Timestamp.from(now));
        Set<Integer> validated = new HashSet<>();
        jdbc.query("""
                WITH original(player_id, generation, started_at, finished_at) AS (VALUES %s)
                SELECT original.player_id,original.generation,original.started_at,original.finished_at,
                       page_run.requested_source_after AS page_requested_after,
                       page_run.source_read_started_at AS page_run_started,
                       page_run.source_read_finished_at AS page_run_finished,
                       page_run.completed_at AS page_run_completed,
                       receipt.latest_generation,receipt.source_read_started_at AS latest_started,
                       receipt.source_read_finished_at AS latest_finished,
                       latest_run.completed_at AS latest_run_completed
                FROM original
                JOIN %s receipt ON receipt.season=? AND receipt.publication_key=? || original.player_id::text
                JOIN %s page_run ON page_run.generation=original.generation
                    AND page_run.season=receipt.season AND page_run.kind='PLAYER_HISTORY'
                    AND page_run.coalescing_key='incremental-core-import'
                    AND page_run.publication_key='incremental-core-import'
                JOIN %s latest_run ON latest_run.generation=receipt.latest_generation
                    AND latest_run.season=receipt.season
                WHERE receipt.latest_generation>=original.generation
                  AND original.generation<=?
                  AND original.started_at<=original.finished_at
                  AND receipt.source_read_started_at IS NOT NULL
                  AND receipt.source_read_finished_at>=receipt.source_read_started_at
                  AND page_run.producer_key='INCREMENTAL_CORE' AND page_run.runtime_mode='ACTIVE'
                  AND page_run.runtime_epoch>0 AND page_run.state='COMPLETED'
                  AND page_run.failed_at IS NULL AND page_run.last_error IS NULL
                  AND page_run.completed_at IS NOT NULL
                  AND page_run.requested_source_after<=original.started_at
                  AND page_run.source_read_started_at IS NOT NULL
                  AND page_run.source_read_finished_at IS NOT NULL
                  AND page_run.source_read_started_at<=original.started_at
                  AND page_run.source_read_finished_at>=original.finished_at
                  AND page_run.completed_at>=page_run.source_read_finished_at
                  AND page_run.completed_at<=?
                  AND latest_run.kind='PLAYER_HISTORY' AND latest_run.producer_key='INCREMENTAL_CORE'
                  AND latest_run.runtime_mode='ACTIVE' AND latest_run.runtime_epoch>0
                  AND latest_run.coalescing_key='incremental-core-import'
                  AND latest_run.publication_key='incremental-core-import'
                  AND latest_run.state IN ('PENDING','LEASED','COMPLETED','FAILED')
                  AND latest_run.requested_source_after<=receipt.source_read_started_at
                  AND latest_run.source_read_started_at IS NOT NULL
                  AND latest_run.source_read_finished_at IS NOT NULL
                  AND latest_run.source_read_started_at<=receipt.source_read_started_at
                  AND latest_run.source_read_finished_at>=receipt.source_read_finished_at
                  AND latest_run.source_read_finished_at<=?
                  AND (latest_run.state<>'COMPLETED' OR
                       (latest_run.completed_at>=latest_run.source_read_finished_at
                        AND latest_run.completed_at<=?))
                  AND (latest_run.state<>'FAILED' OR
                       (latest_run.failed_at IS NOT NULL AND latest_run.failed_at<=?))
                  AND receipt.source_read_started_at<=? AND receipt.source_read_finished_at<=?
                  AND (receipt.latest_generation<>original.generation
                       OR (receipt.source_read_started_at=original.started_at
                           AND receipt.source_read_finished_at=original.finished_at))
                  AND (receipt.latest_generation=original.generation
                       OR receipt.source_read_started_at>=original.finished_at)
                """.formatted(values, tables.receipt(), tables.run(), tables.run()),
                rs -> { validated.add(rs.getInt("player_id")); }, args.toArray());
        return validated.equals(required);
    }

    private CatalogueProof currentCatalogue(int seasonId, String seasonCode, List<FixtureRow> fixtures) {
        Set<Integer> clubs = new HashSet<>();
        fixtures.forEach(fixture -> {
            clubs.add(fixture.homeTeamId());
            clubs.add(fixture.awayTeamId());
        });
        List<PlayerRow> players;
        if (clubs.isEmpty()) {
            players = List.of();
        } else {
            String clubPlaceholders = placeholders(clubs.size());
            List<Object> args = new ArrayList<>();
            args.add(seasonId);
            args.addAll(clubs);
            players = jdbc.query("SELECT id,team_id FROM player WHERE season_id=? AND team_id IN ("
                            + clubPlaceholders + ") ORDER BY id", (rs, n) -> new PlayerRow(seasonId, seasonCode,
                            rs.getInt("id"), rs.getInt("team_id"), "", 1, 1, Map.of()), args.toArray());
        }
        Map<Integer, Set<Integer>> byFixture = new java.util.TreeMap<>();
        for (FixtureRow fixture : fixtures) {
            Set<Integer> ids = new TreeSet<>();
            players.stream().filter(player -> player.teamId() == fixture.homeTeamId()
                    || player.teamId() == fixture.awayTeamId()).map(PlayerRow::id).forEach(ids::add);
            byFixture.put(fixture.id(), Set.copyOf(ids));
        }
        return new CatalogueProof(GameweekFinality.catalogueFingerprint(players, fixtures), Map.copyOf(byFixture));
    }

    private static boolean covers(Map<Integer, Set<Integer>> population,
                                  Map<Integer, Set<Integer>> requiredByFixture) {
        return requiredByFixture.entrySet().stream().allMatch(entry ->
                population.getOrDefault(entry.getKey(), Set.of()).containsAll(entry.getValue()));
    }

    private boolean populationCoversDurableRequiredIds(String seasonCode, SourceSnapshot source,
                                                        RequiredPopulation population,
                                                        GameweekFinalityRepository.CurrentRows current) {
        Map<Integer, Set<Integer>> requiredByFixture = population.requiredPlayerIdsByFixture();
        Set<Integer> fixtures = source.assignedFixtures().stream().map(FixtureRow::id)
                .collect(java.util.stream.Collectors.toSet());
        Map<Integer, Set<Integer>> durableParticipants = durableParticipantIds(seasonCode, fixtures);
        for (FixtureRow fixture : source.assignedFixtures()) {
            Set<Integer> required = requiredByFixture.getOrDefault(fixture.id(), Set.of());
            Set<Integer> sourceParticipants = FixtureVerification.fixtureStatParticipantIds(fixture);
            FixtureVerification.FixtureEvidence evidence =
                    population.participantEvidenceByFixture().get(fixture.id());
            if (!required.containsAll(sourceParticipants)
                    || !required.containsAll(durableParticipants.getOrDefault(fixture.id(), Set.of()))
                    || evidence == null || !required.containsAll(evidence.positivePlayerIds())) return false;
        }
        return current.rows().keySet().stream().allMatch(key ->
                requiredByFixture.getOrDefault(key.fixtureId(), Set.of()).contains(key.playerId()));
    }

    private Map<Integer, Set<Integer>> durableParticipantIds(String season, Set<Integer> fixtureIds) {
        if (fixtureIds.isEmpty()) return Map.of();
        String prefix = OwnedSyncStore.INCREMENTAL_PARTICIPANT_RECEIPT_PREFIX;
        List<String> patterns = fixtureIds.stream().sorted().map(id -> prefix + id + ":player:%").toList();
        List<Object> args = new ArrayList<>();
        args.add(season);
        args.addAll(patterns);
        String predicates = String.join(" OR ", patterns.stream().map(ignored -> "publication_key LIKE ?").toList());
        Map<Integer, Set<Integer>> result = new java.util.TreeMap<>();
        jdbc.query("SELECT publication_key FROM " + tables.receipt()
                + " WHERE season=? AND latest_generation IS NOT NULL AND (" + predicates + ")",
                rs -> {
                    String key = rs.getString("publication_key");
                    int separator = key.indexOf(":player:", prefix.length());
                    try {
                        int fixtureId = Integer.parseInt(key.substring(prefix.length(), separator));
                        int playerId = Integer.parseInt(key.substring(separator + ":player:".length()));
                        result.computeIfAbsent(fixtureId, ignored -> new TreeSet<>()).add(playerId);
                    } catch (RuntimeException invalidKey) {
                        throw new IllegalStateException("Invalid durable fixture participant key", invalidKey);
                    }
                }, args.toArray());
        Map<Integer, Set<Integer>> immutable = new java.util.TreeMap<>();
        result.forEach((fixture, ids) -> immutable.put(fixture, Set.copyOf(ids)));
        return Map.copyOf(immutable);
    }

    private static boolean intervalsCover(Stored row) {
        SourceRead source = row.finalizedSourceRead();
        SourceRead validation = row.validationRead();
        if (source == null || validation == null || row.attemptSourceFloor() == null
                || source.startedAt().isAfter(validation.startedAt())
                || source.finishedAt().isBefore(validation.finishedAt())
                || validation.startedAt().isBefore(row.attemptSourceFloor())
                || row.finalizedAt().isBefore(validation.finishedAt())
                || !source.startedAt().equals(row.attemptSourceStartedAt())
                || !source.finishedAt().equals(row.attemptSourceFinishedAt())
                || !Objects.equals(row.finalizedSourceFloor(), row.attemptSourceFloor())) return false;
        return row.pageStartedAt() == null && row.pageFinishedAt() == null
                || row.pageStartedAt() != null && row.pageFinishedAt() != null
                && !source.startedAt().isAfter(row.pageStartedAt())
                && !source.finishedAt().isBefore(row.pageFinishedAt())
                && !validation.startedAt().isBefore(row.pageFinishedAt());
    }

    private Stored stored(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Stored(State.valueOf(rs.getString("state")), rs.getObject("event_finished", Boolean.class),
                rs.getObject("data_checked", Boolean.class), rs.getBoolean("fixture_manifest_complete"),
                rs.getBoolean("all_assigned_fixtures_finished"), rs.getString("source_signature"),
                instant(rs, "first_checked_at"), rs.getLong("current_invalidation_revision"),
                instant(rs, "current_invalidation_floor"), rs.getString("final_attempt_source_signature"),
                instant(rs, "final_attempt_source_floor"),
                (Long) rs.getObject("final_attempt_invalidation_revision"),
                rs.getString("final_attempt_population_fingerprint"), rs.getString("population_manifest"),
                instant(rs, "final_attempt_source_read_started_at"),
                instant(rs, "final_attempt_source_read_finished_at"),
                ids(rs.getArray("final_attempt_required_player_ids")),
                ids(rs.getArray("final_attempt_completed_player_ids")),
                ids(rs.getArray("final_attempt_outstanding_player_ids")),
                instant(rs, "final_attempt_page_read_started_at"),
                instant(rs, "final_attempt_page_read_finished_at"),
                rs.getString("finalized_source_signature"), rs.getString("finalized_population_fingerprint"),
                rs.getString("finalized_row_revision_fingerprint"), instant(rs, "finalized_source_floor"),
                instant(rs, "finalized_validation_read_started_at"),
                instant(rs, "finalized_validation_read_finished_at"),
                instant(rs, "finalized_source_read_started_at"), instant(rs, "finalized_source_read_finished_at"),
                rs.getString("finalized_page_evidence"),
                (Long) rs.getObject("finalized_invalidation_revision"),
                (Long) rs.getObject("finalized_run_generation"), instant(rs, "finalized_at"),
                rs.getObject("finalized_mandatory_manifest_complete", Boolean.class),
                parseCoverage(rs.getString("optional_coverage")));
    }

    private static Map<Integer, Page> parsePageEvidence(String json) {
        if (json == null) throw new IllegalArgumentException("Missing finalized page evidence");
        try {
            JsonNode root = JSON.readTree(json);
            if (root == null || !root.isObject()) throw new IllegalArgumentException("Invalid finalized page evidence");
            Map<Integer, Page> result = new java.util.TreeMap<>();
            root.fields().forEachRemaining(entry -> {
                int playerId = Integer.parseInt(entry.getKey());
                JsonNode value = entry.getValue();
                Set<String> fields = new HashSet<>();
                value.fieldNames().forEachRemaining(fields::add);
                if (playerId <= 0 || !value.isObject()
                        || !fields.equals(Set.of("generation", "startedAt", "finishedAt"))
                        || !value.path("generation").canConvertToLong()
                        || !value.path("startedAt").isTextual() || !value.path("finishedAt").isTextual()) {
                    throw new IllegalArgumentException("Invalid finalized page evidence");
                }
                long generation = value.path("generation").longValue();
                Instant started = Instant.parse(value.path("startedAt").asText());
                Instant finished = Instant.parse(value.path("finishedAt").asText());
                if (generation <= 0 || started.isAfter(finished)
                        || result.put(playerId, new Page(generation, started, finished)) != null) {
                    throw new IllegalArgumentException("Invalid finalized page evidence");
                }
            });
            return Map.copyOf(result);
        } catch (java.io.IOException | RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid finalized page evidence", invalid);
        }
    }

    private static Map<HistoryParser.Component, ComponentCoverage> parseCoverage(String json) {
        if (json == null) return null;
        try {
            JsonNode root = JSON.readTree(json);
            Map<HistoryParser.Component, ComponentCoverage> result = new EnumMap<>(HistoryParser.Component.class);
            root.fields().forEachRemaining(entry -> {
                HistoryParser.Component component = HistoryParser.Component.valueOf(entry.getKey());
                JsonNode value = entry.getValue();
                result.put(component, new ComponentCoverage(value.path("sourceRows").intValue(),
                        value.path("presentRows").intValue(), value.path("supported").booleanValue()));
            });
            return result.keySet().equals(Set.of(HistoryParser.Component.values()))
                    ? Map.copyOf(result) : null;
        } catch (Exception invalid) {
            return null;
        }
    }

    private static Map<HistoryParser.Component, ComponentCoverage> emptyCoverage() {
        Map<HistoryParser.Component, ComponentCoverage> result = new EnumMap<>(HistoryParser.Component.class);
        for (HistoryParser.Component component : HistoryParser.Component.values()) {
            result.put(component, new ComponentCoverage(0, 0, true));
        }
        return Map.copyOf(result);
    }

    private boolean tableExists(String tableName) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, tableName));
    }

    private static Set<Integer> ids(Array array) throws java.sql.SQLException {
        if (array == null) return Set.of();
        Set<Integer> result = new TreeSet<>();
        for (Object item : (Object[]) array.getArray()) result.add(((Number) item).intValue());
        return Set.copyOf(result);
    }

    private static String placeholders(int size) {
        return String.join(",", java.util.Collections.nCopies(size, "?"));
    }

    private static Integer nullableInteger(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static boolean validHash(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private record Page(long generation, Instant startedAt, Instant finishedAt) { }
    private record CatalogueProof(String fingerprint, Map<Integer, Set<Integer>> playerIdsByFixture) { }

    private record Stored(State state, Boolean eventFinished, Boolean dataChecked,
                          boolean fixtureManifestComplete, boolean allAssignedFixturesFinished,
                          String sourceSignature, Instant firstCheckedAt, long invalidationRevision,
                          Instant invalidationFloor, String finalAttemptSourceSignature,
                          Instant attemptSourceFloor, Long finalAttemptInvalidationRevision,
                          String attemptPopulationFingerprint, String populationManifest,
                          Instant attemptSourceStartedAt, Instant attemptSourceFinishedAt,
                          Set<Integer> required, Set<Integer> completed, Set<Integer> outstanding,
                          Instant pageStartedAt, Instant pageFinishedAt,
                          String finalizedSourceSignature, String finalizedPopulationFingerprint,
                          String finalizedRowFingerprint, Instant finalizedSourceFloor,
                          Instant validationStartedAt, Instant validationFinishedAt,
                          Instant sourceStartedAt, Instant sourceFinishedAt, String finalizedPageEvidence,
                          Long finalizedInvalidationRevision, Long finalizedRunGeneration,
                          Instant finalizedAt, Boolean mandatoryComplete,
                          Map<HistoryParser.Component, ComponentCoverage> optionalCoverage) {
        SweepCheckpoint checkpoint() {
            if (finalAttemptSourceSignature == null || attemptSourceFloor == null
                    || finalAttemptInvalidationRevision == null || attemptPopulationFingerprint == null) return null;
            return new SweepCheckpoint(finalAttemptSourceSignature, attemptPopulationFingerprint, attemptSourceFloor,
                    finalAttemptInvalidationRevision, required, completed, outstanding);
        }

        SourceRead finalizedSourceRead() {
            return sourceStartedAt == null || sourceFinishedAt == null ? null
                    : new SourceRead(sourceStartedAt, sourceFinishedAt);
        }
        SourceRead validationRead() {
            return validationStartedAt == null || validationFinishedAt == null ? null
                    : new SourceRead(validationStartedAt, validationFinishedAt);
        }
        SourceRead pageInterval() {
            return pageStartedAt == null || pageFinishedAt == null ? null
                    : new SourceRead(pageStartedAt, pageFinishedAt);
        }
        FinalReceipt receipt() {
            return new FinalReceipt(finalizedSourceSignature, finalizedRowFingerprint, finalizedRunGeneration,
                    finalizedSourceFloor, finalizedInvalidationRevision, finalizedAt, finalizedSourceRead(),
                    Boolean.TRUE.equals(mandatoryComplete), optionalCoverage);
        }
    }

    public record Result(int gameweek, State state, boolean eventFinished, boolean dataChecked,
                         boolean allAssignedFixturesFinished, Instant firstCheckedAt, FinalReceipt receipt) {
        public Result {
            if (gameweek < 1 || gameweek > 38) throw new IllegalArgumentException("Invalid gameweek");
            Objects.requireNonNull(state);
            if ((state == State.FINALIZED || state == State.FINAL_PENDING)
                    && (!eventFinished || !dataChecked || !allAssignedFixturesFinished)) {
                throw new IllegalArgumentException("Final sweep states require a checked, fully finished source");
            }
            if (state == State.FINALIZED && receipt == null) {
                throw new IllegalArgumentException("Finalized status requires a validated receipt");
            }
            if (state != State.FINALIZED && receipt != null) {
                throw new IllegalArgumentException("Unfinalized status cannot carry a final receipt");
            }
        }
    }
}
