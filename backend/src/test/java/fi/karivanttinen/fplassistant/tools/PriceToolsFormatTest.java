package fi.karivanttinen.fplassistant.tools;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for the price-history rendering and the descriptive trend line.
 *
 * <p>Rewritten for the daily grain on 2026-08-18: rows are one per captured date rather than
 * one per gameweek, since the tool now reads {@code fpl_daily_price}.
 */
class PriceToolsFormatTest {

    private static Map<String, Object> row(String date, int price, int changeEvent, int changeStart) {
        Map<String, Object> r = new HashMap<>();
        r.put("price_date", LocalDate.parse(date));
        r.put("now_cost", price);
        r.put("cost_change_event", changeEvent);
        r.put("cost_change_start", changeStart);
        return r;
    }

    @Test
    void describesARiseAcrossTheCapturedDays() {
        String out = PriceTools.formatHistory("M.Salah", List.of(
                row("2026-09-04", 145, 2, 5), row("2026-09-03", 143, 1, 3),
                row("2026-09-02", 141, 0, 1), row("2026-09-01", 140, 0, 0)));

        assertThat(out).contains("2026-09-04: £14.5m");
        assertThat(out).contains("2026-09-01: £14.0m");
        assertThat(out).contains("Trend: price has risen by £0.5m between 2026-09-01 and 2026-09-04");
        assertThat(out).contains("descriptive, not a prediction");
    }

    @Test
    void describesAFall() {
        String out = PriceTools.formatHistory("Havertz", List.of(
                row("2026-09-02", 78, -2, -2), row("2026-09-01", 80, 0, 0)));

        assertThat(out).contains("Trend: price has fallen by £0.2m between 2026-09-01 and 2026-09-02");
    }

    @Test
    void aSingleCapturedDayIsNotDescribedAsATrend() {
        // The normal state on the day the capture begins. Comparing that row to itself would
        // otherwise render "held steady between 2026-09-01 and 2026-09-01".
        String out = PriceTools.formatHistory("M.Salah", List.of(row("2026-09-01", 145, 0, 0)));

        assertThat(out).contains("only one day has been captured so far (£14.5m)");
        assertThat(out).contains("no trend");
        assertThat(out).doesNotContain("held steady");
    }

    @Test
    void anUnchangedPriceIsReportedAsSteady() {
        String out = PriceTools.formatHistory("Raya", List.of(
                row("2026-09-03", 55, 0, 0), row("2026-09-02", 55, 0, 0), row("2026-09-01", 55, 0, 0)));

        assertThat(out).contains("held steady by £0.0m between 2026-09-01 and 2026-09-03");
    }

    @Test
    void reportsFplsOwnCountersFromTheNewestRow() {
        // These cover movement from before the capture began, which our own series cannot
        // describe — the newest row carries FPL's running totals.
        String out = PriceTools.formatHistory("M.Salah", List.of(
                row("2026-09-04", 145, 2, 9), row("2026-09-01", 140, 0, 4)));

        assertThat(out).contains("FPL's own counters: +0.2m this gameweek, +0.9m since the season started");
    }

    @Test
    void rendersNegativeCountersWithTheirSign() {
        String out = PriceTools.formatHistory("Havertz", List.of(row("2026-09-04", 78, -1, -3)));

        assertThat(out).contains("-0.1m this gameweek, -0.3m since the season started");
    }
}
