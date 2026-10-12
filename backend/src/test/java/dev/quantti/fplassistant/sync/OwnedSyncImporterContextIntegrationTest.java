package dev.quantti.fplassistant.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import dev.quantti.fplassistant.testsupport.TapasSeed;

class OwnedSyncImporterContextIntegrationTest {

    private static final OwnedSyncTables TABLES = new OwnedSyncTables(
            "fpl_ctxrun_test", "fpl_ctxpayload_test", "fpl_ctxreceipt_test",
            "fpl_ctxrequest_state_test", "fpl_ctxrequest_attempt_test", "fpl_ctxfixture_test",
            "fpl_ctxpfs_evidence_test", "fpl_ctxplanner_slot_test", "fpl_ctxplanner_runtime_test",
            "fpl_ctxfixture_dep_test");
    private static final Instant ACTIVATED_AT = Instant.parse("2026-09-13T18:00:00Z");
    private static final AtomicInteger SAFE_CYCLES = new AtomicInteger();
    private static JdbcTemplate jdbc;
    private static List<Integer> originalCurrentSeasons;
    private static boolean disposableDatabaseValidated;
    private static boolean seasonBaselineCaptured;
    private static boolean seasonFlagsChanged;
    private static boolean cleanupAuthorized;

    @BeforeAll
    static void initialize() {
        String url = required("DATABASE_URL");
        String user = required("DB_USER");
        String password = required("DB_PASSWORD");
        initialize(url, user, password,
                () -> new JdbcTemplate(new DriverManagerDataSource(url, user, password)));
    }

    static void initialize(String url, String user, String password, Supplier<JdbcTemplate> jdbcSupplier) {
        jdbc = TapasSeed.openDisposableTestJdbc(url, user, password, jdbcSupplier);
        TapasSeed.ensure(jdbc);
        disposableDatabaseValidated = true;
        TapasSeed.ensureOwnedSyncCaptureSeason(jdbc);
        originalCurrentSeasons = jdbc.queryForList(
                "SELECT id FROM season WHERE is_current=true ORDER BY id", Integer.class);
        seasonBaselineCaptured = true;
        new OwnedSyncSchema(jdbc, TABLES).initialize();
        cleanupAuthorized = true;
    }

    @BeforeEach
    void seedAlreadyActiveRuntimeAndCleanOwnedRows() {
        TapasSeed.setCurrentSeason(jdbc, 27);
        seasonFlagsChanged = true;
        jdbc.execute("TRUNCATE " + TABLES.playerFixtureCurrent() + ", " + TABLES.playerFixtureEvidence() + ", "
                + TABLES.gameweekStatSync() + ", " + TABLES.fixtureSync() + ", "
                + TABLES.fixtureDependencyManifest() + ", " + TABLES.historyFingerprint() + ", "
                + TABLES.payload() + ", " + TABLES.run() + ", " + TABLES.receipt() + ", "
                + TABLES.plannerSlot() + ", " + TABLES.plannerRuntime() + ", "
                + TABLES.alert() + ", " + TABLES.requestAttempt() + ", " + TABLES.requestState()
                + " CASCADE");
        jdbc.update("INSERT INTO " + TABLES.requestState() + "(singleton) VALUES (TRUE)");
        jdbc.update("""
                INSERT INTO %s(instance_id,started_at,heartbeat_at,runtime_mode,runtime_epoch,activation_boundary)
                VALUES ('mode:2026-27',?,?, 'ACTIVE',7,?)
                """.formatted(TABLES.plannerRuntime()), Timestamp.from(ACTIVATED_AT),
                Timestamp.from(ACTIVATED_AT), Timestamp.from(ACTIVATED_AT));
        SAFE_CYCLES.set(0);
    }

    @AfterEach
    void restoreCurrentSeasons() {
        restoreCapturedCurrentSeasons();
    }

    @AfterAll
    static void restoreSeedSeason() {
        restoreCapturedCurrentSeasons();
    }

    private static void restoreCapturedCurrentSeasons() {
        if (!disposableDatabaseValidated || !seasonBaselineCaptured || !seasonFlagsChanged || jdbc == null) return;
        new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()))
                .executeWithoutResult(status -> {
                    jdbc.update("UPDATE season SET is_current=false");
                    originalCurrentSeasons.forEach(id ->
                            jdbc.update("UPDATE season SET is_current=true WHERE id=?", id));
                });
        seasonFlagsChanged = false;
    }

    @Test
    void defaultOffCreatesNeitherImporterNorMonitoringRuntimeAndTouchesNoOwnedRows() {
        defaultOffRunner().run(context -> assertThat(context).hasNotFailed()
                .doesNotHaveBean("ownedSyncWorkDatabase")
                .doesNotHaveBean("ownedSyncMonitoringDatabase")
                .doesNotHaveBean(CoalescedPlayerHistoryCollector.class)
                .doesNotHaveBean(CoalescedPlayerHistoryImportRuntime.class)
                .doesNotHaveBean(OwnedMatchdayRuntime.class)
                .doesNotHaveBean(OwnedSyncWatchdog.class)
                .doesNotHaveBean(SyncOperations.class)
                .doesNotHaveBean(SyncOperationsController.class));

        assertThat(SAFE_CYCLES).hasValue(0);
        assertThat(jdbc.queryForObject("SELECT runtime_epoch FROM " + TABLES.plannerRuntime()
                + " WHERE instance_id='mode:2026-27'", Long.class)).isEqualTo(7L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run(), Integer.class)).isZero();
    }

    @Test
    void drainedOffEpochNeedsExplicitOperatorHandoffBeforeImporterCanStart() {
        var old = new OwnedSyncStore.RuntimeFence("2026-27", OwnedSyncMode.ACTIVE, 7, ACTIVATED_AT);
        var tx = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        new PlannerRuntimeRepository(jdbc, tx, java.time.Clock.systemUTC(), TABLES).deactivate(old);
        assertThatThrownBy(() -> CoalescedPlayerHistoryImportConfiguration.existingActiveFence(
                jdbc, TABLES, "2026-27")).isInstanceOf(IllegalStateException.class);
        handoffRunner().run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(CoalescedPlayerHistoryCollector.class);
            SyncOperations ops = context.getBean(SyncOperations.class);
            assertThatThrownBy(() -> ops.handoffImporter("2026-27", 7))
                    .isInstanceOf(SyncOperations.StaleOperatorRequestException.class);
            assertThatThrownBy(() -> ops.handoffImporter("2025-26", 8))
                    .isInstanceOf(IllegalArgumentException.class);
            jdbc.update("INSERT INTO " + TABLES.plannerRuntime()
                    + "(instance_id,started_at,heartbeat_at) VALUES ('old-worker',NOW(),NOW())");
            assertThatThrownBy(() -> ops.handoffImporter("2026-27", 8))
                    .isInstanceOf(SyncOperations.StaleOperatorRequestException.class);
            jdbc.update("DELETE FROM " + TABLES.plannerRuntime() + " WHERE instance_id='old-worker'");
            jdbc.update("INSERT INTO " + TABLES.receipt()
                    + "(season,publication_key) VALUES ('2026-27','old-pending')");
            jdbc.update("INSERT INTO " + TABLES.run()
                    + "(kind,season,coalescing_key,publication_key,priority,due_at,requested_source_after,"
                    + "runtime_mode,runtime_epoch,created_at,updated_at) VALUES "
                    + "('PLAYER_HISTORY','2026-27','old-pending','old-pending',0,NOW(),NOW(),'ACTIVE',7,NOW(),NOW())");
            assertThatThrownBy(() -> ops.handoffImporter("2026-27", 8))
                    .isInstanceOf(SyncOperations.StaleOperatorRequestException.class);
            jdbc.update("DELETE FROM " + TABLES.run() + " WHERE publication_key='old-pending'");
            assertThat(ops.handoffImporter("2026-27", 8)).isEqualTo(9);
            assertThatThrownBy(() -> new PlannerRuntimeRepository(jdbc, tx,
                    java.time.Clock.systemUTC(), TABLES).activate("2026-27", OwnedSyncMode.ACTIVE))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> ops.handoffImporter("2026-27", 8))
                    .isInstanceOf(SyncOperations.StaleOperatorRequestException.class);
        });
        assertThat(CoalescedPlayerHistoryImportConfiguration.existingActiveFence(
                jdbc, TABLES, "2026-27").epoch()).isEqualTo(9);
        runner().run(context -> assertThat(context).hasNotFailed()
                .hasBean("ownedImporterCollector"));
        handoffRunner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(SyncOperations.class).retireImporter("2026-27", 9))
                    .isEqualTo(10);
        });
        assertThatThrownBy(() -> CoalescedPlayerHistoryImportConfiguration.existingActiveFence(
                jdbc, TABLES, "2026-27")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void opsWithoutExplicitHandoffSettingCannotActivateAnImporter() {
        handoffRunner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThatThrownBy(() -> context.getBean(SyncOperations.class)
                    .retireImporter("2026-27", 7))
                    .isInstanceOf(SyncOperations.StaleOperatorRequestException.class);
        });
        defaultOffRunner().withBean(OwnedSyncTables.class, () -> TABLES)
                .withPropertyValues("fpl.owned-sync.ops.enabled=true",
                        "fpl.owned-sync.playing-season=2026-27",
                        "fpl.owned-sync.cutover-confirmed=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThatThrownBy(() -> context.getBean(SyncOperations.class)
                            .handoffImporter("2026-27", 8))
                            .isInstanceOf(SyncOperations.StaleOperatorRequestException.class);
                });
        assertThat(jdbc.queryForObject("SELECT runtime_epoch FROM " + TABLES.plannerRuntime()
                + " WHERE instance_id='mode:2026-27'", Long.class)).isEqualTo(7);
    }

    @Test
    void importerAndExternalHealthStartWithSeparatePoolsWithoutActivatingOrInitializing() {
        runner().run(context -> {
            assertThat(context).hasNotFailed()
                    .hasBean("ownedSyncWorkDatabase")
                    .hasBean("ownedSyncMonitoringDatabase")
                    .hasBean("ownedImporterCollector")
                    .hasBean("ownedImporterHourlyRuntime")
                    .hasBean("syncOperations")
                    .hasBean("syncOperationsController")
                    .doesNotHaveBean(OwnedMatchdayRuntime.class)
                    .doesNotHaveBean(OwnedMatchdayCoordinator.class)
                    .hasBean("ownedImporterWatchdog")
                    .doesNotHaveBean(HistoryFixtureDispatcher.class)
                    .doesNotHaveBean(CatalogueScheduleDispatcher.class);
            OwnedSyncMonitoringDatabase work = context.getBean("ownedSyncWorkDatabase",
                    OwnedSyncMonitoringDatabase.class);
            OwnedSyncMonitoringDatabase monitor = context.getBean("ownedSyncMonitoringDatabase",
                    OwnedSyncMonitoringDatabase.class);
            assertThat(work.dataSource()).isNotSameAs(monitor.dataSource());
            assertThat(work.dataSource().getPoolName()).isEqualTo("owned-sync-work");
            assertThat(monitor.dataSource().getPoolName()).isEqualTo("owned-sync-monitor");
            SyncOperations operations = context.getBean(SyncOperations.class);
            Instant cutoff = Instant.now().minusSeconds(600);
            for (int i = 0; i < 50 && jdbc.queryForObject("SELECT count(*) FROM "
                    + TABLES.plannerRuntime() + " WHERE instance_id LIKE 'importer:%' AND heartbeat_at>?",
                    Integer.class, Timestamp.from(cutoff)) == 0; i++) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.plannerRuntime()
                    + " WHERE instance_id LIKE 'importer:%' AND heartbeat_at>?", Integer.class,
                    Timestamp.from(cutoff))).isOne();
            context.getBean(OwnedSyncWatchdog.class).checkImporter("2026-27");
            assertThat(operations.healthy()).isTrue();
            jdbc.update("UPDATE " + TABLES.plannerRuntime() + " SET heartbeat_at=?"
                    + " WHERE instance_id LIKE 'importer:%'", Timestamp.from(cutoff.minusSeconds(1)));
            assertThat(operations.healthy()).isFalse();
        });

        assertThat(jdbc.queryForMap("SELECT runtime_mode,runtime_epoch,activation_boundary FROM "
                + TABLES.plannerRuntime() + " WHERE instance_id='mode:2026-27'"))
                .containsEntry("runtime_mode", "ACTIVE")
                .containsEntry("runtime_epoch", 7L)
                .containsEntry("activation_boundary", Timestamp.from(ACTIVATED_AT));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run(), Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.requestAttempt(), Integer.class)).isZero();
    }

    @Test
    void missingTypedSupersedeReasonColumnFailsOptInPreflightBeforeCollectorStarts() {
        jdbc.execute("ALTER TABLE " + TABLES.run() + " DROP CONSTRAINT IF EXISTS ck_" + TABLES.run()
                + "_supersede_reason");
        jdbc.execute("ALTER TABLE " + TABLES.run() + " DROP COLUMN IF EXISTS supersede_reason");
        try {
            runner().run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure().toString())
                        .contains(TABLES.run() + ".supersede_reason")
                        .contains("Install the owned-sync schema migration");
            });
            assertThat(SAFE_CYCLES).hasValue(0);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.requestAttempt(), Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run(), Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT runtime_epoch FROM " + TABLES.plannerRuntime()
                    + " WHERE instance_id='mode:2026-27'", Long.class)).isEqualTo(7L);
        } finally {
            new OwnedSyncSchema(jdbc, TABLES).initialize();
        }
    }

    @Test
    void missingPointerTableOrFinalityCheckpointColumnFailsWithoutSchemaRepair() {
        jdbc.execute("DROP TABLE " + TABLES.playerFixtureCurrent() + " CASCADE");
        runner().run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure().toString())
                    .contains(TABLES.playerFixtureCurrent() + ".season_id")
                    .contains("Install the owned-sync schema migration");
        });
        assertThat(jdbc.queryForObject("SELECT to_regclass(?) IS NULL", Boolean.class,
                "public." + TABLES.playerFixtureCurrent())).isTrue();
        assertThat(jdbc.queryForObject("SELECT runtime_epoch FROM " + TABLES.plannerRuntime()
                + " WHERE instance_id='mode:2026-27'", Long.class)).isEqualTo(7L);
        new OwnedSyncSchema(jdbc, TABLES).initialize();

        jdbc.execute("ALTER TABLE " + TABLES.gameweekStatSync()
                + " RENAME COLUMN final_attempt_completed_player_ids TO completed_ids_legacy");
        runner().run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure().toString())
                    .contains(TABLES.gameweekStatSync() + ".final_attempt_completed_player_ids");
        });
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema='public' AND table_name=? AND column_name='final_attempt_completed_player_ids'",
                String.class, TABLES.gameweekStatSync())).isEmpty();
        jdbc.execute("ALTER TABLE " + TABLES.gameweekStatSync()
                + " RENAME COLUMN completed_ids_legacy TO final_attempt_completed_player_ids");
        assertThat(SAFE_CYCLES).hasValue(0);
    }

    private ApplicationContextRunner defaultOffRunner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(OwnedSyncRuntimeConfiguration.class,
                        CoalescedPlayerHistoryImportConfiguration.class,
                        OwnedSyncMonitoringDatabase.DatabaseConfiguration.class,
                        SyncOperationsConfiguration.class, SyncOperationsController.class)
                .withPropertyValues(
                        "spring.datasource.url=" + required("DATABASE_URL"),
                        "spring.datasource.username=" + required("DB_USER"),
                        "spring.datasource.password=" + required("DB_PASSWORD"));
    }

    private ApplicationContextRunner handoffRunner() {
        return defaultOffRunner().withBean(OwnedSyncTables.class, () -> TABLES)
                .withPropertyValues("fpl.owned-sync.ops.enabled=true",
                        "fpl.owned-sync.handoff.enabled=true",
                        "fpl.owned-sync.playing-season=2026-27",
                        "fpl.owned-sync.cutover-confirmed=true");
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(OwnedSyncRuntimeConfiguration.class,
                        CoalescedPlayerHistoryImportConfiguration.class,
                        OwnedSyncMonitoringDatabase.DatabaseConfiguration.class,
                        SyncOperationsConfiguration.class, SyncOperationsController.class,
                        SafeLifecycleConfiguration.class)
                .withBean(OwnedSyncTables.class, () -> TABLES)
                .withPropertyValues(
                        "spring.datasource.url=" + required("DATABASE_URL"),
                        "spring.datasource.username=" + required("DB_USER"),
                        "spring.datasource.password=" + required("DB_PASSWORD"),
                        "fpl.owned-sync.importer.enabled=true",
                        "fpl.owned-sync.mode=ACTIVE",
                        "fpl.owned-sync.playing-season=2026-27",
                        "fpl.owned-sync.cutover-confirmed=true",
                        "fpl.owned-sync.ops.enabled=true",
                        "fpl.owned-sync.external-health.enabled=true",
                        "fpl.owned-sync.external-health.token=context-test-token",
                        "fpl.owned-sync.alerts.via-monitor=true");
    }

    @Configuration(proxyBeanMethods = false)
    static class SafeLifecycleConfiguration {
        @Bean
        @Primary
        CoalescedPlayerHistoryImportEntrypoint ownedTestImportEntrypoint() {
            return () -> {
                SAFE_CYCLES.incrementAndGet();
                return new CoalescedPlayerHistoryCollector.CycleResult(
                        CoalescedPlayerHistoryCollector.State.COMPLETED,
                        List.of(), List.of(), List.of(), List.of(), null);
            };
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
