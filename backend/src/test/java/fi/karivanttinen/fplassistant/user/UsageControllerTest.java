package fi.karivanttinen.fplassistant.user;

import java.util.UUID;

import fi.karivanttinen.fplassistant.testsupport.LocalSigningKey;
import fi.karivanttinen.fplassistant.testsupport.TestJwks;
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

    @AfterEach
    void clear() {
        jdbc.update("DELETE FROM " + usageTable + " WHERE user_id = ?", UUID.fromString(USER));
        jdbc.update("DELETE FROM " + profileTable + " WHERE user_id = ?", UUID.fromString(USER));
    }

    @Test
    void itNeedsAToken() throws Exception {
        mockMvc.perform(get("/api/usage")).andExpect(status().isUnauthorized());
    }

    /**
     * A share, never a count. The absolute numbers are the owner's (issue #11): a response
     * carrying the limit, or how many are left, would tell the user exactly what this is meant
     * to keep to ourselves.
     */
    @Test
    void itReportsWhatIsLeftAsAPercentageAndNoAbsoluteNumbers() throws Exception {
        setLimit(10);
        seedChatEvents(3);

        mockMvc.perform(get("/api/usage")
                .header("Authorization", "Bearer " + TestJwks.validTokenFor(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingPercent").value(70))
                .andExpect(jsonPath("$.unlimited").value(false))
                .andExpect(jsonPath("$.nextFreeAt").isNotEmpty())
                .andExpect(jsonPath("$.used").doesNotExist())
                .andExpect(jsonPath("$.limit").doesNotExist());
    }

    /** An indicator that reads empty while a message can still be sent is worse than none. */
    @Test
    void aLastRemainingMessageNeverReadsAsZero() throws Exception {
        setLimit(200);
        seedChatEvents(199);

        mockMvc.perform(get("/api/usage")
                .header("Authorization", "Bearer " + TestJwks.validTokenFor(USER)))
                .andExpect(jsonPath("$.remainingPercent").value(1));
    }

    @Test
    void aSpentWeekReadsAsZero() throws Exception {
        setLimit(2);
        seedChatEvents(2);

        mockMvc.perform(get("/api/usage")
                .header("Authorization", "Bearer " + TestJwks.validTokenFor(USER)))
                .andExpect(jsonPath("$.remainingPercent").value(0));
    }

    @Test
    void anUnlimitedAccountSaysSoRatherThanReportingAShare() throws Exception {
        setLimit(-1);

        mockMvc.perform(get("/api/usage")
                .header("Authorization", "Bearer " + TestJwks.validTokenFor(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unlimited").value(true));
    }

    /** No number of seconds turns a suspended account back on, so it is not offered one. */
    @Test
    void aSuspendedAccountIsNotToldWhenToComeBack() throws Exception {
        setLimit(0);
        seedChatEvents(1);

        mockMvc.perform(get("/api/usage")
                .header("Authorization", "Bearer " + TestJwks.validTokenFor(USER)))
                .andExpect(jsonPath("$.remainingPercent").value(0))
                .andExpect(jsonPath("$.nextFreeAt").doesNotExist());
    }

    private void setLimit(int limit) {
        jdbc.update("INSERT INTO " + profileTable + " (user_id, weekly_message_limit) VALUES (?, ?)"
                + " ON CONFLICT (user_id) DO UPDATE SET weekly_message_limit = EXCLUDED.weekly_message_limit",
                UUID.fromString(USER), limit);
    }

    private void seedChatEvents(int count) {
        for (int i = 0; i < count; i++) {
            jdbc.update("INSERT INTO " + usageTable + " (user_id, event_type, created_at)"
                    + " VALUES (?, 'chat', now() - interval '2 days')", UUID.fromString(USER));
        }
    }
}
