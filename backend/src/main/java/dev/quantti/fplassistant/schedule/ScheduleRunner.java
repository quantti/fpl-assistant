package dev.quantti.fplassistant.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * One-shot CLI: {@code --sync-schedule} captures FPL's gameweeks and fixtures and exits, the
 * same shape as {@code --sync-prices}. Exit code 1 when nothing was written, so a cron or a
 * human notices.
 */
@Component
public class ScheduleRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ScheduleRunner.class);

    private final ScheduleSyncService syncService;

    public ScheduleRunner(ScheduleSyncService syncService) {
        this.syncService = syncService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption("sync-schedule")) {
            return;
        }
        try {
            int written = syncService.sync();
            log.info("--sync-schedule complete: {} rows; exiting", written);
            System.exit(written > 0 ? 0 : 1);
        } catch (Exception e) {
            log.error("--sync-schedule failed", e);
            System.exit(1);
        }
    }
}
