package fi.karivanttinen.fplassistant.trace;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Persistence boundary used by future chat instrumentation and the trace admin API. */
@Service
@DependsOn("chatTraceSchema")
public class ChatTraceStore {

    public static final int MAX_LIST_SIZE = 100;

    private final JdbcTemplate jdbc;
    // Boot 4's web starter does not guarantee a legacy Jackson ObjectMapper bean.
    private final ObjectMapper json = new ObjectMapper();
    private final String table;

    public ChatTraceStore(JdbcTemplate jdbc,
                          @Value("${fpl.trace.table-name}") String table) {
        this.jdbc = jdbc;
        this.table = table;
    }

    public record Summary(UUID id, UUID userId, UUID conversationId, Instant createdAt,
                          Instant completedAt) {}

    public record Trace(UUID id, UUID userId, UUID conversationId, JsonNode payload,
                        Instant createdAt, Instant completedAt) {}

    /** Starts one trace. The payload may be any JSON object or array the instrumentation needs. */
    public UUID start(UUID userId, UUID conversationId, JsonNode payload) {
        return jdbc.queryForObject("INSERT INTO " + table
                        + " (user_id, conversation_id, payload) VALUES (?, ?, CAST(? AS jsonb))"
                        + " RETURNING id",
                UUID.class, userId, conversationId, encode(payload));
    }

    /** Replaces the trace snapshot and marks it complete; false means the id did not exist. */
    public boolean complete(UUID traceId, JsonNode payload) {
        return jdbc.update("UPDATE " + table
                        + " SET payload = CAST(? AS jsonb), completed_at = NOW() WHERE id = ?",
                encode(payload), traceId) == 1;
    }

    public List<Summary> list(int limit, Instant before) {
        if (limit < 1 || limit > MAX_LIST_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIST_SIZE);
        }
        String where = before == null ? "" : " WHERE created_at < ?";
        Object[] args = before == null
                ? new Object[] {limit} : new Object[] {Timestamp.from(before), limit};
        return jdbc.query("SELECT id, user_id, conversation_id, created_at, completed_at FROM "
                        + table + where + " ORDER BY created_at DESC, id DESC LIMIT ?",
                (rs, row) -> new Summary(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getObject("conversation_id", UUID.class),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("completed_at") == null
                                ? null : rs.getTimestamp("completed_at").toInstant()),
                args);
    }

    public Optional<Trace> find(UUID id) {
        return jdbc.query("SELECT id, user_id, conversation_id, payload, created_at, completed_at"
                        + " FROM " + table + " WHERE id = ?",
                (rs, row) -> new Trace(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getObject("conversation_id", UUID.class),
                        decode(rs.getString("payload")),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("completed_at") == null
                                ? null : rs.getTimestamp("completed_at").toInstant()),
                id).stream().findFirst();
    }

    private String encode(JsonNode payload) {
        if (payload == null) {
            throw new IllegalArgumentException("trace payload is required");
        }
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("trace payload is not valid JSON", e);
        }
    }

    private JsonNode decode(String payload) {
        try {
            return json.readTree(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored trace payload is not valid JSON", e);
        }
    }
}
