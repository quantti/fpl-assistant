package fi.karivanttinen.fplassistant.user;

import java.util.Optional;

/**
 * The window's message quota is spent. Deliberately not a {@code ResponseStatusException}: the
 * answer carries {@code Retry-After}, and the seconds come from the quota that raised it.
 */
public class QuotaExceededException extends RuntimeException {

    private final UsageService.Quota quota;

    public QuotaExceededException(UsageService.Quota quota) {
        super(message(quota));
        this.quota = quota;
    }

    public Optional<Long> retryAfterSeconds() {
        return quota.retryAfterSeconds();
    }

    /**
     * Names the number on the free plan and not on the paid one — the same split as
     * {@link UsageController}, decided on the same {@link Plan} so the two cannot disagree. The
     * free limit is the offer; the paid limit is a cost-control number (issue #11).
     *
     * <p>A suspended account is told neither: no limit is the reason it cannot send, and quoting
     * one would send the user to wait for a window that will not open.
     *
     * <p>The free plan's wording also names the window, because "this week's messages" is untrue
     * of a round that lasts three days and of an off-season that lasts three months. It names the
     * deadline's round rather than "this gameweek": the matches being played while the window is
     * open belong to the previous round.
     */
    private static String message(UsageService.Quota quota) {
        if (quota.limit() != null && quota.limit() == 0) {
            return "This account cannot send messages.";
        }
        if (quota.plan() != Plan.FREE || quota.limit() == null) {
            return "You have used this period's messages.";
        }
        return switch (quota.window().kind()) {
            case GAMEWEEK -> "You have used all " + quota.limit() + " of your messages for gameweek "
                    + quota.window().gameweek() + ".";
            case OFF_SEASON -> "You have used all " + quota.limit()
                    + " of your off-season messages.";
            case FALLBACK -> "You have used all " + quota.limit() + " of this week's messages.";
        };
    }
}
