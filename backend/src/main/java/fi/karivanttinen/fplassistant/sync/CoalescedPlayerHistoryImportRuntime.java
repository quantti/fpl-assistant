package fi.karivanttinen.fplassistant.sync;

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
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean cycleRunning = new AtomicBoolean();
    private final AtomicBoolean initialMaintenancePending = new AtomicBoolean(true);
    private ScheduledExecutorService scheduler;

    public CoalescedPlayerHistoryImportRuntime(CoalescedPlayerHistoryImportEntrypoint collector,
                                                IntSupplier payloadMaintenance) {
        this.collector = Objects.requireNonNull(collector);
        this.payloadMaintenance = Objects.requireNonNull(payloadMaintenance);
    }

    @Override
    public synchronized void start() {
        if (!running.compareAndSet(false, true)) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("owned-sync-importer-hourly").daemon(true).factory());
        scheduler.scheduleWithFixedDelay(this::runScheduledCycle, 0, INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
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
            log.info("hourly incremental core import: {} ({} pages requested, {} committed, {} failed)",
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
        try {
            if (!scheduler.awaitTermination(STOP_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        scheduler = null;
    }

    @Override public boolean isRunning() { return running.get(); }
}
