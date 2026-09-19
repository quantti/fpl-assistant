package fi.karivanttinen.fplassistant.user;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a user has spent in the window they are in, and whether they may spend more.
 *
 * <p>The window is a gameweek: from one FPL deadline to the next of the same season, with the gap
 * between two seasons as one off-season window of its own. {@link QuotaWindows} resolves it. When
 * no schedule can be read the count falls back to the rolling seven days it used to be, which is
 * the only thing {@link #WINDOW} is still for. Only {@code chat} events count — title generation
 * or a background call would otherwise silently consume someone's quota.
 *
 * <p>Usage is recorded when a call starts, never when it finishes. The cost is incurred at the
 * start; recording on completion would make an aborted stream free to repeat.
 *
 * <p>The fallback counts entirely in the database, against {@code now()}, and so cannot drift from
 * the timestamps it counts. A fixed window cannot: which window an instant falls in is decided
 * here, from this process's clock, against deadlines the database supplied. The bounds themselves
 * are database values, so the count inside a window is exact; only the choice of window can be
 * off, and only by the app-to-database clock skew, for a message sent within that skew of a
 * deadline. Bounded by NTP and not worth a second round trip inside the claim's advisory lock.
 */
@Service
public class UsageService {

    /** The fallback's rolling window. Seven days, stated once. */
    static final Duration WINDOW = Duration.ofDays(7);

    /** Marks a chat turn among whatever else may record usage later. */
    static final String CHAT_EVENT = "chat";

    private final JdbcTemplate jdbc;
    private final QuotaWindows windows;
    private final String usageTable;
    private final String profileTable;

    public UsageService(JdbcTemplate jdbc,
                        QuotaWindows windows,
                        @Value("${fpl.user.usage-table-name}") String usageTable,
                        @Value("${fpl.user.profile-table-name}") String profileTable) {
        this.jdbc = jdbc;
        this.windows = windows;
        this.usageTable = usageTable;
        this.profileTable = profileTable;
    }

    /**
     * @param plan       the tier the limit was resolved against. Carried rather than looked up
     *                   again, so the response and the 429 cannot disagree about it
     * @param limit      messages allowed per window, or {@code null} for unlimited
     * @param nextFreeAt when the caller may send again, or {@code null} when waiting cannot help,
     *                   which is what a suspended account (limit 0) means. In a fixed window it is
     *                   the window's end and is set only once the allowance is spent — before that
     *                   there is nothing to wait for. In the fallback it is when the message that
     *                   has to age out does so, and is set whenever anything is counted at all:
     *                   a rolling window is always in the middle of freeing something
     * @param resetsAt   when the count returns to zero. {@code null} in the fallback, where
     *                   nothing resets, and in an off-season window whose end is not yet captured
     * @param window     the period the numbers describe, so the reply and the page can name it
     */
    public record Quota(Plan plan, int used, Integer limit, Instant nextFreeAt, Instant resetsAt,
                        QuotaWindow window) {

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
        Plan plan = planOf(userId);
        QuotaWindow window = windows.current(Instant.now());
        Integer limit = resolveLimit(userId, window);
        if (limit == null) {
            // Unlimited: the count is the one query worth skipping, since it is the reason this
            // check exists at all.
            return new Quota(plan, 0, null, null, null, window);
        }

        if (window.fallback()) {
            int used = countRollingWindow(userId);
            return new Quota(plan, used, limit, nextFreeAt(userId, used, limit), null, window);
        }

        int used = countBetween(userId, window.from(), window.until());
        // A fixed window has one reset instant, so "when does the count start over" and "when may
        // I send again" are the same answer — unlike the fallback, where nothing starts over.
        return new Quota(plan, used, limit, used >= limit ? window.until() : null,
                window.until(), window);
    }

    private int countRollingWindow(UUID userId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM %s
                WHERE user_id = ? AND event_type = ? AND created_at > now() - interval '%d days'
                """.formatted(usageTable, WINDOW.toDays()),
                Integer.class, userId, CHAT_EVENT);
    }

    /**
     * Half-open. {@code from} is always known — a window with no lower bound is the fallback, not
     * a window — while {@code until} is absent once the season being played has run out of
     * captured deadlines and the next one has not been published.
     */
    private int countBetween(UUID userId, Instant from, Instant until) {
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM " + usageTable
                + " WHERE user_id = ? AND event_type = ? AND created_at >= ?");
        List<Object> args = new ArrayList<>(List.of(userId, CHAT_EVENT, Timestamp.from(from)));
        if (until != null) {
            sql.append(" AND created_at < ?");
            args.add(Timestamp.from(until));
        }
        return jdbc.queryForObject(sql.toString(), Integer.class, args.toArray());
    }

    /**
     * When the rolling fallback window next frees a slot.
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
     * The account's tier. Every account is free in this phase; Phase 5b adds the one branch that
     * asks whether an entitling subscription exists, and adds it here so nothing else has to
     * change.
     */
    private Plan planOf(UUID userId) {
        return Plan.FREE;
    }

    /**
     * {@code null} means unlimited. A missing profile row and a {@code NULL} column are the same
     * case — the window's own allowance — which is what almost every account is. An override
     * replaces that number whatever kind of window is in force: it is a statement about the
     * account, not about the period.
     */
    private Integer resolveLimit(UUID userId, QuotaWindow window) {
        List<Integer> stored = jdbc.queryForList(
                "SELECT weekly_message_limit FROM " + profileTable + " WHERE user_id = ?",
                Integer.class, userId);
        Integer override = stored.isEmpty() ? null : stored.getFirst();
        // The sentinel applies to the configured default too. It is documented beside the
        // property, so someone will set QUOTA_FREE_MESSAGES_PER_GAMEWEEK=-1 to open the gates — and
        // reading it literally would refuse every account with "all -1 messages used".
        int limit = override == null ? window.limit() : override;
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
     * Fills in the latest reported turn aggregate. Separate from the insert because the row must
     * exist before generation starts; the controller persists the final aggregate on completion
     * or after a failure that reported usage.
     *
     * <p>Tokens only. Money is deliberately not recorded: a price depends on the model, the date
     * and whatever promotion is running, so a stored figure is wrong as soon as any of those
     * move — and the question worth answering is how many tokens a message spent.
     */
    public void recordTokens(long eventId, String model, Integer inputTokens,
                             Integer cachedTokens, Integer cacheWriteTokens, Integer outputTokens) {
        jdbc.update("UPDATE " + usageTable + " SET model = ?, input_tokens = ?, cached_tokens = ?,"
                + " cache_write_tokens = ?, output_tokens = ? WHERE id = ?",
                model, inputTokens, cachedTokens, cacheWriteTokens, outputTokens, eventId);
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
