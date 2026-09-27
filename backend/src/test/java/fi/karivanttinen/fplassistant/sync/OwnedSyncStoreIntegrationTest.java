package fi.karivanttinen.fplassistant.sync;

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
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.Endpoint;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.EndpointKind;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.FutureFloor;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.Lease;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.LeaseLostException;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.PayloadRetentionLimitException;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.Ready;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.RuntimeFenceLostException;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.Run;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.SourceRead;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.StaleGenerationException;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.SyncKind;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.Validation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
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
    void admissionDeferralDoesNotIncreaseDurableFetchFailures() {
        Run run = store.enqueue(SyncKind.PLAYER_HISTORY, "2097-98", "history", "history", clock.instant());
        Lease first = store.acquire("worker", Duration.ofMinutes(5)).orElseThrow();
        store.retry(first, clock.instant().plusSeconds(1), "HTTP admission deferred: cooldown");
        clock.advance(Duration.ofSeconds(1));
        Lease second = store.acquire("worker", Duration.ofMinutes(5)).orElseThrow();
        store.retryFetchFailure(second, clock.instant().plusSeconds(1), "timed out");

        assertThat(jdbc.queryForObject("SELECT attempt_count FROM " + TABLES.run()
                + " WHERE generation=?", Integer.class, run.generation())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT consecutive_fetch_failures FROM " + TABLES.run()
                + " WHERE generation=?", Integer.class, run.generation())).isOne();
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
                .isEqualTo(SyncKind.CATALOGUE_SCHEDULE);
    }

    @Test
    void prerequisitesAndDependenciesCannotBeStarvedByWaitingFixtureFanout() {
        Lease firstWaiting = null;
        Instant firstDemand = null;
        for (int fixture = 1; fixture <= 39; fixture++) {
            Run run = store.enqueue(SyncKind.FIXTURE_VERIFICATION, "2097-98", "fixture-" + fixture,
                    "fixture:" + fixture, clock.instant());
            Lease lease = store.acquire("fixture-worker", Duration.ofMinutes(5)).orElseThrow();
            assertThat(lease.generation()).isEqualTo(run.generation());
            if (fixture == 1) {
                firstWaiting = lease;
                firstDemand = store.requestedSourceAfter(lease);
            }
            store.retryForDependency(lease, clock.instant(), "waiting for histories");
        }
        Lease staleWaiting = firstWaiting;
        assertThat(jdbc.queryForObject("SELECT requested_source_after FROM " + TABLES.run()
                + " WHERE generation=?", java.sql.Timestamp.class, staleWaiting.generation()).toInstant())
                .isEqualTo(firstDemand);
        assertThatThrownBy(() -> store.retryForDependency(staleWaiting, clock.instant(), "stale retry"))
                .isInstanceOf(LeaseLostException.class);

        for (int player = 1; player <= 600; player++) {
            store.enqueue(SyncKind.PLAYER_HISTORY, "2097-98", "player-history:" + player,
                    "player-history:" + player, clock.instant());
        }
        store.enqueue(SyncKind.CATALOGUE_SCHEDULE, "2097-98", "catalogue", "catalogue", clock.instant());
        store.enqueue(SyncKind.CATALOGUE_SCHEDULE, "2097-98", "schedule", "schedule", clock.instant());

        for (int prerequisite = 0; prerequisite < 2; prerequisite++) {
            Lease lease = store.acquire("worker", Duration.ofMinutes(5)).orElseThrow();
            assertThat(lease.kind()).isEqualTo(SyncKind.CATALOGUE_SCHEDULE);
            store.fail(lease, "test cleanup");
        }
        for (int player = 0; player < 600; player++) {
            Lease lease = store.acquire("worker", Duration.ofMinutes(5)).orElseThrow();
            assertThat(lease.kind()).isEqualTo(SyncKind.PLAYER_HISTORY);
            store.fail(lease, "test cleanup");
        }

        Lease readyRetry = store.acquire("worker", Duration.ofMinutes(5)).orElseThrow();
        assertThat(readyRetry.kind()).isEqualTo(SyncKind.FIXTURE_VERIFICATION);
        store.retry(readyRetry, clock.instant(), "ordinary retry");
        store.enqueue(SyncKind.PLAYER_HISTORY, "2097-98", "player-history:601",
                "player-history:601", clock.instant());
        assertThat(store.acquire("worker", Duration.ofMinutes(5)).orElseThrow().generation())
                .isEqualTo(readyRetry.generation());
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
    void httpFenceUsesTheDurableRenewalAndAcceptsFixtureEndpoints() {
        OwnedSyncStore fenced = fencedStore();
        Run run = enqueueFixtureVerification(fenced, 44);
        Lease initial = fenced.acquireForSeason("2097-98", "http-worker", Duration.ofMinutes(2))
                .orElseThrow();
        assertThat(initial.generation()).isEqualTo(run.generation());

        clock.advance(Duration.ofSeconds(30));
        Lease renewed = fenced.renew(initial, Duration.ofMinutes(4));
        Instant endpointFloor = clock.instant().minusSeconds(40);

        assertThat(fenced.httpFence(initial, Endpoint.bootstrap(), endpointFloor))
                .isEqualTo(new Ready(renewed.expiresAt(), clock.instant().minusSeconds(30)));
        assertThat(fenced.httpFence(initial, Endpoint.fixtures(), endpointFloor))
                .isEqualTo(new Ready(renewed.expiresAt(), clock.instant().minusSeconds(30)));
        assertThat(fenced.httpFence(initial, Endpoint.eventLive(7), endpointFloor))
                .isEqualTo(new Ready(renewed.expiresAt(), clock.instant().minusSeconds(30)));
        assertNoHttpEffects("fixture:44");
    }

    @Test
    void httpFenceRejectsExpiredAndReassignedLeasesWithoutStagingOrPublishing() {
        OwnedSyncStore fenced = fencedStore();
        enqueueFixtureVerification(fenced, 45);
        Lease old = fenced.acquireForSeason("2097-98", "old-worker", Duration.ofMinutes(1))
                .orElseThrow();
        clock.advance(Duration.ofMinutes(2));

        assertThatThrownBy(() -> fenced.httpFence(old, Endpoint.bootstrap(), clock.instant()))
                .isInstanceOf(LeaseLostException.class);
        Lease replacement = fenced.acquireForSeason("2097-98", "new-worker", Duration.ofMinutes(5))
                .orElseThrow();
        assertThat(replacement.fence()).isGreaterThan(old.fence());
        assertThatThrownBy(() -> fenced.httpFence(old, Endpoint.bootstrap(), clock.instant()))
                .isInstanceOf(LeaseLostException.class);
        assertNoHttpEffects("fixture:45");
    }

    @Test
    void httpFenceRejectsAReplacedRuntimeEpochWithoutStagingOrPublishing() {
        OwnedSyncStore stale = fencedStore();
        enqueueFixtureVerification(stale, 46);
        Lease lease = stale.acquireForSeason("2097-98", "epoch-worker", Duration.ofMinutes(5))
                .orElseThrow();

        new PlannerRuntimeRepository(jdbc, tx, clock, TABLES).activate("2097-98", OwnedSyncMode.STAGING);

        assertThatThrownBy(() -> stale.httpFence(lease, Endpoint.bootstrap(), clock.instant()))
                .isInstanceOf(RuntimeFenceLostException.class);
        assertNoHttpEffects("fixture:46");
    }

    @Test
    void httpFenceSeesDemandAdvancedSinceEndpointSelection() {
        OwnedSyncStore fenced = fencedStore();
        Run run = enqueueCatalogueSchedule(fenced);
        Lease lease = fenced.acquireForSeason("2097-98", "demand-worker", Duration.ofMinutes(5))
                .orElseThrow();
        assertThat(lease.generation()).isEqualTo(run.generation());
        Instant earlierSelection = fenced.requestedSourceAfter(lease);

        clock.advance(Duration.ofSeconds(20));
        Instant nextDue = clock.instant().plusSeconds(15);
        fenced.enqueue(SyncKind.CATALOGUE_SCHEDULE, "2097-98",
                CatalogueScheduleDispatcher.SCHEDULE_COALESCING_KEY,
                CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY, nextDue);

        assertThat(fenced.httpFence(lease, Endpoint.fixtures(), earlierSelection))
                .isEqualTo(new FutureFloor(nextDue));
        assertNoHttpEffects(CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY);
    }

    @Test
    void httpFenceRejectsWrongCatalogueEndpointAndHistoryPlayerId() {
        OwnedSyncStore fenced = fencedStore();
        enqueueCatalogueSchedule(fenced);
        String historyKey = HistoryFixtureDispatcher.playerHistoryPublicationKey(321);
        fenced.enqueue(SyncKind.PLAYER_HISTORY, "2097-98", historyKey, historyKey, clock.instant());

        Lease catalogue = fenced.acquireForSeason("2097-98", "endpoint-worker", Duration.ofMinutes(5))
                .orElseThrow();
        assertThatThrownBy(() -> fenced.httpFence(catalogue, Endpoint.eventLive(7), clock.instant()))
                .isInstanceOf(IllegalArgumentException.class);
        Lease history = fenced.acquireForSeason("2097-98", "history-worker", Duration.ofMinutes(5))
                .orElseThrow();
        assertThat(history.kind()).isEqualTo(SyncKind.PLAYER_HISTORY);
        assertThatThrownBy(() -> fenced.httpFence(history, Endpoint.elementSummary(322), clock.instant()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fenced.httpFence(history, Endpoint.eventLive(0), clock.instant()))
                .isInstanceOf(IllegalArgumentException.class);
        assertNoHttpEffects(CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY);
        assertNoHttpEffects(historyKey);
    }

    @Test
    void httpFenceReturnsFutureFloorForTheExactHistoryEndpoint() {
        OwnedSyncStore fenced = fencedStore();
        String historyKey = HistoryFixtureDispatcher.playerHistoryPublicationKey(321);
        fenced.enqueue(SyncKind.PLAYER_HISTORY, "2097-98", historyKey, historyKey, clock.instant());
        Lease lease = fenced.acquireForSeason("2097-98", "history-worker", Duration.ofMinutes(5))
                .orElseThrow();
        Instant endpointFloor = clock.instant().plusSeconds(30);

        assertThat(fenced.httpFence(lease, Endpoint.elementSummary(321), endpointFloor))
                .isEqualTo(new FutureFloor(endpointFloor));
        assertNoHttpEffects(historyKey);
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
    void conditionalPendingInvokesCallbackOnceCompletesAndDoesNotAdvanceReceipt() {
        enqueue("fixture-conditional", "fixture:conditional");
        Lease lease = store.acquire("worker", Duration.ofMinutes(5)).orElseThrow();
        AtomicInteger invocations = new AtomicInteger();

        String value = store.publishConditionally(lease, read(), () -> {
            invocations.incrementAndGet();
            jdbc.update("INSERT INTO " + PROBE + " VALUES ('fixture:conditional', 1)");
            return OwnedSyncStore.ConditionalPublication.pending("pending");
        });

        assertThat(value).isEqualTo("pending");
        assertThat(invocations).hasValue(1);
        assertThat(count(PROBE)).isOne();
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.run() + " WHERE generation=?",
                String.class, lease.generation())).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT latest_generation FROM " + TABLES.receipt()
                + " WHERE season='2097-98' AND publication_key='fixture:conditional'", Long.class))
                .isNull();
    }

    @Test
    void staleConditionalGenerationNeverInvokesCallback() {
        var older = store.enqueue(SyncKind.FIXTURE_VERIFICATION, "2097-98", "conditional-old",
                "fixture:conditional-stale", clock.instant());
        var newer = store.enqueue(SyncKind.FIXTURE_VERIFICATION, "2097-98", "conditional-new",
                "fixture:conditional-stale", clock.instant());
        Lease first = store.acquire("worker-a", Duration.ofMinutes(5)).orElseThrow();
        Lease second = store.acquire("worker-b", Duration.ofMinutes(5)).orElseThrow();
        Lease oldLease = first.generation() == older.generation() ? first : second;
        Lease newLease = first.generation() == newer.generation() ? first : second;
        store.publishConditionally(newLease, read(), () ->
                OwnedSyncStore.ConditionalPublication.published("verified"));
        AtomicInteger invocations = new AtomicInteger();

        assertThatThrownBy(() -> store.publishConditionally(oldLease, read(), () -> {
            invocations.incrementAndGet();
            return OwnedSyncStore.ConditionalPublication.pending("pending");
        })).isInstanceOf(StaleGenerationException.class);
        assertThat(invocations).hasValue(0);
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
    void reusesTheEarliestValidPayloadAtOrAfterTheFloor() {
        store.enqueue(SyncKind.FIXTURE_VERIFICATION, "2097-98", "fixture:11",
                "fixture:11", clock.instant());
        Lease lease = store.acquire("worker", Duration.ofMinutes(5)).orElseThrow();
        SourceRead first = new SourceRead(clock.instant().minusSeconds(600), clock.instant().minusSeconds(590));
        SourceRead recheck = new SourceRead(clock.instant().minusSeconds(60), clock.instant().minusSeconds(50));
        long firstRead = store.stage(lease, Endpoint.bootstrap(), first, Validation.valid(), new byte[]{1});
        long recheckRead = store.stage(lease, Endpoint.bootstrap(), recheck, Validation.valid(), new byte[]{2});

        // Without this, a later attempt would pick the recheck read and every history fetched
        // against the first read would count as stale.
        assertThat(store.earliestValidStagedPayload(lease, Endpoint.bootstrap(),
                clock.instant().minusSeconds(3600))).get()
                .extracting(OwnedSyncStore.StagedPayload::id).isEqualTo(firstRead);
        assertThat(store.earliestValidStagedPayload(lease, Endpoint.bootstrap(),
                clock.instant().minusSeconds(120))).get()
                .extracting(OwnedSyncStore.StagedPayload::id).isEqualTo(recheckRead);
        assertThat(store.earliestValidStagedPayload(lease, Endpoint.bootstrap(),
                clock.instant())).isEmpty();
    }

    @Test
    void metadataSelectionRejectsIneligibleCheckpointsBeforeHydratingTheExactEligibleOne() {
        TrackingJdbcTemplate tracking = new TrackingJdbcTemplate(jdbc.getDataSource());
        OwnedSyncStore tracked = new OwnedSyncStore(tracking, tx, clock, TABLES);
        Run run = tracked.enqueue(SyncKind.FIXTURE_VERIFICATION, "2097-98", "metadata",
                "fixture:metadata", clock.instant());
        Lease lease = tracked.acquire("metadata-worker", Duration.ofMinutes(5)).orElseThrow();
        assertThat(lease.generation()).isEqualTo(run.generation());
        clock.advance(Duration.ofSeconds(3));
        SourceRead eligible = read();
        tracked.stage(lease, Endpoint.elementSummary(7), eligible, Validation.valid(), bytes("history"));
        tracked.stage(lease, Endpoint.eventLive(7), eligible, Validation.valid(), bytes("live"));
        tracked.stage(lease, Endpoint.elementSummary(8), eligible, Validation.invalid("bad"), bytes("bad"));

        tracking.resetHydratedBodies();
        assertThat(tracked.earliestValidStagedPayloadMetadata(lease, Endpoint.elementSummary(8),
                eligible.startedAt())).isEmpty();
        assertThat(tracked.earliestValidStagedPayloadMetadata(lease, Endpoint.elementSummary(7),
                clock.instant())).isEmpty();
        var selected = tracked.earliestValidStagedPayloadMetadata(lease, Endpoint.elementSummary(7),
                eligible.startedAt()).orElseThrow();

        assertThat(selected.endpoint()).isEqualTo(Endpoint.elementSummary(7));
        assertThat(tracking.hydratedBodies()).isZero();
        assertThat(tracked.hydrateStagedPayload(lease, Endpoint.elementSummary(7), selected,
                eligible.startedAt()).orElseThrow().body()).isEqualTo(bytes("history"));
        assertThat(tracking.hydratedBodies()).isOne();
    }

    @Test
    void stagedHydrationDoesNotLoadBodyAfterSelectedPayloadBecomesInvalid() {
        TrackingJdbcTemplate tracking = new TrackingJdbcTemplate(jdbc.getDataSource());
        OwnedSyncStore tracked = new OwnedSyncStore(tracking, tx, clock, TABLES);
        tracked.enqueue(SyncKind.FIXTURE_VERIFICATION, "2097-98", "metadata-invalid",
                "fixture:metadata-invalid", clock.instant());
        Lease lease = tracked.acquire("metadata-invalid-worker", Duration.ofMinutes(5)).orElseThrow();
        Endpoint endpoint = Endpoint.elementSummary(7);
        clock.advance(Duration.ofSeconds(3));
        SourceRead sourceRead = read();
        tracked.stage(lease, endpoint, sourceRead, Validation.valid(), bytes("history"));
        var selected = tracked.earliestValidStagedPayloadMetadata(lease, endpoint,
                sourceRead.startedAt()).orElseThrow();
        assertThat(tracking.hydratedBodies()).isZero();

        jdbc.update("UPDATE " + TABLES.payload()
                + " SET validation_state='INVALID', validation_error='changed after selection' WHERE id=?",
                selected.id());
        tracking.resetHydratedBodies();

        assertThat(tracked.hydrateStagedPayload(lease, endpoint, selected, sourceRead.startedAt())).isEmpty();
        assertThat(tracking.hydratedBodies()).isZero();
    }

    @Test
    void stagedHydrationDoesNotLoadBodyAfterDurableSourceFloorAdvances() {
        TrackingJdbcTemplate tracking = new TrackingJdbcTemplate(jdbc.getDataSource());
        OwnedSyncStore tracked = new OwnedSyncStore(tracking, tx, clock, TABLES);
        Run run = tracked.enqueue(SyncKind.FIXTURE_VERIFICATION, "2097-98", "staged-floor",
                "fixture:staged-floor", clock.instant());
        Lease lease = tracked.acquire("staged-floor-worker", Duration.ofMinutes(5)).orElseThrow();
        assertThat(lease.generation()).isEqualTo(run.generation());
        Endpoint endpoint = Endpoint.elementSummary(7);
        clock.advance(Duration.ofSeconds(3));
        SourceRead sourceRead = read();
        tracked.stage(lease, endpoint, sourceRead, Validation.valid(), bytes("history"));
        var selected = tracked.earliestValidStagedPayloadMetadata(lease, endpoint,
                sourceRead.startedAt()).orElseThrow();
        assertThat(tracking.hydratedBodies()).isZero();

        clock.advance(Duration.ofSeconds(1));
        Run coalesced = tracked.enqueue(SyncKind.FIXTURE_VERIFICATION, "2097-98", "staged-floor",
                "fixture:staged-floor", clock.instant());
        assertThat(coalesced.generation()).isEqualTo(lease.generation());
        assertThat(tracked.requestedSourceAfter(lease)).isAfter(sourceRead.startedAt());
        tracking.resetHydratedBodies();

        assertThat(tracked.hydrateStagedPayload(lease, endpoint, selected, sourceRead.startedAt())).isEmpty();
        assertThat(tracking.hydratedBodies()).isZero();
    }

    @Test
    void crossRunHydrationDoesNotLoadBodyAfterDurableSourceFloorAdvances() {
        TrackingJdbcTemplate tracking = new TrackingJdbcTemplate(jdbc.getDataSource());
        OwnedSyncStore tracked = new OwnedSyncStore(tracking, tx, clock, TABLES);
        Run consumer = tracked.enqueue(SyncKind.FIXTURE_VERIFICATION, "2097-98", "fixture:floor",
                "fixture:floor", clock.instant());
        Lease consumerLease = tracked.acquire("history-consumer", Duration.ofMinutes(5)).orElseThrow();
        assertThat(consumerLease.generation()).isEqualTo(consumer.generation());
        clock.advance(Duration.ofSeconds(3));
        Run source = tracked.enqueue(SyncKind.PLAYER_HISTORY, "2097-98", "player-history:7",
                "player-history:7", clock.instant());
        Lease sourceLease = tracked.acquire("history-source", Duration.ofMinutes(5)).orElseThrow();
        assertThat(sourceLease.generation()).isEqualTo(source.generation());
        SourceRead sourceRead = read();
        tracked.stage(sourceLease, Endpoint.elementSummary(7), sourceRead, Validation.valid(), bytes("history"));
        tracked.publish(sourceLease, sourceRead, () -> null);

        Endpoint endpoint = Endpoint.elementSummary(7);
        var selected = tracked.latestValidPayloadMetadata("2097-98", endpoint,
                sourceRead.startedAt()).orElseThrow();
        assertThat(tracking.hydratedBodies()).isZero();

        clock.advance(Duration.ofSeconds(1));
        Run coalesced = tracked.enqueue(SyncKind.FIXTURE_VERIFICATION, "2097-98", "fixture:floor",
                "fixture:floor", clock.instant());
        assertThat(coalesced.generation()).isEqualTo(consumerLease.generation());
        assertThat(tracked.requestedSourceAfter(consumerLease)).isAfter(sourceRead.startedAt());
        tracking.resetHydratedBodies();

        assertThat(tracked.hydrateLatestValidPayload(consumerLease, "2097-98", endpoint, selected,
                sourceRead.startedAt())).isEmpty();
        assertThat(tracking.hydratedBodies()).isZero();
    }

    @Test
    void crossRunMetadataHydratesASelectedValidHistory() {
        TrackingJdbcTemplate tracking = new TrackingJdbcTemplate(jdbc.getDataSource());
        OwnedSyncStore tracked = new OwnedSyncStore(tracking, tx, clock, TABLES);
        Run consumer = tracked.enqueue(SyncKind.FIXTURE_VERIFICATION, "2097-98", "fixture:metadata",
                "fixture:metadata", clock.instant());
        Lease consumerLease = tracked.acquire("history-consumer", Duration.ofMinutes(5)).orElseThrow();
        assertThat(consumerLease.generation()).isEqualTo(consumer.generation());
        clock.advance(Duration.ofSeconds(3));
        Run source = tracked.enqueue(SyncKind.PLAYER_HISTORY, "2097-98", "player-history:7",
                "player-history:7", clock.instant());
        Lease sourceLease = tracked.acquire("history-source", Duration.ofMinutes(5)).orElseThrow();
        assertThat(sourceLease.generation()).isEqualTo(source.generation());
        SourceRead sourceRead = read();
        tracked.stage(sourceLease, Endpoint.elementSummary(7), sourceRead, Validation.valid(), bytes("history"));
        tracked.publish(sourceLease, sourceRead, () -> null);

        tracking.resetHydratedBodies();
        var selected = tracked.latestValidPayloadMetadata("2097-98", Endpoint.elementSummary(7),
                sourceRead.startedAt()).orElseThrow();

        assertThat(tracking.hydratedBodies()).isZero();
        assertThat(tracked.hydrateLatestValidPayload(consumerLease, "2097-98", Endpoint.elementSummary(7),
                selected, sourceRead.startedAt()).orElseThrow().body()).isEqualTo(bytes("history"));
        assertThat(tracking.hydratedBodies()).isOne();
    }

    @Test
    void endpointSpecificReuseCannotAliasIdsSeasonsOrOlderSourceReads() {
        store.enqueue(SyncKind.PLAYER_HISTORY, "2097-98", "player-history:7",
                "player-history:7", clock.instant());
        Lease lease = store.acquire("worker", Duration.ofMinutes(5)).orElseThrow();
        SourceRead older = new SourceRead(clock.instant().minusSeconds(2), clock.instant().minusSeconds(1));
        long history = store.stage(lease, Endpoint.elementSummary(7), older, Validation.valid(), new byte[]{7});
        store.stage(lease, Endpoint.eventLive(7), older, Validation.valid(), new byte[]{70});

        assertThat(store.latestStagedPayload(lease, Endpoint.elementSummary(7))).get()
                .extracting(payload -> payload.id()).isEqualTo(history);
        assertThat(store.latestStagedPayload(lease, Endpoint.elementSummary(8))).isEmpty();
        assertThat(store.latestValidPayload("2097-98", Endpoint.elementSummary(7), older.startedAt())).isPresent();
        assertThat(store.latestValidPayload("2097-98", Endpoint.elementSummary(7), older.startedAt().plusMillis(1)))
                .isEmpty();
        assertThat(store.latestValidPayload("2096-97", Endpoint.elementSummary(7), older.startedAt()))
                .isEmpty();
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

    private OwnedSyncStore fencedStore() {
        var fence = new PlannerRuntimeRepository(jdbc, tx, clock, TABLES)
                .activate("2097-98", OwnedSyncMode.STAGING);
        return new OwnedSyncStore(jdbc, tx, clock, TABLES, fence);
    }

    private Run enqueueFixtureVerification(OwnedSyncStore target, int fixtureId) {
        String key = FixtureSyncPublisher.publicationKey(fixtureId);
        return target.enqueue(SyncKind.FIXTURE_VERIFICATION, "2097-98", key, key, clock.instant());
    }

    private Run enqueueCatalogueSchedule(OwnedSyncStore target) {
        return target.enqueue(SyncKind.CATALOGUE_SCHEDULE, "2097-98",
                CatalogueScheduleDispatcher.SCHEDULE_COALESCING_KEY,
                CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY, clock.instant());
    }

    private void assertNoHttpEffects(String publicationKey) {
        assertThat(count(TABLES.payload())).isZero();
        assertThat(jdbc.queryForObject("SELECT latest_generation FROM " + TABLES.receipt()
                + " WHERE season = '2097-98' AND publication_key = ?", Long.class, publicationKey)).isNull();
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

    private static final class TrackingJdbcTemplate extends JdbcTemplate {
        private final AtomicInteger hydratedBodies = new AtomicInteger();

        private TrackingJdbcTemplate(DataSource dataSource) {
            super(dataSource);
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            List<T> rows = super.query(sql, rowMapper, args);
            if (sql.contains("p.body")) hydratedBodies.addAndGet(rows.size());
            return rows;
        }

        private int hydratedBodies() {
            return hydratedBodies.get();
        }

        private void resetHydratedBodies() {
            hydratedBodies.set(0);
        }
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
