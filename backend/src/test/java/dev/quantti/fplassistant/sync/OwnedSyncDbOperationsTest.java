package dev.quantti.fplassistant.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import dev.quantti.fplassistant.sync.OwnedSyncDbOperations.Outcome;
import dev.quantti.fplassistant.sync.OwnedSyncDbOperations.Rejected;
import dev.quantti.fplassistant.sync.OwnedSyncDbOperations.Rejection;
import dev.quantti.fplassistant.sync.OwnedSyncDbOperations.Success;
import dev.quantti.fplassistant.sync.OwnedSyncDbOperations.Uncertain;
import dev.quantti.fplassistant.sync.OwnedSyncDbOperations.Uncertainty;

class OwnedSyncDbOperationsTest {

    @Test
    @Timeout(value = 4, unit = TimeUnit.SECONDS)
    void successfulSignalBeforeWorkerIdleMustAllowThirdOperationWithinOriginalDeadline() throws Exception {
        Duration callerWait = Duration.ofMillis(500);
        OwnedSyncDbOperations operations = new OwnedSyncDbOperations(callerWait);
        CountDownLatch finishedSignalReached = new CountDownLatch(2);
        CountDownLatch releaseWorkers = new CountDownLatch(1);
        CountDownLatch thirdCallIssued = new CountDownLatch(1);
        operations.afterFinishedSignal(finishedSignalReached, releaseWorkers);
        OwnedSyncDeadline methodDeadline = OwnedSyncDeadline.after(callerWait);
        AtomicReference<Thread> firstWorker = new AtomicReference<>();
        AtomicReference<Thread> secondWorker = new AtomicReference<>();
        AtomicReference<Thread> thirdWorker = new AtomicReference<>();
        Thread releaser = new Thread(() -> {
            try {
                if (thirdCallIssued.await(1, TimeUnit.SECONDS)) {
                    LockSupport.parkNanos(Duration.ofMillis(20).toNanos());
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                releaseWorkers.countDown();
            }
        }, "owned-sync-db-test-release");
        releaser.setDaemon(true);
        try {
            Outcome<String> first = operations.call(() -> {
                firstWorker.set(Thread.currentThread());
                return "first";
            }, methodDeadline);
            Outcome<String> second = operations.call(() -> {
                secondWorker.set(Thread.currentThread());
                return "second";
            }, methodDeadline);

            assertThat(first).isEqualTo(new Success<>("first"));
            assertThat(second).isEqualTo(new Success<>("second"));
            assertThat(firstWorker.get()).isNotSameAs(secondWorker.get());
            assertThat(firstWorker.get().isAlive()).isTrue();
            assertThat(secondWorker.get().isAlive()).isTrue();
            assertThat(finishedSignalReached.await(1, TimeUnit.SECONDS)).isTrue();

            ThreadPoolExecutor executor = supervisedWorkers(operations);
            assertThat(executor.getQueue()).isInstanceOf(SynchronousQueue.class);
            assertThat(executor.getQueue()).isEmpty();
            assertThat(executor.getLargestPoolSize()).isLessThanOrEqualTo(2);
            assertThat(methodDeadline.remainingNanos()).isPositive();

            releaser.start();
            long thirdCallStarted = System.nanoTime();
            thirdCallIssued.countDown();
            Outcome<String> third = operations.call(() -> {
                thirdWorker.set(Thread.currentThread());
                return "third";
            }, methodDeadline);
            long thirdCallElapsed = System.nanoTime() - thirdCallStarted;

            assertThat(third).isEqualTo(new Success<>("third"));
            assertThat(thirdCallElapsed).isLessThan(callerWait.toNanos());
            assertThat(methodDeadline.remainingNanos()).isPositive();
            assertThat(thirdWorker.get()).isIn(firstWorker.get(), secondWorker.get());
            assertThat(executor.getQueue()).isEmpty();
            assertThat(executor.getLargestPoolSize()).isLessThanOrEqualTo(2);
        } finally {
            releaseWorkers.countDown();
            releaser.join(1_000);
            operations.close();
            join(firstWorker.get());
            join(secondWorker.get());
            join(thirdWorker.get());
        }
    }

    @Test
    @Timeout(value = 7, unit = TimeUnit.SECONDS)
    void twoStuckWorkersRejectThirdWithoutBacklogAndRecoverAfterRelease() throws Exception {
        OwnedSyncDbOperations operations = new OwnedSyncDbOperations();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch finished = new CountDownLatch(2);
        AtomicReference<Thread> firstWorker = new AtomicReference<>();
        AtomicReference<Thread> secondWorker = new AtomicReference<>();
        AtomicBoolean workerInterrupted = new AtomicBoolean();
        AtomicInteger rejectedCallRuns = new AtomicInteger();
        AtomicReference<Thread> recoveryWorker = new AtomicReference<>();
        try {
            Outcome<String> first = operations.call(stuckTask(started, finished, release,
                    firstWorker, workerInterrupted), OwnedSyncDeadline.after(Duration.ofSeconds(1)));
            Outcome<String> second = operations.call(stuckTask(started, finished, release,
                    secondWorker, workerInterrupted), OwnedSyncDeadline.after(Duration.ofSeconds(1)));

            assertThat(first).isInstanceOfSatisfying(Uncertain.class,
                    result -> assertThat(result.reason()).isEqualTo(Uncertainty.TIMED_OUT));
            assertThat(second).isInstanceOfSatisfying(Uncertain.class,
                    result -> assertThat(result.reason()).isEqualTo(Uncertainty.TIMED_OUT));
            assertThat(started.getCount()).isZero();
            assertThat(firstWorker.get()).isNotSameAs(secondWorker.get());
            assertThat(firstWorker.get().isDaemon()).isTrue();
            assertThat(secondWorker.get().isDaemon()).isTrue();
            assertThat(firstWorker.get().getName()).startsWith("owned-sync-db-");
            assertThat(secondWorker.get().getName()).startsWith("owned-sync-db-");
            assertThat(firstWorker.get().isAlive()).isTrue();
            assertThat(secondWorker.get().isAlive()).isTrue();

            long rejectedAt = System.nanoTime();
            Outcome<String> third = operations.call(() -> {
                rejectedCallRuns.incrementAndGet();
                return "should not run";
            }, OwnedSyncDeadline.after(Duration.ofSeconds(1)));
            assertThat(third).isInstanceOfSatisfying(Rejected.class,
                    result -> assertThat(result.reason()).isEqualTo(Rejection.SATURATED));
            assertThat(System.nanoTime() - rejectedAt).isLessThan(Duration.ofMillis(400).toNanos());
            assertThat(rejectedCallRuns).hasValue(0);
            assertThat(workerInterrupted).isFalse();

            release.countDown();
            assertThat(finished.await(1, TimeUnit.SECONDS)).isTrue();
            Outcome<String> recovered = callWhenAvailable(operations, () -> {
                recoveryWorker.set(Thread.currentThread());
                return "recovered";
            });
            assertThat(recovered).isEqualTo(new Success<>("recovered"));
            assertThat(recoveryWorker.get()).isIn(firstWorker.get(), secondWorker.get());
        } finally {
            release.countDown();
            operations.close();
            join(firstWorker.get());
            join(secondWorker.get());
        }
    }

    @Test
    @Timeout(value = 4, unit = TimeUnit.SECONDS)
    void timeoutDoesNotCancelWorkerAndLateSuccessCannotReplaceUncertainOutcome() throws Exception {
        OwnedSyncDbOperations operations = new OwnedSyncDbOperations();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        AtomicBoolean workerInterrupted = new AtomicBoolean();
        Outcome<String> outcome;
        try {
            outcome = operations.call(() -> {
                worker.set(Thread.currentThread());
                started.countDown();
                try {
                    release.await();
                    return "late success";
                } catch (InterruptedException interrupted) {
                    workerInterrupted.set(true);
                    throw interrupted;
                } finally {
                    finished.countDown();
                }
            }, OwnedSyncDeadline.after(Duration.ofMillis(300)));

            assertThat(started.getCount()).isZero();
            assertThat(outcome).isInstanceOfSatisfying(Uncertain.class,
                    result -> assertThat(result.reason()).isEqualTo(Uncertainty.TIMED_OUT));
            assertThat(workerInterrupted).isFalse();
            assertThat(worker.get().isAlive()).isTrue();

            long closeStarted = System.nanoTime();
            operations.close();
            assertThat(System.nanoTime() - closeStarted).isLessThan(Duration.ofMillis(250).toNanos());
            assertThat(worker.get().isAlive()).isTrue();

            release.countDown();
            assertThat(finished.await(1, TimeUnit.SECONDS)).isTrue();
            join(worker.get());
            assertThat(workerInterrupted).isFalse();
            assertThat(outcome).isInstanceOf(Uncertain.class);
        } finally {
            release.countDown();
            operations.close();
            join(worker.get());
        }
    }

    @Test
    @Timeout(value = 4, unit = TimeUnit.SECONDS)
    void interruptAfterStartIsUncertainAndRestoredWithoutInterruptingWorker() throws Exception {
        OwnedSyncDbOperations operations = new OwnedSyncDbOperations();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch operationStarted = new CountDownLatch(1);
        CountDownLatch callerFinished = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        AtomicReference<Outcome<String>> outcome = new AtomicReference<>();
        AtomicBoolean callerInterruptRestored = new AtomicBoolean();
        AtomicBoolean workerInterrupted = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            outcome.set(operations.call(() -> {
                worker.set(Thread.currentThread());
                operationStarted.countDown();
                try {
                    release.await();
                    return "finished";
                } catch (InterruptedException interrupted) {
                    workerInterrupted.set(true);
                    throw interrupted;
                }
            }, OwnedSyncDeadline.after(Duration.ofSeconds(5))));
            callerInterruptRestored.set(Thread.currentThread().isInterrupted());
            callerFinished.countDown();
        }, "owned-sync-db-test-caller");
        try {
            caller.start();
            assertThat(operationStarted.await(1, TimeUnit.SECONDS)).isTrue();
            caller.interrupt();
            assertThat(callerFinished.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(outcome.get()).isInstanceOfSatisfying(Uncertain.class,
                    result -> assertThat(result.reason()).isEqualTo(Uncertainty.INTERRUPTED));
            assertThat(callerInterruptRestored).isTrue();
            assertThat(workerInterrupted).isFalse();
            assertThat(worker.get().isAlive()).isTrue();
        } finally {
            release.countDown();
            operations.close();
            caller.join(1_000);
            join(worker.get());
        }
        assertThat(caller.isAlive()).isFalse();
        assertThat(workerInterrupted).isFalse();
    }

    @Test
    void interruptAndExpiredDeadlineBeforeSubmissionRejectWithoutStartingOperation() {
        OwnedSyncDbOperations operations = new OwnedSyncDbOperations();
        AtomicInteger started = new AtomicInteger();
        try {
            Thread.currentThread().interrupt();
            Outcome<String> interrupted;
            try {
                interrupted = operations.call(() -> {
                    started.incrementAndGet();
                    return "unexpected";
                }, OwnedSyncDeadline.after(Duration.ofSeconds(1)));
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                Thread.interrupted();
            }
            assertThat(interrupted).isInstanceOfSatisfying(Rejected.class,
                    result -> assertThat(result.reason()).isEqualTo(Rejection.INTERRUPTED));

            Outcome<String> expired = operations.call(() -> {
                started.incrementAndGet();
                return "unexpected";
            }, OwnedSyncDeadline.after(Duration.ZERO));
            assertThat(expired).isInstanceOfSatisfying(Rejected.class,
                    result -> assertThat(result.reason()).isEqualTo(Rejection.DEADLINE_EXPIRED));
            assertThat(started).hasValue(0);
        } finally {
            Thread.interrupted();
            operations.close();
        }
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.SECONDS)
    void repeatedOperationsUseTheOriginalMethodEntryDeadline() throws Exception {
        OwnedSyncDbOperations operations = new OwnedSyncDbOperations();
        AtomicReference<Thread> worker = new AtomicReference<>();
        OwnedSyncDeadline deadline = OwnedSyncDeadline.after(Duration.ofMillis(250));
        try {
            Outcome<String> first = operations.call(() -> {
                worker.set(Thread.currentThread());
                return "first";
            }, deadline);
            assertThat(first).isEqualTo(new Success<>("first"));

            LockSupport.parkNanos(deadline.remainingNanos() + Duration.ofMillis(5).toNanos());
            AtomicBoolean secondStarted = new AtomicBoolean();
            Outcome<String> second = operations.call(() -> {
                secondStarted.set(true);
                return "deadline must not reset";
            }, deadline);
            assertThat(second).isInstanceOfSatisfying(Rejected.class,
                    result -> assertThat(result.reason()).isEqualTo(Rejection.DEADLINE_EXPIRED));
            assertThat(secondStarted).isFalse();
        } finally {
            operations.close();
            join(worker.get());
        }
    }

    private static ThreadPoolExecutor supervisedWorkers(OwnedSyncDbOperations operations)
            throws ReflectiveOperationException {
        Field executorField = OwnedSyncDbOperations.class.getDeclaredField("executor");
        executorField.setAccessible(true);
        return (ThreadPoolExecutor) executorField.get(operations);
    }

    private static Callable<String> stuckTask(CountDownLatch started, CountDownLatch finished,
                                               CountDownLatch release, AtomicReference<Thread> worker,
                                               AtomicBoolean interrupted) {
        return () -> {
            worker.set(Thread.currentThread());
            started.countDown();
            try {
                release.await();
                return "released";
            } catch (InterruptedException exception) {
                interrupted.set(true);
                throw exception;
            } finally {
                finished.countDown();
            }
        };
    }

    private static <T> Outcome<T> callWhenAvailable(OwnedSyncDbOperations operations,
                                                    Callable<T> operation) throws InterruptedException {
        long stopAt = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (System.nanoTime() < stopAt) {
            Outcome<T> result = operations.call(operation, OwnedSyncDeadline.after(Duration.ofSeconds(1)));
            if (!(result instanceof Rejected<?> rejected && rejected.reason() == Rejection.SATURATED)) {
                return result;
            }
            LockSupport.parkNanos(Duration.ofMillis(1).toNanos());
        }
        throw new AssertionError("workers did not recover after their operations exited");
    }

    private static void join(Thread thread) throws InterruptedException {
        if (thread == null) return;
        thread.join(1_000);
        assertThat(thread.isAlive()).isFalse();
    }
}
