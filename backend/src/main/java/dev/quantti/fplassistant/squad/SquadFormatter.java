package dev.quantti.fplassistant.squad;

import java.util.List;
import java.util.Locale;

import dev.quantti.fplassistant.tools.ExpectedPoints;
import java.util.Map;

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

    private static final String[] POSITION_ABBR = {"", "GK", "DEF", "MID", "FWD"};

    private SquadFormatter() {
    }

    /**
     * @param resolvedIncludes {@code mustInclude} resolved to real candidates, index-parallel with
     *        {@code constraints.mustInclude()} — every name is resolved to exactly one player
     *        before this method ever runs, so the two lists are always the same length
     * @param resolvedExcludes {@code mustExclude} resolved the same way, index-parallel with
     *        {@code constraints.mustExclude()}
     * @param scores expected points over the horizon, keyed by element id — the same map
     *        {@code SquadTools} already built to feed the solver
     * @param expectedMinutes expected minutes per club fixture, keyed by element id
     * @param noCandidateWasScoreable true when not one rankable candidate in the whole pool found
     *        a fixture to score against, so every score is zero and this squad is an arbitrary
     *        legal fifteen rather than a ranked one
     */
    public static String format(SolvedSquad solved, SquadConstraints constraints,
                                String priceProvenance, String metricProvenance,
                                int excludedForNoRecord, List<SquadCandidate> resolvedIncludes,
                                List<SquadCandidate> resolvedExcludes,
                                Map<Integer, Double> scores, Map<Integer, Double> expectedMinutes,
                                boolean noCandidateWasScoreable, String blendNote) {
        StringBuilder out = new StringBuilder();
        out.append(priceProvenance).append('\n').append(metricProvenance).append('\n');
        if (blendNote != null) {
            out.append(blendNote).append('\n');
        }
        out.append('\n');

        if (noCandidateWasScoreable) {
            out.append("WARNING: no player in today's pool could be matched to a real fixture for"
                    + " this horizon, so every expected-points figure below is zero. This is an"
                    + " arbitrary legal fifteen, not a ranked selection — the pick of players, the"
                    + " captain and the bench order carry no signal. Say so rather than presenting"
                    + " it as a ranked squad.\n\n");
        }

        out.append("Constraints\n");
        for (int i = 0; i < constraints.mustInclude().size(); i++) {
            String name = constraints.mustInclude().get(i);
            int id = resolvedIncludes.get(i).elementId();
            boolean included = solved.squad().stream().anyMatch(c -> c.elementId() == id);
            out.append("  ").append(name).append(": included ")
                    .append(included ? "✓" : "✗").append("\n");
        }
        for (int i = 0; i < constraints.mustExclude().size(); i++) {
            String name = constraints.mustExclude().get(i);
            int id = resolvedExcludes.get(i).elementId();
            boolean excluded = solved.squad().stream().noneMatch(c -> c.elementId() == id);
            out.append("  ").append(name).append(": excluded ")
                    .append(excluded ? "✓" : "✗").append("\n");
        }
        for (var quota : constraints.clubQuotas()) {
            long achieved = solved.squad().stream()
                    .filter(c -> c.teamId() == quota.teamId())
                    .count();
            boolean met = switch (quota.comparator()) {
                case EXACTLY -> achieved == quota.count();
                case AT_LEAST -> achieved >= quota.count();
                case AT_MOST -> achieved <= quota.count();
            };
            out.append("  ").append(quota.club()).append(": ").append(achieved).append('/')
                    .append(describe(quota)).append(" ").append(met ? "✓" : "✗").append("\n");
        }
        boolean budgetMet = solved.totalCost() <= constraints.budget();
        out.append("  Budget: £").append(Feasibility.money(solved.totalCost())).append(" of £")
                .append(Feasibility.money(constraints.budget())).append(" ").append(budgetMet ? "✓" : "✗").append("\n");
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
            out.append("  ").append(line(player, scores)).append('\n');
        }

        out.append("\nBench, in substitution order\n");
        int order = 1;
        for (SquadCandidate player : solved.bench()) {
            String prefix = player.elementType() == 1 ? "  " : "  " + order++ + ". ";
            out.append(prefix).append(line(player, scores))
                    .append(benchMinutesNote(player, expectedMinutes)).append('\n');
        }

        out.append("\nCaptain: ").append(solved.captain().webName())
                .append(" (").append(solved.captain().clubShort()).append(")\n");
        out.append("Vice-captain: ").append(solved.viceCaptain().webName())
                .append(" (").append(solved.viceCaptain().clubShort()).append(")\n");

        out.append("\nTotal £").append(Feasibility.money(solved.totalCost())).append(", £")
                .append(Feasibility.money(constraints.budget() - solved.totalCost())).append(" left.\n");

        if (!constraints.isFromScratchBudget()) {
            out.append("\nThis budget is not the from-scratch £100.0m, so it looks like a wildcard:"
                    + " every player here is priced as a fresh purchase, because FPL does not"
                    + " publish a manager's selling price. Selling returns the purchase price plus"
                    + " half the profit, so a squad you already part-own costs less than this in"
                    + " reality — the figure is conservative, never optimistic.\n");
        }

        if (excludedForNoRecord > 0) {
            out.append("\n").append(excludedForNoRecord)
                    .append(" players were left out of the ranking because they have too little"
                            + " playing history to judge — new signings, and players back from"
                            + " long injuries. Name them and I will build around them anyway.\n");
        }

        out.append("\nSave points and penalty saves are not in these numbers, so goalkeepers are"
                + " understated. Defensive contribution is included, as each player's estimated"
                + " chance of reaching his position's action threshold in a match.\n");

        return out.toString();
    }

    private static String describe(SquadConstraints.ClubQuota quota) {
        return switch (quota.comparator()) {
            case EXACTLY -> String.valueOf(quota.count());
            case AT_LEAST -> "at least " + quota.count();
            case AT_MOST -> "at most " + quota.count();
        };
    }

    /**
     * @param scores expected points over the horizon, keyed by element id — printed for every
     *        real player so the model has something to quote when asked "why this squad?"
     *        instead of reaching elsewhere or inventing a reason.
     */
    private static String line(SquadCandidate player, Map<Integer, Double> scores) {
        StringBuilder line = new StringBuilder();
        line.append(POSITION_ABBR[player.elementType()])
                .append("  ").append(player.webName())
                .append(" (").append(player.clubShort()).append(") £")
                .append(Feasibility.money(player.nowCost()));
        if (player.tier() == SquadCandidate.Tier.BODY) {
            line.append(" — bench body, no playing record, worth nothing in this model");
        } else {
            line.append(String.format(Locale.ROOT, " — %.2f xPts",
                    scores.getOrDefault(player.elementId(), 0.0)));
            Object hasPrevious = player.metrics().get("blend_has_previous");
            if (hasPrevious != null) {
                int pct = (int) Math.round(
                        ExpectedPoints.numeric(player.metrics().get("blend_weight")) * 100);
                if (!Boolean.TRUE.equals(hasPrevious)) {
                    line.append(" (this season only — no previous record)");
                } else if (pct < 100) {
                    line.append(" (this season ").append(pct).append("%)");
                }
            }
        }
        if (player.isDoubtful()) {
            line.append(" — DOUBTFUL: ").append(player.news());
        }
        return line.toString();
    }

    /**
     * Expected minutes per club fixture, printed on bench rows only. The XI already clears the
     * minutes floor by construction (row 14), so the number is uninteresting there; on the bench
     * it is the whole trade the spec asks to make visible — cheapness against playing time.
     */
    private static String benchMinutesNote(SquadCandidate player, Map<Integer, Double> expectedMinutes) {
        if (player.tier() == SquadCandidate.Tier.BODY) {
            return "";
        }
        double minutes = expectedMinutes.getOrDefault(player.elementId(), 0.0);
        return String.format(Locale.ROOT, ", %.0f expected mins/fixture", minutes);
    }
}
