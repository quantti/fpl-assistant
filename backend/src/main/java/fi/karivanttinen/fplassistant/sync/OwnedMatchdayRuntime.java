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
    private static final Duration SHUTDOWN_LIMIT = Duration.ofSeconds(10);

    private final OwnedMatchdayCoordinator coordinator;
    private final PlannerRuntimeRepository runtimeRepository;
    private final OwnedSyncHttpClient http;
    private final String instanceId;
    private final OwnedSyncStore.RuntimeFence runtimeFence;
    private final OwnedSyncWatchdog watchdog;
    private final String season;
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
        this.coordinator = Objects.requireNonNull(coordinator);
        this.runtimeRepository = Objects.requireNonNull(runtimeRepository);
        this.http = Objects.requireNonNull(http);
        this.instanceId = Objects.requireNonNull(instanceId);
        this.runtimeFence = Objects.requireNonNull(runtimeFence);
        this.watchdog = Objects.requireNonNull(watchdog);
        this.season = Objects.requireNonNull(season);
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
        if (!running.compareAndSet(true, false)) return;
        try {
            runtimeRepository.deactivate(runtimeFence);
        } catch (RuntimeException failure) {
            log.error("owned sync could not durably fence this activation during shutdown; cancelling local work", failure);
        } finally {
            shutdown(plannerExecutor);
            shutdown(workerExecutor);
            shutdown(watchdogExecutor);
            shutdown(deliveryExecutor);
            long deadline = System.nanoTime() + SHUTDOWN_LIMIT.toNanos();
            await(plannerExecutor, deadline);
            await(workerExecutor, deadline);
            await(watchdogExecutor, deadline);
            await(deliveryExecutor, deadline);
            http.close();
        }
    }

    private static void shutdown(ScheduledExecutorService executor) {
        if (executor != null) executor.shutdown();
    }

    private static void await(ScheduledExecutorService executor, long deadline) {
        if (executor == null) return;
        try {
            long remaining = Math.max(0, deadline - System.nanoTime());
            if (!executor.awaitTermination(remaining, TimeUnit.NANOSECONDS)) executor.shutdownNow();
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override public boolean isRunning() { return running.get(); }
    @Override public boolean isAutoStartup() { return true; }
    @Override public int getPhase() { return Integer.MAX_VALUE - 100; }
}
