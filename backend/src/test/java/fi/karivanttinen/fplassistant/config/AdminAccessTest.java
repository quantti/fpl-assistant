package fi.karivanttinen.fplassistant.config;

import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Who is an admin: the configured set, and nobody else. The empty set is the case that matters
 * most — an unset {@code ADMIN_USER_IDS} must close the admin API, not open it.
 */
class AdminAccessTest {

    private static final String ADMIN = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    private static final String OTHER = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";

    @Test
    void anEmptySettingMakesNobodyAnAdmin() {
        AdminAccess access = new AdminAccess("");
        assertThat(access.isAdmin(UUID.fromString(ADMIN))).isFalse();
        assertThat(granted(access, jwtFor(ADMIN))).isFalse();
    }

    @Test
    void theListIsCommaSeparatedAndTolerantOfSpaces() {
        AdminAccess access = new AdminAccess(" " + ADMIN + " , " + OTHER + ", ");
        assertThat(access.isAdmin(UUID.fromString(ADMIN))).isTrue();
        assertThat(access.isAdmin(UUID.fromString(OTHER))).isTrue();
        assertThat(access.isAdmin(UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"))).isFalse();
    }

    @Test
    void aListedSubjectIsGrantedAndAnyOtherIsNot() {
        AdminAccess access = new AdminAccess(ADMIN);
        assertThat(granted(access, jwtFor(ADMIN))).isTrue();
        assertThat(granted(access, jwtFor(OTHER))).isFalse();
    }

    @Test
    void anAnonymousCallerIsNotAnAdmin() {
        AdminAccess access = new AdminAccess(ADMIN);
        Authentication anonymous = new AnonymousAuthenticationToken("key", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
        assertThat(granted(access, anonymous)).isFalse();
    }

    /** A verified token whose subject is not a UUID is refused, never a 500. */
    @Test
    void aSubjectThatIsNotAUuidIsRefused() {
        AdminAccess access = new AdminAccess(ADMIN);
        assertThat(granted(access, jwtFor("not-a-uuid"))).isFalse();
    }

    @Test
    void aMalformedIdInTheSettingFailsAtStartupNotAtRequestTime() {
        assertThatThrownBy(() -> new AdminAccess("not-a-uuid"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNullUserIdIsNotAnAdmin() {
        assertThat(new AdminAccess(ADMIN).isAdmin(null)).isFalse();
    }

    private static boolean granted(AdminAccess access, Authentication authentication) {
        Supplier<Authentication> supplier = () -> authentication;
        AuthorizationResult result = access.authorize(supplier,
                new RequestAuthorizationContext(new MockHttpServletRequest()));
        return result != null && result.isGranted();
    }

    private static Authentication jwtFor(String subject) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "ES256").subject(subject).build();
        return new JwtAuthenticationToken(jwt);
    }
}
