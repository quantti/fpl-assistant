package dev.quantti.fplassistant.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

class OwnedSyncRuntimeConfigurationTest {

    @Test
    void defaultOffCreatesNoOwnedRuntimeAndTouchesNoDatabase() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        new ApplicationContextRunner()
                .withBean(JdbcTemplate.class, () -> jdbc)
                .withUserConfiguration(OwnedSyncRuntimeConfiguration.class,
                        CoalescedPlayerHistoryImportConfiguration.class,
                        SyncOperationsConfiguration.class)
                .run(context -> {
                    org.assertj.core.api.Assertions.assertThat(context)
                            .doesNotHaveBean("ownedSyncWorkDatabase")
                            .doesNotHaveBean(OwnedMatchdayRuntime.class)
                            .doesNotHaveBean(CoalescedPlayerHistoryImportEntrypoint.class)
                            .doesNotHaveBean(CoalescedPlayerHistoryCollector.class)
                            .doesNotHaveBean(CoalescedPlayerHistoryImportRuntime.class)
                            .doesNotHaveBean(OwnedMatchdayCoordinator.class)
                            .doesNotHaveBean(OwnedSyncHttpClient.class)
                            .doesNotHaveBean(OwnedSyncWatchdog.class)
                            .doesNotHaveBean(SyncOperations.class)
                            .doesNotHaveBean(SyncOperationsController.class);
                });
    }

    @Test
    void activeRequiresBothExplicitSeasonAndCutoverConfirmation() {
        assertThatThrownBy(() -> OwnedSyncRuntimeConfiguration.validateActivation(
                OwnedSyncMode.ACTIVE, "", true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("playing-season");
        assertThatThrownBy(() -> OwnedSyncRuntimeConfiguration.validateActivation(
                OwnedSyncMode.ACTIVE, "2026-27", false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("cutover-confirmed");
        OwnedSyncRuntimeConfiguration.validateActivation(OwnedSyncMode.STAGING, "", false);
    }

    @Test
    void newImporterRequiresActiveModeExplicitSeasonAndCutoverWithoutTouchingTheDatabase() {
        assertThatThrownBy(() -> CoalescedPlayerHistoryImportConfiguration.validateActivation(
                "OFF", "2026-27", true)).hasMessageContaining("mode=ACTIVE");
        assertThatThrownBy(() -> CoalescedPlayerHistoryImportConfiguration.validateActivation(
                "STAGING", "2026-27", true)).hasMessageContaining("mode=ACTIVE");
        assertThatThrownBy(() -> CoalescedPlayerHistoryImportConfiguration.validateActivation(
                "ACTIVE", "", true)).hasMessageContaining("playing-season");
        assertThatThrownBy(() -> CoalescedPlayerHistoryImportConfiguration.validateActivation(
                "ACTIVE", "2026-27", false)).hasMessageContaining("cutover-confirmed");
        assertThat(CoalescedPlayerHistoryImportConfiguration.validateActivation(
                "ACTIVE", "2026-27", true).playingSeason()).isEqualTo("2026-27");
    }

    @Test
    void importerGuardSelectsTheNewCoreLoopButLeavesDailyCaptureEnabled() {
        assertThat(OwnedSyncRuntimeConfiguration.legacyRuntimeEnabled("ACTIVE", false)).isTrue();
        assertThat(OwnedSyncRuntimeConfiguration.legacyRuntimeEnabled("ACTIVE", true)).isFalse();
        assertThat(OwnedSyncRuntimeConfiguration.legacyRuntimeEnabled("OFF", false)).isFalse();
        assertThat(OwnedSyncRuntimeConfiguration.catalogueCaptureEnabled("ACTIVE", false)).isFalse();
        assertThat(OwnedSyncRuntimeConfiguration.catalogueCaptureEnabled("ACTIVE", true)).isTrue();
        assertThat(OwnedSyncRuntimeConfiguration.catalogueCaptureEnabled("OFF", false)).isTrue();
    }

    @Test
    void invalidOptInFailsDuringActivationValidation() {
        new ApplicationContextRunner()
                .withUserConfiguration(CoalescedPlayerHistoryImportConfiguration.class)
                .withPropertyValues("fpl.owned-sync.importer.enabled=true",
                        "fpl.owned-sync.mode=STAGING",
                        "fpl.owned-sync.playing-season=2026-27",
                        "fpl.owned-sync.cutover-confirmed=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "Incremental importer requires fpl.owned-sync.mode=ACTIVE");
                });
    }

    @Test
    void hourlyRuntimeRunsOneCycleImmediatelyAndUsesAnHourlyFixedDelay() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        AtomicInteger maintenanceRuns = new AtomicInteger();
        CountDownLatch firstRun = new CountDownLatch(1);
        CountDownLatch maintenanceRun = new CountDownLatch(2);
        CoalescedPlayerHistoryImportEntrypoint entrypoint = () -> {
            runs.incrementAndGet();
            firstRun.countDown();
            return new CoalescedPlayerHistoryCollector.CycleResult(
                    CoalescedPlayerHistoryCollector.State.COMPLETED,
                    java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), null);
        };
        CoalescedPlayerHistoryImportRuntime runtime = new CoalescedPlayerHistoryImportRuntime(entrypoint, () -> {
            maintenanceRuns.incrementAndGet();
            maintenanceRun.countDown();
            return 0;
        });
        try {
            assertThat(CoalescedPlayerHistoryImportRuntime.INTERVAL).isEqualTo(Duration.ofHours(1));
            runtime.start();
            assertThat(firstRun.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(maintenanceRun.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(runs).hasValue(1);
            assertThat(maintenanceRuns).hasValue(2);
        } finally {
            runtime.stop();
        }
        assertThat(runtime.isRunning()).isFalse();
    }

    @Test
    void scheduledImportFailureStillRunsMaintenance() throws Exception {
        AtomicInteger maintenanceRuns = new AtomicInteger();
        CountDownLatch maintenanceRun = new CountDownLatch(2);
        CoalescedPlayerHistoryImportEntrypoint entrypoint = () -> {
            throw new IllegalStateException("controlled import failure");
        };
        CoalescedPlayerHistoryImportRuntime runtime = new CoalescedPlayerHistoryImportRuntime(entrypoint, () -> {
            maintenanceRuns.incrementAndGet();
            maintenanceRun.countDown();
            return 0;
        });
        try {
            runtime.start();
            assertThat(maintenanceRun.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(maintenanceRuns).hasValue(2);
        } finally {
            runtime.stop();
        }
        assertThat(runtime.isRunning()).isFalse();
    }

    @Test
    void manualImportExceptionIsNotReplacedByMaintenanceFailure() {
        AtomicInteger maintenanceRuns = new AtomicInteger();
        CoalescedPlayerHistoryImportEntrypoint entrypoint = () -> {
            throw new IllegalStateException("controlled import failure");
        };
        CoalescedPlayerHistoryImportRuntime runtime = new CoalescedPlayerHistoryImportRuntime(entrypoint, () -> {
            maintenanceRuns.incrementAndGet();
            throw new IllegalStateException("controlled cleanup failure");
        });

        assertThatThrownBy(runtime::runOnce).hasMessage("controlled import failure");
        assertThat(maintenanceRuns).hasValue(1);
    }

    @Test
    void legacyCurrentSeasonCliIsBlockedOnlyAfterActiveCutover() {
        var arguments = new DefaultApplicationArguments("--sync-schedule");
        assertThatThrownBy(() -> OwnedSyncRuntimeConfiguration.validateLegacyArguments(
                OwnedSyncMode.ACTIVE, arguments)).hasMessageContaining("disabled");
        OwnedSyncRuntimeConfiguration.validateLegacyArguments(OwnedSyncMode.STAGING, arguments);
        OwnedSyncRuntimeConfiguration.validateLegacyArguments(OwnedSyncMode.OFF, arguments);
        OwnedSyncRuntimeConfiguration.validateLegacyArguments(OwnedSyncMode.ACTIVE,
                new DefaultApplicationArguments("--backfill", "--backfill-defcon=2025-26"));
    }
}
