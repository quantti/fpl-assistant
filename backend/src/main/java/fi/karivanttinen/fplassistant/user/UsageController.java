package fi.karivanttinen.fplassistant.user;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;
import fi.karivanttinen.fplassistant.config.TokenSubject;
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
     * What is left: always as a share, and on the free plan as a count as well.
     *
     * <p>Issue #11 settled that the absolute numbers stay on this side of the wire. That rule now
     * holds for the paid plan only. A free tier's limit is public information — it is the offer,
     * and it is on the marketing page either way — so hiding it only makes the product look
     * evasive about the one number a prospective user needs. The paid limit stays the owner's
     * business: it is a cost-control number to be tuned from usage data, not an entitlement.
     *
     * <p>Absent rather than null when they are not to be shown, which is what the
     * {@code NON_NULL} include buys: a field carrying {@code null} is still an admission of which
     * fields exist, and the frontend then has to distinguish "not shown" from "no value".
     *
     * @param plan             the tier the limit was resolved against
     * @param remainingPercent 0–100, and never 0 while a message can still be sent — an
     *                         indicator that reads empty before the door closes is worse than no
     *                         indicator
     * @param unlimited        true when the account has no limit; {@code remainingPercent} is
     *                         then meaningless and set to 100
     * @param nextFreeAt       when the window next frees a message, or null when nothing is
     *                         counted or waiting cannot help. It names no count, and without it
     *                         someone out of messages has no idea when they can continue
     * @param used             messages spent this week, on the free plan only
     * @param limit            messages the week allows, on the free plan only. An unlimited
     *                         account carries neither: there is no count worth displaying
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UsageResponse(Plan plan, int remainingPercent, boolean unlimited,
                                Instant nextFreeAt, Integer used, Integer limit) {}

    @GetMapping
    public UsageResponse usage(@AuthenticationPrincipal Jwt jwt) {
        UsageService.Quota quota = usage.quotaOf(TokenSubject.userId(jwt));
        boolean statesTheNumbers = quota.plan() == Plan.FREE && quota.limit() != null;
        return new UsageResponse(quota.plan(), remainingPercent(quota), quota.limit() == null,
                quota.retryAfterSeconds().isEmpty() ? null : quota.nextFreeAt(),
                statesTheNumbers ? quota.used() : null,
                statesTheNumbers ? quota.limit() : null);
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
