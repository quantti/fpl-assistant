package dev.quantti.fplassistant.squad;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import dev.quantti.fplassistant.schedule.HorizonFixtures;
import dev.quantti.fplassistant.tools.ExpectedPoints;
import dev.quantti.fplassistant.tools.UkTime;

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
     * @param scheduleProvenance the schedule capture's own source line — the third of the three,
     *        printed under the price and metric lines and carrying the changeover clarification
     *        when the capture and the metric season are not the same season. Required, like its
     *        two siblings: every reply names all three sources, and before the first capture the
     *        caller passes a line that says so rather than omitting it
     * @param projections expected points over the horizon plus whether the projection is known;
     *        the solver receives zero weight for an unavailable projection, but the reply does
     *        not mislabel that absence as a numeric forecast
     * @param noCandidateWasScoreable true when not one rankable candidate in the whole pool had a
     *        usable projection at all, so every score is zero and this squad is an arbitrary legal
     *        fifteen rather than a ranked one
     * @param horizon the editable gameweek window every projection was summed over — named in the
     *        reply so a blank ("0.00 over GW4–8") cannot be read as an unknown, or the reverse
     */
    public static String format(SolvedSquad solved, SquadConstraints constraints,
                                String priceProvenance, String metricProvenance,
                                String scheduleProvenance,
                                int excludedForNoRecord, List<SquadCandidate> resolvedIncludes,
                                List<SquadCandidate> resolvedExcludes,
                                Map<Integer, HorizonScorer.Projection> projections,
                                boolean noCandidateWasScoreable, String blendNote,
                                HorizonFixtures.Result horizon) {
        // All three source lines are required, and the third is the one a future caller is
        // likeliest to forget: an optional schedule line would let an unattributed reply compile.
        StringBuilder out = new StringBuilder();
        out.append(Objects.requireNonNull(priceProvenance, "priceProvenance")).append('\n')
                .append(Objects.requireNonNull(metricProvenance, "metricProvenance")).append('\n')
                .append(Objects.requireNonNull(scheduleProvenance, "scheduleProvenance"))
                .append('\n');
        if (blendNote != null) {
            out.append(blendNote).append('\n');
        }
        out.append(horizonLine(horizon)).append('\n');
        out.append('\n');

        if (noCandidateWasScoreable) {
            out.append("WARNING: no player in today's pool has a usable horizon projection."
                    + " Figures below are n/a, not zero-point forecasts; any legal fifteen would"
                    + " be unranked, so do not present the names, captain or bench order as a"
                    + " model preference.\n\n");
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
            out.append("  ").append(line(player, projections, horizon)).append('\n');
        }

        out.append("\nBench, in substitution order\n");
        int order = 1;
        for (SquadCandidate player : solved.bench()) {
            String prefix = player.elementType() == 1 ? "  " : "  " + order++ + ". ";
            out.append(prefix).append(line(player, projections, horizon))
                    .append(benchMinutesNote(player)).append('\n');
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
                            + " long injuries. A named fit player may be carried on the bench, but"
                            + " a pin does not make them eligible for the XI.\n");
        }

        out.append("\nSave points and penalty saves are not in these numbers, so goalkeepers are"
                + " understated. Defensive contribution is included where its appearance-class"
                + " evidence is available; partial expected-point rows are labelled.\n");

        return out.toString();
    }

    private static String describe(SquadConstraints.ClubQuota quota) {
        return switch (quota.comparator()) {
            case EXACTLY -> String.valueOf(quota.count());
            case AT_LEAST -> "at least " + quota.count();
            case AT_MOST -> "at most " + quota.count();
        };
    }

    /** The editable window as the reply names it, for example {@code GW4–8}. */
    static String gameweekRange(HorizonFixtures.Result horizon) {
        return "GW" + horizon.startGameweek() + "–" + horizon.endGameweek();
    }

    /** An {@code UnknownReason} as prose, the same shape the pick tools print it in. */
    static String horizonReason(HorizonFixtures.UnknownReason reason) {
        return reason.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static String horizonLine(HorizonFixtures.Result horizon) {
        if (horizon.status() != HorizonFixtures.Status.KNOWN) {
            return "Horizon: unknown (" + horizonReason(horizon.reason())
                    + "), so no fixture count below is a zero.";
        }
        String line = "Horizon: " + gameweekRange(horizon) + " (" + horizon.actualGameweeks()
                + " calendar gameweek" + (horizon.actualGameweeks() == 1 ? "" : "s") + ")";
        if (horizon.truncated()) {
            line += "; " + horizon.requestedGameweeks() + " requested, truncated to "
                    + horizon.actualGameweeks() + " by the end of the season";
        }
        return line + ".";
    }

    private static String line(SquadCandidate player,
                               Map<Integer, HorizonScorer.Projection> projections,
                               HorizonFixtures.Result horizon) {
        StringBuilder line = new StringBuilder();
        line.append(POSITION_ABBR[player.elementType()])
                .append("  ").append(player.webName())
                .append(" (").append(player.clubShort()).append(") £")
                .append(Feasibility.money(player.nowCost()));
        HorizonScorer.Projection projection = projections.get(player.elementId());
        if (player.tier() == SquadCandidate.Tier.BODY) {
            line.append(" — bench body, no rankable ability record; unscored");
        } else {
            line.append(" — ").append(expectedPoints(projection, horizon));
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
        if (!player.mayStart()) {
            line.append(" — bench only: ").append(player.eligibility().reason());
        }
        if (player.minutesWindow() != null) {
            line.append(" — observed current-club 60+ ")
                    .append(player.minutesWindow().sixtyPlus()).append('/')
                    .append(player.minutesWindow().expected()).append(", ")
                    .append(player.minutesWindow().totalMinutes()).append(" mins");
            if (player.minutesWindow().firstKickoff() != null
                    && player.minutesWindow().lastKickoff() != null) {
                LocalDate first = LocalDate.ofInstant(
                        player.minutesWindow().firstKickoff(), UkTime.zone());
                LocalDate last = LocalDate.ofInstant(
                        player.minutesWindow().lastKickoff(), UkTime.zone());
                line.append(", ").append(first);
                if (!first.equals(last)) {
                    line.append(" to ").append(last);
                }
            }
            if (!player.minutesWindow().complete()) {
                line.append(" (").append(player.minutesWindow().reason()).append(')');
            } else if (player.minutesWindow().expected() < ExpectedPoints.WINDOW_MATCHES) {
                line.append(" (short window: ").append(player.minutesWindow().expected())
                        .append(" fixtures)");
            }
        }
        if (player.isDoubtful()) {
            line.append(" — DOUBTFUL: ").append(player.news());
        }
        return line.toString();
    }

    /**
     * The one place a projection becomes words, used by XI and bench rows alike. A club that
     * plays nothing in the window reads as a zero over a named range; a club whose window could
     * not be established reads as n/a with the schedule's own reason. Collapsing the two into a
     * single "n/a" — or into a single "0.00" — is exactly the claim this reply must not make.
     */
    private static String expectedPoints(HorizonScorer.Projection projection,
                                         HorizonFixtures.Result horizon) {
        if (projection == null || (!projection.available() && projection.horizonReason() == null)) {
            return "xPts n/a (projection unavailable)";
        }
        if (!projection.available()) {
            return "xPts n/a (horizon unknown: "
                    + horizonReason(projection.horizonReason()) + ")";
        }
        if (projection.fixtures() == 0) {
            // An available projection over no fixtures can only come from a known window, so the
            // range is always there to name.
            return "0.00 xPts (no fixtures in " + gameweekRange(horizon) + ")";
        }
        StringBuilder points = new StringBuilder(String.format(Locale.ROOT,
                "%.2f xPts over %d fixture%s", projection.optimizerPoints(),
                projection.fixtures(), projection.fixtures() == 1 ? "" : "s"));
        if (projection.neutralFdrLegs() > 0) {
            points.append(" (FDR n/a for ").append(projection.neutralFdrLegs())
                    .append(" fixture").append(projection.neutralFdrLegs() == 1 ? "" : "s")
                    .append(", neutral ").append(HorizonScorer.NEUTRAL_FDR).append(" assumed)");
        }
        if (projection.partial()) {
            points.append(" (partial: one or more expected components unavailable)");
        }
        return points.toString();
    }

    /**
     * Blended expected minutes per club fixture, printed on bench rows only. Raw current-club
     * evidence is printed separately on every row and alone controls XI eligibility.
     */
    private static String benchMinutesNote(SquadCandidate player) {
        if (player.exposure() == null) {
            return ", expected mins/fixture n/a";
        }
        return String.format(Locale.ROOT, ", %.0f expected mins/fixture",
                player.expectedMinutes());
    }
}
