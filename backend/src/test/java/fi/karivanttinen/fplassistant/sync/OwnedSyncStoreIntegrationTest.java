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
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.PublicPayloadPair;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.Ready;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.RuntimeFenceLostException;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.Run;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.SourceRead;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.StagedPayload;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.StaleGenerationException;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.SyncKind;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.Validation;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.ValidationState;
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
    void ensurePlayerHistoryAtLeastKeepsExactFloorAndPreservesAnActiveLease() {
        Instant firstFloor = clock.instant().minusSeconds(60);
        Run first = store.ensurePlayerHistoryAtLeast("2097-98", 71, firstFloor);
        Lease lease = store.acquire("history-worker", Duration.ofMinutes(20)).orElseThrow();
        assertThat(lease.generation()).isEqualTo(first.generation());
        Instant firstDue = jdbc.queryForObject("SELECT due_at FROM " + TABLES.run()
                + " WHERE generation=?", java.sql.Timestamp.class, first.generation()).toInstant();

        clock.advance(Duration.ofMinutes(10));
        Run repeated = store.ensurePlayerHistoryAtLeast("2097-98", 71, firstFloor);

        assertThat(repeated.generation()).isEqualTo(first.generation());
        assertThat(store.requestedSourceAfter(lease)).isEqualTo(firstFloor);
        assertThat(jdbc.queryForObject("SELECT due_at FROM " + TABLES.run()
                + " WHERE generation=?", java.sql.Timestamp.class, first.generation()).toInstant())
                .isEqualTo(firstDue);

        Instant newerFixtureFloor = firstFloor.plusSeconds(30);
        store.ensurePlayerHistoryAtLeast("2097-98", 71, newerFixtureFloor);
        assertThat(store.requestedSourceAfter(lease)).isEqualTo(newerFixtureFloor);
        assertThat(jdbc.queryForObject("SELECT due_at <= requested_source_after FROM " + TABLES.run()
                + " WHERE generation=?", Boolean.class, first.generation())).isTrue();
        assertThat(jdbc.queryForObject("SELECT due_at FROM " + TABLES.run()
                + " WHERE generation=?", java.sql.Timestamp.class, first.generation()).toInstant())
                .isEqualTo(firstDue);
        assertThat(jdbc.queryForObject("SELECT state || ':' || lease_owner || ':' || lease_token::text || ':' || lease_fence "
                + "FROM " + TABLES.run() + " WHERE generation=?", String.class, first.generation()))
                .isEqualTo("LEASED:history-worker:" + lease.token() + ":" + lease.fence());

        store.ensurePlayerHistoryAtLeast("2097-98", 71, firstFloor.minusSeconds(30));
        assertThat(store.requestedSourceAfter(lease)).isEqualTo(newerFixtureFloor);
    }

    @Test
    void concurrentHistoryNotificationsKeepTheMaximumFloorOnOneLeasedRun() throws Exception {
        Instant initialFloor = clock.instant().minusSeconds(60);
        Run run = store.ensurePlayerHistoryAtLeast("2097-98", 72, initialFloor);
        Lease lease = store.acquire("history-worker", Duration.ofMinutes(5)).orElseThrow();
        assertThat(lease.generation()).isEqualTo(run.generation());
        Instant firstFloor = initialFloor.plusSeconds(10);
        Instant secondFloor = initialFloor.plusSeconds(20);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Run> first = executor.submit(() -> {
                start.await();
                return store.ensurePlayerHistoryAtLeast("2097-98", 72, firstFloor);
            });
            Future<Run> second = executor.submit(() -> {
                start.await();
                return store.ensurePlayerHistoryAtLeast("2097-98", 72, secondFloor);
            });
            start.countDown();

            assertThat(first.get().generation()).isEqualTo(run.generation());
            assertThat(second.get().generation()).isEqualTo(run.generation());
        }

        assertThat(store.requestedSourceAfter(lease)).isEqualTo(secondFloor);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                + " WHERE kind='PLAYER_HISTORY' AND season='2097-98' AND coalescing_key='player-history:72' "
                + "AND state IN ('PENDING', 'LEASED')", Integer.class)).isOne();
    }

    @Test
    void ensurePlayerHistoryAtLeastCreatesANewGenerationAfterCompletion() {
        Instant floor = clock.instant().minusSeconds(60);
        Run completed = store.ensurePlayerHistoryAtLeast("2097-98", 73, floor);
        Lease lease = store.acquire("history-worker", Duration.ofMinutes(5)).orElseThrow();
        SourceRead read = read();
        store.completeStaged(lease, read, List.of(read));

        Instant requiredFloor = floor.plusSeconds(10);
        Run next = store.ensurePlayerHistoryAtLeast("2097-98", 73, requiredFloor);

        assertThat(next.generation()).isGreaterThan(completed.generation());
        assertThat(next.state()).isEqualTo(OwnedSyncStore.RunState.PENDING);
        assertThat(jdbc.queryForObject("SELECT requested_source_after FROM " + TABLES.run()
                + " WHERE generation=?", java.sql.Timestamp.class, next.generation()).toInstant())
                .isEqualTo(requiredFloor);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.receipt()
                + " WHERE season='2097-98' AND publication_key='player-history:73'", Integer.class)).isOne();
    }

    @Test
    void ensurePlayerHistoryAtLeastRejectsAReplacedRuntimeEpoch() {
        OwnedSyncStore fenced = fencedStore();
        Instant floor = clock.instant().minusSeconds(30);
        fenced.ensurePlayerHistoryAtLeast("2097-98", 74, floor);
        new PlannerRuntimeRepository(jdbc, tx, clock, TABLES).activate("2097-98", OwnedSyncMode.STAGING);

        assertThatThrownBy(() -> fenced.ensurePlayerHistoryAtLeast("2097-98", 75, floor))
                .isInstanceOf(RuntimeFenceLostException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.receipt()
                + " WHERE season='2097-98' AND publication_key='player-history:75'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                + " WHERE season='2097-98' AND coalescing_key='player-history:75'", Integer.class)).isZero();
    }

    @Test
    void fixtureDependencySchemaIsOwnedConstrainedAndIdempotent() {
        new OwnedSyncSchema(jdbc, TABLES).initialize();

        assertThat(OwnedSyncTables.defaults().fixtureDependencyManifest())
                .isEqualTo("fpl_fixture_dependency_manifest");
        assertThat(TABLES.fixtureDependencyManifest()).endsWith("_test");
        assertThat(jdbc.queryForObject("SELECT relrowsecurity FROM pg_class WHERE oid=?::regclass",
                Boolean.class, TABLES.fixtureDependencyManifest())).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conrelid=?::regclass "
                + "AND contype='f' AND confdeltype='c'", Integer.class,
                TABLES.fixtureDependencyManifest())).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT fpl_sync_required_ids_valid(ARRAY[2,7,11])",
                Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT fpl_sync_required_ids_valid(ARRAY[7,2])",
                Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT fpl_sync_required_ids_valid(ARRAY[2,2])",
                Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT fpl_sync_required_ids_valid(ARRAY[0,2])",
                Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT fpl_sync_required_ids_valid(ARRAY("
                + "SELECT generate_series(1,81)))", Boolean.class)).isFalse();
    }

    @Test
    void dependencyManifestWriteSurvivesStoreRestartAndUpsertsOneCanonicalRow() {
        Run run = enqueueFixtureVerification(store, 61);
        Lease lease = store.acquire("fixture-worker", Duration.ofMinutes(5)).orElseThrow();
        ManifestAnchors anchors = stageManifestAnchors(lease, Validation.valid(), true);
        Instant dueAt = clock.instant().plus(Duration.ofMinutes(2));

        store.retryForDependency(lease, dueAt, "waiting for fresh histories", 61, 3,
                anchors.pair(), anchors.live(), List.of(17, 2, 9));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.fixtureDependencyManifest()
                + " WHERE run_generation=?", Integer.class, run.generation())).isOne();
        Integer[] storedPlayerIds = jdbc.queryForObject("SELECT required_player_ids FROM "
                + TABLES.fixtureDependencyManifest() + " WHERE run_generation=?",
                (rs, row) -> (Integer[]) rs.getArray(1).getArray(), run.generation());
        assertThat(storedPlayerIds).containsExactly(2, 9, 17);
        assertThat(jdbc.queryForObject("SELECT source_floor FROM " + TABLES.fixtureDependencyManifest()
                + " WHERE run_generation=?", java.sql.Timestamp.class, run.generation()).toInstant())
                .isEqualTo(jdbc.queryForObject("SELECT requested_source_after FROM " + TABLES.run()
                        + " WHERE generation=?", java.sql.Timestamp.class, run.generation()).toInstant());
        assertThat(jdbc.queryForObject("SELECT priority FROM " + TABLES.run()
                + " WHERE generation=?", Integer.class, run.generation()))
                .isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT state || ':' || (lease_token IS NULL)::text FROM "
                + TABLES.run() + " WHERE generation=?", String.class, run.generation()))
                .isEqualTo("PENDING:true");
        assertThatThrownBy(() -> jdbc.update("UPDATE " + TABLES.fixtureDependencyManifest()
                + " SET required_player_ids=ARRAY[17,2] WHERE run_generation=?", run.generation()))
                .isInstanceOf(DataIntegrityViolationException.class);

        OwnedSyncStore restarted = new OwnedSyncStore(jdbc, tx, clock, TABLES);
        clock.advance(Duration.ofMinutes(2));
        Lease resumed = restarted.acquire("restarted-worker", Duration.ofMinutes(5)).orElseThrow();
        assertThat(resumed.generation()).isEqualTo(run.generation());
        restarted.retryForDependency(resumed, clock.instant().plus(Duration.ofMinutes(1)),
                "updated dependency set", 61, 3, anchors.pair(), anchors.live(), List.of(4, 3));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.fixtureDependencyManifest()
                + " WHERE run_generation=?", Integer.class, run.generation())).isOne();
        Integer[] updatedPlayerIds = jdbc.queryForObject("SELECT required_player_ids FROM "
                + TABLES.fixtureDependencyManifest() + " WHERE run_generation=?",
                (rs, row) -> (Integer[]) rs.getArray(1).getArray(), run.generation());
        assertThat(updatedPlayerIds).containsExactly(3, 4);
    }

    @Test
    void metadataReadinessFiltersBeforeLimitWithoutLoadingBodiesAndSurvivesRestart() {
        TrackingJdbcTemplate tracking = new TrackingJdbcTemplate(jdbc.getDataSource());
        var fence = new PlannerRuntimeRepository(jdbc, tx, clock, TABLES)
                .activate("2097-98", OwnedSyncMode.STAGING);
        OwnedSyncStore tracked = new OwnedSyncStore(tracking, tx, clock, TABLES, fence);

        Run noManifest = enqueueFixtureVerification(tracked, 70);
        jdbc.update("UPDATE " + TABLES.run() + " SET priority=100 WHERE generation=?", noManifest.generation());

        seedReadinessHistory(tracked, 7, true);
        ReadinessFixture staleHistory = seedReadinessFixture(tracked, 71, 7);

        ReadinessFixture missingHistory = seedReadinessFixture(tracked, 72, 1);

        ReadinessFixture wrongSeason = seedReadinessFixture(tracked, 73, 2);
        long wrongSeasonHistory = seedReadinessHistory(tracked, 2, true).generation();
        jdbc.update("INSERT INTO " + TABLES.receipt() + "(season, publication_key) VALUES (?, ?) "
                + "ON CONFLICT DO NOTHING", "2096-97", "player-history:2");
        jdbc.update("UPDATE " + TABLES.run() + " SET season='2096-97' WHERE generation=?", wrongSeasonHistory);

        ReadinessFixture wrongEpoch = seedReadinessFixture(tracked, 74, 3);
        long wrongEpochHistory = seedReadinessHistory(tracked, 3, true).generation();
        jdbc.update("UPDATE " + TABLES.run() + " SET runtime_epoch=runtime_epoch+1 WHERE generation=?",
                wrongEpochHistory);

        ReadinessFixture lateDemand = seedReadinessFixture(tracked, 75, 4);
        clock.advance(Duration.ofSeconds(1));
        tracked.enqueue(SyncKind.FIXTURE_VERIFICATION, "2097-98", "fixture:75", "fixture:75", clock.instant());

        ReadinessFixture oldHistoryAfterDemand = seedReadinessFixture(tracked, 85, 10);
        HistoryEvidence historyBeforeDemand = seedReadinessHistory(tracked, 10, true);
        clock.advance(Duration.ofSeconds(1));
        tracked.enqueue(SyncKind.FIXTURE_VERIFICATION, "2097-98", "fixture:85", "fixture:85", clock.instant());
        refreshReadinessFixture(tracked, oldHistoryAfterDemand, 85, 10);
        Instant lateDemandFloor = jdbc.queryForObject("SELECT requested_source_after FROM " + TABLES.run()
                + " WHERE generation=?", java.sql.Timestamp.class, oldHistoryAfterDemand.generation()).toInstant();
        assertThat(historyBeforeDemand.read().startedAt()).isBefore(lateDemandFloor);
        assertThat(jdbc.queryForObject("SELECT b.read_started_at >= r.requested_source_after "
                + "AND f.read_started_at >= r.requested_source_after "
                + "AND l.read_started_at >= r.requested_source_after FROM " + TABLES.run() + " r JOIN "
                + TABLES.fixtureDependencyManifest() + " m ON m.run_generation=r.generation JOIN "
                + TABLES.payload() + " b ON b.id=m.bootstrap_payload_id JOIN " + TABLES.payload()
                + " f ON f.id=m.fixtures_payload_id JOIN " + TABLES.payload()
                + " l ON l.id=m.live_payload_id WHERE r.generation=?",
                Boolean.class, oldHistoryAfterDemand.generation())).isTrue();

        ReadinessFixture invalidAnchor = seedReadinessFixture(tracked, 76, 5);
        seedReadinessHistory(tracked, 5, true);
        jdbc.update("UPDATE " + TABLES.payload() + " SET validation_state='INVALID' WHERE id=?",
                invalidAnchor.bootstrapPayloadId());

        ReadinessFixture deletedAnchor = seedReadinessFixture(tracked, 77, 6);
        seedReadinessHistory(tracked, 6, true);
        jdbc.update("DELETE FROM " + TABLES.payload() + " WHERE id=?", deletedAnchor.fixturesPayloadId());

        ReadinessFixture advancedManifestFloor = seedReadinessFixture(tracked, 78, 8);
        HistoryEvidence oldForManifestFloor = seedReadinessHistory(tracked, 8, true);
        clock.advance(Duration.ofSeconds(1));
        jdbc.update("UPDATE " + TABLES.fixtureDependencyManifest()
                + " SET source_floor=? WHERE run_generation=?", java.sql.Timestamp.from(clock.instant()),
                advancedManifestFloor.generation());
        assertThat(oldForManifestFloor.read().startedAt()).isBefore(clock.instant());

        ReadinessFixture wrongEndpoint = seedReadinessFixture(tracked, 79, 9);
        seedReadinessHistory(tracked, 9, true);
        jdbc.update("UPDATE " + TABLES.payload() + " SET endpoint_kind='FIXTURES' WHERE id=?",
                wrongEndpoint.bootstrapPayloadId());

        ReadinessFixture pendingHistory = seedReadinessFixture(tracked, 80, 20);
        HistoryEvidence pendingEvidence = seedReadinessHistory(tracked, 20, false);
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.run() + " WHERE generation=?",
                String.class, pendingEvidence.generation())).isEqualTo("PENDING");

        ReadinessFixture recoalescedHistory = seedReadinessFixture(tracked, 81, 21);
        HistoryEvidence completedEvidence = seedReadinessHistory(tracked, 21, true);
        clock.advance(Duration.ofSeconds(1));
        Run newerHistoryDemand = tracked.ensurePlayerHistoryAtLeast("2097-98", 21, clock.instant());
        assertThat(newerHistoryDemand.generation()).isGreaterThan(completedEvidence.generation());
        jdbc.update("UPDATE " + TABLES.run() + " SET priority=100 WHERE generation=?",
                newerHistoryDemand.generation());

        ReadinessFixture future = seedReadinessFixture(tracked, 82, 22);
        seedReadinessHistory(tracked, 22, true);
        jdbc.update("UPDATE " + TABLES.run() + " SET due_at=? WHERE generation=?",
                java.sql.Timestamp.from(clock.instant().plus(Duration.ofHours(1))), future.generation());

        ReadinessFixture activeLease = seedReadinessFixture(tracked, 83, 23);
        seedReadinessHistory(tracked, 23, true);
        jdbc.update("UPDATE " + TABLES.run() + " SET due_at=? WHERE generation=?",
                java.sql.Timestamp.from(clock.instant().minus(Duration.ofDays(1))), activeLease.generation());
        Lease active = tracked.acquireForSeason("2097-98", "metadata-active", Duration.ofMinutes(5))
                .orElseThrow();
        assertThat(active.generation()).isEqualTo(activeLease.generation());

        ReadinessFixture expiredLease = seedReadinessFixture(tracked, 84, 24);
        seedReadinessHistory(tracked, 24, true);
        jdbc.update("UPDATE " + TABLES.run() + " SET due_at=? WHERE generation=?",
                java.sql.Timestamp.from(clock.instant().minus(Duration.ofDays(2))), expiredLease.generation());
        Lease expired = tracked.acquireForSeason("2097-98", "metadata-expired", Duration.ofMinutes(5))
                .orElseThrow();
        assertThat(expired.generation()).isEqualTo(expiredLease.generation());
        jdbc.update("UPDATE " + TABLES.run() + " SET lease_expires_at=?, due_at=? WHERE generation=?",
                java.sql.Timestamp.from(clock.instant().minusMillis(1)), java.sql.Timestamp.from(clock.instant()),
                expiredLease.generation());

        jdbc.update("UPDATE " + TABLES.run() + " SET priority=5 WHERE kind='FIXTURE_VERIFICATION'");
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT priority) FROM " + TABLES.run()
                + " WHERE kind='FIXTURE_VERIFICATION'", Integer.class)).isOne();
        long classificationStarted = System.nanoTime();
        List<Run> firstTwo = tracked.readyFixtureGenerations("2097-98", 2);
        long classificationNanos = System.nanoTime() - classificationStarted;
        System.out.printf("OWNED_READINESS fixtureRuns=%d limit=2 readyReturned=%d classificationMs=%.3f "
                        + "payloadBodySelects=%d%n",
                jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                        + " WHERE kind='FIXTURE_VERIFICATION'", Integer.class),
                firstTwo.size(), classificationNanos / 1_000_000.0, tracking.payloadBodySelects());
        assertThat(firstTwo).extracting(Run::generation)
                .containsExactly(pendingHistory.generation(), recoalescedHistory.generation());
        assertThat(tracking.hydratedBodies()).isZero();
        assertThat(tracking.payloadBodySelects()).isZero();

        List<Long> ready = tracked.readyFixtureGenerations("2097-98", 100).stream()
                .map(Run::generation).toList();
        assertThat(ready).containsExactly(pendingHistory.generation(), recoalescedHistory.generation(),
                expiredLease.generation());
        assertThat(ready).doesNotContain(noManifest.generation(), staleHistory.generation(),
                missingHistory.generation(), wrongSeason.generation(), wrongEpoch.generation(),
                lateDemand.generation(), invalidAnchor.generation(), deletedAnchor.generation(),
                advancedManifestFloor.generation(), wrongEndpoint.generation(), oldHistoryAfterDemand.generation(),
                future.generation(),
                activeLease.generation());
        assertThat(tracking.hydratedBodies()).isZero();
        assertThat(tracking.payloadBodySelects()).isZero();

        OwnedSyncStore restarted = new OwnedSyncStore(tracking, tx, clock, TABLES, fence);
        assertThat(restarted.readyFixtureGenerations("2097-98", 100).stream()
                .map(Run::generation).toList()).isEqualTo(ready);
        assertThat(tracking.hydratedBodies()).isZero();
        assertThat(tracking.payloadBodySelects()).isZero();

        new PlannerRuntimeRepository(jdbc, tx, clock, TABLES).activate("2097-98", OwnedSyncMode.STAGING);
        assertThatThrownBy(() -> restarted.readyFixtureGenerations("2097-98", 100))
                .isInstanceOf(RuntimeFenceLostException.class);
    }

    @Test
    void dependencyManifestAcceptsPostgresRoundedReadTimestamps() {
        Run run = enqueueFixtureVerification(store, 67);
        Lease lease = store.acquire("fixture-worker", Duration.ofMinutes(5)).orElseThrow();
        Instant sourceFloor = store.requestedSourceAfter(lease);
        clock.advance(Duration.ofSeconds(2));

        Instant pairStarted = sourceFloor.plusNanos(123_456_789);
        Instant pairFinished = pairStarted.plusMillis(100).plusNanos(789);
        SourceRead pairRead = new SourceRead(pairStarted, pairFinished);
        long bootstrapId = store.stage(lease, Endpoint.bootstrap(), pairRead, Validation.valid(), bytes("bootstrap"));
        long fixturesId = store.stage(lease, Endpoint.fixtures(), pairRead, Validation.valid(), bytes("fixtures"));
        Instant liveStarted = pairFinished.plusMillis(1).plusNanos(789);
        SourceRead liveRead = new SourceRead(liveStarted, liveStarted.plusMillis(10).plusNanos(876_543));
        long liveId = store.stage(lease, Endpoint.eventLive(3), liveRead, Validation.valid(), bytes("live"));
        StagedPayload bootstrap = new StagedPayload(bootstrapId, lease.generation(), EndpointKind.BOOTSTRAP_STATIC,
                pairRead, ValidationState.VALID, null, bytes("bootstrap"));
        StagedPayload fixtures = new StagedPayload(fixturesId, lease.generation(), EndpointKind.FIXTURES,
                pairRead, ValidationState.VALID, null, bytes("fixtures"));
        StagedPayload live = new StagedPayload(liveId, lease.generation(), EndpointKind.EVENT_LIVE,
                liveRead, ValidationState.VALID, null, bytes("live"));

        store.retryForDependency(lease, clock.instant().plusSeconds(30), "waiting", 67, 3,
                new PublicPayloadPair(lease.generation(), bootstrap, fixtures), live, List.of(1));

        assertThat(jdbc.queryForObject("SELECT read_started_at FROM " + TABLES.payload()
                + " WHERE id=?", java.sql.Timestamp.class, bootstrapId).toInstant())
                .isEqualTo(pairStarted.plusNanos(211));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.fixtureDependencyManifest()
                + " WHERE run_generation=?", Integer.class, run.generation())).isOne();
    }

    @Test
    void dependencyManifestRejectsReadMetadataChangedBeyondPostgresPrecision() {
        Run run = enqueueFixtureVerification(store, 68);
        Lease lease = store.acquire("fixture-worker", Duration.ofMinutes(5)).orElseThrow();
        ManifestAnchors anchors = stageManifestAnchors(lease, Validation.valid(), true);
        StagedPayload original = anchors.pair().bootstrap();
        SourceRead changedRead = new SourceRead(original.read().startedAt(),
                original.read().finishedAt().plusNanos(1_000));
        StagedPayload changed = new StagedPayload(original.id(), original.runGeneration(), original.endpointKind(),
                changedRead, original.validationState(), original.validationError(), original.body());

        assertThatThrownBy(() -> store.retryForDependency(lease, clock.instant().plusSeconds(30), "waiting",
                68, 3, new PublicPayloadPair(lease.generation(), changed, anchors.pair().fixtures()),
                anchors.live(), List.of(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("metadata changed");
        assertManifestNotWrittenAndLeaseRetained(run.generation());
    }

    @Test
    void invalidAnchorsAndAdvancedDemandNeverWriteOrReleaseManifestRun() {
        Run invalidRun = enqueueFixtureVerification(store, 62);
        Lease invalidLease = store.acquire("fixture-worker", Duration.ofMinutes(5)).orElseThrow();
        ManifestAnchors unchecked = stageManifestAnchors(invalidLease, Validation.unchecked(), true);

        assertThatThrownBy(() -> store.retryForDependency(invalidLease, clock.instant().plusSeconds(30),
                "waiting", 62, 3, unchecked.pair(), unchecked.live(), List.of(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("valid own endpoint");
        assertManifestNotWrittenAndLeaseRetained(invalidRun.generation());

        setUp();
        Run wrongEventRun = enqueueFixtureVerification(store, 63);
        Lease wrongEventLease = store.acquire("fixture-worker", Duration.ofMinutes(5)).orElseThrow();
        ManifestAnchors wrongEvent = stageManifestAnchors(wrongEventLease, Validation.valid(), true);
        assertThatThrownBy(() -> store.retryForDependency(wrongEventLease,
                clock.instant().plusSeconds(30), "waiting", 63, 4,
                wrongEvent.pair(), wrongEvent.live(), List.of(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("endpoint");
        assertManifestNotWrittenAndLeaseRetained(wrongEventRun.generation());

        setUp();
        Run staleLiveRun = enqueueFixtureVerification(store, 66);
        Lease staleLiveLease = store.acquire("fixture-worker", Duration.ofMinutes(5)).orElseThrow();
        ManifestAnchors staleLive = stageManifestAnchors(staleLiveLease, Validation.valid(), false);
        assertThatThrownBy(() -> store.retryForDependency(staleLiveLease,
                clock.instant().plusSeconds(30), "waiting", 66, 3,
                staleLive.pair(), staleLive.live(), List.of(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("predates");
        assertManifestNotWrittenAndLeaseRetained(staleLiveRun.generation());

        setUp();
        Run advancedRun = enqueueFixtureVerification(store, 64);
        Lease advancedLease = store.acquire("fixture-worker", Duration.ofMinutes(5)).orElseThrow();
        ManifestAnchors oldReads = stageManifestAnchors(advancedLease, Validation.valid(), true);
        clock.advance(Duration.ofSeconds(1));
        store.enqueue(SyncKind.FIXTURE_VERIFICATION, "2097-98", "fixture:64", "fixture:64",
                clock.instant().plus(Duration.ofMinutes(1)));

        assertThatThrownBy(() -> store.retryForDependency(advancedLease,
                clock.instant().plusSeconds(30), "waiting", 64, 3,
                oldReads.pair(), oldReads.live(), List.of(1)))
                .isInstanceOf(OwnedSyncStore.SourceDemandAdvancedException.class);
        assertManifestNotWrittenAndLeaseRetained(advancedRun.generation());
    }

    @Test
    void dependencyManifestAndLeaseReleaseRollBackTogether() {
        Run run = enqueueFixtureVerification(store, 65);
        Lease lease = store.acquire("fixture-worker", Duration.ofMinutes(5)).orElseThrow();
        ManifestAnchors anchors = stageManifestAnchors(lease, Validation.valid(), true);
        jdbc.execute("CREATE OR REPLACE FUNCTION fpl_fixture_manifest_rollback_test() RETURNS trigger "
                + "LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'forced manifest rollback'; "
                + "RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER fpl_fixture_manifest_rollback_test BEFORE UPDATE OF priority ON "
                + TABLES.run() + " FOR EACH ROW EXECUTE FUNCTION fpl_fixture_manifest_rollback_test()");
        try {
            assertThatThrownBy(() -> store.retryForDependency(lease,
                    clock.instant().plusSeconds(30), "waiting", 65, 3,
                    anchors.pair(), anchors.live(), List.of(1)))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS fpl_fixture_manifest_rollback_test ON " + TABLES.run());
            jdbc.execute("DROP FUNCTION IF EXISTS fpl_fixture_manifest_rollback_test()");
        }

        assertManifestNotWrittenAndLeaseRetained(run.generation());
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
        assertThatThrownBy(() -> new OwnedSyncTables("a".repeat(40), "payload", "receipt",
                "request_state", "request_attempt", "fixture_sync", "player_fixture_evidence",
                "planner_slot", "planner_runtime"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ReadinessFixture seedReadinessFixture(OwnedSyncStore target, int fixtureId, int playerId) {
        String key = FixtureSyncPublisher.publicationKey(fixtureId);
        Run run = target.enqueue(SyncKind.FIXTURE_VERIFICATION, "2097-98", key, key, clock.instant());
        Lease lease = target.acquireForSeason("2097-98", "readiness-fixture-" + fixtureId,
                Duration.ofMinutes(5)).orElseThrow();
        assertThat(lease.generation()).isEqualTo(run.generation());
        return stageReadinessManifest(target, lease, fixtureId, playerId);
    }

    private ReadinessFixture refreshReadinessFixture(OwnedSyncStore target, ReadinessFixture existing,
                                                     int fixtureId, int playerId) {
        jdbc.update("UPDATE " + TABLES.run() + " SET due_at=? WHERE generation=?",
                java.sql.Timestamp.from(clock.instant().minus(Duration.ofDays(1))), existing.generation());
        Lease lease = target.acquireForSeason("2097-98", "readiness-refresh-" + fixtureId,
                Duration.ofMinutes(5)).orElseThrow();
        assertThat(lease.generation()).isEqualTo(existing.generation());
        return stageReadinessManifest(target, lease, fixtureId, playerId);
    }

    private ReadinessFixture stageReadinessManifest(OwnedSyncStore target, Lease lease,
                                                     int fixtureId, int playerId) {
        long generation = lease.generation();
        SourceRead pairRead = new SourceRead(clock.instant(), clock.instant());
        long bootstrapId = target.stage(lease, Endpoint.bootstrap(), pairRead, Validation.valid(), bytes("bootstrap"));
        long fixturesId = target.stage(lease, Endpoint.fixtures(), pairRead, Validation.valid(), bytes("fixtures"));
        StagedPayload bootstrap = new StagedPayload(bootstrapId, generation, EndpointKind.BOOTSTRAP_STATIC,
                pairRead, ValidationState.VALID, null, bytes("bootstrap"));
        StagedPayload fixtures = new StagedPayload(fixturesId, generation, EndpointKind.FIXTURES,
                pairRead, ValidationState.VALID, null, bytes("fixtures"));

        clock.advance(Duration.ofMillis(1));
        SourceRead liveRead = new SourceRead(clock.instant(), clock.instant());
        long liveId = target.stage(lease, Endpoint.eventLive(3), liveRead, Validation.valid(), bytes("live"));
        StagedPayload live = new StagedPayload(liveId, generation, EndpointKind.EVENT_LIVE,
                liveRead, ValidationState.VALID, null, bytes("live"));
        target.retryForDependency(lease, clock.instant(), "waiting for history", fixtureId, 3,
                new PublicPayloadPair(generation, bootstrap, fixtures), live, List.of(playerId));
        return new ReadinessFixture(generation, bootstrapId, fixturesId);
    }

    private HistoryEvidence seedReadinessHistory(OwnedSyncStore target, int playerId, boolean complete) {
        String key = HistoryFixtureDispatcher.playerHistoryPublicationKey(playerId);
        Run run = target.enqueue(SyncKind.PLAYER_HISTORY, "2097-98", key, key, clock.instant());
        Lease lease = target.acquireForSeason("2097-98", "readiness-history-" + playerId,
                Duration.ofMinutes(5)).orElseThrow();
        assertThat(lease.generation()).isEqualTo(run.generation());
        SourceRead sourceRead = new SourceRead(clock.instant(), clock.instant());
        target.stage(lease, Endpoint.elementSummary(playerId), sourceRead, Validation.valid(), bytes("history"));
        if (complete) target.completeStaged(lease, sourceRead, List.of(sourceRead));
        else {
            target.retry(lease, clock.instant(), "valid retained history checkpoint");
            jdbc.update("UPDATE " + TABLES.run() + " SET priority=100 WHERE generation=?", run.generation());
        }
        return new HistoryEvidence(run.generation(), sourceRead);
    }

    private record ReadinessFixture(long generation, long bootstrapPayloadId, long fixturesPayloadId) { }

    private record HistoryEvidence(long generation, SourceRead read) { }

    private ManifestAnchors stageManifestAnchors(Lease lease, Validation bootstrapValidation,
                                                 boolean liveAfterFixtureRead) {
        SourceRead pairRead = new SourceRead(clock.instant(), clock.instant());
        store.stage(lease, Endpoint.bootstrap(), pairRead, bootstrapValidation, bytes("bootstrap"));
        store.stage(lease, Endpoint.fixtures(), pairRead, Validation.valid(), bytes("fixtures"));
        StagedPayload bootstrap = store.latestStagedPayload(lease, Endpoint.bootstrap()).orElseThrow();
        StagedPayload fixtures = store.latestStagedPayload(lease, Endpoint.fixtures()).orElseThrow();
        if (liveAfterFixtureRead) clock.advance(Duration.ofMillis(1));
        SourceRead liveRead = liveAfterFixtureRead ? new SourceRead(clock.instant(), clock.instant()) : pairRead;
        store.stage(lease, Endpoint.eventLive(3), liveRead, Validation.valid(), bytes("live"));
        StagedPayload live = store.latestStagedPayload(lease, Endpoint.eventLive(3)).orElseThrow();
        return new ManifestAnchors(new PublicPayloadPair(lease.generation(), bootstrap, fixtures), live);
    }

    private void assertManifestNotWrittenAndLeaseRetained(long generation) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.fixtureDependencyManifest()
                + " WHERE run_generation=?", Integer.class, generation)).isZero();
        assertThat(jdbc.queryForObject("SELECT state || ':' || (lease_token IS NOT NULL)::text || ':' || priority "
                + "FROM " + TABLES.run() + " WHERE generation=?", String.class, generation))
                .isEqualTo("LEASED:true:1");
    }

    private record ManifestAnchors(PublicPayloadPair pair, StagedPayload live) { }

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
        private final AtomicInteger payloadBodySelects = new AtomicInteger();

        private TrackingJdbcTemplate(DataSource dataSource) {
            super(dataSource);
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            List<T> rows = super.query(sql, rowMapper, args);
            if (sql.toLowerCase(java.util.Locale.ROOT).contains("body")) payloadBodySelects.incrementAndGet();
            if (sql.contains("p.body")) hydratedBodies.addAndGet(rows.size());
            return rows;
        }

        private int hydratedBodies() {
            return hydratedBodies.get();
        }

        private int payloadBodySelects() {
            return payloadBodySelects.get();
        }

        private void resetHydratedBodies() {
            hydratedBodies.set(0);
            payloadBodySelects.set(0);
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
