package dev.quantti.fplassistant.price;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the staleness rule behind the freshness alert.
 *
 * <p>The capture writes one row per UK date at 02:00 UK, so from 02:00 onwards the latest row
 * should be today and before 02:00 yesterday. The rule has to know the time of day to avoid a
 * false alarm every morning before the capture runs — and a test run at 01:00 must not flip a
 * "today is fresh" assertion, so the rule takes the clock rather than reading it.
 */
class DailyPriceFreshnessTest {

    @Test
    void beforeTheTwoAmCaptureYesterdayIsExpectedAndFresh() {
        assertThat(DailyPriceFreshnessCheck.isStale(
                LocalDate.of(2026, 9, 10), LocalDateTime.of(2026, 9, 11, 1, 59)))
                .isFalse();
    }

    @Test
    void afterTheTwoAmCaptureYesterdayIsStale() {
        assertThat(DailyPriceFreshnessCheck.isStale(
                LocalDate.of(2026, 9, 10), LocalDateTime.of(2026, 9, 11, 6, 0)))
                .isTrue();
    }

    @Test
    void todaysRowIsFreshFromTwoAmOnwards() {
        assertThat(DailyPriceFreshnessCheck.isStale(
                LocalDate.of(2026, 9, 11), LocalDateTime.of(2026, 9, 11, 6, 0)))
                .isFalse();
        // Exactly at 02:00 the current day's row is expected.
        assertThat(DailyPriceFreshnessCheck.isStale(
                LocalDate.of(2026, 9, 11), LocalDateTime.of(2026, 9, 11, 2, 0)))
                .isFalse();
    }

    @Test
    void multipleMissedDaysAreStale() {
        assertThat(DailyPriceFreshnessCheck.isStale(
                LocalDate.of(2026, 9, 9), LocalDateTime.of(2026, 9, 11, 6, 0)))
                .isTrue();
    }
}
