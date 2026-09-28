package fi.karivanttinen.fplassistant.sync;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/** One short planner tick plus exactly two bounded database-leased worker loops. */
public final class OwnedMatchdayRuntime implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OwnedMatchdayRuntime.class);
    private static final Duration SHUTDOWN_LIMIT = Duration.ofSeconds(50);
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(10);
    private static final Duration TRANSPORT_CLOSE_RESERVE = Duration.ofMillis(1_100);

    private final OwnedMatchdayCoordinator coordinator;
    private final PlannerRuntimeRepository runtimeRepository;
    private final OwnedSyncHttpClient http;
    private final String instanceId;
    private final OwnedSyncStore.RuntimeFence runtimeFence;
    private final OwnedSyncWatchdog watchdog;
    private final String season;
    private final Duration shutdownLimit;
    private final Duration shutdownGrace;
    private final AtomicBoolean running = new AtomicBoolean();
    private ScheduledExecutorService plannerExecutor;
    private ScheduledExecutorService workerExecutor;
    private ScheduledExecutorService watchdogExecutor;
    private ScheduledExecutorService deliveryExecutor;

    public OwnedMatchdayRuntime(OwnedMatchdayCoordinator coordinator,
                                PlannerRuntimeRepository runtimeRepository,
                                OwnedSyncHttpClient http, String instanceId,
                                OwnedSyncStore.RuntimeFence runtimeFence,
                                OwnedSyncWatchdog watchdog, String season) {
        this(coordinator, runtimeRepository, http, instanceId, runtimeFence, watchdog, season,
                SHUTDOWN_LIMIT, SHUTDOWN_GRACE);
    }

    OwnedMatchdayRuntime(OwnedMatchdayCoordinator coordinator,
                         PlannerRuntimeRepository runtimeRepository,
                         OwnedSyncHttpClient http, String instanceId,
                         OwnedSyncStore.RuntimeFence runtimeFence,
                         OwnedSyncWatchdog watchdog, String season,
                         Duration shutdownLimit, Duration shutdownGrace) {
        this.coordinator = Objects.requireNonNull(coordinator);
        this.runtimeRepository = Objects.requireNonNull(runtimeRepository);
        this.http = Objects.requireNonNull(http);
        this.instanceId = Objects.requireNonNull(instanceId);
        this.runtimeFence = Objects.requireNonNull(runtimeFence);
        this.watchdog = Objects.requireNonNull(watchdog);
        this.season = Objects.requireNonNull(season);
        this.shutdownLimit = positiveShutdownLimit(shutdownLimit);
        this.shutdownGrace = positiveShutdownGrace(shutdownGrace, this.shutdownLimit);
    }

    @Override
    public synchronized void start() {
        if (!running.compareAndSet(false, true)) return;
        plannerExecutor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("owned-sync-planner").daemon(true).factory());
        workerExecutor = Executors.newScheduledThreadPool(2,
                Thread.ofPlatform().name("owned-sync-worker-", 0).daemon(true).factory());
        watchdogExecutor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("owned-sync-watchdog").daemon(true).factory());
        deliveryExecutor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("owned-sync-alert-delivery").daemon(true).factory());
        // A tick parses a full league snapshot and persists its transitions. Workers retain their
        // one-second cadence. A minute between ticks bounds idle metadata traffic, at the cost of
        // up to a minute of planning delay (plus tick duration); source slots remain unchanged.
        plannerExecutor.scheduleWithFixedDelay(() -> guarded("planner", coordinator::tick),
                0, 1, TimeUnit.MINUTES);
        for (int worker = 0; worker < 2; worker++) {
            String id = Integer.toString(worker + 1);
            workerExecutor.scheduleWithFixedDelay(
                    () -> guarded("worker-" + id, () -> coordinator.dispatchOne(id)),
                    0, 1, TimeUnit.SECONDS);
        }
        watchdogExecutor.scheduleWithFixedDelay(
                () -> guarded("watchdog", () -> watchdog.check(season)),
                5, 5, TimeUnit.MINUTES);
        deliveryExecutor.scheduleWithFixedDelay(
                () -> guarded("alert-delivery", watchdog::deliver),
                0, 30, TimeUnit.SECONDS);
        watchdogExecutor.scheduleWithFixedDelay(
                () -> guarded("payload-retention", coordinator::pruneStagedPayloads),
                0, 1, TimeUnit.HOURS);
    }

    private void guarded(String task, Runnable operation) {
        if (!running.get()) return;
        try {
            operation.run();
        } catch (RuntimeException failure) {
            log.error("owned sync {} failed", task, failure);
            try {
                runtimeRepository.recordError(instanceId,
                        task + ": " + (failure.getMessage() == null
                                ? failure.getClass().getSimpleName() : failure.getMessage()));
            } catch (RuntimeException heartbeatFailure) {
                log.error("owned sync could not persist runtime failure", heartbeatFailure);
            }
        }
    }

    @Override
    public synchronized void stop() {
        long stopStarted = System.nanoTime();
        long deadline = stopStarted + shutdownLimit.toNanos();
        if (!running.compareAndSet(true, false)) return;
        http.stopSending();
        try {
            OwnedSyncDbOperations.Outcome<Void> deactivation = http.deactivateDatabaseOnly(() -> {
                runtimeRepository.deactivate(runtimeFence);
                return null;
            }, deadline);
            if (deactivation instanceof OwnedSyncDbOperations.Rejected<?> rejected) {
                log.error("owned sync OFF not confirmed during shutdown: DB deactivation rejected ({})",
                        rejected.reason());
            } else if (deactivation instanceof OwnedSyncDbOperations.Uncertain<?> uncertain) {
                log.error("owned sync OFF not confirmed during shutdown: DB deactivation outcome uncertain ({})",
                        uncertain.reason(), uncertain.failure());
            } else if (!(deactivation instanceof OwnedSyncDbOperations.Success<?>)) {
                log.error("owned sync OFF not confirmed during shutdown: invalid DB deactivation outcome");
            }
        } catch (RuntimeException failure) {
            log.error("owned sync OFF not confirmed during shutdown: DB deactivation supervisor failed", failure);
        } finally {
            shutdown(plannerExecutor);
            shutdown(workerExecutor);
            shutdown(watchdogExecutor);
            shutdown(deliveryExecutor);
            long closeReserve = Math.min(TRANSPORT_CLOSE_RESERVE.toNanos(), shutdownLimit.toNanos() / 4);
            long drainDeadline = deadline - closeReserve;
            long graceDeadline = Math.min(drainDeadline, stopStarted + shutdownGrace.toNanos());
            StopWait wait = new StopWait();
            try {
                wait.await(workerExecutor, graceDeadline);
                wait.await(plannerExecutor, graceDeadline);
                wait.await(watchdogExecutor, graceDeadline);
                wait.await(deliveryExecutor, graceDeadline);
                shutdownNow(plannerExecutor);
                shutdownNow(workerExecutor);
                shutdownNow(watchdogExecutor);
                shutdownNow(deliveryExecutor);

                boolean workerExited = wait.await(workerExecutor, drainDeadline);
                boolean plannerExited = wait.await(plannerExecutor, drainDeadline);
                boolean watchdogExited = wait.await(watchdogExecutor, drainDeadline);
                boolean deliveryExited = wait.await(deliveryExecutor, drainDeadline);
                if (!workerExited) {
                    log.warn("owned sync worker did not exit and settle within the shutdown budget; "
                            + "the owned work database may close with the attempt unresolved");
                }
                if (!plannerExited || !watchdogExited || !deliveryExited) {
                    log.warn("owned sync auxiliary executor did not exit within the shutdown budget");
                }
            } finally {
                try {
                    http.close();
                } finally {
                    try {
                        if (!http.awaitDbOperationsUntil(deadline)) {
                            log.warn("owned sync DB operation did not exit within the shutdown budget; "
                                    + "the owned work database may close with settlement unresolved");
                        }
                    } finally {
                        if (wait.interrupted()) Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }

    private static Duration positiveShutdownLimit(Duration limit) {
        Objects.requireNonNull(limit);
        if (limit.isZero() || limit.isNegative() || limit.compareTo(SHUTDOWN_LIMIT) > 0) {
            throw new IllegalArgumentException("shutdown limit must be positive and at most 50 seconds");
        }
        return limit;
    }

    private static Duration positiveShutdownGrace(Duration grace, Duration limit) {
        Objects.requireNonNull(grace);
        if (grace.isZero() || grace.isNegative() || grace.compareTo(limit) > 0) {
            throw new IllegalArgumentException("shutdown grace must be positive and no longer than its limit");
        }
        return grace;
    }

    private static void shutdown(ScheduledExecutorService executor) {
        if (executor != null) executor.shutdown();
    }

    private static void shutdownNow(ScheduledExecutorService executor) {
        if (executor != null) executor.shutdownNow();
    }

    private static final class StopWait {
        private boolean interrupted = Thread.interrupted();

        boolean interrupted() { return interrupted; }

        boolean await(ScheduledExecutorService executor, long deadline) {
            if (executor == null) return true;
            while (!executor.isTerminated()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return false;
                try {
                    if (executor.awaitTermination(remaining, TimeUnit.NANOSECONDS)) return true;
                } catch (InterruptedException shutdownInterrupted) {
                    interrupted = true;
                }
            }
            return true;
        }
    }

    @Override public boolean isRunning() { return running.get(); }
    @Override public boolean isAutoStartup() { return true; }
    @Override public int getPhase() { return Integer.MAX_VALUE - 100; }
}
