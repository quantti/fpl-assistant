package dev.quantti.fplassistant.tools;

import java.util.ArrayList;
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
 *
 * <p>{@code lastN} counts gameweeks. The live snapshot is fetched separately and always shown on
 * top of them, so asking for five gameweeks returns five gameweeks and the live price, not four
 * and the live price.
 */
@Component
public class PriceTools implements FplTool {

    private static final Logger log = LoggerFactory.getLogger(PriceTools.class);
    private static final int MAX_GWS = 38;
    private static final int MAX_NAME_MATCHES = 4;

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
            Get a player's price history for the current season — the last N per-gameweek
            prices (from vaastav, once each GW has been played) plus the latest live snapshot
            of now_cost. Includes a descriptive trend: how much the price has risen or fallen
            over the window and in which direction. Use this for buy/sell timing and
            team-value questions. The trend is descriptive, not a prediction of future
            movement. The reply begins with the season the numbers come from.
            """)
    public String getPriceHistory(String playerName, int lastN) {
        try {
            SeasonContext season = seasonResolver.resolve();
            int gameweeks = Math.max(1, Math.min(lastN, MAX_GWS));
            String pattern = "%" + LikePatterns.escape(playerName.toLowerCase(Locale.ROOT)) + "%";

            List<Map<String, Object>> players = jdbc.queryForList("""
                    SELECT id, web_name FROM player
                    WHERE season_id = ?
                      AND (LOWER(web_name) LIKE ?
                           OR LOWER(second_name) LIKE ?
                           OR LOWER(first_name || ' ' || second_name) LIKE ?)
                    ORDER BY now_cost DESC
                    LIMIT ?
                    """, season.seasonId(), pattern, pattern, pattern, MAX_NAME_MATCHES);

            if (players.isEmpty()) {
                return season.provenance() + "\nPlayer not found: " + playerName;
            }

            int elementId = ((Number) players.getFirst().get("id")).intValue();
            String webName = (String) players.getFirst().get("web_name");
            String ambiguity = NameMatch.ambiguityNote(
                    players.stream().map(p -> String.valueOf(p.get("web_name"))).toList());

            // The live snapshot (gameweek 0) and the played gameweeks are fetched separately so
            // that lastN bounds the gameweeks only — a shared LIMIT would spend one on the snapshot.
            List<Map<String, Object>> snapshot = jdbc.queryForList("""
                    SELECT gameweek, price, source FROM %s
                    WHERE season = ? AND element_id = ? AND gameweek = 0
                    """.formatted(priceTable), // priceTable is config-injected, not user input
                    season.code(), elementId);

            List<Map<String, Object>> gameweekRows = jdbc.queryForList("""
                    SELECT gameweek, price, source FROM %s
                    WHERE season = ? AND element_id = ? AND gameweek > 0
                    ORDER BY gameweek DESC
                    LIMIT ?
                    """.formatted(priceTable), // priceTable is config-injected, not user input
                    season.code(), elementId, gameweeks);

            List<Map<String, Object>> rows = new ArrayList<>(snapshot);
            rows.addAll(gameweekRows);

            if (rows.isEmpty()) {
                return season.provenance() + "\nNo price history yet for " + webName
                        + " in " + season.code() + " (the season may not have started, or no"
                        + " snapshot has run)." + ambiguity;
            }

            return season.provenance() + "\n\n" + formatHistory(webName, rows) + ambiguity;
        } catch (DataAccessException e) {
            log.error("getPriceHistory(\"{}\", {}) failed", playerName, lastN, e);
            return "Price data is temporarily unavailable (database error)."
                    + " Tell the user and suggest asking again shortly.";
        }
    }

    /** @param rows newest first: the live snapshot (gameweek 0) if present, then gameweeks descending */
    static String formatHistory(String webName, List<Map<String, Object>> rows) {
        StringBuilder out = new StringBuilder("Price history for ").append(webName).append(":\n");
        for (Map<String, Object> r : rows) {
            int gw = ((Number) r.get("gameweek")).intValue();
            int price = ((Number) r.get("price")).intValue();
            out.append("  %s: £%.1fm (%s)\n".formatted(
                    gw == 0 ? "live" : "GW" + gw, price / 10.0, r.get("source")));
        }
        out.append("\n").append(trendSummary(rows));
        return out.toString();
    }

    /** Descriptive only: compares the earliest and latest price in the window. */
    private static String trendSummary(List<Map<String, Object>> rows) {
        if (rows.size() < 2) {
            // One data point is a price, not a trend. Before GW1 this is the normal state:
            // the startup snapshot has run and vaastav has published no gameweek yet.
            return "Trend: only one price point is stored so far (£%.1fm), so there is no trend"
                    .formatted(((Number) rows.getFirst().get("price")).intValue() / 10.0)
                    + " to describe yet. Per-gameweek prices appear once gameweeks have been played.";
        }
        Map<String, Object> latest = rows.getFirst();
        Map<String, Object> earliest = rows.getLast();
        int change = ((Number) latest.get("price")).intValue() - ((Number) earliest.get("price")).intValue();
        String direction = change > 0 ? "risen" : change < 0 ? "fallen" : "held steady";
        return "Trend: price has %s by £%.1fm between %s and %s (descriptive, not a prediction)."
                .formatted(direction, Math.abs(change) / 10.0,
                        label(earliest.get("gameweek")), label(latest.get("gameweek")));
    }

    private static String label(Object gameweek) {
        return (gameweek instanceof Number n && n.intValue() == 0) ? "the live snapshot" : "GW" + gameweek;
    }
}
