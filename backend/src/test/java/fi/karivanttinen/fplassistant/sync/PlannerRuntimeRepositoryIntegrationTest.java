package fi.karivanttinen.fplassistant.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import fi.karivanttinen.fplassistant.sync.AdaptiveMatchdayPlanner.SlotReason;
import fi.karivanttinen.fplassistant.sync.AdaptiveMatchdayPlanner.WorkKind;
import fi.karivanttinen.fplassistant.sync.AdaptiveMatchdayPlanner.WorkSlot;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.ReadInterval;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.Season;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.SourceDocument;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.Endpoint;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.SourceRead;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.SyncKind;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.Validation;

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

        assertThat(secondStore.findInLatestValidPublicPayloadPairs(
                "2026-27", 20, pair -> java.util.Optional.of(pair))).isEmpty();
        assertThatThrownBy(() -> firstStore.enqueue(OwnedSyncStore.SyncKind.PLAYER_HISTORY,
                "2026-27", "late", "late", FIRST.plusSeconds(2)))
                .isInstanceOf(OwnedSyncStore.RuntimeFenceLostException.class);
    }

    @Test
    void canonicalFallbackLoadsOnlyVisitedFullSizePairsAndKeepsAllFences() throws Exception {
        byte[] bootstrap = resource("bootstrap-static.json");
        byte[] fixtures = resource("fixtures.json");
        byte[] malformedBootstrap = bootstrap.clone();
        malformedBootstrap[0] = '[';
        Season season = new Season(27, "2026-27", LocalDate.of(2026, 8, 21), null);
        TrackingJdbcTemplate tracking = new TrackingJdbcTemplate(dataSource);

        var oldFence = repository(FIRST).activate(season.code(), OwnedSyncMode.STAGING);
        stagePair(new OwnedSyncStore(tracking, tx, Clock.fixed(FIRST, ZoneOffset.UTC), TABLES, oldFence),
                season.code(), "old-epoch", FIRST, bootstrap, fixtures, true);
        var fence = repository(FIRST.plusSeconds(1)).activate(season.code(), OwnedSyncMode.STAGING);

        OwnedSyncStore wrongSeasonStore = new OwnedSyncStore(tracking, tx,
                Clock.fixed(FIRST.plusSeconds(2), ZoneOffset.UTC), TABLES, fence);
        long wrongSeason = stagePair(wrongSeasonStore, season.code(), "wrong-season",
                FIRST.plusSeconds(2), bootstrap, fixtures, true);
        jdbc.update("INSERT INTO " + TABLES.receipt()
                + "(season,publication_key) VALUES ('2098-99','wrong-season')");
        jdbc.update("UPDATE " + TABLES.run() + " SET season='2098-99' WHERE generation=?", wrongSeason);

        long expected = 0;
        OwnedSyncStore current = null;
        for (int index = 0; index < 6; index++) {
            Instant at = FIRST.plusSeconds(3 + index);
            current = new OwnedSyncStore(tracking, tx, Clock.fixed(at, ZoneOffset.UTC), TABLES, fence);
            long generation = stagePair(current, season.code(), "candidate-" + index, at,
                    index == 5 ? malformedBootstrap : bootstrap, fixtures, true);
            if (index == 4) expected = generation;
        }
        Instant incompleteAt = FIRST.plusSeconds(10);
        OwnedSyncStore incompleteStore = new OwnedSyncStore(tracking, tx,
                Clock.fixed(incompleteAt, ZoneOffset.UTC), TABLES, fence);
        stagePair(incompleteStore, season.code(), "incomplete", incompleteAt,
                bootstrap, fixtures, false);

        AtomicInteger visited = new AtomicInteger();
        Optional<Long> selected = current.findInLatestValidPublicPayloadPairs(season.code(), 20, pair -> {
            visited.incrementAndGet();
            try {
                CatalogueScheduleParser.parse(season,
                        new SourceDocument(pair.bootstrap().body(), new ReadInterval(
                                pair.bootstrap().read().startedAt(), pair.bootstrap().read().finishedAt())),
                        new SourceDocument(pair.fixtures().body(), new ReadInterval(
                                pair.fixtures().read().startedAt(), pair.fixtures().read().finishedAt())));
                return Optional.of(pair.runGeneration());
            } catch (IllegalArgumentException invalid) {
                return Optional.empty();
            }
        });

        assertThat(selected).contains(expected);
        assertThat(visited).hasValue(2);
        assertThat(tracking.bodyPairQueries()).isEqualTo(2);

        assertThat(current.findInLatestValidPublicPayloadPairs(season.code(), 20,
                pair -> {
                    visited.incrementAndGet();
                    try {
                        CatalogueScheduleParser.parse(season,
                                new SourceDocument(pair.bootstrap().body(), new ReadInterval(
                                        pair.bootstrap().read().startedAt(), pair.bootstrap().read().finishedAt())),
                                new SourceDocument(pair.fixtures().body(), new ReadInterval(
                                        pair.fixtures().read().startedAt(), pair.fixtures().read().finishedAt())));
                        return Optional.of(pair.runGeneration());
                    } catch (IllegalArgumentException invalid) {
                        return Optional.empty();
                    }
                })).contains(expected);
        assertThat(visited).hasValue(4);
        assertThat(tracking.bodyPairQueries()).isEqualTo(2);

        assertThat(current.findInLatestValidPublicPayloadPairs(season.code(), 20,
                incompleteAt.plusSeconds(1), pair -> {
                    throw new AssertionError("Stale bodies must not reach the visitor");
                })).isEmpty();
        assertThat(tracking.bodyPairQueries()).isEqualTo(2);
    }

    @Test
    void canonicalPairInvalidatedAfterMetadataSelectionIsNotReturned() {
        var fence = repository(FIRST).activate("2026-27", OwnedSyncMode.STAGING);
        TrackingJdbcTemplate tracking = new TrackingJdbcTemplate(dataSource);
        var store = new OwnedSyncStore(tracking, tx, Clock.fixed(FIRST, ZoneOffset.UTC), TABLES, fence);
        long older = stagePair(store, "2026-27", "older", FIRST, new byte[]{1}, new byte[]{2}, true);
        long newer = stagePair(store, "2026-27", "newer", FIRST, new byte[]{3}, new byte[]{4}, true);

        assertThat(store.findInLatestValidPublicPayloadPairs("2026-27", 20, FIRST,
                pair -> Optional.of(pair.runGeneration()))).contains(newer);
        assertThat(tracking.bodyPairQueries()).isOne();
        tracking.afterCandidateQuery = () -> jdbc.update("UPDATE " + TABLES.payload()
                + " SET validation_state='INVALID' WHERE run_generation=?", newer);

        assertThat(store.findInLatestValidPublicPayloadPairs("2026-27", 20, FIRST,
                pair -> Optional.of(pair.runGeneration()))).contains(older);
        assertThat(tracking.bodyPairQueries()).isEqualTo(2);
    }

    @Test
    void concurrentCanonicalPairSelectionsShareOneBodyLoadAndDefendBytes() throws Exception {
        var fence = repository(FIRST).activate("2026-27", OwnedSyncMode.STAGING);
        TrackingJdbcTemplate tracking = new TrackingJdbcTemplate(dataSource);
        var store = new OwnedSyncStore(tracking, tx, Clock.fixed(FIRST, ZoneOffset.UTC), TABLES, fence);
        stagePair(store, "2026-27", "shared", FIRST, new byte[]{1}, new byte[]{2}, true);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(3)) {
            List<Future<byte[]>> selections = java.util.stream.IntStream.range(0, 3)
                    .mapToObj(index -> executor.submit(() -> {
                        start.await();
                        return store.findInLatestValidPublicPayloadPairs("2026-27", 20,
                                pair -> Optional.of(pair.bootstrap().body())).orElseThrow();
                    })).toList();
            start.countDown();
            byte[] first = selections.getFirst().get();
            first[0] = 99;
            assertThat(selections.get(1).get()).containsExactly((byte) 1);
            assertThat(selections.get(2).get()).containsExactly((byte) 1);
        }
        assertThat(tracking.bodyPairQueries()).isOne();
        assertThat(store.findInLatestValidPublicPayloadPairs("2026-27", 20,
                pair -> Optional.of(pair.bootstrap().body())).orElseThrow()).containsExactly((byte) 1);
        assertThat(tracking.bodyPairQueries()).isOne();
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

    private static long stagePair(OwnedSyncStore store, String season, String key, Instant at,
                                  byte[] bootstrap, byte[] fixtures, boolean complete) {
        var run = store.enqueue(SyncKind.CATALOGUE_SCHEDULE, season, key, key, at);
        var lease = store.acquireForSeason(season, key + "-worker", java.time.Duration.ofMinutes(5))
                .orElseThrow();
        SourceRead read = new SourceRead(at, at);
        store.stage(lease, Endpoint.bootstrap(), read, Validation.valid(), bootstrap);
        if (complete) store.stage(lease, Endpoint.fixtures(), read, Validation.valid(), fixtures);
        store.publish(lease, read, () -> null);
        return run.generation();
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream input = PlannerRuntimeRepositoryIntegrationTest.class.getResourceAsStream(
                "/owned-sync-source-fixtures/" + name)) {
            if (input == null) throw new IOException("Missing fixture " + name);
            return input.readAllBytes();
        }
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

    private static final class TrackingJdbcTemplate extends JdbcTemplate {
        private final AtomicInteger bodyPairQueries = new AtomicInteger();
        private Runnable afterCandidateQuery;

        private TrackingJdbcTemplate(DataSource dataSource) {
            super(dataSource);
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            if (sql.contains("b.body AS b_body")) bodyPairQueries.incrementAndGet();
            List<T> result = super.query(sql, rowMapper, args);
            if (afterCandidateQuery != null && sql.contains("b.id AS b_id")
                    && !sql.contains("b.body AS b_body")) {
                Runnable callback = afterCandidateQuery;
                afterCandidateQuery = null;
                callback.run();
            }
            return result;
        }

        private int bodyPairQueries() {
            return bodyPairQueries.get();
        }
    }
}
