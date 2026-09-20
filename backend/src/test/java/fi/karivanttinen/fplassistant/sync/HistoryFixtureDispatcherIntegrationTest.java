package fi.karivanttinen.fplassistant.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;

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
        var run = dispatcher.enqueueFixtureVerification(SEASON, 21, clock.instant());
        fetch.results.add(ok(bootstrap));
        fetch.results.add(ok(fixtures));
        clock.advance(Duration.ofMillis(1));
        fetch.results.add(ok(liveWithDeparted));

        DispatchResult result = dispatcher.process(acquire(run.generation()), SEASON);

        assertThat(result.outcome()).isEqualTo(Outcome.WAITING_FOR_HISTORIES);
        assertThat(fetch.endpoints).containsExactly(Endpoint.bootstrap(), Endpoint.fixtures(),
                Endpoint.eventLive(3));
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
        Lease lease;
        do {
            lease = store.acquire("worker", Duration.ofMinutes(5)).orElseThrow();
        } while (lease.generation() != generation);
        return lease;
    }

    private Response ok(byte[] body) {
        Instant start = clock.instant();
        clock.advance(Duration.ofMillis(100));
        return new Response(200, body, null, new SourceRead(start, clock.instant()));
    }

    private SourceRead readNow() {
        return new SourceRead(clock.instant(), clock.instant());
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
        @Override public OwnedSyncHttpClient.Result get(Endpoint endpoint, int failureAttempt) {
            endpoints.add(endpoint);
            if (results.isEmpty()) throw new AssertionError("Unexpected fetch " + endpoint);
            return results.removeFirst();
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
