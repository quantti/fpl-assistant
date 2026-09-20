package fi.karivanttinen.fplassistant.tools;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Stable wording for the comparison-coverage contract shared by squad and pick tools. */
public final class ComparisonCoverageFormatter {

    private ComparisonCoverageFormatter() {
    }

    public static String refusal(ComparisonCoverage coverage, Instant asOf) {
        return "COMPARISON_DATA_INCOMPLETE: I cannot fairly compare the whole league as of "
                + asOf + ". " + affected(coverage)
                + " This is a data exclusion, not evidence that those clubs are weak or that"
                + " their players carry rotation risk. No budget, pin, formation, quota or"
                + " exclusion can bypass it; a complete 15-player recommendation requires a new"
                + " successful squad build after the evidence is available.";
    }

    public static String restricted(ComparisonCoverage coverage, Instant asOf) {
        if (!coverage.assessed()) {
            return "Comparison coverage: not assessed because the current ranking sources were"
                    + " unavailable or season-incompatible. " + playerCounts(coverage);
        }
        if (coverage.complete()) {
            return success(coverage);
        }
        return "RESTRICTED COMPARISON POPULATION as of " + asOf + ": "
                + affected(coverage)
                + " The eligible-only rows below are not a league-wide best list and must not be"
                + " used to hand-build a fallback squad. " + playerCounts(coverage);
    }

    public static String success(ComparisonCoverage coverage) {
        if (!coverage.assessed()) {
            return "Comparison coverage: not assessed because the current ranking sources were"
                    + " unavailable or season-incompatible. " + playerCounts(coverage);
        }
        return "Comparison coverage: all clubs with relevant current-role fixtures have played-side"
                + " core evidence. " + playerCounts(coverage);
    }

    private static String affected(ComparisonCoverage coverage) {
        List<String> clubs = new ArrayList<>();
        for (ComparisonCoverage.AffectedClub club : coverage.affectedClubs()) {
            List<String> fixtures = club.fixtures().stream().map(gap -> "fixture "
                    + gap.fixtureId() + (gap.gameweek() == null ? " (GW unassigned)" : " (GW"
                    + gap.gameweek() + ")") + ": " + reason(gap.reason())).toList();
            clubs.add(club.teamName() + " (" + club.candidateCount()
                    + " captured players) [" + String.join("; ", fixtures) + "]");
        }
        return "Affected clubs: " + String.join(", ", clubs) + ".";
    }

    private static String playerCounts(ComparisonCoverage coverage) {
        Map<MinutesModel.Eligibility, Integer> counts = coverage.groupedPlayerExclusions();
        Map<RankingMetrics.WindowReason, Integer> windows =
                new java.util.EnumMap<>(RankingMetrics.WindowReason.class);
        coverage.playerExclusions().stream().filter(row -> row.windowReason() != null)
                .forEach(row -> windows.merge(row.windowReason(), 1, Integer::sum));
        String windowCounts = windows.isEmpty() ? "none" : windows.entrySet().stream()
                .map(entry -> entry.getKey() + " " + entry.getValue())
                .collect(java.util.stream.Collectors.joining(", "));
        return "Player comparison receipt: " + coverage.eligiblePlayers() + " eligible of "
                + coverage.capturePlayers() + "; grouped exclusions — availability risk "
                + counts.getOrDefault(MinutesModel.Eligibility.AVAILABILITY_RISK, 0)
                + ", unknown current evidence "
                + counts.getOrDefault(MinutesModel.Eligibility.UNKNOWN_CURRENT_EVIDENCE, 0)
                + "; window reasons — " + windowCounts + ".";
    }

    private static String reason(ComparisonCoverage.GapReason reason) {
        return reason.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
