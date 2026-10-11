package dev.quantti.fplassistant.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
    void importerGuardExcludesLegacyCoreLoopWithoutChangingDefaultActiveRouting() {
        assertThat(OwnedSyncRuntimeConfiguration.legacyRuntimeEnabled("ACTIVE", false)).isTrue();
        assertThat(OwnedSyncRuntimeConfiguration.legacyRuntimeEnabled("ACTIVE", true)).isFalse();
        assertThat(OwnedSyncRuntimeConfiguration.legacyRuntimeEnabled("OFF", false)).isFalse();
    }

    @Test
    void invalidOptInFailsBeforeDatabaseAccess() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        new ApplicationContextRunner()
                .withBean(JdbcTemplate.class, () -> jdbc)
                .withUserConfiguration(CoalescedPlayerHistoryImportConfiguration.class)
                .withPropertyValues("fpl.owned-sync.importer.enabled=true",
                        "fpl.owned-sync.mode=STAGING",
                        "fpl.owned-sync.playing-season=2026-27",
                        "fpl.owned-sync.cutover-confirmed=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "Incremental importer requires fpl.owned-sync.mode=ACTIVE");
                    verify(jdbc, never()).execute(anyString());
                });
    }

    @Test
    void completeImporterOptInStillFailsClosedUntilTheCompatibilityWriterExists() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        new ApplicationContextRunner()
                .withBean(JdbcTemplate.class, () -> jdbc)
                .withUserConfiguration(OwnedSyncRuntimeConfiguration.class,
                        OwnedSyncMonitoringDatabase.DatabaseConfiguration.class,
                        CoalescedPlayerHistoryImportConfiguration.class)
                .withPropertyValues("fpl.owned-sync.importer.enabled=true",
                        "fpl.owned-sync.mode=ACTIVE",
                        "fpl.owned-sync.playing-season=2026-27",
                        "fpl.owned-sync.cutover-confirmed=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "Incremental importer remains inert until a changed-only compatibility writer "
                                    + "publishes team, player, gameweek and fixture rows without writing daily capture tables");
                    assertThat(OwnedSyncRuntimeConfiguration.legacyRuntimeEnabled("ACTIVE", true)).isFalse();
                    verify(jdbc, never()).execute(anyString());
                });
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
