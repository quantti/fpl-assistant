package fi.karivanttinen.fplassistant.squad;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

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
                () -> pinsWithinTheClubLimit(pinned),
                () -> pinsWithinPositionQuotas(pinned),
                () -> clubsHaveEnoughPlayers(pool, constraints),
                () -> poolCanFillEveryPosition(pool),
                () -> budgetCoversThePinsAndTheCheapestCompletion(pool, constraints, pinned));
    }

    @SafeVarargs
    private static Optional<String> firstOf(java.util.function.Supplier<Optional<String>>... checks) {
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
     * The one refusal that must carry a number. "You cannot afford this" is useless; "you are
     * £3.5m short" tells the user which pin to drop.
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
