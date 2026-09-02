package fi.karivanttinen.fplassistant.tools;

import fi.karivanttinen.fplassistant.schedule.ScheduleSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
public class GameweekTools implements FplTool {

    private static final Logger log = LoggerFactory.getLogger(GameweekTools.class);

    private final JdbcTemplate jdbc;
    private final SeasonResolver seasonResolver;
    private final ScheduleSource schedule;

    public GameweekTools(JdbcTemplate jdbc, SeasonResolver seasonResolver, ScheduleSource schedule) {
        this.jdbc = jdbc;
        this.seasonResolver = seasonResolver;
        this.schedule = schedule;
    }

    @Tool(description = """
            Get current and next gameweek information: deadline (in UK time, as FPL
            publishes it), average score, highest score, most captained player ID,
            most transferred in player ID. When the reply begins with "Data source: FPL's own
            gameweek and fixture list", status is judged against the clock at the time of
            asking; when it begins with "Data source: season ...", it came from the weekly
            sync — status may lag, and if that is not the season currently being played, tell
            the user the figures are historical.
            """)
    public String getGameweekInfo() {
        try {
            Optional<ScheduleSource.Snapshot> captured = schedule.latest();
            if (captured.isPresent()) {
                return fromCapture(captured.get());
            }
            SeasonContext season = seasonResolver.resolve();

            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT id, name, deadline_time, finished, is_current, is_next,
                           average_entry_score, highest_score,
                           most_captained, most_transferred_in
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

    /**
     * The capture path. Status comes from the deadline against the clock, not from FPL's flags as
     * captured at 02:10: the gameweek whose deadline has passed most recently and is not finished is
     * being played, and the first with a deadline still ahead is next.
     */
    private String fromCapture(ScheduleSource.Snapshot snapshot) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, name, deadline_time, finished, average_entry_score, highest_score,
                       most_captained, most_transferred_in,
                       deadline_time <= now() AS deadline_passed
                FROM %s WHERE season = ?
                ORDER BY deadline_time
                """.formatted(schedule.gameweekTable()), snapshot.season());
        Map<String, Object> current = null;
        Map<String, Object> next = null;
        for (Map<String, Object> r : rows) {
            if (Boolean.TRUE.equals(r.get("deadline_passed"))) {
                current = r;
            } else if (next == null) {
                next = r;
            }
        }
        String provenance = ScheduleSource.provenance(snapshot.season(), snapshot.capturedAt(), Instant.now());
        if (current == null && next == null) {
            return provenance + "\nNo gameweek data captured for this season.";
        }
        StringBuilder out = new StringBuilder(provenance).append("\n\n");
        if (current != null) {
            out.append(formatCaptured(current, Boolean.TRUE.equals(current.get("finished")) ? "FINISHED" : "CURRENT"));
        }
        if (current != null && next != null) {
            out.append("\n---\n");
        }
        if (next != null) {
            out.append(formatCaptured(next, "NEXT"));
        }
        return out.toString();
    }

    private String formatCaptured(Map<String, Object> r, String status) {
        return """
                %s (id=%s)
                Status: %s
                Deadline: %s
                Average score: %s | Highest: %s
                Most captained player_id: %s | Most transferred in: %s
                """.formatted(r.get("name"), r.get("id"), status,
                UkTime.format(r.get("deadline_time"), "deadline unknown"),
                r.get("average_entry_score"), r.get("highest_score"),
                r.get("most_captained"), r.get("most_transferred_in"));
    }

    private String formatGameweek(Map<String, Object> r) {
        return formatCaptured(r, status(r));
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
