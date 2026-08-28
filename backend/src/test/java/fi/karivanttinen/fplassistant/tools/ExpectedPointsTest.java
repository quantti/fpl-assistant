package fi.karivanttinen.fplassistant.tools;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The scoring formula, on its own. It lives apart from {@link PickTools} because two tools now
 * rank on it and a copy in each is a guarantee they diverge.
 */
class ExpectedPointsTest {

    private static Map<String, Object> row(int elementType, double xg90, double xa90, Double xgc) {
        Map<String, Object> row = new HashMap<>();
        row.put("element_type", elementType);
        row.put("xg90", BigDecimal.valueOf(xg90));
        row.put("xa90", BigDecimal.valueOf(xa90));
        row.put("xgc_per_match", xgc == null ? null : BigDecimal.valueOf(xgc));
        return row;
    }

    @Test
    void aGoalIsWorthMoreToADefenderThanToAForward() {
        double defender = ExpectedPoints.perNinety(row(2, 0.5, 0, null));
        double forward = ExpectedPoints.perNinety(row(4, 0.5, 0, null));

        assertThat(defender).isGreaterThan(forward);
        assertThat(defender - forward).isCloseTo(0.5 * (6 - 4), within(1e-9));
    }

    @Test
    void aCleanSheetIsWorthNothingToAForward() {
        double withCleanSheetChance = ExpectedPoints.perNinety(row(4, 0, 0, 0.5));
        double without = ExpectedPoints.perNinety(row(4, 0, 0, null));

        assertThat(withCleanSheetChance).isEqualTo(without);
    }

    @Test
    void anUnrecordedExpectedConcessionIsNotAGuaranteedCleanSheet() {
        // The column is NOT NULL DEFAULT 0, so an unpopulated season reads as 0.00 — and
        // exp(-0) is 100%, which would put every defender in that season top of every list.
        double zero = ExpectedPoints.perNinety(row(2, 0, 0, 0.0));
        double absent = ExpectedPoints.perNinety(row(2, 0, 0, null));

        assertThat(zero).isEqualTo(absent);
        assertThat(zero).isEqualTo(ExpectedPoints.APPEARANCE_POINTS);
    }

    @Test
    void anOutOfRangePositionDegradesRatherThanThrowing() {
        // FPL added managers as a fifth element_type and the column has no CHECK constraint.
        assertThat(ExpectedPoints.goalPoints(5)).isZero();
        assertThat(ExpectedPoints.cleanSheetPoints(0)).isZero();
        assertThat(ExpectedPoints.perNinety(row(5, 1.0, 1.0, 0.5)))
                .isEqualTo(ExpectedPoints.APPEARANCE_POINTS + 1.0 * ExpectedPoints.ASSIST_POINTS);
    }

    @Test
    void cleanSheetProbabilityIsPoisson() {
        assertThat(ExpectedPoints.cleanSheetProbability(0.5))
                .isCloseTo(Math.exp(-0.5), within(1e-9));
    }

    private static Map<String, Object> defensiveRow(int elementType, int matches, int hits) {
        Map<String, Object> row = row(elementType, 0, 0, null);
        row.put("defcon_matches", matches);
        row.put("defcon_hits_def", hits);
        row.put("defcon_hits_mid", hits);
        row.put("defcon_avg", BigDecimal.valueOf(9.0));
        return row;
    }

    @Test
    void clearingTheThresholdHalfTheTimeIsWorthHalfThePoints() {
        double half = ExpectedPoints.perNinety(defensiveRow(2, 10, 5));
        double never = ExpectedPoints.perNinety(defensiveRow(2, 10, 0));

        assertThat(half - never).isCloseTo(0.5 * ExpectedPoints.DEFCON_POINTS, within(1e-9));
    }

    @Test
    void aMidfielderIsJudgedOnAHigherThresholdThanADefender() {
        assertThat(ExpectedPoints.defconThreshold(2)).isEqualTo(10);
        assertThat(ExpectedPoints.defconThreshold(3)).isEqualTo(12);
        assertThat(ExpectedPoints.defconThreshold(4)).isEqualTo(12);
    }

    @Test
    void aGoalkeeperEarnsNoDefensiveContribution() {
        // FPL awards it to outfield players only — all 20 keepers with an hour behind them read
        // 0 in the live 2026-27 data. A keeper with hits must still score nothing from this term.
        assertThat(ExpectedPoints.perNinety(defensiveRow(1, 10, 10)))
                .isEqualTo(ExpectedPoints.perNinety(defensiveRow(1, 10, 0)));
    }

    @Test
    void aRecordShorterThanTheWindowAdmitsIsNotReadAsARate() {
        // One match at 100% is not a certainty worth a flat two points, and it would top every
        // list. The floor is the two matches the minutes floor already admits a player on.
        Map<String, Object> oneForOne = defensiveRow(2, 1, 1);

        assertThat(ExpectedPoints.hasDefconSignal(oneForOne, 2)).isFalse();
        assertThat(ExpectedPoints.perNinety(oneForOne))
                .isEqualTo(ExpectedPoints.APPEARANCE_POINTS);
    }

    @Test
    void aRowWithoutTheDefensiveColumnsScoresExactlyAsItDidBefore() {
        // Every row seeded before these columns existed reads NULL. The term must vanish rather
        // than estimate, or every existing expectation in the suite moves under it.
        Map<String, Object> untouched = row(2, 0.4, 0.2, 0.9);

        assertThat(ExpectedPoints.hasDefconSignal(untouched, 2)).isFalse();
        assertThat(ExpectedPoints.perNinety(untouched)).isCloseTo(
                ExpectedPoints.APPEARANCE_POINTS
                        + 0.4 * ExpectedPoints.goalPoints(2)
                        + 0.2 * ExpectedPoints.ASSIST_POINTS
                        + ExpectedPoints.cleanSheetProbability(0.9)
                                * ExpectedPoints.cleanSheetPoints(2),
                within(1e-9));
    }
}
