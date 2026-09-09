package dev.quantti.fplassistant.price;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Alerts when the daily price capture has gone stale, so a missed day is loud instead of
 * silent. The capture ({@link DailyPriceScheduler}) writes one row per UK date at 02:00 UK;
 * by 06:00 the same day that row must exist. If it does not, something stopped the capture —
 * a scheduler failure, a reverted always-on Fly setting, or an FPL response whose shape
 * changed so the sync wrote zero rows — and this check is the difference between finding out
 * within hours and finding out when a user asks why a price is wrong.
 *
 * <p>Checks at 06:00 / 12:00 / 18:00 UK, all safely after the 02:00 capture window. Silent
 * when healthy; ERROR when stale; WARN when the table has no rows at all (a fresh environment
 * where the capture has never run — the tools already answer "no data captured yet" for that
 * state).
 *
 * <p>Gated on the same property as the capture itself: disabling the scheduled capture
 * disables the alert that watches it.
 */
@Component
@ConditionalOnProperty(name = "fpl.daily-price.scheduled", havingValue = "true")
public class DailyPriceFreshnessCheck {

    private static final Logger log = LoggerFactory.getLogger(DailyPriceFreshnessCheck.class);
    private static final ZoneId UK = ZoneId.of("Europe/London");

    private final JdbcTemplate jdbc;
    private final String tableName;

    public DailyPriceFreshnessCheck(JdbcTemplate jdbc,
                                    @Value("${fpl.daily-price.table-name}") String tableName) {
        this.jdbc = jdbc;
        this.tableName = tableName;
    }

    @Scheduled(cron = "${fpl.daily-price.check-at}", zone = "${fpl.daily-price.fetch-zone}")
    public void checkFreshness() {
        try {
            LocalDate latest = jdbc.queryForObject(
                    "SELECT MAX(price_date) FROM %s".formatted(tableName), LocalDate.class);
            if (latest == null) {
                log.warn("fpl_daily_price has no rows yet; freshness check skipped. If this is"
                        + " production, the 02:00 UK capture has never run — check the always-on"
                        + " Fly machine setting.");
                return;
            }
            LocalDateTime now = LocalDateTime.now(UK);
            if (isStale(latest, now)) {
                log.error("DAILY PRICE CAPTURE IS STALE: latest row is {} but {} was expected"
                                + " ({} days behind). The 02:00 UK capture has missed at least one"
                                + " day — check the always-on Fly machine setting and"
                                + " fpl.daily-price config, or run --sync-prices by hand.",
                        latest, expectedDate(now),
                        ChronoUnit.DAYS.between(latest, now.toLocalDate()));
            } else if (latest.isAfter(now.toLocalDate())) {
                // A row dated in the future can only come from a machine whose clock runs
                // ahead (e.g. a manual --sync-prices from a skewed laptop). It also suppresses
                // staleness alerts for as long as it stays the newest row, so it deserves a
                // warning rather than silence.
                log.warn("fpl_daily_price has a row dated {} — in the future relative to this"
                        + " container's clock. A capture was run from a machine with a skewed"
                        + " clock; the freshness alert cannot watch the series until it catches"
                        + " up.", latest);
            }
        } catch (DataAccessException e) {
            // The check must never do more than fail to run; the capture itself is unaffected.
            log.error("freshness check could not read fpl_daily_price", e);
        }
    }

    /**
     * The capture runs at 02:00 UK and writes a row dated that UK day. So from 02:00 onwards
     * the latest row should be today; before 02:00 it should be yesterday — anything older
     * means at least one capture was missed. Takes the clock rather than reading it so the
     * rule can be pinned in tests regardless of when they run.
     */
    static boolean isStale(LocalDate latest, LocalDateTime nowUk) {
        return latest.isBefore(expectedDate(nowUk));
    }

    /**
     * Strict predicate for selection evidence. Unlike the alert's lower-bound check, this
     * rejects both future captures and a row dated today before today's capture is due.
     */
    public static boolean isFresh(LocalDate captured, Instant asOf) {
        return captured != null && captured.equals(expectedDate(LocalDateTime.ofInstant(asOf, UK)));
    }

    static LocalDate expectedDate(LocalDateTime nowUk) {
        LocalDate today = nowUk.toLocalDate();
        return nowUk.getHour() < 2 ? today.minusDays(1) : today;
    }
}
