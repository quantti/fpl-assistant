package fi.karivanttinen.fplassistant.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import fi.karivanttinen.fplassistant.sync.AdaptiveMatchdayPlanner.SlotReason;
import fi.karivanttinen.fplassistant.sync.AdaptiveMatchdayPlanner.WorkKind;
import fi.karivanttinen.fplassistant.sync.AdaptiveMatchdayPlanner.WorkSlot;

class PlannerRuntimeRepositoryIntegrationTest {

    private static final OwnedSyncTables TABLES = OwnedSyncTables.tests();
    private static final Instant FIRST = Instant.parse("2097-09-19T12:00:00Z");
    private static final String CALLBACK_TABLE = "fpl_sync_planner_callback_test";
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;

    @BeforeAll
    static void initializeDatabase() {
        dataSource = new DriverManagerDataSource(requiredEnv("DATABASE_URL"),
                requiredEnv("DB_USER"), requiredEnv("DB_PASSWORD"));
        refuseProductionBeforeAnyWrite();
        jdbc = new JdbcTemplate(dataSource);
        new OwnedSyncSchema(jdbc, TABLES).initialize();
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + CALLBACK_TABLE
                + " (slot_id VARCHAR(300) PRIMARY KEY)");
        jdbc.execute("ALTER TABLE " + CALLBACK_TABLE + " ENABLE ROW LEVEL SECURITY");
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM " + CALLBACK_TABLE);
        jdbc.update("DELETE FROM " + TABLES.plannerSlot());
        jdbc.update("DELETE FROM " + TABLES.plannerRuntime());
        jdbc.update("DELETE FROM " + TABLES.payload());
        jdbc.update("DELETE FROM " + TABLES.run());
        jdbc.update("DELETE FROM " + TABLES.receipt());
    }

    @Test
    void tableNamesPreserveDefaultsTestsAndCompatibilityConstruction() {
        assertThat(OwnedSyncTables.defaults().plannerSlot()).isEqualTo("fpl_sync_planner_slot");
        assertThat(OwnedSyncTables.defaults().plannerRuntime()).isEqualTo("fpl_sync_planner_runtime");
        assertThat(TABLES.plannerSlot()).isEqualTo("fpl_sync_planner_slot_test");
        assertThat(new OwnedSyncTables("sync_run", "sync_payload", "sync_receipt").plannerRuntime())
                .isEqualTo("sync_run_planner_runtime");
        assertThat(new OwnedSyncTables("sync_run", "sync_payload", "sync_receipt",
                "sync_request", "sync_attempt", "sync_fixture", "sync_evidence").plannerSlot())
                .isEqualTo("sync_run_planner_slot");
    }

    @Test
    void stableSlotIsEnqueuedOnceAndCanBeLoaded() {
        PlannerRuntimeRepository repository = repository(FIRST);
        WorkSlot slot = slot("stable-daily-slot");

        assertThat(repository.enqueueOnce("2026-27", slot, this::recordCallback)).isTrue();
        assertThat(repository.enqueueOnce("2026-27", slot, this::recordCallback)).isFalse();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + CALLBACK_TABLE, Integer.class)).isOne();
        assertThat(repository.loadSlots("2026-27")).singleElement().satisfies(saved -> {
            assertThat(saved.slot()).isEqualTo(slot);
            assertThat(saved.enqueuedAt()).isEqualTo(FIRST);
        });
    }

    @Test
    void concurrentInstancesCommitOneClaimAndOneCallback() throws Exception {
        PlannerRuntimeRepository left = repository(FIRST);
        PlannerRuntimeRepository right = repository(FIRST);
        WorkSlot slot = slot("concurrent-slot");
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(() -> awaitAndEnqueue(start, left, slot));
            Future<Boolean> second = executor.submit(() -> awaitAndEnqueue(start, right, slot));
            start.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + CALLBACK_TABLE, Integer.class)).isOne();
    }

    @Test
    void callbackFailureRollsBackClaimAndCallbackWritesTogether() {
        PlannerRuntimeRepository repository = repository(FIRST);
        WorkSlot slot = slot("rollback-slot");

        assertThatThrownBy(() -> repository.enqueueOnce("2026-27", slot, value -> {
            recordCallback(value);
            throw new IllegalStateException("enqueue failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(repository.loadSlots("2026-27")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + CALLBACK_TABLE, Integer.class)).isZero();
        assertThat(repository.enqueueOnce("2026-27", slot, this::recordCallback)).isTrue();
    }

    @Test
    void everyActivationAdvancesEpochAndFencesThePriorLeaderEvenInTheSameMode() {
        PlannerRuntimeRepository activation = repository(FIRST);
        var stagingFence = activation.activate("2026-27", OwnedSyncMode.STAGING);
        OwnedSyncStore stagingStore = fencedStore(FIRST, stagingFence);
        var old = stagingStore.enqueue(OwnedSyncStore.SyncKind.CATALOGUE_SCHEDULE, "2026-27",
                "old-staging", "old-staging", FIRST);
        var oldLease = stagingStore.acquireForSeason("2026-27", "staging-worker", java.time.Duration.ofMinutes(5))
                .orElseThrow();

        var activeFence = repository(FIRST.plusSeconds(1)).activate("2026-27", OwnedSyncMode.ACTIVE);
        assertThat(activeFence.epoch()).isGreaterThan(stagingFence.epoch());
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.run() + " WHERE generation=?",
                String.class, old.generation())).isEqualTo("FAILED");
        assertThatThrownBy(() -> stagingStore.completeStaged(oldLease,
                new OwnedSyncStore.SourceRead(FIRST, FIRST), List.of()))
                .isInstanceOf(OwnedSyncStore.RuntimeFenceLostException.class);

        OwnedSyncStore activeStore = fencedStore(FIRST.plusSeconds(2), activeFence);
        var current = activeStore.enqueue(OwnedSyncStore.SyncKind.CATALOGUE_SCHEDULE, "2026-27",
                "active", "active", FIRST.plusSeconds(2));
        var replacement = repository(FIRST.plusSeconds(3)).activate("2026-27", OwnedSyncMode.ACTIVE);
        assertThat(replacement.epoch()).isGreaterThan(activeFence.epoch());
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.run() + " WHERE generation=?",
                String.class, current.generation())).isEqualTo("FAILED");
    }

    @Test
    void normalTicksDoNotAdvanceOrSelfFenceTheActivationEpoch() {
        var fence = repository(FIRST).activate("2026-27", OwnedSyncMode.ACTIVE);
        PlannerRuntimeRepository runtime = new PlannerRuntimeRepository(jdbc, tx,
                Clock.fixed(FIRST.plusSeconds(1), ZoneOffset.UTC), TABLES, fence);
        OwnedSyncStore store = fencedStore(FIRST.plusSeconds(1), fence);
        var run = store.enqueue(OwnedSyncStore.SyncKind.CATALOGUE_SCHEDULE, "2026-27",
                "normal", "normal", FIRST.plusSeconds(1));

        runtime.heartbeat("instance-normal");
        runtime.heartbeat("instance-normal");

        assertThat(jdbc.queryForObject("SELECT runtime_epoch FROM " + TABLES.plannerRuntime()
                + " WHERE instance_id='mode:2026-27'", Long.class)).isEqualTo(fence.epoch());
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.run() + " WHERE generation=?",
                String.class, run.generation())).isEqualTo("PENDING");
    }

    @Test
    void offThenActiveRequiresFreshSameEpochSourceEvidence() {
        var firstFence = repository(FIRST).activate("2026-27", OwnedSyncMode.ACTIVE);
        OwnedSyncStore firstStore = fencedStore(FIRST, firstFence);
        var run = firstStore.enqueue(OwnedSyncStore.SyncKind.CATALOGUE_SCHEDULE, "2026-27",
                "source", "source", FIRST);
        var lease = firstStore.acquireForSeason("2026-27", "first", java.time.Duration.ofMinutes(5)).orElseThrow();
        var read = new OwnedSyncStore.SourceRead(FIRST, FIRST);
        firstStore.stage(lease, OwnedSyncStore.Endpoint.bootstrap(), read, OwnedSyncStore.Validation.valid(), new byte[]{1});
        firstStore.stage(lease, OwnedSyncStore.Endpoint.fixtures(), read, OwnedSyncStore.Validation.valid(), new byte[]{2});
        firstStore.publish(lease, read, () -> null);
        PlannerRuntimeRepository firstRuntime = new PlannerRuntimeRepository(jdbc, tx,
                Clock.fixed(FIRST.plusSeconds(1), ZoneOffset.UTC), TABLES, firstFence);
        firstRuntime.deactivate(firstFence);

        var secondFence = repository(FIRST.plusSeconds(2)).activate("2026-27", OwnedSyncMode.ACTIVE);
        OwnedSyncStore secondStore = fencedStore(FIRST.plusSeconds(2), secondFence);

        assertThat(secondStore.latestValidPublicPayloadPairs("2026-27", 20)).isEmpty();
        assertThatThrownBy(() -> firstStore.enqueue(OwnedSyncStore.SyncKind.PLAYER_HISTORY,
                "2026-27", "late", "late", FIRST.plusSeconds(2)))
                .isInstanceOf(OwnedSyncStore.RuntimeFenceLostException.class);
    }

    @Test
    void heartbeatAdvancesWithoutErasingPersistedError() {
        PlannerRuntimeRepository first = repository(FIRST);
        first.heartbeat("worker-a");
        PlannerRuntimeRepository failed = repository(FIRST.plusSeconds(5));
        failed.recordError("worker-a", "source unavailable");
        PlannerRuntimeRepository recovered = repository(FIRST.plusSeconds(10));
        recovered.heartbeat("worker-a");

        assertThat(recovered.loadRuntime("worker-a")).hasValueSatisfying(state -> {
            assertThat(state.startedAt()).isEqualTo(FIRST);
            assertThat(state.heartbeatAt()).isEqualTo(FIRST.plusSeconds(10));
            assertThat(state.lastError()).isEqualTo("source unavailable");
            assertThat(state.lastErrorAt()).isEqualTo(FIRST.plusSeconds(5));
        });
        assertThat(jdbc.queryForObject("SELECT relrowsecurity FROM pg_class WHERE oid=to_regclass(?)",
                Boolean.class, TABLES.plannerSlot())).isTrue();
        assertThat(jdbc.queryForObject("SELECT relrowsecurity FROM pg_class WHERE oid=to_regclass(?)",
                Boolean.class, TABLES.plannerRuntime())).isTrue();
    }

    private PlannerRuntimeRepository repository(Instant now) {
        return new PlannerRuntimeRepository(jdbc, tx, Clock.fixed(now, ZoneOffset.UTC), TABLES);
    }

    private OwnedSyncStore fencedStore(Instant now, OwnedSyncStore.RuntimeFence fence) {
        return new OwnedSyncStore(jdbc, tx, Clock.fixed(now, ZoneOffset.UTC), TABLES, fence);
    }

    private void recordCallback(WorkSlot slot) {
        jdbc.update("INSERT INTO " + CALLBACK_TABLE + "(slot_id) VALUES (?)", slot.id());
    }

    private static boolean awaitAndEnqueue(CountDownLatch start, PlannerRuntimeRepository repository,
                                           WorkSlot slot) {
        try {
            start.await();
            return repository.enqueueOnce("2026-27", slot,
                    value -> jdbc.update("INSERT INTO " + CALLBACK_TABLE + "(slot_id) VALUES (?)", value.id()));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }

    private static WorkSlot slot(String id) {
        return new WorkSlot(id, WorkKind.AFFECTED_HISTORY, FIRST.minusSeconds(60), Set.of(4, 3),
                Set.of(22, 11), SlotReason.MATCHDAY_FAST, 2);
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private static void refuseProductionBeforeAnyWrite() {
        JdbcTemplate probe = new JdbcTemplate(dataSource);
        String manager = probe.queryForObject("SELECT to_regclass('public.manager')::text", String.class);
        if (manager != null) throw new IllegalStateException("Refusing planner integration test writes to shared DB");
        assertThat(TABLES.plannerSlot()).endsWith("_test");
        assertThat(TABLES.plannerRuntime()).endsWith("_test");
    }
}
