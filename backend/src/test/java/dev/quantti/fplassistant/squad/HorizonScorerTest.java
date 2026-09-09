package dev.quantti.fplassistant.squad;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.quantti.fplassistant.tools.ExpectedPoints;
import dev.quantti.fplassistant.tools.MinutesModel;
import dev.quantti.fplassistant.tools.RankingMetrics;
import dev.quantti.fplassistant.tools.SeasonBlend;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class HorizonScorerTest {

    private static SquadCandidate candidate(double expectedMinutes, int sixty, double xg90) {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("element_type", 3);
        metrics.put("xg90", BigDecimal.valueOf(xg90));
        metrics.put("xa90", BigDecimal.ZERO);
        metrics.put("pts90", BigDecimal.ZERO);
        metrics.put("xgc_per_match", null);
        int fixtures = 10;
        int totalMinutes = (int) Math.round(expectedMinutes * fixtures);
        SeasonBlend.Exposure exposure = new SeasonBlend.Exposure(fixtures, fixtures, sixty,
                totalMinutes, 1, sixty / (double) fixtures, 1 - sixty / (double) fixtures,
                expectedMinutes, 3);
        RankingMetrics.MinutesWindow window = new RankingMetrics.MinutesWindow(
                true, RankingMetrics.WindowReason.COMPLETE, 9001,
                Instant.parse("2026-08-01T12:00:00Z"),
                Instant.parse("2026-09-01T12:00:00Z"), fixtures, fixtures, totalMinutes,
                fixtures, sixty, List.of());
        MinutesModel.Eligibility eligibility = sixty == fixtures && totalMinutes >= 180
                ? MinutesModel.Eligibility.ELIGIBLE : MinutesModel.Eligibility.MINUTES_RISK;
        return new SquadCandidate(1, "Test", "Test Player", "Ashfield Rovers", "ASH", 3, 60,
                10.0, "a", null, 9001, expectedMinutes, metrics,
                SquadCandidate.Tier.RANKABLE, window, exposure, eligibility);
    }

    @Test
    void displayedMinutesComeFromTheBlendedExposureRate() {
        assertThat(HorizonScorer.expectedMinutes(candidate(85.5, 10, 0.5)))
                .isCloseTo(85.5, within(1e-9));
        assertThat(HorizonScorer.expectedMinutes(candidate(45, 0, 0.5)))
                .isCloseTo(45, within(1e-9));
    }

    @Test
    void aBodyAndAnUnknownHorizonAreUnscoredRatherThanZeroForecasts() {
        SquadCandidate body = new SquadCandidate(2, "Body", "Body Player", "Ashfield Rovers",
                "ASH", 2, 40, 0.1, "a", null, 9001, 0, Map.of(),
                SquadCandidate.Tier.BODY, null, null,
                MinutesModel.Eligibility.UNKNOWN_CURRENT_EVIDENCE);

        assertThat(HorizonScorer.projection(body, List.of(2, 2, 2)).available()).isFalse();
        assertThat(HorizonScorer.projection(candidate(90, 10, 0.5), List.of()).available()).isFalse();
    }

    @Test
    void anEasyFixtureIsWorthMoreThanAHardOne() {
        SquadCandidate player = candidate(90, 10, 0.5);

        double easy = HorizonScorer.score(player, List.of(1));
        double hard = HorizonScorer.score(player, List.of(5));

        assertThat(easy).isGreaterThan(hard);
        assertThat(HorizonScorer.fdrAdjust(3)).isEqualTo(1.0);
        assertThat(HorizonScorer.fdrAdjust(1)).isCloseTo(1.2, within(1e-9));
        assertThat(HorizonScorer.fdrAdjust(5)).isCloseTo(0.8, within(1e-9));
    }

    @Test
    void sharedPerFixtureComponentsAreNotScaledByMinutesTwice() {
        SquadCandidate player = candidate(60, 10, 0.5);
        double shared = ExpectedPoints.perFixture(player.metrics(), player.exposure())
                .orElseThrow().points();

        assertThat(HorizonScorer.score(player, List.of(3))).isCloseTo(shared, within(1e-9));
    }

    @Test
    void eachFixtureContributesOnceToTheExistingCoarseHorizon() {
        SquadCandidate player = candidate(90, 10, 0.5);
        double single = HorizonScorer.score(player, List.of(3));
        double doubled = HorizonScorer.score(player, List.of(3, 3));

        assertThat(doubled).isCloseTo(single * 2, within(1e-9));
    }
}
