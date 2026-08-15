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
 * Phase 4 pick-advice tools. {@link #getDifferentialPicks} and {@link #getCaptainCandidates}
 * combine ownership, form and points from the shared {@code player} table with the average
 * fixture-difficulty rating (FDR) of each player's next N fixtures, then order by a simple
 * composite. These give the model data to cite alongside RAG strategy docs — they do not
 * replace strategy advice. Every reply is prefixed with {@link SeasonContext#provenance()}.
 */
@Component
public class PickTools implements FplTool {

    private static final Logger log = LoggerFactory.getLogger(PickTools.class);
    private static final int MAX_PICKS = 10;
    private static final int MAX_FIXTURES = 5;

    private final JdbcTemplate jdbc;
    private final SeasonResolver seasonResolver;

    public PickTools(JdbcTemplate jdbc, SeasonResolver seasonResolver) {
        this.jdbc = jdbc;
        this.seasonResolver = seasonResolver;
    }

    @Tool(description = """
            Suggest differential FPL picks — players with low ownership (≤ maxOwnership
            percent) who have reasonable form and favourable upcoming fixtures. Filters by
            position (1=GK, 2=DEF, 3=MID, 4=FWD, or "any"). Returns up to 10 players ordered by
            form, then fixture ease, then ownership. Use for differential/pick questions and
            combine with strategy docs. The reply begins with the season the numbers come from.
            """)
    public String getDifferentialPicks(String position, double maxOwnership, int nextN) {
        try {
            SeasonContext season = seasonResolver.resolve();
            Integer elementType = parsePosition(position);
            int fixtureLimit = Math.max(1, Math.min(nextN, MAX_FIXTURES));

            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT p.web_name, p.element_type, p.now_cost, p.selected_by_percent,
                           p.form, p.total_points, p.team_id,
                           t.short_name as team_short
                    FROM player p JOIN team t ON t.id = p.team_id AND t.season_id = p.season_id
                    WHERE p.season_id = ?
                      AND p.selected_by_percent <= ?
                      AND p.form > 0
                      AND (? = 0 OR p.element_type = ?)
                    ORDER BY p.form DESC, p.selected_by_percent ASC, p.total_points DESC
                    LIMIT 60
                    """, season.seasonId(), maxOwnership, elementType, elementType);

            if (rows.isEmpty()) {
                return season.provenance() + "\nNo differentials match those filters in "
                        + season.code() + ".";
            }

            return season.provenance() + "\n\n" + formatPicks("Differential picks", rows, season, fixtureLimit);
        } catch (DataAccessException e) {
            log.error("getDifferentialPicks(\"{}\", {}, {}) failed", position, maxOwnership, nextN, e);
            return "Pick data is temporarily unavailable (database error)."
                    + " Tell the user and suggest asking again shortly.";
        }
    }

    @Tool(description = """
            Suggest FPL captain candidates — high-form attackers and midfielders with
            favourable upcoming fixtures. Returns up to 10 players with ownership (so the user
            can see template vs differential captains), recent points, form, and the average
            FDR of their next N fixtures. Use for captaincy questions and combine with strategy
            docs. The reply begins with the season the numbers come from.
            """)
    public String getCaptainCandidates(int nextN) {
        try {
            SeasonContext season = seasonResolver.resolve();
            int fixtureLimit = Math.max(1, Math.min(nextN, MAX_FIXTURES));

            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT p.web_name, p.element_type, p.now_cost, p.selected_by_percent,
                           p.form, p.total_points, p.team_id,
                           t.short_name as team_short
                    FROM player p JOIN team t ON t.id = p.team_id AND t.season_id = p.season_id
                    WHERE p.season_id = ?
                      AND p.element_type IN (3, 4)
                      AND p.form > 0
                    ORDER BY p.form DESC, p.total_points DESC
                    LIMIT 60
                    """, season.seasonId());

            if (rows.isEmpty()) {
                return season.provenance() + "\nNo captain candidates with form yet in "
                        + season.code() + ".";
            }

            return season.provenance() + "\n\n" + formatPicks("Captain candidates", rows, season, fixtureLimit);
        } catch (DataAccessException e) {
            log.error("getCaptainCandidates({}) failed", nextN, e);
            return "Pick data is temporarily unavailable (database error)."
                    + " Tell the user and suggest asking again shortly.";
        }
    }

    private String formatPicks(String heading, List<Map<String, Object>> rows, SeasonContext season, int fixtureLimit) {
        Integer startGw = startGameweek(season.seasonId());
        StringBuilder out = new StringBuilder(heading).append(" (").append(season.code()).append("):\n");
        int shown = 0;
        for (Map<String, Object> r : rows) {
            if (shown >= MAX_PICKS) {
                break;
            }
            int teamId = ((Number) r.get("team_id")).intValue();
            Double avgFdr = avgFdr(season.seasonId(), teamId, startGw, fixtureLimit);
            String pos = positionLabel(((Number) r.get("element_type")).intValue());
            String fdr = avgFdr == null ? "n/a" : String.format("%.1f", avgFdr);
            out.append("  %s %s (%s) £%.1fm | Own %s%% | Form %s | Pts %s | Avg FDR %s\n".formatted(
                    pos, r.get("web_name"), r.get("team_short"),
                    ((Number) r.get("now_cost")).intValue() / 10.0,
                    r.get("selected_by_percent"), r.get("form"), r.get("total_points"), fdr));
            shown++;
        }
        if (startGw == null) {
            out.append("\n(No upcoming gameweek found — FDR is n/a. Fixtures may not be scheduled yet.)\n");
        }
        return out.toString();
    }

    /** Average FDR of the team's next N fixtures (by gameweek), or null if none scheduled. */
    private Double avgFdr(int seasonId, int teamId, Integer startGw, int fixtureLimit) {
        if (startGw == null) {
            return null;
        }
        try {
            return jdbc.queryForObject("""
                    SELECT AVG(CASE WHEN team_h = ? THEN team_h_difficulty ELSE team_a_difficulty END)
                    FROM (
                        SELECT team_h, team_a, team_h_difficulty, team_a_difficulty, gameweek
                        FROM fixture
                        WHERE season_id = ? AND (team_h = ? OR team_a = ?)
                          AND gameweek >= ? AND finished = false
                        ORDER BY gameweek, kickoff_time
                        LIMIT ?
                    ) next_fixtures
                    """, Double.class, teamId, seasonId, teamId, teamId, startGw, fixtureLimit);
        } catch (DataAccessException e) {
            return null;
        }
    }

    /** The gameweek to start counting from: current, else next, else GW1 — same fallback as FixtureTools. */
    private Integer startGameweek(int seasonId) {
        try {
            return jdbc.queryForObject("""
                    SELECT COALESCE(
                        (SELECT id FROM gameweek WHERE season_id = ? AND is_current = true ORDER BY id LIMIT 1),
                        (SELECT id FROM gameweek WHERE season_id = ? AND is_next = true ORDER BY id LIMIT 1),
                        (SELECT MIN(id) FROM gameweek WHERE season_id = ?))
                    """, Integer.class, seasonId, seasonId, seasonId);
        } catch (DataAccessException e) {
            return null;
        }
    }

    /** @return 1–4 for a valid position, or 0 for "any"/unknown (a sentinel the SQL treats as "no filter"). */
    private static Integer parsePosition(String position) {
        if (position == null || position.isBlank() || position.equalsIgnoreCase("any")) {
            return 0;
        }
        String p = position.trim().toLowerCase(Locale.ROOT);
        return switch (p) {
            case "1", "gk" -> 1;
            case "2", "def" -> 2;
            case "3", "mid" -> 3;
            case "4", "fwd" -> 4;
            default -> 0; // unknown -> treat as "any" rather than matching nothing
        };
    }

    private static String positionLabel(int elementType) {
        return switch (elementType) {
            case 1 -> "GK";
            case 2 -> "DEF";
            case 3 -> "MID";
            case 4 -> "FWD";
            default -> "?";
        };
    }
}
