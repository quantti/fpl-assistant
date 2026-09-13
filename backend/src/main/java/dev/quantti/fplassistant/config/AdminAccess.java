package dev.quantti.fplassistant.config;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

/**
 * Who may call {@code /api/admin/**}: the accounts named in {@code fpl.admin.user-ids}
 * ({@code ADMIN_USER_IDS}), a comma-separated list of JWT {@code sub} ids, and nobody else.
 *
 * <p>A configured set rather than a role claim or a profile column. One person is the whole
 * population, every other operational knob here is an environment variable, and neither the
 * database nor a Supabase setting can then grant admin — only a deploy-time secret can. The set
 * exists so it is not a single hard-coded id; if admins ever multiply, a claim on the token is
 * the cleaner mechanism and this class is the one place to swap it in.
 *
 * <p><b>Empty means nobody.</b> An unset variable closes the admin API rather than opening it,
 * the same posture as the missing service-role key closing sign-up. A malformed id fails the
 * context at startup: a typo that silently became "nobody" would look exactly like the secret
 * not being set.
 *
 * <p>Applied as the {@link AuthorizationManager} on the path in {@code SecurityConfig}, not per
 * controller, so a controller under {@code /api/admin} cannot forget it. An anonymous caller is
 * refused here and Spring turns that into the usual 401; a signed-in non-admin gets 403.
 */
@Component
public class AdminAccess implements AuthorizationManager<RequestAuthorizationContext> {

    private final Set<UUID> adminIds;

    public AdminAccess(@Value("${fpl.admin.user-ids:}") String configured) {
        this.adminIds = Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .map(UUID::fromString)
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isAdmin(UUID userId) {
        return adminIds.contains(userId);
    }

    @Override
    public AuthorizationResult authorize(Supplier<? extends Authentication> authentication,
                                         RequestAuthorizationContext context) {
        Authentication auth = authentication.get();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            return new AuthorizationDecision(false);
        }
        try {
            return new AuthorizationDecision(isAdmin(UUID.fromString(jwt.getSubject())));
        } catch (IllegalArgumentException | NullPointerException e) {
            return new AuthorizationDecision(false);
        }
    }
}
