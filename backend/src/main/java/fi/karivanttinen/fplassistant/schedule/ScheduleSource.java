package fi.karivanttinen.fplassistant.schedule;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import fi.karivanttinen.fplassistant.tools.UkTime;
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
        return Optional.of(new Snapshot((String) row.get("season"), instantOf(row.get("captured_at"))));
    }

    /**
     * pgjdbc usually hands a TIMESTAMPTZ back as {@code java.sql.Timestamp} from an untyped
     * getObject, but {@code UkTime}'s javadoc records that it can be an {@code OffsetDateTime}
     * depending on configuration — and a blind cast would escape the tools'
     * {@code catch (DataAccessException)} as a raw ClassCastException.
     */
    private static Instant instantOf(Object value) {
        return switch (value) {
            case java.sql.Timestamp ts -> ts.toInstant();
            case OffsetDateTime odt -> odt.toInstant();
            case Instant i -> i;
            default -> throw new IllegalStateException(
                    "captured_at came back as " + (value == null ? "null" : value.getClass().getName()));
        };
    }

    /**
     * The gameweek the fixture listing starts from: the round being played (latest passed
     * deadline, unfinished), else the next round, else the season's last — a finished season
     * lists its final round rather than opening day. This is deliberately not the same question
     * {@code GameweekTools} answers with its current/next scan: that reports the round being
     * played even once it is finished, this names where an "upcoming fixtures" answer begins.
     * One derivation here so the two tools cannot disagree on the deadline arithmetic.
     */
    public Integer startGameweek(String season) {
        return jdbc.queryForObject("""
                SELECT COALESCE(
                    (SELECT MAX(id) FROM %1$s WHERE season = ? AND deadline_time <= now() AND finished = false),
                    (SELECT MIN(id) FROM %1$s WHERE season = ? AND deadline_time > now()),
                    (SELECT MAX(id) FROM %1$s WHERE season = ?))
                """.formatted(gameweekTable),
                Integer.class, season, season, season);
    }

    public static String provenance(String season, Instant capturedAt, Instant now) {
        long daysOld = Math.max(0, ChronoUnit.DAYS.between(
                LocalDate.ofInstant(capturedAt, UkTime.zone()), LocalDate.ofInstant(now, UkTime.zone())));
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
