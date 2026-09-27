package fi.karivanttinen.fplassistant.sync;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import fi.karivanttinen.fplassistant.sync.OwnedSyncRequestBudget.Allowed;
import fi.karivanttinen.fplassistant.sync.OwnedSyncRequestBudget.Completion;
import fi.karivanttinen.fplassistant.sync.OwnedSyncRequestBudget.Deferred;
import fi.karivanttinen.fplassistant.sync.OwnedSyncRequestBudget.DeferredReason;
import fi.karivanttinen.fplassistant.sync.OwnedSyncRequestBudget.Permit;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.Endpoint;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.SourceRead;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;

/**
 * Inert, explicitly-invoked transport for the four public FPL source endpoints.
 *
 * <p>It does not schedule, validate, stage or publish anything. One admitted invocation submits one
 * GET. Apache HttpClient hard cancellation aborts the active exchange. Cancellation acknowledgement
 * is awaited only briefly; if worker termination remains unconfirmed, the durable permit stays held
 * until its original expiry. This does not guarantee that the peer has observed a closed socket.
 */
public final class OwnedSyncHttpClient implements AutoCloseable {

    private static final URI FPL_BASE = URI.create("https://fantasy.premierleague.com/api/");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration PERMIT_SAFETY_MARGIN = Duration.ofMillis(1);
    private static final Duration CANCELLATION_ACK_TIMEOUT = Duration.ofSeconds(1);
    private static final int MAX_RETRY_AFTER_CHARS = 1_024;

    private final OwnedSyncRequestBudget budget;
    private final CloseableHttpClient client;
    private final ExecutorService exchanges;
    private final URI baseUri;
    private final Duration requestTimeout;
    private final Clock clock;

    /** Production construction deliberately fixes the source host and transport limits. */
    public OwnedSyncHttpClient(OwnedSyncRequestBudget budget) {
        this(budget, newSingleAttemptClient(), newExchangeExecutor(), FPL_BASE,
                DEFAULT_REQUEST_TIMEOUT, Clock.systemUTC(), false);
    }

    /** Package-private loopback-only seam for local transport tests. */
    OwnedSyncHttpClient(OwnedSyncRequestBudget budget, URI loopbackBase, Duration requestTimeout) {
        this(budget, testClient(loopbackBase), newExchangeExecutor(), loopbackBase,
                requestTimeout, Clock.systemUTC(), true);
    }

    /** Package-private clock seam for deterministic loopback transport tests. */
    OwnedSyncHttpClient(OwnedSyncRequestBudget budget, URI loopbackBase, Duration requestTimeout,
                        Clock clock) {
        this(budget, testClient(loopbackBase), newExchangeExecutor(), loopbackBase,
                requestTimeout, clock, true);
    }

    /** Package-private cancellation seam for a worker that does not acknowledge abort. */
    OwnedSyncHttpClient(OwnedSyncRequestBudget budget, URI loopbackBase, Duration requestTimeout,
                        ExecutorService exchanges) {
        this(budget, testClient(loopbackBase), exchanges, loopbackBase,
                requestTimeout, Clock.systemUTC(), true);
    }

    private OwnedSyncHttpClient(OwnedSyncRequestBudget budget, CloseableHttpClient client,
                                ExecutorService exchanges, URI baseUri,
                                Duration requestTimeout, Clock clock, boolean testBase) {
        this.budget = Objects.requireNonNull(budget);
        this.client = Objects.requireNonNull(client);
        this.exchanges = Objects.requireNonNull(exchanges);
        this.baseUri = normalizedBase(baseUri, testBase);
        this.requestTimeout = positive(requestTimeout);
        this.clock = Objects.requireNonNull(clock);
    }

    public Result get(Endpoint endpoint, int failureAttempt) {
        String path = path(endpoint); // Validate all model-derived identifiers before admission.
        if (failureAttempt < 1) throw new IllegalArgumentException("failureAttempt must be positive");

        var admission = budget.admit();
        if (admission instanceof Deferred deferred) {
            return new DeferredResult(deferred.nextDue(), deferred.reason());
        }

        Permit permit = ((Allowed) admission).permit();
        Instant callDeadline = clock.instant().plus(requestTimeout);
        Instant permitDeadline = permit.expiresAt().minus(PERMIT_SAFETY_MARGIN);
        Instant deadline = callDeadline.isBefore(permitDeadline) ? callDeadline : permitDeadline;
        AtomicReference<Headers> headers = new AtomicReference<>();
        HttpGet request = null;
        Future<?> exchange = null;
        Instant readStarted = clock.instant();
        Completion completion = Completion.error();
        try {
            Duration remaining = Duration.between(readStarted, deadline);
            if (remaining.isZero() || remaining.isNegative()) {
                completion = Completion.timeout(failureAttempt);
                return failure(FailureKind.TIMEOUT, "Request permit expired before submission",
                        readStarted, clock.instant(), headers.get());
            }
            request = new HttpGet(baseUri.resolve(path));
            request.setHeader("Accept", "application/json");
            request.setConfig(requestConfig(remaining));
            HttpGet submitted = request;
            exchange = exchanges.submit(() -> execute(submitted, headers));
            long waitNanos = Math.max(1, Duration.between(clock.instant(), deadline).toNanos());
            WireResponse response = (WireResponse) exchange.get(waitNanos, TimeUnit.NANOSECONDS);
            Instant readFinished = clock.instant();
            completion = response.statusCode >= 200 && response.statusCode < 300
                    ? Completion.success()
                    : Completion.http(response.statusCode, response.retryAfter, failureAttempt);
            return new Response(response.statusCode, response.body, response.retryAfter,
                    new SourceRead(readStarted, readFinished));
        } catch (InterruptedException e) {
            completion = failureCompletion(headers.get(), failureAttempt, Completion.error());
            if (!abortAndAwait(request, exchange)) completion = completion.holdingPermitUntilExpiry();
            Thread.currentThread().interrupt();
            return failure(FailureKind.INTERRUPTED, "Request interrupted", readStarted, clock.instant(), headers.get());
        } catch (TimeoutException e) {
            completion = transientCompletion(headers.get(), failureAttempt);
            if (!abortAndAwait(request, exchange)) completion = completion.holdingPermitUntilExpiry();
            return failure(FailureKind.TIMEOUT, "Request timed out", readStarted, clock.instant(), headers.get());
        } catch (ExecutionException e) {
            Throwable cause = rootCause(e);
            if (hasCause(e, SocketTimeoutException.class)) {
                completion = transientCompletion(headers.get(), failureAttempt);
                return failure(FailureKind.TIMEOUT, "Request timed out", readStarted, clock.instant(), headers.get());
            }
            completion = failureCompletion(headers.get(), failureAttempt, Completion.error());
            if (cause instanceof BodyTooLargeException) {
                return failure(FailureKind.OVERSIZE, cause.getMessage(), readStarted, clock.instant(), headers.get());
            }
            return failure(FailureKind.IO_ERROR, boundedMessage(cause), readStarted, clock.instant(), headers.get());
        } catch (RuntimeException e) {
            completion = failureCompletion(headers.get(), failureAttempt, Completion.error());
            if (!abortAndAwait(request, exchange)) completion = completion.holdingPermitUntilExpiry();
            return failure(FailureKind.IO_ERROR, boundedMessage(e), readStarted, clock.instant(), headers.get());
        } finally {
            budget.complete(permit, completion);
        }
    }

    private WireResponse execute(HttpGet request, AtomicReference<Headers> headers) throws IOException {
        return client.execute(request, response -> {
            int statusCode = response.getCode();
            String retryAfter = bounded(header(response.getFirstHeader("Retry-After")));
            headers.set(new Headers(statusCode, retryAfter));
            return new WireResponse(statusCode, boundedBody(response.getEntity()), retryAfter);
        });
    }

    private static byte[] boundedBody(HttpEntity entity) throws IOException {
        if (entity == null) return new byte[0];
        long declared = entity.getContentLength();
        if (declared > OwnedSyncStore.MAX_PAYLOAD_BYTES) {
            throw new BodyTooLargeException(OwnedSyncStore.MAX_PAYLOAD_BYTES);
        }
        int initial = declared >= 0 ? (int) declared : Math.min(8_192, OwnedSyncStore.MAX_PAYLOAD_BYTES);
        ByteArrayOutputStream body = new ByteArrayOutputStream(initial);
        byte[] buffer = new byte[8_192];
        try (InputStream input = entity.getContent()) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count > OwnedSyncStore.MAX_PAYLOAD_BYTES - body.size()) {
                    throw new BodyTooLargeException(OwnedSyncStore.MAX_PAYLOAD_BYTES);
                }
                body.write(buffer, 0, count);
            }
        }
        return body.toByteArray();
    }

    private static RequestConfig requestConfig(Duration remaining) {
        return RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.of(remaining))
                .setResponseTimeout(Timeout.of(remaining))
                .setHardCancellationEnabled(true)
                .setRedirectsEnabled(false)
                .build();
    }

    private static Completion transientCompletion(Headers headers, int failureAttempt) {
        return failureCompletion(headers, failureAttempt, Completion.timeout(failureAttempt));
    }

    private static Completion failureCompletion(Headers headers, int failureAttempt, Completion fallback) {
        if (headers != null && (headers.statusCode == 429 || headers.statusCode >= 500)) {
            return Completion.http(headers.statusCode, headers.retryAfter, failureAttempt);
        }
        return fallback;
    }

    Optional<Instant> nextAdmissionHint() {
        return budget.nextAdmissionHint();
    }

    private static Failure failure(FailureKind kind, String message, Instant started, Instant finished,
                                   Headers headers) {
        return new Failure(kind, message, headers == null ? null : headers.statusCode,
                headers == null ? null : headers.retryAfter, new SourceRead(started, finished));
    }

    private static String path(Endpoint endpoint) {
        Objects.requireNonNull(endpoint);
        return switch (endpoint.kind()) {
            case BOOTSTRAP_STATIC -> noIds(endpoint, "bootstrap-static/");
            case FIXTURES -> noIds(endpoint, "fixtures/");
            case EVENT_LIVE -> endpoint.eventId() != null && endpoint.eventId() > 0 && endpoint.playerId() == null
                    ? "event/" + endpoint.eventId() + "/live/" : invalid(endpoint);
            case ELEMENT_SUMMARY -> endpoint.eventId() == null && endpoint.playerId() != null && endpoint.playerId() > 0
                    ? "element-summary/" + endpoint.playerId() + "/" : invalid(endpoint);
        };
    }

    private static String noIds(Endpoint endpoint, String path) {
        return endpoint.eventId() == null && endpoint.playerId() == null ? path : invalid(endpoint);
    }

    private static String invalid(Endpoint endpoint) {
        throw new IllegalArgumentException("Endpoint identifiers do not match " + endpoint.kind());
    }

    private static CloseableHttpClient testClient(URI loopbackBase) {
        normalizedBase(loopbackBase, true); // Reject before allocating client-owned resources.
        return newSingleAttemptClient();
    }

    private static ExecutorService newExchangeExecutor() {
        return Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("owned-sync-http-", 0).factory());
    }

    private static CloseableHttpClient newSingleAttemptClient() {
        var connections = PoolingHttpClientConnectionManagerBuilder.create()
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.of(CONNECT_TIMEOUT))
                        .build())
                .build();
        return HttpClients.custom()
                .setConnectionManager(connections)
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .build();
    }

    private static URI normalizedBase(URI base, boolean testBase) {
        Objects.requireNonNull(base);
        String host = base.getHost();
        boolean loopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                || "::1".equals(host) || "0:0:0:0:0:0:0:1".equals(host);
        if ((!testBase && !FPL_BASE.equals(base)) || (testBase && (!loopback || !"http".equals(base.getScheme())))
                || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null) {
            throw new IllegalArgumentException("Only the fixed FPL base or a test loopback base is allowed");
        }
        String value = base.toString();
        return value.endsWith("/") ? base : URI.create(value + "/");
    }

    private static Duration positive(Duration timeout) {
        Objects.requireNonNull(timeout);
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(DEFAULT_REQUEST_TIMEOUT) > 0) {
            throw new IllegalArgumentException("request timeout must be positive and at most 30 seconds");
        }
        return timeout;
    }

    private static String header(Header header) {
        return header == null ? null : header.getValue();
    }

    private static String bounded(String value) {
        if (value == null) return null;
        return value.length() <= MAX_RETRY_AFTER_CHARS ? value : value.substring(0, MAX_RETRY_AFTER_CHARS);
    }

    private static String boundedMessage(Throwable error) {
        String value = error.getMessage();
        if (value == null || value.isBlank()) value = error.getClass().getSimpleName();
        return value.length() <= OwnedSyncStore.MAX_ERROR_CHARS
                ? value : value.substring(0, OwnedSyncStore.MAX_ERROR_CHARS);
    }

    private static Throwable rootCause(Throwable error) {
        Throwable result = error;
        while (result.getCause() != null) result = result.getCause();
        return result;
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (type.isInstance(current)) return true;
        }
        return false;
    }

    private static boolean abortAndAwait(HttpGet request, Future<?> exchange) {
        if (request != null) request.cancel();
        if (exchange == null) return true;
        long deadline = System.nanoTime() + CANCELLATION_ACK_TIMEOUT.toNanos();
        boolean interrupted = false;
        try {
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return false;
                try {
                    exchange.get(remaining, TimeUnit.NANOSECONDS);
                    return true;
                } catch (InterruptedException e) {
                    interrupted = true;
                } catch (ExecutionException | java.util.concurrent.CancellationException e) {
                    return true;
                } catch (TimeoutException e) {
                    return false;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /** Aborts active I/O and gives workers at most one second to acknowledge shutdown. */
    @Override public void close() {
        client.close(CloseMode.IMMEDIATE);
        exchanges.shutdownNow();
        boolean interrupted = false;
        try {
            try {
                exchanges.awaitTermination(CANCELLATION_ACK_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                interrupted = true;
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    public sealed interface Result permits DeferredResult, Response, Failure {}

    public record DeferredResult(Instant nextDue, DeferredReason reason) implements Result {
        public DeferredResult {
            Objects.requireNonNull(nextDue);
            Objects.requireNonNull(reason);
        }
    }

    /** Raw transport evidence only; a 200 response is not parsed, validated, staged or verified. */
    public record Response(int statusCode, byte[] body, String retryAfter, SourceRead read,
                           Instant nextDue) implements Result {
        public Response(int statusCode, byte[] body, String retryAfter, SourceRead read) {
            this(statusCode, body, retryAfter, read, null);
        }
        public Response {
            if (statusCode < 100 || statusCode > 599) throw new IllegalArgumentException("invalid HTTP status");
            body = Objects.requireNonNull(body).clone();
            Objects.requireNonNull(read);
        }
        @Override public byte[] body() { return body.clone(); }
    }

    public record Failure(FailureKind kind, String message, Integer statusCode,
                          String retryAfter, SourceRead read, Instant nextDue) implements Result {
        public Failure(FailureKind kind, String message, Integer statusCode,
                       String retryAfter, SourceRead read) {
            this(kind, message, statusCode, retryAfter, read, null);
        }
        public Failure {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(message);
            Objects.requireNonNull(read);
        }
    }

    public enum FailureKind { TIMEOUT, OVERSIZE, IO_ERROR, INTERRUPTED }

    private record Headers(int statusCode, String retryAfter) {}
    private record WireResponse(int statusCode, byte[] body, String retryAfter) {}

    private static final class BodyTooLargeException extends IOException {
        private BodyTooLargeException(int maximum) {
            super("Response body exceeds " + maximum + " bytes");
        }
    }
}
