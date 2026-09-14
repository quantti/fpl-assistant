package dev.quantti.fplassistant.defcon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Refreshes the defensive-contribution columns on a clock.
 *
 * <p><b>Timing.</b> 03:00 Europe/London — an hour after the daily price capture, so the two
 * never share the scheduler thread. This one runs for minutes rather than seconds (one request
 * per player), and holding the 02:00 capture behind it would delay the one job that cannot
 * catch up later: FPL publishes only today's price, while these per-match rows can be re-fetched
 * any night.
 *
 * <p>Nightly rather than weekly because it is cheap to be current and expensive to be wrong: a
 * gameweek's rows land as tapas syncs them, and an update run that finds nothing new writes
 * nothing.
 *
 * <p><b>Failure is logged, never thrown.</b> A missed night leaves the previous night's values
 * in place — they are not wiped — so the cost of a miss is staleness, not a gap, and the next
 * run repairs it.
 */
@Component
@ConditionalOnProperty(name = "fpl.defcon.scheduled", havingValue = "true")
@ConditionalOnExpression("'${fpl.owned-sync.mode:OFF}' != 'ACTIVE'")
public class DefensiveStatsScheduler {

    private static final Logger log = LoggerFactory.getLogger(DefensiveStatsScheduler.class);

    private final DefensiveStatsSyncService syncService;

    public DefensiveStatsScheduler(DefensiveStatsSyncService syncService) {
        this.syncService = syncService;
    }

    @Scheduled(cron = "${fpl.defcon.fetch-at}", zone = "${fpl.defcon.fetch-zone}")
    public void refreshDefensiveStats() {
        try {
            int updated = syncService.sync();
            log.info("scheduled defcon refresh: {} rows", updated);
        } catch (Exception e) {
            log.error("scheduled defcon refresh failed; the columns keep the previous run's"
                    + " values until the next run or a manual --sync-defcon", e);
        }
    }
}
