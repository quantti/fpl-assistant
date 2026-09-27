package fi.karivanttinen.fplassistant.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zaxxer.hikari.HikariDataSource;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.Season;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.EndpointKind;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.Lease;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.RuntimeFence;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.SourceRead;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.SyncKind;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.Validation;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.ValidationState;
import fi.karivanttinen.fplassistant.testsupport.TapasSeed;

/** Failing baseline for real two-worker HTTP admission under a fixture-first backlog. */
class OwnedMatchdayAdmissionProgressIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final OwnedSyncTables TABLES = OwnedSyncTables.tests();
    private static final CatalogueScheduleTables CAPTURED = new CatalogueScheduleTables(
            "fpl_admission_daily_price_test", "fpl_admission_gameweek_test", "fpl_admission_fixture_test");
    private static final Season SEASON = new Season(27, "2026-27", LocalDate.of(2026, 8, 21), null);
    private static final Instant START = Instant.parse("2026-10-01T12:00:00Z");
    private static final Duration RESPONSE_TIME = Duration.ofMillis(100);
    private static final Duration WORKER_DELAY = Duration.ofSeconds(1);
    private static final Duration VIRTUAL_LIMIT = Duration.ofSeconds(60);
    private static final int DISPATCH_LIMIT = 150;
    private static final int FIXTURE_RUNS = 50;
    private static final int HISTORY_RUNS = 68;
    private static final int SEEDED_PARTIALS = 10;
    private static final long WALL_LIMIT_NANOS = TimeUnit.SECONDS.toNanos(110);
    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static SourceData source;

    @BeforeAll
    static void initialize() throws Exception {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(required("DATABASE_URL"));
        dataSource.setUsername(required("DB_USER"));
        dataSource.setPassword(required("DB_PASSWORD"));
        dataSource.setMaximumPoolSize(3);
        dataSource.setMinimumIdle(1);
        dataSource.setConnectionTimeout(2_000);
        dataSource.addDataSourceProperty("connectTimeout", "3");
        dataSource.addDataSourceProperty("socketTimeout", "10");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(10);
        TapasSeed.ensureOwnedSyncCaptureSeason(jdbc);
        new OwnedSyncSchema(jdbc, TABLES).initialize();
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        tx.setTimeout(30);
        source = SourceData.load();
    }

    @AfterAll
    static void closePool() {
        if (dataSource != null) dataSource.close();
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void twoWorkerFixtureFanoutDoesNotStarveDueHistories() throws Exception {
        long wallDeadline = System.nanoTime() + WALL_LIMIT_NANOS;
        List<Metrics> baselines = new ArrayList<>();
        for (int firstWorker : List.of(1, 2)) {
            try (Harness harness = new Harness(firstWorker, wallDeadline)) {
                harness.seedCanonicalPair();
                harness.seedMixedBacklog();
                baselines.add(harness.driveMixedBacklog());
            }
        }

        baselines.forEach(metrics -> System.out.printf(
                "OWNED_ADMISSION_BASELINE firstWorker=%d firstAdmissionWorker=%d firstHistoryGetSeconds=%s "
                        + "firstHistoryCompleteSeconds=%s distinctCompletedPlayers=%s historiesWithin60=%d "
                        + "stagedFixtures=%d claims=%d deferrals=%d admitted=%d gets=%s "
                        + "payloadRows=%d stagedRawBytes=%d returnedGets=%d returnedRawBytes=%d "
                        + "newPartialBootstraps=%d peakNewPartialPairs=%d dispatches=%d virtualSeconds=%.3f%n",
                metrics.firstWorker(), metrics.firstAdmissionWorker(), offset(metrics.firstHistoryGet()),
                offset(metrics.firstHistoryCompleted()), metrics.completedPlayerIds(),
                metrics.historiesWithin60(), metrics.stagedFixtures(), metrics.claims(),
                metrics.deferrals(), metrics.admittedAttempts(),
                metrics.getsByEndpoint(), metrics.payloadRows(), metrics.stagedRawBytes(),
                metrics.returnedGets(), metrics.returnedRawBytes(), metrics.newPartialBootstraps(),
                metrics.peakNewPartialPairs(), metrics.dispatches(), metrics.virtualSeconds()));

        assertThat(baselines).hasSize(2).allSatisfy(metrics -> {
            assertThat(metrics.firstAdmissionWorker()).isEqualTo(metrics.firstWorker());
            assertThat(metrics.virtualSeconds()).isLessThanOrEqualTo(VIRTUAL_LIMIT.toNanos() / 1e9);
            assertThat(metrics.dispatches()).isLessThanOrEqualTo(DISPATCH_LIMIT);
            assertThat(metrics.firstHistoryCompleted())
                    .as("a valid history must complete within 15 virtual seconds")
                    .isNotNull()
                    .isBeforeOrEqualTo(START.plusSeconds(15));
            assertThat(metrics.historiesWithin60())
                    .as("at least ten distinct valid histories must complete within 60 virtual seconds")
                    .isGreaterThanOrEqualTo(10);
        });
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void coldParentHistoryDeferralDoesNotBlockCanonicalPrerequisites() throws Exception {
        ColdParentEvidence cold = runColdParentCase(System.nanoTime() + WALL_LIMIT_NANOS);
        assertThat(cold.historyClaims()).isEqualTo(1);
        assertThat(cold.parentClaims()).isEqualTo(3);
        assertThat(cold.getsByEndpoint()).containsEntry(EndpointKind.BOOTSTRAP_STATIC, 1)
                .containsEntry(EndpointKind.FIXTURES, 1);
        assertThat(cold.pendingParents()).isEqualTo(2);
        assertThat(cold.historyDeferred()).isTrue();
        assertThat(cold.bootstrapOnlyOnFirstParentDispatch()).isTrue();
        assertThat(cold.fixturesFetchedOnDeferredRetry()).isTrue();
    }

    private static ColdParentEvidence runColdParentCase(long wallDeadline) throws Exception {
        try (Harness harness = new Harness(1, wallDeadline)) {
            harness.enqueueHistoryOnly();
            boolean historyClaim = harness.coordinator.dispatchOne("cold-history");
            int parentRunsAfterHistory = jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                    + " WHERE season=? AND kind='CATALOGUE_SCHEDULE' AND state='PENDING'",
                    Integer.class, SEASON.code());
            String historyError = jdbc.queryForObject("SELECT last_error FROM " + TABLES.run()
                    + " WHERE season=? AND kind='PLAYER_HISTORY'", String.class, SEASON.code());
            long firstParent = jdbc.queryForObject("SELECT generation FROM " + TABLES.run()
                    + " WHERE season=? AND kind='CATALOGUE_SCHEDULE' AND state='PENDING' "
                    + "ORDER BY due_at,generation LIMIT 1", Long.class, SEASON.code());

            int parentClaims = harness.dispatchOneWithControlledResponse("cold-parent-first");
            Map<EndpointKind, Integer> firstDispatchGets = harness.server.getsByEndpoint();
            int stagedBootstrap = jdbc.queryForObject("SELECT count(*) FROM " + TABLES.payload()
                    + " WHERE run_generation=? AND endpoint_kind='BOOTSTRAP_STATIC'", Integer.class, firstParent);
            int stagedFixtures = jdbc.queryForObject("SELECT count(*) FROM " + TABLES.payload()
                    + " WHERE run_generation=? AND endpoint_kind='FIXTURES'", Integer.class, firstParent);
            String firstParentError = jdbc.queryForObject("SELECT last_error FROM " + TABLES.run()
                    + " WHERE generation=?", String.class, firstParent);
            boolean bootstrapOnlyOnFirstDispatch = firstDispatchGets.getOrDefault(
                    EndpointKind.BOOTSTRAP_STATIC, 0) == 1
                    && firstDispatchGets.getOrDefault(EndpointKind.FIXTURES, 0) == 0
                    && stagedBootstrap == 1 && stagedFixtures == 0
                    && firstParentError != null && firstParentError.contains("HTTP budget deferred");

            parentClaims += harness.dispatchOneWithControlledResponse("cold-parent-contender");
            Instant retryAt = harness.clock.instant().plus(WORKER_DELAY);
            Instant spacingAt = harness.nextSpacingAt();
            if (spacingAt.isAfter(retryAt)) retryAt = spacingAt;
            harness.clock.set(retryAt);
            long dueGeneration = jdbc.queryForObject("SELECT generation FROM " + TABLES.run()
                    + " WHERE season=? AND kind='CATALOGUE_SCHEDULE' AND state='PENDING' AND due_at<=? "
                    + "ORDER BY due_at,generation LIMIT 1", Long.class, SEASON.code(), Timestamp.from(retryAt));
            parentClaims += harness.dispatchOneWithControlledResponse("cold-parent-retry");
            Map<EndpointKind, Integer> gets = harness.server.getsByEndpoint();
            int retryState = jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                    + " WHERE generation=? AND state='COMPLETED'", Integer.class, dueGeneration);
            int retryBootstrap = jdbc.queryForObject("SELECT count(*) FROM " + TABLES.payload()
                    + " WHERE run_generation=? AND endpoint_kind='BOOTSTRAP_STATIC'", Integer.class, dueGeneration);
            int retryFixtures = jdbc.queryForObject("SELECT count(*) FROM " + TABLES.payload()
                    + " WHERE run_generation=? AND endpoint_kind='FIXTURES'", Integer.class, dueGeneration);
            return new ColdParentEvidence(historyClaim ? 1 : 0, parentClaims, gets,
                    parentRunsAfterHistory, historyError != null
                            && historyError.contains("Canonical schedule is not available"),
                    bootstrapOnlyOnFirstDispatch,
                    dueGeneration == firstParent && retryState == 1
                            && retryBootstrap == 1 && retryFixtures == 1
                            && gets.getOrDefault(EndpointKind.FIXTURES, 0) == 1);
        }
    }

    private static void clearOwnedRows() {
        jdbc.execute("TRUNCATE " + TABLES.playerFixtureEvidence() + "," + TABLES.fixtureSync() + ","
                + TABLES.historyFingerprint() + "," + TABLES.payload() + "," + TABLES.run() + ","
                + TABLES.receipt() + " CASCADE");
        jdbc.update("DELETE FROM " + TABLES.plannerSlot());
        jdbc.update("DELETE FROM " + TABLES.plannerRuntime());
        jdbc.update("DELETE FROM " + TABLES.requestAttempt());
        jdbc.update("UPDATE " + TABLES.requestState() + " SET last_admitted_at=NULL,cooldown_until=NULL");
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private static String offset(Instant value) {
        return value == null ? "none" : String.format("%.3f", Duration.between(START, value).toNanos() / 1e9);
    }

    private record Metrics(int firstWorker, int firstAdmissionWorker, Instant firstHistoryGet,
                           Instant firstHistoryCompleted, Set<Integer> completedPlayerIds,
                           int historiesWithin60, int stagedFixtures, int claims, int deferrals,
                           int admittedAttempts,
                           Map<EndpointKind, Integer> getsByEndpoint, int payloadRows, long stagedRawBytes,
                           int returnedGets, long returnedRawBytes, int newPartialBootstraps,
                           int peakNewPartialPairs, int dispatches, double virtualSeconds) { }

    private record ColdParentEvidence(int historyClaims, int parentClaims,
                                      Map<EndpointKind, Integer> getsByEndpoint,
                                      int pendingParents, boolean historyDeferred,
                                      boolean bootstrapOnlyOnFirstParentDispatch,
                                      boolean fixturesFetchedOnDeferredRetry) { }

    private record DispatchCompletion(int worker, boolean claimed, Instant completedAt) { }

    private record ClaimAttempt(long generation, int attemptCount) { }

    private record ScheduledResponse(RequestArrival arrival, Instant releaseAt) { }

    private record HistoryCompletion(String publicationKey, Instant completedAt) {
        int playerId() {
            return Integer.parseInt(publicationKey.substring("player-history:".length()));
        }
    }

    private static final class Harness implements AutoCloseable {
        private final int firstWorker;
        private final long wallDeadline;
        private final MutableClock clock = new MutableClock(START);
        private final RuntimeFence fence;
        private final PlannerRuntimeRepository activation;
        private final OwnedSyncStore store;
        private final OwnedSyncRequestBudget budget;
        private final RequestServer server;
        private final OwnedSyncHttpClient http;
        private final OwnedMatchdayCoordinator coordinator;
        private final ExecutorService dispatchWorkers = Executors.newFixedThreadPool(2,
                Thread.ofPlatform().name("admission-test-worker-", 0).factory());
        private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("admission-test-watchdog").daemon(true).factory());
        private final AtomicBoolean clientClosed = new AtomicBoolean();
        private final AtomicBoolean watchdogFired = new AtomicBoolean();
        private final ScheduledFuture<?> watchdogTask;
        private final Set<Long> seededPartialGenerations = new HashSet<>();
        private final Set<ClaimAttempt> countedDeferrals = new HashSet<>();

        Harness(int firstWorker, long wallDeadline) throws Exception {
            this.firstWorker = firstWorker;
            this.wallDeadline = wallDeadline;
            clearOwnedRows();
            activation = new PlannerRuntimeRepository(jdbc, tx, clock, TABLES);
            fence = activation.activate(SEASON.code(), OwnedSyncMode.STAGING);
            store = new OwnedSyncStore(jdbc, tx, clock, TABLES, fence);
            budget = new OwnedSyncRequestBudget(jdbc, tx, clock, TABLES);
            server = new RequestServer(source, clock);
            http = new OwnedSyncHttpClient(budget, server.baseUri(), Duration.ofSeconds(30), clock);
            var catalogue = CatalogueScheduleDispatcher.staging(store, http, clock);
            var histories = HistoryFixtureDispatcher.staging(store, http, clock);
            PlannerRuntimeRepository runtime = new PlannerRuntimeRepository(jdbc, tx, clock, TABLES, fence);
            PlannerInputRepository inputs = new PlannerInputRepository(jdbc, store, TABLES, CAPTURED, clock);
            coordinator = new OwnedMatchdayCoordinator(SEASON, new AdaptiveMatchdayPlanner(clock), inputs,
                    runtime, store, catalogue, histories, "admission-repro");
            long watchdogDelay = Math.max(1, wallDeadline - System.nanoTime());
            watchdogTask = watchdog.schedule(this::emergencyStop, watchdogDelay, TimeUnit.NANOSECONDS);
        }

        void seedCanonicalPair() {
            CatalogueScheduleDispatcher catalogue = CatalogueScheduleDispatcher.staging(store, http, clock);
            var run = catalogue.enqueueCatalogue(SEASON, clock.instant());
            Lease lease = store.acquireForSeason(SEASON.code(), "seed-canonical", Duration.ofMinutes(5))
                    .orElseThrow();
            assertThat(lease.generation()).isEqualTo(run.generation());
            SourceRead read = new SourceRead(clock.instant(), clock.instant());
            CatalogueScheduleParser.parse(SEASON,
                    new CatalogueScheduleParser.SourceDocument(source.bootstrapBytes(),
                            new CatalogueScheduleParser.ReadInterval(read.startedAt(), read.finishedAt())),
                    new CatalogueScheduleParser.SourceDocument(source.fixtureBytes(),
                            new CatalogueScheduleParser.ReadInterval(read.startedAt(), read.finishedAt())));
            long bootstrapId = store.stage(lease, OwnedSyncStore.Endpoint.bootstrap(), read,
                    Validation.unchecked(), source.bootstrapBytes());
            long fixturesId = store.stage(lease, OwnedSyncStore.Endpoint.fixtures(), read,
                    Validation.unchecked(), source.fixtureBytes());
            store.validateStagedPayloads(lease, List.of(bootstrapId, fixturesId), Validation.valid());
            store.completeStaged(lease, read, List.of(read, read));
        }

        void seedMixedBacklog() {
            var histories = HistoryFixtureDispatcher.staging(store, http, clock);
            var waiting = histories.enqueueFixtureVerification(SEASON, FIXTURE_RUNS, clock.instant());
            Lease waitingLease = store.acquireForSeason(SEASON.code(), "seed-dependency", Duration.ofMinutes(5))
                    .orElseThrow();
            assertThat(waitingLease.generation()).isEqualTo(waiting.generation());
            store.retryForDependency(waitingLease, clock.instant(), "Waiting for seeded player histories");

            List<Long> fixtureGenerations = new ArrayList<>();
            for (int fixtureId = 1; fixtureId < FIXTURE_RUNS; fixtureId++) {
                fixtureGenerations.add(histories.enqueueFixtureVerification(
                        SEASON, fixtureId, clock.instant()).generation());
            }
            assertThat(fixtureGenerations).hasSize(49);
            for (int index = 0; index < SEEDED_PARTIALS; index++) {
                Lease lease = store.acquireForSeason(SEASON.code(), "seed-partial-" + index,
                        Duration.ofMinutes(5)).orElseThrow();
                assertThat(lease.kind()).isEqualTo(SyncKind.FIXTURE_VERIFICATION);
                SourceRead read = new SourceRead(clock.instant(), clock.instant());
                store.stage(lease, OwnedSyncStore.Endpoint.bootstrap(), read,
                        Validation.unchecked(), source.bootstrapBytes());
                store.retry(lease, clock.instant().plus(Duration.ofHours(1)),
                        "Seeded usable partial bootstrap checkpoint");
                seededPartialGenerations.add(lease.generation());
            }
            for (long generation : seededPartialGenerations) {
                jdbc.update("UPDATE " + TABLES.run() + " SET due_at=? WHERE generation=?",
                        Timestamp.from(clock.instant()), generation);
            }
            for (int playerId : source.playerIds()) {
                histories.enqueuePlayerHistory(SEASON, playerId, clock.instant());
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                    + " WHERE season=? AND kind='FIXTURE_VERIFICATION'", Integer.class, SEASON.code()))
                    .isEqualTo(FIXTURE_RUNS);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                    + " WHERE season=? AND kind='PLAYER_HISTORY'", Integer.class, SEASON.code()))
                    .isEqualTo(HISTORY_RUNS);
            assertThat(seededPartialGenerations).hasSize(SEEDED_PARTIALS);
        }

        void enqueueHistoryOnly() {
            HistoryFixtureDispatcher.staging(store, http, clock)
                    .enqueuePlayerHistory(SEASON, source.playerIds().getFirst(), clock.instant());
        }

        Metrics driveMixedBacklog() throws Exception {
            ManualDriver driver = new ManualDriver(this);
            driver.run();
            return metrics(driver);
        }

        private Metrics metrics(ManualDriver driver) {
            Instant end = clock.instant();
            Instant historyLimit = START.plus(VIRTUAL_LIMIT);
            List<HistoryCompletion> completed = jdbc.query("SELECT publication_key,completed_at FROM "
                            + TABLES.run() + " WHERE season=? AND kind='PLAYER_HISTORY' AND state='COMPLETED' "
                            + "ORDER BY completed_at,generation",
                    (rs, row) -> new HistoryCompletion(rs.getString(1), rs.getTimestamp(2).toInstant()),
                    SEASON.code());
            Instant firstCompleted = completed.isEmpty() ? null : completed.getFirst().completedAt();
            Set<Integer> completedPlayers = completed.stream().map(HistoryCompletion::playerId)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            Set<Integer> completedWithin60 = completed.stream()
                    .filter(row -> !row.completedAt().isAfter(historyLimit))
                    .map(HistoryCompletion::playerId).collect(java.util.stream.Collectors.toUnmodifiableSet());
            int completedFixtures = jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                    + " WHERE season=? AND kind='FIXTURE_VERIFICATION' AND state='COMPLETED'",
                    Integer.class, SEASON.code());
            int payloadRows = jdbc.queryForObject("SELECT count(*) FROM " + TABLES.payload()
                    + " p JOIN " + TABLES.run() + " r ON r.generation=p.run_generation"
                    + " WHERE r.season=?", Integer.class, SEASON.code());
            long stagedBytes = jdbc.queryForObject("SELECT COALESCE(sum(octet_length(p.body)),0) FROM "
                    + TABLES.payload() + " p JOIN " + TABLES.run() + " r ON r.generation=p.run_generation"
                    + " WHERE r.season=?", Long.class, SEASON.code());
            List<Long> partialGenerations = jdbc.queryForList("SELECT r.generation FROM " + TABLES.run()
                    + " r WHERE r.season=? AND r.kind='FIXTURE_VERIFICATION'"
                    + " AND EXISTS (SELECT 1 FROM " + TABLES.payload() + " b WHERE b.run_generation=r.generation"
                    + " AND b.endpoint_kind='BOOTSTRAP_STATIC') AND NOT EXISTS (SELECT 1 FROM " + TABLES.payload()
                    + " f WHERE f.run_generation=r.generation AND f.endpoint_kind='FIXTURES')",
                    Long.class, SEASON.code());
            int partials = (int) partialGenerations.stream()
                    .filter(generation -> !seededPartialGenerations.contains(generation)).count();
            return new Metrics(firstWorker, driver.firstAdmissionWorker,
                    server.firstHistoryGet(), firstCompleted, completedPlayers, completedWithin60.size(),
                    completedFixtures, driver.claims, driver.deferrals,
                    jdbc.queryForObject("SELECT count(*) FROM " + TABLES.requestAttempt(), Integer.class),
                    server.getsByEndpoint(), payloadRows, stagedBytes, server.returnedGets(),
                    server.returnedRawBytes(), partials, driver.peakNewPartialPairs, driver.dispatches,
                    Duration.between(START, end).toNanos() / 1e9);
        }

        void captureDeferrals(ManualDriver driver) {
            jdbc.query("SELECT generation,attempt_count FROM " + TABLES.run()
                            + " WHERE season=? AND state='PENDING' AND last_error LIKE 'HTTP admission deferred:%'",
                    (rs, row) -> new ClaimAttempt(rs.getLong(1), rs.getInt(2)), SEASON.code())
                    .forEach(attempt -> {
                        if (countedDeferrals.add(attempt)) driver.deferrals++;
                    });
        }

        int dispatchOneWithControlledResponse(String workerId) throws Exception {
            Future<Boolean> future = dispatchWorkers.submit(() -> coordinator.dispatchOne(workerId));
            List<ScheduledResponse> responses = new ArrayList<>();
            while (!future.isDone()) {
                RequestArrival arrival;
                while ((arrival = server.pollArrival(0, TimeUnit.MILLISECONDS)) != null) {
                    responses.add(new ScheduledResponse(arrival, arrival.at().plus(RESPONSE_TIME)));
                }
                for (ScheduledResponse response : List.copyOf(responses)) {
                    if (!response.releaseAt().isAfter(clock.instant())) {
                        response.arrival().release().countDown();
                        responses.remove(response);
                    }
                }
                if (future.isDone()) break;

                Instant nextResponse = responses.stream().map(ScheduledResponse::releaseAt)
                        .min(Instant::compareTo).orElse(null);
                if (nextResponse != null && nextResponse.isAfter(clock.instant())) {
                    clock.set(nextResponse);
                    continue;
                }
                RequestArrival observed = server.pollArrival(
                        Math.min(TimeUnit.MILLISECONDS.toNanos(5), remainingWallNanos()), TimeUnit.NANOSECONDS);
                if (observed != null) {
                    responses.add(new ScheduledResponse(observed, observed.at().plus(RESPONSE_TIME)));
                }
            }
            return future.get(remainingWallNanos(), TimeUnit.NANOSECONDS) ? 1 : 0;
        }

        Instant nextSpacingAt() {
            Timestamp last = jdbc.queryForObject("SELECT last_admitted_at FROM " + TABLES.requestState()
                    + " WHERE singleton=TRUE", Timestamp.class);
            return last == null ? null : last.toInstant().plusSeconds(1);
        }

        int newOutstandingPartialPairs() {
            List<Long> generations = jdbc.queryForList("SELECT r.generation FROM " + TABLES.run()
                    + " r WHERE r.season=? AND r.kind='FIXTURE_VERIFICATION'"
                    + " AND r.state IN ('PENDING','LEASED')"
                    + " AND EXISTS (SELECT 1 FROM " + TABLES.payload() + " b WHERE b.run_generation=r.generation"
                    + " AND b.endpoint_kind='BOOTSTRAP_STATIC') AND NOT EXISTS (SELECT 1 FROM " + TABLES.payload()
                    + " f WHERE f.run_generation=r.generation AND f.endpoint_kind='FIXTURES')",
                    Long.class, SEASON.code());
            return (int) generations.stream().filter(generation -> !seededPartialGenerations.contains(generation))
                    .count();
        }

        private void emergencyStop() {
            watchdogFired.set(true);
            server.releaseAll();
            dispatchWorkers.shutdownNow();
            closeClient();
        }

        private void closeClient() {
            if (clientClosed.compareAndSet(false, true)) http.close();
        }

        void stopDispatchWorkers() throws InterruptedException {
            dispatchWorkers.shutdownNow();
            closeClient();
            server.releaseAll();
            long remaining = Math.min(TimeUnit.SECONDS.toNanos(5), Math.max(1, wallDeadline - System.nanoTime()));
            if (!dispatchWorkers.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
                throw new IllegalStateException("driver cannot yet verify FIFO: workers did not quiesce at cutoff");
            }
        }

        private long remainingWallNanos() {
            long remaining = wallDeadline - System.nanoTime();
            if (remaining <= 0) {
                throw new IllegalStateException("driver cannot yet verify FIFO: workers did not reach "
                        + "an observed response release or completion before the wall watchdog");
            }
            return remaining;
        }

        @Override
        public void close() throws Exception {
            watchdogTask.cancel(false);
            server.releaseAll();
            dispatchWorkers.shutdownNow();
            closeClient();
            try {
                server.close();
            } finally {
                watchdog.shutdownNow();
                boolean workerClean = dispatchWorkers.awaitTermination(5, TimeUnit.SECONDS);
                boolean watchdogClean = watchdog.awaitTermination(5, TimeUnit.SECONDS);
                try {
                    activation.deactivate(fence);
                } finally {
                    clearOwnedRows();
                }
                assertThat(workerClean).as("dispatch workers must stop during cleanup").isTrue();
                assertThat(watchdogClean).as("wall watchdog must stop during cleanup").isTrue();
                assertThat(watchdogFired).as("wall watchdog must not be needed in a completed run").isFalse();
            }
        }
    }

    private static final class ManualDriver {
        private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(5);

        private final Harness harness;
        private final Instant stopAt = START.plus(VIRTUAL_LIMIT);
        private final Instant[] nextAt = { START, START };
        private final Map<Integer, PendingDispatch> active = new LinkedHashMap<>();
        private final List<ScheduledResponse> responses = new ArrayList<>();
        private int nextTieWorker;
        private int dispatches;
        private int claims;
        private int deferrals;
        private int firstAdmissionWorker;
        private int peakNewPartialPairs;

        ManualDriver(Harness harness) {
            this.harness = harness;
            nextTieWorker = harness.firstWorker;
        }

        void run() throws Exception {
            setToScheduledStart(harness.firstWorker);
            PendingDispatch first = start(harness.firstWorker);
            active.put(first.worker(), first);
            RequestArrival firstArrival = awaitFirstArrival(first);
            schedule(firstArrival);
            observeAdmission(harness.firstWorker);

            int secondWorker = 3 - harness.firstWorker;
            if (dispatches < DISPATCH_LIMIT && !nextAt[secondWorker - 1].isAfter(harness.clock.instant())) {
                PendingDispatch second = start(secondWorker);
                active.put(second.worker(), second);
                nextTieWorker = harness.firstWorker;
            }

            while (true) {
                harness.remainingWallNanos();
                drainArrivals();
                releaseDueResponses();
                collectCompleted();
                samplePartialPairs();
                harness.captureDeferrals(this);
                if (harness.clock.instant().isBefore(stopAt)) startReadyWorkers();

                if (active.isEmpty() && (!hasActiveRuns() || dispatches >= DISPATCH_LIMIT)) break;
                if (!harness.clock.instant().isBefore(stopAt) && active.isEmpty()) break;

                Event next = nextEvent();
                if (next == null) {
                    waitForEvent();
                    continue;
                }
                if (next.at().isAfter(stopAt)) {
                    if (active.isEmpty()) {
                        harness.clock.set(stopAt);
                    } else {
                        quiesceAtCutoff();
                        break;
                    }
                    continue;
                }
                if (!active.isEmpty() && next.kind() != EventKind.RESPONSE) {
                    throw new IllegalStateException("driver cannot yet verify FIFO: active worker "
                            + "has no observed response release");
                }
                if (next.at().isAfter(harness.clock.instant())) harness.clock.set(next.at());
            }
            samplePartialPairs();
            if (!active.isEmpty()) {
                throw new IllegalStateException("driver cannot yet verify FIFO: workers remain active at snapshot");
            }
        }

        private RequestArrival awaitFirstArrival(PendingDispatch dispatch) throws Exception {
            while (true) {
                RequestArrival arrival = harness.server.pollArrival(
                        Math.min(POLL_NANOS, harness.remainingWallNanos()), TimeUnit.NANOSECONDS);
                if (arrival != null) return arrival;
                if (dispatch.future().isDone()) {
                    dispatch.future().get();
                    throw new AssertionError("first worker completed without a server-observed GET");
                }
            }
        }

        private void drainArrivals() throws Exception {
            RequestArrival arrival;
            while ((arrival = harness.server.pollArrival(0, TimeUnit.MILLISECONDS)) != null) schedule(arrival);
        }

        private void schedule(RequestArrival arrival) {
            responses.add(new ScheduledResponse(arrival, arrival.at().plus(RESPONSE_TIME)));
        }

        private void releaseDueResponses() {
            Instant now = harness.clock.instant();
            for (ScheduledResponse response : List.copyOf(responses)) {
                if (!response.releaseAt().isAfter(now)) {
                    response.arrival().release().countDown();
                    responses.remove(response);
                }
            }
        }

        private void collectCompleted() throws Exception {
            for (PendingDispatch dispatch : List.copyOf(active.values())) {
                if (!dispatch.future().isDone()) continue;
                DispatchCompletion result = dispatch.future().get();
                if (result.claimed()) claims++;
                nextAt[result.worker() - 1] = result.completedAt().plus(WORKER_DELAY);
                active.remove(result.worker());
            }
        }

        private void startReadyWorkers() {
            while (dispatches < DISPATCH_LIMIT && hasActiveRuns()) {
                int worker = readyWorker();
                if (worker == 0) return;
                PendingDispatch dispatch = start(worker);
                active.put(worker, dispatch);
                nextTieWorker = 3 - worker;
            }
        }

        private int readyWorker() {
            List<Integer> ready = new ArrayList<>(2);
            for (int worker : List.of(1, 2)) {
                if (!active.containsKey(worker) && !nextAt[worker - 1].isAfter(harness.clock.instant())) {
                    ready.add(worker);
                }
            }
            if (ready.isEmpty()) return 0;
            if (ready.size() == 1) return ready.getFirst();
            return ready.contains(nextTieWorker) ? nextTieWorker : ready.getFirst();
        }

        private Event nextEvent() {
            Event next = null;
            for (ScheduledResponse response : responses) {
                next = earlier(next, new Event(response.releaseAt(), EventKind.RESPONSE));
            }
            if (!active.isEmpty()) return next;
            if (dispatches < DISPATCH_LIMIT) {
                for (int worker : List.of(1, 2)) {
                    next = earlier(next, new Event(nextAt[worker - 1], EventKind.WORKER_START));
                }
            }
            return next;
        }

        private Event earlier(Event current, Event candidate) {
            return current == null || candidate.at().isBefore(current.at()) ? candidate : current;
        }

        private void waitForEvent() throws Exception {
            RequestArrival arrival = harness.server.pollArrival(
                    Math.min(POLL_NANOS, harness.remainingWallNanos()), TimeUnit.NANOSECONDS);
            if (arrival != null) schedule(arrival);
        }

        private void quiesceAtCutoff() throws InterruptedException {
            harness.stopDispatchWorkers();
            active.clear();
            responses.clear();
        }

        private void samplePartialPairs() {
            peakNewPartialPairs = Math.max(peakNewPartialPairs, harness.newOutstandingPartialPairs());
        }

        private boolean hasActiveRuns() {
            return jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run()
                    + " WHERE season=? AND state IN ('PENDING','LEASED')", Integer.class, SEASON.code()) > 0;
        }

        private void setToScheduledStart(int worker) {
            Instant due = nextAt[worker - 1];
            if (due.isAfter(harness.clock.instant())) harness.clock.set(due);
        }

        private PendingDispatch start(int worker) {
            if (dispatches >= DISPATCH_LIMIT) throw new IllegalStateException("dispatch ceiling exceeded");
            dispatches++;
            Future<DispatchCompletion> future = harness.dispatchWorkers.submit(() -> {
                boolean claimed = harness.coordinator.dispatchOne(Integer.toString(worker));
                return new DispatchCompletion(worker, claimed, harness.clock.instant());
            });
            return new PendingDispatch(worker, future);
        }

        private void observeAdmission(int worker) {
            if (firstAdmissionWorker == 0) firstAdmissionWorker = worker;
        }

        private enum EventKind { RESPONSE, WORKER_START }

        private record Event(Instant at, EventKind kind) { }

        private record PendingDispatch(int worker, Future<DispatchCompletion> future) { }
    }

    private static final class RequestServer implements AutoCloseable {
        private final MutableClock clock;
        private final HttpServer server;
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private final BlockingQueue<RequestArrival> arrivals = new LinkedBlockingQueue<>();
        private final List<RequestArrival> observed = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final SourceData source;
        private final AtomicReference<Instant> firstHistoryGet = new AtomicReference<>();
        private final AtomicReference<Integer> returnedGets = new AtomicReference<>(0);
        private final AtomicReference<Long> returnedRawBytes = new AtomicReference<>(0L);

        RequestServer(SourceData source, MutableClock clock) throws IOException {
            this.source = source;
            this.clock = clock;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/api/", this::handle);
            server.start();
        }

        URI baseUri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/");
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            SourceData.Body body;
            try {
                body = source.response(path);
            } catch (RuntimeException invalid) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            RequestArrival arrival = new RequestArrival(path, body.kind(), body.bytes(), clock.instant(),
                    new CountDownLatch(1));
            observed.add(arrival);
            if (body.kind() == EndpointKind.ELEMENT_SUMMARY) firstHistoryGet.compareAndSet(null, arrival.at());
            arrivals.add(arrival);
            try {
                if (!arrival.release().await(90, TimeUnit.SECONDS)) {
                    exchange.close();
                    return;
                }
                byte[] bytes = arrival.body();
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                returnedGets.updateAndGet(value -> value + 1);
                returnedRawBytes.updateAndGet(value -> value + bytes.length);
                exchange.close();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                exchange.close();
            }
        }

        RequestArrival pollArrival(long timeout, TimeUnit unit) throws InterruptedException {
            return arrivals.poll(timeout, unit);
        }

        RequestArrival awaitArrival(long wallDeadline) throws Exception {
            while (System.nanoTime() < wallDeadline) {
                RequestArrival result = arrivals.poll(10, TimeUnit.MILLISECONDS);
                if (result != null) return result;
            }
            return null;
        }

        void releaseAll() {
            observed.forEach(arrival -> arrival.release().countDown());
        }

        Instant firstHistoryGet() { return firstHistoryGet.get(); }
        int returnedGets() { return returnedGets.get(); }
        long returnedRawBytes() { return returnedRawBytes.get(); }

        Map<EndpointKind, Integer> getsByEndpoint() {
            Map<EndpointKind, Integer> result = new LinkedHashMap<>();
            observed.forEach(arrival -> result.merge(arrival.kind(), 1, Integer::sum));
            return Map.copyOf(result);
        }

        @Override
        public void close() throws Exception {
            releaseAll();
            server.stop(0);
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS))
                    .as("loopback HTTP handlers must stop during cleanup").isTrue();
        }
    }

    private record RequestArrival(String path, EndpointKind kind, byte[] body, Instant at,
                                 CountDownLatch release) { }

    private static final class SourceData {
        private final byte[] bootstrap;
        private final byte[] fixtures;
        private final List<Integer> playerIds;
        private final Map<Integer, byte[]> liveByEvent;
        private final Map<Integer, byte[]> histories;

        private SourceData(byte[] bootstrap, byte[] fixtures, List<Integer> playerIds,
                           Map<Integer, byte[]> liveByEvent, Map<Integer, byte[]> histories) {
            this.bootstrap = bootstrap;
            this.fixtures = fixtures;
            this.playerIds = playerIds;
            this.liveByEvent = liveByEvent;
            this.histories = histories;
        }

        static SourceData load() throws Exception {
            ObjectNode bootstrap = (ObjectNode) JSON.readTree(resource("bootstrap-static.json"));
            ArrayNode fixtureRows = (ArrayNode) JSON.readTree(resource("fixtures.json"));
            Map<Integer, List<JsonNode>> sourcePlayersByTeam = new TreeMap<>();
            bootstrap.withArray("elements").forEach(player -> sourcePlayersByTeam
                    .computeIfAbsent(player.get("team").intValue(), ignored -> new ArrayList<>()).add(player));
            ArrayNode selected = JSON.createArrayNode();
            Map<Integer, List<Integer>> playersByTeam = new TreeMap<>();
            Map<Integer, Integer> playerTeams = new HashMap<>();
            List<Integer> playerIds = new ArrayList<>();
            for (Map.Entry<Integer, List<JsonNode>> entry : sourcePlayersByTeam.entrySet()) {
                int count = entry.getKey() <= 8 ? 4 : 3;
                List<Integer> ids = entry.getValue().stream().limit(count)
                        .map(player -> player.get("id").intValue()).sorted().toList();
                playersByTeam.put(entry.getKey(), ids);
                ids.forEach(id -> {
                    playerIds.add(id);
                    playerTeams.put(id, entry.getKey());
                });
            }
            Set<Integer> selectedIds = Set.copyOf(playerIds);
            ArrayNode trimmed = JSON.createArrayNode();
            bootstrap.withArray("elements").forEach(player -> {
                if (selectedIds.contains(player.get("id").intValue())) trimmed.add(player);
            });
            bootstrap.set("elements", trimmed);
            bootstrap.withArray("events").forEach(eventNode -> {
                ObjectNode event = (ObjectNode) eventNode;
                int id = event.get("id").intValue();
                event.put("finished", id <= 5).put("data_checked", id <= 5)
                        .put("is_current", id == 5).put("is_next", id == 6);
            });

            for (int index = 0; index < 50; index++) {
                ObjectNode fixture = (ObjectNode) fixtureRows.get(index);
                int home = fixture.get("team_h").intValue();
                int away = fixture.get("team_a").intValue();
                fixture.put("started", true).put("finished", true).put("finished_provisional", true)
                        .put("minutes", 90).put("team_h_score", 0).put("team_a_score", 0);
                ArrayNode stats = fixture.putArray("stats");
                ObjectNode minutes = stats.addObject().put("identifier", "minutes");
                addParticipants(minutes.putArray("h"), playersByTeam.get(home));
                addParticipants(minutes.putArray("a"), playersByTeam.get(away));
            }
            byte[] bootstrapBytes = JSON.writeValueAsBytes(bootstrap);
            byte[] fixtureBytes = JSON.writeValueAsBytes(fixtureRows);
            Map<Integer, byte[]> lives = createLiveManifests(fixtureRows, playersByTeam);
            Map<Integer, byte[]> histories = createHistories(fixtureRows, playerIds, playerTeams, playersByTeam);
            return new SourceData(bootstrapBytes, fixtureBytes, List.copyOf(playerIds),
                    Map.copyOf(lives), Map.copyOf(histories));
        }

        byte[] bootstrapBytes() { return bootstrap; }
        byte[] fixtureBytes() { return fixtures; }
        List<Integer> playerIds() { return playerIds; }

        Body response(String path) {
            if (path.equals("/api/bootstrap-static/")) return new Body(EndpointKind.BOOTSTRAP_STATIC, bootstrap);
            if (path.equals("/api/fixtures/")) return new Body(EndpointKind.FIXTURES, fixtures);
            if (path.startsWith("/api/event/") && path.endsWith("/live/")) {
                int event = Integer.parseInt(path.substring("/api/event/".length(), path.length() - "/live/".length()));
                return new Body(EndpointKind.EVENT_LIVE, liveByEvent.getOrDefault(event,
                        "{\"elements\":[]}".getBytes(StandardCharsets.UTF_8)));
            }
            if (path.startsWith("/api/element-summary/") && path.endsWith("/")) {
                int player = Integer.parseInt(path.substring("/api/element-summary/".length(), path.length() - 1));
                byte[] body = histories.get(player);
                if (body == null) throw new IllegalArgumentException("Unknown pinned player id");
                return new Body(EndpointKind.ELEMENT_SUMMARY, body);
            }
            throw new IllegalArgumentException("Unsupported local FPL path");
        }

        private static void addParticipants(ArrayNode rows, List<Integer> ids) {
            ids.stream().limit(2).forEach(id -> rows.addObject().put("element", id).put("value", 90));
        }

        private static Map<Integer, byte[]> createLiveManifests(ArrayNode fixtures,
                                                                Map<Integer, List<Integer>> playersByTeam)
                throws IOException {
            Map<Integer, Map<Integer, ObjectNode>> events = new TreeMap<>();
            for (JsonNode fixture : fixtures) {
                if (!fixture.get("finished").booleanValue()) continue;
                int eventId = fixture.get("event").intValue();
                int fixtureId = fixture.get("id").intValue();
                for (int team : List.of(fixture.get("team_h").intValue(), fixture.get("team_a").intValue())) {
                    for (int player : playersByTeam.get(team).stream().limit(2).toList()) {
                        ObjectNode element = events.computeIfAbsent(eventId, ignored -> new TreeMap<>())
                                .computeIfAbsent(player, id -> {
                                    ObjectNode created = JSON.createObjectNode().put("id", id);
                                    created.putArray("explain");
                                    return created;
                                });
                        ObjectNode explanation = element.withArray("explain").addObject().put("fixture", fixtureId);
                        explanation.putArray("stats").addObject().put("identifier", "minutes")
                                .put("value", 90).put("points", 0).put("points_modification", 0);
                    }
                }
            }
            Map<Integer, byte[]> result = new TreeMap<>();
            for (Map.Entry<Integer, Map<Integer, ObjectNode>> event : events.entrySet()) {
                ObjectNode root = JSON.createObjectNode();
                ArrayNode elements = root.putArray("elements");
                event.getValue().values().forEach(elements::add);
                result.put(event.getKey(), JSON.writeValueAsBytes(root));
            }
            return result;
        }

        private static Map<Integer, byte[]> createHistories(ArrayNode fixtures, List<Integer> players,
                                                              Map<Integer, Integer> playerTeams,
                                                              Map<Integer, List<Integer>> playersByTeam)
                throws IOException {
            Map<Integer, byte[]> result = new TreeMap<>();
            for (int player : players) {
                int team = playerTeams.get(player);
                boolean participant = playersByTeam.get(team).indexOf(player) < 2;
                ObjectNode root = JSON.createObjectNode();
                ArrayNode history = root.putArray("history");
                for (JsonNode fixture : fixtures) {
                    if (!fixture.get("finished").booleanValue()) continue;
                    int home = fixture.get("team_h").intValue();
                    int away = fixture.get("team_a").intValue();
                    if (team != home && team != away) continue;
                    boolean wasHome = team == home;
                    ObjectNode row = history.addObject().put("element", player)
                            .put("fixture", fixture.get("id").intValue())
                            .put("round", fixture.get("event").intValue())
                            .put("opponent_team", wasHome ? away : home).put("was_home", wasHome)
                            .put("kickoff_time", fixture.get("kickoff_time").textValue())
                            .put("minutes", participant ? 90 : 0).put("total_points", 0).put("bonus", 0)
                            .put("bps", 0).put("goals_scored", 0).put("assists", 0)
                            .put("clean_sheets", 0).put("goals_conceded", 0).put("own_goals", 0)
                            .put("penalties_saved", 0).put("penalties_missed", 0).put("saves", 0)
                            .put("yellow_cards", 0).put("red_cards", 0).put("starts", participant ? 1 : 0)
                            .put("value", 50).put("selected", 0).put("transfers_in", 0).put("transfers_out", 0)
                            .put("expected_goals", "0.00").put("expected_assists", "0.00")
                            .put("expected_goal_involvements", "0.00").put("expected_goals_conceded", "0.00")
                            .put("influence", "0.0").put("creativity", "0.0").put("threat", "0.0")
                            .put("ict_index", "0.0").put("clearances_blocks_interceptions", 0)
                            .put("recoveries", 0).put("tackles", 0).put("defensive_contribution", 0);
                }
                result.put(player, JSON.writeValueAsBytes(root));
            }
            return result;
        }

        private static InputStream resource(String name) {
            InputStream input = OwnedMatchdayAdmissionProgressIntegrationTest.class
                    .getResourceAsStream("/owned-sync-source-fixtures/" + name);
            if (input == null) throw new IllegalArgumentException("Missing pinned " + name);
            return input;
        }

        private static byte[] readResource(String name) throws IOException {
            try (InputStream input = resource(name)) {
                return input.readAllBytes();
            }
        }

        private record Body(EndpointKind kind, byte[] bytes) { }
    }

    private static String resource(String name) throws IOException {
        return new String(SourceData.readResource(name), StandardCharsets.UTF_8);
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> current;

        MutableClock(Instant initial) { current = new AtomicReference<>(initial); }
        void set(Instant value) { current.set(value); }
        void advance(Duration duration) { current.updateAndGet(value -> value.plus(duration)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current.get(); }
    }
}
