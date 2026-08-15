package fi.karivanttinen.fplassistant.tools;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Phase 4 fixture-difficulty outlook tool. {@link #getFixtureDifficulty} is narrowly about a
 * team's upcoming FDR trend — it does not duplicate {@link FixtureTools#getUpcomingFixtures},
 * which carries scores, kickoff times and postponed handling. Every reply is prefixed with
 * {@link SeasonContext#provenance()}.
 */
@Component
public class FixtureDifficultyTools implements FplTool {

    private static final Logger log = LoggerFactory.getLogger(FixtureDifficultyTools.class);
    private static final int MAX_FIXTURES = 10;

    private final JdbcTemplate jdbc;
    private final SeasonResolver seasonResolver;

    public FixtureDifficultyTools(JdbcTemplate jdbc, SeasonResolver seasonResolver) {
        this.jdbc = jdbc;
        this.seasonResolver = seasonResolver;
    }

    @Tool(description = """
            Get a Premier League team's upcoming fixture difficulty outlook — the next N
            fixtures with their FDR rating (1-5, lower = easier), opponent, venue and kickoff.
            Use this for fixture-run questions where the difficulty trend matters more than
            scores or full fixture details. nextN is capped at 10. The reply begins with the
            season the fixtures come from; if that is not the season currently being played,
            tell the user the fixtures are historical.
            """)
    public String getFixtureDifficulty(String teamName, int nextN) {
        try {
            SeasonContext season = seasonResolver.resolve();
            int limit = Math.max(1, Math.min(nextN, MAX_FIXTURES));
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
            String shortName = (String) teams.getFirst().get("short_name");

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
                           f.kickoff_time, f.team_h = ? as is_home
                    FROM fixture f
                    JOIN team th ON th.id = f.team_h AND th.season_id = f.season_id
                    JOIN team ta ON ta.id = f.team_a AND ta.season_id = f.season_id
                    WHERE f.season_id = ?
                      AND (f.team_h = ? OR f.team_a = ?)
                      AND f.gameweek >= ?
                      AND f.finished = false
                    ORDER BY f.gameweek, f.kickoff_time
                    LIMIT ?
                    """, teamId, season.seasonId(), teamId, teamId, startGw, limit);

            if (rows.isEmpty()) {
                return season.provenance() + "\nNo upcoming fixtures found for " + name + " ("
                        + shortName + ") in " + season.code() + ".";
            }

            return season.provenance() + "\n\n" + format(name, shortName, rows);
        } catch (DataAccessException e) {
            log.error("getFixtureDifficulty(\"{}\", {}) failed", teamName, nextN, e);
            return "Fixture-difficulty data is temporarily unavailable (database error)."
                    + " Tell the user and suggest asking again shortly.";
        }
    }

    private String format(String name, String shortName, List<Map<String, Object>> rows) {
        StringBuilder out = new StringBuilder("Fixture difficulty for ").append(name)
                .append(" (").append(shortName).append("):\n");
        int fdrSum = 0;
        for (Map<String, Object> r : rows) {
            boolean isHome = Boolean.TRUE.equals(r.get("is_home"));
            String opponent = isHome ? (String) r.get("away_short") : (String) r.get("home_short");
            Object rawFdr = isHome ? r.get("team_h_difficulty") : r.get("team_a_difficulty");
            int fdr = rawFdr == null ? 0 : ((Number) rawFdr).intValue();
            fdrSum += fdr;
            String venue = isHome ? "(H)" : "(A)";
            String kickoff = Objects.toString(r.get("kickoff_time"), "kickoff TBD");
            out.append("  GW%s: vs %s %s | FDR %d | %s\n".formatted(
                    r.get("gameweek"), opponent, venue, fdr, kickoff));
        }
        out.append("\nAverage FDR over next %d fixtures: %.1f (lower = easier)\n"
                .formatted(rows.size(), (double) fdrSum / rows.size()));
        return out.toString();
    }
}
