package dev.quantti.fplassistant.tools;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalDouble;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Expected full-fixture FPL scoring, independent of observed minutes and realised points. */
class ExpectedPointsTest {

    private static Map<String, Object> row(int elementType, double xg90, double xa90) {
        Map<String, Object> row = new HashMap<>();
        row.put("element_type", elementType);
        row.put("xg90", BigDecimal.valueOf(xg90));
        row.put("xa90", BigDecimal.valueOf(xa90));
        return row;
    }

    @Test
    void aGoalIsWorthMoreToADefenderThanToAForward() {
        double defender = projection(row(2, 0.5, 0), OptionalDouble.of(1.0)).points();
        double forward = projection(row(4, 0.5, 0), OptionalDouble.of(1.0)).points();

        assertThat(defender - forward).isCloseTo(0.5 * (6 - 4) + 4 * Math.exp(-1.0),
                within(1e-9));
    }

    @Test
    void fullFixtureProjectionDoesNotReadMinuteOrRealisedPointFields() {
        Map<String, Object> lowMinutes = row(3, 0.5, 0.2);
        lowMinutes.put("mins", 1);
        lowMinutes.put("matches60", 0);
        lowMinutes.put("points_per_fixture", 99.0);
        lowMinutes.put("expected_minutes", 1.0);
        Map<String, Object> highMinutes = row(3, 0.5, 0.2);
        highMinutes.put("mins", 9_000);
        highMinutes.put("matches60", 100);
        highMinutes.put("points_per_fixture", 0.0);
        highMinutes.put("expected_minutes", 90.0);

        assertThat(projection(lowMinutes, OptionalDouble.empty()).points())
                .isEqualTo(projection(highMinutes, OptionalDouble.empty()).points());
        assertThat(projection(lowMinutes, OptionalDouble.empty()).appearance())
                .isEqualTo(ExpectedPoints.APPEARANCE_POINTS);
    }

    @Test
    void unavailableAttackRatesAbstainRatherThanBecomingZero() {
        Map<String, Object> missingXg = row(4, 0.5, 0.2);
        missingXg.remove("xg90");
        Map<String, Object> missingXa = row(4, 0.5, 0.2);
        missingXa.put("xa90", null);

        assertThat(ExpectedPoints.expectedPerFixture(missingXg, OptionalDouble.empty())).isEmpty();
        assertThat(ExpectedPoints.expectedPerFixture(missingXa, OptionalDouble.empty())).isEmpty();
    }

    @Test
    void fixtureCleanSheetUsesProjectedConcessionAndIsPartialWhenMissing() {
        Map<String, Object> defender = row(2, 0, 0);

        ExpectedPoints.PerFixture known = projection(defender, OptionalDouble.of(1.25));
        ExpectedPoints.PerFixture unknown = projection(defender, OptionalDouble.empty());

        assertThat(known.cleanSheet()).isCloseTo(4 * Math.exp(-1.25), within(1e-9));
        assertThat(unknown.cleanSheet()).isNull();
        assertThat(unknown.points()).isEqualTo(ExpectedPoints.APPEARANCE_POINTS);
    }

    @Test
    void aForwardHasNoCleanSheetTerm() {
        assertThat(projection(row(4, 0, 0), OptionalDouble.of(0.10)).cleanSheet()).isZero();
    }

    @Test
    void outOfRangePositionsDegradeRatherThanThrowing() {
        assertThat(ExpectedPoints.goalPoints(5)).isZero();
        assertThat(ExpectedPoints.cleanSheetPoints(0)).isZero();
        assertThat(projection(row(5, 1.0, 1.0), OptionalDouble.of(0.5)).points())
                .isEqualTo(ExpectedPoints.APPEARANCE_POINTS + ExpectedPoints.ASSIST_POINTS);
    }

    @Test
    void cleanSheetProbabilityIsPoisson() {
        assertThat(ExpectedPoints.cleanSheetProbability(0.5)).isCloseTo(Math.exp(-0.5), within(1e-9));
    }

    @Test
    void defensiveContributionUsesTheActionRateAndNotPastPointReturns() {
        Map<String, Object> lower = defensiveRow(2, 10, 7.0);
        lower.put("points_per_fixture", 99.0);
        Map<String, Object> higher = defensiveRow(2, 10, 11.0);
        higher.put("points_per_fixture", 0.0);

        assertThat(projection(higher, OptionalDouble.empty()).defensiveContribution())
                .isGreaterThan(projection(lower, OptionalDouble.empty()).defensiveContribution());
        assertThat(projection(defensiveRow(2, 10, 8.0), OptionalDouble.empty())
                .defensiveContribution()).isCloseTo(ExpectedPoints.thresholdProbability(8.0, 10)
                        * ExpectedPoints.DEFCON_POINTS, within(1e-9));
    }

    @Test
    void defconNeedsEnoughObservationsAndGoalkeepersReceiveNone() {
        assertThat(ExpectedPoints.hasDefconSignal(defensiveRow(2, 1, 14.0), 2)).isFalse();
        assertThat(projection(defensiveRow(2, 1, 14.0), OptionalDouble.empty())
                .defensiveContribution()).isZero();
        assertThat(projection(defensiveRow(1, 10, 15.0), OptionalDouble.empty())
                .defensiveContribution()).isZero();
    }

    @Test
    void thresholdsAndTheirProbabilityHaveExpectedBounds() {
        assertThat(ExpectedPoints.defconThreshold(2)).isEqualTo(10);
        assertThat(ExpectedPoints.defconThreshold(3)).isEqualTo(12);
        assertThat(ExpectedPoints.thresholdProbability(9.8, 10)).isBetween(0.45, 0.55);
        assertThat(ExpectedPoints.thresholdProbability(0, 10)).isZero();
        assertThat(ExpectedPoints.thresholdProbability(9, 0)).isZero();
    }

    private static ExpectedPoints.PerFixture projection(Map<String, Object> row,
                                                        OptionalDouble goalsAgainst) {
        return ExpectedPoints.expectedPerFixture(row, goalsAgainst).orElseThrow();
    }

    private static Map<String, Object> defensiveRow(int elementType, int matches,
                                                    double meanActions) {
        Map<String, Object> row = row(elementType, 0, 0);
        row.put("defcon_matches", matches);
        row.put("defcon_avg", BigDecimal.valueOf(meanActions));
        return row;
    }
}
