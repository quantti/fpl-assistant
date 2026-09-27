package fi.karivanttinen.fplassistant.sync;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.sql.DataSource;

import fi.karivanttinen.fplassistant.sync.OwnedSyncRequestBudget.Allowed;
import fi.karivanttinen.fplassistant.sync.OwnedSyncRequestBudget.Completion;
import fi.karivanttinen.fplassistant.sync.OwnedSyncRequestBudget.Deferred;
import fi.karivanttinen.fplassistant.sync.OwnedSyncRequestBudget.DeferredReason;
import fi.karivanttinen.fplassistant.sync.OwnedSyncRequestBudget.Limits;
import fi.karivanttinen.fplassistant.sync.OwnedSyncRequestBudget.Permit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class OwnedSyncRequestBudgetIntegrationTest {

    private static final OwnedSyncTables TABLES = OwnedSyncTables.tests();
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;

    private MutableClock clock;
    private OwnedSyncRequestBudget budget;

    @BeforeAll
    static void initializeDatabase() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                requiredEnv("DATABASE_URL"), requiredEnv("DB_USER"), requiredEnv("DB_PASSWORD"));
        refuseProductionBeforeAnyWrite(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        new OwnedSyncSchema(jdbc, TABLES).initialize();
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM " + TABLES.requestAttempt());
        jdbc.update("UPDATE " + TABLES.requestState() + " SET last_admitted_at = NULL, cooldown_until = NULL");
        clock = new MutableClock(Instant.parse("2097-08-15T12:00:00Z"));
        budget = new OwnedSyncRequestBudget(jdbc, tx, clock, TABLES);
    }

    @Test
    void oneSecondBoundaryIsGlobalAcrossInstancesAndLocked() throws Exception {
        Permit first = allowed(budget.admit());
        budget.complete(first, Completion.success());
        OwnedSyncRequestBudget reopened = new OwnedSyncRequestBudget(jdbc, tx, clock, TABLES);
        Deferred early = deferred(reopened.admit());
        assertThat(early.reason()).isEqualTo(DeferredReason.MINIMUM_SPACING);
        assertThat(early.nextDue()).isEqualTo(first.admittedAt().plusSeconds(1));

        clock.advance(Duration.ofSeconds(1));
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> left = executor.submit(() -> awaitAndAdmit(start, budget));
            Future<?> right = executor.submit(() -> awaitAndAdmit(start, reopened));
            start.countDown();
            Object a = left.get();
            Object b = right.get();
            assertThat((a instanceof Allowed ? 1 : 0) + (b instanceof Allowed ? 1 : 0)).isEqualTo(1);
            assertThat((a instanceof Deferred ? 1 : 0) + (b instanceof Deferred ? 1 : 0)).isEqualTo(1);
            Object denied = a instanceof Deferred ? a : b;
            assertThat(((Deferred) denied).reason()).isEqualTo(DeferredReason.UNSETTLED);
        }
    }

    @Test
    void databaseRecoveryBeforePermitTtlRemainsUnsettledAcrossRestart() {
        Limits limits = limits(20, Duration.ofHours(24), Duration.ofSeconds(10));
        budget = new OwnedSyncRequestBudget(jdbc, tx, clock, TABLES, limits);
        Permit admitted = allowed(budget.admit());
        clock.advance(Duration.ofSeconds(2));

        OwnedSyncRequestBudget reopened = new OwnedSyncRequestBudget(jdbc, tx, clock, TABLES, limits);
        Deferred unsettled = deferred(reopened.admit());
        assertThat(admitted.expiresAt()).isAfter(clock.instant());
        assertThat(unsettled.reason()).isEqualTo(DeferredReason.UNSETTLED);
        assertThat(unsettled.nextDue()).isEqualTo(clock.instant().plusSeconds(30));
        assertThat(unresolvedAttempts()).isEqualTo(1);
    }

    @Test
    void databaseRecoveryAfterPermitTtlDoesNotClearUnknownAttempt() {
        Limits limits = limits(20, Duration.ofHours(24), Duration.ofSeconds(3));
        budget = new OwnedSyncRequestBudget(jdbc, tx, clock, TABLES, limits);
        Permit admitted = allowed(budget.admit());
        clock.advance(Duration.between(clock.instant(), admitted.expiresAt().plusSeconds(1)));

        OwnedSyncRequestBudget reopened = new OwnedSyncRequestBudget(jdbc, tx, clock, TABLES, limits);
        Deferred unsettled = deferred(reopened.admit());
        assertThat(unsettled.reason()).isEqualTo(DeferredReason.UNSETTLED);
        assertThat(unsettled.nextDue()).isEqualTo(clock.instant().plusSeconds(30));
        assertThat(unresolvedAttempts()).isEqualTo(1);
    }

    @Test
    void unresolvedAttemptOlderThanRetentionRemainsAndStillBlocksAdmission() {
        Instant old = clock.instant().minus(Duration.ofHours(25));
        insertAttempt(UUID.randomUUID(), old, old.plusSeconds(3), null);

        Deferred unsettled = deferred(budget.admit());
        assertThat(unsettled.reason()).isEqualTo(DeferredReason.UNSETTLED);
        assertThat(unsettled.nextDue()).isEqualTo(clock.instant().plusSeconds(30));
        assertThat(unresolvedAttempts()).isEqualTo(1);
    }

    @Test
    void twoCompletedButHeldPermitsStillCountTowardLiveCap() {
        Limits limits = limits(20, Duration.ofHours(24), Duration.ofSeconds(20));
        budget = new OwnedSyncRequestBudget(jdbc, tx, clock, TABLES, limits);
        Permit first = allowed(budget.admit());
        budget.complete(first, Completion.success().holdingPermitUntilExpiry());
        clock.advance(Duration.ofSeconds(1));
        Permit second = allowed(budget.admit());
        budget.complete(second, Completion.success().holdingPermitUntilExpiry());
        clock.advance(Duration.ofSeconds(1));

        Deferred full = deferred(budget.admit());
        assertThat(full.reason()).isEqualTo(DeferredReason.LIVE_PERMITS);
        assertThat(full.nextDue()).isEqualTo(first.expiresAt());

        clock.advance(Duration.between(clock.instant(), first.expiresAt()));
        assertThat(budget.admit()).isInstanceOf(Allowed.class);
    }

    @Test
    void rollingAttemptLimitExpiresAtExactWindowWithoutRestartReset() {
        Limits limits = limits(2, Duration.ofHours(24), Duration.ofSeconds(5));
        budget = new OwnedSyncRequestBudget(jdbc, tx, clock, TABLES, limits);
        Instant firstAt = clock.instant();
        budget.complete(allowed(budget.admit()), Completion.success());
        clock.advance(Duration.ofSeconds(1));
        budget.complete(allowed(budget.admit()), Completion.success());

        OwnedSyncRequestBudget reopened = new OwnedSyncRequestBudget(jdbc, tx, clock, TABLES, limits);
        Deferred exhausted = deferred(reopened.admit());
        assertThat(exhausted.reason()).isEqualTo(DeferredReason.ATTEMPT_LIMIT);
        assertThat(exhausted.nextDue()).isEqualTo(firstAt.plus(Duration.ofHours(24)));

        clock.advance(Duration.between(clock.instant(), firstAt.plus(Duration.ofHours(24))));
        assertThat(reopened.admit()).isInstanceOf(Allowed.class);
    }

    @Test
    void retryAfterDeltaAndRfcDateAreDurableAndNeverCapped() {
        Permit delta = allowed(budget.admit());
        budget.complete(delta, Completion.http(429, "172800", 1));
        OwnedSyncRequestBudget reopened = new OwnedSyncRequestBudget(jdbc, tx, clock, TABLES);
        Deferred deltaCooldown = deferred(reopened.admit());
        assertThat(deltaCooldown.reason()).isEqualTo(DeferredReason.COOLDOWN);
        assertThat(deltaCooldown.nextDue()).isEqualTo(clock.instant().plus(Duration.ofDays(2)));

        clock.advance(Duration.ofDays(2));
        Permit date = allowed(reopened.admit());
        String retryDate = ZonedDateTime.ofInstant(clock.instant().plus(Duration.ofDays(2)), ZoneOffset.UTC)
                .format(DateTimeFormatter.RFC_1123_DATE_TIME);
        reopened.complete(date, Completion.http(503, retryDate, 1));
        assertThat(deferred(budget.admit()).nextDue()).isEqualTo(clock.instant().plus(Duration.ofDays(2)));
    }

    @Test
    void invalidAndOverflowingRetryAfterFallBackConservativelyAndOrdinary4xxDoesNotBackoff() {
        Permit invalid = allowed(budget.admit());
        budget.complete(invalid, Completion.http(429, "not a date", 3));
        Deferred fallback = deferred(budget.admit());
        assertThat(fallback.nextDue()).isBetween(clock.instant().plusSeconds(8), clock.instant().plusSeconds(10));

        clock.advance(Duration.between(clock.instant(), fallback.nextDue()));
        Permit numericOverflow = allowed(budget.admit());
        budget.complete(numericOverflow, Completion.http(429, "9223372036854775808", 3));
        Deferred overflowFallback = deferred(budget.admit());
        assertThat(overflowFallback.nextDue())
                .isBetween(clock.instant().plusSeconds(8), clock.instant().plusSeconds(10));

        clock.advance(Duration.between(clock.instant(), overflowFallback.nextDue()));
        Permit instantOverflow = allowed(budget.admit());
        budget.complete(instantOverflow, Completion.http(429, Long.toString(Long.MAX_VALUE), 3));
        Deferred instantOverflowFallback = deferred(budget.admit());
        assertThat(instantOverflowFallback.nextDue())
                .isBetween(clock.instant().plusSeconds(8), clock.instant().plusSeconds(10));

        clock.advance(Duration.between(clock.instant(), instantOverflowFallback.nextDue()));
        Permit databaseOverflow = allowed(budget.admit());
        budget.complete(databaseOverflow, Completion.http(429, "10000000000000", 3));
        Deferred databaseOverflowFallback = deferred(budget.admit());
        assertThat(databaseOverflowFallback.nextDue())
                .isBetween(clock.instant().plusSeconds(8), clock.instant().plusSeconds(10));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.requestAttempt()
                + " WHERE permit_id = ? AND completed_at IS NOT NULL", Integer.class, databaseOverflow.id()))
                .isEqualTo(1);

        clock.advance(Duration.between(clock.instant(), databaseOverflowFallback.nextDue()));
        Permit badPayload = allowed(budget.admit());
        budget.complete(badPayload, Completion.http(422, "600", 1));
        clock.advance(Duration.ofSeconds(1));
        assertThat(budget.admit()).isInstanceOf(Allowed.class);
    }

    @Test
    void timeoutBackoffIsBoundedAndLateCompletionCannotLowerCooldown() {
        Permit old = allowed(budget.admit());
        budget.complete(old, Completion.timeout(1));
        Instant shortCooldown = deferred(budget.admit()).nextDue();
        assertThat(shortCooldown).isBetween(clock.instant().plusSeconds(2), clock.instant().plusMillis(2_500));

        clock.advance(Duration.between(clock.instant(), shortCooldown));
        Permit newer = allowed(budget.admit());
        budget.complete(newer, Completion.timeout(30));
        Instant longCooldown = deferred(budget.admit()).nextDue();
        assertThat(longCooldown).isEqualTo(clock.instant().plus(Duration.ofMinutes(30)));

        budget.complete(old, Completion.timeout(1));
        assertThat(deferred(budget.admit()).nextDue()).isEqualTo(longCooldown);
    }

    @Test
    void lateAndRepeatedCompletionCannotAffectLaterPermits() {
        Limits limits = limits(20, Duration.ofHours(24), Duration.ofSeconds(5));
        budget = new OwnedSyncRequestBudget(jdbc, tx, clock, TABLES, limits);
        Permit earlier = allowed(budget.admit());
        budget.complete(earlier, Completion.success());
        clock.advance(Duration.ofSeconds(5));
        Permit current = allowed(budget.admit());

        budget.complete(earlier, Completion.success());
        budget.complete(earlier, Completion.timeout(1));
        budget.complete(current, Completion.success());
        clock.advance(Duration.ofSeconds(1));
        Permit later = allowed(budget.admit());
        assertThat(current.id()).isNotEqualTo(later.id());
    }

    @Test
    void purgeRemovesOnlyOldCompletedAttempts() {
        Instant old = clock.instant().minus(Duration.ofHours(25));
        insertAttempt(UUID.randomUUID(), old, old.plusSeconds(10), old.plusSeconds(5));
        Instant recent = clock.instant().minus(Duration.ofHours(23));
        insertAttempt(UUID.randomUUID(), recent, recent.plusSeconds(10), recent.plusSeconds(5));

        assertThat(budget.admit()).isInstanceOf(Allowed.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.requestAttempt(), Integer.class))
                .isEqualTo(2); // recent completed + newly admitted
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.requestAttempt()
                + " WHERE admitted_at < ?", Integer.class, Timestamp.from(clock.instant().minus(Duration.ofHours(24)))))
                .isZero();
    }

    private int unresolvedAttempts() {
        return jdbc.queryForObject("SELECT count(*) FROM " + TABLES.requestAttempt()
                + " WHERE completed_at IS NULL", Integer.class);
    }

    private void insertAttempt(UUID id, Instant admitted, Instant expires, Instant completed) {
        jdbc.update("INSERT INTO " + TABLES.requestAttempt()
                        + "(permit_id, admitted_at, permit_expires_at, completed_at) VALUES (?, ?, ?, ?)",
                id, Timestamp.from(admitted), Timestamp.from(expires),
                completed == null ? null : Timestamp.from(completed));
    }

    private static Limits limits(int attempts, Duration window, Duration ttl) {
        return new Limits(Duration.ofSeconds(1), 2, attempts, window, ttl,
                Duration.ofSeconds(2), Duration.ofMinutes(30));
    }

    private static Object awaitAndAdmit(CountDownLatch start, OwnedSyncRequestBudget target) {
        try {
            start.await();
            return target.admit();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static Permit allowed(Object admission) {
        assertThat(admission).isInstanceOf(Allowed.class);
        return ((Allowed) admission).permit();
    }

    private static Deferred deferred(Object admission) {
        assertThat(admission).isInstanceOf(Deferred.class);
        return (Deferred) admission;
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private static void refuseProductionBeforeAnyWrite(DataSource dataSource) {
        JdbcTemplate probe = new JdbcTemplate(dataSource);
        String manager = probe.queryForObject("SELECT to_regclass('public.manager')::text", String.class);
        if (manager != null) throw new IllegalStateException("Refusing owned-sync integration test writes to shared DB");
        assertThat(TABLES.requestState()).endsWith("_test");
        assertThat(TABLES.requestAttempt()).endsWith("_test");
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private MutableClock(Instant instant) { this.instant = instant; }
        void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
