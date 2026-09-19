package fi.karivanttinen.fplassistant.trace;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fi.karivanttinen.fplassistant.testsupport.LocalSigningKey;
import fi.karivanttinen.fplassistant.testsupport.TestJwks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({LocalSigningKey.class, ChatTraceIntegrationTest.FixedClock.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ChatTraceIntegrationTest {

    private static final String ADMIN = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    private static final String NOT_ADMIN = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
    private static final Instant NOW = Instant.parse("2030-01-20T12:00:00Z");

    @Autowired ChatTraceStore traces;
    @Autowired ChatTraceRetention retention;
    @Autowired ChatTraceSchema schema;
    private final ObjectMapper json = new ObjectMapper();
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mockMvc;
    @Value("${fpl.trace.table-name}") String table;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM " + table);
    }

    @Test
    void schemaIsIsolatedIdempotentAndDenyAllRls() {
        assertThat(table).isEqualTo("fpl_chat_trace_test");
        schema.ensureSchema();
        schema.ensureSchema();
        assertThat(jdbc.queryForObject("SELECT relrowsecurity FROM pg_class WHERE oid = ?::regclass",
                Boolean.class, table)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_policies WHERE schemaname = 'public'"
                + " AND tablename = ?", Integer.class, table)).isZero();
    }

    @Test
    void startAndCompleteKeepOneFlexibleJsonSnapshot() throws Exception {
        UUID user = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();
        UUID id = traces.start(user, conversation, json.readTree("{\"request\":\"started\"}"));

        JsonNode completed = json.readTree("""
                {"effectivePrompts":["system","history"],
                 "modelRounds":[{"model":"example","usage":{"input":12}}],
                 "toolCalls":[{"name":"getPlayerStats","result":"partial"}],
                 "finalAnswer":"A partial answer","errors":[{"message":"stream closed"}]}
                """);
        assertThat(traces.complete(id, completed)).isTrue();

        ChatTraceStore.Trace stored = traces.find(id).orElseThrow();
        assertThat(stored.userId()).isEqualTo(user);
        assertThat(stored.conversationId()).isEqualTo(conversation);
        assertThat(stored.completedAt()).isNotNull();
        assertThat(stored.payload()).isEqualTo(completed);
        assertThat(traces.complete(UUID.randomUUID(), completed)).isFalse();
    }

    @Test
    void retentionDeletesOnlyRowsOlderThanFourteenDays() throws Exception {
        UUID old = traces.start(UUID.randomUUID(), null, json.readTree("{}"));
        UUID recent = traces.start(UUID.randomUUID(), null, json.readTree("{}"));
        jdbc.update("UPDATE " + table + " SET created_at = ? WHERE id = ?",
                Timestamp.from(NOW.minus(ChatTraceRetention.RETENTION).minusSeconds(1)), old);
        jdbc.update("UPDATE " + table + " SET created_at = ? WHERE id = ?",
                Timestamp.from(NOW.minus(ChatTraceRetention.RETENTION)), recent);

        assertThat(retention.purge()).isEqualTo(1);
        assertThat(traces.find(old)).isEmpty();
        assertThat(traces.find(recent)).isPresent();
    }

    @Test
    void adminListAndDetailAreProtectedBoundedAndNoStore() throws Exception {
        UUID id = traces.start(UUID.randomUUID(), null, json.readTree("{\"secret\":true}"));

        mockMvc.perform(get("/api/admin/chat-traces")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/chat-traces").header("Authorization", bearer(NOT_ADMIN)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/chat-traces?limit=101")
                        .header("Authorization", bearer(ADMIN)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/admin/chat-traces?limit=1")
                        .header("Authorization", bearer(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].payload").doesNotExist());

        mockMvc.perform(get("/api/admin/chat-traces/" + id)
                        .header("Authorization", bearer(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.payload.secret").value(true));
        mockMvc.perform(get("/api/admin/chat-traces/" + UUID.randomUUID())
                        .header("Authorization", bearer(ADMIN)))
                .andExpect(status().isNotFound());
    }

    private static String bearer(String subject) {
        return "Bearer " + TestJwks.validTokenFor(subject);
    }

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock traceClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
