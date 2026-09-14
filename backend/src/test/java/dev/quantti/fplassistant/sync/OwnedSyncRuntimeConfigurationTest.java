package dev.quantti.fplassistant.sync;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

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
                        SyncOperationsConfiguration.class)
                .run(context -> {
                    org.assertj.core.api.Assertions.assertThat(context)
                            .doesNotHaveBean("ownedSyncWorkDatabase")
                            .doesNotHaveBean(OwnedMatchdayRuntime.class)
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
