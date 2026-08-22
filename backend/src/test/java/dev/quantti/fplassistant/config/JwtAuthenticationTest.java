package dev.quantti.fplassistant.config;

import dev.quantti.fplassistant.testsupport.LocalSigningKey;
import dev.quantti.fplassistant.testsupport.TestJwks;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The authorization boundary: who reaches {@code /api/**} and who does not.
 *
 * <p>Verified against a local signing key rather than Supabase — we hold no Supabase private
 * key, so an expired or wrongly-signed token could not otherwise be produced at all. The decoder
 * below is built exactly as {@code SecurityConfig} builds the real one, including
 * {@link SecurityConfig#tokenValidator}, so what is under test is the application's own
 * configuration and not a paraphrase of it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(LocalSigningKey.class)
class JwtAuthenticationTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void aRequestWithNoTokenIsRejected() throws Exception {
        mockMvc.perform(protectedEndpoint(null)).andExpect(status().isUnauthorized());
    }

    @Test
    void aMalformedTokenIsRejected() throws Exception {
        mockMvc.perform(protectedEndpoint("not-a-jwt-at-all")).andExpect(status().isUnauthorized());
    }

    @Test
    void anExpiredTokenIsRejected() throws Exception {
        mockMvc.perform(protectedEndpoint(TestJwks.expiredToken())).andExpect(status().isUnauthorized());
    }

    @Test
    void aTokenFromAnotherIssuerIsRejected() throws Exception {
        mockMvc.perform(protectedEndpoint(TestJwks.wrongIssuerToken())).andExpect(status().isUnauthorized());
    }

    @Test
    void aTokenForAnotherAudienceIsRejected() throws Exception {
        mockMvc.perform(protectedEndpoint(TestJwks.wrongAudienceToken())).andExpect(status().isUnauthorized());
    }

    /** The one that matters most: a well-formed token nobody in possession of our JWKS signed. */
    @Test
    void aTokenSignedWithTheWrongKeyIsRejected() throws Exception {
        mockMvc.perform(protectedEndpoint(TestJwks.foreignKeyToken())).andExpect(status().isUnauthorized());
    }

    @Test
    void aValidTokenReachesTheController() throws Exception {
        mockMvc.perform(protectedEndpoint(TestJwks.validToken())).andExpect(status().isOk());
    }

    /**
     * Chat needs a token now that the frontend can send one. It was deliberately open between
     * 2026-08-19 and this change: requiring one while the deployed UI had no sign-in answered
     * 401 to every user, so the two only ship merged together.
     */
    @Test
    void chatRequiresAToken() throws Exception {
        mockMvc.perform(post("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"messages\":[]}"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * The one permitAll route under {@code /api/**}. There is no token at sign-up and there
     * cannot be one, so the chain must let the request reach the controller — which then refuses
     * it on the invite code rather than on the missing token.
     */
    @Test
    void signUpIsReachableWithoutAToken() throws Exception {
        mockMvc.perform(post("/api/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"a@example.com\",\"password\":\"x\",\"inviteCode\":\"no\"}"))
                .andExpect(status().isForbidden());
    }

    /**
     * Only that one verb on that one path. Opening a route is not opening a prefix — a GET on the
     * same path, or anything else under /api/auth, still needs a token.
     */
    @Test
    void nothingElseUnderApiAuthIsPublic() throws Exception {
        mockMvc.perform(get("/api/auth/signup")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/anything-else")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Fail closed for everything the client routes do not cover. A path with a dot in it is a
     * file request, so it never reaches the SPA forward — and nothing else outside
     * {@code /api/**} is permitted, which is what {@code SpaForwardingTest} pins by asserting no
     * server endpoint lives where a client route could shadow it.
     */
    @Test
    void anUnknownFilePathIsNotPublicByDefault() throws Exception {
        mockMvc.perform(get("/internal/whatever.json")).andExpect(status().isUnauthorized());
    }

    @Test
    void theHealthEndpointStaysPublic() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    /**
     * The frontend is served by this same application and has to load before anyone can sign in.
     * There is no built frontend on the test classpath, so 404 is the pass — 401 would mean the
     * sign-in screen itself needs a token.
     */
    @Test
    void theFrontendIsServedWithoutAToken() throws Exception {
        // 404, precisely: there is no built frontend on the test classpath, and asserting "not
        // 401" would also pass for the 403 a fail-closed chain returns — which would mean the
        // sign-in screen itself never loads.
        mockMvc.perform(get("/index.html")).andExpect(status().isNotFound());
    }

    /**
     * Verifying a token must not mean a round trip to the key server. The decoder caches the key
     * set after the first fetch; without that, every authenticated request would wait on
     * Supabase and every outage there would be an outage here.
     *
     * <p>An unknown {@code kid} is the one case that does re-fetch — that is Nimbus handling key
     * rotation, and it is rate-limited rather than per-request, so a stream of tokens signed by
     * an unknown key cannot be turned into a stream of requests to the key server.
     */
    @Test
    void theKeySetIsFetchedOnceAndCached() throws Exception {
        mockMvc.perform(protectedEndpoint(TestJwks.validToken())).andExpect(status().isOk());
        int afterFirstVerification = TestJwks.jwksRequestCount();
        assertThat(afterFirstVerification).isPositive();

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(protectedEndpoint(TestJwks.validToken())).andExpect(status().isOk());
        }
        assertThat(TestJwks.jwksRequestCount())
                .as("a cached key set means no further fetches")
                .isEqualTo(afterFirstVerification);

        // A token naming an unknown key id makes the decoder look for a rotated key. That is
        // attacker-controlled, so it must stay bounded — and must still answer 401 once the
        // rate limit refuses the refresh, rather than turning nonsense input into a 500.
        for (int i = 0; i < 6; i++) {
            mockMvc.perform(protectedEndpoint(TestJwks.foreignKeyToken())).andExpect(status().isUnauthorized());
        }
        assertThat(TestJwks.jwksRequestCount())
                .as("an unknown key id may refresh the key set, but not once per request")
                .isLessThanOrEqualTo(afterFirstVerification + 2);
    }

    /**
     * The profile endpoint, not chat: a GET here spends no rate-limit budget, and
     * ChatControllerTest documents that spending it from a growing suite is a time bomb.
     */
    private static org.springframework.test.web.servlet.RequestBuilder protectedEndpoint(String token) {
        var request = get("/api/profile");
        return token == null ? request : request.header("Authorization", "Bearer " + token);
    }
}
