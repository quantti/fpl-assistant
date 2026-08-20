package fi.karivanttinen.fplassistant.user;

import java.time.Instant;

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
     * @param limit      null when the account is unlimited, in which case {@code used} is not
     *                   counted either — there is nothing to count it against
     * @param nextFreeAt when the oldest counted message leaves the window, or null when nothing
     *                   is counted
     */
    public record UsageResponse(int used, Integer limit, Instant nextFreeAt) {}

    @GetMapping
    public UsageResponse usage(@AuthenticationPrincipal Jwt jwt) {
        UsageService.Quota quota = usage.quotaOf(TokenSubject.userId(jwt));
        return new UsageResponse(quota.used(), quota.limit(), quota.nextFreeAt());
    }
}
