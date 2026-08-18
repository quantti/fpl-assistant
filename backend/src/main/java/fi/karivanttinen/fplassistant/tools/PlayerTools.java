package fi.karivanttinen.fplassistant.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class PlayerTools implements FplTool {

    private static final Logger log = LoggerFactory.getLogger(PlayerTools.class);

    private final JdbcTemplate jdbc;
    private final SeasonResolver seasonResolver;

    public PlayerTools(JdbcTemplate jdbc, SeasonResolver seasonResolver) {
        this.jdbc = jdbc;
        this.seasonResolver = seasonResolver;
    }

    @Tool(description = """
            Search for an FPL player by name (web name or surname) and return their
            season stats: position, team, price, total points, form, goals,
            assists, clean sheets, xG, xA, ownership %, and availability status.
            Returns up to 5 matching players. The reply begins with the season the numbers
            come from; if that is not the season currently being played, tell the user the
            figures are historical.
            """)
    public String getPlayerStats(String playerName) {
        try {
            SeasonContext season = seasonResolver.resolve();
            String pattern = "%" + LikePatterns.escape(playerName.toLowerCase(Locale.ROOT)) + "%";

            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT p.web_name, p.first_name, p.second_name,
                           t.name as team, t.short_name,
                           p.element_type, p.now_cost, p.total_points, p.form,
                           p.goals_scored, p.assists, p.clean_sheets,
                           p.minutes, p.expected_goals, p.expected_assists,
                           p.selected_by_percent, p.status, p.news
                    FROM player p
                    JOIN team t ON t.id = p.team_id AND t.season_id = p.season_id
                    WHERE p.season_id = ?
                      AND (LOWER(p.web_name) LIKE ?
                           OR LOWER(p.second_name) LIKE ?
                           OR LOWER(p.first_name) LIKE ?
                           OR LOWER(p.first_name || ' ' || p.second_name) LIKE ?)
                    ORDER BY p.total_points DESC
                    LIMIT 5
                    """, season.seasonId(), pattern, pattern, pattern, pattern);

            if (rows.isEmpty()) {
                return season.provenance() + "\nPlayer not found: " + playerName;
            }

            return season.provenance() + "\n\n" + rows.stream()
                    .map(r -> formatPlayer(r, season))
                    .collect(Collectors.joining("\n---\n"));
        } catch (DataAccessException e) {
            log.error("getPlayerStats(\"{}\") failed", playerName, e);
            return "Player data is temporarily unavailable (database error)."
                    + " Tell the user and suggest asking again shortly.";
        }
    }

    private String formatPlayer(Map<String, Object> r, SeasonContext season) {
        String position = switch (((Number) r.get("element_type")).intValue()) {
            case 1 -> "GK";
            case 2 -> "DEF";
            case 3 -> "MID";
            case 4 -> "FWD";
            default -> "?";
        };
        double price = ((Number) r.get("now_cost")).doubleValue() / 10.0;
        // Price and ownership are "current-state" fields: when the numbers come from a
        // completed season, label them so the model cannot present them as this season's.
        String priceLabel = season.currentSeason()
                ? "Price"
                : "Price (" + season.code() + ", historical)";
        String ownershipLabel = season.currentSeason()
                ? "Ownership"
                : "Ownership (" + season.code() + ", historical)";
        // FPL status codes: a=available, d=doubtful, i/s/u/n = out for some reason. news is nullable.
        Object news = r.get("news");
        String detail = (news == null || news.toString().isBlank()) ? "" : " (" + news + ")";
        String status = switch (String.valueOf(r.get("status"))) {
            case "a" -> "Available";
            case "d" -> "Doubtful" + detail;
            default -> "Unavailable" + detail;
        };

        return """
                %s %s (%s) [%s]
                %s: £%.1fm | Points: %s | Form: %s
                Goals: %s | Assists: %s | CS: %s | Minutes: %s
                xG: %s | xA: %s | %s: %s%%
                Status: %s
                """.formatted(
                position, r.get("web_name"), r.get("short_name"), r.get("team"),
                priceLabel, price, r.get("total_points"), r.get("form"),
                r.get("goals_scored"), r.get("assists"), r.get("clean_sheets"), r.get("minutes"),
                r.get("expected_goals"), r.get("expected_assists"), ownershipLabel, r.get("selected_by_percent"),
                status
        );
    }
}
