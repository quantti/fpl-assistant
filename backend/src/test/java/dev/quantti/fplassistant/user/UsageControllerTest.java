package dev.quantti.fplassistant.user;

import java.util.UUID;

import dev.quantti.fplassistant.testsupport.LocalSigningKey;
import dev.quantti.fplassistant.testsupport.TestJwks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The indicator's endpoint: what the caller has spent, and against what. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(LocalSigningKey.class)
class UsageControllerTest {

    private static final String USER = "8f5a9263-1111-4111-8111-ffffffffffff";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Value("${fpl.user.usage-table-name}")
    String usageTable;

    @Value("${fpl.user.profile-table-name}")
    String profileTable;

    @Value("${fpl.quota.messages-per-week}")
    int defaultLimit;

    @AfterEach
    void clear() {
        jdbc.update("DELETE FROM " + usageTable + " WHERE user_id = ?", UUID.fromString(USER));
        jdbc.update("DELETE FROM " + profileTable + " WHERE user_id = ?", UUID.fromString(USER));
    }

    @Test
    void itNeedsAToken() throws Exception {
        mockMvc.perform(get("/api/usage")).andExpect(status().isUnauthorized());
    }

    @Test
    void itReportsWhatWasUsedAndTheLimitItCountsAgainst() throws Exception {
        jdbc.update("INSERT INTO " + usageTable + " (user_id, event_type, created_at)"
                + " VALUES (?, 'chat', now() - interval '2 days')", UUID.fromString(USER));

        mockMvc.perform(get("/api/usage")
                .header("Authorization", "Bearer " + TestJwks.validTokenFor(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.used").value(1))
                .andExpect(jsonPath("$.limit").value(defaultLimit))
                .andExpect(jsonPath("$.nextFreeAt").isNotEmpty());
    }

    /** An unlimited account has nothing to count and says so, rather than reporting a fake zero. */
    @Test
    void anUnlimitedAccountReportsNoLimit() throws Exception {
        jdbc.update("INSERT INTO " + profileTable + " (user_id, weekly_message_limit) VALUES (?, -1)",
                UUID.fromString(USER));

        mockMvc.perform(get("/api/usage")
                .header("Authorization", "Bearer " + TestJwks.validTokenFor(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").doesNotExist());
    }
}
