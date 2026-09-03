package fi.karivanttinen.fplassistant.schedule;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

import fi.karivanttinen.fplassistant.tools.UkTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Alerts when the schedule capture has gone stale — the counterpart of
 * {@code DailyPriceFreshnessCheck}, for the same reason: a scheduler that silently stops
 * (a reverted always-on Fly setting, a changed config, an FPL response shape change) would
 * otherwise only show up as an ageing "captured N days ago" line inside chat replies. The
 * capture runs at 02:10 UK; by the 06:00 check {@code MAX(captured_at)} must fall on today's
 * UK date.
 *
 * <p>Expected noise: across the summer reset the sync legitimately refuses to write (finished
 * season, July guard), so this alert fires until FPL publishes the new season. That is the
 * same accepted noise the price check produces, and it is noise about a true statement — the
 * snapshot really is old.
 *
 * <p>Gated on the same property as the capture itself.
 */
@Component
@ConditionalOnProperty(name = "fpl.schedule.scheduled", havingValue = "true")
public class ScheduleFreshnessCheck {

    private static final Logger log = LoggerFactory.getLogger(ScheduleFreshnessCheck.class);

    /** The capture fires at 02:10 UK; from this time on, today's capture must exist. */
    static final LocalTime CAPTURE_SETTLED = LocalTime.of(3, 0);

    private final JdbcTemplate jdbc;
    private final String gameweekTable;

    public ScheduleFreshnessCheck(JdbcTemplate jdbc,
                                  @Value("${fpl.schedule.gameweek-table-name}") String gameweekTable) {
        this.jdbc = jdbc;
        this.gameweekTable = gameweekTable;
    }

    @Scheduled(cron = "${fpl.schedule.check-at}", zone = "${fpl.schedule.fetch-zone}")
    public void checkFreshness() {
        try {
            Instant latest = jdbc.queryForObject(
                    "SELECT MAX(captured_at) FROM %s".formatted(gameweekTable), Instant.class);
            if (latest == null) {
                log.warn("{} has no rows yet; freshness check skipped. If this is production,"
                        + " the 02:10 UK capture has never run — run --sync-schedule by hand.",
                        gameweekTable);
                return;
            }
            LocalDate latestDay = LocalDate.ofInstant(latest, UkTime.zone());
            LocalDateTime now = LocalDateTime.now(UkTime.zone());
            if (isStale(latestDay, now)) {
                log.error("SCHEDULE CAPTURE IS STALE: latest capture is {} ({} days behind)."
                                + " The 02:10 UK capture has missed at least one day — check the"
                                + " always-on Fly machine setting and fpl.schedule config, or run"
                                + " --sync-schedule by hand. (Expected across the summer reset,"
                                + " when FPL still serves the finished season and the sync refuses"
                                + " to write.)",
                        latestDay, ChronoUnit.DAYS.between(latestDay, now.toLocalDate()));
            }
        } catch (DataAccessException e) {
            // The check must never do more than fail to run; the capture itself is unaffected.
            log.error("freshness check could not read {}", gameweekTable, e);
        }
    }

    /**
     * From {@link #CAPTURE_SETTLED} onwards the latest capture should be today; before it,
     * yesterday — anything older means at least one capture was missed. Same rule as the price
     * check, on the schedule's own clock.
     */
    static boolean isStale(LocalDate latestCaptureDay, LocalDateTime nowUk) {
        LocalDate expected = nowUk.toLocalTime().isBefore(CAPTURE_SETTLED)
                ? nowUk.toLocalDate().minusDays(1)
                : nowUk.toLocalDate();
        return latestCaptureDay.isBefore(expected);
    }
}
