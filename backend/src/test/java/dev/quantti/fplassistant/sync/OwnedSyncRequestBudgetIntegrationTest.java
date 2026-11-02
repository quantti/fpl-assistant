package dev.quantti.fplassistant.sync;

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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import javax.sql.DataSource;

import dev.quantti.fplassistant.sync.OwnedSyncDbOperations.Outcome;
import dev.quantti.fplassistant.sync.OwnedSyncDbOperations.Uncertain;
import dev.quantti.fplassistant.sync.OwnedSyncDbOperations.Uncertainty;
import dev.quantti.fplassistant.sync.OwnedSyncRequestBudget.Admission;
import dev.quantti.fplassistant.sync.OwnedSyncRequestBudget.Allowed;
import dev.quantti.fplassistant.sync.OwnedSyncRequestBudget.Completion;
import dev.quantti.fplassistant.sync.OwnedSyncRequestBudget.Deferred;
import dev.quantti.fplassistant.sync.OwnedSyncRequestBudget.DeferredReason;
import dev.quantti.fplassistant.sync.OwnedSyncRequestBudget.Limits;
import dev.quantti.fplassistant.sync.OwnedSyncRequestBudget.Permit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void reservesMandatoryCapacityAndWaitsForEnoughRollingExpirations() {
        var limits = Limits.defaults(6000);
        budget = new OwnedSyncRequestBudget(jdbc, tx, clock, TABLES, limits);
        Instant start = clock.instant().minus(Duration.ofHours(23));
        jdbc.update("INSERT INTO " + TABLES.requestAttempt()
                + "(permit_id, admitted_at, permit_expires_at, completed_at) "
                + "SELECT md5(i::text)::uuid, ?::timestamptz+i*interval '1 second', "
                + "?::timestamptz+i*interval '1 second'+interval '2 minutes', "
                + "?::timestamptz+i*interval '1 second' FROM generate_series(1,4000) i",
                Timestamp.from(start), Timestamp.from(start), Timestamp.from(start));
        var live = new OwnedSyncRequestBudget.Demand(OwnedSyncRequestBudget.Purpose.LIVE, 670);
        Deferred wait = deferred(budget.admit(UUID.randomUUID(), live));
        assertThat(wait.nextDue()).isEqualTo(start.plusSeconds(395).plus(Duration.ofHours(24)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.requestAttempt(), Integer.class))
                .isEqualTo(4000);
        var post = new OwnedSyncRequestBudget.Demand(OwnedSyncRequestBudget.Purpose.POST_MATCH, 670);
        Permit permit = allowed(budget.admit(UUID.randomUUID(), post));
        budget.complete(permit, Completion.success());
        clock.advance(Duration.ofSeconds(1));
        assertThat(new OwnedSyncRequestBudget(jdbc, tx, clock, TABLES, limits)
                .admit(UUID.randomUUID(), live)).isInstanceOf(Deferred.class);
        clock.advance(Duration.between(clock.instant(), start.plusSeconds(396).plus(Duration.ofHours(24))));
        assertThat(budget.admit(UUID.randomUUID(), live)).isInstanceOf(Allowed.class);
    }

    @Test
    void admissionCeilingsReserveDiscoveryThenFinalThenPostMatch() {
        assertThat(new OwnedSyncRequestBudget.Demand(OwnedSyncRequestBudget.Purpose.LIVE, 670)
                .ceiling(6000)).isEqualTo(3606);
        assertThat(new OwnedSyncRequestBudget.Demand(OwnedSyncRequestBudget.Purpose.POST_MATCH, 670)
                .ceiling(6000)).isEqualTo(5074);
        assertThat(new OwnedSyncRequestBudget.Demand(OwnedSyncRequestBudget.Purpose.FINAL, 670)
                .ceiling(6000)).isEqualTo(5872);
        assertThat(OwnedSyncRequestBudget.Demand.DISCOVERY.ceiling(6000)).isEqualTo(6000);
        assertThat(new OwnedSyncRequestBudget.Demand(OwnedSyncRequestBudget.Purpose.LIVE, 2000)
                .ceiling(6000)).isZero();
        assertThat(new OwnedSyncRequestBudget.Demand(OwnedSyncRequestBudget.Purpose.POST_MATCH, 670)
                .ceiling(20)).isZero();
        budget = new OwnedSyncRequestBudget(jdbc, tx, clock, TABLES, Limits.defaults(20));
        assertThat(deferred(budget.admit(UUID.randomUUID(),
                new OwnedSyncRequestBudget.Demand(OwnedSyncRequestBudget.Purpose.POST_MATCH, 670))).reason())
                .isEqualTo(DeferredReason.CAPACITY_RESERVED);
        assertThat(budget.admit()).isInstanceOf(Allowed.class);
    }

    @Test
    void oneSecondBoundaryIsGlobalAcrossInstancesAndLocked() throws Exception {
        Permit first = allowed(budget.admit());
        budget.complete(first, Completion.success());
        OwnedSyncRequestBudget reopened = new OwnedSyncRequestBudget(jdbc, tx, clock, TABLES);
        Deferred early = deferred(reopened.admit());
        assertThat(early.reason()).isEqualTo(DeferredReason.MINIMUM_SPACING);
        assertThat(early.nextDue()).isEqualTo(first.admittedAt().plusSeconds(1));
        assertThat(reopened.nextAdmissionHint()).isEmpty();

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
    void completedAttemptSpacingUsesCompletionTimeAfterRestart() {
        Permit permit = allowed(budget.admit());
        clock.advance(Duration.ofMillis(800));
        Instant completedAt = clock.instant();

        budget.complete(permit, Completion.success());
        OwnedSyncRequestBudget reopened = new OwnedSyncRequestBudget(jdbc, tx, clock, TABLES);

        Instant spacingDue = completedAt.plusSeconds(1);
        Deferred blocked = deferred(reopened.admit());
        assertThat(blocked.reason()).isEqualTo(DeferredReason.MINIMUM_SPACING);
        assertThat(blocked.nextDue()).isEqualTo(spacingDue);
        assertThat(reopened.nextAdmissionHint()).isEmpty();

        clock.advance(Duration.ofMillis(200));
        Deferred admissionSpacingExpired = deferred(reopened.admit());
        assertThat(admissionSpacingExpired.reason()).isEqualTo(DeferredReason.MINIMUM_SPACING);
        assertThat(admissionSpacingExpired.nextDue()).isEqualTo(spacingDue);

        clock.advance(Duration.ofMillis(800));
        assertThat(reopened.admit()).isInstanceOf(Allowed.class);
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
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void timedOutAdmissionCanCommitOnlyUnresolvedPreallocatedAttemptAndBlocksAfterRestart() throws Exception {
        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        var lockOwner = Executors.newSingleThreadExecutor();
        Future<?> lockTransaction = lockOwner.submit(() -> holdRequestStateLock(lockAcquired, releaseLock));
        OwnedSyncDbOperations operations = new OwnedSyncDbOperations();
        CountDownLatch operationStarted = new CountDownLatch(1);
        CountDownLatch operationFinished = new CountDownLatch(1);
        UUID attemptId = UUID.randomUUID();
        try {
            assertThat(lockAcquired.await(1, TimeUnit.SECONDS)).isTrue();
            Outcome<Admission> outcome = operations.call(() -> {
                operationStarted.countDown();
                try {
                    return budget.admit(attemptId);
                } finally {
                    operationFinished.countDown();
                }
            }, OwnedSyncDeadline.after(Duration.ofMillis(250)));

            assertThat(operationStarted.getCount()).isZero();
            assertThat(outcome).isInstanceOfSatisfying(Uncertain.class,
                    result -> assertThat(result.reason()).isEqualTo(Uncertainty.TIMED_OUT));
            assertThat(awaitBudgetLockWait("SELECT last_admitted_at, cooldown_until FROM "
                    + TABLES.requestState())).isTrue();
            assertThat(attemptExists(attemptId)).isFalse();

            releaseLock.countDown();
            assertThat(operationFinished.await(2, TimeUnit.SECONDS)).isTrue();
            lockTransaction.get(2, TimeUnit.SECONDS);
            assertThat(attemptExists(attemptId)).isTrue();
            assertThat(unresolvedAttempts()).isEqualTo(1);

            OwnedSyncRequestBudget reopened = new OwnedSyncRequestBudget(jdbc, tx, clock, TABLES);
            Deferred blocked = deferred(reopened.admit());
            assertThat(blocked.reason()).isEqualTo(DeferredReason.UNSETTLED);
        } finally {
            releaseLock.countDown();
            operations.close();
            try {
                assertThat(operationFinished.await(2, TimeUnit.SECONDS)).isTrue();
                lockTransaction.get(2, TimeUnit.SECONDS);
            } finally {
                lockOwner.shutdownNow();
            }
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void timedOutSettlementIsUnresolvedUntilAtomicCompletionAndCooldownCommit() throws Exception {
        Permit permit = allowed(budget.admit());
        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        var lockOwner = Executors.newSingleThreadExecutor();
        Future<?> lockTransaction = lockOwner.submit(() -> holdRequestStateLock(lockAcquired, releaseLock));
        OwnedSyncDbOperations operations = new OwnedSyncDbOperations();
        CountDownLatch operationStarted = new CountDownLatch(1);
        CountDownLatch operationFinished = new CountDownLatch(1);
        try {
            assertThat(lockAcquired.await(1, TimeUnit.SECONDS)).isTrue();
            Outcome<OwnedSyncRequestBudget.Settlement> outcome = operations.call(() -> {
                operationStarted.countDown();
                try {
                    return budget.complete(permit, Completion.http(429, "7200", 1));
                } finally {
                    operationFinished.countDown();
                }
            }, OwnedSyncDeadline.after(Duration.ofMillis(250)));

            assertThat(operationStarted.getCount()).isZero();
            assertThat(outcome).isInstanceOfSatisfying(Uncertain.class,
                    result -> assertThat(result.reason()).isEqualTo(Uncertainty.TIMED_OUT));
            assertThat(awaitBudgetLockWait("SELECT singleton FROM " + TABLES.requestState())).isTrue();
            assertThat(attemptCompletedAt(permit.id())).isNull();
            assertThat(budget.nextAdmissionHint()).isEmpty();

            releaseLock.countDown();
            assertThat(operationFinished.await(2, TimeUnit.SECONDS)).isTrue();
            lockTransaction.get(2, TimeUnit.SECONDS);
            assertThat(attemptCompletedAt(permit.id())).isEqualTo(clock.instant());
            Instant expectedCooldown = clock.instant().plusSeconds(7_200);
            assertThat(budget.nextAdmissionHint()).contains(expectedCooldown);
            Deferred cooldown = deferred(budget.admit());
            assertThat(cooldown.reason()).isEqualTo(DeferredReason.COOLDOWN);
            assertThat(cooldown.nextDue()).isEqualTo(expectedCooldown);
        } finally {
            releaseLock.countDown();
            operations.close();
            try {
                assertThat(operationFinished.await(2, TimeUnit.SECONDS)).isTrue();
                lockTransaction.get(2, TimeUnit.SECONDS);
            } finally {
                lockOwner.shutdownNow();
            }
        }
    }

    @Test
    void duplicateCompletionReturnsCommittedCooldownAndMissingPermitIsInvariantFailure() {
        Permit permit = allowed(budget.admit());
        Instant expectedCooldown = clock.instant().plus(Duration.ofDays(2));

        OwnedSyncRequestBudget.Settlement first = budget.complete(permit,
                Completion.http(429, "172800", 1));
        OwnedSyncRequestBudget.Settlement repeated = budget.complete(permit, Completion.success());

        assertThat(first.committedCooldownUntil()).contains(expectedCooldown);
        assertThat(repeated.committedCooldownUntil()).contains(expectedCooldown);
        assertThat(budget.nextAdmissionHint()).contains(expectedCooldown);

        Permit missing = new Permit(UUID.randomUUID(), clock.instant(), clock.instant().plusSeconds(1));
        assertThatThrownBy(() -> budget.complete(missing, Completion.success()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing owned-sync permit");
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
    void defaultAttemptLimitAndImporterCeilingRemainBounded() {
        assertThat(Limits.defaults().maxAttempts()).isEqualTo(5_000);
        assertThat(limits(5_000, Duration.ofHours(24), Duration.ofSeconds(5)).maxAttempts()).isEqualTo(5_000);
        assertThatThrownBy(() -> limits(6_001, Duration.ofHours(24), Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 6000");
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

    private static Void holdRequestStateLock(CountDownLatch acquired, CountDownLatch release) {
        return tx.execute(status -> {
            jdbc.queryForObject("SELECT singleton FROM " + TABLES.requestState()
                    + " WHERE singleton = TRUE FOR UPDATE", Boolean.class);
            acquired.countDown();
            try {
                if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("lock release timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
            return null;
        });
    }

    private static boolean awaitBudgetLockWait(String queryPrefix) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            Boolean waiting = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM pg_stat_activity "
                    + "WHERE pid <> pg_backend_pid() AND datname = current_database() "
                    + "AND wait_event_type = 'Lock' AND query LIKE ?)", Boolean.class, queryPrefix + "%");
            if (Boolean.TRUE.equals(waiting)) return true;
            LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
        }
        return false;
    }

    private boolean attemptExists(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM "
                + TABLES.requestAttempt() + " WHERE permit_id = ?)", Boolean.class, id));
    }

    private Instant attemptCompletedAt(UUID id) {
        Timestamp completed = jdbc.queryForObject("SELECT completed_at FROM " + TABLES.requestAttempt()
                + " WHERE permit_id = ?", Timestamp.class, id);
        return completed == null ? null : completed.toInstant();
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
