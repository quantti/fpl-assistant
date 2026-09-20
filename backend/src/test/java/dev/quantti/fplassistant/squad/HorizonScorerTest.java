package dev.quantti.fplassistant.squad;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.quantti.fplassistant.schedule.HorizonFixtures;
import dev.quantti.fplassistant.tools.ClubRates;
import dev.quantti.fplassistant.tools.ExpectedPoints;
import dev.quantti.fplassistant.tools.MinutesModel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class HorizonScorerTest {

    private static final int CLUB = 4242;
    private static final int OPPONENT = 1;
    private static final Instant KICKOFF = Instant.parse("2026-09-13T14:00:00Z");

    private static HorizonFixtures.ClubWindow window(Integer... difficulties) {
        List<HorizonFixtures.Fixture> fixtures = new ArrayList<>();
        int id = 1;
        for (Integer difficulty : difficulties) {
            fixtures.add(new HorizonFixtures.Fixture(id, 3 + id, true, OPPONENT, KICKOFF,
                    difficulty));
            id++;
        }
        return new HorizonFixtures.ClubWindow(CLUB, List.copyOf(fixtures), null);
    }

    private static HorizonFixtures.ClubWindow unknown(HorizonFixtures.UnknownReason reason) {
        return new HorizonFixtures.ClubWindow(CLUB, List.of(), reason);
    }

    private static SquadCandidate attacker(double xg90) {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("element_type", 4);
        metrics.put("xg90", BigDecimal.valueOf(xg90));
        metrics.put("xa90", BigDecimal.ZERO);
        metrics.put("defcon_avg", BigDecimal.ZERO);
        metrics.put("defcon_matches", 2);
        return new SquadCandidate(1, "Test", "Test Player", "Ashfield Rovers", "ASH", 4, 60,
                10.0, "a", null, CLUB, metrics, SquadCandidate.Tier.RANKABLE, null,
                MinutesModel.Eligibility.ELIGIBLE);
    }

    private static SquadCandidate fourPointsPerFixture() {
        return attacker(0.5); // two appearance points plus two attacking points
    }

    @Test
    void observedMinuteFieldsDoNotChangeTheHorizonProjection() {
        SquadCandidate lowMinutes = attacker(0.5);
        lowMinutes.metrics().put("mins", 1);
        lowMinutes.metrics().put("matches60", 0);
        SquadCandidate highMinutes = attacker(0.5);
        highMinutes.metrics().put("mins", 9_000);
        highMinutes.metrics().put("matches60", 100);

        assertThat(HorizonScorer.score(lowMinutes, window(3), ClubRates.NONE))
                .isEqualTo(HorizonScorer.score(highMinutes, window(3), ClubRates.NONE));
    }

    @Test
    void aBodyIsUnscoredRatherThanAZeroForecast() {
        SquadCandidate body = new SquadCandidate(3, "Body", "Body Player", "Ashfield Rovers",
                "ASH", 2, 40, 0.1, "a", null, CLUB, Map.of(), SquadCandidate.Tier.BODY, null,
                MinutesModel.Eligibility.UNKNOWN_CURRENT_EVIDENCE);

        HorizonScorer.Projection projection = HorizonScorer.projection(body, window(2, 2, 2),
                ClubRates.NONE);

        assertThat(projection.available()).isFalse();
        assertThat(projection.horizonReason()).isNull();
    }

    @Test
    void anUnknownWindowIsUnscoredAndCarriesTheSchedulesOwnReason() {
        HorizonScorer.Projection projection = HorizonScorer.projection(attacker(0.5),
                unknown(HorizonFixtures.UnknownReason.UNASSIGNED_FIXTURE), ClubRates.NONE);

        assertThat(projection.available()).isFalse();
        assertThat(projection.optimizerPoints()).isZero();
        assertThat(projection.horizonReason())
                .isEqualTo(HorizonFixtures.UnknownReason.UNASSIGNED_FIXTURE);
    }

    @Test
    void aKnownEmptyWindowIsARealZeroRatherThanAnAbsence() {
        HorizonScorer.Projection projection =
                HorizonScorer.projection(attacker(0.5), window(), ClubRates.NONE);

        assertThat(projection.available()).isTrue();
        assertThat(projection.optimizerPoints()).isZero();
        assertThat(projection.fixtures()).isZero();
        assertThat(projection.partial()).isFalse();
    }

    @Test
    void fdrScalesTheAttackingTermOnly() {
        SquadCandidate player = fourPointsPerFixture();

        HorizonScorer.Projection projection = HorizonScorer.projection(player, window(2),
                ClubRates.NONE);

        assertThat(projection.optimizerPoints()).isCloseTo(
                2.0 + 2.0 * HorizonScorer.fdrAdjust(2), within(1e-9));
        assertThat(HorizonScorer.fdrAdjust(3)).isEqualTo(1.0);
        assertThat(HorizonScorer.fdrAdjust(1)).isCloseTo(1.2, within(1e-9));
        assertThat(HorizonScorer.fdrAdjust(5)).isCloseTo(0.8, within(1e-9));
    }

    @Test
    void eachFixtureContributesOnceToTheExistingCoarseHorizon() {
        SquadCandidate player = attacker(0.5);

        assertThat(HorizonScorer.score(player, window(3, 3), ClubRates.NONE))
                .isCloseTo(HorizonScorer.score(player, window(3), ClubRates.NONE) * 2,
                        within(1e-9));
    }

    @Test
    void aTwoFixtureWindowSumsBothFdrAdjustedExpectedValues() {
        SquadCandidate player = fourPointsPerFixture();

        HorizonScorer.Projection projection = HorizonScorer.projection(player, window(2, 4),
                ClubRates.NONE);

        assertThat(projection.fixtures()).isEqualTo(2);
        assertThat(projection.optimizerPoints()).isCloseTo(8.0, within(1e-9));
        assertThat(projection.partial()).isFalse();
    }

    @Test
    void anUnpublishedFdrKeepsItsFixtureAtNeutralDifficulty() {
        HorizonScorer.Projection projection = HorizonScorer.projection(fourPointsPerFixture(),
                window(2, null), ClubRates.NONE);

        assertThat(projection.fixtures()).isEqualTo(2);
        assertThat(projection.neutralFdrLegs()).isEqualTo(1);
        assertThat(projection.optimizerPoints()).isCloseTo(
                2 * 2.0 + 2.0 * (HorizonScorer.fdrAdjust(2) + HorizonScorer.fdrAdjust(3)),
                within(1e-9));
    }

    @Test
    void missingAttackEvidenceIsUnscoredRatherThanSubstitutedFromMinutes() {
        SquadCandidate player = attacker(0.5);
        player.metrics().remove("xg90");

        HorizonScorer.Projection projection = HorizonScorer.projection(player, window(1),
                ClubRates.NONE);

        assertThat(projection.available()).isFalse();
        assertThat(projection.optimizerPoints()).isZero();
    }

    private static ClubRates.Rates rates(double clubDefence, double opponentAttack) {
        Map<Integer, ClubRates.SeasonSide> sides = new HashMap<>();
        for (int id = 100; id < 120; id++) {
            sides.put(id, new ClubRates.SeasonSide(2, 2.8, 2.8, 1, 1.4));
        }
        sides.put(CLUB, new ClubRates.SeasonSide(2, 2.8, 2 * clubDefence, 1, 1.4));
        sides.put(OPPONENT, new ClubRates.SeasonSide(2, 2 * opponentAttack, 2.8, 1, 1.4));
        return ClubRates.blend(sides, Map.of());
    }

    private static SquadCandidate defender() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("element_type", 2);
        metrics.put("xg90", BigDecimal.ZERO);
        metrics.put("xa90", BigDecimal.ZERO);
        metrics.put("defcon_avg", BigDecimal.ZERO);
        metrics.put("defcon_matches", 2);
        return new SquadCandidate(4, "Stopper", "Stopper Player", "Ashfield Rovers", "ASH", 2,
                45, 5.0, "a", null, CLUB, metrics, SquadCandidate.Tier.RANKABLE, null,
                MinutesModel.Eligibility.ELIGIBLE);
    }

    @Test
    void clubRatesProjectTheFixtureSpecificCleanSheet() {
        SquadCandidate stopper = defender();

        double leaky = HorizonScorer.score(stopper, window(3), rates(2.0, 1.4));
        double mean = HorizonScorer.score(stopper, window(3), rates(0.7, 1.4));
        double weakOpponent = HorizonScorer.score(stopper, window(3), rates(1.4, 0.7));
        double strongOpponent = HorizonScorer.score(stopper, window(3), rates(1.4, 2.5));

        assertThat(mean).isGreaterThan(leaky);
        assertThat(weakOpponent).isGreaterThan(strongOpponent);
    }

    @Test
    void aFixtureWithoutAClubProjectionIsPartialAndContributesNoCleanSheet() {
        HorizonScorer.Projection projection = HorizonScorer.projection(defender(), window(3),
                ClubRates.NONE);

        assertThat(projection.partial()).isTrue();
        assertThat(projection.optimizerPoints()).isCloseTo(2.0, within(1e-9));
    }
}
