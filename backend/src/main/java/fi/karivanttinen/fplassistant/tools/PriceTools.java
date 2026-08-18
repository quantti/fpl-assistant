package fi.karivanttinen.fplassistant.tools;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Price trend, read from the daily {@code fpl_daily_price} capture. The trend is
 * <b>descriptive</b> — "price has risen/fallen X over N days" — never a prediction.
 *
 * <p><b>Moved off {@code fpl_player_price} on 2026-08-18.</b> That table holds per-gameweek
 * prices from vaastav plus a snapshot copied from the tapas {@code player} table, and both of
 * its writers are gated on {@code SeasonContext.currentSeason()}. During the season changeover
 * the gate is shut, so the table was empty; the player lookup also went through the tapas
 * {@code player} table, which held zero rows for 2026-27 three days before that season kicked
 * off — meaning this tool answered "player not found" for every current player. The daily
 * capture has none of those dependencies: it names its own players, its own teams and its own
 * season, straight from FPL.
 *
 * <p>{@code lastN} therefore counts <b>days</b>, not gameweeks — the grain the source now has,
 * and the one that matches the question being asked, since FPL moves prices nightly rather than
 * per gameweek.
 */
@Component
public class PriceTools implements FplTool {

    private static final Logger log = LoggerFactory.getLogger(PriceTools.class);
    private static final int MAX_DAYS = 60;

    private final JdbcTemplate jdbc;
    private final DailyPriceSource source;

    public PriceTools(JdbcTemplate jdbc, DailyPriceSource source) {
        this.jdbc = jdbc;
        this.source = source;
    }

    @Tool(description = """
            Get a player's recent price movement — the last N days of prices captured daily
            from FPL, plus how much the price has risen or fallen over that window and FPL's
            own counters for the change this gameweek and since the season started. The first
            row is today's price (or the latest captured) — use this for "what does X cost
            now" as well as for buy/sell timing and team-value questions. The trend is
            descriptive, not a prediction of future movement. FPL changes prices once a day at
            00:00 UK; the reply states which day's capture it comes from, and if that is not
            today, say so.
            """)
    public String getPriceHistory(String playerName, int lastN) {
        try {
            LocalDate captured = source.latestCaptureDate();
            if (captured == null) {
                return "No price data has been captured yet. Tell the user prices are"
                        + " unavailable rather than quoting a figure from memory.";
            }
            int days = Math.max(1, Math.min(lastN, MAX_DAYS));

            List<Map<String, Object>> matches = source.matchPlayers(playerName, captured);
            LocalDate today = LocalDate.now(UkTime.zone());
            if (matches.isEmpty()) {
                return DailyPriceSource.provenance(seasonOn(captured), captured, today)
                        + "\nPlayer not found: " + playerName;
            }

            Map<String, Object> latest = matches.getFirst();
            int elementId = ((Number) latest.get("element_id")).intValue();
            String season = String.valueOf(latest.get("season"));
            String webName = String.valueOf(latest.get("web_name"));
            String ambiguity = NameMatch.ambiguityNote(
                    matches.stream().map(m -> String.valueOf(m.get("web_name"))).toList());

            // element_id alone is not a player: FPL renumbers every season, so id 1 is a
            // different player in 2027-28 than in 2026-27. Without the season predicate the
            // trend would mix last season's rows for the same number into this season's
            // series at the changeover — the exact window this table was built to survive.
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT price_date, now_cost, cost_change_event, cost_change_start
                    FROM %s
                    WHERE element_id = ? AND season = ?
                    ORDER BY price_date DESC
                    LIMIT ?
                    """.formatted(source.table()), elementId, season, days);

            String header = DailyPriceSource.provenance(season, captured, today);
            return header + "\n\n" + formatHistory(webName, rows) + ambiguity;
        } catch (DataAccessException e) {
            log.error("getPriceHistory(\"{}\", {}) failed", playerName, lastN, e);
            return "Price data is temporarily unavailable (database error)."
                    + " Tell the user and suggest asking again shortly.";
        }
    }

    /** @param rows newest first, one per captured day */
    static String formatHistory(String webName, List<Map<String, Object>> rows) {
        StringBuilder out = new StringBuilder("Price history for ").append(webName).append(":\n");
        for (Map<String, Object> r : rows) {
            out.append("  %s: £%.1fm\n".formatted(
                    r.get("price_date"), ((Number) r.get("now_cost")).intValue() / 10.0));
        }
        out.append("\n").append(trendSummary(rows));

        Map<String, Object> latest = rows.getFirst();
        // FPL's own counters, which cover movement from before our capture began — the series
        // above starts on 2026-08-18 and cannot describe anything earlier.
        out.append("\nFPL's own counters: %+.1fm this gameweek, %+.1fm since the season started."
                .formatted(((Number) latest.get("cost_change_event")).intValue() / 10.0,
                        ((Number) latest.get("cost_change_start")).intValue() / 10.0));
        return out.toString();
    }

    /** Descriptive only: compares the earliest and latest price in the window. */
    private static String trendSummary(List<Map<String, Object>> rows) {
        if (rows.size() < 2) {
            return "Trend: only one day has been captured so far (£%.1fm), so there is no trend"
                    .formatted(((Number) rows.getFirst().get("now_cost")).intValue() / 10.0)
                    + " to describe yet. The daily capture began on 2026-08-18 and builds from there.";
        }
        Map<String, Object> latest = rows.getFirst();
        Map<String, Object> earliest = rows.getLast();
        int change = ((Number) latest.get("now_cost")).intValue()
                - ((Number) earliest.get("now_cost")).intValue();
        String direction = change > 0 ? "risen" : change < 0 ? "fallen" : "held steady";
        return "Trend: price has %s by £%.1fm between %s and %s (descriptive, not a prediction)."
                .formatted(direction, Math.abs(change) / 10.0,
                        earliest.get("price_date"), latest.get("price_date"));
    }

    /** Only needed on the not-found path, where no matched row carries the season. */
    private String seasonOn(LocalDate captured) {
        String season = jdbc.queryForObject(
                "SELECT season FROM %s WHERE price_date = ? LIMIT 1".formatted(source.table()),
                String.class, captured);
        return season == null ? "unknown" : season;
    }
}
