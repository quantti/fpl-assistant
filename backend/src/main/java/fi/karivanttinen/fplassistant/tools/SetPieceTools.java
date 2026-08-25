package fi.karivanttinen.fplassistant.tools;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * Who takes a club's penalties, direct free kicks and corners — from FPL's own published order,
 * captured daily, with the date each duty last changed.
 *
 * <p><b>Why this is a tool and not a document.</b> Set-piece duty is the single most revisable
 * fact in FPL: it changes with a transfer, a manager, an injury, and above all with what actually
 * happens on the pitch. A hand-written note is written before anyone has taken a penalty, and the
 * first weekend of the season is exactly when it stops being true. {@code bootstrap-static}
 * publishes {@code penalties_order}, {@code direct_freekicks_order} and
 * {@code corners_and_indirect_freekicks_order} on the same elements as the price, so the daily
 * capture already has them — 66 penalty takers, 81 corner takers and 56 free-kick takers on the
 * day this tool was written.
 *
 * <p>Because the rows are dated, this answers a question no snapshot can: <b>when</b> the order
 * changed, and what it was before. That is the part a manager actually needs — "Gyökeres was on
 * penalties until the 24th" changes a transfer decision in a way "Saka takes them" does not.
 *
 * <p>Like the other daily-capture tools ({@code PriceTools}, {@code AvailabilityTools}) this uses
 * {@link DailyPriceSource#provenance} rather than {@code SeasonContext.provenance()} — a
 * documented exception in {@code AGENTS.md}, because the resolver would attribute a capture made
 * today to whichever season the database happens to have data for.
 */
@Component
public class SetPieceTools implements FplTool {

    private static final Logger log = LoggerFactory.getLogger(SetPieceTools.class);

    /** How far back the change log looks. Long enough to cover a transfer window's aftermath. */
    private static final int CHANGE_WINDOW_DAYS = 60;

    private record Duty(String column, String label) { }

    private static final List<Duty> DUTIES = List.of(
            new Duty("penalties_order", "Penalties"),
            new Duty("direct_fk_order", "Direct free kicks"),
            new Duty("corners_order", "Corners and indirect free kicks"));

    private final JdbcTemplate jdbc;
    private final DailyPriceSource source;

    public SetPieceTools(JdbcTemplate jdbc, DailyPriceSource source) {
        this.jdbc = jdbc;
        this.source = source;
    }

    @Tool(description = """
            Get a Premier League club's set-piece takers: who takes penalties, direct free kicks
            and corners, in FPL's own published order, plus the date each duty last changed and
            what it was before. Use this whenever penalties or set pieces bear on the answer —
            it is FPL's live data, captured daily, and it supersedes any written note about
            takers. Accepts a club name or its three-letter code. The reply states the capture
            date; if the order changed recently, say so, because a taker who lost the duty last
            week is the common trap.
            """)
    public String getSetPieceTakers(String teamName) {
        try {
            LocalDate captured = source.latestCaptureDate();
            if (captured == null) {
                return "No FPL capture has run yet, so set-piece duty is unknown. Tell the user"
                        + " this is unavailable rather than naming takers from memory.";
            }
            String pattern = "%" + LikePatterns.escape(teamName.toLowerCase(Locale.ROOT)) + "%";

            List<Map<String, Object>> takers = jdbc.queryForList("""
                    SELECT element_id, web_name, season, team_short, team_name,
                           penalties_order, direct_fk_order, corners_order
                    FROM %s
                    WHERE price_date = ?
                      AND (LOWER(team_short) = LOWER(?) OR LOWER(team_name) LIKE ?)
                      AND (penalties_order IS NOT NULL OR direct_fk_order IS NOT NULL
                           OR corners_order IS NOT NULL)
                    ORDER BY team_short, web_name
                    """.formatted(source.table()), captured, teamName, pattern);

            if (takers.isEmpty()) {
                // An unmatched club and a club with no published duty are different answers, and
                // collapsing them is the dangerous one: "no set-piece takers" reads as a fact
                // about the club when it is really a fact about the spelling.
                Integer clubRows = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM %s
                        WHERE price_date = ?
                          AND (LOWER(team_short) = LOWER(?) OR LOWER(team_name) LIKE ?)
                        """.formatted(source.table()), Integer.class, captured, teamName, pattern);
                if (clubRows == null || clubRows == 0) {
                    return "No club matches \"" + teamName + "\" in the current capture. Ask the"
                            + " user which club they mean — do not answer as if the club had no"
                            + " set-piece takers.";
                }
                return "FPL publishes no set-piece order for \"" + teamName + "\" as of "
                        + captured + ". That is an absence of data, not an absence of takers.";
            }

            String season = String.valueOf(takers.getFirst().get("season"));
            LocalDate windowStart = captured.minusDays(CHANGE_WINDOW_DAYS);
            LocalDate seriesStart = jdbc.queryForObject(
                    "SELECT MIN(price_date) FROM %s WHERE season = ?".formatted(source.table()),
                    LocalDate.class, season);

            StringBuilder out = new StringBuilder(
                    DailyPriceSource.provenance(season, captured, LocalDate.now(UkTime.zone())));
            out.append("\nSet-piece duty as published by FPL on ").append(captured).append(".\n");

            Map<String, List<Map<String, Object>>> byClub = new LinkedHashMap<>();
            for (Map<String, Object> row : takers) {
                byClub.computeIfAbsent(String.valueOf(row.get("team_short")),
                        ignored -> new ArrayList<>()).add(row);
            }
            if (byClub.size() > 1) {
                out.append("\"").append(teamName).append("\" matched ").append(byClub.size())
                        .append(" clubs; all are listed.\n");
            }

            for (Map.Entry<String, List<Map<String, Object>>> club : byClub.entrySet()) {
                Map<String, Object> first = club.getValue().getFirst();
                out.append("\n").append(first.get("team_name")).append(" (").append(club.getKey())
                        .append(")\n");
                for (Duty duty : DUTIES) {
                    out.append("- ").append(duty.label()).append(": ")
                            .append(order(club.getValue(), duty.column())).append("\n");
                }
                out.append(changes(club.getValue(), season, captured, windowStart));
            }

            if (seriesStart != null) {
                out.append("\nThe daily capture begins ").append(seriesStart)
                        .append(", so no change before that date can be reported.\n");
            }
            return out.toString();
        } catch (DataAccessException e) {
            log.error("getSetPieceTakers(\"{}\") failed", teamName, e);
            return "Set-piece data is temporarily unavailable (database error). Tell the user and"
                    + " suggest asking again shortly.";
        }
    }

    /** "1 Saka, 2 Gyökeres, 3 Ødegaard", or a plain statement that FPL publishes no order. */
    private static String order(List<Map<String, Object>> rows, String column) {
        List<Map<String, Object>> ranked = rows.stream()
                .filter(row -> row.get(column) != null)
                .sorted(java.util.Comparator.comparingInt(row -> ((Number) row.get(column)).intValue()))
                .toList();
        if (ranked.isEmpty()) {
            return "no order published";
        }
        return ranked.stream()
                .map(row -> ((Number) row.get(column)).intValue() + " " + row.get("web_name"))
                .reduce((a, b) -> a + ", " + b).orElse("");
    }

    /**
     * Every change to this club's set-piece order inside the window, oldest first.
     *
     * <p>Computed from the series in Java rather than in SQL because the series has holes: a day
     * the 02:00 capture did not run is a missing row, and a window function comparing adjacent
     * rows would report the gap as a change. Comparing consecutive <i>captures</i> instead means
     * a missed day delays a change by a day rather than inventing one.
     */
    private String changes(List<Map<String, Object>> takers, String season, LocalDate captured,
                           LocalDate windowStart) {
        List<Integer> elementIds = takers.stream()
                .map(row -> ((Number) row.get("element_id")).intValue()).toList();
        String placeholders = String.join(",", java.util.Collections.nCopies(elementIds.size(), "?"));
        List<Object> args = new ArrayList<>(List.of(season, windowStart, captured));
        args.addAll(elementIds);

        List<Map<String, Object>> series = jdbc.queryForList("""
                SELECT price_date, element_id, web_name,
                       penalties_order, direct_fk_order, corners_order
                FROM %s
                WHERE season = ? AND price_date >= ? AND price_date <= ?
                  AND element_id IN (%s)
                ORDER BY element_id, price_date
                """.formatted(source.table(), placeholders), args.toArray());

        Map<Integer, Map<String, Object>> previous = new LinkedHashMap<>();
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> row : series) {
            int elementId = ((Number) row.get("element_id")).intValue();
            Map<String, Object> before = previous.put(elementId, row);
            if (before == null) {
                continue;
            }
            // A capture from before set-piece duty was captured at all carries three nulls, and
            // cannot tell "this player takes nothing" from "nobody looked". Comparing against it
            // would announce that every taker in the league changed duty on the day the columns
            // were added, which is the opposite of what a change log is for.
            if (noDutyCaptured(before)) {
                continue;
            }
            for (Duty duty : DUTIES) {
                Integer was = asInt(before.get(duty.column()));
                Integer now = asInt(row.get(duty.column()));
                if (java.util.Objects.equals(was, now)) {
                    continue;
                }
                lines.add("  " + row.get("price_date") + ": " + row.get("web_name") + " "
                        + duty.label().toLowerCase(Locale.ROOT) + " "
                        + (was == null ? "none" : "#" + was) + " -> "
                        + (now == null ? "none" : "#" + now));
            }
        }
        if (lines.isEmpty()) {
            return "  No change in the last " + CHANGE_WINDOW_DAYS + " days.\n";
        }
        return "  Changes in the last " + CHANGE_WINDOW_DAYS + " days:\n"
                + String.join("\n", lines) + "\n";
    }

    /** True when a capture holds no set-piece data at all for this player. */
    private static boolean noDutyCaptured(Map<String, Object> row) {
        return DUTIES.stream().allMatch(duty -> row.get(duty.column()) == null);
    }

    private static Integer asInt(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }
}
