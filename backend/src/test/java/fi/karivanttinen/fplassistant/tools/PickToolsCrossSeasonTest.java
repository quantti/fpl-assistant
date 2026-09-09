package fi.karivanttinen.fplassistant.tools;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import fi.karivanttinen.fplassistant.schedule.ScheduleSource;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;

/** Previous-season ability is useful evidence but can never certify a current role. */
class PickToolsCrossSeasonTest {

    private static final String CURRENT = "T-2026-27";
    private static final String PREVIOUS = "T-2025-26";
    private static final LocalDate DATE = LocalDate.of(2026, 9, 9);

    @Test
    void historicalOnlyRegularIsExcludedEvenAtMaximumRealisedWeight() {
        SeasonContext current = new SeasonContext(9027, CURRENT, true, CURRENT,
                LocalDate.of(2026, 8, 14));
        SeasonContext previous = new SeasonContext(9026, PREVIOUS, false, CURRENT,
                LocalDate.of(2026, 8, 14));
        MetricSeasons seasons = new MetricSeasons(current, Optional.of(previous), 38, 38);
        DailyPriceSource.Capture capture = new DailyPriceSource.Capture(CURRENT, DATE);

        RankingMetrics.CapturePlayer player = new RankingMetrics.CapturePlayer(
                50, "OldRegular", "Old Regular", 9701, "OLD", "Old Town", 3,
                70, 5.0, "a", null, Instant.parse("2026-09-09T10:00:00Z"), 1);
        SeasonBlend.Exposure oldExposure = new SeasonBlend.Exposure(
                10, 10, 10, 900, 1, 1, 0, 90, 6);
        RankingMetrics.MinutesWindow oldWindow = new RankingMetrics.MinutesWindow(
                true, RankingMetrics.WindowReason.COMPLETE, 9601,
                Instant.parse("2025-08-01T12:00:00Z"),
                Instant.parse("2025-10-01T12:00:00Z"), 10, 10, 900, 10, 10, List.of());
        RankingMetrics.SeasonMetrics oldRecord = new RankingMetrics.SeasonMetrics(
                previous, 500, 3, Map.of("mins", 900.0, "xg90", 0.8),
                oldWindow, oldExposure);
        SeasonBlend.Blended historicalAbility = new SeasonBlend.Blended(
                Map.of("mins", 900.0, "xg90", 0.8, "element_type", 3),
                0, 0, true, null, true);
        RankingMetrics.Candidate candidate = new RankingMetrics.Candidate(
                player, null, oldRecord, historicalAbility,
                MinutesModel.Eligibility.UNKNOWN_CURRENT_EVIDENCE);
        ScheduleSource.View noSchedule = new ScheduleSource.View(Optional.empty(), List.of(),
                List.of(), new ScheduleSource.Completeness(false,
                        ScheduleSource.CompletenessReason.NO_CAPTURE));
        RankingMetrics.Snapshot snapshot = new RankingMetrics.Snapshot(
                Instant.parse("2026-09-09T12:00:00Z"), capture, seasons, noSchedule,
                List.of(candidate));

        SeasonResolver resolver = Mockito.mock(SeasonResolver.class);
        Mockito.when(resolver.resolve()).thenReturn(current);
        Mockito.when(resolver.metricSeasons(current)).thenReturn(seasons);
        DailyPriceSource daily = Mockito.mock(DailyPriceSource.class);
        Mockito.when(daily.latestCapture()).thenReturn(Optional.of(capture));
        RankingMetrics metrics = Mockito.mock(RankingMetrics.class);
        Mockito.when(metrics.load(capture, seasons)).thenReturn(snapshot);

        PickTools tools = new PickTools(resolver, daily, metrics);
        String result = tools.getCaptainCandidates(5);

        assertThat(result).contains("Schedule evidence: no FPL schedule capture")
                .contains("No captain candidates qualify")
                .contains("unknown current evidence 1")
                .doesNotContain(" OldRegular (")
                .doesNotContainIgnoringCase("guaranteed");
    }
}
