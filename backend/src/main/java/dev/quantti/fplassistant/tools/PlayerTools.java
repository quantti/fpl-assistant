package dev.quantti.fplassistant.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class PlayerTools {

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
            String pattern = "%" + escapeLike(playerName.toLowerCase()) + "%";

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
                           OR LOWER(p.first_name) LIKE ?)
                    ORDER BY p.total_points DESC
                    LIMIT 5
                    """, season.seasonId(), pattern, pattern, pattern);

            if (rows.isEmpty()) {
                return season.provenance() + "\nPlayer not found: " + playerName;
            }

            return season.provenance() + "\n\n" + rows.stream()
                    .map(this::formatPlayer)
                    .collect(Collectors.joining("\n---\n"));
        } catch (DataAccessException e) {
            return "Player data is temporarily unavailable (database error)."
                    + " Tell the user and suggest asking again shortly.";
        }
    }

    /**
     * LIKE treats {@code %}, {@code _} and {@code \} as metacharacters even inside a bound
     * parameter; unescaped, a literal "%" would match every player in the season.
     */
    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private String formatPlayer(Map<String, Object> r) {
        String position = switch (((Number) r.get("element_type")).intValue()) {
            case 1 -> "GK";
            case 2 -> "DEF";
            case 3 -> "MID";
            case 4 -> "FWD";
            default -> "?";
        };
        double price = ((Number) r.get("now_cost")).doubleValue() / 10.0;
        String status = "a".equals(r.get("status")) ? "Available" : "Unavailable (" + r.get("news") + ")";

        return """
                %s %s (%s) [%s]
                Price: £%.1fm | Points: %s | Form: %s
                Goals: %s | Assists: %s | CS: %s | Minutes: %s
                xG: %s | xA: %s | Ownership: %s%%
                Status: %s
                """.formatted(
                position, r.get("web_name"), r.get("short_name"), r.get("team"),
                price, r.get("total_points"), r.get("form"),
                r.get("goals_scored"), r.get("assists"), r.get("clean_sheets"), r.get("minutes"),
                r.get("expected_goals"), r.get("expected_assists"), r.get("selected_by_percent"),
                status
        );
    }
}
