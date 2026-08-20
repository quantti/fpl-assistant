package dev.quantti.fplassistant.user;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * What a user has spent this week, and whether they may spend more.
 *
 * <p>The window is a rolling seven days counted in the database, not in this process: a count
 * against {@code now()} needs no clock of its own and cannot drift from the timestamps it is
 * counting. Only {@code chat} events count — title generation or a background call would
 * otherwise silently consume someone's quota.
 *
 * <p>Usage is recorded when a call starts, never when it finishes. The cost is incurred at the
 * start; recording on completion would make an aborted stream free to repeat.
 */
@Service
public class UsageService {

    /** The rolling window. Seven days, stated once. */
    static final Duration WINDOW = Duration.ofDays(7);

    /** Marks a chat turn among whatever else may record usage later. */
    static final String CHAT_EVENT = "chat";

    private final JdbcTemplate jdbc;
    private final String usageTable;
    private final String profileTable;
    private final int defaultLimit;

    public UsageService(JdbcTemplate jdbc,
                        @Value("${fpl.user.usage-table-name}") String usageTable,
                        @Value("${fpl.user.profile-table-name}") String profileTable,
                        @Value("${fpl.quota.messages-per-week}") int defaultLimit) {
        this.jdbc = jdbc;
        this.usageTable = usageTable;
        this.profileTable = profileTable;
        this.defaultLimit = defaultLimit;
    }

    /**
     * @param limit      messages allowed per window, or {@code null} for unlimited
     * @param nextFreeAt when the oldest counted message leaves the window, or {@code null} when
     *                   nothing is counted — or when waiting cannot help, which is what a
     *                   suspended account (limit 0) means
     */
    public record Quota(int used, Integer limit, Instant nextFreeAt) {

        public boolean exceeded() {
            return limit != null && used >= limit;
        }

        /** Seconds until one message frees up, or empty when waiting changes nothing. */
        public java.util.Optional<Long> retryAfterSeconds() {
            if (nextFreeAt == null) {
                return java.util.Optional.empty();
            }
            long seconds = Duration.between(Instant.now(), nextFreeAt).toSeconds();
            return java.util.Optional.of(Math.max(1, seconds));
        }
    }

    public Quota quotaOf(UUID userId) {
        Integer limit = resolveLimit(userId);
        if (limit == null) {
            // Unlimited: the count is the one query worth skipping, since it is the reason this
            // check exists at all.
            return new Quota(0, null, null);
        }

        return jdbc.queryForObject("""
                SELECT count(*) AS used, min(created_at) AS oldest FROM %s
                WHERE user_id = ? AND event_type = ? AND created_at > now() - interval '%d days'
                """.formatted(usageTable, WINDOW.toDays()),
                (rs, row) -> {
                    int used = rs.getInt("used");
                    Timestamp oldest = rs.getTimestamp("oldest");
                    Instant nextFree = oldest == null ? null : oldest.toInstant().plus(WINDOW);
                    return new Quota(used, limit, nextFree);
                },
                userId, CHAT_EVENT);
    }

    /**
     * {@code null} means unlimited. A missing profile row and a {@code NULL} column are the same
     * case — the configured default — which is what almost every account is.
     */
    private Integer resolveLimit(UUID userId) {
        List<Integer> stored = jdbc.queryForList(
                "SELECT weekly_message_limit FROM " + profileTable + " WHERE user_id = ?",
                Integer.class, userId);
        Integer configured = stored.isEmpty() ? null : stored.getFirst();
        if (configured == null) {
            return defaultLimit;
        }
        return configured < 0 ? null : configured;
    }

    /** Records the start of one chat turn and returns the row's id, for the token update. */
    public long recordChatStart(UUID userId, String provider) {
        return jdbc.queryForObject(
                "INSERT INTO " + usageTable + " (user_id, event_type, provider)"
                        + " VALUES (?, ?, ?) RETURNING id",
                Long.class, userId, CHAT_EVENT, provider);
    }

    /**
     * Fills in what the provider reported. Separate from the insert because the numbers only
     * exist once the answer is finished, and the row has to exist before it starts.
     */
    public void recordTokens(long eventId, Integer inputTokens, Integer cachedTokens,
                             Integer outputTokens) {
        jdbc.update("UPDATE " + usageTable
                + " SET input_tokens = ?, cached_tokens = ?, output_tokens = ? WHERE id = ?",
                inputTokens, cachedTokens, outputTokens, eventId);
    }
}
