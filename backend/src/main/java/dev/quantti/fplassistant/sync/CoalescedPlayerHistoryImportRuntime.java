package dev.quantti.fplassistant.sync;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/** Opt-in hourly trigger for the durable incremental collector; it never changes runtime mode. */
public final class CoalescedPlayerHistoryImportRuntime implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(CoalescedPlayerHistoryImportRuntime.class);
    static final Duration INTERVAL = Duration.ofHours(1);
    private static final Duration STOP_WAIT = Duration.ofSeconds(5);

    private final CoalescedPlayerHistoryImportEntrypoint collector;
    private final IntSupplier payloadMaintenance;
    private final Runnable heartbeat;
    private final Runnable watchdog;
    private final Runnable delivery;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean cycleRunning = new AtomicBoolean();
    private final AtomicBoolean initialMaintenancePending = new AtomicBoolean(true);
    private ScheduledExecutorService scheduler;
    private ScheduledExecutorService heartbeatScheduler;
    private ScheduledExecutorService watchdogScheduler;
    private ScheduledExecutorService deliveryScheduler;

    public CoalescedPlayerHistoryImportRuntime(CoalescedPlayerHistoryImportEntrypoint collector,
                                                IntSupplier payloadMaintenance) {
        this(collector, payloadMaintenance, null, null, null);
    }

    public CoalescedPlayerHistoryImportRuntime(CoalescedPlayerHistoryImportEntrypoint collector,
                                                IntSupplier payloadMaintenance, Runnable heartbeat,
                                                Runnable watchdog, Runnable delivery) {
        this.collector = Objects.requireNonNull(collector);
        this.payloadMaintenance = Objects.requireNonNull(payloadMaintenance);
        this.heartbeat = heartbeat;
        this.watchdog = watchdog;
        this.delivery = delivery;
    }

    @Override
    public synchronized void start() {
        if (!running.compareAndSet(false, true)) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("owned-sync-importer-hourly").daemon(true).factory());
        scheduler.scheduleWithFixedDelay(this::runScheduledCycle, 0, INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
        if (heartbeat != null) {
            heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().name("owned-sync-importer-heartbeat").daemon(true).factory());
            heartbeatScheduler.scheduleWithFixedDelay(() -> guarded("heartbeat", heartbeat),
                    0, 1, TimeUnit.MINUTES);
        }
        if (watchdog != null) {
            watchdogScheduler = Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().name("owned-sync-importer-watchdog").daemon(true).factory());
            watchdogScheduler.scheduleWithFixedDelay(() -> guarded("watchdog", watchdog),
                    5, 5, TimeUnit.MINUTES);
        }
        if (delivery != null) {
            deliveryScheduler = Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().name("owned-sync-importer-alert-delivery").daemon(true).factory());
            deliveryScheduler.scheduleWithFixedDelay(() -> guarded("alert-delivery", delivery),
                    0, 30, TimeUnit.SECONDS);
        }
    }

    private void guarded(String task, Runnable operation) {
        if (!running.get()) return;
        try {
            operation.run();
        } catch (RuntimeException failure) {
            log.error("incremental core import {} failed", task, failure);
        }
    }

    /** Explicit manual boundary used by focused operations and tests. */
    public CoalescedPlayerHistoryCollector.CycleResult runOnce() {
        if (!cycleRunning.compareAndSet(false, true)) {
            return new CoalescedPlayerHistoryCollector.CycleResult(
                    CoalescedPlayerHistoryCollector.State.BUSY,
                    List.of(), List.of(), List.of(), List.of(), null);
        }
        try {
            return runCycleWithMaintenance();
        } finally {
            cycleRunning.set(false);
        }
    }

    private void runScheduledCycle() {
        if (!running.get() || !cycleRunning.compareAndSet(false, true)) return;
        try {
            if (initialMaintenancePending.compareAndSet(true, false)) runPayloadMaintenance();
            CoalescedPlayerHistoryCollector.CycleResult result = runCycleWithMaintenance();
            log.info("hourly incremental core import: {} ({} pages selected, {} committed, {} failed)",
                    result.state(), result.selectedPlayerIds().size(), result.committedPlayerIds().size(),
                    result.failedPlayerIds().size());
        } catch (RuntimeException failure) {
            log.error("hourly incremental core import failed", failure);
        } finally {
            cycleRunning.set(false);
        }
    }

    private CoalescedPlayerHistoryCollector.CycleResult runCycleWithMaintenance() {
        try {
            return collector.runCycle();
        } finally {
            runPayloadMaintenance();
        }
    }

    private void runPayloadMaintenance() {
        try {
            int pruned = payloadMaintenance.getAsInt();
            if (pruned > 0) {
                log.info("incremental payload retention pruned {} old payload bodies", pruned);
            }
        } catch (RuntimeException failure) {
            log.error("incremental payload retention failed; continuing without changing runtime mode", failure);
        }
    }

    @Override
    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) return;
        if (scheduler == null) return;
        scheduler.shutdown();
        if (heartbeatScheduler != null) heartbeatScheduler.shutdown();
        if (watchdogScheduler != null) watchdogScheduler.shutdown();
        if (deliveryScheduler != null) deliveryScheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(STOP_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                scheduler.shutdownNow();
            }
            if (heartbeatScheduler != null && !heartbeatScheduler.awaitTermination(
                    STOP_WAIT.toMillis(), TimeUnit.MILLISECONDS)) heartbeatScheduler.shutdownNow();
            if (watchdogScheduler != null && !watchdogScheduler.awaitTermination(
                    STOP_WAIT.toMillis(), TimeUnit.MILLISECONDS)) watchdogScheduler.shutdownNow();
            if (deliveryScheduler != null && !deliveryScheduler.awaitTermination(
                    STOP_WAIT.toMillis(), TimeUnit.MILLISECONDS)) deliveryScheduler.shutdownNow();
        } catch (InterruptedException interrupted) {
            scheduler.shutdownNow();
            if (heartbeatScheduler != null) heartbeatScheduler.shutdownNow();
            if (watchdogScheduler != null) watchdogScheduler.shutdownNow();
            if (deliveryScheduler != null) deliveryScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        scheduler = null;
        heartbeatScheduler = null;
        watchdogScheduler = null;
        deliveryScheduler = null;
    }

    @Override public boolean isRunning() { return running.get(); }
}
