package fi.karivanttinen.fplassistant.config;

import java.util.List;

import fi.karivanttinen.fplassistant.testsupport.LocalSigningKey;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Client-side routes reach {@code index.html}; the API and the actuator do not.
 *
 * <p>MockMvc does not execute the forward, which is what makes {@code forwardedUrl} the honest
 * assertion here: the status of a real forward depends on whether a built frontend is on the
 * classpath, and there is none in the test run.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(LocalSigningKey.class)
class SpaForwardingTest {

    @Autowired
    MockMvc mockMvc;

    // Qualified by name: the actuator contributes handler mappings of its own, so the type alone
    // is ambiguous.
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @Test
    void aDeepLinkLoadsTheApplication() throws Exception {
        mockMvc.perform(get("/conversations/8f2c"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void theMyTeamDeepLinkLoadsTheApplication() throws Exception {
        mockMvc.perform(get("/my-team"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void theMyTeamApiIsNeverForwardedToTheApplication() throws Exception {
        mockMvc.perform(get("/api/my-team").header("Fly-Client-IP", "192.0.2.201"))
                .andExpect(status().isUnauthorized())
                .andExpect(forwardedUrl(null));
    }

    /**
     * A missing asset must 404 rather than answer with the index page under a JavaScript content
     * type — the browser would then report a syntax error instead of a missing file.
     */
    @Test
    void aFileRequestIsNotForwarded() throws Exception {
        mockMvc.perform(get("/assets/index-deadbeef.js"))
                .andExpect(status().isNotFound())
                .andExpect(forwardedUrl(null));
    }

    /**
     * An unknown API path must answer as an API: a client parsing JSON cannot do anything with a
     * 200 page of HTML except report the wrong error.
     */
    @Test
    void anUnknownApiPathIsNotForwarded() throws Exception {
        mockMvc.perform(get("/api/nothing-here").header("Authorization", "Bearer nonsense"))
                .andExpect(forwardedUrl(null));
    }

    @Test
    void theActuatorIsNotForwarded() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl(null));
    }

    /**
     * The catch-all permit that makes deep links work is only safe while the server owns no
     * endpoint a client route could shadow. {@code SecurityConfig} permits every extension-less
     * GET outside {@code /api/**} and {@code /actuator/**}, so a controller mapped anywhere else
     * would be world-readable — the exact silent hole the fail-closed default was introduced to
     * remove. This is the failing test that stops it happening quietly: put new endpoints under
     * {@code /api/}.
     */
    @Test
    void everyServerEndpointLivesWhereTheCatchAllCannotExposeIt() {
        // Annotated controllers only. A RouterFunction bean, a registered servlet, or a
        // dotless file in static/ is invisible here and would be public under the same permit —
        // if Phase 5b registers its Stripe webhook functionally, this assertion does not see it.
        List<String> exposed = handlerMapping.getHandlerMethods().entrySet().stream()
                .filter(entry -> !isSpaForward(entry.getValue()))
                .flatMap(entry -> entry.getKey().getPatternValues().stream())
                .filter(pattern -> !isUnder("/api", pattern)
                        && !isUnder("/actuator", pattern)
                        // Spring's own error dispatch target. It reveals the error attributes of
                        // the request that failed and nothing else.
                        && !pattern.equals("/error"))
                .toList();

        assertThat(exposed)
                .as("endpoints outside /api/** are public: the client-route permit cannot tell"
                        + " them apart from a route the browser owns")
                .isEmpty();
    }

    private static boolean isSpaForward(HandlerMethod handler) {
        return handler.getBeanType().equals(SpaForwardingController.class);
    }

    /**
     * Prefix matching by segment, matching what the security patterns exclude. A plain
     * {@code startsWith("/actuator")} would also cover {@code /actuator-admin}, which the
     * client-route permit makes public — so the loosest filter here would be blind to exactly
     * the endpoint this test exists to catch.
     */
    private static boolean isUnder(String prefix, String pattern) {
        return pattern.equals(prefix) || pattern.startsWith(prefix + "/");
    }
}
