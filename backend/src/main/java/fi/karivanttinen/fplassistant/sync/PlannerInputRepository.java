package fi.karivanttinen.fplassistant.sync;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;

import fi.karivanttinen.fplassistant.sync.AdaptiveMatchdayPlanner.Fixture;
import fi.karivanttinen.fplassistant.sync.AdaptiveMatchdayPlanner.FixtureProgress;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.ReadInterval;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.Season;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.Snapshot;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.SourceDocument;

/** Builds planner input from durable source staging, falling back to the existing FPL captures. */
public final class PlannerInputRepository {

    private final JdbcTemplate jdbc;
    private final OwnedSyncStore store;
    private final OwnedSyncTables owned;
    private final CatalogueScheduleTables captured;
    private final Clock clock;

    public PlannerInputRepository(JdbcTemplate jdbc, OwnedSyncStore store, OwnedSyncTables owned,
                                  CatalogueScheduleTables captured, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.store = Objects.requireNonNull(store);
        this.owned = Objects.requireNonNull(owned);
        this.captured = Objects.requireNonNull(captured);
        this.clock = Objects.requireNonNull(clock);
    }

    public PlannerSnapshot load(Season season) {
        Objects.requireNonNull(season);
        Snapshot fresh = latestOwnedSnapshot(season);
        SourceState source = fresh == null ? capturedSnapshot(season) : sourceState(fresh);
        store.withinRuntimeFence(() -> {
            observeTransitions(season, source.fixtures());
            return null;
        });
        return new PlannerSnapshot(source.playerIds(), source.fixtures(), progress(season), fresh != null);
    }

    private Snapshot latestOwnedSnapshot(Season season) {
        return store.findInLatestValidPublicPayloadPairs(season.code(), 20, pair -> {
            try {
                return Optional.of(CatalogueScheduleParser.parse(
                        season, document(pair.bootstrap()), document(pair.fixtures())));
            } catch (IllegalArgumentException ignored) {
                // Validation metadata never bypasses the strict parser.
                return Optional.empty();
            }
        }).orElse(null);
    }

    private SourceState sourceState(Snapshot snapshot) {
        Map<Integer, Boolean> checked = new HashMap<>();
        snapshot.gameweeks().forEach(row -> checked.put(row.id(), row.dataChecked()));
        Map<Integer, Set<Integer>> playersByTeam = new HashMap<>();
        snapshot.players().forEach(player -> playersByTeam
                .computeIfAbsent(player.teamId(), ignored -> new HashSet<>()).add(player.id()));
        List<Fixture> fixtures = snapshot.fixtures().stream().map(row -> new Fixture(row.id(), row.gameweek(),
                row.kickoff(), row.started(), row.finished(), checked.getOrDefault(row.gameweek(), false),
                snapshot.fixturesRead().completedAt(), union(playersByTeam.get(row.homeTeamId()),
                playersByTeam.get(row.awayTeamId())))).toList();
        return new SourceState(snapshot.players().stream().map(CatalogueScheduleParser.PlayerRow::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet()), fixtures);
    }

    private SourceState capturedSnapshot(Season season) {
        List<Map<String, Object>> latest = jdbc.queryForList("""
                SELECT element_id, team_id FROM %s
                WHERE season=? AND price_date=(SELECT MAX(price_date) FROM %s WHERE season=?)
                """.formatted(captured.dailyPrice(), captured.dailyPrice()), season.code(), season.code());
        Set<Integer> playerIds = new HashSet<>();
        Map<Integer, Set<Integer>> playersByTeam = new HashMap<>();
        for (Map<String, Object> row : latest) {
            int player = ((Number) row.get("element_id")).intValue();
            playerIds.add(player);
            if (row.get("team_id") != null) playersByTeam.computeIfAbsent(
                    ((Number) row.get("team_id")).intValue(), ignored -> new HashSet<>()).add(player);
        }
        List<Fixture> fixtures = jdbc.query("""
                SELECT f.id, f.gameweek, f.team_h, f.team_a, f.kickoff_time, f.started, f.finished,
                       f.captured_at, COALESCE(g.data_checked, false) data_checked
                FROM %s f LEFT JOIN %s g ON g.season=f.season AND g.id=f.gameweek
                WHERE f.season=? ORDER BY f.id
                """.formatted(captured.fixture(), captured.gameweek()), (rs, row) -> new Fixture(
                rs.getInt("id"), rs.getObject("gameweek", Integer.class),
                rs.getTimestamp("kickoff_time") == null ? null : rs.getTimestamp("kickoff_time").toInstant(),
                rs.getBoolean("started"), rs.getBoolean("finished"), rs.getBoolean("data_checked"),
                rs.getTimestamp("captured_at").toInstant(), union(playersByTeam.get(rs.getInt("team_h")),
                playersByTeam.get(rs.getInt("team_a")))), season.code());
        return new SourceState(Set.copyOf(playerIds), fixtures);
    }

    private void observeTransitions(Season season, List<Fixture> fixtures) {
        Instant now = clock.instant();
        String sql = """
                INSERT INTO %s(season_id, season_code, fixture_id, state,
                    first_finished_observed_at, data_checked_transition_at, last_data_checked, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (season_id, fixture_id) DO UPDATE SET
                    state=CASE WHEN %s.state='VERIFIED' THEN %s.state ELSE EXCLUDED.state END,
                    next_due_at=CASE WHEN %s.first_finished_observed_at IS NULL
                        AND EXCLUDED.first_finished_observed_at IS NOT NULL
                        THEN EXCLUDED.first_finished_observed_at ELSE %s.next_due_at END,
                    attempt_count=CASE WHEN %s.first_finished_observed_at IS NULL
                        AND EXCLUDED.first_finished_observed_at IS NOT NULL
                        THEN 0 ELSE %s.attempt_count END,
                    last_error=CASE WHEN %s.first_finished_observed_at IS NULL
                        AND EXCLUDED.first_finished_observed_at IS NOT NULL
                        THEN NULL ELSE %s.last_error END,
                    first_finished_observed_at=COALESCE(%s.first_finished_observed_at,
                        EXCLUDED.first_finished_observed_at),
                    data_checked_transition_at=CASE
                        WHEN NOT %s.last_data_checked AND EXCLUDED.last_data_checked
                        THEN EXCLUDED.data_checked_transition_at
                        ELSE %s.data_checked_transition_at END,
                    last_data_checked=EXCLUDED.last_data_checked, updated_at=EXCLUDED.updated_at
                """.formatted(owned.fixtureSync(), owned.fixtureSync(), owned.fixtureSync(),
                owned.fixtureSync(), owned.fixtureSync(), owned.fixtureSync(), owned.fixtureSync(),
                owned.fixtureSync(), owned.fixtureSync(), owned.fixtureSync(), owned.fixtureSync(),
                owned.fixtureSync());
        List<Object[]> observations = fixtures.stream().map(fixture -> new Object[] {
                season.id(), season.code(), fixture.id(), fixture.started()
                    ? (fixture.finished() ? "VERIFYING" : "WAITING_FINISH") : "SCHEDULED",
                // Our own first sighting, not the capture's finish time: the retry window that
                // follows it is about how long we have been trying, so a backlog fixture seen for
                // the first time at cutover still gets the normal follow-up cadence.
                fixture.finished() ? timestamp(now) : null,
                fixture.dataChecked() ? timestamp(fixture.sourceObservedAt()) : null,
                fixture.dataChecked(), timestamp(now)
        }).toList();
        jdbc.batchUpdate(sql, observations, new int[] {
                java.sql.Types.INTEGER, java.sql.Types.VARCHAR, java.sql.Types.INTEGER, java.sql.Types.VARCHAR,
                java.sql.Types.TIMESTAMP, java.sql.Types.TIMESTAMP, java.sql.Types.BOOLEAN, java.sql.Types.TIMESTAMP
        });
    }

    private List<FixtureProgress> progress(Season season) {
        Instant now = clock.instant();
        return jdbc.query("""
                SELECT fs.fixture_id, fs.state, fs.first_finished_observed_at, fs.updated_at, fs.attempt_count,
                       fs.next_due_at, fs.first_verified_at, fs.data_checked_transition_at,
                       CASE WHEN fs.state='VERIFIED' AND fs.current_verified_generation IS NOT NULL
                                  AND fs.first_verified_at IS NOT NULL
                                  AND publication.latest_generation=fs.current_verified_generation
                                  AND publication.published_at IS NOT NULL AND publication.published_at<=?
                                  AND publication.source_read_started_at IS NOT NULL
                                  AND publication.source_read_finished_at IS NOT NULL
                                  AND publication.source_read_started_at<=publication.source_read_finished_at
                                  AND publication.source_read_finished_at<=publication.published_at
                                  AND publication.source_read_started_at=run.source_read_started_at
                                  AND publication.source_read_finished_at=run.source_read_finished_at
                                  AND run.kind='FIXTURE_VERIFICATION' AND run.state='COMPLETED'
                                  AND run.runtime_mode='ACTIVE' AND run.runtime_epoch>0
                                  AND run.completed_at IS NOT NULL AND run.completed_at<=publication.published_at
                                  AND run.source_read_started_at IS NOT NULL
                                  AND run.source_read_finished_at IS NOT NULL
                                  AND run.source_read_started_at<=run.source_read_finished_at
                                  AND run.source_read_finished_at<=publication.published_at
                            THEN publication.source_read_started_at END AS completed_source_started_at
                FROM %s fs
                JOIN season s ON s.id=fs.season_id AND s.code=fs.season_code
                LEFT JOIN %s publication ON publication.season=fs.season_code
                    AND publication.publication_key='fixture:' || fs.fixture_id::text
                    AND publication.latest_generation=fs.current_verified_generation
                LEFT JOIN %s run ON run.generation=fs.current_verified_generation
                    AND run.season=fs.season_code
                    AND run.coalescing_key='fixture:' || fs.fixture_id::text
                    AND run.publication_key='fixture:' || fs.fixture_id::text
                WHERE fs.season_id=? AND fs.season_code=?
                """.formatted(owned.fixtureSync(), owned.receipt(), owned.run()), (rs, row) -> {
            Instant firstVerified = "VERIFIED".equals(rs.getString("state"))
                    ? instant(rs.getTimestamp("first_verified_at")) : null;
            Instant sourceStarted = instant(rs.getTimestamp("completed_source_started_at"));
            Set<String> completedCorrections = AdaptiveMatchdayPlanner.completedCorrections(firstVerified,
                    instant(rs.getTimestamp("data_checked_transition_at")), sourceStarted);
            return new FixtureProgress(rs.getInt("fixture_id"),
                    Set.of("WAITING_FINISH", "WAITING_STATS", "VERIFYING").contains(rs.getString("state")),
                    instant(rs.getTimestamp("first_finished_observed_at")),
                    rs.getInt("attempt_count") == 0 ? null : instant(rs.getTimestamp("updated_at")),
                    rs.getInt("attempt_count"), instant(rs.getTimestamp("next_due_at")), firstVerified,
                    instant(rs.getTimestamp("data_checked_transition_at")), completedCorrections);
        }, timestamp(now), season.id(), season.code());
    }

    private static SourceDocument document(OwnedSyncStore.StagedPayload payload) {
        return new SourceDocument(payload.body(), new ReadInterval(
                payload.read().startedAt(), payload.read().finishedAt()));
    }

    private static Set<Integer> union(Set<Integer> first, Set<Integer> second) {
        Set<Integer> result = new HashSet<>();
        if (first != null) result.addAll(first);
        if (second != null) result.addAll(second);
        return Set.copyOf(result);
    }

    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    public record PlannerSnapshot(Set<Integer> playerIds, List<Fixture> fixtures,
                                  List<FixtureProgress> progress, boolean ownedDiscovery) {
        public PlannerSnapshot {
            playerIds = Set.copyOf(playerIds);
            fixtures = List.copyOf(fixtures);
            progress = List.copyOf(progress);
        }
    }

    /** Compatibility constructor for focused planner/coordinator tests. */
    public static PlannerSnapshot captured(Set<Integer> playerIds, List<Fixture> fixtures,
                                           List<FixtureProgress> progress) {
        return new PlannerSnapshot(playerIds, fixtures, progress, false);
    }

    private record SourceState(Set<Integer> playerIds, List<Fixture> fixtures) { }
}
