package dev.quantti.fplassistant.user;

import java.util.Optional;

/**
 * The weekly message quota is spent. Deliberately not a {@code ResponseStatusException}: the
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
     * Names no number (issue #11). The user is told they are out for now, and the usage page
     * shows how much of the week is left as a share — the limit itself is the owner's business.
     */
    private static String message(UsageService.Quota quota) {
        if (quota.limit() != null && quota.limit() == 0) {
            return "This account cannot send messages.";
        }
        return "You have used this week's messages.";
    }
}
