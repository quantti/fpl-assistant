package dev.quantti.fplassistant.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class GameweekTools implements FplTool {

    private static final Logger log = LoggerFactory.getLogger(GameweekTools.class);

    private final JdbcTemplate jdbc;
    private final SeasonResolver seasonResolver;

    public GameweekTools(JdbcTemplate jdbc, SeasonResolver seasonResolver) {
        this.jdbc = jdbc;
        this.seasonResolver = seasonResolver;
    }

    @Tool(description = """
            Get current and next gameweek information: deadline, average score,
            highest score, most captained player ID, most transferred in player ID.
            The reply begins with the season the numbers come from; if that is not the season
            currently being played, tell the user the figures are historical.
            """)
    public String getGameweekInfo() {
        try {
            SeasonContext season = seasonResolver.resolve();

            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT id, name, deadline_time, finished, is_current, is_next,
                           average_entry_score, highest_score,
                           most_captained, most_transferred_in, most_selected
                    FROM gameweek
                    WHERE season_id = ?
                      AND (is_current = true OR is_next = true)
                    ORDER BY id
                    """, season.seasonId());

            if (rows.isEmpty()) {
                return season.provenance() + "\nNo gameweek data available for this season.";
            }

            return season.provenance() + "\n\n" + rows.stream()
                    .map(this::formatGameweek)
                    .collect(Collectors.joining("\n---\n"));
        } catch (DataAccessException e) {
            log.error("getGameweekInfo() failed", e);
            return "Gameweek data is temporarily unavailable (database error)."
                    + " Tell the user and suggest asking again shortly.";
        }
    }

    private String formatGameweek(Map<String, Object> r) {
        return """
                %s (id=%s)
                Status: %s
                Deadline: %s
                Average score: %s | Highest: %s
                Most captained player_id: %s | Most transferred in: %s
                """.formatted(
                r.get("name"), r.get("id"),
                status(r),
                r.get("deadline_time"),
                r.get("average_entry_score"), r.get("highest_score"),
                r.get("most_captained"), r.get("most_transferred_in")
        );
    }

    /**
     * A finished season keeps its last gameweek flagged {@code is_current}, so that flag alone
     * would label a gameweek from May as the one being played right now.
     */
    private String status(Map<String, Object> r) {
        if (Boolean.TRUE.equals(r.get("finished"))) {
            return "FINISHED";
        }
        return Boolean.TRUE.equals(r.get("is_current")) ? "CURRENT" : "NEXT";
    }
}
