package fi.karivanttinen.fplassistant.sync;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import javax.sql.DataSource;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import fi.karivanttinen.fplassistant.sync.OwnedSyncHttpClient.DeferredResult;
import fi.karivanttinen.fplassistant.sync.OwnedSyncHttpClient.Failure;
import fi.karivanttinen.fplassistant.sync.OwnedSyncHttpClient.FailureKind;
import fi.karivanttinen.fplassistant.sync.OwnedSyncHttpClient.Response;
import fi.karivanttinen.fplassistant.sync.OwnedSyncDbOperations.Outcome;
import fi.karivanttinen.fplassistant.sync.OwnedSyncRequestBudget.DeferredReason;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.Endpoint;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.EndpointKind;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.FutureFloor;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.HttpFenceResult;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.Ready;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.apache.hc.core5.io.CloseMode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class OwnedSyncHttpClientIntegrationTest {

    private static final OwnedSyncTables TABLES = OwnedSyncTables.tests();
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;

    private final List<HttpServer> servers = new ArrayList<>();
    private final List<OwnedSyncHttpClient> transports = new ArrayList<>();
    private MutableClock budgetClock;
    private OwnedSyncRequestBudget budget;

    @BeforeAll
    static void initializeDatabase() {
        dataSource = new DriverManagerDataSource(
                requiredEnv("DATABASE_URL"), requiredEnv("DB_USER"), requiredEnv("DB_PASSWORD"));
        refuseProductionBeforeAnyWrite(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        new OwnedSyncSchema(jdbc, TABLES).initialize();
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM " + TABLES.requestAttempt());
        jdbc.update("UPDATE " + TABLES.requestState() + " SET last_admitted_at = NULL, cooldown_until = NULL");
        budgetClock = new MutableClock(Instant.parse("2097-08-15T12:00:00Z"));
        budget = new OwnedSyncRequestBudget(jdbc, tx, budgetClock, TABLES);
    }

    @AfterEach
    void stopServers() {
        servers.forEach(server -> server.stop(0));
        transports.forEach(OwnedSyncHttpClient::close);
    }

    @Test
    void selectsOnlyTheFourPublicSourcePathsAndReturnsRawReads() throws Exception {
        List<String> paths = new ArrayList<>();
        HttpServer server = server(exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            respond(exchange, 200, "{}".getBytes(StandardCharsets.UTF_8));
        });
        OwnedSyncHttpClient transport = transport(server, Duration.ofSeconds(2));

        List<Endpoint> endpoints = List.of(Endpoint.bootstrap(), Endpoint.fixtures(),
                Endpoint.eventLive(3), Endpoint.elementSummary(557));
        for (Endpoint endpoint : endpoints) {
            Response response = (Response) transport.get(endpoint, 1);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("{}".getBytes(StandardCharsets.UTF_8));
            assertThat(response.read().finishedAt()).isAfterOrEqualTo(response.read().startedAt());
            budgetClock.advance(Duration.ofSeconds(1));
        }

        assertThat(paths).containsExactly("/api/bootstrap-static/", "/api/fixtures/",
                "/api/event/3/live/", "/api/element-summary/557/");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void productionFixedHostRequiresTheFencedOverload() {
        OwnedSyncHttpClient production = new OwnedSyncHttpClient(budget);
        transports.add(production);

        assertThatThrownBy(() -> production.get(Endpoint.fixtures(), 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("caller fence");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.requestAttempt(), Integer.class)).isZero();
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void fenceCallbacksBracketAdmissionOnSupervisedDbWorkersAndFinishTransactionsBeforeHttp() throws Exception {
        AtomicInteger fenceCalls = new AtomicInteger();
        AtomicInteger transactionCompletions = new AtomicInteger();
        List<Thread> callbackThreads = new CopyOnWriteArrayList<>();
        HttpServer server = server(exchange -> {
            assertThat(fenceCalls).hasValue(2);
            assertThat(transactionCompletions).hasValue(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.requestAttempt()
                    + " WHERE completed_at IS NULL", Integer.class)).isEqualTo(1);
            respond(exchange, 200, new byte[0]);
        });
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/");
        OwnedSyncHttpClient transport = new OwnedSyncHttpClient(
                budget, base, Duration.ofSeconds(2), budgetClock);
        transports.add(transport);
        Thread caller = Thread.currentThread();
        java.util.function.Supplier<HttpFenceResult> fence = () -> {
            callbackThreads.add(Thread.currentThread());
            return tx.execute(status -> {
                int ordinal = fenceCalls.incrementAndGet();
                int attempts = jdbc.queryForObject("SELECT count(*) FROM " + TABLES.requestAttempt(),
                        Integer.class);
                assertThat(attempts).isEqualTo(ordinal == 1 ? 0 : 1);
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCompletion(int completionStatus) {
                        transactionCompletions.incrementAndGet();
                    }
                });
                return readyFence();
            });
        };

        assertThat(transport.get(Endpoint.fixtures(), 1, fence)).isInstanceOf(Response.class);

        assertThat(callbackThreads).hasSize(2).allSatisfy(thread -> {
            assertThat(thread).isNotSameAs(caller);
            assertThat(thread.getName()).startsWith("owned-sync-db-");
        });
        assertCompletedPermit();
    }

    @Test
    void futureFloorBeforeAdmissionReturnsItsDueWithoutCreatingPermitOrGetting() throws Exception {
        Instant nextDue = budgetClock.instant().plus(Duration.ofMinutes(8));
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server(exchange -> {
            calls.incrementAndGet();
            respond(exchange, 200, new byte[0]);
        });
        OwnedSyncHttpClient transport = transport(server, Duration.ofSeconds(2));

        DeferredResult result = (DeferredResult) transport.get(Endpoint.fixtures(), 1,
                () -> new FutureFloor(nextDue));

        assertThat(result.nextDue()).isEqualTo(nextDue);
        assertThat(result.reason()).isEqualTo(DeferredReason.HTTP_NOT_SUBMITTED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.requestAttempt(), Integer.class)).isZero();
        assertThat(calls).hasValue(0);
    }

    @Test
    void futureFloorAfterAdmissionSettlesPermitAndReturnsItsExactDueWithoutGetting() throws Exception {
        Instant nextDue = budgetClock.instant().plus(Duration.ofMinutes(8));
        AtomicInteger fences = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server(exchange -> {
            calls.incrementAndGet();
            respond(exchange, 200, new byte[0]);
        });
        OwnedSyncHttpClient transport = transport(server, Duration.ofSeconds(2));

        DeferredResult result = (DeferredResult) transport.get(Endpoint.fixtures(), 1,
                () -> fences.incrementAndGet() == 1 ? readyFence() : new FutureFloor(nextDue));

        assertThat(result.nextDue()).isEqualTo(nextDue);
        assertThat(result.reason()).isEqualTo(DeferredReason.HTTP_NOT_SUBMITTED);
        assertThat(fences).hasValue(2);
        assertCompletedPermit();
        assertThat(calls).hasValue(0);
    }

    @Test
    void lostFenceBeforePermitSkipsAdmissionAndLostFenceAfterPermitSettlesNoFetch() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger serverCalls = new AtomicInteger();
        HttpServer server = server(exchange -> {
            serverCalls.incrementAndGet();
            respond(exchange, 200, new byte[0]);
        });
        OwnedSyncHttpClient transport = transport(server, Duration.ofSeconds(2));

        DeferredResult before = (DeferredResult) transport.get(Endpoint.fixtures(), 1, () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("lease lost");
        });
        assertThat(before.reason()).isEqualTo(DeferredReason.DB_ADMISSION_UNCERTAIN);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.requestAttempt(), Integer.class)).isZero();

        DeferredResult after = (DeferredResult) transport.get(Endpoint.fixtures(), 1, () -> {
            if (calls.incrementAndGet() == 2) return readyFence();
            throw new IllegalStateException("lease lost after admission");
        });
        assertThat(after.reason()).isEqualTo(DeferredReason.NO_FETCH_UNCERTAIN);
        assertCompletedPermit();
        assertThat(serverCalls).hasValue(0);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void sendGateRejectsReadyFenceThatExpiresWhileExchangeWorkerIsWaiting() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server(exchange -> {
            calls.incrementAndGet();
            respond(exchange, 200, new byte[0]);
        });
        DelayedExecutor delayed = new DelayedExecutor();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/");
        OwnedSyncHttpClient transport = new OwnedSyncHttpClient(budget, base,
                Duration.ofSeconds(5), budgetClock, delayed);
        transports.add(transport);
        AtomicInteger fences = new AtomicInteger();
        AtomicReference<OwnedSyncHttpClient.Result> result = new AtomicReference<>();
        Thread caller = Thread.ofPlatform().start(() -> result.set(transport.get(Endpoint.fixtures(), 1,
                () -> fences.incrementAndGet() == 1 ? readyFence()
                        : new Ready(budgetClock.instant().plusSeconds(50), budgetClock.instant()))));

        assertThat(delayed.awaitSubmitted()).isTrue();
        budgetClock.advance(Duration.ofSeconds(51));
        delayed.release();
        caller.join(3_000);

        assertThat(caller.isAlive()).isFalse();
        assertThat(result.get()).isInstanceOfSatisfying(DeferredResult.class,
                deferred -> assertThat(deferred.reason()).isEqualTo(DeferredReason.HTTP_NOT_SUBMITTED));
        assertThat(fences).hasValue(2);
        assertCompletedPermit();
        assertThat(calls).hasValue(0);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void timedOutPostAdmissionFenceSettlesPermitAndLateReadyCannotSend() throws Exception {
        CountDownLatch stalledFence = new CountDownLatch(1);
        CountDownLatch releaseFence = new CountDownLatch(1);
        CountDownLatch fenceFinished = new CountDownLatch(1);
        AtomicInteger fences = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server(exchange -> {
            calls.incrementAndGet();
            respond(exchange, 200, new byte[0]);
        });
        OwnedSyncDbOperations operations = new OwnedSyncDbOperations(Duration.ofMillis(100));
        OwnedSyncHttpClient transport = transport(
                server, Duration.ofSeconds(2), operations, Duration.ofSeconds(3));
        try {
            DeferredResult result = (DeferredResult) transport.get(Endpoint.fixtures(), 1, () -> {
                if (fences.incrementAndGet() == 1) return readyFence();
                stalledFence.countDown();
                try {
                    releaseFence.await();
                    return readyFence();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                } finally {
                    fenceFinished.countDown();
                }
            });

            assertThat(stalledFence.getCount()).isZero();
            assertThat(result.reason()).isEqualTo(DeferredReason.NO_FETCH_UNCERTAIN);
            assertCompletedPermit();
            assertThat(calls).hasValue(0);
            releaseFence.countDown();
            assertThat(fenceFinished.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(fences).hasValue(2);
            assertThat(calls).hasValue(0);
        } finally {
            releaseFence.countDown();
            operations.close();
        }
    }

    @Test
    void deferredAdmissionMakesNoRequestAndReturnsCause() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server(exchange -> {
            calls.incrementAndGet();
            respond(exchange, 200, new byte[0]);
        });
        OwnedSyncHttpClient transport = transport(server, Duration.ofSeconds(2));

        assertThat(transport.get(Endpoint.fixtures(), 1)).isInstanceOf(Response.class);
        DeferredResult deferred = (DeferredResult) transport.get(Endpoint.fixtures(), 1);

        assertThat(deferred.reason()).isEqualTo(DeferredReason.MINIMUM_SPACING);
        assertThat(deferred.nextDue()).isEqualTo(budgetClock.instant().plusSeconds(1));
        assertThat(calls).hasValue(1);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void serverObservedGetStartsStayOneSecondApartAfterDelayedFirstStart() throws Exception {
        Clock actualClock = Clock.systemUTC();
        budget = new OwnedSyncRequestBudget(jdbc, tx, actualClock, TABLES);
        List<ServerObservation> observations = new CopyOnWriteArrayList<>();
        HttpServer server = server(exchange -> {
            observations.add(new ServerObservation(Instant.now(), System.nanoTime()));
            respond(exchange, 200, new byte[0]);
        });
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/");
        DelayedExecutor delayed = new DelayedExecutor();
        OwnedSyncHttpClient firstTransport = new OwnedSyncHttpClient(budget, base,
                Duration.ofSeconds(5), actualClock, delayed);
        transports.add(firstTransport);
        OwnedSyncHttpClient secondTransport = new OwnedSyncHttpClient(budget, base, Duration.ofSeconds(5));
        transports.add(secondTransport);
        AtomicReference<OwnedSyncHttpClient.Result> firstResult = new AtomicReference<>();
        Thread firstCaller = Thread.ofPlatform().start(
                () -> firstResult.set(firstTransport.get(Endpoint.fixtures(), 1)));

        assertThat(delayed.awaitSubmitted()).isTrue();
        Instant admittedAt = jdbc.queryForObject("SELECT admitted_at FROM " + TABLES.requestAttempt(),
                java.sql.Timestamp.class).toInstant();
        DeferredResult overlapping = (DeferredResult) secondTransport.get(Endpoint.fixtures(), 1);
        assertThat(overlapping.reason()).isEqualTo(DeferredReason.UNSETTLED);
        assertThat(observations).isEmpty();

        LockSupport.parkNanos(Duration.ofMillis(800).toNanos());
        delayed.release();
        firstCaller.join(5_000);
        assertThat(firstCaller.isAlive()).isFalse();
        assertThat(firstResult.get()).isInstanceOf(Response.class);
        assertThat(delayed.awaitFinished()).isTrue();
        assertThat(observations).hasSize(1);
        assertThat(Duration.between(admittedAt, observations.get(0).observedAt()))
                .isGreaterThanOrEqualTo(Duration.ofMillis(750));

        DeferredResult spacing = (DeferredResult) secondTransport.get(Endpoint.fixtures(), 1);
        assertThat(spacing.reason()).isEqualTo(DeferredReason.MINIMUM_SPACING);
        Instant completedAt = jdbc.queryForObject("SELECT completed_at FROM " + TABLES.requestAttempt(),
                java.sql.Timestamp.class).toInstant();
        assertThat(spacing.nextDue()).isEqualTo(completedAt.plusSeconds(1));
        assertThat(budget.nextAdmissionHint()).isEmpty();
        assertThat(observations).hasSize(1);

        long waitNanos = Duration.between(Instant.now(), spacing.nextDue().plusMillis(25)).toNanos();
        if (waitNanos > 0) LockSupport.parkNanos(waitNanos);
        assertThat(secondTransport.get(Endpoint.fixtures(), 1)).isInstanceOf(Response.class);

        assertThat(observations).hasSize(2);
        assertThat(observations.get(1).monotonicNanos() - observations.get(0).monotonicNanos())
                .isGreaterThanOrEqualTo(Duration.ofSeconds(1).toNanos());
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void delayed800MillisecondResponseUsesMinimumSpacingNotCooldown() throws Exception {
        Clock actualClock = Clock.systemUTC();
        budget = new OwnedSyncRequestBudget(jdbc, tx, actualClock, TABLES);
        List<ServerObservation> observations = new CopyOnWriteArrayList<>();
        CountDownLatch firstRequestStarted = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server(exchange -> {
            observations.add(new ServerObservation(Instant.now(), System.nanoTime()));
            if (calls.getAndIncrement() == 0) {
                firstRequestStarted.countDown();
                LockSupport.parkNanos(Duration.ofMillis(800).toNanos());
            }
            respond(exchange, 200, new byte[0]);
        });
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/");
        OwnedSyncHttpClient firstTransport = new OwnedSyncHttpClient(budget, base,
                Duration.ofSeconds(5), actualClock);
        transports.add(firstTransport);
        OwnedSyncHttpClient secondTransport = new OwnedSyncHttpClient(budget, base,
                Duration.ofSeconds(5), actualClock);
        transports.add(secondTransport);
        AtomicReference<OwnedSyncHttpClient.Result> firstResult = new AtomicReference<>();
        Thread firstCaller = Thread.ofPlatform().start(
                () -> firstResult.set(firstTransport.get(Endpoint.fixtures(), 1)));

        assertThat(firstRequestStarted.await(2, TimeUnit.SECONDS)).isTrue();
        DeferredResult overlapping = (DeferredResult) secondTransport.get(Endpoint.fixtures(), 1);
        assertThat(overlapping.reason()).isEqualTo(DeferredReason.UNSETTLED);
        firstCaller.join(5_000);
        assertThat(firstCaller.isAlive()).isFalse();
        assertThat(firstResult.get()).isInstanceOf(Response.class);

        Instant completedAt = jdbc.queryForObject("SELECT completed_at FROM " + TABLES.requestAttempt(),
                java.sql.Timestamp.class).toInstant();
        assertThat(Duration.between(observations.getFirst().observedAt(), completedAt))
                .isGreaterThanOrEqualTo(Duration.ofMillis(750));
        DeferredResult spacing = (DeferredResult) secondTransport.get(Endpoint.fixtures(), 1);
        assertThat(spacing.reason()).isEqualTo(DeferredReason.MINIMUM_SPACING);
        assertThat(spacing.nextDue()).isEqualTo(completedAt.plusSeconds(1));
        assertThat(budget.nextAdmissionHint()).isEmpty();

        long waitNanos = Duration.between(Instant.now(), spacing.nextDue().plusMillis(25)).toNanos();
        if (waitNanos > 0) LockSupport.parkNanos(waitNanos);
        assertThat(secondTransport.get(Endpoint.fixtures(), 1)).isInstanceOf(Response.class);
        assertThat(observations).hasSize(2);
        assertThat(observations.get(1).monotonicNanos() - observations.get(0).monotonicNanos())
                .isGreaterThanOrEqualTo(Duration.ofSeconds(1).toNanos());
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void queuedFollowersAreFifoInBothArrivalOrdersAndSpaceFromSettlement() throws Exception {
        Clock realClock = Clock.systemUTC();
        budget = new OwnedSyncRequestBudget(jdbc, tx, realClock, TABLES);

        for (int order = 0; order < 2; order++) {
            if (order > 0) {
                jdbc.update("DELETE FROM " + TABLES.requestAttempt());
                jdbc.update("UPDATE " + TABLES.requestState()
                        + " SET last_admitted_at = NULL, cooldown_until = NULL");
                budget = new OwnedSyncRequestBudget(jdbc, tx, realClock, TABLES);
            }
            Endpoint firstEndpoint = order == 0 ? Endpoint.fixtures() : Endpoint.bootstrap();
            Endpoint secondEndpoint = order == 0 ? Endpoint.bootstrap() : Endpoint.fixtures();
            List<ServerObservation> observations = new CopyOnWriteArrayList<>();
            List<String> paths = new CopyOnWriteArrayList<>();
            CountDownLatch firstRequest = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            AtomicInteger requestCount = new AtomicInteger();
            HttpServer server = server(exchange -> {
                observations.add(new ServerObservation(Instant.now(), System.nanoTime()));
                paths.add(exchange.getRequestURI().getPath());
                if (requestCount.getAndIncrement() == 0) {
                    firstRequest.countDown();
                    awaitIgnoringInterrupts(releaseFirst);
                }
                respond(exchange, 200, new byte[0]);
            });
            OwnedSyncHttpClient transport = transport(server, Duration.ofSeconds(5));
            AtomicReference<OwnedSyncHttpClient.Result> firstResult = new AtomicReference<>();
            AtomicReference<OwnedSyncHttpClient.Result> secondResult = new AtomicReference<>();
            Thread firstCaller = Thread.ofPlatform().start(() -> firstResult.set(transport.get(firstEndpoint, 1)));
            Thread secondCaller = null;
            AtomicLong secondEntry = new AtomicLong();
            try {
                assertThat(firstRequest.await(2, TimeUnit.SECONDS)).isTrue();
                secondCaller = Thread.ofPlatform().start(() -> {
                    secondEntry.set(System.nanoTime());
                    secondResult.set(transport.get(secondEndpoint, 1));
                });
                assertThat(awaitTurnQueueWait(secondCaller, Duration.ofSeconds(2))).isTrue();

                releaseFirst.countDown();
                joinAndAssertStopped(firstCaller, secondCaller);
                assertThat(firstResult.get()).isInstanceOf(Response.class);
                assertThat(secondResult.get()).isInstanceOf(Response.class);
                assertThat(observations).hasSize(2);
                assertThat(paths).containsExactly(order == 0 ? "/api/fixtures/" : "/api/bootstrap-static/",
                        order == 0 ? "/api/bootstrap-static/" : "/api/fixtures/");

                List<java.sql.Timestamp> completions = jdbc.query("SELECT completed_at FROM "
                                + TABLES.requestAttempt() + " ORDER BY admitted_at",
                        (rs, row) -> rs.getTimestamp(1));
                assertThat(completions).hasSize(2);
                Duration completionToNextGet = Duration.between(completions.getFirst().toInstant(),
                        observations.get(1).observedAt());
                assertThat(completionToNextGet).isGreaterThanOrEqualTo(Duration.ofSeconds(1));
                assertThat(observations.get(1).monotonicNanos() - secondEntry.get())
                        .isLessThanOrEqualTo(Duration.ofSeconds(3).toNanos());
            } finally {
                releaseFirst.countDown();
                joinAndAssertStopped(firstCaller, secondCaller);
            }
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void expiredQueuedFollowerDoesNotPromoteOrFetch() throws Exception {
        Clock realClock = Clock.systemUTC();
        budget = new OwnedSyncRequestBudget(jdbc, tx, realClock, TABLES);
        OwnedSyncDbOperations operations = new OwnedSyncDbOperations(Duration.ofMillis(200));
        AtomicLong turnClockOffset = new AtomicLong();
        AtomicInteger requests = new AtomicInteger();
        CountDownLatch firstRequest = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        HttpServer server = server(exchange -> {
            requests.incrementAndGet();
            firstRequest.countDown();
            awaitIgnoringInterrupts(releaseFirst);
            respond(exchange, 200, new byte[0]);
        });
        OwnedSyncHttpClient transport = new OwnedSyncHttpClient(budget,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/"),
                Duration.ofSeconds(5), realClock, operations, Duration.ofSeconds(5),
                () -> System.nanoTime() + turnClockOffset.get());
        transports.add(transport);
        AtomicReference<OwnedSyncHttpClient.Result> ownerResult = new AtomicReference<>();
        AtomicReference<OwnedSyncHttpClient.Result> followerResult = new AtomicReference<>();
        Thread owner = Thread.ofPlatform().start(() -> ownerResult.set(transport.get(Endpoint.fixtures(), 1)));
        Thread follower = null;
        try {
            assertThat(firstRequest.await(2, TimeUnit.SECONDS)).isTrue();
            follower = Thread.ofPlatform().start(
                    () -> followerResult.set(transport.get(Endpoint.bootstrap(), 1)));
            assertThat(awaitTurnQueueWait(follower, Duration.ofSeconds(2))).isTrue();

            turnClockOffset.set(Duration.ofSeconds(4).toNanos());
            releaseFirst.countDown();
            joinAndAssertStopped(owner, follower);
            assertThat(ownerResult.get()).isInstanceOf(Response.class);
            assertThat(followerResult.get()).isInstanceOfSatisfying(DeferredResult.class,
                    deferred -> assertThat(deferred.reason()).isEqualTo(DeferredReason.HTTP_NOT_SUBMITTED));
            assertThat(requests).hasValue(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.requestAttempt(), Integer.class))
                    .isEqualTo(1);
        } finally {
            releaseFirst.countDown();
            joinAndAssertStopped(owner, follower);
            awaitDbWorkersIdle(operations, Duration.ofSeconds(2));
            closeTransportAndAssertDbWorkers(transport, operations);
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void settlementAcknowledgementGatesQueuedFollowerAdmissionProbe() throws Exception {
        Clock realClock = Clock.systemUTC();
        CountDownLatch commitReached = new CountDownLatch(1);
        CountDownLatch releaseCommitAck = new CountDownLatch(1);
        CountDownLatch transactionFinished = new CountDownLatch(1);
        PostCommitGateTransactionTemplate gatedTx = new PostCommitGateTransactionTemplate(
                dataSource, 2, commitReached, releaseCommitAck, transactionFinished, false);
        OwnedSyncRequestBudget gatedBudget = new OwnedSyncRequestBudget(jdbc, gatedTx, realClock, TABLES);
        OwnedSyncDbOperations operations = new OwnedSyncDbOperations(Duration.ofSeconds(4));
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = server(exchange -> {
            requests.incrementAndGet();
            respond(exchange, 200, new byte[0]);
        });
        OwnedSyncHttpClient transport = new OwnedSyncHttpClient(gatedBudget,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/"),
                Duration.ofSeconds(2), realClock, operations, Duration.ofSeconds(7));
        transports.add(transport);
        AtomicReference<OwnedSyncHttpClient.Result> firstResult = new AtomicReference<>();
        AtomicReference<OwnedSyncHttpClient.Result> followerResult = new AtomicReference<>();
        Thread firstCaller = Thread.ofPlatform().start(
                () -> firstResult.set(transport.get(Endpoint.fixtures(), 1)));
        Thread follower = null;
        try {
            assertThat(commitReached.await(2, TimeUnit.SECONDS)).isTrue();
            follower = Thread.ofPlatform().start(
                    () -> followerResult.set(transport.get(Endpoint.bootstrap(), 1)));
            assertThat(awaitTurnQueueWait(follower, Duration.ofSeconds(2))).isTrue();

            assertThat(gatedTx.transactionCount()).isEqualTo(2);
            assertThat(followerResult.get()).isNull();
            assertThat(requests).hasValue(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.requestAttempt(), Integer.class))
                    .isEqualTo(1);

            releaseCommitAck.countDown();
            assertThat(transactionFinished.await(2, TimeUnit.SECONDS)).isTrue();
            joinAndAssertStopped(firstCaller, follower);
            assertThat(firstResult.get()).isInstanceOf(Response.class);
            assertThat(followerResult.get()).isNotNull();
            if (followerResult.get() instanceof Response) {
                assertThat(requests).hasValue(2);
                assertThat(gatedTx.transactionCount()).isEqualTo(4);
            } else {
                assertThat(followerResult.get()).isInstanceOf(DeferredResult.class);
                assertThat(requests).hasValue(1);
            }
        } finally {
            releaseCommitAck.countDown();
            joinAndAssertStopped(firstCaller, follower);
            awaitDbWorkersIdle(operations, Duration.ofSeconds(2));
            closeTransportAndAssertDbWorkers(transport, operations);
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void immediateReentryCannotBargeAheadOfQueuedFollowerAndThirdTicketDefers() throws Exception {
        budget = new OwnedSyncRequestBudget(jdbc, tx, Clock.systemUTC(), TABLES);
        List<String> paths = new CopyOnWriteArrayList<>();
        CountDownLatch firstRequest = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondRequest = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        HttpServer server = server(exchange -> {
            String path = exchange.getRequestURI().getPath();
            paths.add(path);
            if (path.equals("/api/fixtures/")) {
                if (firstRequest.getCount() != 0) {
                    firstRequest.countDown();
                    awaitIgnoringInterrupts(releaseFirst);
                }
            } else if (path.equals("/api/bootstrap-static/")) {
                secondRequest.countDown();
                awaitIgnoringInterrupts(releaseSecond);
            }
            respond(exchange, 200, new byte[0]);
        });
        OwnedSyncHttpClient transport = transport(server, Duration.ofSeconds(5));
        AtomicReference<OwnedSyncHttpClient.Result> firstResult = new AtomicReference<>();
        AtomicReference<OwnedSyncHttpClient.Result> reentryResult = new AtomicReference<>();
        AtomicReference<OwnedSyncHttpClient.Result> followerResult = new AtomicReference<>();
        CountDownLatch reentryCalled = new CountDownLatch(1);
        Thread firstCaller = Thread.ofPlatform().start(() -> {
            firstResult.set(transport.get(Endpoint.fixtures(), 1));
            reentryCalled.countDown();
            reentryResult.set(transport.get(Endpoint.eventLive(3), 1));
        });
        Thread follower = null;
        try {
            assertThat(firstRequest.await(2, TimeUnit.SECONDS)).isTrue();
            follower = Thread.ofPlatform().start(() -> followerResult.set(transport.get(Endpoint.bootstrap(), 1)));
            assertThat(awaitTurnQueueWait(follower, Duration.ofSeconds(2))).isTrue();

            DeferredResult overflow = (DeferredResult) transport.get(Endpoint.elementSummary(557), 1);
            assertThat(overflow.reason()).isEqualTo(DeferredReason.HTTP_TURN_QUEUE_FULL);
            assertThat(paths).containsExactly("/api/fixtures/");

            releaseFirst.countDown();
            assertThat(secondRequest.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(reentryCalled.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(awaitTurnQueueWait(firstCaller, Duration.ofSeconds(2))).isTrue();
            releaseSecond.countDown();
            joinAndAssertStopped(firstCaller, follower);

            assertThat(firstResult.get()).isInstanceOf(Response.class);
            assertThat(followerResult.get()).isInstanceOf(Response.class);
            assertThat(reentryResult.get()).isInstanceOf(Response.class);
            assertThat(paths).containsExactly("/api/fixtures/", "/api/bootstrap-static/", "/api/event/3/live/");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.requestAttempt(), Integer.class))
                    .isEqualTo(3);
        } finally {
            releaseFirst.countDown();
            releaseSecond.countDown();
            joinAndAssertStopped(firstCaller, follower);
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void interruptedAndShutdownWaitersDoNotFetchAndInterruptionIsRestored() throws Exception {
        budget = new OwnedSyncRequestBudget(jdbc, tx, Clock.systemUTC(), TABLES);
        AtomicInteger requests = new AtomicInteger();
        CountDownLatch firstRequest = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        HttpServer server = server(exchange -> {
            requests.incrementAndGet();
            firstRequest.countDown();
            awaitIgnoringInterrupts(releaseFirst);
            respond(exchange, 200, new byte[0]);
        });
        OwnedSyncHttpClient transport = transport(server, Duration.ofSeconds(5));
        AtomicReference<OwnedSyncHttpClient.Result> ownerResult = new AtomicReference<>();
        Thread owner = Thread.ofPlatform().start(() -> ownerResult.set(transport.get(Endpoint.fixtures(), 1)));
        AtomicReference<OwnedSyncHttpClient.Result> interruptedResult = new AtomicReference<>();
        AtomicBoolean interruptRestored = new AtomicBoolean();
        Thread interruptedWaiter = null;
        AtomicReference<OwnedSyncHttpClient.Result> shutdownWaiterResult = new AtomicReference<>();
        Thread shutdownWaiter = null;
        try {
            assertThat(firstRequest.await(2, TimeUnit.SECONDS)).isTrue();
            interruptedWaiter = Thread.ofPlatform().start(() -> {
                interruptedResult.set(transport.get(Endpoint.bootstrap(), 1));
                interruptRestored.set(Thread.currentThread().isInterrupted());
            });
            assertThat(awaitTurnQueueWait(interruptedWaiter, Duration.ofSeconds(2))).isTrue();
            interruptedWaiter.interrupt();
            joinAndAssertStopped(interruptedWaiter);
            assertThat(interruptedResult.get()).isInstanceOfSatisfying(DeferredResult.class,
                    deferred -> assertThat(deferred.reason()).isEqualTo(DeferredReason.DB_INTERRUPTED));
            assertThat(interruptRestored).isTrue();

            shutdownWaiter = Thread.ofPlatform().start(
                    () -> shutdownWaiterResult.set(transport.get(Endpoint.eventLive(3), 1)));
            assertThat(awaitTurnQueueWait(shutdownWaiter, Duration.ofSeconds(2))).isTrue();
            transport.close();
            joinAndAssertStopped(shutdownWaiter);
            assertThat(shutdownWaiterResult.get()).isInstanceOfSatisfying(DeferredResult.class,
                    deferred -> assertThat(deferred.reason()).isEqualTo(DeferredReason.HTTP_CLIENT_CLOSED));

            releaseFirst.countDown();
            joinAndAssertStopped(owner);
            assertThat(ownerResult.get()).isNotNull();
            assertThat(requests).hasValue(1);
        } finally {
            releaseFirst.countDown();
            transport.close();
            joinAndAssertStopped(owner, interruptedWaiter, shutdownWaiter);
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void queuedFollowerAfterUnknownAttemptDoesNotProbeOrSend() throws Exception {
        budget = new OwnedSyncRequestBudget(jdbc, tx, Clock.systemUTC(), TABLES);
        AtomicInteger requests = new AtomicInteger();
        CountDownLatch firstRequest = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        HttpServer server = server(exchange -> {
            requests.incrementAndGet();
            firstRequest.countDown();
            awaitIgnoringInterrupts(releaseFirst);
            exchange.close();
        });
        OwnedSyncHttpClient transport = transport(server, Duration.ofSeconds(2));
        AtomicReference<OwnedSyncHttpClient.Result> unknownResult = new AtomicReference<>();
        AtomicReference<OwnedSyncHttpClient.Result> followerResult = new AtomicReference<>();
        Thread owner = Thread.ofPlatform().start(() -> unknownResult.set(transport.get(Endpoint.fixtures(), 1)));
        Thread follower = null;
        try {
            assertThat(firstRequest.await(2, TimeUnit.SECONDS)).isTrue();
            follower = Thread.ofPlatform().start(
                    () -> followerResult.set(transport.get(Endpoint.bootstrap(), 1)));
            assertThat(awaitTurnQueueWait(follower, Duration.ofSeconds(2))).isTrue();

            releaseFirst.countDown();
            joinAndAssertStopped(owner, follower);
            assertThat(unknownResult.get()).isInstanceOfSatisfying(DeferredResult.class,
                    deferred -> assertThat(deferred.reason()).isEqualTo(DeferredReason.HTTP_EXCHANGE_UNCERTAIN));
            assertThat(followerResult.get()).isInstanceOfSatisfying(DeferredResult.class,
                    deferred -> assertThat(deferred.reason()).isEqualTo(DeferredReason.UNSETTLED));
            assertThat(requests).hasValue(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.requestAttempt(), Integer.class))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.requestAttempt()
                    + " WHERE completed_at IS NULL", Integer.class)).isEqualTo(1);
        } finally {
            releaseFirst.countDown();
            joinAndAssertStopped(owner, follower);
        }
    }

    @Test
    void doesNotFollowRedirects() throws Exception {
        AtomicInteger targetCalls = new AtomicInteger();
        HttpServer server = server(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/target")) {
                targetCalls.incrementAndGet();
                respond(exchange, 200, "target".getBytes(StandardCharsets.UTF_8));
            } else {
                exchange.getResponseHeaders().add("Location", "/target");
                respond(exchange, 302, new byte[0]);
            }
        });

        Response response = (Response) transport(server, Duration.ofSeconds(2)).get(Endpoint.bootstrap(), 1);

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(targetCalls).hasValue(0);
    }

    @Test
    void connectionClosedBeforeHeadersLeavesUnknownAttemptBlockingAcrossRestartAndTtl() throws Exception {
        HttpServer unrelated = server(exchange -> respond(exchange, 200, new byte[0]));
        URI unrelatedUri = URI.create("http://127.0.0.1:" + unrelated.getAddress().getPort() + "/initialize");
        java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(unrelatedUri).GET().build(),
                java.net.http.HttpResponse.BodyHandlers.discarding());

        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server(exchange -> {
            calls.incrementAndGet();
            exchange.close();
        });
        OwnedSyncHttpClient transport = transport(server, Duration.ofSeconds(2));

        DeferredResult unknown = (DeferredResult) transport.get(Endpoint.fixtures(), 1);

        assertThat(unknown.reason()).isEqualTo(DeferredReason.HTTP_EXCHANGE_UNCERTAIN);
        assertThat(calls).hasValue(1);
        assertThat(jdbc.queryForObject("SELECT completed_at IS NULL FROM " + TABLES.requestAttempt(),
                Boolean.class)).isTrue();
        assertThat(((DeferredResult) transport.get(Endpoint.fixtures(), 1)).reason())
                .isEqualTo(DeferredReason.UNSETTLED);

        budgetClock.advance(Duration.ofMinutes(3));
        OwnedSyncRequestBudget reopenedBudget = new OwnedSyncRequestBudget(jdbc, tx, budgetClock, TABLES);
        OwnedSyncHttpClient restarted = new OwnedSyncHttpClient(reopenedBudget,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/"),
                Duration.ofSeconds(2));
        transports.add(restarted);
        assertThat(((DeferredResult) restarted.get(Endpoint.fixtures(), 1)).reason())
                .isEqualTo(DeferredReason.UNSETTLED);
        assertThat(calls).hasValue(1);
    }

    @Test
    void twoHourRetryAfterOn429IsPersistedAndOnlyOneAttemptIsSent() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server(exchange -> {
            calls.incrementAndGet();
            exchange.getResponseHeaders().add("Retry-After", "7200");
            respond(exchange, 429, "slow down".getBytes(StandardCharsets.UTF_8));
        });
        OwnedSyncHttpClient transport = transport(server, Duration.ofSeconds(2));

        Response response = (Response) transport.get(Endpoint.fixtures(), 1);
        DeferredResult deferred = (DeferredResult) transport.get(Endpoint.fixtures(), 1);

        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.retryAfter()).isEqualTo("7200");
        assertThat(response.nextDue()).isEqualTo(budgetClock.instant().plusSeconds(7_200));
        assertThat(deferred.reason()).isEqualTo(DeferredReason.COOLDOWN);
        assertThat(deferred.nextDue()).isEqualTo(budgetClock.instant().plusSeconds(7_200));
        assertThat(calls).hasValue(1);
    }

    @Test
    void serverErrorBacksOffWhileOrdinary404DoesNot() throws Exception {
        AtomicInteger status = new AtomicInteger(500);
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server(exchange -> {
            calls.incrementAndGet();
            respond(exchange, status.get(), new byte[0]);
        });
        OwnedSyncHttpClient transport = transport(server, Duration.ofSeconds(2));

        assertThat(((Response) transport.get(Endpoint.fixtures(), 1)).statusCode()).isEqualTo(500);
        assertThat(((DeferredResult) transport.get(Endpoint.fixtures(), 1)).reason())
                .isEqualTo(DeferredReason.COOLDOWN);

        // Reset durable state to isolate the ordinary-client-error classification.
        jdbc.update("DELETE FROM " + TABLES.requestAttempt());
        jdbc.update("UPDATE " + TABLES.requestState()
                + " SET last_admitted_at = NULL, cooldown_until = NULL");
        status.set(404);
        assertThat(((Response) transport.get(Endpoint.fixtures(), 1)).statusCode()).isEqualTo(404);
        budgetClock.advance(Duration.ofSeconds(1));
        assertThat(transport.get(Endpoint.fixtures(), 1)).isInstanceOf(Response.class);
        assertThat(calls).hasValue(3);
    }

    @Test
    void malformedSuccessRemainsRawAndIsNeverCalledValidated() throws Exception {
        byte[] malformed = "{not-json".getBytes(StandardCharsets.UTF_8);
        HttpServer server = server(exchange -> respond(exchange, 200, malformed));

        Response response = (Response) transport(server, Duration.ofSeconds(2)).get(Endpoint.bootstrap(), 1);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(malformed);
    }

    @Test
    void timeoutBeforeHeadersLeavesAttemptUnknownAndNeverAdmitsAnotherGet() throws Exception {
        CountDownLatch requestReceived = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server(exchange -> {
            calls.incrementAndGet();
            requestReceived.countDown();
            await(release);
            exchange.close();
        });
        OwnedSyncHttpClient transport = transport(server, Duration.ofMillis(150));
        var result = new java.util.concurrent.atomic.AtomicReference<OwnedSyncHttpClient.Result>();
        Thread caller = Thread.ofPlatform().start(() -> result.set(transport.get(Endpoint.fixtures(), 1)));
        assertThat(requestReceived.await(2, TimeUnit.SECONDS)).isTrue();
        caller.join(2_000);
        release.countDown();

        assertThat(caller.isAlive()).isFalse();
        DeferredResult unknown = (DeferredResult) result.get();
        assertThat(unknown.reason()).isEqualTo(DeferredReason.HTTP_EXCHANGE_UNCERTAIN);
        assertThat(jdbc.queryForObject("SELECT completed_at IS NULL FROM " + TABLES.requestAttempt(),
                Boolean.class)).isTrue();
        assertThat(((DeferredResult) transport.get(Endpoint.fixtures(), 1)).reason())
                .isEqualTo(DeferredReason.UNSETTLED);
        assertThat(calls).hasValue(1);
    }

    @Test
    void retryAfterHeadersSurviveAHangingBodyTimeout() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = server(exchange -> {
            exchange.getResponseHeaders().add("Retry-After", "90");
            exchange.sendResponseHeaders(429, 0);
            exchange.getResponseBody().write('x');
            exchange.getResponseBody().flush();
            await(release);
            exchange.close();
        });

        Failure failure = (Failure) transport(server, Duration.ofMillis(200)).get(Endpoint.fixtures(), 1);
        release.countDown();

        assertThat(failure.kind()).isEqualTo(FailureKind.TIMEOUT);
        assertThat(failure.statusCode()).isEqualTo(429);
        assertThat(failure.retryAfter()).isEqualTo("90");
        assertThat(failure.nextDue()).isEqualTo(budgetClock.instant().plusSeconds(90));
        OwnedSyncRequestBudget.Deferred deferred = (OwnedSyncRequestBudget.Deferred) budget.admit();
        assertThat(deferred.reason()).isEqualTo(DeferredReason.COOLDOWN);
        assertThat(deferred.nextDue()).isEqualTo(budgetClock.instant().plusSeconds(90));
    }

    @Test
    void rejectsOversizeBodyBeforeReturningIt() throws Exception {
        byte[] oversized = new byte[OwnedSyncStore.MAX_PAYLOAD_BYTES + 1];
        HttpServer server = server(exchange -> respond(exchange, 200, oversized));

        Failure failure = (Failure) transport(server, Duration.ofSeconds(3)).get(Endpoint.fixtures(), 1);

        assertThat(failure.kind()).isEqualTo(FailureKind.OVERSIZE);
        assertThat(failure.statusCode()).isEqualTo(200);
        assertThat(failure.message()).contains(Integer.toString(OwnedSyncStore.MAX_PAYLOAD_BYTES));
        assertCompletedPermit();
    }

    @Test
    void interruptionBeforeHeadersLeavesUnknownAttemptAndRestoresTheFlag() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = server(exchange -> {
            entered.countDown();
            await(release);
            exchange.close();
        });
        OwnedSyncHttpClient transport = transport(server, Duration.ofSeconds(5));
        var result = new java.util.concurrent.atomic.AtomicReference<OwnedSyncHttpClient.Result>();
        var interrupted = new java.util.concurrent.atomic.AtomicBoolean();

        Thread worker = Thread.ofPlatform().start(() -> {
            result.set(transport.get(Endpoint.fixtures(), 1));
            interrupted.set(Thread.currentThread().isInterrupted());
        });
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        worker.interrupt();
        worker.join(2_000);
        release.countDown();

        assertThat(worker.isAlive()).isFalse();
        assertThat(result.get()).isInstanceOfSatisfying(DeferredResult.class,
                deferred -> assertThat(deferred.reason()).isEqualTo(DeferredReason.HTTP_EXCHANGE_UNCERTAIN));
        assertThat(interrupted).isTrue();
        assertThat(jdbc.queryForObject("SELECT completed_at IS NULL FROM " + TABLES.requestAttempt(),
                Boolean.class)).isTrue();
        assertThat(((DeferredResult) transport.get(Endpoint.fixtures(), 1)).reason())
                .isEqualTo(DeferredReason.UNSETTLED);
    }

    @Test
    void stopSendingRefusesBeforeDatabaseAdmission() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server(exchange -> {
            calls.incrementAndGet();
            respond(exchange, 200, new byte[0]);
        });
        OwnedSyncHttpClient transport = transport(server, Duration.ofSeconds(2));
        transport.stopSending();

        DeferredResult stopped = (DeferredResult) transport.get(Endpoint.fixtures(), 1);

        assertThat(stopped.reason()).isEqualTo(DeferredReason.HTTP_CLIENT_CLOSED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.requestAttempt(), Integer.class)).isZero();
        assertThat(calls).hasValue(0);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void delayedWorkerReleasedAfterStopSendingSettlesPermitWithoutSending() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server(exchange -> {
            calls.incrementAndGet();
            respond(exchange, 200, new byte[0]);
        });
        DelayedExecutor delayed = new DelayedExecutor();
        OwnedSyncHttpClient transport = new OwnedSyncHttpClient(budget,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/"),
                Duration.ofSeconds(5), budgetClock, delayed);
        transports.add(transport);
        var result = new AtomicReference<OwnedSyncHttpClient.Result>();
        Thread caller = Thread.ofPlatform().start(
                () -> result.set(transport.get(Endpoint.fixtures(), 1, this::readyFence)));

        assertThat(delayed.awaitSubmitted()).isTrue();
        transport.stopSending();
        delayed.release();
        caller.join(2_000);

        assertThat(caller.isAlive()).isFalse();
        assertThat(result.get()).isInstanceOfSatisfying(DeferredResult.class,
                deferred -> assertThat(deferred.reason()).isEqualTo(DeferredReason.HTTP_CLIENT_CLOSED));
        assertThat(jdbc.queryForObject("SELECT completed_at IS NOT NULL FROM " + TABLES.requestAttempt(),
                Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.requestAttempt(), Integer.class)).isEqualTo(1);
        assertThat(((DeferredResult) transport.get(Endpoint.fixtures(), 1)).reason())
                .isEqualTo(DeferredReason.HTTP_CLIENT_CLOSED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.requestAttempt(), Integer.class)).isEqualTo(1);
        assertThat(delayed.awaitFinished()).isTrue();
        assertThat(calls).hasValue(0);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void blockedClientCloseRunsOffCallerAndConcurrentGetDoesNotReachDatabase() throws Exception {
        CountDownLatch closeEntered = new CountDownLatch(1);
        CountDownLatch releaseClose = new CountDownLatch(1);
        AtomicReference<Thread> closeThread = new AtomicReference<>();
        CloseableHttpClient apache = mock(CloseableHttpClient.class);
        doAnswer(invocation -> {
            closeThread.set(Thread.currentThread());
            closeEntered.countDown();
            awaitIgnoringInterrupts(releaseClose);
            return null;
        }).when(apache).close(CloseMode.IMMEDIATE);
        OwnedSyncDbOperations operations = new OwnedSyncDbOperations(Duration.ofMillis(100));
        OwnedSyncHttpClient transport = customTransport(apache, boundedExecutor(1, "close-control-"), operations);
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        Thread caller = Thread.currentThread();
        Thread closer = Thread.ofPlatform().start(() -> {
            try {
                transport.close();
            } catch (Throwable failure) {
                closeFailure.set(failure);
            }
        });

        try {
            assertThat(closeEntered.await(1, TimeUnit.SECONDS)).isTrue();
            DeferredResult stopped = (DeferredResult) transport.get(Endpoint.fixtures(), 1);
            closer.join(2_000);

            assertThat(closer.isAlive()).isFalse();
            assertThat(closeFailure.get()).isNull();
            assertThat(closeThread.get()).isNotSameAs(caller);
            assertThat(stopped.reason()).isEqualTo(DeferredReason.HTTP_CLIENT_CLOSED);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.requestAttempt(), Integer.class)).isZero();
            assertThat(operations.call(() -> true, OwnedSyncDeadline.after(Duration.ofSeconds(1))))
                    .isInstanceOfSatisfying(OwnedSyncDbOperations.Rejected.class,
                            rejected -> assertThat(rejected.reason())
                                    .isEqualTo(OwnedSyncDbOperations.Rejection.CLOSED));
        } finally {
            releaseClose.countDown();
            closer.join(1_000);
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void throwingClientCloseDoesNotSkipExecutorAndDatabaseCleanup() throws Exception {
        CloseableHttpClient apache = mock(CloseableHttpClient.class);
        doThrow(new IllegalStateException("close failed")).when(apache).close(CloseMode.IMMEDIATE);
        OwnedSyncDbOperations operations = new OwnedSyncDbOperations(Duration.ofMillis(100));
        OwnedSyncHttpClient transport = customTransport(apache, boundedExecutor(1, "close-control-"), operations);

        transport.close();

        verify(apache).close(CloseMode.IMMEDIATE);
        assertThat(operations.call(() -> true, OwnedSyncDeadline.after(Duration.ofSeconds(1))))
                .isInstanceOfSatisfying(OwnedSyncDbOperations.Rejected.class,
                        rejected -> assertThat(rejected.reason()).isEqualTo(OwnedSyncDbOperations.Rejection.CLOSED));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void saturatedControlWorkerDoesNotBlockCloseOrSkipOtherCleanup() throws Exception {
        CountDownLatch controlStarted = new CountDownLatch(1);
        CountDownLatch releaseControl = new CountDownLatch(1);
        ThreadPoolExecutor control = boundedExecutor(1, "close-control-");
        control.execute(() -> {
            controlStarted.countDown();
            awaitIgnoringInterrupts(releaseControl);
        });
        assertThat(controlStarted.await(1, TimeUnit.SECONDS)).isTrue();
        CloseableHttpClient apache = mock(CloseableHttpClient.class);
        OwnedSyncDbOperations operations = new OwnedSyncDbOperations(Duration.ofMillis(100));
        OwnedSyncHttpClient transport = customTransport(apache, control, operations);

        try {
            transport.close();

            verify(apache, never()).close(CloseMode.IMMEDIATE);
            assertThat(operations.call(() -> true, OwnedSyncDeadline.after(Duration.ofSeconds(1))))
                    .isInstanceOfSatisfying(OwnedSyncDbOperations.Rejected.class,
                            rejected -> assertThat(rejected.reason())
                                    .isEqualTo(OwnedSyncDbOperations.Rejection.CLOSED));
        } finally {
            releaseControl.countDown();
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void delayedWorkerReleasedAfterTimeoutCannotSendAndNoEarlyAdmissionOccurs() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server(exchange -> {
            calls.incrementAndGet();
            respond(exchange, 200, new byte[0]);
        });
        DelayedExecutor delayed = new DelayedExecutor();
        OwnedSyncHttpClient transport = new OwnedSyncHttpClient(budget,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/"),
                Duration.ofMillis(150), delayed);
        transports.add(transport);

        DeferredResult notSubmitted = (DeferredResult) transport.get(Endpoint.fixtures(), 1);

        assertThat(notSubmitted.reason()).isEqualTo(DeferredReason.HTTP_NOT_SUBMITTED);
        assertThat(jdbc.queryForObject("SELECT completed_at IS NOT NULL FROM " + TABLES.requestAttempt(),
                Boolean.class)).isTrue();
        assertThat(((DeferredResult) transport.get(Endpoint.fixtures(), 1)).reason())
                .isEqualTo(DeferredReason.MINIMUM_SPACING);
        assertThat(calls).hasValue(0);

        delayed.release();
        assertThat(delayed.awaitFinished()).isTrue();
        assertThat(calls).hasValue(0);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void insufficientExchangeTimeSettlesProvenNoFetch() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server(exchange -> {
            calls.incrementAndGet();
            respond(exchange, 200, new byte[0]);
        });
        OwnedSyncDbOperations operations = new OwnedSyncDbOperations(Duration.ofMillis(250));
        OwnedSyncHttpClient transport = transport(
                server, Duration.ofSeconds(2), operations, Duration.ofSeconds(2));
        try {
            DeferredResult result = (DeferredResult) transport.get(Endpoint.fixtures(), 1);

            assertThat(result.reason()).isEqualTo(DeferredReason.HTTP_NOT_SUBMITTED);
            assertCompletedPermit();
            assertThat(calls).hasValue(0);
        } finally {
            operations.close();
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void rejectedPostAdmissionAttemptClearsAndRestoresInterruptForSupervisedSettlement() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server(exchange -> {
            calls.incrementAndGet();
            respond(exchange, 200, new byte[0]);
        });
        RejectingInterruptingExecutor rejecting = new RejectingInterruptingExecutor();
        OwnedSyncHttpClient transport = new OwnedSyncHttpClient(budget,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/"),
                Duration.ofSeconds(2), rejecting);
        transports.add(transport);
        var result = new java.util.concurrent.atomic.AtomicReference<OwnedSyncHttpClient.Result>();
        var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        Thread caller = Thread.ofPlatform().start(() -> {
            result.set(transport.get(Endpoint.fixtures(), 1));
            interrupted.set(Thread.currentThread().isInterrupted());
        });
        caller.join(3_000);

        assertThat(caller.isAlive()).isFalse();
        assertThat(result.get()).isInstanceOfSatisfying(DeferredResult.class,
                deferred -> assertThat(deferred.reason()).isEqualTo(DeferredReason.HTTP_WORKERS_SATURATED));
        assertThat(interrupted).isTrue();
        assertCompletedPermit();
        assertThat(calls).hasValue(0);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void sourceReadIntervalBeginsOnExchangeWorkerAndAdmissionWaitsForIt() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server(exchange -> {
            calls.incrementAndGet();
            respond(exchange, 200, new byte[0]);
        });
        MutableClock workerClock = new MutableClock(budgetClock.instant());
        DelayedExecutor delayed = new DelayedExecutor();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/");
        OwnedSyncHttpClient transport = new OwnedSyncHttpClient(budget, base,
                Duration.ofSeconds(3), workerClock, delayed);
        transports.add(transport);
        OwnedSyncHttpClient independent = new OwnedSyncHttpClient(budget, base,
                Duration.ofSeconds(3), workerClock);
        transports.add(independent);
        var result = new java.util.concurrent.atomic.AtomicReference<OwnedSyncHttpClient.Result>();
        Thread caller = Thread.ofPlatform().start(() -> result.set(transport.get(Endpoint.fixtures(), 1)));

        assertThat(delayed.awaitSubmitted()).isTrue();
        java.time.Instant admittedAt = jdbc.queryForObject("SELECT admitted_at FROM "
                + TABLES.requestAttempt(), java.sql.Timestamp.class).toInstant();
        assertThat(((DeferredResult) independent.get(Endpoint.fixtures(), 1)).reason())
                .isEqualTo(DeferredReason.UNSETTLED);
        assertThat(calls).hasValue(0);

        workerClock.advance(Duration.ofSeconds(30));
        delayed.release();
        assertThat(delayed.awaitFinished()).isTrue();
        caller.join(3_000);

        Response response = (Response) result.get();
        assertThat(response.read().startedAt()).isAfterOrEqualTo(admittedAt.plusSeconds(30));
        assertThat(response.read().finishedAt()).isAfterOrEqualTo(response.read().startedAt());
        assertThat(calls).hasValue(1);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void timedOutSettlementWaitsForLate429AndCommitsCooldownWithoutReturningARead() throws Exception {
        CountDownLatch headersSent = new CountDownLatch(1);
        CountDownLatch releaseServer = new CountDownLatch(1);
        HttpServer server = server(exchange -> {
            exchange.getResponseHeaders().add("Retry-After", "90");
            exchange.sendResponseHeaders(429, 0);
            exchange.getResponseBody().write('x');
            exchange.getResponseBody().flush();
            headersSent.countDown();
            await(releaseServer);
            exchange.close();
        });
        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        var lockOwner = Executors.newSingleThreadExecutor();
        OwnedSyncDbOperations operations = new OwnedSyncDbOperations(Duration.ofMillis(250));
        OwnedSyncHttpClient transport = transport(
                server, Duration.ofSeconds(2), operations, Duration.ofSeconds(6));
        var result = new java.util.concurrent.atomic.AtomicReference<OwnedSyncHttpClient.Result>();
        Thread caller = Thread.ofPlatform().start(() -> result.set(transport.get(Endpoint.fixtures(), 1)));
        Future<?> lockTransaction = null;
        try {
            assertThat(headersSent.await(2, TimeUnit.SECONDS)).isTrue();
            lockTransaction = lockOwner.submit(() -> holdRequestStateLock(lockAcquired, releaseLock));
            assertThat(lockAcquired.await(1, TimeUnit.SECONDS)).isTrue();
            caller.join(5_000);

            assertThat(caller.isAlive()).isFalse();
            assertThat(result.get()).isInstanceOfSatisfying(DeferredResult.class,
                    deferred -> assertThat(deferred.reason()).isEqualTo(DeferredReason.SETTLEMENT_UNCERTAIN));
            assertThat(jdbc.queryForObject("SELECT completed_at IS NULL FROM " + TABLES.requestAttempt(),
                    Boolean.class)).isTrue();

            releaseServer.countDown();
            releaseLock.countDown();
            lockTransaction.get(2, TimeUnit.SECONDS);
            assertThat(awaitCompletedAttempt()).isTrue();
            assertThat(budget.nextAdmissionHint()).contains(budgetClock.instant().plusSeconds(90));
            assertThat(transport.get(Endpoint.fixtures(), 1)).isInstanceOf(DeferredResult.class);
        } finally {
            releaseServer.countDown();
            releaseLock.countDown();
            operations.close();
            if (lockTransaction != null) lockTransaction.get(2, TimeUnit.SECONDS);
            lockOwner.shutdownNow();
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void timedOutSupervisedAdmissionNeverGetsAfterLateCommitAndBlocksAnotherClient() throws Exception {
        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        var lockOwner = Executors.newSingleThreadExecutor();
        Future<?> lockTransaction = lockOwner.submit(() -> holdRequestStateLock(lockAcquired, releaseLock));
        OwnedSyncDbOperations operations = new OwnedSyncDbOperations(Duration.ofMillis(250));
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server(exchange -> {
            calls.incrementAndGet();
            respond(exchange, 200, new byte[0]);
        });
        OwnedSyncHttpClient first = transport(server, Duration.ofSeconds(2), operations, Duration.ofSeconds(2));
        OwnedSyncHttpClient second = transport(server, Duration.ofSeconds(2), operations, Duration.ofSeconds(2));
        try {
            assertThat(lockAcquired.await(1, TimeUnit.SECONDS)).isTrue();

            DeferredResult timedOut = (DeferredResult) first.get(Endpoint.fixtures(), 1);

            assertThat(timedOut.reason()).isEqualTo(DeferredReason.DB_ADMISSION_UNCERTAIN);
            assertThat(timedOut.nextDue()).isEqualTo(budgetClock.instant().plusSeconds(30));
            assertThat(awaitBudgetLockWait("SELECT last_admitted_at, cooldown_until FROM "
                    + TABLES.requestState())).isTrue();
            assertThat(calls).hasValue(0);

            releaseLock.countDown();
            lockTransaction.get(2, TimeUnit.SECONDS);
            assertThat(awaitUnresolvedAttempt()).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.requestAttempt()
                    + " WHERE completed_at IS NULL", Integer.class)).isEqualTo(1);

            DeferredResult blocked = (DeferredResult) second.get(Endpoint.fixtures(), 1);
            assertThat(blocked.reason()).isEqualTo(DeferredReason.UNSETTLED);
            Thread.sleep(100);
            assertThat(calls).hasValue(0);
        } finally {
            releaseLock.countDown();
            operations.close();
            try {
                lockTransaction.get(2, TimeUnit.SECONDS);
            } finally {
                lockOwner.shutdownNow();
            }
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void committedAdmissionWithLostCommitAckTimesOutWithoutLateGetAndBlocksPastPermitTtl() throws Exception {
        CountDownLatch commitReached = new CountDownLatch(1);
        CountDownLatch releaseCommitAck = new CountDownLatch(1);
        CountDownLatch transactionFinished = new CountDownLatch(1);
        PostCommitGateTransactionTemplate gatedTx = new PostCommitGateTransactionTemplate(
                dataSource, 1, commitReached, releaseCommitAck, transactionFinished, true);
        OwnedSyncRequestBudget uncertainBudget = new OwnedSyncRequestBudget(jdbc, gatedTx, budgetClock, TABLES);
        OwnedSyncDbOperations operations = new OwnedSyncDbOperations(Duration.ofMillis(150));
        AtomicInteger getCalls = new AtomicInteger();
        HttpServer server = server(exchange -> {
            getCalls.incrementAndGet();
            respond(exchange, 200, new byte[0]);
        });
        OwnedSyncHttpClient first = transport(uncertainBudget, server, Duration.ofSeconds(2),
                operations, Duration.ofSeconds(4));
        long started = System.nanoTime();
        try {
            DeferredResult uncertain = (DeferredResult) first.get(Endpoint.fixtures(), 1);
            long callerNanos = System.nanoTime() - started;

            assertThat(uncertain.reason()).isEqualTo(DeferredReason.DB_ADMISSION_UNCERTAIN);
            assertThat(callerNanos).isLessThan(Duration.ofSeconds(2).toNanos());
            assertThat(commitReached.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.requestAttempt()
                    + " WHERE completed_at IS NULL", Integer.class)).isEqualTo(1);

            budgetClock.advance(Duration.ofMinutes(3));
            assertThat(jdbc.queryForObject("SELECT permit_expires_at < ? FROM " + TABLES.requestAttempt(),
                    Boolean.class, java.sql.Timestamp.from(budgetClock.instant()))).isTrue();
            releaseCommitAck.countDown();
            assertThat(transactionFinished.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(gatedTx.acknowledgementLost()).isTrue();

            OwnedSyncRequestBudget independentBudget = new OwnedSyncRequestBudget(jdbc, tx, budgetClock, TABLES);
            OwnedSyncHttpClient independent = transport(independentBudget, server, Duration.ofSeconds(2));
            DeferredResult blocked = (DeferredResult) independent.get(Endpoint.fixtures(), 1);
            assertThat(blocked.reason()).isEqualTo(DeferredReason.UNSETTLED);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.requestAttempt()
                    + " WHERE completed_at IS NULL", Integer.class)).isEqualTo(1);
            assertThat(getCalls).hasValue(0);
        } finally {
            releaseCommitAck.countDown();
            assertThat(transactionFinished.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(awaitDbWorkersIdle(operations, Duration.ofSeconds(2))).isTrue();
            closeTransportAndAssertDbWorkers(first, operations);
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void lostSettlementCommitAckAfter429DoesNotReturnConfirmedResponseOrAllowEarlyGet() throws Exception {
        CountDownLatch responseSent = new CountDownLatch(1);
        CountDownLatch commitReached = new CountDownLatch(1);
        CountDownLatch releaseCommitAck = new CountDownLatch(1);
        CountDownLatch transactionFinished = new CountDownLatch(1);
        PostCommitGateTransactionTemplate gatedTx = new PostCommitGateTransactionTemplate(
                dataSource, 2, commitReached, releaseCommitAck, transactionFinished, true);
        OwnedSyncRequestBudget uncertainBudget = new OwnedSyncRequestBudget(jdbc, gatedTx, budgetClock, TABLES);
        OwnedSyncDbOperations operations = new OwnedSyncDbOperations(Duration.ofMillis(150));
        AtomicInteger getCalls = new AtomicInteger();
        HttpServer server = server(exchange -> {
            getCalls.incrementAndGet();
            exchange.getResponseHeaders().add("Retry-After", "7200");
            respond(exchange, 429, "slow down".getBytes(StandardCharsets.UTF_8));
            responseSent.countDown();
        });
        OwnedSyncHttpClient first = transport(uncertainBudget, server, Duration.ofSeconds(2),
                operations, Duration.ofSeconds(4));
        AtomicReference<OwnedSyncHttpClient.Result> result = new AtomicReference<>();
        AtomicLong callerNanos = new AtomicLong();
        Thread caller = Thread.ofPlatform().start(() -> {
            long callStarted = System.nanoTime();
            result.set(first.get(Endpoint.fixtures(), 1));
            callerNanos.set(System.nanoTime() - callStarted);
        });
        try {
            assertThat(responseSent.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(commitReached.await(2, TimeUnit.SECONDS)).isTrue();
            caller.join(2_000);

            assertThat(caller.isAlive()).isFalse();
            assertThat(callerNanos.get()).isLessThan(Duration.ofSeconds(2).toNanos());
            assertThat(result.get()).isInstanceOfSatisfying(DeferredResult.class, deferred -> {
                assertThat(deferred.reason()).isEqualTo(DeferredReason.SETTLEMENT_UNCERTAIN);
                assertThat(deferred.nextDue()).isNotEqualTo(budgetClock.instant().plusSeconds(7_200));
            });
            assertThat(result.get()).isNotInstanceOf(Response.class);
            Instant cooldown = budgetClock.instant().plusSeconds(7_200);
            assertThat(uncertainBudget.nextAdmissionHint()).contains(cooldown);
            assertThat(jdbc.queryForObject("SELECT completed_at IS NOT NULL FROM "
                    + TABLES.requestAttempt(), Boolean.class)).isTrue();

            OwnedSyncRequestBudget independentBudget = new OwnedSyncRequestBudget(jdbc, tx, budgetClock, TABLES);
            OwnedSyncHttpClient independent = transport(independentBudget, server, Duration.ofSeconds(2));
            DeferredResult early = (DeferredResult) independent.get(Endpoint.fixtures(), 1);
            assertThat(early.reason()).isEqualTo(DeferredReason.COOLDOWN);
            assertThat(early.nextDue()).isEqualTo(cooldown);
            assertThat(getCalls).hasValue(1);

            releaseCommitAck.countDown();
            assertThat(transactionFinished.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(gatedTx.acknowledgementLost()).isTrue();
            assertThat(independent.get(Endpoint.fixtures(), 1)).isInstanceOf(DeferredResult.class);
            assertThat(getCalls).hasValue(1);
        } finally {
            releaseCommitAck.countDown();
            caller.join(2_000);
            assertThat(caller.isAlive()).isFalse();
            assertThat(transactionFinished.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(awaitDbWorkersIdle(operations, Duration.ofSeconds(2))).isTrue();
            closeTransportAndAssertDbWorkers(first, operations);
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void blockedPoolAcquisitionBoundsCallersUsesTwoZeroQueueDbWorkersAndCannotSendLateGet() throws Exception {
        CountDownLatch acquisitionEntered = new CountDownLatch(2);
        CountDownLatch releaseAcquisition = new CountDownLatch(1);
        BlockingDataSource blockedDataSource = new BlockingDataSource(dataSource,
                acquisitionEntered, releaseAcquisition);
        JdbcTemplate blockedJdbc = new JdbcTemplate(blockedDataSource);
        TransactionTemplate blockedTx = new TransactionTemplate(new DataSourceTransactionManager(blockedDataSource));
        OwnedSyncRequestBudget blockedBudget = new OwnedSyncRequestBudget(blockedJdbc, blockedTx,
                budgetClock, TABLES);
        OwnedSyncDbOperations operations = new OwnedSyncDbOperations(Duration.ofMillis(200));
        AtomicInteger getCalls = new AtomicInteger();
        HttpServer server = server(exchange -> {
            getCalls.incrementAndGet();
            respond(exchange, 200, new byte[0]);
        });
        OwnedSyncHttpClient firstTransport = transport(blockedBudget, server, Duration.ofSeconds(2),
                operations, Duration.ofSeconds(3));
        OwnedSyncHttpClient secondTransport = transport(blockedBudget, server, Duration.ofSeconds(2),
                operations, Duration.ofSeconds(3));
        CountDownLatch callersFinished = new CountDownLatch(2);
        AtomicReference<OwnedSyncHttpClient.Result> firstResult = new AtomicReference<>();
        AtomicReference<OwnedSyncHttpClient.Result> secondResult = new AtomicReference<>();
        AtomicLong firstElapsed = new AtomicLong();
        AtomicLong secondElapsed = new AtomicLong();
        Thread firstCaller = Thread.ofPlatform().start(() -> {
            long callStarted = System.nanoTime();
            firstResult.set(firstTransport.get(Endpoint.fixtures(), 1));
            firstElapsed.set(System.nanoTime() - callStarted);
            callersFinished.countDown();
        });
        Thread secondCaller = Thread.ofPlatform().start(() -> {
            long callStarted = System.nanoTime();
            secondResult.set(secondTransport.get(Endpoint.fixtures(), 1));
            secondElapsed.set(System.nanoTime() - callStarted);
            callersFinished.countDown();
        });
        try {
            assertThat(acquisitionEntered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(callersFinished.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(firstElapsed.get()).isLessThan(Duration.ofSeconds(2).toNanos());
            assertThat(secondElapsed.get()).isLessThan(Duration.ofSeconds(2).toNanos());
            assertThat(firstResult.get()).isInstanceOfSatisfying(DeferredResult.class,
                    deferred -> assertThat(deferred.reason()).isEqualTo(DeferredReason.DB_ADMISSION_UNCERTAIN));
            assertThat(secondResult.get()).isInstanceOfSatisfying(DeferredResult.class,
                    deferred -> assertThat(deferred.reason()).isEqualTo(DeferredReason.DB_ADMISSION_UNCERTAIN));

            ThreadPoolExecutor dbWorkers = supervisedDbWorkers(operations);
            assertThat(dbWorkers.getPoolSize()).isLessThanOrEqualTo(2);
            assertThat(dbWorkers.getActiveCount()).isEqualTo(2);
            assertThat(dbWorkers.getQueue()).isEmpty();
            assertThat(blockedDataSource.maximumConcurrentAcquisitions()).isEqualTo(2);

            long saturationStarted = System.nanoTime();
            DeferredResult saturated = (DeferredResult) secondTransport.get(Endpoint.fixtures(), 1);
            assertThat(System.nanoTime() - saturationStarted).isLessThan(Duration.ofMillis(300).toNanos());
            assertThat(saturated.reason()).isEqualTo(DeferredReason.DB_OPERATIONS_SATURATED);
            assertThat(getCalls).hasValue(0);
        } finally {
            releaseAcquisition.countDown();
            firstCaller.join(2_000);
            secondCaller.join(2_000);
            assertThat(firstCaller.isAlive()).isFalse();
            assertThat(secondCaller.isAlive()).isFalse();
            assertThat(awaitDbWorkersIdle(operations, Duration.ofSeconds(2))).isTrue();
            assertThat(getCalls).hasValue(0);
            firstTransport.close();
            secondTransport.close();
            operations.close();
            assertThat(supervisedDbWorkers(operations).awaitTermination(1, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void unacknowledgedStartedExchangeThatLaterObservesLoopback429StaysUnresolved() throws Exception {
        CountDownLatch requestReceived = new CountDownLatch(1);
        CountDownLatch upstreamResponseCaptured = new CountDownLatch(1);
        CountDownLatch releaseResponseHandler = new CountDownLatch(1);
        CountDownLatch responseHandlerFinished = new CountDownLatch(1);
        AtomicInteger getCalls = new AtomicInteger();
        HttpServer server = server(exchange -> {
            getCalls.incrementAndGet();
            requestReceived.countDown();
            exchange.getResponseHeaders().add("Retry-After", "7200");
            respond(exchange, 429, "slow down".getBytes(StandardCharsets.UTF_8));
        });
        AtomicReference<HttpUriRequestBase> submittedRequest = new AtomicReference<>();
        AtomicInteger upstreamStatus = new AtomicInteger();
        CloseableHttpClient delayedResponseClient = mock(CloseableHttpClient.class);
        doAnswer(invocation -> {
            HttpUriRequestBase request = (HttpUriRequestBase) invocation.getArgument(0);
            submittedRequest.set(request);
            HttpClientResponseHandler<?> handler = invocation.getArgument(1);
            HttpResponse<byte[]> upstream = java.net.http.HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(request.getUri()).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            upstreamStatus.set(upstream.statusCode());
            upstreamResponseCaptured.countDown();
            awaitIgnoringInterrupts(releaseResponseHandler);

            BasicClassicHttpResponse response = new BasicClassicHttpResponse(upstream.statusCode());
            upstream.headers().firstValue("Retry-After")
                    .ifPresent(value -> response.addHeader("Retry-After", value));
            response.setEntity(new ByteArrayEntity(upstream.body(), ContentType.APPLICATION_OCTET_STREAM));
            try {
                return handler.handleResponse(response);
            } finally {
                responseHandlerFinished.countDown();
                response.close();
            }
        }).when(delayedResponseClient).execute(any(ClassicHttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpClientResponseHandler<Object>>any());

        ThreadPoolExecutor exchangeWorkers = boundedExecutor(1, "late-429-exchange-");
        ThreadPoolExecutor cancellationWorkers = boundedExecutor(1, "late-429-cancel-");
        OwnedSyncDbOperations operations = new OwnedSyncDbOperations(Duration.ofMillis(200));
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/");
        OwnedSyncHttpClient transport = new OwnedSyncHttpClient(budget, delayedResponseClient,
                exchangeWorkers, cancellationWorkers, base, Duration.ofMillis(150), budgetClock,
                operations, Duration.ofSeconds(5), true, true);
        transports.add(transport);
        AtomicReference<OwnedSyncHttpClient.Result> result = new AtomicReference<>();
        AtomicLong callerNanos = new AtomicLong();
        Thread caller = Thread.ofPlatform().start(() -> {
            long callStarted = System.nanoTime();
            result.set(transport.get(Endpoint.fixtures(), 1));
            callerNanos.set(System.nanoTime() - callStarted);
        });
        try {
            assertThat(requestReceived.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(upstreamResponseCaptured.await(2, TimeUnit.SECONDS)).isTrue();
            caller.join(3_000);

            assertThat(caller.isAlive()).isFalse();
            assertThat(callerNanos.get()).isLessThan(Duration.ofMillis(2_500).toNanos());
            assertThat(result.get()).isInstanceOfSatisfying(DeferredResult.class,
                    deferred -> assertThat(deferred.reason()).isEqualTo(DeferredReason.HTTP_EXCHANGE_UNCERTAIN));
            assertThat(upstreamStatus).hasValue(429);
            assertThat(submittedRequest.get().isCancelled()).isTrue();
            assertThat(jdbc.queryForObject("SELECT completed_at IS NULL FROM "
                    + TABLES.requestAttempt(), Boolean.class)).isTrue();

            OwnedSyncRequestBudget independentBudget = new OwnedSyncRequestBudget(jdbc, tx, budgetClock, TABLES);
            OwnedSyncHttpClient independent = transport(independentBudget, server, Duration.ofSeconds(2));
            DeferredResult blocked = (DeferredResult) independent.get(Endpoint.fixtures(), 1);
            assertThat(blocked.reason()).isEqualTo(DeferredReason.UNSETTLED);
            assertThat(getCalls).hasValue(1);

            releaseResponseHandler.countDown();
            assertThat(responseHandlerFinished.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(awaitExecutorIdle(exchangeWorkers, Duration.ofSeconds(2))).isTrue();
            assertThat(jdbc.queryForObject("SELECT completed_at IS NULL FROM "
                    + TABLES.requestAttempt(), Boolean.class)).isTrue();
            assertThat(jdbc.queryForObject("SELECT cooldown_until IS NULL FROM "
                    + TABLES.requestState(), Boolean.class)).isTrue();
            assertThat(independent.get(Endpoint.fixtures(), 1)).isInstanceOfSatisfying(DeferredResult.class,
                    deferred -> assertThat(deferred.reason()).isEqualTo(DeferredReason.UNSETTLED));
            assertThat(getCalls).hasValue(1);
        } finally {
            releaseResponseHandler.countDown();
            caller.join(2_000);
            assertThat(caller.isAlive()).isFalse();
            assertThat(responseHandlerFinished.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(awaitExecutorIdle(exchangeWorkers, Duration.ofSeconds(2))).isTrue();
            transport.close();
            assertThat(exchangeWorkers.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
            assertThat(cancellationWorkers.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
            assertThat(supervisedDbWorkers(operations).awaitTermination(1, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void saturatedDbAdmissionIsDeferredWithoutQueuingAGet() throws Exception {
        OwnedSyncDbOperations operations = new OwnedSyncDbOperations(Duration.ofMillis(200));
        CountDownLatch operationsStarted = new CountDownLatch(2);
        CountDownLatch operationsFinished = new CountDownLatch(2);
        CountDownLatch releaseOperations = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server(exchange -> {
            calls.incrementAndGet();
            respond(exchange, 200, new byte[0]);
        });
        OwnedSyncHttpClient transport = transport(
                server, Duration.ofSeconds(2), operations, Duration.ofSeconds(2));
        try {
            for (int i = 0; i < 2; i++) {
                Outcome<Boolean> occupied = operations.call(() -> {
                    operationsStarted.countDown();
                    try {
                        releaseOperations.await();
                        return true;
                    } finally {
                        operationsFinished.countDown();
                    }
                }, OwnedSyncDeadline.after(Duration.ofSeconds(2)));
                assertThat(occupied).isInstanceOf(OwnedSyncDbOperations.Uncertain.class);
            }
            assertThat(operationsStarted.getCount()).isZero();

            DeferredResult result = (DeferredResult) transport.get(Endpoint.fixtures(), 1);
            assertThat(result.reason()).isEqualTo(DeferredReason.DB_OPERATIONS_SATURATED);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.requestAttempt(), Integer.class))
                    .isZero();

            releaseOperations.countDown();
            assertThat(operationsFinished.await(1, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(100);
            assertThat(calls).hasValue(0);
        } finally {
            releaseOperations.countDown();
            operations.close();
        }
    }

    @Test
    void invalidEndpointAndArbitraryBaseAreRejectedBeforeAdmission() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server(exchange -> {
            calls.incrementAndGet();
            respond(exchange, 200, new byte[0]);
        });
        OwnedSyncHttpClient transport = transport(server, Duration.ofSeconds(2));

        assertThatThrownBy(() -> transport.get(new Endpoint(EndpointKind.EVENT_LIVE, 0, null), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transport.get(new Endpoint(EndpointKind.FIXTURES, null, 1), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OwnedSyncHttpClient(budget, URI.create("http://example.com/api/"),
                Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).hasValue(0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.requestAttempt(), Integer.class)).isZero();
    }

    private OwnedSyncHttpClient customTransport(CloseableHttpClient apache, ThreadPoolExecutor control,
                                                OwnedSyncDbOperations operations) {
        OwnedSyncHttpClient transport = new OwnedSyncHttpClient(budget, apache,
                boundedExecutor(2, "test-owned-http-"), control, URI.create("http://127.0.0.1:1/api/"),
                Duration.ofSeconds(2), budgetClock, operations, Duration.ofSeconds(5), true, true);
        transports.add(transport);
        return transport;
    }

    private static ThreadPoolExecutor boundedExecutor(int workers, String threadPrefix) {
        AtomicInteger sequence = new AtomicInteger();
        return new ThreadPoolExecutor(workers, workers, 0, TimeUnit.NANOSECONDS, new SynchronousQueue<>(),
                task -> {
                    Thread thread = new Thread(task, threadPrefix + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
    }

    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private boolean awaitBudgetLockWait(String queryPrefix) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            Boolean waiting = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM pg_stat_activity "
                    + "WHERE pid <> pg_backend_pid() AND datname = current_database() "
                    + "AND wait_event_type = 'Lock' AND query LIKE ?)", Boolean.class, queryPrefix + "%");
            if (Boolean.TRUE.equals(waiting)) return true;
            LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
        }
        return false;
    }

    private boolean awaitUnresolvedAttempt() {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            Integer unresolved = jdbc.queryForObject("SELECT count(*) FROM " + TABLES.requestAttempt()
                    + " WHERE completed_at IS NULL", Integer.class);
            if (unresolved != null && unresolved > 0) return true;
            LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
        }
        return false;
    }

    private boolean awaitCompletedAttempt() {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            Integer completed = jdbc.queryForObject("SELECT count(*) FROM " + TABLES.requestAttempt()
                    + " WHERE completed_at IS NOT NULL", Integer.class);
            if (completed != null && completed > 0) return true;
            LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
        }
        return false;
    }

    private static Void holdRequestStateLock(CountDownLatch acquired, CountDownLatch release) {
        return tx.execute(status -> {
            jdbc.queryForObject("SELECT singleton FROM " + TABLES.requestState()
                    + " WHERE singleton = TRUE FOR UPDATE", Boolean.class);
            acquired.countDown();
            try {
                if (!release.await(4, TimeUnit.SECONDS)) throw new AssertionError("lock release timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
            return null;
        });
    }

    private void assertCompletedPermit() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.requestAttempt()
                + " WHERE completed_at IS NOT NULL", Integer.class)).isEqualTo(1);
    }

    private Ready readyFence() {
        Instant now = budgetClock.instant();
        return new Ready(now.plusSeconds(90), now);
    }

    private OwnedSyncHttpClient transport(HttpServer server, Duration timeout) {
        return transport(budget, server, timeout);
    }

    private OwnedSyncHttpClient transport(OwnedSyncRequestBudget selectedBudget,
                                          HttpServer server, Duration timeout) {
        OwnedSyncHttpClient transport = new OwnedSyncHttpClient(selectedBudget,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/"), timeout);
        transports.add(transport);
        return transport;
    }

    private OwnedSyncHttpClient transport(OwnedSyncRequestBudget selectedBudget, HttpServer server,
                                          Duration timeout, OwnedSyncDbOperations operations,
                                          Duration methodTimeout) {
        OwnedSyncHttpClient transport = new OwnedSyncHttpClient(selectedBudget,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/"),
                timeout, budgetClock, operations, methodTimeout);
        transports.add(transport);
        return transport;
    }

    private OwnedSyncHttpClient transport(HttpServer server, Duration timeout,
                                          OwnedSyncDbOperations operations, Duration methodTimeout) {
        OwnedSyncHttpClient transport = new OwnedSyncHttpClient(budget,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/"),
                timeout, budgetClock, operations, methodTimeout);
        transports.add(transport);
        return transport;
    }

    private HttpServer server(ThrowingHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                handler.handle(exchange);
            } catch (Exception e) {
                exchange.close();
            }
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        servers.add(server);
        return server;
    }

    private static ThreadPoolExecutor supervisedDbWorkers(OwnedSyncDbOperations operations) {
        try {
            Field executor = OwnedSyncDbOperations.class.getDeclaredField("executor");
            executor.setAccessible(true);
            return (ThreadPoolExecutor) executor.get(operations);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Could not inspect the bounded DB supervisor", failure);
        }
    }

    private static boolean awaitDbWorkersIdle(OwnedSyncDbOperations operations, Duration timeout) {
        return awaitExecutorIdle(supervisedDbWorkers(operations), timeout);
    }

    private static void closeTransportAndAssertDbWorkers(OwnedSyncHttpClient transport,
                                                          OwnedSyncDbOperations operations)
            throws InterruptedException {
        transport.close();
        operations.close();
        assertThat(supervisedDbWorkers(operations).awaitTermination(1, TimeUnit.SECONDS)).isTrue();
    }

    private static void joinAndAssertStopped(Thread... threads) throws InterruptedException {
        for (Thread thread : threads) {
            if (thread == null) continue;
            thread.join(5_000);
            assertThat(thread.isAlive()).isFalse();
        }
    }

    private static boolean awaitTurnQueueWait(Thread thread, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            boolean inQueue = thread.getState() == Thread.State.TIMED_WAITING
                    && java.util.Arrays.stream(thread.getStackTrace())
                    .anyMatch(frame -> frame.getMethodName().equals("joinTurn"));
            if (inQueue) return true;
            LockSupport.parkNanos(Duration.ofMillis(2).toNanos());
        }
        return false;
    }

    private static boolean awaitExecutorIdle(ThreadPoolExecutor executor, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (executor.getActiveCount() != 0 && System.nanoTime() < deadline) {
            LockSupport.parkNanos(Duration.ofMillis(1).toNanos());
        }
        return executor.getActiveCount() == 0;
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record ServerObservation(Instant observedAt, long monotonicNanos) {}

    @FunctionalInterface
    private interface ThrowingHandler { void handle(HttpExchange exchange) throws Exception; }

    private static final class DelayedExecutor extends AbstractExecutorService {
        private final CountDownLatch submitted = new CountDownLatch(1);
        private final CountDownLatch finished = new CountDownLatch(1);
        private volatile Runnable queued;
        private volatile boolean shutdown;

        @Override public void execute(Runnable command) {
            if (shutdown) throw new java.util.concurrent.RejectedExecutionException();
            queued = command;
            submitted.countDown();
        }

        boolean awaitSubmitted() throws InterruptedException {
            return submitted.await(2, TimeUnit.SECONDS);
        }

        void release() {
            Runnable task = queued;
            if (task == null) throw new IllegalStateException("no exchange task was submitted");
            Thread.ofPlatform().start(() -> {
                try {
                    task.run();
                } finally {
                    finished.countDown();
                }
            });
        }

        boolean awaitFinished() throws InterruptedException {
            return finished.await(2, TimeUnit.SECONDS);
        }

        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() {
            shutdown = true;
            return queued == null ? List.of() : List.of(queued);
        }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return shutdown; }
    }

    private static final class RejectingInterruptingExecutor extends AbstractExecutorService {
        private volatile boolean shutdown;

        @Override public void execute(Runnable command) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.RejectedExecutionException("test rejection");
        }

        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() { shutdown = true; return List.of(); }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return shutdown; }
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private static void refuseProductionBeforeAnyWrite(DataSource dataSource) {
        JdbcTemplate probe = new JdbcTemplate(dataSource);
        String manager = probe.queryForObject("SELECT to_regclass('public.manager')::text", String.class);
        if (manager != null) throw new IllegalStateException("Refusing owned-sync integration test writes to shared DB");
        assertThat(TABLES.requestState()).endsWith("_test");
        assertThat(TABLES.requestAttempt()).endsWith("_test");
    }

    private static final class PostCommitGateTransactionTemplate extends TransactionTemplate {
        private final int gateTransaction;
        private final CountDownLatch commitReached;
        private final CountDownLatch releaseCommitAck;
        private final CountDownLatch transactionFinished;
        private final boolean loseAcknowledgement;
        private final AtomicInteger transactions = new AtomicInteger();
        private volatile boolean acknowledgementLost;

        private PostCommitGateTransactionTemplate(DataSource source, int gateTransaction,
                                                   CountDownLatch commitReached,
                                                   CountDownLatch releaseCommitAck,
                                                   CountDownLatch transactionFinished,
                                                   boolean loseAcknowledgement) {
            super(new DataSourceTransactionManager(source));
            this.gateTransaction = gateTransaction;
            this.commitReached = commitReached;
            this.releaseCommitAck = releaseCommitAck;
            this.transactionFinished = transactionFinished;
            this.loseAcknowledgement = loseAcknowledgement;
        }

        @Override
        public <T> T execute(TransactionCallback<T> action) throws TransactionException {
            int transaction = transactions.incrementAndGet();
            T result = super.execute(action);
            if (transaction != gateTransaction) return result;
            commitReached.countDown();
            try {
                if (!releaseCommitAck.await(5, TimeUnit.SECONDS)) {
                    throw new TransactionSystemException("test did not release the post-commit gate");
                }
                if (loseAcknowledgement) {
                    acknowledgementLost = true;
                    throw new TransactionSystemException("simulated lost commit acknowledgement");
                }
                return result;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new TransactionSystemException("interrupted at the post-commit gate");
            } finally {
                transactionFinished.countDown();
            }
        }

        private boolean acknowledgementLost() {
            return acknowledgementLost;
        }

        private int transactionCount() {
            return transactions.get();
        }
    }

    private static final class BlockingDataSource extends DelegatingDataSource {
        private final CountDownLatch acquisitionEntered;
        private final CountDownLatch releaseAcquisition;
        private final AtomicInteger acquiring = new AtomicInteger();
        private final AtomicInteger maximumAcquiring = new AtomicInteger();

        private BlockingDataSource(DataSource target, CountDownLatch acquisitionEntered,
                                   CountDownLatch releaseAcquisition) {
            super(target);
            this.acquisitionEntered = acquisitionEntered;
            this.releaseAcquisition = releaseAcquisition;
        }

        @Override
        public Connection getConnection() throws SQLException {
            int active = acquiring.incrementAndGet();
            maximumAcquiring.accumulateAndGet(active, Math::max);
            acquisitionEntered.countDown();
            try {
                if (!releaseAcquisition.await(5, TimeUnit.SECONDS)) {
                    throw new SQLException("test did not release blocked connection acquisition");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new SQLException("interrupted at blocked connection acquisition", interrupted);
            } finally {
                acquiring.decrementAndGet();
            }
            return super.getConnection();
        }

        private int maximumConcurrentAcquisitions() {
            return maximumAcquiring.get();
        }
    }

    private static final class MutableClock extends Clock {
        private volatile Instant instant;
        private MutableClock(Instant instant) { this.instant = instant; }
        void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
