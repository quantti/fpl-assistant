package dev.quantti.fplassistant.testsupport;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.List;
import java.util.function.Supplier;

import dev.quantti.fplassistant.sync.CatalogueScheduleParser.FixtureRow;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.TeamRow;

/**
 * The fixture league every data-dependent test asserts against.
 *
 * <p>Before the move to an ephemeral test database these tests asserted on whatever the shared
 * Supabase happened to contain — Salah's real xG, Arsenal's real fixture list, "no season has an
 * unfinished fixture". That made them assertions about a production sync rather than about this
 * code, and it made several of them unable to reach the branch they were written for: with no
 * unfinished fixture anywhere in the database, the fixture-difficulty tool could only ever return
 * its empty message.
 *
 * <p>So the tests seed what they assert on. Four clubs, six players, four gameweeks and nine
 * fixtures are enough to exercise every path the tools have: a completed season that the
 * {@link dev.quantti.fplassistant.tools.SeasonResolver} falls back to, an empty current
 * season alongside it (the production changeover situation, reproduced deliberately), a double
 * gameweek, unfinished fixtures with difficulty ratings, an injured player who must never be
 * recommended, and two clubs whose names share a prefix so the ambiguity note has something to
 * report.
 *
 * <p>Idempotent: every insert is {@code ON CONFLICT DO NOTHING}, so any number of test classes
 * can call {@link #ensure} in any order.
 */
public final class SharedStatsSeed {

    /** Holds the data. Completed, so tools must label it historical. */
    public static final int POPULATED_SEASON_ID = 9026;
    public static final String POPULATED_SEASON_CODE = "T-2025-26";

    /** Flagged current but empty — exactly what a real season looks like before its first sync. */
    public static final int CURRENT_SEASON_ID = 9027;
    public static final String CURRENT_SEASON_CODE = "T-2026-27";

    public static final int TEAM_ASHFIELD = 9001;
    public static final int TEAM_NORTHMOOR_CITY = 9002;
    public static final int TEAM_NORTHMOOR_UNITED = 9003;
    public static final int TEAM_SELBY = 9004;
    public static final int TEAM_WOODBRIDGE = 9005;

    public static final String ASHFIELD = "Ashfield Rovers";
    public static final String ASHFIELD_SHORT = "ASH";
    /** Matches both Northmoor clubs, so the tools' ambiguity note has to fire. */
    public static final String AMBIGUOUS_TEAM_PREFIX = "northmoor";
    /** In the league but with no fixtures at all, for the "nothing upcoming" branch. */
    public static final String TEAM_WITHOUT_FIXTURES = "Woodbridge";

    /** Attacking midfielder at Ashfield, with expected stats across three finished fixtures. */
    public static final String PLAYER_WEB_NAME = "Okafor";
    public static final String PLAYER_FULL_NAME = "Emeka Okafor";
    /** Low ownership and available: the only player who may appear in a MID differentials list. */
    public static final String DIFFERENTIAL_PLAYER = "Bramley";
    /** High form but injured: must never be recommended by any pick tool. */
    public static final String INJURED_PLAYER = "Halvorsen";

    /** Available, but with no per-fixture record — the pick tools cannot judge him at all. */
    public static final String PLAYER_WITHOUT_UNDERLYING = "Novak";

    /** Available and has a record, but far too little of it to carry a per-90 figure. */
    public static final String PLAYER_BELOW_MINUTES_FLOOR = "Halloran";

    /** Ashfield plays twice in gameweek 2, so a one-gameweek window returns two fixtures. */
    public static final int DOUBLE_GAMEWEEK = 2;

    private SharedStatsSeed() {
    }

    /** Shared capture-season parent for owned-sync staging tests; never changes current flags. */
    public static void ensureOwnedSyncCaptureSeason(JdbcTemplate jdbc) {
        ensure(jdbc);
        jdbc.update("""
                INSERT INTO season (id, code, name, start_date, is_current)
                VALUES (27, '2026-27', 'Owned sync capture test', DATE '2026-08-21', false)
                ON CONFLICT (id) DO NOTHING
                """);
        String code = jdbc.queryForObject("SELECT code FROM season WHERE id=27", String.class);
        java.sql.Date startDate = jdbc.queryForObject("SELECT start_date FROM season WHERE id=27", java.sql.Date.class);
        if (!"2026-27".equals(code) || !java.sql.Date.valueOf("2026-08-21").equals(startDate)) {
            throw new IllegalStateException("Owned-sync capture season id 27 must remain 2026-27 starting 2026-08-21; "
                    + "found code=" + code + ", start_date=" + startDate);
        }
    }

    /** Adds a PFS key outside the recorded catalogue so owned-sync discovery can test departed players. */
    public static void seedOwnedSyncKnownHistoryKey(JdbcTemplate jdbc, int seasonId, int fixtureId,
                                                     int gameweek, int playerId, int playerTeamId,
                                                     int opponentTeamId, boolean wasHome,
                                                     java.time.Instant kickoff) {
        ensureOwnedSyncCaptureSeason(jdbc);
        if (seasonId != 27 || fixtureId <= 0 || gameweek < 1 || gameweek > 38 || playerId <= 0
                || playerTeamId <= 0 || opponentTeamId <= 0 || playerTeamId == opponentTeamId) {
            throw new IllegalArgumentException("Invalid owned-sync known-history test key");
        }
        jdbc.update("""
                INSERT INTO player_fixture_stats(fixture_id, player_id, season_id, gameweek,
                    player_team_id, opponent_team_id, was_home, kickoff_time, value)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 45)
                ON CONFLICT (fixture_id, player_id, season_id) DO NOTHING
                """, fixtureId, playerId, seasonId, gameweek, playerTeamId, opponentTeamId,
                wasHome, java.sql.Timestamp.from(kickoff));
    }

    /** Seeds the SharedStats fixture identity mirror used by the owned-sync collector evidence test. */
    public static void seedOwnedSyncFixtureIdentity(JdbcTemplate jdbc, int seasonId,
                                                     List<TeamRow> teams, List<FixtureRow> fixtures) {
        ensureOwnedSyncCaptureSeason(jdbc);
        if (seasonId != 27 || teams.isEmpty() || fixtures.isEmpty()
                || teams.stream().anyMatch(team -> team.seasonId() != seasonId)
                || fixtures.stream().anyMatch(fixture -> fixture.seasonId() != seasonId)) {
            throw new IllegalArgumentException("Invalid owned-sync fixture identity test seed");
        }
        jdbc.batchUpdate("""
                INSERT INTO team(id,season_id,code,name,short_name,strength) VALUES (?,?,?,?,?,3)
                ON CONFLICT (id,season_id) DO UPDATE SET code=EXCLUDED.code,name=EXCLUDED.name,
                    short_name=EXCLUDED.short_name
                """, teams.stream().map(team -> new Object[] {team.id(), seasonId, team.code(),
                team.name(), team.shortName()}).toList());
        jdbc.batchUpdate("""
                INSERT INTO fixture(id,season_id,gameweek,code,team_h,team_a,team_h_score,team_a_score,
                    team_h_difficulty,team_a_difficulty,kickoff_time,started,finished,
                    finished_provisional,minutes,stats)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb)
                ON CONFLICT (id,season_id) DO UPDATE SET gameweek=EXCLUDED.gameweek,code=EXCLUDED.code,
                    team_h=EXCLUDED.team_h,team_a=EXCLUDED.team_a,team_h_score=EXCLUDED.team_h_score,
                    team_a_score=EXCLUDED.team_a_score,team_h_difficulty=EXCLUDED.team_h_difficulty,
                    team_a_difficulty=EXCLUDED.team_a_difficulty,kickoff_time=EXCLUDED.kickoff_time,
                    started=EXCLUDED.started,finished=EXCLUDED.finished,
                    finished_provisional=EXCLUDED.finished_provisional,minutes=EXCLUDED.minutes,
                    stats=EXCLUDED.stats
                """, fixtures.stream().map(fixture -> new Object[] {fixture.id(), seasonId,
                fixture.gameweek(), fixture.code(), fixture.homeTeamId(), fixture.awayTeamId(),
                fixture.homeScore(), fixture.awayScore(), fixture.homeDifficulty(), fixture.awayDifficulty(),
                fixture.kickoff() == null ? null : Timestamp.from(fixture.kickoff()), fixture.started(),
                fixture.finished(), fixture.fields().get("finished_provisional"), fixture.fields().get("minutes"),
                fixture.fields().get("stats")}).toList());
    }

    /** Selects one test season as current without assigning meaning to the real playing season. */
    public static void setCurrentSeason(JdbcTemplate jdbc, int seasonId) {
        ensure(jdbc);
        if (!Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM season WHERE id=?)", Boolean.class, seasonId))) {
            throw new IllegalArgumentException("Test season does not exist: " + seasonId);
        }
        jdbc.update("UPDATE season SET is_current=(id=?)", seasonId);
    }

    /** Dedicated canonical-code rows for owned-sync publication tests. */
    public static void ensureOwnedSyncFixture(JdbcTemplate jdbc) {
        ensure(jdbc);
        jdbc.execute("""
                INSERT INTO season (id, code, name, start_date, end_date, is_current) VALUES
                    (9825, '2024-25', 'Owned sync test season', DATE '2024-08-16', DATE '2025-05-25', false)
                ON CONFLICT (id) DO NOTHING;
                INSERT INTO team (id, season_id, code, name, short_name, strength) VALUES
                    (9801, 9825, 9801, 'Sync Home', 'SYH', 3),
                    (9802, 9825, 9802, 'Sync Away', 'SYA', 3)
                ON CONFLICT (id, season_id) DO NOTHING;
                INSERT INTO player (id, season_id, team_id, first_name, second_name, web_name,
                    element_type, now_cost, status) VALUES
                    (98201, 9825, 9801, 'Home', 'Sync', 'HomeSync', 3, 75, 'a'),
                    (98202, 9825, 9802, 'Away', 'Sync', 'AwaySync', 3, 75, 'a')
                ON CONFLICT (id, season_id) DO NOTHING;
                INSERT INTO fixture (id, season_id, gameweek, code, team_h, team_a, team_h_score,
                    team_a_score, kickoff_time, started, finished, finished_provisional, minutes, stats) VALUES
                    (98101, 9825, 1, 98101, 9801, 9802, 2, 1,
                     TIMESTAMPTZ '2024-08-17T14:00:00Z', true, true, true, 90,
                     '[{"identifier":"minutes","h":[{"element":98201,"value":90}],
                         "a":[{"element":98202,"value":90}]}]'::jsonb)
                ON CONFLICT (id, season_id) DO UPDATE SET gameweek=EXCLUDED.gameweek,
                    code=EXCLUDED.code, team_h=EXCLUDED.team_h, team_a=EXCLUDED.team_a,
                    team_h_score=EXCLUDED.team_h_score, team_a_score=EXCLUDED.team_a_score,
                    kickoff_time=EXCLUDED.kickoff_time, started=EXCLUDED.started,
                    finished=EXCLUDED.finished, finished_provisional=EXCLUDED.finished_provisional,
                    minutes=EXCLUDED.minutes, stats=EXCLUDED.stats
                """);
    }

    static boolean isDisposableTestDatabase(String url, String user, String password,
                                            String ci, String githubActions,
                                            String githubWorkflow, String githubJob) {
        if (!"postgres".equals(user) || !"postgres".equals(password)) return false;
        if ("jdbc:postgresql://localhost:55432/postgres".equals(url)
                || "jdbc:postgresql://127.0.0.1:55432/postgres".equals(url)) return true;
        return "jdbc:postgresql://localhost:5432/postgres".equals(url)
                && "true".equalsIgnoreCase(ci)
                && "true".equalsIgnoreCase(githubActions)
                && "test and deploy".equals(githubWorkflow)
                && "test".equals(githubJob);
    }

    /** Validates the configured target before a fixture constructs or uses JDBC. */
    public static JdbcTemplate openDisposableTestJdbc(String url, String user, String password,
                                                        Supplier<JdbcTemplate> jdbcSupplier) {
        requireDisposableTestDatabase(url, user, password, System.getenv("CI"), System.getenv("GITHUB_ACTIONS"),
                System.getenv("GITHUB_WORKFLOW"), System.getenv("GITHUB_JOB"));
        return jdbcSupplier.get();
    }

    /** Validates the configured target before a fixture constructs a JDBC datasource. */
    public static void requireDisposableTestDatabase() {
        requireDisposableTestDatabase(System.getenv("DATABASE_URL"), System.getenv("DB_USER"),
                System.getenv("DB_PASSWORD"), System.getenv("CI"), System.getenv("GITHUB_ACTIONS"),
                System.getenv("GITHUB_WORKFLOW"), System.getenv("GITHUB_JOB"));
    }

    public static void requireDisposableTestDatabase(String url, String user, String password,
                                                     String ci, String githubActions,
                                                     String githubWorkflow, String githubJob) {
        if (!isDisposableTestDatabase(url, user, password, ci, githubActions, githubWorkflow, githubJob)) {
            throw new IllegalStateException("Refusing to use outside the disposable test database; "
                    + "use backend/.env.test (localhost:55432) or the documented GitHub Actions test job");
        }
    }

    /**
     * Seeds the fixture league only on the local disposable test database. GitHub Actions uses
     * its documented job-scoped Postgres service on localhost:5432; local runs must use
     * backend/.env.test on localhost:55432. The manager-table check remains a second, content-based
     * fence against the shared SharedStats database.
     */
    public static void ensure(JdbcTemplate jdbc) {
        requireDisposableTestDatabase();
        String sharedOnlyTable = jdbc.queryForObject(
                "SELECT to_regclass('public.manager')::text", String.class);
        if (sharedOnlyTable != null) {
            throw new IllegalStateException("""
                    Refusing to seed: this database contains upstream-stats-app's own 'manager' table, \
                    so it is the shared production database, not an ephemeral test container. \
                    Point DATABASE_URL at a disposable Postgres (see docs/DEVELOPMENT.md).""");
        }

        jdbc.execute("""
                INSERT INTO season (id, code, name, start_date, end_date, is_current) VALUES
                    (9026, 'T-2025-26', 'Test season 2025-26', DATE '2025-08-15', DATE '2026-05-24', false),
                    (9027, 'T-2026-27', 'Test season 2026-27', DATE '2026-08-14', NULL, true)
                ON CONFLICT (id) DO NOTHING
                """);

        jdbc.execute("""
                INSERT INTO team (id, season_id, code, name, short_name, strength) VALUES
                    (9001, 9026, 9001, 'Ashfield Rovers',  'ASH', 4),
                    (9002, 9026, 9002, 'Northmoor City',   'NMC', 3),
                    (9003, 9026, 9003, 'Northmoor United', 'NMU', 3),
                    (9004, 9026, 9004, 'Selby Town',       'SEL', 2),
                    (9005, 9026, 9005, 'Woodbridge Athletic', 'WBA', 2)
                ON CONFLICT (id, season_id) DO NOTHING
                """);

        // GW2 is current and already played; GW3 is next. Both branches of the tools' start-week
        // fallback (current, else next, else MIN) therefore have something to resolve.
        jdbc.execute("""
                INSERT INTO gameweek (id, season_id, name, deadline_time, finished, data_checked,
                                      is_current, is_next, average_entry_score, highest_score,
                                      most_captained, most_transferred_in) VALUES
                    (1, 9026, 'Gameweek 1', TIMESTAMPTZ '2025-08-15 17:30:00+00', true,  true,  false, false, 52, 120, 9203, 9202),
                    (2, 9026, 'Gameweek 2', TIMESTAMPTZ '2025-08-22 17:30:00+00', true,  true,  true,  false, 58, 131, 9203, 9201),
                    (3, 9026, 'Gameweek 3', TIMESTAMPTZ '2025-08-29 17:30:00+00', false, false, false, true,  NULL, NULL, NULL, NULL),
                    (4, 9026, 'Gameweek 4', TIMESTAMPTZ '2025-09-12 17:30:00+00', false, false, false, false, NULL, NULL, NULL, NULL)
                ON CONFLICT (id, season_id) DO NOTHING
                """);

        jdbc.execute("""
                INSERT INTO player (id, season_id, team_id, first_name, second_name, web_name,
                                    element_type, now_cost, status, news, total_points, minutes,
                                    goals_scored, assists, form, selected_by_percent, points_per_game) VALUES
                    (9201, 9026, 9001, 'Emeka', 'Okafor',    'Okafor',    3, 75, 'a', NULL, 60, 265, 2, 1, 6.5, 12.50, 6.0),
                    (9202, 9026, 9002, 'Iker',  'Bramley',   'Bramley',   3, 55, 'a', NULL, 41, 270, 2, 0, 5.0,  3.20, 4.1),
                    (9203, 9026, 9001, 'Diego', 'Vasquez',   'Vasquez',   4, 96, 'a', NULL, 70, 253, 3, 0, 7.1, 25.00, 7.0),
                    (9204, 9026, 9003, 'Lars',  'Halvorsen', 'Halvorsen', 4, 84, 'i',
                        'Knee injury - expected back late September', 55, 180, 2, 1, 8.0, 2.00, 6.9),
                    (9205, 9026, 9003, 'Tiago', 'Ferreira',  'Ferreira',  2, 45, 'a', NULL, 38, 270, 0, 1, 4.0,  8.00, 3.8),
                    (9206, 9026, 9004, 'Piotr', 'Kaminski',  'Kaminski',  1, 45, 'a', NULL, 33, 270, 0, 0, 3.5,  6.00, 3.3),
                    -- No player_fixture_stats rows anywhere: a summer signing, as far as the
                    -- underlying data is concerned. The pick tools must count him out loud
                    -- rather than drop him, which would read as a player judged and rejected.
                    (9207, 9026, 9005, 'Ryan',  'Novak',     'Novak',     3, 60, 'a', NULL, 12,  45, 0, 0, 1.2, 50.00, 1.5),
                    -- One substitute appearance. He has a record, so a count of "players with no
                    -- rows" would miss him, while the minutes floor still drops him from the
                    -- ranking — the gap the exclusion count used to fall through.
                    (9208, 9026, 9003, 'Sean',  'Halloran',  'Halloran',  2, 45, 'a', NULL,  2,  45, 0, 0, 0.5,  4.00, 0.5)
                ON CONFLICT (id, season_id) DO NOTHING
                """);

        // 9101-9110 finished (gameweeks 1-2), 9105-9108 still to play (gameweeks 3-4) with
        // difficulty ratings, so the fixture-difficulty tool reaches its Average FDR branch.
        // Ashfield appears twice in gameweek 2 (9103 and 9110) — the double gameweek.
        jdbc.execute("""
                INSERT INTO fixture (id, season_id, gameweek, code, team_h, team_a,
                                     team_h_score, team_a_score, team_h_difficulty, team_a_difficulty,
                                     kickoff_time, started, finished) VALUES
                    (9101, 9026, 1, 9101, 9001, 9002, 2, 1, 2, 3, TIMESTAMPTZ '2025-08-16 14:00:00+00', true, true),
                    (9102, 9026, 1, 9102, 9003, 9004, 0, 0, 3, 3, TIMESTAMPTZ '2025-08-16 16:30:00+00', true, true),
                    (9103, 9026, 2, 9103, 9002, 9001, 1, 3, 4, 2, TIMESTAMPTZ '2025-08-23 14:00:00+00', true, true),
                    (9104, 9026, 2, 9104, 9004, 9003, 2, 2, 3, 3, TIMESTAMPTZ '2025-08-23 16:30:00+00', true, true),
                    (9110, 9026, 2, 9110, 9001, 9004, 0, 0, 2, 4, TIMESTAMPTZ '2025-08-24 14:00:00+00', true, true),
                    (9105, 9026, 3, 9105, 9001, 9003, NULL, NULL, 2, 4, TIMESTAMPTZ '2025-08-30 14:00:00+00', false, false),
                    (9106, 9026, 3, 9106, 9004, 9002, NULL, NULL, 3, 3, TIMESTAMPTZ '2025-08-30 16:30:00+00', false, false),
                    (9107, 9026, 4, 9107, 9002, 9001, NULL, NULL, 4, 2, TIMESTAMPTZ '2025-09-13 14:00:00+00', false, false),
                    (9108, 9026, 4, 9108, 9003, 9004, NULL, NULL, 3, 3, TIMESTAMPTZ '2025-09-13 16:30:00+00', false, false)
                ON CONFLICT (id, season_id) DO NOTHING
                """);

        // goals_conceded carries the whole team's figure on every player row, exactly as FPL
        // publishes it — that is why the tool aggregates it with MAX and not SUM, and why the
        // values below match each fixture's scoreline rather than being per-player.
        jdbc.execute("""
                INSERT INTO player_fixture_stats (fixture_id, player_id, season_id, gameweek,
                        player_team_id, opponent_team_id, was_home, kickoff_time, minutes,
                        total_points, goals_scored, assists, expected_goals, expected_assists,
                        expected_goal_involvements, clean_sheets, goals_conceded,
                        expected_goals_conceded, value) VALUES
                    (9101, 9201, 9026, 1, 9001, 9002, true,  TIMESTAMPTZ '2025-08-16 14:00:00+00', 90, 8, 1, 0, 0.55, 0.30, 0.85, 0, 1, 1.10, 75),
                    (9101, 9203, 9026, 1, 9001, 9002, true,  TIMESTAMPTZ '2025-08-16 14:00:00+00', 90, 7, 1, 0, 0.70, 0.10, 0.80, 0, 1, 1.10, 96),
                    (9101, 9202, 9026, 1, 9002, 9001, false, TIMESTAMPTZ '2025-08-16 14:00:00+00', 90, 6, 1, 0, 0.40, 0.05, 0.45, 0, 2, 1.60, 55),
                    (9103, 9201, 9026, 2, 9001, 9002, false, TIMESTAMPTZ '2025-08-23 14:00:00+00', 90, 10, 1, 1, 0.45, 0.35, 0.80, 0, 1, 0.90, 75),
                    (9103, 9203, 9026, 2, 9001, 9002, false, TIMESTAMPTZ '2025-08-23 14:00:00+00', 85, 13, 2, 0, 1.10, 0.05, 1.15, 0, 1, 0.90, 96),
                    (9103, 9202, 9026, 2, 9002, 9001, true,  TIMESTAMPTZ '2025-08-23 14:00:00+00', 90, 5, 1, 0, 0.35, 0.00, 0.35, 0, 3, 2.10, 55),
                    (9110, 9201, 9026, 2, 9001, 9004, true,  TIMESTAMPTZ '2025-08-24 14:00:00+00', 90, 3, 0, 0, 0.25, 0.20, 0.45, 1, 0, 0.60, 75),
                    (9110, 9203, 9026, 2, 9001, 9004, true,  TIMESTAMPTZ '2025-08-24 14:00:00+00', 78, 2, 0, 0, 0.40, 0.00, 0.40, 1, 0, 0.60, 96),
                    -- A defender and a goalkeeper, so the pick tools' defensive metric (expected
                    -- goals conceded per match) has something to rank. Selby keep the tighter
                    -- side of the two.
                    (9102, 9205, 9026, 1, 9003, 9004, true,  TIMESTAMPTZ '2025-08-16 16:30:00+00', 90, 6, 0, 0, 0.05, 0.10, 0.15, 1, 0, 0.80, 45),
                    (9104, 9205, 9026, 2, 9003, 9004, false, TIMESTAMPTZ '2025-08-23 16:30:00+00', 90, 2, 0, 1, 0.05, 0.20, 0.25, 0, 2, 1.70, 45),
                    (9102, 9206, 9026, 1, 9004, 9003, false, TIMESTAMPTZ '2025-08-16 16:30:00+00', 90, 6, 0, 0, 0.00, 0.00, 0.00, 1, 0, 0.60, 45),
                    (9104, 9206, 9026, 2, 9004, 9003, true,  TIMESTAMPTZ '2025-08-23 16:30:00+00', 90, 2, 0, 0, 0.00, 0.00, 0.00, 0, 2, 1.10, 45),
                    (9102, 9208, 9026, 1, 9003, 9004, true,  TIMESTAMPTZ '2025-08-16 16:30:00+00', 45, 2, 0, 0, 0.02, 0.03, 0.05, 0, 0, 0.80, 45)
                ON CONFLICT (fixture_id, player_id, season_id) DO NOTHING
                """);
    }
}
