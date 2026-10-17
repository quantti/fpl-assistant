package dev.quantti.fplassistant.sync;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.quantti.fplassistant.config.AdminAccess;
import dev.quantti.fplassistant.config.SecurityConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** Exercises the real authorization chain, not only standalone controller routing. No DB or JWT service. */
class SyncOperatorSecurityTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private SyncOperations operations;

    @BeforeEach
    void start() {
        startContext(true, "operator-fixture");
    }

    void startContext(boolean enabled, String token) {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                "supabase.jwks-uri=https://invalid.example/jwks", "supabase.issuer=https://invalid.example",
                "supabase.audience=authenticated", "test.ops-enabled=" + enabled,
                "test.operator-token=" + token);
        context.register(TestConfig.class);
        context.refresh();
        operations = context.getBean(SyncOperations.class);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    void close() {
        if (context != null) context.close();
    }

    @Test
    void handoffAndRetireUseOperatorTokenWithoutUserJwt() throws Exception {
        when(operations.handoffImporter("2026-27", 106)).thenReturn(107L);
        when(operations.retireImporter("2026-27", 107)).thenReturn(108L);
        mvc.perform(post("/ops/sync/importer/handoff")
                .header(SyncOperationsController.OPERATOR_HEADER, "operator-fixture")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"playingSeason\":\"2026-27\",\"expectedOffEpoch\":106}"))
                .andExpect(status().isOk());
        mvc.perform(post("/ops/sync/importer/retire")
                .header(SyncOperationsController.OPERATOR_HEADER, "operator-fixture")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"playingSeason\":\"2026-27\",\"expectedImporterEpoch\":107}"))
                .andExpect(status().isOk());
    }

    @Test
    void missingWrongAndMonitorTokensCannotReachOperations() throws Exception {
        for (String route : new String[] {"handoff", "retire"}) {
            String body = route.equals("handoff")
                    ? "{\"playingSeason\":\"2026-27\",\"expectedOffEpoch\":106}"
                    : "{\"playingSeason\":\"2026-27\",\"expectedImporterEpoch\":107}";
            mvc.perform(post("/ops/sync/importer/" + route).contentType(MediaType.APPLICATION_JSON)
                    .content(body)).andExpect(status().isUnauthorized());
            mvc.perform(post("/ops/sync/importer/" + route).with(jwt())
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
            mvc.perform(post("/ops/sync/importer/" + route)
                    .header(SyncOperationsController.OPERATOR_HEADER, "wrong")
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
            mvc.perform(post("/ops/sync/importer/" + route)
                    .header(SyncOperationsController.MONITOR_HEADER, "monitor-fixture")
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(operations);
    }

    @Test
    void disabledOperationsAndBlankTokenStayClosed() throws Exception {
        for (boolean enabled : new boolean[] {false, true}) {
            context.close();
            startContext(enabled, "");
            for (String route : new String[] {"handoff", "retire"}) {
                String body = route.equals("handoff")
                        ? "{\"playingSeason\":\"2026-27\",\"expectedOffEpoch\":106}"
                        : "{\"playingSeason\":\"2026-27\",\"expectedImporterEpoch\":107}";
                mvc.perform(post("/ops/sync/importer/" + route)
                        .header(SyncOperationsController.OPERATOR_HEADER, "operator-fixture")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(enabled ? status().isUnauthorized() : status().isNotFound());
            }
            verifyNoInteractions(operations);
        }
    }

    @Test
    void staleHandoffStillReturnsConflict() throws Exception {
        when(operations.handoffImporter("2026-27", 106))
                .thenThrow(new SyncOperations.StaleOperatorRequestException("stale"));
        mvc.perform(post("/ops/sync/importer/handoff")
                .header(SyncOperationsController.OPERATOR_HEADER, "operator-fixture")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"playingSeason\":\"2026-27\",\"expectedOffEpoch\":106}"))
                .andExpect(status().isConflict());
    }

    @Test
    void unknownOperatorRouteStaysDenied() throws Exception {
        mvc.perform(post("/ops/sync/importer/unknown")
                .header(SyncOperationsController.OPERATOR_HEADER, "operator-fixture"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(operations);
    }

    @Configuration
    @EnableWebMvc
    @Import(SecurityConfig.class)
    static class TestConfig {
        @Bean SyncOperations operations() { return mock(SyncOperations.class); }
        @Bean AdminAccess adminAccess() { return new AdminAccess(""); }
        @Bean SyncOperationsController controller(SyncOperations operations,
                                                   org.springframework.core.env.Environment environment) {
            return new SyncOperationsController(operations,
                    environment.getProperty("test.ops-enabled", Boolean.class, false),
                    environment.getProperty("test.operator-token", ""), true, "monitor-fixture");
        }
    }
}
