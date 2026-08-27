package dev.quantti.fplassistant.squad;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.ojalgo.optimisation.ExpressionsBasedModel;
import org.ojalgo.optimisation.Optimisation;
import org.ojalgo.optimisation.Variable;

/**
 * Picks the squad and the starting XI as one integer program.
 *
 * <p>Two binary variables per candidate: {@code s} for membership of the fifteen, {@code x} for
 * membership of the starting XI. Writing the constraints as literal rows is the point of using a
 * solver at all — "three players from Arsenal" becomes an equality nothing can violate, rather
 * than a preference a language model may or may not honour.
 *
 * <p>The bench carries weight {@link #BENCH_WEIGHT} rather than zero. At zero the solver would
 * fill it with arbitrary cheap names, since they are all equally worthless to it; at a small
 * weight it prefers the ones who actually play, which is what auto-substitution needs.
 */
public final class SquadSolver {

    public static final int XI_SIZE = 11;
    public static final double BENCH_WEIGHT = 0.1;
    public static final int GK_IN_XI = 1;
    public static final int MIN_DEF_IN_XI = 3;
    public static final int MIN_FWD_IN_XI = 1;

    private SquadSolver() {
    }

    public static Optional<SolvedSquad> solve(List<SquadCandidate> pool,
                                              Map<Integer, Double> scores,
                                              SquadConstraints constraints,
                                              List<SquadCandidate> pinned) {
        return solve(pool, scores, constraints, pinned, List.of());
    }

    /**
     * @param relaxed the structural defaults already given up, recorded on the result. Task 8
     *        drives this; with the rules of the game alone it is always empty.
     */
    static Optional<SolvedSquad> solve(List<SquadCandidate> pool, Map<Integer, Double> scores,
                                       SquadConstraints constraints, List<SquadCandidate> pinned,
                                       List<String> relaxed) {
        if (pool.isEmpty()) {
            return Optional.empty();
        }
        ExpressionsBasedModel model = new ExpressionsBasedModel();
        List<Variable> inSquad = new ArrayList<>();
        List<Variable> inXi = new ArrayList<>();

        for (SquadCandidate candidate : pool) {
            double score = scores.getOrDefault(candidate.elementId(), 0.0);
            // The objective reads: a starter is worth his score, a bench player a tenth of it.
            inSquad.add(model.newVariable("s" + candidate.elementId()).binary()
                    .weight(score * BENCH_WEIGHT));
            inXi.add(model.newVariable("x" + candidate.elementId()).binary()
                    .weight(score * (1 - BENCH_WEIGHT)));
        }

        rulesOfTheGame(model, pool, inSquad, inXi, constraints);

        Optimisation.Result result = model.maximise();
        if (!result.getState().isFeasible()) {
            return Optional.empty();
        }
        return Optional.of(assemble(pool, scores, inSquad, inXi, result, relaxed));
    }

    /**
     * The rules FPL itself enforces. These are never relaxed — not for a user constraint, not to
     * rescue an infeasible model. A squad that breaks one of them cannot be entered.
     */
    private static void rulesOfTheGame(ExpressionsBasedModel model, List<SquadCandidate> pool,
                                       List<Variable> inSquad, List<Variable> inXi,
                                       SquadConstraints constraints) {
        var squadSize = model.newExpression("squad-size").level(Feasibility.SQUAD_SIZE);
        var xiSize = model.newExpression("xi-size").level(XI_SIZE);
        var budget = model.newExpression("budget").upper(constraints.budget());

        Map<Integer, org.ojalgo.optimisation.Expression> positionQuota = new HashMap<>();
        for (int type = 1; type < Feasibility.POSITION_QUOTAS.length; type++) {
            positionQuota.put(type, model.newExpression("position-" + type)
                    .level(Feasibility.POSITION_QUOTAS[type]));
        }

        var gkInXi = model.newExpression("xi-gk").level(GK_IN_XI);
        var defInXi = model.newExpression("xi-def").lower(MIN_DEF_IN_XI);
        var fwdInXi = model.newExpression("xi-fwd").lower(MIN_FWD_IN_XI);

        Map<String, org.ojalgo.optimisation.Expression> clubLimit = new HashMap<>();

        for (int i = 0; i < pool.size(); i++) {
            SquadCandidate candidate = pool.get(i);
            Variable s = inSquad.get(i);
            Variable x = inXi.get(i);

            squadSize.set(s, 1);
            xiSize.set(x, 1);
            budget.set(s, candidate.nowCost());

            var quota = positionQuota.get(candidate.elementType());
            if (quota != null) {
                quota.set(s, 1);
            }

            clubLimit.computeIfAbsent(candidate.club().toLowerCase(java.util.Locale.ROOT),
                    key -> model.newExpression("club-" + key).upper(Feasibility.MAX_PER_CLUB))
                    .set(s, 1);

            switch (candidate.elementType()) {
                case 1 -> gkInXi.set(x, 1);
                case 2 -> defInXi.set(x, 1);
                case 4 -> fwdInXi.set(x, 1);
                default -> { }
            }

            // The XI is a subset of the squad: x - s <= 0.
            model.newExpression("xi-subset-" + candidate.elementId()).upper(0)
                    .set(x, 1).set(s, -1);

            // A body is bought, never started. He is asserted to be worth nothing, and starting
            // him would be a claim about him that nothing supports.
            if (candidate.tier() == SquadCandidate.Tier.BODY) {
                x.level(0);
            }
        }
    }

    private static SolvedSquad assemble(List<SquadCandidate> pool, Map<Integer, Double> scores,
                                        List<Variable> inSquad, List<Variable> inXi,
                                        Optimisation.Result result, List<String> relaxed) {
        List<SquadCandidate> squad = new ArrayList<>();
        List<SquadCandidate> startingXi = new ArrayList<>();
        for (int i = 0; i < pool.size(); i++) {
            if (chosen(result, inSquad.get(i))) {
                squad.add(pool.get(i));
            }
            if (chosen(result, inXi.get(i))) {
                startingXi.add(pool.get(i));
            }
        }

        Comparator<SquadCandidate> byScore = Comparator
                .comparingDouble((SquadCandidate c) -> scores.getOrDefault(c.elementId(), 0.0))
                .reversed();
        startingXi.sort(Comparator.comparingInt(SquadCandidate::elementType).thenComparing(byScore));

        List<SquadCandidate> bench = new ArrayList<>(squad);
        bench.removeAll(startingXi);
        // The reserve keeper first — he can only ever replace the keeper — then the outfield
        // substitutes in the order FPL should try them.
        bench.sort(Comparator.comparingInt((SquadCandidate c) -> c.elementType() == 1 ? 0 : 1)
                .thenComparing(byScore));

        List<SquadCandidate> byMerit = new ArrayList<>(startingXi);
        byMerit.sort(byScore);
        SquadCandidate captain = byMerit.getFirst();
        // A different club where one is available: a postponed fixture otherwise takes both, and
        // then no player is doubled at all.
        SquadCandidate viceCaptain = byMerit.stream()
                .skip(1)
                .filter(c -> !c.club().equalsIgnoreCase(captain.club()))
                .findFirst()
                .orElse(byMerit.get(1));

        int totalCost = squad.stream().mapToInt(SquadCandidate::nowCost).sum();

        return new SolvedSquad(squad, startingXi, bench, captain, viceCaptain, totalCost,
                formation(startingXi), relaxed);
    }

    private static boolean chosen(Optimisation.Result result, Variable variable) {
        return result.doubleValue(variable.getIndex().index) > 0.5;
    }

    static String formation(List<SquadCandidate> startingXi) {
        long def = startingXi.stream().filter(c -> c.elementType() == 2).count();
        long mid = startingXi.stream().filter(c -> c.elementType() == 3).count();
        long fwd = startingXi.stream().filter(c -> c.elementType() == 4).count();
        return "%d-%d-%d".formatted(def, mid, fwd);
    }
}
