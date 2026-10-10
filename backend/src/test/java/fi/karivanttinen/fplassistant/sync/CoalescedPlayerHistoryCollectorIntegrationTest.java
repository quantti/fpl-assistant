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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.sql.DataSource;

import fi.karivanttinen.fplassistant.defcon.DefensiveStatsSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.FixtureRow;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.PlayerRow;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.Season;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.SourceDocument;
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
        dataSource = new DriverManagerDataSource(required("DATABASE_URL"), required("DB_USER"),
                required("DB_PASSWORD"));
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        TapasSeed.ensureOwnedSyncCaptureSeason(jdbc);
        originalCurrentSeasons = jdbc.query("SELECT id FROM season WHERE is_current=true ORDER BY id",
                (rs, row) -> rs.getInt(1));
        TapasSeed.setCurrentSeason(jdbc, SEASON_ID);
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
    }

    @AfterAll
    static void restoreCurrentSeason() {
        if (jdbc == null) return;
        new PlannerRuntimeRepository(jdbc, tx, Clock.systemUTC(), TABLES)
                .deactivate(new RuntimeFence(season.code(), OwnedSyncMode.ACTIVE,
                        jdbc.queryForObject("SELECT runtime_epoch FROM " + TABLES.plannerRuntime()
                                + " WHERE instance_id=?", Long.class, "mode:" + season.code()), START));
        jdbc.update("UPDATE season SET is_current=false");
        for (int id : originalCurrentSeasons) jdbc.update("UPDATE season SET is_current=true WHERE id=?", id);
        jdbc.update("DELETE FROM player_fixture_stats WHERE season_id=? AND fixture_id=29"
                + " AND player_id IN (1, 2, 28, ?)", SEASON_ID, FINAL_PENDING_PLAYER);
        jdbc.update("DELETE FROM player_fixture_stats WHERE season_id=? AND player_id IN (?, ?)",
                SEASON_ID, KNOWN_PLAYER, 658);
    }

    @BeforeEach
    void setUp() {
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
        clearOwnedTables();
        jdbc.update("DELETE FROM player_fixture_stats WHERE season_id=? AND fixture_id=29"
                + " AND player_id IN (1, 2, 28, ?)", SEASON_ID, FINAL_PENDING_PLAYER);
        jdbc.update("DELETE FROM player_fixture_stats WHERE season_id=? AND player_id IN (?, ?, ?, ?, ?)",
                SEASON_ID, KNOWN_PLAYER, FINAL_PENDING_PLAYER, LOCAL_HTTP_PLAYER, WATERMARK_PLAYER, 658);
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

        AtomicReference<CycleResult> nested = new AtomicReference<>();
        AtomicReference<CoalescedPlayerHistoryCollector> collectorRef = new AtomicReference<>();
        fetch.onFirstRequest(() -> nested.set(collectorRef.get().runCycle()));
        CoalescedPlayerHistoryCollector collector = collector(true);
        collectorRef.set(collector);

        CycleResult first = collector.runCycle();

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
        assertThat(fetch.playerIds()).hasSize(660);
        assertThat(new HashSet<>(fetch.playerIds())).hasSize(660);

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
        int awayPlayer = 28;
        byte[] sourceFixtures = setup.fixtureSource();
        byte[] checkedBootstrap = setup.bootstrapSource();
        SourceRead initialRead = new SourceRead(START, START.plusMillis(3));
        var initialSnapshot = CatalogueScheduleParser.parse(season,
                document(checkedBootstrap, new SourceRead(START, START.plusMillis(1))),
                document(sourceFixtures, new SourceRead(START.plusMillis(2), START.plusMillis(3))));
        var source = GameweekFinality.SourceSnapshot.from(initialSnapshot, 3);
        var fixtureRow = initialSnapshot.fixtures().stream().filter(row -> row.id() == fixtureId)
                .findFirst().orElseThrow();
        var liveRead = new CatalogueScheduleParser.ReadInterval(START.plusMillis(4), START.plusMillis(5));
        var evidence = FixtureVerification.fixtureEvidenceByFixture(List.of(fixtureRow),
                new SourceDocument(setup.liveSource(), liveRead));
        var population = new GameweekFinality.RequiredPopulation(OwnedSyncStore.sha256(checkedBootstrap),
                Map.of(), Map.of(fixtureId, Set.of(homePlayer, awayPlayer)), Map.of(), evidence);
        store.enqueueIncrementalCoreImport(season, START);
        Lease lease = store.acquireIncrementalCoreImport(season.code(), "finality-test", Duration.ofMinutes(30))
                .orElseThrow();
        store.observeIncrementalSnapshot(lease, initialSnapshot);
        clock.set(liveRead.completedAt());
        GameweekFinalityRepository finality = new GameweekFinalityRepository(jdbc, store, TABLES, clock);

        var started = finality.beginOrResume(lease, season, source, population, initialRead);
        assertThat(started.state()).isEqualTo(fi.karivanttinen.fplassistant.sync.GameweekFinality.State.FINAL_PENDING);
        assertThat(started.checkpoint().requiredPlayerIds()).containsExactly(homePlayer, awayPlayer);
        assertThat(started.checkpoint().outstandingPlayerIds()).containsExactly(homePlayer, awayPlayer);
        clock.set(started.checkpoint().sourceFloor().plusMillis(1));
        SourceRead pageRead = new SourceRead(clock.instant(), clock.instant().plusMillis(1));
        clock.set(pageRead.finishedAt());
        List<CanonicalFixture> canonical = initialSnapshot.fixtures().stream()
                .map(HistoryParser::canonicalFixture).toList();
        byte[] homeHistory = onlyHistoryForFixture(resource("element-summary-1.json"), fixtureId, homePlayer);
        publisher.publish(lease, season, homePlayer, document(homeHistory, pageRead), canonical);
        byte[] awayHistory = historyForAwayPlayer28(fixtureId);
        publisher.publish(lease, season, awayPlayer, document(awayHistory, pageRead), canonical);
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
        assertThat(resumed.checkpoint().completedPlayerIds()).containsExactly(homePlayer, awayPlayer);
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

        assertThat(receipt.sourceSignature()).isEqualTo(source.signature());
        assertThat(receipt.rowRevisionFingerprint()).matches("[0-9a-f]{64}");
        assertThat(receipt.optionalCoverage()).hasSize(HistoryParser.Component.values().length);
        assertThat(receipt.sourceReadInterval())
                .isEqualTo(new SourceRead(initialRead.startedAt(), endRead.finishedAt()));
        assertThat(finality.load(restartedLease, season, 3).orElseThrow().finalizedSourceReadInterval())
                .isEqualTo(receipt.sourceReadInterval());
        assertThat(finality.load(restartedLease, season, 3).orElseThrow().initialSourceReadInterval())
                .isEqualTo(initialRead);
        assertThat(store.incrementalSourceInterval(restartedLease)).isEqualTo(receipt.sourceReadInterval());
        var afterRestart = finality.beginOrResume(restartedLease, season, endSource, population, endRead);
        assertThat(afterRestart.state()).isEqualTo(fi.karivanttinen.fplassistant.sync.GameweekFinality.State.FINALIZED);
        assertThat(afterRestart.invalidationRevision()).isEqualTo(receipt.invalidationRevision());

        assertThat(jdbc.update("UPDATE player_fixture_stats SET minutes=minutes+1"
                + " WHERE season_id=? AND fixture_id=? AND player_id=?", SEASON_ID, fixtureId,
                homePlayer)).isEqualTo(1);
        var tamperedRestart = finality.beginOrResume(restartedLease, season, endSource, population, endRead);
        assertThat(tamperedRestart.state()).isEqualTo(fi.karivanttinen.fplassistant.sync.GameweekFinality.State.FINAL_PENDING);
        assertThat(tamperedRestart.invalidationRevision()).isEqualTo(receipt.invalidationRevision() + 1);
        assertThat(tamperedRestart.checkpoint().outstandingPlayerIds()).containsExactly(homePlayer, awayPlayer);
        assertThat(tamperedRestart.checkpoint().sourceFloor()).isAfter(pageRead.finishedAt());
        assertThat(tamperedRestart.pageReadInterval()).isNull();
        assertThat(tamperedRestart.finalizedAt()).isNull();
    }

    @Test
    void checkedStartupFinalSweepFinalizesRealRowsAndNextCycleDoesNotRewriteThem() throws Exception {
        CheckedFixtureSetup setup = checkedSingleFixtureSetup();

        CycleResult first = collector(true).runCycle();

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
        assertThat(jdbc.queryForObject("SELECT count(*) FROM player_fixture_stats"
                + " WHERE season_id=? AND fixture_id=? AND player_id=?", Integer.class,
                SEASON_ID, setup.fixtureId(), 28)).isOne();
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

        fetch.clearCalls();
        CycleResult next = collector(true).runCycle();

        assertThat(next.state()).isEqualTo(State.COMPLETED);
        assertThat(next.selectedPlayerIds()).isEmpty();
        assertThat(fetch.count(EndpointKind.ELEMENT_SUMMARY)).isZero();
        assertThat(fetch.count(EndpointKind.EVENT_LIVE)).isZero();
        assertThat(jdbc.queryForObject("SELECT updated_at FROM player_fixture_stats"
                + " WHERE season_id=? AND fixture_id=? AND player_id=?", java.sql.Timestamp.class,
                SEASON_ID, setup.fixtureId(), 28)).isEqualTo(rowUpdatedAt);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.playerFixtureEvidence()
                + " WHERE season_id=? AND fixture_id=? AND player_id=?", Integer.class,
                SEASON_ID, setup.fixtureId(), 28)).isEqualTo(evidenceRows);
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
        private void clearCalls() {
            calls.clear();
            successfulReads.clear();
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
                        if (preparedSql == null && args != null && args.length > 0 && args[0] instanceof String sql
                                && (method.getName().equals("execute") || method.getName().equals("executeQuery")
                                    || method.getName().equals("executeUpdate") || method.getName().equals("addBatch"))) {
                            statements.add(sql);
                        }
                        return invoke(target, method, args);
                    });
        }

        private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        }

        private List<String> statements() { return List.copyOf(statements); }
        private void clear() { statements.clear(); }
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
    private record FixtureKey(int id, int gameweek, int homeTeam, int awayTeam, Instant kickoff,
                              boolean started, boolean finished) { }
    private record ActiveFixture(byte[] body, int id) { }
    private record RecordedRead(Endpoint endpoint, SourceRead read) { }
}
