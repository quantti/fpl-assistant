package dev.quantti.fplassistant.user;

import java.time.Instant;

import dev.quantti.fplassistant.config.TokenSubject;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the caller has spent this week. Exists for the frontend's usage indicator: a limit the
 * user meets only as a 429 is a limit they had no way to see coming.
 */
@RestController
@RequestMapping("/api/usage")
public class UsageController {

    private final UsageService usage;

    public UsageController(UsageService usage) {
        this.usage = usage;
    }

    /**
     * What is left, as a share rather than a count.
     *
     * <p>The absolute numbers stay on this side of the wire (issue #11): the user is told how
     * much of their week remains, not how many messages they get. A percentage that leaked the
     * limit would be the same information in another form, which is why nothing here carries the
     * count used, the limit, or how many messages are left.
     *
     * @param remainingPercent 0–100, and never 0 while a message can still be sent — an
     *                         indicator that reads empty before the door closes is worse than no
     *                         indicator
     * @param unlimited        true when the account has no limit; {@code remainingPercent} is
     *                         then meaningless and set to 100
     * @param nextFreeAt       when the window next frees a message, or null when nothing is
     *                         counted or waiting cannot help. It names no count, and without it
     *                         someone out of messages has no idea when they can continue
     */
    public record UsageResponse(int remainingPercent, boolean unlimited, Instant nextFreeAt) {}

    @GetMapping
    public UsageResponse usage(@AuthenticationPrincipal Jwt jwt) {
        UsageService.Quota quota = usage.quotaOf(TokenSubject.userId(jwt));
        return new UsageResponse(remainingPercent(quota), quota.limit() == null,
                quota.retryAfterSeconds().isEmpty() ? null : quota.nextFreeAt());
    }

    private static int remainingPercent(UsageService.Quota quota) {
        Integer limit = quota.limit();
        if (limit == null) {
            return 100;
        }
        if (limit == 0 || quota.exceeded()) {
            return 0;
        }
        int left = limit - quota.used();
        // Rounded down, because a bar that says 1% with nothing left is a lie in the direction
        // that matters — but never to zero while a message remains, for the same reason.
        return Math.max(1, (int) Math.floor(left * 100.0 / limit));
    }
}
