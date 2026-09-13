package dev.quantti.fplassistant.squad;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import dev.quantti.fplassistant.schedule.HorizonFixtures;
import dev.quantti.fplassistant.tools.ExpectedPoints;
import dev.quantti.fplassistant.tools.MinutesModel;
import dev.quantti.fplassistant.tools.RankingMetrics;
import dev.quantti.fplassistant.tools.SeasonBlend;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class HorizonScorerTest {

    private static final int CLUB = 4242;
    private static final Instant KICKOFF = Instant.parse("2026-09-13T14:00:00Z");

    /** A known window over the given per-fixture difficulties; a null difficulty is unpublished. */
    private static HorizonFixtures.ClubWindow window(Integer... difficulties) {
        List<HorizonFixtures.Fixture> fixtures = new ArrayList<>();
        int id = 1;
        for (Integer difficulty : difficulties) {
            fixtures.add(new HorizonFixtures.Fixture(id, 3 + id, true, 1, KICKOFF, difficulty));
            id++;
        }
        return new HorizonFixtures.ClubWindow(CLUB, List.copyOf(fixtures), null);
    }

    private static HorizonFixtures.ClubWindow unknown(HorizonFixtures.UnknownReason reason) {
        return new HorizonFixtures.ClubWindow(CLUB, List.of(), reason);
    }

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
                10.0, "a", null, CLUB, expectedMinutes, metrics,
                SquadCandidate.Tier.RANKABLE, window, exposure, eligibility);
    }

    /**
     * A forward whose per-fixture expectation is exactly 4.00, so the acceptance row's arithmetic
     * can be pinned to a round number instead of to a formula restated in the test. Two appearance
     * points, two attacking points, no clean-sheet term at this position and a known-zero
     * defensive-contribution record — known, so nothing about the row is partial.
     */
    private static SquadCandidate fourPointsPerFixture() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("element_type", 4);
        metrics.put("xg90", BigDecimal.valueOf(0.5));
        metrics.put("xa90", BigDecimal.ZERO);
        metrics.put("defcon_avg", BigDecimal.ZERO);
        metrics.put("defcon_matches", 2);
        SeasonBlend.Exposure exposure =
                new SeasonBlend.Exposure(10, 10, 10, 900, 1, 1, 0, 90, 4);
        return new SquadCandidate(2, "Acceptance", "Acceptance Row", "Barnwell Town", "BAR", 4,
                80, 10.0, "a", null, CLUB, 90, metrics, SquadCandidate.Tier.RANKABLE, null,
                exposure, MinutesModel.Eligibility.ELIGIBLE);
    }

    private static double perFixture(SquadCandidate candidate) {
        return ExpectedPoints.perFixture(candidate.metrics(), candidate.exposure(),
                OptionalDouble.empty()).orElseThrow().points();
    }

    @Test
    void displayedMinutesComeFromTheBlendedExposureRate() {
        assertThat(HorizonScorer.expectedMinutes(candidate(85.5, 10, 0.5)))
                .isCloseTo(85.5, within(1e-9));
        assertThat(HorizonScorer.expectedMinutes(candidate(45, 0, 0.5)))
                .isCloseTo(45, within(1e-9));
    }

    @Test
    void aBodyIsUnscoredRatherThanAZeroForecast() {
        SquadCandidate body = new SquadCandidate(3, "Body", "Body Player", "Ashfield Rovers",
                "ASH", 2, 40, 0.1, "a", null, CLUB, 0, Map.of(),
                SquadCandidate.Tier.BODY, null, null,
                MinutesModel.Eligibility.UNKNOWN_CURRENT_EVIDENCE);

        HorizonScorer.Projection projection = HorizonScorer.projection(body, window(2, 2, 2));

        assertThat(projection.available()).isFalse();
        assertThat(projection.horizonReason()).as("the schedule is fine; the record is not")
                .isNull();
    }

    @Test
    void anUnknownWindowIsUnscoredAndCarriesTheSchedulesOwnReason() {
        HorizonScorer.Projection projection = HorizonScorer.projection(candidate(90, 10, 0.5),
                unknown(HorizonFixtures.UnknownReason.UNASSIGNED_FIXTURE));

        assertThat(projection.available()).isFalse();
        assertThat(projection.optimizerPoints()).isZero();
        assertThat(projection.fixtures()).isZero();
        assertThat(projection.horizonReason())
                .isEqualTo(HorizonFixtures.UnknownReason.UNASSIGNED_FIXTURE);
    }

    @Test
    void aKnownEmptyWindowIsARealZeroRatherThanAnAbsence() {
        HorizonScorer.Projection projection =
                HorizonScorer.projection(candidate(90, 10, 0.5), window());

        assertThat(projection.available()).as("a blank is a finding, not a gap").isTrue();
        assertThat(projection.optimizerPoints()).isZero();
        assertThat(projection.fixtures()).isZero();
        assertThat(projection.partial()).as("no component was used, so nothing is partial")
                .isFalse();
        assertThat(projection.horizonReason()).isNull();
    }

    @Test
    void anEasyFixtureIsWorthMoreThanAHardOne() {
        SquadCandidate player = candidate(90, 10, 0.5);

        double easy = HorizonScorer.score(player, window(1));
        double hard = HorizonScorer.score(player, window(5));

        assertThat(easy).isGreaterThan(hard);
        assertThat(HorizonScorer.fdrAdjust(3)).isEqualTo(1.0);
        assertThat(HorizonScorer.fdrAdjust(1)).isCloseTo(1.2, within(1e-9));
        assertThat(HorizonScorer.fdrAdjust(5)).isCloseTo(0.8, within(1e-9));
    }

    @Test
    void sharedPerFixtureComponentsAreNotScaledByMinutesTwice() {
        SquadCandidate player = candidate(60, 10, 0.5);

        assertThat(HorizonScorer.score(player, window(3)))
                .isCloseTo(perFixture(player), within(1e-9));
    }

    @Test
    void eachFixtureContributesOnceToTheExistingCoarseHorizon() {
        SquadCandidate player = candidate(90, 10, 0.5);

        assertThat(HorizonScorer.score(player, window(3, 3)))
                .isCloseTo(HorizonScorer.score(player, window(3)) * 2, within(1e-9));
    }

    @Test
    void aTwoFixtureWindowSumsBothFdrAdjustedPerFixtureValues() {
        SquadCandidate player = candidate(90, 10, 0.5);

        HorizonScorer.Projection projection = HorizonScorer.projection(player, window(2, 4));

        assertThat(projection.fixtures()).isEqualTo(2);
        assertThat(projection.optimizerPoints()).isCloseTo(
                perFixture(player) * (HorizonScorer.fdrAdjust(2) + HorizonScorer.fdrAdjust(4)),
                within(1e-9));
    }

    /** The acceptance row: four points a fixture, one kind and one hard fixture, eight points. */
    @Test
    void fourPointsAcrossAnFdrTwoAndAnFdrFourFixtureIsEightPoints() {
        SquadCandidate player = fourPointsPerFixture();

        assertThat(perFixture(player)).isCloseTo(4.0, within(1e-9));

        HorizonScorer.Projection projection = HorizonScorer.projection(player, window(2, 4));

        assertThat(projection.optimizerPoints()).isCloseTo(8.0, within(1e-9));
        assertThat(projection.partial()).isFalse();
        assertThat(projection.neutralFdrLegs()).isZero();
    }

    @Test
    void aDoubleGameweekLegWithNoPublishedFdrKeepsTheCountAndGoesNeutralAlone() {
        SquadCandidate player = fourPointsPerFixture();

        HorizonScorer.Projection projection = HorizonScorer.projection(player, window(2, null));

        assertThat(projection.fixtures()).as("the leg is still played").isEqualTo(2);
        assertThat(projection.neutralFdrLegs()).isEqualTo(1);
        assertThat(projection.optimizerPoints()).isCloseTo(
                4.0 * (HorizonScorer.fdrAdjust(2) + HorizonScorer.fdrAdjust(3)), within(1e-9));
    }

    @Test
    void anOutOfRangeDifficultyIsTreatedAsUnpublishedRatherThanScored() {
        SquadCandidate player = fourPointsPerFixture();

        HorizonScorer.Projection projection = HorizonScorer.projection(player, window(9));

        assertThat(projection.neutralFdrLegs()).isEqualTo(1);
        assertThat(projection.optimizerPoints()).isCloseTo(4.0, within(1e-9));
    }
}
