package fi.karivanttinen.fplassistant.shortlist;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import fi.karivanttinen.fplassistant.schedule.HorizonFixtures;
import fi.karivanttinen.fplassistant.schedule.ScheduleSource;
import fi.karivanttinen.fplassistant.tools.ClubRates;
import fi.karivanttinen.fplassistant.tools.ComparisonCoverage;
import fi.karivanttinen.fplassistant.tools.DailyPriceSource;
import fi.karivanttinen.fplassistant.tools.RankingMetrics;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ShortlistComparisonTest {
    private static final Instant NOW = Instant.parse("2026-09-09T12:00:00Z");

    @Test
    void priceUnitsAndXgiKeepUnknownDistinctFromZero() {
        assertThat(ShortlistComparison.value(60, 60)).isEqualTo(10);
        assertThat(ShortlistComparison.value(0, 60)).isZero();
        assertThat(ShortlistComparison.value(60, 0)).isNull();
        assertThat(ShortlistComparison.value(null, 60)).isNull();
        assertThat(ShortlistComparison.sum(1.2, 0.3)).isEqualTo(1.5);
        assertThat(ShortlistComparison.sum(0.0, 0.0)).isZero();
        assertThat(ShortlistComparison.sum(null, 0.0)).isNull();
    }

    @Test
    void doublesCountTwiceAndBlankWeeksStayInFiveWeekWindow() {
        var forecast = ShortlistComparison.forecast(snapshot(true), horizon(List.of(
                fixture(1, 6, 2, 3), fixture(2, 6, 2, 0), fixture(3, 8, 2, 4))), 1, Map.of(2, "OPP"));
        assertThat(forecast.weeks()).hasSize(5);
        assertThat(forecast.weeks().getFirst().fixtures()).hasSize(2);
        assertThat(forecast.weeks().get(1).fixtures()).isEmpty();
        assertThat(forecast.weeks().getFirst().fixtures().get(1).difficulty()).isNull();
        assertThat(forecast.expectedCleanSheets()).isCloseTo(3 * Math.exp(-1.2), within(1e-9));
    }

    @Test
    void missingProjectionNeverBecomesPartialTotalButFplPlayerCoverageDoesNotGateUnderstat() {
        var missing = ShortlistComparison.forecast(snapshot(true), horizon(List.of(
                fixture(1, 6, 2, 3), fixture(2, 7, 99, 3))), 1, Map.of(99, "HUL"));
        assertThat(missing.expectedCleanSheets()).isNull();
        assertThat(missing.reason()).contains("GW7 vs HUL", "Understat opponent model unavailable");
        assertThat(ShortlistComparison.forecast(snapshot(false), horizon(List.of(fixture(1, 6, 2, 3))),
                1, Map.of()).expectedCleanSheets()).isCloseTo(Math.exp(-1.2), within(1e-9));
        assertThat(ShortlistComparison.forecast(snapshot(true), horizon(List.of()),
                1, Map.of()).expectedCleanSheets()).isZero();
        var unknown = new HorizonFixtures.Result(NOW, Optional.empty(), 5, HorizonFixtures.Status.NO_EDITABLE_GAMEWEEK,
                HorizonFixtures.UnknownReason.NO_EDITABLE_GAMEWEEK, null, null, Map.of());
        assertThat(ShortlistComparison.forecast(snapshot(true), unknown, 1, Map.of()).expectedCleanSheets()).isNull();
    }

    @Test
    void defconDenominatorExcludesMissingReadingsAndZeroMinutes() {
        var result = ShortlistComparison.defcon(List.of(
                Map.of("minutes", 90, "defensive_contribution", 10),
                Map.of("minutes", 30, "defensive_contribution", 0),
                Map.of("minutes", 0, "defensive_contribution", 0),
                Map.of("minutes", 90)), 2);
        assertThat(result.threshold()).isEqualTo(10);
        assertThat(result.hits()).isEqualTo(1);
        assertThat(result.readings()).isEqualTo(2);
        assertThat(result.observedAppearances()).isEqualTo(3);
        assertThat(result.actionsPer90()).isEqualTo(7.5);
    }

    private static HorizonFixtures.Fixture fixture(int id, int gw, int opponent, int fdr) {
        return new HorizonFixtures.Fixture(id, gw, true, opponent, NOW.plusSeconds(86400L * gw), fdr);
    }
    private static HorizonFixtures.Result horizon(List<HorizonFixtures.Fixture> fixtures) {
        return new HorizonFixtures.Result(NOW, Optional.empty(), 5, HorizonFixtures.Status.KNOWN,
                null, 6, 10, Map.of(1, new HorizonFixtures.ClubWindow(1, fixtures, null)));
    }
    private static RankingMetrics.Snapshot snapshot(boolean covered) {
        var rate = new ClubRates.ClubRate(1.2, 1.2, 5, 1, 5, false);
        var rates = new ClubRates.Rates(Map.of(1, rate, 2, rate), Optional.of(new ClubRates.League(1.2, 1, 1, 100)));
        var view = new ScheduleSource.View(Optional.empty(), List.of(), List.of(),
                new ScheduleSource.Completeness(false, ScheduleSource.CompletenessReason.NO_CAPTURE));
        var coverage = new ComparisonCoverage(List.of(), List.of(), 0, 0, covered, ComparisonCoverage.EvidenceStatus.LEGACY);
        return new RankingMetrics.Snapshot(NOW, new DailyPriceSource.Capture("SC-CUR", LocalDate.of(2026, 9, 9)),
                null, view, List.of(), rates, "test", coverage);
    }
}
