package fi.karivanttinen.fplassistant.tools;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Phase 4 price-trend tool. {@link #getPriceHistory} reads the current season's per-GW prices
 * from {@code fpl_player_price} (vaastav rows for played GWs plus the latest live snapshot) and
 * describes the trend. The trend is <b>descriptive</b> — "price has risen/fallen X since GW3"
 * — never a prediction of future movement.
 */
@Component
public class PriceTools implements FplTool {

    private static final Logger log = LoggerFactory.getLogger(PriceTools.class);
    private static final int MAX_GWS = 38;

    private final JdbcTemplate jdbc;
    private final SeasonResolver seasonResolver;
    private final String priceTable;

    public PriceTools(JdbcTemplate jdbc, SeasonResolver seasonResolver,
                      @Value("${fpl.price.table-name}") String priceTable) {
        this.jdbc = jdbc;
        this.seasonResolver = seasonResolver;
        this.priceTable = priceTable;
    }

    @Tool(description = """
            Get a player's price history for the current season — the per-gameweek prices
            (from vaastav, once each GW has been played) plus the latest live snapshot of
            now_cost. Includes a descriptive trend: how much the price has risen or fallen
            over the window and in which direction. Use this for buy/sell timing and
            team-value questions. The trend is descriptive, not a prediction of future
            movement. The reply begins with the season the numbers come from.
            """)
    public String getPriceHistory(String playerName, int lastN) {
        try {
            SeasonContext season = seasonResolver.resolve();
            int limit = Math.max(1, Math.min(lastN, MAX_GWS));
            String pattern = "%" + LikePatterns.escape(playerName.toLowerCase(Locale.ROOT)) + "%";

            List<Map<String, Object>> players = jdbc.queryForList("""
                    SELECT id, web_name, first_name, second_name, now_cost FROM player
                    WHERE season_id = ?
                      AND (LOWER(web_name) LIKE ?
                           OR LOWER(second_name) LIKE ?
                           OR LOWER(first_name || ' ' || second_name) LIKE ?)
                    ORDER BY now_cost DESC
                    LIMIT 1
                    """, season.seasonId(), pattern, pattern, pattern);

            if (players.isEmpty()) {
                return season.provenance() + "\nPlayer not found: " + playerName;
            }

            int elementId = ((Number) players.getFirst().get("id")).intValue();
            String webName = (String) players.getFirst().get("web_name");

            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT gameweek, price, source FROM %s
                    WHERE season = ? AND element_id = ?
                    ORDER BY (gameweek = 0) DESC, gameweek DESC
                    LIMIT ?
                    """.formatted(priceTable), // priceTable is config-injected, not user input
                    season.code(), elementId, limit);

            if (rows.isEmpty()) {
                return season.provenance() + "\nNo price history yet for " + webName
                        + " in " + season.code() + " (the season may not have started, or no"
                        + " snapshot has run).";
            }

            return season.provenance() + "\n\n" + formatHistory(webName, rows);
        } catch (DataAccessException e) {
            log.error("getPriceHistory(\"{}\", {}) failed", playerName, lastN, e);
            return "Price data is temporarily unavailable (database error)."
                    + " Tell the user and suggest asking again shortly.";
        }
    }

    private String formatHistory(String webName, List<Map<String, Object>> rows) {
        // rows are newest-first; gameweek 0 is the live snapshot.
        StringBuilder out = new StringBuilder("Price history for ").append(webName).append(":\n");
        for (Map<String, Object> r : rows) {
            int gw = ((Number) r.get("gameweek")).intValue();
            int price = ((Number) r.get("price")).intValue();
            String source = (String) r.get("source");
            String label = gw == 0 ? "live" : "GW" + gw;
            out.append("  %s: £%.1fm (%s)\n".formatted(label, price / 10.0, source));
        }
        out.append("\n").append(trendSummary(rows));
        return out.toString();
    }

    /** Descriptive only: compares the earliest and latest price in the window. */
    private String trendSummary(List<Map<String, Object>> rows) {
        // earliest = highest gameweek number that is not 0 ... actually rows are newest-first,
        // so the last element is the oldest. Use it as the window start.
        Map<String, Object> latest = rows.getFirst();
        Map<String, Object> earliest = rows.getLast();
        int latestPrice = ((Number) latest.get("price")).intValue();
        int earliestPrice = ((Number) earliest.get("price")).intValue();
        int change = latestPrice - earliestPrice;
        Object latestGw = latest.get("gameweek");
        Object earliestGw = earliest.get("gameweek");
        String fromLabel = (earliestGw instanceof Number n && n.intValue() == 0) ? "live snapshot" : "GW" + earliestGw;
        String toLabel = (latestGw instanceof Number n2 && n2.intValue() == 0) ? "live snapshot" : "GW" + latestGw;
        String direction = change > 0 ? "risen" : change < 0 ? "fallen" : "held steady";
        return "Trend: price has %s by £%.1fm between %s and %s (descriptive, not a prediction)."
                .formatted(direction, Math.abs(change) / 10.0, fromLabel, toLabel);
    }
}
