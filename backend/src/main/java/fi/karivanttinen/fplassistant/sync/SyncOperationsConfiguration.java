package fi.karivanttinen.fplassistant.sync;

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
        OwnedSyncWatchdog.Notification notification = notification(environment);
        return new SyncOperations(database.jdbc(), database.transaction(), Clock.systemUTC(),
                OwnedSyncTables.defaults(), mode, monitorEnabled && mode != OwnedSyncMode.OFF,
                notification.enabled() && notification.configured(), notification.externalMonitor());
    }

    static OwnedSyncWatchdog.Notification notification(Environment environment) {
        boolean external = environment.getProperty(
                "fpl.owned-sync.alerts.via-monitor", Boolean.class, false);
        if (external && (!environment.getProperty(
                "fpl.owned-sync.external-health.enabled", Boolean.class, false)
                || environment.getProperty("fpl.owned-sync.external-health.token", "").isBlank())) {
            throw new IllegalArgumentException("Monitor notification requires authenticated external health");
        }
        return new OwnedSyncWatchdog.Notification(environment.getProperty(
                "fpl.owned-sync.alerts.webhook-enabled", Boolean.class, false),
                !environment.getProperty("fpl.owned-sync.alerts.webhook-url", "").isBlank(), external);
    }
}
