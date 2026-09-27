package fi.karivanttinen.fplassistant.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.Season;
import fi.karivanttinen.fplassistant.sync.HistoryFixtureDispatcher.DispatchResult;
import fi.karivanttinen.fplassistant.sync.HistoryFixtureDispatcher.FailureType;
import fi.karivanttinen.fplassistant.sync.HistoryFixtureDispatcher.Outcome;
import fi.karivanttinen.fplassistant.sync.OwnedSyncHttpClient.DeferredResult;
import fi.karivanttinen.fplassistant.sync.OwnedSyncHttpClient.Response;
import fi.karivanttinen.fplassistant.sync.OwnedSyncRequestBudget.DeferredReason;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.Endpoint;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.Lease;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.SourceRead;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.SyncKind;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.Validation;
import fi.karivanttinen.fplassistant.testsupport.TapasSeed;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class HistoryFixtureDispatcherIntegrationTest {

    private static final OwnedSyncTables TABLES = OwnedSyncTables.tests();
    private static final Season SEASON = new Season(27, "2026-27", LocalDate.of(2026, 8, 21), null);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static byte[] bootstrap;
    private static byte[] fixtures;
    private static byte[] historyOne;
    private static byte[] liveThree;

    private MutableClock clock;
    private OwnedSyncStore store;
    private FakeFetch fetch;
    private HistoryFixtureDispatcher dispatcher;

    @BeforeAll
    static void initialize() throws Exception {
        var dataSource = new DriverManagerDataSource(required("DATABASE_URL"), required("DB_USER"),
                required("DB_PASSWORD"));
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        TapasSeed.ensure(jdbc);
        new OwnedSyncSchema(jdbc, TABLES).initialize();
        bootstrap = resource("bootstrap-static.json");
        fixtures = resource("fixtures.json");
        historyOne = resource("element-summary-1.json");
        liveThree = resource("event-3-live.json");
    }

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE " + TABLES.playerFixtureEvidence() + ", " + TABLES.fixtureSync()
                + ", " + TABLES.historyFingerprint() + ", " + TABLES.payload() + ", "
                + TABLES.run() + ", " + TABLES.receipt() + " CASCADE");
        clock = new MutableClock(Instant.parse("2026-09-13T18:00:00Z"));
        store = new OwnedSyncStore(jdbc, tx, clock, TABLES);
        fetch = new FakeFetch();
        var repository = new FixtureSyncRepository(jdbc, TABLES);
        var publisher = new FixtureSyncPublisher(jdbc, clock, store, repository, TABLES);
        dispatcher = new HistoryFixtureDispatcher(store, publisher, fetch, clock);
    }

    @Test
    void historyHttpCallerFenceDefersWhenDemandAdvancesAfterSourceSelection() throws Exception {
        activateStagingStore();
        seedCanonicalPair();
        var historyRun = dispatcher.enqueuePlayerHistory(SEASON, 1, clock.instant());
        Lease lease = acquire(historyRun.generation(), Duration.ofMinutes(2));
        Instant selectedFloor = store.requestedSourceAfter(lease);
        Instant advancedFloor = clock.instant().plus(Duration.ofHours(1));
        AtomicReference<HttpFetchRequest> observed = new AtomicReference<>();

        try (Loopback loopback = loopback()) {
            dispatcher = dispatcher(fencedHttpFetch(loopback, request -> {
                observed.set(request);
                store.enqueue(SyncKind.PLAYER_HISTORY, SEASON.code(), "player-history:1",
                        "player-history:1", advancedFloor);
            }));

            DispatchResult result = dispatcher.process(lease, SEASON);

            assertThat(result.failure()).isEqualTo(FailureType.HTTP_DEFERRED);
            assertThat(observed.get().endpoint()).isEqualTo(Endpoint.elementSummary(1));
            assertThat(observed.get().sourceFloor()).isEqualTo(selectedFloor);
            assertThat(observed.get().lease().expiresAt()).isAfter(clock.instant().plusSeconds(180));
            assertNoHttpOrFetchFailure(loopback, lease.generation(), 2);
            assertThat(jdbc.queryForObject("SELECT due_at FROM " + TABLES.run()
                    + " WHERE generation=?", java.sql.Timestamp.class, lease.generation()).toInstant())
                    .isEqualTo(advancedFloor);
        }
    }

    @Test
    void fixturePairHttpCallerFenceUsesItsSelectedFloorWhenDemandAdvances() throws Exception {
        activateStagingStore();
        var run = dispatcher.enqueueFixtureVerification(SEASON, 21, clock.instant());
        Lease lease = acquire(run.generation(), Duration.ofMinutes(2));
        Instant selectedFloor = store.requestedSourceAfter(lease);
        Instant advancedFloor = clock.instant().plus(Duration.ofHours(1));
        AtomicReference<HttpFetchRequest> observed = new AtomicReference<>();

        try (Loopback loopback = loopback()) {
            dispatcher = dispatcher(fencedHttpFetch(loopback, request -> {
                observed.set(request);
                store.enqueue(SyncKind.FIXTURE_VERIFICATION, SEASON.code(), "fixture:21",
                        "fixture:21", advancedFloor);
            }));

            DispatchResult result = dispatcher.process(lease, SEASON);

            assertThat(result.failure()).isEqualTo(FailureType.HTTP_DEFERRED);
            assertThat(observed.get().endpoint()).isEqualTo(Endpoint.bootstrap());
            assertThat(observed.get().sourceFloor()).isEqualTo(selectedFloor);
            assertThat(observed.get().lease().expiresAt()).isAfter(clock.instant().plusSeconds(180));
            assertNoHttpOrFetchFailure(loopback, lease.generation(), 0);
        }
    }

    @Test
    void historyHttpCallerFenceRejectsAReplacedRuntimeEpochBeforeAdmission() throws Exception {
        activateStagingStore();
        seedCanonicalPair();
        Lease lease = enqueueAndAcquireHistory();
        int stages = payloadCount();

        try (Loopback loopback = loopback()) {
            dispatcher = dispatcher(fencedHttpFetch(loopback, request ->
                    new PlannerRuntimeRepository(jdbc, tx, clock, TABLES)
                            .activate(SEASON.code(), OwnedSyncMode.STAGING)));

            assertThatThrownBy(() -> dispatcher.process(lease, SEASON))
                    .isInstanceOf(OwnedSyncStore.LeaseLostException.class);

            assertNoHttpOrFetchFailure(loopback, lease.generation(), stages);
        }
    }

    @Test
    void historyHttpCallerFenceRejectsLeaseReassignmentWithoutRenewingExpiredLease() throws Exception {
        activateStagingStore();
        seedCanonicalPair();
        Lease lease = enqueueAndAcquireHistory();
        int stages = payloadCount();
        AtomicReference<Lease> replacement = new AtomicReference<>();

        try (Loopback loopback = loopback()) {
            dispatcher = dispatcher(fencedHttpFetch(loopback, request -> {
                clock.advance(Duration.ofMinutes(6));
                replacement.set(store.acquire("replacement", Duration.ofMinutes(5)).orElseThrow());
            }));

            assertThatThrownBy(() -> dispatcher.process(lease, SEASON))
                    .isInstanceOf(OwnedSyncStore.LeaseLostException.class);

            assertThat(replacement.get().generation()).isEqualTo(lease.generation());
            assertThat(jdbc.queryForObject("SELECT lease_owner FROM " + TABLES.run()
                    + " WHERE generation=?", String.class, lease.generation())).isEqualTo("replacement");
            assertNoHttpOrFetchFailure(loopback, lease.generation(), stages);
        }
    }

    @Test
    void historyPostAdmissionFenceInvalidationSettlesWithoutSendingOrStaging() throws Exception {
        activateStore(OwnedSyncMode.ACTIVE);
        seedCanonicalPair();
        Lease lease = enqueueAndAcquireHistory();
        int stages = payloadCount();

        try (Loopback loopback = loopback()) {
            installPostAdmissionEpochInvalidation();
            try {
                dispatcher = new HistoryFixtureDispatcher(store, publisher(), loopback.http, clock);

                assertThatThrownBy(() -> dispatcher.process(lease, SEASON))
                        .isInstanceOf(OwnedSyncStore.LeaseLostException.class);

                assertThat(loopback.requests).hasValue(0);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.requestAttempt(), Integer.class))
                        .isOne();
                assertThat(jdbc.queryForObject("SELECT completed_at IS NOT NULL FROM "
                        + TABLES.requestAttempt(), Boolean.class)).isTrue();
                assertThat(jdbc.queryForObject("SELECT consecutive_fetch_failures FROM " + TABLES.run()
                        + " WHERE generation=?", Integer.class, lease.generation())).isZero();
                assertThat(payloadCount()).isEqualTo(stages);
            } finally {
                removePostAdmissionEpochInvalidation();
            }
        }
    }

    @Test
    void playerJobPublishesCanonicalRawHistoryReceiptWithoutWritingPfs() {
        seedCanonicalPair();
        int before = jdbc.queryForObject("SELECT count(*) FROM player_fixture_stats", Integer.class);
        var run = dispatcher.enqueuePlayerHistory(SEASON, 1, clock.instant());
        fetch.results.add(ok(historyOne));

        DispatchResult result = dispatcher.process(acquire(run.generation()), SEASON);

        assertThat(result.outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(fetch.endpoints).containsExactly(Endpoint.elementSummary(1));
        assertThat(store.sourceReceipt(SEASON.code(), "player-history:1")).isPresent();
        assertThat(jdbc.queryForObject("SELECT validation_state FROM " + TABLES.payload()
                + " WHERE endpoint_kind='ELEMENT_SUMMARY'", String.class)).isEqualTo("VALID");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM player_fixture_stats", Integer.class))
                .isEqualTo(before);
    }

    @Test
    void fixtureRecheckFetchesUseTheLatestHistoryFinishPlusOneMillisecond() {
        HistoryFixtureDispatcher staged = new HistoryFixtureDispatcher(store, null, fetch, clock, false);
        var fixtureRun = staged.enqueueFixtureVerification(SEASON, 21, clock.instant());
        Lease fixtureLease = acquire(fixtureRun.generation());
        SourceRead pairRead = readNow();
        stagedPair(fixtureLease, pairRead);
        clock.advance(Duration.ofMillis(1));
        SourceRead liveRead = readNow();
        store.stage(fixtureLease, Endpoint.eventLive(3), liveRead, Validation.valid(), liveThree);

        var interval = new CatalogueScheduleParser.ReadInterval(pairRead.startedAt(), pairRead.finishedAt());
        var snapshot = CatalogueScheduleParser.parse(SEASON,
                new CatalogueScheduleParser.SourceDocument(bootstrap, interval),
                new CatalogueScheduleParser.SourceDocument(fixtures, interval));
        var fixture = snapshot.fixtures().stream().filter(row -> row.id() == 21).findFirst().orElseThrow();
        Set<Integer> players = new LinkedHashSet<>();
        snapshot.players().stream().filter(player -> player.teamId() == fixture.homeTeamId()
                        || player.teamId() == fixture.awayTeamId())
                .map(CatalogueScheduleParser.PlayerRow::id).forEach(players::add);
        players.addAll(FixtureVerification.participantIds(fixture,
                new CatalogueScheduleParser.SourceDocument(liveThree,
                        new CatalogueScheduleParser.ReadInterval(liveRead.startedAt(), liveRead.finishedAt()))));
        SourceRead historyRead = readNow();
        var evidenceRun = store.enqueue(SyncKind.PLAYER_HISTORY, SEASON.code(), "player-history:1",
                "player-history:1", historyRead.startedAt());
        Lease evidenceLease = acquire(evidenceRun.generation());
        for (int playerId : players) {
            store.stage(evidenceLease, Endpoint.elementSummary(playerId), historyRead,
                    Validation.valid(), historyOne);
        }
        store.completeStaged(evidenceLease, historyRead, List.of(historyRead));
        Instant recheckFloor = historyRead.finishedAt().plusMillis(1);
        clock.advance(Duration.ofMillis(2));
        SourceRead recheckRead = readNow();
        fetch.results.add(new Response(200, bootstrap, null, recheckRead));
        fetch.results.add(new Response(200, fixtures, null, recheckRead));

        DispatchResult result = staged.process(fixtureLease, SEASON);

        assertThat(result.outcome()).isEqualTo(Outcome.STAGED);
        assertThat(fetch.endpoints).containsExactly(Endpoint.bootstrap(), Endpoint.fixtures());
        assertThat(fetch.requests).extracting(HttpFetchRequest::sourceFloor)
                .containsExactly(recheckFloor, recheckFloor);
    }

    @Test
    void correctedHistoryGameweekWaitsForFreshParentThenFailsBoundedlyWhenParentIsUnchanged()
            throws Exception {
        assertCorrectedHistoryRefreshesParent("round", 5);
    }

    @Test
    void correctedHistoryKickoffWaitsForFreshParentThenFailsBoundedlyWhenParentIsUnchanged()
            throws Exception {
        assertCorrectedHistoryRefreshesParent("kickoff_time", "2026-09-12T20:00:00Z");
    }

    @Test
    void fullHistoryQueuesOnlyNewOrChangedFixtureFingerprintsIncludingOptionalXg() throws Exception {
        seedCanonicalPair();
        var firstRun = dispatcher.enqueuePlayerHistory(SEASON, 1, clock.instant());
        fetch.results.add(ok(historyOne));
        dispatcher.process(acquire(firstRun.generation()), SEASON);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                + " WHERE kind='FIXTURE_VERIFICATION'", Integer.class)).isPositive();

        jdbc.update("DELETE FROM " + TABLES.run() + " WHERE kind='FIXTURE_VERIFICATION'");
        clock.advance(Duration.ofSeconds(1));
        var unchangedRun = dispatcher.enqueuePlayerHistory(SEASON, 1, clock.instant());
        fetch.results.add(ok(historyOne));
        dispatcher.process(acquire(unchangedRun.generation()), SEASON);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                + " WHERE kind='FIXTURE_VERIFICATION'", Integer.class)).isZero();

        ObjectNode corrected = (ObjectNode) JSON.readTree(historyOne);
        ObjectNode changed = (ObjectNode) corrected.withArray("history").get(0);
        int fixtureId = changed.get("fixture").intValue();
        changed.put("expected_goals", "0.99");
        clock.advance(Duration.ofSeconds(1));
        var correctedRun = dispatcher.enqueuePlayerHistory(SEASON, 1, clock.instant());
        fetch.results.add(ok(JSON.writeValueAsBytes(corrected)));
        dispatcher.process(acquire(correctedRun.generation()), SEASON);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                + " WHERE kind='FIXTURE_VERIFICATION' AND publication_key=?", Integer.class,
                "fixture:" + fixtureId)).isOne();
    }

    @Test
    void malformedPlayerHistoryFailsTerminallyAndRetainsInvalidStage() {
        seedCanonicalPair();
        var run = dispatcher.enqueuePlayerHistory(SEASON, 1, clock.instant());
        fetch.results.add(ok("{\"history\":{}}".getBytes()));

        DispatchResult result = dispatcher.process(acquire(run.generation()), SEASON);

        assertThat(result.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(result.failure()).isEqualTo(FailureType.INVALID_SOURCE);
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.run()
                + " WHERE generation=?", String.class, run.generation())).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT validation_state FROM " + TABLES.payload()
                + " WHERE endpoint_kind='ELEMENT_SUMMARY'", String.class)).isEqualTo("INVALID");
        assertThat(store.sourceReceipt(SEASON.code(), "player-history:1")).isEmpty();
    }

    @Test
    void fixtureJobFansOutExplicitPlayerJobsIncludingZeroMinuteDepartedParticipant() throws Exception {
        byte[] liveWithDeparted = departedZeroMinuteLive();
        int before = jdbc.queryForObject("SELECT count(*) FROM player_fixture_stats", Integer.class);
        Instant requestedFloor = clock.instant();
        var run = dispatcher.enqueueFixtureVerification(SEASON, 21, requestedFloor);
        fetch.results.add(ok(bootstrap));
        fetch.results.add(ok(fixtures));
        clock.advance(Duration.ofMillis(1));
        fetch.results.add(ok(liveWithDeparted));

        DispatchResult result = dispatcher.process(acquire(run.generation()), SEASON);

        assertThat(result.outcome()).isEqualTo(Outcome.WAITING_FOR_HISTORIES);
        assertThat(fetch.endpoints).containsExactly(Endpoint.bootstrap(), Endpoint.fixtures(),
                Endpoint.eventLive(3));
        assertThat(fetch.requests).extracting(HttpFetchRequest::sourceFloor)
                .containsExactly(requestedFloor, requestedFloor, jdbc.queryForObject("SELECT read_finished_at FROM "
                        + TABLES.payload() + " WHERE run_generation=? AND endpoint_kind='FIXTURES'",
                        java.sql.Timestamp.class, run.generation()).toInstant().plusMillis(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                + " WHERE kind='PLAYER_HISTORY'", Integer.class)).isEqualTo(73);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                + " WHERE kind='CATALOGUE_SCHEDULE' AND state='PENDING'", Integer.class)).isZero();
        Lease next = store.acquire("progress-worker", Duration.ofMinutes(5)).orElseThrow();
        assertThat(next.kind()).isEqualTo(SyncKind.PLAYER_HISTORY);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                + " WHERE publication_key='player-history:999'", Integer.class)).isOne();
        int waitingPriority = jdbc.queryForObject("SELECT priority FROM " + TABLES.run()
                + " WHERE generation=?", Integer.class, run.generation());
        int historyPriority = jdbc.queryForObject("SELECT min(priority) FROM " + TABLES.run()
                + " WHERE kind='PLAYER_HISTORY'", Integer.class);
        assertThat(waitingPriority).isGreaterThan(historyPriority);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM player_fixture_stats", Integer.class))
                .isEqualTo(before);
    }

    @Test
    void budgetDeferralKeepsCheckpointAndRetriesAtBudgetDueWithoutFailedVerificationLadder() {
        jdbc.update("INSERT INTO " + TABLES.fixtureSync()
                        + "(season_id, season_code, fixture_id, state, updated_at)"
                        + " VALUES (?, ?, ?, 'SCHEDULED', ?)",
                SEASON.id(), SEASON.code(), 21, java.sql.Timestamp.from(clock.instant()));
        var run = dispatcher.enqueueFixtureVerification(SEASON, 21, clock.instant());
        fetch.results.add(ok(bootstrap));
        fetch.results.add(new DeferredResult(clock.instant().plusSeconds(1),
                DeferredReason.MINIMUM_SPACING));

        DispatchResult deferred = dispatcher.process(acquire(run.generation()), SEASON);

        assertThat(deferred.failure()).isEqualTo(FailureType.HTTP_DEFERRED);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM " + TABLES.fixtureSync()
                + " WHERE fixture_id=21", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT due_at FROM " + TABLES.run()
                + " WHERE generation=?", java.sql.Timestamp.class, run.generation()).toInstant())
                .isEqualTo(clock.instant().plusSeconds(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.payload()
                + " WHERE endpoint_kind='BOOTSTRAP_STATIC'", Integer.class)).isOne();

        clock.advance(Duration.ofSeconds(1));
        fetch.results.add(ok(fixtures));
        fetch.results.add(ok(liveThree));
        DispatchResult resumed = dispatcher.process(acquire(run.generation()), SEASON);

        assertThat(resumed.outcome()).isEqualTo(Outcome.WAITING_FOR_HISTORIES);
        assertThat(fetch.endpoints).containsExactly(Endpoint.bootstrap(), Endpoint.fixtures(),
                Endpoint.fixtures(), Endpoint.eventLive(3));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                + " WHERE kind='CATALOGUE_SCHEDULE' AND state='PENDING'", Integer.class)).isZero();
    }

    @Test
    void repeatedMissingCanonicalNotificationPreservesParentCheckpointAndQueueProgress() {
        dispatcher.enqueuePlayerHistory(SEASON, 1, clock.instant());
        DispatchResult firstMissing = dispatcher.process(store.acquire("history-1", Duration.ofMinutes(5))
                .orElseThrow(), SEASON);
        assertThat(firstMissing.failure()).isEqualTo(FailureType.MISSING_CANONICAL_SCHEDULE);

        CatalogueScheduleDispatcher parentDispatcher = new CatalogueScheduleDispatcher(
                store, null, (endpoint, attempt) -> fetch.get(endpoint, attempt), clock, false);
        Instant parentFloor = null;
        for (int parent = 0; parent < 2; parent++) {
            Lease parentLease = store.acquire("parent-" + parent, Duration.ofMinutes(5)).orElseThrow();
            assertThat(parentLease.kind()).isEqualTo(SyncKind.CATALOGUE_SCHEDULE);
            if (parentFloor == null) {
                parentFloor = jdbc.queryForObject("SELECT requested_source_after FROM " + TABLES.run()
                        + " WHERE generation=?", java.sql.Timestamp.class,
                        parentLease.generation()).toInstant();
            }
            fetch.results.add(ok(bootstrap));
            fetch.results.add(new DeferredResult(clock.instant().plusSeconds(1),
                    DeferredReason.MINIMUM_SPACING));
            assertThat(parentDispatcher.process(parentLease, SEASON))
                    .isEqualTo(CatalogueScheduleDispatcher.Outcome.RETRY_SCHEDULED);
        }

        var secondHistory = dispatcher.enqueuePlayerHistory(SEASON, 2, clock.instant());
        Lease historyLease = store.acquire("history-2", Duration.ofMinutes(5)).orElseThrow();
        assertThat(historyLease.generation()).isEqualTo(secondHistory.generation());
        assertThat(dispatcher.process(historyLease, SEASON).failure())
                .isEqualTo(FailureType.MISSING_CANONICAL_SCHEDULE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.payload()
                + " WHERE endpoint_kind='BOOTSTRAP_STATIC' AND validation_state='UNCHECKED'",
                Integer.class)).isEqualTo(2);
        assertThat(jdbc.query("SELECT requested_source_after FROM " + TABLES.run()
                        + " WHERE kind='CATALOGUE_SCHEDULE' ORDER BY generation",
                (rs, row) -> rs.getTimestamp(1).toInstant())).containsOnly(parentFloor);

        clock.advance(Duration.ofSeconds(1));
        fetch.results.add(ok(fixtures));
        for (int parent = 0; parent < 2; parent++) {
            Lease parentLease = store.acquire("resumed-parent-" + parent, Duration.ofMinutes(5))
                    .orElseThrow();
            assertThat(parentLease.kind()).isEqualTo(SyncKind.CATALOGUE_SCHEDULE);
            assertThat(parentDispatcher.process(parentLease, SEASON))
                    .isEqualTo(CatalogueScheduleDispatcher.Outcome.STAGED);
        }

        assertThat(fetch.endpoints).containsExactly(
                Endpoint.bootstrap(), Endpoint.fixtures(), Endpoint.bootstrap(), Endpoint.fixtures(),
                Endpoint.fixtures());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                + " WHERE kind='CATALOGUE_SCHEDULE' AND state IN ('PENDING', 'LEASED')",
                Integer.class)).isZero();

        clock.advance(Duration.ofSeconds(31));
        Lease resumedHistory = store.acquire("resumed-history", Duration.ofMinutes(5)).orElseThrow();
        assertThat(resumedHistory.publicationKey()).isEqualTo("player-history:1");
        fetch.results.add(ok(historyOne));
        assertThat(dispatcher.process(resumedHistory, SEASON).outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(store.sourceReceipt(SEASON.code(), "player-history:1")).isPresent();
    }

    @Test
    void incompleteOwnCheckpointDefersBeforeItsBodyIsHydrated() {
        TrackingJdbcTemplate tracking = new TrackingJdbcTemplate(jdbc.getDataSource());
        OwnedSyncStore trackedStore = new OwnedSyncStore(tracking, tx, clock, TABLES);
        HistoryFixtureDispatcher staged = new HistoryFixtureDispatcher(trackedStore, null, fetch, clock, false);
        var run = staged.enqueueFixtureVerification(SEASON, 21, clock.instant());
        Lease first = trackedStore.acquire("checkpoint-worker", Duration.ofMinutes(5)).orElseThrow();
        assertThat(first.generation()).isEqualTo(run.generation());
        trackedStore.stage(first, Endpoint.bootstrap(), readNow(), Validation.valid(), bootstrap);

        tracking.resetBodyQueries();
        fetch.results.add(new DeferredResult(clock.instant().plusSeconds(1),
                DeferredReason.MINIMUM_SPACING));
        DispatchResult deferred = staged.process(first, SEASON);

        assertThat(deferred.failure()).isEqualTo(FailureType.HTTP_DEFERRED);
        assertThat(fetch.endpoints).containsExactly(Endpoint.fixtures());
        assertThat(tracking.bodyQueries()).isZero();

        clock.advance(Duration.ofSeconds(1));
        tracking.resetBodyQueries();
        fetch.results.add(ok(fixtures));
        fetch.results.add(ok(liveThree));
        DispatchResult resumed = staged.process(trackedStore.acquire("checkpoint-resume", Duration.ofMinutes(5))
                .orElseThrow(), SEASON);

        assertThat(resumed.outcome()).isEqualTo(Outcome.WAITING_FOR_HISTORIES);
        assertThat(fetch.endpoints).containsExactly(Endpoint.fixtures(), Endpoint.fixtures(),
                Endpoint.eventLive(3));
        assertThat(tracking.bodyQueries()).isOne();
    }

    @Test
    void missingHistoryDependencyPreventsAllHistoryBodyHydration() {
        TrackingJdbcTemplate tracking = new TrackingJdbcTemplate(jdbc.getDataSource());
        OwnedSyncStore trackedStore = new OwnedSyncStore(tracking, tx, clock, TABLES);
        HistoryFixtureDispatcher staged = new HistoryFixtureDispatcher(trackedStore, null, fetch, clock, false);
        var fixtureRun = staged.enqueueFixtureVerification(SEASON, 21, clock.instant());
        Lease fixtureLease = trackedStore.acquire("fixture-worker", Duration.ofMinutes(5)).orElseThrow();
        assertThat(fixtureLease.generation()).isEqualTo(fixtureRun.generation());
        SourceRead pairRead = readNow();
        trackedStore.stage(fixtureLease, Endpoint.bootstrap(), pairRead, Validation.valid(), bootstrap);
        trackedStore.stage(fixtureLease, Endpoint.fixtures(), pairRead, Validation.valid(), fixtures);

        clock.advance(Duration.ofSeconds(1));
        SourceRead evidenceRead = readNow();
        trackedStore.stage(fixtureLease, Endpoint.eventLive(3), evidenceRead, Validation.valid(), liveThree);
        var historyRun = trackedStore.enqueue(SyncKind.PLAYER_HISTORY, SEASON.code(), "player-history:259",
                "player-history:259", clock.instant());
        Lease historyLease = trackedStore.acquire("history-worker", Duration.ofMinutes(5)).orElseThrow();
        assertThat(historyLease.generation()).isEqualTo(historyRun.generation());
        trackedStore.stage(historyLease, Endpoint.elementSummary(259), evidenceRead,
                Validation.valid(), historyOne);
        trackedStore.publish(historyLease, evidenceRead, () -> null);

        tracking.resetBodyQueries();
        DispatchResult result = staged.process(fixtureLease, SEASON);

        assertThat(result.outcome()).isEqualTo(Outcome.WAITING_FOR_HISTORIES);
        assertThat(fetch.endpoints).isEmpty();
        assertThat(tracking.bodyQueries()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                + " WHERE kind='PLAYER_HISTORY' AND state='PENDING'", Integer.class)).isEqualTo(71);
    }

    @Test
    void rateLimitedFirstEndpointUsesAuthoritativeDueAndRetainsItsRawResponse() {
        Instant due = clock.instant().plus(Duration.ofHours(2));
        var run = dispatcher.enqueueFixtureVerification(SEASON, 21, clock.instant());
        fetch.results.add(new Response(429, "busy".getBytes(), "ignored", readNow(), due));

        DispatchResult result = dispatcher.process(acquire(run.generation()), SEASON);

        assertThat(result.outcome()).isEqualTo(Outcome.RETRY_SCHEDULED);
        assertThat(result.failure()).isEqualTo(FailureType.HTTP_FAILURE);
        assertThat(jdbc.queryForObject("SELECT due_at FROM " + TABLES.run()
                + " WHERE generation=?", java.sql.Timestamp.class, run.generation()).toInstant())
                .isEqualTo(due);
        assertThat(jdbc.queryForObject("SELECT validation_state FROM " + TABLES.payload(), String.class))
                .isEqualTo("INVALID");
    }

    @Test
    void runtimeFixtureFailuresUseDurableLadderAndQueuedAttemptStopsAtTwelveHours() {
        jdbc.update("INSERT INTO season(id, code, name, start_date, end_date, is_current)"
                + " VALUES (27, '2026-27', 'Runtime test', '2026-08-21', '2027-05-23', false)"
                + " ON CONFLICT (id) DO NOTHING");
        jdbc.update("INSERT INTO " + TABLES.fixtureSync()
                        + "(season_id, season_code, fixture_id, state, first_finished_observed_at, updated_at)"
                        + " VALUES (27, '2026-27', 21, 'VERIFYING', ?, ?)",
                java.sql.Timestamp.from(clock.instant()), java.sql.Timestamp.from(clock.instant()));
        var run = dispatcher.enqueueFixtureVerification(SEASON, 21, clock.instant());

        long[] delays = {600, 1_200, 2_400, 3_600};
        for (long delay : delays) {
            fetch.results.add(new Response(500, new byte[0], null, readNow(), clock.instant().plusSeconds(1)));
            DispatchResult failed = dispatcher.process(acquire(run.generation()), SEASON);
            assertThat(failed.failure()).isEqualTo(FailureType.HTTP_FAILURE);
            Instant due = jdbc.queryForObject("SELECT next_due_at FROM " + TABLES.fixtureSync()
                    + " WHERE fixture_id=21", java.sql.Timestamp.class).toInstant();
            assertThat(due).isEqualTo(clock.instant().plusSeconds(delay));
            clock.advance(Duration.ofSeconds(delay));
        }

        clock.advance(Duration.between(clock.instant(),
                Instant.parse("2026-09-14T06:00:00Z")));
        int fetchedBefore = fetch.endpoints.size();
        DispatchResult capped = dispatcher.process(acquire(run.generation()), SEASON);
        assertThat(capped.outcome()).isEqualTo(Outcome.RETRY_SCHEDULED);
        assertThat(fetch.endpoints).hasSize(fetchedBefore);
        assertThat(jdbc.queryForObject("SELECT alert_state FROM " + TABLES.fixtureSync()
                + " WHERE fixture_id=21", String.class)).isEqualTo("DAILY_ONLY");
        assertThat(jdbc.queryForObject("SELECT next_due_at FROM " + TABLES.fixtureSync()
                + " WHERE fixture_id=21", java.sql.Timestamp.class).toInstant())
                .isEqualTo(Instant.parse("2026-09-15T02:00:00Z"));
    }

    private void assertCorrectedHistoryRefreshesParent(String field, Object value) throws Exception {
        seedCanonicalPair();
        ObjectNode corrected = (ObjectNode) JSON.readTree(historyOne);
        ObjectNode row = (ObjectNode) corrected.withArray("history").get(3);
        if (value instanceof Integer number) row.put(field, number);
        else row.put(field, value.toString());
        var run = dispatcher.enqueuePlayerHistory(SEASON, 1, clock.instant());
        fetch.results.add(ok(JSON.writeValueAsBytes(corrected)));

        DispatchResult stale = dispatcher.process(acquire(run.generation()), SEASON);

        assertThat(stale.outcome()).isEqualTo(Outcome.RETRY_SCHEDULED);
        assertThat(stale.failure()).isEqualTo(FailureType.STALE_PARENT);
        assertThat(jdbc.queryForObject("SELECT validation_state FROM " + TABLES.payload()
                + " WHERE endpoint_kind='ELEMENT_SUMMARY'", String.class)).isEqualTo("UNCHECKED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                + " WHERE kind='CATALOGUE_SCHEDULE' AND state='PENDING'", Integer.class)).isEqualTo(2);

        publishQueuedFreshParent();
        clock.advance(Duration.ofSeconds(31));
        DispatchResult unchanged = dispatcher.process(acquire(run.generation()), SEASON);

        assertThat(unchanged.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(unchanged.failure()).isEqualTo(FailureType.INVALID_SOURCE);
        assertThat(fetch.endpoints).containsExactly(Endpoint.elementSummary(1));
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM " + TABLES.run()
                + " WHERE generation=?", Integer.class, run.generation())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT validation_error FROM " + TABLES.payload()
                + " WHERE endpoint_kind='ELEMENT_SUMMARY'", String.class))
                .startsWith("Fresh catalogue/schedule parent still contradicts history source:");
    }

    private void publishQueuedFreshParent() {
        Lease lease = store.acquire("parent-worker", Duration.ofMinutes(5)).orElseThrow();
        assertThat(lease.kind()).isEqualTo(SyncKind.CATALOGUE_SCHEDULE);
        SourceRead read = readNow();
        store.stage(lease, Endpoint.bootstrap(), read, Validation.valid(), bootstrap);
        store.stage(lease, Endpoint.fixtures(), read, Validation.valid(), fixtures);
        store.publish(lease, read, () -> null);
    }

    private void stagedPair(Lease lease, SourceRead read) {
        store.stage(lease, Endpoint.bootstrap(), read, Validation.valid(), bootstrap);
        store.stage(lease, Endpoint.fixtures(), read, Validation.valid(), fixtures);
    }

    private void seedCanonicalPair() {
        var run = store.enqueue(SyncKind.CATALOGUE_SCHEDULE, SEASON.code(), "test-canonical",
                "test-canonical", clock.instant());
        Lease lease = acquire(run.generation());
        SourceRead read = readNow();
        long bootstrapId = store.stage(lease, Endpoint.bootstrap(), read, Validation.valid(), bootstrap);
        long fixtureId = store.stage(lease, Endpoint.fixtures(), read, Validation.valid(), fixtures);
        store.validateStagedPayloads(lease, java.util.List.of(bootstrapId, fixtureId), Validation.valid());
        store.publish(lease, read, () -> null);
        clock.advance(Duration.ofSeconds(1));
    }

    private Lease acquire(long generation) {
        return acquire(generation, Duration.ofMinutes(5));
    }

    private Lease acquire(long generation, Duration duration) {
        Lease lease;
        do {
            lease = store.acquire("worker", duration).orElseThrow();
        } while (lease.generation() != generation);
        return lease;
    }

    private Lease enqueueAndAcquireHistory() {
        var run = dispatcher.enqueuePlayerHistory(SEASON, 1, clock.instant());
        return acquire(run.generation());
    }

    private void activateStagingStore() {
        activateStore(OwnedSyncMode.STAGING);
    }

    private void activateStore(OwnedSyncMode mode) {
        OwnedSyncStore.RuntimeFence fence = new PlannerRuntimeRepository(jdbc, tx, clock, TABLES)
                .activate(SEASON.code(), mode);
        store = new OwnedSyncStore(jdbc, tx, clock, TABLES, fence);
        dispatcher = dispatcher(fetch);
    }

    private HistoryFixtureDispatcher dispatcher(HistoryFixtureDispatcher.Fetch httpFetch) {
        return new HistoryFixtureDispatcher(store, publisher(), httpFetch, clock);
    }

    private FixtureSyncPublisher publisher() {
        return new FixtureSyncPublisher(jdbc, clock, store,
                new FixtureSyncRepository(jdbc, TABLES), TABLES);
    }

    private HistoryFixtureDispatcher.Fetch fencedHttpFetch(Loopback loopback,
                                                            java.util.function.Consumer<HttpFetchRequest> beforeFence) {
        return new HistoryFixtureDispatcher.Fetch() {
            @Override
            public OwnedSyncHttpClient.Result get(Endpoint endpoint, int failureAttempt) {
                throw new AssertionError("fenced fetch overload was not used");
            }

            @Override
            public OwnedSyncHttpClient.Result get(Lease lease, Endpoint endpoint, int failureAttempt,
                                                  Instant sourceFloor) {
                beforeFence.accept(new HttpFetchRequest(lease, endpoint, sourceFloor));
                return loopback.http.get(endpoint, failureAttempt,
                        () -> store.httpFence(lease, endpoint, sourceFloor));
            }
        };
    }

    private Loopback loopback() throws Exception {
        jdbc.update("DELETE FROM " + TABLES.requestAttempt());
        jdbc.update("UPDATE " + TABLES.requestState() + " SET last_admitted_at=NULL, cooldown_until=NULL");
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        OwnedSyncRequestBudget budget = new OwnedSyncRequestBudget(jdbc, tx, clock, TABLES);
        OwnedSyncHttpClient http = new OwnedSyncHttpClient(budget,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/"),
                Duration.ofSeconds(2), clock);
        return new Loopback(server, http, requests);
    }

    private void assertNoHttpOrFetchFailure(Loopback loopback, long generation, int expectedPayloads) {
        assertThat(loopback.requests).hasValue(0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.requestAttempt(), Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT consecutive_fetch_failures FROM " + TABLES.run()
                + " WHERE generation=?", Integer.class, generation)).isZero();
        assertThat(payloadCount()).isEqualTo(expectedPayloads);
    }

    private void installPostAdmissionEpochInvalidation() {
        jdbc.execute("CREATE OR REPLACE FUNCTION fpl_history_test_bump_epoch() RETURNS trigger AS $$ "
                + "BEGIN UPDATE " + TABLES.plannerRuntime()
                + " SET runtime_epoch=runtime_epoch+1 WHERE instance_id='mode:2026-27'; "
                + "RETURN NEW; END; $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER fpl_history_test_epoch_trigger AFTER INSERT ON "
                + TABLES.requestAttempt() + " FOR EACH ROW EXECUTE FUNCTION fpl_history_test_bump_epoch()");
    }

    private void removePostAdmissionEpochInvalidation() {
        jdbc.execute("DROP TRIGGER IF EXISTS fpl_history_test_epoch_trigger ON " + TABLES.requestAttempt());
        jdbc.execute("DROP FUNCTION IF EXISTS fpl_history_test_bump_epoch()");
    }

    private record HttpFetchRequest(Lease lease, Endpoint endpoint, Instant sourceFloor) { }

    private final class Loopback implements AutoCloseable {
        private final HttpServer server;
        private final OwnedSyncHttpClient http;
        private final AtomicInteger requests;

        private Loopback(HttpServer server, OwnedSyncHttpClient http, AtomicInteger requests) {
            this.server = server;
            this.http = http;
            this.requests = requests;
        }

        @Override public void close() {
            http.close();
            server.stop(0);
        }
    }

    private Response ok(byte[] body) {
        Instant start = clock.instant();
        clock.advance(Duration.ofMillis(100));
        return new Response(200, body, null, new SourceRead(start, clock.instant()));
    }

    private SourceRead readNow() {
        return new SourceRead(clock.instant(), clock.instant());
    }

    private int payloadCount() {
        return jdbc.queryForObject("SELECT count(*) FROM " + TABLES.payload(), Integer.class);
    }

    private static byte[] departedZeroMinuteLive() throws Exception {
        ObjectNode root = (ObjectNode) JSON.readTree(liveThree);
        ObjectNode player = JSON.createObjectNode();
        player.put("id", 999);
        ArrayNode explain = player.putArray("explain");
        ObjectNode fixture = explain.addObject();
        fixture.put("fixture", 21);
        ObjectNode minutes = fixture.putArray("stats").addObject();
        minutes.put("identifier", "minutes");
        minutes.put("value", 0);
        minutes.put("points", 0);
        minutes.put("points_modification", 0);
        ((ArrayNode) root.get("elements")).add(player);
        return JSON.writeValueAsBytes(root);
    }

    private static byte[] resource(String name) throws Exception {
        try (InputStream input = HistoryFixtureDispatcherIntegrationTest.class.getResourceAsStream(
                "/owned-sync-source-fixtures/" + name)) {
            if (input == null) throw new IllegalArgumentException("Missing " + name);
            return input.readAllBytes();
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private final class FakeFetch implements HistoryFixtureDispatcher.Fetch {
        private final Deque<OwnedSyncHttpClient.Result> results = new ArrayDeque<>();
        private final java.util.List<Endpoint> endpoints = new java.util.ArrayList<>();
        private final java.util.List<HttpFetchRequest> requests = new java.util.ArrayList<>();
        @Override public OwnedSyncHttpClient.Result get(Endpoint endpoint, int failureAttempt) {
            endpoints.add(endpoint);
            if (results.isEmpty()) throw new AssertionError("Unexpected fetch " + endpoint);
            return results.removeFirst();
        }
        @Override public OwnedSyncHttpClient.Result get(Lease lease, Endpoint endpoint,
                                                        int failureAttempt, Instant sourceFloor) {
            requests.add(new HttpFetchRequest(lease, endpoint, sourceFloor));
            return get(endpoint, failureAttempt);
        }
    }

    private static final class TrackingJdbcTemplate extends JdbcTemplate {
        private final AtomicInteger bodyQueries = new AtomicInteger();

        private TrackingJdbcTemplate(javax.sql.DataSource dataSource) {
            super(dataSource);
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            if (sql.contains("SELECT") && sql.contains("body")) bodyQueries.incrementAndGet();
            return super.query(sql, rowMapper, args);
        }

        private int bodyQueries() {
            return bodyQueries.get();
        }

        private void resetBodyQueries() {
            bodyQueries.set(0);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant value;
        private MutableClock(Instant value) { this.value = value; }
        private void advance(Duration duration) { value = value.plus(duration); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return value; }
    }
}
