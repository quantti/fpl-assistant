package fi.karivanttinen.fplassistant.user;

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

    private static String message(UsageService.Quota quota) {
        if (quota.limit() != null && quota.limit() == 0) {
            return "This account cannot send messages.";
        }
        return "You have used all %d messages for this week.".formatted(quota.limit());
    }
}
