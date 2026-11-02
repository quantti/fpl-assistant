package dev.quantti.fplassistant.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;

import dev.quantti.fplassistant.defcon.DefensiveStatsSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.FixtureRow;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.GameweekRow;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.PlayerRow;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.Season;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.SourceDocument;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.TeamRow;
import dev.quantti.fplassistant.sync.HistoryParser.CanonicalFixture;
import dev.quantti.fplassistant.sync.IncrementalPfsRepository.PublicationResult;
import dev.quantti.fplassistant.sync.CoalescedPlayerHistoryCollector.CycleResult;
import dev.quantti.fplassistant.sync.CoalescedPlayerHistoryCollector.State;
import dev.quantti.fplassistant.sync.OwnedSyncHttpClient.DeferredResult;
import dev.quantti.fplassistant.sync.OwnedSyncHttpClient.Failure;
import dev.quantti.fplassistant.sync.OwnedSyncHttpClient.FailureKind;
import dev.quantti.fplassistant.sync.OwnedSyncRequestBudget;
import dev.quantti.fplassistant.sync.OwnedSyncHttpClient.Response;
import dev.quantti.fplassistant.sync.OwnedSyncRequestBudget.DeferredReason;
import dev.quantti.fplassistant.sync.OwnedSyncStore.Endpoint;
import dev.quantti.fplassistant.sync.OwnedSyncStore.EndpointKind;
import dev.quantti.fplassistant.sync.OwnedSyncStore.Lease;
import dev.quantti.fplassistant.sync.OwnedSyncStore.RuntimeFence;
import dev.quantti.fplassistant.sync.OwnedSyncStore.SourceRead;
import dev.quantti.fplassistant.sync.OwnedSyncStore.SyncKind;
import dev.quantti.fplassistant.sync.OwnedSyncStore.Validation;
import dev.quantti.fplassistant.testsupport.SharedStatsSeed;
import dev.quantti.fplassistant.tools.PfsReadSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionProxy;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class CoalescedPlayerHistoryCollectorIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final OwnedSyncTables TABLES = new OwnedSyncTables("fpl_collector_test",
            "fpl_collector_payload_test", "fpl_collector_receipt_test", "fpl_collector_request_state_test",
            "fpl_collector_request_attempt_test", "fpl_collector_fixture_test",
            "fpl_collector_pfs_evidence_test", "fpl_collector_slot_test", "fpl_collector_runtime_test",
            "fpl_collector_fixture_dependency_test");
    private static final CatalogueScheduleTables CAPTURED = new CatalogueScheduleTables(
            "fpl_collector_daily_test", "fpl_collector_gameweek_test", "fpl_collector_schedule_test");
    private static final int SEASON_ID = 27;
    private static final int KNOWN_PLAYER = 99001;
    private static final int FINAL_PENDING_PLAYER = 99002;
    private static final int ACTIVE_PLAYER = 99003;
    private static final int LOCAL_HTTP_PLAYER = 99005;
    private static final int WATERMARK_PLAYER = 99006;
    private static final int DOUBLE_FIXTURE_ONE = 989101;
    private static final int DOUBLE_FIXTURE_TWO = 989102;
    private static final int DOUBLE_HOME_ONE = 9912;
    private static final int DOUBLE_AWAY_ONE = 9914;
    private static final int DOUBLE_HOME_TWO = 9904;
    private static final int DOUBLE_AWAY_TWO = 9920;
    private static final Instant START = Instant.parse("2026-09-13T18:00:00Z");

    private static JdbcTemplate jdbc;
    private static DataSource dataSource;
    private static TransactionTemplate tx;
    private static Season season;
    private static List<Integer> originalCurrentSeasons;
    private static boolean disposableDatabaseValidated;
    private static boolean seasonBaselineCaptured;
    private static boolean seasonFlagsChanged;
    private static boolean cleanupAuthorized;
    private static byte[] bootstrap;
    private static byte[] fixtures;
    private static byte[] eventThreeLive;
    private static byte[] eventFourLive;

    private MutableClock clock;
    private OwnedSyncStore store;
    private IncrementalPlayerFixturePublisher publisher;
    private RecordedFetch fetch;
    private RuntimeFence runtimeFence;

    @BeforeAll
    static void initialize() throws Exception {
        String url = required("DATABASE_URL");
        String user = required("DB_USER");
        String password = required("DB_PASSWORD");
        initialize(url, user, password,
                () -> new JdbcTemplate(new DriverManagerDataSource(url, user, password)));
    }

    static void initialize(String url, String user, String password, Supplier<JdbcTemplate> jdbcSupplier)
            throws Exception {
        jdbc = SharedStatsSeed.openDisposableTestJdbc(url, user, password, jdbcSupplier);
        dataSource = jdbc.getDataSource();
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        SharedStatsSeed.ensureOwnedSyncCaptureSeason(jdbc);
        disposableDatabaseValidated = true;
        originalCurrentSeasons = jdbc.query("SELECT id FROM season WHERE is_current=true ORDER BY id",
                (rs, row) -> rs.getInt(1));
        seasonBaselineCaptured = true;
        SharedStatsSeed.setCurrentSeason(jdbc, SEASON_ID);
        seasonFlagsChanged = true;
        season = jdbc.queryForObject("SELECT id, code, start_date, end_date FROM season WHERE id=?",
                (rs, row) -> new Season(rs.getInt("id"), rs.getString("code"),
                        rs.getDate("start_date").toLocalDate(),
                        rs.getDate("end_date") == null ? null : rs.getDate("end_date").toLocalDate()), SEASON_ID);
        assertThat(season).isEqualTo(new Season(27, "2026-27", LocalDate.of(2026, 8, 21), null));
        new OwnedSyncSchema(jdbc, TABLES).initialize();
        new DefensiveStatsSchema(jdbc).ensureColumns();
        new dev.quantti.fplassistant.schedule.ScheduleSchema(jdbc,
                CAPTURED.gameweek(), CAPTURED.fixture()).ensureSchema();
        bootstrap = resource("bootstrap-static.json");
        fixtures = resource("fixtures.json");
        eventThreeLive = resource("event-3-live.json");
        eventFourLive = resource("event-4-live.json");
        cleanupAuthorized = true;
    }

    @AfterAll
    static void restoreCurrentSeason() {
        if (cleanupAuthorized && jdbc != null) {
            Long epoch = jdbc.queryForObject("SELECT runtime_epoch FROM " + TABLES.plannerRuntime()
                    + " WHERE instance_id=?", Long.class, "mode:" + season.code());
            if (epoch != null) {
                new PlannerRuntimeRepository(jdbc, tx, Clock.systemUTC(), TABLES)
                        .deactivate(new RuntimeFence(season.code(), OwnedSyncMode.ACTIVE, epoch, START));
            }
            jdbc.update("DELETE FROM player_fixture_stats WHERE season_id=? AND fixture_id=29"
                    + " AND player_id IN (1, 2, 28, ?)", SEASON_ID, FINAL_PENDING_PLAYER);
            jdbc.update("DELETE FROM player_fixture_stats WHERE season_id=? AND player_id IN (?, ?)",
                    SEASON_ID, KNOWN_PLAYER, 658);
        }
        restoreCapturedCurrentSeasons();
    }

    private static void restoreCapturedCurrentSeasons() {
        if (!disposableDatabaseValidated || !seasonBaselineCaptured || !seasonFlagsChanged || jdbc == null) return;
        tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE season SET is_current=false");
            originalCurrentSeasons.forEach(id -> jdbc.update("UPDATE season SET is_current=true WHERE id=?", id));
        });
        seasonFlagsChanged = false;
    }

    @BeforeEach
    void setUp() {
        if (!cleanupAuthorized) throw new IllegalStateException("Disposable test database setup did not complete");
        clearOwnedTables();
        jdbc.update("DELETE FROM player_fixture_stats WHERE season_id=? AND fixture_id=29"
                + " AND player_id IN (1, 2, 28, ?)", SEASON_ID, FINAL_PENDING_PLAYER);
        jdbc.update("DELETE FROM player_fixture_stats WHERE season_id=? AND player_id IN (?, ?)",
                SEASON_ID, KNOWN_PLAYER, 658);
        clock = new MutableClock(START);
        runtimeFence = new PlannerRuntimeRepository(jdbc, tx, clock, TABLES)
                .activate(season.code(), OwnedSyncMode.ACTIVE);
        store = new OwnedSyncStore(jdbc, tx, clock, TABLES, runtimeFence);
        publisher = new IncrementalPlayerFixturePublisher(
                new IncrementalPfsRepository(jdbc, clock, store, TABLES));
        fetch = new RecordedFetch();
        fetch.bindClock(clock);
    }

    @AfterEach
    void cleanUp() {
        if (!cleanupAuthorized || jdbc == null) return;
        clearOwnedTables();
        jdbc.update("DELETE FROM " + CAPTURED.gameweek());
        jdbc.update("DELETE FROM " + CAPTURED.fixture());
        jdbc.update("DELETE FROM player_fixture_stats WHERE season_id=?", SEASON_ID);
        jdbc.update("DELETE FROM fixture WHERE season_id=?", SEASON_ID);
        jdbc.update("DELETE FROM gameweek WHERE season_id=?", SEASON_ID);
        jdbc.update("DELETE FROM player WHERE season_id=?", SEASON_ID);
        jdbc.update("DELETE FROM team WHERE season_id=?", SEASON_ID);
    }

    @Test
    void busyStartupReportsActualLeaseExpiryAndTakesOverOnlyAfterItExpires() throws Exception {
        store.enqueueIncrementalCoreImport(season, clock.instant());
        var oldLease = store.acquireIncrementalCoreImport(season.code(), "old-process", Duration.ofMinutes(5))
                .orElseThrow();
        var idleSchedule = JSON.readTree(onlyFixturesFinished(fixtures));
        ((ObjectNode) idleSchedule.get(0)).put("kickoff_time", START.plus(Duration.ofDays(1)).toString());
        fetch.setSources(markAllGameweeksUnchecked(bootstrap), JSON.writeValueAsBytes(idleSchedule),
                eventThreeLive, eventFourLive);
        var collector = collector(true);
        var busy = collector.runCycle();
        assertThat(busy.state()).isEqualTo(State.BUSY);
        assertThat(busy.nextDue()).isEqualTo(oldLease.expiresAt());
        assertThat(fetch.count(EndpointKind.BOOTSTRAP_STATIC)).isZero();
        clock.set(oldLease.expiresAt().minusMillis(1));
        assertThat(collector.runCycle().state()).isEqualTo(State.BUSY);
        assertThat(fetch.count(EndpointKind.BOOTSTRAP_STATIC)).isZero();
        clock.set(oldLease.expiresAt().plusMillis(1));
        var resumed = collector.runCycle();
        assertThat(resumed.state()).as("cycle %s; error %s", resumed, latestRunError()).isEqualTo(State.COMPLETED);
        assertThat(fetch.count(EndpointKind.BOOTSTRAP_STATIC)).isOne();
        assertThatThrownBy(() -> store.retry(oldLease, clock.instant().plusSeconds(3600), "old worker"))
                .isInstanceOf(OwnedSyncStore.LeaseLostException.class);
    }

    @Test
    void legacyAcquisitionCannotClaimEligibleIncrementalCoreWork() {
        store.enqueueIncrementalCoreImport(season, clock.instant());

        assertThat(store.acquire("legacy-ordinary", Duration.ofMinutes(5))).isEmpty();
        assertThat(store.acquireFairForSeason(season.code(), "legacy-fair", Duration.ofMinutes(5))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.run()
                + " WHERE producer_key=? AND season=?", String.class,
                OwnedSyncStore.INCREMENTAL_CORE_PRODUCER, season.code())).isEqualTo("PENDING");
    }

    @Test
    void incrementalRetentionPrunesOldCompletedFailedAndSupersededBodiesOnly() {
        StagedCorePair completed = stagePublishedCorePair();
        store.completeStaged(completed.lease(), store.incrementalSourceInterval(completed.lease()),
                List.of(completed.bootstrapRead(), completed.fixturesRead()));
        clock.advance(CoalescedPlayerHistoryCollector.PAYLOAD_RETENTION.plusMillis(1));
        assertThat(store.deleteIncrementalCoreTerminalPayloadsOlderThan(
                CoalescedPlayerHistoryCollector.PAYLOAD_RETENTION)).isEqualTo(2);
        assertPrunedPayloadBodies(completed.lease().generation(), 2);

        StagedCorePair superseded = stagePublishedCorePair();
        store.supersedeIncrementalCoreImport(superseded.lease());
        clock.advance(CoalescedPlayerHistoryCollector.PAYLOAD_RETENTION.plusMillis(1));
        assertThat(store.deleteIncrementalCoreTerminalPayloadsOlderThan(
                CoalescedPlayerHistoryCollector.PAYLOAD_RETENTION)).isEqualTo(2);
        assertPrunedPayloadBodies(superseded.lease().generation(), 2);

        StagedCorePair failed = stagePublishedCorePair();
        store.failFetch(failed.lease(), "controlled terminal failure");
        clock.advance(CoalescedPlayerHistoryCollector.PAYLOAD_RETENTION.plusMillis(1));
        assertThat(store.deleteIncrementalCoreTerminalPayloadsOlderThan(
                CoalescedPlayerHistoryCollector.PAYLOAD_RETENTION)).isEqualTo(2);
        assertPrunedPayloadBodies(failed.lease().generation(), 2);

        var legacyRun = store.enqueue(SyncKind.CATALOGUE_SCHEDULE, season.code(),
                "retention-legacy", "retention-legacy", clock.instant());
        Lease legacyLease = store.acquireForSeason(season.code(), "retention-legacy-worker",
                Duration.ofMinutes(30)).orElseThrow();
        assertThat(legacyLease.generation()).isEqualTo(legacyRun.generation());
        SourceRead legacyRead = nextRead();
        store.stage(legacyLease, Endpoint.bootstrap(), legacyRead, Validation.valid(), bootstrap);
        store.completeStaged(legacyLease, legacyRead, List.of(legacyRead));
        clock.advance(CoalescedPlayerHistoryCollector.PAYLOAD_RETENTION.plusMillis(1));
        assertThat(store.deleteIncrementalCoreTerminalPayloadsOlderThan(
                CoalescedPlayerHistoryCollector.PAYLOAD_RETENTION)).isZero();
        assertThat(payloadCount(legacyLease.generation())).isOne();
        assertThat(jdbc.queryForObject("SELECT octet_length(body) > 0 AND body_pruned_at IS NULL FROM "
                + TABLES.payload() + " WHERE run_generation=?", Boolean.class, legacyLease.generation())).isTrue();
    }

    @Test
    void incrementalRetentionPreservesPendingAndExpiredLeasedPairsForSuccessfulResume() {
        StagedCorePair pair = stagePublishedCorePair();
        Instant retryAt = clock.instant().plus(CoalescedPlayerHistoryCollector.PAYLOAD_RETENTION)
                .plusSeconds(1);
        store.retry(pair.lease(), retryAt, "controlled partial cycle");
        clock.set(retryAt.plusSeconds(1));
        assertThat(store.deleteIncrementalCoreTerminalPayloadsOlderThan(
                CoalescedPlayerHistoryCollector.PAYLOAD_RETENTION)).isZero();
        assertThat(payloadCount(pair.lease().generation())).isEqualTo(2);
        assertPayloadBodiesPresent(pair.lease().generation(), 2);

        Lease firstResume = store.acquireIncrementalCoreImport(season.code(), "retention-resume-1",
                Duration.ofHours(1)).orElseThrow();
        assertThat(firstResume.generation()).isEqualTo(pair.lease().generation());
        clock.advance(CoalescedPlayerHistoryCollector.PAYLOAD_RETENTION.plusSeconds(1));
        assertThat(store.deleteIncrementalCoreTerminalPayloadsOlderThan(
                CoalescedPlayerHistoryCollector.PAYLOAD_RETENTION)).isZero();
        assertThat(payloadCount(pair.lease().generation())).isEqualTo(2);
        assertPayloadBodiesPresent(pair.lease().generation(), 2);

        Lease resumed = store.acquireIncrementalCoreImport(season.code(), "retention-resume-2",
                Duration.ofHours(1)).orElseThrow();
        assertThat(resumed.generation()).isEqualTo(pair.lease().generation());
        var snapshot = store.incrementalCompatibilitySnapshot(resumed).orElseThrow();
        assertThat(snapshot.bootstrap().body()).containsExactly(bootstrap);
        assertThat(snapshot.fixtures().body()).containsExactly(fixtures);
        store.completeStaged(resumed, store.incrementalSourceInterval(resumed),
                List.of(pair.bootstrapRead(), pair.fixturesRead()));
        assertThat(store.deleteIncrementalCoreTerminalPayloadsOlderThan(
                CoalescedPlayerHistoryCollector.PAYLOAD_RETENTION)).isEqualTo(2);
        assertPrunedPayloadBodies(pair.lease().generation(), 2);
    }

    @Test
    void incrementalRetentionKeepsPublishedParentBodiesWhileNewCoreRunIsActive() {
        StagedCorePair parent = stagePublishedCorePair();
        store.completeStaged(parent.lease(), store.incrementalSourceInterval(parent.lease()),
                List.of(parent.bootstrapRead(), parent.fixturesRead()));
        clock.advance(CoalescedPlayerHistoryCollector.PAYLOAD_RETENTION.plusMillis(1));
        var pending = store.enqueueIncrementalCoreImport(season, clock.instant());
        assertThat(pending.generation()).isNotEqualTo(parent.lease().generation());

        assertThat(store.deleteIncrementalCoreTerminalPayloadsOlderThan(
                CoalescedPlayerHistoryCollector.PAYLOAD_RETENTION)).isZero();
        assertThat(payloadCount(parent.lease().generation())).isEqualTo(2);
        assertPayloadBodiesPresent(parent.lease().generation(), 2);
        Lease pendingLease = store.acquireIncrementalCoreImport(season.code(), "retention-pending-parent",
                Duration.ofMinutes(30)).orElseThrow();
        store.failFetch(pendingLease, "controlled failure before fetching source pair");

        assertThat(store.deleteIncrementalCoreTerminalPayloadsOlderThan(
                CoalescedPlayerHistoryCollector.PAYLOAD_RETENTION)).isEqualTo(2);
        assertPrunedPayloadBodies(parent.lease().generation(), 2);
    }

    @Test
    void scheduledImportFailureRunsRetentionWithoutChangingRuntimeMode() throws Exception {
        StagedCorePair pair = stagePublishedCorePair();
        store.completeStaged(pair.lease(), store.incrementalSourceInterval(pair.lease()),
                List.of(pair.bootstrapRead(), pair.fixturesRead()));
        clock.advance(CoalescedPlayerHistoryCollector.PAYLOAD_RETENTION.plusMillis(1));
        Map<String, Object> runtimeBefore = jdbc.queryForMap("SELECT runtime_mode,runtime_epoch,activation_boundary "
                + "FROM " + TABLES.plannerRuntime() + " WHERE instance_id=?", "mode:" + season.code());
        AtomicInteger deleted = new AtomicInteger();
        CountDownLatch maintenanceRun = new CountDownLatch(2);
        CoalescedPlayerHistoryImportRuntime runtime = new CoalescedPlayerHistoryImportRuntime(() -> {
            throw new IllegalStateException("controlled scheduled import failure");
        }, () -> {
            int count = store.deleteIncrementalCoreTerminalPayloadsOlderThan(
                    CoalescedPlayerHistoryCollector.PAYLOAD_RETENTION);
            deleted.addAndGet(count);
            maintenanceRun.countDown();
            return count;
        });

        try {
            runtime.start();
            assertThat(maintenanceRun.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            runtime.stop();
        }

        assertThat(deleted).hasValue(2);
        assertPrunedPayloadBodies(pair.lease().generation(), 2);
        assertThat(jdbc.queryForMap("SELECT runtime_mode,runtime_epoch,activation_boundary FROM "
                + TABLES.plannerRuntime() + " WHERE instance_id=?", "mode:" + season.code()))
                .isEqualTo(runtimeBefore);
    }

    @Test
    void requestBudgetSpacingRefusalResumesAfterExplicitClockAdvance() {
        jdbc.update("INSERT INTO " + TABLES.requestState()
                + "(singleton) VALUES (TRUE) ON CONFLICT DO NOTHING");
        OwnedSyncRequestBudget budget = new OwnedSyncRequestBudget(jdbc, tx, clock, TABLES);

        var first = (OwnedSyncRequestBudget.Allowed) budget.admit();
        budget.complete(first.permit(), OwnedSyncRequestBudget.Completion.success());
        var refused = (OwnedSyncRequestBudget.Deferred) budget.admit();

        assertThat(refused.reason()).isEqualTo(DeferredReason.MINIMUM_SPACING);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.requestAttempt(), Integer.class)).isOne();
        clock.set(refused.nextDue());
        var resumed = budget.admit();

        assertThat(resumed).isInstanceOf(OwnedSyncRequestBudget.Allowed.class);
        var second = ((OwnedSyncRequestBudget.Allowed) resumed).permit();
        assertThat(second.admittedAt()).isEqualTo(refused.nextDue());
        budget.complete(second, OwnedSyncRequestBudget.Completion.success());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.requestAttempt(), Integer.class)).isEqualTo(2);
    }

    @Test
    void coalescesRecordedLeaguePagesAndDoesNotRefetchCompletedPostFinishWork() throws Exception {
        byte[] source = markGameweekFinished(fixtures, 4);
        source = addParticipant(addParticipant(source, 3, FINAL_PENDING_PLAYER), 4, ACTIVE_PLAYER + 1);
        FixtureKey knownFixture = fixture(source, 4, true);
        SharedStatsSeed.seedOwnedSyncKnownHistoryKey(jdbc, SEASON_ID, knownFixture.id(), knownFixture.gameweek(),
                KNOWN_PLAYER, knownFixture.homeTeam(), knownFixture.awayTeam(), true, knownFixture.kickoff());
        byte[] ordinaryBootstrap = markGameweekUnchecked(
                markGameweekUnchecked(markGameweekUnchecked(bootstrap, 1), 2), 3);
        fetch.setSources(ordinaryBootstrap, source, eventThreeLive, eventFourLive);
        fetch.setPlayerPage(1, onlyHistoryForFixture(resource("element-summary-1.json"), 29, 1));
        fetch.setPlayerPage(28, historyForAwayPlayer28(29));

        // Reuse connections only for this serial full-roster smoke test.
        try (HikariDataSource pool = new HikariDataSource()) {
            pool.setDataSource(dataSource);
            pool.setMaximumPoolSize(1);
            pool.setMinimumIdle(0);
            SqlRecordingDataSource recording = new SqlRecordingDataSource(pool);
            JdbcTemplate pooledJdbc = new JdbcTemplate(recording);
            TransactionTemplate pooledTx = new TransactionTemplate(new DataSourceTransactionManager(recording));
            OwnedSyncStore pooledStore = new OwnedSyncStore(pooledJdbc, pooledTx, clock, TABLES, runtimeFence);
            IncrementalPlayerFixturePublisher pooledPublisher = new IncrementalPlayerFixturePublisher(
                    new IncrementalPfsRepository(pooledJdbc, clock, pooledStore, TABLES));
            installUnit6WriteCounters();
            AtomicReference<CycleResult> nested = new AtomicReference<>();
            AtomicReference<CoalescedPlayerHistoryCollector> collectorRef = new AtomicReference<>();
            fetch.onFirstRequest(() -> nested.set(collectorRef.get().runCycle()));
            CoalescedPlayerHistoryCollector collector = new CoalescedPlayerHistoryCollector(
                    season, true, pooledStore, pooledPublisher, fetch, clock, "collector-test");
            collectorRef.set(collector);
            CycleResult first;
            long cycleMillis;
            Map<String, Long> physicalWrites;
            try {
                long cycleStarted = System.nanoTime();
                first = collector.runCycle();
                cycleMillis = Duration.ofNanos(System.nanoTime() - cycleStarted).toMillis();
                physicalWrites = unit6WriteCounts();
            } finally {
                removeUnit6WriteCounters();
            }

            assertThat(first.state()).isEqualTo(State.COMPLETED);
            assertThat(nested.get().state()).isEqualTo(State.BUSY);
            assertThat(first.finalPendingGameweeks()).isEmpty();
            assertThat(first.selectedPlayerIds()).hasSize(660).contains(KNOWN_PLAYER, FINAL_PENDING_PLAYER,
                    ACTIVE_PLAYER + 1).doesNotContain(658);
            assertThat(new HashSet<>(first.selectedPlayerIds())).hasSize(first.selectedPlayerIds().size());
            assertThat(first.selectedPlayerIds().indexOf(FINAL_PENDING_PLAYER))
                    .isLessThan(first.selectedPlayerIds().indexOf(ACTIVE_PLAYER + 1));
            assertThat(first.committedPlayerIds()).hasSize(660);
            assertThat(fetch.count(EndpointKind.BOOTSTRAP_STATIC)).isEqualTo(1);
            assertThat(fetch.count(EndpointKind.FIXTURES)).isEqualTo(1);
            assertThat(fetch.count(EndpointKind.EVENT_LIVE)).isEqualTo(4);
            assertThat(physicalWrites.getOrDefault("player_fixture_stats/INSERT", 0L)).isEqualTo(2L);
            assertThat(fetch.playerIds()).hasSize(660);
            assertThat(new HashSet<>(fetch.playerIds())).hasSize(660);
            assertThat(JSON.readTree(bootstrap).path("teams").size()).isEqualTo(20);
            assertThat(JSON.readTree(bootstrap).path("elements").size()).isEqualTo(657);
            System.out.printf("UNIT6_RECORDED_NORMAL league={clubs:20,cataloguePlayers:657,fixtures:380} "
                            + "selected=%d unique=%d gets={bootstrap:%d,fixtures:%d,event-live:%d,history:%d} "
                            + "bodyBytes=%d jdbc={statements:%d,queries:%d,updates:%d,rows:%d,logicalBytes~%d} "
                            + "physicalWrites=%s cycleWallMs=%d%n",
                    first.selectedPlayerIds().size(), new HashSet<>(first.selectedPlayerIds()).size(),
                    fetch.count(EndpointKind.BOOTSTRAP_STATIC), fetch.count(EndpointKind.FIXTURES),
                    fetch.count(EndpointKind.EVENT_LIVE), fetch.count(EndpointKind.ELEMENT_SUMMARY),
                    fetch.returnedBodyBytes(), recording.statements().size(), recording.queryExecutions(),
                    recording.updateExecutions(), recording.returnedRows(),
                    recording.approximateLogicalReturnedBytes(), physicalWrites, cycleMillis);

            fetch.clearCalls();
            CycleResult idle = collector.runCycle();

            assertThat(idle.state()).isEqualTo(State.COMPLETED);
            assertThat(idle.selectedPlayerIds()).isEmpty();
            assertThat(fetch.count(EndpointKind.BOOTSTRAP_STATIC)).isEqualTo(1);
            assertThat(fetch.count(EndpointKind.FIXTURES)).isEqualTo(1);
            assertThat(fetch.count(EndpointKind.EVENT_LIVE)).isZero();
            assertThat(fetch.count(EndpointKind.ELEMENT_SUMMARY)).isZero();
        }
    }

    @Test
    void legacyThenTwoCoalescedNonemptyPagesPreserveProducerWatermarkOrdering() throws Exception {
        int fixtureId = fixtureExplainedFor(eventFourLive, 658);
        byte[] source = onlyFixtureFinished(fixtures, fixtureId);
        List<CanonicalFixture> canonical = canonicalFixtures(source);
        byte[] originalPage = relabelHistoryPlayer(resource("element-summary-658.json"), WATERMARK_PLAYER);
        byte[] changedPage = changeHistoryPoints(originalPage, fixtureId, 6);
        String publicationKey = "player-history:" + WATERMARK_PLAYER;

        store.enqueue(SyncKind.PLAYER_HISTORY, season.code(), publicationKey, publicationKey, clock.instant());
        Lease legacy = store.acquire("legacy-page", Duration.ofMinutes(30)).orElseThrow();
        SourceRead legacyRead = nextRead();
        PublicationResult legacyPublication = publisher.publish(legacy, season, WATERMARK_PLAYER,
                document(originalPage, legacyRead), canonical);
        assertThat(legacyPublication.pfsRowsWritten()).isEqualTo(1);

        Lease firstCore = nextCoreLease();
        SourceRead firstCoreRead = nextRead();
        PublicationResult changed = publisher.publish(firstCore, season, WATERMARK_PLAYER,
                document(changedPage, firstCoreRead), canonical);
        assertThat(changed.pfsRowsWritten()).isEqualTo(1);
        store.completeStaged(firstCore, firstCoreRead, List.of(firstCoreRead));

        Lease secondCore = nextCoreLease();
        SourceRead secondCoreRead = nextRead();
        PublicationResult unchanged = publisher.publish(secondCore, season, WATERMARK_PLAYER,
                document(changedPage, secondCoreRead), canonical);
        assertThat(unchanged.pfsRowsWritten()).isZero();
        assertThat(unchanged.changedKeys()).isEmpty();
        store.completeStaged(secondCore, secondCoreRead, List.of(secondCoreRead));

        SourceRead obsoleteRead = nextRead();
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> publisher.publish(legacy, season,
                WATERMARK_PLAYER, document(originalPage, obsoleteRead), canonical)))
                .isInstanceOf(IncrementalPfsRepository.SourceOrderConflictException.class);
        assertThat(jdbc.queryForObject("SELECT total_points FROM player_fixture_stats WHERE season_id=? "
                + "AND fixture_id=? AND player_id=?", Integer.class, SEASON_ID, fixtureId, WATERMARK_PLAYER))
                .isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT latest_generation FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", Long.class, season.code(),
                IncrementalPlayerFixturePublisher.pageReceiptKey(WATERMARK_PLAYER)))
                .isEqualTo(secondCore.generation());
    }

    @Test
    void coalescesOneInProgressEventAndPrioritizesFinalPendingPages() throws Exception {
        ActiveFixture active = makeFixtureInProgress(markGameweekFinished(fixtures, 4), 4);
        byte[] source = addParticipantToFixture(
                addParticipantToFixture(active.body(), fixture(active.body(), 3, true).id(), FINAL_PENDING_PLAYER),
                active.id(), ACTIVE_PLAYER);
        fetch.setSources(bootstrap, source, eventThreeLive, eventFourLive);
        fetch.deferOnPlayerCall(1, clock.instant().plus(Duration.ofHours(2)));

        CycleResult result = collector(true).runCycle();

        assertThat(result.state()).isEqualTo(State.RETRY_SCHEDULED);
        assertThat(result.finalPendingGameweeks()).containsExactly(1, 2, 3);
        assertThat(result.selectedPlayerIds()).contains(FINAL_PENDING_PLAYER, ACTIVE_PLAYER);
        assertThat(result.selectedPlayerIds().indexOf(FINAL_PENDING_PLAYER))
                .isLessThan(result.selectedPlayerIds().indexOf(ACTIVE_PLAYER));
        assertThat(new HashSet<>(result.selectedPlayerIds())).hasSize(result.selectedPlayerIds().size());
        assertThat(fetch.count(EndpointKind.EVENT_LIVE)).isEqualTo(4);
        assertThat(fetch.playerIds()).hasSize(1);
        assertThat(fetch.playerIds().getFirst()).isEqualTo(result.selectedPlayerIds().getFirst());
        assertThat(result.nextDue()).isAfter(START.plus(Duration.ofHours(1)));
    }

    @Test
    void oneMissingPageDoesNotBlockOtherPagesAndCooldownIsDurable() throws Exception {
        fetch.setSources(bootstrap, fixtures, eventThreeLive, eventFourLive);
        fetch.failPlayerCall(1);
        fetch.deferOnPlayerCall(4, clock.instant().plus(Duration.ofHours(3)));

        CycleResult result = collector(true).runCycle();

        assertThat(result.state()).isEqualTo(State.RETRY_SCHEDULED);
        assertThat(result.selectedPlayerIds()).hasSize(657);
        assertThat(result.failedPlayerIds()).containsExactly(result.selectedPlayerIds().getFirst());
        assertThat(result.committedPlayerIds()).containsExactly(result.selectedPlayerIds().get(1),
                result.selectedPlayerIds().get(2));
        assertThat(fetch.playerIds()).containsExactly(result.selectedPlayerIds().subList(0, 4).toArray(Integer[]::new));
        assertThat(result.nextDue()).isAfter(clock.instant().minus(Duration.ofHours(3)));
        Integer generation = jdbc.queryForObject("SELECT count(*) FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=? AND latest_generation IS NOT NULL", Integer.class,
                season.code(), IncrementalPlayerFixturePublisher.pageReceiptKey(result.failedPlayerIds().getFirst()));
        assertThat(generation).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.requestState(), Integer.class)).isZero();
    }

    @Test
    void persistedPositiveLiveIdentityResumesItsMissingPostFinishPage() throws Exception {
        int playerId = 109;
        int liveOnlyFixtureId = fixtureExplainedFor(eventFourLive, playerId);
        byte[] source = emptyFixtureStats(onlyCollectorFixtureFinished(fixtures, liveOnlyFixtureId));
        fetch.setSources(bootstrap, source, eventThreeLive, eventFourLive);
        fetch.failPlayer(playerId);
        CoalescedPlayerHistoryCollector collector = collector(true);

        CycleResult first = collector.runCycle();

        assertThat(first.state()).isEqualTo(State.RETRY_SCHEDULED);
        assertThat(first.failedPlayerIds()).containsExactly(playerId);
        assertThat(first.selectedPlayerIds()).contains(playerId);
        assertThat(fetch.count(EndpointKind.EVENT_LIVE)).isEqualTo(1);

        fetch.clearCalls();
        clock.set(first.nextDue().plusSeconds(1));
        CycleResult resumed = collector.runCycle();

        assertThat(resumed.state()).isEqualTo(State.COMPLETED);
        assertThat(resumed.selectedPlayerIds()).containsExactly(playerId);
        assertThat(resumed.committedPlayerIds()).containsExactly(playerId);
        assertThat(fetch.playerIds()).containsExactly(playerId);
        assertThat(fetch.count(EndpointKind.EVENT_LIVE)).isEqualTo(1);
    }

    @Test
    void positiveLiveIdentitySurvivesRepeatedPageFailureAndLaterLiveOmission() throws Exception {
        int playerId = 109;
        int liveOnlyFixtureId = fixtureExplainedFor(eventFourLive, playerId);
        byte[] source = emptyFixtureStats(onlyCollectorFixtureFinished(fixtures, liveOnlyFixtureId));
        fetch.setSources(bootstrap, source, eventThreeLive, eventFourLive);
        fetch.failPlayerCalls(playerId, 2);
        CoalescedPlayerHistoryCollector collector = collector(true);

        CycleResult first = collector.runCycle();
        assertThat(first.state()).isEqualTo(State.RETRY_SCHEDULED);
        assertThat(first.selectedPlayerIds()).contains(playerId);
        assertThat(first.failedPlayerIds()).contains(playerId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", Integer.class, season.code(),
                OwnedSyncStore.INCREMENTAL_PARTICIPANT_RECEIPT_PREFIX + liveOnlyFixtureId + ":player:" + playerId))
                .isOne();

        jdbc.update("DELETE FROM " + TABLES.receipt() + " WHERE season=? AND publication_key=?",
                season.code(), OwnedSyncStore.INCREMENTAL_LIVE_EVENT_RECEIPT_PREFIX + 4);
        fetch.clearCalls();
        fetch.setEventFourLive("{\"elements\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        clock.set(first.nextDue().plusSeconds(1));
        CycleResult second = collector.runCycle();
        assertThat(second.state()).isEqualTo(State.RETRY_SCHEDULED);
        assertThat(second.selectedPlayerIds()).contains(playerId);
        assertThat(second.failedPlayerIds()).contains(playerId);
        assertThat(fetch.count(EndpointKind.EVENT_LIVE)).isEqualTo(1);

        fetch.clearCalls();
        clock.set(second.nextDue().plusSeconds(1));
        CycleResult third = collector.runCycle();
        assertThat(third.state()).isEqualTo(State.COMPLETED);
        assertThat(third.selectedPlayerIds()).contains(playerId);
        assertThat(third.committedPlayerIds()).contains(playerId);
        assertThat(fetch.playerIds()).contains(playerId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", Integer.class, season.code(),
                OwnedSyncStore.INCREMENTAL_PARTICIPANT_RECEIPT_PREFIX + liveOnlyFixtureId + ":player:" + playerId))
                .isOne();
    }

    @Test
    void idleUncheckedCycleAvoidsPfsPopulationHistoryAndStagedBodyReads() throws Exception {
        int fixtureId = fixtureExplainedFor(eventFourLive, 658);
        byte[] source = emptyFixtureStats(onlyCollectorFixtureFinished(fixtures, fixtureId));
        int cataloguePlayer = firstPlayerForFixture(bootstrap, source, fixtureId);
        fetch.setSources(bootstrapOnlyPlayer(bootstrap, cataloguePlayer), source, eventThreeLive,
                liveOnlyPlayer(eventFourLive, 658));
        SqlRecordingDataSource recording = useRecordingJdbc();
        CoalescedPlayerHistoryCollector collector = collector(true);

        CycleResult first = collector.runCycle();
        assertThat(first.state()).isEqualTo(State.COMPLETED);
        assertThat(first.selectedPlayerIds()).containsExactly(cataloguePlayer);
        assertThat(fetch.count(EndpointKind.EVENT_LIVE)).isOne();
        String incompleteAppearance = jdbc.queryForObject("SELECT publication_key FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key LIKE ? AND publication_key LIKE '%:c:0:p:0:%'",
                String.class, season.code(), IncrementalAppearanceReceipt.SNAPSHOT_PREFIX + fixtureId + ":%");
        assertThat(incompleteAppearance).contains(":c:0:p:0:");
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.gameweekStatSync()
                + " WHERE season_id=? AND gameweek=4", String.class, SEASON_ID)).isEqualTo("OPEN");
        Timestamp liveFinished = jdbc.queryForObject("SELECT source_read_finished_at FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", Timestamp.class, season.code(),
                OwnedSyncStore.INCREMENTAL_LIVE_EVENT_RECEIPT_PREFIX + 4);
        Timestamp historyStarted = jdbc.queryForObject("SELECT source_read_started_at FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", Timestamp.class, season.code(),
                IncrementalPlayerFixturePublisher.pageReceiptKey(cataloguePlayer));
        assertThat(historyStarted.toInstant()).isAfterOrEqualTo(liveFinished.toInstant());

        recording.clear();
        fetch.clearCalls();
        CycleResult idle = collector.runCycle();

        assertThat(idle.state()).isEqualTo(State.COMPLETED);
        assertThat(idle.selectedPlayerIds()).isEmpty();
        assertThat(fetch.count(EndpointKind.ELEMENT_SUMMARY)).isZero();
        assertThat(fetch.count(EndpointKind.EVENT_LIVE)).isZero();
        assertThat(recording.statements()).noneMatch(sql -> {
            String normalized = sql.toLowerCase(java.util.Locale.ROOT);
            return normalized.contains("player_fixture_stats")
                    || normalized.contains(TABLES.playerFixtureCurrent().toLowerCase(java.util.Locale.ROOT))
                    || normalized.contains(TABLES.payload().toLowerCase(java.util.Locale.ROOT)
                            + " where body")
                    || normalized.contains("element_summary");
        });
    }

    @Test
    void finalCapacityReceiptSurvivesSourceChangeAndClearsOnlyOnCompletionOrFailure() throws Exception {
        byte[] catalogue = markGameweekChecked(markAllGameweeksUnchecked(bootstrapOnlyPlayer(bootstrap, 1)), 3);
        byte[] source = fixtureStatsForPlayers(onlyCollectorFixtureFinished(fixtures, 29), 29, 1, 28);
        fetch.setSources(catalogue, source, liveForFixturePlayers(eventThreeLive, 29, Set.of(1, 28)), eventFourLive);
        fetch.setPlayerPage(1, onlyHistoryForFixture(resource("element-summary-1.json"), 29, 1));
        fetch.setPlayerPage(28, historyForAwayPlayer28(29));
        fetch.blockedPurpose = OwnedSyncRequestBudget.Purpose.FINAL;
        var wait = compatibilityCollector(true).runCycle();
        assertThat(wait.state()).isEqualTo(State.RETRY_SCHEDULED);
        assertThat(capacityReceipts()).isOne();
        clock.set(wait.nextDue());
        var changed = JSON.readTree(catalogue);
        ObjectNode player = (ObjectNode) changed.path("elements").get(0);
        player.put("now_cost", player.path("now_cost").asInt() + 1);
        fetch.setSources(JSON.writeValueAsBytes(changed), source,
                liveForFixturePlayers(eventThreeLive, 29, Set.of(1, 28)), eventFourLive);
        var superseded = compatibilityCollector(true).runCycle();
        assertThat(superseded.state()).isEqualTo(State.SUPERSEDED);
        assertThat(capacityReceipts()).isOne();
        clock.set(superseded.nextDue());
        wait = compatibilityCollector(true).runCycle();
        assertThat(wait.state()).isEqualTo(State.RETRY_SCHEDULED);
        assertThat(capacityReceipts()).isOne();
        clock.set(wait.nextDue());
        fetch.blockedPurpose = null;
        var complete = compatibilityCollector(true).runCycle();
        assertThat(complete.state()).isEqualTo(State.COMPLETED);
        assertThat(capacityReceipts()).isZero();

        var run = store.enqueueIncrementalCoreImport(season, clock.instant());
        var lease = store.acquireIncrementalCoreImport(season.code(), "capacity-failure-test", Duration.ofMinutes(5))
                .orElseThrow();
        store.recordIncrementalCapacityWait(lease);
        assertThat(capacityReceipts()).isOne();
        store.retryFetchFailure(lease, clock.instant().plusSeconds(3600), "actual upstream failure");
        assertThat(capacityReceipts()).isZero();
        assertThat(jdbc.queryForObject("SELECT consecutive_fetch_failures FROM " + TABLES.run()
                + " WHERE generation=?", Integer.class, run.generation())).isOne();
    }

    private int capacityReceipts() {
        return jdbc.queryForObject("SELECT count(*) FROM " + TABLES.receipt()
                + " WHERE publication_key='incremental-capacity-wait'", Integer.class);
    }

    @Test
    void discoveryCapacityWaitHasDurableProofAndRealSourceFailureClearsIt() throws Exception {
        fetch.blockedPurpose = OwnedSyncRequestBudget.Purpose.DISCOVERY;
        var wait = collector(true).runCycle();
        assertThat(wait.state()).isEqualTo(State.RETRY_SCHEDULED);
        assertThat(capacityReceipts()).isOne();
        clock.set(wait.nextDue());
        var lease = store.acquireIncrementalCoreImport(season.code(), "discovery-failure-test", Duration.ofMinutes(5))
                .orElseThrow();
        store.fail(lease, "invalid source identity");
        assertThat(capacityReceipts()).isZero();
    }

    @Test
    void finalCapacityWaitPersistsItsPurposeWithoutClaimingCompletion() throws Exception {
        fetch.setSources(bootstrap, fixtures, eventThreeLive, eventFourLive);
        fetch.blockedPurpose = OwnedSyncRequestBudget.Purpose.FINAL;
        var result = collector(true).runCycle();
        assertThat(result.state()).isEqualTo(State.RETRY_SCHEDULED);
        assertThat(result.committedPlayerIds()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT last_error FROM " + TABLES.run()
                + " ORDER BY generation DESC LIMIT 1", String.class))
                .isEqualTo("HTTP budget deferred: CAPACITY_RESERVED; purpose=FINAL");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.gameweekStatSync()
                + " WHERE state='FINALIZED'", Integer.class)).isZero();
    }

    @Test
    void realPageFailuresRemainVisibleWhenLaterPagesWaitForAdmission() throws Exception {
        int fixtureId = fixtureExplainedFor(eventFourLive, 658);
        byte[] source = emptyFixtureStats(onlyCollectorFixtureFinished(fixtures, fixtureId));
        var players = sourceCandidates(source, fixtureId).stream().limit(2)
                .collect(java.util.stream.Collectors.toSet());
        fetch.setSources(bootstrapPlayers(bootstrap, players), source, eventThreeLive,
                liveOnlyPlayer(eventFourLive, 658));
        for (int cycle = 1; cycle <= 3; cycle++) {
            fetch.clearCalls();
            fetch.failPlayerCall(1);
            fetch.deferOnPlayerCall(2, clock.instant().plus(Duration.ofHours(8)));
            var result = collector(true).runCycle();
            assertThat(result.state()).isEqualTo(State.RETRY_SCHEDULED);
            assertThat(result.failedPlayerIds()).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT last_error FROM " + TABLES.run()
                    + " ORDER BY generation DESC LIMIT 1", String.class)).contains("Unresolved player pages: 1");
            assertThat(jdbc.queryForObject("SELECT consecutive_fetch_failures FROM " + TABLES.run()
                    + " ORDER BY generation DESC LIMIT 1", Integer.class)).isEqualTo(cycle);
            clock.set(result.nextDue());
        }
    }

    @Test
    void refusedLiveEventDoesNotStarvePostMatchPagesFromAnotherEvent() throws Exception {
        ActiveFixture active = makeFixtureInProgress(markGameweekFinished(fixtures, 4), 4);
        int finishedFixture = fixture(active.body(), 3, true).id();
        byte[] source = emptyFixtureStats(makeFixtureInProgress(
                onlyFixturesFinished(fixtures, finishedFixture, active.id()), 4).body());
        int player = firstPlayerForFixture(bootstrap, source, finishedFixture);
        byte[] unchecked = markGameweekUnchecked(markGameweekUnchecked(markGameweekUnchecked(bootstrap, 1), 2), 3);
        fetch.setSources(bootstrapOnlyPlayer(unchecked, player), source, eventThreeLive, eventFourLive);
        fetch.blockedPurpose = OwnedSyncRequestBudget.Purpose.LIVE;
        var result = collector(true).runCycle();
        assertThat(result.state()).isEqualTo(State.RETRY_SCHEDULED);
        assertThat(result.committedPlayerIds()).contains(player);
        assertThat(fetch.demands).extracting(OwnedSyncRequestBudget.Demand::purpose)
                .contains(OwnedSyncRequestBudget.Purpose.LIVE, OwnedSyncRequestBudget.Purpose.POST_MATCH);
    }

    @Test
    void capacityWaitKeepsHourlyDiscoveryAndDoesNotBecomeRepeatedFetchFailure() throws Exception {
        int fixtureId = fixtureExplainedFor(eventFourLive, 658);
        byte[] source = emptyFixtureStats(onlyCollectorFixtureFinished(fixtures, fixtureId));
        int player = firstPlayerForFixture(bootstrap, source, fixtureId);
        fetch.setSources(bootstrapOnlyPlayer(bootstrap, player), source, eventThreeLive,
                liveOnlyPlayer(eventFourLive, 658));
        fetch.blockedPurpose = OwnedSyncRequestBudget.Purpose.POST_MATCH;
        for (int hour = 0; hour < 4; hour++) {
            fetch.clearCalls();
            var result = collector(true).runCycle();
            assertThat(result.state()).isEqualTo(State.RETRY_SCHEDULED);
            assertThat(result.nextDue()).isBefore(clock.instant().plus(Duration.ofHours(2)));
            assertThat(fetch.count(EndpointKind.BOOTSTRAP_STATIC)).isOne();
            assertThat(fetch.count(EndpointKind.FIXTURES)).isOne();
            assertThat(jdbc.queryForObject("SELECT max(consecutive_fetch_failures) FROM " + TABLES.run(),
                    Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT last_error FROM " + TABLES.run()
                    + " ORDER BY generation DESC LIMIT 1", String.class))
                    .isEqualTo("HTTP budget deferred: CAPACITY_RESERVED");
            clock.set(result.nextDue());
        }
        assertThat(capacityReceipts()).as("post-match waits do not prove final-capacity exhaustion").isZero();
        fetch.blockedPurpose = null;
        assertThat(collector(true).runCycle().committedPlayerIds()).containsExactly(player);
        assertThat(fetch.demands).anySatisfy(demand -> {
            assertThat(demand.purpose()).isEqualTo(OwnedSyncRequestBudget.Purpose.POST_MATCH);
            assertThat(demand.players()).isPositive();
        });
    }

    @Test
    void provisionalFinishStopsHourlyRefetchButCorrectionAndRealFinishRefresh() throws Exception {
        int fixtureId = fixtureExplainedFor(eventFourLive, 658);
        byte[] finished = emptyFixtureStats(onlyCollectorFixtureFinished(fixtures, fixtureId));
        var provisional = JSON.readTree(finished);
        for (var row : provisional) {
            if (row.path("id").asInt() == fixtureId) {
                ((ObjectNode) row).put("finished", false).put("finished_provisional", true);
            }
        }
        int player = firstPlayerForFixture(bootstrap, finished, fixtureId);
        byte[] catalogue = bootstrapOnlyPlayer(bootstrap, player);
        byte[] source = JSON.writeValueAsBytes(provisional);
        fetch.setSources(catalogue, source, eventThreeLive, liveOnlyPlayer(eventFourLive, 658));
        var first = collector(true).runCycle();
        assertThat(first.state()).isEqualTo(State.COMPLETED);
        assertThat(first.selectedPlayerIds()).containsExactly(player);

        for (int hour = 0; hour < 3; hour++) {
            clock.advance(Duration.ofHours(1));
            fetch.clearCalls();
            var idle = collector(true).runCycle();
            assertThat(idle.state()).isEqualTo(State.COMPLETED);
            assertThat(fetch.count(EndpointKind.ELEMENT_SUMMARY)).isZero();
            assertThat(fetch.count(EndpointKind.EVENT_LIVE)).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.gameweekStatSync()
                + " WHERE season_id=? AND gameweek=4", String.class, SEASON_ID)).isEqualTo("OPEN");

        for (var row : provisional) {
            if (row.path("id").asInt() == fixtureId) ((ObjectNode) row).put("finished_provisional", false);
        }
        fetch.setSources(catalogue, JSON.writeValueAsBytes(provisional), eventThreeLive,
                liveOnlyPlayer(eventFourLive, 658));
        for (int hour = 0; hour < 2; hour++) {
            clock.advance(Duration.ofHours(1));
            assertThat(collector(true).runCycle().selectedPlayerIds()).containsExactly(player);
        }
        fetch.setSources(catalogue, source, eventThreeLive, liveOnlyPlayer(eventFourLive, 658));
        assertThat(collector(true).runCycle().selectedPlayerIds()).containsExactly(player);
        assertThat(collector(true).runCycle().selectedPlayerIds()).isEmpty();

        fetch.setSources(catalogue, changeFixtureScore(source, fixtureId, 3), eventThreeLive,
                liveOnlyPlayer(eventFourLive, 658));
        assertThat(collector(true).runCycle().selectedPlayerIds()).containsExactly(player);
        fetch.setSources(catalogue, finished, eventThreeLive, liveOnlyPlayer(eventFourLive, 658));
        assertThat(collector(true).runCycle().selectedPlayerIds()).containsExactly(player);
    }

    @Test
    void uncheckedFixtureHeaderCorrectionRefreshesOnlyItsPlayerPages() throws Exception {
        int affectedFixture = 31;
        int unaffectedFixture = 32;
        byte[] original = onlyFixturesFinished(fixtures, affectedFixture, unaffectedFixture);
        fetch.setSources(bootstrap, original, eventThreeLive, eventFourLive);
        CoalescedPlayerHistoryCollector collector = collector(true);

        CycleResult first = collector.runCycle();
        assertThat(first.state()).isEqualTo(State.COMPLETED);
        Instant unaffectedMarker = finishedMarker(unaffectedFixture);
        Instant affectedMarker = finishedMarker(affectedFixture);
        Set<Integer> unaffectedPlayers = sourceCandidates(original, unaffectedFixture);
        Set<Integer> affectedPlayers = sourceCandidates(original, affectedFixture);
        assertThat(first.selectedPlayerIds()).containsAll(affectedPlayers).containsAll(unaffectedPlayers);

        byte[] corrected = changeFixtureScore(original, affectedFixture, 3);
        fetch.clearCalls();
        fetch.setSources(bootstrap, corrected, eventThreeLive, eventFourLive);
        CycleResult second = collector.runCycle();

        assertThat(second.state()).isEqualTo(State.COMPLETED);
        assertThat(second.selectedPlayerIds()).containsAll(affectedPlayers)
                .doesNotContainAnyElementsOf(unaffectedPlayers);
        assertThat(finishedMarker(affectedFixture)).isAfter(affectedMarker);
        assertThat(finishedMarker(unaffectedFixture)).isEqualTo(unaffectedMarker);
        assertThat(second.finalPendingGameweeks()).doesNotContain(4);
        assertThat(jdbc.queryForObject("SELECT data_checked FROM " + TABLES.gameweekStatSync()
                + " WHERE season_id=? AND gameweek=4", Boolean.class, SEASON_ID)).isFalse();
    }

    @Test
    void resumesPartialPagesWithTheActualDurableRunReadInterval() throws Exception {
        int fixtureId = fixtureExplainedFor(eventFourLive, 658);
        byte[] source = onlyCollectorFixtureFinished(fixtures, fixtureId);
        source = emptyFixtureStats(source);
        source = addParticipantToFixture(addParticipantToFixture(source, fixtureId, 99002), fixtureId, 99004);
        int cataloguePlayer = firstPlayerForFixture(bootstrap, source, fixtureId);
        fetch.setSources(bootstrapOnlyPlayer(bootstrap, cataloguePlayer), source, eventThreeLive,
                "{\"elements\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        fetch.deferOnPlayerCall(3, clock.instant().plus(Duration.ofHours(2)));
        CoalescedPlayerHistoryCollector collector = collector(true);

        CycleResult first = collector.runCycle();
        long generation = jdbc.queryForObject("SELECT generation FROM " + TABLES.run()
                + " WHERE producer_key=? AND season=?", Long.class,
                OwnedSyncStore.INCREMENTAL_CORE_PRODUCER, season.code());
        List<RecordedRead> firstReads = fetch.successfulReads();
        assertThat(first.state()).isEqualTo(State.RETRY_SCHEDULED);
        assertThat(first.committedPlayerIds()).containsExactly(cataloguePlayer, 99002);
        assertThat(first.selectedPlayerIds()).containsExactly(cataloguePlayer, 99002, 99004);
        Instant originalStart = firstReads.stream().map(read -> read.read().startedAt())
                .min(Instant::compareTo).orElseThrow();

        fetch.clearCalls();
        fetch.clearPageOutcomes();
        clock.set(first.nextDue().plusSeconds(1));
        CycleResult resumed = collector.runCycle();
        List<RecordedRead> resumedReads = fetch.successfulReads();

        assertThat(resumed.state()).isEqualTo(State.COMPLETED);
        assertThat(resumed.selectedPlayerIds()).containsExactly(99004);
        assertThat(resumed.committedPlayerIds()).containsExactly(99004);
        assertThat(resumedReads.getLast().endpoint().kind()).isEqualTo(EndpointKind.FIXTURES);
        var interval = jdbc.queryForObject("SELECT source_read_started_at, source_read_finished_at FROM "
                + TABLES.run() + " WHERE generation=?", (rs, row) -> new SourceRead(
                rs.getTimestamp(1).toInstant(), rs.getTimestamp(2).toInstant()), generation);
        assertThat(interval.startedAt()).isEqualTo(originalStart);
        assertThat(interval.finishedAt()).isEqualTo(resumedReads.getLast().read().finishedAt());
        assertThat(resumedReads.getLast().read().finishedAt()).isAfter(originalStart);
    }

    @Test
    void compatibilityRetryReusesPublishedPairAndCorrectionUsesANewGeneration() throws Exception {
        int fixtureId = 29;
        byte[] source = fixtureStatsForPlayers(
                onlyCollectorFixtureFinished(fixtures, fixtureId), fixtureId, 1, 28);
        byte[] sourceBootstrap = markAllGameweeksUnchecked(bootstrapPlayers(bootstrap, Set.of(1)));
        byte[] live = liveForFixturePlayers(eventThreeLive, fixtureId, Set.of(1, 28));
        fetch.setSources(sourceBootstrap, source, live, eventFourLive);
        fetch.setPlayerPage(1, onlyHistoryForFixture(resource("element-summary-1.json"), fixtureId, 1));
        fetch.setPlayerPage(28, historyForAwayPlayer28(fixtureId));
        fetch.failPlayer(28);
        SqlRecordingDataSource recording = new SqlRecordingDataSource(dataSource);
        JdbcTemplate recordedJdbc = new JdbcTemplate(recording);
        TransactionTemplate recordedTx = new TransactionTemplate(new DataSourceTransactionManager(recording));
        CoalescedPlayerHistoryCollector collector = compatibilityCollector(true, recordedJdbc, recordedTx);
        AtomicReference<CycleResult> competingCycle = new AtomicReference<>();
        fetch.onFirstRequest(() -> competingCycle.set(collector.runCycle()));
        installUnit6WriteCounters();
        CycleResult first;
        long cycleMillis;
        Map<String, Long> physicalWrites;
        try {
            long cycleStarted = System.nanoTime();
            first = collector.runCycle();
            cycleMillis = Duration.ofNanos(System.nanoTime() - cycleStarted).toMillis();
            physicalWrites = unit6WriteCounts();
        } finally {
            removeUnit6WriteCounters();
        }

        assertThat(competingCycle.get().state()).isEqualTo(State.BUSY);
        assertThat(first.state()).isEqualTo(State.RETRY_SCHEDULED);
        assertThat(first.committedPlayerIds()).containsExactly(1);
        assertThat(first.failedPlayerIds()).containsExactly(28);
        System.out.printf("UNIT6_PARTIAL gets={bootstrap:%d,fixtures:%d,event-live:%d,history:%d} "
                        + "successfulHistoryPlayers=%s bodyBytes=%d jdbc={statements:%d,queries:%d,updates:%d,"
                        + "rows:%d,logicalBytes~%d} physicalWrites=%s cycleWallMs=%d%n",
                fetch.count(EndpointKind.BOOTSTRAP_STATIC), fetch.count(EndpointKind.FIXTURES),
                fetch.count(EndpointKind.EVENT_LIVE), fetch.count(EndpointKind.ELEMENT_SUMMARY),
                fetch.successfulReads().stream().filter(read -> read.endpoint().kind() == EndpointKind.ELEMENT_SUMMARY)
                        .map(read -> read.endpoint().playerId()).toList(), fetch.returnedBodyBytes(),
                recording.statements().size(),
                recording.queryExecutions(), recording.updateExecutions(), recording.returnedRows(),
                recording.approximateLogicalReturnedBytes(), physicalWrites, cycleMillis);
        Map<String, Object> originalReceipt = jdbc.queryForMap("SELECT latest_generation, "
                + "source_bootstrap_payload_id, source_fixtures_payload_id, source_pair_fingerprint, "
                + "source_read_started_at, source_read_finished_at FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", season.code(),
                OwnedSyncStore.INCREMENTAL_COMPATIBILITY_PUBLICATION_KEY);
        long firstGeneration = ((Number) originalReceipt.get("latest_generation")).longValue();
        long bootstrapPayloadId = ((Number) originalReceipt.get("source_bootstrap_payload_id")).longValue();
        long fixturePayloadId = ((Number) originalReceipt.get("source_fixtures_payload_id")).longValue();
        Instant originalStart = ((java.sql.Timestamp) originalReceipt.get("source_read_started_at")).toInstant();
        byte[] originalBootstrapBody = jdbc.queryForObject("SELECT body FROM " + TABLES.payload()
                + " WHERE id=?", byte[].class, bootstrapPayloadId);
        byte[] originalFixtureBody = jdbc.queryForObject("SELECT body FROM " + TABLES.payload()
                + " WHERE id=?", byte[].class, fixturePayloadId);

        fetch.clearCalls();
        fetch.clearPageOutcomes();
        clock.set(first.nextDue().plusSeconds(1));
        CycleResult resumed = collector.runCycle();

        assertThat(resumed.state()).isEqualTo(State.COMPLETED);
        assertThat(resumed.selectedPlayerIds()).containsExactly(28);
        assertThat(resumed.committedPlayerIds()).containsExactly(28);
        assertThat(fetch.playerIds()).containsExactly(28);
        assertThat(fetch.count(EndpointKind.BOOTSTRAP_STATIC)).isOne();
        assertThat(fetch.count(EndpointKind.FIXTURES)).isOne();
        assertThat(fetch.count(EndpointKind.ELEMENT_SUMMARY)).isOne();
        assertThat(fetch.returnedBodyBytes()).isPositive();
        Map<String, Object> resumedReceipt = jdbc.queryForMap("SELECT latest_generation, "
                + "source_bootstrap_payload_id, source_fixtures_payload_id, source_pair_fingerprint, "
                + "source_read_started_at, source_read_finished_at FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", season.code(),
                OwnedSyncStore.INCREMENTAL_COMPATIBILITY_PUBLICATION_KEY);
        assertThat(resumedReceipt.get("latest_generation")).isEqualTo(firstGeneration);
        assertThat(resumedReceipt.get("source_bootstrap_payload_id")).isEqualTo(bootstrapPayloadId);
        assertThat(resumedReceipt.get("source_fixtures_payload_id")).isEqualTo(fixturePayloadId);
        assertThat(resumedReceipt.get("source_pair_fingerprint")).isEqualTo(originalReceipt.get("source_pair_fingerprint"));
        assertThat(jdbc.queryForObject("SELECT body FROM " + TABLES.payload() + " WHERE id=?",
                byte[].class, bootstrapPayloadId)).containsExactly(originalBootstrapBody);
        assertThat(jdbc.queryForObject("SELECT body FROM " + TABLES.payload() + " WHERE id=?",
                byte[].class, fixturePayloadId)).containsExactly(originalFixtureBody);
        SourceRead resumedRunRead = jdbc.queryForObject("SELECT source_read_started_at, source_read_finished_at "
                + "FROM " + TABLES.run() + " WHERE generation=?", (rs, row) -> new SourceRead(
                rs.getTimestamp(1).toInstant(), rs.getTimestamp(2).toInstant()), firstGeneration);
        assertThat(resumedRunRead.startedAt()).isEqualTo(originalStart);
        assertThat(resumedRunRead.finishedAt()).isEqualTo(fetch.successfulReads().getLast().read().finishedAt());
        System.out.printf("UNIT6_PARTIAL_RESUME gets={bootstrap:%d,fixtures:%d,event-live:%d,history:%d} "
                        + "historyPlayers=%s bodyBytes=%d originalGeneration=%d sourceInterval=%s..%s%n",
                fetch.count(EndpointKind.BOOTSTRAP_STATIC), fetch.count(EndpointKind.FIXTURES),
                fetch.count(EndpointKind.EVENT_LIVE), fetch.count(EndpointKind.ELEMENT_SUMMARY),
                fetch.playerIds(), fetch.returnedBodyBytes(), firstGeneration,
                resumedRunRead.startedAt(), resumedRunRead.finishedAt());

        byte[] correctedFixtures = changeFixtureScore(source, fixtureId, 3);
        fetch.clearCalls();
        fetch.setSources(sourceBootstrap, correctedFixtures, live, eventFourLive);
        CycleResult corrected = collector.runCycle();

        assertThat(corrected.state()).isEqualTo(State.COMPLETED);
        long correctedGeneration = jdbc.queryForObject("SELECT generation FROM " + TABLES.run()
                + " WHERE producer_key=? AND season=? AND state='COMPLETED' ORDER BY generation DESC LIMIT 1",
                Long.class, OwnedSyncStore.INCREMENTAL_CORE_PRODUCER, season.code());
        Map<String, Object> correctedReceipt = jdbc.queryForMap("SELECT latest_generation, "
                + "source_bootstrap_payload_id, source_fixtures_payload_id, source_pair_fingerprint FROM "
                + TABLES.receipt() + " WHERE season=? AND publication_key=?", season.code(),
                OwnedSyncStore.INCREMENTAL_COMPATIBILITY_PUBLICATION_KEY);
        assertThat(correctedGeneration).isGreaterThan(firstGeneration);
        assertThat(correctedReceipt.get("latest_generation")).isEqualTo(correctedGeneration);
        assertThat(correctedReceipt.get("source_bootstrap_payload_id")).isNotEqualTo(bootstrapPayloadId);
        assertThat(correctedReceipt.get("source_fixtures_payload_id")).isNotEqualTo(fixturePayloadId);
        assertThat(jdbc.queryForObject("SELECT source_published FROM " + TABLES.payload()
                + " WHERE id=?", Boolean.class, bootstrapPayloadId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT source_published FROM " + TABLES.payload()
                + " WHERE id=?", Boolean.class, fixturePayloadId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT body FROM " + TABLES.payload() + " WHERE id=?",
                byte[].class, bootstrapPayloadId)).containsExactly(originalBootstrapBody);
        assertThat(jdbc.queryForObject("SELECT body FROM " + TABLES.payload() + " WHERE id=?",
                byte[].class, fixturePayloadId)).containsExactly(originalFixtureBody);
    }

    @Test
    void correctedFixtureHeaderSupersedesPartialPairThenRefreshesAffectedPages() throws Exception {
        int fixtureId = 29;
        Instant correctedKickoff = Instant.parse("2026-09-13T17:00:00Z");
        byte[] originalFixtures = fixtureStatsForPlayers(
                onlyCollectorFixtureFinished(fixtures, fixtureId), fixtureId, 1, 28);
        byte[] sourceBootstrap = markAllGameweeksUnchecked(bootstrapPlayers(bootstrap, Set.of(1)));
        byte[] live = liveForFixturePlayers(eventThreeLive, fixtureId, Set.of(1, 28));
        fetch.setSources(sourceBootstrap, originalFixtures, live, live);
        fetch.setPlayerPage(1, onlyHistoryForFixture(resource("element-summary-1.json"), fixtureId, 1));
        fetch.setPlayerPage(28, historyForAwayPlayer28(fixtureId));
        fetch.failPlayer(28);
        CoalescedPlayerHistoryCollector collector = compatibilityCollector(true);

        CycleResult first = collector.runCycle();

        assertThat(first.state()).isEqualTo(State.RETRY_SCHEDULED);
        assertThat(first.committedPlayerIds()).containsExactly(1);
        assertThat(first.failedPlayerIds()).containsExactly(28);
        Map<String, Object> originalReceipt = jdbc.queryForMap("SELECT latest_generation, "
                + "source_bootstrap_payload_id, source_fixtures_payload_id, source_pair_fingerprint, "
                + "source_read_started_at, source_read_finished_at, source_bootstrap_read_started_at, "
                + "source_bootstrap_read_finished_at, source_fixtures_read_started_at, "
                + "source_fixtures_read_finished_at FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", season.code(),
                OwnedSyncStore.INCREMENTAL_COMPATIBILITY_PUBLICATION_KEY);
        long oldGeneration = ((Number) originalReceipt.get("latest_generation")).longValue();
        long bootstrapPayloadId = ((Number) originalReceipt.get("source_bootstrap_payload_id")).longValue();
        long fixturesPayloadId = ((Number) originalReceipt.get("source_fixtures_payload_id")).longValue();
        byte[] oldBootstrapBody = jdbc.queryForObject("SELECT body FROM " + TABLES.payload()
                + " WHERE id=?", byte[].class, bootstrapPayloadId);
        byte[] oldFixturesBody = jdbc.queryForObject("SELECT body FROM " + TABLES.payload()
                + " WHERE id=?", byte[].class, fixturesPayloadId);
        Map<String, Object> oldRunInterval = jdbc.queryForMap("SELECT source_read_started_at, "
                + "source_read_finished_at FROM " + TABLES.run() + " WHERE generation=?", oldGeneration);
        Map<String, Object> oldPlayerRow = jdbc.queryForMap("SELECT gameweek,kickoff_time FROM player_fixture_stats "
                + "WHERE season_id=? AND fixture_id=? AND player_id=1", SEASON_ID, fixtureId);
        Long oldPlayerGeneration = jdbc.queryForObject("SELECT source_run_generation FROM "
                + TABLES.playerFixtureCurrent() + " WHERE season_id=? AND fixture_id=? AND player_id=1",
                Long.class, SEASON_ID, fixtureId);

        byte[] correctedFixtures = reassignFixture(originalFixtures, fixtureId, 4);
        fetch.clearCalls();
        fetch.clearPageOutcomes();
        fetch.setSources(sourceBootstrap, correctedFixtures, live, live);
        clock.set(first.nextDue().plusSeconds(1));
        CycleResult superseded = collector.runCycle();

        assertThat(superseded.state()).isEqualTo(State.SUPERSEDED);
        assertThat(superseded.committedPlayerIds()).isEmpty();
        assertThat(fetch.playerIds()).isEmpty();
        assertThat(fetch.count(EndpointKind.BOOTSTRAP_STATIC)).isOne();
        assertThat(fetch.count(EndpointKind.FIXTURES)).isOne();
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.run() + " WHERE generation=?",
                String.class, oldGeneration)).isEqualTo("SUPERSEDED");
        Map<String, Object> stillOldReceipt = jdbc.queryForMap("SELECT latest_generation, "
                + "source_bootstrap_payload_id, source_fixtures_payload_id, source_pair_fingerprint, "
                + "source_read_started_at, source_read_finished_at, source_bootstrap_read_started_at, "
                + "source_bootstrap_read_finished_at, source_fixtures_read_started_at, "
                + "source_fixtures_read_finished_at FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", season.code(),
                OwnedSyncStore.INCREMENTAL_COMPATIBILITY_PUBLICATION_KEY);
        assertThat(stillOldReceipt).isEqualTo(originalReceipt);
        assertThat(jdbc.queryForObject("SELECT body FROM " + TABLES.payload() + " WHERE id=?",
                byte[].class, bootstrapPayloadId)).containsExactly(oldBootstrapBody);
        assertThat(jdbc.queryForObject("SELECT body FROM " + TABLES.payload() + " WHERE id=?",
                byte[].class, fixturesPayloadId)).containsExactly(oldFixturesBody);
        assertThat(jdbc.queryForObject("SELECT gameweek FROM fixture WHERE season_id=? AND id=?",
                Integer.class, SEASON_ID, fixtureId)).isEqualTo(3);
        assertThat(jdbc.queryForMap("SELECT source_read_started_at, source_read_finished_at FROM "
                + TABLES.run() + " WHERE generation=?", oldGeneration)).isEqualTo(oldRunInterval);
        assertThat(jdbc.queryForMap("SELECT gameweek,kickoff_time FROM player_fixture_stats "
                + "WHERE season_id=? AND fixture_id=? AND player_id=1", SEASON_ID, fixtureId)).isEqualTo(oldPlayerRow);
        assertThat(jdbc.queryForObject("SELECT source_run_generation FROM " + TABLES.playerFixtureCurrent()
                + " WHERE season_id=? AND fixture_id=? AND player_id=1", Long.class, SEASON_ID, fixtureId))
                .isEqualTo(oldPlayerGeneration);
        assertThat(new PfsReadSource(jdbc, "ACTIVE", season.code(), TABLES.fixtureSync(),
                TABLES.playerFixtureEvidence(), TABLES.playerFixtureCurrent(), TABLES.receipt())
                .incrementalRowsByGameweek(SEASON_ID, Set.of(fixtureId)).get(3).incrementalRows()).isPositive();

        byte[] correctedPlayerOne = rewriteHistoryFixture(
                onlyHistoryForFixture(resource("element-summary-1.json"), fixtureId, 1),
                fixtureId, 4, correctedKickoff);
        byte[] correctedPlayerTwentyEight = rewriteHistoryFixture(
                historyForAwayPlayer28(fixtureId), fixtureId, 4, correctedKickoff);
        fetch.setPlayerPage(1, correctedPlayerOne);
        fetch.setPlayerPage(28, correctedPlayerTwentyEight);
        SqlRecordingDataSource recording = new SqlRecordingDataSource(dataSource);
        JdbcTemplate recordedJdbc = new JdbcTemplate(recording);
        TransactionTemplate recordedTx = new TransactionTemplate(new DataSourceTransactionManager(recording));
        fetch.clearCalls();
        CycleResult corrected = compatibilityCollector(true, recordedJdbc, recordedTx).runCycle();

        assertThat(corrected.state()).isEqualTo(State.COMPLETED);
        assertThat(corrected.committedPlayerIds()).containsExactly(1, 28);
        assertThat(fetch.playerIds()).containsExactly(1, 28);
        assertThat(fetch.count(EndpointKind.BOOTSTRAP_STATIC)).isOne();
        assertThat(fetch.count(EndpointKind.FIXTURES)).isOne();
        assertThat(recording.statements().stream().map(sql -> sql.toLowerCase(java.util.Locale.ROOT))
                .filter(sql -> sql.contains("insert into player_fixture_stats"))).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT gameweek FROM fixture WHERE season_id=? AND id=?",
                Integer.class, SEASON_ID, fixtureId)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM player_fixture_stats WHERE season_id=? "
                + "AND fixture_id=? AND player_id IN (1,28) AND gameweek=4 AND kickoff_time=?",
                Integer.class, SEASON_ID, fixtureId, java.sql.Timestamp.from(correctedKickoff))).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.fixtureSync()
                + " WHERE season_id=? AND fixture_id=? AND current_verified_generation IS NOT NULL",
                Integer.class, SEASON_ID, fixtureId)).isZero();
        assertThat(jdbc.queryForObject("SELECT latest_generation FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", Long.class, season.code(),
                OwnedSyncStore.INCREMENTAL_COMPATIBILITY_PUBLICATION_KEY)).isGreaterThan(oldGeneration);
        assertThat(jdbc.queryForObject("SELECT body FROM " + TABLES.payload() + " WHERE id=?",
                byte[].class, bootstrapPayloadId)).containsExactly(oldBootstrapBody);
        assertThat(jdbc.queryForObject("SELECT body FROM " + TABLES.payload() + " WHERE id=?",
                byte[].class, fixturesPayloadId)).containsExactly(oldFixturesBody);
    }

    @Test
    void elapsedUnstartedFixtureIsCollectedWithoutPromotingSourceFlags() throws Exception {
        int fixtureId = 40;
        Instant elapsedKickoff = Instant.parse("2026-09-13T13:00:00Z");
        byte[] source = emptyFixtureStats(onlyFixtureUnstartedPastKickoff(fixtures, fixtureId, elapsedKickoff));
        int cataloguePlayer = firstPlayerForFixture(bootstrap, source, fixtureId);
        fetch.setSources(bootstrapOnlyPlayer(bootstrap, cataloguePlayer), source,
                eventThreeLive, "{\"elements\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        CycleResult result = collector(true).runCycle();

        assertThat(result.state()).isEqualTo(State.COMPLETED);
        assertThat(result.selectedPlayerIds()).containsExactly(cataloguePlayer);
        assertThat(result.finalPendingGameweeks()).doesNotContain(4);
        assertThat(fetch.count(EndpointKind.ELEMENT_SUMMARY)).isEqualTo(1);
        assertThat(fetch.count(EndpointKind.EVENT_LIVE)).isEqualTo(1);
        FixtureKey fixture = fixtureById(source, fixtureId);
        assertThat(fixture.started()).isFalse();
        assertThat(fixture.finished()).isFalse();
        assertThat(jdbc.queryForObject("SELECT data_checked FROM " + TABLES.gameweekStatSync()
                + " WHERE season_id=? AND gameweek=4", Boolean.class, SEASON_ID)).isFalse();
    }

    @Test
    void finalityCheckpointResumesFreshPageAfterCrashAndFinalizesOnlyAfterEndRead() throws Exception {
        CheckedFixtureSetup setup = checkedSingleFixtureSetup();
        int fixtureId = setup.fixtureId();
        int homePlayer = setup.cataloguePlayer();
        int emptyCataloguePlayer = 2;
        int awayPlayer = 28;
        byte[] sourceFixtures = setup.fixtureSource();
        byte[] checkedBootstrap = markGameweekChecked(bootstrapPlayers(bootstrap,
                Set.of(homePlayer, emptyCataloguePlayer)), 3);
        SourceRead initialRead = new SourceRead(START, START.plusMillis(3));
        var initialSnapshot = CatalogueScheduleParser.parse(season,
                document(checkedBootstrap, new SourceRead(START, START.plusMillis(1))),
                document(sourceFixtures, new SourceRead(START.plusMillis(2), START.plusMillis(3))));
        var source = GameweekFinality.SourceSnapshot.from(initialSnapshot, 3);
        seedReaderSource(initialSnapshot, source);
        GameweekFinalityReader reader = new GameweekFinalityReader(jdbc, clock, TABLES);
        var checkedPending = reader.read(SEASON_ID, season.code(), Set.of(3)).get(3);
        assertThat(checkedPending.state()).isEqualTo(GameweekFinality.State.FINAL_PENDING);
        assertThat(checkedPending.receipt()).isNull();
        var fixtureRow = initialSnapshot.fixtures().stream().filter(row -> row.id() == fixtureId)
                .findFirst().orElseThrow();
        var liveRead = new CatalogueScheduleParser.ReadInterval(START.plusMillis(4), START.plusMillis(5));
        var evidence = FixtureVerification.fixtureEvidenceByFixture(List.of(fixtureRow),
                new SourceDocument(setup.liveSource(), liveRead));
        var population = new GameweekFinality.RequiredPopulation(
                GameweekFinality.catalogueFingerprint(initialSnapshot, source.assignedFixtures()),
                Map.of(fixtureId, Set.of(homePlayer, emptyCataloguePlayer)),
                Map.of(fixtureId, Set.of(homePlayer, awayPlayer)), Map.of(), evidence);
        store.enqueueIncrementalCoreImport(season, START);
        Lease lease = store.acquireIncrementalCoreImport(season.code(), "finality-test", Duration.ofMinutes(30))
                .orElseThrow();
        store.observeIncrementalSnapshot(lease, initialSnapshot);
        clock.set(liveRead.completedAt());
        store.recordIncrementalFixtureSourceRevisions(lease, initialSnapshot.fixtures(),
                new SourceRead(START.plusMillis(2), START.plusMillis(3)));
        store.recordIncrementalLiveEvent(lease, 3, source.signature(),
                Map.of(fixtureId, Set.of(homePlayer, awayPlayer)), List.of(fixtureRow), evidence,
                new SourceRead(liveRead.startedAt(), liveRead.completedAt()));
        GameweekFinalityRepository finality = new GameweekFinalityRepository(jdbc, store, TABLES, clock);

        var started = finality.beginOrResume(lease, season, source, population, initialRead);
        assertThat(started.state()).isEqualTo(dev.quantti.fplassistant.sync.GameweekFinality.State.FINAL_PENDING);
        assertThat(started.checkpoint().requiredPlayerIds()).containsExactly(homePlayer, emptyCataloguePlayer, awayPlayer);
        assertThat(started.checkpoint().outstandingPlayerIds())
                .containsExactly(homePlayer, emptyCataloguePlayer, awayPlayer);
        clock.set(started.checkpoint().sourceFloor().plusMillis(1));
        SourceRead pageRead = new SourceRead(clock.instant(), clock.instant().plusMillis(1));
        clock.set(pageRead.finishedAt());
        List<CanonicalFixture> canonical = initialSnapshot.fixtures().stream()
                .map(HistoryParser::canonicalFixture).toList();
        byte[] homeHistory = onlyHistoryForFixture(resource("element-summary-1.json"), fixtureId, homePlayer);
        publisher.publish(lease, season, homePlayer, document(homeHistory, pageRead), canonical);
        finality.recordCompletedPage(lease, season, 3, homePlayer);
        clock.set(pageRead.finishedAt());

        // A valid price-only bootstrap update supersedes this partial container run. Its committed
        // page remains fresh for the same checked fixture/population revision and must not be refetched.
        byte[] changedBootstrap = changeBootstrapPrice(checkedBootstrap, homePlayer, 77);
        Instant changedSourceStart = clock.instant().plusSeconds(10);
        SourceRead changedSourceRead = new SourceRead(changedSourceStart, changedSourceStart.plusMillis(3));
        var changedSnapshot = CatalogueScheduleParser.parse(season,
                document(changedBootstrap, new SourceRead(changedSourceStart, changedSourceStart.plusMillis(1))),
                document(sourceFixtures, new SourceRead(changedSourceStart.plusMillis(2), changedSourceRead.finishedAt())));
        var changedSource = GameweekFinality.SourceSnapshot.from(changedSnapshot, 3);
        assertThat(changedSource.signature()).isEqualTo(source.signature());
        clock.set(changedSourceRead.finishedAt());
        store.observeIncrementalSnapshot(lease, changedSnapshot);
        store.supersedeIncrementalCoreImport(lease);
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.run() + " WHERE generation=?",
                String.class, lease.generation())).isEqualTo("SUPERSEDED");

        PlannerRuntimeRepository runtime = new PlannerRuntimeRepository(jdbc, tx, clock, TABLES);
        runtime.deactivate(runtimeFence);
        runtimeFence = runtime.activate(season.code(), OwnedSyncMode.ACTIVE);
        store = new OwnedSyncStore(jdbc, tx, clock, TABLES, runtimeFence);
        publisher = new IncrementalPlayerFixturePublisher(
                new IncrementalPfsRepository(jdbc, clock, store, TABLES));
        finality = new GameweekFinalityRepository(jdbc, store, TABLES, clock);

        store.enqueueIncrementalCoreImport(season, clock.instant());
        Lease restartedLease = store.acquireIncrementalCoreImport(season.code(), "finality-test-restarted",
                Duration.ofMinutes(30)).orElseThrow();
        assertThat(restartedLease.generation()).isGreaterThan(lease.generation());
        assertThat(store.requestedSourceAfter(restartedLease)).isAfter(initialRead.startedAt());
        SourceRead restartRead = new SourceRead(clock.instant().plusMillis(1), clock.instant().plusMillis(3));
        var restartSnapshot = CatalogueScheduleParser.parse(season,
                document(changedBootstrap, new SourceRead(restartRead.startedAt(), restartRead.startedAt().plusMillis(1))),
                document(sourceFixtures, new SourceRead(restartRead.startedAt().plusMillis(2), restartRead.finishedAt())));
        clock.set(restartRead.finishedAt());
        store.observeIncrementalSnapshot(restartedLease, restartSnapshot);
        var resumed = finality.beginOrResume(restartedLease, season,
                dev.quantti.fplassistant.sync.GameweekFinality.SourceSnapshot.from(restartSnapshot, 3), population, restartRead);
        assertThat(resumed.checkpoint().completedPlayerIds()).containsExactly(homePlayer);
        assertThat(resumed.checkpoint().outstandingPlayerIds())
                .containsExactly(emptyCataloguePlayer, awayPlayer);
        assertThat(resumed.pageReadInterval()).isEqualTo(pageRead);
        assertThat(resumed.sourceReadInterval()).isEqualTo(new SourceRead(initialRead.startedAt(), restartRead.finishedAt()));
        assertThat(resumed.initialSourceReadInterval()).isEqualTo(initialRead);
        byte[] awayHistory = historyForAwayPlayer28(fixtureId);
        SourceRead awayPageRead = nextRead();
        publisher.publish(restartedLease, season, awayPlayer, document(awayHistory, awayPageRead), canonical);
        SourceRead emptyPageRead = nextRead();
        publisher.publish(restartedLease, season, emptyCataloguePlayer,
                document("{\"history\":[]}".getBytes(StandardCharsets.UTF_8), emptyPageRead), canonical);
        finality.recordCompletedPage(restartedLease, season, 3, awayPlayer);
        finality.recordCompletedPage(restartedLease, season, 3, emptyCataloguePlayer);
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.gameweekStatSync()
                + " WHERE season_id=? AND gameweek=3", String.class, SEASON_ID)).isEqualTo("FINAL_PENDING");

        SourceRead endRead = new SourceRead(emptyPageRead.finishedAt().plusMillis(1),
                emptyPageRead.finishedAt().plusMillis(3));
        var endSnapshot = CatalogueScheduleParser.parse(season,
                document(changedBootstrap, new SourceRead(endRead.startedAt(), endRead.startedAt().plusMillis(1))),
                document(sourceFixtures, new SourceRead(endRead.startedAt().plusMillis(2), endRead.finishedAt())));
        var endSource = dev.quantti.fplassistant.sync.GameweekFinality.SourceSnapshot.from(endSnapshot, 3);
        clock.set(endRead.finishedAt());
        var receipt = finality.finalizeSweep(restartedLease, season, endSource, population, endRead);
        assertThat(finality.load(restartedLease, season, 3).orElseThrow().finalizedSourceReadInterval())
                .isEqualTo(receipt.sourceReadInterval());
        assertThat(finality.load(restartedLease, season, 3).orElseThrow().initialSourceReadInterval())
                .isEqualTo(initialRead);
        assertThat(store.incrementalSourceInterval(restartedLease)).isEqualTo(receipt.sourceReadInterval());
        var afterRestart = finality.beginOrResume(restartedLease, season, endSource, population, endRead);
        assertThat(afterRestart.state()).isEqualTo(dev.quantti.fplassistant.sync.GameweekFinality.State.FINALIZED);
        assertThat(afterRestart.invalidationRevision()).isEqualTo(receipt.invalidationRevision());
        store.completeStaged(restartedLease, store.incrementalSourceInterval(restartedLease),
                List.of(restartRead, awayPageRead, emptyPageRead, endRead));

        var validated = reader.read(SEASON_ID, season.code(), Set.of(3)).get(3);
        assertThat(validated.state()).isEqualTo(GameweekFinality.State.FINALIZED);
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.run() + " WHERE generation=?",
                String.class, lease.generation())).isEqualTo("SUPERSEDED");
        assertThat(validated.receipt()).isEqualTo(receipt);
        assertThat(receipt.sourceSignature()).isEqualTo(source.signature());
        assertThat(receipt.rowRevisionFingerprint()).matches("[0-9a-f]{64}");
        assertThat(receipt.optionalCoverage()).hasSize(HistoryParser.Component.values().length);
        assertThat(receipt.sourceReadInterval())
                .isEqualTo(new SourceRead(initialRead.startedAt(), endRead.finishedAt()));

        Map<String, Object> homeRowBeforeNoop = jdbc.queryForMap("SELECT updated_at,minutes,total_points,bps FROM"
                + " player_fixture_stats WHERE season_id=? AND fixture_id=? AND player_id=?",
                SEASON_ID, fixtureId, homePlayer);
        Map<String, Object> homePointerBeforeNoop = jdbc.queryForMap("SELECT source_run_generation,row_fingerprint,"
                + "physical_fingerprint,source_read_started_at,source_read_finished_at,updated_at FROM "
                + TABLES.playerFixtureCurrent() + " WHERE season_id=? AND fixture_id=? AND player_id=?",
                SEASON_ID, fixtureId, homePlayer);
        int homeEvidenceBeforeNoop = jdbc.queryForObject("SELECT count(*) FROM "
                + TABLES.playerFixtureEvidence() + " WHERE season_id=? AND fixture_id=? AND player_id=?",
                Integer.class, SEASON_ID, fixtureId, homePlayer);
        Instant laterStart = endRead.finishedAt().plusMillis(10);
        clock.set(laterStart);
        store.enqueueIncrementalCoreImport(season, laterStart);
        Lease laterLease = store.acquireIncrementalCoreImport(season.code(), "later-final-page",
                Duration.ofMinutes(30)).orElseThrow();
        SourceRead laterPageRead = new SourceRead(laterStart.plusMillis(1), laterStart.plusMillis(2));
        clock.set(laterPageRead.finishedAt());
        var unchangedPage = publisher.publish(laterLease, season, homePlayer,
                document(homeHistory, laterPageRead), canonical);
        var unchangedEmptyPage = publisher.publish(laterLease, season, emptyCataloguePlayer,
                document("{\"history\":[]}".getBytes(StandardCharsets.UTF_8), laterPageRead), canonical);
        assertThat(unchangedPage.pfsRowsWritten()).isZero();
        assertThat(unchangedEmptyPage.pfsRowsWritten()).isZero();
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.run() + " WHERE generation=?",
                String.class, laterLease.generation())).isEqualTo("LEASED");
        var leasedPageRead = reader.read(SEASON_ID, season.code(), Set.of(3)).get(3);
        assertThat(leasedPageRead.state()).isEqualTo(GameweekFinality.State.FINALIZED);
        assertThat(leasedPageRead.receipt()).isEqualTo(receipt);

        // A different player's page fetch failure requeues the containing cycle as PENDING after
        // this page commits. The latest watermark must not depend on whole-cycle completion.
        store.retryFetchFailure(laterLease, laterStart.plus(Duration.ofHours(1)),
                "unrelated player page deferred");
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.run() + " WHERE generation=?",
                String.class, laterLease.generation())).isEqualTo("PENDING");
        var requeuedPageRead = reader.read(SEASON_ID, season.code(), Set.of(3)).get(3);
        assertThat(requeuedPageRead.state()).isEqualTo(GameweekFinality.State.FINALIZED);
        assertThat(requeuedPageRead.receipt()).isEqualTo(receipt);

        // A retry may later terminate unsuccessfully after the committed page. Its newer watermark
        // remains valid, but the original finality proof generation below must still be COMPLETED.
        clock.set(laterStart.plus(Duration.ofHours(1)));
        Lease failedLaterLease = store.acquireIncrementalCoreImport(season.code(), "later-final-page-retry",
                Duration.ofMinutes(30)).orElseThrow();
        store.fail(failedLaterLease, "unrelated page retry exhausted");
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.run() + " WHERE generation=?",
                String.class, laterLease.generation())).isEqualTo("FAILED");
        var failedLaterPageRead = reader.read(SEASON_ID, season.code(), Set.of(3)).get(3);
        assertThat(failedLaterPageRead.state()).isEqualTo(GameweekFinality.State.FINALIZED);
        assertThat(failedLaterPageRead.receipt()).isEqualTo(receipt);

        Instant supersededStart = clock.instant().plusMillis(1);
        clock.set(supersededStart);
        Lease supersededLease = nextCoreLease();
        SourceRead supersededPageRead = nextRead();
        var supersededPage = publisher.publish(supersededLease, season, homePlayer,
                document(homeHistory, supersededPageRead), canonical);
        assertThat(supersededPage.pfsRowsWritten()).isZero();
        store.supersedeIncrementalCoreImport(supersededLease);
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.run() + " WHERE generation=?",
                String.class, supersededLease.generation())).isEqualTo("SUPERSEDED");
        Lease observerLease = nextCoreLease();
        assertThat(finality.currentFinalizedGameweeks(observerLease, season, endSnapshot, Set.of(3)))
                .containsExactly(3);
        store.fail(observerLease, "finality reader assertion complete");
        var supersededWatermarkRead = reader.read(SEASON_ID, season.code(), Set.of(3)).get(3);
        assertThat(supersededWatermarkRead.state()).isEqualTo(GameweekFinality.State.FINALIZED);
        assertThat(supersededWatermarkRead.receipt()).isEqualTo(receipt);
        String validSupersedeReason = jdbc.queryForObject("SELECT supersede_reason FROM " + TABLES.run()
                + " WHERE generation=?", String.class, supersededLease.generation());
        assertThat(validSupersedeReason).isEqualTo(
                OwnedSyncStore.IncrementalSupersedeReason.VALIDATED_CANONICAL_SOURCE_PAIR.code());
        String reasonConstraint = "ck_" + TABLES.run() + "_supersede_reason";
        jdbc.execute("ALTER TABLE " + TABLES.run() + " DROP CONSTRAINT " + reasonConstraint);
        try {
            jdbc.update("UPDATE " + TABLES.run()
                    + " SET supersede_reason='UNSANCTIONED',last_error=NULL WHERE generation=?",
                    supersededLease.generation());
            assertThat(reader.read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                    .as("arbitrary superseded donors cannot validate the page watermark")
                    .isEqualTo(GameweekFinality.State.FINAL_PENDING);
            jdbc.update("UPDATE " + TABLES.run()
                    + " SET supersede_reason=NULL,last_error=? WHERE generation=?",
                    "INCREMENTAL_SUPERSEDED:VALIDATED_CANONICAL_SOURCE_PAIR", supersededLease.generation());
            assertThat(reader.read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                    .as("legacy error text alone is not a supersede proof")
                    .isEqualTo(GameweekFinality.State.FINAL_PENDING);
            jdbc.update("UPDATE " + TABLES.run() + " SET supersede_reason=?,last_error=NULL WHERE generation=?",
                    validSupersedeReason, supersededLease.generation());
            assertThat(reader.read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                    .isEqualTo(GameweekFinality.State.FINALIZED);
        } finally {
            jdbc.update("UPDATE " + TABLES.run() + " SET supersede_reason=?,last_error=NULL WHERE generation=?",
                    validSupersedeReason, supersededLease.generation());
            new OwnedSyncSchema(jdbc, TABLES).initialize();
        }
        assertThat(jdbc.queryForMap("SELECT updated_at,minutes,total_points,bps FROM player_fixture_stats"
                + " WHERE season_id=? AND fixture_id=? AND player_id=?", SEASON_ID, fixtureId, homePlayer))
                .isEqualTo(homeRowBeforeNoop);
        assertThat(jdbc.queryForMap("SELECT source_run_generation,row_fingerprint,physical_fingerprint,"
                + "source_read_started_at,source_read_finished_at,updated_at FROM " + TABLES.playerFixtureCurrent()
                + " WHERE season_id=? AND fixture_id=? AND player_id=?", SEASON_ID, fixtureId, homePlayer))
                .isEqualTo(homePointerBeforeNoop);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.playerFixtureEvidence()
                + " WHERE season_id=? AND fixture_id=? AND player_id=?", Integer.class,
                SEASON_ID, fixtureId, homePlayer)).isEqualTo(homeEvidenceBeforeNoop);

        java.sql.Timestamp donorFloor = jdbc.queryForObject("SELECT requested_source_after FROM " + TABLES.run()
                + " WHERE generation=?", java.sql.Timestamp.class, lease.generation());
        jdbc.update("UPDATE " + TABLES.run() + " SET requested_source_after=? WHERE generation=?",
                java.sql.Timestamp.from(pageRead.finishedAt().plusMillis(1)), lease.generation());
        assertThat(reader.read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                .isEqualTo(GameweekFinality.State.FINAL_PENDING);
        jdbc.update("UPDATE " + TABLES.run() + " SET requested_source_after=? WHERE generation=?",
                donorFloor, lease.generation());

        java.sql.Timestamp donorStarted = jdbc.queryForObject("SELECT source_read_started_at FROM " + TABLES.run()
                + " WHERE generation=?", java.sql.Timestamp.class, lease.generation());
        java.sql.Timestamp donorFinished = jdbc.queryForObject("SELECT source_read_finished_at FROM " + TABLES.run()
                + " WHERE generation=?", java.sql.Timestamp.class, lease.generation());
        jdbc.update("UPDATE " + TABLES.run() + " SET source_read_started_at=NULL,source_read_finished_at=NULL"
                + " WHERE generation=?", lease.generation());
        assertThat(reader.read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                .isEqualTo(GameweekFinality.State.FINAL_PENDING);
        jdbc.update("UPDATE " + TABLES.run() + " SET source_read_started_at=?,source_read_finished_at=?"
                + " WHERE generation=?", donorStarted, donorFinished, lease.generation());

        String pageEvidence = jdbc.queryForObject("SELECT finalized_page_evidence::text FROM "
                + TABLES.gameweekStatSync() + " WHERE season_id=? AND gameweek=?", String.class, SEASON_ID, 3);
        ObjectNode missingDonorEvidence = (ObjectNode) JSON.readTree(pageEvidence);
        ((ObjectNode) missingDonorEvidence.path(Integer.toString(homePlayer))).put("generation", 999999999L);
        jdbc.update("UPDATE " + TABLES.gameweekStatSync() + " SET finalized_page_evidence=?::jsonb"
                + " WHERE season_id=? AND gameweek=?", JSON.writeValueAsString(missingDonorEvidence), SEASON_ID, 3);
        assertThat(reader.read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                .isEqualTo(GameweekFinality.State.FINAL_PENDING);
        jdbc.update("UPDATE " + TABLES.gameweekStatSync() + " SET finalized_page_evidence=?::jsonb"
                + " WHERE season_id=? AND gameweek=?", pageEvidence, SEASON_ID, 3);

        Long advancedHomeWatermark = jdbc.queryForObject("SELECT latest_generation FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", Long.class, season.code(),
                IncrementalPlayerFixturePublisher.PAGE_RECEIPT_PREFIX + homePlayer);
        assertThat(advancedHomeWatermark).isGreaterThan(lease.generation());
        assertThat(pageRead.finishedAt()).isBefore(receipt.sourceReadInterval().finishedAt());
        Map<String, Object> originalDonorRun = jdbc.queryForMap("SELECT state,source_read_finished_at,completed_at,"
                + "failed_at,last_error,supersede_reason FROM " + TABLES.run() + " WHERE generation=?",
                lease.generation());
        jdbc.update("UPDATE " + TABLES.run() + " SET state='PENDING',source_read_finished_at=?,"
                + "completed_at=NULL,failed_at=NULL,last_error=NULL,supersede_reason=NULL,lease_owner=NULL,lease_token=NULL,"
                + "lease_expires_at=NULL WHERE generation=?",
                java.sql.Timestamp.from(clock.instant().plusSeconds(30)), lease.generation());
        assertThat(reader.read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                .as("a future source end on the original PENDING donor invalidates the page proof"
                        + " even after a later page watermark")
                .isEqualTo(GameweekFinality.State.FINAL_PENDING);
        jdbc.update("UPDATE " + TABLES.run() + " SET state=?,source_read_finished_at=?,completed_at=?,"
                + "failed_at=?,last_error=?,supersede_reason=? WHERE generation=?", originalDonorRun.get("state"),
                originalDonorRun.get("source_read_finished_at"), originalDonorRun.get("completed_at"),
                originalDonorRun.get("failed_at"), originalDonorRun.get("last_error"),
                originalDonorRun.get("supersede_reason"), lease.generation());
        assertThat(reader.read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                .isEqualTo(GameweekFinality.State.FINALIZED);

        String runtimeId = "mode:" + season.code();
        jdbc.update("UPDATE " + TABLES.plannerRuntime()
                + " SET runtime_mode='OFF',runtime_epoch=runtime_epoch+1 WHERE instance_id=?", runtimeId);
        var afterOff = reader.read(SEASON_ID, season.code(), Set.of(3)).get(3);
        assertThat(afterOff.state()).isEqualTo(GameweekFinality.State.FINALIZED);
        assertThat(afterOff.receipt()).isEqualTo(receipt);
        jdbc.update("UPDATE " + TABLES.plannerRuntime()
                + " SET runtime_mode='ACTIVE',runtime_epoch=runtime_epoch+1 WHERE instance_id=?", runtimeId);
        var afterReactivation = reader.read(SEASON_ID, season.code(), Set.of(3)).get(3);
        assertThat(afterReactivation.state()).isEqualTo(GameweekFinality.State.FINALIZED);
        assertThat(afterReactivation.receipt()).isEqualTo(receipt);

        java.sql.Timestamp requestedAfter = jdbc.queryForObject("SELECT requested_source_after FROM "
                + TABLES.run() + " WHERE generation=?", java.sql.Timestamp.class, receipt.runGeneration());
        jdbc.update("UPDATE " + TABLES.run() + " SET requested_source_after=? WHERE generation=?",
                java.sql.Timestamp.from(laterPageRead.finishedAt().plusSeconds(1)), receipt.runGeneration());
        assertThat(reader.read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                .isEqualTo(GameweekFinality.State.FINAL_PENDING);
        jdbc.update("UPDATE " + TABLES.run() + " SET requested_source_after=? WHERE generation=?",
                requestedAfter, receipt.runGeneration());

        jdbc.update("UPDATE " + TABLES.run() + " SET source_read_started_at=NULL WHERE generation=?",
                receipt.runGeneration());
        assertThat(reader.read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                .isEqualTo(GameweekFinality.State.FINAL_PENDING);
        jdbc.update("UPDATE " + TABLES.run() + " SET source_read_started_at=?,source_read_finished_at=?"
                + " WHERE generation=?", java.sql.Timestamp.from(receipt.sourceReadInterval().startedAt()),
                java.sql.Timestamp.from(receipt.sourceReadInterval().finishedAt()), receipt.runGeneration());

        jdbc.update("UPDATE " + TABLES.run() + " SET state='FAILED' WHERE generation=?", receipt.runGeneration());
        assertThat(reader.read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                .isEqualTo(GameweekFinality.State.FINAL_PENDING);
        jdbc.update("UPDATE " + TABLES.run() + " SET state='COMPLETED' WHERE generation=?", receipt.runGeneration());

        String pageKey = IncrementalPlayerFixturePublisher.pageReceiptKey(emptyCataloguePlayer);
        jdbc.update("UPDATE " + TABLES.receipt() + " SET source_read_started_at=?,source_read_finished_at=?"
                + " WHERE season=? AND publication_key=?", java.sql.Timestamp.from(pageRead.startedAt()),
                java.sql.Timestamp.from(pageRead.finishedAt()), season.code(), pageKey);
        assertThat(reader.read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                .isEqualTo(GameweekFinality.State.FINAL_PENDING);
        jdbc.update("UPDATE " + TABLES.receipt() + " SET source_read_started_at=?,source_read_finished_at=?"
                + " WHERE season=? AND publication_key=?", java.sql.Timestamp.from(laterPageRead.startedAt()),
                java.sql.Timestamp.from(laterPageRead.finishedAt()), season.code(), pageKey);

        jdbc.update("UPDATE " + TABLES.gameweekStatSync()
                + " SET final_attempt_required_player_ids=ARRAY[?,?],final_attempt_completed_player_ids=ARRAY[?,?]"
                + " WHERE season_id=? AND gameweek=?",
                homePlayer, awayPlayer, homePlayer, awayPlayer, SEASON_ID, 3);
        assertThat(reader.read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                .isEqualTo(GameweekFinality.State.FINAL_PENDING);
        jdbc.update("UPDATE " + TABLES.gameweekStatSync()
                + " SET final_attempt_required_player_ids=ARRAY[?,?,?],"
                + " final_attempt_completed_player_ids=ARRAY[?,?,?] WHERE season_id=? AND gameweek=?",
                homePlayer, emptyCataloguePlayer, awayPlayer, homePlayer, emptyCataloguePlayer, awayPlayer,
                SEASON_ID, 3);

        assertThat(jdbc.update("UPDATE player_fixture_stats SET minutes=minutes+1"
                + " WHERE season_id=? AND fixture_id=? AND player_id=?", SEASON_ID, fixtureId,
                homePlayer)).isEqualTo(1);
        var tamperedReader = reader.read(SEASON_ID, season.code(), Set.of(3)).get(3);
        assertThat(tamperedReader.state()).isEqualTo(GameweekFinality.State.FINAL_PENDING);
        assertThat(tamperedReader.receipt()).isNull();

        jdbc.update("UPDATE fixture SET team_h_score=COALESCE(team_h_score,0)+1"
                + " WHERE season_id=? AND id=?", SEASON_ID, fixtureId);
        var correctedSource = reader.read(SEASON_ID, season.code(), Set.of(3)).get(3);
        assertThat(correctedSource.state()).isEqualTo(GameweekFinality.State.FINAL_PENDING);
        assertThat(correctedSource.receipt()).isNull();
    }

    @Test
    void currentSourceMembershipDropsRetainedCatalogueOnlyPlayerAndPreservesFinalityOnNoop() throws Exception {
        int fixtureId = 29;
        int catalogueOnlyPlayer = 2;
        byte[] initialBootstrap = markGameweekChecked(bootstrapPlayers(bootstrap, Set.of(1, catalogueOnlyPlayer)), 3);
        byte[] fixtureSource = fixtureStatsForPlayers(onlyCollectorFixtureFinished(fixtures, fixtureId), fixtureId, 1, 28);
        byte[] liveSource = liveForFixturePlayers(eventThreeLive, fixtureId, Set.of(1, 28));
        fetch.setSources(initialBootstrap, fixtureSource, liveSource, eventFourLive);
        fetch.setPlayerPage(1, onlyHistoryForFixture(resource("element-summary-1.json"), fixtureId, 1));
        fetch.setPlayerPage(28, historyForAwayPlayer28(fixtureId));
        fetch.setPlayerPage(catalogueOnlyPlayer, RecordedFetch.EMPTY_HISTORY);

        CycleResult initial = compatibilityCollector(true).runCycle();

        assertThat(initial.state()).as("cycle: %s; error: %s", initial, latestRunError()).isEqualTo(State.COMPLETED);
        assertThat(fetch.playerIds()).containsExactly(1, catalogueOnlyPlayer, 28);
        GameweekFinalityReader reader = new GameweekFinalityReader(jdbc, clock, TABLES);
        assertThat(reader.read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                .isEqualTo(GameweekFinality.State.FINALIZED);
        String initialManifest = jdbc.queryForObject("SELECT final_attempt_population_manifest::text FROM "
                + TABLES.gameweekStatSync() + " WHERE season_id=? AND gameweek=3", String.class, SEASON_ID);
        assertThat(JSON.readTree(initialManifest).path("sourceCatalogueMembershipRequired").asBoolean())
                .isTrue();

        jdbc.update("""
                INSERT INTO player_fixture_stats (fixture_id,player_id,season_id,gameweek,
                        player_team_id,opponent_team_id,was_home,kickoff_time,minutes,total_points,value)
                VALUES (20,2,27,2,1,2,FALSE,TIMESTAMPTZ '2026-08-31 19:00:00+00',90,2,50)
                """);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM player_fixture_stats"
                + " WHERE season_id=? AND fixture_id=20 AND player_id=2", Integer.class, SEASON_ID)).isOne();
        String publishedMembership = jdbc.queryForObject("SELECT source_catalogue::text FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", String.class, season.code(),
                OwnedSyncStore.INCREMENTAL_COMPATIBILITY_PUBLICATION_KEY);
        assertThat(JSON.readTree(publishedMembership).path("players").toString()).contains("[2,1]");

        Long membershipGeneration = jdbc.queryForObject("SELECT latest_generation FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", Long.class, season.code(),
                OwnedSyncStore.INCREMENTAL_COMPATIBILITY_PUBLICATION_KEY);
        assertThat(membershipGeneration).isPositive();
        jdbc.update("UPDATE " + TABLES.receipt() + " SET source_catalogue=NULL"
                + " WHERE season=? AND publication_key=?", season.code(),
                OwnedSyncStore.INCREMENTAL_COMPATIBILITY_PUBLICATION_KEY);
        assertThat(reader.read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                .as("incremental finality cannot fall back to retained player rows without its proof")
                .isEqualTo(GameweekFinality.State.FINAL_PENDING);

        ObjectNode detachedGeneration = (ObjectNode) JSON.readTree(publishedMembership);
        detachedGeneration.put("sourceGeneration", 0);
        jdbc.update("UPDATE " + TABLES.receipt() + " SET source_catalogue=?::jsonb"
                + " WHERE season=? AND publication_key=?", publishedMembership, season.code(),
                OwnedSyncStore.INCREMENTAL_COMPATIBILITY_PUBLICATION_KEY);
        jdbc.update("UPDATE " + TABLES.receipt() + " SET source_catalogue=?::jsonb"
                + " WHERE season=? AND publication_key=?", JSON.writeValueAsString(detachedGeneration), season.code(),
                OwnedSyncStore.INCREMENTAL_COMPATIBILITY_PUBLICATION_KEY);
        assertThat(jdbc.queryForObject("SELECT source_catalogue IS NOT NULL AND"
                + " (source_catalogue->>'sourceGeneration')::bigint<>latest_generation FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", Boolean.class, season.code(),
                OwnedSyncStore.INCREMENTAL_COMPATIBILITY_PUBLICATION_KEY)).isTrue();
        assertThat(reader.read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                .as("a present membership detached from the coalesced source generation is rejected")
                .isEqualTo(GameweekFinality.State.FINAL_PENDING);

        ObjectNode wrongSeason = (ObjectNode) JSON.readTree(publishedMembership);
        wrongSeason.put("seasonCode", "detached-season");
        jdbc.update("UPDATE " + TABLES.receipt() + " SET source_catalogue=?::jsonb"
                + " WHERE season=? AND publication_key=?", publishedMembership, season.code(),
                OwnedSyncStore.INCREMENTAL_COMPATIBILITY_PUBLICATION_KEY);
        jdbc.update("UPDATE " + TABLES.receipt() + " SET source_catalogue=?::jsonb"
                + " WHERE season=? AND publication_key=?", JSON.writeValueAsString(wrongSeason), season.code(),
                OwnedSyncStore.INCREMENTAL_COMPATIBILITY_PUBLICATION_KEY);
        assertThat(reader.read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                .as("membership source season code must match the playing season")
                .isEqualTo(GameweekFinality.State.FINAL_PENDING);

        jdbc.update("UPDATE " + TABLES.receipt() + " SET source_catalogue=?::jsonb"
                + " WHERE season=? AND publication_key=?", publishedMembership, season.code(),
                OwnedSyncStore.INCREMENTAL_COMPATIBILITY_PUBLICATION_KEY);
        jdbc.update("UPDATE " + TABLES.run() + " SET producer_key='LEGACY' WHERE generation=?",
                membershipGeneration);
        assertThat(reader.read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                .as("catalogue proof must be published by the coalesced source producer")
                .isEqualTo(GameweekFinality.State.FINAL_PENDING);
        jdbc.update("UPDATE " + TABLES.run() + " SET producer_key=? WHERE generation=?",
                OwnedSyncStore.INCREMENTAL_CORE_PRODUCER, membershipGeneration);
        jdbc.update("UPDATE " + TABLES.receipt() + " SET source_catalogue=?::jsonb"
                + " WHERE season=? AND publication_key=?", publishedMembership, season.code(),
                OwnedSyncStore.INCREMENTAL_COMPATIBILITY_PUBLICATION_KEY);
        assertThat(reader.read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                .isEqualTo(GameweekFinality.State.FINALIZED);

        byte[] revisedBootstrap = markGameweekChecked(bootstrapPlayers(bootstrap, Set.of(29)), 3);
        fetch.setSources(revisedBootstrap, fixtureSource, liveSource, eventFourLive);
        fetch.clearCalls();
        fetch.failPlayer(28);
        clock.set(clock.instant().plusSeconds(10));
        CycleResult partial = compatibilityCollector(true).runCycle();

        assertThat(partial.state()).isEqualTo(State.RETRY_SCHEDULED);
        assertThat(fetch.playerIds()).containsExactly(1, 28);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM player WHERE season_id=? AND id IN (1,2)",
                Integer.class, SEASON_ID)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM player_fixture_stats"
                + " WHERE season_id=? AND fixture_id=20 AND player_id=2", Integer.class, SEASON_ID)).isOne();
        String revisedMembership = jdbc.queryForObject("SELECT source_catalogue::text FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", String.class, season.code(),
                OwnedSyncStore.INCREMENTAL_COMPATIBILITY_PUBLICATION_KEY);
        assertThat(JSON.readTree(revisedMembership).path("players").toString()).isEqualTo("[[29,2]]");
        assertThat(reader.read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                .isEqualTo(GameweekFinality.State.FINAL_PENDING);
        String manifest = jdbc.queryForObject("SELECT final_attempt_population_manifest::text FROM "
                + TABLES.gameweekStatSync() + " WHERE season_id=? AND gameweek=3", String.class, SEASON_ID);
        JsonNode revisedPopulation = JSON.readTree(manifest);
        JsonNode catalogue = revisedPopulation.path("catalogue").path(Integer.toString(fixtureId));
        assertThat(catalogue.toString()).isEqualTo("[]");
        assertThat(revisedPopulation.path("known").path(Integer.toString(fixtureId)).toString())
                .as("a retained PFS FK parent in another fixture is not an actual known identity here")
                .isEqualTo("[]");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM player_fixture_stats"
                + " WHERE season_id=? AND fixture_id=? AND player_id=2", Integer.class, SEASON_ID, fixtureId))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT final_attempt_required_player_ids::text FROM "
                + TABLES.gameweekStatSync() + " WHERE season_id=? AND gameweek=3", String.class, SEASON_ID))
                .isEqualTo("{1,28}");
        assertThat(jdbc.queryForObject("SELECT final_attempt_outstanding_player_ids::text FROM "
                + TABLES.gameweekStatSync() + " WHERE season_id=? AND gameweek=3", String.class, SEASON_ID))
                .isEqualTo("{28}");

        fetch.clearCalls();
        clock.set(partial.nextDue().plusSeconds(1));
        CycleResult finalized = compatibilityCollector(true).runCycle();

        assertThat(finalized.state()).as("cycle: %s; error: %s", finalized, latestRunError())
                .isEqualTo(State.COMPLETED);
        assertThat(fetch.playerIds()).containsExactly(28);
        assertThat(reader.read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                .isEqualTo(GameweekFinality.State.FINALIZED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM player_fixture_stats"
                + " WHERE season_id=? AND fixture_id=20 AND player_id=2", Integer.class, SEASON_ID)).isOne();

        fetch.clearCalls();
        clock.set(clock.instant().plusSeconds(10));
        CycleResult noop = compatibilityCollector(true).runCycle();
        assertThat(noop.state()).isEqualTo(State.COMPLETED);
        assertThat(fetch.playerIds()).isEmpty();
        assertThat(reader.read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                .isEqualTo(GameweekFinality.State.FINALIZED);

        SqlRecordingDataSource recording = new SqlRecordingDataSource(dataSource);
        JdbcTemplate snapshotJdbc = new JdbcTemplate(recording);
        TransactionTemplate snapshotTx = new TransactionTemplate(new DataSourceTransactionManager(recording));
        snapshotTx.setReadOnly(true);
        snapshotTx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        GameweekFinalityReader snapshotReader = new GameweekFinalityReader(snapshotJdbc, clock, TABLES);
        snapshotTx.execute(status -> snapshotReader.read(SEASON_ID, season.code(), Set.of(3)));
        assertThat(recording.statements().stream().filter(sql -> sql.contains("source_catalogue::text")))
                .hasSize(1);
        assertThat(recording.statements().stream().map(sql -> sql.toLowerCase(java.util.Locale.ROOT)))
                .noneMatch(sql -> sql.contains("from player where season_id=? and team_id in"));

        new PlannerRuntimeRepository(jdbc, tx, clock, TABLES).deactivate(runtimeFence);
        runtimeFence = null;
        assertThat(reader.read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                .as("archived source proof remains valid after OFF without a current-mode gate")
                .isEqualTo(GameweekFinality.State.FINALIZED);
    }

    @Test
    void collectorResumesPriceOnlySupersededSweepAndPreservesFinalityOnRealNoop() throws Exception {
        CheckedFixtureSetup setup = checkedSingleFixtureSetup();
        fetch.failPlayer(28);
        CoalescedPlayerHistoryCollector collector = compatibilityCollector(true);

        CycleResult partial = collector.runCycle();

        assertThat(partial.state()).isEqualTo(State.RETRY_SCHEDULED);
        assertThat(partial.finalPendingGameweeks()).contains(3);
        assertThat(partial.committedPlayerIds()).containsExactly(setup.cataloguePlayer());
        assertThat(partial.failedPlayerIds()).containsExactly(28);
        assertThat(fetch.playerIds()).containsExactly(setup.cataloguePlayer(), 28);
        long originalGeneration = jdbc.queryForObject("SELECT latest_generation FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", Long.class, season.code(),
                OwnedSyncStore.INCREMENTAL_COMPATIBILITY_PUBLICATION_KEY);
        assertThat(new GameweekFinalityReader(jdbc, clock, TABLES)
                .read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                .isEqualTo(GameweekFinality.State.FINAL_PENDING);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM player_fixture_stats WHERE season_id=?"
                + " AND fixture_id=? AND player_id=?", Integer.class,
                SEASON_ID, setup.fixtureId(), setup.cataloguePlayer())).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM player_fixture_stats WHERE season_id=?"
                + " AND fixture_id=? AND player_id=28", Integer.class, SEASON_ID, setup.fixtureId())).isZero();

        byte[] changedBootstrap = changeBootstrapPrice(setup.bootstrapSource(), setup.cataloguePlayer(), 77);
        var originalSnapshot = CatalogueScheduleParser.parse(season,
                document(setup.bootstrapSource(), new SourceRead(START, START.plusMillis(1))),
                document(setup.fixtureSource(), new SourceRead(START.plusMillis(2), START.plusMillis(3))));
        var changedSnapshot = CatalogueScheduleParser.parse(season,
                document(changedBootstrap, new SourceRead(START, START.plusMillis(1))),
                document(setup.fixtureSource(), new SourceRead(START.plusMillis(2), START.plusMillis(3))));
        var originalSource = GameweekFinality.SourceSnapshot.from(originalSnapshot, 3);
        var changedSource = GameweekFinality.SourceSnapshot.from(changedSnapshot, 3);
        assertThat(changedBootstrap).isNotEqualTo(setup.bootstrapSource());
        assertThat(changedSource.signature()).isEqualTo(originalSource.signature());
        assertThat(GameweekFinality.catalogueFingerprint(changedSnapshot, changedSource.assignedFixtures()))
                .isEqualTo(GameweekFinality.catalogueFingerprint(originalSnapshot, originalSource.assignedFixtures()));

        fetch.setSources(changedBootstrap, setup.fixtureSource(), setup.liveSource(), eventFourLive);
        fetch.clearCalls();
        clock.set(partial.nextDue().plusSeconds(1));
        CycleResult superseded = collector.runCycle();

        assertThat(superseded.state()).isEqualTo(State.SUPERSEDED);
        assertThat(fetch.count(EndpointKind.BOOTSTRAP_STATIC)).isOne();
        assertThat(fetch.count(EndpointKind.FIXTURES)).isOne();
        assertThat(fetch.count(EndpointKind.ELEMENT_SUMMARY)).isZero();
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.run() + " WHERE generation=?",
                String.class, originalGeneration)).isEqualTo("SUPERSEDED");
        assertThat(new GameweekFinalityReader(jdbc, clock, TABLES)
                .read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                .isEqualTo(GameweekFinality.State.FINAL_PENDING);

        fetch.clearCalls();
        fetch.clearPageOutcomes();
        clock.set(superseded.nextDue().plusSeconds(1));
        CycleResult completed = collector.runCycle();

        assertThat(completed.state()).as("cycle: %s; last error: %s", completed, latestRunError())
                .isEqualTo(State.COMPLETED);
        assertThat(completed.committedPlayerIds()).containsExactly(28);
        assertThat(fetch.playerIds()).containsExactly(28);
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.run()
                + " WHERE generation=(SELECT latest_generation FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?)", String.class, season.code(),
                OwnedSyncStore.INCREMENTAL_COMPATIBILITY_PUBLICATION_KEY)).isEqualTo("COMPLETED");
        var finalizedReader = new GameweekFinalityReader(jdbc, clock, TABLES);
        assertThat(finalizedReader.read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                .isEqualTo(GameweekFinality.State.FINALIZED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.receipt() + " participant JOIN "
                + TABLES.receipt() + " live ON live.season=participant.season WHERE participant.season=?"
                + " AND participant.publication_key IN (?,?) AND live.publication_key=?"
                + " AND participant.source_read_started_at=live.source_read_started_at"
                + " AND participant.source_read_finished_at=live.source_read_finished_at", Integer.class,
                season.code(), OwnedSyncStore.INCREMENTAL_PARTICIPANT_RECEIPT_PREFIX + setup.fixtureId()
                        + ":player:" + setup.cataloguePlayer(),
                OwnedSyncStore.INCREMENTAL_PARTICIPANT_RECEIPT_PREFIX + setup.fixtureId() + ":player:28",
                OwnedSyncStore.INCREMENTAL_LIVE_EVENT_RECEIPT_PREFIX + 3)).isEqualTo(2);

        Map<String, Object> compatibilityPair = jdbc.queryForMap("SELECT latest_generation,"
                + "source_bootstrap_payload_id,source_fixtures_payload_id,source_pair_fingerprint,"
                + "source_bootstrap_read_started_at,source_bootstrap_read_finished_at,"
                + "source_fixtures_read_started_at,source_fixtures_read_finished_at,"
                + "source_read_started_at,source_read_finished_at FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", season.code(),
                OwnedSyncStore.INCREMENTAL_COMPATIBILITY_PUBLICATION_KEY);
        long finalGeneration = ((Number) compatibilityPair.get("latest_generation")).longValue();
        assertThat(finalGeneration).isGreaterThan(originalGeneration);
        long bootstrapPayload = ((Number) compatibilityPair.get("source_bootstrap_payload_id")).longValue();
        long fixturesPayload = ((Number) compatibilityPair.get("source_fixtures_payload_id")).longValue();
        assertThat(jdbc.queryForObject("SELECT body FROM " + TABLES.payload() + " WHERE id=?",
                byte[].class, bootstrapPayload)).containsExactly(changedBootstrap);
        assertThat(jdbc.queryForObject("SELECT body FROM " + TABLES.payload() + " WHERE id=?",
                byte[].class, fixturesPayload)).containsExactly(setup.fixtureSource());
        SourceRead fetchedBootstrap = fetch.successfulReads().stream()
                .filter(read -> read.endpoint().kind() == EndpointKind.BOOTSTRAP_STATIC)
                .findFirst().orElseThrow().read();
        SourceRead fetchedFixtures = fetch.successfulReads().stream()
                .filter(read -> read.endpoint().kind() == EndpointKind.FIXTURES)
                .findFirst().orElseThrow().read();
        SourceRead publishedBootstrap = jdbc.queryForObject("SELECT read_started_at,read_finished_at FROM "
                + TABLES.payload() + " WHERE id=?", (rs, row) -> new SourceRead(
                        rs.getTimestamp(1).toInstant(), rs.getTimestamp(2).toInstant()), bootstrapPayload);
        SourceRead publishedFixtures = jdbc.queryForObject("SELECT read_started_at,read_finished_at FROM "
                + TABLES.payload() + " WHERE id=?", (rs, row) -> new SourceRead(
                        rs.getTimestamp(1).toInstant(), rs.getTimestamp(2).toInstant()), fixturesPayload);
        assertThat(publishedBootstrap).isEqualTo(new SourceRead(
                OwnedSyncStore.postgresTimestampPrecision(fetchedBootstrap.startedAt()),
                OwnedSyncStore.postgresTimestampPrecision(fetchedBootstrap.finishedAt())));
        assertThat(publishedFixtures).isEqualTo(new SourceRead(
                OwnedSyncStore.postgresTimestampPrecision(fetchedFixtures.startedAt()),
                OwnedSyncStore.postgresTimestampPrecision(fetchedFixtures.finishedAt())));
        assertThat(compatibilityPair.get("source_bootstrap_read_started_at"))
                .isEqualTo(java.sql.Timestamp.from(publishedBootstrap.startedAt()));
        assertThat(compatibilityPair.get("source_bootstrap_read_finished_at"))
                .isEqualTo(java.sql.Timestamp.from(publishedBootstrap.finishedAt()));
        assertThat(compatibilityPair.get("source_fixtures_read_started_at"))
                .isEqualTo(java.sql.Timestamp.from(publishedFixtures.startedAt()));
        assertThat(compatibilityPair.get("source_fixtures_read_finished_at"))
                .isEqualTo(java.sql.Timestamp.from(publishedFixtures.finishedAt()));
        assertThat(compatibilityPair.get("source_read_started_at"))
                .isEqualTo(java.sql.Timestamp.from(publishedBootstrap.startedAt()));
        assertThat(compatibilityPair.get("source_read_finished_at"))
                .isEqualTo(java.sql.Timestamp.from(publishedFixtures.finishedAt()));
        var authoritativePair = OwnedSyncStore.CanonicalSourcePair.of(
                new OwnedSyncStore.CanonicalSourcePayload(bootstrapPayload, finalGeneration,
                        EndpointKind.BOOTSTRAP_STATIC, publishedBootstrap, OwnedSyncStore.sha256(changedBootstrap)),
                new OwnedSyncStore.CanonicalSourcePayload(fixturesPayload, finalGeneration,
                        EndpointKind.FIXTURES, publishedFixtures, OwnedSyncStore.sha256(setup.fixtureSource())));
        assertThat(compatibilityPair.get("source_pair_fingerprint")).isEqualTo(authoritativePair.fingerprint());

        Map<String, List<Object>> pfsBeforeNoop = pfsRowsSnapshot(Set.of(setup.fixtureId()));
        Map<String, List<Object>> proofBeforeNoop = pfsProofSnapshot(Set.of(setup.fixtureId()));
        Map<String, Object> finalityBeforeNoop = finalitySnapshot();
        fetch.clearCalls();
        clock.set(clock.instant().plusSeconds(10));
        CycleResult noop = collector.runCycle();

        assertThat(noop.state()).isEqualTo(State.COMPLETED);
        assertThat(noop.selectedPlayerIds()).isEmpty();
        assertThat(fetch.count(EndpointKind.BOOTSTRAP_STATIC)).isOne();
        assertThat(fetch.count(EndpointKind.FIXTURES)).isOne();
        assertThat(fetch.playerIds()).isEmpty();
        assertThat(fetch.count(EndpointKind.EVENT_LIVE)).isZero();
        assertThat(finalizedReader.read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                .isEqualTo(GameweekFinality.State.FINALIZED);
        assertThat(pfsRowsSnapshot(Set.of(setup.fixtureId()))).isEqualTo(pfsBeforeNoop);
        assertThat(pfsProofSnapshot(Set.of(setup.fixtureId()))).isEqualTo(proofBeforeNoop);
        assertThat(finalitySnapshot()).isEqualTo(finalityBeforeNoop);
    }

    @Test
    void checkedStartupFinalSweepFinalizesRealRowsAndNextCycleDoesNotRewriteThem() throws Exception {
        CheckedFixtureSetup setup = checkedSingleFixtureSetup();
        SqlRecordingDataSource recording = useRecordingJdbc();
        try {
            installUnit6WriteCounters();
            CoalescedPlayerHistoryCollector collector = collector(true);
            long cycleStarted = System.nanoTime();
            CycleResult first = collector.runCycle();
            long cycleMillis = Duration.ofNanos(System.nanoTime() - cycleStarted).toMillis();

            assertThat(first.state()).as("cycle: %s; last error: %s", first, latestRunError())
                    .isEqualTo(State.COMPLETED);
            assertThat(first.selectedPlayerIds()).containsExactly(setup.cataloguePlayer(), 28);
            assertThat(first.committedPlayerIds()).containsExactly(setup.cataloguePlayer(), 28);
            assertThat(jdbc.queryForObject("SELECT participant.source_read_started_at=live.source_read_started_at"
                    + " AND participant.source_read_finished_at=live.source_read_finished_at FROM "
                    + TABLES.receipt() + " participant JOIN " + TABLES.receipt()
                    + " live ON live.season=participant.season WHERE participant.season=?"
                    + " AND participant.publication_key=? AND live.publication_key=?", Boolean.class,
                    season.code(), OwnedSyncStore.INCREMENTAL_PARTICIPANT_RECEIPT_PREFIX
                            + setup.fixtureId() + ":player:28",
                    OwnedSyncStore.INCREMENTAL_LIVE_EVENT_RECEIPT_PREFIX + 3)).isTrue();
            assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.gameweekStatSync()
                    + " WHERE season_id=? AND gameweek=3", String.class, SEASON_ID)).isEqualTo("FINALIZED");
            assertThat(jdbc.queryForList("SELECT player_id FROM player_fixture_stats"
                    + " WHERE season_id=? AND fixture_id=? ORDER BY player_id", Integer.class,
                    SEASON_ID, setup.fixtureId())).containsExactly(1, 28);
            java.sql.Timestamp rowUpdatedAt = jdbc.queryForObject("SELECT updated_at FROM player_fixture_stats"
                    + " WHERE season_id=? AND fixture_id=? AND player_id=?", java.sql.Timestamp.class,
                    SEASON_ID, setup.fixtureId(), 28);
            int evidenceRows = jdbc.queryForObject("SELECT count(*) FROM " + TABLES.playerFixtureEvidence()
                    + " WHERE season_id=? AND fixture_id=? AND player_id=?", Integer.class,
                    SEASON_ID, setup.fixtureId(), 28);
            Instant firstCanonicalStart = fetch.successfulReads().stream()
                    .filter(read -> read.endpoint().kind() == EndpointKind.BOOTSTRAP_STATIC)
                    .map(read -> read.read().startedAt()).findFirst().orElseThrow();
            Instant terminalFinish = fetch.successfulReads().getLast().read().finishedAt();
            var receipt = jdbc.queryForObject("SELECT finalized_source_read_started_at,"
                    + " finalized_source_read_finished_at FROM " + TABLES.gameweekStatSync()
                    + " WHERE season_id=? AND gameweek=3", (rs, row) -> new SourceRead(
                            rs.getTimestamp(1).toInstant(), rs.getTimestamp(2).toInstant()), SEASON_ID);
            assertThat(receipt).isEqualTo(new SourceRead(firstCanonicalStart, terminalFinish));
            assertThat(Duration.between(firstCanonicalStart, terminalFinish)).isLessThan(Duration.ofMinutes(10));
            assertThat(fetch.count(EndpointKind.BOOTSTRAP_STATIC)).isEqualTo(2);
            assertThat(fetch.count(EndpointKind.FIXTURES)).isEqualTo(2);
            assertThat(fetch.count(EndpointKind.EVENT_LIVE)).isOne();
            assertThat(fetch.playerIds()).containsExactly(1, 28);
            assertThat(fetch.returnedBodyBytes()).isPositive();
            assertThat(unit6WriteCount("player_fixture_stats", "INSERT")).isEqualTo(2L);
            Map<String, Long> firstWriteCounts = unit6WriteCounts();

            System.out.printf("UNIT6_FINAL_CAPTURE source=recorded-fixture-29 controlled-participants=[1,28] "
                            + "gets={bootstrap:%d,fixtures:%d,event-live:%d,history:%d} bodyBytes=%d "
                            + "jdbc={statements:%d,queries:%d,updates:%d,rows:%d,logicalBytes~%d} "
                            + "physicalWrites=%s cycleWallMs=%d%n",
                    fetch.count(EndpointKind.BOOTSTRAP_STATIC), fetch.count(EndpointKind.FIXTURES),
                    fetch.count(EndpointKind.EVENT_LIVE), fetch.count(EndpointKind.ELEMENT_SUMMARY),
                    fetch.returnedBodyBytes(), recording.statements().size(), recording.queryExecutions(),
                    recording.updateExecutions(), recording.returnedRows(),
                    recording.approximateLogicalReturnedBytes(), firstWriteCounts, cycleMillis);

            Map<String, String> receiptsBeforeNoOp = unit6ReceiptSnapshot();
            recording.clear();
            fetch.clearCalls();
            long noOpStarted = System.nanoTime();
            CycleResult next = collector.runCycle();
            long noOpCycleMillis = Duration.ofNanos(System.nanoTime() - noOpStarted).toMillis();

            assertThat(next.state()).isEqualTo(State.COMPLETED);
            assertThat(next.selectedPlayerIds()).isEmpty();
            assertThat(fetch.count(EndpointKind.BOOTSTRAP_STATIC)).isOne();
            assertThat(fetch.count(EndpointKind.FIXTURES)).isOne();
            assertThat(fetch.count(EndpointKind.ELEMENT_SUMMARY)).isZero();
            assertThat(fetch.count(EndpointKind.EVENT_LIVE)).isZero();
            assertThat(recording.queryExecutions()).isPositive();
            assertThat(recording.approximateLogicalReturnedBytes()).isPositive();
            Map<String, Long> noOpWriteCounts = unit6WriteCounts();
            Map<String, Long> noOpCountsWithoutFreshnessReceipt = new java.util.TreeMap<>(noOpWriteCounts);
            Map<String, Long> firstCountsWithoutFreshnessReceipt = new java.util.TreeMap<>(firstWriteCounts);
            noOpCountsWithoutFreshnessReceipt.remove(TABLES.receipt() + "/UPDATE");
            firstCountsWithoutFreshnessReceipt.remove(TABLES.receipt() + "/UPDATE");
            assertThat(noOpCountsWithoutFreshnessReceipt).isEqualTo(firstCountsWithoutFreshnessReceipt);
            assertThat(noOpWriteCounts.getOrDefault("player_fixture_stats/INSERT", 0L)).isEqualTo(2L);
            assertThat(noOpWriteCounts.getOrDefault("player_fixture_stats/UPDATE", 0L)).isZero();
            assertThat(noOpWriteCounts.getOrDefault(TABLES.playerFixtureEvidence() + "/INSERT", 0L)).isEqualTo(2L);
            assertThat(noOpWriteCounts.getOrDefault(TABLES.playerFixtureCurrent() + "/INSERT", 0L)).isEqualTo(2L);
            assertThat(noOpWriteCounts.getOrDefault(TABLES.gameweekStatSync() + "/UPDATE", 0L))
                    .isEqualTo(firstWriteCounts.getOrDefault(TABLES.gameweekStatSync() + "/UPDATE", 0L));
            Map<String, String> receiptsAfterNoOp = unit6ReceiptSnapshot();
            Set<String> changedReceiptKeys = new java.util.TreeSet<>();
            for (String key : receiptsBeforeNoOp.keySet()) {
                if (!java.util.Objects.equals(receiptsBeforeNoOp.get(key), receiptsAfterNoOp.get(key))) {
                    changedReceiptKeys.add(key);
                }
            }
            for (String key : receiptsAfterNoOp.keySet()) {
                if (!receiptsBeforeNoOp.containsKey(key)) changedReceiptKeys.add(key);
            }
            assertThat(changedReceiptKeys).hasSize(1);
            assertThat(noOpWriteCounts.getOrDefault(TABLES.receipt() + "/UPDATE", 0L))
                    .isEqualTo(firstWriteCounts.getOrDefault(TABLES.receipt() + "/UPDATE", 0L) + 1);
            assertThat(jdbc.queryForObject("SELECT updated_at FROM player_fixture_stats"
                    + " WHERE season_id=? AND fixture_id=? AND player_id=?", java.sql.Timestamp.class,
                    SEASON_ID, setup.fixtureId(), 28)).isEqualTo(rowUpdatedAt);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.playerFixtureEvidence()
                    + " WHERE season_id=? AND fixture_id=? AND player_id=?", Integer.class,
                    SEASON_ID, setup.fixtureId(), 28)).isEqualTo(evidenceRows);
            System.out.printf("UNIT6_FINAL_NOOP gets={bootstrap:%d,fixtures:%d,event-live:%d,history:%d} "
                            + "bodyBytes=%d jdbc={statements:%d,queries:%d,updates:%d,rows:%d,logicalBytes~%d} "
                            + "physicalWrites=%s changedReceiptKeys=%s cycleWallMs=%d%n",
                    fetch.count(EndpointKind.BOOTSTRAP_STATIC), fetch.count(EndpointKind.FIXTURES),
                    fetch.count(EndpointKind.EVENT_LIVE), fetch.count(EndpointKind.ELEMENT_SUMMARY),
                    fetch.returnedBodyBytes(), recording.statements().size(), recording.queryExecutions(),
                    recording.updateExecutions(),
                    recording.returnedRows(), recording.approximateLogicalReturnedBytes(), noOpWriteCounts,
                    changedReceiptKeys, noOpCycleMillis);
        } finally {
            removeUnit6WriteCounters();
        }
    }

    @Test
    void unchangedDiscoveryRetryKeepsKnownZeroMinutePlayerOutstanding() throws Exception {
        int fixtureId = 29;
        int cataloguePlayer = 1;
        int knownOnlyPlayer = 99001;
        byte[] fixtureSource = fixtureStatsForPlayers(
                onlyCollectorFixtureFinished(fixtures, fixtureId), fixtureId, cataloguePlayer, 28);
        ArrayNode schedule = (ArrayNode) JSON.readTree(fixtureSource);
        for (JsonNode node : schedule) {
            if (node.path("id").asInt() != fixtureId) continue;
            for (JsonNode stat : node.path("stats")) {
                for (String side : List.of("h", "a")) {
                    ArrayNode entries = (ArrayNode) stat.path(side);
                    for (int index = entries.size() - 1; index >= 0; index--) {
                        if (entries.get(index).path("element").asInt() != cataloguePlayer) entries.remove(index);
                    }
                }
            }
        }
        fixtureSource = JSON.writeValueAsBytes(schedule);
        byte[] bootstrapSource = markAllGameweeksUnchecked(bootstrapOnlyPlayer(bootstrap, cataloguePlayer));
        byte[] liveSource = liveForFixturePlayers(eventThreeLive, fixtureId, Set.of(cataloguePlayer));
        FixtureKey fixture = fixtureById(fixtureSource, fixtureId);
        byte[] knownOnlyHistory = historyWithMinutes(resource("element-summary-1.json"), fixtureId,
                knownOnlyPlayer, 0);
        ObjectNode knownOnlyPage = (ObjectNode) JSON.readTree(knownOnlyHistory);
        ObjectNode knownOnlyRow = (ObjectNode) knownOnlyPage.path("history").get(0);
        knownOnlyRow.put("total_points", 0);
        knownOnlyRow.put("bonus", 0);
        knownOnlyRow.put("bps", 0);
        knownOnlyHistory = JSON.writeValueAsBytes(knownOnlyPage);

        SharedStatsSeed.seedOwnedSyncKnownHistoryKey(jdbc, SEASON_ID, fixtureId, fixture.gameweek(), knownOnlyPlayer,
                fixture.homeTeam(), fixture.awayTeam(), true, fixture.kickoff());
        Lease seedLease = nextCoreLease();
        SourceRead seedRead = nextRead();
        publisher.publish(seedLease, season, knownOnlyPlayer, document(knownOnlyHistory, seedRead),
                canonicalFixtures(fixtureSource));
        store.completeStaged(seedLease, seedRead, List.of(seedRead));
        Map<String, Object> oldKnownRow = jdbc.queryForMap("SELECT minutes,total_points,bps FROM player_fixture_stats"
                + " WHERE season_id=? AND fixture_id=? AND player_id=?", SEASON_ID, fixtureId, knownOnlyPlayer);
        assertThat(oldKnownRow).containsEntry("minutes", 0).containsEntry("total_points", 0);

        var snapshot = CatalogueScheduleParser.parse(season,
                document(bootstrapSource, new SourceRead(START, START.plusMillis(1))),
                document(fixtureSource, new SourceRead(START.plusMillis(2), START.plusMillis(3))));
        var sourceFixture = snapshot.fixtures().stream().filter(row -> row.id() == fixtureId).findFirst().orElseThrow();
        var liveEvidence = FixtureVerification.fixtureEvidenceByFixture(List.of(sourceFixture),
                document(liveSource, new SourceRead(START.plusMillis(4), START.plusMillis(5)))).get(fixtureId);
        assertThat(snapshot.players()).extracting(CatalogueScheduleParser.PlayerRow::id)
                .containsExactly(cataloguePlayer);
        assertThat(FixtureVerification.fixtureStatParticipantIds(sourceFixture))
                .containsExactlyInAnyOrder(cataloguePlayer);
        assertThat(liveEvidence.positivePlayerIds()).containsExactlyInAnyOrder(cataloguePlayer);

        fetch.setSources(bootstrapSource, fixtureSource, liveSource, eventFourLive);
        fetch.setPlayerPage(cataloguePlayer,
                onlyHistoryForFixture(resource("element-summary-1.json"), fixtureId, cataloguePlayer));
        fetch.setPlayerPage(knownOnlyPlayer, knownOnlyHistory);
        fetch.failPlayerCalls(knownOnlyPlayer, 2);
        CycleResult first = compatibilityCollector(true).runCycle();
        List<Integer> firstPageCalls = fetch.playerIds();
        assertThat(first.state()).as("first cycle: %s", first).isEqualTo(State.RETRY_SCHEDULED);
        assertThat(first.selectedPlayerIds()).containsExactly(cataloguePlayer, knownOnlyPlayer);
        assertThat(first.committedPlayerIds()).containsExactly(cataloguePlayer);
        assertThat(first.failedPlayerIds()).containsExactly(knownOnlyPlayer);
        assertThat(first.finalPendingGameweeks()).isEmpty();
        assertThat(firstPageCalls).containsExactly(cataloguePlayer, knownOnlyPlayer);
        SourceRead actualLiveRead = fetch.successfulReads().stream()
                .filter(read -> read.endpoint().kind() == EndpointKind.EVENT_LIVE)
                .findFirst().orElseThrow().read();
        FixtureVerification.FixtureEvidence storedLiveEvidence = FixtureVerification.fixtureEvidenceByFixture(
                List.of(sourceFixture), document(liveSource, actualLiveRead)).get(fixtureId);
        String appearanceSnapshotKey = IncrementalAppearanceReceipt.snapshotKey(
                sourceFixture, fixture.gameweek(), storedLiveEvidence, storedLiveEvidence.liveRead());
        Map<String, Object> appearanceSnapshot = jdbc.queryForMap("SELECT latest_generation,"
                + "source_read_started_at,source_read_finished_at,source_pair_fingerprint FROM "
                + TABLES.receipt() + " WHERE season=? AND publication_key=?",
                season.code(), appearanceSnapshotKey);
        assertThat(appearanceSnapshot.get("latest_generation")).isNotNull();
        assertThat(appearanceSnapshot.get("source_pair_fingerprint"))
                .isEqualTo(IncrementalAppearanceReceipt.fingerprint(sourceFixture,
                        fixture.gameweek(), liveEvidence));
        assertThat(appearanceSnapshotKey).contains(":c:0:p:1");
        FixtureVerification.Appearance sourceAppearance = storedLiveEvidence.appearances().get(cataloguePlayer);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", Integer.class, season.code(),
                IncrementalAppearanceReceipt.playerKey(fixtureId, cataloguePlayer, sourceAppearance,
                        storedLiveEvidence.liveRead()))).isOne();

        String sourceFingerprint = jdbc.queryForObject("SELECT source_pair_fingerprint FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", String.class, season.code(), OwnedSyncStore.INCREMENTAL_CORE_KEY);
        String populationFingerprint = jdbc.queryForObject("SELECT source_pair_fingerprint FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", String.class, season.code(),
                OwnedSyncStore.INCREMENTAL_POPULATION_RECEIPT_PREFIX + fixtureId);
        List<String> populationMembers = jdbc.queryForList("SELECT publication_key FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key LIKE ? ORDER BY publication_key", String.class, season.code(),
                OwnedSyncStore.INCREMENTAL_PARTICIPANT_RECEIPT_PREFIX + fixtureId + ":player:%");
        assertThat(populationMembers).containsExactly(
                OwnedSyncStore.INCREMENTAL_PARTICIPANT_RECEIPT_PREFIX + fixtureId + ":player:" + cataloguePlayer);
        List<String> discoveryMembers = jdbc.queryForList("SELECT publication_key FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key LIKE ? ORDER BY publication_key", String.class, season.code(),
                OwnedSyncStore.INCREMENTAL_DISCOVERY_RECEIPT_PREFIX + fixtureId + ":player:%");
        assertThat(discoveryMembers).containsExactly(
                OwnedSyncStore.INCREMENTAL_DISCOVERY_RECEIPT_PREFIX + fixtureId + ":player:" + cataloguePlayer,
                OwnedSyncStore.INCREMENTAL_DISCOVERY_RECEIPT_PREFIX + fixtureId + ":player:" + knownOnlyPlayer);
        List<String> knownMembers = jdbc.queryForList("SELECT publication_key FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key LIKE ? ORDER BY publication_key", String.class, season.code(),
                OwnedSyncStore.INCREMENTAL_KNOWN_RECEIPT_PREFIX + fixtureId + ":player:%");
        assertThat(knownMembers).containsExactly(
                OwnedSyncStore.INCREMENTAL_KNOWN_RECEIPT_PREFIX + fixtureId + ":player:" + knownOnlyPlayer);
        Map<String, Object> populationMetadataBeforeRetry = jdbc.queryForMap("SELECT latest_generation,"
                + "source_read_started_at,source_read_finished_at,source_pair_fingerprint,published_at FROM "
                + TABLES.receipt() + " WHERE season=? AND publication_key=?", season.code(),
                OwnedSyncStore.INCREMENTAL_POPULATION_RECEIPT_PREFIX + fixtureId);
        Map<String, List<Object>> rowsBeforeRetry = pfsRowsSnapshot(Set.of(fixtureId));
        Map<String, List<Object>> proofBeforeRetry = pfsProofSnapshot(Set.of(fixtureId));
        Map<String, Object> pfsBeforeRetry = jdbc.queryForMap("SELECT minutes,total_points,bps FROM player_fixture_stats"
                + " WHERE season_id=? AND fixture_id=? AND player_id=?", SEASON_ID, fixtureId, knownOnlyPlayer);

        fetch.clearCalls();
        clock.set(first.nextDue().plusSeconds(1));
        SqlRecordingDataSource recording = new SqlRecordingDataSource(dataSource);
        JdbcTemplate recordedJdbc = new JdbcTemplate(recording);
        TransactionTemplate recordedTx = new TransactionTemplate(new DataSourceTransactionManager(recording));
        CycleResult retry = compatibilityCollector(true, recordedJdbc, recordedTx).runCycle();
        List<Integer> retryPageCalls = fetch.playerIds();
        String retryError = latestRunError();
        String retrySourceFingerprint = jdbc.queryForObject("SELECT source_pair_fingerprint FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", String.class, season.code(), OwnedSyncStore.INCREMENTAL_CORE_KEY);
        String retryPopulationFingerprint = jdbc.queryForObject("SELECT source_pair_fingerprint FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", String.class, season.code(),
                OwnedSyncStore.INCREMENTAL_POPULATION_RECEIPT_PREFIX + fixtureId);
        List<String> retryPopulationMembers = jdbc.queryForList("SELECT publication_key FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key LIKE ? ORDER BY publication_key", String.class, season.code(),
                OwnedSyncStore.INCREMENTAL_PARTICIPANT_RECEIPT_PREFIX + fixtureId + ":player:%");
        List<String> retryDiscoveryMembers = jdbc.queryForList("SELECT publication_key FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key LIKE ? ORDER BY publication_key", String.class, season.code(),
                OwnedSyncStore.INCREMENTAL_DISCOVERY_RECEIPT_PREFIX + fixtureId + ":player:%");
        List<String> retryKnownMembers = jdbc.queryForList("SELECT publication_key FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key LIKE ? ORDER BY publication_key", String.class, season.code(),
                OwnedSyncStore.INCREMENTAL_KNOWN_RECEIPT_PREFIX + fixtureId + ":player:%");
        Map<String, Object> populationMetadataAfterRetry = jdbc.queryForMap("SELECT latest_generation,"
                + "source_read_started_at,source_read_finished_at,source_pair_fingerprint,published_at FROM "
                + TABLES.receipt() + " WHERE season=? AND publication_key=?", season.code(),
                OwnedSyncStore.INCREMENTAL_POPULATION_RECEIPT_PREFIX + fixtureId);
        Map<String, List<Object>> rowsAfterRetry = pfsRowsSnapshot(Set.of(fixtureId));
        Map<String, List<Object>> proofAfterRetry = pfsProofSnapshot(Set.of(fixtureId));
        Map<String, Object> pfsAfterRetry = jdbc.queryForMap("SELECT minutes,total_points,bps FROM player_fixture_stats"
                + " WHERE season_id=? AND fixture_id=? AND player_id=?", SEASON_ID, fixtureId, knownOnlyPlayer);
        int knownPfsQueries = knownPlayerHydrationQueryCount(recording);
        int retryDiscoveryBatches = discoveryCandidateBatchCount(recording);
        String diagnostic = "first={state=" + first.state() + ",selected=" + first.selectedPlayerIds()
                + ",committed=" + first.committedPlayerIds() + ",failed=" + first.failedPlayerIds()
                + ",historyCalls=" + firstPageCalls + "}, retry={state=" + retry.state()
                + ",selected=" + retry.selectedPlayerIds() + ",committed=" + retry.committedPlayerIds()
                + ",failed=" + retry.failedPlayerIds() + ",historyCalls=" + retryPageCalls
                + "}, sourceFingerprintUnchanged=" + sourceFingerprint.equals(retrySourceFingerprint)
                + ", populationFingerprintUnchanged=" + populationFingerprint.equals(retryPopulationFingerprint)
                + ", livePopulationUnchanged=" + populationMembers.equals(retryPopulationMembers)
                + ", discoveryPopulationUnchanged=" + discoveryMembers.equals(retryDiscoveryMembers)
                + ", knownPopulationUnchanged=" + knownMembers.equals(retryKnownMembers)
                + ", knownPfsQueriesOnRetry=" + knownPfsQueries + ", discoveryBatchesOnRetry="
                + retryDiscoveryBatches + ", error=" + retryError
                + ", knownOnlyPfs=" + pfsBeforeRetry + " -> " + pfsAfterRetry
                + ", eventRequests=" + fetch.requests().stream()
                        .filter(request -> request.endpoint().kind() == EndpointKind.EVENT_LIVE).toList();
        System.out.println("PR157_KNOWN_PLAYER_RETRY_REPRO " + diagnostic);

        org.assertj.core.api.SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(retry.state()).as(diagnostic).isEqualTo(State.RETRY_SCHEDULED);
            softly.assertThat(retry.selectedPlayerIds()).as(diagnostic).containsExactly(knownOnlyPlayer);
            softly.assertThat(fetch.count(EndpointKind.EVENT_LIVE)).as(diagnostic).isZero();
            softly.assertThat(retry.committedPlayerIds()).as(diagnostic).isEmpty();
            softly.assertThat(retry.failedPlayerIds()).as(diagnostic).containsExactly(knownOnlyPlayer);
            softly.assertThat(retryPageCalls).as(diagnostic).containsExactly(knownOnlyPlayer);
            softly.assertThat(knownPfsQueries).as(diagnostic).isZero();
            softly.assertThat(retryDiscoveryBatches).as(diagnostic).isZero();
            softly.assertThat(retrySourceFingerprint).as(diagnostic).isEqualTo(sourceFingerprint);
            softly.assertThat(retryPopulationFingerprint).as(diagnostic).isEqualTo(populationFingerprint);
            softly.assertThat(retryPopulationMembers).as(diagnostic).isEqualTo(populationMembers);
            softly.assertThat(retryDiscoveryMembers).as(diagnostic).isEqualTo(discoveryMembers);
            softly.assertThat(retryKnownMembers).as(diagnostic).isEqualTo(knownMembers);
            softly.assertThat(populationMetadataAfterRetry).isEqualTo(populationMetadataBeforeRetry);
            softly.assertThat(rowsAfterRetry).isEqualTo(rowsBeforeRetry);
            softly.assertThat(proofAfterRetry).isEqualTo(proofBeforeRetry);
            softly.assertThat(pfsAfterRetry).as(diagnostic).isEqualTo(pfsBeforeRetry);
        });

        fetch.clearPageOutcomes();
        fetch.setPlayerPage(knownOnlyPlayer, RecordedFetch.EMPTY_HISTORY);
        fetch.clearCalls();
        clock.set(retry.nextDue().plusSeconds(1));
        CycleResult emptyPage = compatibilityCollector(true).runCycle();

        assertThat(emptyPage.state()).isEqualTo(State.COMPLETED);
        assertThat(emptyPage.selectedPlayerIds()).containsExactly(knownOnlyPlayer);
        assertThat(emptyPage.committedPlayerIds()).containsExactly(knownOnlyPlayer);
        assertThat(fetch.playerIds()).containsExactly(knownOnlyPlayer);
        assertThat(jdbc.queryForObject("SELECT row_state FROM " + TABLES.playerFixtureCurrent()
                + " WHERE season_id=? AND fixture_id=? AND player_id=?", String.class,
                SEASON_ID, fixtureId, knownOnlyPlayer)).isEqualTo("WITHDRAWN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", Integer.class, season.code(),
                OwnedSyncStore.INCREMENTAL_KNOWN_RECEIPT_PREFIX + fixtureId + ":player:" + knownOnlyPlayer))
                .as("a successful empty page resolves the durable known retry candidate")
                .isZero();
        assertThat(jdbc.queryForMap("SELECT minutes,total_points,bps FROM player_fixture_stats"
                + " WHERE season_id=? AND fixture_id=? AND player_id=?", SEASON_ID, fixtureId, knownOnlyPlayer))
                .isEqualTo(pfsBeforeRetry);
        assertThat(pfsRowsSnapshot(Set.of(fixtureId))).isEqualTo(rowsBeforeRetry);
        assertThat(pfsProofSnapshotForKey(Set.of(fixtureId), fixtureId + "/" + cataloguePlayer))
                .isEqualTo(proofBeforeRetry.get(fixtureId + "/" + cataloguePlayer));

        fetch.clearCalls();
        clock.set(clock.instant().plusSeconds(10));
        SqlRecordingDataSource noopRecording = new SqlRecordingDataSource(dataSource);
        JdbcTemplate noopJdbc = new JdbcTemplate(noopRecording);
        TransactionTemplate noopTx = new TransactionTemplate(new DataSourceTransactionManager(noopRecording));
        CycleResult noop = compatibilityCollector(true, noopJdbc, noopTx).runCycle();
        int noopKnownPfsQueries = knownPlayerHydrationQueryCount(noopRecording);

        assertThat(noop.state()).isEqualTo(State.COMPLETED);
        assertThat(noop.selectedPlayerIds()).isEmpty();
        assertThat(fetch.playerIds()).isEmpty();
        assertThat(noopKnownPfsQueries).isZero();
        assertThat(discoveryCandidateBatchCount(noopRecording)).isZero();
        assertThat(jdbc.queryForObject("SELECT row_state FROM " + TABLES.playerFixtureCurrent()
                + " WHERE season_id=? AND fixture_id=? AND player_id=?", String.class,
                SEASON_ID, fixtureId, knownOnlyPlayer)).isEqualTo("WITHDRAWN");

        byte[] correctedFixtures = changeFixtureScore(fixtureSource, fixtureId, 3);
        fetch.setSources(bootstrapSource, correctedFixtures, liveSource, eventFourLive);
        fetch.clearCalls();
        clock.set(clock.instant().plusSeconds(10));
        SqlRecordingDataSource correctionRecording = new SqlRecordingDataSource(dataSource);
        JdbcTemplate correctionJdbc = new JdbcTemplate(correctionRecording);
        TransactionTemplate correctionTx = new TransactionTemplate(new DataSourceTransactionManager(correctionRecording));
        CycleResult corrected = compatibilityCollector(true, correctionJdbc, correctionTx).runCycle();
        int correctionKnownPfsQueries = knownPlayerHydrationQueryCount(correctionRecording);
        String correctedPopulationFingerprint = jdbc.queryForObject("SELECT source_pair_fingerprint FROM "
                + TABLES.receipt() + " WHERE season=? AND publication_key=?", String.class, season.code(),
                OwnedSyncStore.INCREMENTAL_POPULATION_RECEIPT_PREFIX + fixtureId);

        assertThat(corrected.state()).isEqualTo(State.COMPLETED);
        assertThat(corrected.selectedPlayerIds()).containsExactly(cataloguePlayer, knownOnlyPlayer);
        assertThat(fetch.playerIds()).containsExactly(cataloguePlayer, knownOnlyPlayer);
        assertThat(correctionKnownPfsQueries).isEqualTo(1);
        assertThat(discoveryCandidateBatchCount(correctionRecording)).isZero();
        assertThat(correctedPopulationFingerprint).isNotEqualTo(populationFingerprint);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", Integer.class, season.code(),
                OwnedSyncStore.INCREMENTAL_KNOWN_RECEIPT_PREFIX + fixtureId + ":player:" + knownOnlyPlayer))
                .isZero();

        fetch.clearCalls();
        clock.set(clock.instant().plusSeconds(10));
        SqlRecordingDataSource postCorrectionRecording = new SqlRecordingDataSource(dataSource);
        JdbcTemplate postCorrectionJdbc = new JdbcTemplate(postCorrectionRecording);
        TransactionTemplate postCorrectionTx = new TransactionTemplate(new DataSourceTransactionManager(postCorrectionRecording));
        CycleResult postCorrectionNoop = compatibilityCollector(true, postCorrectionJdbc, postCorrectionTx).runCycle();
        int repeatedKnownPfsQueries = knownPlayerHydrationQueryCount(postCorrectionRecording);

        assertThat(postCorrectionNoop.state()).isEqualTo(State.COMPLETED);
        assertThat(postCorrectionNoop.selectedPlayerIds()).isEmpty();
        assertThat(fetch.playerIds()).isEmpty();
        assertThat(repeatedKnownPfsQueries).isZero();
        assertThat(discoveryCandidateBatchCount(postCorrectionRecording)).isZero();
    }

    @Test
    void sourceKnownZeroMinutePlayerPublishesCurrentRowWithoutParticipantPromotion() throws Exception {
        CheckedFixtureSetup setup = checkedSingleFixtureSetup();
        int knownPlayer = 99001;
        byte[] knownHistory = historyWithMinutes(resource("element-summary-1.json"), setup.fixtureId(),
                knownPlayer, 0);
        ObjectNode knownPage = (ObjectNode) JSON.readTree(knownHistory);
        ObjectNode knownRow = (ObjectNode) knownPage.path("history").get(0);
        knownRow.put("total_points", 0);
        knownRow.put("bonus", 0);
        knownRow.put("bps", 0);
        knownHistory = JSON.writeValueAsBytes(knownPage);
        SharedStatsSeed.seedOwnedSyncKnownHistoryKey(jdbc, SEASON_ID, setup.fixtureId(), 3, knownPlayer,
                1, 2, true, fixtureById(setup.fixtureSource(), setup.fixtureId()).kickoff());
        Lease seedLease = nextCoreLease();
        SourceRead seedRead = nextRead();
        publisher.publish(seedLease, season, knownPlayer, document(knownHistory, seedRead),
                canonicalFixtures(setup.fixtureSource()));
        store.completeStaged(seedLease, seedRead, List.of(seedRead));
        fetch.setPlayerPage(knownPlayer, knownHistory);

        CycleResult result = compatibilityCollector(true).runCycle();

        assertThat(result.state()).as("cycle: %s; error: %s", result, latestRunError()).isEqualTo(State.COMPLETED);
        assertThat(result.selectedPlayerIds()).containsExactly(1, 28, knownPlayer);
        assertThat(result.committedPlayerIds()).containsExactly(1, 28, knownPlayer);
        assertThat(fetch.playerIds()).containsExactly(1, 28, knownPlayer);
        assertThat(jdbc.queryForObject("SELECT row_state FROM " + TABLES.playerFixtureCurrent()
                + " WHERE season_id=? AND fixture_id=? AND player_id=?", String.class,
                SEASON_ID, setup.fixtureId(), knownPlayer)).isEqualTo("VALID");
        assertThat(jdbc.queryForObject("SELECT minutes FROM player_fixture_stats"
                + " WHERE season_id=? AND fixture_id=? AND player_id=?", Integer.class,
                SEASON_ID, setup.fixtureId(), knownPlayer)).isZero();

        String manifest = jdbc.queryForObject("SELECT final_attempt_population_manifest::text FROM "
                + TABLES.gameweekStatSync() + " WHERE season_id=? AND gameweek=3", String.class, SEASON_ID);
        JsonNode population = JSON.readTree(manifest);
        assertThat(population.path("known").path(Integer.toString(setup.fixtureId())).toString())
                .isEqualTo("[" + knownPlayer + "]");
        assertThat(population.path("participants").path(Integer.toString(setup.fixtureId())).toString())
                .isEqualTo("[1,28]");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", Integer.class, season.code(),
                OwnedSyncStore.INCREMENTAL_PARTICIPANT_RECEIPT_PREFIX + setup.fixtureId()
                        + ":player:" + knownPlayer)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", Integer.class, season.code(),
                OwnedSyncStore.INCREMENTAL_KNOWN_RECEIPT_PREFIX + setup.fixtureId()
                        + ":player:" + knownPlayer)).isOne();
        assertThat(new GameweekFinalityReader(jdbc, clock, TABLES)
                .read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                .isEqualTo(GameweekFinality.State.FINALIZED);
    }

    @Test
    void coldLeaguePopulationFitsBoundedTransactionWithDatabaseRoundTripLatency() {
        Map<Integer, Set<Integer>> participants = new LinkedHashMap<>();
        Map<Integer, Set<Integer>> candidates = new LinkedHashMap<>();
        Map<Integer, String> fingerprints = new LinkedHashMap<>();
        for (int fixtureId = 1; fixtureId <= 65; fixtureId++) {
            Set<Integer> fixturePlayers = new LinkedHashSet<>();
            for (int offset = 0; offset < 62; offset++) {
                fixturePlayers.add(((fixtureId - 1) * 31 + offset) % 667 + 1);
            }
            candidates.put(fixtureId, Set.copyOf(fixturePlayers));
            participants.put(fixtureId, fixturePlayers.stream().limit(31)
                    .collect(java.util.stream.Collectors.toSet()));
            fingerprints.put(fixtureId, "a".repeat(64));
        }
        Lease lease = nextCoreLease();
        SqlRecordingDataSource recording = new SqlRecordingDataSource(dataSource);
        recording.receiptWriteDelayNanos = Duration.ofMillis(4).toNanos();
        TransactionTemplate boundedTx = new TransactionTemplate(new DataSourceTransactionManager(recording));
        boundedTx.setTimeout(4);
        store = new OwnedSyncStore(new JdbcTemplate(recording), boundedTx, clock, TABLES, runtimeFence);

        assertThatCode(() -> store.recordIncrementalPopulation(lease, participants, candidates,
                Map.of(), fingerprints, nextRead())).doesNotThrowAnyException();

        assertThat(recording.receiptWriteExecutions)
                .as("JDBC receipt write executions, not prepared statements or rows").isLessThanOrEqualTo(20);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.receipt()
                + " WHERE publication_key LIKE 'incremental-participant:%'", Integer.class)).isEqualTo(2_015);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.receipt()
                + " WHERE publication_key LIKE 'incremental-discovery:%'", Integer.class)).isEqualTo(4_030);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.receipt()
                + " WHERE publication_key LIKE 'incremental-population:%'", Integer.class)).isEqualTo(65);
    }

    @Test
    void liveEventParticipantsAndAppearanceReceiptsUseBoundedBatches() {
        Lease lease = nextCoreLease();
        SourceRead bootstrapRead = nextRead();
        SourceRead fixturesRead = nextRead();
        var snapshot = CatalogueScheduleParser.parse(season, document(bootstrap, bootstrapRead),
                document(fixtures, fixturesRead));
        store.observeIncrementalSnapshot(lease, snapshot);
        store.recordIncrementalFixtureSourceRevisions(lease, snapshot.fixtures(), fixturesRead);
        var eventFixtures = snapshot.fixtures().stream().filter(fixture -> Integer.valueOf(3)
                .equals(fixture.gameweek())).toList();
        assertThat(eventFixtures).hasSize(10);
        SourceRead liveRead = nextRead();
        var evidence = FixtureVerification.fixtureEvidenceByFixture(eventFixtures, document(eventThreeLive, liveRead));
        Map<Integer, Set<Integer>> participants = new LinkedHashMap<>();
        for (var fixture : eventFixtures) {
            Set<Integer> players = new LinkedHashSet<>(FixtureVerification.fixtureStatParticipantIds(fixture));
            players.addAll(evidence.get(fixture.id()).positivePlayerIds());
            for (int playerId = 7_000; players.size() < 80; playerId++) players.add(playerId);
            participants.put(fixture.id(), Set.copyOf(players));
        }
        SqlRecordingDataSource recording = new SqlRecordingDataSource(dataSource);
        recording.receiptWriteDelayNanos = Duration.ofMillis(4).toNanos();
        TransactionTemplate boundedTx = new TransactionTemplate(new DataSourceTransactionManager(recording));
        boundedTx.setTimeout(4);
        store = new OwnedSyncStore(new JdbcTemplate(recording), boundedTx, clock, TABLES, runtimeFence);

        store.recordIncrementalLiveEvent(lease, 3, GameweekFinality.SourceSnapshot.from(snapshot, 3).signature(),
                participants, eventFixtures, evidence, liveRead);

        assertThat(recording.receiptWriteExecutions).isLessThanOrEqualTo(20);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.receipt()
                + " WHERE publication_key LIKE 'incremental-participant:%'", Integer.class)).isEqualTo(800);
        Map<String, String> receipts = unit6ReceiptSnapshot();
        assertThat(receipts.keySet().stream().filter(key -> key.startsWith(IncrementalAppearanceReceipt.SNAPSHOT_PREFIX)))
                .hasSize(10);
        assertThat(receipts.keySet().stream().filter(key -> key.startsWith(IncrementalAppearanceReceipt.PLAYER_PREFIX)))
                .hasSize(evidence.values().stream().mapToInt(fixture -> fixture.appearances().size()).sum());
    }

    @Test
    void populationBatchesPreserveReceiptOrderingAndUnchangedFingerprint() {
        Lease lease = nextCoreLease();
        Map<Integer, Set<Integer>> players = Map.of(29, Set.of(1, 28));
        Map<Integer, String> fingerprints = Map.of(29, "a".repeat(64));
        SourceRead firstRead = nextRead();
        store.recordIncrementalPopulation(lease, players, players, Map.of(), fingerprints, firstRead);
        String participantKey = OwnedSyncStore.INCREMENTAL_PARTICIPANT_RECEIPT_PREFIX + "29:player:1";
        String populationKey = OwnedSyncStore.INCREMENTAL_POPULATION_RECEIPT_PREFIX + 29;
        jdbc.update("UPDATE " + TABLES.receipt() + " SET source_pair_fingerprint=? WHERE publication_key=?",
                "b".repeat(64), participantKey);
        String firstPopulation = unit6ReceiptSnapshot().get(populationKey);
        SourceRead newerRead = nextRead();

        store.recordIncrementalPopulation(lease, players, players, players, fingerprints, newerRead);

        assertThat(unit6ReceiptSnapshot().get(populationKey)).isEqualTo(firstPopulation);
        assertThat(jdbc.queryForObject("SELECT source_read_finished_at FROM " + TABLES.receipt()
                + " WHERE publication_key=?", java.sql.Timestamp.class, participantKey))
                .isEqualTo(java.sql.Timestamp.from(newerRead.finishedAt()));
        assertThat(jdbc.queryForObject("SELECT source_pair_fingerprint FROM " + TABLES.receipt()
                + " WHERE publication_key=?", String.class, participantKey)).isEqualTo("b".repeat(64));
        Map<String, String> newer = unit6ReceiptSnapshot();

        store.recordIncrementalPopulation(lease, players, players, players, fingerprints, firstRead);

        assertThat(unit6ReceiptSnapshot()).isEqualTo(newer);
        jdbc.update("UPDATE " + TABLES.receipt() + " SET latest_generation=? WHERE publication_key=?",
                lease.generation() + 1, participantKey);
        Map<String, String> higherGeneration = unit6ReceiptSnapshot();

        store.recordIncrementalPopulation(lease, players, players, players, fingerprints, nextRead());

        assertThat(unit6ReceiptSnapshot().get(participantKey)).isEqualTo(higherGeneration.get(participantKey));
    }

    @Test
    void populationBatchFailureRollsBackEarlierBatches() {
        Lease lease = nextCoreLease();
        Set<Integer> players = java.util.stream.IntStream.rangeClosed(1, 501).boxed()
                .collect(java.util.stream.Collectors.toSet());
        SqlRecordingDataSource recording = new SqlRecordingDataSource(dataSource);
        recording.failReceiptWriteAt = 2;
        TransactionTemplate recordedTx = new TransactionTemplate(new DataSourceTransactionManager(recording));
        store = new OwnedSyncStore(new JdbcTemplate(recording), recordedTx, clock, TABLES, runtimeFence);
        Map<String, String> before = unit6ReceiptSnapshot();

        assertThatThrownBy(() -> store.recordIncrementalPopulation(lease, Map.of(29, players),
                Map.of(29, players), Map.of(), Map.of(29, "a".repeat(64)), nextRead()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);

        assertThat(recording.receiptWriteExecutions).isEqualTo(2);
        assertThat(unit6ReceiptSnapshot()).isEqualTo(before);
    }

    @Test
    void discoveryCounterCountsExecutionsWithoutDependingOnSqlComments() throws SQLException {
        SqlRecordingDataSource recording = new SqlRecordingDataSource(dataSource);
        try (Connection connection = recording.getConnection();
             PreparedStatement statement = connection.prepareStatement("INSERT INTO " + TABLES.receipt()
                     + "(season, publication_key) VALUES (?, ?) ON CONFLICT DO NOTHING")) {
            statement.setString(1, season.code());
            statement.setString(2, OwnedSyncStore.INCREMENTAL_DISCOVERY_RECEIPT_PREFIX + "29:player:1");
            statement.addBatch();
            assertThat(discoveryCandidateBatchCount(recording)).isZero();

            statement.executeBatch();
            statement.addBatch();
            statement.executeBatch();
            assertThat(discoveryCandidateBatchCount(recording)).isEqualTo(2);

            statement.setString(2, OwnedSyncStore.INCREMENTAL_PARTICIPANT_RECEIPT_PREFIX + "29:player:1");
            statement.addBatch();
            statement.executeBatch();
            assertThat(discoveryCandidateBatchCount(recording)).isEqualTo(2);

            statement.setObject(2, OwnedSyncStore.INCREMENTAL_DISCOVERY_RECEIPT_PREFIX + "29:player:2");
            statement.addBatch();
            statement.clearBatch();
            statement.executeBatch();
            assertThat(discoveryCandidateBatchCount(recording)).isEqualTo(2);

            statement.executeLargeUpdate();
            assertThat(discoveryCandidateBatchCount(recording)).isEqualTo(3);

            recording.clear();
            assertThat(discoveryCandidateBatchCount(recording)).isZero();
        }
    }

    @Test
    void discoveryCandidateReceiptsUseBoundedBatchesAndSkipExistingIds() {
        int fixtureId = 29;
        Set<Integer> candidates = new LinkedHashSet<>();
        for (int playerId = 1; playerId <= 501; playerId++) candidates.add(playerId);
        Map<Integer, Set<Integer>> candidateIds = Map.of(fixtureId, Set.copyOf(candidates));
        Map<Integer, String> fingerprints = Map.of(fixtureId, "a".repeat(64));
        Lease lease = nextCoreLease();
        SqlRecordingDataSource recording = new SqlRecordingDataSource(dataSource);
        JdbcTemplate recordedJdbc = new JdbcTemplate(recording);
        TransactionTemplate recordedTx = new TransactionTemplate(new DataSourceTransactionManager(recording));
        store = new OwnedSyncStore(recordedJdbc, recordedTx, clock, TABLES, runtimeFence);

        Map<Integer, Set<Integer>> existing = store.incrementalDiscoveredPlayerIds(lease, List.of(fixtureId));
        store.recordIncrementalPopulation(lease, Map.of(), candidateIds, existing, fingerprints, nextRead());

        assertThat(existing).isEmpty();
        assertThat(discoveryCandidateBatchCount(recording)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key LIKE ?", Integer.class, season.code(),
                OwnedSyncStore.INCREMENTAL_DISCOVERY_RECEIPT_PREFIX + fixtureId + ":player:%")).isEqualTo(501);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key LIKE ?", Integer.class, season.code(),
                OwnedSyncStore.INCREMENTAL_PARTICIPANT_RECEIPT_PREFIX + fixtureId + ":player:%")).isZero();

        recording.clear();
        existing = store.incrementalDiscoveredPlayerIds(lease, List.of(fixtureId));
        store.recordIncrementalPopulation(lease, Map.of(), candidateIds, existing, fingerprints, nextRead());

        assertThat(existing.get(fixtureId)).hasSize(501);
        assertThat(discoveryCandidateBatchCount(recording)).isZero();
    }

    @Test
    void checkedTenFixtureGameweekResumesNoopsAndCorrectsOnlyTheChangedRow() throws Exception {
        MultiFixtureSetup setup = checkedMultiFixtureSetup();
        assertThat(setup.fixtureIds()).hasSize(10);
        assertThat(setup.participantIds()).hasSize(40);
        assertThat(setup.participantsByFixture()).allSatisfy((fixtureId, ids) -> assertThat(ids).hasSize(4));
        int catalogueSize = setup.participantIds().size() + 1;
        assertThat(JSON.readTree(setup.bootstrapSource()).path("elements").size()).isEqualTo(catalogueSize);

        for (String table : List.of("team", "player", "gameweek", "fixture")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE season_id=?",
                    Integer.class, SEASON_ID)).as("empty compatibility baseline for %s", table).isZero();
        }
        installUnit6WriteCounters();
        try {
            int failedPlayer = setup.participantIds().stream().min(Integer::compareTo).orElseThrow();
            fetch.failPlayer(failedPlayer);
            CycleResult partial = compatibilityCollector(true).runCycle();

            assertThat(partial.state()).isEqualTo(State.RETRY_SCHEDULED);
            assertThat(partial.finalPendingGameweeks()).containsExactly(3);
            assertThat(partial.selectedPlayerIds()).hasSize(catalogueSize).containsAll(setup.participantIds());
            assertThat(partial.failedPlayerIds()).containsExactly(failedPlayer);
            assertThat(partial.committedPlayerIds()).hasSize(catalogueSize - 1);
            assertThat(fetch.count(EndpointKind.BOOTSTRAP_STATIC)).isOne();
            assertThat(fetch.count(EndpointKind.FIXTURES)).isOne();
            assertThat(fetch.count(EndpointKind.EVENT_LIVE)).as("live events %s", fetch.calls().stream()
                    .filter(endpoint -> endpoint.kind() == EndpointKind.EVENT_LIVE)
                    .map(Endpoint::eventId).toList()).isOne();
            assertThat(fetch.count(EndpointKind.ELEMENT_SUMMARY)).isEqualTo(catalogueSize);
            int initialHistoryGets = fetch.count(EndpointKind.ELEMENT_SUMMARY);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM team WHERE season_id=?", Integer.class,
                    SEASON_ID)).isEqualTo(20);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM player WHERE season_id=?", Integer.class,
                    SEASON_ID)).isEqualTo(catalogueSize);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM gameweek WHERE season_id=?", Integer.class,
                    SEASON_ID)).isEqualTo(38);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fixture WHERE season_id=?", Integer.class,
                    SEASON_ID)).isEqualTo(380);
            Map<String, Long> catalogueWritesAfterFirstCycle = compatibilityCatalogueWriteCounts();
            assertThat(catalogueWritesAfterFirstCycle).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "team/INSERT", 20L, "team/UPDATE", 0L,
                    "player/INSERT", (long) catalogueSize, "player/UPDATE", 0L,
                    "gameweek/INSERT", 38L, "gameweek/UPDATE", 0L,
                    "fixture/INSERT", 380L, "fixture/UPDATE", 0L));
            assertThat(jdbc.queryForObject("SELECT cardinality(final_attempt_required_player_ids) FROM "
                    + TABLES.gameweekStatSync() + " WHERE season_id=? AND gameweek=3", Integer.class,
                    SEASON_ID)).isEqualTo(catalogueSize);
            assertThat(jdbc.queryForObject("SELECT cardinality(final_attempt_outstanding_player_ids) FROM "
                    + TABLES.gameweekStatSync() + " WHERE season_id=? AND gameweek=3", Integer.class,
                    SEASON_ID)).isOne();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM player_fixture_stats WHERE season_id=?"
                    + " AND fixture_id IN (21,22,23,24,25,26,27,28,29,30)", Integer.class, SEASON_ID))
                    .isEqualTo(39);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.playerFixtureEvidence()
                    + " WHERE season_id=? AND fixture_id IN (21,22,23,24,25,26,27,28,29,30)",
                    Integer.class, SEASON_ID)).isEqualTo(39);
            assertThat(new PfsReadSource(jdbc, "ACTIVE", season.code(), TABLES.fixtureSync(),
                    TABLES.playerFixtureEvidence(), TABLES.playerFixtureCurrent(), TABLES.receipt())
                    .incrementalRowsByGameweek(SEASON_ID, setup.fixtureIds()).get(3).incrementalRows())
                    .isEqualTo(39);
            assertThat(new GameweekFinalityReader(jdbc, clock, TABLES)
                    .read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                    .isEqualTo(GameweekFinality.State.FINAL_PENDING);

            List<RecordedRead> firstAttemptReads = fetch.successfulReads();
            fetch.clearCalls();
            clock.set(partial.nextDue().plusSeconds(1));
            CycleResult resumed = compatibilityCollector(true).runCycle();
            int sourceOnlyRetries = 0;
            while (resumed.state() == State.RETRY_SCHEDULED && resumed.selectedPlayerIds().isEmpty()
                    && fetch.count(EndpointKind.ELEMENT_SUMMARY) == 0 && sourceOnlyRetries < 2) {
                assertThat(resumed.failedPlayerIds()).isEmpty();
                assertThat(fetch.count(EndpointKind.BOOTSTRAP_STATIC)).isOne();
                assertThat(fetch.count(EndpointKind.FIXTURES)).isOne();
                fetch.clearCalls();
                clock.set(resumed.nextDue().plusSeconds(1));
                resumed = compatibilityCollector(true).runCycle();
                sourceOnlyRetries++;
            }

            assertThat(resumed.state()).as("cycle=%s last error=%s endpoints=%s", resumed, latestRunError(),
                    fetch.calls().stream().map(endpoint -> endpoint.kind() + ":" + endpoint.eventId()
                            + ":" + endpoint.playerId()).toList()).isEqualTo(State.COMPLETED);
            assertThat(resumed.selectedPlayerIds()).containsExactly(failedPlayer);
            assertThat(resumed.committedPlayerIds()).containsExactly(failedPlayer);
            assertThat(fetch.playerIds()).containsExactly(failedPlayer);
            assertThat(fetch.count(EndpointKind.BOOTSTRAP_STATIC)).isEqualTo(2);
            assertThat(fetch.count(EndpointKind.FIXTURES)).isEqualTo(2);
            assertThat(fetch.count(EndpointKind.EVENT_LIVE)).isOne();
            assertThat(fetch.count(EndpointKind.ELEMENT_SUMMARY)).isOne();
            int resumeHistoryGets = fetch.count(EndpointKind.ELEMENT_SUMMARY);
            assertThat(compatibilityCatalogueWriteCounts()).isEqualTo(catalogueWritesAfterFirstCycle);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM player_fixture_stats WHERE season_id=?"
                    + " AND fixture_id IN (21,22,23,24,25,26,27,28,29,30)", Integer.class, SEASON_ID))
                    .isEqualTo(40);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.playerFixtureEvidence()
                    + " WHERE season_id=? AND fixture_id IN (21,22,23,24,25,26,27,28,29,30)",
                    Integer.class, SEASON_ID)).isEqualTo(40);
            assertThat(new GameweekFinalityReader(jdbc, clock, TABLES)
                    .read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                    .isEqualTo(GameweekFinality.State.FINALIZED);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.playerFixtureEvidence()
                    + " WHERE season_id=? AND fixture_id IN (21,22,23,24,25,26,27,28,29,30)"
                    + " AND (component_presence & ?) = ?", Integer.class, SEASON_ID,
                    HistoryParser.Component.allMask(), HistoryParser.Component.allMask())).isEqualTo(40);

            Map<String, Object> completedRun = jdbc.queryForMap("SELECT generation,state,source_read_started_at,"
                    + "source_read_finished_at FROM " + TABLES.run() + " WHERE producer_key=? AND season=?"
                    + " ORDER BY generation DESC LIMIT 1", OwnedSyncStore.INCREMENTAL_CORE_PRODUCER,
                    season.code());
            assertThat(completedRun.get("state")).isEqualTo("COMPLETED");
            assertThat(((java.sql.Timestamp) completedRun.get("source_read_started_at")).toInstant()).isNotNull();
            assertThat(((java.sql.Timestamp) completedRun.get("source_read_finished_at")).toInstant()).isNotNull();
            Instant latestHistoryFinish = fetch.successfulReads().stream()
                    .filter(read -> read.endpoint().kind() == EndpointKind.ELEMENT_SUMMARY)
                    .map(read -> read.read().finishedAt()).max(Instant::compareTo).orElseThrow();
            Instant terminalBootstrapStart = fetch.successfulReads().stream()
                    .filter(read -> read.endpoint().kind() == EndpointKind.BOOTSTRAP_STATIC)
                    .map(read -> read.read().startedAt()).max(Instant::compareTo).orElseThrow();
            Instant terminalFixtureStart = fetch.successfulReads().stream()
                    .filter(read -> read.endpoint().kind() == EndpointKind.FIXTURES)
                    .map(read -> read.read().startedAt()).max(Instant::compareTo).orElseThrow();
            Instant terminalFinish = fetch.successfulReads().getLast().read().finishedAt();
            assertThat(terminalBootstrapStart).isAfter(latestHistoryFinish);
            assertThat(terminalFixtureStart).isAfter(terminalBootstrapStart);
            assertThat(terminalFinish).isAfter(terminalFixtureStart);
            Instant firstCanonicalStart = firstAttemptReads.stream()
                    .filter(read -> read.endpoint().kind() == EndpointKind.BOOTSTRAP_STATIC)
                    .map(read -> read.read().startedAt()).findFirst().orElseThrow();
            SourceRead finalizedInterval = jdbc.queryForObject("SELECT finalized_source_read_started_at,"
                    + "finalized_source_read_finished_at FROM " + TABLES.gameweekStatSync()
                    + " WHERE season_id=? AND gameweek=3", (rs, row) -> new SourceRead(
                            rs.getTimestamp(1).toInstant(), rs.getTimestamp(2).toInstant()), SEASON_ID);
            assertThat(finalizedInterval).isEqualTo(new SourceRead(firstCanonicalStart, terminalFinish));

            Map<String, List<Object>> finalizedRows = pfsRowsSnapshot(setup.fixtureIds());
            Map<String, List<Object>> finalizedProof = pfsProofSnapshot(setup.fixtureIds());
            Map<String, String> finalizedPageWatermarks = pageWatermarkSnapshot();
            Map<String, Object> finalityBeforeNoop = finalitySnapshot();
            Map<String, Long> writesBeforeNoop = unit6WriteCounts();
            fetch.clearCalls();
            clock.set(clock.instant().plusSeconds(1));
            CycleResult noOp = compatibilityCollector(true).runCycle();

            assertThat(noOp.state()).isEqualTo(State.COMPLETED);
            assertThat(noOp.selectedPlayerIds()).isEmpty();
            assertThat(fetch.count(EndpointKind.BOOTSTRAP_STATIC)).isOne();
            assertThat(fetch.count(EndpointKind.FIXTURES)).isOne();
            assertThat(fetch.count(EndpointKind.EVENT_LIVE)).isZero();
            assertThat(fetch.count(EndpointKind.ELEMENT_SUMMARY)).isZero();
            int noOpHistoryGets = fetch.count(EndpointKind.ELEMENT_SUMMARY);
            assertThat(compatibilityCatalogueWriteCounts()).isEqualTo(catalogueWritesAfterFirstCycle);
            assertThat(pfsRowsSnapshot(setup.fixtureIds())).isEqualTo(finalizedRows);
            assertThat(pfsProofSnapshot(setup.fixtureIds())).isEqualTo(finalizedProof);
            assertThat(pageWatermarkSnapshot()).isEqualTo(finalizedPageWatermarks);
            assertThat(finalitySnapshot()).isEqualTo(finalityBeforeNoop);
            Map<String, Long> writesAfterNoop = unit6WriteCounts();
            for (String key : List.of("player_fixture_stats/INSERT", "player_fixture_stats/UPDATE",
                    TABLES.playerFixtureEvidence() + "/INSERT", TABLES.playerFixtureEvidence() + "/UPDATE",
                    TABLES.playerFixtureCurrent() + "/INSERT", TABLES.playerFixtureCurrent() + "/UPDATE",
                    TABLES.gameweekStatSync() + "/UPDATE")) {
                assertThat(writesAfterNoop.getOrDefault(key, 0L))
                        .as("no-op does not rewrite %s", key).isEqualTo(writesBeforeNoop.getOrDefault(key, 0L));
            }
            assertThat(new GameweekFinalityReader(jdbc, clock, TABLES)
                    .read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                    .isEqualTo(GameweekFinality.State.FINALIZED);

            int correctedFixture = setup.fixtureIds().stream().min(Integer::compareTo).orElseThrow();
            int correctedPlayer = setup.participantsByFixture().get(correctedFixture).stream()
                    .min(Integer::compareTo).orElseThrow();
            byte[] correctedFixtures = incrementFixtureBps(setup.fixtureSource(), correctedFixture,
                    correctedPlayer);
            byte[] correctedPage = incrementHistoryBps(setup.historyPages().get(correctedPlayer), correctedFixture);
            fetch.setSources(setup.bootstrapSource(), correctedFixtures, setup.liveSource(), eventFourLive);
            fetch.setTerminalSources(setup.bootstrapSource(), correctedFixtures);
            fetch.setPlayerPage(correctedPlayer, correctedPage);
            Map<String, List<Object>> rowsBeforeCorrection = pfsRowsSnapshot(setup.fixtureIds());
            Map<String, List<Object>> proofBeforeCorrection = pfsProofSnapshot(setup.fixtureIds());
            Map<String, Long> writesBeforeCorrection = unit6WriteCounts();
            Map<String, Long> catalogueWritesBeforeCorrection = compatibilityCatalogueWriteCounts();
            fetch.clearCalls();
            clock.set(clock.instant().plusSeconds(1));
            CycleResult correction = compatibilityCollector(true).runCycle();

            assertThat(correction.state()).as("last error: %s", latestRunError()).isEqualTo(State.COMPLETED);
            Map<String, List<Object>> rowsAfterCorrection = pfsRowsSnapshot(setup.fixtureIds());
            assertThat(rowsAfterCorrection.keySet()).isEqualTo(rowsBeforeCorrection.keySet());
            for (String key : rowsBeforeCorrection.keySet()) {
                if (key.equals(correctedFixture + "/" + correctedPlayer)) continue;
                assertThat(rowsAfterCorrection.get(key)).as("unchanged row %s", key)
                        .isEqualTo(rowsBeforeCorrection.get(key));
                assertThat(pfsProofSnapshotForKey(setup.fixtureIds(), key))
                        .as("unchanged pointer/evidence/timestamp %s", key)
                        .isEqualTo(proofBeforeCorrection.get(key));
            }
            assertThat(rowsAfterCorrection.get(correctedFixture + "/" + correctedPlayer))
                    .isNotEqualTo(rowsBeforeCorrection.get(correctedFixture + "/" + correctedPlayer));
            assertThat(pfsProofSnapshotForKey(setup.fixtureIds(), correctedFixture + "/" + correctedPlayer))
                    .isNotEqualTo(proofBeforeCorrection.get(correctedFixture + "/" + correctedPlayer));
            Map<String, Long> writesAfterCorrection = unit6WriteCounts();
            assertThat(writesAfterCorrection.getOrDefault("player_fixture_stats/UPDATE", 0L)
                    - writesBeforeCorrection.getOrDefault("player_fixture_stats/UPDATE", 0L)).isOne();
            assertThat(writesAfterCorrection.getOrDefault("player_fixture_stats/INSERT", 0L)
                    - writesBeforeCorrection.getOrDefault("player_fixture_stats/INSERT", 0L)).isZero();
            Map<String, Long> catalogueWritesAfterCorrection = compatibilityCatalogueWriteCounts();
            assertThat(catalogueWriteDelta(catalogueWritesBeforeCorrection, catalogueWritesAfterCorrection))
                    .containsExactlyInAnyOrderEntriesOf(Map.of(
                            "team/INSERT", 0L, "team/UPDATE", 0L,
                            "player/INSERT", 0L, "player/UPDATE", 0L,
                            "gameweek/INSERT", 0L, "gameweek/UPDATE", 0L,
                            "fixture/INSERT", 0L, "fixture/UPDATE", 1L));
            GameweekFinalityReader reader = new GameweekFinalityReader(jdbc, clock, TABLES);
            assertThat(reader.read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                    .isEqualTo(GameweekFinality.State.FINALIZED);

            Map<String, List<Object>> correctedRows = pfsRowsSnapshot(setup.fixtureIds());
            Map<String, List<Object>> correctedProof = pfsProofSnapshot(setup.fixtureIds());
            Map<String, Object> correctedFinality = finalitySnapshot();
            Map<String, Long> writesBeforeCorrectionNoop = unit6WriteCounts();
            fetch.clearCalls();
            clock.set(clock.instant().plusSeconds(1));
            CycleResult correctionNoop = compatibilityCollector(true).runCycle();
            assertThat(correctionNoop.state()).isEqualTo(State.COMPLETED);
            assertThat(correctionNoop.selectedPlayerIds()).isEmpty();
            assertThat(fetch.count(EndpointKind.BOOTSTRAP_STATIC)).isOne();
            assertThat(fetch.count(EndpointKind.FIXTURES)).isOne();
            assertThat(fetch.count(EndpointKind.EVENT_LIVE)).isZero();
            assertThat(fetch.count(EndpointKind.ELEMENT_SUMMARY)).isZero();
            assertThat(pfsRowsSnapshot(setup.fixtureIds())).isEqualTo(correctedRows);
            assertThat(pfsProofSnapshot(setup.fixtureIds())).isEqualTo(correctedProof);
            assertThat(finalitySnapshot()).isEqualTo(correctedFinality);
            assertThat(reader.read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                    .isEqualTo(GameweekFinality.State.FINALIZED);
            Map<String, Long> writesAfterCorrectionNoop = unit6WriteCounts();
            for (String key : List.of("player_fixture_stats/INSERT", "player_fixture_stats/UPDATE",
                    TABLES.playerFixtureEvidence() + "/INSERT", TABLES.playerFixtureEvidence() + "/UPDATE",
                    TABLES.playerFixtureCurrent() + "/INSERT", TABLES.playerFixtureCurrent() + "/UPDATE",
                    TABLES.gameweekStatSync() + "/UPDATE")) {
                assertThat(writesAfterCorrectionNoop.getOrDefault(key, 0L))
                        .as("corrected no-op does not rewrite %s", key)
                        .isEqualTo(writesBeforeCorrectionNoop.getOrDefault(key, 0L));
            }
            assertThat(compatibilityCatalogueWriteCounts()).isEqualTo(catalogueWritesAfterCorrection);

            System.out.printf("UNIT6_MULTI_FIXTURE_SYNTHETIC gameweek=3 fixtures=%d clubs=20 cataloguePlayers=%d "
                            + "sourceParticipants=%d initialHistoryGets=%d resumeHistoryGets=%d "
                            + "sourceOnlyRetries=%d noopHistoryGets=%d correctionPfsUpdates=%d "
                            + "catalogueWritesFirst=%s catalogueWritesCorrection=%s readerFinalizedAfterCorrectionAndNoop=%s%n",
                    setup.fixtureIds().size(), catalogueSize, setup.participantIds().size(), initialHistoryGets,
                    resumeHistoryGets, sourceOnlyRetries, noOpHistoryGets,
                    writesAfterCorrection.getOrDefault("player_fixture_stats/UPDATE", 0L)
                            - writesBeforeCorrection.getOrDefault("player_fixture_stats/UPDATE", 0L),
                    catalogueWritesAfterFirstCycle, catalogueWriteDelta(catalogueWritesBeforeCorrection,
                            catalogueWritesAfterCorrection), reader.read(SEASON_ID, season.code(), Set.of(3))
                            .get(3).state());
        } finally {
            removeUnit6WriteCounters();
        }
    }

    @Test
    void checkedDoubleFixtureGameweekFinalizesOverlappingPlayersOnceWithFixtureScopedRows() throws Exception {
        MultiFixtureSetup setup = checkedDoubleFixtureSetup();
        publishCompatibilitySnapshot(setup);
        assertThat(setup.fixtureIds()).containsExactlyInAnyOrder(29, 36);
        assertThat(setup.participantIds()).containsExactlyInAnyOrder(1, 4, 28, 40, 544, 545);
        assertThat(setup.participantsByFixture()).containsEntry(29, Set.of(1, 4, 28, 40))
                .containsEntry(36, Set.of(1, 4, 544, 545));
        for (int repeatedPlayer : List.of(1, 4)) {
            List<Integer> historyFixtureIds = new ArrayList<>();
            for (JsonNode row : JSON.readTree(setup.historyPages().get(repeatedPlayer)).path("history")) {
                historyFixtureIds.add(row.path("fixture").asInt());
                assertThat(row.path("round").asInt()).isEqualTo(3);
            }
            assertThat(historyFixtureIds).containsExactlyInAnyOrder(29, 36);
        }

        CycleResult result = collector(true).runCycle();

        assertThat(result.state()).isEqualTo(State.COMPLETED);
        assertThat(result.finalPendingGameweeks()).containsExactly(3);
        assertThat(result.committedPlayerIds()).containsExactlyInAnyOrderElementsOf(setup.participantIds());
        assertThat(fetch.count(EndpointKind.EVENT_LIVE)).isOne();
        assertThat(fetch.count(EndpointKind.ELEMENT_SUMMARY)).isEqualTo(setup.participantIds().size());
        assertThat(fetch.playerIds()).containsExactlyInAnyOrderElementsOf(setup.participantIds());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM player_fixture_stats WHERE season_id=?"
                + " AND fixture_id IN (29,36)", Integer.class, SEASON_ID)).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.playerFixtureEvidence()
                + " WHERE season_id=? AND fixture_id IN (29,36) AND (component_presence & ?) = ?",
                Integer.class, SEASON_ID, HistoryParser.Component.allMask(),
                HistoryParser.Component.allMask())).isEqualTo(8);
        assertThat(jdbc.queryForList("SELECT count(*) FROM player_fixture_stats WHERE season_id=?"
                + " AND fixture_id IN (29,36) AND player_id IN (1,4) GROUP BY player_id",
                Integer.class, SEASON_ID)).containsExactly(2, 2);
        Map<String, List<Object>> repeatedPlayerRows = jdbc.query("SELECT fixture_id,player_id,bps,gameweek,"
                + "player_team_id,opponent_team_id,was_home,kickoff_time FROM player_fixture_stats "
                + "WHERE season_id=? AND fixture_id IN (29,36) AND player_id IN (1,4) "
                + "ORDER BY player_id,fixture_id", rs -> {
                    Map<String, List<Object>> rows = new java.util.TreeMap<>();
                    while (rs.next()) rows.put(rs.getInt("fixture_id") + "/" + rs.getInt("player_id"),
                            List.of(rs.getInt("bps"), rs.getInt("gameweek"), rs.getInt("player_team_id"),
                                    rs.getInt("opponent_team_id"), rs.getBoolean("was_home"),
                                    rs.getTimestamp("kickoff_time").toInstant()));
                    return Map.copyOf(rows);
                }, SEASON_ID);
        assertThat(repeatedPlayerRows).containsExactlyInAnyOrderEntriesOf(Map.of(
                "29/1", List.of(15, 3, 1, 6, true, Instant.parse("2026-09-06T15:30:00Z")),
                "36/1", List.of(38, 3, 1, 20, false, Instant.parse("2026-09-07T17:00:00Z")),
                "29/4", List.of(10, 3, 1, 6, true, Instant.parse("2026-09-06T15:30:00Z")),
                "36/4", List.of(30, 3, 1, 20, false, Instant.parse("2026-09-07T17:00:00Z"))));
        assertThat(new GameweekFinalityReader(jdbc, clock, TABLES)
                .read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                .isEqualTo(GameweekFinality.State.FINALIZED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.gameweekStatSync()
                + " WHERE season_id=? AND gameweek=3 AND state='FINALIZED'", Integer.class, SEASON_ID)).isOne();
    }

    @Test
    void sameGenerationLiveRefreshForRetryKeepsCompletedFixtureProofImmutable() throws Exception {
        PairCapture capture = pairCapture();
        SharedStatsSeed.seedOwnedSyncFixtureIdentity(jdbc, SEASON_ID, capture.teams(),
                List.of(capture.fixtureOne(), capture.fixtureTwo()));
        fetch.setSources(capture.bootstrap(), capture.fixtures(), capture.live(), eventFourLive);
        capture.historyPages().forEach(fetch::setPlayerPage);
        fetch.failPlayerCalls(82, 1);
        fetch.failPlayer(501);

        CycleResult first = collector(true).runCycle();

        assertThat(first.state()).isEqualTo(State.RETRY_SCHEDULED);
        assertThat(first.selectedPlayerIds()).containsExactly(82, 259, 350, 501);
        assertThat(first.committedPlayerIds()).containsExactly(259, 350);
        assertThat(first.failedPlayerIds()).containsExactly(82, 501);
        assertThat(fetch.count(EndpointKind.EVENT_LIVE)).isEqualTo(1);
        PfsReadSource reader = collectorPfsReadSource();
        Map<PfsReadSource.FixtureSide, PfsReadSource.SidePopulation> partial = doubleFixturePopulation(reader);
        assertThat(partial.get(new PfsReadSource.FixtureSide(DOUBLE_FIXTURE_ONE, DOUBLE_HOME_ONE)).complete())
                .isTrue();
        assertThat(partial.get(new PfsReadSource.FixtureSide(DOUBLE_FIXTURE_ONE, DOUBLE_AWAY_ONE)).complete())
                .isTrue();
        assertThat(partial.get(new PfsReadSource.FixtureSide(DOUBLE_FIXTURE_TWO, DOUBLE_AWAY_TWO)))
                .satisfies(side -> {
                    assertThat(side.sourceRosterComplete()).isTrue();
                    assertThat(side.expectedPositive()).isEqualTo(1);
                    assertThat(side.matchedPositive()).isZero();
                    assertThat(side.complete()).isFalse();
                });
        String fixtureOneSnapshotKey = jdbc.queryForObject("SELECT publication_key FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key LIKE ?", String.class, season.code(),
                IncrementalAppearanceReceipt.SNAPSHOT_PREFIX + DOUBLE_FIXTURE_ONE + ":%");
        Map<String, Object> firstFixtureProof = appearanceReceipt(fixtureOneSnapshotKey);
        String fixtureOneRevisionKey = IncrementalAppearanceReceipt.sourceFixtureKey(DOUBLE_FIXTURE_ONE);
        Map<String, Object> firstFixtureRevision = appearanceReceipt(fixtureOneRevisionKey);
        BigDecimal firstFixtureXg = collectorFixtureXg(reader, DOUBLE_FIXTURE_ONE);
        assertThat(firstFixtureXg).isEqualByComparingTo("1.00");
        assertThat(collectorFixtureXg(reader, DOUBLE_FIXTURE_TWO)).isNull();

        fetch.clearCalls();
        fetch.clearPageOutcomes();
        clock.set(first.nextDue().plusSeconds(1));
        CycleResult resumed = collector(true).runCycle();

        assertThat(resumed.state()).as("resume result %s; requests %s", resumed, fetch.calls())
                .isEqualTo(State.COMPLETED);
        assertThat(resumed.selectedPlayerIds()).containsExactly(82, 501);
        assertThat(fetch.playerIds()).containsExactly(82, 501);
        assertThat(fetch.count(EndpointKind.EVENT_LIVE)).isEqualTo(1);
        Map<PfsReadSource.FixtureSide, PfsReadSource.SidePopulation> complete = doubleFixturePopulation(reader);
        assertThat(complete.get(new PfsReadSource.FixtureSide(DOUBLE_FIXTURE_ONE, DOUBLE_HOME_ONE)).complete())
                .isTrue();
        assertThat(complete.get(new PfsReadSource.FixtureSide(DOUBLE_FIXTURE_ONE, DOUBLE_AWAY_ONE)).complete())
                .isTrue();
        assertThat(complete.get(new PfsReadSource.FixtureSide(DOUBLE_FIXTURE_TWO, DOUBLE_HOME_TWO)).complete())
                .as("fixture 2 home: %s", complete.get(
                        new PfsReadSource.FixtureSide(DOUBLE_FIXTURE_TWO, DOUBLE_HOME_TWO)))
                .isTrue();
        assertThat(complete.get(new PfsReadSource.FixtureSide(DOUBLE_FIXTURE_TWO, DOUBLE_AWAY_TWO)).complete())
                .isTrue();
        assertThat(appearanceReceipt(fixtureOneSnapshotKey)).isEqualTo(firstFixtureProof);
        assertThat(appearanceReceipt(fixtureOneRevisionKey)).isEqualTo(firstFixtureRevision);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key LIKE ?", Integer.class, season.code(),
                IncrementalAppearanceReceipt.SNAPSHOT_PREFIX + DOUBLE_FIXTURE_TWO + ":%")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key LIKE ?", Integer.class, season.code(),
                IncrementalAppearanceReceipt.LIVE_EVENT_SOURCE_PREFIX + "3:generation:%")).isEqualTo(2);
        assertThat(collectorFixtureXg(reader, DOUBLE_FIXTURE_ONE)).isEqualByComparingTo(firstFixtureXg);
        assertThat(collectorFixtureXg(reader, DOUBLE_FIXTURE_TWO)).isEqualByComparingTo("1.00");
    }

    @Test
    void sameFixtureRetryKeepsEarlierSameSidePagesEligibleAcrossUnchangedLiveRefresh() throws Exception {
        SingleFixtureCapture capture = sameFixtureCapture();
        SharedStatsSeed.seedOwnedSyncFixtureIdentity(jdbc, SEASON_ID, capture.teams(), List.of(capture.fixture()));
        fetch.setSources(capture.bootstrap(), capture.fixtures(), capture.live(), eventFourLive);
        capture.historyPages().forEach(fetch::setPlayerPage);
        fetch.failPlayer(82);

        CycleResult first = collector(true).runCycle();

        assertThat(first.state()).isEqualTo(State.RETRY_SCHEDULED);
        assertThat(first.selectedPlayerIds()).containsExactly(82, 259, 350);
        assertThat(first.committedPlayerIds()).containsExactly(259, 350);
        assertThat(first.failedPlayerIds()).containsExactly(82);
        assertThat(first.finalPendingGameweeks()).isEmpty();
        assertThat(fetch.count(EndpointKind.EVENT_LIVE)).isEqualTo(1);
        PfsReadSource reader = collectorPfsReadSource();
        Map<PfsReadSource.FixtureSide, PfsReadSource.SidePopulation> partial = reader.fixtureSidePopulation(
                SEASON_ID, season.code(), "fixture", "season_id", SEASON_ID,
                Set.of(DOUBLE_FIXTURE_ONE), clock.instant());
        assertThat(partial.get(new PfsReadSource.FixtureSide(DOUBLE_FIXTURE_ONE, DOUBLE_HOME_ONE)))
                .satisfies(side -> {
                    assertThat(side.sourceRosterComplete()).isTrue();
                    assertThat(side.expectedPositive()).isEqualTo(2);
                    assertThat(side.matchedPositive()).isEqualTo(1);
                    assertThat(side.complete()).isFalse();
                });
        assertThat(partial.get(new PfsReadSource.FixtureSide(DOUBLE_FIXTURE_ONE, DOUBLE_AWAY_ONE)).complete())
                .isTrue();
        Map<String, Object> playerAProof = playerPageProof(DOUBLE_FIXTURE_ONE, 259);
        Map<String, Object> playerAPage = pageWatermark(259);
        int playerAEvidenceCount = playerEvidenceCount(DOUBLE_FIXTURE_ONE, 259);
        String firstSnapshotKey = jdbc.queryForObject("SELECT publication_key FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key LIKE ? ORDER BY source_read_started_at LIMIT 1",
                String.class, season.code(), IncrementalAppearanceReceipt.SNAPSHOT_PREFIX
                        + DOUBLE_FIXTURE_ONE + ":%");
        Map<String, Object> firstSnapshot = appearanceReceipt(firstSnapshotKey);

        fetch.clearCalls();
        fetch.clearPageOutcomes();
        clock.set(first.nextDue().plusSeconds(1));
        CycleResult resumed = collector(true).runCycle();

        assertThat(resumed.state()).as("resume result %s; requests %s", resumed, fetch.calls())
                .isEqualTo(State.COMPLETED);
        assertThat(resumed.selectedPlayerIds()).containsExactly(82);
        assertThat(fetch.playerIds()).containsExactly(82);
        assertThat(fetch.count(EndpointKind.EVENT_LIVE)).isEqualTo(1);
        assertThat(resumed.finalPendingGameweeks()).isEmpty();
        Map<PfsReadSource.FixtureSide, PfsReadSource.SidePopulation> complete = reader.fixtureSidePopulation(
                SEASON_ID, season.code(), "fixture", "season_id", SEASON_ID,
                Set.of(DOUBLE_FIXTURE_ONE), clock.instant());
        assertThat(complete.get(new PfsReadSource.FixtureSide(DOUBLE_FIXTURE_ONE, DOUBLE_HOME_ONE)).complete())
                .isTrue();
        assertThat(complete.get(new PfsReadSource.FixtureSide(DOUBLE_FIXTURE_ONE, DOUBLE_AWAY_ONE)).complete())
                .isTrue();
        assertThat(playerPageProof(DOUBLE_FIXTURE_ONE, 259)).isEqualTo(playerAProof);
        assertThat(pageWatermark(259)).isEqualTo(playerAPage);
        assertThat(playerEvidenceCount(DOUBLE_FIXTURE_ONE, 259)).isEqualTo(playerAEvidenceCount);
        assertThat(appearanceReceipt(firstSnapshotKey)).isEqualTo(firstSnapshot);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key LIKE ?", Integer.class, season.code(),
                IncrementalAppearanceReceipt.SNAPSHOT_PREFIX + DOUBLE_FIXTURE_ONE + ":%")).isEqualTo(2);

        fetch.clearCalls();
        fetch.clearPageOutcomes();
        fetch.setSources(capture.bootstrap(), capture.fixtures(), sameFixtureLive(80), eventFourLive);
        clock.set(clock.instant().plusSeconds(10));
        jdbc.update("DELETE FROM " + TABLES.receipt() + " WHERE season=? AND publication_key=?",
                season.code(), IncrementalPlayerFixturePublisher.pageReceiptKey(82));
        CycleResult changedMinutes = collector(true).runCycle();

        assertThat(changedMinutes.state()).isEqualTo(State.COMPLETED);
        assertThat(changedMinutes.selectedPlayerIds()).containsExactly(82, 259, 350);
        assertThat(fetch.playerIds()).containsExactly(82, 259, 350);
        assertThat(fetch.count(EndpointKind.EVENT_LIVE)).isEqualTo(1);
        Map<PfsReadSource.FixtureSide, PfsReadSource.SidePopulation> changedPopulation =
                reader.fixtureSidePopulation(SEASON_ID, season.code(), "fixture", "season_id", SEASON_ID,
                        Set.of(DOUBLE_FIXTURE_ONE), clock.instant());
        assertThat(changedPopulation.get(new PfsReadSource.FixtureSide(
                DOUBLE_FIXTURE_ONE, DOUBLE_HOME_ONE))).satisfies(side -> {
                    assertThat(side.sourceRosterComplete()).isTrue();
                    assertThat(side.expectedPositive()).isEqualTo(2);
                    assertThat(side.matchedPositive()).isEqualTo(1);
                    assertThat(side.complete()).isFalse();
                });
        assertThat(playerPageProof(DOUBLE_FIXTURE_ONE, 259)).isEqualTo(playerAProof);
        Map<String, Object> newestSnapshot = jdbc.queryForMap("SELECT source_read_finished_at FROM "
                + TABLES.receipt() + " WHERE season=? AND publication_key LIKE ?"
                + " ORDER BY source_read_started_at DESC LIMIT 1", season.code(),
                IncrementalAppearanceReceipt.SNAPSHOT_PREFIX + DOUBLE_FIXTURE_ONE + ":%");
        assertThat(((Timestamp) pageWatermark(259).get("source_read_started_at")).toInstant())
                .isAfterOrEqualTo(((Timestamp) newestSnapshot.get("source_read_finished_at")).toInstant());
    }

    @Test
    void appearanceRestartKeepsOtherEventsLiveFailureAndClearsCapacitySilencing() throws Exception {
        assertAppearanceRestartCapacityAndFailure(true);
    }

    @Test
    void appearanceRestartWithoutSourceFailureKeepsCapacityWaitNonPaging() throws Exception {
        assertAppearanceRestartCapacityAndFailure(false);
    }

    private void assertAppearanceRestartCapacityAndFailure(boolean liveFailure) throws Exception {
        SingleFixtureCapture capture = sameFixtureCapture();
        byte[] source = appendUncheckedFixtureParticipant(capture.fixtures(), fixtures, 38, 600);
        fetch.setSources(capture.bootstrap(), source, capture.live(),
                liveForFixturePlayers(eventFourLive, 38, Set.of(600)));
        capture.historyPages().forEach(fetch::setPlayerPage);
        var collector = compatibilityCollector(true);
        fetch.blockedPurpose = OwnedSyncRequestBudget.Purpose.DISCOVERY;
        var capacityWait = collector.runCycle();
        assertThat(capacityWait.state()).isEqualTo(State.RETRY_SCHEDULED);
        assertThat(capacityReceipts()).isOne();
        clock.set(capacityWait.nextDue());
        fetch.blockedPurpose = null;
        fetch.deferOnPlayerCall(2, clock.instant().plus(Duration.ofHours(8)));
        var partial = collector.runCycle();
        assertThat(partial.state()).isEqualTo(State.RETRY_SCHEDULED);
        assertThat(partial.committedPlayerIds()).containsExactly(82);
        assertThat(partial.failedPlayerIds()).isEmpty();
        assertThat(capacityReceipts()).isOne();
        long previous = ((Number) pageWatermark(82).get("latest_generation")).longValue();

        fetch.clearCalls();
        fetch.clearPageOutcomes();
        fetch.setSources(capture.bootstrap(), source, sameFixtureLive(80), liveFailure
                ? "{invalid event-live".getBytes(java.nio.charset.StandardCharsets.UTF_8)
                : liveForFixturePlayers(eventFourLive, 38, Set.of(600)));
        clock.set(partial.nextDue().plusSeconds(1));
        var restarted = collector.runCycle();
        assertThat(restarted.state()).isEqualTo(State.RETRY_SCHEDULED);
        assertThat(fetch.playerIds()).as("appearance correction still fences old pages").isEmpty();
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.run()
                + " WHERE generation=?", String.class, previous)).isEqualTo("SUPERSEDED");
        String error = jdbc.queryForObject("SELECT last_error FROM " + TABLES.run()
                + " WHERE state='PENDING' ORDER BY generation DESC LIMIT 1", String.class);
        if (liveFailure) assertThat(error).contains("Unresolved event-live sources: 1");
        else assertThat(error).isNull();
        assertThat(jdbc.queryForObject("SELECT consecutive_fetch_failures FROM " + TABLES.run()
                + " WHERE state='PENDING' ORDER BY generation DESC LIMIT 1", Integer.class))
                .isEqualTo(liveFailure ? 1 : 0);
        assertThat(capacityReceipts()).isEqualTo(liveFailure ? 0 : 1);
    }

    @Test
    void changedLiveMinutesOnPartialRetryRestartsAndRevalidatesEveryPage() throws Exception {
        SingleFixtureCapture capture = sameFixtureCapture();
        int fixtureId = capture.fixture().id();
        int playerA = 259;
        int playerB = 82;
        int playerC = 350;
        SharedStatsSeed.seedOwnedSyncFixtureIdentity(jdbc, SEASON_ID, capture.teams(), List.of(capture.fixture()));
        fetch.setSources(capture.bootstrap(), capture.fixtures(), capture.live(), eventFourLive);
        capture.historyPages().forEach(fetch::setPlayerPage);
        fetch.failPlayer(playerB);
        CoalescedPlayerHistoryCollector collector = compatibilityCollector(true);

        CycleResult partial = collector.runCycle();

        assertThat(partial.state()).isEqualTo(State.RETRY_SCHEDULED);
        assertThat(partial.selectedPlayerIds()).containsExactly(playerB, playerA, playerC);
        assertThat(partial.committedPlayerIds()).containsExactly(playerA, playerC);
        assertThat(partial.failedPlayerIds()).containsExactly(playerB);
        long firstGeneration = ((Number) pageWatermark(playerA).get("latest_generation")).longValue();
        assertThat(((Number) pageWatermark(playerC).get("latest_generation")).longValue())
                .isEqualTo(firstGeneration);
        String initialFingerprint = jdbc.queryForObject("SELECT source_pair_fingerprint FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", String.class, season.code(),
                IncrementalAppearanceReceipt.appearanceStateKey(fixtureId));
        Instant firstAppearanceFloor = jdbc.queryForObject("SELECT source_read_finished_at FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", Timestamp.class, season.code(),
                IncrementalAppearanceReceipt.appearanceStateKey(fixtureId)).toInstant();
        assertThat(firstAppearanceFloor).isEqualTo(fetch.successfulReads().stream()
                .filter(read -> read.endpoint().kind() == EndpointKind.EVENT_LIVE)
                .map(read -> read.read().finishedAt()).findFirst().orElseThrow());
        Map<String, Object> sourceRevisionBefore = appearanceReceipt(
                IncrementalAppearanceReceipt.sourceFixtureKey(fixtureId));

        byte[] revisedLive = sameFixtureLive(80);
        byte[] revisedPlayerBPage = historyWithMinutes(capture.historyPages().get(playerB), fixtureId, playerB, 80);
        fetch.clearCalls();
        fetch.clearPageOutcomes();
        fetch.setSources(capture.bootstrap(), capture.fixtures(), revisedLive, eventFourLive);
        fetch.setPlayerPage(playerB, revisedPlayerBPage);
        clock.set(partial.nextDue().plusSeconds(1));
        CycleResult restarted = collector.runCycle();

        assertThat(restarted.state()).isEqualTo(State.RETRY_SCHEDULED);
        assertThat(restarted.failedPlayerIds()).isEmpty();
        assertThat(fetch.playerIds()).as("stale same-generation pages are never republished").isEmpty();
        assertThat(fetch.count(EndpointKind.EVENT_LIVE)).isOne();
        long advancedGeneration = jdbc.queryForObject("SELECT generation FROM " + TABLES.run()
                + " WHERE producer_key=? AND season=? AND state='PENDING' ORDER BY generation DESC LIMIT 1",
                Long.class, OwnedSyncStore.INCREMENTAL_CORE_PRODUCER, season.code());
        assertThat(advancedGeneration).isGreaterThan(firstGeneration);
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.run() + " WHERE generation=?",
                String.class, firstGeneration)).isEqualTo("SUPERSEDED");
        Instant revisedAppearanceFloor = jdbc.queryForObject("SELECT source_read_finished_at FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", Timestamp.class, season.code(),
                IncrementalAppearanceReceipt.appearanceStateKey(fixtureId)).toInstant();
        String revisedFingerprint = jdbc.queryForObject("SELECT source_pair_fingerprint FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", String.class, season.code(),
                IncrementalAppearanceReceipt.appearanceStateKey(fixtureId));
        assertThat(revisedAppearanceFloor).isAfter(firstAppearanceFloor);
        assertThat(revisedAppearanceFloor).isEqualTo(fetch.successfulReads().stream()
                .filter(read -> read.endpoint().kind() == EndpointKind.EVENT_LIVE)
                .map(read -> read.read().finishedAt()).findFirst().orElseThrow());
        assertThat(revisedFingerprint).isNotEqualTo(initialFingerprint);
        assertThat(appearanceReceipt(IncrementalAppearanceReceipt.sourceFixtureKey(fixtureId)))
                .isEqualTo(sourceRevisionBefore);
        Map<PfsReadSource.FixtureSide, PfsReadSource.SidePopulation> invalidated =
                collectorPfsReadSource().fixtureSidePopulation(SEASON_ID, season.code(), "fixture", "season_id",
                        SEASON_ID, Set.of(fixtureId), clock.instant());
        assertThat(invalidated.get(new PfsReadSource.FixtureSide(fixtureId, DOUBLE_HOME_ONE)))
                .satisfies(side -> {
                    assertThat(side.sourceRosterComplete()).isTrue();
                    assertThat(side.expectedPositive()).isEqualTo(2);
                    assertThat(side.matchedPositive()).isZero();
                    assertThat(side.complete()).isFalse();
                });
        assertThat(invalidated.get(new PfsReadSource.FixtureSide(fixtureId, DOUBLE_AWAY_ONE)))
                .satisfies(side -> {
                    assertThat(side.sourceRosterComplete()).isTrue();
                    assertThat(side.expectedPositive()).isEqualTo(1);
                    assertThat(side.matchedPositive()).isZero();
                    assertThat(side.complete()).isFalse();
                });

        Map<String, List<Object>> rowsBeforeRevalidation = pfsRowsSnapshot(Set.of(fixtureId));
        Map<String, List<Object>> proofBeforeRevalidation = pfsProofSnapshot(Set.of(fixtureId));
        Map<String, Object> playerAProof = playerPageProof(fixtureId, playerA);
        Map<String, Object> playerCProof = playerPageProof(fixtureId, playerC);
        int playerAEvidenceCount = playerEvidenceCount(fixtureId, playerA);
        int playerCEvidenceCount = playerEvidenceCount(fixtureId, playerC);
        fetch.clearCalls();
        clock.set(restarted.nextDue().plusSeconds(1));
        CycleResult recovered = collector.runCycle();

        assertThat(recovered.state()).as("cycle %s; error %s", recovered, latestRunError())
                .isEqualTo(State.COMPLETED);
        assertThat(recovered.selectedPlayerIds()).containsExactly(playerB, playerA, playerC);
        assertThat(recovered.committedPlayerIds()).containsExactly(playerB, playerA, playerC);
        assertThat(fetch.playerIds()).containsExactly(playerB, playerA, playerC);
        Map<String, Object> newRun = jdbc.queryForMap("SELECT generation,requested_source_after FROM "
                + TABLES.run() + " WHERE generation=?", advancedGeneration);
        Instant runFloor = ((Timestamp) newRun.get("requested_source_after")).toInstant();
        assertThat(fetch.requests().stream().filter(request -> request.endpoint().kind() == EndpointKind.ELEMENT_SUMMARY))
                .allSatisfy(request -> assertThat(request.floor()).isAfterOrEqualTo(revisedAppearanceFloor)
                        .isAfterOrEqualTo(runFloor));
        assertThat(fetch.successfulReads().stream()
                .filter(read -> read.endpoint().kind() == EndpointKind.ELEMENT_SUMMARY))
                .allSatisfy(read -> assertThat(read.read().startedAt()).isAfterOrEqualTo(revisedAppearanceFloor));
        assertThat(jdbc.queryForObject("SELECT minutes FROM player_fixture_stats WHERE season_id=?"
                + " AND fixture_id=? AND player_id=?", Integer.class, SEASON_ID, fixtureId, playerB)).isEqualTo(80);
        Map<String, List<Object>> rowsAfterRevalidation = pfsRowsSnapshot(Set.of(fixtureId));
        Map<String, List<Object>> proofAfterRevalidation = pfsProofSnapshot(Set.of(fixtureId));
        assertThat(rowsAfterRevalidation.get(fixtureId + "/" + playerA))
                .isEqualTo(rowsBeforeRevalidation.get(fixtureId + "/" + playerA));
        assertThat(rowsAfterRevalidation.get(fixtureId + "/" + playerC))
                .isEqualTo(rowsBeforeRevalidation.get(fixtureId + "/" + playerC));
        assertThat(proofAfterRevalidation.get(fixtureId + "/" + playerA))
                .isEqualTo(proofBeforeRevalidation.get(fixtureId + "/" + playerA));
        assertThat(proofAfterRevalidation.get(fixtureId + "/" + playerC))
                .isEqualTo(proofBeforeRevalidation.get(fixtureId + "/" + playerC));
        assertThat(pageWatermark(playerA).get("latest_generation")).isEqualTo(advancedGeneration);
        assertThat(pageWatermark(playerC).get("latest_generation")).isEqualTo(advancedGeneration);
        assertThat(playerPageProof(fixtureId, playerA)).isEqualTo(playerAProof);
        assertThat(playerPageProof(fixtureId, playerC)).isEqualTo(playerCProof);
        assertThat(playerEvidenceCount(fixtureId, playerA)).isEqualTo(playerAEvidenceCount);
        assertThat(playerEvidenceCount(fixtureId, playerC)).isEqualTo(playerCEvidenceCount);

        PfsReadSource reader = collectorPfsReadSource();
        Map<PfsReadSource.FixtureSide, PfsReadSource.SidePopulation> complete = reader.fixtureSidePopulation(
                SEASON_ID, season.code(), "fixture", "season_id", SEASON_ID, Set.of(fixtureId), clock.instant());
        assertThat(complete.get(new PfsReadSource.FixtureSide(fixtureId, DOUBLE_HOME_ONE)).complete()).isTrue();
        assertThat(complete.get(new PfsReadSource.FixtureSide(fixtureId, DOUBLE_AWAY_ONE)).complete()).isTrue();
        assertThat(jdbc.queryForObject("SELECT data_checked FROM " + TABLES.gameweekStatSync()
                + " WHERE season_id=? AND gameweek=?", Boolean.class, SEASON_ID,
                capture.fixture().gameweek())).isFalse();

        fetch.clearCalls();
        clock.set(clock.instant().plusSeconds(10));
        CycleResult noop = collector.runCycle();
        assertThat(noop.state()).isEqualTo(State.COMPLETED);
        assertThat(noop.selectedPlayerIds()).isEmpty();
        assertThat(fetch.count(EndpointKind.EVENT_LIVE)).isZero();
        assertThat(fetch.count(EndpointKind.ELEMENT_SUMMARY)).isZero();
        assertThat(reader.fixtureSidePopulation(SEASON_ID, season.code(), "fixture", "season_id", SEASON_ID,
                Set.of(fixtureId), clock.instant()).get(new PfsReadSource.FixtureSide(
                        fixtureId, DOUBLE_HOME_ONE)).complete()).isTrue();
        assertThat(jdbc.queryForObject("SELECT data_checked FROM " + TABLES.gameweekStatSync()
                + " WHERE season_id=? AND gameweek=?", Boolean.class, SEASON_ID,
                capture.fixture().gameweek())).isFalse();
    }

    @Test
    void checkedMixedFixtureAppearanceRevisionInvalidatesCheckpointAndRefreshesAllPages() throws Exception {
        MultiFixtureSetup setup = checkedDoubleFixtureSetup();
        int changedFixture = 36;
        int failedPlayer = 545;
        publishCompatibilitySnapshot(setup);
        fetch.failPlayer(failedPlayer);

        CycleResult partial = collector(true).runCycle();

        assertThat(partial.state()).isEqualTo(State.RETRY_SCHEDULED);
        assertThat(partial.finalPendingGameweeks()).containsExactly(3);
        assertThat(partial.failedPlayerIds()).containsExactly(failedPlayer);
        assertThat(partial.committedPlayerIds()).containsExactlyInAnyOrderElementsOf(
                setup.participantIds().stream().filter(id -> id != failedPlayer).toList());
        long firstGeneration = ((Number) pageWatermark(1).get("latest_generation")).longValue();
        assertThat(setup.participantIds()).contains(1, 4, 28, 40, 544, failedPlayer);

        fetch.clearCalls();
        fetch.clearPageOutcomes();
        fetch.failPlayer(failedPlayer);
        clock.set(partial.nextDue().plusSeconds(1));
        CycleResult unchangedPartial = collector(true).runCycle();

        assertThat(unchangedPartial.state()).isEqualTo(State.RETRY_SCHEDULED);
        assertThat(fetch.playerIds()).containsExactly(failedPlayer);
        assertThat(((Number) pageWatermark(1).get("latest_generation")).longValue()).isEqualTo(firstGeneration);
        assertThat(new GameweekFinalityReader(jdbc, clock, TABLES)
                .read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                .isEqualTo(GameweekFinality.State.FINAL_PENDING);

        int originalMinutes = fixtureSpecificLiveMinutes(setup.liveSource(), changedFixture, failedPlayer);
        int revisedMinutes = originalMinutes == 45 ? 46 : 45;
        byte[] revisedLive = changeFixtureLiveMinutes(setup.liveSource(), changedFixture, failedPlayer,
                revisedMinutes);
        byte[] revisedPage = historyWithMinutes(setup.historyPages().get(failedPlayer), changedFixture,
                failedPlayer, revisedMinutes);
        fetch.clearCalls();
        fetch.clearPageOutcomes();
        fetch.setSources(setup.bootstrapSource(), setup.fixtureSource(), revisedLive, eventFourLive);
        fetch.setPlayerPage(failedPlayer, revisedPage);
        clock.set(unchangedPartial.nextDue().plusSeconds(1));
        CycleResult invalidated = collector(true).runCycle();

        assertThat(invalidated.state()).isEqualTo(State.RETRY_SCHEDULED);
        assertThat(fetch.count(EndpointKind.EVENT_LIVE)).isOne();
        assertThat(fetch.playerIds()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.run() + " WHERE generation=?",
                String.class, firstGeneration)).isEqualTo("SUPERSEDED");
        assertThat(jdbc.queryForObject("SELECT supersede_reason FROM " + TABLES.run()
                + " WHERE generation=?", String.class, firstGeneration))
                .isEqualTo(OwnedSyncStore.IncrementalSupersedeReason.APPEARANCE_EVIDENCE_ADVANCED.code());
        assertThat(new GameweekFinalityReader(jdbc, clock, TABLES)
                .read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                .isEqualTo(GameweekFinality.State.FINAL_PENDING);
        Instant appearanceFloor = jdbc.queryForObject("SELECT current_invalidation_floor FROM "
                + TABLES.gameweekStatSync() + " WHERE season_id=? AND gameweek=3", Timestamp.class, SEASON_ID)
                .toInstant();
        assertThat(appearanceFloor).isEqualTo(fetch.successfulReads().stream()
                .filter(read -> read.endpoint().kind() == EndpointKind.EVENT_LIVE)
                .map(read -> read.read().finishedAt()).toList().getLast());
        assertThat(jdbc.queryForObject("SELECT final_attempt_source_floor FROM " + TABLES.gameweekStatSync()
                + " WHERE season_id=? AND gameweek=3", Timestamp.class, SEASON_ID).toInstant())
                .isAfterOrEqualTo(appearanceFloor);

        fetch.clearCalls();
        clock.set(invalidated.nextDue().plusSeconds(1));
        CycleResult recovered = collector(true).runCycle();

        assertThat(recovered.state()).as("cycle: %s; error: %s", recovered, latestRunError())
                .isEqualTo(State.COMPLETED);
        assertThat(fetch.playerIds()).containsExactlyInAnyOrderElementsOf(setup.participantIds());
        assertThat(setup.participantIds()).allSatisfy(player -> assertThat(
                ((Timestamp) pageWatermark(player).get("source_read_started_at")).toInstant())
                .isAfter(appearanceFloor));
        assertThat(new GameweekFinalityReader(jdbc, clock, TABLES)
                .read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                .isEqualTo(GameweekFinality.State.FINALIZED);
    }

    @Test
    void unrelatedFailedPageDoesNotHoldACompleteCheckedGameweekPending() throws Exception {
        int checkedFixture = 29;
        int uncheckedFixture = 38;
        int uncheckedPlayer = 600;
        byte[] checkedBootstrap = markGameweekChecked(markAllGameweeksUnchecked(
                bootstrapPlayers(bootstrap, Set.of(1, uncheckedPlayer))), 3);
        byte[] checkedFixtures = fixtureStatsForPlayers(
                onlyCollectorFixtureFinished(fixtures, checkedFixture), checkedFixture, 1, 28);
        byte[] sourceFixtures = appendUncheckedFixtureParticipant(
                checkedFixtures, fixtures, uncheckedFixture, uncheckedPlayer);
        byte[] eventThree = liveForFixturePlayers(eventThreeLive, checkedFixture, Set.of(1, 28));
        byte[] eventFour = liveForFixturePlayers(eventFourLive, uncheckedFixture, Set.of(uncheckedPlayer));
        fetch.setSources(checkedBootstrap, sourceFixtures, eventThree, eventFour);
        fetch.setPlayerPage(1, onlyHistoryForFixture(resource("element-summary-1.json"), checkedFixture, 1));
        fetch.setPlayerPage(28, historyForAwayPlayer28(checkedFixture));
        fetch.setPlayerPage(uncheckedPlayer, historyWithMinutes(relabelHistoryPlayer(
                resource("element-summary-658.json"), uncheckedPlayer), uncheckedFixture, uncheckedPlayer, 12));
        fetch.failPlayer(uncheckedPlayer);

        CycleResult partial = compatibilityCollector(true).runCycle();

        assertThat(partial.state()).isEqualTo(State.RETRY_SCHEDULED);
        assertThat(partial.failedPlayerIds()).containsExactly(uncheckedPlayer);
        assertThat(jdbc.queryForObject("SELECT last_error FROM " + TABLES.run()
                + " WHERE state='PENDING'", String.class)).contains("Unresolved player pages: 1");
        assertThat(jdbc.queryForObject("SELECT consecutive_fetch_failures FROM " + TABLES.run()
                + " WHERE state='PENDING'", Integer.class)).isOne();
        assertThat(partial.committedPlayerIds()).contains(1, 28);
        assertThat(new GameweekFinalityReader(jdbc, clock, TABLES)
                .read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                .isEqualTo(GameweekFinality.State.FINALIZED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                + " WHERE producer_key=? AND state='COMPLETED'", Integer.class,
                OwnedSyncStore.INCREMENTAL_CORE_PRODUCER)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                + " WHERE producer_key=? AND state='PENDING'", Integer.class,
                OwnedSyncStore.INCREMENTAL_CORE_PRODUCER)).isOne();
    }

    @Test
    void unrelatedMissingLiveEventDoesNotHoldACompleteCheckedGameweekPending() throws Exception {
        int checkedFixture = 29;
        int uncheckedFixture = 38;
        int uncheckedPlayer = 600;
        byte[] checkedBootstrap = markGameweekChecked(markAllGameweeksUnchecked(
                bootstrapPlayers(bootstrap, Set.of(1, uncheckedPlayer))), 3);
        byte[] checkedFixtures = fixtureStatsForPlayers(
                onlyCollectorFixtureFinished(fixtures, checkedFixture), checkedFixture, 1, 28);
        byte[] sourceFixtures = appendUncheckedFixtureParticipant(
                checkedFixtures, fixtures, uncheckedFixture, uncheckedPlayer);
        byte[] eventThree = liveForFixturePlayers(eventThreeLive, checkedFixture, Set.of(1, 28));
        fetch.setSources(checkedBootstrap, sourceFixtures, eventThree,
                "{invalid live event".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        fetch.setPlayerPage(1, onlyHistoryForFixture(resource("element-summary-1.json"), checkedFixture, 1));
        fetch.setPlayerPage(28, historyForAwayPlayer28(checkedFixture));
        fetch.setPlayerPage(uncheckedPlayer, historyWithMinutes(relabelHistoryPlayer(
                resource("element-summary-658.json"), uncheckedPlayer), uncheckedFixture, uncheckedPlayer, 12));

        CycleResult partial = compatibilityCollector(true).runCycle();

        assertThat(partial.state()).isEqualTo(State.RETRY_SCHEDULED);
        assertThat(partial.failedPlayerIds()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT last_error FROM " + TABLES.run()
                + " WHERE state='PENDING'", String.class)).contains("Unresolved event-live sources: 1");
        assertThat(jdbc.queryForObject("SELECT consecutive_fetch_failures FROM " + TABLES.run()
                + " WHERE state='PENDING'", Integer.class)).isOne();
        assertThat(new GameweekFinalityReader(jdbc, clock, TABLES)
                .read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                .isEqualTo(GameweekFinality.State.FINALIZED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                + " WHERE producer_key=? AND state='PENDING'", Integer.class,
                OwnedSyncStore.INCREMENTAL_CORE_PRODUCER)).isOne();
    }

    @Test
    void unrelatedUncheckedAppearanceChangeSupersedesRunAndReusesCheckedDonorAtPostgresPrecision()
            throws Exception {
        int checkedFixture = 29;
        int uncheckedFixture = 38;
        int checkedPlayerA = 1;
        int checkedPlayerB = 28;
        int uncheckedPlayer = 600;
        byte[] checkedBootstrap = markGameweekChecked(markAllGameweeksUnchecked(
                bootstrapPlayers(bootstrap, Set.of(checkedPlayerA, uncheckedPlayer))), 3);
        byte[] checkedFixtures = fixtureStatsForPlayers(
                onlyCollectorFixtureFinished(fixtures, checkedFixture), checkedFixture,
                checkedPlayerA, checkedPlayerB);
        byte[] sourceFixtures = appendUncheckedFixtureParticipant(
                checkedFixtures, fixtures, uncheckedFixture, uncheckedPlayer);
        byte[] eventThree = liveForFixturePlayers(eventThreeLive, checkedFixture,
                Set.of(checkedPlayerA, checkedPlayerB));
        byte[] eventFour = liveForFixturePlayers(eventFourLive, uncheckedFixture, Set.of(uncheckedPlayer));
        byte[] checkedHistoryA = onlyHistoryForFixture(resource("element-summary-1.json"),
                checkedFixture, checkedPlayerA);
        byte[] checkedHistoryB = historyForAwayPlayer28(checkedFixture);
        byte[] uncheckedHistory = historyWithMinutes(relabelHistoryPlayer(
                resource("element-summary-658.json"), uncheckedPlayer), uncheckedFixture, uncheckedPlayer, 12);
        fetch.useSubMillisecondReads();
        fetch.setSources(checkedBootstrap, sourceFixtures, eventThree, eventFour);
        fetch.setPlayerPage(checkedPlayerA, checkedHistoryA);
        fetch.setPlayerPage(checkedPlayerB, checkedHistoryB);
        fetch.setPlayerPage(uncheckedPlayer, uncheckedHistory);
        fetch.failPlayer(checkedPlayerB);

        CycleResult partial = compatibilityCollector(true).runCycle();

        assertThat(partial.state()).as("cycle %s; error %s", partial, latestRunError())
                .isEqualTo(State.RETRY_SCHEDULED);
        assertThat(partial.finalPendingGameweeks()).containsExactly(3);
        assertThat(partial.failedPlayerIds()).containsExactly(checkedPlayerB);
        assertThat(partial.committedPlayerIds()).contains(checkedPlayerA, uncheckedPlayer);
        long firstGeneration = ((Number) pageWatermark(checkedPlayerA).get("latest_generation")).longValue();
        assertThat(fetch.playerIds()).containsExactly(checkedPlayerA, checkedPlayerB, uncheckedPlayer);
        SourceRead rawCheckedLive = fetch.successfulReads().stream()
                .filter(read -> read.endpoint().equals(Endpoint.eventLive(3)))
                .map(RecordedRead::read).findFirst().orElseThrow();
        SourceRead persistedCheckedLive = jdbc.queryForObject("SELECT source_read_started_at,"
                + "source_read_finished_at FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", (rs, row) -> new SourceRead(
                        rs.getTimestamp(1).toInstant(), rs.getTimestamp(2).toInstant()), season.code(),
                IncrementalAppearanceReceipt.appearanceStateKey(checkedFixture));
        assertThat(persistedCheckedLive).isEqualTo(new SourceRead(
                OwnedSyncStore.postgresTimestampPrecision(rawCheckedLive.startedAt()),
                OwnedSyncStore.postgresTimestampPrecision(rawCheckedLive.finishedAt())));
        assertThat(persistedCheckedLive).isNotEqualTo(rawCheckedLive);
        Map<String, Object> originalCheckedPage = pageWatermark(checkedPlayerA);
        Map<String, Object> originalCheckedPfs = jdbc.queryForMap("SELECT minutes,updated_at FROM "
                + "player_fixture_stats WHERE season_id=? AND fixture_id=? AND player_id=?",
                SEASON_ID, checkedFixture, checkedPlayerA);
        Instant originalCheckedFloor = jdbc.queryForObject("SELECT final_attempt_source_floor FROM "
                + TABLES.gameweekStatSync() + " WHERE season_id=? AND gameweek=3", Timestamp.class,
                SEASON_ID).toInstant();
        Instant originalUncheckedFloor = jdbc.queryForObject("SELECT source_read_finished_at FROM "
                + TABLES.receipt() + " WHERE season=? AND publication_key=?", Timestamp.class,
                season.code(), IncrementalAppearanceReceipt.appearanceStateKey(uncheckedFixture)).toInstant();
        assertThat(originalCheckedFloor).isEqualTo(persistedCheckedLive.finishedAt().plusMillis(1));
        SourceRead rawBootstrapRead = fetch.successfulReads().stream()
                .filter(read -> read.endpoint().kind() == EndpointKind.BOOTSTRAP_STATIC)
                .map(RecordedRead::read).findFirst().orElseThrow();
        SourceRead rawFixturesRead = fetch.successfulReads().stream()
                .filter(read -> read.endpoint().kind() == EndpointKind.FIXTURES)
                .map(RecordedRead::read).findFirst().orElseThrow();
        var snapshot = CatalogueScheduleParser.parse(season,
                document(checkedBootstrap, rawBootstrapRead), document(sourceFixtures, rawFixturesRead));
        var checkedSource = GameweekFinality.SourceSnapshot.from(snapshot, 3);
        var checkedEvidence = FixtureVerification.fixtureEvidenceByFixture(checkedSource.assignedFixtures(),
                document(eventThree, rawCheckedLive));
        var checkedPopulation = new GameweekFinality.RequiredPopulation("a".repeat(64), Map.of(),
                Map.of(checkedFixture, Set.of(checkedPlayerA, checkedPlayerB)), Map.of(), checkedEvidence);
        assertThat(GameweekFinalityRepository.currentAppearanceStateMatches(jdbc, TABLES, clock,
                season.code(), checkedSource, checkedPopulation, originalCheckedFloor, firstGeneration, true)).isTrue();
        var captured = checkedEvidence.get(checkedFixture);
        var overlappingEvidence = new FixtureVerification.FixtureEvidence(captured.complete(),
                captured.positivePlayerIds(), captured.appearances(), new CatalogueScheduleParser.ReadInterval(
                        persistedCheckedLive.startedAt().minusNanos(600),
                        persistedCheckedLive.finishedAt().plusNanos(600)));
        var overlappingPopulation = new GameweekFinality.RequiredPopulation("a".repeat(64), Map.of(),
                Map.of(checkedFixture, Set.of(checkedPlayerA, checkedPlayerB)), Map.of(),
                Map.of(checkedFixture, overlappingEvidence));
        assertThat(GameweekFinalityRepository.currentAppearanceStateMatches(jdbc, TABLES, clock,
                season.code(), checkedSource, overlappingPopulation, originalCheckedFloor, firstGeneration, true))
                .as("a different overlapping live-read interval cannot stand in for the persisted capture")
                .isFalse();

        byte[] changedEventFour = changeFixtureLiveMinutes(eventFour, uncheckedFixture, uncheckedPlayer, 45);
        byte[] changedUncheckedHistory = historyWithMinutes(uncheckedHistory,
                uncheckedFixture, uncheckedPlayer, 45);
        jdbc.update("DELETE FROM " + TABLES.receipt() + " WHERE season=? AND publication_key=?",
                season.code(), IncrementalAppearanceReceipt.LIVE_EVENT_PREFIX + 4);
        fetch.clearCalls();
        fetch.clearPageOutcomes();
        fetch.setSources(checkedBootstrap, sourceFixtures, eventThree, changedEventFour);
        fetch.setPlayerPage(uncheckedPlayer, changedUncheckedHistory);
        clock.set(partial.nextDue().plusSeconds(1));
        CycleResult superseded = compatibilityCollector(true).runCycle();

        assertThat(superseded.state()).isEqualTo(State.RETRY_SCHEDULED);
        assertThat(fetch.playerIds()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.run() + " WHERE generation=?",
                String.class, firstGeneration)).isEqualTo("SUPERSEDED");
        assertThat(jdbc.queryForObject("SELECT supersede_reason FROM " + TABLES.run()
                + " WHERE generation=?", String.class, firstGeneration))
                .isEqualTo(OwnedSyncStore.IncrementalSupersedeReason.APPEARANCE_EVIDENCE_ADVANCED.code());
        Instant changedUncheckedFloor = jdbc.queryForObject("SELECT source_read_finished_at FROM "
                + TABLES.receipt() + " WHERE season=? AND publication_key=?", Timestamp.class,
                season.code(), IncrementalAppearanceReceipt.appearanceStateKey(uncheckedFixture)).toInstant();
        assertThat(changedUncheckedFloor).isAfter(originalUncheckedFloor);
        assertThat(jdbc.queryForObject("SELECT current_invalidation_revision FROM "
                + TABLES.gameweekStatSync() + " WHERE season_id=? AND gameweek=3", Long.class,
                SEASON_ID)).isZero();
        assertThat(jdbc.queryForObject("SELECT final_attempt_source_floor FROM "
                + TABLES.gameweekStatSync() + " WHERE season_id=? AND gameweek=3", Timestamp.class,
                SEASON_ID).toInstant()).isEqualTo(originalCheckedFloor);
        assertThat(((Timestamp) pageWatermark(uncheckedPlayer).get("source_read_started_at")).toInstant())
                .isBefore(changedUncheckedFloor);

        fetch.clearCalls();
        clock.set(superseded.nextDue().plusSeconds(1));
        CycleResult recovered = compatibilityCollector(true).runCycle();

        assertThat(recovered.state()).as("cycle %s; error %s", recovered, latestRunError())
                .isEqualTo(State.COMPLETED);
        assertThat(fetch.playerIds()).containsExactly(checkedPlayerB, uncheckedPlayer);
        assertThat(pageWatermark(checkedPlayerA)).isEqualTo(originalCheckedPage);
        assertThat(jdbc.queryForMap("SELECT minutes,updated_at FROM player_fixture_stats"
                + " WHERE season_id=? AND fixture_id=? AND player_id=?",
                SEASON_ID, checkedFixture, checkedPlayerA)).isEqualTo(originalCheckedPfs);
        assertThat(jdbc.queryForObject("SELECT minutes FROM player_fixture_stats WHERE season_id=?"
                + " AND fixture_id=? AND player_id=?", Integer.class, SEASON_ID,
                checkedFixture, checkedPlayerB)).isEqualTo(90);
        assertThat(jdbc.queryForObject("SELECT minutes FROM player_fixture_stats WHERE season_id=?"
                + " AND fixture_id=? AND player_id=?", Integer.class, SEASON_ID,
                uncheckedFixture, uncheckedPlayer)).isEqualTo(45);
        long recoveredGeneration = ((Number) pageWatermark(checkedPlayerB).get("latest_generation")).longValue();
        assertThat(recoveredGeneration).isGreaterThan(firstGeneration);
        assertThat(jdbc.queryForObject("SELECT supersede_reason FROM " + TABLES.run()
                + " WHERE generation=?", String.class, firstGeneration))
                .isEqualTo(OwnedSyncStore.IncrementalSupersedeReason.APPEARANCE_EVIDENCE_ADVANCED.code());
        Map<String, Object> finalPageEvidence = jdbc.queryForMap("SELECT finalized_page_evidence::text AS evidence"
                + " FROM " + TABLES.gameweekStatSync() + " WHERE season_id=? AND gameweek=3", SEASON_ID);
        assertThat(JSON.readTree((String) finalPageEvidence.get("evidence"))
                .path(Integer.toString(checkedPlayerA)).path("generation").asLong()).isEqualTo(firstGeneration);
        GameweekFinalityReader reader = new GameweekFinalityReader(jdbc, clock, TABLES);
        assertThat(reader.read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                .isEqualTo(GameweekFinality.State.FINALIZED);
        Map<PfsReadSource.FixtureSide, PfsReadSource.SidePopulation> sides = collectorPfsReadSource()
                .fixtureSidePopulation(SEASON_ID, season.code(), "fixture", "season_id", SEASON_ID,
                        Set.of(checkedFixture), clock.instant());
        assertThat(sides.get(new PfsReadSource.FixtureSide(checkedFixture, 1)).complete()).isTrue();
        assertThat(sides.get(new PfsReadSource.FixtureSide(checkedFixture, 6)).complete()).isTrue();

        Map<String, Object> finalRow = finalitySnapshot();
        Map<String, Object> finalDonor = pageWatermark(checkedPlayerA);
        fetch.clearCalls();
        clock.set(clock.instant().plusSeconds(10));
        CycleResult noop = compatibilityCollector(true).runCycle();
        assertThat(noop.state()).as("cycle %s; error %s; requests %s", noop, latestRunError(), fetch.calls())
                .isEqualTo(State.COMPLETED);
        assertThat(fetch.playerIds()).isEmpty();
        assertThat(finalitySnapshot()).isEqualTo(finalRow);
        assertThat(pageWatermark(checkedPlayerA)).isEqualTo(finalDonor);
        new PlannerRuntimeRepository(jdbc, tx, clock, TABLES).deactivate(runtimeFence);
        assertThat(jdbc.queryForObject("SELECT runtime_mode FROM " + TABLES.plannerRuntime()
                + " WHERE instance_id=?", String.class, "mode:" + season.code())).isEqualTo("OFF");
        assertThat(reader.read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                .isEqualTo(GameweekFinality.State.FINALIZED);
    }

    @Test
    void offReaderTreatsOldMissingSupersedeColumnAsPendingWithoutReferencingIt() throws Exception {
        checkedSingleFixtureSetup();
        assertThat(compatibilityCollector(true).runCycle().state()).isEqualTo(State.COMPLETED);
        GameweekFinalityReader reader = new GameweekFinalityReader(jdbc, clock, TABLES);
        assertThat(reader.read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                .isEqualTo(GameweekFinality.State.FINALIZED);
        new PlannerRuntimeRepository(jdbc, tx, clock, TABLES).deactivate(runtimeFence);
        assertThat(jdbc.queryForObject("SELECT runtime_mode FROM " + TABLES.plannerRuntime()
                + " WHERE instance_id=?", String.class, "mode:" + season.code())).isEqualTo("OFF");

        jdbc.execute("ALTER TABLE " + TABLES.run() + " DROP CONSTRAINT ck_" + TABLES.run()
                + "_supersede_reason");
        jdbc.execute("ALTER TABLE " + TABLES.run() + " DROP COLUMN supersede_reason");
        SqlRecordingDataSource recording = new SqlRecordingDataSource(dataSource);
        try {
            GameweekFinalityReader oldSchemaReader = new GameweekFinalityReader(
                    new JdbcTemplate(recording), clock, TABLES);
            assertThat(oldSchemaReader.read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                    .isEqualTo(GameweekFinality.State.FINAL_PENDING);
            assertThat(recording.statements()).noneMatch(sql ->
                    sql.toLowerCase(java.util.Locale.ROOT).contains("supersede_reason"));
        } finally {
            new OwnedSyncSchema(jdbc, TABLES).initialize();
        }
    }

    @Test
    void finalMetricCorrectionChangesOnlyItsRowAndRequiresFreshGameweekReproof() throws Exception {
        CheckedFixtureSetup setup = checkedSingleFixtureSetup();
        int fixtureId = setup.fixtureId();
        byte[] variedHomePage = withOptionalHistoryValues(
                onlyHistoryForFixture(resource("element-summary-1.json"), fixtureId, 1), fixtureId, 1,
                Map.ofEntries(Map.entry("expected_goals", "0.87"), Map.entry("expected_assists", "0.34"),
                        Map.entry("expected_goal_involvements", "1.21"),
                        Map.entry("expected_goals_conceded", "1.09"), Map.entry("influence", "18.3"),
                        Map.entry("creativity", "29.4"), Map.entry("threat", "41.2"),
                        Map.entry("ict_index", "8.9"), Map.entry("clearances_blocks_interceptions", 2),
                        Map.entry("recoveries", 3), Map.entry("tackles", 4),
                        Map.entry("defensive_contribution", 9)));
        byte[] knownZeroAwayPage = withOptionalHistoryValues(historyForAwayPlayer28(fixtureId), fixtureId, 28,
                Map.ofEntries(Map.entry("expected_goals", "0.00"), Map.entry("expected_assists", "0.00"),
                        Map.entry("expected_goal_involvements", "0.00"),
                        Map.entry("expected_goals_conceded", "0.00"), Map.entry("influence", "0.0"),
                        Map.entry("creativity", "0.0"), Map.entry("threat", "0.0"),
                        Map.entry("ict_index", "0.0"), Map.entry("clearances_blocks_interceptions", 0),
                        Map.entry("recoveries", 0), Map.entry("tackles", 0),
                        Map.entry("defensive_contribution", 0)));
        fetch.setPlayerPage(1, variedHomePage);
        fetch.setPlayerPage(28, knownZeroAwayPage);
        assertThat(compatibilityCollector(true).runCycle().state()).isEqualTo(State.COMPLETED);
        assertThat(pfsOptionalMetrics(fixtureId, 1)).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                Map.entry("expected_goals", new java.math.BigDecimal("0.87")),
                Map.entry("expected_assists", new java.math.BigDecimal("0.34")),
                Map.entry("expected_goal_involvements", new java.math.BigDecimal("1.21")),
                Map.entry("expected_goals_conceded", new java.math.BigDecimal("1.09")),
                Map.entry("influence", new java.math.BigDecimal("18.3")),
                Map.entry("creativity", new java.math.BigDecimal("29.4")),
                Map.entry("threat", new java.math.BigDecimal("41.2")),
                Map.entry("ict_index", new java.math.BigDecimal("8.9")),
                Map.entry("clearances_blocks_interceptions", 2), Map.entry("recoveries", 3),
                Map.entry("tackles", 4), Map.entry("defensive_contribution", 9)));
        assertThat(pfsOptionalMetrics(fixtureId, 28)).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                Map.entry("expected_goals", new java.math.BigDecimal("0.00")),
                Map.entry("expected_assists", new java.math.BigDecimal("0.00")),
                Map.entry("expected_goal_involvements", new java.math.BigDecimal("0.00")),
                Map.entry("expected_goals_conceded", new java.math.BigDecimal("0.00")),
                Map.entry("influence", new java.math.BigDecimal("0.0")),
                Map.entry("creativity", new java.math.BigDecimal("0.0")),
                Map.entry("threat", new java.math.BigDecimal("0.0")),
                Map.entry("ict_index", new java.math.BigDecimal("0.0")),
                Map.entry("clearances_blocks_interceptions", 0), Map.entry("recoveries", 0),
                Map.entry("tackles", 0), Map.entry("defensive_contribution", 0)));
        assertThat(pfsComponentPresence(fixtureId, 1)).isEqualTo(HistoryParser.Component.allMask());
        assertThat(pfsComponentPresence(fixtureId, 28)).isEqualTo(HistoryParser.Component.allMask());
        Map<String, Object> playerOneBefore = jdbc.queryForMap("SELECT total_points,minutes,expected_goals,"
                + "expected_assists,gameweek,kickoff_time,player_team_id,opponent_team_id,was_home,updated_at "
                + "FROM player_fixture_stats WHERE season_id=? AND fixture_id=? AND player_id=1",
                SEASON_ID, fixtureId);
        Map<String, Object> playerTwentyEightBefore = jdbc.queryForMap("SELECT total_points,minutes,expected_goals,"
                + "gameweek,kickoff_time,player_team_id,opponent_team_id,was_home,updated_at "
                + "FROM player_fixture_stats WHERE season_id=? AND fixture_id=? AND player_id=28",
                SEASON_ID, fixtureId);
        Long playerTwentyEightGeneration = jdbc.queryForObject("SELECT source_run_generation FROM "
                + TABLES.playerFixtureCurrent() + " WHERE season_id=? AND fixture_id=? AND player_id=28",
                Long.class, SEASON_ID, fixtureId);
        int playerTwentyEightEvidence = jdbc.queryForObject("SELECT count(*) FROM "
                + TABLES.playerFixtureEvidence() + " WHERE season_id=? AND fixture_id=? AND player_id=28",
                Integer.class, SEASON_ID, fixtureId);
        Map<String, Object> unrelatedGameweekBefore = jdbc.queryForMap("SELECT state,"
                + "current_invalidation_revision,current_invalidation_floor,finalized_source_signature,finalized_at "
                + "FROM " + TABLES.gameweekStatSync() + " WHERE season_id=? AND gameweek=2", SEASON_ID);

        Lease lease = nextCoreLease();
        clock.set(jdbc.queryForObject("SELECT requested_source_after FROM " + TABLES.run()
                + " WHERE generation=?", java.sql.Timestamp.class, lease.generation()).toInstant());
        byte[] correctedPage = withoutOptionalHistoryComponent(changeHistoryPoints(
                variedHomePage, fixtureId, 9), fixtureId, 1, "expected_assists");
        SourceRead correctionRead = nextRead();
        var correction = publisher.publish(lease, season, 1, document(correctedPage, correctionRead),
                canonicalFixtures(setup.fixtureSource()));
        assertThat(correction.pfsRowsWritten()).isEqualTo(1);
        assertThat(correction.changedKeys()).containsExactly(new IncrementalPfsRepository.RowKey(
                SEASON_ID, season.code(), 3, fixtureId, 1));
        Map<String, Object> playerOneCorrected = jdbc.queryForMap("SELECT total_points,minutes,expected_goals,"
                + "expected_assists,gameweek,kickoff_time,player_team_id,opponent_team_id,was_home,updated_at "
                + "FROM player_fixture_stats WHERE season_id=? AND fixture_id=? AND player_id=1",
                SEASON_ID, fixtureId);
        assertThat(((Number) playerOneCorrected.get("total_points")).intValue()).isEqualTo(9);
        for (String field : playerOneBefore.keySet()) {
            if (!field.equals("total_points") && !field.equals("updated_at")) {
                assertThat(playerOneCorrected.get(field)).as("unchanged corrected-row field %s", field)
                        .isEqualTo(playerOneBefore.get(field));
            }
        }
        assertThat(jdbc.queryForMap("SELECT total_points,minutes,expected_goals,gameweek,kickoff_time,"
                + "player_team_id,opponent_team_id,was_home,updated_at FROM player_fixture_stats "
                + "WHERE season_id=? AND fixture_id=? AND player_id=28", SEASON_ID, fixtureId))
                .isEqualTo(playerTwentyEightBefore);
        Map<String, Object> invalidated = jdbc.queryForMap("SELECT state,current_invalidation_revision,"
                + "current_invalidation_floor FROM " + TABLES.gameweekStatSync()
                + " WHERE season_id=? AND gameweek=3", SEASON_ID);
        assertThat(invalidated.get("state")).isEqualTo("FINAL_PENDING");
        assertThat(invalidated.get("current_invalidation_revision")).isEqualTo(1L);
        assertThat(((java.sql.Timestamp) invalidated.get("current_invalidation_floor")).toInstant())
                .isEqualTo(correctionRead.finishedAt());
        assertThat(jdbc.queryForMap("SELECT state,current_invalidation_revision,current_invalidation_floor,"
                + "finalized_source_signature,finalized_at FROM " + TABLES.gameweekStatSync()
                + " WHERE season_id=? AND gameweek=2", SEASON_ID)).isEqualTo(unrelatedGameweekBefore);
        store.completeStaged(lease, correctionRead, List.of(correctionRead));
        lease = nextCoreLease();
        clock.set(jdbc.queryForObject("SELECT requested_source_after FROM " + TABLES.run()
                + " WHERE generation=?", java.sql.Timestamp.class, lease.generation()).toInstant());

        Instant sourceStart = clock.instant();
        SourceRead freshBootstrapRead = new SourceRead(sourceStart, sourceStart.plusMillis(1));
        SourceRead freshFixturesRead = new SourceRead(sourceStart.plusMillis(2), sourceStart.plusMillis(3));
        SourceRead freshSnapshotRead = new SourceRead(freshBootstrapRead.startedAt(), freshFixturesRead.finishedAt());
        clock.set(freshSnapshotRead.finishedAt().plusMillis(1));
        SourceDocument freshBootstrap = document(setup.bootstrapSource(), freshBootstrapRead);
        SourceDocument freshFixtures = document(setup.fixtureSource(), freshFixturesRead);
        var snapshot = CatalogueScheduleParser.parse(season, freshBootstrap, freshFixtures);
        store.observeIncrementalSnapshot(lease, snapshot);
        store.recordIncrementalFixtureSourceRevisions(lease, snapshot.fixtures(),
                new SourceRead(freshFixturesRead.startedAt(), freshFixturesRead.finishedAt()));
        var fixture = snapshot.fixtures().stream().filter(row -> row.id() == fixtureId).findFirst().orElseThrow();
        Instant liveStart = clock.instant();
        SourceDocument live = document(setup.liveSource(), new SourceRead(liveStart, liveStart.plusMillis(1)));
        clock.set(liveStart.plusMillis(2));
        var evidence = FixtureVerification.fixtureEvidenceByFixture(List.of(fixture), live);
        Set<Integer> cataloguePlayers = snapshot.players().stream()
                .filter(player -> player.teamId() == fixture.homeTeamId() || player.teamId() == fixture.awayTeamId())
                .map(PlayerRow::id).collect(java.util.stream.Collectors.toSet());
        Set<Integer> participants = new LinkedHashSet<>(FixtureVerification.fixtureStatParticipantIds(fixture));
        participants.addAll(evidence.get(fixtureId).positivePlayerIds());
        var source = GameweekFinality.SourceSnapshot.from(snapshot, 3);
        var population = new GameweekFinality.RequiredPopulation(
                GameweekFinality.catalogueFingerprint(snapshot, source.assignedFixtures()),
                Map.of(fixtureId, cataloguePlayers), Map.of(fixtureId, participants), Map.of(), evidence);
        store.recordIncrementalLiveEvent(lease, 3, source.signature(), Map.of(fixtureId, participants),
                List.of(fixture), evidence,
                new SourceRead(evidence.get(fixtureId).liveRead().startedAt(),
                        evidence.get(fixtureId).liveRead().completedAt()));
        var finality = new GameweekFinalityRepository(jdbc, store, TABLES, clock);
        var restarted = finality.beginOrResume(lease, season, source, population, freshSnapshotRead);
        assertThat(restarted.state()).isEqualTo(GameweekFinality.State.FINAL_PENDING);
        assertThat(restarted.invalidationRevision()).isGreaterThanOrEqualTo(1L);
        assertThat(restarted.checkpoint().outstandingPlayerIds()).containsExactly(1, 28);

        SourceRead freshPlayerOneRead = nextRead();
        assertThat(publisher.publish(lease, season, 1, document(correctedPage, freshPlayerOneRead),
                snapshot.fixtures().stream().map(HistoryParser::canonicalFixture).toList()).pfsRowsWritten()).isZero();
        finality.recordCompletedPage(lease, season, 3, 1);
        SourceRead freshPlayerTwentyEightRead = nextRead();
        assertThat(publisher.publish(lease, season, 28,
                document(knownZeroAwayPage, freshPlayerTwentyEightRead),
                snapshot.fixtures().stream().map(HistoryParser::canonicalFixture).toList()).pfsRowsWritten()).isZero();
        finality.recordCompletedPage(lease, season, 3, 28);
        SourceRead afterPagesRead = nextRead();
        var afterPages = finality.beginOrResume(lease, season, source, population, afterPagesRead);
        assertThat(afterPages.invalidationRevision()).isGreaterThanOrEqualTo(1L);
        assertThat(afterPages.checkpoint().outstandingPlayerIds()).isEmpty();

        Instant terminalStart = clock.instant();
        SourceRead terminalBootstrapRead = new SourceRead(terminalStart, terminalStart.plusMillis(1));
        SourceRead terminalFixturesRead = new SourceRead(terminalStart.plusMillis(2), terminalStart.plusMillis(3));
        SourceRead terminalRead = new SourceRead(terminalBootstrapRead.startedAt(), terminalFixturesRead.finishedAt());
        clock.set(terminalRead.finishedAt().plusMillis(1));
        var terminalSnapshot = CatalogueScheduleParser.parse(season,
                document(setup.bootstrapSource(), terminalBootstrapRead),
                document(setup.fixtureSource(), terminalFixturesRead));
        var finalReceipt = finality.finalizeSweep(lease, season,
                GameweekFinality.SourceSnapshot.from(terminalSnapshot, 3), population, terminalRead);
        assertThat(finality.load(lease, season, 3).orElseThrow().state())
                .isEqualTo(GameweekFinality.State.FINALIZED);
        store.completeStaged(lease, store.incrementalSourceInterval(lease),
                List.of(freshSnapshotRead, freshPlayerOneRead, freshPlayerTwentyEightRead,
                        afterPagesRead, terminalRead));
        assertThat(finalReceipt.invalidationRevision()).isEqualTo(
                ((Number) jdbc.queryForMap("SELECT current_invalidation_revision FROM "
                        + TABLES.gameweekStatSync() + " WHERE season_id=? AND gameweek=3", SEASON_ID)
                        .get("current_invalidation_revision")).longValue());
        assertThat(finalReceipt.sourceReadInterval()).isEqualTo(
                new SourceRead(freshSnapshotRead.startedAt(), terminalRead.finishedAt()));
        assertThat(finalReceipt.sourceReadInterval().startedAt()).isAfter(correctionRead.finishedAt());
        assertThat(jdbc.queryForMap("SELECT total_points,minutes,expected_goals,gameweek,kickoff_time,"
                + "player_team_id,opponent_team_id,was_home,updated_at FROM player_fixture_stats "
                + "WHERE season_id=? AND fixture_id=? AND player_id=28", SEASON_ID, fixtureId))
                .isEqualTo(playerTwentyEightBefore);
        assertThat(jdbc.queryForObject("SELECT source_run_generation FROM " + TABLES.playerFixtureCurrent()
                + " WHERE season_id=? AND fixture_id=? AND player_id=28", Long.class, SEASON_ID, fixtureId))
                .isEqualTo(playerTwentyEightGeneration);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.playerFixtureEvidence()
                + " WHERE season_id=? AND fixture_id=? AND player_id=28", Integer.class, SEASON_ID, fixtureId))
                .isEqualTo(playerTwentyEightEvidence);
        assertThat(pfsComponentPresence(fixtureId, 28)).isEqualTo(HistoryParser.Component.allMask());
        assertThat(pfsOptionalMetrics(fixtureId, 28)).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                Map.entry("expected_goals", new java.math.BigDecimal("0.00")),
                Map.entry("expected_assists", new java.math.BigDecimal("0.00")),
                Map.entry("expected_goal_involvements", new java.math.BigDecimal("0.00")),
                Map.entry("expected_goals_conceded", new java.math.BigDecimal("0.00")),
                Map.entry("influence", new java.math.BigDecimal("0.0")),
                Map.entry("creativity", new java.math.BigDecimal("0.0")),
                Map.entry("threat", new java.math.BigDecimal("0.0")),
                Map.entry("ict_index", new java.math.BigDecimal("0.0")),
                Map.entry("clearances_blocks_interceptions", 0), Map.entry("recoveries", 0),
                Map.entry("tackles", 0), Map.entry("defensive_contribution", 0)));
        assertThat(pfsComponentPresence(fixtureId, 1))
                .isEqualTo(HistoryParser.Component.allMask() & ~HistoryParser.Component.EXPECTED_ASSISTS.bit());
        assertThat(pfsOptionalMetrics(fixtureId, 1).get("expected_assists"))
                .isEqualTo(new java.math.BigDecimal("0.34"));
        PfsReadSource maskedReader = new PfsReadSource(jdbc, "ACTIVE", season.code(),
                TABLES.fixtureSync(), TABLES.playerFixtureEvidence(), TABLES.playerFixtureCurrent(), TABLES.receipt());
        assertThat(jdbc.queryForObject("WITH " + maskedReader.cte()
                + " SELECT expected_assists FROM pfs_read WHERE season_id=? AND fixture_id=? AND player_id=1",
                java.math.BigDecimal.class, SEASON_ID, fixtureId)).isNull();
        GameweekFinalityReader reader = new GameweekFinalityReader(jdbc, clock, TABLES);
        assertThat(reader.read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                .isEqualTo(GameweekFinality.State.FINALIZED);

        Set<Integer> fixtureIds = Set.of(fixtureId);
        Map<String, List<Object>> correctedRows = pfsRowsSnapshot(fixtureIds);
        Map<String, List<Object>> correctedProof = pfsProofSnapshot(fixtureIds);
        Map<String, String> correctedPageWatermarks = pageWatermarkSnapshot();
        Map<String, Object> correctedFinality = finalitySnapshot();
        Map<String, Object> compatibilityReceipt = jdbc.queryForMap("SELECT latest_generation,source_read_started_at,"
                + "source_read_finished_at,source_bootstrap_payload_id,source_fixtures_payload_id,"
                + "source_bootstrap_sha256,source_fixtures_sha256,source_pair_fingerprint,source_catalogue::text "
                + "FROM " + TABLES.receipt() + " WHERE season=? AND publication_key=?",
                season.code(), OwnedSyncStore.INCREMENTAL_COMPATIBILITY_PUBLICATION_KEY);
        long publishedGeneration = ((Number) compatibilityReceipt.get("latest_generation")).longValue();
        long bootstrapPayloadId = ((Number) compatibilityReceipt.get("source_bootstrap_payload_id")).longValue();
        long fixturesPayloadId = ((Number) compatibilityReceipt.get("source_fixtures_payload_id")).longValue();
        assertThat(payloadCount(publishedGeneration)).isEqualTo(2);
        assertThat(reader.read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                .isEqualTo(GameweekFinality.State.FINALIZED);
        Long otherGeneration = jdbc.queryForObject("SELECT min(generation) FROM " + TABLES.run()
                + " WHERE generation<>?", Long.class, publishedGeneration);
        assertThat(otherGeneration).isNotNull();
        assertFinalityPendingAfterTemporaryMutation(reader, () -> jdbc.update("UPDATE " + TABLES.payload()
                + " SET validation_state='INVALID' WHERE id=?", bootstrapPayloadId));
        assertFinalityPendingAfterTemporaryMutation(reader, () -> jdbc.update("UPDATE " + TABLES.payload()
                + " SET run_generation=? WHERE id=?", otherGeneration, bootstrapPayloadId));
        assertFinalityPendingAfterTemporaryMutation(reader, () -> jdbc.update("UPDATE " + TABLES.payload()
                + " SET endpoint_kind='FIXTURES' WHERE id=?", bootstrapPayloadId));
        assertFinalityPendingAfterTemporaryMutation(reader, () -> jdbc.update("UPDATE " + TABLES.payload()
                + " SET read_started_at=?,read_finished_at=? WHERE id=?",
                java.sql.Timestamp.from(clock.instant()), java.sql.Timestamp.from(clock.instant()), bootstrapPayloadId));
        assertFinalityPendingAfterTemporaryMutation(reader, () -> jdbc.update("UPDATE " + TABLES.payload()
                + " SET source_payload_sha256=repeat('0',64) WHERE id=?", bootstrapPayloadId));
        clock.advance(CoalescedPlayerHistoryCollector.PAYLOAD_RETENTION.plusMillis(1));
        tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE " + TABLES.payload() + " SET body=body || decode('ff','hex') WHERE id=?",
                    bootstrapPayloadId);
            assertThat(jdbc.queryForObject("SELECT validation_state FROM " + TABLES.payload()
                    + " WHERE id=?", String.class, bootstrapPayloadId)).isEqualTo("INVALID");
            assertThat(reader.read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                    .isEqualTo(GameweekFinality.State.FINAL_PENDING);
            assertThat(store.deleteIncrementalCoreTerminalPayloadsOlderThan(
                    CoalescedPlayerHistoryCollector.PAYLOAD_RETENTION)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT body_pruned_at IS NULL AND octet_length(body)>0 "
                    + "AND source_payload_sha256<>encode(sha256(body),'hex') FROM " + TABLES.payload()
                    + " WHERE id=?", Boolean.class, bootstrapPayloadId)).isTrue();
            assertThat(reader.read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                    .isEqualTo(GameweekFinality.State.FINAL_PENDING);
            status.setRollbackOnly();
        });
        tx.executeWithoutResult(status -> {
            jdbc.execute("ALTER TABLE " + TABLES.payload() + " DISABLE TRIGGER trg_owned_payload_source_guard");
            jdbc.update("UPDATE " + TABLES.payload() + " SET body=body || decode('ff','hex') WHERE id=?",
                    bootstrapPayloadId);
            jdbc.execute("ALTER TABLE " + TABLES.payload() + " ENABLE TRIGGER trg_owned_payload_source_guard");
            assertThat(jdbc.queryForObject("SELECT validation_state='VALID' AND source_payload_sha256<>"
                    + "encode(sha256(body),'hex') FROM " + TABLES.payload() + " WHERE id=?",
                    Boolean.class, bootstrapPayloadId)).isTrue();
            assertThat(reader.read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                    .isEqualTo(GameweekFinality.State.FINAL_PENDING);
            status.setRollbackOnly();
        });
        assertFinalityPendingAfterTemporaryMutation(reader, () -> jdbc.update("DELETE FROM " + TABLES.payload()
                + " WHERE id=?", bootstrapPayloadId));
        assertFinalityPendingAfterTemporaryMutation(reader, () -> jdbc.update("UPDATE " + TABLES.receipt()
                + " SET source_bootstrap_sha256=repeat('0',64) WHERE season=? AND publication_key=?",
                season.code(), OwnedSyncStore.INCREMENTAL_COMPATIBILITY_PUBLICATION_KEY));

        tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE " + TABLES.payload()
                    + " SET validation_state='INVALID' WHERE id=?", bootstrapPayloadId);
            assertThat(reader.read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                    .isEqualTo(GameweekFinality.State.FINAL_PENDING);
            assertThat(store.deleteIncrementalCoreTerminalPayloadsOlderThan(
                    CoalescedPlayerHistoryCollector.PAYLOAD_RETENTION)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT validation_state='INVALID' AND body_pruned_at IS NOT NULL "
                    + "AND octet_length(body)=0 FROM " + TABLES.payload() + " WHERE id=?",
                    Boolean.class, bootstrapPayloadId)).isTrue();
            assertThat(reader.read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                    .isEqualTo(GameweekFinality.State.FINAL_PENDING);
            status.setRollbackOnly();
        });
        assertThat(reader.read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                .isEqualTo(GameweekFinality.State.FINALIZED);
        long rawBytesBeforePrune = jdbc.queryForObject("SELECT sum(octet_length(body)) FROM "
                + TABLES.payload() + " WHERE run_generation=?", Long.class, publishedGeneration);
        assertThat(store.deleteIncrementalCoreTerminalPayloadsOlderThan(
                CoalescedPlayerHistoryCollector.PAYLOAD_RETENTION)).isEqualTo(2);
        assertPrunedPayloadBodies(publishedGeneration, 2);
        assertThat(jdbc.queryForObject("SELECT sum(octet_length(body)) FROM " + TABLES.payload()
                + " WHERE run_generation=?", Long.class, publishedGeneration)).isZero();
        assertThat(rawBytesBeforePrune).isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.payload()
                + " WHERE id IN (?,?) AND run_generation=? AND validation_state='VALID' AND source_published "
                + "AND source_payload_sha256 IN (?,?) AND body_pruned_at IS NOT NULL AND octet_length(body)=0",
                Integer.class, bootstrapPayloadId, fixturesPayloadId, publishedGeneration,
                compatibilityReceipt.get("source_bootstrap_sha256"),
                compatibilityReceipt.get("source_fixtures_sha256"))).isEqualTo(2);
        assertThatThrownBy(() -> jdbc.update("UPDATE " + TABLES.payload()
                + " SET body=? WHERE id=?", setup.bootstrapSource(), bootstrapPayloadId))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE " + TABLES.payload()
                + " SET source_payload_sha256=repeat('0',64) WHERE id=?", fixturesPayloadId))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE " + TABLES.payload()
                + " SET body_pruned_at=NULL WHERE id=?", fixturesPayloadId))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(payloadCount(publishedGeneration)).isEqualTo(2);
        assertThat(jdbc.queryForMap("SELECT latest_generation,source_read_started_at,source_read_finished_at,"
                + "source_bootstrap_payload_id,source_fixtures_payload_id,source_bootstrap_sha256,"
                + "source_fixtures_sha256,source_pair_fingerprint,source_catalogue::text FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", season.code(),
                OwnedSyncStore.INCREMENTAL_COMPATIBILITY_PUBLICATION_KEY)).isEqualTo(compatibilityReceipt);
        assertThat(pfsRowsSnapshot(fixtureIds)).isEqualTo(correctedRows);
        assertThat(pfsProofSnapshot(fixtureIds)).isEqualTo(correctedProof);
        assertThat(pageWatermarkSnapshot()).isEqualTo(correctedPageWatermarks);
        assertThat(finalitySnapshot()).isEqualTo(correctedFinality);
        assertThat(reader.read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                .isEqualTo(GameweekFinality.State.FINALIZED);
        fetch.clearCalls();
        clock.set(clock.instant().plusSeconds(1));
        CycleResult correctionNoop = compatibilityCollector(true).runCycle();

        assertThat(correctionNoop.state()).isEqualTo(State.COMPLETED);
        assertThat(correctionNoop.selectedPlayerIds()).isEmpty();
        assertThat(fetch.count(EndpointKind.BOOTSTRAP_STATIC)).isOne();
        assertThat(fetch.count(EndpointKind.FIXTURES)).isOne();
        assertThat(fetch.count(EndpointKind.EVENT_LIVE)).isZero();
        assertThat(fetch.count(EndpointKind.ELEMENT_SUMMARY)).isZero();
        assertThat(pfsRowsSnapshot(fixtureIds)).isEqualTo(correctedRows);
        assertThat(pfsProofSnapshot(fixtureIds)).isEqualTo(correctedProof);
        assertThat(pageWatermarkSnapshot()).isEqualTo(correctedPageWatermarks);
        assertThat(finalitySnapshot()).isEqualTo(correctedFinality);
        assertThat(reader.read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                .isEqualTo(GameweekFinality.State.FINALIZED);
    }

    @Test
    void successfulWithdrawalRemainsBoundedEvidenceWithoutRepeatedPopulationRevision() throws Exception {
        CheckedFixtureSetup setup = checkedSingleFixtureSetup();
        int fixtureId = setup.fixtureId();
        int withdrawnPlayer = FINAL_PENDING_PLAYER;
        SourceRead initialRead = new SourceRead(START, START.plusMillis(3));
        var initialSnapshot = CatalogueScheduleParser.parse(season,
                document(setup.bootstrapSource(), new SourceRead(START, START.plusMillis(1))),
                document(setup.fixtureSource(), new SourceRead(START.plusMillis(2), START.plusMillis(3))));
        var source = GameweekFinality.SourceSnapshot.from(initialSnapshot, 3);
        var fixtureRow = initialSnapshot.fixtures().stream().filter(row -> row.id() == fixtureId)
                .findFirst().orElseThrow();
        var liveRead = new CatalogueScheduleParser.ReadInterval(START.plusMillis(4), START.plusMillis(5));
        var evidence = FixtureVerification.fixtureEvidenceByFixture(List.of(fixtureRow),
                new SourceDocument(setup.liveSource(), liveRead));
        var population = new GameweekFinality.RequiredPopulation(OwnedSyncStore.sha256(setup.bootstrapSource()),
                Map.of(), Map.of(fixtureId, Set.of(1, 28)), Map.of(), evidence);
        store.enqueueIncrementalCoreImport(season, START);
        Lease lease = store.acquireIncrementalCoreImport(season.code(), "withdrawal-cycle", Duration.ofMinutes(30))
                .orElseThrow();
        store.observeIncrementalSnapshot(lease, initialSnapshot);
        clock.set(liveRead.completedAt());
        store.recordIncrementalFixtureSourceRevisions(lease, initialSnapshot.fixtures(),
                new SourceRead(START.plusMillis(2), START.plusMillis(3)));
        store.recordIncrementalLiveEvent(lease, 3, source.signature(),
                Map.of(fixtureId, Set.of(1, 28)), List.of(fixtureRow), evidence,
                new SourceRead(liveRead.startedAt(), liveRead.completedAt()));
        List<CanonicalFixture> canonical = initialSnapshot.fixtures().stream()
                .map(HistoryParser::canonicalFixture).toList();
        SourceRead oldPageRead = new SourceRead(START.plusMillis(1), START.plusMillis(2));
        byte[] oldHistory = onlyHistoryForFixture(resource("element-summary-1.json"), fixtureId, withdrawnPlayer);
        clock.set(oldPageRead.finishedAt());
        publisher.publish(lease, season, withdrawnPlayer, document(oldHistory, oldPageRead), canonical);
        clock.set(liveRead.completedAt());
        GameweekFinalityRepository finality = new GameweekFinalityRepository(jdbc, store, TABLES, clock);

        var started = finality.beginOrResume(lease, season, source, population, initialRead);
        assertThat(started.checkpoint().requiredPlayerIds()).containsExactly(1, 28, withdrawnPlayer);
        assertThat(started.checkpoint().outstandingPlayerIds()).containsExactly(1, 28, withdrawnPlayer);
        store.completeStaged(lease, store.incrementalSourceInterval(lease), List.of(oldPageRead));
        Lease withdrawalLease = nextCoreLease();
        SourceRead membershipRead = nextRead();
        var resumed = finality.beginOrResume(withdrawalLease, season, source, population, membershipRead);
        assertThat(resumed.checkpoint().requiredPlayerIds()).containsExactly(1, 28, withdrawnPlayer);
        clock.set(resumed.checkpoint().sourceFloor().plusMillis(1));
        SourceRead withdrawalRead = nextRead();
        publisher.publish(withdrawalLease, season, withdrawnPlayer,
                document("{\"history\":[]}".getBytes(StandardCharsets.UTF_8), withdrawalRead), canonical);
        var afterWithdrawal = finality.recordCompletedPage(withdrawalLease, season, 3, withdrawnPlayer);
        SourceRead afterRead = nextRead();
        var stable = finality.beginOrResume(withdrawalLease, season, source, population, afterRead);
        var repeated = finality.beginOrResume(withdrawalLease, season, source, population, afterRead);

        assertThat(afterWithdrawal.checkpoint().requiredPlayerIds()).contains(1, 28, withdrawnPlayer);
        assertThat(afterWithdrawal.checkpoint().completedPlayerIds()).contains(withdrawnPlayer);
        assertThat(stable.checkpoint().requiredPlayerIds()).contains(1, 28, withdrawnPlayer);
        assertThat(repeated.checkpoint().requiredPlayerIds()).contains(1, 28, withdrawnPlayer);
        assertThat(stable.invalidationRevision()).isEqualTo(afterWithdrawal.invalidationRevision());
        assertThat(repeated.invalidationRevision()).isEqualTo(stable.invalidationRevision());
        assertThat(stable.checkpoint().sourceFloor()).isEqualTo(afterWithdrawal.checkpoint().sourceFloor());
        assertThat(repeated.checkpoint().sourceFloor()).isEqualTo(stable.checkpoint().sourceFloor());
        assertThat(jdbc.queryForObject("SELECT row_state FROM " + TABLES.playerFixtureCurrent()
                + " WHERE season_id=? AND fixture_id=? AND player_id=?", String.class,
                SEASON_ID, fixtureId, withdrawnPlayer)).isEqualTo("WITHDRAWN");
    }

    @Test
    void positiveHistoryWithoutPositiveLiveAppearanceKeepsFinalityPending() throws Exception {
        CycleResult result = collectWithExtraHistory(45, false);

        assertThat(result.state()).isEqualTo(State.RETRY_SCHEDULED);
        assertThat(jdbc.queryForObject("SELECT minutes FROM player_fixture_stats"
                + " WHERE season_id=? AND fixture_id=29 AND player_id=2", Integer.class, SEASON_ID)).isEqualTo(45);
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.gameweekStatSync()
                + " WHERE season_id=? AND gameweek=3", String.class, SEASON_ID)).isEqualTo("FINAL_PENDING");
        assertThat(jdbc.queryForObject("SELECT finalized_at IS NULL FROM " + TABLES.gameweekStatSync()
                + " WHERE season_id=? AND gameweek=3", Boolean.class, SEASON_ID)).isTrue();
    }

    @Test
    void positiveHistoryWithZeroMinuteLiveExplanationKeepsFinalityPending() throws Exception {
        CycleResult result = collectWithExtraHistory(45, true);

        assertThat(result.state()).isEqualTo(State.RETRY_SCHEDULED);
        assertThat(jdbc.queryForObject("SELECT minutes FROM player_fixture_stats"
                + " WHERE season_id=? AND fixture_id=29 AND player_id=2", Integer.class, SEASON_ID)).isEqualTo(45);
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.gameweekStatSync()
                + " WHERE season_id=? AND gameweek=3", String.class, SEASON_ID)).isEqualTo("FINAL_PENDING");
    }

    @Test
    void zeroMinuteKnownHistoryNeedsNeitherLiveAppearanceNorCatalogueRegistration() throws Exception {
        CheckedFixtureSetup setup = checkedSingleFixtureSetup();
        byte[] page = historyWithMinutes(resource("element-summary-1.json"), setup.fixtureId(), 658, 0);
        Lease seedLease = nextCoreLease();
        SourceRead seedRead = nextRead();
        publisher.publish(seedLease, season, 658, document(page, seedRead), canonicalFixtures(setup.fixtureSource()));
        store.completeStaged(seedLease, seedRead, List.of(seedRead));
        fetch.setPlayerPage(658, page);

        CycleResult result = collector(true).runCycle();

        assertThat(result.state()).as("last error: %s", latestRunError()).isEqualTo(State.COMPLETED);
        assertThat(result.selectedPlayerIds()).contains(658);
        assertThat(fetch.playerIds()).contains(658);
        assertThat(jdbc.queryForObject("SELECT minutes FROM player_fixture_stats"
                + " WHERE season_id=? AND fixture_id=? AND player_id=658", Integer.class,
                SEASON_ID, setup.fixtureId())).isZero();
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.gameweekStatSync()
                + " WHERE season_id=? AND gameweek=3", String.class, SEASON_ID)).isEqualTo("FINALIZED");
    }

    @Test
    void omittedFixtureStatPlayerFinalizesOnlyAfterProvedHistorySuppliesItsSide() throws Exception {
        CheckedFixtureSetup setup = omittedFixtureStatPlayerSetup();
        SqlRecordingDataSource recording = new SqlRecordingDataSource(dataSource);
        JdbcTemplate recordedJdbc = new JdbcTemplate(recording);
        TransactionTemplate recordedTx = new TransactionTemplate(new DataSourceTransactionManager(recording));
        var collector = compatibilityCollector(true, recordedJdbc, recordedTx);

        CycleResult first = collector.runCycle();

        assertThat(first.state()).as("last error: %s", latestRunError()).isEqualTo(State.RETRY_SCHEDULED);
        assertThat(first.committedPlayerIds()).containsExactlyInAnyOrder(1, 3, 28);
        assertThat(collectorPfsReadSource().fixtureSidePopulation(SEASON_ID, season.code(), "fixture", "season_id",
                SEASON_ID, Set.of(setup.fixtureId()), clock.instant())).hasSize(2)
                .allSatisfy((side, population) -> assertThat(population.complete()).isFalse());
        assertThat(new GameweekFinalityReader(jdbc, clock, TABLES)
                .read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                .isEqualTo(GameweekFinality.State.FINAL_PENDING);
        clock.set(clock.instant().plus(Duration.ofHours(1)).plusSeconds(1));
        recording.clear();

        CycleResult second = collector.runCycle();

        assertThat(second.state()).as("last error: %s", latestRunError()).isEqualTo(State.COMPLETED);
        assertThat(new GameweekFinalityReader(jdbc, clock, TABLES)
                .read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                .isEqualTo(GameweekFinality.State.FINALIZED);
        assertThat(recording.statements().stream()
                .filter(sql -> sql.contains("/* incremental history side proof */"))).hasSize(1);
        assertThat(collectorPfsReadSource().fixtureSidePopulation(SEASON_ID, season.code(), "fixture", "season_id",
                SEASON_ID, Set.of(setup.fixtureId()), clock.instant())).hasSize(2)
                .allSatisfy((side, population) -> assertThat(population.complete()).isTrue());
    }

    @Test
    void historySideHintsRejectUnprovedRowsAndChangedFixtureIdentity() throws Exception {
        CheckedFixtureSetup setup = omittedFixtureStatPlayerSetup();
        assertThat(compatibilityCollector(true).runCycle().state()).isEqualTo(State.RETRY_SCHEDULED);
        clock.set(clock.instant().plus(Duration.ofHours(1)).plusSeconds(1));
        Lease lease = nextCoreLease();
        var snapshot = CatalogueScheduleParser.parse(season, document(setup.bootstrapSource(), nextRead()),
                document(setup.fixtureSource(), nextRead()));
        var fixture = snapshot.fixtures().stream().filter(row -> row.id() == setup.fixtureId()).findFirst().orElseThrow();
        assertThat(store.incrementalHistorySides(lease, SEASON_ID, List.of(fixture)).get(setup.fixtureId()))
                .containsEntry(1, FixtureVerification.Side.HOME)
                .containsEntry(3, FixtureVerification.Side.HOME)
                .containsEntry(28, FixtureVerification.Side.AWAY);
        List<Runnable> corruptions = List.of(
                () -> jdbc.update("UPDATE player_fixture_stats SET minutes=minutes+1"
                        + " WHERE season_id=? AND fixture_id=? AND player_id=1", SEASON_ID, setup.fixtureId()),
                () -> jdbc.update("UPDATE " + TABLES.playerFixtureCurrent() + " SET physical_fingerprint=NULL"
                        + " WHERE season_id=? AND fixture_id=? AND player_id=1", SEASON_ID, setup.fixtureId()),
                () -> jdbc.update("DELETE FROM " + TABLES.playerFixtureCurrent()
                        + " WHERE season_id=? AND fixture_id=? AND player_id=1", SEASON_ID, setup.fixtureId()),
                () -> jdbc.update("UPDATE " + TABLES.playerFixtureCurrent()
                        + " SET row_state='WITHDRAWN',evidence_generation=NULL,row_fingerprint=NULL,physical_fingerprint=NULL"
                        + " WHERE season_id=? AND fixture_id=? AND player_id=1", SEASON_ID, setup.fixtureId()),
                () -> jdbc.update("DELETE FROM " + TABLES.receipt() + " WHERE season=? AND publication_key=?",
                        season.code(), IncrementalPlayerFixturePublisher.pageReceiptKey(1)),
                () -> jdbc.update("UPDATE " + TABLES.receipt()
                        + " SET source_read_started_at=? WHERE season=? AND publication_key=?",
                        java.sql.Timestamp.from(START), season.code(), IncrementalPlayerFixturePublisher.pageReceiptKey(1)));
        for (Runnable corruption : corruptions) {
            tx.executeWithoutResult(status -> {
                corruption.run();
                assertThat(store.incrementalHistorySides(lease, SEASON_ID, List.of(fixture))
                        .getOrDefault(setup.fixtureId(), Map.of())).doesNotContainKey(1);
                status.setRollbackOnly();
            });
        }
        int otherClub = snapshot.teams().stream().mapToInt(team -> team.id())
                .filter(id -> id != fixture.homeTeamId() && id != fixture.awayTeamId()).findFirst().orElseThrow();
        for (String change : List.of("club", "gameweek", "kickoff")) {
            var changed = new CatalogueScheduleParser.FixtureRow(fixture.seasonId(), fixture.seasonCode(),
                    fixture.id(), fixture.code(), change.equals("gameweek") ? fixture.gameweek() + 1 : fixture.gameweek(),
                    change.equals("club") ? otherClub : fixture.homeTeamId(), fixture.awayTeamId(),
                    fixture.homeTeamName(), fixture.homeTeamShort(), fixture.awayTeamName(), fixture.awayTeamShort(),
                    fixture.homeScore(), fixture.awayScore(), fixture.homeDifficulty(), fixture.awayDifficulty(),
                    change.equals("kickoff") ? fixture.kickoff().plusSeconds(60) : fixture.kickoff(),
                    fixture.started(), fixture.finished(), fixture.fields());
            assertThat(store.incrementalHistorySides(lease, SEASON_ID, List.of(changed)))
                    .as("changed fixture %s", change).isEmpty();
        }
        assertThatThrownBy(() -> store.incrementalHistorySides(lease, SEASON_ID + 1, List.of(fixture)))
                .isInstanceOf(IllegalArgumentException.class);
        new PlannerRuntimeRepository(jdbc, tx, clock, TABLES).deactivate(runtimeFence);
        assertThatThrownBy(() -> store.incrementalHistorySides(lease, SEASON_ID, List.of(fixture)))
                .isInstanceOf(OwnedSyncStore.RuntimeFenceLostException.class);
    }

    @Test
    void freshHistoryContradictingAnOmittedPlayersPriorSideCannotFinalize() throws Exception {
        CheckedFixtureSetup setup = omittedFixtureStatPlayerSetup();
        assertThat(compatibilityCollector(true).runCycle().state()).isEqualTo(State.RETRY_SCHEDULED);
        fetch.setPlayerPage(1, relabelHistoryPlayer(historyForAwayPlayer28(setup.fixtureId()), 1));
        clock.set(clock.instant().plus(Duration.ofHours(1)).plusSeconds(1));

        compatibilityCollector(true).runCycle();

        assertThat(jdbc.queryForObject("SELECT was_home FROM player_fixture_stats"
                + " WHERE season_id=? AND fixture_id=? AND player_id=1", Boolean.class,
                SEASON_ID, setup.fixtureId())).isFalse();
        assertThat(new GameweekFinalityReader(jdbc, clock, TABLES)
                .read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                .isNotEqualTo(GameweekFinality.State.FINALIZED);
    }

    @Test
    void historySideReadFailureClosesTheRunWithoutFetchingPlayerPages() throws Exception {
        checkedSingleFixtureSetup();
        store = Mockito.spy(store);
        Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("history side read unavailable"))
                .when(store).incrementalHistorySides(Mockito.any(), Mockito.eq(SEASON_ID), Mockito.anyList());

        CycleResult result = collector(true).runCycle();

        assertThat(result.state()).isEqualTo(State.FAILED);
        assertThat(latestRunError()).contains("history side read unavailable");
        assertThat(fetch.count(EndpointKind.ELEMENT_SUMMARY)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run() + " WHERE state='LEASED'",
                Integer.class)).isZero();
    }

    @Test
    void oneSidedLiveEvidenceLeavesFinalityPendingButCommitsIndependentPages() throws Exception {
        CheckedFixtureSetup setup = checkedSingleFixtureSetup();
        byte[] oneSidedLive = liveForFixturePlayers(eventThreeLive, setup.fixtureId(), Set.of(1));
        fetch.setSources(setup.bootstrapSource(), setup.fixtureSource(), oneSidedLive, eventFourLive);

        CycleResult result = collector(true).runCycle();

        assertThat(result.state()).isEqualTo(State.RETRY_SCHEDULED);
        assertThat(result.committedPlayerIds()).containsExactly(1, 28);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM player_fixture_stats"
                + " WHERE season_id=? AND fixture_id=? AND player_id IN (?, ?)", Integer.class,
                SEASON_ID, setup.fixtureId(), 1, 28)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.gameweekStatSync()
                + " WHERE season_id=? AND gameweek=3", String.class, SEASON_ID)).isEqualTo("FINAL_PENDING");
    }

    @Test
    void staleFinalizedPageWatermarkReopensTheGameweekForFreshPages() throws Exception {
        assertFinalizedEvidenceTamperReopens(setup -> jdbc.update("UPDATE " + TABLES.receipt()
                + " SET source_read_started_at=? WHERE season=? AND publication_key=?",
                java.sql.Timestamp.from(START), season.code(),
                IncrementalPlayerFixturePublisher.pageReceiptKey(setup.cataloguePlayer())));
    }

    @Test
    void deletedFinalizedCurrentPointerReopensTheGameweekForFreshPages() throws Exception {
        assertFinalizedEvidenceTamperReopens(setup -> jdbc.update("DELETE FROM " + TABLES.playerFixtureCurrent()
                + " WHERE season_id=? AND fixture_id=? AND player_id=?",
                SEASON_ID, setup.fixtureId(), setup.cataloguePlayer()));
    }

    @Test
    void offThenActiveKeepsFinalizedPageProofWhenSourceMembershipIsUnchanged() throws Exception {
        CheckedFixtureSetup setup = checkedSingleFixtureSetup();
        installUnit6WriteCounters();
        try {
            CycleResult first = compatibilityCollector(true).runCycle();
            assertThat(first.state()).as("last error: %s", latestRunError()).isEqualTo(State.COMPLETED);
            GameweekFinalityReader reader = new GameweekFinalityReader(jdbc, clock, TABLES);
            var initial = reader.read(SEASON_ID, season.code(), Set.of(3)).get(3);
            assertThat(initial.state()).isEqualTo(GameweekFinality.State.FINALIZED);
            String manifestJson = jdbc.queryForObject("SELECT final_attempt_population_manifest::text FROM "
                    + TABLES.gameweekStatSync() + " WHERE season_id=? AND gameweek=3", String.class, SEASON_ID);
            var manifest = GameweekFinalityRepository.populationManifest(manifestJson);
            assertThat(manifest.population().sourceCatalogueMembershipRequired()).isTrue();
            assertThat(manifest.population().participantEvidenceByFixture()).containsKey(setup.fixtureId());

            Map<String, List<Object>> rows = pfsRowsSnapshot(Set.of(setup.fixtureId()));
            Map<String, List<Object>> pfsProof = pfsProofSnapshot(Set.of(setup.fixtureId()));
            Map<String, String> pageWatermarks = pageWatermarkSnapshot();
            Map<String, Object> finality = finalitySnapshot();
            Map<String, Object> finalizedPageProof = finalizedPageProofSnapshot();
            Map<String, Object> sourceCatalogue = sourceCatalogueSnapshot();
            Map<String, Long> writes = unit6WriteCounts();
            List<Long> pageEpochs = jdbc.query("""
                    SELECT DISTINCT page_run.runtime_epoch
                    FROM %s finality
                    CROSS JOIN LATERAL jsonb_each(finality.finalized_page_evidence) page
                    JOIN %s page_run ON page_run.generation=(page.value->>'generation')::bigint
                    WHERE finality.season_id=? AND finality.gameweek=3
                    ORDER BY page_run.runtime_epoch
                    """.formatted(TABLES.gameweekStatSync(), TABLES.run()),
                    (rs, row) -> rs.getLong(1), SEASON_ID);
            assertThat(pageEpochs).containsExactly(runtimeFence.epoch());

            long finalizedEpoch = runtimeFence.epoch();
            PlannerRuntimeRepository runtime = new PlannerRuntimeRepository(jdbc, tx, clock, TABLES);
            runtime.deactivate(runtimeFence);
            runtimeFence = runtime.activate(season.code(), OwnedSyncMode.ACTIVE);
            assertThat(runtimeFence.epoch()).isGreaterThan(finalizedEpoch);
            assertThat(reader.read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                    .isEqualTo(GameweekFinality.State.FINALIZED);
            store = new OwnedSyncStore(jdbc, tx, clock, TABLES, runtimeFence);
            publisher = new IncrementalPlayerFixturePublisher(
                    new IncrementalPfsRepository(jdbc, clock, store, TABLES));

            fetch.clearCalls();
            clock.set(clock.instant().plusSeconds(60));
            CycleResult resumed = compatibilityCollector(true).runCycle();

            assertThat(resumed.state()).as("last error: %s", latestRunError()).isEqualTo(State.COMPLETED);
            assertThat(resumed.selectedPlayerIds()).isEmpty();
            assertThat(fetch.count(EndpointKind.BOOTSTRAP_STATIC)).isOne();
            assertThat(fetch.count(EndpointKind.FIXTURES)).isOne();
            assertThat(fetch.count(EndpointKind.EVENT_LIVE)).isZero();
            assertThat(fetch.playerIds()).isEmpty();
            var after = reader.read(SEASON_ID, season.code(), Set.of(3)).get(3);
            assertThat(after.state()).isEqualTo(GameweekFinality.State.FINALIZED);
            assertThat(after.receipt()).isEqualTo(initial.receipt());
            assertThat(pfsRowsSnapshot(Set.of(setup.fixtureId()))).isEqualTo(rows);
            assertThat(pfsProofSnapshot(Set.of(setup.fixtureId()))).isEqualTo(pfsProof);
            assertThat(pageWatermarkSnapshot()).isEqualTo(pageWatermarks);
            assertThat(finalitySnapshot()).isEqualTo(finality);
            assertThat(finalizedPageProofSnapshot()).isEqualTo(finalizedPageProof);
            assertThat(sourceCatalogueSnapshot().get("membership_fingerprint"))
                    .isEqualTo(sourceCatalogue.get("membership_fingerprint"));
            assertThat(sourceCatalogueSnapshot().get("source_generation"))
                    .isEqualTo(sourceCatalogueSnapshot().get("latest_generation"));
            assertThat(((Number) sourceCatalogueSnapshot().get("latest_generation")).longValue())
                    .isGreaterThan(((Number) sourceCatalogue.get("latest_generation")).longValue());
            assertThat(jdbc.query("""
                    SELECT DISTINCT page_run.runtime_epoch
                    FROM %s finality
                    CROSS JOIN LATERAL jsonb_each(finality.finalized_page_evidence) page
                    JOIN %s page_run ON page_run.generation=(page.value->>'generation')::bigint
                    WHERE finality.season_id=? AND finality.gameweek=3
                    ORDER BY page_run.runtime_epoch
                    """.formatted(TABLES.gameweekStatSync(), TABLES.run()),
                    (rs, row) -> rs.getLong(1), SEASON_ID)).containsExactly(finalizedEpoch);
            Map<String, Long> writesAfter = unit6WriteCounts();
            for (String table : List.of("player_fixture_stats", TABLES.playerFixtureEvidence(),
                    TABLES.playerFixtureCurrent(), TABLES.gameweekStatSync())) {
                for (String operation : List.of("INSERT", "UPDATE", "DELETE")) {
                    String key = table + "/" + operation;
                    assertThat(writesAfter.getOrDefault(key, 0L)).as("no proof churn for %s", key)
                            .isEqualTo(writes.getOrDefault(key, 0L));
                }
            }
        } finally {
            removeUnit6WriteCounters();
        }
    }

    @Test
    void successfulEmptyAppearancePageRemainsOutstandingAndIsRetried() throws Exception {
        CheckedFixtureSetup setup = checkedSingleFixtureSetup();
        fetch.setPlayerPage(28, RecordedFetch.EMPTY_HISTORY);

        CycleResult first = collector(true).runCycle();

        assertThat(first.state()).isEqualTo(State.RETRY_SCHEDULED);
        assertThat(first.committedPlayerIds()).containsExactly(setup.cataloguePlayer(), 28);
        assertThat(jdbc.queryForObject("SELECT 28=ANY(final_attempt_outstanding_player_ids) FROM "
                + TABLES.gameweekStatSync() + " WHERE season_id=? AND gameweek=3", Boolean.class,
                SEASON_ID)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM player_fixture_stats"
                + " WHERE season_id=? AND fixture_id=? AND player_id=?", Integer.class,
                SEASON_ID, setup.fixtureId(), 28)).isZero();

        fetch.clearCalls();
        byte[] validHistoryPage = historyForAwayPlayer28(setup.fixtureId());
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> HistoryParser.parse(season, 28,
                document(validHistoryPage, new SourceRead(clock.instant(), clock.instant())),
                canonicalFixtures(setup.fixtureSource())))).isNull();
        fetch.setPlayerPage(28, validHistoryPage);
        clock.set(first.nextDue().plusSeconds(1));
        CycleResult resumed = collector(true).runCycle();

        assertThat(resumed.state()).as("last error: %s", latestRunError()).isEqualTo(State.COMPLETED);
        assertThat(resumed.selectedPlayerIds()).containsExactly(28);
        assertThat(fetch.playerIds()).containsExactly(28);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM player_fixture_stats"
                + " WHERE season_id=? AND fixture_id=? AND player_id=?", Integer.class,
                SEASON_ID, setup.fixtureId(), 28)).isOne();
    }

    @Test
    void reassignedTerminalFixtureCannotFinalizeTheCheckedGameweek() throws Exception {
        CheckedFixtureSetup setup = checkedSingleFixtureSetup();
        byte[] terminalSchedule = changeFixtureScore(setup.fixtureSource(), setup.fixtureId(), 3);
        fetch.setTerminalSources(null, terminalSchedule);

        CycleResult result = collector(true).runCycle();

        assertThat(result.state()).isEqualTo(State.RETRY_SCHEDULED);
        assertThat(fetch.count(EndpointKind.BOOTSTRAP_STATIC)).isEqualTo(2);
        assertThat(fetch.count(EndpointKind.FIXTURES)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT finalized_at IS NULL FROM " + TABLES.gameweekStatSync()
                + " WHERE season_id=? AND gameweek=3", Boolean.class, SEASON_ID)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                + " WHERE producer_key=? AND season=? AND state='FAILED' AND last_error LIKE ?", Integer.class,
                OwnedSyncStore.INCREMENTAL_CORE_PRODUCER, season.code(), "%source changed during final sweep%"))
                .isOne();
    }

    @Test
    void checkedSweepKeepsValidPageVisibleAndResumeFetchesOnlyTheFailedPage() throws Exception {
        CheckedFixtureSetup setup = checkedSingleFixtureSetup();
        fetch.failPlayer(setup.cataloguePlayer());

        CycleResult first = collector(true).runCycle();

        assertThat(first.state()).as("last error: %s", latestRunError())
                .isEqualTo(State.RETRY_SCHEDULED);
        assertThat(first.selectedPlayerIds()).containsExactly(setup.cataloguePlayer(), 28);
        assertThat(first.committedPlayerIds()).containsExactly(28);
        assertThat(first.failedPlayerIds()).containsExactly(setup.cataloguePlayer());
        assertThat(jdbc.queryForObject("SELECT row_state FROM " + TABLES.playerFixtureCurrent()
                + " WHERE season_id=? AND fixture_id=? AND player_id=?", String.class,
                SEASON_ID, setup.fixtureId(), 28)).isEqualTo("VALID");
        assertThat(jdbc.queryForObject("SELECT finalized_at IS NULL FROM " + TABLES.gameweekStatSync()
                + " WHERE season_id=? AND gameweek=3", Boolean.class, SEASON_ID)).isTrue();
        Instant initialStart = fetch.successfulReads().stream()
                .filter(read -> read.endpoint().kind() == EndpointKind.BOOTSTRAP_STATIC)
                .map(read -> read.read().startedAt()).findFirst().orElseThrow();

        fetch.clearCalls();
        clock.set(first.nextDue().plusSeconds(1));
        CycleResult resumed = collector(true).runCycle();

        assertThat(resumed.state()).isEqualTo(State.COMPLETED);
        assertThat(resumed.selectedPlayerIds()).containsExactly(setup.cataloguePlayer());
        assertThat(resumed.committedPlayerIds()).containsExactly(setup.cataloguePlayer());
        assertThat(fetch.playerIds()).containsExactly(setup.cataloguePlayer());
        assertThat(fetch.count(EndpointKind.BOOTSTRAP_STATIC)).isEqualTo(2);
        assertThat(fetch.count(EndpointKind.FIXTURES)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.gameweekStatSync()
                + " WHERE season_id=? AND gameweek=3", String.class, SEASON_ID)).isEqualTo("FINALIZED");
        Instant terminalFinish = fetch.successfulReads().getLast().read().finishedAt();
        var receipt = jdbc.queryForObject("SELECT finalized_source_read_started_at,"
                + " finalized_source_read_finished_at FROM " + TABLES.gameweekStatSync()
                + " WHERE season_id=? AND gameweek=3", (rs, row) -> new SourceRead(
                        rs.getTimestamp(1).toInstant(), rs.getTimestamp(2).toInstant()), SEASON_ID);
        assertThat(receipt).isEqualTo(new SourceRead(initialStart, terminalFinish));
    }

    @Test
    void omittedFailedPageRemainsRequiredAcrossChangedPopulationAndLeaseRestart() throws Exception {
        int fixtureId = fixtureExplainedFor(eventFourLive, 658);
        int retainedPlayer = 99002;
        byte[] sourceFixtures = emptyFixtureStats(onlyCollectorFixtureFinished(fixtures, fixtureId));
        List<CanonicalFixture> canonical = canonicalFixtures(sourceFixtures);
        Lease presentLease = nextCoreLease();
        SourceRead presentRead = nextRead();
        byte[] retainedHistory = onlyHistoryForFixture(resource("element-summary-658.json"), fixtureId,
                retainedPlayer);
        publisher.publish(presentLease, season, retainedPlayer, document(retainedHistory, presentRead), canonical);
        store.completeStaged(presentLease, presentRead, List.of(presentRead));
        Lease withdrawnLease = nextCoreLease();
        SourceRead oldWithdrawalRead = nextRead();
        publisher.publish(withdrawnLease, season, retainedPlayer,
                document("{\"history\":[]}".getBytes(StandardCharsets.UTF_8), oldWithdrawalRead), canonical);
        store.completeStaged(withdrawnLease, oldWithdrawalRead, List.of(oldWithdrawalRead));

        byte[] checkedBootstrap = markGameweekChecked(bootstrap, 4);
        Lease lease = nextCoreLease();
        Instant initialStart = clock.instant();
        SourceRead initialRead = new SourceRead(initialStart, initialStart.plusMillis(3));
        clock.set(initialRead.finishedAt().plusMillis(1));
        var initialSnapshot = CatalogueScheduleParser.parse(season,
                document(checkedBootstrap, new SourceRead(initialStart, initialStart.plusMillis(1))),
                document(sourceFixtures, new SourceRead(initialStart.plusMillis(2), initialRead.finishedAt())));
        var source = dev.quantti.fplassistant.sync.GameweekFinality.SourceSnapshot.from(initialSnapshot, 4);
        var populationAB = new dev.quantti.fplassistant.sync.GameweekFinality.RequiredPopulation(
                OwnedSyncStore.sha256(checkedBootstrap), Map.of(),
                Map.of(fixtureId, Set.of(LOCAL_HTTP_PLAYER, retainedPlayer)), Map.of());
        store.observeIncrementalSnapshot(lease, initialSnapshot);
        store.recordIncrementalPopulation(lease, Map.of(fixtureId, Set.of(LOCAL_HTTP_PLAYER, retainedPlayer)),
                Map.of(fixtureId, "a".repeat(64)), initialRead);
        assertThat(oldWithdrawalRead.startedAt()).isBefore(initialRead.finishedAt());
        GameweekFinalityRepository finality = new GameweekFinalityRepository(jdbc, store, TABLES, clock);
        var attempt = finality.beginOrResume(lease, season, source, populationAB, initialRead);
        assertThat(attempt.checkpoint().requiredPlayerIds()).containsExactly(retainedPlayer, LOCAL_HTTP_PLAYER);
        assertThat(attempt.checkpoint().outstandingPlayerIds()).containsExactly(retainedPlayer, LOCAL_HTTP_PLAYER);
        // B's old WITHDRAWN pointer predates the participant manifest; its later page attempt failed.

        byte[] changedBootstrapIdentity = "changed-bootstrap".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var populationA = new dev.quantti.fplassistant.sync.GameweekFinality.RequiredPopulation(
                OwnedSyncStore.sha256(changedBootstrapIdentity), Map.of(),
                Map.of(fixtureId, Set.of(LOCAL_HTTP_PLAYER)), Map.of());
        SourceRead changedPopulationRead = new SourceRead(clock.instant().plusMillis(1),
                clock.instant().plusMillis(2));
        clock.set(changedPopulationRead.finishedAt());
        assertThat(populationA.fingerprint(source)).isNotEqualTo(populationAB.fingerprint(source));
        var changed = finality.beginOrResume(lease, season, source, populationA, changedPopulationRead);
        assertThat(changed.checkpoint().requiredPlayerIds()).containsExactly(retainedPlayer, LOCAL_HTTP_PLAYER);
        assertThat(changed.checkpoint().outstandingPlayerIds()).containsExactly(retainedPlayer, LOCAL_HTTP_PLAYER);
        assertThat(changed.sourceReadInterval().startedAt()).isEqualTo(changedPopulationRead.startedAt());

        clock.set(lease.expiresAt().plusMillis(1));
        Lease restartedLease = store.acquireIncrementalCoreImport(season.code(), "finality-restarted",
                Duration.ofMinutes(30)).orElseThrow();
        SourceRead restartRead = new SourceRead(clock.instant(), clock.instant().plusMillis(1));
        clock.set(restartRead.finishedAt());
        var resumed = finality.beginOrResume(restartedLease, season, source, populationA, restartRead);
        assertThat(resumed.checkpoint().requiredPlayerIds()).containsExactly(retainedPlayer, LOCAL_HTTP_PLAYER);
        assertThat(resumed.sourceReadInterval().startedAt()).isEqualTo(changedPopulationRead.startedAt());
        assertThat(resumed.initialSourceReadInterval()).isEqualTo(changedPopulationRead);

        byte[] history = onlyHistoryForFixture(resource("element-summary-658.json"), fixtureId, LOCAL_HTTP_PLAYER);
        SourceRead freshPageRead = new SourceRead(restartRead.finishedAt().plusMillis(1),
                restartRead.finishedAt().plusMillis(2));
        clock.set(freshPageRead.finishedAt());
        publisher.publish(restartedLease, season, LOCAL_HTTP_PLAYER, document(history, freshPageRead),
                initialSnapshot.fixtures().stream().map(HistoryParser::canonicalFixture).toList());
        var partial = finality.beginOrResume(restartedLease, season, source, populationA, restartRead);
        assertThat(partial.checkpoint().completedPlayerIds()).containsExactly(LOCAL_HTTP_PLAYER);
        assertThat(partial.checkpoint().outstandingPlayerIds()).containsExactly(retainedPlayer);
        assertThat(partial.checkpoint().requiredPlayerIds()).containsExactly(retainedPlayer, LOCAL_HTTP_PLAYER);

        SourceRead endRead = new SourceRead(freshPageRead.finishedAt().plusMillis(1),
                freshPageRead.finishedAt().plusMillis(3));
        var endSnapshot = CatalogueScheduleParser.parse(season,
                document(checkedBootstrap, new SourceRead(endRead.startedAt(), endRead.startedAt().plusMillis(1))),
                document(sourceFixtures, new SourceRead(endRead.startedAt().plusMillis(2), endRead.finishedAt())));
        clock.set(endRead.finishedAt());
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> finality.finalizeSweep(restartedLease, season,
                GameweekFinality.SourceSnapshot.from(endSnapshot, 4),
                populationA, endRead))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("checkpoint no longer matches the current revision");
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.gameweekStatSync()
                + " WHERE season_id=? AND gameweek=4", String.class, SEASON_ID)).isEqualTo("FINAL_PENDING");
        assertThat(jdbc.queryForObject("SELECT finalized_at IS NULL FROM " + TABLES.gameweekStatSync()
                + " WHERE season_id=? AND gameweek=4", Boolean.class, SEASON_ID)).isTrue();
    }

    @Test
    void completeBlankCheckedGameweekFinalizesWithoutInventingPlayerPages() throws Exception {
        byte[] checkedBootstrap = markGameweekChecked(bootstrap, 4);
        byte[] blankFixtures = withoutAssignedFixtures(fixtures);
        SourceRead initialRead = new SourceRead(START, START.plusMillis(3));
        var initialSnapshot = CatalogueScheduleParser.parse(season,
                document(checkedBootstrap, new SourceRead(START, START.plusMillis(1))),
                document(blankFixtures, new SourceRead(START.plusMillis(2), initialRead.finishedAt())));
        var source = dev.quantti.fplassistant.sync.GameweekFinality.SourceSnapshot.from(initialSnapshot, 4);
        var population = new dev.quantti.fplassistant.sync.GameweekFinality.RequiredPopulation(
                OwnedSyncStore.sha256(checkedBootstrap), Map.of(), Map.of(), Map.of());
        assertThat(source.assignedFixtures()).isEmpty();
        store.enqueueIncrementalCoreImport(season, START);
        Lease lease = store.acquireIncrementalCoreImport(season.code(), "blank-finality", Duration.ofMinutes(30))
                .orElseThrow();
        store.observeIncrementalSnapshot(lease, initialSnapshot);
        clock.set(initialRead.finishedAt());
        GameweekFinalityRepository finality = new GameweekFinalityRepository(jdbc, store, TABLES, clock);
        var checkpoint = finality.beginOrResume(lease, season, source, population, initialRead).checkpoint();
        assertThat(checkpoint.requiredPlayerIds()).isEmpty();
        assertThat(checkpoint.outstandingPlayerIds()).isEmpty();

        SourceRead endRead = new SourceRead(checkpoint.sourceFloor().plusMillis(1),
                checkpoint.sourceFloor().plusMillis(3));
        var endSnapshot = CatalogueScheduleParser.parse(season,
                document(checkedBootstrap, new SourceRead(endRead.startedAt(), endRead.startedAt().plusMillis(1))),
                document(blankFixtures, new SourceRead(endRead.startedAt().plusMillis(2), endRead.finishedAt())));
        clock.set(endRead.finishedAt());
        var receipt = finality.finalizeSweep(lease, season,
                dev.quantti.fplassistant.sync.GameweekFinality.SourceSnapshot.from(endSnapshot, 4),
                population, endRead);

        assertThat(receipt.mandatoryManifestComplete()).isTrue();
        assertThat(receipt.sourceReadInterval()).isEqualTo(new SourceRead(initialRead.startedAt(), endRead.finishedAt()));
        var blankProgress = finality.load(lease, season, 4).orElseThrow();
        assertThat(blankProgress.pageReadInterval()).isNull();
        assertThat(blankProgress.finalizedSourceReadInterval()).isEqualTo(receipt.sourceReadInterval());
        assertThat(store.incrementalSourceInterval(lease)).isEqualTo(receipt.sourceReadInterval());
        assertThat(receipt.optionalCoverage().values()).allSatisfy(coverage -> {
            assertThat(coverage.sourceRows()).isZero();
            assertThat(coverage.presentRows()).isZero();
        });
        assertThat(finality.load(lease, season, 4).orElseThrow().state())
                .isEqualTo(dev.quantti.fplassistant.sync.GameweekFinality.State.FINALIZED);
    }

    @Test
    void checkedFlagRegressionReopensAndReturningCheckedSourceBecomesPendingAgain() throws Exception {
        int fixtureId = fixtureExplainedFor(eventFourLive, 658);
        byte[] sourceFixtures = emptyFixtureStats(onlyCollectorFixtureFinished(fixtures, fixtureId));
        byte[] checkedBootstrap = markGameweekChecked(bootstrap, 4);
        byte[] uncheckedBootstrap = markGameweekUnchecked(checkedBootstrap, 4);
        store.enqueueIncrementalCoreImport(season, START);
        Lease lease = store.acquireIncrementalCoreImport(season.code(), "finality-transition", Duration.ofMinutes(30))
                .orElseThrow();

        SourceRead firstRead = new SourceRead(START, START.plusMillis(3));
        var checked = CatalogueScheduleParser.parse(season,
                document(checkedBootstrap, new SourceRead(START, START.plusMillis(1))),
                document(sourceFixtures, new SourceRead(START.plusMillis(2), firstRead.finishedAt())));
        clock.set(firstRead.finishedAt());
        store.observeIncrementalSnapshot(lease, checked);
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.gameweekStatSync()
                + " WHERE season_id=? AND gameweek=4", String.class, SEASON_ID)).isEqualTo("FINAL_PENDING");

        SourceRead regressionRead = new SourceRead(START.plusSeconds(10), START.plusSeconds(13));
        var regressed = CatalogueScheduleParser.parse(season,
                document(uncheckedBootstrap, new SourceRead(regressionRead.startedAt(), regressionRead.startedAt()
                        .plusMillis(1))),
                document(sourceFixtures, new SourceRead(regressionRead.startedAt().plusMillis(2),
                        regressionRead.finishedAt())));
        clock.set(regressionRead.finishedAt());
        store.observeIncrementalSnapshot(lease, regressed);
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.gameweekStatSync()
                + " WHERE season_id=? AND gameweek=4", String.class, SEASON_ID)).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT current_invalidation_revision FROM "
                + TABLES.gameweekStatSync() + " WHERE season_id=? AND gameweek=4", Long.class, SEASON_ID))
                .isEqualTo(1L);

        SourceRead returnedRead = new SourceRead(START.plusSeconds(20), START.plusSeconds(23));
        var returned = CatalogueScheduleParser.parse(season,
                document(checkedBootstrap, new SourceRead(returnedRead.startedAt(), returnedRead.startedAt()
                        .plusMillis(1))),
                document(sourceFixtures, new SourceRead(returnedRead.startedAt().plusMillis(2),
                        returnedRead.finishedAt())));
        clock.set(returnedRead.finishedAt());
        store.observeIncrementalSnapshot(lease, returned);
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.gameweekStatSync()
                + " WHERE season_id=? AND gameweek=4", String.class, SEASON_ID)).isEqualTo("FINAL_PENDING");
        assertThat(jdbc.queryForObject("SELECT current_invalidation_revision FROM "
                + TABLES.gameweekStatSync() + " WHERE season_id=? AND gameweek=4", Long.class, SEASON_ID))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT first_checked_at FROM " + TABLES.gameweekStatSync()
                + " WHERE season_id=? AND gameweek=4", java.sql.Timestamp.class, SEASON_ID).toInstant())
                .isEqualTo(returnedRead.finishedAt());
    }

    @Test
    void historyOnlyCorrectionInvalidatesBothOldAndNewGameweeksWhileCheckedFlagsRemainTrue() {
        store.enqueueIncrementalCoreImport(season, START);
        Lease lease = store.acquireIncrementalCoreImport(season.code(), "history-only-finality",
                Duration.ofMinutes(30)).orElseThrow();
        String signature = "a".repeat(64);
        for (int gameweek : List.of(2, 4)) {
            jdbc.update("""
                    INSERT INTO %s(season_id, season_code, gameweek, state, event_finished, data_checked,
                        fixture_manifest_complete, all_assigned_fixtures_finished, source_signature,
                        first_checked_at, current_invalidation_revision, current_invalidation_floor, updated_at)
                    VALUES (?, ?, ?, 'FINAL_PENDING', TRUE, TRUE, TRUE, TRUE, ?, ?, 0, ?, ?)
                    """.formatted(TABLES.gameweekStatSync()), SEASON_ID, season.code(), gameweek, signature,
                    java.sql.Timestamp.from(START), java.sql.Timestamp.from(START), java.sql.Timestamp.from(START));
        }
        SourceRead correction = new SourceRead(START.plusSeconds(10), START.plusSeconds(11));
        clock.set(correction.finishedAt());
        store.invalidateIncrementalGameweeks(lease, Set.of(
                new IncrementalPfsRepository.RowKey(SEASON_ID, season.code(), 2, 70001, LOCAL_HTTP_PLAYER),
                new IncrementalPfsRepository.RowKey(SEASON_ID, season.code(), 4, 70001, LOCAL_HTTP_PLAYER)), correction);

        for (int gameweek : List.of(2, 4)) {
            Map<String, Object> row = jdbc.queryForMap("SELECT state, event_finished, data_checked,"
                    + " source_signature, current_invalidation_revision, current_invalidation_floor FROM "
                    + TABLES.gameweekStatSync() + " WHERE season_id=? AND gameweek=?", SEASON_ID, gameweek);
            assertThat(row.get("state")).isEqualTo("FINAL_PENDING");
            assertThat(row.get("event_finished")).isEqualTo(true);
            assertThat(row.get("data_checked")).isEqualTo(true);
            assertThat(row.get("source_signature")).isEqualTo(signature);
            assertThat(row.get("current_invalidation_revision")).isEqualTo(1L);
            assertThat(((java.sql.Timestamp) row.get("current_invalidation_floor")).toInstant())
                    .isEqualTo(correction.finishedAt());
        }
    }

    @Test
    void importerAttachesOnlyToAnAlreadyActiveSeasonRuntime() {
        assertThat(CoalescedPlayerHistoryImportConfiguration.existingActiveFence(jdbc, TABLES, season.code()))
                .isEqualTo(runtimeFence);
        jdbc.update("UPDATE " + TABLES.plannerRuntime()
                + " SET runtime_mode='STAGING' WHERE instance_id=?", "mode:" + season.code());

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                CoalescedPlayerHistoryImportConfiguration.existingActiveFence(jdbc, TABLES, season.code())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("already ACTIVE");
        assertThat(fetch.calls()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run(), Integer.class)).isZero();

        runtimeFence = new PlannerRuntimeRepository(jdbc, tx, clock, TABLES)
                .activate(season.code(), OwnedSyncMode.ACTIVE);
    }

    @Test
    void configuredSeasonMustStillBeTheDatabaseCurrentSeason() {
        fetch.setSources(bootstrap, fixtures, eventThreeLive, eventFourLive);
        SharedStatsSeed.ensure(jdbc);
        SharedStatsSeed.setCurrentSeason(jdbc, SharedStatsSeed.CURRENT_SEASON_ID);
        try {
            assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> collector(true).runCycle()))
                    .isInstanceOf(OwnedSyncStore.RuntimeFenceLostException.class);
            assertThat(fetch.calls()).isEmpty();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run(), Integer.class)).isZero();
        } finally {
            SharedStatsSeed.setCurrentSeason(jdbc, SEASON_ID);
        }
    }

    @Test
    void actualLocalHttpUsesSharedBudgetAndKeepsLegacyProducerIsolated() throws Exception {
        int fixtureId = fixtureExplainedFor(eventFourLive, 109);
        byte[] source = emptyFixtureStats(onlyCollectorFixtureFinished(fixtures, fixtureId));
        int cataloguePlayer = firstPlayerForFixture(bootstrap, source, fixtureId);
        byte[] bootstrapBody = relabelBootstrapPlayer(
                bootstrapOnlyPlayer(bootstrap, cataloguePlayer), cataloguePlayer, LOCAL_HTTP_PLAYER);
        byte[] liveBody = relabelLivePlayer(eventFourLive, 109, LOCAL_HTTP_PLAYER);
        byte[] historyBody = historyWithMinutes(resource("element-summary-658.json"), fixtureId,
                LOCAL_HTTP_PLAYER, 90);
        HistoryParser.parse(season, LOCAL_HTTP_PLAYER, document(historyBody,
                new SourceRead(START, START.plusMillis(1))), canonicalFixtures(source));
        byte[] emptyHistory = "{\"history\":[]}".getBytes(StandardCharsets.UTF_8);
        List<String> paths = java.util.Collections.synchronizedList(new ArrayList<>());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/", exchange -> respondLocal(exchange, paths, bootstrapBody, source,
                liveBody, historyBody, emptyHistory, clock, LOCAL_HTTP_PLAYER));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/");
        jdbc.update("INSERT INTO " + TABLES.requestState()
                + "(singleton) VALUES (TRUE) ON CONFLICT DO NOTHING");
        OwnedSyncRequestBudget budget = new OwnedSyncRequestBudget(jdbc, tx, clock, TABLES);
        OwnedSyncHttpClient http = new OwnedSyncHttpClient(budget, base, Duration.ofSeconds(5), clock);
        AtomicInteger spacingWaits = new AtomicInteger();
        http.setWaitObserverForTests(wait -> {
            if (wait.phase() == OwnedSyncHttpClient.WaitPhase.START
                    && wait.kind() == OwnedSyncHttpClient.WaitKind.SPACING) {
                spacingWaits.incrementAndGet();
                clock.set(wait.nextDue());
            }
        });
        try {
            String publicationKey = "player-history:" + LOCAL_HTTP_PLAYER;
            store.enqueue(SyncKind.PLAYER_HISTORY, season.code(), publicationKey, publicationKey, clock.instant());
            CoalescedPlayerHistoryCollector.Fetch loopbackFetch = (lease, endpoint, attempt, floor) -> {
                OwnedSyncHttpClient.Result fetched = http.get(endpoint, attempt,
                        () -> store.httpFence(lease, endpoint, floor));
                if (fetched instanceof DeferredResult deferred
                        && deferred.reason() == DeferredReason.HTTP_NOT_SUBMITTED
                        && deferred.nextDue().isAfter(clock.instant())) {
                    clock.set(deferred.nextDue());
                    fetched = http.get(endpoint, attempt, () -> store.httpFence(lease, endpoint, floor));
                }
                return fetched;
            };
            CycleResult result = new CoalescedPlayerHistoryCollector(season, true, store, publisher,
                    loopbackFetch, clock, "local-http-collector").runCycle();

            String lastError = jdbc.queryForObject("SELECT last_error FROM " + TABLES.run()
                    + " WHERE producer_key=? AND publication_key=?", String.class,
                    OwnedSyncStore.INCREMENTAL_CORE_PRODUCER, OwnedSyncStore.INCREMENTAL_CORE_KEY);
            assertThat(result.state()).as(result + " paths=" + paths + " error=" + lastError)
                    .isEqualTo(State.COMPLETED);
            assertThat(result.committedPlayerIds()).contains(LOCAL_HTTP_PLAYER);
            assertThat(paths).contains("/api/element-summary/" + LOCAL_HTTP_PLAYER + "/");
            assertThat(spacingWaits).hasValueGreaterThan(0);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM player_fixture_stats WHERE season_id=? "
                    + "AND fixture_id=? AND player_id=?", Integer.class, SEASON_ID, fixtureId,
                    LOCAL_HTTP_PLAYER)).isOne();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                    + " WHERE producer_key='LEGACY' AND publication_key=? AND state='PENDING'",
                    Integer.class, publicationKey)).isOne();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                    + " WHERE producer_key=? AND publication_key=? AND state='COMPLETED'", Integer.class,
                    OwnedSyncStore.INCREMENTAL_CORE_PRODUCER, OwnedSyncStore.INCREMENTAL_CORE_KEY)).isOne();

            Lease nextLease = nextCoreLease();
            OwnedSyncHttpClient secondTransport = new OwnedSyncHttpClient(budget, base,
                    Duration.ofSeconds(5), clock);
            secondTransport.setWaitObserverForTests(wait -> {
                if (wait.phase() == OwnedSyncHttpClient.WaitPhase.START
                        && wait.kind() == OwnedSyncHttpClient.WaitKind.SPACING) clock.set(wait.nextDue());
            });
            try {
                var sharedPermitRead = secondTransport.get(Endpoint.bootstrap(), 1,
                        () -> store.httpFence(nextLease, Endpoint.bootstrap(),
                                store.requestedSourceAfter(nextLease)));
                assertThat(sharedPermitRead).isInstanceOf(Response.class);
            } finally {
                secondTransport.close();
            }

            List<java.sql.Timestamp> admissions = jdbc.queryForList("SELECT admitted_at FROM "
                    + TABLES.requestAttempt() + " ORDER BY admitted_at", java.sql.Timestamp.class);
            assertThat(admissions).hasSizeGreaterThanOrEqualTo(paths.size()).hasSizeLessThan(5_000);
            for (int index = 1; index < admissions.size(); index++) {
                assertThat(admissions.get(index).toInstant())
                        .isAfterOrEqualTo(admissions.get(index - 1).toInstant().plusSeconds(1));
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.requestAttempt()
                    + " WHERE completed_at IS NULL", Integer.class)).isZero();
        } finally {
            http.close();
            server.stop(0);
        }
    }

    @Test
    void disabledCollectorDoesNotEnqueueOrFetch() {
        fetch.setSources(bootstrap, fixtures, eventThreeLive, eventFourLive);
        CoalescedPlayerHistoryCollector disabled = new CoalescedPlayerHistoryCollector(season, store,
                publisher, fetch, clock, "disabled-test");

        assertThat(disabled.runCycle().state()).isEqualTo(State.DISABLED);
        assertThat(fetch.calls()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run(), Integer.class)).isZero();
    }

    private String latestRunError() {
        return jdbc.queryForObject("SELECT last_error FROM " + TABLES.run()
                + " WHERE producer_key=? AND season=? ORDER BY generation DESC LIMIT 1", String.class,
                OwnedSyncStore.INCREMENTAL_CORE_PRODUCER, season.code());
    }

    @Test
    void importerWatchdogRecoversLegacyFixtureOnlyAfterCheckedSourceAndRowsAreFinalized() throws Exception {
        CheckedFixtureSetup setup = checkedSingleFixtureSetup();
        jdbc.update("INSERT INTO " + TABLES.requestState()
                + "(singleton) VALUES (TRUE) ON CONFLICT (singleton) DO NOTHING");
        String key = "fixture-overdue:" + season.code() + ":" + setup.fixtureId();
        jdbc.update("INSERT INTO " + TABLES.alert()
                        + "(incident_key,occurrence,event_type,category,severity,season,fixture_id,"
                        + "payload,delivery_state,next_delivery_at,created_at) "
                        + "VALUES (?,1,'INCIDENT','FIXTURE_VERIFICATION_OVERDUE','WARNING',?,?,"
                        + "'{}','PENDING',?,?)",
                key, season.code(), setup.fixtureId(), java.sql.Timestamp.from(clock.instant()),
                java.sql.Timestamp.from(clock.instant()));
        String gameweekKey = "incremental-gameweek:" + season.code() + ":3";
        jdbc.update("INSERT INTO " + TABLES.alert()
                        + "(incident_key,occurrence,event_type,category,severity,season,"
                        + "payload,delivery_state,next_delivery_at,created_at) "
                        + "VALUES (?,1,'INCIDENT','INCREMENTAL_GAMEWEEK_OVERDUE','WARNING',?,"
                        + "'{}','PENDING',?,?)",
                gameweekKey, season.code(), java.sql.Timestamp.from(clock.instant()),
                java.sql.Timestamp.from(clock.instant()));
        JdbcTemplate boundedJdbc = new JdbcTemplate(jdbc.getDataSource());
        boundedJdbc.setQueryTimeout(5);
        TransactionTemplate boundedTx = new TransactionTemplate(tx.getTransactionManager());
        boundedTx.setTimeout(5);
        List<String> delivered = new java.util.ArrayList<>();
        OwnedSyncWatchdog watchdog = new OwnedSyncWatchdog(boundedJdbc, boundedTx, clock, TABLES,
                new OwnedSyncWatchdog.Notification(true, true), delivered::add);
        watchdog.checkImporter(season.code());
        assertThat(watchdog.deliver()).isGreaterThanOrEqualTo(2);
        assertThat(delivered).hasSizeGreaterThanOrEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.alert()
                + " WHERE incident_key IN (?,?) AND delivery_state='DELIVERED'", Integer.class,
                key, gameweekKey)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.alert()
                + " WHERE incident_key=? AND event_type='RECOVERY'", Integer.class, key)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.alert()
                + " WHERE incident_key=? AND event_type='RECOVERY'", Integer.class, gameweekKey)).isZero();

        CycleResult completed = compatibilityCollector(true).runCycle();
        assertThat(completed.state()).as("last error: %s", latestRunError()).isEqualTo(State.COMPLETED);
        assertThat(new GameweekFinalityReader(jdbc, clock, TABLES)
                .read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                .isEqualTo(GameweekFinality.State.FINALIZED);
        watchdog.checkImporter(season.code());
        assertThat(watchdog.deliver()).isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.alert()
                + " WHERE incident_key=? AND event_type='RECOVERY'", Integer.class, key)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.alert()
                + " WHERE incident_key IN (?,?) AND event_type='RECOVERY' AND delivery_state='DELIVERED'",
                Integer.class, key, gameweekKey)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.alert()
                + " WHERE incident_key=? AND event_type='RECOVERY'", Integer.class, gameweekKey)).isOne();
    }

    private void assertFinalizedEvidenceTamperReopens(java.util.function.Consumer<CheckedFixtureSetup> tamper)
            throws Exception {
        CheckedFixtureSetup setup = checkedSingleFixtureSetup();
        CycleResult finalized = compatibilityCollector(true).runCycle();
        assertThat(finalized.state()).as("last error: %s", latestRunError()).isEqualTo(State.COMPLETED);
        GameweekFinalityReader reader = new GameweekFinalityReader(jdbc, clock, TABLES);
        assertThat(reader.read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                .isEqualTo(GameweekFinality.State.FINALIZED);
        String manifestJson = jdbc.queryForObject("SELECT final_attempt_population_manifest::text FROM "
                + TABLES.gameweekStatSync() + " WHERE season_id=? AND gameweek=3", String.class, SEASON_ID);
        var manifest = GameweekFinalityRepository.populationManifest(manifestJson);
        assertThat(manifest.population().sourceCatalogueMembershipRequired()).isTrue();
        assertThat(manifest.population().participantEvidenceByFixture()).containsKey(setup.fixtureId());
        Map<String, String> previousPageWatermarks = pageWatermarkSnapshot();
        Map<String, Object> previousFinalizedPageProof = finalizedPageProofSnapshot();

        tamper.accept(setup);
        assertThat(reader.read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                .isEqualTo(GameweekFinality.State.FINAL_PENDING);
        fetch.clearCalls();
        clock.set(clock.instant().plusSeconds(60));
        CycleResult reopened = compatibilityCollector(true).runCycle();

        assertThat(reopened.state()).as("last error: %s", latestRunError()).isEqualTo(State.COMPLETED);
        assertThat(reopened.selectedPlayerIds()).containsExactly(setup.cataloguePlayer(), 28);
        assertThat(fetch.playerIds()).containsExactly(setup.cataloguePlayer(), 28);
        assertThat(fetch.count(EndpointKind.BOOTSTRAP_STATIC)).isEqualTo(2);
        assertThat(fetch.count(EndpointKind.FIXTURES)).isEqualTo(2);
        assertThat(reader.read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                .isEqualTo(GameweekFinality.State.FINALIZED);
        assertThat(pageWatermarkSnapshot()).isNotEqualTo(previousPageWatermarks);
        Map<String, Object> revalidatedPageProof = finalizedPageProofSnapshot();
        assertThat(revalidatedPageProof).isNotEqualTo(previousFinalizedPageProof);
        assertThat(((Number) revalidatedPageProof.get("finalized_run_generation")).longValue())
                .isGreaterThan(((Number) previousFinalizedPageProof.get("finalized_run_generation")).longValue());
        Map<String, Object> sourceCatalogue = sourceCatalogueSnapshot();
        assertThat(sourceCatalogue.get("source_generation")).isEqualTo(sourceCatalogue.get("latest_generation"));
    }

    private CycleResult collectWithExtraHistory(int historyMinutes, boolean includeZeroMinuteLive)
            throws Exception {
        CheckedFixtureSetup setup = checkedSingleFixtureSetup();
        byte[] checkedBootstrap = markGameweekChecked(bootstrapPlayers(bootstrap, Set.of(1, 2)), 3);
        Set<Integer> livePlayers = includeZeroMinuteLive ? Set.of(1, 2, 28) : Set.of(1, 28);
        byte[] live = liveForFixturePlayers(eventThreeLive, setup.fixtureId(), livePlayers);
        fetch.setSources(checkedBootstrap, setup.fixtureSource(), live, eventFourLive);
        fetch.setPlayerPage(2, historyWithMinutes(resource("element-summary-2.json"),
                setup.fixtureId(), 2, historyMinutes));
        return collector(true).runCycle();
    }

    private void seedReaderSource(CatalogueScheduleParser.Snapshot snapshot,
                                  GameweekFinality.SourceSnapshot source) {
        GameweekRow event = snapshot.gameweeks().stream().filter(row -> row.id() == source.gameweek())
                .findFirst().orElseThrow();
        jdbc.update("""
                INSERT INTO gameweek (id,season_id,name,deadline_time,finished,data_checked,is_current,is_next)
                VALUES (?,?,?,?,?,?,FALSE,FALSE)
                ON CONFLICT (id,season_id) DO UPDATE SET name=EXCLUDED.name,
                    deadline_time=EXCLUDED.deadline_time,finished=EXCLUDED.finished,
                    data_checked=EXCLUDED.data_checked,is_current=FALSE,is_next=FALSE
                """, event.id(), event.seasonId(), event.name(),
                java.sql.Timestamp.from(event.deadline()), event.finished(), event.dataChecked());
        for (TeamRow team : snapshot.teams()) {
            jdbc.update("""
                    INSERT INTO team (id,season_id,code,name,short_name)
                    VALUES (?,?,?,?,?)
                    ON CONFLICT (id,season_id) DO UPDATE SET code=EXCLUDED.code,
                        name=EXCLUDED.name,short_name=EXCLUDED.short_name
                    """, team.id(), team.seasonId(), team.code(), team.name(), team.shortName());
        }
        for (FixtureRow fixture : source.assignedFixtures()) {
            jdbc.update("""
                    INSERT INTO fixture (id,season_id,gameweek,code,team_h,team_a,team_h_score,team_a_score,
                        team_h_difficulty,team_a_difficulty,kickoff_time,started,finished,
                        finished_provisional,minutes,stats)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb)
                    ON CONFLICT (id,season_id) DO UPDATE SET gameweek=EXCLUDED.gameweek,
                        code=EXCLUDED.code,team_h=EXCLUDED.team_h,team_a=EXCLUDED.team_a,
                        team_h_score=EXCLUDED.team_h_score,team_a_score=EXCLUDED.team_a_score,
                        team_h_difficulty=EXCLUDED.team_h_difficulty,
                        team_a_difficulty=EXCLUDED.team_a_difficulty,kickoff_time=EXCLUDED.kickoff_time,
                        started=EXCLUDED.started,finished=EXCLUDED.finished,
                        finished_provisional=EXCLUDED.finished_provisional,minutes=EXCLUDED.minutes,
                        stats=EXCLUDED.stats
                    """, fixture.id(), fixture.seasonId(), fixture.gameweek(), fixture.code(),
                    fixture.homeTeamId(), fixture.awayTeamId(), fixture.homeScore(), fixture.awayScore(),
                    fixture.homeDifficulty(), fixture.awayDifficulty(),
                    fixture.kickoff() == null ? null : java.sql.Timestamp.from(fixture.kickoff()),
                    fixture.started(), fixture.finished(), fixture.fields().get("finished_provisional"),
                    fixture.fields().get("minutes"), fixture.fields().get("stats"));
        }
        for (PlayerRow player : snapshot.players()) {
            jdbc.update("""
                    INSERT INTO player (id,season_id,team_id,web_name,element_type,now_cost)
                    VALUES (?,?,?,?,?,?)
                    ON CONFLICT (id,season_id) DO UPDATE SET team_id=EXCLUDED.team_id,
                        web_name=EXCLUDED.web_name,element_type=EXCLUDED.element_type,now_cost=EXCLUDED.now_cost
                    """, player.id(), player.seasonId(), player.teamId(), player.webName(),
                    player.elementType(), player.nowCost());
        }
    }

    private static byte[] appendUncheckedFixtureParticipant(byte[] checkedFixtures, byte[] fullFixtures,
                                                              int fixtureId, int playerId) throws IOException {
        ArrayNode schedule = (ArrayNode) JSON.readTree(checkedFixtures);
        ObjectNode fixture = ((ObjectNode) findFixture(JSON.readTree(fullFixtures), fixtureId)).deepCopy();
        fixture.put("started", true);
        fixture.put("finished", true);
        fixture.put("finished_provisional", false);
        fixture.put("minutes", 90);
        ObjectNode bps = JSON.createObjectNode().put("identifier", "bps");
        bps.set("h", JSON.createArrayNode());
        ArrayNode away = JSON.createArrayNode();
        away.addObject().put("element", playerId).put("value", 1);
        bps.set("a", away);
        fixture.set("stats", JSON.createArrayNode().add(bps));
        for (int index = 0; index < schedule.size(); index++) {
            if (schedule.get(index).path("id").asInt() == fixtureId) {
                schedule.set(index, fixture);
                return JSON.writeValueAsBytes(schedule);
            }
        }
        throw new IllegalStateException("Missing unchecked fixture " + fixtureId);
    }

    private MultiFixtureSetup checkedDoubleFixtureSetup() throws Exception {
        byte[] bootstrap = resource("bootstrap-static.json");
        byte[] originalFixtures = resource("fixtures.json");
        byte[] eventThreeLive = resource("event-3-live.json");
        byte[] eventFourLive = resource("event-4-live.json");
        byte[] fixtureSource = reassignFixture(onlyCollectorFixtureFinished(originalFixtures, 29), 36, 3);
        ArrayNode schedule = (ArrayNode) JSON.readTree(fixtureSource);
        ((ObjectNode) findFixture(schedule, 36)).put("kickoff_time", "2026-09-07T17:00:00Z");
        Map<Integer, Set<Integer>> participantsByFixture = new LinkedHashMap<>();
        Map<Integer, byte[]> historyPages = new LinkedHashMap<>();
        Map<Integer, byte[]> liveByFixture = Map.of(29, eventThreeLive, 36, eventFourLive);
        Map<Integer, List<List<Integer>>> participantSides = Map.of(
                29, List.of(List.of(1, 4), List.of(28, 40)),
                36, List.of(List.of(544, 545), List.of(1, 4)));
        Set<Integer> participants = new LinkedHashSet<>();
        for (int fixtureId : List.of(29, 36)) {
            JsonNode fixture = findFixture(schedule, fixtureId);
            JsonNode original = findFixture(JSON.readTree(originalFixtures), fixtureId);
            JsonNode bps = null;
            for (JsonNode stat : original.path("stats")) {
                if (stat.path("identifier").asText().equals("bps")) { bps = stat; break; }
            }
            if (bps == null) throw new IllegalStateException("Missing recorded BPS template for " + fixtureId);
            ArrayNode home = JSON.createArrayNode();
            ArrayNode away = JSON.createArrayNode();
            Set<Integer> fixtureParticipants = new LinkedHashSet<>();
            for (int side = 0; side < 2; side++) {
                boolean isHome = side == 0;
                ArrayNode controlledSide = isHome ? home : away;
                for (int id : participantSides.get(fixtureId).get(side)) {
                    int value = fixtureStatValue(bps, id);
                    controlledSide.addObject().put("element", id).put("value", value);
                    fixtureParticipants.add(id);
                    participants.add(id);
                    historyPages.putIfAbsent(id, emptyHistoryPage());
                    addControlledHistory(historyPages, id, fixture, isHome, value,
                            fixtureSpecificLiveMinutes(liveByFixture.get(fixtureId), fixtureId, id));
                }
            }
            ObjectNode controlledBps = JSON.createObjectNode().put("identifier", "bps");
            controlledBps.set("h", home);
            controlledBps.set("a", away);
            ((ObjectNode) fixture).set("stats", JSON.createArrayNode().add(controlledBps));
            participantsByFixture.put(fixtureId, Set.copyOf(fixtureParticipants));
        }
        byte[] checkedBootstrap = markGameweekChecked(
                markAllGameweeksUnchecked(bootstrapPlayers(bootstrap, participants)), 3);
        byte[] live = liveForControlledFixtureSources(liveByFixture, participantsByFixture);
        fixtureSource = JSON.writeValueAsBytes(schedule);
        fetch.setSources(checkedBootstrap, fixtureSource, live, eventFourLive);
        historyPages.forEach(fetch::setPlayerPage);
        return new MultiFixtureSetup(Set.copyOf(participantsByFixture.keySet()), participantsByFixture,
                Set.copyOf(participants), Map.copyOf(historyPages), fixtureSource, checkedBootstrap, live);
    }

    private void publishCompatibilitySnapshot(MultiFixtureSetup setup) {
        Lease lease = nextCoreLease();
        Instant sourceStart = clock.instant().plusMillis(1);
        SourceRead bootstrapRead = new SourceRead(sourceStart, sourceStart.plusMillis(1));
        SourceRead fixturesRead = new SourceRead(sourceStart.plusMillis(2), sourceStart.plusMillis(3));
        SourceDocument bootstrapDocument = document(setup.bootstrapSource(), bootstrapRead);
        SourceDocument fixtureDocument = document(setup.fixtureSource(), fixturesRead);
        clock.set(fixturesRead.finishedAt().plusMillis(1));
        long bootstrapPayload = store.stage(lease, Endpoint.bootstrap(), bootstrapRead,
                OwnedSyncStore.Validation.unchecked(), setup.bootstrapSource());
        long fixturesPayload = store.stage(lease, Endpoint.fixtures(), fixturesRead,
                OwnedSyncStore.Validation.unchecked(), setup.fixtureSource());
        store.validateStagedPayloads(lease, List.of(bootstrapPayload, fixturesPayload),
                OwnedSyncStore.Validation.valid());
        new CompatibilityCatalogueWriter(jdbc, clock, store, TABLES, CAPTURED)
                .publish(lease, season, bootstrapDocument, fixtureDocument);
        store.completeStaged(lease, store.incrementalSourceInterval(lease), List.of(bootstrapRead, fixturesRead));
    }

    private MultiFixtureSetup checkedMultiFixtureSetup() throws Exception {
        ArrayNode schedule = (ArrayNode) JSON.readTree(fixtures);
        Map<Integer, Set<Integer>> participantsByFixture = new java.util.LinkedHashMap<>();
        Map<Integer, byte[]> historyPages = new java.util.LinkedHashMap<>();
        Set<Integer> participantIds = new java.util.TreeSet<>();
        for (JsonNode node : schedule) {
            int sourceGameweek = node.path("event").asInt(-1);
            if (sourceGameweek != 3) {
                ObjectNode unrelated = (ObjectNode) node;
                unrelated.put("started", false);
                unrelated.put("finished", false);
                unrelated.put("finished_provisional", false);
                unrelated.put("minutes", 0);
                unrelated.putNull("kickoff_time");
                unrelated.putNull("team_h_score");
                unrelated.putNull("team_a_score");
                continue;
            }
            ObjectNode fixture = (ObjectNode) node;
            int fixtureId = fixture.path("id").asInt();
            JsonNode bps = null;
            for (JsonNode stat : fixture.path("stats")) {
                if (stat.path("identifier").asText().equals("bps")) bps = stat;
            }
            if (bps == null || bps.path("h").size() < 2 || bps.path("a").size() < 2) {
                throw new IllegalStateException("Recorded GW3 fixture lacks controlled BPS participants: " + fixtureId);
            }
            ArrayNode home = JSON.createArrayNode();
            ArrayNode away = JSON.createArrayNode();
            Set<Integer> fixtureParticipants = new java.util.TreeSet<>();
            for (int i = 0; i < 2; i++) {
                JsonNode homeBps = bps.path("h").get(i);
                JsonNode awayBps = bps.path("a").get(i);
                int homeId = homeBps.path("element").asInt();
                int awayId = awayBps.path("element").asInt();
                home.add(homeBps.deepCopy());
                away.add(awayBps.deepCopy());
                fixtureParticipants.add(homeId);
                fixtureParticipants.add(awayId);
                participantIds.add(homeId);
                participantIds.add(awayId);
                addControlledHistory(historyPages, homeId, fixture, true, homeBps.path("value").asInt(),
                        fixtureSpecificLiveMinutes(eventThreeLive, fixtureId, homeId));
                addControlledHistory(historyPages, awayId, fixture, false, awayBps.path("value").asInt(),
                        fixtureSpecificLiveMinutes(eventThreeLive, fixtureId, awayId));
            }
            ObjectNode controlledBps = JSON.createObjectNode().put("identifier", "bps");
            controlledBps.set("h", home);
            controlledBps.set("a", away);
            fixture.set("stats", JSON.createArrayNode().add(controlledBps));
            participantsByFixture.put(fixtureId, Set.copyOf(fixtureParticipants));
        }
        if (participantsByFixture.size() != 10 || participantIds.size() != 40) {
            throw new IllegalStateException("Expected a controlled ten-fixture GW3 source population");
        }
        byte[] fixtureSource = JSON.writeValueAsBytes(schedule);
        Set<Integer> catalogueIds = new java.util.TreeSet<>(participantIds);
        for (JsonNode player : JSON.readTree(bootstrap).path("elements")) {
            if (catalogueIds.add(player.path("id").asInt())) break;
        }
        byte[] checkedBootstrap = markGameweekChecked(
                markAllGameweeksUnchecked(bootstrapPlayers(bootstrap, catalogueIds)), 3);
        byte[] live = liveForGameweekParticipants(eventThreeLive, participantsByFixture);
        fetch.setSources(checkedBootstrap, fixtureSource, live, eventFourLive);
        historyPages.forEach(fetch::setPlayerPage);
        return new MultiFixtureSetup(Set.copyOf(participantsByFixture.keySet()), participantsByFixture,
                Set.copyOf(participantIds), Map.copyOf(historyPages), fixtureSource, checkedBootstrap, live);
    }

    private static byte[] emptyHistoryPage() throws IOException {
        ObjectNode page = (ObjectNode) JSON.readTree(resource("element-summary-1.json"));
        page.set("history", JSON.createArrayNode());
        return JSON.writeValueAsBytes(page);
    }

    private static JsonNode findFixture(JsonNode source, int fixtureId) {
        for (JsonNode fixture : source) {
            if (fixture.path("id").asInt() == fixtureId) return fixture;
        }
        throw new IllegalStateException("Missing recorded fixture " + fixtureId);
    }

    private static byte[] liveForControlledFixtureSources(Map<Integer, byte[]> sourceByFixture,
            Map<Integer, Set<Integer>> participantsByFixture) throws IOException {
        ObjectNode root = (ObjectNode) JSON.readTree(sourceByFixture.values().iterator().next());
        Map<Integer, ObjectNode> elementsByPlayer = new LinkedHashMap<>();
        Set<Integer> seen = new HashSet<>();
        for (Map.Entry<Integer, Set<Integer>> fixture : participantsByFixture.entrySet()) {
            int fixtureId = fixture.getKey();
            JsonNode source = JSON.readTree(sourceByFixture.get(fixtureId));
            for (int playerId : fixture.getValue()) {
                JsonNode sourceElement = null;
                for (JsonNode element : source.path("elements")) {
                    if (element.path("id").asInt() == playerId) { sourceElement = element; break; }
                }
                if (sourceElement == null) throw new IllegalStateException("Missing live player " + playerId);
                ArrayNode selectedExplanations = JSON.createArrayNode();
                for (JsonNode explanation : sourceElement.path("explain")) {
                    if (explanation.path("fixture").asInt() == fixtureId) {
                        selectedExplanations.add(explanation.deepCopy());
                    }
                }
                if (selectedExplanations.isEmpty()) {
                    throw new IllegalStateException("Missing live fixture " + fixtureId);
                }
                ObjectNode selected = elementsByPlayer.get(playerId);
                if (selected == null) {
                    selected = ((ObjectNode) sourceElement).deepCopy();
                    selected.set("explain", JSON.createArrayNode());
                    elementsByPlayer.put(playerId, selected);
                }
                ((ArrayNode) selected.path("explain")).addAll(selectedExplanations);
                seen.add(playerId);
            }
        }
        Set<Integer> expected = participantsByFixture.values().stream().flatMap(Set::stream)
                .collect(java.util.stream.Collectors.toSet());
        if (!seen.equals(expected)) throw new IllegalStateException("Controlled live fixture set differs");
        ArrayNode elements = JSON.createArrayNode();
        elementsByPlayer.values().forEach(elements::add);
        root.set("elements", elements);
        return JSON.writeValueAsBytes(root);
    }

    private static byte[] changeFixtureLiveMinutes(byte[] source, int fixtureId, int playerId,
                                                   int minutes) throws IOException {
        ObjectNode root = (ObjectNode) JSON.readTree(source);
        for (JsonNode element : root.path("elements")) {
            if (element.path("id").asInt() != playerId) continue;
            for (JsonNode explanation : element.path("explain")) {
                if (explanation.path("fixture").asInt() != fixtureId) continue;
                for (JsonNode stat : explanation.path("stats")) {
                    if (!stat.path("identifier").asText().equals("minutes")) continue;
                    ((ObjectNode) stat).put("value", minutes);
                    if (stat.has("points")) ((ObjectNode) stat).put("points", minutes >= 60 ? 2 : 1);
                    return JSON.writeValueAsBytes(root);
                }
            }
        }
        throw new IllegalStateException("Missing live fixture minutes for player " + playerId);
    }

    private static int fixtureSpecificLiveMinutes(byte[] source, int fixtureId, int playerId) throws IOException {
        for (JsonNode element : JSON.readTree(source).path("elements")) {
            if (element.path("id").asInt() != playerId) continue;
            for (JsonNode explanation : element.path("explain")) {
                if (explanation.path("fixture").asInt() != fixtureId) continue;
                for (JsonNode stat : explanation.path("stats")) {
                    if (stat.path("identifier").asText().equals("minutes")) {
                        return stat.path("value").asInt();
                    }
                }
            }
        }
        throw new IllegalStateException("Missing controlled fixture minutes for player " + playerId);
    }

    private static void addControlledHistory(Map<Integer, byte[]> pages, int playerId, JsonNode fixture,
                                             boolean home, int bps, int minutes) throws IOException {
        ObjectNode page;
        ArrayNode history;
        if (pages.containsKey(playerId)) {
            page = (ObjectNode) JSON.readTree(pages.get(playerId));
            history = (ArrayNode) page.path("history");
        } else {
            page = (ObjectNode) JSON.readTree(resource("element-summary-1.json"));
            history = JSON.createArrayNode();
            page.set("history", history);
        }
        ObjectNode row = null;
        for (JsonNode existing : history) {
            if (existing.path("fixture").asInt() == fixture.path("id").asInt()) {
                row = (ObjectNode) existing;
                break;
            }
        }
        if (row == null) {
            JsonNode template = null;
            for (JsonNode existing : JSON.readTree(resource("element-summary-1.json")).path("history")) {
                if (existing.path("fixture").asInt() == 29) { template = existing; break; }
            }
            if (template == null) throw new IllegalStateException("Missing recorded history row template");
            row = ((ObjectNode) template).deepCopy();
            history.add(row);
        }
        int homeTeam = fixture.path("team_h").asInt();
        int awayTeam = fixture.path("team_a").asInt();
        row.put("element", playerId);
        row.put("fixture", fixture.path("id").asInt());
        row.put("opponent_team", home ? awayTeam : homeTeam);
        row.put("was_home", home);
        row.put("round", fixture.path("event").asInt());
        row.put("kickoff_time", fixture.path("kickoff_time").asText());
        row.put("team_h_score", fixture.path("team_h_score").asInt());
        row.put("team_a_score", fixture.path("team_a_score").asInt());
        row.put("minutes", minutes);
        row.put("starts", minutes >= 60 ? 1 : 0);
        row.put("total_points", 2);
        row.put("bps", bps);
        row.put("expected_goals", "0.00");
        row.put("expected_assists", "0.00");
        row.put("expected_goal_involvements", "0.00");
        row.put("expected_goals_conceded", "0.00");
        row.put("influence", "0.0");
        row.put("creativity", "0.0");
        row.put("threat", "0.0");
        row.put("ict_index", "0.0");
        row.put("clearances_blocks_interceptions", 0);
        row.put("recoveries", 0);
        row.put("tackles", 0);
        row.put("defensive_contribution", 0);
        pages.put(playerId, JSON.writeValueAsBytes(page));
    }

    private static byte[] liveForGameweekParticipants(byte[] source,
                                                       Map<Integer, Set<Integer>> participantsByFixture)
            throws IOException {
        ObjectNode root = (ObjectNode) JSON.readTree(source);
        ArrayNode elements = JSON.createArrayNode();
        Set<Integer> seen = new HashSet<>();
        for (JsonNode node : root.path("elements")) {
            int playerId = node.path("id").asInt();
            ArrayNode explanations = JSON.createArrayNode();
            for (JsonNode explanation : node.path("explain")) {
                int fixtureId = explanation.path("fixture").asInt();
                if (participantsByFixture.getOrDefault(fixtureId, Set.of()).contains(playerId)) {
                    explanations.add(explanation.deepCopy());
                    seen.add(playerId);
                }
            }
            if (!explanations.isEmpty()) {
                ObjectNode selected = ((ObjectNode) node).deepCopy();
                selected.set("explain", explanations);
                elements.add(selected);
            }
        }
        Set<Integer> expected = participantsByFixture.values().stream().flatMap(Set::stream)
                .collect(java.util.stream.Collectors.toSet());
        if (!seen.equals(expected)) throw new IllegalStateException("Controlled live manifest differs from participants");
        root.set("elements", elements);
        return JSON.writeValueAsBytes(root);
    }

    private static byte[] incrementFixtureBps(byte[] source, int fixtureId, int playerId) throws IOException {
        ArrayNode schedule = (ArrayNode) JSON.readTree(source);
        for (JsonNode node : schedule) {
            if (node.path("id").asInt() != fixtureId) continue;
            for (JsonNode stat : node.path("stats")) {
                if (!stat.path("identifier").asText().equals("bps")) continue;
                for (String side : List.of("h", "a")) {
                    for (JsonNode entry : stat.path(side)) {
                        if (entry.path("element").asInt() == playerId) {
                            ((ObjectNode) entry).put("value", entry.path("value").asInt() + 1);
                            return JSON.writeValueAsBytes(schedule);
                        }
                    }
                }
            }
        }
        throw new IllegalStateException("No fixture BPS row for correction target");
    }

    private static byte[] incrementHistoryBps(byte[] source, int fixtureId) throws IOException {
        ObjectNode page = (ObjectNode) JSON.readTree(source);
        for (JsonNode node : page.path("history")) {
            if (node.path("fixture").asInt() == fixtureId) {
                ((ObjectNode) node).put("bps", node.path("bps").asInt() + 1);
                return JSON.writeValueAsBytes(page);
            }
        }
        throw new IllegalStateException("No history row for correction target");
    }

    private Map<String, List<Object>> pfsRowsSnapshot(Set<Integer> fixtureIds) {
        String placeholders = String.join(",", java.util.Collections.nCopies(fixtureIds.size(), "?"));
        Object[] args = new Object[fixtureIds.size() + 1];
        args[0] = SEASON_ID;
        int index = 1;
        for (int fixtureId : fixtureIds.stream().sorted().toList()) args[index++] = fixtureId;
        return jdbc.query("SELECT fixture_id,player_id,total_points,bps,minutes,expected_goals,expected_assists,"
                + "gameweek,kickoff_time,player_team_id,opponent_team_id,was_home,updated_at FROM "
                + "player_fixture_stats WHERE season_id=? AND fixture_id IN (" + placeholders + ") "
                + "ORDER BY fixture_id,player_id", rs -> {
                    Map<String, List<Object>> rows = new java.util.TreeMap<>();
                    while (rs.next()) rows.put(rs.getInt("fixture_id") + "/" + rs.getInt("player_id"),
                            java.util.Arrays.asList(rs.getObject("total_points"), rs.getObject("bps"),
                                    rs.getObject("minutes"), rs.getObject("expected_goals"),
                                    rs.getObject("expected_assists"), rs.getObject("gameweek"),
                                    rs.getObject("kickoff_time"), rs.getObject("player_team_id"),
                                    rs.getObject("opponent_team_id"), rs.getObject("was_home"),
                                    rs.getObject("updated_at")));
                    return Map.copyOf(rows);
                }, args);
    }

    private Map<String, List<Object>> pfsProofSnapshot(Set<Integer> fixtureIds) {
        String placeholders = String.join(",", java.util.Collections.nCopies(fixtureIds.size(), "?"));
        Object[] args = new Object[fixtureIds.size() + 1];
        args[0] = SEASON_ID;
        int index = 1;
        for (int fixtureId : fixtureIds.stream().sorted().toList()) args[index++] = fixtureId;
        return jdbc.query("SELECT c.fixture_id,c.player_id,c.source_run_generation,c.evidence_generation,"
                + "c.row_state,p.updated_at,e.component_presence,e.source_read_started_at,e.source_read_finished_at,"
                + "(SELECT count(*) FROM " + TABLES.playerFixtureEvidence() + " archived WHERE "
                + "archived.season_id=c.season_id AND archived.fixture_id=c.fixture_id "
                + "AND archived.player_id=c.player_id) AS evidence_count,(SELECT string_agg(archived.generation::text"
                + " || ':' || archived.component_presence::text || ':' || archived.source_read_started_at::text"
                + " || ':' || archived.source_read_finished_at::text,',' ORDER BY archived.generation) FROM "
                + TABLES.playerFixtureEvidence() + " archived WHERE archived.season_id=c.season_id "
                + "AND archived.fixture_id=c.fixture_id AND archived.player_id=c.player_id) AS evidence_archive "
                + "FROM " + TABLES.playerFixtureCurrent() + " c JOIN player_fixture_stats p ON p.season_id=c.season_id"
                + " AND p.fixture_id=c.fixture_id AND p.player_id=c.player_id JOIN "
                + TABLES.playerFixtureEvidence() + " e ON e.season_id=c.season_id AND e.fixture_id=c.fixture_id"
                + " AND e.player_id=c.player_id AND e.generation=c.evidence_generation WHERE c.season_id=?"
                + " AND c.fixture_id IN (" + placeholders + ") ORDER BY c.fixture_id,c.player_id", rs -> {
                    Map<String, List<Object>> rows = new java.util.TreeMap<>();
                    while (rs.next()) rows.put(rs.getInt("fixture_id") + "/" + rs.getInt("player_id"),
                            java.util.Arrays.asList(rs.getObject("source_run_generation"),
                                    rs.getObject("evidence_generation"), rs.getObject("row_state"),
                                    rs.getObject("updated_at"), rs.getObject("component_presence"),
                                    rs.getObject("source_read_started_at"), rs.getObject("source_read_finished_at"),
                                    rs.getObject("evidence_count"), rs.getObject("evidence_archive")));
                    return Map.copyOf(rows);
                }, args);
    }

    private List<Object> pfsProofSnapshotForKey(Set<Integer> fixtureIds, String key) {
        return pfsProofSnapshot(fixtureIds).get(key);
    }

    private Map<String, String> pageWatermarkSnapshot() {
        return jdbc.query("SELECT publication_key,latest_generation,source_read_started_at,source_read_finished_at "
                + "FROM " + TABLES.receipt() + " WHERE season=? AND publication_key LIKE ? ORDER BY publication_key",
                rs -> {
                    Map<String, String> result = new java.util.TreeMap<>();
                    while (rs.next()) result.put(rs.getString("publication_key"), rs.getString("latest_generation")
                            + ":" + rs.getString("source_read_started_at") + ":"
                            + rs.getString("source_read_finished_at"));
                    return Map.copyOf(result);
                }, season.code(), IncrementalPlayerFixturePublisher.PAGE_RECEIPT_PREFIX + "%");
    }

    private Map<String, Object> finalitySnapshot() {
        return jdbc.queryForMap("SELECT state,finalized_at,finalized_source_read_started_at,"
                + "finalized_source_read_finished_at,finalized_source_signature,finalized_row_revision_fingerprint,"
                + "final_attempt_source_signature,final_attempt_source_floor,final_attempt_invalidation_revision,"
                + "final_attempt_population_fingerprint,final_attempt_population_manifest::text,"
                + "final_attempt_source_read_started_at,final_attempt_source_read_finished_at,"
                + "final_attempt_required_player_ids::text,final_attempt_completed_player_ids::text,"
                + "final_attempt_outstanding_player_ids::text,final_attempt_page_read_started_at,"
                + "final_attempt_page_read_finished_at FROM " + TABLES.gameweekStatSync()
                + " WHERE season_id=? AND gameweek=3", SEASON_ID);
    }

    private Map<String, Object> finalizedPageProofSnapshot() {
        return jdbc.queryForMap("SELECT finalized_run_generation,finalized_page_evidence::text,"
                + "finalized_source_read_started_at,finalized_source_read_finished_at,finalized_source_floor "
                + "FROM " + TABLES.gameweekStatSync() + " WHERE season_id=? AND gameweek=3", SEASON_ID);
    }

    private Map<String, Object> sourceCatalogueSnapshot() {
        return jdbc.queryForMap("SELECT latest_generation,(source_catalogue->>'sourceGeneration')::bigint "
                + "AS source_generation,source_catalogue->>'membershipFingerprint' AS membership_fingerprint "
                + "FROM " + TABLES.receipt() + " WHERE season=? AND publication_key=?",
                season.code(), OwnedSyncStore.INCREMENTAL_COMPATIBILITY_PUBLICATION_KEY);
    }

    private CheckedFixtureSetup omittedFixtureStatPlayerSetup() throws Exception {
        CheckedFixtureSetup setup = checkedSingleFixtureSetup();
        JsonNode sourceRoot = JSON.readTree(setup.fixtureSource());
        for (JsonNode fixture : sourceRoot) {
            if (fixture.path("id").asInt() != setup.fixtureId()) continue;
            for (JsonNode stat : fixture.path("stats")) {
                for (JsonNode player : stat.path("h")) {
                    if (player.path("element").asInt() == 1) ((ObjectNode) player).put("element", 3);
                }
            }
        }
        byte[] source = JSON.writeValueAsBytes(sourceRoot);
        ObjectNode live = (ObjectNode) JSON.readTree(setup.liveSource());
        addLivePlayer((ArrayNode) live.get("elements"), 3, setup.fixtureId(), 90);
        byte[] liveSource = JSON.writeValueAsBytes(live);
        fetch.setSources(setup.bootstrapSource(), source, liveSource, eventFourLive);
        fetch.setPlayerPage(3, onlyHistoryForFixture(
                relabelHistoryPlayer(resource("element-summary-1.json"), 3), setup.fixtureId(), 3));
        return new CheckedFixtureSetup(setup.fixtureId(), setup.cataloguePlayer(), source,
                setup.bootstrapSource(), liveSource);
    }

    private CheckedFixtureSetup checkedSingleFixtureSetup() throws Exception {
        int fixtureId = 29;
        byte[] source = onlyCollectorFixtureFinished(fixtures, fixtureId);
        source = fixtureStatsForPlayers(source, fixtureId, 1, 28);
        byte[] checkedBootstrap = markGameweekChecked(bootstrapOnlyPlayer(bootstrap, 1), 3);
        byte[] live = liveForFixturePlayers(eventThreeLive, fixtureId, Set.of(1, 28));
        fetch.setSources(checkedBootstrap, source, live, eventFourLive);
        fetch.setPlayerPage(1, onlyHistoryForFixture(resource("element-summary-1.json"), fixtureId, 1));
        fetch.setPlayerPage(28, historyForAwayPlayer28(fixtureId));
        return new CheckedFixtureSetup(fixtureId, 1, source, checkedBootstrap, live);
    }

    private static void respondLocal(HttpExchange exchange, List<String> paths, byte[] bootstrapBody,
                                     byte[] fixtureBody, byte[] liveBody, byte[] historyBody,
                                     byte[] emptyHistory, MutableClock clock, int playerId) throws IOException {
        String path = exchange.getRequestURI().getPath();
        paths.add(path);
        clock.set(clock.instant().plusSeconds(2));
        byte[] body;
        if (path.equals("/api/bootstrap-static/")) body = bootstrapBody;
        else if (path.equals("/api/fixtures/")) body = fixtureBody;
        else if (path.equals("/api/event/4/live/")) body = liveBody;
        else if (path.equals("/api/element-summary/" + playerId + "/")) body = historyBody;
        else if (path.startsWith("/api/element-summary/")) body = emptyHistory;
        else body = "{}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private void installUnit6WriteCounters() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS fpl_unit6_write_count_test("
                + "table_name text NOT NULL, operation text NOT NULL, writes bigint NOT NULL,"
                + " PRIMARY KEY(table_name,operation))");
        jdbc.update("DELETE FROM fpl_unit6_write_count_test");
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION fpl_unit6_count_write_test() RETURNS trigger
                LANGUAGE plpgsql AS $$
                BEGIN
                    INSERT INTO fpl_unit6_write_count_test(table_name,operation,writes)
                    VALUES (TG_TABLE_NAME,TG_OP,1)
                    ON CONFLICT (table_name,operation) DO UPDATE
                        SET writes=fpl_unit6_write_count_test.writes+1;
                    RETURN NULL;
                END;
                $$
                """);
        for (String table : unit6ObservedTables()) {
            jdbc.execute("DROP TRIGGER IF EXISTS fpl_unit6_write_count_test ON " + table);
            jdbc.execute("CREATE TRIGGER fpl_unit6_write_count_test"
                    + " AFTER INSERT OR UPDATE OR DELETE ON " + table
                    + " FOR EACH ROW EXECUTE FUNCTION fpl_unit6_count_write_test()");
        }
    }

    private void removeUnit6WriteCounters() {
        for (String table : unit6ObservedTables()) {
            jdbc.execute("DROP TRIGGER IF EXISTS fpl_unit6_write_count_test ON " + table);
        }
        jdbc.execute("DROP FUNCTION IF EXISTS fpl_unit6_count_write_test()");
        jdbc.execute("DROP TABLE IF EXISTS fpl_unit6_write_count_test");
    }

    private List<String> unit6ObservedTables() {
        return List.of("team", "player", "gameweek", "fixture", "player_fixture_stats",
                TABLES.playerFixtureEvidence(), TABLES.playerFixtureCurrent(), TABLES.gameweekStatSync(),
                TABLES.fixtureSync(), TABLES.receipt());
    }

    private Map<String, String> unit6ReceiptSnapshot() {
        return jdbc.query("SELECT publication_key,latest_generation,source_read_started_at,"
                + "source_read_finished_at,published_at,source_pair_fingerprint FROM " + TABLES.receipt()
                + " WHERE season=? ORDER BY publication_key", rs -> {
                    Map<String, String> rows = new java.util.TreeMap<>();
                    while (rs.next()) rows.put(rs.getString("publication_key"), rs.getString("latest_generation")
                            + ":" + rs.getString("source_read_started_at") + ":"
                            + rs.getString("source_read_finished_at") + ":" + rs.getString("published_at")
                            + ":" + rs.getString("source_pair_fingerprint"));
                    return Map.copyOf(rows);
                }, season.code());
    }

    private Map<String, Long> unit6WriteCounts() {
        return jdbc.query("SELECT table_name,operation,writes FROM fpl_unit6_write_count_test",
                rs -> {
                    Map<String, Long> counts = new java.util.TreeMap<>();
                    while (rs.next()) counts.put(rs.getString("table_name") + "/" + rs.getString("operation"),
                            rs.getLong("writes"));
                    return Map.copyOf(counts);
                });
    }

    private Map<String, Long> compatibilityCatalogueWriteCounts() {
        Map<String, Long> all = unit6WriteCounts();
        Map<String, Long> result = new java.util.TreeMap<>();
        for (String table : List.of("team", "player", "gameweek", "fixture")) {
            for (String operation : List.of("INSERT", "UPDATE")) {
                String key = table + "/" + operation;
                result.put(key, all.getOrDefault(key, 0L));
            }
        }
        return Map.copyOf(result);
    }

    private static Map<String, Long> catalogueWriteDelta(Map<String, Long> before, Map<String, Long> after) {
        Map<String, Long> result = new java.util.TreeMap<>();
        before.forEach((key, value) -> result.put(key, after.getOrDefault(key, 0L) - value));
        return Map.copyOf(result);
    }

    private Map<String, Object> pfsOptionalMetrics(int fixtureId, int playerId) {
        return jdbc.queryForMap("SELECT expected_goals,expected_assists,expected_goal_involvements,"
                + "expected_goals_conceded,influence,creativity,threat,ict_index,"
                + "clearances_blocks_interceptions,recoveries,tackles,defensive_contribution "
                + "FROM player_fixture_stats WHERE season_id=? AND fixture_id=? AND player_id=?",
                SEASON_ID, fixtureId, playerId);
    }

    private long pfsComponentPresence(int fixtureId, int playerId) {
        return jdbc.queryForObject("SELECT e.component_presence FROM " + TABLES.playerFixtureCurrent()
                + " c JOIN " + TABLES.playerFixtureEvidence() + " e ON e.season_id=c.season_id"
                + " AND e.fixture_id=c.fixture_id AND e.player_id=c.player_id"
                + " AND e.generation=c.evidence_generation WHERE c.season_id=? AND c.fixture_id=?"
                + " AND c.player_id=?", Long.class, SEASON_ID, fixtureId, playerId);
    }

    private long unit6WriteCount(String table, String operation) {
        Long count = jdbc.queryForObject("SELECT writes FROM fpl_unit6_write_count_test"
                + " WHERE table_name=? AND operation=?", Long.class, table, operation);
        return count == null ? 0 : count;
    }

    private SqlRecordingDataSource useRecordingJdbc() {
        SqlRecordingDataSource recording = new SqlRecordingDataSource(dataSource);
        JdbcTemplate recordedJdbc = new JdbcTemplate(recording);
        TransactionTemplate recordedTx = new TransactionTemplate(new DataSourceTransactionManager(recording));
        store = new OwnedSyncStore(recordedJdbc, recordedTx, clock, TABLES, runtimeFence);
        publisher = new IncrementalPlayerFixturePublisher(
                new IncrementalPfsRepository(recordedJdbc, clock, store, TABLES));
        return recording;
    }

    private PfsReadSource collectorPfsReadSource() {
        return new PfsReadSource(jdbc, "OFF", "", TABLES.fixtureSync(),
                TABLES.playerFixtureEvidence(), TABLES.playerFixtureCurrent(), TABLES.receipt());
    }

    private Map<PfsReadSource.FixtureSide, PfsReadSource.SidePopulation> doubleFixturePopulation(
            PfsReadSource reader) {
        return reader.fixtureSidePopulation(SEASON_ID, season.code(), "fixture", "season_id", SEASON_ID,
                Set.of(DOUBLE_FIXTURE_ONE, DOUBLE_FIXTURE_TWO), clock.instant());
    }

    private BigDecimal collectorFixtureXg(PfsReadSource reader, int fixtureId) {
        return jdbc.queryForObject("WITH " + reader.cte()
                + " SELECT SUM(expected_goals) FROM pfs_read WHERE season_id=? AND fixture_id=?",
                BigDecimal.class, SEASON_ID, fixtureId);
    }

    private Map<String, Object> appearanceReceipt(String key) {
        return jdbc.queryForMap("SELECT latest_generation,source_read_started_at,source_read_finished_at,"
                + "source_pair_fingerprint FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", season.code(), key);
    }

    private Map<String, Object> playerPageProof(int fixtureId, int playerId) {
        return jdbc.queryForMap("SELECT c.source_run_generation,c.evidence_generation,c.row_fingerprint,"
                + "c.physical_fingerprint,c.source_read_started_at AS pointer_started,"
                + "c.source_read_finished_at AS pointer_finished,c.updated_at AS pointer_updated,"
                + "e.component_presence,e.source_read_started_at AS evidence_started,"
                + "e.source_read_finished_at AS evidence_finished,p.updated_at AS pfs_updated "
                + "FROM " + TABLES.playerFixtureCurrent() + " c JOIN " + TABLES.playerFixtureEvidence()
                + " e ON e.season_id=c.season_id AND e.fixture_id=c.fixture_id AND e.player_id=c.player_id"
                + " AND e.generation=c.evidence_generation JOIN player_fixture_stats p"
                + " ON p.season_id=c.season_id AND p.fixture_id=c.fixture_id AND p.player_id=c.player_id"
                + " WHERE c.season_id=? AND c.fixture_id=? AND c.player_id=?",
                SEASON_ID, fixtureId, playerId);
    }

    private Map<String, Object> pageWatermark(int playerId) {
        return jdbc.queryForMap("SELECT latest_generation,source_read_started_at,source_read_finished_at"
                + " FROM " + TABLES.receipt() + " WHERE season=? AND publication_key=?",
                season.code(), IncrementalPlayerFixturePublisher.pageReceiptKey(playerId));
    }

    private int playerEvidenceCount(int fixtureId, int playerId) {
        return jdbc.queryForObject("SELECT count(*) FROM " + TABLES.playerFixtureEvidence()
                + " WHERE season_id=? AND fixture_id=? AND player_id=?", Integer.class,
                SEASON_ID, fixtureId, playerId);
    }

    private SingleFixtureCapture sameFixtureCapture() throws Exception {
        PairCapture base = pairCapture();
        ObjectNode bootstrapRoot = (ObjectNode) JSON.readTree(base.bootstrap());
        int homeCode = 0;
        int awayCode = 0;
        for (JsonNode team : bootstrapRoot.path("teams")) {
            if (team.path("id").asInt() == DOUBLE_HOME_ONE) homeCode = team.path("code").asInt();
            if (team.path("id").asInt() == DOUBLE_AWAY_ONE) awayCode = team.path("code").asInt();
        }
        ArrayNode players = JSON.createArrayNode();
        for (JsonNode raw : bootstrapRoot.path("elements")) {
            int id = raw.path("id").asInt();
            if (id != 259 && id != 82 && id != 350) continue;
            ObjectNode player = ((ObjectNode) raw).deepCopy();
            player.put("team", id == 350 ? DOUBLE_AWAY_ONE : DOUBLE_HOME_ONE);
            player.put("team_code", id == 350 ? awayCode : homeCode);
            players.add(player);
        }
        bootstrapRoot.set("elements", players);

        ArrayNode schedule = (ArrayNode) JSON.readTree(base.fixtures());
        for (JsonNode raw : schedule) {
            ObjectNode fixture = (ObjectNode) raw;
            if (fixture.path("id").asInt() == DOUBLE_FIXTURE_ONE) {
                fixture.put("team_h_score", 1);
                fixture.put("team_a_score", 0);
                fixture.set("stats", sameFixtureStats());
            } else if (fixture.path("id").asInt() == DOUBLE_FIXTURE_TWO) {
                fixture.putNull("event");
                fixture.put("started", false);
                fixture.put("finished", false);
                fixture.put("finished_provisional", false);
                fixture.put("minutes", 0);
                fixture.putNull("team_h_score");
                fixture.putNull("team_a_score");
                fixture.set("stats", JSON.createArrayNode());
            }
        }

        byte[] sourceBootstrap = JSON.writeValueAsBytes(bootstrapRoot);
        byte[] sourceFixtures = JSON.writeValueAsBytes(schedule);
        byte[] live = sameFixtureLive();
        var snapshot = CatalogueScheduleParser.parse(season,
                document(sourceBootstrap, new SourceRead(START, START.plusMillis(1))),
                document(sourceFixtures, new SourceRead(START.plusMillis(2), START.plusMillis(3))));
        assertThat(snapshot.gameweeks().stream().filter(gameweek -> gameweek.id() == 3)
                .findFirst().orElseThrow().dataChecked()).isFalse();
        FixtureRow fixture = snapshot.fixtures().stream()
                .filter(row -> row.id() == DOUBLE_FIXTURE_ONE).findFirst().orElseThrow();
        SourceDocument liveDocument = document(live, new SourceRead(START.plusMillis(4), START.plusMillis(5)));
        assertThat(FixtureVerification.fixtureEvidenceByFixture(List.of(fixture), liveDocument)
                .get(DOUBLE_FIXTURE_ONE).complete()).isTrue();
        Map<Integer, byte[]> pages = Map.of(
                259, syntheticHistoryPage(259, fixture, 10, 0, "0.10"),
                82, syntheticHistoryPage(82, fixture, 90, 1, "0.90"),
                350, syntheticHistoryPage(350, fixture, 90, 0, "0.25"));
        List<TeamRow> teams = snapshot.teams().stream()
                .filter(team -> team.id() == DOUBLE_HOME_ONE || team.id() == DOUBLE_AWAY_ONE).toList();
        return new SingleFixtureCapture(sourceBootstrap, sourceFixtures, live, fixture, pages, teams);
    }

    private static ArrayNode sameFixtureStats() {
        ArrayNode stats = JSON.createArrayNode();
        ObjectNode minutes = stats.addObject();
        minutes.put("identifier", "minutes");
        minutes.putArray("h").addObject().put("element", 259).put("value", 10);
        minutes.withArray("h").addObject().put("element", 82).put("value", 90);
        minutes.putArray("a").addObject().put("element", 350).put("value", 90);
        ObjectNode goals = stats.addObject();
        goals.put("identifier", "goals_scored");
        goals.putArray("h").addObject().put("element", 259).put("value", 0);
        goals.withArray("h").addObject().put("element", 82).put("value", 1);
        goals.putArray("a").addObject().put("element", 350).put("value", 0);
        return stats;
    }

    private static byte[] sameFixtureLive() throws IOException {
        return sameFixtureLive(90);
    }

    private static byte[] sameFixtureLive(int secondHomeMinutes) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode elements = root.putArray("elements");
        addLivePlayer(elements, 259, DOUBLE_FIXTURE_ONE, 10);
        addLivePlayer(elements, 82, DOUBLE_FIXTURE_ONE, secondHomeMinutes);
        addLivePlayer(elements, 350, DOUBLE_FIXTURE_ONE, 90);
        return JSON.writeValueAsBytes(root);
    }

    private PairCapture pairCapture() throws Exception {
        Map<Integer, Integer> teamIds = Map.of(12, DOUBLE_HOME_ONE, 14, DOUBLE_AWAY_ONE,
                4, DOUBLE_HOME_TWO, 20, DOUBLE_AWAY_TWO);
        ObjectNode bootstrapRoot = (ObjectNode) JSON.readTree(bootstrap);
        ArrayNode sourceTeams = JSON.createArrayNode();
        for (JsonNode row : bootstrapRoot.path("teams")) {
            int oldId = row.path("id").asInt();
            ObjectNode team = ((ObjectNode) row).deepCopy();
            team.put("id", teamIds.getOrDefault(oldId, oldId));
            sourceTeams.add(team);
        }
        bootstrapRoot.set("teams", sourceTeams);
        ArrayNode sourcePlayers = JSON.createArrayNode();
        Map<Integer, Integer> playerTeams = Map.of(259, 12, 350, 14, 82, 4, 501, 20);
        for (JsonNode row : bootstrapRoot.path("elements")) {
            int playerId = row.path("id").asInt();
            if (!playerTeams.containsKey(playerId)) continue;
            ObjectNode player = ((ObjectNode) row).deepCopy();
            player.put("team", teamIds.get(playerTeams.get(playerId)));
            sourcePlayers.add(player);
        }
        bootstrapRoot.set("elements", sourcePlayers);
        for (JsonNode event : bootstrapRoot.path("events")) ((ObjectNode) event).put("data_checked", false);

        ArrayNode sourceFixtures = (ArrayNode) JSON.readTree(fixtures);
        for (JsonNode raw : sourceFixtures) {
            ObjectNode fixture = (ObjectNode) raw;
            int fixtureId = fixture.path("id").asInt();
            int oldHome = fixture.path("team_h").asInt();
            int oldAway = fixture.path("team_a").asInt();
            fixture.put("team_h", teamIds.getOrDefault(oldHome, oldHome));
            fixture.put("team_a", teamIds.getOrDefault(oldAway, oldAway));
            if (fixtureId == 21 || fixtureId == 22) {
                boolean first = fixtureId == 21;
                fixture.put("id", first ? DOUBLE_FIXTURE_ONE : DOUBLE_FIXTURE_TWO);
                fixture.put("team_h_score", 0);
                fixture.put("team_a_score", first ? 0 : 1);
                fixture.set("stats", pairFixtureStats(first));
            } else {
                fixture.putNull("event");
                fixture.put("kickoff_time", "2026-09-20T12:00:00Z");
                fixture.put("started", false);
                fixture.put("finished", false);
                fixture.put("finished_provisional", false);
                fixture.put("minutes", 0);
                fixture.putNull("team_h_score");
                fixture.putNull("team_a_score");
                fixture.set("stats", JSON.createArrayNode());
            }
        }

        byte[] sourceBootstrap = JSON.writeValueAsBytes(bootstrapRoot);
        byte[] sourceSchedule = JSON.writeValueAsBytes(sourceFixtures);
        byte[] live = pairLive();
        SourceRead bootstrapRead = new SourceRead(START, START.plusMillis(1));
        SourceRead fixtureRead = new SourceRead(START.plusMillis(2), START.plusMillis(3));
        var snapshot = CatalogueScheduleParser.parse(season, document(sourceBootstrap, bootstrapRead),
                document(sourceSchedule, fixtureRead));
        FixtureRow first = snapshot.fixtures().stream()
                .filter(row -> row.id() == DOUBLE_FIXTURE_ONE).findFirst().orElseThrow();
        FixtureRow second = snapshot.fixtures().stream()
                .filter(row -> row.id() == DOUBLE_FIXTURE_TWO).findFirst().orElseThrow();
        SourceDocument liveDocument = document(live, new SourceRead(START.plusMillis(4), START.plusMillis(5)));
        assertThat(FixtureVerification.fixtureEvidenceByFixture(List.of(first, second), liveDocument))
                .allSatisfy((id, evidence) -> assertThat(evidence.complete()).isTrue());
        Map<Integer, byte[]> pages = Map.of(
                82, syntheticHistoryPage(82, second, 30, 0, "0.10"),
                259, syntheticHistoryPage(259, first, 10, 0, "0.10"),
                350, syntheticHistoryPage(350, first, 90, 0, "0.90"),
                501, syntheticHistoryPage(501, second, 90, 1, "0.90"));
        List<TeamRow> teams = snapshot.teams().stream().filter(team -> teamIds.containsValue(team.id())).toList();
        return new PairCapture(sourceBootstrap, sourceSchedule, live, first, second, pages, teams);
    }

    private static ArrayNode pairFixtureStats(boolean first) {
        int homePlayer = first ? 259 : 82;
        int awayPlayer = first ? 350 : 501;
        ArrayNode stats = JSON.createArrayNode();
        ObjectNode minutes = stats.addObject();
        minutes.put("identifier", "minutes");
        minutes.putArray("h").addObject().put("element", homePlayer).put("value", first ? 10 : 30);
        minutes.putArray("a").addObject().put("element", awayPlayer).put("value", 90);
        ObjectNode goals = stats.addObject();
        goals.put("identifier", "goals_scored");
        goals.putArray("h").addObject().put("element", homePlayer).put("value", 0);
        goals.putArray("a").addObject().put("element", awayPlayer).put("value", first ? 0 : 1);
        return stats;
    }

    private static byte[] pairLive() throws IOException {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode elements = root.putArray("elements");
        addLivePlayer(elements, 259, DOUBLE_FIXTURE_ONE, 10);
        addLivePlayer(elements, 350, DOUBLE_FIXTURE_ONE, 90);
        addLivePlayer(elements, 82, DOUBLE_FIXTURE_TWO, 30);
        addLivePlayer(elements, 501, DOUBLE_FIXTURE_TWO, 90);
        return JSON.writeValueAsBytes(root);
    }

    private static void addLivePlayer(ArrayNode elements, int playerId, int fixtureId, int minutes) {
        ObjectNode player = elements.addObject();
        player.put("id", playerId);
        ObjectNode explanation = player.putArray("explain").addObject();
        explanation.put("fixture", fixtureId);
        explanation.putArray("stats").addObject().put("identifier", "minutes")
                .put("value", minutes).put("points", minutes >= 60 ? 2 : 1).put("points_modification", 0);
    }

    private static byte[] syntheticHistoryPage(int playerId, FixtureRow fixture, int minutes,
                                               int goals, String xg) throws IOException {
        ObjectNode root = (ObjectNode) JSON.readTree(resource("element-summary-1.json"));
        ObjectNode row = ((ObjectNode) root.path("history").get(0)).deepCopy();
        boolean home = playerId == 259 || playerId == 82;
        row.put("element", playerId);
        row.put("fixture", fixture.id());
        row.put("round", fixture.gameweek());
        row.put("opponent_team", home ? fixture.awayTeamId() : fixture.homeTeamId());
        row.put("was_home", home);
        row.put("kickoff_time", fixture.kickoff().toString());
        row.put("minutes", minutes);
        row.put("goals_scored", goals);
        row.put("expected_goals", xg);
        row.put("expected_assists", "0.00");
        row.put("expected_goal_involvements", xg);
        row.put("expected_goals_conceded", "0.20");
        ArrayNode history = JSON.createArrayNode().add(row);
        root.set("history", history);
        return JSON.writeValueAsBytes(root);
    }

    private CoalescedPlayerHistoryCollector collector(boolean enabled) {
        return new CoalescedPlayerHistoryCollector(season, enabled, store, publisher, fetch, clock,
                "collector-test");
    }

    private CoalescedPlayerHistoryCollector compatibilityCollector(boolean enabled) {
        CompatibilityCatalogueWriter compatibility = new CompatibilityCatalogueWriter(jdbc, clock, store, TABLES,
                CAPTURED);
        return new CoalescedPlayerHistoryCollector(season, enabled, store, publisher, compatibility, fetch, clock,
                "compatibility-collector-test");
    }

    private CoalescedPlayerHistoryCollector compatibilityCollector(boolean enabled, JdbcTemplate targetJdbc,
                                                                   TransactionTemplate targetTx) {
        store = new OwnedSyncStore(targetJdbc, targetTx, clock, TABLES, runtimeFence);
        publisher = new IncrementalPlayerFixturePublisher(
                new IncrementalPfsRepository(targetJdbc, clock, store, TABLES));
        CompatibilityCatalogueWriter compatibility = new CompatibilityCatalogueWriter(targetJdbc, clock,
                store, TABLES, CAPTURED);
        return new CoalescedPlayerHistoryCollector(season, enabled, store, publisher, compatibility, fetch, clock,
                "compatibility-collector-test");
    }

    private Lease nextCoreLease() {
        store.enqueueIncrementalCoreImport(season, clock.instant());
        return store.acquireIncrementalCoreImport(season.code(), "watermark-test", Duration.ofMinutes(30))
                .orElseThrow();
    }

    private StagedCorePair stagePublishedCorePair() {
        store.enqueueIncrementalCoreImport(season, clock.instant());
        Lease lease = store.acquireIncrementalCoreImport(season.code(), "retention-pair-worker",
                Duration.ofMinutes(30)).orElseThrow();
        Instant sourceStart = clock.instant().plusMillis(1);
        SourceRead bootstrapRead = new SourceRead(sourceStart, sourceStart.plusMillis(1));
        SourceRead fixturesRead = new SourceRead(sourceStart.plusMillis(2), sourceStart.plusMillis(3));
        SourceDocument bootstrapDocument = document(bootstrap, bootstrapRead);
        SourceDocument fixturesDocument = document(fixtures, fixturesRead);
        clock.set(fixturesRead.finishedAt().plusMillis(1));
        long bootstrapId = store.stage(lease, Endpoint.bootstrap(), bootstrapRead,
                Validation.unchecked(), bootstrap);
        long fixturesId = store.stage(lease, Endpoint.fixtures(), fixturesRead,
                Validation.unchecked(), fixtures);
        store.validateStagedPayloads(lease, List.of(bootstrapId, fixturesId), Validation.valid());
        new CompatibilityCatalogueWriter(jdbc, clock, store, TABLES, CAPTURED)
                .publish(lease, season, bootstrapDocument, fixturesDocument);
        return new StagedCorePair(lease, bootstrapRead, fixturesRead);
    }

    private int payloadCount(long generation) {
        return jdbc.queryForObject("SELECT count(*) FROM " + TABLES.payload()
                + " WHERE run_generation=?", Integer.class, generation);
    }

    private void assertPrunedPayloadBodies(long generation, int expectedCount) {
        assertThat(payloadCount(generation)).isEqualTo(expectedCount);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.payload()
                + " WHERE run_generation=? AND body_pruned_at IS NOT NULL AND octet_length(body)=0 "
                + "AND source_payload_sha256 ~ '^[0-9a-f]{64}$'", Integer.class, generation))
                .isEqualTo(expectedCount);
    }

    private void assertPayloadBodiesPresent(long generation, int expectedCount) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.payload()
                + " WHERE run_generation=? AND body_pruned_at IS NULL AND octet_length(body)>0 "
                + "AND source_payload_sha256=encode(sha256(body),'hex')", Integer.class, generation))
                .isEqualTo(expectedCount);
    }

    private void assertFinalityPendingAfterTemporaryMutation(GameweekFinalityReader reader, Runnable mutation) {
        tx.executeWithoutResult(status -> {
            mutation.run();
            assertThat(reader.read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                    .isEqualTo(GameweekFinality.State.FINAL_PENDING);
            status.setRollbackOnly();
        });
        assertThat(reader.read(SEASON_ID, season.code(), Set.of(3)).get(3).state())
                .isEqualTo(GameweekFinality.State.FINALIZED);
    }

    private SourceRead nextRead() {
        Instant started = clock.instant();
        Instant finished = started.plusMillis(1);
        clock.set(finished.plusMillis(1));
        return new SourceRead(started, finished);
    }

    private static SourceDocument document(byte[] body, SourceRead read) {
        return new SourceDocument(body, new CatalogueScheduleParser.ReadInterval(read.startedAt(), read.finishedAt()));
    }

    private List<CanonicalFixture> canonicalFixtures(byte[] fixtureBody) throws IOException {
        SourceDocument bootstrapDocument = new SourceDocument(bootstrap,
                new CatalogueScheduleParser.ReadInterval(START, START.plusMillis(1)));
        SourceDocument fixtureDocument = new SourceDocument(fixtureBody,
                new CatalogueScheduleParser.ReadInterval(START.plusMillis(2), START.plusMillis(3)));
        return CatalogueScheduleParser.parse(season, bootstrapDocument, fixtureDocument).fixtures().stream()
                .map(HistoryParser::canonicalFixture).toList();
    }

    private static byte[] relabelHistoryPlayer(byte[] source, int playerId) throws IOException {
        ObjectNode root = (ObjectNode) JSON.readTree(source);
        for (JsonNode node : root.path("history")) ((ObjectNode) node).put("element", playerId);
        return JSON.writeValueAsBytes(root);
    }

    private static byte[] onlyHistoryForFixture(byte[] source, int fixtureId, int playerId) throws IOException {
        ObjectNode root = (ObjectNode) JSON.readTree(source);
        ArrayNode history = JSON.createArrayNode();
        for (JsonNode node : root.path("history")) {
            if (node.path("fixture").asInt() != fixtureId) continue;
            ObjectNode row = ((ObjectNode) node).deepCopy();
            row.put("element", playerId);
            history.add(row);
        }
        if (history.isEmpty()) throw new IllegalStateException("No history row for fixture " + fixtureId);
        root.set("history", history);
        return JSON.writeValueAsBytes(root);
    }

    private static byte[] changeHistoryPoints(byte[] source, int fixtureId, int points) throws IOException {
        ObjectNode root = (ObjectNode) JSON.readTree(source);
        for (JsonNode node : root.path("history")) {
            if (node.path("fixture").asInt() == fixtureId) {
                ((ObjectNode) node).put("total_points", points);
                return JSON.writeValueAsBytes(root);
            }
        }
        throw new IllegalStateException("No history row for fixture " + fixtureId);
    }

    private void clearOwnedTables() {
        jdbc.execute("TRUNCATE " + TABLES.fixtureDependencyManifest() + ", " + TABLES.playerFixtureEvidence()
                + ", " + TABLES.playerFixtureCurrent() + ", " + TABLES.historyFingerprint() + ", "
                + TABLES.gameweekStatSync() + ", " + TABLES.fixtureSync() + ", " + TABLES.payload() + ", "
                + TABLES.receipt() + ", " + TABLES.requestAttempt() + ", " + TABLES.run() + ", "
                + TABLES.plannerSlot() + ", " + TABLES.alert() + " CASCADE");
        jdbc.update("DELETE FROM " + TABLES.requestState());
    }

    private static byte[] addParticipant(byte[] source, int gameweek, int playerId) throws IOException {
        FixtureKey selected = fixture(source, gameweek, true);
        return addParticipantToFixture(source, selected.id(), playerId);
    }

    private static byte[] reassignFixture(byte[] source, int fixtureId, int gameweek) throws IOException {
        ArrayNode schedule = (ArrayNode) JSON.readTree(source);
        for (JsonNode node : schedule) {
            if (node.path("id").asInt() == fixtureId) {
                ObjectNode fixture = (ObjectNode) node;
                fixture.put("event", gameweek);
                fixture.put("kickoff_time", "2026-09-13T17:00:00Z");
                fixture.put("started", true);
                fixture.put("finished", true);
                fixture.put("finished_provisional", false);
                fixture.put("minutes", 90);
                if (fixture.path("team_h_score").isNull() || fixture.path("team_a_score").isNull()) {
                    fixture.put("team_h_score", 0);
                    fixture.put("team_a_score", 0);
                }
                return JSON.writeValueAsBytes(schedule);
            }
        }
        throw new IllegalStateException("No fixture found: " + fixtureId);
    }

    private static byte[] addHistoryFixture(byte[] source, FixtureKey fixture, boolean wasHome)
            throws IOException {
        ObjectNode root = (ObjectNode) JSON.readTree(source);
        ObjectNode row = (ObjectNode) root.withArray("history").get(0).deepCopy();
        row.put("fixture", fixture.id());
        row.put("round", fixture.gameweek());
        row.put("opponent_team", wasHome ? fixture.awayTeam() : fixture.homeTeam());
        row.put("was_home", wasHome);
        row.put("kickoff_time", fixture.kickoff().toString());
        ((ArrayNode) root.get("history")).add(row);
        return JSON.writeValueAsBytes(root);
    }

    private static byte[] addParticipantToFixture(byte[] source, int fixtureId, int playerId) throws IOException {
        return addParticipantToFixtureSide(source, fixtureId, playerId, true);
    }

    private static byte[] addParticipantAwayToFixture(byte[] source, int fixtureId, int playerId) throws IOException {
        return addParticipantToFixtureSide(source, fixtureId, playerId, false);
    }

    private static byte[] addParticipantToFixtureSide(byte[] source, int fixtureId, int playerId,
                                                       boolean home) throws IOException {
        ArrayNode schedule = (ArrayNode) JSON.readTree(source);
        for (JsonNode node : schedule) {
            if (node.path("id").asInt() != fixtureId) continue;
            ObjectNode fixture = (ObjectNode) node;
            ArrayNode stats = (ArrayNode) fixture.get("stats");
            if (stats.isEmpty()) throw new IllegalStateException("Fixture has no stats: " + fixtureId);
            ObjectNode firstStat = (ObjectNode) stats.get(0);
            ((ArrayNode) firstStat.get(home ? "h" : "a")).addObject()
                    .put("element", playerId).put("value", 0);
            return JSON.writeValueAsBytes(schedule);
        }
        throw new IllegalStateException("No fixture found: " + fixtureId);
    }

    private static byte[] markGameweekChecked(byte[] source, int gameweek) throws IOException {
        return setGameweekDataChecked(source, gameweek, true);
    }

    private static byte[] markAllGameweeksUnchecked(byte[] source) throws IOException {
        ObjectNode root = (ObjectNode) JSON.readTree(source);
        for (JsonNode event : root.path("events")) ((ObjectNode) event).put("data_checked", false);
        return JSON.writeValueAsBytes(root);
    }

    private static byte[] markGameweekUnchecked(byte[] source, int gameweek) throws IOException {
        return setGameweekDataChecked(source, gameweek, false);
    }

    private static byte[] setGameweekDataChecked(byte[] source, int gameweek, boolean checked) throws IOException {
        ObjectNode root = (ObjectNode) JSON.readTree(source);
        for (JsonNode node : root.path("events")) {
            if (node.path("id").asInt() != gameweek) continue;
            ObjectNode event = (ObjectNode) node;
            event.put("finished", true);
            event.put("data_checked", checked);
            return JSON.writeValueAsBytes(root);
        }
        throw new IllegalStateException("No gameweek found: " + gameweek);
    }

    private static byte[] markGameweekAllFixturesFinished(byte[] source, int gameweek) throws IOException {
        ArrayNode schedule = (ArrayNode) JSON.readTree(source);
        for (JsonNode node : schedule) {
            if (node.path("event").asInt() != gameweek) continue;
            ObjectNode fixture = (ObjectNode) node;
            fixture.put("started", true);
            fixture.put("finished", true);
            fixture.put("finished_provisional", false);
            fixture.put("minutes", 90);
            if (fixture.path("team_h_score").isNull() || fixture.path("team_a_score").isNull()) {
                fixture.put("team_h_score", 0);
                fixture.put("team_a_score", 0);
            }
        }
        return JSON.writeValueAsBytes(schedule);
    }

    private static byte[] markGameweekFinished(byte[] source, int gameweek) throws IOException {
        ArrayNode schedule = (ArrayNode) JSON.readTree(source);
        for (JsonNode node : schedule) {
            if (node.path("event").asInt() != gameweek || !node.path("started").asBoolean()) continue;
            ObjectNode fixture = (ObjectNode) node;
            fixture.put("finished", true);
            fixture.put("finished_provisional", false);
            fixture.put("minutes", Math.max(90, fixture.path("minutes").asInt()));
        }
        return JSON.writeValueAsBytes(schedule);
    }

    private static byte[] withoutAssignedFixtures(byte[] source) throws IOException {
        ArrayNode schedule = (ArrayNode) JSON.readTree(source);
        for (JsonNode node : schedule) ((ObjectNode) node).putNull("event");
        return JSON.writeValueAsBytes(schedule);
    }

    private static byte[] onlyFixtureFinished(byte[] source, int fixtureId) throws IOException {
        ArrayNode schedule = (ArrayNode) JSON.readTree(source);
        for (JsonNode node : schedule) {
            ObjectNode fixture = (ObjectNode) node;
            if (fixture.path("id").asInt() == fixtureId) {
                fixture.put("started", true);
                fixture.put("finished", true);
                fixture.put("finished_provisional", false);
                fixture.put("minutes", 90);
            } else {
                fixture.put("started", false);
                fixture.put("finished", false);
                fixture.put("finished_provisional", false);
                fixture.put("minutes", 0);
                fixture.putNull("team_h_score");
                fixture.putNull("team_a_score");
            }
        }
        return JSON.writeValueAsBytes(schedule);
    }

    private static byte[] onlyCollectorFixtureFinished(byte[] source, int fixtureId) throws IOException {
        ArrayNode schedule = (ArrayNode) JSON.readTree(onlyFixtureFinished(source, fixtureId));
        for (JsonNode node : schedule) {
            ObjectNode fixture = (ObjectNode) node;
            if (fixture.path("id").asInt() == fixtureId) continue;
            fixture.putNull("event");
            fixture.putNull("kickoff_time");
        }
        return JSON.writeValueAsBytes(schedule);
    }

    private static byte[] onlyFixturesFinished(byte[] source, int... fixtureIds) throws IOException {
        Set<Integer> selected = java.util.Arrays.stream(fixtureIds).boxed().collect(java.util.stream.Collectors.toSet());
        ArrayNode schedule = (ArrayNode) JSON.readTree(source);
        for (JsonNode node : schedule) {
            ObjectNode fixture = (ObjectNode) node;
            if (selected.contains(fixture.path("id").asInt())) {
                fixture.put("started", true);
                fixture.put("finished", true);
                fixture.put("finished_provisional", false);
                fixture.put("minutes", 90);
                if (fixture.path("team_h_score").isNull()) fixture.put("team_h_score", 0);
                if (fixture.path("team_a_score").isNull()) fixture.put("team_a_score", 0);
            } else {
                fixture.put("started", false);
                fixture.put("finished", false);
                fixture.put("finished_provisional", false);
                fixture.put("minutes", 0);
                fixture.putNull("team_h_score");
                fixture.putNull("team_a_score");
                fixture.putNull("event");
                fixture.putNull("kickoff_time");
            }
        }
        return JSON.writeValueAsBytes(schedule);
    }

    private static byte[] emptyFixtureStats(byte[] source) throws IOException {
        ArrayNode schedule = (ArrayNode) JSON.readTree(source);
        for (JsonNode node : schedule) {
            ObjectNode fixture = (ObjectNode) node;
            ObjectNode stat = JSON.createObjectNode().put("identifier", "goals_scored");
            stat.set("h", JSON.createArrayNode());
            stat.set("a", JSON.createArrayNode());
            ArrayNode stats = JSON.createArrayNode().add(stat);
            fixture.set("stats", stats);
        }
        return JSON.writeValueAsBytes(schedule);
    }

    private static byte[] fixtureStatsForPlayers(byte[] source, int fixtureId,
                                                 int homePlayer, int awayPlayer) throws IOException {
        ArrayNode schedule = (ArrayNode) JSON.readTree(source);
        for (JsonNode node : schedule) {
            if (node.path("id").asInt() != fixtureId) continue;
            ObjectNode fixture = (ObjectNode) node;
            ArrayNode stats = JSON.createArrayNode();
            for (String identifier : List.of("saves", "bps")) {
                JsonNode original = null;
                for (JsonNode stat : fixture.path("stats")) {
                    if (identifier.equals(stat.path("identifier").asText())) original = stat;
                }
                if (original == null) throw new IllegalStateException("Missing fixture stat " + identifier);
                ObjectNode reduced = JSON.createObjectNode().put("identifier", identifier);
                ArrayNode home = JSON.createArrayNode();
                ArrayNode away = JSON.createArrayNode();
                home.addObject().put("element", homePlayer)
                        .put("value", fixtureStatValue(original, homePlayer));
                away.addObject().put("element", awayPlayer)
                        .put("value", fixtureStatValue(original, awayPlayer));
                reduced.set("h", home);
                reduced.set("a", away);
                stats.add(reduced);
            }
            fixture.set("stats", stats);
            return JSON.writeValueAsBytes(schedule);
        }
        throw new IllegalStateException("No fixture found: " + fixtureId);
    }

    private static int fixtureStatValue(JsonNode stat, int playerId) {
        for (String side : List.of("h", "a")) {
            for (JsonNode value : stat.path(side)) {
                if (value.path("element").asInt() == playerId) return value.path("value").asInt();
            }
        }
        throw new IllegalStateException("No fixture stat value for player " + playerId);
    }

    private static byte[] liveForFixturePlayers(byte[] source, int fixtureId, Set<Integer> playerIds)
            throws IOException {
        ObjectNode root = (ObjectNode) JSON.readTree(source);
        ArrayNode elements = JSON.createArrayNode();
        for (JsonNode node : root.path("elements")) {
            if (!playerIds.contains(node.path("id").asInt())) continue;
            ObjectNode element = ((ObjectNode) node).deepCopy();
            ArrayNode explanations = JSON.createArrayNode();
            for (JsonNode explanation : element.path("explain")) {
                if (explanation.path("fixture").asInt() == fixtureId) explanations.add(explanation.deepCopy());
            }
            element.set("explain", explanations);
            elements.add(element);
        }
        root.set("elements", elements);
        return JSON.writeValueAsBytes(root);
    }

    private byte[] historyForAwayPlayer28(int fixtureId) throws IOException {
        byte[] page = onlyHistoryForFixture(resource("element-summary-1.json"), fixtureId, 28);
        ObjectNode root = (ObjectNode) JSON.readTree(page);
        ObjectNode row = (ObjectNode) root.path("history").get(0);
        row.put("was_home", false);
        row.put("opponent_team", 1);
        return JSON.writeValueAsBytes(root);
    }

    private static byte[] withOptionalHistoryValues(byte[] source, int fixtureId, int playerId,
                                                     Map<String, ?> values) throws IOException {
        ObjectNode root = (ObjectNode) JSON.readTree(source);
        for (JsonNode node : root.path("history")) {
            if (node.path("fixture").asInt() != fixtureId || node.path("element").asInt() != playerId) continue;
            ObjectNode row = (ObjectNode) node;
            values.forEach((field, value) -> row.set(field, JSON.valueToTree(value)));
            return JSON.writeValueAsBytes(root);
        }
        throw new IllegalStateException("No history row for fixture " + fixtureId + " and player " + playerId);
    }

    private static byte[] withoutOptionalHistoryComponent(byte[] source, int fixtureId, int playerId,
                                                           String component) throws IOException {
        ObjectNode root = (ObjectNode) JSON.readTree(source);
        for (JsonNode node : root.path("history")) {
            if (node.path("fixture").asInt() != fixtureId || node.path("element").asInt() != playerId) continue;
            ((ObjectNode) node).remove(component);
            return JSON.writeValueAsBytes(root);
        }
        throw new IllegalStateException("No history row for fixture " + fixtureId + " and player " + playerId);
    }

    private static byte[] rewriteHistoryFixture(byte[] source, int fixtureId, int gameweek,
                                                 Instant kickoff) throws IOException {
        ObjectNode root = (ObjectNode) JSON.readTree(source);
        for (JsonNode node : root.path("history")) {
            if (node.path("fixture").asInt() != fixtureId) continue;
            ((ObjectNode) node).put("round", gameweek);
            ((ObjectNode) node).put("kickoff_time", kickoff.toString());
            return JSON.writeValueAsBytes(root);
        }
        throw new IllegalStateException("No history row for fixture " + fixtureId);
    }

    private static byte[] onlyFixtureUnstartedPastKickoff(byte[] source, int fixtureId, Instant kickoff)
            throws IOException {
        ArrayNode schedule = (ArrayNode) JSON.readTree(source);
        for (JsonNode node : schedule) {
            ObjectNode fixture = (ObjectNode) node;
            fixture.put("started", false);
            fixture.put("finished", false);
            fixture.put("finished_provisional", false);
            fixture.put("minutes", 0);
            fixture.putNull("team_h_score");
            fixture.putNull("team_a_score");
            if (fixture.path("id").asInt() == fixtureId) fixture.put("kickoff_time", kickoff.toString());
            else {
                fixture.putNull("event");
                fixture.putNull("kickoff_time");
            }
        }
        return JSON.writeValueAsBytes(schedule);
    }

    private static byte[] changeFixtureScore(byte[] source, int fixtureId, int homeScore) throws IOException {
        ArrayNode schedule = (ArrayNode) JSON.readTree(source);
        for (JsonNode node : schedule) {
            if (node.path("id").asInt() == fixtureId) {
                ((ObjectNode) node).put("team_h_score", homeScore);
                return JSON.writeValueAsBytes(schedule);
            }
        }
        throw new IllegalStateException("No fixture found: " + fixtureId);
    }

    private Instant finishedMarker(int fixtureId) {
        return jdbc.queryForObject("SELECT source_read_finished_at FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", java.sql.Timestamp.class, season.code(),
                OwnedSyncStore.FINISHED_FIXTURE_RECEIPT_PREFIX + fixtureId).toInstant();
    }

    private Set<Integer> sourceCandidates(byte[] fixtureSource, int fixtureId) throws IOException {
        SourceDocument bootstrapDocument = new SourceDocument(bootstrap,
                new CatalogueScheduleParser.ReadInterval(START, START.plusMillis(1)));
        SourceDocument fixtureDocument = new SourceDocument(fixtureSource,
                new CatalogueScheduleParser.ReadInterval(START.plusMillis(2), START.plusMillis(3)));
        var snapshot = CatalogueScheduleParser.parse(season, bootstrapDocument, fixtureDocument);
        FixtureRow selected = snapshot.fixtures().stream().filter(row -> row.id() == fixtureId)
                .findFirst().orElseThrow();
        Set<Integer> ids = new LinkedHashSet<>();
        snapshot.players().stream().filter(player -> player.teamId() == selected.homeTeamId()
                || player.teamId() == selected.awayTeamId()).map(PlayerRow::id).forEach(ids::add);
        ids.addAll(FixtureVerification.fixtureStatParticipantIds(selected));
        return Set.copyOf(ids);
    }

    private static int firstPlayerForFixture(byte[] bootstrap, byte[] fixtureSource, int fixtureId)
            throws IOException {
        FixtureKey fixture = fixtureById(fixtureSource, fixtureId);
        for (JsonNode player : JSON.readTree(bootstrap).path("elements")) {
            if (player.path("team").asInt() == fixture.homeTeam()
                    || player.path("team").asInt() == fixture.awayTeam()) return player.path("id").asInt();
        }
        throw new IllegalStateException("No catalogue player belongs to fixture " + fixtureId);
    }

    private static byte[] changeBootstrapPrice(byte[] source, int playerId, int nowCost) throws IOException {
        ObjectNode root = (ObjectNode) JSON.readTree(source);
        for (JsonNode player : root.path("elements")) {
            if (player.path("id").asInt() == playerId) {
                ((ObjectNode) player).put("now_cost", nowCost);
                return JSON.writeValueAsBytes(root);
            }
        }
        throw new IllegalStateException("No catalogue player " + playerId);
    }

    private static byte[] bootstrapPlayers(byte[] source, Set<Integer> playerIds) throws IOException {
        ObjectNode root = (ObjectNode) JSON.readTree(source);
        ArrayNode players = JSON.createArrayNode();
        for (JsonNode player : root.path("elements")) {
            if (playerIds.contains(player.path("id").asInt())) players.add(player);
        }
        if (players.size() != playerIds.size()) throw new IllegalStateException("Missing catalogue players");
        root.set("elements", players);
        return JSON.writeValueAsBytes(root);
    }

    private static byte[] bootstrapOnlyPlayer(byte[] source, int playerId) throws IOException {
        ObjectNode root = (ObjectNode) JSON.readTree(source);
        ArrayNode players = JSON.createArrayNode();
        for (JsonNode player : root.path("elements")) {
            if (player.path("id").asInt() == playerId) players.add(player);
        }
        if (players.isEmpty()) throw new IllegalStateException("No catalogue player " + playerId);
        root.set("elements", players);
        return JSON.writeValueAsBytes(root);
    }

    private static byte[] relabelBootstrapPlayer(byte[] source, int sourceId, int playerId) throws IOException {
        ObjectNode root = (ObjectNode) JSON.readTree(source);
        for (JsonNode node : root.path("elements")) {
            if (node.path("id").asInt() == sourceId) {
                ((ObjectNode) node).put("id", playerId);
                return JSON.writeValueAsBytes(root);
            }
        }
        throw new IllegalStateException("No catalogue player " + sourceId);
    }

    private static byte[] historyWithMinutes(byte[] source, int fixtureId, int playerId, int minutes)
            throws IOException {
        byte[] page = onlyHistoryForFixture(source, fixtureId, playerId);
        ObjectNode root = (ObjectNode) JSON.readTree(page);
        ObjectNode row = (ObjectNode) root.path("history").get(0);
        row.put("minutes", minutes);
        row.put("starts", minutes > 0 ? 1 : 0);
        return JSON.writeValueAsBytes(root);
    }

    private static byte[] liveOnlyPlayer(byte[] source, int playerId) throws IOException {
        ObjectNode root = (ObjectNode) JSON.readTree(source);
        ArrayNode elements = JSON.createArrayNode();
        for (JsonNode element : root.path("elements")) {
            if (element.path("id").asInt() == playerId) elements.add(element);
        }
        root.set("elements", elements);
        return JSON.writeValueAsBytes(root);
    }

    private static byte[] relabelLivePlayer(byte[] source, int sourcePlayerId, int playerId) throws IOException {
        ObjectNode root = (ObjectNode) JSON.readTree(source);
        ArrayNode elements = JSON.createArrayNode();
        for (JsonNode node : root.path("elements")) {
            if (node.path("id").asInt() != sourcePlayerId) continue;
            ObjectNode element = ((ObjectNode) node).deepCopy();
            element.put("id", playerId);
            elements.add(element);
        }
        if (elements.isEmpty()) throw new IllegalStateException("No recorded live player " + sourcePlayerId);
        root.set("elements", elements);
        return JSON.writeValueAsBytes(root);
    }

    private static int fixtureExplainedFor(byte[] liveBody, int playerId) throws IOException {
        JsonNode root = JSON.readTree(liveBody);
        for (JsonNode element : root.path("elements")) {
            if (element.path("id").asInt() != playerId) continue;
            return element.path("explain").get(0).path("fixture").asInt();
        }
        throw new IllegalStateException("Recorded live source has no player " + playerId);
    }

    private static ActiveFixture makeFixtureInProgress(byte[] source, int gameweek) throws IOException {
        ArrayNode schedule = (ArrayNode) JSON.readTree(source);
        for (JsonNode node : schedule) {
            if (node.path("event").asInt() != gameweek || !node.path("finished").asBoolean()) continue;
            ObjectNode fixture = (ObjectNode) node;
            fixture.put("started", true);
            fixture.put("finished", false);
            fixture.put("finished_provisional", false);
            fixture.put("minutes", 45);
            return new ActiveFixture(JSON.writeValueAsBytes(schedule), fixture.path("id").asInt());
        }
        throw new IllegalStateException("No finished fixture found for gameweek " + gameweek);
    }

    private static FixtureKey fixture(byte[] source, int gameweek, boolean finished) throws IOException {
        ArrayNode schedule = (ArrayNode) JSON.readTree(source);
        for (JsonNode node : schedule) {
            if (node.path("event").asInt() != gameweek || node.path("finished").asBoolean() != finished) continue;
            return fixtureKey(node);
        }
        throw new IllegalStateException("No matching fixture found for gameweek " + gameweek);
    }

    private static FixtureKey fixtureById(byte[] source, int fixtureId) throws IOException {
        for (JsonNode node : JSON.readTree(source)) {
            if (node.path("id").asInt() == fixtureId) return fixtureKey(node);
        }
        throw new IllegalStateException("No fixture found: " + fixtureId);
    }

    private static FixtureKey fixtureKey(JsonNode node) {
        return new FixtureKey(node.path("id").asInt(), node.path("event").asInt(),
                node.path("team_h").asInt(), node.path("team_a").asInt(),
                Instant.parse(node.path("kickoff_time").asText()), node.path("started").asBoolean(),
                node.path("finished").asBoolean());
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream input = CoalescedPlayerHistoryCollectorIntegrationTest.class
                .getResourceAsStream("/owned-sync-source-fixtures/" + name)) {
            if (input == null) throw new IllegalStateException("Missing recorded source: " + name);
            return input.readAllBytes();
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing " + name);
        return value;
    }

    private static final class RecordedFetch implements CoalescedPlayerHistoryCollector.Fetch {
        private static final byte[] EMPTY_HISTORY = "{\"history\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        private static final byte[] EMPTY_LIVE = "{\"elements\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        private final List<Endpoint> calls = new ArrayList<>();
        private final List<OwnedSyncRequestBudget.Demand> demands = new ArrayList<>();
        private OwnedSyncRequestBudget.Purpose blockedPurpose;
        private final List<RecordedRequest> requests = new ArrayList<>();
        private final List<RecordedRead> successfulReads = new ArrayList<>();
        private final List<Integer> successfulBodyBytes = new ArrayList<>();
        private byte[] bootstrap;
        private byte[] fixtures;
        private byte[] eventThree;
        private byte[] eventFour;
        private byte[] terminalBootstrap;
        private byte[] terminalFixtures;
        private final Map<Integer, byte[]> playerPages = new java.util.HashMap<>();
        private MutableClock clock;
        private Runnable firstRequest;
        private int failedPageCall;
        private int deferredPageCall;
        private int pageCalls;
        private final Map<Integer, Integer> failingPlayerCalls = new java.util.HashMap<>();
        private Instant deferUntil;
        private boolean failFirstPage;
        private boolean subMillisecondReads;
        private boolean firstRequestRun;

        private void setSources(byte[] bootstrap, byte[] fixtures, byte[] eventThree, byte[] eventFour) {
            this.bootstrap = bootstrap;
            this.fixtures = fixtures;
            this.eventThree = eventThree;
            this.eventFour = eventFour;
        }

        private void setTerminalSources(byte[] bootstrap, byte[] fixtures) {
            terminalBootstrap = bootstrap;
            terminalFixtures = fixtures;
        }

        private void bindClock(MutableClock clock) { this.clock = clock; }
        private void useSubMillisecondReads() { subMillisecondReads = true; }
        private void onFirstRequest(Runnable callback) { firstRequest = callback; }
        private void failPlayerCall(int call) { failFirstPage = true; failedPageCall = call; }
        private void failPlayer(int playerId) { failPlayerCalls(playerId, 1); }
        private void failPlayerCalls(int playerId, int calls) {
            if (calls <= 0) failingPlayerCalls.remove(playerId);
            else failingPlayerCalls.put(playerId, calls);
        }
        private void setEventFourLive(byte[] body) { eventFour = body; }
        private void setPlayerPage(int playerId, byte[] body) { playerPages.put(playerId, body.clone()); }
        private void deferOnPlayerCall(int call, Instant due) { deferredPageCall = call; deferUntil = due; }

        @Override
        public OwnedSyncHttpClient.Result get(Lease lease, Endpoint endpoint, int attempt, Instant floor,
                                              OwnedSyncRequestBudget.Demand demand) {
            demands.add(demand);
            if (demand.purpose() == blockedPurpose) {
                return new DeferredResult(clock.instant().plus(Duration.ofHours(8)), DeferredReason.CAPACITY_RESERVED);
            }
            return get(lease, endpoint, attempt, floor);
        }

        @Override
        public OwnedSyncHttpClient.Result get(Lease lease, Endpoint endpoint, int failureAttempt, Instant floor) {
            if (clock == null) throw new IllegalStateException("clock not bound");
            if (clock.instant().isBefore(floor)) clock.set(floor);
            calls.add(endpoint);
            requests.add(new RecordedRequest(endpoint, floor));
            if (!firstRequestRun) {
                firstRequestRun = true;
                if (firstRequest != null) firstRequest.run();
            }
            if (endpoint.kind() == EndpointKind.ELEMENT_SUMMARY) {
                pageCalls++;
                if (deferredPageCall == pageCalls) {
                    return new DeferredResult(deferUntil, DeferredReason.COOLDOWN);
                }
                Integer remainingFailures = failingPlayerCalls.get(endpoint.playerId());
                if (failFirstPage && failedPageCall == pageCalls
                        || remainingFailures != null && remainingFailures > 0) {
                    if (remainingFailures != null && remainingFailures > 0) {
                        if (remainingFailures == 1) failingPlayerCalls.remove(endpoint.playerId());
                        else failingPlayerCalls.put(endpoint.playerId(), remainingFailures - 1);
                    }
                    return new Failure(FailureKind.IO_ERROR, "recorded page failure", null, null,
                            interval(), null);
                }
            }
            byte[] body = switch (endpoint.kind()) {
                case BOOTSTRAP_STATIC -> terminalBootstrap != null && count(EndpointKind.BOOTSTRAP_STATIC) > 1
                        ? terminalBootstrap : bootstrap;
                case FIXTURES -> terminalFixtures != null && count(EndpointKind.FIXTURES) > 1
                        ? terminalFixtures : fixtures;
                case EVENT_LIVE -> endpoint.eventId() == 4 ? eventFour
                        : endpoint.eventId() == 3 ? eventThree : eventThree == null ? EMPTY_LIVE : eventThree;
                case ELEMENT_SUMMARY -> playerPages.getOrDefault(endpoint.playerId(), EMPTY_HISTORY);
            };
            SourceRead read = interval();
            successfulReads.add(new RecordedRead(endpoint, read));
            successfulBodyBytes.add(body.length);
            return new Response(200, body, null, read);
        }

        private SourceRead interval() {
            Instant start = clock.instant();
            Instant finish = subMillisecondReads ? start.plusNanos(300) : start.plusMillis(1);
            clock.set(finish.plusMillis(subMillisecondReads ? 1 : 5));
            return new SourceRead(start, finish);
        }

        private int count(EndpointKind kind) {
            return (int) calls.stream().filter(endpoint -> endpoint.kind() == kind).count();
        }

        private List<Integer> playerIds() {
            return calls.stream().filter(endpoint -> endpoint.kind() == EndpointKind.ELEMENT_SUMMARY)
                    .map(Endpoint::playerId).toList();
        }

        private List<Endpoint> calls() { return List.copyOf(calls); }
        private List<RecordedRequest> requests() { return List.copyOf(requests); }
        private int returnedBodyBytes() { return successfulBodyBytes.stream().mapToInt(Integer::intValue).sum(); }
        private void clearCalls() {
            calls.clear();
            requests.clear();
            successfulReads.clear();
            successfulBodyBytes.clear();
            pageCalls = 0;
            firstRequestRun = false;
            firstRequest = null;
        }

        private void clearPageOutcomes() {
            failFirstPage = false;
            failingPlayerCalls.clear();
            deferredPageCall = 0;
            deferUntil = null;
        }

        private List<RecordedRead> successfulReads() { return List.copyOf(successfulReads); }
    }

    private static int knownPlayerHydrationQueryCount(SqlRecordingDataSource recording) {
        return (int) recording.statements().stream()
                .map(sql -> sql.stripLeading().toLowerCase(java.util.Locale.ROOT))
                .filter(sql -> sql.startsWith("select fixture_id, player_id from player_fixture_stats"
                        + " where season_id=? and fixture_id in ("))
                .count();
    }

    private static int discoveryCandidateBatchCount(SqlRecordingDataSource recording) {
        return recording.discoveryCandidateWriteExecutions;
    }

    private static final class SqlRecordingDataSource implements DataSource {
        private final DataSource delegate;
        private final List<String> statements = new ArrayList<>();
        private int queryExecutions;
        private int updateExecutions;
        private long updateRowsAffected;
        private long returnedRows;
        private long approximateLogicalReturnedBytes;
        private long receiptWriteDelayNanos;
        private int receiptWriteExecutions;
        private int discoveryCandidateWriteExecutions;
        private int failReceiptWriteAt = -1;

        private SqlRecordingDataSource(DataSource delegate) { this.delegate = delegate; }

        @Override public Connection getConnection() throws SQLException { return connection(delegate.getConnection()); }
        @Override public Connection getConnection(String username, String password) throws SQLException {
            return connection(delegate.getConnection(username, password));
        }
        @Override public java.io.PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }
        @Override public void setLogWriter(java.io.PrintWriter writer) throws SQLException { delegate.setLogWriter(writer); }
        @Override public void setLoginTimeout(int seconds) throws SQLException { delegate.setLoginTimeout(seconds); }
        @Override public int getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }
        @Override public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface.isInstance(this)) return iface.cast(this);
            return delegate.unwrap(iface);
        }
        @Override public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return iface.isInstance(this) || delegate.isWrapperFor(iface);
        }

        private Connection connection(Connection target) {
            return (Connection) Proxy.newProxyInstance(ConnectionProxy.class.getClassLoader(),
                    new Class<?>[] { ConnectionProxy.class }, (proxy, method, args) -> {
                        if (method.getName().equals("getTargetConnection")) return target;
                        if ((method.getName().equals("prepareStatement") || method.getName().equals("prepareCall"))
                                && args != null && args.length > 0 && args[0] instanceof String sql) {
                            statements.add(sql);
                            return statement(invoke(target, method, args), sql);
                        }
                        Object result = invoke(target, method, args);
                        return result instanceof Statement statement ? statement(statement, null) : result;
                    });
        }

        private Statement statement(Object value, String preparedSql) {
            Statement target = (Statement) value;
            Class<?> contract = target instanceof CallableStatement ? CallableStatement.class
                    : target instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
            String[] publicationKey = { null };
            boolean[] discoveryBatch = { false };
            return (Statement) Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[] { contract },
                    (proxy, method, args) -> {
                        String name = method.getName();
                        String sql = preparedSql;
                        if (sql == null && args != null && args.length > 0 && args[0] instanceof String query
                                && (name.equals("execute") || name.equals("executeQuery")
                                    || name.equals("executeUpdate") || name.equals("addBatch"))) {
                            sql = query;
                            statements.add(query);
                        }
                        if ((name.equals("setString") || name.equals("setObject"))
                                && args != null && args.length > 1 && Integer.valueOf(2).equals(args[0])) {
                            publicationKey[0] = args[1] instanceof String key ? key : null;
                        } else if (name.equals("clearParameters")) {
                            publicationKey[0] = null;
                        }
                        boolean discoveryKey = publicationKey[0] != null && publicationKey[0]
                                .startsWith(OwnedSyncStore.INCREMENTAL_DISCOVERY_RECEIPT_PREFIX);
                        if (name.equals("addBatch") && discoveryKey) discoveryBatch[0] = true;
                        if (name.equals("clearBatch")) discoveryBatch[0] = false;
                        if ((name.equals("executeQuery") || name.equals("execute")) && isReadSql(sql)) {
                            queryExecutions++;
                        }
                        if (sql != null && sql.contains("INSERT INTO " + TABLES.receipt())
                                && (name.equals("executeUpdate") || name.equals("executeLargeUpdate")
                                    || name.equals("executeBatch") || name.equals("executeLargeBatch"))) {
                            receiptWriteExecutions++;
                            boolean batch = name.equals("executeBatch") || name.equals("executeLargeBatch");
                            if (batch ? discoveryBatch[0] : discoveryKey) discoveryCandidateWriteExecutions++;
                            if (batch) discoveryBatch[0] = false;
                            if (receiptWriteExecutions == failReceiptWriteAt) {
                                throw new SQLException("Injected receipt batch failure", "57014");
                            }
                            if (receiptWriteDelayNanos > 0) {
                                java.util.concurrent.locks.LockSupport.parkNanos(receiptWriteDelayNanos);
                            }
                        }
                        Object result = invoke(target, method, args);
                        if (name.equals("executeUpdate") || name.equals("executeLargeUpdate")) {
                            updateExecutions++;
                            if (result instanceof Number affected) updateRowsAffected += affected.longValue();
                        }
                        if (result instanceof ResultSet resultSet) return resultSet(resultSet);
                        return result;
                    });
        }

        private ResultSet resultSet(ResultSet target) {
            return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                    new Class<?>[] { ResultSet.class }, (proxy, method, args) -> {
                        Object result = invoke(target, method, args);
                        if (method.getName().equals("next") && Boolean.TRUE.equals(result)) returnedRows++;
                        else if (method.getName().startsWith("get") && args != null && args.length > 0
                                && (args[0] instanceof Integer || args[0] instanceof String)
                                && result != null && !(result instanceof ResultSet)) {
                            approximateLogicalReturnedBytes += approximateSize(result);
                        }
                        return result;
                    });
        }

        private static boolean isReadSql(String sql) {
            if (sql == null) return false;
            String normalized = sql.stripLeading().toLowerCase(java.util.Locale.ROOT);
            return normalized.startsWith("select") || normalized.startsWith("with")
                    || normalized.startsWith("show") || normalized.startsWith("values");
        }

        private static long approximateSize(Object value) {
            if (value instanceof byte[] bytes) return bytes.length;
            if (value instanceof Boolean) return 1;
            if (value instanceof Number number) {
                return number.toString().getBytes(StandardCharsets.UTF_8).length;
            }
            return String.valueOf(value).getBytes(StandardCharsets.UTF_8).length;
        }

        private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        }

        private List<String> statements() { return List.copyOf(statements); }
        private int queryExecutions() { return queryExecutions; }
        private int updateExecutions() { return updateExecutions; }
        private long updateRowsAffected() { return updateRowsAffected; }
        private long returnedRows() { return returnedRows; }
        private long approximateLogicalReturnedBytes() { return approximateLogicalReturnedBytes; }
        private void clear() {
            statements.clear();
            queryExecutions = 0;
            updateExecutions = 0;
            updateRowsAffected = 0;
            returnedRows = 0;
            approximateLogicalReturnedBytes = 0;
            receiptWriteExecutions = 0;
            discoveryCandidateWriteExecutions = 0;
        }
    }

    private static final class MutableClock extends Clock {
        private Instant current;
        private final ZoneId zone;

        private MutableClock(Instant current) { this(current, ZoneId.of("UTC")); }
        private MutableClock(Instant current, ZoneId zone) { this.current = current; this.zone = zone; }
        private synchronized void set(Instant instant) { current = instant; }
        private synchronized void advance(Duration duration) { current = current.plus(duration); }
        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId selected) { return new MutableClock(current, selected); }
        @Override public synchronized Instant instant() { return current; }
    }

    private record StagedCorePair(Lease lease, SourceRead bootstrapRead, SourceRead fixturesRead) { }
    private record PairCapture(byte[] bootstrap, byte[] fixtures, byte[] live, FixtureRow fixtureOne,
                               FixtureRow fixtureTwo, Map<Integer, byte[]> historyPages, List<TeamRow> teams) { }
    private record SingleFixtureCapture(byte[] bootstrap, byte[] fixtures, byte[] live, FixtureRow fixture,
                                        Map<Integer, byte[]> historyPages, List<TeamRow> teams) { }
    private record CheckedFixtureSetup(int fixtureId, int cataloguePlayer, byte[] fixtureSource,
                                       byte[] bootstrapSource, byte[] liveSource) { }
    private record MultiFixtureSetup(Set<Integer> fixtureIds,
                                     Map<Integer, Set<Integer>> participantsByFixture,
                                     Set<Integer> participantIds, Map<Integer, byte[]> historyPages,
                                     byte[] fixtureSource, byte[] bootstrapSource, byte[] liveSource) { }
    private record FixtureKey(int id, int gameweek, int homeTeam, int awayTeam, Instant kickoff,
                              boolean started, boolean finished) { }
    private record ActiveFixture(byte[] body, int id) { }
    private record RecordedRequest(Endpoint endpoint, Instant floor) { }
    private record RecordedRead(Endpoint endpoint, SourceRead read) { }
}
