package dev.quantti.fplassistant.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class OwnedMatchdayRuntimeTest {

    @Test
    void plannerStartsImmediatelyThenWaitsOneMinuteWhileWorkersContinue() {
        OwnedMatchdayCoordinator coordinator = mock(OwnedMatchdayCoordinator.class);
        PlannerRuntimeRepository repository = mock(PlannerRuntimeRepository.class);
        OwnedSyncHttpClient http = mock(OwnedSyncHttpClient.class);
        OwnedSyncWatchdog watchdog = mock(OwnedSyncWatchdog.class);
        var fence = new OwnedSyncStore.RuntimeFence(
                "2026-27", OwnedSyncMode.STAGING, 7, Instant.EPOCH);
        OwnedMatchdayRuntime runtime = new OwnedMatchdayRuntime(coordinator, repository, http,
                "test", fence, watchdog, "2026-27");
        try {
            runtime.start();
            verify(coordinator, timeout(1_000)).tick();
            verify(coordinator, after(5_500).times(1)).tick();
            verify(coordinator, timeout(1_000).atLeast(2)).dispatchOne("1");
            verify(coordinator, timeout(1_000).atLeast(2)).dispatchOne("2");
            verify(coordinator, timeout(60_000).times(2)).tick();
        } finally {
            runtime.stop();
        }
    }

    @Test
    void prunesStagedPayloadsOnStartAndThenHourly() {
        OwnedMatchdayCoordinator coordinator = mock(OwnedMatchdayCoordinator.class);
        PlannerRuntimeRepository repository = mock(PlannerRuntimeRepository.class);
        OwnedSyncHttpClient http = mock(OwnedSyncHttpClient.class);
        OwnedSyncWatchdog watchdog = mock(OwnedSyncWatchdog.class);
        var fence = new OwnedSyncStore.RuntimeFence(
                "2026-27", OwnedSyncMode.STAGING, 7, Instant.EPOCH);
        OwnedMatchdayRuntime runtime = new OwnedMatchdayRuntime(coordinator, repository, http,
                "test", fence, watchdog, "2026-27");
        try {
            runtime.start();
            verify(coordinator, timeout(1_000)).pruneStagedPayloads();
            verify(coordinator, after(1_500).times(1)).pruneStagedPayloads();
        } finally {
            runtime.stop();
        }
    }

    @Test
    void stopAlwaysClosesTransportWhenDurableDeactivationThrows() {
        OwnedMatchdayCoordinator coordinator = mock(OwnedMatchdayCoordinator.class);
        PlannerRuntimeRepository repository = mock(PlannerRuntimeRepository.class);
        OwnedSyncHttpClient http = mock(OwnedSyncHttpClient.class);
        OwnedSyncWatchdog watchdog = mock(OwnedSyncWatchdog.class);
        var fence = new OwnedSyncStore.RuntimeFence(
                "2026-27", OwnedSyncMode.STAGING, 7, Instant.EPOCH);
        doThrow(new IllegalStateException("database unavailable")).when(repository).deactivate(fence);
        OwnedMatchdayRuntime runtime = new OwnedMatchdayRuntime(coordinator, repository, http,
                "test", fence, watchdog, "2026-27");

        runtime.start();
        runtime.stop();

        assertThat(runtime.isRunning()).isFalse();
        verify(repository).deactivate(fence);
        verify(http, timeout(1_000)).close();
    }
}
