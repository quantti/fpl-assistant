package fi.karivanttinen.fplassistant.sync;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import javax.sql.DataSource;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import fi.karivanttinen.fplassistant.sync.OwnedSyncHttpClient.DeferredResult;
import fi.karivanttinen.fplassistant.sync.OwnedSyncHttpClient.Failure;
import fi.karivanttinen.fplassistant.sync.OwnedSyncHttpClient.FailureKind;
import fi.karivanttinen.fplassistant.sync.OwnedSyncHttpClient.Response;
import fi.karivanttinen.fplassistant.sync.OwnedSyncRequestBudget.DeferredReason;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.Endpoint;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.EndpointKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OwnedSyncHttpClientIntegrationTest {

    private static final OwnedSyncTables TABLES = OwnedSyncTables.tests();
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;

    private final List<HttpServer> servers = new ArrayList<>();
    private final List<OwnedSyncHttpClient> transports = new ArrayList<>();
    private MutableClock budgetClock;
    private OwnedSyncRequestBudget budget;

    @BeforeAll
    static void initializeDatabase() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
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
    void connectionClosedBeforeHeadersIsNotRetriedAfterTheJdkClientIsInitialized() throws Exception {
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

        Failure failure = (Failure) transport(server, Duration.ofSeconds(2)).get(Endpoint.fixtures(), 1);

        assertThat(failure.kind()).isEqualTo(FailureKind.IO_ERROR);
        assertThat(calls).hasValue(1);
        assertCompletedPermit();
    }

    @Test
    void retryAfterOn429IsPersistedAndOnlyOneAttemptIsSent() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server(exchange -> {
            calls.incrementAndGet();
            exchange.getResponseHeaders().add("Retry-After", "120");
            respond(exchange, 429, "slow down".getBytes(StandardCharsets.UTF_8));
        });
        OwnedSyncHttpClient transport = transport(server, Duration.ofSeconds(2));

        Response response = (Response) transport.get(Endpoint.fixtures(), 1);
        DeferredResult deferred = (DeferredResult) transport.get(Endpoint.fixtures(), 1);

        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.retryAfter()).isEqualTo("120");
        assertThat(deferred.reason()).isEqualTo(DeferredReason.COOLDOWN);
        assertThat(deferred.nextDue()).isEqualTo(budgetClock.instant().plusSeconds(120));
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
    void interruptionCancelsTheExchangeRestoresTheFlagAndCompletesPermit() throws Exception {
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
        assertThat(((Failure) result.get()).kind()).isEqualTo(FailureKind.INTERRUPTED);
        assertThat(interrupted).isTrue();
        assertCompletedPermit();
    }

    @Test
    void unacknowledgedCancellationReturnsBoundedlyAndHoldsPermitThroughCooldownUntilExpiry() throws Exception {
        HttpServer server = server(exchange -> respond(exchange, 200, new byte[0]));
        var blocked = new UnacknowledgedExecutor();
        OwnedSyncHttpClient transport = new OwnedSyncHttpClient(budget,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/"),
                Duration.ofMillis(100), blocked);
        transports.add(transport);

        long started = System.nanoTime();
        Failure failure = (Failure) transport.get(Endpoint.fixtures(), 1);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(failure.kind()).isEqualTo(FailureKind.TIMEOUT);
        assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
        assertThat(jdbc.queryForObject("SELECT hold_until_expiry FROM " + TABLES.requestAttempt(),
                Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT completed_at IS NOT NULL FROM " + TABLES.requestAttempt(),
                Boolean.class)).isTrue();
        DeferredResult cooldown = (DeferredResult) transport.get(Endpoint.fixtures(), 1);
        assertThat(cooldown.reason()).isEqualTo(DeferredReason.COOLDOWN);

        budgetClock.advance(Duration.ofSeconds(3));
        assertThat(budget.admit()).isInstanceOf(OwnedSyncRequestBudget.Allowed.class);
        budgetClock.advance(Duration.ofSeconds(1));
        OwnedSyncRequestBudget.Deferred occupied = (OwnedSyncRequestBudget.Deferred) budget.admit();
        assertThat(occupied.reason()).isEqualTo(DeferredReason.LIVE_PERMITS);
        assertThat(occupied.nextDue()).isEqualTo(Instant.parse("2097-08-15T12:02:00Z"));

        budgetClock.advance(Duration.between(budgetClock.instant(), occupied.nextDue()));
        assertThat(budget.admit()).isInstanceOf(OwnedSyncRequestBudget.Allowed.class);
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

    private void assertCompletedPermit() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.requestAttempt()
                + " WHERE completed_at IS NOT NULL", Integer.class)).isEqualTo(1);
    }

    private OwnedSyncHttpClient transport(HttpServer server, Duration timeout) {
        OwnedSyncHttpClient transport = new OwnedSyncHttpClient(budget,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/"), timeout);
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

    @FunctionalInterface
    private interface ThrowingHandler { void handle(HttpExchange exchange) throws Exception; }

    private static final class UnacknowledgedExecutor extends AbstractExecutorService {
        private volatile boolean shutdown;

        @Override protected <T> java.util.concurrent.RunnableFuture<T> newTaskFor(Callable<T> task) {
            return new FutureTask<>(task) {
                @Override public T get() {
                    throw new AssertionError("cancellation acknowledgement must never wait unboundedly");
                }

                @Override public T get(long timeout, TimeUnit unit) throws TimeoutException {
                    LockSupport.parkNanos(unit.toNanos(timeout));
                    throw new TimeoutException("worker did not acknowledge cancellation");
                }
            };
        }

        @Override public void execute(Runnable command) {}
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() { shutdown = true; return Collections.emptyList(); }
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

    private static final class MutableClock extends Clock {
        private Instant instant;
        private MutableClock(Instant instant) { this.instant = instant; }
        void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
