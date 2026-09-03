package dev.quantti.fplassistant.schedule;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduleSourceTest {

    private static final Instant NOW = Instant.parse("2026-08-29T12:00:00Z");

    @Test
    void namesTheSeasonAndTheCaptureAge() {
        String line = ScheduleSource.provenance("2026-27", Instant.parse("2026-08-29T01:10:00Z"), NOW);

        assertThat(line).startsWith("Data source: FPL's own gameweek and fixture list for season 2026-27");
        assertThat(line).contains("captured today");
    }

    @Test
    void anOldCaptureSaysHowOldItIs() {
        String line = ScheduleSource.provenance("2026-27", Instant.parse("2026-08-26T01:10:00Z"), NOW);

        assertThat(line).contains("captured 3 days ago");
        assertThat(line).contains("scores and postponements may have changed since");
    }
}
