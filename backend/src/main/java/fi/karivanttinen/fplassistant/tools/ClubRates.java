package fi.karivanttinen.fplassistant.tools;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Club-level expected-goal rates and the per-fixture concession they project.
 *
 * <p>The clean-sheet term used to be {@code exp(-xGC)} on the <em>player's</em> average expected
 * concession over his window — backward-looking and opponent-blind, with fixture difficulty
 * applied beside it as an average FDR. This class supplies the other half of the fixture: the
 * defending club's concession rate, the opponent's scoring rate and the venue, combined the way a
 * Poisson goals model does, so a defender's clean-sheet chance differs against a side scoring
 * 2.2 a game and one scoring 0.9 (KAR-30).
 *
 * <p>Rates are per club and per fixture-side from direct Understat match totals; xGA is the
 * opponent's xG from the same result, not a player aggregate. The current and previous seasons blend on the
 * one shared ramp, {@link SeasonBlend#weight}, keyed on the club's own completed fixtures this
 * season. League constants are pooled over both seasons and not ramped: the league mean and the
 * home/away effect are not a club's property. They are pooled over <em>every</em> club of each
 * season, which is why {@link #league} is public and takes its own maps — a caller that bridges
 * clubs between seasons must pool the raw, unbridged sides, so a club that bridges nowhere (a
 * relegated side) still shapes the mean it is measured against.
 *
 * <p>Nothing here is a calibration claim. Below the floors the projection is absent rather than
 * estimated, and callers print n/a and label the row partial.
 */
public final class ClubRates {

    /** Fixture-sides a club must have, across both seasons, before it carries a rate. */
    public static final int MIN_CLUB_FIXTURES = 2;

    /** Fixture-sides the league must have, pooled over both seasons, before venue and mean exist. */
    public static final int MIN_LEAGUE_SIDES = 40;

    /** One club's completed sides in one season, as summed by the read. */
    public record SeasonSide(int fixtures, double xgFor, double xgAgainst,
                             int homeFixtures, double homeXgFor) {
        public SeasonSide {
            if (fixtures < 0 || homeFixtures < 0 || homeFixtures > fixtures) {
                throw new IllegalArgumentException("invalid club season side");
            }
        }
    }

    /**
     * @param attack mean team xG per fixture-side, blended
     * @param defence mean team xGC per fixture-side, blended
     * @param fixtures sides across both seasons, the evidence count
     * @param weight the ramp's value for this club, reported even when it could not apply
     * @param fixturesThisSeason completed current-season sides, the ramp's input
     * @param hasPrevious whether a previous-season record blended in
     */
    public record ClubRate(double attack, double defence, int fixtures, double weight,
                           int fixturesThisSeason, boolean hasPrevious) {
    }

    /** League mean team xG per side and the venue factors around it; home + away = 2. */
    public record League(double meanXg, double homeFactor, double awayFactor, int sides) {
    }

    /** Keyed by the capture's team id; a club absent here has no usable rate. */
    public record Rates(Map<Integer, ClubRate> clubs, Optional<League> league) {
        public Rates {
            clubs = Map.copyOf(clubs);
            league = java.util.Objects.requireNonNull(league);
        }
    }

    public static final Rates NONE = new Rates(Map.of(), Optional.empty());

    private ClubRates() {
    }

    /**
     * @param current this season's sides per capture team id, empty when none were read
     * @param previous last season's sides per capture team id, empty when not blending
     */
    public static Rates blend(Map<Integer, SeasonSide> current, Map<Integer, SeasonSide> previous) {
        return blend(current, previous, league(current, previous));
    }

    /**
     * As {@link #blend(Map, Map)}, but with the league constants supplied by the caller — for a
     * caller whose club maps have been bridged between seasons and which therefore must pool the
     * league over the raw sides of each season instead.
     *
     * @param current this season's sides per capture team id, empty when none were read
     * @param previous last season's sides per capture team id, empty when not blending
     * @param league the pooled league constants, empty when they are below the floor
     */
    public static Rates blend(Map<Integer, SeasonSide> current, Map<Integer, SeasonSide> previous,
                              Optional<League> league) {
        Map<Integer, ClubRate> clubs = new HashMap<>();
        Set<Integer> ids = new HashSet<>(current.keySet());
        ids.addAll(previous.keySet());
        for (int club : ids) {
            SeasonSide cur = current.get(club);
            SeasonSide prev = previous.get(club);
            int curFixtures = cur == null ? 0 : cur.fixtures();
            int prevFixtures = prev == null ? 0 : prev.fixtures();
            if (curFixtures + prevFixtures < MIN_CLUB_FIXTURES) {
                continue;
            }
            double tableWeight = SeasonBlend.weight(curFixtures);
            double w = prev == null || prevFixtures == 0 ? 1.0
                    : cur == null || curFixtures == 0 ? 0.0 : tableWeight;
            double attack = mix(rate(cur, true), rate(prev, true), w);
            double defence = mix(rate(cur, false), rate(prev, false), w);
            clubs.put(club, new ClubRate(attack, defence, curFixtures + prevFixtures,
                    tableWeight, curFixtures, prev != null && prevFixtures > 0));
        }
        return new Rates(clubs, league);
    }

    /**
     * The chance-of-conceding input for one fixture: what {@code defendingClub} is expected to
     * let in against {@code opponent}, at home or away. Empty when either club or the league
     * has no rate; a genuine zero projection remains zero, not a missing-data sentinel.
     */
    public static OptionalDouble projectedGoalsAgainst(Rates rates, int defendingClub,
                                                       int opponent, boolean defendingAtHome) {
        if (rates.league().isEmpty()) {
            return OptionalDouble.empty();
        }
        ClubRate defence = rates.clubs().get(defendingClub);
        ClubRate attack = rates.clubs().get(opponent);
        League league = rates.league().orElseThrow();
        if (defence == null || attack == null || league.meanXg() <= 0) {
            return OptionalDouble.empty();
        }
        double venue = defendingAtHome ? league.awayFactor() : league.homeFactor();
        return OptionalDouble.of(defence.defence() * attack.attack() / league.meanXg() * venue);
    }

    /**
     * The league mean and venue factors, pooled over every side in the two maps — bridged or not,
     * and whether or not the club clears {@link #MIN_CLUB_FIXTURES}. The league mean is not a
     * club's property: a club that has no rate of its own, because it plays in only one of the two
     * seasons, still belongs in the population its rivals are measured against. Empty below
     * {@link #MIN_LEAGUE_SIDES} or when one venue is unrepresented.
     */
    public static Optional<League> league(Map<?, SeasonSide> current,
                                          Map<?, SeasonSide> previous) {
        int sides = 0;
        int homeSides = 0;
        double xg = 0;
        double homeXg = 0;
        for (Map<?, SeasonSide> season : java.util.List.of(current, previous)) {
            for (SeasonSide side : season.values()) {
                sides += side.fixtures();
                homeSides += side.homeFixtures();
                xg += side.xgFor();
                homeXg += side.homeXgFor();
            }
        }
        int awaySides = sides - homeSides;
        if (sides < MIN_LEAGUE_SIDES || homeSides == 0 || awaySides == 0 || xg <= 0) {
            return Optional.empty();
        }
        double meanXg = xg / sides;
        return Optional.of(new League(meanXg, (homeXg / homeSides) / meanXg,
                ((xg - homeXg) / awaySides) / meanXg, sides));
    }

    private static double rate(SeasonSide side, boolean attack) {
        if (side == null || side.fixtures() == 0) {
            return 0;
        }
        return (attack ? side.xgFor() : side.xgAgainst()) / side.fixtures();
    }

    private static double mix(double current, double previous, double weight) {
        return weight * current + (1 - weight) * previous;
    }
}
