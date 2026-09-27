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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import fi.karivanttinen.fplassistant.sync.OwnedSyncDbOperations.Outcome;
import fi.karivanttinen.fplassistant.sync.OwnedSyncDbOperations.Rejected;
import fi.karivanttinen.fplassistant.sync.OwnedSyncDbOperations.Rejection;
import fi.karivanttinen.fplassistant.sync.OwnedSyncDbOperations.Success;
import fi.karivanttinen.fplassistant.sync.OwnedSyncDbOperations.Uncertain;
import fi.karivanttinen.fplassistant.sync.OwnedSyncDbOperations.Uncertainty;
import fi.karivanttinen.fplassistant.sync.OwnedSyncRequestBudget.Admission;
import fi.karivanttinen.fplassistant.sync.OwnedSyncRequestBudget.Allowed;
import fi.karivanttinen.fplassistant.sync.OwnedSyncRequestBudget.Completion;
import fi.karivanttinen.fplassistant.sync.OwnedSyncRequestBudget.Deferred;
import fi.karivanttinen.fplassistant.sync.OwnedSyncRequestBudget.DeferredReason;
import fi.karivanttinen.fplassistant.sync.OwnedSyncRequestBudget.Permit;
import fi.karivanttinen.fplassistant.sync.OwnedSyncRequestBudget.Settlement;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.Endpoint;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.FutureFloor;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.HttpFenceResult;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.Ready;
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
 * <p>It does not schedule, validate, stage or publish anything. An admitted invocation submits at
 * most one GET, and only while enough bounded time remains for cancellation and settlement.
 * Cancellation is requested on a bounded worker; only the exchange worker's exit latch
 * acknowledges termination. If it remains unconfirmed, the durable attempt is left unresolved and
 * blocks later admission indefinitely. This does not guarantee that the peer has observed a closed
 * socket.
 */
public final class OwnedSyncHttpClient implements AutoCloseable {

    private static final URI FPL_BASE = URI.create("https://fantasy.premierleague.com/api/");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration METHOD_DEADLINE = Duration.ofSeconds(180);
    private static final Duration PREPARATION_DEADLINE = Duration.ofSeconds(73);
    private static final Duration TURN_GRACE = Duration.ofSeconds(3);
    private static final Duration DB_REJECTION_RECHECK = Duration.ofSeconds(1);
    private static final Duration DB_ADMISSION_UNCERTAIN_RECHECK = Duration.ofSeconds(30);
    private static final Duration UNSETTLED_RECHECK = Duration.ofSeconds(30);
    private static final Duration SETTLEMENT_SAFETY_MARGIN = Duration.ofSeconds(1);
    private static final Duration PERMIT_SAFETY_MARGIN = Duration.ofMillis(1);
    private static final Duration CANCELLATION_ACK_TIMEOUT = Duration.ofSeconds(1);
    private static final int MAX_RETRY_AFTER_CHARS = 1_024;

    private final OwnedSyncRequestBudget budget;
    private final OwnedSyncDbOperations dbOperations;
    private final CloseableHttpClient client;
    private final ExecutorService exchanges;
    private final ExecutorService cancellations;
    private final URI baseUri;
    private final Duration requestTimeout;
    private final Duration methodTimeout;
    private final Clock clock;
    private final LongSupplier turnNanoTime;
    private final boolean closeDbOperations;
    private final boolean loopbackTest;
    private final Object sendGate = new Object();
    private final Object turnGate = new Object();
    private final java.util.ArrayDeque<TurnTicket> turns = new java.util.ArrayDeque<>();
    private boolean closing;
    private boolean turnClosed;
    private boolean unsafeTurnChain;

    /** Production construction deliberately fixes the source host and transport limits. */
    public OwnedSyncHttpClient(OwnedSyncRequestBudget budget) {
        this(budget, newSingleAttemptClient(), newExchangeExecutor(), newCancellationExecutor(), FPL_BASE,
                DEFAULT_REQUEST_TIMEOUT, Clock.systemUTC(), new OwnedSyncDbOperations(),
                METHOD_DEADLINE, false, true);
    }

    /** Package-private loopback-only seam for local transport tests. */
    OwnedSyncHttpClient(OwnedSyncRequestBudget budget, URI loopbackBase, Duration requestTimeout) {
        this(budget, testClient(loopbackBase), newExchangeExecutor(), newCancellationExecutor(), loopbackBase,
                requestTimeout, Clock.systemUTC(), new OwnedSyncDbOperations(), METHOD_DEADLINE, true, true);
    }

    /** Package-private clock seam for deterministic loopback transport tests. */
    OwnedSyncHttpClient(OwnedSyncRequestBudget budget, URI loopbackBase, Duration requestTimeout,
                        Clock clock) {
        this(budget, testClient(loopbackBase), newExchangeExecutor(), newCancellationExecutor(), loopbackBase,
                requestTimeout, clock, new OwnedSyncDbOperations(), METHOD_DEADLINE, true, true);
    }

    /** Package-private cancellation seam for a worker that does not acknowledge abort. */
    OwnedSyncHttpClient(OwnedSyncRequestBudget budget, URI loopbackBase, Duration requestTimeout,
                        ExecutorService exchanges) {
        this(budget, testClient(loopbackBase), exchanges, newCancellationExecutor(), loopbackBase,
                requestTimeout, Clock.systemUTC(), new OwnedSyncDbOperations(), METHOD_DEADLINE, true, true);
    }

    /** Package-private clock seam for delayed exchange-worker tests. */
    OwnedSyncHttpClient(OwnedSyncRequestBudget budget, URI loopbackBase, Duration requestTimeout,
                        Clock clock, ExecutorService exchanges) {
        this(budget, testClient(loopbackBase), exchanges, newCancellationExecutor(), loopbackBase,
                requestTimeout, clock, new OwnedSyncDbOperations(), METHOD_DEADLINE, true, true);
    }

    /** Loopback seam shares the supplied supervisor and permits short method deadlines in tests. */
    OwnedSyncHttpClient(OwnedSyncRequestBudget budget, URI loopbackBase, Duration requestTimeout,
                        Clock clock, OwnedSyncDbOperations dbOperations, Duration methodTimeout) {
        this(budget, testClient(loopbackBase), newExchangeExecutor(), newCancellationExecutor(), loopbackBase,
                requestTimeout, clock, dbOperations, methodTimeout, true, false);
    }

    /** Package-private monotonic-clock seam for deterministic FIFO deadline tests. */
    OwnedSyncHttpClient(OwnedSyncRequestBudget budget, URI loopbackBase, Duration requestTimeout,
                        Clock clock, OwnedSyncDbOperations dbOperations, Duration methodTimeout,
                        LongSupplier turnNanoTime) {
        this(budget, testClient(loopbackBase), newExchangeExecutor(), newCancellationExecutor(), loopbackBase,
                requestTimeout, clock, dbOperations, methodTimeout, true, false, turnNanoTime);
    }

    OwnedSyncHttpClient(OwnedSyncRequestBudget budget, CloseableHttpClient client,
                        ExecutorService exchanges, ExecutorService cancellations,
                        URI baseUri, Duration requestTimeout,
                        Clock clock, OwnedSyncDbOperations dbOperations, Duration methodTimeout,
                        boolean testBase, boolean closeDbOperations) {
        this(budget, client, exchanges, cancellations, baseUri, requestTimeout, clock, dbOperations,
                methodTimeout, testBase, closeDbOperations, System::nanoTime);
    }

    OwnedSyncHttpClient(OwnedSyncRequestBudget budget, CloseableHttpClient client,
                        ExecutorService exchanges, ExecutorService cancellations,
                        URI baseUri, Duration requestTimeout,
                        Clock clock, OwnedSyncDbOperations dbOperations, Duration methodTimeout,
                        boolean testBase, boolean closeDbOperations, LongSupplier turnNanoTime) {
        this.budget = Objects.requireNonNull(budget);
        this.dbOperations = Objects.requireNonNull(dbOperations);
        this.client = Objects.requireNonNull(client);
        this.exchanges = Objects.requireNonNull(exchanges);
        this.cancellations = Objects.requireNonNull(cancellations);
        this.baseUri = normalizedBase(baseUri, testBase);
        this.requestTimeout = positive(requestTimeout);
        this.methodTimeout = boundedMethodTimeout(methodTimeout);
        this.clock = Objects.requireNonNull(clock);
        this.turnNanoTime = Objects.requireNonNull(turnNanoTime);
        this.closeDbOperations = closeDbOperations;
        this.loopbackTest = testBase;
    }

    /** Two-argument calls are reserved for package-private loopback tests. */
    Result get(Endpoint endpoint, int failureAttempt) {
        if (!loopbackTest) {
            throw new IllegalStateException("Production HTTP requests require a caller fence");
        }
        return getInternal(endpoint, failureAttempt, null);
    }

    /**
     * Fetches one fixed public FPL endpoint after two supervised DB-only caller-fence checks.
     * The supplier must only inspect durable DB state; it must not initiate or schedule HTTP.
     */
    public Result get(Endpoint endpoint, int failureAttempt, Supplier<HttpFenceResult> fence) {
        return getInternal(endpoint, failureAttempt, Objects.requireNonNull(fence));
    }

    private Result getInternal(Endpoint endpoint, int failureAttempt, Supplier<HttpFenceResult> fence) {
        long methodEntryNanos = System.nanoTime();
        OwnedSyncDeadline methodDeadline = OwnedSyncDeadline.after(methodTimeout, methodEntryNanos);
        OwnedSyncDeadline preparationDeadline = methodDeadline.cappedBy(PREPARATION_DEADLINE, methodEntryNanos);
        OwnedSyncDeadline graceDeadline = methodDeadline.cappedBy(TURN_GRACE, methodEntryNanos);
        TurnJoin joined = joinTurn(graceDeadline);
        if (joined.failure != null) return turnJoinFailure(joined.failure);

        TurnTicket ticket = joined.ticket;
        boolean safeToHandover = false;
        try {
            if (isClosing()) return locallyClosed();
            if (ticket.poisoned) return unresolved(DeferredReason.UNSETTLED);
            String path;
            try {
                path = path(endpoint); // Validate only after joining the FIFO turn.
                if (failureAttempt < 1) throw new IllegalArgumentException("failureAttempt must be positive");
            } catch (IllegalArgumentException | NullPointerException invalidRequest) {
                safeToHandover = true;
                throw invalidRequest;
            }
            Result result = getWithTurnHeld(path, failureAttempt, fence, methodDeadline,
                    preparationDeadline, graceDeadline, ticket.waited);
            safeToHandover = safeForHandover(result);
            return result;
        } finally {
            releaseTurn(ticket, safeToHandover);
        }
    }

    private Result getWithTurnHeld(String path, int failureAttempt,
                                   Supplier<HttpFenceResult> fence, OwnedSyncDeadline methodDeadline,
                                   OwnedSyncDeadline preparationDeadline, OwnedSyncDeadline graceDeadline,
                                   boolean waitedForTurn) {
        if (isClosing()) return locallyClosed();

        Admission admission = null;
        for (int admissionTry = 0; admissionTry < 2; admissionTry++) {
            if (fence != null) {
                Outcome<HttpFenceResult> beforeAdmission = checkFence(fence, preparationDeadline);
                if (!(beforeAdmission instanceof Success<?> success)) return fenceFailure(beforeAdmission);
                Object checked = success.value();
                if (checked instanceof FutureFloor futureFloor) {
                    return new DeferredResult(futureFloor.nextDue(), DeferredReason.HTTP_NOT_SUBMITTED);
                }
                if (!(checked instanceof Ready)) return uncertainAdmission(Uncertainty.OPERATION_FAILED);
            }

            UUID attemptId = UUID.randomUUID();
            Outcome<Admission> admissionOutcome = dbOperations.call(
                    () -> budget.admit(attemptId), preparationDeadline);
            if (admissionOutcome instanceof Rejected<?> rejected) {
                return rejectedAdmission(rejected.reason());
            }
            if (admissionOutcome instanceof Uncertain<?> uncertain) {
                return uncertainAdmission(uncertain.reason());
            }

            admission = (Admission) ((Success<?>) admissionOutcome).value();
            if (admission instanceof Deferred deferred) {
                if (admissionTry == 0 && waitedForTurn
                        && deferred.reason() == DeferredReason.MINIMUM_SPACING) {
                    SpacingWait wait = waitForSpacing(deferred.nextDue(), graceDeadline);
                    if (wait == SpacingWait.READY) continue;
                    if (wait == SpacingWait.CLOSED) return locallyClosed();
                    if (wait == SpacingWait.INTERRUPTED) {
                        return databaseDeferral(DeferredReason.HTTP_NOT_SUBMITTED);
                    }
                }
                return new DeferredResult(deferred.nextDue(), deferred.reason());
            }
            break;
        }

        if (!(admission instanceof Allowed allowed)) {
            throw new IllegalStateException("Admission retry ended without a result");
        }
        Permit permit = allowed.permit();
        Ready postFence = null;
        if (fence != null) {
            Outcome<HttpFenceResult> afterAdmission = checkFence(fence, preparationDeadline);
            if (afterAdmission instanceof Success<?> success) {
                Object checked = success.value();
                if (checked instanceof FutureFloor futureFloor) {
                    return settledFutureFloor(permit, futureFloor.nextDue(), methodDeadline);
                }
                if (checked instanceof Ready ready) {
                    postFence = ready;
                } else {
                    return settledNoFetch(permit, Completion.error(), methodDeadline,
                            DeferredReason.NO_FETCH_UNCERTAIN);
                }
            } else {
                return settledNoFetch(permit, Completion.error(), methodDeadline,
                        fenceFailureReason(afterAdmission));
            }
        }
        if (isClosing()) {
            return settledNoFetch(permit, Completion.error(), methodDeadline, DeferredReason.HTTP_CLIENT_CLOSED);
        }
        Duration permitRemaining = Duration.between(clock.instant(), permit.expiresAt());
        if (permitRemaining.isZero() || permitRemaining.isNegative()) {
            return settledNoFetch(permit, Completion.error(), methodDeadline,
                    DeferredReason.HTTP_NOT_SUBMITTED);
        }

        OwnedSyncDeadline permitDeadline = OwnedSyncDeadline.after(permitRemaining);
        Duration settlementAndCancellationReserve = dbOperations.maximumCallerWait()
                .plus(SETTLEMENT_SAFETY_MARGIN).plus(CANCELLATION_ACK_TIMEOUT);
        OwnedSyncDeadline fenceDeadline = null;
        if (postFence != null) {
            Duration fenceRemaining = Duration.between(clock.instant(), postFence.expiresAt());
            if (fenceRemaining.isZero() || fenceRemaining.isNegative()) {
                return settledNoFetch(permit, Completion.error(), methodDeadline,
                        DeferredReason.HTTP_NOT_SUBMITTED);
            }
            if (postFence.sourceFloor().isAfter(clock.instant())) {
                return settledFutureFloor(permit, postFence.sourceFloor(), methodDeadline);
            }
            fenceDeadline = OwnedSyncDeadline.after(fenceRemaining);
        }
        OwnedSyncDeadline exchangeDeadline = postFence == null
                ? OwnedSyncDeadline.earliest(
                        OwnedSyncDeadline.after(requestTimeout),
                        methodDeadline.minus(settlementAndCancellationReserve),
                        permitDeadline.minus(settlementAndCancellationReserve.plus(PERMIT_SAFETY_MARGIN)))
                : OwnedSyncDeadline.earliest(
                        OwnedSyncDeadline.after(requestTimeout),
                        methodDeadline.minus(settlementAndCancellationReserve),
                        permitDeadline.minus(settlementAndCancellationReserve.plus(PERMIT_SAFETY_MARGIN)),
                        fenceDeadline.minus(settlementAndCancellationReserve.plus(PERMIT_SAFETY_MARGIN)));
        if (Thread.currentThread().isInterrupted()) {
            return settledNoFetch(permit, Completion.error(), methodDeadline,
                    DeferredReason.HTTP_NOT_SUBMITTED);
        }
        long initialExchangeNanos = exchangeDeadline.remainingNanos();
        if (initialExchangeNanos == 0) {
            return settledNoFetch(permit, Completion.error(), methodDeadline,
                    DeferredReason.HTTP_NOT_SUBMITTED);
        }

        HttpGet request;
        try {
            request = new HttpGet(baseUri.resolve(path));
            request.setHeader("Accept", "application/json");
            request.setConfig(requestConfig(Duration.ofNanos(initialExchangeNanos)));
        } catch (RuntimeException setupFailure) {
            return settledNoFetch(permit, Completion.error(), methodDeadline,
                    DeferredReason.HTTP_NOT_SUBMITTED);
        }
        if (Thread.currentThread().isInterrupted()) {
            return settledNoFetch(permit, Completion.error(), methodDeadline,
                    DeferredReason.HTTP_NOT_SUBMITTED);
        }
        if (isClosing()) {
            return settledNoFetch(permit, Completion.error(), methodDeadline, DeferredReason.HTTP_CLIENT_CLOSED);
        }

        Ready sendFence = postFence;
        ExchangeGate gate = new ExchangeGate();
        ExchangeAttempt attempt = new ExchangeAttempt();
        CountDownLatch workerExited = new CountDownLatch(1);
        Future<?> exchange;
        try {
            exchange = exchanges.submit(() -> {
                try {
                    long remaining = exchangeDeadline.remainingNanos();
                    if (remaining == 0) {
                        gate.abandon();
                        return;
                    }
                    request.setConfig(requestConfig(Duration.ofNanos(remaining)));
                    if (!tryStart(gate, methodDeadline, permitDeadline, exchangeDeadline, sendFence)) return;
                    Instant started = clock.instant();
                    attempt.begin(started);
                    try {
                        attempt.response = execute(request, attempt);
                    } catch (Throwable failure) {
                        attempt.failure = failure;
                        if (failure instanceof Error error) throw error;
                    } finally {
                        attempt.finish(clock.instant());
                    }
                } finally {
                    workerExited.countDown();
                }
            });
        } catch (RejectedExecutionException saturated) {
            gate.abandon();
            if (gate.started()) return unresolved(DeferredReason.HTTP_EXCHANGE_UNCERTAIN);
            DeferredReason reason = isClosing()
                    ? DeferredReason.HTTP_CLIENT_CLOSED : DeferredReason.HTTP_WORKERS_SATURATED;
            return settledNoFetch(permit, Completion.error(), methodDeadline, reason);
        } catch (RuntimeException submissionFailure) {
            gate.abandon();
            if (!gate.started()) return settleGateNoFetch(permit, gate, methodDeadline);
            return unresolved(DeferredReason.HTTP_EXCHANGE_UNCERTAIN);
        }

        boolean restoreInterrupt = false;
        try {
            long waitNanos = exchangeDeadline.remainingNanos();
            if (waitNanos == 0) throw new TimeoutException();
            exchange.get(waitNanos, TimeUnit.NANOSECONDS);
            if (workerExited.getCount() != 0) return unresolved(DeferredReason.HTTP_EXCHANGE_UNCERTAIN);
            if (!gate.started()) return settleGateNoFetch(permit, gate, methodDeadline);
            return completedAttempt(permit, attempt, failureAttempt, methodDeadline, null);
        } catch (InterruptedException interrupted) {
            restoreInterrupt = true; // InterruptedException cleared the flag for bounded cleanup/settlement.
            gate.abandon();
            if (!gate.started()) return settleGateNoFetch(permit, gate, methodDeadline);
            ExitAcknowledgement exit = cancelAndAwait(request, workerExited, methodDeadline);
            restoreInterrupt |= exit.interrupted();
            if (!exit.acknowledged()) return unresolved(DeferredReason.HTTP_EXCHANGE_UNCERTAIN);
            return completedAttempt(permit, attempt, failureAttempt, methodDeadline, FailureKind.INTERRUPTED);
        } catch (TimeoutException timeout) {
            gate.abandon();
            if (!gate.started()) return settleGateNoFetch(permit, gate, methodDeadline);
            ExitAcknowledgement exit = cancelAndAwait(request, workerExited, methodDeadline);
            restoreInterrupt |= exit.interrupted();
            if (!exit.acknowledged()) return unresolved(DeferredReason.HTTP_EXCHANGE_UNCERTAIN);
            return completedAttempt(permit, attempt, failureAttempt, methodDeadline, FailureKind.TIMEOUT);
        } catch (ExecutionException executionFailure) {
            if (workerExited.getCount() != 0) return unresolved(DeferredReason.HTTP_EXCHANGE_UNCERTAIN);
            if (!gate.started()) return settleGateNoFetch(permit, gate, methodDeadline);
            if (attempt.failure == null) attempt.failure = rootCause(executionFailure);
            return completedAttempt(permit, attempt, failureAttempt, methodDeadline, null);
        } catch (RuntimeException exchangeFailure) {
            gate.abandon();
            if (!gate.started()) return settleGateNoFetch(permit, gate, methodDeadline);
            ExitAcknowledgement exit = cancelAndAwait(request, workerExited, methodDeadline);
            restoreInterrupt |= exit.interrupted();
            if (!exit.acknowledged()) return unresolved(DeferredReason.HTTP_EXCHANGE_UNCERTAIN);
            if (attempt.failure == null) attempt.failure = exchangeFailure;
            return completedAttempt(permit, attempt, failureAttempt, methodDeadline, null);
        } finally {
            if (restoreInterrupt) Thread.currentThread().interrupt();
        }
    }

    private Result completedAttempt(Permit permit, ExchangeAttempt attempt, int failureAttempt,
                                    OwnedSyncDeadline deadline, FailureKind callerFailure) {
        SourceRead read = attempt.read();
        if (read == null) return unresolved(DeferredReason.HTTP_EXCHANGE_UNCERTAIN);
        WireResponse response = attempt.response;
        if (response != null) {
            Completion completion = response.statusCode >= 200 && response.statusCode < 300
                    ? Completion.success()
                    : Completion.http(response.statusCode, response.retryAfter, failureAttempt);
            return settledResponse(permit, completion, deadline, response, read);
        }

        Headers observed = attempt.headers;
        if (observed == null) return unresolved(DeferredReason.HTTP_EXCHANGE_UNCERTAIN);
        Throwable failure = attempt.failure;
        boolean timeout = callerFailure == FailureKind.TIMEOUT
                || (callerFailure == null && hasCause(failure, SocketTimeoutException.class));
        FailureKind kind = callerFailure;
        String message;
        if (callerFailure != null) {
            message = callerFailure == FailureKind.TIMEOUT ? "Request timed out" : "Request interrupted";
        } else if (failure instanceof BodyTooLargeException) {
            kind = FailureKind.OVERSIZE;
            message = failure.getMessage();
        } else if (timeout) {
            kind = FailureKind.TIMEOUT;
            message = "Request timed out";
        } else {
            kind = FailureKind.IO_ERROR;
            message = failure == null ? "Exchange ended after receiving headers" : boundedMessage(rootCause(failure));
        }
        Completion completion = timeout ? transientCompletion(observed, failureAttempt)
                : failureCompletion(observed, failureAttempt, Completion.error());
        return settledFailure(permit, completion, deadline, kind, message, read, observed);
    }

    private Result settledResponse(Permit permit, Completion completion, OwnedSyncDeadline deadline,
                                   WireResponse response, SourceRead read) {
        Outcome<Settlement> outcome = settle(permit, completion, deadline);
        if (!(outcome instanceof Success<?> success)) return unresolved(DeferredReason.SETTLEMENT_UNCERTAIN);
        Settlement settlement = (Settlement) success.value();
        return new Response(response.statusCode, response.body, response.retryAfter,
                read, settlement.committedCooldownUntil().orElse(null));
    }

    private Result settledFailure(Permit permit, Completion completion, OwnedSyncDeadline deadline,
                                  FailureKind kind, String message, SourceRead read, Headers headers) {
        Outcome<Settlement> outcome = settle(permit, completion, deadline);
        if (!(outcome instanceof Success<?> success)) return unresolved(DeferredReason.SETTLEMENT_UNCERTAIN);
        Settlement settlement = (Settlement) success.value();
        return new Failure(kind, message, headers.statusCode, headers.retryAfter, read,
                settlement.committedCooldownUntil().orElse(null));
    }

    private Result settledNoFetch(Permit permit, Completion completion, OwnedSyncDeadline deadline,
                                  DeferredReason reason) {
        boolean restoreInterrupt = Thread.interrupted();
        try {
            Outcome<Settlement> outcome = settle(permit, completion, deadline);
            return outcome instanceof Success<?> ? databaseDeferral(reason)
                    : unresolved(DeferredReason.SETTLEMENT_UNCERTAIN);
        } finally {
            if (restoreInterrupt) Thread.currentThread().interrupt();
        }
    }

    private Result settledFutureFloor(Permit permit, Instant nextDue, OwnedSyncDeadline deadline) {
        boolean restoreInterrupt = Thread.interrupted();
        try {
            Outcome<Settlement> outcome = settle(permit, Completion.error(), deadline);
            return outcome instanceof Success<?>
                    ? new DeferredResult(nextDue, DeferredReason.HTTP_NOT_SUBMITTED)
                    : unresolved(DeferredReason.SETTLEMENT_UNCERTAIN);
        } finally {
            if (restoreInterrupt) Thread.currentThread().interrupt();
        }
    }

    private Outcome<HttpFenceResult> checkFence(Supplier<HttpFenceResult> fence,
                                                 OwnedSyncDeadline deadline) {
        return dbOperations.call(fence::get, deadline);
    }

    private Result fenceFailure(Outcome<HttpFenceResult> outcome) {
        if (outcome instanceof Rejected<?> rejected) return rejectedAdmission(rejected.reason());
        Uncertainty uncertainty = outcome instanceof Uncertain<?> uncertain
                ? uncertain.reason() : Uncertainty.OPERATION_FAILED;
        return uncertainAdmission(uncertainty);
    }

    private DeferredReason fenceFailureReason(Outcome<HttpFenceResult> outcome) {
        if (outcome instanceof Rejected<?> rejected) {
            return switch (rejected.reason()) {
                case SATURATED -> DeferredReason.DB_OPERATIONS_SATURATED;
                case CLOSED -> DeferredReason.DB_OPERATIONS_CLOSED;
                case DEADLINE_EXPIRED -> DeferredReason.DB_DEADLINE_EXPIRED;
                case INTERRUPTED -> DeferredReason.DB_INTERRUPTED;
            };
        }
        if (outcome instanceof Uncertain<?> uncertain && uncertain.reason() == Uncertainty.INTERRUPTED) {
            return DeferredReason.DB_INTERRUPTED;
        }
        return DeferredReason.NO_FETCH_UNCERTAIN;
    }

    private Result settleGateNoFetch(Permit permit, ExchangeGate gate, OwnedSyncDeadline deadline) {
        Instant nextDue = gate.futureFloorDue();
        return nextDue == null
                ? settledNoFetch(permit, Completion.error(), deadline, notStartedReason())
                : settledFutureFloor(permit, nextDue, deadline);
    }

    private Outcome<Settlement> settle(Permit permit, Completion completion, OwnedSyncDeadline deadline) {
        return dbOperations.call(() -> budget.complete(permit, completion), deadline);
    }

    private WireResponse execute(HttpGet request, ExchangeAttempt attempt) throws IOException {
        return client.execute(request, response -> {
            int statusCode = response.getCode();
            String retryAfter = bounded(header(response.getFirstHeader("Retry-After")));
            attempt.headers = new Headers(statusCode, retryAfter);
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

    private DeferredResult rejectedAdmission(Rejection rejection) {
        DeferredReason reason = switch (rejection) {
            case SATURATED -> DeferredReason.DB_OPERATIONS_SATURATED;
            case CLOSED -> DeferredReason.DB_OPERATIONS_CLOSED;
            case DEADLINE_EXPIRED -> DeferredReason.DB_DEADLINE_EXPIRED;
            case INTERRUPTED -> DeferredReason.DB_INTERRUPTED;
        };
        return databaseDeferral(reason);
    }

    private DeferredResult uncertainAdmission(Uncertainty uncertainty) {
        DeferredReason reason = uncertainty == Uncertainty.INTERRUPTED
                ? DeferredReason.DB_INTERRUPTED : DeferredReason.DB_ADMISSION_UNCERTAIN;
        return new DeferredResult(clock.instant().plus(DB_ADMISSION_UNCERTAIN_RECHECK), reason);
    }

    private DeferredResult databaseDeferral(DeferredReason reason) {
        Duration recheck = reason == DeferredReason.HTTP_CLIENT_CLOSED
                ? UNSETTLED_RECHECK : DB_REJECTION_RECHECK;
        return new DeferredResult(clock.instant().plus(recheck), reason);
    }

    private DeferredResult locallyClosed() {
        return databaseDeferral(DeferredReason.HTTP_CLIENT_CLOSED);
    }

    private DeferredReason notStartedReason() {
        return isClosing() ? DeferredReason.HTTP_CLIENT_CLOSED : DeferredReason.HTTP_NOT_SUBMITTED;
    }

    private boolean isClosing() {
        synchronized (sendGate) {
            return closing;
        }
    }

    private TurnJoin joinTurn(OwnedSyncDeadline graceDeadline) {
        boolean restoreInterrupt = false;
        TurnJoin result;
        synchronized (turnGate) {
            if (turnClosed) return new TurnJoin(null, TurnJoinFailure.CLOSED);
            if (turns.size() >= 2) return new TurnJoin(null, TurnJoinFailure.FULL);

            TurnTicket ticket = new TurnTicket(!turns.isEmpty());
            turns.addLast(ticket);
            while (true) {
                if (turnClosed) {
                    turns.remove(ticket);
                    turnGate.notifyAll();
                    result = new TurnJoin(null, TurnJoinFailure.CLOSED);
                    break;
                }
                long remaining = graceDeadline.remainingNanos(turnNanoTime.getAsLong());
                if (ticket.waited && remaining == 0) {
                    turns.remove(ticket);
                    turnGate.notifyAll();
                    result = new TurnJoin(null, TurnJoinFailure.TIMED_OUT);
                    break;
                }
                if (turns.peekFirst() == ticket) {
                    ticket.poisoned = unsafeTurnChain;
                    return new TurnJoin(ticket, null);
                }
                if (remaining == 0) {
                    turns.remove(ticket);
                    turnGate.notifyAll();
                    result = new TurnJoin(null, TurnJoinFailure.TIMED_OUT);
                    break;
                }
                try {
                    waitOn(turnGate, remaining);
                } catch (InterruptedException interrupted) {
                    turns.remove(ticket);
                    turnGate.notifyAll();
                    restoreInterrupt = true;
                    result = new TurnJoin(null, TurnJoinFailure.INTERRUPTED);
                    break;
                }
            }
        }
        if (restoreInterrupt) Thread.currentThread().interrupt();
        return result;
    }

    private Result turnJoinFailure(TurnJoinFailure failure) {
        return switch (failure) {
            case CLOSED -> locallyClosed();
            case INTERRUPTED -> databaseDeferral(DeferredReason.DB_INTERRUPTED);
            case TIMED_OUT -> databaseDeferral(DeferredReason.HTTP_NOT_SUBMITTED);
            case FULL -> databaseDeferral(DeferredReason.HTTP_TURN_QUEUE_FULL);
        };
    }

    private SpacingWait waitForSpacing(Instant nextDue, OwnedSyncDeadline graceDeadline) {
        Duration delay = Duration.between(clock.instant(), nextDue);
        OwnedSyncDeadline dueDeadline = OwnedSyncDeadline.after(delay.isNegative() ? Duration.ZERO : delay);
        boolean restoreInterrupt = false;
        SpacingWait result;
        synchronized (turnGate) {
            while (true) {
                if (turnClosed) {
                    result = SpacingWait.CLOSED;
                    break;
                }
                if (Thread.currentThread().isInterrupted()) {
                    result = SpacingWait.INTERRUPTED;
                    break;
                }
                long graceRemaining = graceDeadline.remainingNanos();
                if (graceRemaining == 0) {
                    result = SpacingWait.EXPIRED;
                    break;
                }
                long dueRemaining = dueDeadline.remainingNanos();
                if (dueRemaining == 0) {
                    result = SpacingWait.READY;
                    break;
                }
                try {
                    waitOn(turnGate, Math.min(graceRemaining, dueRemaining));
                } catch (InterruptedException interrupted) {
                    restoreInterrupt = true;
                    result = SpacingWait.INTERRUPTED;
                    break;
                }
            }
        }
        if (restoreInterrupt) Thread.currentThread().interrupt();
        return result;
    }

    private void releaseTurn(TurnTicket ticket, boolean safeToHandover) {
        synchronized (turnGate) {
            if (turns.peekFirst() != ticket) {
                throw new IllegalStateException("Owned-sync FIFO turn released out of order");
            }
            turns.removeFirst();
            if (!safeToHandover) unsafeTurnChain = true;
            if (turns.isEmpty()) unsafeTurnChain = false;
            turnGate.notifyAll();
        }
    }

    private static boolean safeForHandover(Result result) {
        if (!(result instanceof DeferredResult deferred)) return true;
        return switch (deferred.reason()) {
            case UNSETTLED, DB_INTERRUPTED, DB_ADMISSION_UNCERTAIN, NO_FETCH_UNCERTAIN,
                    HTTP_EXCHANGE_UNCERTAIN, SETTLEMENT_UNCERTAIN -> false;
            default -> true;
        };
    }

    private static void waitOn(Object monitor, long nanos) throws InterruptedException {
        long millis = TimeUnit.NANOSECONDS.toMillis(nanos);
        int extraNanos = (int) (nanos - TimeUnit.MILLISECONDS.toNanos(millis));
        monitor.wait(millis, extraNanos);
    }

    private boolean tryStart(ExchangeGate gate, OwnedSyncDeadline methodDeadline,
                             OwnedSyncDeadline permitDeadline, OwnedSyncDeadline exchangeDeadline,
                             Ready fence) {
        synchronized (sendGate) {
            return gate.tryStart(closing, methodDeadline, permitDeadline, exchangeDeadline,
                    fence, clock.instant());
        }
    }

    /** Closes only the local GET gate; it performs no shutdown or database work. */
    void stopSending() {
        synchronized (sendGate) {
            closing = true;
        }
        synchronized (turnGate) {
            turnClosed = true;
            turnGate.notifyAll();
        }
    }

    private DeferredResult unresolved(DeferredReason reason) {
        return new DeferredResult(clock.instant().plus(UNSETTLED_RECHECK), reason);
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
        return boundedExecutor(2, "owned-sync-http-");
    }

    private static ExecutorService newCancellationExecutor() {
        return boundedExecutor(1, "owned-sync-cancel-");
    }

    private static ExecutorService boundedExecutor(int workers, String name) {
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, name + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(workers, workers, 0, TimeUnit.NANOSECONDS,
                new SynchronousQueue<>(), factory, new ThreadPoolExecutor.AbortPolicy());
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

    private static Duration boundedMethodTimeout(Duration timeout) {
        Objects.requireNonNull(timeout);
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(METHOD_DEADLINE) > 0) {
            throw new IllegalArgumentException("method timeout must be positive and at most 180 seconds");
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

    private ExitAcknowledgement cancelAndAwait(HttpGet request, CountDownLatch workerExited,
                                               OwnedSyncDeadline methodDeadline) {
        try {
            cancellations.execute(() -> {
                try {
                    request.cancel();
                } catch (RuntimeException ignored) {
                    // Cancellation is best effort; only worker exit is an acknowledgement.
                }
            });
        } catch (RejectedExecutionException ignored) {
            // A saturated/closed cancellation worker must never block the caller.
        }

        long settlementReserve = dbOperations.maximumCallerWait().toNanos()
                + SETTLEMENT_SAFETY_MARGIN.toNanos();
        long waitNanos = Math.min(CANCELLATION_ACK_TIMEOUT.toNanos(),
                Math.max(0, methodDeadline.remainingNanos() - settlementReserve));
        long waitDeadline = System.nanoTime() + waitNanos;
        boolean interrupted = false;
        while (workerExited.getCount() != 0) {
            long remaining = waitDeadline - System.nanoTime();
            if (remaining <= 0) return new ExitAcknowledgement(false, interrupted);
            try {
                if (!workerExited.await(remaining, TimeUnit.NANOSECONDS)) {
                    return new ExitAcknowledgement(false, interrupted);
                }
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        return new ExitAcknowledgement(true, interrupted);
    }

    /**
     * Closes the send gate first, then attempts Apache cleanup on the bounded control executor.
     * The caller waits at most one second for that cleanup and worker termination. If the control
     * worker is saturated or client close ignores interruption, Apache resources may remain unclosed;
     * only the fixed-capacity daemon control and exchange workers can linger. The local gate remains
     * closed, so this containment tradeoff cannot admit another GET.
     */
    @Override public void close() {
        stopSending();
        long deadline = System.nanoTime() + CANCELLATION_ACK_TIMEOUT.toNanos();
        Future<?> clientClose = null;
        boolean interrupted = Thread.interrupted();
        try {
            try {
                clientClose = cancellations.submit(() -> {
                    client.close(CloseMode.IMMEDIATE);
                    return null;
                });
            } catch (RejectedExecutionException saturated) {
                // Cleanup below still shuts down every owned executor and DB supervisor.
            }
            if (clientClose != null) {
                long remaining = deadline - System.nanoTime();
                if (remaining > 0) {
                    try {
                        clientClose.get(remaining, TimeUnit.NANOSECONDS);
                    } catch (InterruptedException shutdownInterrupted) {
                        interrupted = true;
                        clientClose.cancel(true);
                    } catch (ExecutionException | TimeoutException ignored) {
                        clientClose.cancel(true);
                    }
                } else {
                    clientClose.cancel(true);
                }
            }
        } finally {
            try {
                cancellations.shutdownNow();
            } finally {
                try {
                    exchanges.shutdownNow();
                } finally {
                    try {
                        if (closeDbOperations) dbOperations.close();
                    } finally {
                        long remaining = deadline - System.nanoTime();
                        if (remaining > 0) {
                            try {
                                exchanges.awaitTermination(remaining, TimeUnit.NANOSECONDS);
                            } catch (InterruptedException shutdownInterrupted) {
                                interrupted = true;
                            }
                        }
                        if (interrupted) Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }

    @FunctionalInterface
    interface DispatcherFetch {
        Result get(Endpoint endpoint, int failureAttempt);

        default Result get(OwnedSyncStore.Lease lease, Endpoint endpoint, int failureAttempt,
                           Instant sourceFloor) {
            return get(endpoint, failureAttempt);
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
    private record ExitAcknowledgement(boolean acknowledged, boolean interrupted) {}
    private record TurnJoin(TurnTicket ticket, TurnJoinFailure failure) {}

    private static final class TurnTicket {
        private final boolean waited;
        private boolean poisoned;

        private TurnTicket(boolean waited) {
            this.waited = waited;
        }
    }

    private enum TurnJoinFailure { CLOSED, FULL, INTERRUPTED, TIMED_OUT }
    private enum SpacingWait { READY, CLOSED, INTERRUPTED, EXPIRED }

    private static final class ExchangeGate {
        private boolean started;
        private boolean abandoned;
        private Instant futureFloorDue;

        synchronized boolean tryStart(boolean closing, OwnedSyncDeadline methodDeadline,
                                      OwnedSyncDeadline permitDeadline, OwnedSyncDeadline exchangeDeadline,
                                      Ready fence, Instant now) {
            if (started || abandoned) return false;
            if (closing || Thread.currentThread().isInterrupted()
                    || methodDeadline.remainingNanos() == 0
                    || permitDeadline.remainingNanos() == 0
                    || exchangeDeadline.remainingNanos() == 0
                    || (fence != null && !fence.expiresAt().isAfter(now))) {
                abandoned = true;
                return false;
            }
            if (fence != null && fence.sourceFloor().isAfter(now)) {
                futureFloorDue = fence.sourceFloor();
                abandoned = true;
                return false;
            }
            started = true;
            return true;
        }

        synchronized void abandon() {
            if (!started) abandoned = true;
        }

        synchronized boolean started() {
            return started;
        }

        synchronized Instant futureFloorDue() {
            return futureFloorDue;
        }
    }

    private static final class ExchangeAttempt {
        private volatile Instant startedAt;
        private volatile Instant finishedAt;
        private volatile Headers headers;
        private volatile WireResponse response;
        private volatile Throwable failure;

        void begin(Instant startedAt) {
            this.startedAt = startedAt;
        }

        void finish(Instant finishedAt) {
            this.finishedAt = finishedAt;
        }

        SourceRead read() {
            Instant started = startedAt;
            Instant finished = finishedAt;
            return started == null || finished == null ? null : new SourceRead(started, finished);
        }
    }

    private static final class BodyTooLargeException extends IOException {
        private BodyTooLargeException(int maximum) {
            super("Response body exceeds " + maximum + " bytes");
        }
    }
}
