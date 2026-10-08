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
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.FixtureDependencyManifest;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.Lease;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.Run;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.SourceRead;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.StagedPayload;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.StagedPayloadMetadata;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.SyncKind;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.Validation;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.ValidationState;
import fi.karivanttinen.fplassistant.testsupport.TapasSeed;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class HistoryFixtureDispatcherIntegrationTest {

    private static final OwnedSyncTables TABLES = OwnedSyncTables.tests();
    private static final CatalogueScheduleTables CAPTURED = CatalogueScheduleTables.tests();
    private static final Season SEASON = new Season(27, "2026-27", LocalDate.of(2026, 8, 21), null);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static byte[] bootstrap;
    private static byte[] fixtures;
    private static byte[] unfinishedFixture21;
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
        unfinishedFixture21 = withFixtureUnfinished(fixtures, 21);
        historyOne = resource("element-summary-1.json");
        liveThree = resource("event-3-live.json");
    }

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE " + TABLES.playerFixtureEvidence() + ", " + TABLES.fixtureSync()
                + ", " + TABLES.historyFingerprint() + ", " + TABLES.payload() + ", "
                + TABLES.run() + ", " + TABLES.receipt() + " CASCADE");
        jdbc.update("UPDATE " + TABLES.requestState() + " SET cooldown_until=NULL");
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
        for (int playerId : players) {
            String historyKey = "player-history:" + playerId;
            var evidenceRun = store.enqueue(SyncKind.PLAYER_HISTORY, SEASON.code(), historyKey,
                    historyKey, historyRead.startedAt());
            Lease evidenceLease = acquire(evidenceRun.generation());
            store.stage(evidenceLease, Endpoint.elementSummary(playerId), historyRead,
                    Validation.valid(), historyOne);
            store.completeStaged(evidenceLease, historyRead, List.of(historyRead));
        }
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
        Long bootstrapPayloadId = jdbc.queryForObject("SELECT id FROM " + TABLES.payload()
                + " WHERE run_generation=? AND endpoint_kind='BOOTSTRAP_STATIC'", Long.class, run.generation());
        Long fixturesPayloadId = jdbc.queryForObject("SELECT id FROM " + TABLES.payload()
                + " WHERE run_generation=? AND endpoint_kind='FIXTURES'", Long.class, run.generation());
        Long livePayloadId = jdbc.queryForObject("SELECT id FROM " + TABLES.payload()
                + " WHERE run_generation=? AND endpoint_kind='EVENT_LIVE' AND event_id=3",
                Long.class, run.generation());
        assertThat(jdbc.query("SELECT bootstrap_payload_id, fixtures_payload_id, live_payload_id FROM "
                        + TABLES.fixtureDependencyManifest() + " WHERE run_generation=?",
                (rs, row) -> List.of(rs.getLong(1), rs.getLong(2), rs.getLong(3)), run.generation()))
                .containsExactly(List.of(bootstrapPayloadId, fixturesPayloadId, livePayloadId));
        Integer[] requiredPlayerIds = jdbc.queryForObject("SELECT required_player_ids FROM "
                        + TABLES.fixtureDependencyManifest() + " WHERE run_generation=?",
                (rs, row) -> (Integer[]) rs.getArray(1).getArray(), run.generation());
        assertThat(requiredPlayerIds).hasSize(73).contains(999);
        assertThat(jdbc.queryForObject("SELECT fpl_sync_required_ids_valid(required_player_ids) FROM "
                        + TABLES.fixtureDependencyManifest() + " WHERE run_generation=?",
                Boolean.class, run.generation())).isTrue();
        assertThat(jdbc.queryForObject("SELECT source_floor FROM " + TABLES.fixtureDependencyManifest()
                + " WHERE run_generation=?", java.sql.Timestamp.class, run.generation()).toInstant())
                .isEqualTo(requestedFloor);
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
    void repeatedMissingHistoryNotificationsDoNotAdvanceTheFixtureSourceFloor() {
        HistoryFixtureDispatcher staged = new HistoryFixtureDispatcher(store, null, fetch, clock, false);
        var run = staged.enqueueFixtureVerification(SEASON, 21, clock.instant());
        Lease fixtureLease = acquire(run.generation());
        fetch.results.add(ok(bootstrap));
        fetch.results.add(ok(fixtures));
        clock.advance(Duration.ofMillis(1));
        fetch.results.add(ok(liveThree));

        assertThat(staged.process(fixtureLease, SEASON).outcome()).isEqualTo(Outcome.WAITING_FOR_HISTORIES);
        Instant historyFloor = jdbc.queryForObject("SELECT read_finished_at FROM " + TABLES.payload()
                + " WHERE run_generation=? AND endpoint_kind='FIXTURES'", java.sql.Timestamp.class,
                run.generation()).toInstant().plusMillis(1);
        List<HistoryJob> jobs = historyJobs();
        assertThat(jobs).isNotEmpty();
        assertThat(jdbc.query("SELECT requested_source_after FROM " + TABLES.run()
                        + " WHERE kind='PLAYER_HISTORY' ORDER BY generation",
                (rs, row) -> rs.getTimestamp(1).toInstant())).containsOnly(historyFloor);
        HistoryJob leasedJob = jobs.getFirst();

        clock.advance(Duration.ofMinutes(2));
        Lease resumedFixture = acquire(run.generation());
        assertThat(resumedFixture.generation()).isEqualTo(run.generation());
        String leaseIdentity = jdbc.queryForObject("SELECT lease_owner || ':' || lease_token::text || ':' || lease_fence "
                + "FROM " + TABLES.run() + " WHERE generation=?", String.class, leasedJob.generation());
        assertThat(leaseIdentity).isNotBlank();
        int historyRuns = jobs.size();

        assertThat(staged.process(resumedFixture, SEASON).outcome()).isEqualTo(Outcome.WAITING_FOR_HISTORIES);

        assertThat(historyJobs()).containsExactlyElementsOf(jobs);
        assertThat(jdbc.query("SELECT requested_source_after FROM " + TABLES.run()
                        + " WHERE kind='PLAYER_HISTORY' ORDER BY generation",
                (rs, row) -> rs.getTimestamp(1).toInstant())).containsOnly(historyFloor);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                + " WHERE kind='PLAYER_HISTORY'", Integer.class)).isEqualTo(historyRuns);
        assertThat(jdbc.queryForObject("SELECT lease_owner || ':' || lease_token::text || ':' || lease_fence "
                + "FROM " + TABLES.run() + " WHERE generation=?", String.class, leasedJob.generation()))
                .isEqualTo(leaseIdentity);
        assertThat(fetch.endpoints).containsExactly(Endpoint.bootstrap(), Endpoint.fixtures(), Endpoint.eventLive(3));
    }

    @Test
    void unchangedHistoryFloorWithValidEvidenceDoesNotCreateAnotherHistoryRequest() {
        HistoryFixtureDispatcher staged = new HistoryFixtureDispatcher(store, null, fetch, clock, false);
        var run = staged.enqueueFixtureVerification(SEASON, 21, clock.instant());
        Lease fixtureLease = acquire(run.generation());
        fetch.results.add(ok(bootstrap));
        fetch.results.add(ok(fixtures));
        clock.advance(Duration.ofMillis(1));
        fetch.results.add(ok(liveThree));

        assertThat(staged.process(fixtureLease, SEASON).outcome()).isEqualTo(Outcome.WAITING_FOR_HISTORIES);
        Instant historyFloor = jdbc.queryForObject("SELECT read_finished_at FROM " + TABLES.payload()
                + " WHERE run_generation=? AND endpoint_kind='FIXTURES'", java.sql.Timestamp.class,
                run.generation()).toInstant().plusMillis(1);
        List<HistoryJob> jobs = historyJobs();
        int initialHistoryRuns = jobs.size();

        for (HistoryJob job : jobs) {
            Lease historyLease = acquire(job.generation());
            SourceRead historyRead = readNow();
            store.stage(historyLease, Endpoint.elementSummary(job.playerId()), historyRead,
                    Validation.valid(), historyOne);
            store.publish(historyLease, historyRead, () -> null);
        }
        assertThat(jdbc.query("SELECT requested_source_after FROM " + TABLES.run()
                        + " WHERE kind='PLAYER_HISTORY' ORDER BY generation",
                (rs, row) -> rs.getTimestamp(1).toInstant())).containsOnly(historyFloor);

        clock.advance(Duration.ofMinutes(2));
        Lease resumedFixture = acquire(run.generation());
        fetch.results.add(ok(bootstrap));
        fetch.results.add(ok(fixtures));

        assertThat(staged.process(resumedFixture, SEASON).outcome()).isEqualTo(Outcome.STAGED);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                + " WHERE kind='PLAYER_HISTORY'", Integer.class)).isEqualTo(initialHistoryRuns);
        assertThat(jdbc.query("SELECT state FROM " + TABLES.run()
                        + " WHERE kind='PLAYER_HISTORY' ORDER BY generation",
                (rs, row) -> rs.getString(1))).containsOnly("COMPLETED");
        assertThat(fetch.endpoints).containsExactly(Endpoint.bootstrap(), Endpoint.fixtures(), Endpoint.eventLive(3),
                Endpoint.bootstrap(), Endpoint.fixtures());
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
        clock.advance(Duration.ofMillis(1));
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
        clock.advance(Duration.ofMillis(1));
        fetch.results.add(ok(liveThree));
        DispatchResult resumed = staged.process(trackedStore.acquire("checkpoint-resume", Duration.ofMinutes(5))
                .orElseThrow(), SEASON);

        assertThat(resumed.outcome()).isEqualTo(Outcome.WAITING_FOR_HISTORIES);
        assertThat(fetch.endpoints).containsExactly(Endpoint.fixtures(), Endpoint.fixtures(),
                Endpoint.eventLive(3));
        assertThat(tracking.bodyQueries()).isOne();
    }

    @Test
    void unchangedBlockedManifestRetryReadsNoOwnHistoryOrCanonicalBodies() throws Exception {
        TrackingJdbcTemplate tracking = new TrackingJdbcTemplate(jdbc.getDataSource());
        OwnedSyncStore.RuntimeFence fence = new PlannerRuntimeRepository(jdbc, tx, clock, TABLES)
                .activate(SEASON.code(), OwnedSyncMode.ACTIVE);
        OwnedSyncStore trackedStore = new OwnedSyncStore(tracking, tx, clock, TABLES, fence);
        store = trackedStore;
        dispatcher = new HistoryFixtureDispatcher(trackedStore, publisher(), fetch, clock);
        LaneReuse currentParent = seedCurrentCanonicalPairReusedAcrossLanes();
        long currentParentGeneration = currentParent.sourceGeneration();

        SourceRead oldRead = readNow();
        Set<Integer> playerIds = fixturePlayerIds(oldRead);
        byte[] largeHistory = largeHistoryBody();
        for (int playerId : playerIds) {
            var oldRun = dispatcher.enqueuePlayerHistory(SEASON, playerId, oldRead.startedAt());
            Lease oldLease = trackedStore.acquire("old-history-" + playerId, Duration.ofMinutes(5))
                    .orElseThrow();
            assertThat(oldLease.generation()).isEqualTo(oldRun.generation());
            trackedStore.stage(oldLease, Endpoint.elementSummary(playerId), oldRead,
                    Validation.valid(), largeHistory);
            trackedStore.completeStaged(oldLease, oldRead, List.of(oldRead));
        }
        clock.advance(Duration.ofDays(1));
        jdbc.update("INSERT INTO season(id, code, name, start_date, end_date, is_current)"
                        + " VALUES (27, '2026-27', 'Runtime test', '2026-08-21', '2027-05-23', false)"
                        + " ON CONFLICT (id) DO NOTHING");
        new PlannerInputRepository(jdbc, trackedStore, TABLES, CAPTURED, clock).load(SEASON);
        assertThat(jdbc.queryForObject("SELECT finished_parent_generation FROM " + TABLES.fixtureSync()
                + " WHERE season_id=27 AND fixture_id=21", Long.class)).isEqualTo(currentParentGeneration);
        assertThat(jdbc.queryForObject("SELECT finished_parent_publication_generation FROM "
                + TABLES.fixtureSync() + " WHERE season_id=27 AND fixture_id=21", Long.class))
                .isEqualTo(currentParent.publicationGeneration());
        assertThat(currentParent.sourceGeneration()).isNotEqualTo(currentParent.publicationGeneration());
        assertThat(jdbc.queryForObject("SELECT latest_generation FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", Long.class, SEASON.code(),
                CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY)).isEqualTo(currentParent.sourceGeneration());
        assertThat(jdbc.queryForObject("SELECT latest_generation FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", Long.class, SEASON.code(),
                CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY)).isEqualTo(currentParent.publicationGeneration());
        assertThat(trackedStore.currentPublishedCanonicalPair(SEASON.code(), currentParent.sourcePair(),
                clock.instant())).contains(new OwnedSyncStore.CanonicalPublication(
                currentParent.sourceGeneration(), currentParent.publicationGeneration(),
                currentParent.sourcePair().fingerprint()));
        Instant firstFinished = jdbc.queryForObject("SELECT first_finished_observed_at FROM "
                + TABLES.fixtureSync() + " WHERE season_id=27 AND fixture_id=21",
                java.sql.Timestamp.class).toInstant();
        jdbc.update("UPDATE " + TABLES.fixtureSync() + " SET next_due_at=NULL, due_reason=NULL,"
                + " transport_retry_at=NULL, dependency_retry_at=NULL, recovery_day=NULL,"
                + " recovery_started_at=NULL, recovery_until=NULL WHERE season_id=27 AND fixture_id=21");
        trackedStore = new OwnedSyncStore(tracking, tx, clock, TABLES, fence);
        store = trackedStore;
        dispatcher = new HistoryFixtureDispatcher(trackedStore, publisher(), fetch, clock);
        clock.advance(Duration.ofHours(13));
        assertThat(clock.instant()).isAfter(firstFinished.plus(Duration.ofHours(12)));
        assertThat(trackedStore.currentCanonicalParentEvidence(SEASON.code(), 21, clock.instant()))
                .isEqualTo(new OwnedSyncStore.CanonicalParentEvidence(true, true));
        var fixtureRun = dispatcher.enqueueFixtureVerification(SEASON, 21, clock.instant());
        Lease fixtureLease = acquire(fixtureRun.generation());
        Instant requestedFloor = trackedStore.requestedSourceAfter(fixtureLease);
        SourceRead pairRead = readNow();
        long bootstrapId = trackedStore.stage(fixtureLease, Endpoint.bootstrap(), pairRead,
                Validation.valid(), bootstrap);
        long fixturesId = trackedStore.stage(fixtureLease, Endpoint.fixtures(), pairRead,
                Validation.valid(), fixtures);
        clock.advance(Duration.ofMillis(1));
        SourceRead evidenceRead = readNow();
        long liveId = trackedStore.stage(fixtureLease, Endpoint.eventLive(3), evidenceRead,
                Validation.valid(), liveThree);
        int readyPlayer = playerIds.stream().min(Integer::compareTo).orElseThrow();
        var historyRun = dispatcher.enqueuePlayerHistory(SEASON, readyPlayer, clock.instant());
        Lease historyLease = acquire(historyRun.generation());
        SourceRead freshHistoryRead = readNow();
        trackedStore.stage(historyLease, Endpoint.elementSummary(readyPlayer), freshHistoryRead,
                Validation.valid(), largeHistory);
        trackedStore.completeStaged(historyLease, freshHistoryRead, List.of(freshHistoryRead));
        List<Integer> wrongScopePlayers = playerIds.stream().filter(id -> id != readyPlayer)
                .sorted().limit(3).toList();
        for (int index = 0; index < wrongScopePlayers.size(); index++) {
            int playerId = wrongScopePlayers.get(index);
            var wrongRun = dispatcher.enqueuePlayerHistory(SEASON, playerId, clock.instant());
            Lease wrongLease = acquire(wrongRun.generation());
            SourceRead wrongRead = readNow();
            trackedStore.stage(wrongLease, Endpoint.elementSummary(playerId), wrongRead,
                    Validation.valid(), largeHistory);
            trackedStore.completeStaged(wrongLease, wrongRead, List.of(wrongRead));
            if (index == 0) {
                jdbc.update("UPDATE " + TABLES.run() + " SET runtime_epoch=runtime_epoch-1 WHERE generation=?",
                        wrongRun.generation());
            } else if (index == 1) {
                String key = HistoryFixtureDispatcher.playerHistoryPublicationKey(playerId);
                jdbc.update("INSERT INTO " + TABLES.receipt()
                        + "(season,publication_key) VALUES ('2025-26',?) ON CONFLICT DO NOTHING", key);
                jdbc.update("UPDATE " + TABLES.run() + " SET season='2025-26' WHERE generation=?",
                        wrongRun.generation());
            } else {
                String wrongKey = HistoryFixtureDispatcher.playerHistoryPublicationKey(playerId + 10000);
                jdbc.update("INSERT INTO " + TABLES.receipt()
                        + "(season,publication_key) VALUES (?,?) ON CONFLICT DO NOTHING",
                        SEASON.code(), wrongKey);
                jdbc.update("UPDATE " + TABLES.run()
                        + " SET coalescing_key=?, publication_key=? WHERE generation=?",
                        wrongKey, wrongKey, wrongRun.generation());
            }
        }

        tracking.resetBodyQueries();
        DispatchResult first = dispatcher.process(fixtureLease, SEASON);

        assertThat(first.outcome()).isEqualTo(Outcome.WAITING_FOR_HISTORIES);
        assertThat(playerIds).hasSize(72);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.fixtureDependencyManifest()
                + " WHERE run_generation=?", Integer.class, fixtureRun.generation())).isOne();
        Integer[] missing = jdbc.queryForObject("SELECT ARRAY(SELECT required.player_id"
                        + " FROM " + TABLES.fixtureDependencyManifest()
                        + " m CROSS JOIN LATERAL unnest(m.required_player_ids) required(player_id)"
                        + " WHERE m.run_generation=? AND required.player_id<>?)",
                (rs, row) -> (Integer[]) rs.getArray(1).getArray(), fixtureRun.generation(), readyPlayer);
        assertThat(missing).hasSize(71);
        assertThat(tracking.bodyQueries()).isEqualTo(4);
        assertThat(tracking.snapshot().bodyFamily(PayloadReadProbe.BodyFamily.OWN_ANCHORS))
                .isEqualTo(new PayloadReadProbe.FamilyMetrics(3, 3, 3,
                        bootstrap.length + (long) fixtures.length + liveThree.length));
        assertThat(tracking.snapshot().bodyFamily(PayloadReadProbe.BodyFamily.CANONICAL_PAIRS))
                .isEqualTo(new PayloadReadProbe.FamilyMetrics(1, 1, 2,
                        bootstrap.length + (long) fixtures.length));
        assertThat(tracking.snapshot().bodyFamily(PayloadReadProbe.BodyFamily.CROSS_RUN_HISTORIES).rawBytes())
                .isZero();
        assertThat(jdbc.queryForObject("SELECT bootstrap_payload_id FROM "
                + TABLES.fixtureDependencyManifest() + " WHERE run_generation=?", Long.class,
                fixtureRun.generation())).isEqualTo(bootstrapId);
        assertThat(jdbc.queryForObject("SELECT fixtures_payload_id FROM "
                + TABLES.fixtureDependencyManifest() + " WHERE run_generation=?", Long.class,
                fixtureRun.generation())).isEqualTo(fixturesId);
        assertThat(jdbc.queryForObject("SELECT live_payload_id FROM "
                + TABLES.fixtureDependencyManifest() + " WHERE run_generation=?", Long.class,
                fixtureRun.generation())).isEqualTo(liveId);
        Instant legacyDue = clock.instant().plus(Duration.ofMinutes(15));
        writeLegacyDependencyWait(21, legacyDue);
        assertThat(jdbc.queryForObject("SELECT due_reason FROM " + TABLES.fixtureSync()
                + " WHERE season_id=? AND fixture_id=21", String.class, SEASON.id())).isNull();
        assertThat(legacyDue).isAfter(clock.instant());
        jdbc.update("UPDATE " + TABLES.run() + " SET due_at=? WHERE kind='PLAYER_HISTORY' AND state='PENDING'",
                java.sql.Timestamp.from(clock.instant().plus(Duration.ofHours(2))));
        clock.advance(Duration.ofMinutes(2));
        trackedStore = new OwnedSyncStore(tracking, tx, clock, TABLES, fence);
        store = trackedStore;
        dispatcher = new HistoryFixtureDispatcher(trackedStore, publisher(), fetch, clock);
        dispatcher.enqueuePersistedFixtureRetry(SEASON, 21, clock.instant());
        Lease retryLease = acquire(fixtureRun.generation());
        assertThat(trackedStore.requestedSourceAfter(retryLease)).isEqualTo(requestedFloor);
        assertThat(trackedStore.fixtureDependencyManifest(retryLease, 21).orElseThrow()
                .missingPlayerIds()).hasSize(71);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                + " WHERE kind='PLAYER_HISTORY' AND state='PENDING'", Integer.class)).isEqualTo(71);

        tracking.resetBodyQueries();
        DispatchResult repeated = dispatcher.process(retryLease, SEASON);

        assertThat(repeated.outcome()).isEqualTo(Outcome.RETRY_SCHEDULED);
        assertThat(repeated.failure()).isEqualTo(FailureType.HTTP_DEFERRED);
        assertThat(fetch.endpoints).isEmpty();
        assertThat(tracking.bodyQueries()).isZero();
        assertThat(tracking.snapshot().bodyValues()).isZero();
        assertThat(tracking.snapshot().rawBytes()).isZero();
        assertThat(jdbc.queryForObject("SELECT requested_source_after FROM " + TABLES.run()
                + " WHERE generation=?", java.sql.Timestamp.class, fixtureRun.generation()).toInstant())
                .isEqualTo(requestedFloor);
        assertThat(jdbc.queryForObject("SELECT next_due_at FROM " + TABLES.fixtureSync()
                + " WHERE season_id=? AND fixture_id=21", java.sql.Timestamp.class, SEASON.id()).toInstant())
                .isEqualTo(legacyDue);
        assertThat(jdbc.queryForObject("SELECT due_at FROM " + TABLES.run()
                + " WHERE generation=?", java.sql.Timestamp.class, fixtureRun.generation()).toInstant())
                .isEqualTo(legacyDue);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                + " WHERE kind='CATALOGUE_SCHEDULE' AND state IN ('PENDING','LEASED')", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                + " WHERE kind='PLAYER_HISTORY' AND state='PENDING'", Integer.class)).isEqualTo(71);
        assertThat(jdbc.queryForObject("SELECT consecutive_fetch_failures FROM " + TABLES.run()
                + " WHERE generation=?", Integer.class, fixtureRun.generation())).isZero();
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM " + TABLES.fixtureSync()
                + " WHERE season_id=? AND fixture_id=21", Integer.class, SEASON.id())).isZero();

        List<HistoryJob> pendingHistories = pendingHistoryJobs();
        assertThat(pendingHistories).hasSize(71);
        jdbc.update("UPDATE " + TABLES.run() + " SET due_at=? WHERE kind='PLAYER_HISTORY' AND state='PENDING'",
                java.sql.Timestamp.from(clock.instant()));
        for (HistoryJob job : pendingHistories) {
            Lease arrivedLease = trackedStore.acquire("arrived-history-" + job.playerId(), Duration.ofMinutes(5))
                    .orElseThrow();
            assertThat(arrivedLease.generation()).isEqualTo(job.generation());
            SourceRead arrivedRead = readNow();
            trackedStore.stage(arrivedLease, Endpoint.elementSummary(job.playerId()), arrivedRead,
                    Validation.valid(), largeHistory);
            trackedStore.completeStaged(arrivedLease, arrivedRead, List.of(arrivedRead));
        }
        dispatcher.enqueuePersistedFixtureRetry(SEASON, 21, clock.instant());
        Lease historiesReadyLease = acquire(fixtureRun.generation());
        assertThat(trackedStore.fixtureDependencyManifest(historiesReadyLease, 21).orElseThrow()
                .missingPlayerIds()).isEmpty();
        tracking.resetBodyQueries();
        DispatchResult historiesReady = dispatcher.process(historiesReadyLease, SEASON);
        assertThat(historiesReady.outcome()).isEqualTo(Outcome.RETRY_SCHEDULED);
        assertThat(historiesReady.failure()).isEqualTo(FailureType.HTTP_DEFERRED);
        assertThat(fetch.endpoints).isEmpty();
        assertThat(tracking.bodyQueries()).isZero();
        assertThat(tracking.snapshot().rawBytes()).isZero();
        assertThat(jdbc.queryForObject("SELECT next_due_at FROM " + TABLES.fixtureSync()
                + " WHERE season_id=? AND fixture_id=21", java.sql.Timestamp.class, SEASON.id()).toInstant())
                .isEqualTo(legacyDue);
        assertThat(jdbc.queryForObject("SELECT due_at FROM " + TABLES.run()
                + " WHERE generation=?", java.sql.Timestamp.class, fixtureRun.generation()).toInstant())
                .isEqualTo(legacyDue);

        clock.advance(Duration.between(clock.instant(), legacyDue));
        fetch.results.add(new DeferredResult(clock.instant().plusSeconds(10), DeferredReason.MINIMUM_SPACING));
        Lease dueLease = acquire(fixtureRun.generation());
        tracking.resetBodyQueries();
        DispatchResult due = dispatcher.process(dueLease, SEASON);
        assertThat(due.failure()).isEqualTo(FailureType.HTTP_DEFERRED);
        assertThat(fetch.endpoints).containsExactly(Endpoint.bootstrap());
        assertThat(tracking.snapshot().bodyFamily(PayloadReadProbe.BodyFamily.CROSS_RUN_HISTORIES))
                .isEqualTo(new PayloadReadProbe.FamilyMetrics(1, 72, 72, 72L * 64 * 1024));
        assertThat(tracking.snapshot().maxBodyBytesInSingleQuery(
                PayloadReadProbe.BodyFamily.CROSS_RUN_HISTORIES)).isEqualTo(72L * 64 * 1024);
        assertThat(tracking.snapshot().rawBytes()).isGreaterThan(72L * 64 * 1024);
    }

    @Test
    void currentParentProofRejectsOldPublicationAfterNewUnpublishedDemandWithoutReadingBodies() {
        TrackingJdbcTemplate tracking = new TrackingJdbcTemplate(jdbc.getDataSource());
        OwnedSyncStore.RuntimeFence fence = new PlannerRuntimeRepository(jdbc, tx, clock, TABLES)
                .activate(SEASON.code(), OwnedSyncMode.ACTIVE);
        store = new OwnedSyncStore(tracking, tx, clock, TABLES, fence);
        long currentParentGeneration = seedCurrentCanonicalPair();
        jdbc.update("INSERT INTO season(id, code, name, start_date, end_date, is_current)"
                        + " VALUES (27, '2026-27', 'Runtime test', '2026-08-21', '2027-05-23', false)"
                        + " ON CONFLICT (id) DO NOTHING");
        new PlannerInputRepository(jdbc, store, TABLES, CAPTURED, clock).load(SEASON);
        assertThat(jdbc.queryForObject("SELECT finished_parent_generation FROM " + TABLES.fixtureSync()
                + " WHERE season_id=27 AND fixture_id=21", Long.class)).isEqualTo(currentParentGeneration);

        tracking.resetBodyQueries();
        clock.advance(Duration.ofMillis(1));
        seedUnpublishedCanonicalPair();
        store = new OwnedSyncStore(tracking, tx, clock, TABLES, fence);

        assertThat(store.currentCanonicalParentEvidence(SEASON.code(), 21, clock.instant()))
                .isEqualTo(OwnedSyncStore.CanonicalParentEvidence.MISSING);
        assertThat(jdbc.queryForObject("SELECT latest_generation FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", Long.class, SEASON.code(),
                CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY)).isEqualTo(currentParentGeneration);
        assertThat(tracking.bodyQueries()).isZero();
    }

    @Test
    void missingCurrentParentQueuesOnlyTheCoalescedCanonicalDependencies() {
        TrackingJdbcTemplate tracking = new TrackingJdbcTemplate(jdbc.getDataSource());
        OwnedSyncStore.RuntimeFence fence = new PlannerRuntimeRepository(jdbc, tx, clock, TABLES)
                .activate(SEASON.code(), OwnedSyncMode.ACTIVE);
        store = new OwnedSyncStore(tracking, tx, clock, TABLES, fence);
        dispatcher = new HistoryFixtureDispatcher(store, publisher(), fetch, clock);
        FixtureManifestSeed seed = seedBlockedManifest();
        jdbc.update("INSERT INTO season(id, code, name, start_date, end_date, is_current)"
                        + " VALUES (27, '2026-27', 'Runtime test', '2026-08-21', '2027-05-23', false)"
                        + " ON CONFLICT (id) DO NOTHING");
        jdbc.update("INSERT INTO " + TABLES.fixtureSync()
                        + "(season_id,season_code,fixture_id,state,first_finished_observed_at,updated_at)"
                        + " VALUES (27,'2026-27',21,'WAITING_STATS',?,?)",
                java.sql.Timestamp.from(clock.instant().minus(Duration.ofDays(2))),
                java.sql.Timestamp.from(clock.instant()));

        tracking.resetBodyQueries();
        DispatchResult first = dispatcher.process(acquire(seed.generation()), SEASON);

        assertThat(first.outcome()).isEqualTo(Outcome.RETRY_SCHEDULED);
        assertThat(first.failure()).isEqualTo(FailureType.HTTP_DEFERRED);
        assertThat(tracking.bodyQueries()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                + " WHERE kind='CATALOGUE_SCHEDULE' AND state IN ('PENDING','LEASED')", Integer.class)).isEqualTo(2);

        clock.advance(Duration.ofMinutes(2));
        dispatcher.enqueuePersistedFixtureRetry(SEASON, 21, clock.instant());
        DispatchResult repeated = dispatcher.process(acquire(seed.generation()), SEASON);

        assertThat(repeated.outcome()).isEqualTo(Outcome.RETRY_SCHEDULED);
        assertThat(repeated.failure()).isEqualTo(FailureType.HTTP_DEFERRED);
        assertThat(tracking.bodyQueries()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                + " WHERE kind='CATALOGUE_SCHEDULE' AND state IN ('PENDING','LEASED')", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                + " WHERE kind='CATALOGUE_SCHEDULE' AND coalescing_key IN (?,?)",
                Integer.class, CatalogueScheduleDispatcher.CATALOGUE_PRICE_COALESCING_KEY,
                CatalogueScheduleDispatcher.SCHEDULE_COALESCING_KEY)).isEqualTo(2);
    }

    @Test
    void blockedManifestWakesCurrentPublishedParentWithoutItsExactFinishMarker() {
        TrackingJdbcTemplate tracking = new TrackingJdbcTemplate(jdbc.getDataSource());
        OwnedSyncStore.RuntimeFence fence = new PlannerRuntimeRepository(jdbc, tx, clock, TABLES)
                .activate(SEASON.code(), OwnedSyncMode.ACTIVE);
        store = new OwnedSyncStore(tracking, tx, clock, TABLES, fence);
        dispatcher = new HistoryFixtureDispatcher(store, publisher(), fetch, clock);
        seedCurrentCanonicalPair();
        assertThat(store.currentCanonicalParentEvidence(SEASON.code(), 21, clock.instant()))
                .isEqualTo(new OwnedSyncStore.CanonicalParentEvidence(true, false));

        FixtureManifestSeed seed = seedBlockedManifest();
        jdbc.update("INSERT INTO season(id, code, name, start_date, end_date, is_current)"
                        + " VALUES (27, '2026-27', 'Runtime test', '2026-08-21', '2027-05-23', false)"
                        + " ON CONFLICT (id) DO NOTHING");
        Instant transportDue = clock.instant().plus(Duration.ofHours(1));
        jdbc.update("INSERT INTO " + TABLES.fixtureSync()
                        + "(season_id,season_code,fixture_id,state,first_finished_observed_at,next_due_at,"
                        + "due_reason,transport_retry_at,alert_state,updated_at)"
                        + " VALUES (27,'2026-27',21,'WAITING_STATS',?,?,'TRANSPORT',?,'DAILY_ONLY',?)",
                java.sql.Timestamp.from(clock.instant().minus(Duration.ofHours(13))),
                java.sql.Timestamp.from(transportDue), java.sql.Timestamp.from(transportDue),
                java.sql.Timestamp.from(clock.instant()));
        Instant canonicalFloor = clock.instant().plus(Duration.ofHours(2));
        store.enqueue(SyncKind.CATALOGUE_SCHEDULE, SEASON.code(),
                CatalogueScheduleDispatcher.CATALOGUE_PRICE_COALESCING_KEY,
                CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY, canonicalFloor);
        store.enqueue(SyncKind.CATALOGUE_SCHEDULE, SEASON.code(),
                CatalogueScheduleDispatcher.SCHEDULE_COALESCING_KEY,
                CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY, canonicalFloor);
        dispatcher.enqueuePersistedFixtureRetry(SEASON, 21, clock.instant());

        tracking.resetBodyQueries();
        DispatchResult blocked = dispatcher.process(acquire(seed.generation()), SEASON);

        assertThat(blocked.outcome()).isEqualTo(Outcome.RETRY_SCHEDULED);
        assertThat(blocked.failure()).isEqualTo(FailureType.HTTP_DEFERRED);
        assertThat(fetch.endpoints).isEmpty();
        assertThat(tracking.bodyQueries()).isZero();
        assertThat(tracking.snapshot().bodyValues()).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(DISTINCT coalescing_key) FROM %s
                WHERE kind='CATALOGUE_SCHEDULE' AND coalescing_key IN (?,?) AND state='PENDING'
                  AND due_at=? AND requested_source_after=? AND runtime_mode='ACTIVE' AND runtime_epoch=?
                  AND lease_token IS NULL
                """.formatted(TABLES.run()), Integer.class,
                CatalogueScheduleDispatcher.CATALOGUE_PRICE_COALESCING_KEY,
                CatalogueScheduleDispatcher.SCHEDULE_COALESCING_KEY,
                java.sql.Timestamp.from(clock.instant()), java.sql.Timestamp.from(canonicalFloor), fence.epoch()))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT next_due_at FROM " + TABLES.fixtureSync()
                + " WHERE season_id=27 AND fixture_id=21", java.sql.Timestamp.class).toInstant())
                .isEqualTo(transportDue);
    }

    @Test
    void blockedManifestAdmitsAgainstFreshFallbackParentAfterPendingDonorDemandAdvances() {
        TrackingJdbcTemplate tracking = new TrackingJdbcTemplate(jdbc.getDataSource());
        OwnedSyncStore.RuntimeFence fence = new PlannerRuntimeRepository(jdbc, tx, clock, TABLES)
                .activate(SEASON.code(), OwnedSyncMode.ACTIVE);
        store = new OwnedSyncStore(tracking, tx, clock, TABLES, fence);
        dispatcher = new HistoryFixtureDispatcher(store, publisher(), fetch, clock);
        LaneReuse stale = seedPendingDonorPublishedPair();
        PlannerInputRepository inputs = new PlannerInputRepository(jdbc, store, TABLES, CAPTURED, clock);
        inputs.load(SEASON);

        Instant donorDemand = clock.instant().plusSeconds(10);
        Run coalescedDonor = store.enqueue(SyncKind.CATALOGUE_SCHEDULE, SEASON.code(),
                CatalogueScheduleDispatcher.SCHEDULE_COALESCING_KEY,
                CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY, donorDemand);
        assertThat(coalescedDonor.generation()).isEqualTo(stale.sourceGeneration());
        assertThat(store.currentPublishedCanonicalPair(SEASON.code(), stale.sourcePair(), clock.instant())).isEmpty();
        assertThat(store.currentCanonicalParentEvidence(SEASON.code(), 21, clock.instant()))
                .isEqualTo(OwnedSyncStore.CanonicalParentEvidence.MISSING);
        clock.advance(Duration.between(clock.instant(), donorDemand));

        Lease freshPublisher = acquire(stale.sourceGeneration());
        Run freshSource = store.enqueue(SyncKind.CATALOGUE_SCHEDULE, SEASON.code(),
                CatalogueScheduleDispatcher.CATALOGUE_PRICE_COALESCING_KEY,
                CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY, clock.instant());
        Lease freshSourceLease = acquire(freshSource.generation());
        SourceRead freshRead = readNow();
        long bootstrapId = store.stage(freshSourceLease, Endpoint.bootstrap(), freshRead,
                Validation.valid(), bootstrap);
        long fixturesId = store.stage(freshSourceLease, Endpoint.fixtures(), freshRead,
                Validation.valid(), fixtures);
        store.completeStaged(freshSourceLease, freshRead, List.of(freshRead, freshRead));
        var fallbackPair = OwnedSyncStore.CanonicalSourcePair.from(
                new StagedPayload(bootstrapId, freshSource.generation(),
                        OwnedSyncStore.EndpointKind.BOOTSTRAP_STATIC, freshRead,
                        ValidationState.VALID, null, bootstrap),
                new StagedPayload(fixturesId, freshSource.generation(),
                        OwnedSyncStore.EndpointKind.FIXTURES, freshRead,
                        ValidationState.VALID, null, fixtures));
        store.publish(freshPublisher, freshRead, List.of(freshRead, freshRead), fallbackPair, () -> null);

        var selected = store.findInLatestCurrentPublishedCanonicalPairs(SEASON.code(), 20, clock.instant(),
                candidate -> java.util.Optional.of(candidate));
        assertThat(selected).isPresent();
        assertThat(selected.orElseThrow().sourcePair()).isEqualTo(fallbackPair);
        assertThat(selected.orElseThrow().publication().sourceGeneration()).isEqualTo(freshSource.generation());
        assertThat(selected.orElseThrow().publication().publicationGeneration()).isEqualTo(stale.sourceGeneration());
        inputs.load(SEASON);
        assertThat(jdbc.queryForObject("SELECT finished_parent_generation FROM " + TABLES.fixtureSync()
                + " WHERE season_id=? AND fixture_id=21", Long.class, SEASON.id()))
                .isEqualTo(freshSource.generation());
        assertThat(jdbc.queryForObject("SELECT finished_parent_publication_generation FROM "
                + TABLES.fixtureSync() + " WHERE season_id=? AND fixture_id=21", Long.class, SEASON.id()))
                .isEqualTo(stale.sourceGeneration());
        assertThat(store.currentCanonicalParentEvidence(SEASON.code(), 21, clock.instant()))
                .isEqualTo(new OwnedSyncStore.CanonicalParentEvidence(true, true));

        Instant firstFinished = clock.instant().minus(Duration.ofHours(13));
        jdbc.update("UPDATE " + TABLES.fixtureSync() + " SET state='WAITING_STATS', first_finished_observed_at=?,"
                        + " next_due_at=NULL, due_reason=NULL, transport_retry_at=NULL, dependency_retry_at=NULL,"
                        + " recovery_day=NULL, recovery_started_at=NULL, recovery_until=NULL"
                        + " WHERE season_id=? AND fixture_id=21",
                java.sql.Timestamp.from(firstFinished), SEASON.id());
        FixtureManifestSeed manifest = seedBlockedManifest();
        assertThat(store.currentCanonicalParentEvidence(SEASON.code(), 21, clock.instant()))
                .isEqualTo(new OwnedSyncStore.CanonicalParentEvidence(true, true));

        tracking.resetBodyQueries();
        fetch.endpoints.clear();
        DispatchResult blocked = dispatcher.process(acquire(manifest.generation()), SEASON);

        assertThat(blocked.outcome()).isEqualTo(Outcome.WAITING_FOR_HISTORIES);
        assertThat(fetch.endpoints).isEmpty();
        assertThat(tracking.bodyQueries()).isZero();
        assertThat(tracking.snapshot().bodyValues()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                + " WHERE kind='CATALOGUE_SCHEDULE' AND coalescing_key IN (?,?)"
                + " AND state IN ('PENDING','LEASED')", Integer.class,
                CatalogueScheduleDispatcher.CATALOGUE_PRICE_COALESCING_KEY,
                CatalogueScheduleDispatcher.SCHEDULE_COALESCING_KEY)).isZero();
        assertThat(jdbc.queryForObject("SELECT recovery_started_at FROM " + TABLES.fixtureSync()
                + " WHERE season_id=? AND fixture_id=21", java.sql.Timestamp.class, SEASON.id())).isNotNull();
    }

    @Test
    void blockedManifestUsesFreshestFinishedParentDespiteLaterOlderPublication() {
        TrackingJdbcTemplate tracking = new TrackingJdbcTemplate(jdbc.getDataSource());
        OwnedSyncStore.RuntimeFence fence = new PlannerRuntimeRepository(jdbc, tx, clock, TABLES)
                .activate(SEASON.code(), OwnedSyncMode.ACTIVE);
        store = new OwnedSyncStore(tracking, tx, clock, TABLES, fence);
        dispatcher = new HistoryFixtureDispatcher(store, publisher(), fetch, clock);
        Instant base = clock.instant();
        LaneReuse older = seedPublishedParent(unfinishedFixture21, CatalogueLane.PRICE,
                CatalogueLane.SCHEDULE, false, base.plusSeconds(10));
        LaneReuse fresher = seedPublishedParent(fixtures, CatalogueLane.SCHEDULE,
                CatalogueLane.PRICE, true, base.plusSeconds(60));
        jdbc.update("UPDATE " + TABLES.run() + " SET completed_at=? WHERE generation=?",
                java.sql.Timestamp.from(base.plusSeconds(90)), older.publicationGeneration());
        jdbc.update("UPDATE " + TABLES.receipt() + " SET published_at=? WHERE season=? AND publication_key=?",
                java.sql.Timestamp.from(base.plusSeconds(100)), SEASON.code(),
                CatalogueLane.SCHEDULE.publicationKey());
        jdbc.update("UPDATE " + TABLES.run() + " SET requested_source_after=? WHERE generation IN (?,?,?,?)",
                java.sql.Timestamp.from(base), older.sourceGeneration(), older.publicationGeneration(),
                fresher.sourceGeneration(), fresher.publicationGeneration());
        clock.set(base.plusSeconds(200));
        assertThat(older.sourcePair().fixtures().read().finishedAt())
                .isBefore(fresher.sourcePair().fixtures().read().finishedAt());
        assertThat(jdbc.queryForObject("SELECT published_at FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", java.sql.Timestamp.class, SEASON.code(),
                CatalogueLane.PRICE.publicationKey()).toInstant()).isBefore(base.plusSeconds(100));
        assertThat(jdbc.queryForObject("SELECT published_at FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", java.sql.Timestamp.class, SEASON.code(),
                CatalogueLane.SCHEDULE.publicationKey()).toInstant()).isAfter(base.plusSeconds(90));
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.run() + " WHERE generation=?",
                String.class, fresher.sourceGeneration())).isEqualTo("PENDING");
        assertThat(store.currentPublishedCanonicalPair(SEASON.code(), older.sourcePair(), clock.instant())).isPresent();
        assertThat(store.currentPublishedCanonicalPair(SEASON.code(), fresher.sourcePair(), clock.instant())).isPresent();

        var selected = store.findInLatestCurrentPublishedCanonicalPairs(SEASON.code(), 20, clock.instant(),
                candidate -> java.util.Optional.of(candidate)).orElseThrow();
        assertThat(selected.sourcePair()).isEqualTo(fresher.sourcePair());
        assertThat(selected.publication().sourceGeneration()).isEqualTo(fresher.sourceGeneration());
        assertThat(selected.publication().publicationGeneration()).isEqualTo(fresher.publicationGeneration());
        new PlannerInputRepository(jdbc, store, TABLES, CAPTURED, clock).load(SEASON);
        assertThat(store.currentCanonicalParentEvidence(SEASON.code(), 21, clock.instant()))
                .isEqualTo(new OwnedSyncStore.CanonicalParentEvidence(true, true));
        jdbc.update("UPDATE " + TABLES.fixtureSync() + " SET state='WAITING_STATS', first_finished_observed_at=?,"
                        + " next_due_at=NULL, due_reason=NULL, transport_retry_at=NULL, dependency_retry_at=NULL,"
                        + " recovery_day=NULL, recovery_started_at=NULL, recovery_until=NULL"
                        + " WHERE season_id=? AND fixture_id=21",
                java.sql.Timestamp.from(clock.instant().minus(Duration.ofHours(13))), SEASON.id());
        FixtureManifestSeed manifest = seedBlockedManifest();
        assertThat(store.currentCanonicalParentEvidence(SEASON.code(), 21, clock.instant()))
                .isEqualTo(new OwnedSyncStore.CanonicalParentEvidence(true, true));
        int pendingCanonicalBefore = jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                + " WHERE kind='CATALOGUE_SCHEDULE' AND coalescing_key IN (?,?)"
                + " AND state IN ('PENDING','LEASED')", Integer.class,
                CatalogueScheduleDispatcher.CATALOGUE_PRICE_COALESCING_KEY,
                CatalogueScheduleDispatcher.SCHEDULE_COALESCING_KEY);
        java.sql.Timestamp donorDueBefore = jdbc.queryForObject("SELECT due_at FROM " + TABLES.run()
                + " WHERE generation=?", java.sql.Timestamp.class, fresher.sourceGeneration());
        java.sql.Timestamp donorFloorBefore = jdbc.queryForObject("SELECT requested_source_after FROM "
                + TABLES.run() + " WHERE generation=?", java.sql.Timestamp.class, fresher.sourceGeneration());

        tracking.resetBodyQueries();
        fetch.endpoints.clear();
        DispatchResult blocked = dispatcher.process(acquire(manifest.generation()), SEASON);

        assertThat(blocked.outcome()).isEqualTo(Outcome.WAITING_FOR_HISTORIES);
        assertThat(fetch.endpoints).isEmpty();
        assertThat(tracking.bodyQueries()).isZero();
        assertThat(tracking.snapshot().bodyValues()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                + " WHERE kind='CATALOGUE_SCHEDULE' AND coalescing_key IN (?,?)"
                + " AND state IN ('PENDING','LEASED')", Integer.class,
                CatalogueScheduleDispatcher.CATALOGUE_PRICE_COALESCING_KEY,
                CatalogueScheduleDispatcher.SCHEDULE_COALESCING_KEY)).isEqualTo(pendingCanonicalBefore);
        assertThat(jdbc.queryForObject("SELECT due_at FROM " + TABLES.run() + " WHERE generation=?",
                java.sql.Timestamp.class, fresher.sourceGeneration())).isEqualTo(donorDueBefore);
        assertThat(jdbc.queryForObject("SELECT requested_source_after FROM " + TABLES.run()
                + " WHERE generation=?", java.sql.Timestamp.class, fresher.sourceGeneration()))
                .isEqualTo(donorFloorBefore);
        assertThat(jdbc.queryForObject("SELECT recovery_started_at FROM " + TABLES.fixtureSync()
                + " WHERE season_id=? AND fixture_id=21", java.sql.Timestamp.class, SEASON.id())).isNotNull();
    }

    @Test
    void blockedManifestCreatesBothCanonicalJobsWhenNeitherExists() {
        TrackingJdbcTemplate tracking = new TrackingJdbcTemplate(jdbc.getDataSource());
        OwnedSyncStore.RuntimeFence fence = new PlannerRuntimeRepository(jdbc, tx, clock, TABLES)
                .activate(SEASON.code(), OwnedSyncMode.ACTIVE);
        store = new OwnedSyncStore(tracking, tx, clock, TABLES, fence);
        dispatcher = new HistoryFixtureDispatcher(store, publisher(), fetch, clock);
        seedCurrentCanonicalPair();
        assertThat(store.currentCanonicalParentEvidence(SEASON.code(), 21, clock.instant()))
                .isEqualTo(new OwnedSyncStore.CanonicalParentEvidence(true, false));

        FixtureManifestSeed seed = seedBlockedManifest();
        jdbc.update("INSERT INTO season(id, code, name, start_date, end_date, is_current)"
                        + " VALUES (27, '2026-27', 'Runtime test', '2026-08-21', '2027-05-23', false)"
                        + " ON CONFLICT (id) DO NOTHING");
        Instant transportDue = clock.instant().plus(Duration.ofHours(1));
        jdbc.update("INSERT INTO " + TABLES.fixtureSync()
                        + "(season_id,season_code,fixture_id,state,first_finished_observed_at,next_due_at,"
                        + "due_reason,transport_retry_at,alert_state,updated_at)"
                        + " VALUES (27,'2026-27',21,'WAITING_STATS',?,?,'TRANSPORT',?,'DAILY_ONLY',?)",
                java.sql.Timestamp.from(clock.instant().minus(Duration.ofHours(13))),
                java.sql.Timestamp.from(transportDue), java.sql.Timestamp.from(transportDue),
                java.sql.Timestamp.from(clock.instant()));
        dispatcher.enqueuePersistedFixtureRetry(SEASON, 21, clock.instant());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                + " WHERE kind='CATALOGUE_SCHEDULE' AND coalescing_key IN (?,?)"
                + " AND state IN ('PENDING','LEASED')", Integer.class,
                CatalogueScheduleDispatcher.CATALOGUE_PRICE_COALESCING_KEY,
                CatalogueScheduleDispatcher.SCHEDULE_COALESCING_KEY)).isZero();

        tracking.resetBodyQueries();
        DispatchResult blocked = dispatcher.process(acquire(seed.generation()), SEASON);

        assertThat(blocked.outcome()).isEqualTo(Outcome.RETRY_SCHEDULED);
        assertThat(blocked.failure()).isEqualTo(FailureType.HTTP_DEFERRED);
        assertThat(fetch.endpoints).isEmpty();
        assertThat(tracking.bodyQueries()).isZero();
        assertThat(tracking.snapshot().bodyValues()).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(DISTINCT coalescing_key) FROM %s
                WHERE kind='CATALOGUE_SCHEDULE' AND coalescing_key IN (?,?) AND state='PENDING'
                  AND due_at=? AND requested_source_after=? AND runtime_mode='ACTIVE' AND runtime_epoch=?
                  AND lease_token IS NULL
                """.formatted(TABLES.run()), Integer.class,
                CatalogueScheduleDispatcher.CATALOGUE_PRICE_COALESCING_KEY,
                CatalogueScheduleDispatcher.SCHEDULE_COALESCING_KEY,
                java.sql.Timestamp.from(clock.instant()), java.sql.Timestamp.from(clock.instant()), fence.epoch()))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT next_due_at FROM " + TABLES.fixtureSync()
                + " WHERE season_id=27 AND fixture_id=21", java.sql.Timestamp.class).toInstant())
                .isEqualTo(transportDue);
        assertThat(jdbc.queryForObject("SELECT transport_retry_at FROM " + TABLES.fixtureSync()
                + " WHERE season_id=27 AND fixture_id=21", java.sql.Timestamp.class).toInstant())
                .isEqualTo(transportDue);
    }

    @Test
    void blockedManifestRetryKeepsAdmissionCooldownOnFixtureRunDue() {
        activateStore(OwnedSyncMode.ACTIVE);
        jdbc.update("INSERT INTO season(id, code, name, start_date, end_date, is_current)"
                        + " VALUES (27, '2026-27', 'Runtime test', '2026-08-21', '2027-05-23', false)"
                        + " ON CONFLICT (id) DO NOTHING");
        seedCurrentCanonicalPairReusedAcrossLanes();
        new PlannerInputRepository(jdbc, store, TABLES, CAPTURED, clock).load(SEASON);
        assertThat(store.currentCanonicalParentEvidence(SEASON.code(), 21, clock.instant()))
                .isEqualTo(new OwnedSyncStore.CanonicalParentEvidence(true, true));

        FixtureManifestSeed seed = seedBlockedManifest();
        Instant retryAt = clock.instant().plus(Duration.ofMinutes(2));
        Instant admissionCooldown = clock.instant().plus(Duration.ofHours(1));
        jdbc.update("UPDATE " + TABLES.fixtureSync() + " SET first_finished_observed_at=?, next_due_at=NULL,"
                        + " due_reason=NULL, dependency_retry_at=NULL, transport_retry_at=NULL, recovery_day=NULL,"
                        + " recovery_started_at=NULL, recovery_until=NULL WHERE season_id=? AND fixture_id=21",
                java.sql.Timestamp.from(clock.instant().minus(Duration.ofHours(13))), SEASON.id());
        jdbc.update("UPDATE " + TABLES.requestState() + " SET cooldown_until=? WHERE singleton=TRUE",
                java.sql.Timestamp.from(admissionCooldown));
        Lease retryLease = acquire(seed.generation());

        DispatchResult result = dispatcher.process(retryLease, SEASON);

        assertThat(result.outcome()).isEqualTo(Outcome.RETRY_SCHEDULED);
        assertThat(result.failure()).isEqualTo(FailureType.HTTP_DEFERRED);
        assertThat(jdbc.queryForObject("SELECT due_at FROM " + TABLES.run()
                + " WHERE generation=?", java.sql.Timestamp.class, seed.generation()).toInstant())
                .isEqualTo(admissionCooldown);
        assertThat(jdbc.queryForObject("SELECT dependency_retry_at FROM " + TABLES.fixtureSync()
                + " WHERE season_id=? AND fixture_id=21", java.sql.Timestamp.class, SEASON.id()).toInstant())
                .isEqualTo(retryAt);
        assertThat(jdbc.queryForObject("SELECT due_reason FROM " + TABLES.fixtureSync()
                + " WHERE season_id=? AND fixture_id=21", String.class, SEASON.id())).isEqualTo("DEPENDENCY");

        clock.advance(Duration.ofMinutes(2));
        assertThat(store.acquire("before-admission-cooldown", Duration.ofMinutes(5))
                .filter(lease -> lease.generation() == seed.generation())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT due_at FROM " + TABLES.run()
                + " WHERE generation=?", java.sql.Timestamp.class, seed.generation()).toInstant())
                .isEqualTo(admissionCooldown);
    }

    @Test
    void obsoleteOrBrokenManifestFallsBackToFreshConstruction() {
        List<ManifestMutation> mutations = List.of(
                new ManifestMutation("invalid bootstrap", (seed) -> jdbc.update("UPDATE " + TABLES.payload()
                        + " SET validation_state='INVALID' WHERE id=?", seed.bootstrapId()), Endpoint.bootstrap()),
                new ManifestMutation("deleted bootstrap", (seed) -> jdbc.update("DELETE FROM " + TABLES.payload()
                        + " WHERE id=?", seed.bootstrapId()), Endpoint.bootstrap()),
                new ManifestMutation("broken live event", (seed) -> jdbc.update("UPDATE " + TABLES.payload()
                        + " SET event_id=4 WHERE id=?", seed.liveId()), Endpoint.eventLive(3)),
                new ManifestMutation("advanced source demand", (seed) -> dispatcher.enqueueFixtureVerification(
                        SEASON, 21, clock.instant().plus(Duration.ofHours(1))), Endpoint.bootstrap()));

        for (ManifestMutation mutation : mutations) {
            setUp();
            FixtureManifestSeed seed = seedBlockedManifest();
            Lease retryLease = acquire(seed.generation());
            assertThat(store.fixtureDependencyManifest(retryLease, 21)).isPresent();
            mutation.mutate().accept(seed);
            assertThat(store.fixtureDependencyManifest(retryLease, 21)).as(mutation.label()).isEmpty();
            fetch.results.add(new DeferredResult(clock.instant().plusSeconds(10), DeferredReason.MINIMUM_SPACING));

            DispatchResult result = dispatcher.process(retryLease, SEASON);

            assertThat(result.outcome()).as(mutation.label()).isEqualTo(Outcome.RETRY_SCHEDULED);
            assertThat(fetch.endpoints).as(mutation.label()).containsExactly(mutation.expectedFetch());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                    + " WHERE generation=? AND state='PENDING'", Integer.class, seed.generation())).isOne();
        }
    }

    @Test
    void publicationRejectsHistoryInvalidatedAfterReadyMetadataWasRead() throws Exception {
        var fixtureRun = dispatcher.enqueueFixtureVerification(SEASON, 21, clock.instant());
        Lease fixtureLease = acquire(fixtureRun.generation());
        SourceRead pairRead = readNow();
        long bootstrapId = store.stage(fixtureLease, Endpoint.bootstrap(), pairRead,
                Validation.valid(), bootstrap);
        long fixturesId = store.stage(fixtureLease, Endpoint.fixtures(), pairRead,
                Validation.valid(), fixtures);
        clock.advance(Duration.ofMillis(1));
        SourceRead liveRead = readNow();
        long liveId = store.stage(fixtureLease, Endpoint.eventLive(3), liveRead,
                Validation.valid(), liveThree);
        var historyRun = dispatcher.enqueuePlayerHistory(SEASON, 1, clock.instant());
        Lease historyLease = acquire(historyRun.generation());
        SourceRead historyRead = readNow();
        long historyId = store.stage(historyLease, Endpoint.elementSummary(1), historyRead,
                Validation.valid(), historyOne);
        store.completeStaged(historyLease, historyRead, List.of(historyRead));
        store.retryForDependency(fixtureLease, clock.instant().plus(Duration.ofMinutes(2)),
                "Waiting for stable fixture evidence", 21, 3,
                new OwnedSyncStore.PublicPayloadPair(fixtureRun.generation(),
                        new OwnedSyncStore.StagedPayload(bootstrapId, fixtureRun.generation(),
                                OwnedSyncStore.EndpointKind.BOOTSTRAP_STATIC, pairRead,
                                ValidationState.VALID, null, bootstrap),
                        new OwnedSyncStore.StagedPayload(fixturesId, fixtureRun.generation(),
                                OwnedSyncStore.EndpointKind.FIXTURES, pairRead,
                                ValidationState.VALID, null, fixtures)),
                new OwnedSyncStore.StagedPayload(liveId, fixtureRun.generation(),
                        OwnedSyncStore.EndpointKind.EVENT_LIVE, liveRead, ValidationState.VALID,
                        null, liveThree), List.of(1));
        clock.advance(Duration.ofMinutes(2));
        Lease readyLease = acquire(fixtureRun.generation());
        FixtureDependencyManifest manifest = store.fixtureDependencyManifest(readyLease, 21).orElseThrow();
        assertThat(manifest.missingPlayerIds()).isEmpty();
        StagedPayloadMetadata historyMetadata = store.latestValidPayloadMetadata(SEASON.code(),
                Endpoint.elementSummary(1), manifest.effectiveFloor()).orElseThrow();
        jdbc.update("UPDATE " + TABLES.payload()
                + " SET validation_state='INVALID' WHERE id=?", historyId);

        var interval = new CatalogueScheduleParser.ReadInterval(historyRead.startedAt(), historyRead.finishedAt());
        var source = new CatalogueScheduleParser.SourceDocument(historyOne, interval);
        assertThatThrownBy(() -> publisher().publish(readyLease, SEASON, 21,
                new CatalogueScheduleParser.SourceDocument(bootstrap,
                        new CatalogueScheduleParser.ReadInterval(pairRead.startedAt(), pairRead.finishedAt())),
                new CatalogueScheduleParser.SourceDocument(fixtures,
                        new CatalogueScheduleParser.ReadInterval(pairRead.startedAt(), pairRead.finishedAt())),
                new CatalogueScheduleParser.SourceDocument(liveThree,
                        new CatalogueScheduleParser.ReadInterval(liveRead.startedAt(), liveRead.finishedAt())),
                java.util.Map.of(1, source), manifest, java.util.Map.of(1, historyMetadata)))
                .isInstanceOf(OwnedSyncStore.FixtureEvidenceChangedException.class);
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.run()
                + " WHERE generation=?", String.class, fixtureRun.generation())).isEqualTo("LEASED");
        assertThat(jdbc.queryForObject("SELECT latest_generation FROM " + TABLES.receipt()
                + " WHERE season=? AND publication_key=?", Long.class, SEASON.code(), "fixture:21"))
                .isNull();
    }

    @Test
    void racedHistoryInvalidationReturnsNoPartialBodiesAndKeepsManifest() throws Exception {
        TrackingJdbcTemplate tracking = new TrackingJdbcTemplate(jdbc.getDataSource());
        OwnedSyncStore trackedStore = new OwnedSyncStore(tracking, tx, clock, TABLES);
        HistoryFixtureDispatcher staged = new HistoryFixtureDispatcher(trackedStore, null, fetch, clock, false);
        var fixtureRun = staged.enqueueFixtureVerification(SEASON, 21, clock.instant());
        Lease fixtureLease = trackedStore.acquire("fixture-worker", Duration.ofMinutes(5)).orElseThrow();
        SourceRead pairRead = readNow();
        long bootstrapId = trackedStore.stage(fixtureLease, Endpoint.bootstrap(), pairRead,
                Validation.valid(), bootstrap);
        long fixturesId = trackedStore.stage(fixtureLease, Endpoint.fixtures(), pairRead,
                Validation.valid(), fixtures);
        clock.advance(Duration.ofMillis(1));
        SourceRead liveRead = readNow();
        long liveId = trackedStore.stage(fixtureLease, Endpoint.eventLive(3), liveRead,
                Validation.valid(), liveThree);

        Set<Integer> playerIds = fixturePlayerIds(pairRead);
        SourceRead historyRead = readNow();
        for (int playerId : playerIds) {
            var historyRun = trackedStore.enqueue(SyncKind.PLAYER_HISTORY, SEASON.code(),
                    "player-history:" + playerId, "player-history:" + playerId, historyRead.startedAt());
            Lease historyLease = trackedStore.acquire("history-worker", Duration.ofMinutes(5)).orElseThrow();
            assertThat(historyLease.generation()).isEqualTo(historyRun.generation());
            trackedStore.stage(historyLease, Endpoint.elementSummary(playerId), historyRead,
                    Validation.valid(), historyOne);
            trackedStore.completeStaged(historyLease, historyRead, List.of(historyRead));
        }

        tracking.resetBodyQueries();
        tracking.invalidateNextHistoryBeforeBodyQuery(playerIds.iterator().next());
        DispatchResult result = staged.process(fixtureLease, SEASON);

        assertThat(result.outcome()).isEqualTo(Outcome.WAITING_FOR_HISTORIES);
        assertThat(tracking.invalidatedHistoryRows()).isOne();
        assertThat(tracking.bodyQueries()).isEqualTo(4);
        assertThat(tracking.snapshot().bodyFamily(PayloadReadProbe.BodyFamily.CROSS_RUN_HISTORIES))
                .isEqualTo(new PayloadReadProbe.FamilyMetrics(1, 1, 0, 0));
        assertThat(jdbc.query("SELECT bootstrap_payload_id, fixtures_payload_id, live_payload_id FROM "
                        + TABLES.fixtureDependencyManifest() + " WHERE run_generation=?",
                (rs, row) -> List.of(rs.getLong(1), rs.getLong(2), rs.getLong(3)), fixtureRun.generation()))
                .containsExactly(List.of(bootstrapId, fixturesId, liveId));
        Integer[] requiredPlayerIds = jdbc.queryForObject("SELECT required_player_ids FROM "
                        + TABLES.fixtureDependencyManifest() + " WHERE run_generation=?",
                (rs, row) -> (Integer[]) rs.getArray(1).getArray(), fixtureRun.generation());
        assertThat(requiredPlayerIds).containsExactlyElementsOf(playerIds.stream().sorted().toList());
    }

    @Test
    void finalRecheckDeferralKeepsValidHistoryFixtureReadyForRetry() throws Exception {
        HistoryFixtureDispatcher staged = new HistoryFixtureDispatcher(store, null, fetch, clock, false);
        var fixtureRun = staged.enqueueFixtureVerification(SEASON, 21, clock.instant());
        Lease fixtureLease = acquire(fixtureRun.generation());
        SourceRead pairRead = readNow();
        long bootstrapId = store.stage(fixtureLease, Endpoint.bootstrap(), pairRead,
                Validation.valid(), bootstrap);
        long fixturesId = store.stage(fixtureLease, Endpoint.fixtures(), pairRead,
                Validation.valid(), fixtures);
        clock.advance(Duration.ofMillis(1));
        SourceRead liveRead = readNow();
        long liveId = store.stage(fixtureLease, Endpoint.eventLive(3), liveRead,
                Validation.valid(), liveThree);
        Set<Integer> playerIds = fixturePlayerIds(pairRead);
        clock.advance(Duration.ofMillis(1));
        Instant historyFloor = pairRead.finishedAt().plusMillis(1);
        for (int playerId : playerIds) {
            var historyRun = store.enqueue(SyncKind.PLAYER_HISTORY, SEASON.code(),
                    "player-history:" + playerId, "player-history:" + playerId, historyFloor);
            Lease historyLease = acquire(historyRun.generation());
            SourceRead historyRead = readNow();
            store.stage(historyLease, Endpoint.elementSummary(playerId), historyRead,
                    Validation.valid(), historyOne);
            store.completeStaged(historyLease, historyRead, List.of(historyRead));
        }

        Instant deferredDue = clock.instant().plusSeconds(30);
        fetch.results.add(new DeferredResult(deferredDue, DeferredReason.MINIMUM_SPACING));
        DispatchResult result = staged.process(fixtureLease, SEASON);

        assertThat(result.outcome()).isEqualTo(Outcome.RETRY_SCHEDULED);
        assertThat(result.failure()).isEqualTo(FailureType.HTTP_DEFERRED);
        assertThat(fetch.endpoints).containsExactly(Endpoint.bootstrap());
        assertThat(jdbc.query("SELECT bootstrap_payload_id, fixtures_payload_id, live_payload_id FROM "
                        + TABLES.fixtureDependencyManifest() + " WHERE run_generation=?",
                (rs, row) -> List.of(rs.getLong(1), rs.getLong(2), rs.getLong(3)), fixtureRun.generation()))
                .containsExactly(List.of(bootstrapId, fixturesId, liveId));
        Integer[] requiredPlayerIds = jdbc.queryForObject("SELECT required_player_ids FROM "
                        + TABLES.fixtureDependencyManifest() + " WHERE run_generation=?",
                (rs, row) -> (Integer[]) rs.getArray(1).getArray(), fixtureRun.generation());
        assertThat(requiredPlayerIds).containsExactlyElementsOf(playerIds.stream().sorted().toList());
        assertThat(jdbc.queryForObject("SELECT state || ':' || (lease_token IS NULL)::text FROM "
                + TABLES.run() + " WHERE generation=?", String.class, fixtureRun.generation()))
                .isEqualTo("PENDING:true");
        assertThat(jdbc.queryForObject("SELECT due_at FROM " + TABLES.run()
                + " WHERE generation=?", java.sql.Timestamp.class, fixtureRun.generation()).toInstant())
                .isEqualTo(deferredDue);
        clock.advance(Duration.between(clock.instant(), deferredDue));
        assertThat(store.readyFixtureGenerations(SEASON.code(), 10).stream()
                .map(ready -> ready.generation()).toList())
                .contains(fixtureRun.generation());
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

        Instant persistedTransportDue = jdbc.queryForObject("SELECT next_due_at FROM " + TABLES.fixtureSync()
                + " WHERE fixture_id=21", java.sql.Timestamp.class).toInstant();
        clock.advance(Duration.between(clock.instant(),
                Instant.parse("2026-09-14T06:00:00Z")));
        int fetchedBefore = fetch.endpoints.size();
        DispatchResult capped = dispatcher.process(acquire(run.generation()), SEASON);
        assertThat(capped.outcome()).isEqualTo(Outcome.RETRY_SCHEDULED);
        assertThat(fetch.endpoints).hasSize(fetchedBefore);
        assertThat(jdbc.queryForObject("SELECT alert_state FROM " + TABLES.fixtureSync()
                + " WHERE fixture_id=21", String.class)).isEqualTo("NONE");
        assertThat(jdbc.queryForObject("SELECT next_due_at FROM " + TABLES.fixtureSync()
                + " WHERE fixture_id=21", java.sql.Timestamp.class).toInstant())
                .isEqualTo(persistedTransportDue);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM " + TABLES.fixtureSync()
                + " WHERE fixture_id=21", Integer.class)).isEqualTo(4);
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
        publishCanonicalPair(lease, readNow(), bootstrap, fixtures);
    }

    private void publishCanonicalPair(Lease lease, SourceRead read, byte[] bootstrapBody, byte[] fixturesBody) {
        long bootstrapId = store.stage(lease, Endpoint.bootstrap(), read, Validation.valid(), bootstrapBody);
        long fixturesId = store.stage(lease, Endpoint.fixtures(), read, Validation.valid(), fixturesBody);
        var sourcePair = OwnedSyncStore.CanonicalSourcePair.from(
                new StagedPayload(bootstrapId, lease.generation(), OwnedSyncStore.EndpointKind.BOOTSTRAP_STATIC,
                        read, ValidationState.VALID, null, bootstrapBody),
                new StagedPayload(fixturesId, lease.generation(), OwnedSyncStore.EndpointKind.FIXTURES,
                        read, ValidationState.VALID, null, fixturesBody));
        store.publish(lease, read, List.of(read, read), sourcePair, () -> null);
    }

    private void stagedPair(Lease lease, SourceRead read) {
        store.stage(lease, Endpoint.bootstrap(), read, Validation.valid(), bootstrap);
        store.stage(lease, Endpoint.fixtures(), read, Validation.valid(), fixtures);
    }

    private Set<Integer> fixturePlayerIds(SourceRead read) throws Exception {
        var interval = new CatalogueScheduleParser.ReadInterval(read.startedAt(), read.finishedAt());
        var sourceInterval = new CatalogueScheduleParser.SourceDocument(bootstrap, interval);
        var scheduleInterval = new CatalogueScheduleParser.SourceDocument(fixtures, interval);
        var snapshot = CatalogueScheduleParser.parse(SEASON, sourceInterval, scheduleInterval);
        var fixture = snapshot.fixtures().stream().filter(row -> row.id() == 21).findFirst().orElseThrow();
        Set<Integer> playerIds = new LinkedHashSet<>();
        snapshot.players().stream().filter(player -> player.teamId() == fixture.homeTeamId()
                        || player.teamId() == fixture.awayTeamId())
                .map(CatalogueScheduleParser.PlayerRow::id).forEach(playerIds::add);
        playerIds.addAll(FixtureVerification.participantIds(fixture,
                new CatalogueScheduleParser.SourceDocument(liveThree, interval)));
        return playerIds;
    }

    private long seedCurrentCanonicalPair() {
        var run = store.enqueue(SyncKind.CATALOGUE_SCHEDULE, SEASON.code(),
                CatalogueScheduleDispatcher.SCHEDULE_COALESCING_KEY,
                CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY, clock.instant());
        Lease lease = acquire(run.generation());
        SourceRead read = readNow();
        publishCanonicalPair(lease, read, bootstrap, fixtures);
        return run.generation();
    }

    private LaneReuse seedPublishedParent(byte[] fixtureBody, CatalogueLane sourceLane,
                                          CatalogueLane publicationLane, boolean pendingDonor,
                                          Instant readStarted) {
        if (sourceLane == publicationLane) throw new IllegalArgumentException("source and publisher lanes differ");
        clock.set(readStarted.minusSeconds(10));
        Run sourceRun = store.enqueue(SyncKind.CATALOGUE_SCHEDULE, SEASON.code(), sourceLane.coalescingKey(),
                sourceLane.publicationKey(), clock.instant());
        Run publicationRun = store.enqueue(SyncKind.CATALOGUE_SCHEDULE, SEASON.code(),
                publicationLane.coalescingKey(), publicationLane.publicationKey(), clock.instant());
        Lease sourceLease = acquire(sourceRun.generation());
        SourceRead bootstrapRead = new SourceRead(readStarted, readStarted.plusMillis(100));
        SourceRead fixturesRead = new SourceRead(readStarted.plusSeconds(2), readStarted.plusMillis(2100));
        clock.set(fixturesRead.finishedAt().plusMillis(1));
        long bootstrapId = store.stage(sourceLease, Endpoint.bootstrap(), bootstrapRead,
                Validation.valid(), bootstrap);
        long fixturesId = store.stage(sourceLease, Endpoint.fixtures(), fixturesRead,
                Validation.valid(), fixtureBody);
        var sourcePair = OwnedSyncStore.CanonicalSourcePair.from(
                new StagedPayload(bootstrapId, sourceRun.generation(), OwnedSyncStore.EndpointKind.BOOTSTRAP_STATIC,
                        bootstrapRead, ValidationState.VALID, null, bootstrap),
                new StagedPayload(fixturesId, sourceRun.generation(), OwnedSyncStore.EndpointKind.FIXTURES,
                        fixturesRead, ValidationState.VALID, null, fixtureBody));
        SourceRead combined = new SourceRead(bootstrapRead.startedAt(), fixturesRead.finishedAt());
        if (pendingDonor) {
            store.retry(sourceLease, clock.instant().plus(Duration.ofDays(1)), "valid pending source donor");
        } else {
            store.completeStaged(sourceLease, combined, List.of(bootstrapRead, fixturesRead));
        }
        Lease publicationLease = acquire(publicationRun.generation());
        store.publish(publicationLease, combined, List.of(bootstrapRead, fixturesRead), sourcePair, () -> null);
        return new LaneReuse(sourceRun.generation(), publicationRun.generation(), sourcePair);
    }

    private LaneReuse seedPendingDonorPublishedPair() {
        Run sourceRun = store.enqueue(SyncKind.CATALOGUE_SCHEDULE, SEASON.code(),
                CatalogueScheduleDispatcher.SCHEDULE_COALESCING_KEY,
                CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY, clock.instant());
        Run publicationRun = store.enqueue(SyncKind.CATALOGUE_SCHEDULE, SEASON.code(),
                CatalogueScheduleDispatcher.CATALOGUE_PRICE_COALESCING_KEY,
                CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY, clock.instant());
        Lease sourceLease = acquire(sourceRun.generation());
        SourceRead read = readNow();
        long bootstrapId = store.stage(sourceLease, Endpoint.bootstrap(), read, Validation.valid(), bootstrap);
        long fixturesId = store.stage(sourceLease, Endpoint.fixtures(), read, Validation.valid(), fixtures);
        var sourcePair = OwnedSyncStore.CanonicalSourcePair.from(
                new StagedPayload(bootstrapId, sourceRun.generation(), OwnedSyncStore.EndpointKind.BOOTSTRAP_STATIC,
                        read, ValidationState.VALID, null, bootstrap),
                new StagedPayload(fixturesId, sourceRun.generation(), OwnedSyncStore.EndpointKind.FIXTURES,
                        read, ValidationState.VALID, null, fixtures));
        store.retry(sourceLease, clock.instant().plusSeconds(1), "valid pending source donor");
        Lease publicationLease = acquire(publicationRun.generation());
        store.publish(publicationLease, read, List.of(read, read), sourcePair, () -> null);
        return new LaneReuse(sourceRun.generation(), publicationRun.generation(), sourcePair);
    }

    private LaneReuse seedCurrentCanonicalPairReusedAcrossLanes() {
        var sourceRun = store.enqueue(SyncKind.CATALOGUE_SCHEDULE, SEASON.code(),
                CatalogueScheduleDispatcher.CATALOGUE_PRICE_COALESCING_KEY,
                CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY, clock.instant());
        Lease sourceLease = acquire(sourceRun.generation());
        SourceRead read = readNow();
        long bootstrapId = store.stage(sourceLease, Endpoint.bootstrap(), read, Validation.valid(), bootstrap);
        long fixturesId = store.stage(sourceLease, Endpoint.fixtures(), read, Validation.valid(), fixtures);
        var sourcePair = OwnedSyncStore.CanonicalSourcePair.from(
                new StagedPayload(bootstrapId, sourceRun.generation(), OwnedSyncStore.EndpointKind.BOOTSTRAP_STATIC,
                        read, ValidationState.VALID, null, bootstrap),
                new StagedPayload(fixturesId, sourceRun.generation(), OwnedSyncStore.EndpointKind.FIXTURES,
                        read, ValidationState.VALID, null, fixtures));
        store.publish(sourceLease, read, List.of(read, read), sourcePair, () -> null);
        var persisted = store.findInLatestValidPublicPayloadPairs(SEASON.code(), 20,
                pair -> java.util.Optional.of(pair)).orElseThrow();
        sourcePair = OwnedSyncStore.CanonicalSourcePair.from(persisted.bootstrap(), persisted.fixtures());

        var publicationRun = store.enqueue(SyncKind.CATALOGUE_SCHEDULE, SEASON.code(),
                CatalogueScheduleDispatcher.SCHEDULE_COALESCING_KEY,
                CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY, clock.instant());
        Lease publicationLease = acquire(publicationRun.generation());
        store.publish(publicationLease, read, List.of(read, read), sourcePair, () -> null);
        return new LaneReuse(sourceRun.generation(), publicationRun.generation(), sourcePair);
    }

    private void seedUnpublishedCanonicalPair() {
        var run = store.enqueue(SyncKind.CATALOGUE_SCHEDULE, SEASON.code(),
                CatalogueScheduleDispatcher.SCHEDULE_COALESCING_KEY,
                CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY, clock.instant());
        Lease lease = acquire(run.generation());
        SourceRead read = readNow();
        store.stage(lease, Endpoint.bootstrap(), read, Validation.valid(), bootstrap);
        store.stage(lease, Endpoint.fixtures(), read, Validation.valid(), fixtures);
        store.completeStaged(lease, read, List.of(read));
    }

    private void writeLegacyDependencyWait(int fixtureId, Instant dueAt) {
        // This is the pre-typed recordDependencyWait write: it changed only next_due_at and error metadata.
        jdbc.update("UPDATE " + TABLES.fixtureSync()
                        + " SET next_due_at=?, last_error=?, updated_at=? WHERE season_code=? AND fixture_id=?",
                java.sql.Timestamp.from(dueAt), "legacy dependency wait", java.sql.Timestamp.from(clock.instant()),
                SEASON.code(), fixtureId);
        jdbc.update("UPDATE " + TABLES.fixtureSync()
                        + " SET due_reason=NULL, transport_retry_at=NULL, dependency_retry_at=NULL"
                        + " WHERE season_code=? AND fixture_id=?",
                SEASON.code(), fixtureId);
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
    private record LaneReuse(long sourceGeneration, long publicationGeneration,
                             OwnedSyncStore.CanonicalSourcePair sourcePair) { }
    private record HistoryJob(long generation, int playerId) { }
    private record FixtureManifestSeed(long generation,
                                       long bootstrapId, long fixturesId, long liveId) { }
    private record ManifestMutation(String label, java.util.function.Consumer<FixtureManifestSeed> mutate,
                                    Endpoint expectedFetch) { }

    private enum CatalogueLane {
        PRICE(CatalogueScheduleDispatcher.CATALOGUE_PRICE_COALESCING_KEY,
                CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY),
        SCHEDULE(CatalogueScheduleDispatcher.SCHEDULE_COALESCING_KEY,
                CatalogueScheduleWriter.SCHEDULE_PUBLICATION_KEY);

        private final String coalescingKey;
        private final String publicationKey;

        CatalogueLane(String coalescingKey, String publicationKey) {
            this.coalescingKey = coalescingKey;
            this.publicationKey = publicationKey;
        }

        String coalescingKey() { return coalescingKey; }
        String publicationKey() { return publicationKey; }
    }

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

    private List<HistoryJob> historyJobs() {
        return jdbc.query("SELECT generation, publication_key FROM " + TABLES.run()
                        + " WHERE kind='PLAYER_HISTORY' ORDER BY generation",
                (rs, row) -> new HistoryJob(rs.getLong("generation"), Integer.parseInt(
                        rs.getString("publication_key").substring("player-history:".length()))));
    }

    private List<HistoryJob> pendingHistoryJobs() {
        return jdbc.query("SELECT generation, publication_key FROM " + TABLES.run()
                        + " WHERE kind='PLAYER_HISTORY' AND state='PENDING' ORDER BY generation",
                (rs, row) -> new HistoryJob(rs.getLong("generation"), Integer.parseInt(
                        rs.getString("publication_key").substring("player-history:".length()))));
    }

    private FixtureManifestSeed seedBlockedManifest() {
        var run = dispatcher.enqueueFixtureVerification(SEASON, 21, clock.instant());
        Lease lease = acquire(run.generation());
        SourceRead pairRead = readNow();
        long bootstrapId = store.stage(lease, Endpoint.bootstrap(), pairRead, Validation.valid(), bootstrap);
        long fixturesId = store.stage(lease, Endpoint.fixtures(), pairRead, Validation.valid(), fixtures);
        clock.advance(Duration.ofMillis(1));
        SourceRead liveRead = readNow();
        long liveId = store.stage(lease, Endpoint.eventLive(3), liveRead, Validation.valid(), liveThree);
        store.retryForDependency(lease, clock.instant().plus(Duration.ofMinutes(2)),
                "Waiting for fresh player histories", 21, 3,
                new OwnedSyncStore.PublicPayloadPair(run.generation(),
                        new OwnedSyncStore.StagedPayload(bootstrapId, run.generation(),
                                OwnedSyncStore.EndpointKind.BOOTSTRAP_STATIC, pairRead,
                                ValidationState.VALID, null, bootstrap),
                        new OwnedSyncStore.StagedPayload(fixturesId, run.generation(),
                                OwnedSyncStore.EndpointKind.FIXTURES, pairRead,
                                ValidationState.VALID, null, fixtures)),
                new OwnedSyncStore.StagedPayload(liveId, run.generation(), OwnedSyncStore.EndpointKind.EVENT_LIVE,
                        liveRead, ValidationState.VALID, null, liveThree), List.of(259));
        clock.advance(Duration.ofMinutes(2));
        return new FixtureManifestSeed(run.generation(), bootstrapId, fixturesId, liveId);
    }

    private byte[] largeHistoryBody() {
        byte[] body = java.util.Arrays.copyOf(historyOne, 64 * 1024);
        java.util.Arrays.fill(body, historyOne.length, body.length, (byte) ' ');
        return body;
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

    private static byte[] withFixtureUnfinished(byte[] source, int fixtureId) throws Exception {
        ArrayNode rows = (ArrayNode) JSON.readTree(source);
        for (var row : rows) {
            if (row.get("id").intValue() == fixtureId) {
                ((ObjectNode) row).put("finished", false).put("finished_provisional", false)
                        .put("started", false).put("minutes", 0).putNull("team_h_score").putNull("team_a_score");
                return JSON.writeValueAsBytes(rows);
            }
        }
        throw new IllegalStateException("Fixture " + fixtureId + " is missing from the test source");
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

    private static final class TrackingJdbcTemplate extends PayloadReadProbe {
        private final AtomicInteger bodyQueries = new AtomicInteger();
        private final AtomicInteger invalidatedHistoryRows = new AtomicInteger();
        private Integer invalidateHistoryPlayerBeforeNextBodyQuery;

        private TrackingJdbcTemplate(javax.sql.DataSource dataSource) {
            super(dataSource);
        }

        @Override
        public <T> T query(PreparedStatementCreator creator, ResultSetExtractor<T> extractor) {
            if (creator instanceof OwnedSyncStore.HistoryHydrationStatement statement) {
                bodyQueries.incrementAndGet();
                if (invalidateHistoryPlayerBeforeNextBodyQuery != null) {
                    int playerId = invalidateHistoryPlayerBeforeNextBodyQuery;
                    boolean included = false;
                    List<Object> parameters = statement.parameters();
                    for (int index = 0; index < parameters.size() - 1; index += 5) {
                        if (Integer.valueOf(playerId).equals(parameters.get(index))) included = true;
                    }
                    if (included) {
                        invalidateHistoryPlayerBeforeNextBodyQuery = null;
                        invalidatedHistoryRows.addAndGet(super.update("UPDATE " + TABLES.payload()
                                + " SET validation_state='INVALID' WHERE endpoint_kind='ELEMENT_SUMMARY'"
                                + " AND player_id=? AND validation_state='VALID'", playerId));
                    }
                }
            }
            return super.query(creator, extractor);
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            if (PayloadReadProbe.projectsBody(sql)) {
                bodyQueries.incrementAndGet();
                if (invalidateHistoryPlayerBeforeNextBodyQuery != null
                        && sql.contains("'ELEMENT_SUMMARY'")
                        && java.util.Arrays.stream(args).anyMatch(SEASON.code()::equals)) {
                    int playerId = invalidateHistoryPlayerBeforeNextBodyQuery;
                    invalidateHistoryPlayerBeforeNextBodyQuery = null;
                    invalidatedHistoryRows.addAndGet(super.update("UPDATE " + TABLES.payload()
                            + " SET validation_state='INVALID' WHERE endpoint_kind='ELEMENT_SUMMARY'"
                            + " AND player_id=? AND validation_state='VALID'", playerId));
                }
            }
            return super.query(sql, rowMapper, args);
        }

        private int bodyQueries() {
            return bodyQueries.get();
        }

        private int invalidatedHistoryRows() {
            return invalidatedHistoryRows.get();
        }

        private void resetBodyQueries() {
            bodyQueries.set(0);
            invalidatedHistoryRows.set(0);
            invalidateHistoryPlayerBeforeNextBodyQuery = null;
            reset();
        }

        private void invalidateNextHistoryBeforeBodyQuery(int playerId) {
            invalidateHistoryPlayerBeforeNextBodyQuery = playerId;
        }
    }

    private static final class MutableClock extends Clock {
        private Instant value;
        private MutableClock(Instant value) { this.value = value; }
        private void advance(Duration duration) { value = value.plus(duration); }
        private void set(Instant instant) { value = instant; }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return value; }
    }
}
