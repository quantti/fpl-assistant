package dev.quantti.fplassistant.squad;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The constraints a user states, and the defaults that apply when they state none.
 *
 * <p>The first group is the feature's promise: "three players from Manchester City" must produce
 * three players from Manchester City. The second exists because an unconstrained optimum is a
 * bad squad — points per million favour mid-price players, so the solver buys fifteen of them,
 * no premium, and the squad has neither a ceiling nor a captain.
 */
class SquadSolverConstraintsTest {

    private static SquadCandidate player(int id, String club, int type, int cost,
                                         double ownership) {
        return new SquadCandidate(id, "P" + id, "Player " + id, club, club.substring(0, 3),
                type, cost, ownership, "a", null, club.hashCode(), 900, 10,
                Map.of("element_type", type), SquadCandidate.Tier.RANKABLE);
    }

    private static List<SquadCandidate> pool() {
        List<SquadCandidate> pool = new ArrayList<>();
        int id = 1;
        for (int club = 0; club < 12; club++) {
            String name = "Club%02d".formatted(club);
            for (int type = 1; type <= 4; type++) {
                for (int n = 0; n < 4; n++) {
                    // One premium and one heavily owned player per club per position.
                    pool.add(player(id++, name, type, n == 0 ? 110 : 45 + n * 5,
                            n == 1 ? 40.0 : 2.0));
                }
            }
        }
        return pool;
    }

    private static Map<Integer, Double> flatScores(List<SquadCandidate> pool) {
        Map<Integer, Double> scores = new HashMap<>();
        for (SquadCandidate c : pool) {
            scores.put(c.elementId(), 10.0 + c.elementId() % 7);
        }
        return scores;
    }

    /**
     * Scores every premium-priced candidate below every non-premium one, so the unconstrained
     * optimum would buy none of them. Only the premium-range default can then explain any premium
     * showing up in the squad — without it, {@link #theSquadCarriesAtLeastOnePremiumByDefault()}
     * would pass whether or not the constraint row exists.
     */
    private static Map<Integer, Double> scoresThatShunPremiums(List<SquadCandidate> pool) {
        Map<Integer, Double> scores = new HashMap<>();
        for (SquadCandidate c : pool) {
            scores.put(c.elementId(), c.nowCost() >= SquadSolver.PREMIUM_THRESHOLD
                    ? -100.0 : 10.0 + c.elementId() % 7);
        }
        return scores;
    }

    private static Map<Integer, Double> allNailed(List<SquadCandidate> pool) {
        Map<Integer, Double> minutes = new HashMap<>();
        for (SquadCandidate c : pool) {
            minutes.put(c.elementId(), 88.0);
        }
        return minutes;
    }

    private static SquadConstraints with(List<SquadConstraints.ClubQuota> quotas) {
        return new SquadConstraints(List.of(), List.of(), quotas, 1000, 5, null);
    }

    private static SquadConstraints withFormation(SquadConstraints.Formation formation) {
        return new SquadConstraints(List.of(), List.of(), List.of(), 1000, 5, formation);
    }

    @Test
    void exactlyThreeMeansExactlyThree() {
        List<SquadCandidate> pool = pool();

        SolvedSquad solved = SquadSolver.solveWithDefaults(pool, flatScores(pool), with(List.of(
                        new SquadConstraints.ClubQuota("Club00",
                                SquadConstraints.Comparator.EXACTLY, 3))),
                List.of(), allNailed(pool)).orElseThrow();

        assertThat(solved.squad()).filteredOn(c -> c.club().equals("Club00")).hasSize(3);
        SquadSolverRulesTest.assertEveryRule(solved, 1000);
    }

    @Test
    void atLeastTwoIsAFloorAndNotACeiling() {
        List<SquadCandidate> pool = pool();

        SolvedSquad solved = SquadSolver.solveWithDefaults(pool, flatScores(pool), with(List.of(
                        new SquadConstraints.ClubQuota("Club01",
                                SquadConstraints.Comparator.AT_LEAST, 2))),
                List.of(), allNailed(pool)).orElseThrow();

        assertThat(solved.squad()).filteredOn(c -> c.club().equals("Club01"))
                .hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void atMostOneIsHonoured() {
        List<SquadCandidate> pool = pool();

        SolvedSquad solved = SquadSolver.solveWithDefaults(pool, flatScores(pool), with(List.of(
                        new SquadConstraints.ClubQuota("Club02",
                                SquadConstraints.Comparator.AT_MOST, 1))),
                List.of(), allNailed(pool)).orElseThrow();

        assertThat(solved.squad()).filteredOn(c -> c.club().equals("Club02"))
                .hasSizeLessThanOrEqualTo(1);
    }

    @Test
    void aPinnedPlayerIsAlwaysInTheSquad() {
        List<SquadCandidate> pool = pool();
        SquadCandidate pin = pool.stream().filter(c -> c.elementType() == 4).findFirst()
                .orElseThrow();

        SolvedSquad solved = SquadSolver.solveWithDefaults(pool, flatScores(pool), with(List.of()),
                List.of(pin), allNailed(pool)).orElseThrow();

        assertThat(solved.squad()).contains(pin);
        SquadSolverRulesTest.assertEveryRule(solved, 1000);
    }

    @Test
    void anExcludedPlayerIsNeverInTheSquad() {
        List<SquadCandidate> pool = pool();
        SquadCandidate unwanted = pool.getFirst();
        var constraints = new SquadConstraints(List.of(), List.of(unwanted.webName()), List.of(),
                1000, 5, null);
        // Scored far above every other candidate: an unconstrained optimum would certainly buy
        // him, so his absence can only be explained by the exclusion row actually working — the
        // same shape as scoring the BODY candidate 1000 in SquadSolverRulesTest.
        Map<Integer, Double> scores = flatScores(pool);
        scores.put(unwanted.elementId(), 1000.0);

        SolvedSquad solved = SquadSolver.solveWithDefaults(pool, scores, constraints,
                List.of(), allNailed(pool)).orElseThrow();

        assertThat(solved.squad()).doesNotContain(unwanted);
    }

    @Test
    void theSquadCarriesAtLeastOnePremiumByDefault() {
        List<SquadCandidate> pool = pool();

        SolvedSquad solved = SquadSolver.solveWithDefaults(pool, scoresThatShunPremiums(pool),
                with(List.of()), List.of(), allNailed(pool)).orElseThrow();

        assertThat(solved.squad())
                .filteredOn(c -> c.nowCost() >= SquadSolver.PREMIUM_THRESHOLD)
                .hasSizeBetween(SquadSolver.MIN_PREMIUMS, SquadSolver.MAX_PREMIUMS);
    }

    @Test
    void theSquadCarriesSixTemplatePlayersByDefault() {
        List<SquadCandidate> pool = pool();

        SolvedSquad solved = SquadSolver.solveWithDefaults(pool, flatScores(pool), with(List.of()),
                List.of(), allNailed(pool)).orElseThrow();

        assertThat(solved.squad())
                .filteredOn(c -> c.ownership() >= SquadSolver.TEMPLATE_OWNERSHIP)
                .hasSizeGreaterThanOrEqualTo(SquadSolver.TEMPLATE_MIN_COUNT);
    }

    @Test
    void aPlayerWhoDoesNotPlayIsNeverInTheStartingEleven() {
        List<SquadCandidate> pool = pool();
        Map<Integer, Double> minutes = allNailed(pool);
        SquadCandidate benchwarmer = pool.get(5);
        minutes.put(benchwarmer.elementId(), 20.0);
        Map<Integer, Double> scores = flatScores(pool);
        scores.put(benchwarmer.elementId(), 1000.0);

        SolvedSquad solved = SquadSolver.solveWithDefaults(pool, scores, with(List.of()),
                List.of(), minutes).orElseThrow();

        assertThat(solved.startingXi()).doesNotContain(benchwarmer);
    }

    @Test
    void aBenchPlayerHasNoMinutesFloorAtAll() {
        // Cheapness competes against playing time on the bench, and sometimes cheapness wins.
        // That is deliberate: the four-million body is a real and correct pick.
        List<SquadCandidate> pool = new ArrayList<>(pool());
        SquadCandidate cheapNonPlayer = new SquadCandidate(9999, "Fodder", "Fodder Player",
                "Bodytown", "BOD", 2, 40, 0.1, "a", null, 999, 0, 0, Map.of(),
                SquadCandidate.Tier.BODY);
        pool.add(cheapNonPlayer);
        Map<Integer, Double> minutes = allNailed(pool);
        minutes.put(9999, 0.0);
        Map<Integer, Double> scores = flatScores(pool);
        scores.put(9999, 0.0);

        // 745 is chosen, not arbitrary: fifteen real players in this pool cost 750, so the body
        // is the only way to a legal squad; the cheapest squad containing it costs 740 (swap one
        // of the fifteen 50-cost players for the 40-cost body), so 740 is the minimum feasible
        // budget and 750 is the point where the body stops being needed — at or above it the
        // solver never has to reach for the body, and this test would prove nothing. 745 sits
        // clear of both edges.
        var tight = new SquadConstraints(List.of(), List.of(), List.of(), 745, 5, null);
        SolvedSquad solved = SquadSolver.solveWithDefaults(pool, scores, tight, List.of(), minutes)
                .orElseThrow();

        SquadSolverRulesTest.assertEveryRule(solved, 745);
        assertThat(solved.squad()).contains(cheapNonPlayer);
        assertThat(solved.startingXi()).doesNotContain(cheapNonPlayer);
    }

    @Test
    void aStructuralDefaultGivesWayBeforeTheUsersOwnConstraintDoes() {
        // Nine players from three named clubs, none of them premium and none template-owned:
        // the defaults cannot all hold, and the user's quotas must survive anyway.
        List<SquadCandidate> pool = new ArrayList<>();
        int id = 1;
        for (int club = 0; club < 12; club++) {
            String name = "Club%02d".formatted(club);
            boolean named = club < 3;
            for (int type = 1; type <= 4; type++) {
                for (int n = 0; n < 4; n++) {
                    pool.add(player(id++, name, type, named ? 45 : (n == 0 ? 110 : 50),
                            named ? 1.0 : (n == 1 ? 40.0 : 2.0)));
                }
            }
        }
        var quotas = List.of(
                new SquadConstraints.ClubQuota("Club00", SquadConstraints.Comparator.EXACTLY, 3),
                new SquadConstraints.ClubQuota("Club01", SquadConstraints.Comparator.EXACTLY, 3),
                new SquadConstraints.ClubQuota("Club02", SquadConstraints.Comparator.EXACTLY, 3));

        SolvedSquad solved = SquadSolver.solveWithDefaults(pool, flatScores(pool), with(quotas),
                List.of(), allNailed(pool)).orElseThrow();

        for (String club : List.of("Club00", "Club01", "Club02")) {
            assertThat(solved.squad()).filteredOn(c -> c.club().equals(club)).hasSize(3);
        }
        // Pins down the order the ladder gives way in, not just that it gave way somehow: with
        // the correct order this fixture resolves at step 1 (template dropped, premium and
        // starters-play both still held); tried in a different order it would resolve at step 1
        // too, by a different route, and the assertions above would not tell the two apart.
        assertThat(solved.relaxed()).containsExactly(SquadSolver.RELAXED_TEMPLATE);
        SquadSolverRulesTest.assertEveryRule(solved, 1000);
    }

    @Test
    void aForcedFormationOverridesWhereThePointsAre() {
        List<SquadCandidate> pool = pool();
        // Defenders and forwards score far above midfielders — SquadSolverRulesTest's
        // unconstrained equivalent of this bias reaches 5-2-3. A forced "4-4-2" must override it:
        // the equality this constraint adds is never a preference, same as a club quota.
        Map<Integer, Double> scores = new HashMap<>();
        for (SquadCandidate c : pool) {
            scores.put(c.elementId(), switch (c.elementType()) {
                case 2, 4 -> 20.0;
                default -> 1.0;
            });
        }

        SolvedSquad solved = SquadSolver.solveWithDefaults(pool, scores,
                withFormation(new SquadConstraints.Formation(4, 4, 2)),
                List.of(), allNailed(pool)).orElseThrow();

        assertThat(solved.formation()).isEqualTo("4-4-2");
        SquadSolverRulesTest.assertEveryRule(solved, 1000);
    }

    @Test
    void anIllegalForcedFormationNeverSolves() {
        // Feasibility is what turns this into a named refusal in the tool; the solver itself has
        // no relaxation step for a rule of the game, so an illegal formation must simply fail to
        // produce a result, the same way an unmeetable budget does.
        List<SquadCandidate> pool = pool();

        Optional<SolvedSquad> solved = SquadSolver.solveWithDefaults(pool, flatScores(pool),
                withFormation(new SquadConstraints.Formation(1, 1, 1)), List.of(), allNailed(pool));

        assertThat(solved).isEmpty();
    }
}
