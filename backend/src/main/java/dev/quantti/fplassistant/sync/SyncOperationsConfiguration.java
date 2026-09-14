package dev.quantti.fplassistant.sync;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Opt-in read/operation endpoints. Construction performs no schema write or external request. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("${fpl.owned-sync.ops.enabled:false} || ${fpl.owned-sync.external-health.enabled:false}")
public class SyncOperationsConfiguration {

    @Bean
    SyncOperations syncOperations(OwnedSyncMonitoringDatabase database, Environment environment) {
        OwnedSyncMode mode = OwnedSyncMode.valueOf(environment.getProperty(
                "fpl.owned-sync.mode", "OFF").trim().toUpperCase());
        boolean monitorEnabled = environment.getProperty(
                "fpl.owned-sync.external-health.enabled", Boolean.class, false);
        boolean webhookEnabled = environment.getProperty(
                "fpl.owned-sync.alerts.webhook-enabled", Boolean.class, false);
        boolean webhookConfigured = !environment.getProperty(
                "fpl.owned-sync.alerts.webhook-url", "").trim().isEmpty();
        return new SyncOperations(database.jdbc(), database.transaction(), Clock.systemUTC(),
                OwnedSyncTables.defaults(), mode, monitorEnabled && mode != OwnedSyncMode.OFF,
                webhookEnabled && webhookConfigured);
    }
}
