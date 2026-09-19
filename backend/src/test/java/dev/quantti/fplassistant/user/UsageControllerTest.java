package dev.quantti.fplassistant.user;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import dev.quantti.fplassistant.testsupport.LocalSigningKey;
import dev.quantti.fplassistant.testsupport.TestJwks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

    @Value("${fpl.schedule.gameweek-table-name}")
    String gameweekTable;

    /** Cleared around every test: these rows decide which window the response describes. */
    @BeforeEach
    @AfterEach
    void clear() {
        jdbc.update("DELETE FROM " + usageTable + " WHERE user_id = ?", UUID.fromString(USER));
        jdbc.update("DELETE FROM " + profileTable + " WHERE user_id = ?", UUID.fromString(USER));
        jdbc.update("DELETE FROM " + gameweekTable);
    }

    /** A whole season of weekly deadlines, GW1 two days ago: the window is the run-up to GW2. */
    private void seedSeasonUnderway() {
        Instant first = Instant.now().minus(Duration.ofDays(2));
        Instant captured = Instant.now();
        for (int index = 0; index < 38; index++) {
            jdbc.update("INSERT INTO " + gameweekTable
                    + " (season, id, name, deadline_time, captured_at) VALUES (?, ?, ?, ?, ?)",
                    "2097/98", index + 1, "Gameweek " + (index + 1),
                    Timestamp.from(first.plus(Duration.ofDays(7L * index))),
                    Timestamp.from(captured));
        }
    }

    /**
     * The window travels with the numbers. Without it the page would have to guess what period
     * "12 of 20" describes, and it would guess "this week" — which is what this ticket removed.
     */
    @Test
    void aGameweekWindowNamesItsRoundAndItsReset() throws Exception {
        seedSeasonUnderway();
        setLimit(10);
        seedChatEvents(3);

        mockMvc.perform(get("/api/usage")
                .header("Authorization", "Bearer " + TestJwks.validTokenFor(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.window.kind").value("GAMEWEEK"))
                .andExpect(jsonPath("$.window.gameweek").value(2))
                .andExpect(jsonPath("$.window.season").value("2097/98"))
                .andExpect(jsonPath("$.resetsAt").isNotEmpty())
                .andExpect(jsonPath("$.used").value(3))
                // Nothing to wait for while messages remain; the reset is the forward-looking fact.
                .andExpect(jsonPath("$.nextFreeAt").doesNotExist());
    }

    /** No usable capture: the rolling week, and no reset instant to claim, because there is none. */
    @Test
    void theFallbackSaysSoAndOffersNoReset() throws Exception {
        setLimit(10);
        seedChatEvents(3);

        mockMvc.perform(get("/api/usage")
                .header("Authorization", "Bearer " + TestJwks.validTokenFor(USER)))
                .andExpect(jsonPath("$.window.kind").value("FALLBACK"))
                .andExpect(jsonPath("$.window.gameweek").doesNotExist())
                .andExpect(jsonPath("$.resetsAt").doesNotExist());
    }

    @Test
    void itNeedsAToken() throws Exception {
        mockMvc.perform(get("/api/usage")).andExpect(status().isUnauthorized());
    }

    /**
     * The free tier's limit is stated, because it is the offer. Issue #11's "a share, never a
     * count" now holds for the paid plan only: that limit is a cost-control number to be tuned
     * from usage data, and nobody's entitlement to quote back.
     */
    @Test
    void theFreePlanIsToldItsLimitAndWhatItHasUsed() throws Exception {
        setLimit(10);
        seedChatEvents(3);

        mockMvc.perform(get("/api/usage")
                .header("Authorization", "Bearer " + TestJwks.validTokenFor(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan").value("FREE"))
                .andExpect(jsonPath("$.remainingPercent").value(70))
                .andExpect(jsonPath("$.unlimited").value(false))
                .andExpect(jsonPath("$.nextFreeAt").isNotEmpty())
                .andExpect(jsonPath("$.used").value(3))
                .andExpect(jsonPath("$.limit").value(10));
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

    /** No limit, no count worth showing: the question the numbers answer does not arise. */
    @Test
    void anUnlimitedAccountSaysSoRatherThanReportingAShare() throws Exception {
        setLimit(-1);

        mockMvc.perform(get("/api/usage")
                .header("Authorization", "Bearer " + TestJwks.validTokenFor(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unlimited").value(true))
                .andExpect(jsonPath("$.used").doesNotExist())
                .andExpect(jsonPath("$.limit").doesNotExist());
    }

    /** No number of seconds turns a suspended account back on, so it is not offered one. */
    @Test
    void aSuspendedAccountIsNotToldWhenToComeBack() throws Exception {
        setLimit(0);
        seedChatEvents(1);

        mockMvc.perform(get("/api/usage")
                .header("Authorization", "Bearer " + TestJwks.validTokenFor(USER)))
                .andExpect(jsonPath("$.remainingPercent").value(0))
                .andExpect(jsonPath("$.nextFreeAt").doesNotExist())
                // No count and no limit either: a suspended account is told nothing that reads as
                // a quota to wait out — "3 of 0 messages used" would be a sentence about nothing.
                .andExpect(jsonPath("$.used").doesNotExist())
                .andExpect(jsonPath("$.limit").doesNotExist());
    }

    /**
     * The same silence, in a window that does have a reset instant. "Resets at the gameweek 13
     * deadline" under a 0% bar sends a suspended account to wait for a window that will not open
     * for it — the one thing every other branch here is careful not to say.
     */
    @Test
    void aSuspendedAccountIsNotShownTheWindowsReset() throws Exception {
        seedSeasonUnderway();
        setLimit(0);
        seedChatEvents(1);

        mockMvc.perform(get("/api/usage")
                .header("Authorization", "Bearer " + TestJwks.validTokenFor(USER)))
                .andExpect(jsonPath("$.window.kind").value("GAMEWEEK"))
                .andExpect(jsonPath("$.resetsAt").doesNotExist())
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
