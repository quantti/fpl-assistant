package fi.karivanttinen.fplassistant.tools;

/** Shared current-evidence and recurring-low-minutes rule for picks and squad XIs. */
public final class MinutesModel {

    /** Latest complete observed fixtures that must all be below 60 before excluding a player. */
    public static final int CONSISTENTLY_UNDER_60_FIXTURES = 3;

    public enum Eligibility {
        ELIGIBLE("no recent consistent under-60-minute pattern"),
        AVAILABILITY_RISK("the fresh FPL capture does not mark the player available"),
        CONSISTENTLY_UNDER_60("the three latest complete observed fixtures were all under 60 minutes"),
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
        if (consistentlyUnderSixty(window)) {
            return Eligibility.CONSISTENTLY_UNDER_60;
        }
        return Eligibility.ELIGIBLE;
    }

    private static boolean consistentlyUnderSixty(RankingMetrics.MinutesWindow window) {
        return window.latestThree().size() == CONSISTENTLY_UNDER_60_FIXTURES
                && window.latestThree().stream()
                        .allMatch(outcome -> outcome.minutes() != null && outcome.minutes() < 60);
    }
}
