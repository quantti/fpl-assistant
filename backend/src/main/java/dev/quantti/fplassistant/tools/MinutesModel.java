package dev.quantti.fplassistant.tools;

/** Shared availability and usable-player-evidence rule for picks and squad XIs. */
public final class MinutesModel {

    public enum Eligibility {
        ELIGIBLE("fresh availability and usable current player evidence"),
        AVAILABILITY_RISK("the fresh FPL capture does not mark the player available"),
        UNKNOWN_CURRENT_EVIDENCE("current-season evidence is missing, stale or incoherent");

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
        return Eligibility.ELIGIBLE;
    }
}
