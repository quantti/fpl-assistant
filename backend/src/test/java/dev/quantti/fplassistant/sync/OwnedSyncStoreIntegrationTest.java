package dev.quantti.fplassistant.sync;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import dev.quantti.fplassistant.sync.OwnedSyncStore.Endpoint;
import dev.quantti.fplassistant.sync.OwnedSyncStore.EndpointKind;
import dev.quantti.fplassistant.sync.OwnedSyncStore.FutureFloor;
import dev.quantti.fplassistant.sync.OwnedSyncStore.Lease;
import dev.quantti.fplassistant.sync.OwnedSyncStore.LeaseLostException;
import dev.quantti.fplassistant.sync.OwnedSyncStore.PayloadRetentionLimitException;
import dev.quantti.fplassistant.sync.OwnedSyncStore.PublicPayloadPair;
import dev.quantti.fplassistant.sync.OwnedSyncStore.Ready;
import dev.quantti.fplassistant.sync.OwnedSyncStore.RuntimeFenceLostException;
import dev.quantti.fplassistant.sync.OwnedSyncStore.Run;
import dev.quantti.fplassistant.sync.OwnedSyncStore.SourceRead;
import dev.quantti.fplassistant.sync.OwnedSyncStore.StagedPayload;
import dev.quantti.fplassistant.sync.OwnedSyncStore.StaleGenerationException;
import dev.quantti.fplassistant.sync.OwnedSyncStore.SyncKind;
import dev.quantti.fplassistant.sync.OwnedSyncStore.Validation;
import dev.quantti.fplassistant.sync.OwnedSyncStore.ValidationState;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    void historyHydrationCursorRequiresTransactionAndUsesSmallForwardOnlyFetchWindow() throws Exception {
        String sql = "SELECT ?";
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.getAutoCommit()).thenReturn(false);
        when(connection.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY))
                .thenReturn(statement);

        var creator = new OwnedSyncStore.HistoryHydrationStatement(sql, List.of(72));
        assertThat(creator.createPreparedStatement(connection)).isSameAs(statement);

        verify(connection).getAutoCommit();
        verify(connection).prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        verify(statement).setFetchSize(OwnedSyncStore.HISTORY_HYDRATION_FETCH_SIZE);
        verify(statement).setObject(1, 72);
        assertThat(OwnedSyncStore.HISTORY_HYDRATION_FETCH_SIZE).isBetween(1, 80);

        when(connection.getAutoCommit()).thenReturn(true);
        assertThatThrownBy(() -> creator.createPreparedStatement(connection))
                .isInstanceOf(SQLException.class).hasMessageContaining("active transaction");
    }

    @Test
    void hydratesAllSeventyTwoHistoryBodiesOnceIntoFinalDocuments() {
        String season = "2097-98";
        Instant floor = clock.instant();
        Run fixtureRun = store.enqueue(SyncKind.FIXTURE_VERIFICATION, season,
                "fixture:9001", "fixture:9001", floor);
        Lease fixtureLease = store.acquire("fixture-worker", Duration.ofMinutes(5))
                .filter(candidate -> candidate.generation() == fixtureRun.generation()).orElseThrow();
        int bodyBytes = 64 * 1024;
        SourceRead read = new SourceRead(floor, floor);
        Map<Integer, OwnedSyncStore.StagedPayloadMetadata> candidates = new LinkedHashMap<>();
        for (int playerId = 1; playerId <= 72; playerId++) {
            String key = HistoryFixtureDispatcher.playerHistoryPublicationKey(playerId);
            Run historyRun = store.enqueue(SyncKind.PLAYER_HISTORY, season, key, key, floor);
            Lease historyLease = store.acquire("history-worker", Duration.ofMinutes(5))
                    .filter(candidate -> candidate.generation() == historyRun.generation()).orElseThrow();
            store.stage(historyLease, Endpoint.elementSummary(playerId), read,
                    Validation.valid(), historyBody(playerId));
            store.completeStaged(historyLease, read, List.of(read));
            candidates.put(playerId, store.latestValidPayloadMetadata(season,
                    Endpoint.elementSummary(playerId), floor).orElseThrow());
        }

        PayloadReadProbe probe = new PayloadReadProbe(jdbc.getDataSource());
        OwnedSyncStore tracked = new OwnedSyncStore(probe, tx, clock, TABLES);
        var hydration = tracked.hydrateValidHistoryBatch(fixtureLease, candidates, floor);

        assertThat(hydration.complete()).isTrue();
        assertThat(hydration.documents()).hasSize(72);
        for (int playerId = 1; playerId <= 72; playerId++) {
            var document = hydration.documents().get(playerId);
            assertThat(document.bytes()).containsExactly(historyBody(playerId));
            assertThat(document.readInterval().startedAt()).isEqualTo(floor);
            assertThat(document.readInterval().completedAt()).isEqualTo(floor);
        }
        var logicalRead = probe.snapshot().bodyFamily(PayloadReadProbe.BodyFamily.CROSS_RUN_HISTORIES);
        assertThat(logicalRead).isEqualTo(new PayloadReadProbe.FamilyMetrics(1, 72, 72, 72L * bodyBytes));
        assertThat(probe.snapshot().maxBodyBytesInSingleQuery(
                PayloadReadProbe.BodyFamily.CROSS_RUN_HISTORIES)).isEqualTo(72L * bodyBytes);
    }

    @Test
    void publicationBindsReceiptHashesToLockedDatabaseSourceBodies() {
        String season = "2097-98";
        Run run = store.enqueue(SyncKind.CATALOGUE_SCHEDULE, season,
                CatalogueScheduleDispatcher.SCHEDULE_COALESCING_KEY,
                CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY, clock.instant());
        Lease lease = store.acquire("source-worker", Duration.ofMinutes(5))
                .filter(candidate -> candidate.generation() == run.generation()).orElseThrow();
        byte[] bootstrap = bytes("bootstrap source");
        byte[] fixtures = bytes("fixtures source");
        byte[] wrongBootstrap = bytes("wrong bootstrap source");
        byte[] wrongFixtures = bytes("wrong fixtures source");
        SourceRead sourceRead = new SourceRead(clock.instant(), clock.instant());
        long bootstrapId = store.stage(lease, Endpoint.bootstrap(), sourceRead, Validation.valid(), bootstrap);
        long fixturesId = store.stage(lease, Endpoint.fixtures(), sourceRead, Validation.valid(), fixtures);
        var wrongPair = OwnedSyncStore.CanonicalSourcePair.from(
                new OwnedSyncStore.StagedPayload(bootstrapId, run.generation(), EndpointKind.BOOTSTRAP_STATIC,
                        sourceRead, ValidationState.VALID, null, wrongBootstrap),
                new OwnedSyncStore.StagedPayload(fixturesId, run.generation(), EndpointKind.FIXTURES,
                        sourceRead, ValidationState.VALID, null, wrongFixtures));

        assertThatThrownBy(() -> store.publish(lease, sourceRead, List.of(sourceRead, sourceRead), wrongPair, () -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("payload bodies changed");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.payload()
                + " WHERE id IN (?,?) AND source_published", Integer.class, bootstrapId, fixturesId)).isZero();

        var sourcePair = OwnedSyncStore.CanonicalSourcePair.from(
                new OwnedSyncStore.StagedPayload(bootstrapId, run.generation(), EndpointKind.BOOTSTRAP_STATIC,
                        sourceRead, ValidationState.VALID, null, bootstrap),
                new OwnedSyncStore.StagedPayload(fixturesId, run.generation(), EndpointKind.FIXTURES,
                        sourceRead, ValidationState.VALID, null, fixtures));
        store.publish(lease, sourceRead, List.of(sourceRead, sourceRead), sourcePair, () -> null);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.payload()
                + " WHERE id IN (?,?) AND source_published", Integer.class, bootstrapId, fixturesId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT source_bootstrap_sha256 FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", String.class, season,
                CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY)).isEqualTo(OwnedSyncStore.sha256(bootstrap));
        assertThat(jdbc.queryForObject("SELECT source_fixtures_sha256 FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", String.class, season,
                CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY)).isEqualTo(OwnedSyncStore.sha256(fixtures));
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
    void unchangedOrOlderHistoryDemandPreservesAnHttpCooldownButNewerDemandCanAdvanceIt() {
        Instant initialFloor = clock.instant().minusSeconds(60);
        Run run = store.ensurePlayerHistoryAtLeast("2097-98", 76, initialFloor);
        Lease lease = store.acquire("history-worker", Duration.ofMinutes(5)).orElseThrow();
        Instant cooldown = clock.instant().plus(Duration.ofMinutes(30));
        store.retry(lease, cooldown, "HTTP admission cooldown");

        store.ensurePlayerHistoryAtLeast("2097-98", 76, initialFloor);
        assertThat(jdbc.queryForObject("SELECT due_at FROM " + TABLES.run()
                + " WHERE generation=?", java.sql.Timestamp.class, run.generation()).toInstant())
                .isEqualTo(cooldown);

        store.ensurePlayerHistoryAtLeast("2097-98", 76, initialFloor.minusSeconds(30));
        assertThat(jdbc.queryForObject("SELECT due_at FROM " + TABLES.run()
                + " WHERE generation=?", java.sql.Timestamp.class, run.generation()).toInstant())
                .isEqualTo(cooldown);
        assertThat(jdbc.queryForObject("SELECT requested_source_after FROM " + TABLES.run()
                + " WHERE generation=?", java.sql.Timestamp.class, run.generation()).toInstant())
                .isEqualTo(initialFloor);

        Instant newerFloor = initialFloor.plusSeconds(30);
        store.ensurePlayerHistoryAtLeast("2097-98", 76, newerFloor);
        assertThat(jdbc.queryForObject("SELECT due_at FROM " + TABLES.run()
                + " WHERE generation=?", java.sql.Timestamp.class, run.generation()).toInstant())
                .isEqualTo(newerFloor);
        assertThat(jdbc.queryForObject("SELECT requested_source_after FROM " + TABLES.run()
                + " WHERE generation=?", java.sql.Timestamp.class, run.generation()).toInstant())
                .isEqualTo(newerFloor);
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
    void fairClaimsSelectReadyFixtureAndUntouchedFixtureWithinTheirTurnBounds() {
        OwnedSyncStore fair = fencedStore();
        List<Long> blocked = new ArrayList<>();
        for (int fixtureId = 1; fixtureId <= 11; fixtureId++) {
            blocked.add(seedPartialFixture(fair, fixtureId, clock.instant().minusSeconds(12 - fixtureId)).generation());
        }

        ReadinessFixture ready = seedReadinessFixture(fair, 12, 9_012);
        seedReadinessHistory(fair, 9_012, true);
        for (int playerId = 10_000; playerId < 10_020; playerId++) {
            fair.enqueue(SyncKind.PLAYER_HISTORY, "2097-98", "player-history:" + playerId,
                    "player-history:" + playerId, clock.instant());
        }
        Run untouched = enqueueFixtureVerification(fair, 13);
        resetAttemptsForCurrentEpoch();

        List<Lease> claims = new ArrayList<>();
        for (int claim = 0; claim < 8; claim++) {
            claims.add(fair.acquireFairForSeason("2097-98", "fair-worker-" + claim,
                    Duration.ofMinutes(5)).orElseThrow());
        }

        assertThat(claims.get(0).kind()).isEqualTo(SyncKind.PLAYER_HISTORY);
        assertThat(claims.get(1).generation()).as("ready fixture despite eleven older blocked fixtures")
                .isEqualTo(ready.generation());
        assertThat(claims.get(1).generation()).isNotIn(blocked);
        assertThat(claims.get(3).generation()).isIn(blocked);
        assertThat(claims.get(7).generation()).as("untouched fixture by claim eight")
                .isEqualTo(untouched.generation());
        assertThat(claims).allSatisfy(lease -> assertThat(lease.attemptCount()).isEqualTo(1));
    }

    @Test
    void preferentialHistoryTurnSelectsLateManifestDemandThenPlainTurnKeepsHistoryFifo() {
        OwnedSyncStore fair = fencedStore();
        int satisfiedPlayer = 12_001;
        int missingPlayer = 12_002;
        ReadinessFixture fixture = seedReadinessFixture(fair, 91, List.of(satisfiedPlayer, missingPlayer));
        Instant fixtureFloor = jdbc.queryForObject("SELECT requested_source_after FROM " + TABLES.run()
                + " WHERE generation=?", java.sql.Timestamp.class, fixture.generation()).toInstant();
        jdbc.update("UPDATE " + TABLES.run() + " SET due_at=? WHERE generation=?",
                java.sql.Timestamp.from(clock.instant().plus(Duration.ofMinutes(2))), fixture.generation());

        HistoryEvidence satisfiedEvidence = seedReadinessHistory(fair, satisfiedPlayer, true);
        List<Run> broadRoster = new ArrayList<>();
        for (int playerId = 10_000; playerId < 10_005; playerId++) {
            broadRoster.add(enqueueHistory(fair, playerId));
        }
        Run alreadySatisfiedReread = fair.ensurePlayerHistoryAtLeast("2097-98", satisfiedPlayer, fixtureFloor);
        jdbc.update("UPDATE " + TABLES.run() + " SET due_at=? WHERE generation=?",
                java.sql.Timestamp.from(clock.instant()), alreadySatisfiedReread.generation());
        Run lateDependency = enqueueHistory(fair, missingPlayer);
        resetAttemptsForCurrentEpoch();

        Lease preferential = fair.acquireFairForSeason("2097-98", "preferred-history", Duration.ofMinutes(5))
                .orElseThrow();

        assertThat(lateDependency.generation()).isGreaterThan(alreadySatisfiedReread.generation());
        assertThat(lateDependency.generation()).isGreaterThan(broadRoster.getLast().generation());
        assertThat(preferential.generation()).isEqualTo(lateDependency.generation());
        assertThat(preferential.generation()).isNotEqualTo(alreadySatisfiedReread.generation());
        assertThat(satisfiedEvidence.read().startedAt()).isAfter(fixtureFloor);
        assertThat(jdbc.queryForObject("SELECT due_at FROM " + TABLES.run()
                + " WHERE generation=?", java.sql.Timestamp.class, fixture.generation()).toInstant())
                .isAfter(clock.instant());

        Lease plainHistory = fair.acquireFairForSeason("2097-98", "plain-history", Duration.ofMinutes(5))
                .orElseThrow();
        assertThat(plainHistory.generation()).isEqualTo(broadRoster.getFirst().generation());
    }

    @Test
    void preferentialHistoryUsesOldestManifestBeforeHistoryFifoOnSecondPreferredSlot() {
        OwnedSyncStore fair = fencedStore();
        ReadinessFixture oldestManifest = seedReadinessFixture(fair, 88, 13_001);
        ReadinessFixture newerManifest = seedReadinessFixture(fair, 7, 13_002);
        Run newerManifestHistory = enqueueHistory(fair, 13_002);
        Run oldestManifestHistory = enqueueHistory(fair, 13_001);
        resetAttemptsForCurrentEpoch();
        jdbc.update("UPDATE " + TABLES.run() + " SET attempt_count=4 WHERE generation=?",
                oldestManifestHistory.generation());

        Lease preferred = fair.acquireFairForSeason("2097-98", "second-preferred-history",
                Duration.ofMinutes(5)).orElseThrow();

        assertThat(oldestManifest.generation()).isLessThan(newerManifest.generation());
        assertThat(oldestManifestHistory.generation()).isGreaterThan(newerManifestHistory.generation());
        assertThat(preferred.generation()).isEqualTo(oldestManifestHistory.generation());
    }

    @Test
    void sustainedManifestPreferencePreservesRotationAndPlainHistoryFifoAcrossStores() {
        var fence = new PlannerRuntimeRepository(jdbc, tx, clock, TABLES)
                .activate("2097-98", OwnedSyncMode.STAGING);
        TrackingJdbcTemplate tracking = new TrackingJdbcTemplate(jdbc.getDataSource());
        OwnedSyncStore firstStore = new OwnedSyncStore(tracking, tx, clock, TABLES, fence);
        OwnedSyncStore secondStore = new OwnedSyncStore(tracking, tx, clock, TABLES, fence);

        int demandedFirstPlayer = 12_101;
        int demandedSecondPlayer = 12_102;
        List<ReadinessFixture> demandedFixtures = List.of(
                seedReadinessFixture(firstStore, 111, List.of(demandedFirstPlayer, demandedSecondPlayer)),
                seedReadinessFixture(firstStore, 112, List.of(demandedFirstPlayer, demandedSecondPlayer)));
        jdbc.update("UPDATE " + TABLES.run() + " SET priority=100 WHERE generation IN (?, ?)",
                demandedFixtures.get(0).generation(), demandedFixtures.get(1).generation());
        for (int playerId : List.of(demandedFirstPlayer, demandedSecondPlayer)) {
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM %s m
                    CROSS JOIN LATERAL unnest(m.required_player_ids) AS required(player_id)
                    WHERE m.run_generation IN (?, ?) AND required.player_id=?
                    """.formatted(TABLES.fixtureDependencyManifest()), Integer.class,
                    demandedFixtures.get(0).generation(), demandedFixtures.get(1).generation(), playerId))
                    .as("overlapping manifests produce duplicate candidates for player %d", playerId).isEqualTo(2);
        }

        int readyPlayer = 12_103;
        List<ReadinessFixture> ready = new ArrayList<>();
        for (int fixtureId = 113; fixtureId < 119; fixtureId++) {
            ready.add(seedReadinessFixture(firstStore, fixtureId, readyPlayer));
        }
        seedReadinessHistory(firstStore, readyPlayer, true);

        List<Run> ordinaryHistories = new ArrayList<>();
        for (int playerId = 12_110; playerId < 12_116; playerId++) {
            ordinaryHistories.add(enqueueHistory(firstStore, playerId));
        }
        Run demandedFirst = enqueueHistory(firstStore, demandedFirstPlayer);
        enqueueHistory(firstStore, demandedSecondPlayer);

        List<Run> blocked = new ArrayList<>();
        for (int fixtureId = 119; fixtureId < 122; fixtureId++) {
            blocked.add(seedPartialFixture(firstStore, fixtureId, clock.instant()));
        }
        List<Run> untouched = new ArrayList<>();
        for (int fixtureId = 122; fixtureId < 125; fixtureId++) {
            untouched.add(enqueueFixtureVerification(firstStore, fixtureId));
        }

        resetAttemptsForCurrentEpoch();
        Lease warmup = firstStore.acquireFairForSeason("2097-98", "warmup-preference",
                Duration.ofMinutes(5)).orElseThrow();
        assertThat(warmup.generation()).isEqualTo(demandedFirst.generation());
        assertThat(warmup.attemptCount()).isOne();
        firstStore.retry(warmup, clock.instant(), "keep the missing manifest history due");
        assertThatThrownBy(() -> firstStore.retry(warmup, clock.instant(), "stale preference lease"))
                .isInstanceOf(LeaseLostException.class);
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.run()
                + " WHERE generation=?", String.class, demandedFirst.generation())).isEqualTo("PENDING");

        int preferenceAttempts = 1;
        List<OwnedSyncStore> stores = List.of(firstStore, secondStore);
        for (int rotation = 0; rotation < 3; rotation++) {
            List<Long> expectedGenerations = List.of(
                    ready.get(rotation * 2).generation(), ordinaryHistories.get(rotation * 2).generation(),
                    blocked.get(rotation).generation(), demandedFirst.generation(),
                    ready.get(rotation * 2 + 1).generation(), ordinaryHistories.get(rotation * 2 + 1).generation(),
                    untouched.get(rotation).generation(), demandedFirst.generation());
            for (int slot = 0; slot < expectedGenerations.size(); slot++) {
                OwnedSyncStore claimant = stores.get((rotation * 8 + slot) % stores.size());
                Lease lease = claimant.acquireFairForSeason("2097-98",
                        "rotation-" + rotation + "-slot-" + slot, Duration.ofMinutes(5)).orElseThrow();
                assertThat(lease.generation()).as("rotation %d slot %d", rotation + 1, slot + 1)
                        .isEqualTo(expectedGenerations.get(slot));
                assertThat(lease.kind()).as("rotation %d slot %d class", rotation + 1, slot + 1)
                        .isEqualTo(slot == 0 || slot == 2 || slot == 4 || slot == 6
                                ? SyncKind.FIXTURE_VERIFICATION : SyncKind.PLAYER_HISTORY);
                if (slot == 3 || slot == 7) {
                    preferenceAttempts++;
                    assertThat(lease.attemptCount()).isEqualTo(preferenceAttempts);
                    claimant.retry(lease, clock.instant(), "keep the missing manifest history due");
                    assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.run()
                            + " WHERE generation=?", String.class, demandedFirst.generation()))
                            .isEqualTo("PENDING");
                } else {
                    assertThat(lease.attemptCount()).isOne();
                }
            }
        }

        assertThat(preferenceAttempts).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM " + TABLES.run()
                + " WHERE generation=?", Integer.class, demandedFirst.generation())).isEqualTo(7);
        assertThat(tracking.payloadBodySelects()).isZero();
        assertThat(tracking.hydratedBodies()).isZero();
    }

    @Test
    void invalidEpochFutureFloorTerminalDeletedAndMisKeyedManifestsDoNotPreferHistory() {
        OwnedSyncStore fair = fencedStore();
        Run oldestBroadHistory = enqueueHistory(fair, 14_000);
        enqueueHistory(fair, 14_001);

        ReadinessFixture invalid = seedReadinessFixture(fair, 101, 14_101);
        jdbc.update("UPDATE " + TABLES.payload() + " SET validation_state='INVALID' WHERE id=?",
                invalid.bootstrapPayloadId());

        ReadinessFixture wrongEpoch = seedReadinessFixture(fair, 102, 14_102);
        jdbc.update("UPDATE " + TABLES.run() + " SET runtime_epoch=runtime_epoch+1 WHERE generation=?",
                wrongEpoch.generation());

        ReadinessFixture futureFloor = seedReadinessFixture(fair, 103, 14_103);
        jdbc.update("UPDATE " + TABLES.fixtureDependencyManifest() + " SET source_floor=? WHERE run_generation=?",
                java.sql.Timestamp.from(clock.instant().plus(Duration.ofMinutes(1))), futureFloor.generation());

        ReadinessFixture deletedAnchor = seedReadinessFixture(fair, 104, 14_104);
        jdbc.update("DELETE FROM " + TABLES.payload() + " WHERE id=?", deletedAnchor.fixturesPayloadId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.fixtureDependencyManifest()
                + " WHERE run_generation=?", Integer.class, deletedAnchor.generation())).isZero();

        ReadinessFixture misKeyedAnchor = seedReadinessFixture(fair, 105, 14_105);
        jdbc.update("UPDATE " + TABLES.fixtureDependencyManifest()
                + " SET bootstrap_payload_id=fixtures_payload_id WHERE run_generation=?",
                misKeyedAnchor.generation());

        ReadinessFixture futureRead = seedReadinessFixture(fair, 107, 14_107);
        Instant futureReadAt = clock.instant().plus(Duration.ofMinutes(1));
        jdbc.update("UPDATE " + TABLES.payload() + " SET read_started_at=?, read_finished_at=? WHERE id=?",
                java.sql.Timestamp.from(futureReadAt), java.sql.Timestamp.from(futureReadAt),
                futureRead.fixturesPayloadId());
        jdbc.update("UPDATE " + TABLES.payload() + " SET read_started_at=?, read_finished_at=? WHERE id="
                + "(SELECT live_payload_id FROM " + TABLES.fixtureDependencyManifest()
                + " WHERE run_generation=?)", java.sql.Timestamp.from(futureReadAt.plusMillis(1)),
                java.sql.Timestamp.from(futureReadAt.plusMillis(1)), futureRead.generation());

        ReadinessFixture terminal = seedReadinessFixture(fair, 106, 14_106);
        jdbc.update("UPDATE " + TABLES.run() + " SET priority=0 WHERE generation=?", terminal.generation());
        Lease terminalLease = fair.acquireForSeason("2097-98", "terminal-fixture", Duration.ofMinutes(5))
                .orElseThrow();
        assertThat(terminalLease.generation()).isEqualTo(terminal.generation());
        fair.fail(terminalLease, "terminal manifest test");

        List<Run> unusableDependencies = List.of(
                enqueueHistory(fair, 14_101), enqueueHistory(fair, 14_102),
                enqueueHistory(fair, 14_103), enqueueHistory(fair, 14_104),
                enqueueHistory(fair, 14_105), enqueueHistory(fair, 14_106), enqueueHistory(fair, 14_107));
        resetAttemptsForCurrentEpoch();

        Lease first = fair.acquireFairForSeason("2097-98", "unusable-manifests", Duration.ofMinutes(5))
                .orElseThrow();

        assertThat(unusableDependencies.stream().map(Run::generation).toList())
                .allSatisfy(generation -> assertThat(generation).isGreaterThan(oldestBroadHistory.generation()));
        assertThat(first.generation()).isEqualTo(oldestBroadHistory.generation());
    }

    @Test
    void readyFixtureSelectionUsesDueTimeBeforePriority() {
        OwnedSyncStore fair = fencedStore();
        ReadinessFixture older = seedReadinessFixture(fair, 19, 9_019);
        seedReadinessHistory(fair, 9_019, true);
        ReadinessFixture newer = seedReadinessFixture(fair, 20, 9_020);
        seedReadinessHistory(fair, 9_020, true);
        Instant olderDue = clock.instant().minusSeconds(20);
        Instant newerDue = clock.instant().minusSeconds(10);
        jdbc.update("UPDATE " + TABLES.run() + " SET priority=4, due_at=? WHERE generation=?",
                java.sql.Timestamp.from(olderDue), older.generation());
        jdbc.update("UPDATE " + TABLES.run() + " SET priority=1, due_at=? WHERE generation=?",
                java.sql.Timestamp.from(newerDue), newer.generation());

        assertThat(fair.readyFixtureGenerations("2097-98", 2).stream().map(Run::generation).toList())
                .containsExactly(older.generation(), newer.generation());

        resetAttemptsForCurrentEpoch();
        Lease claimed = fair.acquireFairForSeason("2097-98", "ready-order", Duration.ofMinutes(5))
                .orElseThrow();
        assertThat(claimed.generation()).isEqualTo(older.generation());
    }

    @Test
    void fairReadyFixtureClaimsUseDueTimeBeforePriority() {
        OwnedSyncStore fair = fencedStore();
        ReadinessFixture older = seedReadinessFixture(fair, 29, 9_029);
        seedReadinessHistory(fair, 9_029, true);
        ReadinessFixture newer = seedReadinessFixture(fair, 30, 9_030);
        seedReadinessHistory(fair, 9_030, true);
        jdbc.update("UPDATE " + TABLES.run() + " SET priority=4, due_at=? WHERE generation=?",
                java.sql.Timestamp.from(clock.instant().minusSeconds(20)), older.generation());
        jdbc.update("UPDATE " + TABLES.run() + " SET priority=1, due_at=? WHERE generation=?",
                java.sql.Timestamp.from(clock.instant().minusSeconds(10)), newer.generation());
        resetAttemptsForCurrentEpoch();

        Lease claimed = fair.acquireFairForSeason("2097-98", "fair-ready-order", Duration.ofMinutes(5))
                .orElseThrow();

        assertThat(claimed.generation()).isEqualTo(older.generation());
    }

    @Test
    void canonicalClaimsPreemptRotationIncludingAnExpiredExclusiveLease() {
        OwnedSyncStore fair = fencedStore();
        ReadinessFixture ready = seedReadinessFixture(fair, 14, 9_014);
        seedReadinessHistory(fair, 9_014, true);
        Run history = fair.enqueue(SyncKind.PLAYER_HISTORY, "2097-98", "player-history:10014",
                "player-history:10014", clock.instant());
        resetAttemptsForCurrentEpoch();

        fair.enqueue(SyncKind.CATALOGUE_SCHEDULE, "2097-98",
                CatalogueScheduleDispatcher.CATALOGUE_PRICE_COALESCING_KEY,
                CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY, clock.instant());
        fair.enqueue(SyncKind.CATALOGUE_SCHEDULE, "2097-98",
                CatalogueScheduleDispatcher.SCHEDULE_COALESCING_KEY,
                CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY, clock.instant());
        Lease original = fair.acquireForSeason("2097-98", "catalogue-exclusive", Duration.ofMinutes(5))
                .orElseThrow();
        jdbc.update("UPDATE " + TABLES.run() + " SET lease_expires_at=? WHERE generation=?",
                java.sql.Timestamp.from(clock.instant().minusMillis(1)), original.generation());

        Lease first = fair.acquireFairForSeason("2097-98", "canonical-one", Duration.ofMinutes(5))
                .orElseThrow();
        Lease second = fair.acquireFairForSeason("2097-98", "canonical-two", Duration.ofMinutes(5))
                .orElseThrow();
        Lease third = fair.acquireFairForSeason("2097-98", "noncanonical-three", Duration.ofMinutes(5))
                .orElseThrow();

        assertThat(first.kind()).isEqualTo(SyncKind.CATALOGUE_SCHEDULE);
        assertThat(second.kind()).isEqualTo(SyncKind.CATALOGUE_SCHEDULE);
        assertThat(Set.of(first.coalescingKey(), second.coalescingKey())).containsExactlyInAnyOrder(
                CatalogueScheduleDispatcher.CATALOGUE_PRICE_COALESCING_KEY,
                CatalogueScheduleDispatcher.SCHEDULE_COALESCING_KEY);
        assertThat(first.generation()).isEqualTo(original.generation());
        assertThat(first.token()).isNotEqualTo(original.token());
        assertThat(first.fence()).isGreaterThan(original.fence());
        assertThat(third.generation()).isEqualTo(history.generation());
        assertThat(third.generation()).isNotEqualTo(ready.generation());
    }

    @Test
    void futureFloorPreferredWorkYieldsAndExpiredPreferredLeaseCanBeTakenOver() {
        OwnedSyncStore fair = fencedStore();
        ReadinessFixture ready = seedReadinessFixture(fair, 15, 9_015);
        seedReadinessHistory(fair, 9_015, true);
        Run futureHistory = fair.enqueue(SyncKind.PLAYER_HISTORY, "2097-98", "player-history:10015",
                "player-history:10015", clock.instant());
        jdbc.update("UPDATE " + TABLES.run() + " SET requested_source_after=? WHERE generation=?",
                java.sql.Timestamp.from(clock.instant().plusSeconds(30)), futureHistory.generation());
        resetAttemptsForCurrentEpoch();

        Lease borrowed = fair.acquireFairForSeason("2097-98", "borrow-ready", Duration.ofMinutes(5))
                .orElseThrow();
        assertThat(borrowed.generation()).isEqualTo(ready.generation());
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM " + TABLES.run()
                + " WHERE generation=?", Integer.class, futureHistory.generation())).isZero();

        setUp();
        OwnedSyncStore expiredFair = fencedStore();
        Run history = expiredFair.enqueue(SyncKind.PLAYER_HISTORY, "2097-98", "player-history:10016",
                "player-history:10016", clock.instant());
        Lease beforeExpiry = expiredFair.acquireForSeason("2097-98", "expired-old", Duration.ofMinutes(5))
                .orElseThrow();
        jdbc.update("UPDATE " + TABLES.run() + " SET lease_expires_at=?,attempt_count=0 WHERE generation=?",
                java.sql.Timestamp.from(clock.instant().minusMillis(1)), history.generation());

        Lease recovered = expiredFair.acquireFairForSeason("2097-98", "expired-new", Duration.ofMinutes(5))
                .orElseThrow();
        assertThat(recovered.generation()).isEqualTo(history.generation());
        assertThat(recovered.owner()).isEqualTo("expired-new");
        assertThat(recovered.token()).isNotEqualTo(beforeExpiry.token());
        assertThat(recovered.fence()).isGreaterThan(beforeExpiry.fence());
    }

    @Test
    void rotationIsSharedAcrossStoresSurvivesRestartAndResetsAtEpochChange() throws Exception {
        var firstFence = new PlannerRuntimeRepository(jdbc, tx, clock, TABLES)
                .activate("2097-98", OwnedSyncMode.STAGING);
        OwnedSyncStore firstStore = new OwnedSyncStore(jdbc, tx, clock, TABLES, firstFence);
        ReadinessFixture firstReady = seedReadinessFixture(firstStore, 16, 9_016);
        seedReadinessHistory(firstStore, 9_016, true);
        ReadinessFixture firstDemand = seedReadinessFixture(firstStore, 19, List.of(10_150, 10_151));
        ReadinessFixture secondDemand = seedReadinessFixture(firstStore, 20, List.of(10_150, 10_151));
        List<Run> ordinaryHistories = new ArrayList<>();
        for (int playerId = 10_100; playerId < 10_110; playerId++) {
            ordinaryHistories.add(enqueueHistory(firstStore, playerId));
        }
        Run demandedHistory = enqueueHistory(firstStore, 10_150);
        enqueueHistory(firstStore, 10_151);
        assertThat(firstDemand.generation()).isLessThan(secondDemand.generation());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM %s m
                CROSS JOIN LATERAL unnest(m.required_player_ids) AS required(player_id)
                WHERE m.run_generation IN (?, ?) AND required.player_id=?
                """.formatted(TABLES.fixtureDependencyManifest()), Integer.class,
                firstDemand.generation(), secondDemand.generation(), 10_150)).isEqualTo(2);
        resetAttemptsForCurrentEpoch();
        OwnedSyncStore competingStore = new OwnedSyncStore(jdbc, tx, clock, TABLES, firstFence);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Lease> left = executor.submit(() -> {
                start.await();
                return firstStore.acquireFairForSeason("2097-98", "left", Duration.ofMinutes(5)).orElseThrow();
            });
            Future<Lease> right = executor.submit(() -> {
                start.await();
                return competingStore.acquireFairForSeason("2097-98", "right", Duration.ofMinutes(5))
                        .orElseThrow();
            });
            start.countDown();
            List<Lease> claims = List.of(left.get(), right.get());
            assertThat(claims).extracting(Lease::kind)
                    .containsExactlyInAnyOrder(SyncKind.PLAYER_HISTORY, SyncKind.FIXTURE_VERIFICATION);
            assertThat(claims).extracting(Lease::generation).contains(firstReady.generation(), demandedHistory.generation())
                    .doesNotHaveDuplicates();
        }

        OwnedSyncStore restarted = new OwnedSyncStore(jdbc, tx, clock, TABLES, firstFence);
        Lease afterRestart = restarted.acquireFairForSeason("2097-98", "after-restart", Duration.ofMinutes(5))
                .orElseThrow();
        assertThat(afterRestart.kind()).isEqualTo(SyncKind.PLAYER_HISTORY);
        assertThat(afterRestart.generation()).isEqualTo(ordinaryHistories.getFirst().generation());

        var nextFence = new PlannerRuntimeRepository(jdbc, tx, clock, TABLES)
                .activate("2097-98", OwnedSyncMode.STAGING);
        OwnedSyncStore nextEpoch = new OwnedSyncStore(jdbc, tx, clock, TABLES, nextFence);
        ReadinessFixture nextReady = seedReadinessFixture(nextEpoch, 17, 9_017);
        seedReadinessHistory(nextEpoch, 9_017, true);
        Run nextBlocked = seedPartialFixture(nextEpoch, 18, clock.instant());
        Run nextHistory = nextEpoch.enqueue(SyncKind.PLAYER_HISTORY, "2097-98", "player-history:10118",
                "player-history:10118", clock.instant());
        resetAttemptsForCurrentEpoch();

        Lease firstNextEpoch = nextEpoch.acquireFairForSeason("2097-98", "new-epoch", Duration.ofMinutes(5))
                .orElseThrow();
        assertThat(firstNextEpoch.generation()).isEqualTo(nextHistory.generation());
        assertThat(firstNextEpoch.generation()).isNotEqualTo(nextReady.generation());
        assertThat(firstNextEpoch.generation()).isNotEqualTo(nextBlocked.generation());
    }

    @Test
    void dependencyManifestCanBeRecordedWithoutReleasingLeaseOrBypassingMissingHistory() {
        Run run = enqueueFixtureVerification(store, 69);
        Lease lease = store.acquire("fixture-worker", Duration.ofMinutes(5)).orElseThrow();
        ManifestAnchors anchors = stageManifestAnchors(lease, Validation.valid(), true);

        store.recordDependencyManifest(lease, 69, 3, anchors.pair(), anchors.live(), List.of(1));

        assertThat(jdbc.queryForObject("SELECT state || ':' || (lease_token IS NOT NULL)::text || ':' || priority "
                + "FROM " + TABLES.run() + " WHERE generation=?", String.class, run.generation()))
                .isEqualTo("LEASED:true:1");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.fixtureDependencyManifest()
                + " WHERE run_generation=?", Integer.class, run.generation())).isOne();
        assertThat(store.readyFixtureGenerations("2097-98", 1)).isEmpty();
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
    void leaseExpiryDuringLockedSourceValidationRollsBackWriterReceiptAndCompletion() throws Exception {
        Run run = store.enqueue(SyncKind.CATALOGUE_SCHEDULE, "2097-98",
                CatalogueScheduleDispatcher.SCHEDULE_COALESCING_KEY,
                CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY, clock.instant());
        Lease lease = store.acquire("source-worker", Duration.ofMinutes(1)).orElseThrow();
        assertThat(lease.generation()).isEqualTo(run.generation());
        SourceRead sourceRead = new SourceRead(clock.instant(), clock.instant());
        byte[] bootstrap = bytes("lease-fenced bootstrap");
        byte[] fixtures = bytes("lease-fenced fixtures");
        long bootstrapId = store.stage(lease, Endpoint.bootstrap(), sourceRead, Validation.valid(), bootstrap);
        long fixturesId = store.stage(lease, Endpoint.fixtures(), sourceRead, Validation.valid(), fixtures);
        var sourcePair = OwnedSyncStore.CanonicalSourcePair.from(
                new StagedPayload(bootstrapId, lease.generation(), EndpointKind.BOOTSTRAP_STATIC,
                        sourceRead, ValidationState.VALID, null, bootstrap),
                new StagedPayload(fixturesId, lease.generation(), EndpointKind.FIXTURES,
                        sourceRead, ValidationState.VALID, null, fixtures));
        CountDownLatch sourceLocked = new CountDownLatch(1);
        CountDownLatch releaseSource = new CountDownLatch(1);
        CountDownLatch callbackEntered = new CountDownLatch(1);
        AtomicBoolean callbackRan = new AtomicBoolean();

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> blocker = executor.submit(() -> tx.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM " + TABLES.payload() + " WHERE id=? FOR UPDATE",
                        Long.class, bootstrapId);
                sourceLocked.countDown();
                try {
                    if (!releaseSource.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("source lock release timed out");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }));
            assertThat(sourceLocked.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> publisher = executor.submit(() -> store.publish(lease, sourceRead,
                    List.of(sourceRead, sourceRead), sourcePair, () -> {
                        callbackRan.set(true);
                        jdbc.update("INSERT INTO " + PROBE + " VALUES ('lease-expiry-source', 1)");
                        callbackEntered.countDown();
                        return null;
                    }));
            assertThat(callbackEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(publisher.isDone()).isFalse();
            clock.advance(Duration.ofMinutes(2));
            releaseSource.countDown();
            blocker.get(5, TimeUnit.SECONDS);

            assertThatThrownBy(() -> publisher.get(5, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(LeaseLostException.class);
        } finally {
            releaseSource.countDown();
        }

        assertThat(callbackRan).isTrue();
        assertThat(count(PROBE)).isZero();
        Map<String, Object> persisted = jdbc.queryForMap("SELECT state, runtime_mode, runtime_epoch, "
                + "lease_owner, lease_token::text AS lease_token, lease_fence, lease_expires_at FROM "
                + TABLES.run() + " WHERE generation=?", lease.generation());
        assertThat(persisted.get("state")).isEqualTo("LEASED");
        assertThat(persisted.get("runtime_mode")).isEqualTo("OFF");
        assertThat(((Number) persisted.get("runtime_epoch")).longValue()).isZero();
        assertThat(persisted.get("lease_owner")).isEqualTo(lease.owner());
        assertThat(persisted.get("lease_token").toString()).isEqualTo(lease.token().toString());
        assertThat(((Number) persisted.get("lease_fence")).longValue()).isEqualTo(lease.fence());
        assertThat(((java.sql.Timestamp) persisted.get("lease_expires_at")).toInstant())
                .isEqualTo(lease.expiresAt());
        assertThat(jdbc.queryForObject("SELECT latest_generation FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", Long.class,
                "2097-98", CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.payload()
                + " WHERE id IN (?,?) AND source_published", Integer.class, bootstrapId, fixturesId)).isZero();
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
    void payloadReadProbeCountsOwnCanonicalGuardedAndMetadataReadsAtTheMapperBoundary() {
        PayloadReadProbe probe = new PayloadReadProbe(jdbc.getDataSource());
        OwnedSyncStore tracked = new OwnedSyncStore(probe, tx, clock, TABLES);
        tracked.enqueue(SyncKind.CATALOGUE_SCHEDULE, "2097-98", "catalogue-probe",
                "catalogue-probe", clock.instant());
        Lease lease = tracked.acquire("payload-probe-worker", Duration.ofMinutes(5)).orElseThrow();
        clock.advance(Duration.ofSeconds(3));
        SourceRead sourceRead = read();
        byte[] ownBody = bytes("known own checkpoint body");
        byte[] fixtureBody = bytes("known fixture checkpoint body");
        tracked.stage(lease, Endpoint.bootstrap(), sourceRead, Validation.valid(), ownBody);
        tracked.stage(lease, Endpoint.fixtures(), sourceRead, Validation.valid(), fixtureBody);

        probe.reset();
        assertThat(tracked.latestStagedPublicPayload(lease, Endpoint.bootstrap()).orElseThrow().body())
                .isEqualTo(ownBody);
        var own = probe.snapshot().family(PayloadReadProbe.Family.OLD_OWN_CHECKPOINT);
        assertThat(own).isEqualTo(new PayloadReadProbe.FamilyMetrics(1, 1, 1, ownBody.length));

        probe.reset();
        var pair = tracked.findInLatestValidPublicPayloadPairs("2097-98", 20, sourceRead.startedAt(),
                candidate -> java.util.Optional.of(candidate)).orElseThrow();
        assertThat(pair.bootstrap().body()).isEqualTo(ownBody);
        assertThat(pair.fixtures().body()).isEqualTo(fixtureBody);
        var canonical = probe.snapshot().family(PayloadReadProbe.Family.CANONICAL_PAIR);
        assertThat(canonical).isEqualTo(new PayloadReadProbe.FamilyMetrics(1, 1, 2,
                ownBody.length + fixtureBody.length));

        probe.reset();
        var selected = tracked.latestStagedPayloadMetadata(lease, Endpoint.bootstrap()).orElseThrow();
        assertThat(probe.snapshot().attempts()).isZero();
        assertThat(tracked.hydrateStagedPayload(lease, Endpoint.bootstrap(), selected, sourceRead.startedAt())
                .orElseThrow().body()).isEqualTo(ownBody);
        var guarded = probe.snapshot().family(PayloadReadProbe.Family.GUARDED_SINGLE);
        assertThat(guarded).isEqualTo(new PayloadReadProbe.FamilyMetrics(1, 1, 1, ownBody.length));

        jdbc.update("UPDATE " + TABLES.payload()
                + " SET validation_state='INVALID', validation_error='probe zero-row guard' WHERE id=?", selected.id());
        probe.reset();
        assertThat(tracked.hydrateStagedPayload(lease, Endpoint.bootstrap(), selected, sourceRead.startedAt()))
                .isEmpty();
        var emptyGuarded = probe.snapshot().family(PayloadReadProbe.Family.GUARDED_SINGLE);
        assertThat(emptyGuarded).isEqualTo(new PayloadReadProbe.FamilyMetrics(1, 0, 0, 0));

        probe.reset();
        assertThat(tracked.latestStagedPayloadMetadata(lease, Endpoint.bootstrap())).isPresent();
        assertThat(probe.snapshot().attempts()).isZero();
        assertThat(probe.snapshot().bodyValues()).isZero();
    }

    @Test
    void latestMetadataKeepsNewestInvalidExactEndpointWithoutReadingOlderValidBody() {
        PayloadReadProbe probe = new PayloadReadProbe(jdbc.getDataSource());
        OwnedSyncStore tracked = new OwnedSyncStore(probe, tx, clock, TABLES);
        tracked.enqueue(SyncKind.CATALOGUE_SCHEDULE, "2097-98", "metadata-latest",
                "metadata-latest", clock.instant());
        Lease lease = tracked.acquire("metadata-latest-worker", Duration.ofMinutes(5)).orElseThrow();
        clock.advance(Duration.ofSeconds(3));
        SourceRead sourceRead = read();
        long olderId = tracked.stage(lease, Endpoint.bootstrap(), sourceRead,
                Validation.valid(), bytes("older valid"));
        long fixturesId = tracked.stage(lease, Endpoint.fixtures(), sourceRead,
                Validation.valid(), bytes("other endpoint"));
        long newestId = tracked.stage(lease, Endpoint.bootstrap(), sourceRead,
                Validation.invalid("newest invalid"), bytes("newest invalid"));

        probe.reset();
        var selected = tracked.latestStagedPayloadMetadata(lease, Endpoint.bootstrap()).orElseThrow();

        assertThat(newestId).isGreaterThan(olderId);
        assertThat(selected.id()).isEqualTo(newestId);
        assertThat(selected.validationState()).isEqualTo(ValidationState.INVALID);
        assertThat(selected.endpoint()).isEqualTo(Endpoint.bootstrap());
        assertThat(tracked.latestStagedPayloadMetadata(lease, Endpoint.fixtures()).orElseThrow().id())
                .isEqualTo(fixturesId);
        assertThat(tracked.hydrateStagedPayload(lease, Endpoint.bootstrap(), selected, sourceRead.startedAt()))
                .isEmpty();
        assertThatThrownBy(() -> tracked.hydrateStagedPayload(lease, Endpoint.fixtures(), selected,
                sourceRead.startedAt())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endpoint mismatch");
        assertThat(probe.snapshot().attempts()).isZero();
        assertThat(probe.snapshot().bodyValues()).isZero();
    }

    @Test
    void guardedOwnHydrationRejectsWrongRunAdvancedFloorAndSeasonWithoutReturningBodies() {
        PayloadReadProbe probe = new PayloadReadProbe(jdbc.getDataSource());
        OwnedSyncStore tracked = new OwnedSyncStore(probe, tx, clock, TABLES);
        tracked.enqueue(SyncKind.CATALOGUE_SCHEDULE, "2097-98", "metadata-guard-target",
                "metadata-guard-target", clock.instant());
        Lease target = tracked.acquire("metadata-guard-target-worker", Duration.ofMinutes(5)).orElseThrow();
        tracked.enqueue(SyncKind.CATALOGUE_SCHEDULE, "2098-99", "metadata-guard-source",
                "metadata-guard-source", clock.instant());
        Lease source = tracked.acquire("metadata-guard-source-worker", Duration.ofMinutes(5)).orElseThrow();
        clock.advance(Duration.ofSeconds(3));
        SourceRead sourceRead = read();
        tracked.stage(target, Endpoint.bootstrap(), sourceRead, Validation.valid(), bytes("target body"));
        tracked.stage(target, Endpoint.fixtures(), sourceRead, Validation.valid(), bytes("fixture body"));
        long foreignId = tracked.stage(source, Endpoint.bootstrap(), sourceRead,
                Validation.valid(), bytes("foreign body"));
        var bootstrap = tracked.latestStagedPayloadMetadata(target, Endpoint.bootstrap()).orElseThrow();
        var fixture = tracked.latestStagedPayloadMetadata(target, Endpoint.fixtures()).orElseThrow();
        var foreign = tracked.latestStagedPayloadMetadata(source, Endpoint.bootstrap()).orElseThrow();

        probe.reset();
        var wrongRun = new OwnedSyncStore.StagedPayloadMetadata(foreignId, target.generation(),
                Endpoint.bootstrap(), foreign.read(), foreign.validationState());
        assertThat(tracked.hydrateStagedPayload(target, Endpoint.bootstrap(), wrongRun, sourceRead.startedAt()))
                .isEmpty();
        assertThat(probe.snapshot().family(PayloadReadProbe.Family.GUARDED_SINGLE))
                .isEqualTo(new PayloadReadProbe.FamilyMetrics(1, 0, 0, 0));

        probe.reset();
        assertThat(tracked.hydrateStagedPayload(target, Endpoint.bootstrap(), bootstrap,
                sourceRead.startedAt().plusMillis(1))).isEmpty();
        assertThat(probe.snapshot().family(PayloadReadProbe.Family.GUARDED_SINGLE))
                .isEqualTo(new PayloadReadProbe.FamilyMetrics(1, 0, 0, 0));

        jdbc.update("INSERT INTO " + TABLES.receipt()
                + "(season, publication_key) VALUES ('2098-99', 'metadata-guard-target') ON CONFLICT DO NOTHING");
        jdbc.update("UPDATE " + TABLES.run() + " SET season='2098-99' WHERE generation=?", target.generation());
        probe.reset();
        assertThat(tracked.hydrateStagedPayload(target, Endpoint.fixtures(), fixture, sourceRead.startedAt()))
                .isEmpty();
        assertThat(probe.snapshot().family(PayloadReadProbe.Family.GUARDED_SINGLE))
                .isEqualTo(new PayloadReadProbe.FamilyMetrics(1, 0, 0, 0));
    }

    @Test
    void fencedHydrationRejectsExpiredAndReassignedLeaseWithoutReadingCheckpointBody() {
        PayloadReadProbe probe = new PayloadReadProbe(jdbc.getDataSource());
        OwnedSyncStore.RuntimeFence fence = new PlannerRuntimeRepository(jdbc, tx, clock, TABLES)
                .activate("2097-98", OwnedSyncMode.STAGING);
        OwnedSyncStore fenced = new OwnedSyncStore(probe, tx, clock, TABLES, fence);
        Run run = enqueueCatalogueSchedule(fenced);
        Lease old = fenced.acquireForSeason("2097-98", "hydration-old", Duration.ofMinutes(1))
                .orElseThrow();
        assertThat(old.generation()).isEqualTo(run.generation());
        Endpoint endpoint = Endpoint.bootstrap();
        SourceRead sourceRead = new SourceRead(clock.instant(), clock.instant());
        fenced.stage(old, endpoint, sourceRead, Validation.valid(), bytes("fenced body"));
        var selected = fenced.latestStagedPayloadMetadata(old, endpoint).orElseThrow();

        clock.advance(Duration.ofMinutes(2));
        assertThatThrownBy(() -> fenced.hydrateStagedPayload(old, endpoint, selected, sourceRead.startedAt()))
                .isInstanceOf(LeaseLostException.class);
        Lease replacement = fenced.acquireForSeason("2097-98", "hydration-replacement", Duration.ofMinutes(5))
                .orElseThrow();
        assertThat(replacement.generation()).isEqualTo(old.generation());
        assertThat(replacement.fence()).isGreaterThan(old.fence());
        assertThatThrownBy(() -> fenced.hydrateStagedPayload(old, endpoint, selected, sourceRead.startedAt()))
                .isInstanceOf(LeaseLostException.class);

        assertThat(probe.snapshot().family(PayloadReadProbe.Family.GUARDED_SINGLE))
                .isEqualTo(new PayloadReadProbe.FamilyMetrics(0, 0, 0, 0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.payload()
                + " WHERE id=?", Integer.class, selected.id())).isOne();
    }

    @Test
    void fencedHydrationRejectsReplacedEpochWithoutReadingCheckpointBody() {
        PayloadReadProbe probe = new PayloadReadProbe(jdbc.getDataSource());
        OwnedSyncStore.RuntimeFence fence = new PlannerRuntimeRepository(jdbc, tx, clock, TABLES)
                .activate("2097-98", OwnedSyncMode.STAGING);
        OwnedSyncStore fenced = new OwnedSyncStore(probe, tx, clock, TABLES, fence);
        enqueueCatalogueSchedule(fenced);
        Lease lease = fenced.acquireForSeason("2097-98", "hydration-epoch", Duration.ofMinutes(5))
                .orElseThrow();
        Endpoint endpoint = Endpoint.bootstrap();
        SourceRead sourceRead = new SourceRead(clock.instant(), clock.instant());
        fenced.stage(lease, endpoint, sourceRead, Validation.valid(), bytes("fenced epoch body"));
        var selected = fenced.latestStagedPayloadMetadata(lease, endpoint).orElseThrow();
        probe.reset();

        new PlannerRuntimeRepository(jdbc, tx, clock, TABLES).activate("2097-98", OwnedSyncMode.STAGING);

        assertThatThrownBy(() -> fenced.hydrateStagedPayload(lease, endpoint, selected, sourceRead.startedAt()))
                .isInstanceOf(RuntimeFenceLostException.class);
        assertThat(probe.snapshot().family(PayloadReadProbe.Family.GUARDED_SINGLE))
                .isEqualTo(new PayloadReadProbe.FamilyMetrics(0, 0, 0, 0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.payload()
                + " WHERE run_generation=?", Integer.class, lease.generation())).isOne();
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
        assertThat(tracking.snapshot().family(PayloadReadProbe.Family.GUARDED_SINGLE))
                .isEqualTo(new PayloadReadProbe.FamilyMetrics(1, 0, 0, 0));
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
        assertThat(tracking.snapshot().family(PayloadReadProbe.Family.GUARDED_SINGLE))
                .isEqualTo(new PayloadReadProbe.FamilyMetrics(1, 0, 0, 0));
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

    private Run seedPartialFixture(OwnedSyncStore target, int fixtureId, Instant dueAt) {
        String key = FixtureSyncPublisher.publicationKey(fixtureId);
        Run run = target.enqueue(SyncKind.FIXTURE_VERIFICATION, "2097-98", key, key, clock.instant());
        Lease lease = target.acquireForSeason("2097-98", "partial-fixture-" + fixtureId,
                Duration.ofMinutes(5)).orElseThrow();
        assertThat(lease.generation()).isEqualTo(run.generation());
        SourceRead sourceRead = new SourceRead(clock.instant(), clock.instant());
        target.stage(lease, Endpoint.bootstrap(), sourceRead, Validation.unchecked(), bytes("partial"));
        target.retry(lease, dueAt, "fixture has a partial checkpoint");
        jdbc.update("UPDATE " + TABLES.run() + " SET priority=5 WHERE generation=?", run.generation());
        return run;
    }

    private void resetAttemptsForCurrentEpoch() {
        jdbc.update("""
                UPDATE %s SET attempt_count=0
                WHERE season=? AND runtime_mode='STAGING' AND runtime_epoch=(
                    SELECT runtime_epoch FROM %s WHERE instance_id=? AND runtime_mode='STAGING')
                """.formatted(TABLES.run(), TABLES.plannerRuntime()), "2097-98", "mode:2097-98");
    }

    private ReadinessFixture seedReadinessFixture(OwnedSyncStore target, int fixtureId, int playerId) {
        return seedReadinessFixture(target, fixtureId, List.of(playerId));
    }

    private ReadinessFixture seedReadinessFixture(OwnedSyncStore target, int fixtureId,
                                                  List<Integer> playerIds) {
        String key = FixtureSyncPublisher.publicationKey(fixtureId);
        Run run = target.enqueue(SyncKind.FIXTURE_VERIFICATION, "2097-98", key, key, clock.instant());
        Lease lease = target.acquireForSeason("2097-98", "readiness-fixture-" + fixtureId,
                Duration.ofMinutes(5)).orElseThrow();
        assertThat(lease.generation()).isEqualTo(run.generation());
        return stageReadinessManifest(target, lease, fixtureId, playerIds);
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
        return stageReadinessManifest(target, lease, fixtureId, List.of(playerId));
    }

    private ReadinessFixture stageReadinessManifest(OwnedSyncStore target, Lease lease,
                                                     int fixtureId, List<Integer> playerIds) {
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
                new PublicPayloadPair(generation, bootstrap, fixtures), live, playerIds);
        return new ReadinessFixture(generation, bootstrapId, fixturesId);
    }

    private Run enqueueHistory(OwnedSyncStore target, int playerId) {
        String key = HistoryFixtureDispatcher.playerHistoryPublicationKey(playerId);
        return target.enqueue(SyncKind.PLAYER_HISTORY, "2097-98", key, key, clock.instant());
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

    private static byte[] historyBody(int playerId) {
        byte[] body = new byte[64 * 1024];
        java.util.Arrays.fill(body, (byte) playerId);
        return body;
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

    private static final class TrackingJdbcTemplate extends PayloadReadProbe {
        private TrackingJdbcTemplate(DataSource dataSource) {
            super(dataSource);
        }

        private int hydratedBodies() {
            return Math.toIntExact(snapshot().bodyValues());
        }

        private int payloadBodySelects() {
            return Math.toIntExact(snapshot().attempts());
        }

        private void resetHydratedBodies() {
            reset();
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
