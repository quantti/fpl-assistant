package dev.quantti.fplassistant.testsupport;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The season being played, as it looks in the first week: a gameweek, two clubs, a handful of
 * players with new element ids for last season's names, and two played fixtures so a player can
 * have matches this season. Nothing else, so the early-season path is exactly what the tools meet
 * in August.
 *
 * <p>Shared by the pick-tools and expected-stats cross-season tests. {@code CandidatePool}'s
 * cross-season test seeds its own, smaller version under different ids (gameweek 111, players
 * 9421-9422, fixtures 9121-9122) and is left alone — merging the two would be a refactor rather
 * than this work.
 *
 * <p>Ids are scoped so {@link #end} removes exactly these rows: the current season is shared-owned
 * and shared with {@link SharedStatsSeed}, and a blanket delete on {@code season_id} would wipe another
 * class's rows the day one of them seeds it.
 */
public final class NewSeasonSeed {

    public static final int GAMEWEEK = 101;
    public static final int ASHFIELD = 9001;
    public static final int NORTHMOOR_CITY = 9002;
    public static final int FERREIRA = 9403;
    public static final int FIXTURE_HOME = 9111;
    public static final int FIXTURE_AWAY = 9112;

    private NewSeasonSeed() {
    }

    public static void begin(JdbcTemplate jdbc) {
        jdbc.execute("""
                INSERT INTO gameweek (id, season_id, name, deadline_time, finished, data_checked,
                                      is_current, is_next) VALUES
                    (101, 9027, 'Gameweek 1', TIMESTAMPTZ '2026-08-21 17:30:00+00', true, true,
                     true, false)
                ON CONFLICT (id, season_id) DO NOTHING
                """);
        jdbc.execute("""
                INSERT INTO team (id, season_id, code, name, short_name, strength) VALUES
                    (9001, 9027, 9001, 'Ashfield Rovers', 'ASH', 4),
                    (9002, 9027, 9002, 'Northmoor City', 'NMC', 3)
                ON CONFLICT (id, season_id) DO NOTHING
                """);
        jdbc.execute("""
                INSERT INTO fixture (id, season_id, gameweek, code, team_h, team_a,
                                     team_h_score, team_a_score, team_h_difficulty,
                                     team_a_difficulty, kickoff_time, started, finished) VALUES
                    (9111, 9027, 101, 9111, 9001, 9002, 1, 1, 2, 3,
                        TIMESTAMPTZ '2026-08-15 14:00:00+00', true, true),
                    (9112, 9027, 101, 9112, 9002, 9001, 0, 0, 3, 2,
                        TIMESTAMPTZ '2026-08-16 14:00:00+00', true, true)
                ON CONFLICT (id, season_id) DO NOTHING
                """);
        jdbc.execute("""
                INSERT INTO player (id, season_id, team_id, first_name, second_name, web_name,
                                    element_type, now_cost, status, total_points, minutes,
                                    goals_scored, assists, form, selected_by_percent) VALUES
                    (9401, 9027, 9001, 'Emeka', 'Okafor',   'Okafor',   3, 80, 'a', 12, 90, 1, 0, 6.0,  4.0),
                    (9402, 9027, 9001, 'Diego', 'Vasquez',  'Vasquez',  4, 96, 'a', 15, 90, 2, 0, 7.5,  6.0),
                    (9403, 9027, 9001, 'Tiago', 'Ferreira', 'Ferreira', 2, 45, 'a',  6, 90, 0, 0, 3.0,  5.0),
                    -- Shares a web_name with a player who exists in last season's data too, so
                    -- the name bridge has two candidates and no way to tell them apart.
                    (9404, 9027, 9001, 'Nils',  'Rask',     'Rask',     3, 55, 'a',  4, 90, 0, 0, 2.0,  3.0),
                    (9405, 9027, 9001, 'Otto',  'Rask',     'Rask',     2, 45, 'a',  3, 90, 0, 0, 1.5,  2.0)
                ON CONFLICT (id, season_id) DO NOTHING
                """);
        jdbc.execute("""
                INSERT INTO player (id, season_id, team_id, first_name, second_name, web_name,
                                    element_type, now_cost, status, total_points, minutes,
                                    goals_scored, assists, form, selected_by_percent) VALUES
                    (9210, 9026, 9001, 'Nils', 'Rask', 'Rask', 3, 55, 'a', 30, 270, 1, 1, 4.0, 3.0)
                ON CONFLICT (id, season_id) DO NOTHING
                """);
    }

    /** Ferreira's two full matches this season: 0.25 xG in each, so 0.25 xG/90. */
    public static void giveFerreiraTwoMatches(JdbcTemplate jdbc) {
        jdbc.execute("""
                INSERT INTO player_fixture_stats (fixture_id, player_id, season_id, gameweek,
                        player_team_id, opponent_team_id, was_home, kickoff_time, minutes,
                        total_points, goals_scored, assists, expected_goals, expected_assists,
                        expected_goal_involvements, clean_sheets, goals_conceded,
                        expected_goals_conceded, value) VALUES
                    (9111, 9403, 9027, 101, 9001, 9002, true,  TIMESTAMPTZ '2026-08-15 14:00:00+00', 90, 2, 0, 0, 0.25, 0.00, 0.25, 0, 1, 1.20, 45),
                    (9112, 9403, 9027, 101, 9001, 9002, false, TIMESTAMPTZ '2026-08-16 14:00:00+00', 90, 6, 0, 0, 0.25, 0.00, 0.25, 1, 0, 0.80, 45)
                ON CONFLICT (fixture_id, player_id, season_id) DO NOTHING
                """);
    }

    public static void end(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM player_fixture_stats WHERE season_id = 9027"
                + " AND fixture_id IN (9111, 9112)");
        jdbc.update("DELETE FROM fixture WHERE season_id = 9027 AND id IN (9111, 9112)");
        jdbc.update("DELETE FROM player WHERE season_id = 9027 AND id BETWEEN 9401 AND 9410");
        jdbc.update("DELETE FROM player WHERE season_id = 9026 AND id = 9210");
        jdbc.update("DELETE FROM gameweek WHERE season_id = 9027 AND id = 101");
        jdbc.update("DELETE FROM team WHERE season_id = 9027 AND id IN (9001, 9002)");
    }
}
