package fi.karivanttinen.fplassistant.squad;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Everything checked before the integer program runs.
 *
 * <p>ojAlgo answers "infeasible" without saying which row caused it, and "no solution" is not
 * something a user can act on. These checks catch what people actually ask for that cannot be
 * had, and each returns a sentence naming the constraint — and, where money is involved, the
 * amount by which it fails.
 */
public final class Feasibility {

    public static final int SQUAD_SIZE = 15;
    public static final int MAX_PER_CLUB = 3;
    public static final int[] POSITION_QUOTAS = {0, 2, 5, 5, 3};
    public static final String[] POSITION_NAMES =
            {"", "goalkeeper", "defender", "midfielder", "forward"};

    private Feasibility() {
    }

    public static Optional<String> check(List<SquadCandidate> pool, SquadConstraints constraints,
                                         List<SquadCandidate> pinned) {
        return firstOf(
                () -> quotasWithinTheGamesLimit(constraints),
                () -> quotasAgreeWithEachOther(constraints),
                () -> quotaMinimumsFit(constraints),
                () -> formationIsLegal(constraints),
                () -> pinsWithinTheClubLimit(pinned),
                () -> pinsWithinPositionQuotas(pinned),
                () -> clubsHaveEnoughPlayers(pool, constraints),
                () -> poolCanFillEveryPosition(pool),
                () -> pinsNeverExcluded(pinned, constraints),
                () -> budgetCoversThePinsAndTheCheapestCompletion(pool, constraints, pinned));
    }

    @SafeVarargs
    private static Optional<String> firstOf(Supplier<Optional<String>>... checks) {
        for (var check : checks) {
            Optional<String> refusal = check.get();
            if (refusal.isPresent()) {
                return refusal;
            }
        }
        return Optional.empty();
    }

    private static Optional<String> quotasWithinTheGamesLimit(SquadConstraints constraints) {
        for (var quota : constraints.clubQuotas()) {
            boolean isFloor = quota.comparator() != SquadConstraints.Comparator.AT_MOST;
            if (isFloor && quota.count() > MAX_PER_CLUB) {
                return Optional.of(("You asked for %d players from %s, but FPL allows at most %d"
                        + " from any one club. Lower that quota and I can build the squad.")
                        .formatted(quota.count(), quota.club(), MAX_PER_CLUB));
            }
        }
        return Optional.empty();
    }

    private static Optional<String> quotasAgreeWithEachOther(SquadConstraints constraints) {
        Map<String, Integer> floors = new HashMap<>();
        Map<String, Integer> ceilings = new HashMap<>();
        for (var quota : constraints.clubQuotas()) {
            String key = quota.club().toLowerCase(Locale.ROOT);
            switch (quota.comparator()) {
                case EXACTLY -> {
                    floors.merge(key, quota.count(), Math::max);
                    ceilings.merge(key, quota.count(), Math::min);
                }
                case AT_LEAST -> floors.merge(key, quota.count(), Math::max);
                case AT_MOST -> ceilings.merge(key, quota.count(), Math::min);
            }
        }
        for (var entry : floors.entrySet()) {
            Integer ceiling = ceilings.get(entry.getKey());
            if (ceiling != null && ceiling < entry.getValue()) {
                return Optional.of(("The quotas for %s contradict each other: at least %d and at"
                        + " most %d cannot both hold.")
                        .formatted(displayName(constraints, entry.getKey()),
                                entry.getValue(), ceiling));
            }
        }
        return Optional.empty();
    }

    private static String displayName(SquadConstraints constraints, String lowercased) {
        return constraints.clubQuotas().stream()
                .filter(q -> q.club().toLowerCase(Locale.ROOT).equals(lowercased))
                .map(SquadConstraints.ClubQuota::club)
                .findFirst().orElse(lowercased);
    }

    private static Optional<String> quotaMinimumsFit(SquadConstraints constraints) {
        Map<String, Integer> floors = new HashMap<>();
        for (var quota : constraints.clubQuotas()) {
            if (quota.comparator() != SquadConstraints.Comparator.AT_MOST) {
                floors.merge(quota.club().toLowerCase(Locale.ROOT), quota.count(), Math::max);
            }
        }
        int total = floors.values().stream().mapToInt(Integer::intValue).sum();
        if (total > SQUAD_SIZE) {
            return Optional.of(("Those club quotas require %d players and a squad holds %d."
                    + " Drop one of them and I can build the squad.")
                    .formatted(total, SQUAD_SIZE));
        }
        return Optional.empty();
    }

    /**
     * A forced formation must actually describe a legal starting XI: ten outfield players
     * (eleven minus the fixed goalkeeper), at least as many defenders and forwards as the game
     * itself requires, and never more of a position than a squad holds of it in total. Syntax
     * ("three numbers separated by dashes") is {@code ConstraintParser}'s job; whether those
     * numbers describe a squad that can exist is this one's, same split as everywhere else here.
     */
    private static Optional<String> formationIsLegal(SquadConstraints constraints) {
        SquadConstraints.Formation formation = constraints.formation();
        if (formation == null) {
            return Optional.empty();
        }
        int outfield = SquadSolver.XI_SIZE - SquadSolver.GK_IN_XI;
        int sum = formation.def() + formation.mid() + formation.fwd();
        if (sum != outfield) {
            return Optional.of(("The formation %s has %d outfield players, but a starting XI"
                    + " needs %d (%d minus the goalkeeper). Fix the numbers and I can build the"
                    + " squad.").formatted(formation.label(), sum, outfield, SquadSolver.XI_SIZE));
        }
        if (formation.def() < SquadSolver.MIN_DEF_IN_XI || formation.def() > POSITION_QUOTAS[2]) {
            return Optional.of(("The formation %s needs between %d and %d defenders in the"
                    + " starting XI, and it has %d. Fix the numbers and I can build the squad.")
                    .formatted(formation.label(), SquadSolver.MIN_DEF_IN_XI, POSITION_QUOTAS[2],
                            formation.def()));
        }
        if (formation.fwd() < SquadSolver.MIN_FWD_IN_XI || formation.fwd() > POSITION_QUOTAS[4]) {
            return Optional.of(("The formation %s needs between %d and %d forwards in the"
                    + " starting XI, and it has %d. Fix the numbers and I can build the squad.")
                    .formatted(formation.label(), SquadSolver.MIN_FWD_IN_XI, POSITION_QUOTAS[4],
                            formation.fwd()));
        }
        if (formation.mid() > POSITION_QUOTAS[3]) {
            return Optional.of(("The formation %s needs at most %d midfielders in the starting"
                    + " XI, and it has %d. Fix the numbers and I can build the squad.")
                    .formatted(formation.label(), POSITION_QUOTAS[3], formation.mid()));
        }
        return Optional.empty();
    }

    private static Optional<String> pinsWithinTheClubLimit(List<SquadCandidate> pinned) {
        Map<String, Integer> byClub = new HashMap<>();
        for (SquadCandidate player : pinned) {
            byClub.merge(player.club(), 1, Integer::sum);
        }
        for (var entry : byClub.entrySet()) {
            if (entry.getValue() > MAX_PER_CLUB) {
                return Optional.of(("You named %d players from %s. FPL allows at most %d from any"
                        + " one club, so that squad cannot exist.")
                        .formatted(entry.getValue(), entry.getKey(), MAX_PER_CLUB));
            }
        }
        return Optional.empty();
    }

    private static Optional<String> pinsWithinPositionQuotas(List<SquadCandidate> pinned) {
        int[] counts = new int[POSITION_QUOTAS.length];
        for (SquadCandidate player : pinned) {
            if (player.elementType() >= 1 && player.elementType() < counts.length) {
                counts[player.elementType()]++;
            }
        }
        for (int type = 1; type < counts.length; type++) {
            if (counts[type] > POSITION_QUOTAS[type]) {
                return Optional.of(("You named %d %ss and a squad holds %d.")
                        .formatted(counts[type], POSITION_NAMES[type], POSITION_QUOTAS[type]));
            }
        }
        return Optional.empty();
    }

    private static Optional<String> clubsHaveEnoughPlayers(List<SquadCandidate> pool,
                                                          SquadConstraints constraints) {
        for (var quota : constraints.clubQuotas()) {
            if (quota.comparator() == SquadConstraints.Comparator.AT_MOST) {
                continue;
            }
            long available = pool.stream()
                    .filter(c -> c.club().equalsIgnoreCase(quota.club()))
                    .count();
            if (available < quota.count()) {
                return Optional.of(("You asked for %d players from %s, but only %d are available"
                        + " and fit to pick right now.")
                        .formatted(quota.count(), quota.club(), available));
            }
        }
        return Optional.empty();
    }

    private static Optional<String> poolCanFillEveryPosition(List<SquadCandidate> pool) {
        for (int type = 1; type < POSITION_QUOTAS.length; type++) {
            final int position = type;
            long available = pool.stream().filter(c -> c.elementType() == position).count();
            if (available < POSITION_QUOTAS[type]) {
                return Optional.of(("There are only %d %ss available and a squad needs %d."
                        + " The player data may not have been captured yet.")
                        .formatted(available, POSITION_NAMES[type], POSITION_QUOTAS[type]));
            }
        }
        return Optional.empty();
    }

    /**
     * A player named in both lists is a contradiction, not a priority to guess at. Left unchecked,
     * {@code SquadSolver.userConstraints} resolves it by code order — the pin is applied first and
     * wins silently — which is the exact silent-priority-between-user-constraints failure this
     * project's constraints are meant to forbid. Refusing by name is the safe direction: better to
     * ask which one the user meant than to guess.
     */
    private static Optional<String> pinsNeverExcluded(List<SquadCandidate> pinned,
                                                       SquadConstraints constraints) {
        List<String> excluded = constraints.mustExclude().stream()
                .map(name -> name.toLowerCase(Locale.ROOT)).toList();
        for (SquadCandidate player : pinned) {
            if (excluded.contains(player.webName().toLowerCase(Locale.ROOT))) {
                return Optional.of(("You asked me to include %s and also to exclude %s. Only one"
                        + " can be right — tell me which one you meant and I can build the squad.")
                        .formatted(player.webName(), player.webName()));
            }
        }
        return Optional.empty();
    }

    /**
     * The one refusal that must carry a number. "You cannot afford this" is useless; "you are
     * £3.5m short" tells the user which pin to drop.
     *
     * <p>The cheapest-completion loop cannot under-count only because `pinsWithinPositionQuotas`
     * and `poolCanFillEveryPosition` have already passed — under-counting would require a position
     * with fewer pool players than its quota, which check 7 forecloses. Reordering the check chain
     * or lifting this check out for standalone use would silently produce a too-low estimate.
     */
    private static Optional<String> budgetCoversThePinsAndTheCheapestCompletion(
            List<SquadCandidate> pool, SquadConstraints constraints, List<SquadCandidate> pinned) {
        int spent = pinned.stream().mapToInt(SquadCandidate::nowCost).sum();

        int[] stillNeeded = POSITION_QUOTAS.clone();
        for (SquadCandidate player : pinned) {
            if (player.elementType() >= 1 && player.elementType() < stillNeeded.length) {
                stillNeeded[player.elementType()]--;
            }
        }

        List<Integer> pinnedIds = pinned.stream().map(SquadCandidate::elementId).toList();
        int cheapest = 0;
        for (int type = 1; type < stillNeeded.length; type++) {
            final int position = type;
            List<Integer> costs = new ArrayList<>(pool.stream()
                    .filter(c -> c.elementType() == position)
                    .filter(c -> !pinnedIds.contains(c.elementId()))
                    .map(SquadCandidate::nowCost)
                    .sorted()
                    .toList());
            for (int n = 0; n < stillNeeded[type] && n < costs.size(); n++) {
                cheapest += costs.get(n);
            }
        }

        int total = spent + cheapest;
        if (total > constraints.budget()) {
            return Optional.of(("Those players cost £%s and the cheapest legal squad around them"
                    + " comes to £%s, which is £%s over the £%s available. Drop or downgrade one"
                    + " of them and I can build the squad.")
                    .formatted(money(spent), money(total), money(total - constraints.budget()),
                            money(constraints.budget())));
        }
        return Optional.empty();
    }

    /** Tenths of a million as pounds. Pinned to ROOT: a comma decimal reads as thousands. */
    static String money(int tenths) {
        return String.format(Locale.ROOT, "%.1fm", tenths / 10.0);
    }
}
