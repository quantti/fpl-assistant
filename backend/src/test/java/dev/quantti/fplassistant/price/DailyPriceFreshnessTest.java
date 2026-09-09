package dev.quantti.fplassistant.price;

import java.time.Instant;
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

    @Test
    void aRowDatedInTheFutureIsNotStaleButIsNotUsableAsFreshEvidence() {
        // Can only come from a machine with a skewed clock (a manual --sync-prices). The alert
        // keeps its separate warning, while ranking evidence applies the stricter predicate.
        LocalDate future = LocalDate.of(2026, 9, 12);
        LocalDateTime now = LocalDateTime.of(2026, 9, 11, 6, 0);
        assertThat(DailyPriceFreshnessCheck.isStale(future, now)).isFalse();
        assertThat(DailyPriceFreshnessCheck.isFresh(future,
                Instant.parse("2026-09-11T05:00:00Z"))).isFalse();
    }

    @Test
    void strictFreshnessUsesTheTwoAmUkCutoverInGmtAndBst() {
        LocalDate winterYesterday = LocalDate.of(2026, 1, 14);
        assertThat(DailyPriceFreshnessCheck.isFresh(winterYesterday,
                Instant.parse("2026-01-15T01:59:59Z"))).isTrue();
        assertThat(DailyPriceFreshnessCheck.isFresh(winterYesterday,
                Instant.parse("2026-01-15T02:00:00Z"))).isFalse();
        assertThat(DailyPriceFreshnessCheck.isFresh(winterYesterday.plusDays(1),
                Instant.parse("2026-01-15T02:00:00Z"))).isTrue();

        LocalDate summerYesterday = LocalDate.of(2026, 7, 14);
        assertThat(DailyPriceFreshnessCheck.isFresh(summerYesterday,
                Instant.parse("2026-07-15T00:59:59Z"))).isTrue();
        assertThat(DailyPriceFreshnessCheck.isFresh(summerYesterday,
                Instant.parse("2026-07-15T01:00:00Z"))).isFalse();
        assertThat(DailyPriceFreshnessCheck.isFresh(summerYesterday.plusDays(1),
                Instant.parse("2026-07-15T01:00:00Z"))).isTrue();
    }
}
