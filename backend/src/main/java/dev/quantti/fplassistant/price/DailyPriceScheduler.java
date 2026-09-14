package dev.quantti.fplassistant.price;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Captures the day's FPL prices on a clock, so the app has today's prices without anyone
 * running {@link DailyPriceRunner} by hand.
 *
 * <p><b>Timing.</b> FPL applies price changes once a day at 00:00 UK — moved from the
 * long-standing ~01:30 GMT / 02:30 BST for the 2026/27 season
 * (premierleague.com/en/news/4680462). The cron is 02:00 Europe/London: two hours after the
 * change, per the owner's instruction, which also leaves room for FPL's own overnight jobs to
 * settle before we read them.
 *
 * <p>{@code Europe/London} rather than UTC, so the job keeps landing two hours after the price
 * change through the GMT/BST switch instead of drifting to 03:00 for half the year.
 *
 * <p><b>Failure is logged, never thrown.</b> A scheduled method that throws stops nothing else,
 * but it also tells nobody anything useful; the log line is the signal. A missed day leaves a
 * gap in the series rather than corrupting it, and the next day's run is unaffected — the write
 * is keyed by date and upserts, so a manual {@code --sync-prices} can backfill nothing but
 * today. (Genuinely missed past days cannot be recovered: FPL publishes only the current
 * price, which is exactly why this runs daily.)
 */
@Component
@ConditionalOnProperty(name = "fpl.daily-price.scheduled", havingValue = "true")
@ConditionalOnExpression("'${fpl.owned-sync.mode:OFF}' != 'ACTIVE'")
public class DailyPriceScheduler {

    private static final Logger log = LoggerFactory.getLogger(DailyPriceScheduler.class);

    private final DailyPriceSyncService syncService;

    public DailyPriceScheduler(DailyPriceSyncService syncService) {
        this.syncService = syncService;
    }

    @Scheduled(cron = "${fpl.daily-price.fetch-at}", zone = "${fpl.daily-price.fetch-zone}")
    public void captureTodaysPrices() {
        try {
            int written = syncService.sync();
            log.info("scheduled daily price capture: {} rows", written);
        } catch (Exception e) {
            log.error("scheduled daily price capture failed; today's prices will be missing"
                    + " until the next run or a manual --sync-prices", e);
        }
    }
}
