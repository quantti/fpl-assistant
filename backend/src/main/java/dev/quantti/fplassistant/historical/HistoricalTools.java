package dev.quantti.fplassistant.historical;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import dev.quantti.fplassistant.tools.FplTool;
import dev.quantti.fplassistant.tools.UkTime;

/**
 * Historical match results spanning multiple seasons (2019-20 onward, backfilled from
 * vaastav, kept current via the football-data.org sync).
 *
 * <p>Deliberately <b>not</b> season-scoped: unlike every other tool in this codebase, these do
 * not filter through {@code SeasonResolver} and do not prefix with {@code SeasonContext.provenance()}
 * — spanning seasons is the point. Instead every match names its own season, and the reply
 * states the range covered, so a 2019/20 result never reads as recent. This is the third
 * instance of the same discipline database rows and RAG chunks already follow (see
 * {@code AGENTS.md}'s conventions).
 */
@Component
public class HistoricalTools implements FplTool {

    private static final Logger log = LoggerFactory.getLogger(HistoricalTools.class);
    private static final int MAX_RESULTS = 20;

    private final JdbcTemplate jdbc;
    private final HistoricalTeamResolver teamResolver;
    private final String tableName;

    public HistoricalTools(JdbcTemplate jdbc, HistoricalTeamResolver teamResolver,
                           @Value("${fpl.historical.table-name}") String tableName) {
        this.jdbc = jdbc;
        this.teamResolver = teamResolver;
        this.tableName = tableName;
    }

    @Tool(description = """
            Get a club's recent completed match results across multiple Premier League
            seasons (2019-20 onward) — opponent, venue, score, outcome. Use for questions
            about a team's actual recent form or history, not the upcoming schedule (use the
            fixtures tool for that). Results span seasons, so treat older matches as evidence
            about the club's tendencies, not a description of its current form.
            """)
    public String getTeamForm(String teamName, int lastN) {
        try {
            TeamIdentity team = teamResolver.resolve(teamName);
            if (team == null) {
                return "Team not found: " + teamName;
            }
            int limit = Math.max(1, Math.min(lastN, MAX_RESULTS));
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT season, gameweek, home_team_name, away_team_name,
                           home_goals, away_goals, kickoff_time,
                           home_team_code = ? AS is_home
                    FROM %s
                    WHERE home_team_code = ? OR away_team_code = ?
                    ORDER BY kickoff_time DESC NULLS LAST
                    LIMIT ?
                    """.formatted(tableName), // tableName is config-injected, not user input
                    team.code(), team.code(), team.code(), limit);

            if (rows.isEmpty()) {
                return "No historical results found for " + team.name() + ".";
            }

            String header = "Recent results for %s (%d matches, spanning multiple seasons — "
                    .formatted(team.name(), rows.size())
                    + "older results describe tendencies, not current form):\n";
            String body = rows.stream().map(this::formatResult).collect(Collectors.joining("\n"));
            String freshness = "\nMost recent match known: " + mostRecent(rows) + ".";
            return header + body + freshness;
        } catch (DataAccessException e) {
            log.error("getTeamForm(\"{}\", {}) failed", teamName, lastN, e);
            return "Historical match data is temporarily unavailable (database error)."
                    + " Tell the user and suggest asking again shortly.";
        }
    }

    @Tool(description = """
            Get past meetings between two Premier League clubs across multiple seasons
            (2019-20 onward) — head-to-head history. Order of the two team names does not
            matter. Results span seasons; attribute each result to its season rather than
            implying it is recent.
            """)
    public String getHeadToHead(String teamA, String teamB, int limit) {
        try {
            TeamIdentity a = teamResolver.resolve(teamA);
            if (a == null) {
                return "Team not found: " + teamA;
            }
            TeamIdentity b = teamResolver.resolve(teamB);
            if (b == null) {
                return "Team not found: " + teamB;
            }
            int cap = Math.max(1, Math.min(limit, MAX_RESULTS));
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT season, gameweek, home_team_name, away_team_name,
                           home_goals, away_goals, kickoff_time,
                           home_team_code = ? AS is_home
                    FROM %s
                    WHERE (home_team_code = ? AND away_team_code = ?)
                       OR (home_team_code = ? AND away_team_code = ?)
                    ORDER BY kickoff_time DESC NULLS LAST
                    LIMIT ?
                    """.formatted(tableName), // tableName is config-injected, not user input
                    a.code(), a.code(), b.code(), b.code(), a.code(), cap);

            if (rows.isEmpty()) {
                return "No historical meetings found between " + a.name() + " and " + b.name() + ".";
            }

            String header = "Head-to-head, %s vs %s (%d meetings, spanning multiple seasons):\n"
                    .formatted(a.name(), b.name(), rows.size());
            String body = rows.stream().map(this::formatResult).collect(Collectors.joining("\n"));
            return header + body;
        } catch (DataAccessException e) {
            log.error("getHeadToHead(\"{}\", \"{}\", {}) failed", teamA, teamB, limit, e);
            return "Historical match data is temporarily unavailable (database error)."
                    + " Tell the user and suggest asking again shortly.";
        }
    }

    private String formatResult(Map<String, Object> r) {
        boolean isHome = Boolean.TRUE.equals(r.get("is_home"));
        String team = isHome ? (String) r.get("home_team_name") : (String) r.get("away_team_name");
        String opponent = isHome ? (String) r.get("away_team_name") : (String) r.get("home_team_name");
        Object teamGoals = isHome ? r.get("home_goals") : r.get("away_goals");
        Object oppGoals = isHome ? r.get("away_goals") : r.get("home_goals");
        String outcome = outcome(((Number) teamGoals).intValue(), ((Number) oppGoals).intValue());
        Object gw = r.get("gameweek");
        String gwLabel = gw == null ? "" : " GW" + gw;
        String kickoffLabel = UkTime.format(r.get("kickoff_time"), "kickoff unknown");

        return "%s%s: %s %s vs %s %s | %s-%s (%s) | %s".formatted(
                r.get("season"), gwLabel, team, isHome ? "(H)" : "(A)", opponent,
                isHome ? "(A)" : "(H)", teamGoals, oppGoals, outcome, kickoffLabel);
    }

    static String outcome(int forGoals, int againstGoals) {
        if (forGoals > againstGoals) {
            return "W";
        }
        return forGoals < againstGoals ? "L" : "D";
    }

    private static String mostRecent(List<Map<String, Object>> rows) {
        return rows.stream()
                .map(r -> r.get("kickoff_time"))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .map(ts -> UkTime.format(ts, "unknown"))
                .orElse("unknown (no kickoff time recorded)");
    }
}
