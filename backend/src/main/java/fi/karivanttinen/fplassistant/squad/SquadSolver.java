package fi.karivanttinen.fplassistant.squad;

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

    /** £10.0m, in the ×10 integers used everywhere. */
    public static final int PREMIUM_THRESHOLD = 100;
    public static final int MIN_PREMIUMS = 1;
    public static final int MAX_PREMIUMS = 3;
    public static final double TEMPLATE_OWNERSHIP = 20.0;
    public static final int TEMPLATE_MIN_COUNT = 6;

    static final String RELAXED_TEMPLATE = "template minimum";
    static final String RELAXED_PREMIUM = "premium range";
    static final String RELAXED_STARTERS_PLAY = "starters must play";

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
     * Solves with the three structural defaults applied, dropping them one at a time when they
     * cannot all hold.
     *
     * <p><b>User constraints never give way; structural defaults do.</b> The user asked for their
     * constraints and did not ask for the defaults, so a request that cannot satisfy both keeps
     * the request. The order is deliberate: the template minimum protects rank and is the
     * softest, the premium range shapes the squad, and the rule that starters must actually play
     * is given up last because a non-player in the XI is the failure the minutes model exists to
     * prevent.
     *
     * @param expectedMinutes per club fixture, keyed by element id, from {@code HorizonScorer}
     */
    public static Optional<SolvedSquad> solveWithDefaults(List<SquadCandidate> pool,
                                                          Map<Integer, Double> scores,
                                                          SquadConstraints constraints,
                                                          List<SquadCandidate> pinned,
                                                          Map<Integer, Double> expectedMinutes) {
        List<String> ladder = List.of(RELAXED_TEMPLATE, RELAXED_PREMIUM, RELAXED_STARTERS_PLAY);
        List<String> given = new ArrayList<>();

        for (int step = 0; step <= ladder.size(); step++) {
            Optional<SolvedSquad> solved = attempt(pool, scores, constraints, pinned,
                    expectedMinutes, List.copyOf(given));
            if (solved.isPresent()) {
                return solved;
            }
            if (step < ladder.size()) {
                given.add(ladder.get(step));
            }
        }
        return Optional.empty();
    }

    private static Optional<SolvedSquad> attempt(List<SquadCandidate> pool,
                                                 Map<Integer, Double> scores,
                                                 SquadConstraints constraints,
                                                 List<SquadCandidate> pinned,
                                                 Map<Integer, Double> expectedMinutes,
                                                 List<String> relaxed) {
        if (pool.isEmpty()) {
            return Optional.empty();
        }
        ExpressionsBasedModel model = new ExpressionsBasedModel();
        List<Variable> inSquad = new ArrayList<>();
        List<Variable> inXi = new ArrayList<>();
        for (SquadCandidate candidate : pool) {
            double score = scores.getOrDefault(candidate.elementId(), 0.0);
            inSquad.add(model.newVariable("s" + candidate.elementId()).binary()
                    .weight(score * BENCH_WEIGHT));
            inXi.add(model.newVariable("x" + candidate.elementId()).binary()
                    .weight(score * (1 - BENCH_WEIGHT)));
        }

        rulesOfTheGame(model, pool, inSquad, inXi, constraints);
        userConstraints(model, pool, inSquad, constraints, pinned);
        structuralDefaults(model, pool, inSquad, inXi, expectedMinutes, relaxed);

        Optimisation.Result result = model.maximise();
        if (!result.getState().isFeasible()) {
            return Optional.empty();
        }
        return Optional.of(assemble(pool, scores, inSquad, inXi, result, relaxed));
    }

    /**
     * The rows the user asked for. Row 10 — the club quota — is the point of the whole feature:
     * "three City players" is an equality the solver cannot violate, not a preference.
     */
    private static void userConstraints(ExpressionsBasedModel model, List<SquadCandidate> pool,
                                        List<Variable> inSquad, SquadConstraints constraints,
                                        List<SquadCandidate> pinned) {
        List<Integer> pinnedIds = pinned.stream().map(SquadCandidate::elementId).toList();
        List<String> excluded = constraints.mustExclude().stream()
                .map(name -> name.toLowerCase(java.util.Locale.ROOT)).toList();

        for (int i = 0; i < pool.size(); i++) {
            SquadCandidate candidate = pool.get(i);
            if (pinnedIds.contains(candidate.elementId())) {
                inSquad.get(i).level(1);
            } else if (excluded.contains(candidate.webName().toLowerCase(java.util.Locale.ROOT))) {
                inSquad.get(i).level(0);
            }
        }

        int quotaIndex = 0;
        for (var quota : constraints.clubQuotas()) {
            var row = model.newExpression("quota-" + quotaIndex++);
            switch (quota.comparator()) {
                case EXACTLY -> row.level(quota.count());
                case AT_LEAST -> row.lower(quota.count());
                case AT_MOST -> row.upper(quota.count());
            }
            for (int i = 0; i < pool.size(); i++) {
                if (pool.get(i).club().equalsIgnoreCase(quota.club())) {
                    row.set(inSquad.get(i), 1);
                }
            }
        }
    }

    /**
     * The three defaults read out of the strategy corpus, applied only where the user has not
     * spoken. Each is skipped once its label appears in {@code relaxed}.
     *
     * <p>Ownership appears here and nowhere in the scoring. In the score it would be a claim
     * about a player's quality, which is the claim the expected-points model exists to replace.
     * As a constraint it is structural risk management against the field, and the corpus states
     * it plainly: pure template finishes mid-rank, pure contrarian finishes bottom.
     */
    private static void structuralDefaults(ExpressionsBasedModel model, List<SquadCandidate> pool,
                                           List<Variable> inSquad, List<Variable> inXi,
                                           Map<Integer, Double> expectedMinutes,
                                           List<String> relaxed) {
        if (!relaxed.contains(RELAXED_PREMIUM)) {
            var premiums = model.newExpression("premiums")
                    .lower(MIN_PREMIUMS).upper(MAX_PREMIUMS);
            for (int i = 0; i < pool.size(); i++) {
                if (pool.get(i).nowCost() >= PREMIUM_THRESHOLD) {
                    premiums.set(inSquad.get(i), 1);
                }
            }
        }

        if (!relaxed.contains(RELAXED_TEMPLATE)) {
            var template = model.newExpression("template").lower(TEMPLATE_MIN_COUNT);
            for (int i = 0; i < pool.size(); i++) {
                if (pool.get(i).ownership() >= TEMPLATE_OWNERSHIP) {
                    template.set(inSquad.get(i), 1);
                }
            }
        }

        if (!relaxed.contains(RELAXED_STARTERS_PLAY)) {
            // The floor is on the XI, never on the fifteen. The bench carries no minutes
            // requirement at all: there, cheapness competes against playing time through the
            // objective's bench weight, and the cheapest body is sometimes the right pick.
            for (int i = 0; i < pool.size(); i++) {
                double minutes = expectedMinutes.getOrDefault(pool.get(i).elementId(), 0.0);
                if (minutes < HorizonScorer.XI_MIN_EXPECTED_MINUTES) {
                    inXi.get(i).level(0);
                }
            }
        }
    }

    /**
     * The rules FPL itself enforces. These are never relaxed — not for a user constraint, not to
     * rescue an infeasible model. A squad that breaks one of them cannot be entered.
     *
     * <p>A forced formation lives here too, alongside the DEF/FWD floors it replaces: once
     * {@code Feasibility} has confirmed it is a legal starting XI, it is exactly as absolute as
     * the floors are, never given up by the relaxation ladder.
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
        var defInXi = model.newExpression("xi-def");
        var fwdInXi = model.newExpression("xi-fwd");
        // Unbuilt (null) unless a formation is forced: with no formation, midfielders are simply
        // whatever the other three rows leave over, and the existing 5-2-3 test relies on that
        // being unconstrained.
        org.ojalgo.optimisation.Expression midInXi = null;
        SquadConstraints.Formation formation = constraints.formation();
        if (formation != null) {
            // Forced by name: "3-4-3" is an equality nothing can violate, the same discipline
            // rows 1-9 already apply to the club quota and the budget.
            defInXi.level(formation.def());
            fwdInXi.level(formation.fwd());
            midInXi = model.newExpression("xi-mid").level(formation.mid());
        } else {
            defInXi.lower(MIN_DEF_IN_XI);
            fwdInXi.lower(MIN_FWD_IN_XI);
        }

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
                case 3 -> { if (midInXi != null) midInXi.set(x, 1); }
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
        List<SquadCandidate> bench = new ArrayList<>();
        for (int i = 0; i < pool.size(); i++) {
            boolean inTheSquad = chosen(result, inSquad.get(i));
            boolean inTheXi = chosen(result, inXi.get(i));
            if (inTheSquad) {
                squad.add(pool.get(i));
            }
            if (inTheXi) {
                startingXi.add(pool.get(i));
            } else if (inTheSquad) {
                // Squad but not XI, read from the same per-index model result used for squad and
                // startingXi above — not a set difference. SquadCandidate is a record; a
                // removeAll(startingXi) would match on value equality (including metrics), so a
                // pool holding two value-equal candidates would strip every match rather than
                // just the one actually in the XI, silently shrinking the bench.
                bench.add(pool.get(i));
            }
        }

        Comparator<SquadCandidate> byScore = Comparator
                .comparingDouble((SquadCandidate c) -> scores.getOrDefault(c.elementId(), 0.0))
                .reversed();
        startingXi.sort(Comparator.comparingInt(SquadCandidate::elementType).thenComparing(byScore));

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
