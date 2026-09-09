package fi.karivanttinen.fplassistant.schedule;

import java.time.Instant;
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

    @Test
    void strictFreshnessUsesTheThreeAmUkCutoverInGmtAndBst() {
        LocalDate winterYesterday = LocalDate.of(2026, 1, 14);
        assertThat(ScheduleFreshnessCheck.isFresh(winterYesterday,
                Instant.parse("2026-01-15T02:59:59Z"))).isTrue();
        assertThat(ScheduleFreshnessCheck.isFresh(winterYesterday,
                Instant.parse("2026-01-15T03:00:00Z"))).isFalse();
        assertThat(ScheduleFreshnessCheck.isFresh(winterYesterday.plusDays(1),
                Instant.parse("2026-01-15T03:00:00Z"))).isTrue();

        LocalDate summerYesterday = LocalDate.of(2026, 7, 14);
        assertThat(ScheduleFreshnessCheck.isFresh(summerYesterday,
                Instant.parse("2026-07-15T01:59:59Z"))).isTrue();
        assertThat(ScheduleFreshnessCheck.isFresh(summerYesterday,
                Instant.parse("2026-07-15T02:00:00Z"))).isFalse();
        assertThat(ScheduleFreshnessCheck.isFresh(summerYesterday.plusDays(1),
                Instant.parse("2026-07-15T02:00:00Z"))).isTrue();
    }

    @Test
    void aFutureCaptureIsNotFresh() {
        assertThat(ScheduleFreshnessCheck.isFresh(TODAY.plusDays(1),
                Instant.parse("2026-09-15T11:00:00Z"))).isFalse();
    }
}
