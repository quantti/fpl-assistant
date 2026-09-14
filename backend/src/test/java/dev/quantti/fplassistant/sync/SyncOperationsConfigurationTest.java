package dev.quantti.fplassistant.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class SyncOperationsConfigurationTest {

    @Test
    void notificationDelegationIsExplicitAndDoesNotClaimDelivery() {
        assertThat(SyncOperationsConfiguration.notification(new MockEnvironment()).state()).isEqualTo("DISABLED");
        var notification = SyncOperationsConfiguration.notification(monitorEnvironment());
        assertThat(notification.state()).isEqualTo("EXTERNAL");
        assertThat(notification.disabledReason()).contains("cannot attest delivery");
    }

    @Test
    void monitorModeRequiresAnEnabledAuthenticatedHealthEndpoint() {
        var environment = new MockEnvironment().withProperty("fpl.owned-sync.alerts.via-monitor", "true");
        assertThatThrownBy(() -> SyncOperationsConfiguration.notification(environment))
                .isInstanceOf(IllegalArgumentException.class);
        environment.withProperty("fpl.owned-sync.external-health.enabled", "true");
        assertThatThrownBy(() -> SyncOperationsConfiguration.notification(environment))
                .isInstanceOf(IllegalArgumentException.class);
        environment.withProperty("fpl.owned-sync.external-health.token", " ");
        assertThatThrownBy(() -> SyncOperationsConfiguration.notification(environment))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void webhookAndMonitorCannotBothOwnNotificationDelivery() {
        assertThatThrownBy(() -> SyncOperationsConfiguration.notification(monitorEnvironment()
                .withProperty("fpl.owned-sync.alerts.webhook-enabled", "true")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static MockEnvironment monitorEnvironment() {
        return new MockEnvironment().withProperty("fpl.owned-sync.alerts.via-monitor", "true")
                .withProperty("fpl.owned-sync.external-health.enabled", "true")
                .withProperty("fpl.owned-sync.external-health.token", "test-monitor-token");
    }
}
