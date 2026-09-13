package fi.karivanttinen.fplassistant.tools;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import fi.karivanttinen.fplassistant.schedule.ScheduleSource;
import fi.karivanttinen.fplassistant.testsupport.RankingSeed;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;

/** Previous-season ability is useful evidence but can never certify a current role. */
class PickToolsCrossSeasonTest {

    private static final String CURRENT = "T-2026-27";
    private static final String PREVIOUS = "T-2025-26";
    private static final String OLDER = "T-2024-25";
    private static final LocalDate DATE = LocalDate.of(2026, 9, 9);

    /** Midday UK on {@link #DATE}, so the 02:10 UK capture below is fresh for it. */
    private static final Instant AS_OF = Instant.parse("2026-09-09T12:00:00Z");
    private static final Instant SCHEDULE_CAPTURED = Instant.parse("2026-09-09T01:10:00Z");
    private static final Instant FIRST_DEADLINE = Instant.parse("2026-08-14T17:30:00Z");

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
                List.of(candidate), ClubRates.NONE);

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
                .containsSubsequence("Data source: no FPL gameweek and fixture capture",
                        DataAge.METRIC_REFRESH, "Schedule evidence:")
                .contains("No captain candidates qualify")
                .contains("unknown current evidence 1")
                .doesNotContain(" OldRegular (")
                .doesNotContainIgnoringCase("guaranteed");
    }

    /**
     * The changeover reply, where the daily capture and the schedule capture are the season being
     * played while the resolved metric season is still a completed one. Both clarification clauses
     * have to be there: the metric-season banner describes neither the prices nor the fixtures.
     * Under the observed 60+ history rule nobody can be primary-eligible while the current metric
     * season is a completed one, so the reply carrying the sentence is the one that ranks nobody.
     */
    @Test
    void aChangeoverReplySaysTheMetricSeasonDescribesNeitherThePricesNorTheFixtures() {
        SeasonContext resolved = new SeasonContext(9026, PREVIOUS, false, CURRENT,
                LocalDate.of(2026, 8, 14));
        SeasonContext older = new SeasonContext(9025, OLDER, false, CURRENT,
                LocalDate.of(2026, 8, 14));
        MetricSeasons seasons = new MetricSeasons(resolved, Optional.of(older), 38, 38);
        DailyPriceSource.Capture capture = new DailyPriceSource.Capture(CURRENT, DATE);

        RankingMetrics.CapturePlayer player = new RankingMetrics.CapturePlayer(
                50, "OldRegular", "Old Regular", RankingSeed.team(1), "OLD", "Old Town", 3,
                70, 5.0, "a", null, Instant.parse("2026-09-09T10:00:00Z"), 1);
        RankingMetrics.Candidate candidate = new RankingMetrics.Candidate(
                player, null, null, null, MinutesModel.Eligibility.UNKNOWN_CURRENT_EVIDENCE);

        // A complete, fresh capture of the season being played — the one the horizon is derived
        // against, and the third source line the reply has to attribute separately.
        ScheduleSource.View schedule = new ScheduleSource.View(
                Optional.of(new ScheduleSource.Snapshot(CURRENT, SCHEDULE_CAPTURED)),
                RankingSeed.events(SCHEDULE_CAPTURED, FIRST_DEADLINE),
                RankingSeed.fixtures(SCHEDULE_CAPTURED, FIRST_DEADLINE),
                new ScheduleSource.Completeness(true,
                        ScheduleSource.CompletenessReason.COMPLETE));
        RankingMetrics.Snapshot snapshot = new RankingMetrics.Snapshot(
                AS_OF, capture, seasons, schedule, List.of(candidate), ClubRates.NONE);

        SeasonResolver resolver = Mockito.mock(SeasonResolver.class);
        Mockito.when(resolver.resolve()).thenReturn(resolved);
        Mockito.when(resolver.metricSeasons(resolved)).thenReturn(seasons);
        DailyPriceSource daily = Mockito.mock(DailyPriceSource.class);
        Mockito.when(daily.latestCapture()).thenReturn(Optional.of(capture));
        RankingMetrics metrics = Mockito.mock(RankingMetrics.class);
        Mockito.when(metrics.load(capture, seasons)).thenReturn(snapshot);

        PickTools tools = new PickTools(resolver, daily, metrics);
        String result = tools.getDifferentialPicks("any", 100, 5);

        assertThat(result)
                .as("the three sources are attributed in order: price, metric season, schedule")
                .containsSubsequence(
                        "Data source: FPL live prices for season " + CURRENT,
                        "Data source: season " + PREVIOUS,
                        "Data source: FPL's own gameweek and fixture list for season " + CURRENT);
        assertThat(result)
                .as("the metric banner must not be read as describing the prices")
                .contains("The season line above describes the metrics only: price,"
                        + " ownership and availability on every pick are today's, from the"
                        + " capture, and may be quoted as current.")
                .as("nor as describing the fixtures the Score is totalled over")
                .contains("The schedule line, like the price line, describes the season being"
                        + " played: the fixtures every Score is totalled over come from that"
                        + " capture, never from the metric season.");
        assertThat(result)
                .as("the notice follows the clarification and precedes the evidence")
                .containsSubsequence("never from the metric season.", DataAge.METRIC_REFRESH,
                        "Schedule evidence:");
        assertThat(result)
                .contains("No differentials qualify in " + CURRENT)
                .doesNotContain(" OldRegular (");
    }
}
