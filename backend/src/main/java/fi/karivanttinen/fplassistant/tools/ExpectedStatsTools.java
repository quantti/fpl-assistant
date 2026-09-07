package fi.karivanttinen.fplassistant.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Phase 4 expected-stats (xG / xA / xGI / xGA) tools. Both read FPL's own expected-stats columns
 * in {@code player_fixture_stats} — these are FPL's data, not a third-party model — and aggregate
 * over the last N fixtures in the resolved season. Every reply is prefixed with
 * {@link SeasonContext#provenance()}.
 *
 * <p>Each tool answers from a single query. {@code expected_goals}/{@code expected_assists} are
 * per-player and sum across a team; {@code expected_goals_conceded} and {@code goals_conceded}
 * are <b>not</b> — FPL records the team's figure on every player who was on the pitch, so summing
 * them would multiply the team's xGA by its squad size. They are aggregated with {@code MAX},
 * which is the value carried by anyone who played the full match. Verified against the
 * {@code fixture} scoreline: {@code MAX(goals_conceded)} matches the goals the team actually
 * conceded in every checked fixture.
 */
@Component
public class ExpectedStatsTools implements FplTool {

    private static final Logger log = LoggerFactory.getLogger(ExpectedStatsTools.class);
    private static final int MAX_FIXTURES = 10;
    private static final int MAX_NAME_MATCHES = 4;

    private final JdbcTemplate jdbc;
    private final SeasonResolver seasonResolver;

    public ExpectedStatsTools(JdbcTemplate jdbc, SeasonResolver seasonResolver) {
        this.jdbc = jdbc;
        this.seasonResolver = seasonResolver;
    }

    @Tool(description = """
            Get a player's underlying expected stats over their last N fixtures this season,
            filled from last season when this season has fewer than N — every row names its
            season — expected goals (xG), expected assists (xA), expected goal
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
                    LIMIT ?
                    """, season.seasonId(), pattern, pattern, pattern, MAX_NAME_MATCHES);

            if (players.isEmpty()) {
                return season.provenance() + "\nPlayer not found: " + playerName;
            }

            int playerId = ((Number) players.getFirst().get("id")).intValue();
            String webName = (String) players.getFirst().get("web_name");
            String ambiguity = NameMatch.ambiguityNote(
                    players.stream().map(p -> String.valueOf(p.get("web_name"))).toList());

            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT pfs.gameweek, pfs.was_home, pfs.minutes,
                           pfs.goals_scored, pfs.assists,
                           pfs.expected_goals, pfs.expected_assists, pfs.expected_goal_involvements,
                           opp.short_name AS opponent
                    FROM player_fixture_stats pfs
                    LEFT JOIN team opp ON opp.id = pfs.opponent_team_id AND opp.season_id = pfs.season_id
                    WHERE pfs.season_id = ? AND pfs.player_id = ?
                    ORDER BY pfs.kickoff_time DESC NULLS LAST
                    LIMIT ?
                    """, season.seasonId(), playerId, limit);

            List<Map<String, Object>> shown = new ArrayList<>(rows);
            for (Map<String, Object> r : shown) {
                r.put("season_code", season.code());
            }
            Optional<SeasonContext> previous = Optional.empty();
            int fromPrevious = 0;
            if (season.currentSeason() && shown.size() < limit) {
                previous = seasonResolver.previousSeason(season);
                if (previous.isPresent()) {
                    List<Map<String, Object>> older = previousSeasonPlayerRows(
                            previous.get(), season.seasonId(), webName, limit - shown.size());
                    fromPrevious = older.size();
                    shown.addAll(older);
                }
            }

            if (shown.isEmpty()) {
                return season.provenance() + "\nNo fixture-level expected stats yet for " + webName
                        + " in " + season.code() + "." + ambiguity;
            }

            String header = season.provenance() + (fromPrevious > 0
                    ? "\n" + previous.orElseThrow().previousSeasonProvenance(true) : "");
            return header + "\n\n"
                    + formatPlayer(webName, shown, season.code(), fromPrevious) + ambiguity;
        } catch (DataAccessException e) {
            log.error("getPlayerExpectedStats(\"{}\", {}) failed", playerName, lastN, e);
            return "Expected-stats data is temporarily unavailable (database error)."
                    + " Tell the user and suggest asking again shortly.";
        }
    }

    @Tool(description = """
            Get a team's expected output over their last N fixtures this season, filled from
            last season when this season has fewer than N — every row names its season —
            attacking expected goals (xG), expected assists (xA) and expected goal
            involvements (xGI) aggregated across the team's players per fixture, plus actual
            goals scored, and defensively the expected goals against (xGA) and actual goals
            conceded per fixture. Use this to judge whether a team's attack is threatening
            regardless of whether goals have gone in, and whether its defence is under
            sustained pressure (xGA is the clean-sheet signal for its defenders and keeper).
            N is capped at 10. The reply begins with the season the numbers come from; if that
            is not the season currently being played, tell the user the figures are historical.
            """)
    public String getTeamExpectedGoals(String teamName, int lastN) {
        try {
            SeasonContext season = seasonResolver.resolve();
            int limit = Math.max(1, Math.min(lastN, MAX_FIXTURES));
            String pattern = "%" + LikePatterns.escape(teamName.toLowerCase(Locale.ROOT)) + "%";

            List<Map<String, Object>> teams = jdbc.queryForList("""
                    SELECT id, name FROM team
                    WHERE season_id = ?
                      AND (LOWER(name) LIKE ? OR LOWER(short_name) = LOWER(?))
                    ORDER BY name
                    LIMIT ?
                    """, season.seasonId(), pattern, teamName, MAX_NAME_MATCHES);

            if (teams.isEmpty()) {
                return season.provenance() + "\nTeam not found: " + teamName;
            }

            int teamId = ((Number) teams.getFirst().get("id")).intValue();
            String name = (String) teams.getFirst().get("name");
            String ambiguity = NameMatch.ambiguityNote(
                    teams.stream().map(t -> String.valueOf(t.get("name"))).toList());

            // One query for the whole window: attacking figures sum across the team's players,
            // the conceded figures are per-team values repeated on every player (see class javadoc).
            List<Map<String, Object>> fixtures = jdbc.queryForList("""
                    SELECT pfs.gameweek, pfs.was_home,
                           opp.short_name AS opponent,
                           COALESCE(SUM(pfs.expected_goals), 0) AS xg,
                           COALESCE(SUM(pfs.expected_assists), 0) AS xa,
                           COALESCE(SUM(pfs.expected_goal_involvements), 0) AS xgi,
                           COALESCE(SUM(pfs.goals_scored), 0) AS goals,
                           COALESCE(MAX(pfs.expected_goals_conceded), 0) AS xga,
                           COALESCE(MAX(pfs.goals_conceded), 0) AS conceded
                    FROM player_fixture_stats pfs
                    LEFT JOIN team opp ON opp.id = pfs.opponent_team_id AND opp.season_id = pfs.season_id
                    WHERE pfs.season_id = ? AND pfs.player_team_id = ?
                    GROUP BY pfs.fixture_id, pfs.gameweek, pfs.was_home, opp.short_name
                    ORDER BY MAX(pfs.kickoff_time) DESC NULLS LAST
                    LIMIT ?
                    """, season.seasonId(), teamId, limit);

            List<Map<String, Object>> shown = new ArrayList<>(fixtures);
            for (Map<String, Object> f : shown) {
                f.put("season_code", season.code());
            }
            Optional<SeasonContext> previous = Optional.empty();
            int fromPrevious = 0;
            if (season.currentSeason() && shown.size() < limit) {
                previous = seasonResolver.previousSeason(season);
                if (previous.isPresent()) {
                    List<Map<String, Object>> older = previousSeasonTeamRows(
                            previous.get(), name, limit - shown.size());
                    fromPrevious = older.size();
                    shown.addAll(older);
                }
            }

            if (shown.isEmpty()) {
                return season.provenance() + "\nNo fixture-level expected stats yet for " + name
                        + " in " + season.code() + "." + ambiguity;
            }

            String header = season.provenance() + (fromPrevious > 0
                    ? "\n" + previous.orElseThrow().previousSeasonProvenance(true) : "");
            return header + "\n\n"
                    + formatTeam(name, shown, season.code(), fromPrevious) + ambiguity;
        } catch (DataAccessException e) {
            log.error("getTeamExpectedGoals(\"{}\", {}) failed", teamName, lastN, e);
            return "Expected-stats data is temporarily unavailable (database error)."
                    + " Tell the user and suggest asking again shortly.";
        }
    }

    /**
     * Last season's fixtures for the same name, only when that name is unique in BOTH seasons —
     * the same bridge the ranking tools use, for the same reason: element ids are reassigned
     * every summer and a guessed match attaches another player's football to this player's card.
     * One player of the name last season and two this season still leaves unknown which of the
     * two he became, so that fill would be a guess either way round.
     */
    private List<Map<String, Object>> previousSeasonPlayerRows(SeasonContext previous,
                                                               int currentSeasonId,
                                                               String webName, int limit) {
        Integer twins = jdbc.queryForObject("""
                SELECT count(*) FROM player WHERE season_id = ? AND LOWER(web_name) = LOWER(?)
                """, Integer.class, currentSeasonId, webName);
        if (twins == null || twins != 1) {
            return List.of();
        }
        List<Integer> ids = jdbc.queryForList("""
                SELECT id FROM player WHERE season_id = ? AND LOWER(web_name) = LOWER(?)
                """, Integer.class, previous.seasonId(), webName);
        if (ids.size() != 1) {
            return List.of();
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT pfs.gameweek, pfs.was_home, pfs.minutes,
                       pfs.goals_scored, pfs.assists,
                       pfs.expected_goals, pfs.expected_assists, pfs.expected_goal_involvements,
                       opp.short_name AS opponent
                FROM player_fixture_stats pfs
                LEFT JOIN team opp ON opp.id = pfs.opponent_team_id AND opp.season_id = pfs.season_id
                WHERE pfs.season_id = ? AND pfs.player_id = ?
                ORDER BY pfs.kickoff_time DESC NULLS LAST
                LIMIT ?
                """, previous.seasonId(), ids.getFirst(), limit);
        for (Map<String, Object> r : rows) {
            r.put("season_code", previous.code());
        }
        return rows;
    }

    /** The same fill for a club, matched on its name in the previous season. */
    private List<Map<String, Object>> previousSeasonTeamRows(SeasonContext previous, String name,
                                                             int limit) {
        List<Integer> ids = jdbc.queryForList("""
                SELECT id FROM team WHERE season_id = ? AND LOWER(name) = LOWER(?)
                """, Integer.class, previous.seasonId(), name);
        if (ids.size() != 1) {
            return List.of();
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT pfs.gameweek, pfs.was_home,
                       opp.short_name AS opponent,
                       COALESCE(SUM(pfs.expected_goals), 0) AS xg,
                       COALESCE(SUM(pfs.expected_assists), 0) AS xa,
                       COALESCE(SUM(pfs.expected_goal_involvements), 0) AS xgi,
                       COALESCE(SUM(pfs.goals_scored), 0) AS goals,
                       COALESCE(MAX(pfs.expected_goals_conceded), 0) AS xga,
                       COALESCE(MAX(pfs.goals_conceded), 0) AS conceded
                FROM player_fixture_stats pfs
                LEFT JOIN team opp ON opp.id = pfs.opponent_team_id AND opp.season_id = pfs.season_id
                WHERE pfs.season_id = ? AND pfs.player_team_id = ?
                GROUP BY pfs.fixture_id, pfs.gameweek, pfs.was_home, opp.short_name
                ORDER BY MAX(pfs.kickoff_time) DESC NULLS LAST
                LIMIT ?
                """, previous.seasonId(), ids.getFirst(), limit);
        for (Map<String, Object> r : rows) {
            r.put("season_code", previous.code());
        }
        return rows;
    }

    /** How many fixtures came from which season, for the heading. */
    private static String windowLabel(List<Map<String, Object>> rows, String currentCode,
                                      int fromPrevious) {
        if (fromPrevious <= 0) {
            return "";
        }
        String previousCode = String.valueOf(rows.getLast().get("season_code"));
        return ": %d from %s, %d from %s".formatted(
                rows.size() - fromPrevious, currentCode, fromPrevious, previousCode);
    }

    /**
     * The row's season, printed only when the reply actually mixes two. A single-season window
     * needs no prefix — the banner above already names the season, and repeating it on every
     * line is noise the model has to read past.
     */
    private static String seasonPrefix(Map<String, Object> row, int fromPrevious) {
        Object code = row.get("season_code");
        return fromPrevious <= 0 || code == null ? "" : code + " ";
    }

    static String formatPlayer(String webName, List<Map<String, Object>> rows, String currentCode,
                               int fromPrevious) {
        double xg = 0, xa = 0, xgi = 0;
        int goals = 0, assists = 0, minutes = 0;
        StringBuilder out = new StringBuilder("Expected stats for ").append(webName)
                .append(" (last ").append(rows.size()).append(" fixtures")
                .append(windowLabel(rows, currentCode, fromPrevious)).append("):\n");
        for (Map<String, Object> r : rows) {
            xg += num(r, "expected_goals");
            xa += num(r, "expected_assists");
            xgi += num(r, "expected_goal_involvements");
            goals += (int) num(r, "goals_scored");
            assists += (int) num(r, "assists");
            minutes += (int) num(r, "minutes");
            out.append("  %sGW%s vs %s %s: %d min, xG %.2f xA %.2f (G%d A%d)\n".formatted(
                    seasonPrefix(r, fromPrevious), r.get("gameweek"), opponent(r),
                    Boolean.TRUE.equals(r.get("was_home")) ? "(H)" : "(A)",
                    (int) num(r, "minutes"),
                    num(r, "expected_goals"), num(r, "expected_assists"),
                    (int) num(r, "goals_scored"), (int) num(r, "assists")));
        }
        out.append("\nTotals over window: xG %.2f | xA %.2f | xGI %.2f | Goals %d | Assists %d | Minutes %d\n"
                .formatted(xg, xa, xgi, goals, assists, minutes));
        out.append("Actual vs expected goal involvements: %d actual vs %.2f expected.\n"
                .formatted(goals + assists, xgi));
        return out.toString();
    }

    static String formatTeam(String name, List<Map<String, Object>> fixtures,
                             String currentCode, int fromPrevious) {
        double totalXg = 0, totalXa = 0, totalXgi = 0, totalXga = 0;
        int totalGoals = 0, totalConceded = 0;
        StringBuilder out = new StringBuilder("Expected output for ").append(name)
                .append(" (last ").append(fixtures.size()).append(" fixtures")
                .append(windowLabel(fixtures, currentCode, fromPrevious)).append("):\n");
        for (Map<String, Object> f : fixtures) {
            double xg = num(f, "xg");
            double xa = num(f, "xa");
            double xgi = num(f, "xgi");
            double xga = num(f, "xga");
            int goals = (int) num(f, "goals");
            int conceded = (int) num(f, "conceded");
            totalXg += xg;
            totalXa += xa;
            totalXgi += xgi;
            totalXga += xga;
            totalGoals += goals;
            totalConceded += conceded;
            out.append("  %sGW%s vs %s %s: xG %.2f xA %.2f xGI %.2f (scored %d) | xGA %.2f (conceded %d)\n"
                    .formatted(seasonPrefix(f, fromPrevious), f.get("gameweek"), opponent(f),
                            Boolean.TRUE.equals(f.get("was_home")) ? "(H)" : "(A)",
                            xg, xa, xgi, goals, xga, conceded));
        }
        out.append("\nAttack over window: xG %.2f | xA %.2f | xGI %.2f | Goals %d\n"
                .formatted(totalXg, totalXa, totalXgi, totalGoals));
        out.append("Defence over window: xGA %.2f | Goals conceded %d\n"
                .formatted(totalXga, totalConceded));
        return out.toString();
    }

    /** A team that played in a season the {@code team} table no longer covers joins to null. */
    private static String opponent(Map<String, Object> row) {
        Object opponent = row.get("opponent");
        return opponent == null ? "?" : opponent.toString();
    }

    private static double num(Map<String, Object> r, String key) {
        Object v = r.get(key);
        return v == null ? 0.0 : ((Number) v).doubleValue();
    }
}
