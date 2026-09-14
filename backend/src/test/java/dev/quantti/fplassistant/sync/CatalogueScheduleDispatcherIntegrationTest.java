package dev.quantti.fplassistant.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import dev.quantti.fplassistant.sync.CatalogueScheduleDispatcher.Outcome;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.Season;
import dev.quantti.fplassistant.sync.OwnedSyncHttpClient.Failure;
import dev.quantti.fplassistant.sync.OwnedSyncHttpClient.FailureKind;
import dev.quantti.fplassistant.sync.OwnedSyncHttpClient.Response;
import dev.quantti.fplassistant.sync.OwnedSyncStore.Endpoint;
import dev.quantti.fplassistant.sync.OwnedSyncStore.Lease;
import dev.quantti.fplassistant.sync.OwnedSyncStore.LeaseLostException;
import dev.quantti.fplassistant.sync.OwnedSyncStore.SourceRead;
import dev.quantti.fplassistant.sync.OwnedSyncStore.SyncKind;
import dev.quantti.fplassistant.sync.OwnedSyncStore.Validation;
import dev.quantti.fplassistant.testsupport.SharedStatsSeed;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class CatalogueScheduleDispatcherIntegrationTest {

    private static final OwnedSyncTables OWNED = OwnedSyncTables.tests();
    private static final CatalogueScheduleTables CAPTURED = CatalogueScheduleTables.tests();
    private static final Season SEASON = new Season(27, "2026-27", LocalDate.of(2026, 8, 21), null);
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static byte[] bootstrap;
    private static byte[] fixtures;

    private MutableClock clock;
    private OwnedSyncStore store;
    private FakeFetch fetch;
    private CatalogueScheduleDispatcher dispatcher;

    @BeforeAll
    static void initialize() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(required("DATABASE_URL"),
                required("DB_USER"), required("DB_PASSWORD"));
        SharedStatsSeed.ensure(new JdbcTemplate(dataSource));
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        new OwnedSyncSchema(jdbc, OWNED).initialize();
        createCapturedTables();
        bootstrap = resource("bootstrap-static.json");
        fixtures = resource("fixtures.json");
    }

    @BeforeEach
    void setUp() {
        clean();
        jdbc.update("UPDATE season SET is_current=false");
        jdbc.update("""
                INSERT INTO season(id, code, name, start_date, end_date, is_current)
                VALUES (27, '2026-27', '2026/27', DATE '2026-08-21', NULL, true)
                ON CONFLICT (id) DO UPDATE SET code=EXCLUDED.code, name=EXCLUDED.name,
                    start_date=EXCLUDED.start_date, end_date=EXCLUDED.end_date, is_current=true
                """);
        clock = new MutableClock(Instant.parse("2026-09-13T18:00:00Z"));
        store = new OwnedSyncStore(jdbc, tx, clock, OWNED);
        fetch = new FakeFetch();
        dispatcher = dispatcher(CAPTURED);
    }

    @AfterEach
    void restoreSeason() {
        clean();
        jdbc.update("DELETE FROM season WHERE id=27");
        jdbc.update("UPDATE season SET is_current=(id=?)", SharedStatsSeed.CURRENT_SEASON_ID);
    }

    @Test
    void twoCanonicalGroupsShareOnePostDemandPairButANewCycleFetchesAgain() {
        var cycle = dispatcher.enqueueCycle(SEASON, clock.instant());
        Lease[] leases = acquireBoth();

        assertThat(dispatcher.process(forGeneration(leases, cycle.cataloguePrice().generation()), SEASON))
                .isEqualTo(Outcome.PUBLISHED);
        assertThat(dispatcher.process(forGeneration(leases, cycle.schedule().generation()), SEASON))
                .isEqualTo(Outcome.PUBLISHED);
        assertThat(fetch.calls).isEqualTo(2);

        clock.advance(Duration.ofMinutes(1));
        var next = dispatcher.enqueueCycle(SEASON, clock.instant());
        Lease[] nextLeases = acquireBoth();
        assertThat(dispatcher.process(forGeneration(nextLeases, next.cataloguePrice().generation()), SEASON))
                .isEqualTo(Outcome.PUBLISHED);
        assertThat(fetch.calls).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + CAPTURED.dailyPrice()
                + " WHERE season='2026-27'", Integer.class)).isEqualTo(657);
    }

    @Test
    void coalescedDemandDuringFetchIsRecheckedAtPublicationAndForcesAFreshPair() {
        var cycle = dispatcher.enqueueCycle(SEASON, clock.instant());
        Lease catalogue = forGeneration(acquireBoth(), cycle.cataloguePrice().generation());
        AtomicBoolean once = new AtomicBoolean();
        fetch.beforeResponse = endpoint -> {
            if (endpoint.equals(Endpoint.bootstrap()) && once.compareAndSet(false, true)) {
                clock.advance(Duration.ofSeconds(1));
                dispatcher.enqueueCycle(SEASON, clock.instant());
            }
        };

        assertThat(dispatcher.process(catalogue, SEASON)).isEqualTo(Outcome.PUBLISHED);
        assertThat(fetch.calls).isEqualTo(4);
        Instant floor = jdbc.queryForObject("SELECT requested_source_after FROM " + OWNED.run()
                + " WHERE generation=?", java.sql.Timestamp.class, catalogue.generation()).toInstant();
        Instant publishedRead = jdbc.queryForObject("SELECT source_read_started_at FROM " + OWNED.receipt()
                + " WHERE season='2026-27' AND publication_key='catalogue-price'",
                java.sql.Timestamp.class).toInstant();
        assertThat(publishedRead).isAfterOrEqualTo(floor);
    }

    @Test
    void futureCoalescedDemandDefersWithoutSpendingFetchBudget() {
        enqueueCatalogue();
        Lease lease = acquireOne(Duration.ofMinutes(5));
        Instant futureFloor = clock.instant().plus(Duration.ofHours(1));
        store.enqueue(SyncKind.CATALOGUE_SCHEDULE, SEASON.code(),
                CatalogueScheduleDispatcher.CATALOGUE_PRICE_COALESCING_KEY,
                CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY, futureFloor);

        assertThat(dispatcher.process(lease, SEASON)).isEqualTo(Outcome.RETRY_SCHEDULED);
        assertThat(fetch.calls).isZero();
        assertThat(jdbc.queryForObject("SELECT due_at FROM " + OWNED.run(),
                java.sql.Timestamp.class).toInstant()).isEqualTo(futureFloor);
        assertThat(store.acquire("too-early", Duration.ofMinutes(5))).isEmpty();
    }

    @Test
    void stagedPreCutoverPairSurvivesRetryAndRestartWithoutRefetch() {
        clock.set(Instant.parse("2026-09-13T00:30:00Z")); // 01:30 BST
        store.enqueue(SyncKind.CATALOGUE_SCHEDULE, SEASON.code(),
                CatalogueScheduleDispatcher.CATALOGUE_PRICE_COALESCING_KEY,
                CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY, clock.instant());
        Lease first = acquireOne(Duration.ofMinutes(5));

        assertThat(dispatcher.process(first, SEASON)).isEqualTo(Outcome.RETRY_SCHEDULED);
        assertThat(fetch.calls).isEqualTo(2);
        assertThat(payloadCount()).isEqualTo(2);

        clock.set(Instant.parse("2026-09-13T01:00:00Z"));
        dispatcher = dispatcher(CAPTURED); // process restart, same durable store/evidence
        assertThat(dispatcher.process(acquireOne(Duration.ofMinutes(5)), SEASON)).isEqualTo(Outcome.PUBLISHED);
        assertThat(fetch.calls).isEqualTo(2);
    }

    @Test
    void delayedPartialRestartRefetchesInsteadOfPermanentlyFailingThePairWindow() {
        enqueueCatalogue();
        Lease first = acquireOne(Duration.ofMinutes(5));
        SourceRead bootstrapRead = new SourceRead(clock.instant(), clock.instant());
        store.stage(first, Endpoint.bootstrap(), bootstrapRead, Validation.unchecked(), bootstrap);
        store.retry(first, clock.instant(), "restart after first endpoint");
        clock.advance(Duration.ofMinutes(11));

        assertThat(dispatcher.process(acquireOne(Duration.ofMinutes(5)), SEASON)).isEqualTo(Outcome.PUBLISHED);
        assertThat(fetch.calls).isEqualTo(3); // old bootstrap + fetched fixture was rejected, then both refreshed
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + OWNED.payload()
                + " WHERE validation_state='INVALID'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + OWNED.payload()
                + " WHERE validation_state='VALID'", Integer.class)).isEqualTo(2);
    }

    @Test
    void restartWithBothUncheckedBodiesOutsideTheWindowRefetchesBoth() {
        enqueueCatalogue();
        Lease first = acquireOne(Duration.ofHours(1));
        store.stage(first, Endpoint.bootstrap(), readNow(), Validation.unchecked(), bootstrap);
        clock.advance(Duration.ofMinutes(11));
        store.stage(first, Endpoint.fixtures(), readNow(), Validation.unchecked(), fixtures);
        store.retry(first, clock.instant(), "restart after both endpoints");

        assertThat(dispatcher.process(acquireOne(Duration.ofMinutes(5)), SEASON)).isEqualTo(Outcome.PUBLISHED);
        assertThat(fetch.calls).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + OWNED.payload()
                + " WHERE validation_state='INVALID'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + OWNED.payload()
                + " WHERE validation_state='VALID'", Integer.class)).isEqualTo(2);
    }

    @Test
    void malformedRaw200AndOversizedPayloadFailTerminallyWithoutPublishing() {
        enqueueCatalogue();
        fetch.results.add(new Response(200, "{}".getBytes(), null, readNow()));
        assertThat(dispatcher.process(acquireOne(Duration.ofMinutes(5)), SEASON)).isEqualTo(Outcome.FAILED);
        assertRunState("FAILED");
        assertThat(payloadCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + OWNED.payload()
                + " WHERE validation_state='INVALID'", Integer.class)).isEqualTo(2);

        cleanRunsAndCore();
        enqueueCatalogue();
        fetch.results.clear();
        fetch.results.add(new Response(200, new byte[OwnedSyncStore.MAX_PAYLOAD_BYTES + 1], null, readNow()));
        assertThat(dispatcher.process(acquireOne(Duration.ofMinutes(5)), SEASON)).isEqualTo(Outcome.FAILED);
        assertRunState("FAILED");
        assertThat(payloadCount()).isZero();
    }

    @Test
    void sourceReceiptRaceRefreshesOnceAndUsesTheSamePublicationErrorHandling() throws Exception {
        enqueueCatalogue();
        Lease lease = acquireOne(Duration.ofMinutes(5));
        CountDownLatch seasonLocked = new CountDownLatch(1);
        CountDownLatch releaseSeason = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> blocker = executor.submit(() -> tx.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM season WHERE id=27 FOR UPDATE", Integer.class);
                seasonLocked.countDown();
                await(releaseSeason);
            }));
            assertThat(seasonLocked.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Outcome> outcome = executor.submit(() -> dispatcher.process(lease, SEASON));
            awaitFetchCalls(2);
            awaitPublisherWaiting();
            jdbc.update("""
                    INSERT INTO %s(season, publication_key, source_read_started_at,
                                   source_read_finished_at, published_at)
                    VALUES ('2026-27', ?, ?, ?, ?)
                    ON CONFLICT (season, publication_key) DO UPDATE SET
                        source_read_started_at=EXCLUDED.source_read_started_at,
                        source_read_finished_at=EXCLUDED.source_read_finished_at,
                        published_at=EXCLUDED.published_at
                    """.formatted(OWNED.receipt()),
                    CatalogueScheduleWriter.SHARED_TEAM_SOURCE_RECEIPT_KEY,
                    java.sql.Timestamp.from(clock.instant()), java.sql.Timestamp.from(clock.instant()),
                    java.sql.Timestamp.from(clock.instant()));
            releaseSeason.countDown();
            blocker.get(5, TimeUnit.SECONDS);

            Outcome actual = outcome.get(10, TimeUnit.SECONDS);
            assertThat(actual).as("calls=%s state=%s error=%s", fetch.calls,
                    jdbc.queryForObject("SELECT state FROM " + OWNED.run(), String.class),
                    jdbc.queryForObject("SELECT last_error FROM " + OWNED.run(), String.class))
                    .isEqualTo(Outcome.PUBLISHED);
            assertThat(fetch.calls).isEqualTo(4);
        } finally {
            releaseSeason.countDown();
        }
    }

    @Test
    void transientHttpUsesTheAuthoritativePersistedRetryHint() {
        for (int status : List.of(429, 503)) {
            cleanRunsAndCore();
            enqueueCatalogue();
            Instant due = clock.instant().plus(Duration.ofHours(2));
            fetch.results.add(new Response(status, "busy".getBytes(), "untrusted duplicate", readNow(), due));

            assertThat(dispatcher.process(acquireOne(Duration.ofMinutes(5)), SEASON))
                    .isEqualTo(Outcome.RETRY_SCHEDULED);
            assertThat(jdbc.queryForObject("SELECT due_at FROM " + OWNED.run(),
                    java.sql.Timestamp.class).toInstant()).isEqualTo(due);
            assertThat(jdbc.queryForObject("SELECT validation_state FROM " + OWNED.payload(), String.class))
                    .isEqualTo("INVALID");
            fetch.results.clear();
        }
    }

    @Test
    void interruptedFetchReturnsOnlyAfterSchedulingADurableRetry() {
        enqueueCatalogue();
        fetch.results.add(new Failure(FailureKind.INTERRUPTED, "interrupted", null, null, readNow()));

        assertThat(dispatcher.process(acquireOne(Duration.ofMinutes(5)), SEASON))
                .isEqualTo(Outcome.RETRY_SCHEDULED);
        assertRunState("PENDING");
        assertThat(payloadCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT latest_generation FROM " + OWNED.receipt()
                + " WHERE season='2026-27' AND publication_key='catalogue-price'", Long.class)).isNull();
    }

    @Test
    void leaseExpiryDuringFetchCannotStageRetryOrPublish() {
        enqueueCatalogue();
        fetch.beforeResponse = endpoint -> clock.advance(Duration.ofMinutes(6));

        assertThatThrownBy(() -> dispatcher.process(acquireOne(Duration.ofMinutes(5)), SEASON))
                .isInstanceOf(LeaseLostException.class);
        assertThat(payloadCount()).isZero();
        assertRunState("LEASED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM player WHERE season_id=27", Integer.class)).isZero();
    }

    @Test
    void publicationSqlRollbackKeepsValidatedStagesAndSchedulesDurableRetry() {
        enqueueCatalogue();
        CatalogueScheduleTables failing = new CatalogueScheduleTables(
                "fpl_daily_price_fail_dispatch_test", CAPTURED.gameweek(), CAPTURED.fixture());
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + failing.dailyPrice() + " (LIKE "
                + CAPTURED.dailyPrice() + " INCLUDING ALL)");
        jdbc.execute("ALTER TABLE " + failing.dailyPrice() + " DROP CONSTRAINT IF EXISTS fail_dispatch_price");
        jdbc.execute("ALTER TABLE " + failing.dailyPrice()
                + " ADD CONSTRAINT fail_dispatch_price CHECK (now_cost < 0)");
        dispatcher = dispatcher(failing);

        assertThat(dispatcher.process(acquireOne(Duration.ofMinutes(5)), SEASON))
                .isEqualTo(Outcome.RETRY_SCHEDULED);
        assertRunState("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + OWNED.payload()
                + " WHERE validation_state='VALID'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM player WHERE season_id=27", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT latest_generation FROM " + OWNED.receipt()
                + " WHERE season='2026-27' AND publication_key='catalogue-price'", Long.class)).isNull();
    }

    @Test
    void previousDayEvidenceIsNeverReused() {
        enqueueCatalogue();
        assertThat(dispatcher.process(acquireOne(Duration.ofMinutes(5)), SEASON)).isEqualTo(Outcome.PUBLISHED);
        assertThat(fetch.calls).isEqualTo(2);

        clock.advance(Duration.ofDays(1));
        enqueueCatalogue();
        assertThat(dispatcher.process(acquireOne(Duration.ofMinutes(5)), SEASON)).isEqualTo(Outcome.PUBLISHED);
        assertThat(fetch.calls).isEqualTo(4);
    }

    private CatalogueScheduleDispatcher dispatcher(CatalogueScheduleTables tables) {
        return new CatalogueScheduleDispatcher(store,
                new CatalogueScheduleWriter(jdbc, clock, store, OWNED, tables), fetch, clock);
    }

    private void enqueueCatalogue() {
        store.enqueue(SyncKind.CATALOGUE_SCHEDULE, SEASON.code(),
                CatalogueScheduleDispatcher.CATALOGUE_PRICE_COALESCING_KEY,
                CatalogueScheduleWriter.CATALOGUE_PRICE_PUBLICATION_KEY, clock.instant());
    }

    private Lease acquireOne(Duration duration) {
        return store.acquire("worker", duration).orElseThrow();
    }

    private Lease[] acquireBoth() {
        return new Lease[]{acquireOne(Duration.ofMinutes(5)), acquireOne(Duration.ofMinutes(5))};
    }

    private static Lease forGeneration(Lease[] leases, long generation) {
        return java.util.Arrays.stream(leases).filter(lease -> lease.generation() == generation)
                .findFirst().orElseThrow();
    }

    private SourceRead readNow() {
        return new SourceRead(clock.instant(), clock.instant());
    }

    private void awaitFetchCalls(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (fetch.calls < expected && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(fetch.calls).isGreaterThanOrEqualTo(expected);
    }

    private static void awaitPublisherWaiting() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE datname=current_database() AND wait_event_type='Lock'
                      AND query LIKE '%FROM season WHERE id =%'
                    """, Integer.class);
            if (waiting != null && waiting > 0) return;
            Thread.sleep(10);
        }
        throw new AssertionError("publisher did not reach the season lock");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private int payloadCount() {
        return jdbc.queryForObject("SELECT count(*) FROM " + OWNED.payload(), Integer.class);
    }

    private void assertRunState(String state) {
        assertThat(jdbc.queryForObject("SELECT state FROM " + OWNED.run(), String.class)).isEqualTo(state);
    }

    private static void cleanRunsAndCore() {
        jdbc.update("DELETE FROM " + OWNED.payload());
        jdbc.update("DELETE FROM " + OWNED.run());
        jdbc.update("DELETE FROM " + OWNED.receipt());
        jdbc.update("DELETE FROM " + CAPTURED.dailyPrice());
        jdbc.update("DELETE FROM " + CAPTURED.gameweek());
        jdbc.update("DELETE FROM " + CAPTURED.fixture());
        jdbc.update("DELETE FROM player WHERE season_id=27");
        jdbc.update("DELETE FROM fixture WHERE season_id=27");
        jdbc.update("DELETE FROM gameweek WHERE season_id=27");
        jdbc.update("DELETE FROM team WHERE season_id=27");
    }

    private static void clean() {
        cleanRunsAndCore();
        jdbc.update("DELETE FROM fpl_daily_price_fail_dispatch_test");
    }

    private static void createCapturedTables() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    season varchar(10) NOT NULL, price_date date NOT NULL, element_id integer NOT NULL,
                    web_name varchar(100) NOT NULL, full_name varchar(200), team_id integer,
                    team_short varchar(10), team_name varchar(100), element_type smallint,
                    now_cost integer NOT NULL, cost_change_event integer NOT NULL DEFAULT 0,
                    cost_change_start integer NOT NULL DEFAULT 0, selected_by_percent numeric(5,2),
                    status varchar(4), news text, penalties_order smallint, direct_fk_order smallint,
                    corners_order smallint, captured_at timestamptz NOT NULL DEFAULT now(),
                    PRIMARY KEY(season, price_date, element_id))
                """.formatted(CAPTURED.dailyPrice()));
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    season varchar(10) NOT NULL, id integer NOT NULL, name varchar(50),
                    deadline_time timestamptz NOT NULL, finished boolean NOT NULL DEFAULT false,
                    data_checked boolean NOT NULL DEFAULT false, is_current boolean NOT NULL DEFAULT false,
                    is_next boolean NOT NULL DEFAULT false, average_entry_score integer,
                    highest_score integer, most_captained integer, most_transferred_in integer,
                    captured_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(season,id))
                """.formatted(CAPTURED.gameweek()));
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    season varchar(10) NOT NULL, id integer NOT NULL, gameweek integer,
                    team_h integer NOT NULL, team_a integer NOT NULL, team_h_name varchar(100),
                    team_h_short varchar(10), team_a_name varchar(100), team_a_short varchar(10),
                    team_h_difficulty smallint, team_a_difficulty smallint, kickoff_time timestamptz,
                    started boolean NOT NULL DEFAULT false, finished boolean NOT NULL DEFAULT false,
                    team_h_score integer, team_a_score integer,
                    captured_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(season,id))
                """.formatted(CAPTURED.fixture()));
        jdbc.execute("CREATE TABLE IF NOT EXISTS fpl_daily_price_fail_dispatch_test (LIKE "
                + CAPTURED.dailyPrice() + " INCLUDING ALL)");
        jdbc.execute("ALTER TABLE fpl_daily_price_fail_dispatch_test ENABLE ROW LEVEL SECURITY");
    }

    private static byte[] resource(String name) throws Exception {
        try (InputStream input = CatalogueScheduleDispatcherIntegrationTest.class.getResourceAsStream(
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

    private final class FakeFetch implements CatalogueScheduleDispatcher.Fetch {
        private final Deque<OwnedSyncHttpClient.Result> results = new ArrayDeque<>();
        private java.util.function.Consumer<Endpoint> beforeResponse = endpoint -> { };
        private volatile int calls;

        @Override
        public OwnedSyncHttpClient.Result get(Endpoint endpoint, int failureAttempt) {
            calls++;
            if (!results.isEmpty()) return results.removeFirst();
            Instant started = clock.instant();
            beforeResponse.accept(endpoint);
            clock.advance(Duration.ofMillis(100));
            byte[] body = endpoint.equals(Endpoint.bootstrap()) ? bootstrap : fixtures;
            return new Response(200, body, null, new SourceRead(started, clock.instant()));
        }
    }

    private static final class MutableClock extends Clock {
        private Instant value;
        private MutableClock(Instant value) { this.value = value; }
        private void set(Instant value) { this.value = value; }
        private void advance(Duration duration) { value = value.plus(duration); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return value; }
    }
}
