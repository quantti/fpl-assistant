package fi.karivanttinen.fplassistant.price;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * The update script: captures today's FPL prices and exits.
 *
 * <p>Does nothing on a normal start. Launched with {@code --sync-prices} it fetches
 * {@code bootstrap-static}, upserts one row per player into {@code fpl_daily_price} for
 * today's UK date, and exits the JVM so no web server lingers — the same shape as
 * {@code IngestionRunner} and {@code VaastavBackfillRunner}.
 *
 * <pre>
 * ./gradlew bootRun --args="--sync-prices"
 * java -jar app.jar --sync-prices
 * </pre>
 *
 * <p><b>Exit codes</b>, because this is meant to be driven by a scheduler that can only see
 * the exit status: {@code 0} on success, {@code 1} if the fetch or the write failed. A
 * non-zero exit is what makes a missed day visible instead of silent.
 *
 * <p>Re-running on the same day is safe and overwrites that day's rows, so a scheduler that
 * retries after a failure needs no special handling.
 */
@Component
@ConditionalOnExpression("'${fpl.owned-sync.mode:OFF}' != 'ACTIVE'")
public class DailyPriceRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DailyPriceRunner.class);

    private final DailyPriceSyncService syncService;

    public DailyPriceRunner(DailyPriceSyncService syncService) {
        this.syncService = syncService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption("sync-prices")) {
            return;
        }
        try {
            int written = syncService.sync();
            log.info("--sync-prices complete: {} rows; exiting", written);
            System.exit(written > 0 ? 0 : 1);
        } catch (Exception e) {
            // Caught deliberately: the scheduler reads the exit code, and an uncaught
            // exception here would exit 1 anyway but without a readable one-line reason.
            log.error("--sync-prices failed", e);
            System.exit(1);
        }
    }
}
