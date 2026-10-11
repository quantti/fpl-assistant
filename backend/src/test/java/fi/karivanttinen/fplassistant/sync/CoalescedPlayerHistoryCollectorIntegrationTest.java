package fi.karivanttinen.fplassistant.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
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
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.sql.DataSource;

import fi.karivanttinen.fplassistant.defcon.DefensiveStatsSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.FixtureRow;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.GameweekRow;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.PlayerRow;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.Season;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.SourceDocument;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.TeamRow;
import fi.karivanttinen.fplassistant.sync.HistoryParser.CanonicalFixture;
import fi.karivanttinen.fplassistant.sync.IncrementalPfsRepository.PublicationResult;
import fi.karivanttinen.fplassistant.sync.CoalescedPlayerHistoryCollector.CycleResult;
import fi.karivanttinen.fplassistant.sync.CoalescedPlayerHistoryCollector.State;
import fi.karivanttinen.fplassistant.sync.OwnedSyncHttpClient.DeferredResult;
import fi.karivanttinen.fplassistant.sync.OwnedSyncHttpClient.Failure;
import fi.karivanttinen.fplassistant.sync.OwnedSyncHttpClient.FailureKind;
import fi.karivanttinen.fplassistant.sync.OwnedSyncRequestBudget;
import fi.karivanttinen.fplassistant.sync.OwnedSyncHttpClient.Response;
import fi.karivanttinen.fplassistant.sync.OwnedSyncRequestBudget.DeferredReason;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.Endpoint;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.EndpointKind;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.Lease;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.RuntimeFence;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.SourceRead;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.SyncKind;
import fi.karivanttinen.fplassistant.testsupport.TapasSeed;
import fi.karivanttinen.fplassistant.tools.PfsReadSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
    private static final int SEASON_ID = 27;
    private static final int KNOWN_PLAYER = 99001;
    private static final int FINAL_PENDING_PLAYER = 99002;
    private static final int ACTIVE_PLAYER = 99003;
    private static final int LOCAL_HTTP_PLAYER = 99005;
    private static final int WATERMARK_PLAYER = 99006;
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
        jdbc = TapasSeed.openDisposableTestJdbc(url, user, password, jdbcSupplier);
        dataSource = jdbc.getDataSource();
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        TapasSeed.ensureOwnedSyncCaptureSeason(jdbc);
        disposableDatabaseValidated = true;
        originalCurrentSeasons = jdbc.query("SELECT id FROM season WHERE is_current=true ORDER BY id",
                (rs, row) -> rs.getInt(1));
        seasonBaselineCaptured = true;
        TapasSeed.setCurrentSeason(jdbc, SEASON_ID);
        seasonFlagsChanged = true;
        season = jdbc.queryForObject("SELECT id, code, start_date, end_date FROM season WHERE id=?",
                (rs, row) -> new Season(rs.getInt("id"), rs.getString("code"),
                        rs.getDate("start_date").toLocalDate(),
                        rs.getDate("end_date") == null ? null : rs.getDate("end_date").toLocalDate()), SEASON_ID);
        assertThat(season).isEqualTo(new Season(27, "2026-27", LocalDate.of(2026, 8, 21), null));
        new OwnedSyncSchema(jdbc, TABLES).initialize();
        new DefensiveStatsSchema(jdbc).ensureColumns();
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
        jdbc.update("DELETE FROM player_fixture_stats WHERE season_id=?", SEASON_ID);
        jdbc.update("DELETE FROM fixture WHERE season_id=?", SEASON_ID);
        jdbc.update("DELETE FROM gameweek WHERE season_id=?", SEASON_ID);
        jdbc.update("DELETE FROM player WHERE season_id=?", SEASON_ID);
        jdbc.update("DELETE FROM team WHERE season_id=?", SEASON_ID);
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
        TapasSeed.seedOwnedSyncKnownHistoryKey(jdbc, SEASON_ID, knownFixture.id(), knownFixture.gameweek(),
                KNOWN_PLAYER, knownFixture.homeTeam(), knownFixture.awayTeam(), true, knownFixture.kickoff());
        byte[] ordinaryBootstrap = markGameweekUnchecked(
                markGameweekUnchecked(markGameweekUnchecked(bootstrap, 1), 2), 3);
        fetch.setSources(ordinaryBootstrap, source, eventThreeLive, eventFourLive);
        fetch.setPlayerPage(1, onlyHistoryForFixture(resource("element-summary-1.json"), 29, 1));
        fetch.setPlayerPage(28, historyForAwayPlayer28(29));

        SqlRecordingDataSource recording = useRecordingJdbc();
        installUnit6WriteCounters();
        AtomicReference<CycleResult> nested = new AtomicReference<>();
        AtomicReference<CoalescedPlayerHistoryCollector> collectorRef = new AtomicReference<>();
        fetch.onFirstRequest(() -> nested.set(collectorRef.get().runCycle()));
        CoalescedPlayerHistoryCollector collector = collector(true);
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
        GameweekFinalityRepository finality = new GameweekFinalityRepository(jdbc, store, TABLES, clock);

        var started = finality.beginOrResume(lease, season, source, population, initialRead);
        assertThat(started.state()).isEqualTo(fi.karivanttinen.fplassistant.sync.GameweekFinality.State.FINAL_PENDING);
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
        byte[] awayHistory = historyForAwayPlayer28(fixtureId);
        publisher.publish(lease, season, awayPlayer, document(awayHistory, pageRead), canonical);
        publisher.publish(lease, season, emptyCataloguePlayer,
                document("{\"history\":[]}".getBytes(StandardCharsets.UTF_8), pageRead), canonical);
        clock.set(pageRead.finishedAt());

        // Simulate a crash after the page/row commit but before the checkpoint completion marker.
        clock.set(lease.expiresAt().plusMillis(1));
        Lease restartedLease = store.acquireIncrementalCoreImport(season.code(), "finality-test-restarted",
                Duration.ofMinutes(30)).orElseThrow();
        SourceRead restartRead = new SourceRead(clock.instant(), clock.instant().plusMillis(3));
        var restartSnapshot = CatalogueScheduleParser.parse(season,
                document(checkedBootstrap, new SourceRead(restartRead.startedAt(), restartRead.startedAt().plusMillis(1))),
                document(sourceFixtures, new SourceRead(restartRead.startedAt().plusMillis(2), restartRead.finishedAt())));
        clock.set(restartRead.finishedAt());
        var resumed = finality.beginOrResume(restartedLease, season,
                fi.karivanttinen.fplassistant.sync.GameweekFinality.SourceSnapshot.from(restartSnapshot, 3), population, restartRead);
        assertThat(resumed.checkpoint().completedPlayerIds())
                .containsExactly(homePlayer, emptyCataloguePlayer, awayPlayer);
        assertThat(resumed.checkpoint().outstandingPlayerIds()).isEmpty();
        assertThat(resumed.pageReadInterval()).isEqualTo(pageRead);
        assertThat(resumed.sourceReadInterval()).isEqualTo(new SourceRead(initialRead.startedAt(), restartRead.finishedAt()));
        assertThat(resumed.initialSourceReadInterval()).isEqualTo(initialRead);
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.gameweekStatSync()
                + " WHERE season_id=? AND gameweek=3", String.class, SEASON_ID)).isEqualTo("FINAL_PENDING");

        SourceRead endRead = new SourceRead(restartRead.finishedAt().plusMillis(1),
                restartRead.finishedAt().plusMillis(3));
        byte[] refreshedBootstrap = checkedBootstrap.clone();
        var endSnapshot = CatalogueScheduleParser.parse(season,
                document(refreshedBootstrap, new SourceRead(endRead.startedAt(), endRead.startedAt().plusMillis(1))),
                document(sourceFixtures, new SourceRead(endRead.startedAt().plusMillis(2), endRead.finishedAt())));
        var endSource = fi.karivanttinen.fplassistant.sync.GameweekFinality.SourceSnapshot.from(endSnapshot, 3);
        clock.set(endRead.finishedAt());
        var receipt = finality.finalizeSweep(restartedLease, season, endSource, population, endRead);
        assertThat(finality.load(restartedLease, season, 3).orElseThrow().finalizedSourceReadInterval())
                .isEqualTo(receipt.sourceReadInterval());
        assertThat(finality.load(restartedLease, season, 3).orElseThrow().initialSourceReadInterval())
                .isEqualTo(initialRead);
        assertThat(store.incrementalSourceInterval(restartedLease)).isEqualTo(receipt.sourceReadInterval());
        var afterRestart = finality.beginOrResume(restartedLease, season, endSource, population, endRead);
        assertThat(afterRestart.state()).isEqualTo(fi.karivanttinen.fplassistant.sync.GameweekFinality.State.FINALIZED);
        assertThat(afterRestart.invalidationRevision()).isEqualTo(receipt.invalidationRevision());
        store.completeStaged(restartedLease, store.incrementalSourceInterval(restartedLease),
                List.of(initialRead, pageRead, restartRead, endRead));

        var validated = reader.read(SEASON_ID, season.code(), Set.of(3)).get(3);
        assertThat(validated.state()).isEqualTo(GameweekFinality.State.FINALIZED);
        assertThat(validated.receipt()).isEqualTo(receipt);
        assertThat(receipt.sourceSignature()).isEqualTo(source.signature());
        assertThat(receipt.rowRevisionFingerprint()).matches("[0-9a-f]{64}");
        assertThat(receipt.optionalCoverage()).hasSize(HistoryParser.Component.values().length);
        assertThat(receipt.sourceReadInterval())
                .isEqualTo(new SourceRead(initialRead.startedAt(), endRead.finishedAt()));

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
    void checkedTenFixtureGameweekResumesNoopsAndCorrectsOnlyTheChangedRow() throws Exception {
        MultiFixtureSetup setup = checkedMultiFixtureSetup();
        assertThat(setup.fixtureIds()).hasSize(10);
        assertThat(setup.participantIds()).hasSize(40);
        assertThat(setup.participantsByFixture()).allSatisfy((fixtureId, ids) -> assertThat(ids).hasSize(4));

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
            assertThat(partial.selectedPlayerIds()).hasSize(657);
            assertThat(partial.failedPlayerIds()).containsExactly(failedPlayer);
            assertThat(partial.committedPlayerIds()).hasSize(656);
            assertThat(fetch.count(EndpointKind.BOOTSTRAP_STATIC)).isOne();
            assertThat(fetch.count(EndpointKind.FIXTURES)).isOne();
            assertThat(fetch.count(EndpointKind.EVENT_LIVE)).as("live events %s", fetch.calls().stream()
                    .filter(endpoint -> endpoint.kind() == EndpointKind.EVENT_LIVE)
                    .map(Endpoint::eventId).toList()).isOne();
            assertThat(fetch.count(EndpointKind.ELEMENT_SUMMARY)).isEqualTo(657);
            int initialHistoryGets = fetch.count(EndpointKind.ELEMENT_SUMMARY);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM team WHERE season_id=?", Integer.class,
                    SEASON_ID)).isEqualTo(20);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM player WHERE season_id=?", Integer.class,
                    SEASON_ID)).isEqualTo(657);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM gameweek WHERE season_id=?", Integer.class,
                    SEASON_ID)).isEqualTo(38);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fixture WHERE season_id=?", Integer.class,
                    SEASON_ID)).isEqualTo(380);
            Map<String, Long> catalogueWritesAfterFirstCycle = compatibilityCatalogueWriteCounts();
            assertThat(catalogueWritesAfterFirstCycle).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "team/INSERT", 20L, "team/UPDATE", 0L,
                    "player/INSERT", 657L, "player/UPDATE", 0L,
                    "gameweek/INSERT", 38L, "gameweek/UPDATE", 0L,
                    "fixture/INSERT", 380L, "fixture/UPDATE", 0L));
            assertThat(jdbc.queryForObject("SELECT cardinality(final_attempt_required_player_ids) FROM "
                    + TABLES.gameweekStatSync() + " WHERE season_id=? AND gameweek=3", Integer.class,
                    SEASON_ID)).isEqualTo(657);
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

            System.out.printf("UNIT6_MULTI_FIXTURE_SYNTHETIC gameweek=3 fixtures=%d clubs=20 cataloguePlayers=657 "
                            + "sourceParticipants=%d initialHistoryGets=%d resumeHistoryGets=%d "
                            + "sourceOnlyRetries=%d noopHistoryGets=%d correctionPfsUpdates=%d "
                            + "catalogueWritesFirst=%s catalogueWritesCorrection=%s readerFinalizedAfterCorrectionAndNoop=%s%n",
                    setup.fixtureIds().size(), setup.participantIds().size(), initialHistoryGets,
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
    void offThenActiveRevalidatesFinalizedPageWatermarks() throws Exception {
        assertFinalizedEvidenceTamperReopens(setup -> {
            PlannerRuntimeRepository runtime = new PlannerRuntimeRepository(jdbc, tx, clock, TABLES);
            runtime.deactivate(runtimeFence);
            runtimeFence = runtime.activate(season.code(), OwnedSyncMode.ACTIVE);
            store = new OwnedSyncStore(jdbc, tx, clock, TABLES, runtimeFence);
            publisher = new IncrementalPlayerFixturePublisher(
                    new IncrementalPfsRepository(jdbc, clock, store, TABLES));
        });
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
        var source = fi.karivanttinen.fplassistant.sync.GameweekFinality.SourceSnapshot.from(initialSnapshot, 4);
        var populationAB = new fi.karivanttinen.fplassistant.sync.GameweekFinality.RequiredPopulation(
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
        var populationA = new fi.karivanttinen.fplassistant.sync.GameweekFinality.RequiredPopulation(
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
        var source = fi.karivanttinen.fplassistant.sync.GameweekFinality.SourceSnapshot.from(initialSnapshot, 4);
        var population = new fi.karivanttinen.fplassistant.sync.GameweekFinality.RequiredPopulation(
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
                fi.karivanttinen.fplassistant.sync.GameweekFinality.SourceSnapshot.from(endSnapshot, 4),
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
                .isEqualTo(fi.karivanttinen.fplassistant.sync.GameweekFinality.State.FINALIZED);
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
        TapasSeed.ensure(jdbc);
        TapasSeed.setCurrentSeason(jdbc, TapasSeed.CURRENT_SEASON_ID);
        try {
            assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> collector(true).runCycle()))
                    .isInstanceOf(OwnedSyncStore.RuntimeFenceLostException.class);
            assertThat(fetch.calls()).isEmpty();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run(), Integer.class)).isZero();
        } finally {
            TapasSeed.setCurrentSeason(jdbc, SEASON_ID);
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

    private void assertFinalizedEvidenceTamperReopens(java.util.function.Consumer<CheckedFixtureSetup> tamper)
            throws Exception {
        CheckedFixtureSetup setup = checkedSingleFixtureSetup();
        CycleResult finalized = collector(true).runCycle();
        assertThat(finalized.state()).as("last error: %s", latestRunError()).isEqualTo(State.COMPLETED);
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.gameweekStatSync()
                + " WHERE season_id=? AND gameweek=3", String.class, SEASON_ID)).isEqualTo("FINALIZED");

        tamper.accept(setup);
        fetch.clearCalls();
        clock.set(clock.instant().plusSeconds(60));
        CycleResult reopened = collector(true).runCycle();

        assertThat(reopened.state()).as("last error: %s", latestRunError()).isEqualTo(State.COMPLETED);
        assertThat(reopened.selectedPlayerIds()).contains(setup.cataloguePlayer(), 28);
        assertThat(fetch.playerIds()).contains(setup.cataloguePlayer(), 28);
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.gameweekStatSync()
                + " WHERE season_id=? AND gameweek=3", String.class, SEASON_ID)).isEqualTo("FINALIZED");
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
        new CompatibilityCatalogueWriter(jdbc, clock, store, TABLES)
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
        byte[] checkedBootstrap = markGameweekChecked(markAllGameweeksUnchecked(bootstrap), 3);
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

    private CoalescedPlayerHistoryCollector collector(boolean enabled) {
        return new CoalescedPlayerHistoryCollector(season, enabled, store, publisher, fetch, clock,
                "collector-test");
    }

    private CoalescedPlayerHistoryCollector compatibilityCollector(boolean enabled) {
        CompatibilityCatalogueWriter compatibility = new CompatibilityCatalogueWriter(jdbc, clock, store, TABLES);
        return new CoalescedPlayerHistoryCollector(season, enabled, store, publisher, compatibility, fetch, clock,
                "compatibility-collector-test");
    }

    private CoalescedPlayerHistoryCollector compatibilityCollector(boolean enabled, JdbcTemplate targetJdbc,
                                                                   TransactionTemplate targetTx) {
        store = new OwnedSyncStore(targetJdbc, targetTx, clock, TABLES, runtimeFence);
        publisher = new IncrementalPlayerFixturePublisher(
                new IncrementalPfsRepository(targetJdbc, clock, store, TABLES));
        CompatibilityCatalogueWriter compatibility = new CompatibilityCatalogueWriter(targetJdbc, clock,
                store, TABLES);
        return new CoalescedPlayerHistoryCollector(season, enabled, store, publisher, compatibility, fetch, clock,
                "compatibility-collector-test");
    }

    private Lease nextCoreLease() {
        store.enqueueIncrementalCoreImport(season, clock.instant());
        return store.acquireIncrementalCoreImport(season.code(), "watermark-test", Duration.ofMinutes(30))
                .orElseThrow();
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
                + TABLES.plannerSlot() + " CASCADE");
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
        private int failingPlayerCalls;
        private Instant deferUntil;
        private boolean failFirstPage;
        private Integer failingPlayerId;
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
        private void onFirstRequest(Runnable callback) { firstRequest = callback; }
        private void failPlayerCall(int call) { failFirstPage = true; failedPageCall = call; }
        private void failPlayer(int playerId) { failPlayerCalls(playerId, 1); }
        private void failPlayerCalls(int playerId, int calls) {
            failingPlayerId = playerId;
            failingPlayerCalls = calls;
        }
        private void setEventFourLive(byte[] body) { eventFour = body; }
        private void setPlayerPage(int playerId, byte[] body) { playerPages.put(playerId, body.clone()); }
        private void deferOnPlayerCall(int call, Instant due) { deferredPageCall = call; deferUntil = due; }

        @Override
        public OwnedSyncHttpClient.Result get(Lease lease, Endpoint endpoint, int failureAttempt, Instant floor) {
            if (clock == null) throw new IllegalStateException("clock not bound");
            calls.add(endpoint);
            if (!firstRequestRun) {
                firstRequestRun = true;
                if (firstRequest != null) firstRequest.run();
            }
            if (endpoint.kind() == EndpointKind.ELEMENT_SUMMARY) {
                pageCalls++;
                if (deferredPageCall == pageCalls) {
                    return new DeferredResult(deferUntil, DeferredReason.COOLDOWN);
                }
                if (failFirstPage && failedPageCall == pageCalls
                        || failingPlayerId != null && endpoint.playerId().equals(failingPlayerId)
                                && failingPlayerCalls > 0) {
                    if (failingPlayerId != null && endpoint.playerId().equals(failingPlayerId)) {
                        failingPlayerCalls--;
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
            Instant finish = start.plusMillis(1);
            clock.set(finish.plusMillis(5));
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
        private int returnedBodyBytes() { return successfulBodyBytes.stream().mapToInt(Integer::intValue).sum(); }
        private void clearCalls() {
            calls.clear();
            successfulReads.clear();
            successfulBodyBytes.clear();
            pageCalls = 0;
            firstRequestRun = false;
            firstRequest = null;
        }

        private void clearPageOutcomes() {
            failFirstPage = false;
            failingPlayerId = null;
            failingPlayerCalls = 0;
            deferredPageCall = 0;
            deferUntil = null;
        }

        private List<RecordedRead> successfulReads() { return List.copyOf(successfulReads); }
    }

    private static final class SqlRecordingDataSource implements DataSource {
        private final DataSource delegate;
        private final List<String> statements = new ArrayList<>();
        private int queryExecutions;
        private int updateExecutions;
        private long updateRowsAffected;
        private long returnedRows;
        private long approximateLogicalReturnedBytes;

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
                        if ((name.equals("executeQuery") || name.equals("execute")) && isReadSql(sql)) {
                            queryExecutions++;
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
        }
    }

    private static final class MutableClock extends Clock {
        private Instant current;
        private final ZoneId zone;

        private MutableClock(Instant current) { this(current, ZoneId.of("UTC")); }
        private MutableClock(Instant current, ZoneId zone) { this.current = current; this.zone = zone; }
        private synchronized void set(Instant instant) { current = instant; }
        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId selected) { return new MutableClock(current, selected); }
        @Override public synchronized Instant instant() { return current; }
    }

    private record CheckedFixtureSetup(int fixtureId, int cataloguePlayer, byte[] fixtureSource,
                                       byte[] bootstrapSource, byte[] liveSource) { }
    private record MultiFixtureSetup(Set<Integer> fixtureIds,
                                     Map<Integer, Set<Integer>> participantsByFixture,
                                     Set<Integer> participantIds, Map<Integer, byte[]> historyPages,
                                     byte[] fixtureSource, byte[] bootstrapSource, byte[] liveSource) { }
    private record FixtureKey(int id, int gameweek, int homeTeam, int awayTeam, Instant kickoff,
                              boolean started, boolean finished) { }
    private record ActiveFixture(byte[] body, int id) { }
    private record RecordedRead(Endpoint endpoint, SourceRead read) { }
}
