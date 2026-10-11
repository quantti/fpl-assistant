package fi.karivanttinen.fplassistant.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fires the schedule capture daily, ten minutes after the price capture on the same clock:
 * the machine is up and FPL's overnight jobs have settled. Gated by {@code fpl.schedule.scheduled}
 * so the test profile never reaches the network.
 */
@Component
@ConditionalOnProperty(name = "fpl.schedule.scheduled", havingValue = "true")
@ConditionalOnExpression("T(fi.karivanttinen.fplassistant.sync.OwnedSyncRuntimeConfiguration).catalogueCaptureEnabled("
        + "'${fpl.owned-sync.mode:OFF}', ${fpl.owned-sync.importer.enabled:false})")
public class ScheduleScheduler {

    private static final Logger log = LoggerFactory.getLogger(ScheduleScheduler.class);

    private final ScheduleSyncService syncService;

    public ScheduleScheduler(ScheduleSyncService syncService) {
        this.syncService = syncService;
    }

    @Scheduled(cron = "${fpl.schedule.fetch-at}", zone = "${fpl.schedule.fetch-zone}")
    public void captureSchedule() {
        try {
            log.info("scheduled schedule capture: {} rows", syncService.sync());
        } catch (Exception e) {
            log.error("scheduled schedule capture failed; gameweeks and fixtures keep yesterday's"
                    + " capture until the next run or a manual --sync-schedule", e);
        }
    }
}
