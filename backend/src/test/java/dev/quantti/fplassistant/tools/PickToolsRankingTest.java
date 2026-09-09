package dev.quantti.fplassistant.tools;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Pure unit tests for the pick score, the ranking and position parsing.
 *
 * <p>These pin the property the tools exist to have: a recommendation follows what a player is
 * expected to score, never what they happened to score last Saturday. FPL's {@code form} is
 * average points per match over the last 30 days, so ranking on it in GW2 ranks on GW1's
 * scoreline.
 *
 * <p>They also pin the two things that make one number fair across positions — a goal is worth
 * more to a defender than to a forward, and a clean sheet is worth nothing to a forward — and the
 * blend that lets realised points count for more as a season runs.
 */
class PickToolsRankingTest {

    private static Map<String, Object> row(String name, int elementType) {
        Map<String, Object> row = new HashMap<>();
        row.put("web_name", name);
        row.put("element_type", elementType);
        row.put("team_id", 1);
        row.put("selected_by_percent", BigDecimal.valueOf(5.0));
        row.put("mins", 810);
        row.put("matches", 9);
        row.put("xg90", BigDecimal.ZERO);
        row.put("xa90", BigDecimal.ZERO);
        row.put("pts90", BigDecimal.ZERO);
        row.put("expected_per_fixture", 0.0);
        row.put("points_per_fixture", 0.0);
        return row;
    }

    /** A defender who attacks: goals and assists, usually at a side that concedes freely. */
    private static Map<String, Object> attackingDefender(String name, double xg90, double xa90,
                                                         double xgc, double pts90) {
        Map<String, Object> row = row(name, 2);
        row.put("xg90", BigDecimal.valueOf(xg90));
        row.put("xa90", BigDecimal.valueOf(xa90));
        row.put("xgc_per_match", BigDecimal.valueOf(xgc));
        row.put("pts90", BigDecimal.valueOf(pts90));
        row.put("expected_per_fixture", ExpectedPoints.perNinety(row));
        row.put("points_per_fixture", pts90);
        return row;
    }

    private static Map<String, Object> stopper(String name, double xgc, double pts90) {
        return attackingDefender(name, 0.03, 0.04, xgc, pts90);
    }

    private static Map<String, Object> attacker(String name, int elementType, double xg90,
                                                double xa90, double pts90) {
        Map<String, Object> row = row(name, elementType);
        row.put("xg90", BigDecimal.valueOf(xg90));
        row.put("xa90", BigDecimal.valueOf(xa90));
        row.put("pts90", BigDecimal.valueOf(pts90));
        row.put("expected_per_fixture", ExpectedPoints.perNinety(row));
        row.put("points_per_fixture", pts90);
        return row;
    }

    /**
     * The bug this ranking exists to stop. In GW2 a defender who scored once carries a huge
     * points-per-match and no underlying case; early in a season realised points are barely
     * weighted, so he must not lead.
     */
    @Test
    void aOneWeekPointsHaulDoesNotOutrankBetterUnderlyingNumbersEarlyInTheSeason() {
        double earlySeason = PickTools.realisedWeight(1);

        List<Map<String, Object>> ranked = PickTools.rank(
                List.of(stopper("ScoredOnce", 1.62, 13.0),
                        stopper("SolidDefence", 0.62, 2.0)),
                Map.of(1, 3.0), true, earlySeason);

        assertThat(ranked.getFirst().get("web_name")).isEqualTo("SolidDefence");
    }

    /**
     * The same two players in GW20. A season of returns is no longer a fluke, and the blend lets
     * it say so — this is the half of the picture the expected model cannot see (penalties, set
     * pieces, bonus, defensive contributions).
     */
    @Test
    void aSeasonOfRealisedPointsCountsOnceThereIsASeasonOfThem() {
        double lateSeason = PickTools.realisedWeight(20);

        List<Map<String, Object>> ranked = PickTools.rank(
                List.of(stopper("Returns", 1.62, 6.5),
                        stopper("CleanSheetsOnly", 0.62, 3.0)),
                Map.of(1, 3.0), true, lateSeason);

        assertThat(ranked.getFirst().get("web_name")).isEqualTo("Returns");
    }

    @Test
    void scoreBlendsTwoPerFixtureFiguresWithoutAddingAppearanceAgain() {
        Map<String, Object> player = attacker("PerFixture", 3, 0.4, 0.2, 2.0);
        player.put("expected_per_fixture", 4.0);
        player.put("points_per_fixture", 2.0);

        assertThat(PickTools.score(player, 0.5)).isEqualTo(3.0);
    }

    @Test
    void realisedPointsCarryNoWeightAtTheStartAndHalfByTheMidpoint() {
        assertThat(PickTools.realisedWeight(0)).isZero();
        assertThat(PickTools.realisedWeight(1)).isLessThan(0.05);
        assertThat(PickTools.realisedWeight(19)).isEqualTo(0.5);
        assertThat(PickTools.realisedWeight(38)).as("never more than half").isEqualTo(0.5);
    }

    /**
     * Both kinds of defender have to be reachable. A wing-back with real attacking numbers at a
     * leaky side and a stopper at a mean side are different picks, and the score must be able to
     * put either one first depending on the numbers rather than favouring one shape by design.
     */
    @Test
    void anAttackingDefenderCanOutrankAStopper() {
        List<Map<String, Object>> ranked = PickTools.rank(
                List.of(stopper("Stopper", 1.05, 3.5),
                        attackingDefender("WingBack", 0.18, 0.22, 1.35, 3.5)),
                Map.of(1, 3.0), true, 0.0);

        assertThat(ranked.getFirst().get("web_name")).isEqualTo("WingBack");
    }

    @Test
    void aStopperCanOutrankAnAttackingDefender() {
        List<Map<String, Object>> ranked = PickTools.rank(
                List.of(stopper("Stopper", 0.55, 3.5),
                        attackingDefender("WingBack", 0.08, 0.10, 1.60, 3.5)),
                Map.of(1, 3.0), true, 0.0);

        assertThat(ranked.getFirst().get("web_name")).isEqualTo("Stopper");
    }

    /** A goal is worth 6 to a defender and 4 to a forward, so identical xG is not identical value. */
    @Test
    void theSameExpectedGoalsAreWorthMoreToADefenderThanToAForward() {
        Map<String, Object> defender = attacker("Def", 2, 0.30, 0.0, 0.0);
        Map<String, Object> forward = attacker("Fwd", 4, 0.30, 0.0, 0.0);

        assertThat(PickTools.expectedPointsPer90(defender))
                .isGreaterThan(PickTools.expectedPointsPer90(forward));
    }

    /** A clean sheet pays a defender 4, a midfielder 1 and a forward nothing. */
    @Test
    void cleanSheetValueFollowsThePositionAndAForwardGetsNone() {
        Map<String, Object> forward = attacker("Fwd", 4, 0.40, 0.10, 0.0);
        forward.put("xgc_per_match", BigDecimal.valueOf(0.40));
        Map<String, Object> forwardAtLeakySide = attacker("Fwd2", 4, 0.40, 0.10, 0.0);
        forwardAtLeakySide.put("xgc_per_match", BigDecimal.valueOf(2.40));

        assertThat(PickTools.expectedPointsPer90(forward))
                .isEqualTo(PickTools.expectedPointsPer90(forwardAtLeakySide));
    }

    @Test
    void cleanSheetProbabilityFallsAsExpectedConcessionRises() {
        assertThat(PickTools.cleanSheetProbability(0)).isEqualTo(1.0);
        assertThat(PickTools.cleanSheetProbability(0.7)).isCloseTo(0.497, within(0.01));
        assertThat(PickTools.cleanSheetProbability(2.0)).isCloseTo(0.135, within(0.01));
    }

    @Test
    void equalScoresAreBrokenByEasierFixtures() {
        Map<String, Object> hard = attacker("Hard", 3, 0.40, 0.20, 4.0);
        Map<String, Object> easy = attacker("Easy", 3, 0.40, 0.20, 4.0);
        easy.put("team_id", 2);

        List<Map<String, Object>> ranked =
                PickTools.rank(List.of(hard, easy), Map.of(1, 4.5, 2, 2.0), true, 0.2);

        assertThat(ranked.getFirst().get("web_name")).isEqualTo("Easy");
    }

    @Test
    void theScoreStillOutranksFixtures() {
        Map<String, Object> blunt = attacker("EasyButBlunt", 3, 0.05, 0.05, 2.0);
        blunt.put("team_id", 2);
        Map<String, Object> sharp = attacker("SharpButHard", 3, 0.55, 0.25, 6.0);

        List<Map<String, Object>> ranked =
                PickTools.rank(List.of(blunt, sharp), Map.of(1, 5.0, 2, 1.0), true, 0.2);

        assertThat(ranked.getFirst().get("web_name")).isEqualTo("SharpButHard");
    }

    @Test
    void equalScoresAndFixturesFallBackToLowerOwnershipForDifferentials() {
        Map<String, Object> template = attacker("Template", 3, 0.40, 0.20, 4.0);
        template.put("selected_by_percent", BigDecimal.valueOf(40.0));
        Map<String, Object> differential = attacker("Differential", 3, 0.40, 0.20, 4.0);
        differential.put("selected_by_percent", BigDecimal.valueOf(2.0));

        List<Map<String, Object>> ranked =
                PickTools.rank(List.of(template, differential), Map.of(1, 3.0), true, 0.2);

        assertThat(ranked.getFirst().get("web_name")).isEqualTo("Differential");
    }

    /** Between two equal Scores the captain pick is the one who plays more — the old total-points
     *  tie-break rewarded the same thing, since total points is rate times minutes. */
    @Test
    void equalScoresAndFixturesFallBackToMoreMinutesForCaptains() {
        Map<String, Object> fewer = attacker("Fewer", 3, 0.40, 0.20, 4.0);
        fewer.put("mins", 450.0);
        Map<String, Object> more = attacker("More", 3, 0.40, 0.20, 4.0);
        more.put("mins", 900.0);

        List<Map<String, Object>> ranked =
                PickTools.rank(List.of(fewer, more), Map.of(1, 3.0), false, 0.2);

        assertThat(ranked.getFirst().get("web_name")).isEqualTo("More");
    }

    @Test
    void aTeamWithNoKnownFixturesSinksBelowOneWithFixtures() {
        Map<String, Object> unknown = attacker("Unknown", 3, 0.40, 0.20, 4.0);
        unknown.put("team_id", 99);
        Map<String, Object> known = attacker("Known", 3, 0.40, 0.20, 4.0);

        List<Map<String, Object>> ranked =
                PickTools.rank(List.of(unknown, known), Map.of(1, 4.9), true, 0.2);

        assertThat(ranked.getFirst().get("web_name")).isEqualTo("Known");
    }

    @Test
    void positionsParseFromNumbersAndAbbreviations() {
        assertThat(PickTools.parsePosition("1")).isEqualTo(1);
        assertThat(PickTools.parsePosition("GK")).isEqualTo(1);
        assertThat(PickTools.parsePosition("gkp")).isEqualTo(1);
        assertThat(PickTools.parsePosition("DEF")).isEqualTo(2);
        assertThat(PickTools.parsePosition(" mid ")).isEqualTo(3);
        assertThat(PickTools.parsePosition("forward")).isEqualTo(4);
    }

    @Test
    void anyAndBlankMeanNoPositionFilter() {
        assertThat(PickTools.parsePosition("any")).isZero();
        assertThat(PickTools.parsePosition("")).isZero();
        assertThat(PickTools.parsePosition(null)).isZero();
    }

    @Test
    void unrecognisedPositionIsRejectedRatherThanSilentlyWidenedToAny() {
        // Silently treating a typo as "any" answers a question the user did not ask.
        assertThat(PickTools.parsePosition("striker")).isNull();
        assertThat(PickTools.parsePosition("7")).isNull();
    }
}
