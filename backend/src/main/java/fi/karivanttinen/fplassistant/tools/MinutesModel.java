package fi.karivanttinen.fplassistant.tools;

/** The single observed-history admission rule shared by primary picks and squad XIs. */
public final class MinutesModel {

    public enum Eligibility {
        ELIGIBLE("consistent observed 60+ history"),
        AVAILABILITY_RISK("the fresh FPL capture does not mark the player available"),
        MINUTES_RISK("at least one fixture in the observed current-club window was under 60 minutes"),
        INSUFFICIENT_CURRENT_EVIDENCE("the complete current-club window has under 180 minutes"),
        UNKNOWN_CURRENT_EVIDENCE("current-season minutes evidence is missing, stale or incoherent");

        private final String reason;

        Eligibility(String reason) {
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }

    private MinutesModel() {
    }

    public static Eligibility eligibility(RankingMetrics.CapturePlayer player,
                                          RankingMetrics.MinutesWindow window,
                                          boolean currentSeasonEvidence) {
        if (!"a".equals(player.status())) {
            return Eligibility.AVAILABILITY_RISK;
        }
        if (!currentSeasonEvidence || !player.hasValidIdentity()
                || window == null || !window.complete()) {
            return Eligibility.UNKNOWN_CURRENT_EVIDENCE;
        }
        if (window.sixtyPlus() < window.expected()) {
            return Eligibility.MINUTES_RISK;
        }
        if (window.totalMinutes() < ExpectedPoints.MIN_WINDOW_MINUTES) {
            return Eligibility.INSUFFICIENT_CURRENT_EVIDENCE;
        }
        return Eligibility.ELIGIBLE;
    }
}
