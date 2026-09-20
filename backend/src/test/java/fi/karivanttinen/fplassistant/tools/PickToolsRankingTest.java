package fi.karivanttinen.fplassistant.tools;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import fi.karivanttinen.fplassistant.schedule.HorizonFixtures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Pure unit tests for expected-points-only pick ordering and horizon totals. */
class PickToolsRankingTest {

    private static final Instant KICKOFF = Instant.parse("2026-09-12T14:00:00Z");

    private static HorizonFixtures.ClubWindow window(int teamId, Integer... difficulties) {
        List<HorizonFixtures.Fixture> fixtures = new ArrayList<>();
        for (int index = 0; index < difficulties.length; index++) {
            fixtures.add(new HorizonFixtures.Fixture(1_000 * teamId + index, 4 + index, true,
                    99, KICKOFF.plus(index, ChronoUnit.DAYS), difficulties[index]));
        }
        return new HorizonFixtures.ClubWindow(teamId, fixtures, null);
    }

    private static Map<Integer, HorizonFixtures.ClubWindow> windows(
            HorizonFixtures.ClubWindow... clubs) {
        Map<Integer, HorizonFixtures.ClubWindow> byTeam = new LinkedHashMap<>();
        for (HorizonFixtures.ClubWindow club : clubs) {
            byTeam.put(club.teamId(), club);
        }
        return byTeam;
    }

    private static Map<String, Object> pick(String name, int elementId, int teamId,
                                            double expectedPerFixture) {
        Map<String, Object> row = new HashMap<>();
        row.put("web_name", name);
        row.put("id", elementId);
        row.put("team_id", teamId);
        row.put("selected_by_percent", 5.0);
        row.put("expected_per_fixture", expectedPerFixture);
        return row;
    }

    @Test
    void realisedPointsAndMinutesNeverChangeTheExpectedPointsScoreOrOrder() {
        Map<String, Object> expected = pick("Expected", 1, 1, 4.0);
        expected.put("points_per_fixture", 0.0);
        expected.put("mins", 1.0);
        expected.put("expected_minutes", 1.0);

        Map<String, Object> returns = pick("Returns", 2, 1, 3.0);
        returns.put("points_per_fixture", 99.0);
        returns.put("mins", 9_000.0);
        returns.put("expected_minutes", 90.0);

        assertThat(PickTools.score(expected)).isEqualTo(4.0);
        assertThat(PickTools.score(returns)).isEqualTo(3.0);
        assertThat(PickTools.rank(List.of(returns, expected), windows(window(1, 3))))
                .extracting(row -> row.get("web_name"))
                .containsExactly("Expected", "Returns");
    }

    @Test
    void fullFixtureExpectedPointsRespectPositionScoring() {
        Map<String, Object> defender = expectedRow(2, 0.30, 0.0);
        Map<String, Object> forward = expectedRow(4, 0.30, 0.0);

        assertThat(ExpectedPoints.expectedPerFixture(defender, java.util.OptionalDouble.of(1.0))
                .orElseThrow().points()).isGreaterThan(ExpectedPoints.expectedPerFixture(forward,
                        java.util.OptionalDouble.of(1.0)).orElseThrow().points());
    }

    @Test
    void equalExpectedTotalsAreBrokenByEasierFixturesThenLowerOwnership() {
        Map<String, Object> hard = pick("Hard", 1, 1, 4.0);
        hard.put("selected_by_percent", 1.0);
        Map<String, Object> easy = pick("Easy", 2, 2, 4.0);
        easy.put("selected_by_percent", 99.0);
        Map<String, Object> cheaperOwned = pick("Differential", 3, 3, 4.0);
        cheaperOwned.put("selected_by_percent", 2.0);
        Map<String, Object> template = pick("Template", 4, 4, 4.0);
        template.put("selected_by_percent", 40.0);

        List<Map<String, Object>> ranked = PickTools.rank(
                List.of(hard, easy, template, cheaperOwned),
                windows(window(1, 5), window(2, 2), window(3, 3), window(4, 3)));

        assertThat(ranked).extracting(row -> row.get("web_name"))
                .containsExactly("Easy", "Differential", "Template", "Hard");
    }

    @Test
    void aDoubleGameweekTotalsTwoExpectedFixtures() {
        Map<String, Object> doubler = pick("Doubler", 1, 1, 3.0);
        Map<String, Object> single = pick("Single", 2, 2, 5.0);

        List<Map<String, Object>> ranked = PickTools.rank(List.of(single, doubler),
                windows(window(1, 5, 5), window(2, 2)));

        assertThat(PickTools.horizonScore(doubler, windows(window(1, 5, 5)))).isEqualTo(6.0);
        assertThat(ranked).extracting(row -> row.get("web_name"))
                .containsExactly("Doubler", "Single");
    }

    @Test
    void rawExpectedTotalsBreakRoundedTiesAndElementIdClosesExactTies() {
        Map<String, Object> higher = pick("Higher", 90, 1, 3.0002);
        Map<String, Object> lower = pick("Lower", 10, 2, 3.0001);
        Map<String, Object> later = pick("Later", 77, 3, 4.0);
        Map<String, Object> earlier = pick("Earlier", 12, 3, 4.0);

        assertThat(PickTools.rank(List.of(lower, higher),
                windows(window(1, 3, 3), window(2, 3, 3))))
                .extracting(row -> row.get("web_name"))
                .containsExactly("Higher", "Lower");
        assertThat(PickTools.rank(List.of(later, earlier), windows(window(3, 3))))
                .extracting(row -> row.get("web_name"))
                .containsExactly("Earlier", "Later");
    }

    @Test
    void anUnratedFixtureKeepsItsCountButLosesTheFdrTieBreak() {
        HorizonFixtures.ClubWindow unrated = window(2, 2, null);
        Map<String, Object> rated = pick("Rated", 2, 1, 3.0);
        Map<String, Object> unknownFdr = pick("UnknownFdr", 1, 2, 3.0);

        assertThat(unrated.count()).isEqualTo(2);
        assertThat(unrated.averageDifficulty()).isEmpty();
        assertThat(PickTools.rank(List.of(unknownFdr, rated),
                windows(window(1, 5, 5), unrated)))
                .extracting(row -> row.get("web_name"))
                .containsExactly("Rated", "UnknownFdr");
    }

    @Test
    void cleanSheetProbabilityFallsAsExpectedConcessionRises() {
        assertThat(ExpectedPoints.cleanSheetProbability(0)).isEqualTo(1.0);
        assertThat(ExpectedPoints.cleanSheetProbability(0.7)).isCloseTo(0.497, within(0.01));
        assertThat(ExpectedPoints.cleanSheetProbability(2.0)).isCloseTo(0.135, within(0.01));
    }

    @Test
    void positionsParseFromNumbersAndAbbreviations() {
        assertThat(PickTools.parsePosition("1")).isEqualTo(1);
        assertThat(PickTools.parsePosition("GK")).isEqualTo(1);
        assertThat(PickTools.parsePosition("gkp")).isEqualTo(1);
        assertThat(PickTools.parsePosition("DEF")).isEqualTo(2);
        assertThat(PickTools.parsePosition(" mid ")).isEqualTo(3);
        assertThat(PickTools.parsePosition("forward")).isEqualTo(4);
        assertThat(PickTools.parsePosition("any")).isZero();
        assertThat(PickTools.parsePosition("")).isZero();
        assertThat(PickTools.parsePosition(null)).isZero();
        assertThat(PickTools.parsePosition("wingback")).isNull();
    }

    private static Map<String, Object> expectedRow(int position, double xg90, double xa90) {
        Map<String, Object> row = new HashMap<>();
        row.put("element_type", position);
        row.put("xg90", xg90);
        row.put("xa90", xa90);
        row.put("defcon_matches", 0);
        return row;
    }
}
