package dev.quantti.fplassistant.tools;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalDouble;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The club-level half of the clean-sheet term: what a defence is expected to concede against a
 * named opponent at a named venue, from both clubs' expected-goal rates and the league's venue
 * effect. Pure arithmetic; the reads that feed it live in {@code RankingMetrics}.
 */
class ClubRatesTest {

    private static final int HOME_CLUB = 11;
    private static final int AWAY_CLUB = 12;

    /** A club that has played {@code fixtures} sides at the given per-fixture rates. */
    private static ClubRates.SeasonSide side(int fixtures, double xgPerFixture,
                                             double xgcPerFixture, int homeFixtures,
                                             double homeXgPerFixture) {
        return new ClubRates.SeasonSide(fixtures, xgPerFixture * fixtures,
                xgcPerFixture * fixtures, homeFixtures, homeXgPerFixture * homeFixtures);
    }

    /**
     * Twenty average clubs, 20 sides each, home sides scoring 1.6 and away sides 1.2: league
     * mean 1.4, home factor 1.6/1.4, away factor 1.2/1.4.
     */
    private static Map<Integer, ClubRates.SeasonSide> averageLeague() {
        Map<Integer, ClubRates.SeasonSide> clubs = new HashMap<>();
        for (int club = 1; club <= 20; club++) {
            clubs.put(club, side(20, 1.4, 1.4, 10, 1.6));
        }
        return clubs;
    }

    @Test
    void anAverageDefenceAgainstAnAverageAttackProjectsTheLeagueVenueMean() {
        ClubRates.Rates rates = ClubRates.blend(averageLeague(), Map.of());

        OptionalDouble atHome = ClubRates.projectedGoalsAgainst(rates, HOME_CLUB, AWAY_CLUB, true);
        OptionalDouble away = ClubRates.projectedGoalsAgainst(rates, AWAY_CLUB, HOME_CLUB, false);

        assertThat(atHome).hasValueCloseTo(1.2, within(1e-9));   // the away side's mean
        assertThat(away).hasValueCloseTo(1.6, within(1e-9));     // the home side's mean
        assertThat(rates.league().orElseThrow().homeFactor()
                + rates.league().orElseThrow().awayFactor()).isCloseTo(2.0, within(1e-9));
    }

    @Test
    void aStrongerAttackRaisesTheProjectionAndAMeanerDefenceLowersIt() {
        Map<Integer, ClubRates.SeasonSide> clubs = averageLeague();
        clubs.put(AWAY_CLUB, side(20, 2.1, 1.4, 10, 2.4));          // attack 1.5× the mean
        clubs.put(HOME_CLUB, side(20, 1.4, 0.7, 10, 1.6));          // defence half the mean
        ClubRates.Rates rates = ClubRates.blend(clubs, Map.of());

        assertThat(ClubRates.projectedGoalsAgainst(rates, HOME_CLUB, AWAY_CLUB, true))
                .hasValueCloseTo(0.7 * 2.1 / rates.league().orElseThrow().meanXg()
                        * rates.league().orElseThrow().awayFactor(), within(1e-9));
        assertThat(ClubRates.projectedGoalsAgainst(rates, HOME_CLUB, AWAY_CLUB, true).getAsDouble())
                .isLessThan(ClubRates.projectedGoalsAgainst(rates, 3, AWAY_CLUB, true).getAsDouble());
    }

    @Test
    void aClubBelowTwoSidesAcrossBothSeasonsHasNoRate() {
        Map<Integer, ClubRates.SeasonSide> clubs = averageLeague();
        clubs.put(HOME_CLUB, side(1, 1.4, 1.4, 1, 1.4));
        ClubRates.Rates rates = ClubRates.blend(clubs, Map.of());

        assertThat(rates.clubs()).doesNotContainKey(HOME_CLUB);
        assertThat(ClubRates.projectedGoalsAgainst(rates, HOME_CLUB, AWAY_CLUB, true)).isEmpty();
        assertThat(ClubRates.projectedGoalsAgainst(rates, AWAY_CLUB, HOME_CLUB, false))
                .as("an unrated opponent is as blocking as an unrated defence").isEmpty();
    }

    @Test
    void thePreviousSeasonCarriesAClubWithNoCurrentSidesAndTheSharedRampTakesOver() {
        Map<Integer, ClubRates.SeasonSide> previous = averageLeague();
        previous.put(HOME_CLUB, side(38, 1.0, 2.0, 19, 1.1));
        Map<Integer, ClubRates.SeasonSide> current = new HashMap<>();
        current.put(HOME_CLUB, side(2, 2.0, 1.0, 1, 2.0));

        ClubRates.ClubRate zeroCurrent = ClubRates.blend(Map.of(), previous).clubs().get(HOME_CLUB);
        ClubRates.ClubRate twoCurrent = ClubRates.blend(current, previous).clubs().get(HOME_CLUB);

        assertThat(zeroCurrent.weight()).isZero();
        assertThat(zeroCurrent.attack()).isCloseTo(1.0, within(1e-9));
        assertThat(zeroCurrent.hasPrevious()).isTrue();
        assertThat(twoCurrent.weight()).isEqualTo(SeasonBlend.weight(2));
        assertThat(twoCurrent.attack()).isCloseTo(0.30 * 2.0 + 0.70 * 1.0, within(1e-9));
        assertThat(twoCurrent.defence()).isCloseTo(0.30 * 1.0 + 0.70 * 2.0, within(1e-9));
        assertThat(twoCurrent.fixturesThisSeason()).isEqualTo(2);
    }

    @Test
    void aClubWithNoPreviousRecordIsRatedOnThisSeasonAloneOnceItHasTwoSides() {
        Map<Integer, ClubRates.SeasonSide> previous = averageLeague();
        previous.remove(HOME_CLUB);
        Map<Integer, ClubRates.SeasonSide> current = new HashMap<>();
        current.put(HOME_CLUB, side(2, 0.9, 1.9, 1, 0.9));

        ClubRates.ClubRate promoted = ClubRates.blend(current, previous).clubs().get(HOME_CLUB);

        assertThat(promoted.hasPrevious()).isFalse();
        assertThat(promoted.weight()).as("the table's value, reported not silently 100%")
                .isEqualTo(SeasonBlend.weight(2));
        assertThat(promoted.attack()).isCloseTo(0.9, within(1e-9));
        assertThat(promoted.defence()).isCloseTo(1.9, within(1e-9));
    }

    @Test
    void fewerThanFortySidesLeaveNoLeagueConstantsAndSoNoProjection() {
        Map<Integer, ClubRates.SeasonSide> clubs = new HashMap<>();
        clubs.put(HOME_CLUB, side(10, 1.4, 1.4, 5, 1.6));
        clubs.put(AWAY_CLUB, side(10, 1.4, 1.4, 5, 1.6));
        ClubRates.Rates rates = ClubRates.blend(clubs, Map.of());

        assertThat(rates.league()).isEmpty();
        assertThat(rates.clubs()).containsKeys(HOME_CLUB, AWAY_CLUB);
        assertThat(ClubRates.projectedGoalsAgainst(rates, HOME_CLUB, AWAY_CLUB, true)).isEmpty();
    }

    @Test
    void leagueConstantsPoolBothSeasonsWithoutARamp() {
        Map<Integer, ClubRates.SeasonSide> current = new HashMap<>();
        current.put(HOME_CLUB, side(2, 3.0, 1.0, 2, 3.0));
        ClubRates.Rates rates = ClubRates.blend(current, averageLeague());

        // 400 sides at 1.4 plus 2 sides at 3.0.
        assertThat(rates.league().orElseThrow().sides()).isEqualTo(402);
        assertThat(rates.league().orElseThrow().meanXg())
                .isCloseTo((400 * 1.4 + 2 * 3.0) / 402, within(1e-9));
    }

    @Test
    void noneProjectsNothing() {
        assertThat(ClubRates.NONE.clubs()).isEmpty();
        assertThat(ClubRates.projectedGoalsAgainst(ClubRates.NONE, 1, 2, true)).isEmpty();
    }
}
