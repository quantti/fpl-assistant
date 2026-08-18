package dev.quantti.fplassistant.tools;

import java.time.LocalDate;
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
 * Injury and availability, read from the daily {@code fpl_daily_price} capture.
 *
 * <p>FPL publishes a player's availability as a {@code status} code plus a free-text
 * {@code news} note, and both arrive in the same {@code bootstrap-static} response as the
 * price, so the daily capture already holds them. Availability is the most perishable fact in
 * FPL — a player ruled out on Friday is worthless on Saturday — and the only other source this
 * project has is the shared {@code player} table, which syncs weekly. A week-old status calls a
 * ruled-out player fit, which is the specific wrong answer these tools exist to prevent.
 *
 * <p>Because the capture is dated, {@link #getPlayerAvailability} can also say <b>how long</b> a
 * flag has been standing — something no single snapshot can answer, and a real signal in FPL:
 * a doubt first raised today is a different proposition from one that has stood for a fortnight.
 */
@Component
public class AvailabilityTools implements FplTool {

    private static final Logger log = LoggerFactory.getLogger(AvailabilityTools.class);
    private static final int MAX_FLAGGED_LISTED = 30;

    private final JdbcTemplate jdbc;
    private final DailyPriceSource source;

    public AvailabilityTools(JdbcTemplate jdbc, DailyPriceSource source) {
        this.jdbc = jdbc;
        this.source = source;
    }

    @Tool(description = """
            Check whether a specific player is fit, doubtful, injured or suspended, with FPL's
            own news note and how many days the flag has been standing. Use this for any
            question about a player's availability, fitness, injury or return date before
            recommending a transfer or a captain. The reply states which day's capture the
            answer comes from; if that is not today, say so, because FPL updates availability
            continuously.
            """)
    public String getPlayerAvailability(String playerName) {
        try {
            LocalDate captured = source.latestCaptureDate();
            if (captured == null) {
                return "No availability data has been captured yet. Tell the user this is"
                        + " unavailable rather than guessing whether the player is fit.";
            }

            List<Map<String, Object>> matches = source.matchPlayers(playerName, captured);
            if (matches.isEmpty()) {
                return DailyPriceSource.provenance(seasonOf(captured), captured, LocalDate.now(UkTime.zone()))
                        + "\nPlayer not found: " + playerName;
            }

            Map<String, Object> p = matches.getFirst();
            String webName = String.valueOf(p.get("web_name"));
            String ambiguity = NameMatch.ambiguityNote(
                    matches.stream().map(m -> String.valueOf(m.get("web_name"))).toList());

            int elementId = ((Number) p.get("element_id")).intValue();
            String status = String.valueOf(p.get("status"));
            String news = p.get("news") == null ? "" : String.valueOf(p.get("news"));

            StringBuilder out = new StringBuilder(
                    DailyPriceSource.provenance(String.valueOf(p.get("season")), captured,
                            LocalDate.now(UkTime.zone())))
                    .append("\n\n")
                    .append("%s (%s, %s) — %s\n".formatted(
                            webName, p.get("team_short"), position(p.get("element_type")),
                            describe(status)));

            if (!news.isBlank()) {
                out.append("FPL's note: ").append(news).append("\n");
            }
            if (!"a".equals(status)) {
                out.append(flagAge(elementId, status, captured));
            }
            return out + ambiguity;
        } catch (DataAccessException e) {
            log.error("getPlayerAvailability(\"{}\") failed", playerName, e);
            return "Availability data is temporarily unavailable (database error)."
                    + " Tell the user and suggest asking again shortly.";
        }
    }

    @Tool(description = """
            List every player at a Premier League club who is currently flagged — doubtful,
            injured, suspended or otherwise unavailable — with FPL's news note for each. Use
            this for team-news questions ahead of a deadline, or when the user asks who is out
            at a club. Pass the club name or short name (e.g. "Arsenal", "ARS"). The reply
            states which day's capture it comes from.
            """)
    public String getFlaggedPlayers(String teamName) {
        try {
            LocalDate captured = source.latestCaptureDate();
            if (captured == null) {
                return "No availability data has been captured yet. Tell the user this is"
                        + " unavailable rather than guessing who is fit.";
            }

            String pattern = "%" + LikePatterns.escape(teamName.toLowerCase(Locale.ROOT)) + "%";
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT web_name, team_short, element_type, status, news, now_cost, season
                    FROM %s
                    WHERE price_date = ?
                      AND status <> 'a'
                      AND LOWER(team_short) = LOWER(?)
                    ORDER BY now_cost DESC
                    LIMIT ?
                    """.formatted(source.table()), captured, teamName, MAX_FLAGGED_LISTED);

            if (rows.isEmpty()) {
                // Fall back to a substring match on the club's full name, which the daily
                // capture does not store — so match on short_name first, then try LIKE.
                rows = jdbc.queryForList("""
                        SELECT web_name, team_short, element_type, status, news, now_cost, season
                        FROM %s
                        WHERE price_date = ?
                          AND status <> 'a'
                          AND LOWER(team_short) LIKE ?
                        ORDER BY now_cost DESC
                        LIMIT ?
                        """.formatted(source.table()), captured, pattern, MAX_FLAGGED_LISTED);
            }

            String season = rows.isEmpty() ? seasonOf(captured) : String.valueOf(rows.getFirst().get("season"));
            String header = DailyPriceSource.provenance(season, captured, LocalDate.now(UkTime.zone()));

            if (rows.isEmpty()) {
                return header + "\nNo flagged players found for \"" + teamName + "\". Either every"
                        + " player at that club is available, or the club name did not match —"
                        + " check the name with the user before reporting a clean bill of health.";
            }

            StringBuilder out = new StringBuilder(header)
                    .append("\n\nFlagged players (%s), %d found:\n"
                            .formatted(rows.getFirst().get("team_short"), rows.size()));
            for (Map<String, Object> r : rows) {
                out.append("  %s (%s, £%.1fm) — %s".formatted(
                        r.get("web_name"), position(r.get("element_type")),
                        ((Number) r.get("now_cost")).intValue() / 10.0,
                        describe(String.valueOf(r.get("status")))));
                String news = r.get("news") == null ? "" : String.valueOf(r.get("news"));
                if (!news.isBlank()) {
                    out.append(" — ").append(news);
                }
                out.append("\n");
            }
            return out.toString();
        } catch (DataAccessException e) {
            log.error("getFlaggedPlayers(\"{}\") failed", teamName, e);
            return "Availability data is temporarily unavailable (database error)."
                    + " Tell the user and suggest asking again shortly.";
        }
    }

    /**
     * How long the current flag has stood, from the daily series.
     *
     * <p>Reports the first consecutive day carrying this same status, so a player who was
     * doubtful, recovered, and is doubtful again reads as newly doubtful rather than carrying
     * the older date. Says "at least" because the capture only started on 2026-08-18 — a flag
     * older than the series cannot be dated, and claiming otherwise would invent history.
     */
    private String flagAge(int elementId, String status, LocalDate captured) {
        // The current run starts the first day after the most recent day this player carried a
        // *different* status. Expressed as "everything since the last change" rather than as a
        // gaps-and-islands window function: the series can have holes (a day the 02:00 capture
        // did not run), and a row-number streak silently treats a hole as a break in the flag.
        LocalDate since = jdbc.queryForObject("""
                SELECT MIN(price_date) FROM %s
                WHERE element_id = ? AND status = ? AND price_date <= ?
                  AND price_date > COALESCE((
                      SELECT MAX(price_date) FROM %s
                      WHERE element_id = ? AND status <> ? AND price_date <= ?
                  ), DATE '1900-01-01')
                """.formatted(source.table(), source.table()),
                LocalDate.class, elementId, status, captured, elementId, status, captured);

        if (since == null) {
            return "";
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(since, captured);
        if (days == 0) {
            return "This flag is new as of today's capture.\n";
        }
        return "This flag has stood since at least %s (%d days).\n".formatted(since, days);
    }

    /** FPL's own availability codes, spelled out so the model does not have to guess. */
    private static String describe(String status) {
        return switch (status == null ? "" : status) {
            case "a" -> "available";
            case "d" -> "DOUBTFUL";
            case "i" -> "INJURED";
            case "s" -> "SUSPENDED";
            case "u" -> "UNAVAILABLE (not in the squad)";
            case "n" -> "NOT AVAILABLE (on loan or ineligible)";
            default -> "unknown availability (FPL status code '" + status + "')";
        };
    }

    private static String position(Object elementType) {
        if (!(elementType instanceof Number n)) {
            return "?";
        }
        return switch (n.intValue()) {
            case 1 -> "GK";
            case 2 -> "DEF";
            case 3 -> "MID";
            case 4 -> "FWD";
            default -> "?";
        };
    }

    /** Only needed for the not-found paths, where no row carries the season. */
    private String seasonOf(LocalDate captured) {
        String season = jdbc.queryForObject(
                "SELECT season FROM %s WHERE price_date = ? LIMIT 1".formatted(source.table()),
                String.class, captured);
        return season == null ? "unknown" : season;
    }
}
