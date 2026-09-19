package fi.karivanttinen.fplassistant.user;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

    @Value("${fpl.quota.free-messages-per-gameweek}")
    int freeLimit;

    @Value("${fpl.quota.free-messages-off-season}")
    int offSeasonLimit;

    @Value("${fpl.schedule.gameweek-table-name}")
    String gameweekTable;

    /**
     * The gameweek table is cleared around every test, not only after: which window a count falls
     * in is decided by the rows in it, so a capture left behind by another suite would silently
     * move this one's boundaries.
     */
    @BeforeEach
    @AfterEach
    void clear() {
        jdbc.update("DELETE FROM " + usageTable + " WHERE user_id = ?", USER);
        jdbc.update("DELETE FROM " + conversationTable + " WHERE user_id = ?", USER);
        jdbc.update("DELETE FROM " + profileTable + " WHERE user_id = ?", USER);
        jdbc.update("DELETE FROM " + gameweekTable);
    }

    /**
     * A whole season: 38 weekly deadlines from the one given. Whole, because a window is only cut
     * from a season whose events are a clean 1..38 — and weekly rather than named individually so
     * that a season seeded far enough in the past is entirely in the past, which is what makes
     * the off-season gap reachable.
     */
    private void seedSeason(String season, Instant firstDeadline) {
        Instant captured = Instant.now();
        for (int index = 0; index < 38; index++) {
            jdbc.update("INSERT INTO " + gameweekTable
                    + " (season, id, name, deadline_time, captured_at) VALUES (?, ?, ?, ?, ?)",
                    season, index + 1, "Gameweek " + (index + 1),
                    Timestamp.from(firstDeadline.plus(Duration.ofDays(7L * index))),
                    Timestamp.from(captured));
        }
    }

    /** GW1 two days ago, GW2 in five days: the window under test is the run-up to GW2. */
    private void seedSeasonUnderway() {
        seedSeason("2097/98", Instant.now().minus(Duration.ofDays(2)));
    }

    /** Every deadline in the past, so there is no round left to allow messages for. */
    private void seedFinishedSeason() {
        seedSeason("2096/97", Instant.now().minus(Duration.ofDays(300)));
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

    /**
     * The window opened at the last deadline, so what a user spent planning the previous round is
     * not charged against this one. This is the whole point of the change: a rolling week never
     * starts over.
     */
    @Test
    void messagesSentBeforeTheWindowOpenedAreNotCounted() {
        seedSeasonUnderway();
        seedEvents(3, Duration.ofDays(3), UsageService.CHAT_EVENT);
        seedEvents(2, Duration.ofHours(6), UsageService.CHAT_EVENT);

        UsageService.Quota quota = usage.quotaOf(USER);

        assertThat(quota.window().kind()).isEqualTo(QuotaWindow.Kind.GAMEWEEK);
        assertThat(quota.window().gameweek()).isEqualTo(2);
        assertThat(quota.used()).isEqualTo(2);
        assertThat(quota.limit()).isEqualTo(freeLimit);
        assertThat(quota.resetsAt()).isEqualTo(quota.window().until());
    }

    /** A fixed window has one reset instant, which is also when a blocked caller may return. */
    @Test
    void anExhaustedGameweekWindowPointsAtItsOwnDeadline() {
        seedSeasonUnderway();
        setLimit(1);
        seedEvents(1, Duration.ofHours(6), UsageService.CHAT_EVENT);

        UsageService.Quota quota = usage.quotaOf(USER);

        assertThat(quota.exceeded()).isTrue();
        assertThat(quota.nextFreeAt()).isEqualTo(quota.window().until());
        assertThat(quota.retryAfterSeconds()).hasValueSatisfying(seconds ->
                assertThat(seconds).isBetween(Duration.ofDays(4).toSeconds(),
                        Duration.ofDays(5).toSeconds()));
    }

    /**
     * Between two seasons there is no round to allow messages for, so the gap is one window of
     * its own — and here it has no upper bound at all, because the new season is not captured.
     */
    @Test
    void theOffSeasonGapCarriesItsOwnLimit() {
        seedFinishedSeason();
        seedEvents(1, Duration.ofDays(3), UsageService.CHAT_EVENT);

        UsageService.Quota quota = usage.quotaOf(USER);

        assertThat(quota.window().kind()).isEqualTo(QuotaWindow.Kind.OFF_SEASON);
        assertThat(quota.limit()).isEqualTo(offSeasonLimit);
        assertThat(quota.used()).isEqualTo(1);
        assertThat(quota.resetsAt()).as("nothing captured ends this window yet").isNull();
    }

    @Test
    void aPerUserOverrideReplacesTheWindowsOwnLimit() {
        seedFinishedSeason();
        setLimit(5);

        UsageService.Quota quota = usage.quotaOf(USER);

        assertThat(quota.window().kind()).isEqualTo(QuotaWindow.Kind.OFF_SEASON);
        assertThat(quota.limit())
                .as("an override replaces the window's number, whichever window it is")
                .isEqualTo(5);
    }

    /**
     * Without a usable capture the count is the rolling seven days it always was — not fail-open,
     * which a broken capture would turn into uncapped spend, and not fail-closed, which one
     * missing capture would turn into an outage.
     */
    @Test
    void withNoCaptureTheRollingWindowStillApplies() {
        seedEvents(freeLimit, Duration.ofDays(1), UsageService.CHAT_EVENT);

        UsageService.Quota quota = usage.quotaOf(USER);

        assertThat(quota.window().kind()).isEqualTo(QuotaWindow.Kind.FALLBACK);
        assertThat(quota.exceeded()).isTrue();
        assertThat(quota.resetsAt()).as("a rolling window never resets").isNull();
        assertThat(quota.nextFreeAt()).isNotNull();
    }

    /** A half-written season would put a boundary in the wrong place, so it is not used at all. */
    @Test
    void anIncompleteSeasonFallsBackRatherThanGuessing() {
        seedSeasonUnderway();
        jdbc.update("DELETE FROM " + gameweekTable + " WHERE season = ? AND id = ?", "2097/98", 30);

        assertThat(usage.quotaOf(USER).window().kind()).isEqualTo(QuotaWindow.Kind.FALLBACK);
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

    /**
     * The refusal names the number on the free plan. Hiding it would make the product evasive
     * about the one figure a free user needs, and it is on the marketing page either way — while
     * a suspended account is told nothing about limits, because no number is the reason.
     */
    @Test
    void theFreePlansRefusalNamesTheLimitAndASuspendedAccountsDoesNot() {
        setLimit(1);
        usage.claimChatTurn(USER, "anthropic");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> usage.claimChatTurn(USER, "anthropic"))
                .hasMessage("You have used all 1 of this week's messages.");

        setLimit(0);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> usage.claimChatTurn(USER, "anthropic"))
                .hasMessage("This account cannot send messages.");
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
    void runningAggregatesUpdateTheOneClaimToItsFinalTotal() {
        long eventId = usage.claimChatTurn(USER, "anthropic");
        usage.recordTokens(eventId, "claude-sonnet-5", 400, 300, 100, 80);
        usage.recordTokens(eventId, "claude-sonnet-5", 800, 600, 250, 180);
        usage.recordTokens(eventId, "claude-sonnet-5", 1200, 900, 400, 300);

        assertThat(usage.quotaOf(USER).used()).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + usageTable
                + " WHERE user_id = ?", Integer.class, USER)).isOne();
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
