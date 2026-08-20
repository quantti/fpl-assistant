package dev.quantti.fplassistant.chat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Conversations and their messages, always keyed on the owner.
 *
 * <p>Every read takes the user id and filters on it, the same contract {@code UserProfileService}
 * keeps: {@code sub} is the only source of a user id, and a store that cannot be asked for
 * someone else's conversation cannot hand one over by mistake. A conversation belonging to
 * somebody else is indistinguishable here from one that does not exist — which is what lets the
 * controller answer 404 rather than 403, and so decline to confirm that an id is real.
 */
@Service
public class ConversationStore {

    /** Long enough to recognise a conversation in a list, short enough for the column. */
    static final int TITLE_CHARS = 80;

    private final JdbcTemplate jdbc;
    private final String conversationTable;
    private final String messageTable;

    public ConversationStore(JdbcTemplate jdbc,
                             @Value("${fpl.user.conversation-table-name}") String conversationTable,
                             @Value("${fpl.user.message-table-name}") String messageTable) {
        this.jdbc = jdbc;
        this.conversationTable = conversationTable;
        this.messageTable = messageTable;
    }

    public record Summary(UUID id, String title, Instant updatedAt) {}

    public record StoredMessage(String role, String content, Instant createdAt) {}

    /**
     * Opens a conversation owned by this user, titled from the message that started it. The title
     * is what the list has to show before anything else exists to show; generating a better one
     * from the model is a later phase's problem and costs a call.
     */
    public UUID create(UUID userId, String firstMessage) {
        String title = firstMessage.length() > TITLE_CHARS
                ? firstMessage.substring(0, TITLE_CHARS).stripTrailing() + "…"
                : firstMessage;
        return jdbc.queryForObject(
                "INSERT INTO " + conversationTable + " (user_id, title) VALUES (?, ?) RETURNING id",
                UUID.class, userId, title);
    }

    /** Empty when the conversation does not exist <em>or</em> belongs to somebody else. */
    public Optional<UUID> findOwned(UUID conversationId, UUID userId) {
        List<UUID> found = jdbc.queryForList(
                "SELECT id FROM " + conversationTable + " WHERE id = ? AND user_id = ?",
                UUID.class, conversationId, userId);
        return found.stream().findFirst();
    }

    public void append(UUID conversationId, String role, String content) {
        jdbc.update("INSERT INTO " + messageTable
                + " (conversation_id, role, content) VALUES (?, ?, ?)",
                conversationId, role, content);
    }

    /**
     * The most recent {@code limit} turns, oldest first — the order the model needs, taken from
     * the end of the conversation because that is the part that fits in the window.
     */
    public List<ChatTurn> recentTurns(UUID conversationId, int limit) {
        List<ChatTurn> newestFirst = jdbc.query(
                "SELECT role, content FROM " + messageTable
                        + " WHERE conversation_id = ? ORDER BY created_at DESC, id DESC LIMIT ?",
                (rs, row) -> new ChatTurn(rs.getString("role"), rs.getString("content")),
                conversationId, limit);
        return newestFirst.reversed();
    }

    /** The caller's conversations, most recently used first — the order the sidebar shows. */
    public List<Summary> list(UUID userId) {
        return jdbc.query(
                "SELECT id, title, updated_at FROM " + conversationTable
                        + " WHERE user_id = ? AND archived_at IS NULL ORDER BY updated_at DESC",
                (rs, row) -> new Summary(
                        rs.getObject("id", UUID.class),
                        rs.getString("title"),
                        rs.getTimestamp("updated_at").toInstant()),
                userId);
    }

    /** Every message of one conversation, oldest first. Ownership is the caller's to check. */
    public List<StoredMessage> messages(UUID conversationId) {
        return jdbc.query(
                "SELECT role, content, created_at FROM " + messageTable
                        + " WHERE conversation_id = ? ORDER BY created_at, id",
                (rs, row) -> new StoredMessage(
                        rs.getString("role"),
                        rs.getString("content"),
                        rs.getTimestamp("created_at").toInstant()),
                conversationId);
    }

    /**
     * Bumps {@code updated_at} so the conversation rises in the list. The trigger owns the
     * column's value; this only has to touch the row, and touching it with its own id is the
     * smallest write that does.
     */
    public void touch(UUID conversationId) {
        jdbc.update("UPDATE " + conversationTable + " SET id = id WHERE id = ?", conversationId);
    }
}
