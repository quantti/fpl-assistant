package dev.quantti.fplassistant.tools;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Picks the season the tools should query.
 *
 * <p>The season flagged {@code is_current} is created before its data is synced — a new season row
 * exists for weeks while every FPL table is still empty. Filtering on {@code is_current} alone
 * therefore returns nothing at all across the changeover. This resolver prefers the current season
 * once it actually holds data and otherwise falls back to the most recent season that does, so the
 * assistant stays useful; {@link SeasonContext#provenance()} is what keeps the fallback honest.
 */
@Component
public class SeasonResolver {

    private final JdbcTemplate jdbc;

    public SeasonResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public SeasonContext resolve() {
        Map<String, Object> current = jdbc.queryForMap(
                "SELECT id, code, start_date FROM season WHERE is_current = true");

        // A season counts as populated only once both gameweeks and players have landed; a sync
        // that is midway through would otherwise be picked while most tools still see nothing.
        List<Map<String, Object>> populated = jdbc.queryForList("""
                SELECT s.id, s.code, s.is_current
                FROM season s
                WHERE EXISTS (SELECT 1 FROM gameweek g WHERE g.season_id = s.id)
                  AND EXISTS (SELECT 1 FROM player p WHERE p.season_id = s.id)
                ORDER BY s.is_current DESC, s.start_date DESC
                LIMIT 1
                """);

        String currentCode = (String) current.get("code");
        LocalDate currentStart = ((Date) current.get("start_date")).toLocalDate();

        if (populated.isEmpty()) {
            // Nothing anywhere yet. Report the current season so tools can say which one is empty.
            int currentId = ((Number) current.get("id")).intValue();
            return new SeasonContext(currentId, currentCode, true, currentCode, currentStart);
        }

        Map<String, Object> chosen = populated.getFirst();
        return new SeasonContext(
                ((Number) chosen.get("id")).intValue(),
                (String) chosen.get("code"),
                Boolean.TRUE.equals(chosen.get("is_current")),
                currentCode,
                currentStart);
    }
}
