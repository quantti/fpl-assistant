package fi.karivanttinen.fplassistant.defcon;

import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * {@code --sync-defcon}: fills the defensive-contribution columns for the season being played
 * once and exits, for the first run of a season and for repairing a night the scheduler missed.
 *
 * <p>{@code --backfill-defcon=2025-26}: fills a completed season from vaastav instead. A separate
 * flag because it is a separate source for a separate reason — FPL serves per-match rows for the
 * current season only.
 *
 * <p>Both are opt-in like the other runners, so an ordinary boot does nothing.
 */
@Component
public class DefensiveStatsRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DefensiveStatsRunner.class);

    private final DefensiveStatsSyncService syncService;
    private final DefensiveStatsBackfillService backfillService;
    private final boolean ownedActive;

    public DefensiveStatsRunner(DefensiveStatsSyncService syncService,
                                DefensiveStatsBackfillService backfillService) {
        this(syncService, backfillService, "OFF");
    }

    @Autowired
    public DefensiveStatsRunner(DefensiveStatsSyncService syncService,
                                DefensiveStatsBackfillService backfillService,
                                @Value("${fpl.owned-sync.mode:OFF}") String ownedMode) {
        this.syncService = syncService;
        this.backfillService = backfillService;
        this.ownedActive = "ACTIVE".equals(ownedMode);
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean backfilling = args.containsOption("backfill-defcon");
        boolean syncing = args.containsOption("sync-defcon");
        if (!backfilling && !syncing) {
            return;
        }
        // Exits like DailyPriceRunner, and for the same reason: whatever drives this reads the
        // exit code, and a runner that returns leaves a web server up forever with the scheduler
        // armed. The catch is what turns a failure into an exit code instead of a failed context
        // refresh — an exception escaping run() aborts startup, which reads as "the app is
        // broken" rather than "this one job did not work".
        try {
            int updated = 0;
            if (backfilling) {
                List<String> seasons = requestedSeasons(args.getOptionValues("backfill-defcon"));
                if (seasons.isEmpty()) throw new IllegalArgumentException("--backfill-defcon requires a season");
                // Validate the complete request before the first archive fetch/write, so a comma
                // list cannot partly mutate archives before reaching the playing season.
                seasons.forEach(backfillService::validateArchiveSeason);
                for (String season : seasons) {
                    int rows = backfillService.backfill(season);
                    log.info("--backfill-defcon={}: {} rows updated", season, rows);
                    updated += rows;
                }
            }
            if (syncing && !ownedActive) {
                int rows = syncService.sync();
                log.info("--sync-defcon: {} rows updated", rows);
                updated += rows;
            } else if (syncing) {
                log.info("--sync-defcon is disabled after owned-sync ACTIVE cutover");
            }
            System.exit(updated > 0 ? 0 : 1);
        } catch (Exception e) {
            log.error("defcon run failed; nothing may have been written", e);
            System.exit(1);
        }
    }

    static List<String> requestedSeasons(List<String> values) {
        return values.stream().flatMap(value -> Arrays.stream(value.split(",")))
                .map(String::trim).filter(value -> !value.isEmpty()).toList();
    }
}
