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
 * Phase 4 fixture-difficulty outlook tool. {@link #getFixtureDifficulty} is narrowly about a
 * team's upcoming FDR trend — it does not duplicate {@link FixtureTools#getUpcomingFixtures},
 * which carries scores and full fixture details. Every reply is prefixed with
 * {@link SeasonContext#provenance()}.
 *
 * <p>Postponed fixtures ({@code gameweek IS NULL}) are listed separately rather than dropped, as
 * {@code FixtureTools} does: a fixture with no gameweek fails the {@code gameweek >= start}
 * bound, and silently omitting it makes a rearranged run look shorter than it is.
 */
@Component
public class FixtureDifficultyTools implements FplTool {

    private static final Logger log = LoggerFactory.getLogger(FixtureDifficultyTools.class);
    private static final int MAX_FIXTURES = 10;
    private static final int MAX_NAME_MATCHES = 4;

    private final JdbcTemplate jdbc;
    private final SeasonResolver seasonResolver;

    public FixtureDifficultyTools(JdbcTemplate jdbc, SeasonResolver seasonResolver) {
        this.jdbc = jdbc;
        this.seasonResolver = seasonResolver;
    }

    @Tool(description = """
            Get a Premier League team's upcoming fixture difficulty outlook — the next N
            fixtures with their FDR rating (1-5, lower = easier), opponent, venue and kickoff
            time in UK time, plus the average FDR over the run. Postponed fixtures with no
            gameweek assigned are listed separately. Use this for fixture-run questions where
            the difficulty trend matters more than scores or full fixture details. nextN is
            capped at 10. The reply begins with the season the fixtures come from; if that is
            not the season currently being played, tell the user the fixtures are historical.
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
                    LIMIT ?
                    """, season.seasonId(), pattern, teamName, MAX_NAME_MATCHES);

            if (teams.isEmpty()) {
                return season.provenance() + "\nTeam not found: " + teamName;
            }

            int teamId = ((Number) teams.getFirst().get("id")).intValue();
            String name = (String) teams.getFirst().get("name");
            String shortName = (String) teams.getFirst().get("short_name");
            String ambiguity = NameMatch.ambiguityNote(
                    teams.stream().map(t -> String.valueOf(t.get("name"))).toList());

            Integer startGw = jdbc.queryForObject("""
                    SELECT COALESCE(
                        (SELECT id FROM gameweek WHERE season_id = ? AND is_current = true ORDER BY id LIMIT 1),
                        (SELECT id FROM gameweek WHERE season_id = ? AND is_next = true ORDER BY id LIMIT 1),
                        (SELECT MIN(id) FROM gameweek WHERE season_id = ?))
                    """, Integer.class, season.seasonId(), season.seasonId(), season.seasonId());

            if (startGw == null) {
                return season.provenance() + "\nNo gameweeks are scheduled yet for " + season.code()
                        + ", so there is no fixture-difficulty outlook for " + name + " ("
                        + shortName + ") to report." + ambiguity;
            }

            List<Map<String, Object>> scheduled = jdbc.queryForList("""
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

            List<Map<String, Object>> postponed = jdbc.queryForList("""
                    SELECT th.short_name as home_short, ta.short_name as away_short,
                           f.team_h_difficulty, f.team_a_difficulty,
                           f.team_h = ? as is_home
                    FROM fixture f
                    JOIN team th ON th.id = f.team_h AND th.season_id = f.season_id
                    JOIN team ta ON ta.id = f.team_a AND ta.season_id = f.season_id
                    WHERE f.season_id = ?
                      AND (f.team_h = ? OR f.team_a = ?)
                      AND f.gameweek IS NULL
                      AND f.finished = false
                    ORDER BY th.short_name
                    """, teamId, season.seasonId(), teamId, teamId);

            if (scheduled.isEmpty() && postponed.isEmpty()) {
                return season.provenance() + "\nNo upcoming fixtures found for " + name + " ("
                        + shortName + ") in " + season.code() + "." + ambiguity;
            }

            return season.provenance() + "\n\n" + format(name, shortName, scheduled, postponed) + ambiguity;
        } catch (DataAccessException e) {
            log.error("getFixtureDifficulty(\"{}\", {}) failed", teamName, nextN, e);
            return "Fixture-difficulty data is temporarily unavailable (database error)."
                    + " Tell the user and suggest asking again shortly.";
        }
    }

    /**
     * Package-private and free of database access so the formatting can be unit-tested: no
     * season in the database has unfinished fixtures during the changeover, so an integration
     * test can only ever reach the empty path.
     */
    static String format(String name, String shortName,
                         List<Map<String, Object>> scheduled, List<Map<String, Object>> postponed) {
        StringBuilder out = new StringBuilder("Fixture difficulty for ").append(name)
                .append(" (").append(shortName).append("):\n");

        int fdrSum = 0;
        int rated = 0;
        for (Map<String, Object> r : scheduled) {
            boolean isHome = Boolean.TRUE.equals(r.get("is_home"));
            Integer fdr = fdr(r, isHome);
            if (fdr != null) {
                fdrSum += fdr;
                rated++;
            }
            out.append("  GW%s: vs %s %s | FDR %s | %s\n".formatted(
                    r.get("gameweek"), opponent(r, isHome), isHome ? "(H)" : "(A)",
                    fdr == null ? "unknown" : fdr.toString(),
                    kickoff(r.get("kickoff_time"))));
        }

        if (rated > 0) {
            out.append("\nAverage FDR over the next %d rated fixture(s): %.1f (lower = easier)\n"
                    .formatted(rated, (double) fdrSum / rated));
            if (rated < scheduled.size()) {
                out.append("(%d fixture(s) carry no FDR rating yet and are excluded from the average.)\n"
                        .formatted(scheduled.size() - rated));
            }
        } else if (!scheduled.isEmpty()) {
            out.append("\nNone of these fixtures carry an FDR rating yet, so no average can be given.\n");
        }

        if (!postponed.isEmpty()) {
            out.append("\nPostponed / not yet assigned to a gameweek:\n");
            for (Map<String, Object> r : postponed) {
                boolean isHome = Boolean.TRUE.equals(r.get("is_home"));
                Integer fdr = fdr(r, isHome);
                out.append("  vs %s %s | FDR %s | date to be confirmed\n".formatted(
                        opponent(r, isHome), isHome ? "(H)" : "(A)",
                        fdr == null ? "unknown" : fdr.toString()));
            }
        }
        return out.toString();
    }

    private static String opponent(Map<String, Object> row, boolean isHome) {
        Object opponent = isHome ? row.get("away_short") : row.get("home_short");
        return opponent == null ? "?" : opponent.toString();
    }

    /** @return the rating from the team's own side of the fixture, or null when unrated */
    private static Integer fdr(Map<String, Object> row, boolean isHome) {
        Object raw = isHome ? row.get("team_h_difficulty") : row.get("team_a_difficulty");
        return raw == null ? null : ((Number) raw).intValue();
    }

    /** Rendered in UK time and labelled: the container runs UTC, so an unlabelled hour misleads. */
    private static String kickoff(Object kickoffTime) {
        return UkTime.format(kickoffTime, "kickoff TBD");
    }
}
