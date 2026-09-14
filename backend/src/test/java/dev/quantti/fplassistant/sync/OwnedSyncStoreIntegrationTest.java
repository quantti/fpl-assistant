package dev.quantti.fplassistant.sync;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

import dev.quantti.fplassistant.sync.OwnedSyncStore.Endpoint;
import dev.quantti.fplassistant.sync.OwnedSyncStore.EndpointKind;
import dev.quantti.fplassistant.sync.OwnedSyncStore.Lease;
import dev.quantti.fplassistant.sync.OwnedSyncStore.LeaseLostException;
import dev.quantti.fplassistant.sync.OwnedSyncStore.PayloadRetentionLimitException;
import dev.quantti.fplassistant.sync.OwnedSyncStore.Run;
import dev.quantti.fplassistant.sync.OwnedSyncStore.SourceRead;
import dev.quantti.fplassistant.sync.OwnedSyncStore.StaleGenerationException;
import dev.quantti.fplassistant.sync.OwnedSyncStore.SyncKind;
import dev.quantti.fplassistant.sync.OwnedSyncStore.Validation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OwnedSyncStoreIntegrationTest {

    private static final OwnedSyncTables TABLES = OwnedSyncTables.tests();
    private static final String PROBE = "fpl_sync_publication_probe_test";
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;

    private MutableClock clock;
    private OwnedSyncStore store;

    @BeforeAll
    static void initializeDatabase() {
        String url = requiredEnv("DATABASE_URL");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                url, requiredEnv("DB_USER"), requiredEnv("DB_PASSWORD"));
        refuseProductionBeforeAnyWrite(dataSource);

        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        new OwnedSyncSchema(jdbc, TABLES).initialize();
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + PROBE
                + " (publication_key VARCHAR(200) PRIMARY KEY, value INTEGER NOT NULL)");
        jdbc.execute("ALTER TABLE " + PROBE + " ENABLE ROW LEVEL SECURITY");
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM " + PROBE);
        jdbc.update("DELETE FROM " + TABLES.payload());
        jdbc.update("DELETE FROM " + TABLES.run());
        jdbc.update("DELETE FROM " + TABLES.receipt());
        clock = new MutableClock(Instant.parse("2097-08-15T12:00:00Z"));
        store = new OwnedSyncStore(jdbc, tx, clock, TABLES);
    }

    @AfterEach
    void noOpenTransactions() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE pid = pg_backend_pid()",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void coalescesActiveIdenticalWorkAndGenerationsOnlyIncrease() {
        var first = store.enqueue(SyncKind.PLAYER_HISTORY, "2097-98", "all-players", "history", clock.instant());
        var coalesced = store.enqueue(SyncKind.PLAYER_HISTORY, "2097-98", "all-players", "history",
                clock.instant().minusSeconds(30));

        assertThat(coalesced.generation()).isEqualTo(first.generation());
        assertThat(coalesced.dueAt()).isEqualTo(clock.instant().minusSeconds(30));
        Lease lease = store.acquire("worker", Duration.ofMinutes(5)).orElseThrow();
        store.publish(lease, read(), () -> null);

        var next = store.enqueue(SyncKind.PLAYER_HISTORY, "2097-98", "all-players", "history", clock.instant());
        assertThat(next.generation()).isGreaterThan(first.generation());
    }

    @Test
    void sourceDemandFloorAdvancesOnlyForNewCoalescedDemandNotRetries() {
        Run first = store.enqueue(SyncKind.CATALOGUE_SCHEDULE, "2097-98", "catalogue",
                "catalogue", clock.instant().minusSeconds(30));
        Lease lease = store.acquire("worker", Duration.ofMinutes(5)).orElseThrow();
        assertThat(store.requestedSourceAfter(lease)).isEqualTo(clock.instant());

        clock.advance(Duration.ofSeconds(20));
        Run coalesced = store.enqueue(SyncKind.CATALOGUE_SCHEDULE, "2097-98", "catalogue",
                "catalogue", clock.instant().minusSeconds(10));
        assertThat(coalesced.generation()).isEqualTo(first.generation());
        assertThat(store.requestedSourceAfter(lease)).isEqualTo(clock.instant());

        Instant floor = clock.instant();
        store.retry(lease, clock.instant().plus(Duration.ofHours(3)), "cutover");
        assertThat(jdbc.queryForObject("SELECT requested_source_after FROM " + TABLES.run()
                + " WHERE generation=?", java.sql.Timestamp.class, lease.generation()).toInstant())
                .isEqualTo(floor);
    }

    @Test
    void coalescingCollisionWithDifferentPublicationKeyRejectsAndRollsBack() {
        var first = store.enqueue(SyncKind.PLAYER_HISTORY, "2097-98", "all-players", "history:a",
                clock.instant());

        assertThatThrownBy(() -> store.enqueue(SyncKind.PLAYER_HISTORY, "2097-98", "all-players",
                "history:b", clock.instant().minusSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different publicationKey");

        assertThat(jdbc.queryForObject("SELECT publication_key FROM " + TABLES.run()
                + " WHERE generation = ?", String.class, first.generation())).isEqualTo("history:a");
        assertThat(jdbc.queryForObject("SELECT due_at FROM " + TABLES.run()
                + " WHERE generation = ?", java.sql.Timestamp.class, first.generation()).toInstant())
                .isEqualTo(clock.instant());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.receipt()
                + " WHERE season = '2097-98' AND publication_key = 'history:b'", Integer.class)).isZero();
    }

    @Test
    void twoWorkersClaimDifferentRunsInDeterministicPriorityOrder() throws Exception {
        store.enqueue(SyncKind.CATALOGUE_SCHEDULE, "2097-98", "catalogue", "catalogue", clock.instant());
        store.enqueue(SyncKind.FIXTURE_VERIFICATION, "2097-98", "fixture-8", "fixture:8", clock.instant());
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Lease> a = executor.submit(() -> {
                start.await();
                return new OwnedSyncStore(jdbc, tx, clock, TABLES)
                        .acquire("worker-a", Duration.ofMinutes(5)).orElseThrow();
            });
            Future<Lease> b = executor.submit(() -> {
                start.await();
                return new OwnedSyncStore(jdbc, tx, clock, TABLES)
                        .acquire("worker-b", Duration.ofMinutes(5)).orElseThrow();
            });
            start.countDown();
            List<Lease> claims = List.of(a.get(), b.get());

            assertThat(claims).extracting(Lease::generation).doesNotHaveDuplicates();
            assertThat(claims).extracting(Lease::kind)
                    .containsExactlyInAnyOrder(SyncKind.FIXTURE_VERIFICATION, SyncKind.CATALOGUE_SCHEDULE);
        }

        // With one slot, the deterministic ordering itself is directly observable.
        setUp();
        store.enqueue(SyncKind.CATALOGUE_SCHEDULE, "2097-98", "catalogue", "catalogue", clock.instant());
        store.enqueue(SyncKind.FIXTURE_VERIFICATION, "2097-98", "fixture-8", "fixture:8", clock.instant());
        assertThat(store.acquire("worker", Duration.ofMinutes(5)).orElseThrow().kind())
                .isEqualTo(SyncKind.FIXTURE_VERIFICATION);
    }

    @Test
    void renewalExtendsTheSameFenceButAnExpiredTakeoverFencesTheOldWorker() {
        enqueue("fixture-9", "fixture:9");
        Lease old = store.acquire("worker-old", Duration.ofMinutes(2)).orElseThrow();
        clock.advance(Duration.ofMinutes(1));
        Lease renewed = store.renew(old, Duration.ofMinutes(4));
        assertThat(renewed.fence()).isEqualTo(old.fence());
        assertThat(renewed.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(4)));

        clock.advance(Duration.ofMinutes(5));
        Lease takeover = store.acquire("worker-new", Duration.ofMinutes(5)).orElseThrow();
        assertThat(takeover.generation()).isEqualTo(old.generation());
        assertThat(takeover.fence()).isGreaterThan(old.fence());
        assertThat(takeover.token()).isNotEqualTo(old.token());
        assertThatThrownBy(() -> store.stage(old, Endpoint.fixtures(), read(), Validation.valid(), bytes("old")))
                .isInstanceOf(LeaseLostException.class);
        assertThatThrownBy(() -> store.publish(old, read(), () -> {
            throw new AssertionError("stale callback ran");
        })).isInstanceOf(LeaseLostException.class);

        store.stage(takeover, Endpoint.fixtures(), read(), Validation.valid(), bytes("new"));
    }

    @Test
    void expiryWhileWaitingForThePublicationKeyLockPreventsTheCallback() throws Exception {
        enqueue("fixture-lock", "fixture:lock");
        Lease lease = store.acquire("worker", Duration.ofMinutes(1)).orElseThrow();
        CountDownLatch receiptLocked = new CountDownLatch(1);
        CountDownLatch releaseReceipt = new CountDownLatch(1);
        AtomicBoolean callbackRan = new AtomicBoolean();

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> holder = executor.submit(() -> tx.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT publication_key FROM " + TABLES.receipt()
                        + " WHERE season = '2097-98' AND publication_key = 'fixture:lock' FOR UPDATE", String.class);
                receiptLocked.countDown();
                try {
                    releaseReceipt.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }));
            receiptLocked.await();
            Future<?> publisher = executor.submit(() -> store.publish(lease, read(), () -> {
                callbackRan.set(true);
                return null;
            }));
            Thread.sleep(100);
            assertThat(publisher.isDone()).isFalse();
            clock.advance(Duration.ofMinutes(2));
            releaseReceipt.countDown();
            holder.get();

            assertThatThrownBy(publisher::get).hasRootCauseInstanceOf(LeaseLostException.class);
            assertThat(callbackRan).isFalse();
        } finally {
            releaseReceipt.countDown();
        }
    }

    @Test
    void callbackFailureRollsBackPublicationReceiptAndCompletion() {
        enqueue("fixture-10", "fixture:10");
        Lease lease = store.acquire("worker", Duration.ofMinutes(5)).orElseThrow();

        assertThatThrownBy(() -> store.publish(lease, read(), () -> {
            jdbc.update("INSERT INTO " + PROBE + " VALUES ('fixture:10', 1)");
            throw new IllegalStateException("writer failed");
        })).isInstanceOf(IllegalStateException.class).hasMessage("writer failed");

        assertThat(count(PROBE)).isZero();
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.run() + " WHERE generation = ?",
                String.class, lease.generation())).isEqualTo("LEASED");
        assertThat(jdbc.queryForObject("SELECT latest_generation FROM " + TABLES.receipt()
                + " WHERE season = '2097-98' AND publication_key = 'fixture:10'", Long.class)).isNull();

        store.publish(lease, read(), () -> jdbc.update("INSERT INTO " + PROBE + " VALUES ('fixture:10', 2)"));
        assertThat(count(PROBE)).isOne();
    }

    @Test
    void publicationReceiptRejectsAnOlderGenerationForTheSameSeasonAndKey() {
        var older = store.enqueue(SyncKind.FIXTURE_VERIFICATION, "2097-98", "attempt-a", "fixture:11", clock.instant());
        var newer = store.enqueue(SyncKind.FIXTURE_VERIFICATION, "2097-98", "attempt-b", "fixture:11", clock.instant());
        Lease firstClaim = store.acquire("worker-old", Duration.ofMinutes(5)).orElseThrow();
        Lease secondClaim = store.acquire("worker-new", Duration.ofMinutes(5)).orElseThrow();
        Lease oldLease = firstClaim.generation() == older.generation() ? firstClaim : secondClaim;
        Lease newLease = firstClaim.generation() == newer.generation() ? firstClaim : secondClaim;

        store.publish(newLease, read(), () -> jdbc.update("INSERT INTO " + PROBE + " VALUES ('fixture:11', 2)"));
        assertThatThrownBy(() -> store.publish(oldLease, read(), () ->
                jdbc.update("UPDATE " + PROBE + " SET value = 1 WHERE publication_key = 'fixture:11'")))
                .isInstanceOf(StaleGenerationException.class);

        assertThat(jdbc.queryForObject("SELECT value FROM " + PROBE + " WHERE publication_key = 'fixture:11'",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void publicationReceiptsFenceTheSameKeyIndependentlyBySeason() {
        var oldSeason = store.enqueue(SyncKind.FIXTURE_VERIFICATION, "2096-97", "old-season",
                "fixture:seasonal", clock.instant());
        var newSeason = store.enqueue(SyncKind.FIXTURE_VERIFICATION, "2097-98", "new-season",
                "fixture:seasonal", clock.instant());
        Lease first = store.acquire("worker-a", Duration.ofMinutes(5)).orElseThrow();
        Lease second = store.acquire("worker-b", Duration.ofMinutes(5)).orElseThrow();
        Lease oldLease = first.generation() == oldSeason.generation() ? first : second;
        Lease newLease = first.generation() == newSeason.generation() ? first : second;

        store.publish(newLease, read(), () -> null);
        store.publish(oldLease, read(), () -> null);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.receipt()
                + " WHERE publication_key = 'fixture:seasonal'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT latest_generation FROM " + TABLES.receipt()
                + " WHERE season = '2096-97' AND publication_key = 'fixture:seasonal'", Long.class))
                .isEqualTo(oldSeason.generation());
        assertThat(jdbc.queryForObject("SELECT latest_generation FROM " + TABLES.receipt()
                + " WHERE season = '2097-98' AND publication_key = 'fixture:seasonal'", Long.class))
                .isEqualTo(newSeason.generation());
    }

    @Test
    void pendingRunAndPayloadSurviveStoreReopen() {
        enqueue("fixture-12", "fixture:12");
        Lease lease = store.acquire("worker", Duration.ofMinutes(5)).orElseThrow();
        store.stage(lease, Endpoint.eventLive(7), read(), Validation.unchecked(), bytes("{pending"));
        store.retry(lease, clock.instant(), "restart");

        OwnedSyncStore reopened = new OwnedSyncStore(jdbc, tx, clock, TABLES);
        Lease reclaimed = reopened.acquire("after-restart", Duration.ofMinutes(5)).orElseThrow();

        assertThat(reclaimed.generation()).isEqualTo(lease.generation());
        assertThat(count(TABLES.payload())).isOne();
        assertThat(reclaimed.attemptCount()).isEqualTo(2);
    }

    @Test
    void malformedPayloadCanBeRetainedButBodiesAndErrorsAreBounded() {
        enqueue("player-22", "player:22");
        Lease lease = store.acquire("worker", Duration.ofMinutes(5)).orElseThrow();
        String longError = "x".repeat(OwnedSyncStore.MAX_ERROR_CHARS + 50);

        store.stage(lease, Endpoint.elementSummary(22), read(), Validation.invalid(longError), bytes("not-json"));

        assertThat(jdbc.queryForObject("SELECT octet_length(body) FROM " + TABLES.payload(), Integer.class))
                .isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT length(validation_error) FROM " + TABLES.payload(), Integer.class))
                .isEqualTo(OwnedSyncStore.MAX_ERROR_CHARS);
        assertThatThrownBy(() -> store.stage(lease, Endpoint.fixtures(), read(), Validation.invalid("large"),
                new byte[OwnedSyncStore.MAX_PAYLOAD_BYTES + 1])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.stage(lease,
                new Endpoint(EndpointKind.ELEMENT_SUMMARY, null, null), read(), Validation.valid(), bytes("{}")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO %s(run_generation, endpoint_kind, read_started_at, read_finished_at,
                               validation_state, body, staged_at)
                VALUES (?, 'ELEMENT_SUMMARY', ?, ?, 'VALID', ?, ?)
                """.formatted(TABLES.payload()), lease.generation(), java.sql.Timestamp.from(clock.instant()),
                java.sql.Timestamp.from(clock.instant()), bytes("{}"), java.sql.Timestamp.from(clock.instant())))
                .isInstanceOf(DataIntegrityViolationException.class);
        store.retry(lease, clock.instant(), longError);
        assertThat(jdbc.queryForObject("SELECT length(last_error) FROM " + TABLES.run()
                + " WHERE generation = ?", Integer.class, lease.generation()))
                .isEqualTo(OwnedSyncStore.MAX_ERROR_CHARS);
        assertThat(Set.of(EndpointKind.values())).containsExactlyInAnyOrder(
                EndpointKind.BOOTSTRAP_STATIC, EndpointKind.FIXTURES,
                EndpointKind.EVENT_LIVE, EndpointKind.ELEMENT_SUMMARY);
    }

    @Test
    void retainedPayloadLimitIncludesAllRetryAttemptsWithoutDeletingEvidence() {
        enqueue("bounded-retries", "fixture:bounded-retries");
        Lease firstAttempt = store.acquire("worker-a", Duration.ofMinutes(5)).orElseThrow();
        byte[] maximumBody = new byte[OwnedSyncStore.MAX_PAYLOAD_BYTES];
        for (int i = 0; i < 5; i++) {
            store.stage(firstAttempt, Endpoint.fixtures(), read(), Validation.invalid("attempt one"), maximumBody);
        }
        store.retry(firstAttempt, clock.instant(), "retry");

        Lease secondAttempt = store.acquire("worker-b", Duration.ofMinutes(5)).orElseThrow();
        for (int i = 0; i < 5; i++) {
            store.stage(secondAttempt, Endpoint.fixtures(), read(), Validation.invalid("attempt two"), maximumBody);
        }
        assertThatThrownBy(() -> store.stage(secondAttempt, Endpoint.fixtures(), read(), Validation.valid(),
                bytes("overflow"))).isInstanceOf(PayloadRetentionLimitException.class);

        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(octet_length(body)), 0) FROM " + TABLES.payload()
                + " WHERE run_generation = ?", Long.class, secondAttempt.generation()))
                .isEqualTo((long) OwnedSyncStore.MAX_RETAINED_PAYLOAD_BYTES_PER_RUN);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.payload()
                + " WHERE run_generation = ?", Integer.class, secondAttempt.generation())).isEqualTo(10);
    }

    @Test
    void concurrentStagesCannotExceedThePerRunRetainedPayloadLimit() throws Exception {
        enqueue("bounded-concurrent", "fixture:bounded-concurrent");
        Lease lease = store.acquire("worker", Duration.ofMinutes(5)).orElseThrow();
        byte[] maximumBody = new byte[OwnedSyncStore.MAX_PAYLOAD_BYTES];
        for (int i = 0; i < 9; i++) {
            store.stage(lease, Endpoint.fixtures(), read(), Validation.valid(), maximumBody);
        }
        CountDownLatch start = new CountDownLatch(1);

        int successes = 0;
        int refusals = 0;
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> a = executor.submit(() -> {
                start.await();
                return new OwnedSyncStore(jdbc, tx, clock, TABLES)
                        .stage(lease, Endpoint.fixtures(), read(), Validation.valid(), maximumBody);
            });
            Future<?> b = executor.submit(() -> {
                start.await();
                return new OwnedSyncStore(jdbc, tx, clock, TABLES)
                        .stage(lease, Endpoint.fixtures(), read(), Validation.valid(), maximumBody);
            });
            start.countDown();
            for (Future<?> future : List.of(a, b)) {
                try {
                    future.get();
                    successes++;
                } catch (java.util.concurrent.ExecutionException e) {
                    assertThat(e).hasRootCauseInstanceOf(PayloadRetentionLimitException.class);
                    refusals++;
                }
            }
        }

        assertThat(successes).isOne();
        assertThat(refusals).isOne();
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(octet_length(body)), 0) FROM " + TABLES.payload()
                + " WHERE run_generation = ?", Long.class, lease.generation()))
                .isEqualTo((long) OwnedSyncStore.MAX_RETAINED_PAYLOAD_BYTES_PER_RUN);
    }

    @Test
    void futureSourceReadsAreRejectedBeforeStageOrPublication() {
        enqueue("future-read", "fixture:future-read");
        Lease lease = store.acquire("worker", Duration.ofMinutes(5)).orElseThrow();
        SourceRead future = new SourceRead(clock.instant(), clock.instant().plusSeconds(1));

        assertThatThrownBy(() -> store.stage(lease, Endpoint.fixtures(), future, Validation.valid(), bytes("body")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("future");
        AtomicBoolean callbackRan = new AtomicBoolean();
        assertThatThrownBy(() -> store.publish(lease, future, () -> {
            callbackRan.set(true);
            return null;
        })).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("future");

        assertThat(callbackRan).isFalse();
        assertThat(count(TABLES.payload())).isZero();
        assertThat(jdbc.queryForObject("SELECT latest_generation FROM " + TABLES.receipt()
                + " WHERE season = '2097-98' AND publication_key = 'fixture:future-read'", Long.class)).isNull();
    }

    @Test
    void retentionDeletesOnlyTerminalPayloadsAndKeepsActiveWork() {
        enqueue("active", "fixture:13");
        Lease active = store.acquire("worker-a", Duration.ofHours(1)).orElseThrow();
        store.stage(active, Endpoint.bootstrap(), read(), Validation.valid(), bytes("active"));

        enqueue("done", "fixture:14");
        Lease done = store.acquire("worker-b", Duration.ofHours(1)).orElseThrow();
        store.stage(done, Endpoint.fixtures(), read(), Validation.valid(), bytes("done"));
        store.publish(done, read(), () -> null);
        clock.advance(Duration.ofHours(2));

        assertThat(store.deleteTerminalPayloadsOlderThan(Duration.ofHours(1))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT run_generation FROM " + TABLES.payload(), Long.class))
                .isEqualTo(active.generation());
    }

    @Test
    void sourceReadTimeAndPublicationTimeRemainDistinct() {
        enqueue("fixture-15", "fixture:15");
        Lease lease = store.acquire("worker", Duration.ofMinutes(5)).orElseThrow();
        SourceRead sourceRead = read();
        clock.advance(Duration.ofMinutes(2));
        store.publish(lease, sourceRead, () -> null);

        Instant sourceFinished = jdbc.queryForObject("SELECT source_read_finished_at FROM " + TABLES.receipt()
                + " WHERE season = '2097-98' AND publication_key = 'fixture:15'",
                java.sql.Timestamp.class).toInstant();
        Instant published = jdbc.queryForObject("SELECT published_at FROM " + TABLES.receipt()
                + " WHERE season = '2097-98' AND publication_key = 'fixture:15'",
                java.sql.Timestamp.class).toInstant();
        assertThat(sourceFinished).isEqualTo(sourceRead.finishedAt());
        assertThat(published).isEqualTo(clock.instant());
        assertThat(published).isAfter(sourceFinished);
    }

    @Test
    void tableIdentifiersAreValidatedBeforeTheyReachSql() {
        assertThat(OwnedSyncTables.defaults().run()).startsWith("fpl_");
        assertThat(TABLES.run()).endsWith("_test");
        assertThatThrownBy(() -> new OwnedSyncTables("fpl_sync_run; drop table season", "ok", "ok"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void enqueue(String coalescingKey, String publicationKey) {
        store.enqueue(SyncKind.FIXTURE_VERIFICATION, "2097-98", coalescingKey, publicationKey, clock.instant());
    }

    private SourceRead read() {
        return new SourceRead(clock.instant().minusSeconds(2), clock.instant().minusSeconds(1));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private static void refuseProductionBeforeAnyWrite(DataSource dataSource) {
        JdbcTemplate probe = new JdbcTemplate(dataSource);
        String manager = probe.queryForObject("SELECT to_regclass('public.manager')::text", String.class);
        if (manager != null) {
            throw new IllegalStateException("Refusing owned-sync integration test writes to the shared database");
        }
        assertThat(TABLES.run()).endsWith("_test");
        assertThat(TABLES.payload()).endsWith("_test");
        assertThat(TABLES.receipt()).endsWith("_test");
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
