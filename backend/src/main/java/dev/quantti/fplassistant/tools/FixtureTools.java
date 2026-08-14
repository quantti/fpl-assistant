package dev.quantti.fplassistant.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Component
public class FixtureTools {

    private static final Logger log = LoggerFactory.getLogger(FixtureTools.class);

    private final JdbcTemplate jdbc;
    private final SeasonResolver seasonResolver;

    public FixtureTools(JdbcTemplate jdbc, SeasonResolver seasonResolver) {
        this.jdbc = jdbc;
        this.seasonResolver = seasonResolver;
    }

    @Tool(description = """
            Get upcoming fixtures for a Premier League team by name or short name
            (e.g. "Arsenal", "ARS", "Manchester City", "MCI").
            Returns fixture difficulty rating (FDR 1-5, lower = easier), kickoff time,
            and home/away status. nextN controls how many gameweeks to include (max 10);
            a double gameweek counts as one gameweek with multiple fixtures. Fixtures
            postponed without a rescheduled gameweek are listed separately.
            The reply begins with the season the fixtures come from; if that is not the
            season currently being played, tell the user the fixtures are historical.
            """)
    public String getUpcomingFixtures(String teamName, int nextN) {
        try {
            SeasonContext season = seasonResolver.resolve();
            int gwLimit = Math.max(1, Math.min(nextN, 10));
            String pattern = "%" + LikePatterns.escape(teamName.toLowerCase(Locale.ROOT)) + "%";

            List<Map<String, Object>> teams = jdbc.queryForList("""
                    SELECT id, name, short_name FROM team
                    WHERE season_id = ?
                      AND (LOWER(name) LIKE ? OR LOWER(short_name) = LOWER(?))
                    LIMIT 1
                    """, season.seasonId(), pattern, teamName);

            if (teams.isEmpty()) {
                return season.provenance() + "\nTeam not found: " + teamName;
            }

            int teamId = ((Number) teams.getFirst().get("id")).intValue();
            String resolvedName = (String) teams.getFirst().get("name");
            String resolvedShort = (String) teams.getFirst().get("short_name");

            // No gameweek is flagged current between seasons — a bare `>= (SELECT ...)` would
            // compare against NULL and silently drop every row. Fall back to is_next, then GW1.
            Integer startGw = jdbc.queryForObject("""
                    SELECT COALESCE(
                        (SELECT id FROM gameweek WHERE season_id = ? AND is_current = true ORDER BY id LIMIT 1),
                        (SELECT id FROM gameweek WHERE season_id = ? AND is_next = true ORDER BY id LIMIT 1),
                        (SELECT MIN(id) FROM gameweek WHERE season_id = ?))
                    """, Integer.class, season.seasonId(), season.seasonId(), season.seasonId());

            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT f.gameweek,
                           th.short_name as home_short, ta.short_name as away_short,
                           f.team_h_difficulty, f.team_a_difficulty,
                           f.kickoff_time, f.finished,
                           f.team_h_score, f.team_a_score,
                           f.team_h = ? as is_home
                    FROM fixture f
                    JOIN team th ON th.id = f.team_h AND th.season_id = f.season_id
                    JOIN team ta ON ta.id = f.team_a AND ta.season_id = f.season_id
                    WHERE f.season_id = ?
                      AND (f.team_h = ? OR f.team_a = ?)
                      AND (f.gameweek >= ? OR (f.gameweek IS NULL AND f.finished = false))
                    ORDER BY f.gameweek NULLS LAST, f.kickoff_time
                    """, teamId, season.seasonId(), teamId, teamId, startGw);

            // Bound by distinct gameweeks, not fixture rows: a double gameweek is two rows in
            // one round, and "next 5 gameweeks" must always mean five rounds.
            List<String> scheduled = new ArrayList<>();
            List<String> postponed = new ArrayList<>();
            Set<Object> seenGameweeks = new LinkedHashSet<>();
            for (Map<String, Object> r : rows) {
                Object gw = r.get("gameweek");
                if (gw == null) {
                    postponed.add(formatFixture(r));
                } else if (seenGameweeks.contains(gw) || seenGameweeks.size() < gwLimit) {
                    seenGameweeks.add(gw);
                    scheduled.add(formatFixture(r));
                }
            }

            if (scheduled.isEmpty() && postponed.isEmpty()) {
                return season.provenance() + "\nNo upcoming fixtures found for " + resolvedName;
            }

            StringBuilder out = new StringBuilder(season.provenance())
                    .append("\n\nUpcoming fixtures for %s (%s):\n".formatted(resolvedName, resolvedShort))
                    .append(String.join("\n", scheduled));
            if (!postponed.isEmpty()) {
                out.append("\nPostponed, no rescheduled gameweek yet:\n")
                        .append(String.join("\n", postponed));
            }
            return out.toString();
        } catch (DataAccessException e) {
            log.error("getUpcomingFixtures(\"{}\", {}) failed", teamName, nextN, e);
            return "Fixture data is temporarily unavailable (database error)."
                    + " Tell the user and suggest asking again shortly.";
        }
    }

    private String formatFixture(Map<String, Object> r) {
        boolean isHome = Boolean.TRUE.equals(r.get("is_home"));
        String opponent = isHome ? (String) r.get("away_short") : (String) r.get("home_short");
        int fdr = isHome
                ? ((Number) r.get("team_h_difficulty")).intValue()
                : ((Number) r.get("team_a_difficulty")).intValue();
        String venue = isHome ? "(H)" : "(A)";
        String score = r.get("team_h_score") != null
                ? " [%s-%s]".formatted(r.get("team_h_score"), r.get("team_a_score"))
                : "";
        String gwLabel = r.get("gameweek") == null ? "Postponed" : "GW" + r.get("gameweek");

        return "%s: vs %s %s | FDR: %d | %s%s".formatted(
                gwLabel, opponent, venue, fdr,
                Objects.toString(r.get("kickoff_time"), "kickoff TBD"), score);
    }
}
