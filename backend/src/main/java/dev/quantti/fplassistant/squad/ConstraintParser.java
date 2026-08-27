package dev.quantti.fplassistant.squad;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Turns the strings a language model writes into constraints a solver can hold.
 *
 * <p>Club quotas carry a comparator because all three kinds get asked for: exactly three from
 * one club, at least two from another, at most one from a third. A bare number means exactly.
 *
 * <p>Anything malformed throws rather than being silently dropped. A constraint the user stated
 * and the tool ignored is the failure this whole feature exists to prevent.
 */
public final class ConstraintParser {

    public static final int BUDGET_DEFAULT = 1000;
    public static final int HORIZON_DEFAULT = 5;

    /** A club quota fragment: name, colon, optional comparator, digits. */
    private static final java.util.regex.Pattern QUOTA =
            java.util.regex.Pattern.compile("^(.+?):(>=|<=|=)?(\\d+)$");

    private ConstraintParser() {
    }

    public static class MalformedConstraintException extends RuntimeException {
        public MalformedConstraintException(String message) {
            super(message);
        }
    }

    public static SquadConstraints parse(String mustInclude, String mustExclude, String clubQuota,
                                         double budget, int nextN, String formation) {
        return new SquadConstraints(
                names(mustInclude),
                names(mustExclude),
                quotas(clubQuota),
                budget <= 0 ? BUDGET_DEFAULT : (int) Math.round(budget * 10),
                nextN <= 0 ? HORIZON_DEFAULT : nextN,
                formation == null || formation.isBlank() ? null : formation.trim());
    }

    private static List<String> names(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .toList();
    }

    /**
     * Sorted by club name so the order is stable however the model wrote them — an output that
     * reorders itself between two identical calls reads as a different answer.
     */
    private static List<SquadConstraints.ClubQuota> quotas(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(fragment -> !fragment.isEmpty())
                .map(ConstraintParser::quota)
                .sorted(Comparator.comparing(q -> q.club().toLowerCase(Locale.ROOT)))
                .toList();
    }

    private static SquadConstraints.ClubQuota quota(String fragment) {
        var matcher = QUOTA.matcher(fragment);
        if (!matcher.matches()) {
            throw new MalformedConstraintException(
                    "Could not read the club quota \"" + fragment + "\". Write it as"
                            + " \"Arsenal:3\", \"Arsenal:>=2\" or \"Arsenal:<=2\".");
        }
        String club = matcher.group(1).trim();
        if (club.isEmpty()) {
            throw new MalformedConstraintException(
                    "The club quota \"" + fragment + "\" names no club.");
        }
        SquadConstraints.Comparator comparator = switch (matcher.group(2) == null ? "=" : matcher.group(2)) {
            case ">=" -> SquadConstraints.Comparator.AT_LEAST;
            case "<=" -> SquadConstraints.Comparator.AT_MOST;
            default -> SquadConstraints.Comparator.EXACTLY;
        };
        return new SquadConstraints.ClubQuota(club, comparator, Integer.parseInt(matcher.group(3)));
    }
}
