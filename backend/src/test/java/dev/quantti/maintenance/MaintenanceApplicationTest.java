package dev.quantti.maintenance;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = MaintenanceApplication.class,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/maintenance")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MaintenanceApplicationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ApplicationContext context;

    @Test
    void doesNotCreateADatabaseConnectionPool() {
        assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
    }

    @Test
    void servesTheFinnishMaintenancePageInsteadOfLogin() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "FPL Sidekick on huollossa")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "26.9.2026 klo 00.00 Suomen aikaa")));
    }

    @Test
    void servesTheMaintenancePageAtTheRoot() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "FPL Sidekick on huollossa")));
    }

    @Test
    void doesNotExposeTheNormalFrontendThroughIndexHtml() throws Exception {
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "FPL Sidekick on huollossa")));
    }

    @Test
    void refusesApiUseWithoutTryingToAuthenticate() throws Exception {
        mockMvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM).content("{}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void keepsTheFlyHealthCheckAvailable() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
