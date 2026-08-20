package dev.quantti.fplassistant.chat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The store's half of the authorization contract: a conversation is reachable through its owner
 * or not at all, and history comes back in the order the model needs it.
 */
@SpringBootTest
@ActiveProfiles("test")
class ConversationStoreTest {

    private static final UUID USER_A = UUID.fromString("aaaaaaaa-1111-4111-8111-111111111111");
    private static final UUID USER_B = UUID.fromString("bbbbbbbb-2222-4222-8222-222222222222");

    @Autowired
    ConversationStore store;

    @Autowired
    JdbcTemplate jdbc;

    @Value("${fpl.user.conversation-table-name}")
    String conversationTable;

    @AfterEach
    void clear() {
        // Messages cascade from the conversation, so one delete per user is enough.
        for (UUID user : new UUID[] {USER_A, USER_B}) {
            jdbc.update("DELETE FROM " + conversationTable + " WHERE user_id = ?", user);
        }
    }

    @Test
    void aConversationIsFoundByItsOwnerOnly() {
        UUID id = store.create(USER_A, "Who should I captain?");

        assertThat(store.findOwned(id, USER_A)).contains(id);
        assertThat(store.findOwned(id, USER_B))
                .as("another user's conversation is indistinguishable from one that does not exist")
                .isEmpty();
        assertThat(store.findOwned(UUID.randomUUID(), USER_A)).isEmpty();
    }

    @Test
    void theListShowsOnlyTheCallersConversationsMostRecentlyUsedFirst() {
        UUID older = store.create(USER_A, "First question");
        UUID newer = store.create(USER_A, "Second question");
        store.create(USER_B, "Someone else's");

        assertThat(store.list(USER_A)).extracting(ConversationStore.Summary::id)
                .containsExactly(newer, older);

        store.touch(older);

        assertThat(store.list(USER_A)).extracting(ConversationStore.Summary::id)
                .as("a conversation used again rises to the top")
                .containsExactly(older, newer);
    }

    @Test
    void historyComesBackOldestFirstAndBoundedToTheNewestTurns() {
        UUID id = store.create(USER_A, "start");
        for (int i = 1; i <= 5; i++) {
            store.append(id, ChatTurn.USER, "question " + i);
            store.append(id, ChatTurn.ASSISTANT, "answer " + i);
        }

        List<ChatTurn> all = store.recentTurns(id, 100);
        assertThat(all).hasSize(10);
        assertThat(all.getFirst()).isEqualTo(new ChatTurn(ChatTurn.USER, "question 1"));
        assertThat(all.getLast()).isEqualTo(new ChatTurn(ChatTurn.ASSISTANT, "answer 5"));

        // The limit takes the newest turns and still hands them over oldest-first: a window cut
        // from the wrong end would feed the model the beginning of a long conversation.
        assertThat(store.recentTurns(id, 3)).containsExactly(
                new ChatTurn(ChatTurn.ASSISTANT, "answer 4"),
                new ChatTurn(ChatTurn.USER, "question 5"),
                new ChatTurn(ChatTurn.ASSISTANT, "answer 5"));
    }

    @Test
    void messagesAreReadBackOldestFirst() {
        UUID id = store.create(USER_A, "start");
        store.append(id, ChatTurn.USER, "first");
        store.append(id, ChatTurn.ASSISTANT, "second");

        assertThat(store.messages(id)).extracting(ConversationStore.StoredMessage::content)
                .containsExactly("first", "second");
    }

    @Test
    void aLongFirstMessageIsTruncatedIntoTheTitle() {
        String long_ = "a".repeat(ConversationStore.TITLE_CHARS + 40);
        UUID id = store.create(USER_A, long_);

        String title = store.list(USER_A).stream()
                .filter(summary -> summary.id().equals(id))
                .findFirst().orElseThrow().title();
        assertThat(title).hasSize(ConversationStore.TITLE_CHARS + 1).endsWith("…");
    }
}
