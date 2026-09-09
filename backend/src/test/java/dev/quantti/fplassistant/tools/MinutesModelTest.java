package dev.quantti.fplassistant.tools;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MinutesModelTest {

    @Test
    void onlyACompleteFitAllSixtyWindowWithCurrentSupportQualifies() {
        assertThat(MinutesModel.eligibility(player("a"), window(2, 180, 2), true))
                .isEqualTo(MinutesModel.Eligibility.ELIGIBLE);
        assertThat(MinutesModel.eligibility(player("a"), window(3, 180, 3), true))
                .isEqualTo(MinutesModel.Eligibility.ELIGIBLE);

        assertThat(MinutesModel.eligibility(player("a"), window(2, 120, 2), true))
                .isEqualTo(MinutesModel.Eligibility.INSUFFICIENT_CURRENT_EVIDENCE);
        assertThat(MinutesModel.eligibility(player("a"), window(3, 239, 2), true))
                .isEqualTo(MinutesModel.Eligibility.MINUTES_RISK);
        assertThat(MinutesModel.eligibility(player("a"), window(2, 0, 0), true))
                .isEqualTo(MinutesModel.Eligibility.MINUTES_RISK);
    }

    @Test
    void availabilityAndUnknownCoverageTakePrecedenceOverOldAbility() {
        assertThat(MinutesModel.eligibility(player("d"), window(3, 270, 3), true))
                .isEqualTo(MinutesModel.Eligibility.AVAILABILITY_RISK);
        assertThat(MinutesModel.eligibility(player("a"), incomplete(), true))
                .isEqualTo(MinutesModel.Eligibility.UNKNOWN_CURRENT_EVIDENCE);
        assertThat(MinutesModel.eligibility(player("a"), window(3, 270, 3), false))
                .isEqualTo(MinutesModel.Eligibility.UNKNOWN_CURRENT_EVIDENCE);
    }

    @Test
    void everyResultCarriesNonCertifyingReasonText() {
        assertThat(MinutesModel.Eligibility.ELIGIBLE.reason())
                .contains("observed 60+").doesNotContainIgnoringCase("guarantee");
        assertThat(MinutesModel.Eligibility.MINUTES_RISK.reason())
                .contains("under 60");
    }

    private static RankingMetrics.CapturePlayer player(String status) {
        return new RankingMetrics.CapturePlayer(1, "Player", "Test Player", 10,
                "TST", "Test", 2, 50, 1.0, status, null,
                Instant.parse("2026-09-09T01:00:00Z"), 1);
    }

    private static RankingMetrics.MinutesWindow window(int expected, int minutes, int sixty) {
        return new RankingMetrics.MinutesWindow(true, RankingMetrics.WindowReason.COMPLETE,
                10, Instant.parse("2026-08-01T12:00:00Z"),
                Instant.parse("2026-08-20T12:00:00Z"), expected, expected, minutes,
                expected, sixty, List.of());
    }

    private static RankingMetrics.MinutesWindow incomplete() {
        return new RankingMetrics.MinutesWindow(false,
                RankingMetrics.WindowReason.MISSING_OBSERVATION, 10, null, null,
                3, 2, 180, 2, 2, List.of());
    }
}
