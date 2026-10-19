package fi.karivanttinen.fplassistant.schedule;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ScheduleSchedulerTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(ScheduleScheduler.class)
            .withBean(ScheduleSyncService.class, () -> mock(ScheduleSyncService.class))
            .withPropertyValues("fpl.schedule.scheduled=true");

    @Test
    void activeImporterOwnsTheScheduleWithoutACompetingDailyWriter() {
        context.withPropertyValues("fpl.owned-sync.mode=ACTIVE", "fpl.owned-sync.importer.enabled=true")
                .run(result -> assertThat(result).doesNotHaveBean(ScheduleScheduler.class));
    }

    @Test
    void activeLegacyRuntimeAlsoOwnsTheSchedule() {
        context.withPropertyValues("fpl.owned-sync.mode=ACTIVE", "fpl.owned-sync.importer.enabled=false")
                .run(result -> assertThat(result).doesNotHaveBean(ScheduleScheduler.class));
    }

    @Test
    void offAndStagingRetainTheExistingDailyScheduleCapture() {
        for (String mode : new String[]{"OFF", "STAGING"}) {
            context.withPropertyValues("fpl.owned-sync.mode=" + mode)
                    .run(result -> assertThat(result).hasSingleBean(ScheduleScheduler.class));
        }
    }
}
