package dev.quantti.fplassistant.schedule;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Pins the staleness rule itself; the query and the log lines are trivial around it. */
class ScheduleFreshnessCheckTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 15);

    @Test
    void aCaptureFromTodayIsFresh() {
        assertThat(ScheduleFreshnessCheck.isStale(TODAY, TODAY.atTime(12, 0))).isFalse();
    }

    @Test
    void yesterdaysCaptureIsStillFreshBeforeTheCaptureWindowHasSettled() {
        assertThat(ScheduleFreshnessCheck.isStale(TODAY.minusDays(1), TODAY.atTime(2, 30))).isFalse();
    }

    @Test
    void yesterdaysCaptureIsStaleOnceTodaysShouldExist() {
        assertThat(ScheduleFreshnessCheck.isStale(TODAY.minusDays(1), TODAY.atTime(6, 0))).isTrue();
    }

    @Test
    void aTwoDayOldCaptureIsStaleEvenBeforeTheWindow() {
        assertThat(ScheduleFreshnessCheck.isStale(TODAY.minusDays(2), TODAY.atTime(2, 30))).isTrue();
    }
}
