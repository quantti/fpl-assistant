package dev.quantti.fplassistant.tools;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Phase 4 expected-stats (xG / xA / xGI) tools. Both read FPL's own expected-stats columns in
 * {@code player_fixture_stats} — these are FPL's data, not a third-party model — and aggregate
 * over the last N fixtures in the resolved season. Every reply is prefixed with
 * {@link SeasonContext#provenance()}.
 */
@Component
public class ExpectedStatsTools implements FplTool {

    private static final Logger log = LoggerFactory.getLogger(ExpectedStatsTools.class);
    private static final int MAX_FIXTURES = 10;

    private final JdbcTemplate jdbc;
    private final SeasonResolver seasonResolver;

    public ExpectedStatsTools(JdbcTemplate jdbc, SeasonResolver seasonResolver) {
        this.jdbc = jdbc;
        this.seasonResolver = seasonResolver;
    }

    @Tool(description = """
            Get a player's underlying expected stats over their last N fixtures in the
            current season — expected goals (xG), expected assists (xA), expected goal
            involvements (xGI), alongside actual goals, assists and minutes. Use this to
            judge whether a player is over- or under-performing their underlying numbers,
            not just their raw goal/assist counts. N is capped at 10. The reply begins with
            the season the numbers come from; if that is not the season currently being
            played, tell the user the figures are historical.
            """)
    public String getPlayerExpectedStats(String playerName, int lastN) {
        try {
            SeasonContext season = seasonResolver.resolve();
            int limit = Math.max(1, Math.min(lastN, MAX_FIXTURES));
            String pattern = "%" + LikePatterns.escape(playerName.toLowerCase(Locale.ROOT)) + "%";

            List<Map<String, Object>> players = jdbc.queryForList("""
                    SELECT id, web_name FROM player
                    WHERE season_id = ?
                      AND (LOWER(web_name) LIKE ?
                           OR LOWER(second_name) LIKE ?
                           OR LOWER(first_name || ' ' || second_name) LIKE ?)
                    ORDER BY total_points DESC
                    LIMIT 1
                    """, season.seasonId(), pattern, pattern, pattern);

            if (players.isEmpty()) {
                return season.provenance() + "\nPlayer not found: " + playerName;
            }

            int playerId = ((Number) players.getFirst().get("id")).intValue();
            String webName = (String) players.getFirst().get("web_name");

            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT gameweek, opponent_team_id, was_home, minutes,
                           goals_scored, assists,
                           expected_goals, expected_assists, expected_goal_involvements
                    FROM player_fixture_stats
                    WHERE season_id = ? AND player_id = ?
                    ORDER BY kickoff_time DESC NULLS LAST
                    LIMIT ?
                    """, season.seasonId(), playerId, limit);

            if (rows.isEmpty()) {
                return season.provenance() + "\nNo fixture-level expected stats yet for " + webName
                        + " in " + season.code() + ".";
            }

            return season.provenance() + "\n\n" + formatPlayer(webName, rows, season.seasonId());
        } catch (DataAccessException e) {
            log.error("getPlayerExpectedStats(\"{}\", {}) failed", playerName, lastN, e);
            return "Expected-stats data is temporarily unavailable (database error)."
                    + " Tell the user and suggest asking again shortly.";
        }
    }

    @Tool(description = """
            Get a team's attacking expected output over their last N fixtures in the
            current season — expected goals (xG), expected assists (xA) and expected goal
            involvements (xGI) aggregated across the team's players per fixture, plus actual
            goals. Use this to judge whether a team's attack is threatening regardless of
            whether goals have actually gone in. N is capped at 10. The reply begins with the
            season the numbers come from; if that is not the season currently being played,
            tell the user the figures are historical.
            """)
    public String getTeamExpectedGoals(String teamName, int lastN) {
        try {
            SeasonContext season = seasonResolver.resolve();
            int limit = Math.max(1, Math.min(lastN, MAX_FIXTURES));
            String pattern = "%" + LikePatterns.escape(teamName.toLowerCase(Locale.ROOT)) + "%";

            List<Map<String, Object>> teams = jdbc.queryForList("""
                    SELECT id, name, short_name FROM team
                    WHERE season_id = ?
                      AND (LOWER(name) LIKE ? OR LOWER(short_name) = LOWER(?))
                    ORDER BY name
                    LIMIT 1
                    """, season.seasonId(), pattern, teamName);

            if (teams.isEmpty()) {
                return season.provenance() + "\nTeam not found: " + teamName;
            }

            int teamId = ((Number) teams.getFirst().get("id")).intValue();
            String name = (String) teams.getFirst().get("name");

            // Last N distinct fixtures the team played, newest first.
            List<Map<String, Object>> fixtures = jdbc.queryForList("""
                    SELECT fixture_id, gameweek, opponent_team_id, was_home
                    FROM player_fixture_stats
                    WHERE season_id = ? AND player_team_id = ?
                    GROUP BY fixture_id, gameweek, opponent_team_id, was_home, kickoff_time
                    ORDER BY kickoff_time DESC NULLS LAST
                    LIMIT ?
                    """, season.seasonId(), teamId, limit);

            if (fixtures.isEmpty()) {
                return season.provenance() + "\nNo fixture-level expected stats yet for " + name
                        + " in " + season.code() + ".";
            }

            return season.provenance() + "\n\n" + formatTeam(name, teamId, fixtures, season.seasonId());
        } catch (DataAccessException e) {
            log.error("getTeamExpectedGoals(\"{}\", {}) failed", teamName, lastN, e);
            return "Expected-stats data is temporarily unavailable (database error)."
                    + " Tell the user and suggest asking again shortly.";
        }
    }

    private String formatPlayer(String webName, List<Map<String, Object>> rows, int seasonId) {
        double xg = 0, xa = 0, xgi = 0;
        int goals = 0, assists = 0, minutes = 0;
        StringBuilder perFixture = new StringBuilder("Expected stats for ").append(webName)
                .append(" (last ").append(rows.size()).append(" fixtures):\n");
        for (Map<String, Object> r : rows) {
            xg += num(r, "expected_goals");
            xa += num(r, "expected_assists");
            xgi += num(r, "expected_goal_involvements");
            goals += ((Number) r.get("goals_scored")).intValue();
            assists += ((Number) r.get("assists")).intValue();
            minutes += ((Number) r.get("minutes")).intValue();
            perFixture.append("  GW%s vs %s %s: %d min, xG %.2f xA %.2f (G%d A%d)\n".formatted(
                    r.get("gameweek"),
                    opponentName(((Number) r.get("opponent_team_id")).intValue(), seasonId),
                    Boolean.TRUE.equals(r.get("was_home")) ? "(H)" : "(A)",
                    ((Number) r.get("minutes")).intValue(),
                    num(r, "expected_goals"), num(r, "expected_assists"),
                    ((Number) r.get("goals_scored")).intValue(),
                    ((Number) r.get("assists")).intValue()));
        }
        perFixture.append("\nTotals over window: xG %.2f | xA %.2f | xGI %.2f | Goals %d | Assists %d | Minutes %d\n"
                .formatted(xg, xa, xgi, goals, assists, minutes));
        perFixture.append("Actual vs expected goal involvements: %d actual vs %.2f expected.\n"
                .formatted(goals + assists, xgi));
        return perFixture.toString();
    }

    private String formatTeam(String name, int teamId, List<Map<String, Object>> fixtures, int seasonId) {
        double totalXg = 0, totalXa = 0, totalXgi = 0;
        int totalGoals = 0;
        StringBuilder perFixture = new StringBuilder("Attacking expected output for ").append(name)
                .append(" (last ").append(fixtures.size()).append(" fixtures):\n");
        for (Map<String, Object> f : fixtures) {
            int fixtureId = ((Number) f.get("fixture_id")).intValue();
            Map<String, Object> agg = jdbc.queryForMap("""
                    SELECT COALESCE(SUM(expected_goals),0) as xg,
                           COALESCE(SUM(expected_assists),0) as xa,
                           COALESCE(SUM(expected_goal_involvements),0) as xgi,
                           COALESCE(SUM(goals_scored),0) as goals
                    FROM player_fixture_stats
                    WHERE season_id = ? AND fixture_id = ? AND player_team_id = ?
                    """, seasonId, fixtureId, teamId);
            double xg = num(agg, "xg");
            double xa = num(agg, "xa");
            double xgi = num(agg, "xgi");
            int goals = ((Number) agg.get("goals")).intValue();
            totalXg += xg;
            totalXa += xa;
            totalXgi += xgi;
            totalGoals += goals;
            perFixture.append("  GW%s vs %s %s: xG %.2f xA %.2f xGI %.2f (G%d)\n".formatted(
                    f.get("gameweek"),
                    opponentName(((Number) f.get("opponent_team_id")).intValue(), seasonId),
                    Boolean.TRUE.equals(f.get("was_home")) ? "(H)" : "(A)",
                    xg, xa, xgi, goals));
        }
        perFixture.append("\nTotals over window: xG %.2f | xA %.2f | xGI %.2f | Goals %d\n"
                .formatted(totalXg, totalXa, totalXgi, totalGoals));
        return perFixture.toString();
    }

    private String opponentName(int opponentTeamId, int seasonId) {
        try {
            return jdbc.queryForObject(
                    "SELECT short_name FROM team WHERE id = ? AND season_id = ?",
                    String.class, opponentTeamId, seasonId);
        } catch (DataAccessException e) {
            return "?";
        }
    }

    private static double num(Map<String, Object> r, String key) {
        Object v = r.get(key);
        return v == null ? 0.0 : ((Number) v).doubleValue();
    }
}
