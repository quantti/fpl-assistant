package dev.quantti.fplassistant.tools;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MinutesModelTest {

    @Test
    void completeMixedAndShortWindowsRemainEligibleRegardlessOfTheFormerMinuteFloor() {
        assertThat(MinutesModel.eligibility(player("a"), window(2, 120, 2, 60, 60), true))
                .isEqualTo(MinutesModel.Eligibility.ELIGIBLE);
        assertThat(MinutesModel.eligibility(player("a"), window(3, 179, 1, 90, 59, 30), true))
                .isEqualTo(MinutesModel.Eligibility.ELIGIBLE);
        assertThat(MinutesModel.eligibility(player("a"), window(2, 0, 0, 0, 0), true))
                .isEqualTo(MinutesModel.Eligibility.ELIGIBLE);
    }

    @Test
    void lowMinutesNeverChangeEligibility() {
        assertThat(MinutesModel.eligibility(player("a"), window(5, 118, 0, 59, 59, 0, 0, 0), true))
                .isEqualTo(MinutesModel.Eligibility.ELIGIBLE);
        assertThat(MinutesModel.eligibility(player("a"), window(10, 810, 9, 90, 90, 90), true))
                .isEqualTo(MinutesModel.Eligibility.ELIGIBLE);
        assertThat(MinutesModel.eligibility(player("a"), window(2, 40, 0, 20, 20), true))
                .isEqualTo(MinutesModel.Eligibility.ELIGIBLE);
    }

    @Test
    void availabilityAndUnknownCoverageRemainEligibilityGuards() {
        assertThat(MinutesModel.eligibility(player("d"), window(3, 0, 0, 0, 0, 0), true))
                .isEqualTo(MinutesModel.Eligibility.AVAILABILITY_RISK);
        assertThat(MinutesModel.eligibility(player("a"), incomplete(), true))
                .isEqualTo(MinutesModel.Eligibility.UNKNOWN_CURRENT_EVIDENCE);
        assertThat(MinutesModel.eligibility(player("a"), window(3, 0, 0, 0, 0, 0), false))
                .isEqualTo(MinutesModel.Eligibility.UNKNOWN_CURRENT_EVIDENCE);
    }

    @Test
    void eligibleReasonNamesEvidenceRatherThanAStartGuarantee() {
        assertThat(MinutesModel.Eligibility.ELIGIBLE.reason())
                .contains("evidence").doesNotContainIgnoringCase("guarantee");
    }

    private static RankingMetrics.CapturePlayer player(String status) {
        return new RankingMetrics.CapturePlayer(1, "Player", "Test Player", 10,
                "TST", "Test", 2, 50, 1.0, status, null,
                Instant.parse("2026-09-09T01:00:00Z"), 1);
    }

    private static RankingMetrics.MinutesWindow window(int expected, int minutes, int sixty,
                                                       Integer... recentMinutes) {
        List<RankingMetrics.FixtureOutcome> outcomes = new ArrayList<>();
        for (int index = 0; index < recentMinutes.length; index++) {
            outcomes.add(new RankingMetrics.FixtureOutcome(index + 1,
                    Instant.parse("2026-08-20T12:00:00Z").minusSeconds(index),
                    recentMinutes[index]));
        }
        return new RankingMetrics.MinutesWindow(true, RankingMetrics.WindowReason.COMPLETE,
                10, Instant.parse("2026-08-01T12:00:00Z"),
                Instant.parse("2026-08-20T12:00:00Z"), expected, expected, outcomes);
    }

    private static RankingMetrics.MinutesWindow incomplete() {
        return new RankingMetrics.MinutesWindow(false,
                RankingMetrics.WindowReason.MISSING_OBSERVATION, 10, null, null,
                3, 2, List.of());
    }
}
