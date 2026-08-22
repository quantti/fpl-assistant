package fi.karivanttinen.fplassistant.user;

import java.time.Duration;
import java.time.Instant;
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
 * The quota, counted against seeded timestamps rather than the clock: every row this test cares
 * about is written at a stated age, so nothing here waits for time to pass or fails at midnight.
 */
@SpringBootTest
@ActiveProfiles("test")
class UsageServiceTest {

    private static final UUID USER = UUID.fromString("7e4f8152-1111-4111-8111-eeeeeeeeeeee");

    @Autowired
    UsageService usage;

    @Autowired
    JdbcTemplate jdbc;

    @Value("${fpl.user.usage-table-name}")
    String usageTable;

    @Value("${fpl.user.profile-table-name}")
    String profileTable;

    @Value("${fpl.user.conversation-table-name}")
    String conversationTable;

    @Value("${fpl.quota.free-messages-per-week}")
    int freeLimit;

    @AfterEach
    void clear() {
        jdbc.update("DELETE FROM " + usageTable + " WHERE user_id = ?", USER);
        jdbc.update("DELETE FROM " + conversationTable + " WHERE user_id = ?", USER);
        jdbc.update("DELETE FROM " + profileTable + " WHERE user_id = ?", USER);
    }

    private void seedEvents(int count, Duration age, String eventType) {
        for (int i = 0; i < count; i++) {
            jdbc.update("INSERT INTO " + usageTable
                    + " (user_id, event_type, created_at) VALUES (?, ?, now() - (? || ' seconds')::interval)",
                    USER, eventType, age.toSeconds());
        }
    }

    private void setLimit(Integer limit) {
        jdbc.update("INSERT INTO " + profileTable + " (user_id, weekly_message_limit) VALUES (?, ?)"
                + " ON CONFLICT (user_id) DO UPDATE SET weekly_message_limit = EXCLUDED.weekly_message_limit",
                USER, limit);
    }

    @Test
    void anAccountWithNoProfileRowGetsTheConfiguredDefault() {
        UsageService.Quota quota = usage.quotaOf(USER);

        assertThat(quota.limit()).isEqualTo(freeLimit);
        assertThat(quota.used()).isZero();
        assertThat(quota.exceeded()).isFalse();
    }

    /**
     * The tier every account is on until Phase 5b, and the number the spec draws its cost
     * ceiling against. Asserted as a literal rather than against the property: a plan limit that
     * quietly moved would still pass a test reading it from the same place the code does.
     */
    @Test
    void theFreePlanIsTwentyMessagesAWeek() {
        UsageService.Quota quota = usage.quotaOf(USER);

        assertThat(quota.plan()).isEqualTo(Plan.FREE);
        assertThat(quota.limit()).isEqualTo(20);
    }

    /** The override is how an account is given more than its tier before Stripe exists. */
    @Test
    void aPerUserOverrideWinsOverThePlanLimit() {
        setLimit(200);

        assertThat(usage.quotaOf(USER).limit()).isEqualTo(200);
    }

    @Test
    void onlyMessagesInsideTheWindowCount() {
        seedEvents(3, Duration.ofDays(2), UsageService.CHAT_EVENT);
        seedEvents(4, UsageService.WINDOW.plusHours(1), UsageService.CHAT_EVENT);

        assertThat(usage.quotaOf(USER).used())
                .as("the four older than seven days have left the window")
                .isEqualTo(3);
    }

    @Test
    void onlyChatEventsCount() {
        seedEvents(2, Duration.ofHours(1), UsageService.CHAT_EVENT);
        seedEvents(5, Duration.ofHours(1), "title-generation");

        assertThat(usage.quotaOf(USER).used()).isEqualTo(2);
    }

    @Test
    void theLimitIsPerUserAndMinusOneMeansUnlimited() {
        seedEvents(freeLimit + 10, Duration.ofHours(1), UsageService.CHAT_EVENT);

        setLimit(-1);
        UsageService.Quota unlimited = usage.quotaOf(USER);
        assertThat(unlimited.limit()).isNull();
        assertThat(unlimited.exceeded()).isFalse();

        setLimit(freeLimit + 100);
        assertThat(usage.quotaOf(USER).exceeded()).isFalse();

        setLimit(null);
        assertThat(usage.quotaOf(USER).exceeded())
                .as("NULL is not an override — the configured default applies")
                .isTrue();
    }

    @Test
    void zeroSuspendsTheAccountAndWaitingCannotHelp() {
        setLimit(0);

        UsageService.Quota quota = usage.quotaOf(USER);
        assertThat(quota.exceeded()).isTrue();
        assertThat(quota.retryAfterSeconds())
                .as("no number of seconds turns a suspended account back on")
                .isEmpty();
    }

    @Test
    void oneMessageLeavingTheWindowFreesExactlyOneSlot() {
        setLimit(2);
        // One nearly out of the window, one fresh: the older is what frees up next.
        seedEvents(1, UsageService.WINDOW.minusMinutes(5), UsageService.CHAT_EVENT);
        seedEvents(1, Duration.ofHours(1), UsageService.CHAT_EVENT);

        UsageService.Quota quota = usage.quotaOf(USER);
        assertThat(quota.exceeded()).isTrue();
        assertThat(quota.nextFreeAt()).isBetween(Instant.now(), Instant.now().plus(Duration.ofMinutes(6)));
        assertThat(quota.retryAfterSeconds()).hasValueSatisfying(seconds ->
                assertThat(seconds).isBetween(1L, Duration.ofMinutes(6).toSeconds()));
    }

    /**
     * A suspended account usually has usage inside the window — it was chatting until someone
     * suspended it — so there is a timestamp to compute. Answering with it would promise the
     * account comes back at a time it does not.
     */
    @Test
    void aSuspendedAccountWithUsageStillOffersNoRetryAfter() {
        seedEvents(3, Duration.ofDays(1), UsageService.CHAT_EVENT);
        setLimit(0);

        UsageService.Quota quota = usage.quotaOf(USER);
        assertThat(quota.exceeded()).isTrue();
        assertThat(quota.retryAfterSeconds()).isEmpty();
    }

    /**
     * Over the limit — which is what lowering someone's limit does — the next free slot is not
     * the oldest event but the one that many places in. Pointing at the oldest sends the client
     * back for a second refusal it was given no way to predict.
     */
    @Test
    void overTheLimitTheNextFreeSlotSkipsTheOnesStillOwed() {
        setLimit(1);
        // Ages 6, 5 and 4 days: they leave the window in 1, 2 and 3 days respectively.
        seedEvents(1, UsageService.WINDOW.minusDays(1), UsageService.CHAT_EVENT);
        seedEvents(1, UsageService.WINDOW.minusDays(2), UsageService.CHAT_EVENT);
        seedEvents(1, UsageService.WINDOW.minusDays(3), UsageService.CHAT_EVENT);

        UsageService.Quota quota = usage.quotaOf(USER);
        assertThat(quota.used()).isEqualTo(3);
        // Three events against a limit of one: two must leave before there is room, so the slot
        // frees in three days — not the one day the oldest event alone would suggest.
        assertThat(quota.nextFreeAt()).isBetween(
                Instant.now().plus(Duration.ofHours(68)), Instant.now().plus(Duration.ofHours(76)));
    }

    @Test
    void claimingRecordsATurnAndRefusesOneOverTheLimit() {
        setLimit(1);

        long eventId = usage.claimChatTurn(USER, "anthropic");
        assertThat(eventId).isPositive();
        assertThat(usage.quotaOf(USER).used()).isEqualTo(1);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> usage.claimChatTurn(USER, "anthropic"))
                .isInstanceOf(QuotaExceededException.class);
        assertThat(usage.quotaOf(USER).used())
                .as("a refused turn is not a recorded turn")
                .isEqualTo(1);
    }

    /** Unreported is not zero, but it is not a number to add either. */
    @Test
    void theTotalCountsOnlyWhatWasReported() {
        long eventId = usage.claimChatTurn(USER, "anthropic");

        assertThat(jdbc.queryForObject("SELECT total_tokens FROM " + usageTable + " WHERE id = ?",
                Integer.class, eventId))
                .as("a turn whose provider reported nothing has spent nothing we know of")
                .isZero();

        usage.recordTokens(eventId, "claude-sonnet-5", 1000, null, null, 200);
        assertThat(jdbc.queryForObject("SELECT total_tokens FROM " + usageTable + " WHERE id = ?",
                Integer.class, eventId))
                .isEqualTo(1200);
    }

    @Test
    void aDiscardedChargeLeavesNothingBehind() {
        long eventId = usage.claimChatTurn(USER, "anthropic");
        usage.discard(eventId);

        assertThat(usage.quotaOf(USER).used()).isZero();
    }

    /**
     * The charge names its conversation but does not depend on it. Usage counted from something
     * the user can delete is a quota the user can refill by deleting a conversation — which is
     * why the column carries no foreign key and no cascade.
     */
    @Test
    void deletingTheConversationLeavesTheChargeStanding() {
        UUID conversationId = jdbc.queryForObject(
                "INSERT INTO " + conversationTable + " (user_id, title) VALUES (?, 'x') RETURNING id",
                UUID.class, USER);
        long eventId = usage.claimChatTurn(USER, "anthropic");
        usage.attachConversation(eventId, conversationId);

        jdbc.update("DELETE FROM " + conversationTable + " WHERE id = ?", conversationId);

        assertThat(usage.quotaOf(USER).used()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT conversation_id FROM " + usageTable
                + " WHERE id = ?", UUID.class, eventId))
                .as("the reference dangles on purpose rather than taking the row with it")
                .isEqualTo(conversationId);
    }

    @Test
    void tokensAreRecordedAgainstTheEventTheCallOpened() {
        long eventId = usage.claimChatTurn(USER, "anthropic");
        usage.recordTokens(eventId, "claude-sonnet-5", 1200, 900, 400, 300);

        assertThat(jdbc.queryForMap(
                "SELECT provider, model, input_tokens, cached_tokens, cache_write_tokens,"
                        + " output_tokens, total_tokens FROM " + usageTable + " WHERE id = ?", eventId))
                .containsEntry("provider", "anthropic")
                .containsEntry("model", "claude-sonnet-5")
                .containsEntry("input_tokens", 1200)
                .containsEntry("cached_tokens", 900)
                .containsEntry("cache_write_tokens", 400)
                .containsEntry("output_tokens", 300)
                .as("every kind of token, added up by the database rather than by a caller")
                .containsEntry("total_tokens", 2800);
    }
}
