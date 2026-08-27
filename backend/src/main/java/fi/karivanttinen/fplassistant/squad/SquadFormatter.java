package fi.karivanttinen.fplassistant.squad;

import java.util.List;
import java.util.Locale;

/**
 * Renders a solved squad.
 *
 * <p><b>The constraint receipt is the point.</b> The failure this feature replaces was a reply
 * that stated a constraint it had not met — "Arsenal: Gabriel, Calafiori, £4.5m defender" — and
 * a user cannot be expected to audit fifteen rows by hand. Every constraint is printed beside
 * what was actually achieved.
 *
 * <p>Every decimal is pinned to {@link Locale#ROOT}: under a Finnish default locale a comma
 * decimal separator reads as a thousands separator, and £8,0m looks like eighty million.
 */
public final class SquadFormatter {

    private SquadFormatter() {
    }

    public static String format(SolvedSquad solved, SquadConstraints constraints,
                                String priceProvenance, String metricProvenance,
                                int excludedForNoRecord) {
        StringBuilder out = new StringBuilder();
        out.append(priceProvenance).append('\n').append(metricProvenance).append("\n\n");

        out.append("Constraints\n");
        for (String name : constraints.mustInclude()) {
            out.append("  ").append(name).append(": included ✓\n");
        }
        for (String name : constraints.mustExclude()) {
            out.append("  ").append(name).append(": excluded ✓\n");
        }
        for (var quota : constraints.clubQuotas()) {
            long achieved = solved.squad().stream()
                    .filter(c -> c.club().equalsIgnoreCase(quota.club()))
                    .count();
            out.append("  ").append(quota.club()).append(": ").append(achieved).append('/')
                    .append(describe(quota)).append(" ✓\n");
        }
        out.append("  Budget: £").append(money(solved.totalCost())).append(" of £")
                .append(money(constraints.budget())).append(" ✓\n");
        if (!solved.relaxed().isEmpty()) {
            // The wording avoids claiming necessity — the solver's ladder gives up defaults
            // cumulatively, so this list is the set that were not enforced, not a minimal set
            // of what had to go. Don't claim the list is minimal.
            out.append("  Structural defaults not enforced in this squad: ")
                    .append(String.join(", ", solved.relaxed())).append('\n');
        }
        out.append('\n');

        out.append("Starting XI (").append(solved.formation()).append(")\n");
        for (SquadCandidate player : solved.startingXi()) {
            out.append("  ").append(line(player, solved)).append('\n');
        }

        out.append("\nBench, in substitution order\n");
        int order = 1;
        for (SquadCandidate player : solved.bench()) {
            String prefix = player.elementType() == 1 ? "  GK " : "  " + order++ + ". ";
            out.append(prefix).append(line(player, solved)).append('\n');
        }

        out.append("\nCaptain: ").append(solved.captain().webName())
                .append(" (").append(solved.captain().clubShort()).append(")\n");
        out.append("Vice-captain: ").append(solved.viceCaptain().webName())
                .append(" (").append(solved.viceCaptain().clubShort()).append(")\n");

        out.append("\nTotal £").append(money(solved.totalCost())).append(", £")
                .append(money(constraints.budget() - solved.totalCost())).append(" left.\n");

        if (!constraints.isFromScratchBudget()) {
            out.append("This budget is not the from-scratch £100.0m, so it looks like a wildcard:"
                    + " every player here is priced as a fresh purchase, because FPL does not"
                    + " publish a manager's selling price. Selling returns the purchase price plus"
                    + " half the profit, so a squad you already part-own costs less than this in"
                    + " reality — the figure is conservative, never optimistic.\n");
        }

        if (excludedForNoRecord > 0) {
            out.append(excludedForNoRecord)
                    .append(" players were left out of the ranking because they have too little"
                            + " playing history to judge — new signings, and players back from"
                            + " long injuries. Name them and I will build around them anyway.\n");
        }

        out.append("Defensive-contribution points, save points and penalty saves are not in these"
                + " numbers, so goalkeepers and ball-winning defenders are understated.\n");

        return out.toString();
    }

    private static String describe(SquadConstraints.ClubQuota quota) {
        return switch (quota.comparator()) {
            case EXACTLY -> String.valueOf(quota.count());
            case AT_LEAST -> "at least " + quota.count();
            case AT_MOST -> "at most " + quota.count();
        };
    }

    private static String line(SquadCandidate player, SolvedSquad solved) {
        StringBuilder line = new StringBuilder();
        line.append(Feasibility.POSITION_NAMES[player.elementType()].substring(0, 3).toUpperCase(Locale.ROOT))
                .append("  ").append(player.webName())
                .append(" (").append(player.clubShort()).append(") £")
                .append(money(player.nowCost()));
        if (player.tier() == SquadCandidate.Tier.BODY) {
            line.append(" — bench body, no playing record, worth nothing in this model");
        }
        if (player.isDoubtful()) {
            line.append(" — DOUBTFUL: ").append(player.news());
        }
        return line.toString();
    }

    static String money(int tenths) {
        return String.format(Locale.ROOT, "%.1fm", tenths / 10.0);
    }
}
