package dev.quantti.fplassistant.squad;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Scoring over a horizon rather than per 90.
 *
 * <p>The minutes ratio is the guard the whole feature depends on: without it the optimiser buys
 * the cheapest players whose per-90 numbers look adequate, which is the placeholder bug wearing
 * a real name.
 */
class HorizonScorerTest {

    private static SquadCandidate candidate(double minutes, int teamFixtures, double xg90) {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("element_type", 3);
        metrics.put("xg90", BigDecimal.valueOf(xg90));
        metrics.put("xa90", BigDecimal.ZERO);
        metrics.put("pts90", BigDecimal.ZERO);
        metrics.put("xgc_per_match", null);
        return new SquadCandidate(1, "Test", "Test Player", "Ashfield Rovers", "ASH", 3, 60,
                10.0, "a", null, 9001, minutes, teamFixtures, metrics,
                SquadCandidate.Tier.RANKABLE);
    }

    @Test
    void aNailedStarterKeepsNearlyHisWholePerNinetyValue() {
        double minutes = HorizonScorer.expectedMinutes(candidate(855, 10, 0.5));

        assertThat(minutes).isCloseTo(85.5, within(1e-9));
    }

    @Test
    void aRotatedPlayerKeepsAboutHalf() {
        double minutes = HorizonScorer.expectedMinutes(candidate(450, 10, 0.5));

        assertThat(minutes).isCloseTo(45.0, within(1e-9));
    }

    @Test
    void aBodyIsWorthNothingHoweverGoodHisRecordLooks() {
        SquadCandidate body = new SquadCandidate(2, "Body", "Body Player", "Ashfield Rovers",
                "ASH", 2, 40, 0.1, "a", null, 9001, 0, 0, Map.of(),
                SquadCandidate.Tier.BODY);

        assertThat(HorizonScorer.score(body, List.of(2, 2, 2))).isZero();
    }

    @Test
    void anEasyFixtureIsWorthMoreThanAHardOne() {
        SquadCandidate player = candidate(900, 10, 0.5);

        double easy = HorizonScorer.score(player, List.of(1));
        double hard = HorizonScorer.score(player, List.of(5));

        assertThat(easy).isGreaterThan(hard);
        assertThat(HorizonScorer.fdrAdjust(3)).isEqualTo(1.0);
        assertThat(HorizonScorer.fdrAdjust(1)).isCloseTo(1.2, within(1e-9));
        assertThat(HorizonScorer.fdrAdjust(5)).isCloseTo(0.8, within(1e-9));
    }

    @Test
    void aDoubleGameweekCountsTwiceBecauseItIsTwoFixtures() {
        SquadCandidate player = candidate(900, 10, 0.5);

        double single = HorizonScorer.score(player, List.of(3));
        double doubled = HorizonScorer.score(player, List.of(3, 3));

        assertThat(doubled).isCloseTo(single * 2, within(1e-9));
    }

    @Test
    void aBlankGameweekIsSimplyNoFixtureAndScoresNothing() {
        assertThat(HorizonScorer.score(candidate(900, 10, 0.5), List.of())).isZero();
    }

    @Test
    void aPlayerWhoseTeamPlayedNoFixturesScoresNothingRatherThanDividingByZero() {
        assertThat(HorizonScorer.expectedMinutes(candidate(300, 0, 0.5))).isZero();
    }

    @Test
    void minutesAreCappedAtNinetySoAnExtraTimeArtefactCannotInflateAScore() {
        // Team fixture counts come from the player's own rows; a mismatch must not produce a
        // player worth more than a full match.
        assertThat(HorizonScorer.expectedMinutes(candidate(1000, 10, 0.5))).isEqualTo(90.0);
    }
}
