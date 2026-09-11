package fi.karivanttinen.fplassistant.squad;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The promise of the whole feature: a result satisfies every rule of the game, or there is no
 * result. There is no third outcome.
 *
 * <p>The randomised test is the important one. Any single hand-written case can be satisfied by
 * accident; a hundred random pools cannot.
 */
class SquadSolverRulesTest {

    private static final List<Integer> QUOTAS = Feasibility.POSITION_QUOTAS;

    private static SquadCandidate player(int id, String club, int type, int cost) {
        return SquadCandidateTestData.eligible(id, "P" + id, club, type, cost, 5.0,
                Map.of("element_type", type));
    }

    /** Twelve clubs, four positions, four price points — always solvable inside £100.0m. */
    private static List<SquadCandidate> pool(Random random) {
        List<SquadCandidate> pool = new ArrayList<>();
        int id = 1;
        for (int club = 0; club < 12; club++) {
            String name = "Club%02d".formatted(club);
            for (int type = 1; type <= 4; type++) {
                for (int n = 0; n < 4; n++) {
                    pool.add(player(id++, name, type, 40 + random.nextInt(60)));
                }
            }
        }
        return pool;
    }

    private static Map<Integer, Double> scores(List<SquadCandidate> pool, Random random) {
        Map<Integer, Double> scores = new HashMap<>();
        for (SquadCandidate c : pool) {
            scores.put(c.elementId(), random.nextDouble() * 30);
        }
        return scores;
    }

    private static SquadConstraints plain() {
        return new SquadConstraints(List.of(), List.of(), List.of(), 1000, 5, null);
    }

    /** Every club of the pool has a fixture in the horizon, so only the minutes gate binds. */
    private static Set<Integer> allPlayable(List<SquadCandidate> pool) {
        return SquadCandidateTestData.clubsOf(pool);
    }

    private static Set<Integer> allPlayableExcept(List<SquadCandidate> pool, String club) {
        Set<Integer> playable = new LinkedHashSet<>(allPlayable(pool));
        playable.remove(club.hashCode());
        return playable;
    }

    @RepeatedTest(20)
    void everyResultObeysEveryRuleOfTheGame() {
        // Seeded, not fixed: each repetition must still see a different pool, but a failure has
        // to be reproducible — an unseeded Random loses the exact case that broke it.
        long seed = System.nanoTime();
        Random random = new Random(seed);
        List<SquadCandidate> pool = pool(random);
        SquadConstraints constraints = plain();

        try {
            SolvedSquad solved = SquadSolver
                    .solve(pool, scores(pool, random), constraints, List.of(), allPlayable(pool))
                    .orElseThrow(() -> new AssertionError("a full pool inside budget must solve"));

            assertEveryRule(solved, constraints.budget());
        } catch (AssertionError failure) {
            throw new AssertionError(
                    "seed=" + seed + "L reproduces this pool: " + failure.getMessage(), failure);
        }
    }

    /**
     * The rules of the game, minus the budget check: for a caller with no {@code
     * SquadConstraints} in scope. Prefer {@link #assertEveryRule(SolvedSquad, int)} wherever the
     * budget is known — every test in this class is.
     */
    static void assertEveryRule(SolvedSquad solved) {
        assertEveryRule(solved, Integer.MAX_VALUE);
    }

    static void assertEveryRule(SolvedSquad solved, int budget) {
        assertThat(solved.squad()).hasSize(Feasibility.SQUAD_SIZE);
        assertThat(solved.squad()).extracting(SquadCandidate::elementId).doesNotHaveDuplicates();

        for (int type = 1; type <= 4; type++) {
            final int position = type;
            assertThat(solved.squad().stream().filter(c -> c.elementType() == position).count())
                    .as("squad %ss", Feasibility.POSITION_NAMES.get(type))
                    .isEqualTo(QUOTAS.get(type).intValue());
        }

        Map<String, Long> byClub = new HashMap<>();
        for (SquadCandidate c : solved.squad()) {
            byClub.merge(c.club(), 1L, Long::sum);
        }
        assertThat(byClub.values()).allMatch(count -> count <= Feasibility.MAX_PER_CLUB);

        assertThat(solved.startingXi()).hasSize(SquadSolver.XI_SIZE);
        assertThat(solved.squad()).containsAll(solved.startingXi());
        assertThat(solved.bench()).hasSize(Feasibility.SQUAD_SIZE - SquadSolver.XI_SIZE);

        assertThat(count(solved.startingXi(), 1)).as("goalkeepers in the XI").isEqualTo(1);
        assertThat(count(solved.startingXi(), 2)).as("defenders in the XI")
                .isBetween(3L, 5L);
        assertThat(count(solved.startingXi(), 3)).as("midfielders in the XI")
                .isBetween(2L, 5L);
        assertThat(count(solved.startingXi(), 4)).as("forwards in the XI")
                .isBetween(1L, 3L);

        assertThat(count(solved.bench(), 1)).as("the second keeper is always benched")
                .isEqualTo(1);

        assertThat(solved.totalCost())
                .isEqualTo(solved.squad().stream().mapToInt(SquadCandidate::nowCost).sum());
        assertThat(solved.totalCost()).as("squad cost within budget").isLessThanOrEqualTo(budget);

        assertThat(solved.startingXi()).contains(solved.captain());
        assertThat(solved.startingXi()).contains(solved.viceCaptain());
        assertThat(solved.captain()).isNotEqualTo(solved.viceCaptain());
    }

    private static long count(List<SquadCandidate> players, int elementType) {
        return players.stream().filter(c -> c.elementType() == elementType).count();
    }

    @Test
    void aBodyIsNeverInTheStartingEleven() {
        Random random = new Random(7);
        List<SquadCandidate> pool = new ArrayList<>(pool(random));
        // A body at every position: free to buy, and scored far above any real candidate (whose
        // scores top out just under 30). A zero score would not test the rule — the objective
        // already prefers any positive-score real player over a worthless one, with roughly 48
        // real candidates per position competing for the spot. Scoring him this high means the
        // objective alone would want to start him the moment the BODY-never-starts constraint
        // came loose, which is what lets this test actually fail on that regression.
        int id = 9000;
        for (int type = 1; type <= 4; type++) {
            pool.add(SquadCandidateTestData.body(id++, "Body" + type, "Bodytown", type, 1));
        }
        Map<Integer, Double> scores = scores(pool, random);
        pool.stream().filter(c -> c.tier() == SquadCandidate.Tier.BODY)
                .forEach(c -> scores.put(c.elementId(), 1000.0));

        SquadConstraints constraints = plain();
        SolvedSquad solved = SquadSolver.solve(pool, scores, constraints, List.of(),
                allPlayable(pool)).orElseThrow();

        assertThat(solved.squad()).as("cheap and top-scoring: he should be bought")
                .anyMatch(c -> c.tier() == SquadCandidate.Tier.BODY);
        assertThat(solved.startingXi()).as("but never started")
                .noneMatch(c -> c.tier() == SquadCandidate.Tier.BODY);
        assertEveryRule(solved, constraints.budget());
    }

    @Test
    void directSolverEntryCannotStartAnIneligibleRankablePlayer() {
        Random random = new Random(17);
        List<SquadCandidate> pool = pool(random);
        SquadCandidate risky = SquadCandidateTestData.ineligible(pool.get(5),
                fi.karivanttinen.fplassistant.tools.MinutesModel.Eligibility.MINUTES_RISK);
        pool.set(5, risky);
        Map<Integer, Double> scores = scores(pool, random);
        scores.put(risky.elementId(), 10_000.0);

        SolvedSquad solved = SquadSolver.solve(pool, scores, plain(), List.of(),
                allPlayable(pool)).orElseThrow();

        assertThat(solved.startingXi()).doesNotContain(risky);
        assertEveryRule(solved, 1000);
    }

    /**
     * A club with nothing to play in the window cannot field a starter, however the objective
     * feels about him. Scored far above every real candidate so only the rules-of-the-game row
     * can explain his absence from the XI — the same shape as the BODY test above.
     */
    @Test
    void theDirectSolverWillNotStartAPlayerWhoseClubHasNoFixture() {
        Random random = new Random(23);
        List<SquadCandidate> pool = pool(random);
        Map<Integer, Double> scores = scores(pool, random);
        pool.stream().filter(c -> c.club().equals("Club03"))
                .forEach(c -> scores.put(c.elementId(), 10_000.0));

        SolvedSquad solved = SquadSolver.solve(pool, scores, plain(), List.of(),
                allPlayableExcept(pool, "Club03")).orElseThrow();

        assertThat(solved.squad()).as("still purchasable for the bench")
                .anyMatch(c -> c.club().equals("Club03"));
        assertThat(solved.startingXi()).noneMatch(c -> c.club().equals("Club03"));
        assertThat(solved.captain().club()).isNotEqualTo("Club03");
        assertThat(solved.viceCaptain().club()).isNotEqualTo("Club03");
        assertEveryRule(solved, 1000);
    }

    /**
     * Two fixtures are twice the opportunity, and the score says so — but the observed-history
     * rule is not a score. A doubled projection must not buy an ineligible player a start.
     */
    @Test
    void aDoubleGameweekNeverRescuesAMinutesIneligiblePlayerIntoTheXi() {
        Random random = new Random(29);
        List<SquadCandidate> pool = new ArrayList<>(pool(random));
        SquadCandidate risky = SquadCandidateTestData.ineligible(pool.get(9),
                fi.karivanttinen.fplassistant.tools.MinutesModel.Eligibility.MINUTES_RISK);
        pool.set(9, risky);
        Map<Integer, Double> scores = scores(pool, random);
        double best = scores.values().stream().mapToDouble(Double::doubleValue).max().orElseThrow();
        scores.put(risky.elementId(), best * 2);

        SolvedSquad solved = SquadSolver.solve(pool, scores, plain(), List.of(),
                allPlayable(pool)).orElseThrow();

        assertThat(solved.startingXi()).doesNotContain(risky);
        assertEveryRule(solved, 1000);
    }

    @Test
    void reachesFiveTwoThreeWhenThatIsWhereThePointsAre() {
        // 5-2-3 is legal. FPL_RULES.md's enumerated formation list omits it and must not be
        // used to derive anything.
        Random random = new Random(11);
        List<SquadCandidate> pool = pool(random);
        Map<Integer, Double> scores = new HashMap<>();
        for (SquadCandidate c : pool) {
            scores.put(c.elementId(), switch (c.elementType()) {
                case 2, 4 -> 20.0;
                default -> 1.0;
            });
        }

        SolvedSquad solved = SquadSolver.solve(pool, scores, plain(), List.of(),
                allPlayable(pool)).orElseThrow();

        assertThat(count(solved.startingXi(), 2)).isEqualTo(5);
        assertThat(count(solved.startingXi(), 3)).isEqualTo(2);
        assertThat(count(solved.startingXi(), 4)).isEqualTo(3);
        assertThat(solved.formation()).isEqualTo("5-2-3");
    }

    @Test
    void returnsNothingRatherThanSomethingIllegalWhenTheBudgetCannotBeMet() {
        Random random = new Random(3);
        List<SquadCandidate> pool = pool(random);
        SquadConstraints broke = new SquadConstraints(List.of(), List.of(), List.of(), 100, 5, null);

        assertThat(SquadSolver.solve(pool, scores(pool, random), broke, List.of(),
                allPlayable(pool))).isEmpty();
    }
}
