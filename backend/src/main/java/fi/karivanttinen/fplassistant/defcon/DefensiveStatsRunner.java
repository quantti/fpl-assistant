package fi.karivanttinen.fplassistant.defcon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * {@code --sync-defcon}: fills the defensive-contribution columns once and exits, for the first
 * run of a season and for repairing a night the scheduler missed. Opt-in like the other runners,
 * so an ordinary boot does nothing.
 */
@Component
public class DefensiveStatsRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DefensiveStatsRunner.class);

    private final DefensiveStatsSyncService syncService;

    public DefensiveStatsRunner(DefensiveStatsSyncService syncService) {
        this.syncService = syncService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption("sync-defcon")) {
            return;
        }
        int updated = syncService.sync();
        log.info("--sync-defcon: {} rows updated", updated);
    }
}
