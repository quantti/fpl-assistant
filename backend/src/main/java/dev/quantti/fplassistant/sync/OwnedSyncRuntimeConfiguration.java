package dev.quantti.fplassistant.sync;

import java.net.InetAddress;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import dev.quantti.fplassistant.sync.CatalogueScheduleParser.Season;

/** Creates the owned runtime only for an explicit STAGING or guarded ACTIVE mode. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("T(dev.quantti.fplassistant.sync.OwnedSyncRuntimeConfiguration).legacyRuntimeEnabled('${fpl.owned-sync.mode:OFF}', ${fpl.owned-sync.importer.enabled:false})")
public class OwnedSyncRuntimeConfiguration {

    @Bean(destroyMethod = "close")
    OwnedSyncMonitoringDatabase ownedSyncWorkDatabase(Environment environment) {
        return OwnedSyncMonitoringDatabase.work(environment);
    }

    @Bean
    OwnedMatchdayRuntime ownedMatchdayRuntime(
            @Qualifier("ownedSyncMonitoringDatabase") OwnedSyncMonitoringDatabase monitoringDatabase,
            @Qualifier("ownedSyncWorkDatabase") OwnedSyncMonitoringDatabase workDatabase,
            Environment environment,
            ApplicationArguments arguments) {
        JdbcTemplate jdbc = workDatabase.jdbc();
        OwnedSyncMode mode = OwnedSyncMode.valueOf(environment.getProperty(
                "fpl.owned-sync.mode", "OFF").trim().toUpperCase());
        String configured = environment.getProperty("fpl.owned-sync.playing-season", "").trim();
        boolean cutover = environment.getProperty(
                "fpl.owned-sync.cutover-confirmed", Boolean.class, false);
        validateActivation(mode, configured, cutover);
        validateLegacyArguments(mode, arguments);
        OwnedSyncWatchdog.Notification notification = SyncOperationsConfiguration.notification(environment);
        Season season = configuredSeason(jdbc, configured);

        Clock clock = Clock.systemUTC();
        OwnedSyncTables owned = OwnedSyncTables.defaults();
        CatalogueScheduleTables captured = new CatalogueScheduleTables(
                environment.getRequiredProperty("fpl.daily-price.table-name"),
                environment.getRequiredProperty("fpl.schedule.gameweek-table-name"),
                environment.getRequiredProperty("fpl.schedule.fixture-table-name"));
        new OwnedSyncSchema(jdbc, owned).initialize();

        var tx = workDatabase.transaction();
        PlannerRuntimeRepository activationRepository = new PlannerRuntimeRepository(jdbc, tx, clock, owned);
        OwnedSyncStore.RuntimeFence runtimeFence = activationRepository.activate(season.code(), mode);
        PlannerRuntimeRepository runtimeRepository = new PlannerRuntimeRepository(
                jdbc, tx, clock, owned, runtimeFence);
        OwnedSyncStore store = new OwnedSyncStore(jdbc, tx, clock, owned, runtimeFence);
        OwnedSyncRequestBudget budget = new OwnedSyncRequestBudget(jdbc, tx, clock, owned);
        OwnedSyncHttpClient http = new OwnedSyncHttpClient(budget);
        PlannerInputRepository inputRepository = new PlannerInputRepository(
                jdbc, store, owned, captured, clock);

        CatalogueScheduleDispatcher catalogue;
        HistoryFixtureDispatcher histories;
        if (mode == OwnedSyncMode.ACTIVE) {
            FixtureSyncRepository fixtureRepository = new FixtureSyncRepository(jdbc, owned);
            CatalogueScheduleWriter writer = new CatalogueScheduleWriter(jdbc, clock, store, owned, captured);
            FixtureSyncPublisher publisher = new FixtureSyncPublisher(
                    jdbc, clock, store, fixtureRepository, owned);
            catalogue = new CatalogueScheduleDispatcher(store, writer, http, clock);
            histories = new HistoryFixtureDispatcher(store, publisher, http, clock);
        } else {
            catalogue = CatalogueScheduleDispatcher.staging(store, http, clock);
            histories = HistoryFixtureDispatcher.staging(store, http, clock);
        }

        String instanceId = instanceId();
        OwnedMatchdayCoordinator coordinator = new OwnedMatchdayCoordinator(season,
                new AdaptiveMatchdayPlanner(clock), inputRepository, runtimeRepository, store,
                catalogue, histories, instanceId);
        String webhook = environment.getProperty("fpl.owned-sync.alerts.webhook-url", "").trim();
        OwnedSyncWatchdog.AlertSender sender = notification.enabled() && notification.configured()
                ? new OwnedSyncWatchdog.HttpsWebhookSender(webhook)
                : payload -> { };
        OwnedSyncWatchdog watchdog = new OwnedSyncWatchdog(monitoringDatabase.jdbc(),
                monitoringDatabase.transaction(), clock, owned, notification, sender);
        return new OwnedMatchdayRuntime(coordinator, runtimeRepository, http, instanceId, runtimeFence,
                watchdog, season.code());
    }

    static void validateActivation(OwnedSyncMode mode, String configured, boolean cutover) {
        if (mode == OwnedSyncMode.ACTIVE && configured.isEmpty()) {
            throw new IllegalStateException("ACTIVE owned sync requires an explicit playing-season");
        }
        if (mode == OwnedSyncMode.ACTIVE && !cutover) {
            throw new IllegalStateException("ACTIVE owned sync requires cutover-confirmed=true");
        }
    }

    public static boolean legacyRuntimeEnabled(String configuredMode, boolean importerEnabled) {
        return OwnedSyncMode.valueOf(configuredMode.trim().toUpperCase(Locale.ROOT)) != OwnedSyncMode.OFF
                && !importerEnabled;
    }

    static void validateLegacyArguments(OwnedSyncMode mode, ApplicationArguments arguments) {
        if (mode != OwnedSyncMode.ACTIVE) return;
        for (String option : List.of("sync-prices", "sync-schedule", "sync-defcon")) {
            if (arguments.containsOption(option)) {
                throw new IllegalStateException("Legacy --" + option + " is disabled in owned-sync ACTIVE mode");
            }
        }
    }

    private static Season configuredSeason(JdbcTemplate jdbc, String configured) {
        List<Season> rows = jdbc.query("""
                SELECT id, code, start_date, end_date FROM season
                WHERE is_current=true AND (?='' OR code=?)
                """, (rs, row) -> new Season(rs.getInt("id"), rs.getString("code"),
                rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class)),
                configured, configured);
        if (rows.size() != 1) {
            throw new IllegalStateException("Owned sync requires exactly one matching database playing season");
        }
        return rows.getFirst();
    }

    private static String instanceId() {
        try {
            return InetAddress.getLocalHost().getHostName() + ":" + UUID.randomUUID();
        } catch (Exception ignored) {
            return "owned-sync:" + UUID.randomUUID();
        }
    }
}
