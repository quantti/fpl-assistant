package fi.karivanttinen.fplassistant.historical;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import fi.karivanttinen.fplassistant.tools.LikePatterns;

/**
 * Resolves a club name to its persistent FPL {@link TeamIdentity} against the clubs actually
 * present in {@code pl_match_result} — deliberately <b>not</b> {@code FixtureTools}' resolver,
 * which returns a season-scoped {@code team.id} and only ever sees the resolved season's
 * twenty clubs. A relegated club like Watford or Norwich would resolve to nothing there, which
 * is precisely the case these historical tools exist to serve. Only {@link LikePatterns} and
 * the "{@code ORDER BY} before {@code LIMIT}" determinism rule are shared with it.
 */
@Component
public class HistoricalTeamResolver {

    private final JdbcTemplate jdbc;
    private final String tableName;

    public HistoricalTeamResolver(JdbcTemplate jdbc,
                                  @Value("${fpl.historical.table-name}") String tableName) {
        this.jdbc = jdbc;
        this.tableName = tableName;
    }

    /** @return the resolved identity, or {@code null} if no club in the table matches */
    public TeamIdentity resolve(String teamName) {
        String pattern = "%" + LikePatterns.escape(teamName.toLowerCase(Locale.ROOT)) + "%";
        List<Map<String, Object>> rows = jdbc.queryForList("""
                (SELECT home_team_code AS code, home_team_name AS name FROM %1$s
                 WHERE LOWER(home_team_name) LIKE ?)
                UNION
                (SELECT away_team_code AS code, away_team_name AS name FROM %1$s
                 WHERE LOWER(away_team_name) LIKE ?)
                ORDER BY name
                LIMIT 1
                """.formatted(tableName), pattern, pattern); // tableName is config-injected, not user input
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.getFirst();
        return new TeamIdentity(((Number) row.get("code")).intValue(), (String) row.get("name"));
    }
}
