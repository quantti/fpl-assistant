package fi.karivanttinen.fplassistant.sync;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;

import fi.karivanttinen.fplassistant.sync.AdaptiveMatchdayPlanner.Fixture;
import fi.karivanttinen.fplassistant.sync.AdaptiveMatchdayPlanner.FixtureProgress;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.ReadInterval;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.Season;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.Snapshot;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.SourceDocument;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.PublicPayloadPair;

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
        for (PublicPayloadPair pair : store.latestValidPublicPayloadPairs(season.code(), 20)) {
            try {
                return CatalogueScheduleParser.parse(season, document(pair.bootstrap()), document(pair.fixtures()));
            } catch (IllegalArgumentException ignored) {
                // Validation metadata never bypasses the strict parser.
            }
        }
        return null;
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
        for (Fixture fixture : fixtures) {
            jdbc.update("""
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
                    owned.fixtureSync()),
                    season.id(), season.code(), fixture.id(), fixture.started() ?
                            (fixture.finished() ? "VERIFYING" : "WAITING_FINISH") : "SCHEDULED",
                    fixture.finished() ? timestamp(fixture.sourceObservedAt()) : null,
                    fixture.dataChecked() ? timestamp(fixture.sourceObservedAt()) : null,
                    fixture.dataChecked(), timestamp(now));
        }
    }

    private List<FixtureProgress> progress(Season season) {
        return jdbc.query("""
                SELECT fixture_id, state, first_finished_observed_at, updated_at, attempt_count,
                       next_due_at, first_verified_at, data_checked_transition_at
                FROM %s WHERE season_id=? AND season_code=?
                """.formatted(owned.fixtureSync()), (rs, row) -> new FixtureProgress(
                rs.getInt("fixture_id"), Set.of("WAITING_FINISH", "WAITING_STATS", "VERIFYING")
                        .contains(rs.getString("state")),
                instant(rs.getTimestamp("first_finished_observed_at")),
                rs.getInt("attempt_count") == 0 ? null : instant(rs.getTimestamp("updated_at")),
                rs.getInt("attempt_count"), instant(rs.getTimestamp("next_due_at")),
                "VERIFIED".equals(rs.getString("state"))
                        ? instant(rs.getTimestamp("first_verified_at")) : null,
                instant(rs.getTimestamp("data_checked_transition_at")), Set.of()),
                season.id(), season.code());
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
