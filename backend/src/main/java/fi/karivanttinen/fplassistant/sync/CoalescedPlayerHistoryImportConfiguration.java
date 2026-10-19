package fi.karivanttinen.fplassistant.sync;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.Season;

/** Guarded opt-in wiring; this path only attaches to an already ACTIVE owned-sync epoch. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "fpl.owned-sync.importer.enabled", havingValue = "true")
public class CoalescedPlayerHistoryImportConfiguration {

    @Bean
    ImporterActivation ownedImporterActivation(Environment environment) {
        String mode = environment.getProperty("fpl.owned-sync.mode", "OFF");
        String season = environment.getProperty("fpl.owned-sync.playing-season", "").trim();
        boolean cutover = environment.getProperty(
                "fpl.owned-sync.cutover-confirmed", Boolean.class, false);
        return validateActivation(mode, season, cutover);
    }

    @Bean
    @ConditionalOnMissingBean(OwnedSyncTables.class)
    OwnedSyncTables ownedImporterTables() {
        return OwnedSyncTables.defaults();
    }

    @Bean(destroyMethod = "close")
    OwnedSyncMonitoringDatabase ownedSyncWorkDatabase(Environment environment, ImporterActivation activation) {
        return OwnedSyncMonitoringDatabase.work(environment);
    }

    static OwnedSyncRequestBudget.Limits requestLimits(Environment environment) {
        return OwnedSyncRequestBudget.Limits.defaults(environment.getProperty(
                "fpl.owned-sync.importer.max-attempts-per-day", Integer.class, 5_000));
    }

    @Bean
    OwnedSyncRequestBudget ownedImporterRequestBudget(
            @Qualifier("ownedSyncWorkDatabase") OwnedSyncMonitoringDatabase workDatabase,
            OwnedSyncTables tables, Environment environment) {
        return new OwnedSyncRequestBudget(workDatabase.jdbc(), workDatabase.transaction(), Clock.systemUTC(), tables,
                requestLimits(environment));
    }

    @Bean(destroyMethod = "close")
    OwnedSyncHttpClient ownedImporterHttpClient(OwnedSyncRequestBudget budget) {
        return new OwnedSyncHttpClient(budget);
    }

    @Bean
    OwnedSyncStore.RuntimeFence ownedImporterFence(
            @Qualifier("ownedSyncWorkDatabase") OwnedSyncMonitoringDatabase workDatabase,
            ImporterActivation activation, OwnedSyncTables tables) {
        return existingActiveFence(workDatabase.jdbc(), tables, activation.playingSeason());
    }

    @Bean
    CoalescedPlayerHistoryCollector ownedImporterCollector(
            @Qualifier("ownedSyncWorkDatabase") OwnedSyncMonitoringDatabase workDatabase,
            OwnedSyncHttpClient http, ImporterActivation activation, OwnedSyncTables tables,
            OwnedSyncStore.RuntimeFence ownedImporterFence,
            @Value("${fpl.daily-price.table-name}") String dailyTable,
            @Value("${fpl.schedule.gameweek-table-name}") String gameweekTable,
            @Value("${fpl.schedule.fixture-table-name}") String fixtureTable) {
        JdbcTemplate jdbc = workDatabase.jdbc();
        new IncrementalImporterSchemaPreflight(jdbc, tables).requireReady();
        Clock clock = Clock.systemUTC();
        Season season = OwnedSyncRuntimeConfiguration.configuredSeason(jdbc, activation.playingSeason());
        OwnedSyncStore store = new OwnedSyncStore(jdbc, workDatabase.transaction(), clock, tables,
                ownedImporterFence);
        IncrementalPlayerFixturePublisher publisher = new IncrementalPlayerFixturePublisher(
                new IncrementalPfsRepository(jdbc, clock, store, tables));
        CompatibilityCatalogueWriter compatibility = new CompatibilityCatalogueWriter(
                jdbc, clock, store, tables, new CatalogueScheduleTables(dailyTable, gameweekTable, fixtureTable));
        return new CoalescedPlayerHistoryCollector(season, true, store, publisher, compatibility,
                http, clock, instanceId());
    }

    @Bean
    OwnedSyncWatchdog ownedImporterWatchdog(
            @Qualifier("ownedSyncMonitoringDatabase") OwnedSyncMonitoringDatabase monitoringDatabase,
            Environment environment, OwnedSyncTables tables) {
        OwnedSyncWatchdog.Notification notification = SyncOperationsConfiguration.notification(environment);
        String webhook = environment.getProperty("fpl.owned-sync.alerts.webhook-url", "").trim();
        OwnedSyncWatchdog.AlertSender sender = notification.enabled() && notification.configured()
                ? new OwnedSyncWatchdog.HttpsWebhookSender(webhook) : payload -> { };
        return new OwnedSyncWatchdog(monitoringDatabase.jdbc(), monitoringDatabase.transaction(),
                Clock.systemUTC(), tables, notification, sender, requestLimits(environment));
    }

    @Bean
    CoalescedPlayerHistoryImportRuntime ownedImporterHourlyRuntime(
            CoalescedPlayerHistoryImportEntrypoint collector,
            CoalescedPlayerHistoryCollector ownedImporterCollector,
            @Qualifier("ownedSyncWorkDatabase") OwnedSyncMonitoringDatabase workDatabase,
            OwnedSyncWatchdog ownedImporterWatchdog, OwnedSyncTables tables,
            OwnedSyncStore.RuntimeFence ownedImporterFence) {
        PlannerRuntimeRepository runtime = new PlannerRuntimeRepository(workDatabase.jdbc(),
                workDatabase.transaction(), Clock.systemUTC(), tables, ownedImporterFence);
        String instanceId = "importer:" + instanceId();
        return new CoalescedPlayerHistoryImportRuntime(collector, ownedImporterCollector::pruneTerminalPayloads,
                () -> runtime.heartbeat(instanceId),
                () -> ownedImporterWatchdog.checkImporter(ownedImporterFence.season()),
                ownedImporterWatchdog::deliver);
    }

    static ImporterActivation validateActivation(String configuredMode, String configuredSeason,
                                                  boolean cutoverConfirmed) {
        OwnedSyncMode mode = OwnedSyncMode.valueOf(configuredMode.trim().toUpperCase(Locale.ROOT));
        if (mode != OwnedSyncMode.ACTIVE) {
            throw new IllegalStateException("Incremental importer requires fpl.owned-sync.mode=ACTIVE");
        }
        if (configuredSeason.isBlank()) {
            throw new IllegalStateException("Incremental importer requires an explicit playing-season");
        }
        OwnedSyncRuntimeConfiguration.validateActivation(mode, configuredSeason, cutoverConfirmed);
        return new ImporterActivation(configuredSeason);
    }

    static OwnedSyncStore.RuntimeFence existingActiveFence(JdbcTemplate jdbc, OwnedSyncTables tables,
                                                            String season) {
        List<RuntimeActivation> rows = jdbc.query("""
                SELECT runtime_mode, runtime_epoch, activation_boundary
                FROM %s WHERE instance_id=?
                """.formatted(tables.plannerRuntime()), (rs, row) -> new RuntimeActivation(
                rs.getString("runtime_mode"), rs.getLong("runtime_epoch"),
                rs.getTimestamp("activation_boundary") == null ? null
                        : rs.getTimestamp("activation_boundary").toInstant()), "mode:" + season);
        if (rows.size() != 1 || !OwnedSyncMode.ACTIVE.name().equals(rows.getFirst().mode())
                || rows.getFirst().epoch() <= 0 || rows.getFirst().activationBoundary() == null) {
            throw new IllegalStateException("Incremental importer requires an already ACTIVE owned-sync runtime "
                    + "for the configured playing season; it does not activate or change modes");
        }
        RuntimeActivation active = rows.getFirst();
        return new OwnedSyncStore.RuntimeFence(season, OwnedSyncMode.ACTIVE,
                active.epoch(), active.activationBoundary());
    }

    private static String instanceId() {
        try {
            return InetAddress.getLocalHost().getHostName() + ":importer:" + UUID.randomUUID();
        } catch (Exception ignored) {
            return "owned-sync-importer:" + UUID.randomUUID();
        }
    }

    record ImporterActivation(String playingSeason) { }
    private record RuntimeActivation(String mode, long epoch, Instant activationBoundary) { }
}
