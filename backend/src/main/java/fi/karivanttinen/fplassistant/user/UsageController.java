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
 * What the caller has spent in the window they are in. Exists for the frontend's usage indicator:
 * a limit the user meets only as a 429 is a limit they had no way to see coming.
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
     * @param nextFreeAt       when the caller may send again, or null while messages remain or
     *                         when waiting cannot help. It names no count, and without it someone
     *                         out of messages has no idea when they can continue
     * @param used             messages spent in this window, on the free plan only
     * @param limit            messages the window allows, on the free plan only. An unlimited
     *                         account carries neither: there is no count worth displaying
     * @param window           which period the numbers describe. The page needs it to choose its
     *                         wording: a rolling fallback has no deadline to name, and naming one
     *                         anyway would be the page inventing a fact
     * @param resetsAt         when the count returns to zero, absent on a fallback — where
     *                         nothing resets — and on an off-season window whose end is not yet
     *                         captured. Sent whether or not the caller is blocked: a reset you
     *                         can see coming is the point of counting in windows at all
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UsageResponse(Plan plan, int remainingPercent, boolean unlimited,
                                Instant nextFreeAt, Integer used, Integer limit,
                                WindowResponse window, Instant resetsAt) {}

    /** The window without its bounds: the page names a round and a reset, never a range. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WindowResponse(QuotaWindow.Kind kind, Integer gameweek, String season) {}

    @GetMapping
    public UsageResponse usage(@AuthenticationPrincipal Jwt jwt) {
        UsageService.Quota quota = usage.quotaOf(TokenSubject.userId(jwt));
        // A suspended account (limit 0) is told neither count nor limit — the same silence the
        // 429 keeps: no number is the reason it cannot send, and "3 of 0 messages used" would
        // be a sentence about nothing. Unlimited falls out the same way: limit is null.
        boolean statesTheNumbers = quota.plan() == Plan.FREE && quota.limit() != null && quota.limit() > 0;
        QuotaWindow window = quota.window();
        return new UsageResponse(quota.plan(), remainingPercent(quota), quota.limit() == null,
                quota.retryAfterSeconds().isEmpty() ? null : quota.nextFreeAt(),
                statesTheNumbers ? quota.used() : null,
                statesTheNumbers ? quota.limit() : null,
                new WindowResponse(window.kind(), window.gameweek(), window.season()),
                quota.resetsAt());
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
