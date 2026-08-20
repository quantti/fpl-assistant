package dev.quantti.fplassistant.user;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final Pricing pricing;
    private final String usageTable;
    private final String profileTable;
    private final int defaultLimit;

    public UsageService(JdbcTemplate jdbc,
                        Pricing pricing,
                        @Value("${fpl.user.usage-table-name}") String usageTable,
                        @Value("${fpl.user.profile-table-name}") String profileTable,
                        @Value("${fpl.quota.messages-per-week}") int defaultLimit) {
        this.jdbc = jdbc;
        this.pricing = pricing;
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

        /**
         * Seconds until one message frees up, or empty when waiting changes nothing.
         *
         * <p>A suspended account is the case worth spelling out: it may well have usage inside
         * the window, so there is a timestamp to compute — and answering with it would promise
         * that the account comes back at a time when it does not.
         */
        public java.util.Optional<Long> retryAfterSeconds() {
            if (nextFreeAt == null || (limit != null && limit == 0)) {
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

        Integer used = jdbc.queryForObject("""
                SELECT count(*) FROM %s
                WHERE user_id = ? AND event_type = ? AND created_at > now() - interval '%d days'
                """.formatted(usageTable, WINDOW.toDays()),
                Integer.class, userId, CHAT_EVENT);
        return new Quota(used, limit, nextFreeAt(userId, used, limit));
    }

    /**
     * When the window next frees a slot.
     *
     * <p>Not simply the oldest event: an account over its limit has to wait for the
     * {@code (used - limit + 1)}-th oldest to age out, which is what the offset is. They differ
     * whenever a limit is lowered under someone — with the oldest event, the client waits it
     * out, retries, and is refused again by exactly the margin nobody told it about.
     */
    private Instant nextFreeAt(UUID userId, int used, int limit) {
        if (used == 0) {
            return null;
        }
        List<Timestamp> frees = jdbc.queryForList("""
                SELECT created_at FROM %s
                WHERE user_id = ? AND event_type = ? AND created_at > now() - interval '%d days'
                ORDER BY created_at OFFSET ? LIMIT 1
                """.formatted(usageTable, WINDOW.toDays()),
                Timestamp.class, userId, CHAT_EVENT, Math.max(0, used - limit));
        return frees.isEmpty() ? null : frees.getFirst().toInstant().plus(WINDOW);
    }

    /**
     * {@code null} means unlimited. A missing profile row and a {@code NULL} column are the same
     * case — the configured default — which is what almost every account is.
     */
    private Integer resolveLimit(UUID userId) {
        List<Integer> stored = jdbc.queryForList(
                "SELECT weekly_message_limit FROM " + profileTable + " WHERE user_id = ?",
                Integer.class, userId);
        Integer override = stored.isEmpty() ? null : stored.getFirst();
        // The sentinel applies to the configured default too. It is documented beside the
        // property, so someone will set QUOTA_MESSAGES_PER_WEEK=-1 to open the gates — and
        // reading it literally would refuse every account with "all -1 messages used".
        int limit = override == null ? defaultLimit : override;
        return limit < 0 ? null : limit;
    }

    /**
     * Checks the quota and records the turn in one transaction, so two requests cannot both pass
     * a check neither has yet paid for.
     *
     * <p>The count and the insert are otherwise a read followed by a write with nothing between
     * them: two tabs at {@code used == limit - 1} both read the same number and both go through.
     * The advisory lock is per user and released with the transaction, so it serialises one
     * person's own requests and nobody else's.
     *
     * @return the usage row's id, for the token update once the answer is finished
     * @throws QuotaExceededException when the caller has nothing left this week
     */
    @Transactional
    public long claimChatTurn(UUID userId, String provider) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))", Object.class,
                userId.toString());
        Quota quota = quotaOf(userId);
        if (quota.exceeded()) {
            throw new QuotaExceededException(quota);
        }
        return recordChatStart(userId, provider);
    }

    /**
     * Removes a charge for a call that produced nothing. A provider that was down consumed no
     * tokens, and leaving the row would spend a week's allowance on answers nobody received —
     * during an outage, everybody's at once.
     */
    public void discard(long eventId) {
        jdbc.update("DELETE FROM " + usageTable + " WHERE id = ?", eventId);
    }

    /** Records the start of one chat turn and returns the row's id, for the token update. */
    long recordChatStart(UUID userId, String provider) {
        return jdbc.queryForObject(
                "INSERT INTO " + usageTable + " (user_id, event_type, provider)"
                        + " VALUES (?, ?, ?) RETURNING id",
                Long.class, userId, CHAT_EVENT, provider);
    }

    /**
     * Fills in what the provider reported, and what it cost. Separate from the insert because
     * the numbers only exist once the answer is finished, and the row has to exist before it
     * starts.
     *
     * <p>The cost is priced here and stored, not derived when someone reads the row: rates
     * change, and a figure that reprices itself is not a record of what was spent.
     */
    public void recordTokens(long eventId, String provider, String model, Integer inputTokens,
                             Integer cachedTokens, Integer cacheWriteTokens, Integer outputTokens) {
        BigDecimal cost = pricing
                .costOf(provider, inputTokens, cachedTokens, cacheWriteTokens, outputTokens)
                .orElse(null);
        jdbc.update("UPDATE " + usageTable + " SET model = ?, input_tokens = ?, cached_tokens = ?,"
                + " cache_write_tokens = ?, output_tokens = ?, cost_usd = ? WHERE id = ?",
                model, inputTokens, cachedTokens, cacheWriteTokens, outputTokens, cost, eventId);
    }

    /**
     * Ties the charge to the conversation it paid for, so a cost can be read back per message
     * rather than only per week. Written after the fact because the quota is claimed before a
     * conversation exists — a turn nobody was allowed to take must not create one.
     */
    public void attachConversation(long eventId, UUID conversationId) {
        jdbc.update("UPDATE " + usageTable + " SET conversation_id = ? WHERE id = ?",
                conversationId, eventId);
    }
}
