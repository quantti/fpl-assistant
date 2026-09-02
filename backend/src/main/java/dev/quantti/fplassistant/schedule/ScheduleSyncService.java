package dev.quantti.fplassistant.schedule;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Captures FPL's gameweeks and fixtures into {@code fpl_gameweek} and {@code fpl_fixture}.
 *
 * <p>The schedule counterpart of {@code DailyPriceSyncService}, and built on the same three
 * rules: the season code comes from the UK date, not from the database; team names come from
 * the payload's own {@code teams} block, not from tapas; and the response is refused when it
 * still describes a finished season. The mapping methods are static and package-private so
 * they are unit-tested from captured payloads without a network.
 */
@Component
public class ScheduleSyncService {

    static List<Object[]> toGameweekRows(Map<String, Object> bootstrap, String season) {
        List<Object[]> rows = new ArrayList<>();
        if (!(bootstrap.get("events") instanceof List<?> events)) {
            return rows;
        }
        for (Object o : events) {
            if (!(o instanceof Map<?, ?> e)) {
                continue;
            }
            Integer id = asInt(e.get("id"));
            OffsetDateTime deadline = asTime(e.get("deadline_time"));
            if (id == null || deadline == null) {
                continue;   // deadline_time is NOT NULL: a gameweek without one is not a gameweek
            }
            rows.add(new Object[] {
                    season, id, asString(e.get("name")), deadline,
                    Boolean.TRUE.equals(e.get("finished")),
                    Boolean.TRUE.equals(e.get("data_checked")),
                    Boolean.TRUE.equals(e.get("is_current")),
                    Boolean.TRUE.equals(e.get("is_next")),
                    asInt(e.get("average_entry_score")), asInt(e.get("highest_score")),
                    asInt(e.get("most_captained")), asInt(e.get("most_transferred_in"))
            });
        }
        return rows;
    }

    /** id → {name, short_name} from the bootstrap {@code teams} block. */
    static Map<Integer, String[]> teamsOf(Map<String, Object> bootstrap) {
        Map<Integer, String[]> teams = new HashMap<>();
        if (bootstrap.get("teams") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> t) {
                    Integer id = asInt(t.get("id"));
                    if (id != null) {
                        teams.put(id, new String[] {asString(t.get("name")), asString(t.get("short_name"))});
                    }
                }
            }
        }
        return teams;
    }

    static List<Object[]> toFixtureRows(List<Map<String, Object>> fixtures, Map<Integer, String[]> teams,
                                        String season) {
        List<Object[]> rows = new ArrayList<>(fixtures.size());
        for (Map<String, Object> f : fixtures) {
            Integer id = asInt(f.get("id"));
            Integer home = asInt(f.get("team_h"));
            Integer away = asInt(f.get("team_a"));
            if (id == null || home == null || away == null) {
                continue;
            }
            String[] h = teams.getOrDefault(home, new String[2]);
            String[] a = teams.getOrDefault(away, new String[2]);
            rows.add(new Object[] {
                    season, id, asInt(f.get("event")), home, away,
                    h[0], h[1], a[0], a[1],
                    asInt(f.get("team_h_difficulty")), asInt(f.get("team_a_difficulty")),
                    asTime(f.get("kickoff_time")),
                    Boolean.TRUE.equals(f.get("started")), Boolean.TRUE.equals(f.get("finished")),
                    asInt(f.get("team_h_score")), asInt(f.get("team_a_score"))
            });
        }
        return rows;
    }

    private static Integer asInt(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static OffsetDateTime asTime(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value.toString());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
