package fi.karivanttinen.fplassistant.sync;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Supervises at most two DB-only operations. A caller timeout never cancels or replaces a running
 * operation, and this class has no transport or completion callback that could initiate HTTP later.
 */
final class OwnedSyncDbOperations implements AutoCloseable {

    private static final Duration MAX_CALLER_WAIT = Duration.ofSeconds(35);

    private final ThreadPoolExecutor executor;
    private final Duration maximumCallerWait;
    private volatile AfterFinishedSignalHook afterFinishedSignalHook;

    OwnedSyncDbOperations() {
        this(MAX_CALLER_WAIT);
    }

    /** Package-private short-wait seam for deterministic tests; production remains capped at 35s. */
    OwnedSyncDbOperations(Duration maximumCallerWait) {
        this.maximumCallerWait = positiveCallerWait(maximumCallerWait);
        executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.NANOSECONDS,
                new SynchronousQueue<>(), daemonThreadFactory(), new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Runs the complete DB operation on a worker and waits no longer than the configured cap
     * (35 seconds in production) and the supplied method-entry deadline. The callable must contain
     * only DB work and must not initiate or schedule HTTP. A running operation is deliberately left
     * alone when its caller times out or is interrupted; its eventual result is not returned to that
     * caller.
     */
    <T> Outcome<T> call(Callable<T> operation, OwnedSyncDeadline methodDeadline) {
        Objects.requireNonNull(operation);
        Objects.requireNonNull(methodDeadline);
        OwnedSyncDeadline waitDeadline = methodDeadline.cappedBy(maximumCallerWait);

        if (Thread.currentThread().isInterrupted()) return new Rejected<>(Rejection.INTERRUPTED);
        if (waitDeadline.remainingNanos() == 0) return new Rejected<>(Rejection.DEADLINE_EXPIRED);

        Operation<T> task = new Operation<>(operation, afterFinishedSignalHook);
        long retryDeadlineNanos = 0;
        boolean retryWindowStarted = false;
        while (true) {
            if (Thread.currentThread().isInterrupted()) return new Rejected<>(Rejection.INTERRUPTED);
            if (waitDeadline.remainingNanos() == 0) return new Rejected<>(Rejection.DEADLINE_EXPIRED);
            if (retryWindowStarted && retryDeadlineNanos - System.nanoTime() <= 0) {
                return new Rejected<>(Rejection.SATURATED);
            }
            try {
                executor.execute(task);
                break;
            } catch (RejectedExecutionException rejected) {
                if (executor.isShutdown()) return new Rejected<>(Rejection.CLOSED);
                if (Thread.currentThread().isInterrupted()) return new Rejected<>(Rejection.INTERRUPTED);
                if (waitDeadline.remainingNanos() == 0) return new Rejected<>(Rejection.DEADLINE_EXPIRED);
                if (task.state.get() != State.WAITING) {
                    return new Uncertain<>(Uncertainty.OPERATION_FAILED, rejected);
                }
                if (!retryWindowStarted) {
                    retryDeadlineNanos = System.nanoTime()
                            + Math.min(TimeUnit.MILLISECONDS.toNanos(100), waitDeadline.remainingNanos());
                    retryWindowStarted = true;
                }
                long retryRemainingNanos = retryDeadlineNanos - System.nanoTime();
                if (retryRemainingNanos <= 0) return new Rejected<>(Rejection.SATURATED);
                try {
                    TimeUnit.NANOSECONDS.sleep(Math.min(retryRemainingNanos,
                            TimeUnit.MILLISECONDS.toNanos(1)));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return new Rejected<>(Rejection.INTERRUPTED);
                }
            }
        }

        long remaining = waitDeadline.remainingNanos();
        if (remaining == 0) return task.abandonBeforeStart()
                ? new Rejected<>(Rejection.DEADLINE_EXPIRED)
                : new Uncertain<>(Uncertainty.TIMED_OUT, null);

        try {
            if (task.finished.await(remaining, TimeUnit.NANOSECONDS)) {
                return waitDeadline.remainingNanos() > 0
                        ? task.outcome() : new Uncertain<>(Uncertainty.TIMED_OUT, null);
            }
        } catch (InterruptedException interrupted) {
            boolean notStarted = task.abandonBeforeStart();
            Thread.currentThread().interrupt();
            return notStarted ? new Rejected<>(Rejection.INTERRUPTED)
                    : new Uncertain<>(Uncertainty.INTERRUPTED, null);
        }

        return task.abandonBeforeStart() ? new Rejected<>(Rejection.DEADLINE_EXPIRED)
                : new Uncertain<>(Uncertainty.TIMED_OUT, null);
    }

    Duration maximumCallerWait() {
        return maximumCallerWait;
    }

    /** Waits for shutdown workers to exit by an absolute monotonic deadline without cancelling them. */
    boolean awaitTerminationUntil(long deadlineNanos) {
        boolean interrupted = Thread.interrupted();
        try {
            while (!executor.isTerminated()) {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) return false;
                try {
                    if (executor.awaitTermination(remaining, TimeUnit.NANOSECONDS)) return true;
                } catch (InterruptedException shutdownInterrupted) {
                    interrupted = true;
                }
            }
            return true;
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /** Test-only bounded wait for all accepted DB operations to exit after close. */
    boolean awaitTerminationForTests(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout);
        if (timeout.isNegative() || timeout.compareTo(Duration.ofSeconds(5)) > 0) {
            throw new IllegalArgumentException("test termination wait must be between zero and five seconds");
        }
        return executor.awaitTermination(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    /** Test-only seam: hold a worker after it signals completion but before its executor task exits. */
    void afterFinishedSignal(CountDownLatch reached, CountDownLatch release) {
        afterFinishedSignalHook = new AfterFinishedSignalHook(
                Objects.requireNonNull(reached), Objects.requireNonNull(release));
    }

    @Override
    public void close() {
        executor.shutdown();
    }

    private static Duration positiveCallerWait(Duration wait) {
        Objects.requireNonNull(wait);
        if (wait.isZero() || wait.isNegative() || wait.compareTo(MAX_CALLER_WAIT) > 0) {
            throw new IllegalArgumentException("maximum caller wait must be positive and at most 35 seconds");
        }
        return wait;
    }

    private static ThreadFactory daemonThreadFactory() {
        AtomicInteger threadNumber = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "owned-sync-db-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    sealed interface Outcome<T> permits Success, Rejected, Uncertain {}

    record Success<T>(T value) implements Outcome<T> {}

    record Rejected<T>(Rejection reason) implements Outcome<T> {
        Rejected { Objects.requireNonNull(reason); }
    }

    record Uncertain<T>(Uncertainty reason, Throwable failure) implements Outcome<T> {
        Uncertain { Objects.requireNonNull(reason); }
    }

    enum Rejection { SATURATED, CLOSED, DEADLINE_EXPIRED, INTERRUPTED }

    enum Uncertainty { TIMED_OUT, INTERRUPTED, OPERATION_FAILED }

    private enum State { WAITING, RUNNING, ABANDONED, FINISHED }

    private record AfterFinishedSignalHook(CountDownLatch reached, CountDownLatch release) {}

    private static final class Operation<T> implements Runnable {

        private final Callable<T> operation;
        private final AfterFinishedSignalHook afterFinishedSignalHook;
        private final AtomicReference<State> state = new AtomicReference<>(State.WAITING);
        private final CountDownLatch finished = new CountDownLatch(1);
        private volatile T value;
        private volatile Throwable failure;

        private Operation(Callable<T> operation, AfterFinishedSignalHook afterFinishedSignalHook) {
            this.operation = operation;
            this.afterFinishedSignalHook = afterFinishedSignalHook;
        }

        @Override
        public void run() {
            if (!state.compareAndSet(State.WAITING, State.RUNNING)) {
                finished.countDown();
                return;
            }
            try {
                value = operation.call();
            } catch (Throwable thrown) {
                failure = thrown;
                if (thrown instanceof Error error) throw error;
            } finally {
                state.set(State.FINISHED);
                finished.countDown();
                if (afterFinishedSignalHook != null) {
                    afterFinishedSignalHook.reached().countDown();
                    try {
                        afterFinishedSignalHook.release().await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        private boolean abandonBeforeStart() {
            if (state.compareAndSet(State.WAITING, State.ABANDONED)) return true;
            return state.get() == State.ABANDONED;
        }

        private Outcome<T> outcome() {
            Throwable thrown = failure;
            return thrown == null ? new Success<>(value)
                    : new Uncertain<>(Uncertainty.OPERATION_FAILED, thrown);
        }
    }
}
