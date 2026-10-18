package dev.quantti.fplassistant.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;

class OwnedMatchdayRuntimeTest {

    @Test
    void schedulesProductionCadencesWithoutWaitingForTheWallClock() {
        OwnedMatchdayCoordinator coordinator = mock(OwnedMatchdayCoordinator.class);
        PlannerRuntimeRepository repository = mock(PlannerRuntimeRepository.class);
        OwnedSyncHttpClient http = idleHttp();
        OwnedSyncWatchdog watchdog = mock(OwnedSyncWatchdog.class);
        var fence = new OwnedSyncStore.RuntimeFence(
                "2026-27", OwnedSyncMode.STAGING, 7, Instant.EPOCH);
        OwnedMatchdayRuntime runtime = new OwnedMatchdayRuntime(coordinator, repository, http,
                "test", fence, watchdog, "2026-27");
        var planner = mock(ScheduledExecutorService.class);
        var workers = mock(ScheduledExecutorService.class);
        var checks = mock(ScheduledExecutorService.class);
        var delivery = mock(ScheduledExecutorService.class);
        for (var executor : new ScheduledExecutorService[] {planner, workers, checks, delivery}) {
            when(executor.isTerminated()).thenReturn(true);
        }
        try (var factories = mockStatic(Executors.class)) {
            factories.when(() -> Executors.newSingleThreadScheduledExecutor(any()))
                    .thenReturn(planner, checks, delivery);
            factories.when(() -> Executors.newScheduledThreadPool(eq(2), any())).thenReturn(workers);
            try {
                runtime.start();
                var tick = ArgumentCaptor.forClass(Runnable.class);
                verify(planner).scheduleWithFixedDelay(tick.capture(), eq(0L), eq(1L), eq(TimeUnit.MINUTES));
                var workerTasks = ArgumentCaptor.forClass(Runnable.class);
                verify(workers, times(2)).scheduleWithFixedDelay(
                        workerTasks.capture(), eq(0L), eq(1L), eq(TimeUnit.SECONDS));
                tick.getValue().run();
                workerTasks.getAllValues().forEach(Runnable::run);
                workerTasks.getAllValues().forEach(Runnable::run);
                verify(coordinator, times(1)).tick();
                verify(coordinator, times(2)).dispatchOne("1");
                verify(coordinator, times(2)).dispatchOne("2");
                tick.getValue().run();
                verify(coordinator, times(2)).tick();
                verify(checks).scheduleWithFixedDelay(any(Runnable.class), eq(5L), eq(5L), eq(TimeUnit.MINUTES));
                var retention = ArgumentCaptor.forClass(Runnable.class);
                verify(checks).scheduleWithFixedDelay(retention.capture(), eq(0L), eq(1L), eq(TimeUnit.HOURS));
                retention.getValue().run();
                verify(coordinator).pruneStagedPayloads();
                verify(delivery).scheduleWithFixedDelay(any(Runnable.class), eq(0L), eq(30L), eq(TimeUnit.SECONDS));
            } finally {
                runtime.stop();
            }
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void stopWaitsForInterruptedKnownNoFetchSettlementBeforeReturning() throws Exception {
        OwnedMatchdayCoordinator coordinator = mock(OwnedMatchdayCoordinator.class);
        PlannerRuntimeRepository repository = mock(PlannerRuntimeRepository.class);
        AtomicBoolean transportClosed = new AtomicBoolean();
        OwnedSyncHttpClient http = idleHttp(new AtomicReference<>(), transportClosed);
        OwnedSyncWatchdog watchdog = mock(OwnedSyncWatchdog.class);
        var fence = new OwnedSyncStore.RuntimeFence(
                "2026-27", OwnedSyncMode.STAGING, 7, Instant.EPOCH);
        CountDownLatch workerEntered = new CountDownLatch(1);
        CountDownLatch workerInterrupted = new CountDownLatch(1);
        CountDownLatch finishSettlement = new CountDownLatch(1);
        CountDownLatch settlementFinished = new CountDownLatch(1);
        CountDownLatch stopReturned = new CountDownLatch(1);
        AtomicInteger dispatches = new AtomicInteger();
        AtomicBoolean workDatabaseOpen = new AtomicBoolean(true);
        AtomicBoolean settlementSawOpenDatabase = new AtomicBoolean();
        doAnswer(invocation -> {
            if (dispatches.getAndIncrement() == 0) {
                workerEntered.countDown();
                awaitIgnoringInterrupts(finishSettlement, workerInterrupted);
                settlementSawOpenDatabase.set(workDatabaseOpen.get());
                settlementFinished.countDown();
            }
            return null;
        }).when(coordinator).dispatchOne(anyString());
        OwnedMatchdayRuntime runtime = new OwnedMatchdayRuntime(coordinator, repository, http,
                "test", fence, watchdog, "2026-27", Duration.ofMillis(700), Duration.ofMillis(100));
        runtime.start();
        assertThat(workerEntered.await(1, TimeUnit.SECONDS)).isTrue();
        Thread stopper = Thread.ofPlatform().name("owned-sync-stop-test").start(() -> {
            try {
                runtime.stop();
            } finally {
                workDatabaseOpen.set(false);
                stopReturned.countDown();
            }
        });

        try {
            assertThat(workerInterrupted.await(500, TimeUnit.MILLISECONDS)).isTrue();
            assertThat(stopReturned.await(50, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(transportClosed).isFalse();

            finishSettlement.countDown();
            assertThat(settlementFinished.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(stopReturned.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(settlementSawOpenDatabase).isTrue();
            assertThat(transportClosed).isTrue();
            verify(http).close();
        } finally {
            finishSettlement.countDown();
            stopper.join(1_000);
            runtime.stop();
        }
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.SECONDS)
    void stopCapsDrainWhenWorkerNeverFinishes() throws Exception {
        OwnedMatchdayCoordinator coordinator = mock(OwnedMatchdayCoordinator.class);
        PlannerRuntimeRepository repository = mock(PlannerRuntimeRepository.class);
        OwnedSyncHttpClient http = idleHttp();
        OwnedSyncWatchdog watchdog = mock(OwnedSyncWatchdog.class);
        var fence = new OwnedSyncStore.RuntimeFence(
                "2026-27", OwnedSyncMode.STAGING, 7, Instant.EPOCH);
        CountDownLatch workerEntered = new CountDownLatch(1);
        CountDownLatch workerInterrupted = new CountDownLatch(1);
        CountDownLatch finishWorker = new CountDownLatch(1);
        CountDownLatch workerFinished = new CountDownLatch(1);
        AtomicInteger dispatches = new AtomicInteger();
        doAnswer(invocation -> {
            if (dispatches.getAndIncrement() == 0) {
                workerEntered.countDown();
                awaitIgnoringInterrupts(finishWorker, workerInterrupted);
                workerFinished.countDown();
            }
            return null;
        }).when(coordinator).dispatchOne(anyString());
        OwnedMatchdayRuntime runtime = new OwnedMatchdayRuntime(coordinator, repository, http,
                "test", fence, watchdog, "2026-27", Duration.ofMillis(350), Duration.ofMillis(60));
        runtime.start();
        try {
            assertThat(workerEntered.await(1, TimeUnit.SECONDS)).isTrue();
            long started = System.nanoTime();
            runtime.stop();
            long elapsed = System.nanoTime() - started;

            assertThat(workerInterrupted.getCount()).isZero();
            assertThat(workerFinished.getCount()).isEqualTo(1);
            assertThat(elapsed).isBetween(Duration.ofMillis(200).toNanos(), Duration.ofSeconds(1).toNanos());
            verify(http).close();
        } finally {
            finishWorker.countDown();
            assertThat(workerFinished.await(1, TimeUnit.SECONDS)).isTrue();
            runtime.stop();
        }
    }

    private static void awaitIgnoringInterrupts(CountDownLatch release, CountDownLatch interrupted) {
        boolean restoreInterrupt = false;
        while (true) {
            try {
                release.await();
                break;
            } catch (InterruptedException shutdown) {
                restoreInterrupt = true;
                interrupted.countDown();
            }
        }
        if (restoreInterrupt) Thread.currentThread().interrupt();
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void stopReturnsWithinItsBudgetWhenDurableDeactivationNeverFinishes() throws Exception {
        OwnedMatchdayCoordinator coordinator = mock(OwnedMatchdayCoordinator.class);
        PlannerRuntimeRepository repository = mock(PlannerRuntimeRepository.class);
        AtomicReference<OwnedSyncDbOperations.Outcome<?>> deactivationOutcome = new AtomicReference<>();
        AtomicBoolean transportClosed = new AtomicBoolean();
        OwnedSyncHttpClient http = idleHttp(deactivationOutcome, transportClosed);
        OwnedSyncWatchdog watchdog = mock(OwnedSyncWatchdog.class);
        var fence = new OwnedSyncStore.RuntimeFence(
                "2026-27", OwnedSyncMode.STAGING, 7, Instant.EPOCH);
        CountDownLatch deactivationEntered = new CountDownLatch(1);
        CountDownLatch releaseDeactivation = new CountDownLatch(1);
        CountDownLatch deactivationInterrupted = new CountDownLatch(1);
        CountDownLatch stopReturned = new CountDownLatch(1);
        doAnswer(invocation -> {
            deactivationEntered.countDown();
            awaitIgnoringInterrupts(releaseDeactivation, deactivationInterrupted);
            return null;
        }).when(repository).deactivate(fence);
        OwnedMatchdayRuntime runtime = new OwnedMatchdayRuntime(coordinator, repository, http,
                "test", fence, watchdog, "2026-27", Duration.ofMillis(500), Duration.ofMillis(80));
        runtime.start();
        Thread stopper = Thread.ofPlatform().name("owned-sync-stuck-deactivation-test").start(() -> {
            try {
                runtime.stop();
            } finally {
                stopReturned.countDown();
            }
        });

        try {
            assertThat(deactivationEntered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(stopReturned.await(900, TimeUnit.MILLISECONDS)).isTrue();
            assertThat(transportClosed).isTrue();
            assertThat(deactivationOutcome.get()).isInstanceOf(OwnedSyncDbOperations.Uncertain.class);
        } finally {
            releaseDeactivation.countDown();
            stopper.join(1_000);
            assertThat(http.awaitDbOperationsUntil(System.nanoTime() + Duration.ofSeconds(1).toNanos())).isTrue();
            runtime.stop();
        }
    }

    @Test
    void stopConfirmsPromptDatabaseDeactivationBeforeClosingTransport() {
        OwnedMatchdayCoordinator coordinator = mock(OwnedMatchdayCoordinator.class);
        PlannerRuntimeRepository repository = mock(PlannerRuntimeRepository.class);
        AtomicReference<OwnedSyncDbOperations.Outcome<?>> deactivationOutcome = new AtomicReference<>();
        OwnedSyncHttpClient http = idleHttp(deactivationOutcome);
        OwnedSyncWatchdog watchdog = mock(OwnedSyncWatchdog.class);
        var fence = new OwnedSyncStore.RuntimeFence(
                "2026-27", OwnedSyncMode.STAGING, 7, Instant.EPOCH);
        OwnedMatchdayRuntime runtime = new OwnedMatchdayRuntime(coordinator, repository, http,
                "test", fence, watchdog, "2026-27");

        runtime.start();
        runtime.stop();

        assertThat(deactivationOutcome.get()).isInstanceOf(OwnedSyncDbOperations.Success.class);
        var shutdownOrder = inOrder(http, repository);
        shutdownOrder.verify(http).stopSending();
        shutdownOrder.verify(http).deactivateDatabaseOnly(any(), anyLong());
        shutdownOrder.verify(repository).deactivate(fence);
        shutdownOrder.verify(http).close();
        shutdownOrder.verify(http).awaitDbOperationsUntil(anyLong());
    }

    @Test
    void stopAlwaysClosesTransportWhenDurableDeactivationThrows() {
        OwnedMatchdayCoordinator coordinator = mock(OwnedMatchdayCoordinator.class);
        PlannerRuntimeRepository repository = mock(PlannerRuntimeRepository.class);
        OwnedSyncHttpClient http = idleHttp();
        OwnedSyncWatchdog watchdog = mock(OwnedSyncWatchdog.class);
        var fence = new OwnedSyncStore.RuntimeFence(
                "2026-27", OwnedSyncMode.STAGING, 7, Instant.EPOCH);
        doThrow(new IllegalStateException("database unavailable")).when(repository).deactivate(fence);
        OwnedMatchdayRuntime runtime = new OwnedMatchdayRuntime(coordinator, repository, http,
                "test", fence, watchdog, "2026-27");

        runtime.start();
        runtime.stop();

        assertThat(runtime.isRunning()).isFalse();
        var shutdownOrder = inOrder(http, repository);
        shutdownOrder.verify(http).stopSending();
        shutdownOrder.verify(http).deactivateDatabaseOnly(any(), anyLong());
        shutdownOrder.verify(repository).deactivate(fence);
        shutdownOrder.verify(http).close();
        shutdownOrder.verify(http).awaitDbOperationsUntil(anyLong());
    }

    private static OwnedSyncHttpClient idleHttp() {
        return idleHttp(new AtomicReference<>());
    }

    private static OwnedSyncHttpClient idleHttp(AtomicReference<OwnedSyncDbOperations.Outcome<?>> outcome) {
        return idleHttp(outcome, new AtomicBoolean());
    }

    private static OwnedSyncHttpClient idleHttp(AtomicReference<OwnedSyncDbOperations.Outcome<?>> outcome,
                                                 AtomicBoolean transportClosed) {
        OwnedSyncDbOperations operations = new OwnedSyncDbOperations();
        OwnedSyncHttpClient http = mock(OwnedSyncHttpClient.class);
        doAnswer(invocation -> {
            Callable<Void> deactivation = invocation.getArgument(0);
            long deadline = invocation.getArgument(1);
            OwnedSyncDbOperations.Outcome<Void> result = operations.call(
                    deactivation, OwnedSyncDeadline.at(deadline));
            outcome.set(result);
            return result;
        }).when(http).deactivateDatabaseOnly(any(), anyLong());
        when(http.awaitDbOperationsUntil(anyLong())).thenAnswer(invocation ->
                operations.awaitTerminationUntil(invocation.getArgument(0)));
        doAnswer(invocation -> {
            transportClosed.set(true);
            operations.close();
            return null;
        }).when(http).close();
        return http;
    }
}
