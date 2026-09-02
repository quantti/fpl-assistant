package dev.quantti.fplassistant.schedule;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Read side of the schedule capture, shared by {@code GameweekTools} and {@code FixtureTools}.
 *
 * <p>The provenance line is this capture's own, not {@code SeasonContext.provenance()}: the
 * season is read from the rows, which during the changeover is the season being played while
 * the resolver still names the completed one — the same reasoning as
 * {@code DailyPriceSource.provenance()}, and a documented exception in {@code AGENTS.md}.
 */
@Component
public class ScheduleSource {

    private static final ZoneId UK = ZoneId.of("Europe/London");

    public record Snapshot(String season, Instant capturedAt) {
    }

    private final JdbcTemplate jdbc;
    private final String gameweekTable;
    private final String fixtureTable;

    public ScheduleSource(JdbcTemplate jdbc,
                          @Value("${fpl.schedule.gameweek-table-name}") String gameweekTable,
                          @Value("${fpl.schedule.fixture-table-name}") String fixtureTable) {
        this.jdbc = jdbc;
        this.gameweekTable = gameweekTable;
        this.fixtureTable = fixtureTable;
    }

    public String gameweekTable() {
        return gameweekTable;
    }

    public String fixtureTable() {
        return fixtureTable;
    }

    /** The most recently captured season, or empty before the first capture. */
    public Optional<Snapshot> latest() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT season, MAX(captured_at) AS captured_at FROM %s
                GROUP BY season ORDER BY MAX(captured_at) DESC LIMIT 1
                """.formatted(gameweekTable));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.getFirst();
        // pgjdbc hands a TIMESTAMPTZ back as java.sql.Timestamp from an untyped getObject —
        // UkTime.format documents the same fact. Not OffsetDateTime; that cast throws.
        Instant at = ((java.sql.Timestamp) row.get("captured_at")).toInstant();
        return Optional.of(new Snapshot((String) row.get("season"), at));
    }

    public static String provenance(String season, Instant capturedAt, Instant now) {
        long daysOld = Math.max(0, ChronoUnit.DAYS.between(
                LocalDate.ofInstant(capturedAt, UK), LocalDate.ofInstant(now, UK)));
        String age = switch ((int) Math.min(daysOld, 2)) {
            case 0 -> "captured today";
            case 1 -> "captured yesterday";
            default -> "captured %d days ago".formatted(daysOld);
        };
        return ("Data source: FPL's own gameweek and fixture list for season %s, %s. Deadlines are as"
                + " FPL publishes them and gameweek status is judged against the clock now; scores"
                + " and postponements may have changed since the capture.").formatted(season, age);
    }
}
