package dev.quantti.fplassistant.squad;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

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

    private static final int[] QUOTAS = Feasibility.POSITION_QUOTAS;

    private static SquadCandidate player(int id, String club, int type, int cost) {
        return new SquadCandidate(id, "P" + id, "Player " + id, club, club.substring(0, 3),
                type, cost, 5.0, "a", null, club.hashCode(), 900, 10,
                Map.of("element_type", type), SquadCandidate.Tier.RANKABLE);
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

    @RepeatedTest(20)
    void everyResultObeysEveryRuleOfTheGame() {
        Random random = new Random();
        List<SquadCandidate> pool = pool(random);

        SolvedSquad solved = SquadSolver.solve(pool, scores(pool, random), plain(), List.of())
                .orElseThrow(() -> new AssertionError("a full pool inside budget must solve"));

        assertEveryRule(solved);
    }

    static void assertEveryRule(SolvedSquad solved) {
        assertThat(solved.squad()).hasSize(Feasibility.SQUAD_SIZE);
        assertThat(solved.squad()).extracting(SquadCandidate::elementId).doesNotHaveDuplicates();

        for (int type = 1; type <= 4; type++) {
            final int position = type;
            assertThat(solved.squad().stream().filter(c -> c.elementType() == position).count())
                    .as("squad %ss", Feasibility.POSITION_NAMES[type])
                    .isEqualTo(QUOTAS[type]);
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
        // A free body at every position, worth nothing but priced at the floor.
        int id = 9000;
        for (int type = 1; type <= 4; type++) {
            pool.add(new SquadCandidate(id++, "Body" + type, "Body " + type, "Bodytown", "BOD",
                    type, 1, 0.1, "a", null, 999, 0, 0, Map.of(),
                    SquadCandidate.Tier.BODY));
        }
        Map<Integer, Double> scores = scores(pool, random);
        pool.stream().filter(c -> c.tier() == SquadCandidate.Tier.BODY)
                .forEach(c -> scores.put(c.elementId(), 0.0));

        SolvedSquad solved = SquadSolver.solve(pool, scores, plain(), List.of()).orElseThrow();

        assertThat(solved.startingXi()).noneMatch(c -> c.tier() == SquadCandidate.Tier.BODY);
        assertEveryRule(solved);
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

        SolvedSquad solved = SquadSolver.solve(pool, scores, plain(), List.of()).orElseThrow();

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

        assertThat(SquadSolver.solve(pool, scores(pool, random), broke, List.of())).isEmpty();
    }
}
