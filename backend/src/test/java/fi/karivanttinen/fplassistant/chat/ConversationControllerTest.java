package fi.karivanttinen.fplassistant.chat;

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

/** Reading conversations back: the caller's own, and nobody else's. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(LocalSigningKey.class)
class ConversationControllerTest {

    private static final String USER_A = "5c2d6f30-1111-4111-8111-cccccccccccc";
    private static final String USER_B = "6d3e7041-2222-4222-8222-dddddddddddd";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ConversationStore conversations;

    @Value("${fpl.user.conversation-table-name}")
    String conversationTable;

    @AfterEach
    void clear() {
        for (String user : new String[] {USER_A, USER_B}) {
            jdbc.update("DELETE FROM " + conversationTable + " WHERE user_id = ?",
                    UUID.fromString(user));
        }
    }

    @Test
    void bothEndpointsNeedAToken() throws Exception {
        mockMvc.perform(get("/api/conversations")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/conversations/" + UUID.randomUUID() + "/messages"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theListHoldsOnlyTheCallersConversations() throws Exception {
        conversations.create(UUID.fromString(USER_A), "Mine");
        conversations.create(UUID.fromString(USER_B), "Theirs");

        mockMvc.perform(get("/api/conversations").header("Authorization", bearer(USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Mine"));
    }

    @Test
    void messagesComeBackOldestFirstForTheOwner() throws Exception {
        UUID id = conversations.create(UUID.fromString(USER_A), "Captaincy");
        conversations.append(id, ChatTurn.USER, "Who should I captain?");
        conversations.append(id, ChatTurn.ASSISTANT, "Consider the fixtures.");

        mockMvc.perform(get("/api/conversations/" + id + "/messages")
                .header("Authorization", bearer(USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.messages[0].content").value("Who should I captain?"))
                .andExpect(jsonPath("$.messages[0].catalogueId").doesNotExist())
                .andExpect(jsonPath("$.messages[1].role").value("assistant"))
                .andExpect(jsonPath("$.messages[1].catalogueId").doesNotExist())
                .andExpect(jsonPath("$.catalogues").isEmpty());
    }

    /** The one that matters: another user's id must not read as empty, it must read as absent. */
    @Test
    void anotherUsersConversationIsNotFound() throws Exception {
        UUID id = conversations.create(UUID.fromString(USER_A), "Mine");

        mockMvc.perform(get("/api/conversations/" + id + "/messages")
                .header("Authorization", bearer(USER_B)))
                .andExpect(status().isNotFound());
    }

    /**
     * A verified token is not a token with a usable subject. Bare {@code UUID.fromString} on the
     * claim turns a token without one into a 500 with a stack trace, on a path anyone can reach;
     * {@code TokenSubject} keeps it at 401, and this is what stops a third call site forgetting.
     */
    @Test
    void aTokenWithoutAUsableSubjectIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/conversations").header("Authorization", bearer("not-a-uuid")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/conversations/" + UUID.randomUUID() + "/messages")
                .header("Authorization", bearer("not-a-uuid")))
                .andExpect(status().isUnauthorized());
    }

    private static String bearer(String user) {
        return "Bearer " + TestJwks.validTokenFor(user);
    }
}
