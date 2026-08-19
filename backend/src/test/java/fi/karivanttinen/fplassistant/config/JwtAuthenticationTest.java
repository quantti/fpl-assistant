package fi.karivanttinen.fplassistant.config;

import fi.karivanttinen.fplassistant.testsupport.TestJwks;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
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
class JwtAuthenticationTest {

    @TestConfiguration
    static class LocalSigningKey {

        @Bean
        @Primary
        JwtDecoder testJwtDecoder() {
            // The application's own factory, pointed at the local key server: cache, rate
            // limit, algorithm and validators are the real ones.
            return SecurityConfig.decoder(TestJwks.jwksUri(), TestJwks.ISSUER, TestJwks.AUDIENCE);
        }
    }

    @Autowired
    MockMvc mockMvc;

    @Test
    void aRequestWithNoTokenIsRejected() throws Exception {
        mockMvc.perform(chat(null)).andExpect(status().isUnauthorized());
    }

    @Test
    void aMalformedTokenIsRejected() throws Exception {
        mockMvc.perform(chat("not-a-jwt-at-all")).andExpect(status().isUnauthorized());
    }

    @Test
    void anExpiredTokenIsRejected() throws Exception {
        mockMvc.perform(chat(TestJwks.expiredToken())).andExpect(status().isUnauthorized());
    }

    @Test
    void aTokenFromAnotherIssuerIsRejected() throws Exception {
        mockMvc.perform(chat(TestJwks.wrongIssuerToken())).andExpect(status().isUnauthorized());
    }

    @Test
    void aTokenForAnotherAudienceIsRejected() throws Exception {
        mockMvc.perform(chat(TestJwks.wrongAudienceToken())).andExpect(status().isUnauthorized());
    }

    /** The one that matters most: a well-formed token nobody in possession of our JWKS signed. */
    @Test
    void aTokenSignedWithTheWrongKeyIsRejected() throws Exception {
        mockMvc.perform(chat(TestJwks.foreignKeyToken())).andExpect(status().isUnauthorized());
    }

    /**
     * A valid token reaches the controller, which then rejects the empty message list on its own
     * terms. Asserting 400 rather than 200 keeps the LLM out of this test while still proving
     * the request passed authentication.
     */
    @Test
    void aValidTokenReachesTheController() throws Exception {
        mockMvc.perform(chat(TestJwks.validToken())).andExpect(status().isBadRequest());
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
        mockMvc.perform(get("/index.html"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(401));
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
        mockMvc.perform(chat(TestJwks.validToken())).andExpect(status().isBadRequest());
        int afterFirstVerification = TestJwks.jwksRequestCount();
        assertThat(afterFirstVerification).isPositive();

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(chat(TestJwks.validToken())).andExpect(status().isBadRequest());
        }
        assertThat(TestJwks.jwksRequestCount())
                .as("a cached key set means no further fetches")
                .isEqualTo(afterFirstVerification);

        // A token naming an unknown key id makes the decoder look for a rotated key. That is
        // attacker-controlled, so it must stay bounded — and must still answer 401 once the
        // rate limit refuses the refresh, rather than turning nonsense input into a 500.
        for (int i = 0; i < 6; i++) {
            mockMvc.perform(chat(TestJwks.foreignKeyToken())).andExpect(status().isUnauthorized());
        }
        assertThat(TestJwks.jwksRequestCount())
                .as("an unknown key id may refresh the key set, but not once per request")
                .isLessThanOrEqualTo(afterFirstVerification + 2);
    }

    private static org.springframework.test.web.servlet.RequestBuilder chat(String token) {
        var request = post("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"messages\":[]}");
        return token == null ? request : request.header("Authorization", "Bearer " + token);
    }
}
