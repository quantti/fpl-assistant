package fi.karivanttinen.fplassistant.tools;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
 * combine ownership, form and points from the tapas {@code player} table with the average
 * fixture-difficulty rating (FDR) of each player's next N fixtures, then rank on a composite of
 * all three. These give the model data to cite alongside RAG strategy docs — they do not replace
 * strategy advice. Every reply is prefixed with {@link SeasonContext#provenance()}.
 *
 * <p>Both tools exclude players who are injured, suspended or otherwise out ({@code status} not
 * in {@code a}/{@code d}) and label the doubtful ones with their news line. Recommending a player
 * who cannot play is worse than recommending nobody, and {@code form} is a rolling average that
 * stays high for weeks after an injury.
 *
 * <p>The FDR of every team is fetched in one query and reused across the candidate pool, rather
 * than one query per player: a pool of 60 candidates shares at most 20 clubs.
 */
@Component
public class PickTools implements FplTool {

    private static final Logger log = LoggerFactory.getLogger(PickTools.class);
    private static final int MAX_PICKS = 10;
    private static final int MAX_FIXTURES = 5;
    private static final int CANDIDATE_POOL = 60;

    /** Sinks players whose fixtures are unknown below any player with a real FDR. */
    private static final double UNKNOWN_FDR = Double.MAX_VALUE;

    private final JdbcTemplate jdbc;
    private final SeasonResolver seasonResolver;

    public PickTools(JdbcTemplate jdbc, SeasonResolver seasonResolver) {
        this.jdbc = jdbc;
        this.seasonResolver = seasonResolver;
    }

    @Tool(description = """
            Suggest differential FPL picks — players with low ownership (≤ maxOwnership
            percent) who have reasonable form and favourable upcoming fixtures. Filters by
            position (1=GK, 2=DEF, 3=MID, 4=FWD, or "any"). Returns up to 10 players ranked by
            form, then by the average difficulty of their next N fixtures, then by ownership.
            Players who are injured, suspended or otherwise unavailable are excluded; doubtful
            players are included and flagged. Use for differential/pick questions and combine
            with strategy docs. The reply begins with the season the numbers come from.
            """)
    public String getDifferentialPicks(String position, double maxOwnership, int nextN) {
        try {
            SeasonContext season = seasonResolver.resolve();
            Integer elementType = parsePosition(position);
            if (elementType == null) {
                return season.provenance() + "\nUnrecognised position \"" + position
                        + "\". Use 1/GK, 2/DEF, 3/MID, 4/FWD, or \"any\".";
            }
            int fixtureLimit = Math.max(1, Math.min(nextN, MAX_FIXTURES));

            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT p.web_name, p.element_type, p.now_cost, p.selected_by_percent,
                           p.form, p.total_points, p.team_id, p.status, p.news,
                           t.short_name as team_short
                    FROM player p JOIN team t ON t.id = p.team_id AND t.season_id = p.season_id
                    WHERE p.season_id = ?
                      AND p.selected_by_percent <= ?
                      AND p.form > 0
                      AND p.status IN ('a', 'd')
                      AND (? = 0 OR p.element_type = ?)
                    ORDER BY p.form DESC, p.selected_by_percent ASC, p.total_points DESC
                    LIMIT %d
                    """.formatted(CANDIDATE_POOL),
                    season.seasonId(), maxOwnership, elementType, elementType);

            if (rows.isEmpty()) {
                return season.provenance() + "\nNo differentials match those filters in "
                        + season.code() + " (players who are injured or suspended are excluded).";
            }

            return season.provenance() + "\n\n"
                    + formatPicks("Differential picks", rows, season, fixtureLimit, true);
        } catch (DataAccessException e) {
            log.error("getDifferentialPicks(\"{}\", {}, {}) failed", position, maxOwnership, nextN, e);
            return "Pick data is temporarily unavailable (database error)."
                    + " Tell the user and suggest asking again shortly.";
        }
    }

    @Tool(description = """
            Suggest FPL captain candidates — high-form attackers and midfielders with
            favourable upcoming fixtures. Returns up to 10 players ranked by form, then by the
            average difficulty of their next N fixtures, then by total points, each shown with
            ownership (so the user can see template vs differential captains). Players who are
            injured, suspended or otherwise unavailable are excluded; doubtful players are
            included and flagged. Use for captaincy questions and combine with strategy docs.
            The reply begins with the season the numbers come from.
            """)
    public String getCaptainCandidates(int nextN) {
        try {
            SeasonContext season = seasonResolver.resolve();
            int fixtureLimit = Math.max(1, Math.min(nextN, MAX_FIXTURES));

            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT p.web_name, p.element_type, p.now_cost, p.selected_by_percent,
                           p.form, p.total_points, p.team_id, p.status, p.news,
                           t.short_name as team_short
                    FROM player p JOIN team t ON t.id = p.team_id AND t.season_id = p.season_id
                    WHERE p.season_id = ?
                      AND p.element_type IN (3, 4)
                      AND p.form > 0
                      AND p.status IN ('a', 'd')
                    ORDER BY p.form DESC, p.total_points DESC
                    LIMIT %d
                    """.formatted(CANDIDATE_POOL), season.seasonId());

            if (rows.isEmpty()) {
                return season.provenance() + "\nNo captain candidates with form yet in "
                        + season.code() + " (players who are injured or suspended are excluded).";
            }

            return season.provenance() + "\n\n"
                    + formatPicks("Captain candidates", rows, season, fixtureLimit, false);
        } catch (DataAccessException e) {
            log.error("getCaptainCandidates({}) failed", nextN, e);
            return "Pick data is temporarily unavailable (database error)."
                    + " Tell the user and suggest asking again shortly.";
        }
    }

    private String formatPicks(String heading, List<Map<String, Object>> rows, SeasonContext season,
                               int fixtureLimit, boolean ownershipTieBreak) {
        Integer startGw = startGameweek(season.seasonId());
        Map<Integer, Double> fdrByTeam = avgFdrByTeam(season.seasonId(), startGw, fixtureLimit);
        List<Map<String, Object>> ranked = rank(rows, fdrByTeam, ownershipTieBreak);

        StringBuilder out = new StringBuilder(heading).append(" (").append(season.code()).append("):\n");
        String qual = season.currentSeason() ? "" : " (" + season.code() + ")";
        for (Map<String, Object> r : ranked.subList(0, Math.min(MAX_PICKS, ranked.size()))) {
            int teamId = ((Number) r.get("team_id")).intValue();
            Double avgFdr = fdrByTeam.get(teamId);
            out.append("  %s %s (%s) £%.1fm%s | Own %s%%%s | Form %s | Pts %s | Avg FDR %s%s\n".formatted(
                    positionLabel(((Number) r.get("element_type")).intValue()),
                    r.get("web_name"), r.get("team_short"),
                    ((Number) r.get("now_cost")).intValue() / 10.0, qual,
                    r.get("selected_by_percent"), qual,
                    r.get("form"), r.get("total_points"),
                    avgFdr == null ? "n/a" : String.format(Locale.ROOT, "%.1f", avgFdr),
                    availability(r)));
        }
        if (fdrByTeam.isEmpty()) {
            out.append("\n(No upcoming fixtures found — FDR is n/a and the ranking falls back to"
                    + " form. Fixtures for this season may not be scheduled yet.)\n");
        }
        out.append("\nRanked by form, then average FDR of the next ").append(fixtureLimit)
                .append(" fixtures, then ")
                .append(ownershipTieBreak ? "ownership" : "total points")
                .append(". Unavailable players (injured/suspended) are excluded.\n");
        return out.toString();
    }

    /**
     * Orders the candidate pool on the composite the tool descriptions promise. Done in Java
     * rather than SQL because the FDR is not a column — it is an aggregate over each team's
     * next N fixtures, resolved once per team in {@link #avgFdrByTeam}.
     */
    static List<Map<String, Object>> rank(List<Map<String, Object>> rows,
                                          Map<Integer, Double> fdrByTeam,
                                          boolean ownershipTieBreak) {
        Comparator<Map<String, Object>> byForm =
                Comparator.comparingDouble((Map<String, Object> r) -> numeric(r.get("form"))).reversed();
        Comparator<Map<String, Object>> byFdr = Comparator.comparingDouble(r -> {
            Double fdr = fdrByTeam.get(((Number) r.get("team_id")).intValue());
            return fdr == null ? UNKNOWN_FDR : fdr;
        });
        Comparator<Map<String, Object>> tieBreak = ownershipTieBreak
                ? Comparator.comparingDouble(r -> numeric(r.get("selected_by_percent")))
                : Comparator.comparingDouble((Map<String, Object> r) -> numeric(r.get("total_points"))).reversed();

        List<Map<String, Object>> sorted = new ArrayList<>(rows);
        sorted.sort(byForm.thenComparing(byFdr).thenComparing(tieBreak));
        return sorted;
    }

    /** Available players carry no marker; doubtful ones carry their news so the model can relay it. */
    private static String availability(Map<String, Object> r) {
        if (!"d".equals(String.valueOf(r.get("status")))) {
            return "";
        }
        Object news = r.get("news");
        String detail = (news == null || news.toString().isBlank()) ? "" : ": " + news;
        return " | DOUBTFUL" + detail;
    }

    /**
     * Average FDR of each team's next N fixtures, for every team at once. One query for the whole
     * candidate pool — a pool of 60 players shares at most 20 clubs, and asking per player made
     * the same request up to ten times.
     *
     * @return team id -> average FDR; a team with no upcoming fixtures is simply absent
     */
    private Map<Integer, Double> avgFdrByTeam(int seasonId, Integer startGw, int fixtureLimit) {
        Map<Integer, Double> byTeam = new HashMap<>();
        if (startGw == null) {
            return byTeam;
        }
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT team_id, AVG(fdr) AS avg_fdr
                    FROM (
                        SELECT team_id, fdr,
                               ROW_NUMBER() OVER (PARTITION BY team_id ORDER BY gameweek, kickoff_time) AS rn
                        FROM (
                            SELECT team_h AS team_id, team_h_difficulty AS fdr, gameweek, kickoff_time
                            FROM fixture
                            WHERE season_id = ? AND gameweek >= ? AND finished = false
                            UNION ALL
                            SELECT team_a, team_a_difficulty, gameweek, kickoff_time
                            FROM fixture
                            WHERE season_id = ? AND gameweek >= ? AND finished = false
                        ) both_sides
                    ) ranked
                    WHERE rn <= ?
                    GROUP BY team_id
                    """, seasonId, startGw, seasonId, startGw, fixtureLimit);
            for (Map<String, Object> r : rows) {
                Object avg = r.get("avg_fdr");
                if (avg != null) {
                    byTeam.put(((Number) r.get("team_id")).intValue(), ((Number) avg).doubleValue());
                }
            }
        } catch (DataAccessException e) {
            log.warn("Could not resolve upcoming FDR for season {}; picks will show n/a", seasonId, e);
        }
        return byTeam;
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

    private static double numeric(Object value) {
        return value == null ? 0.0 : ((Number) value).doubleValue();
    }

    /**
     * @return 1–4 for a valid position, 0 for "any", or {@code null} for input that names no
     *         position at all — silently widening a typo to "any" would answer a question the
     *         user did not ask
     */
    static Integer parsePosition(String position) {
        if (position == null || position.isBlank() || position.equalsIgnoreCase("any")) {
            return 0;
        }
        return switch (position.trim().toLowerCase(Locale.ROOT)) {
            case "1", "gk", "gkp", "goalkeeper" -> 1;
            case "2", "def", "defender" -> 2;
            case "3", "mid", "midfielder" -> 3;
            case "4", "fwd", "forward" -> 4;
            default -> null;
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
