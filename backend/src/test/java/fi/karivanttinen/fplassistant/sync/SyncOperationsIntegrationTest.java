package fi.karivanttinen.fplassistant.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class SyncOperationsIntegrationTest {

    private static final OwnedSyncTables TABLES = OwnedSyncTables.tests();
    private static final Instant NOW = Instant.parse("2098-02-03T12:00:00Z");
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static SyncOperations operations;

    @BeforeAll
    static void initialize() {
        DataSource source = new DriverManagerDataSource(required("DATABASE_URL"),
                required("DB_USER"), required("DB_PASSWORD"));
        jdbc = new JdbcTemplate(source);
        new OwnedSyncSchema(jdbc, TABLES).initialize();
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        operations = new SyncOperations(jdbc, tx, Clock.fixed(NOW, ZoneOffset.UTC),
                TABLES, OwnedSyncMode.ACTIVE);
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM " + TABLES.alert());
        jdbc.update("DELETE FROM " + TABLES.plannerSlot());
        jdbc.update("DELETE FROM " + TABLES.plannerRuntime());
        jdbc.update("DELETE FROM " + TABLES.payload());
        jdbc.update("DELETE FROM " + TABLES.run());
        jdbc.update("DELETE FROM " + TABLES.receipt());
    }

    @AfterEach
    void cleanUp() {
        reset();
    }

    @Test
    void retryIsAdditiveAndGuardedByCurrentNonOffLeaseEpoch() {
        long failed = failedRun(7, "ACTIVE");
        mode("ACTIVE", 7);

        long retry = operations.retry(failed, 7);

        assertThat(retry).isGreaterThan(failed);
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.run()
                + " WHERE generation=?", String.class, failed)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT runtime_epoch FROM " + TABLES.run()
                + " WHERE generation=?", Long.class, retry)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.run()
                + " WHERE generation=?", String.class, retry)).isEqualTo("PENDING");
    }

    @Test
    void staleEpochAndOffModeCannotWriteRetry() {
        long failed = failedRun(7, "ACTIVE");
        mode("ACTIVE", 8);
        assertThatThrownBy(() -> operations.retry(failed, 7))
                .isInstanceOf(SyncOperations.StaleOperatorRequestException.class);

        jdbc.update("UPDATE " + TABLES.plannerRuntime()
                + " SET runtime_mode='OFF',runtime_epoch=7 WHERE instance_id='mode:2026-27'");
        assertThatThrownBy(() -> operations.retry(failed, 7))
                .isInstanceOf(SyncOperations.StaleOperatorRequestException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.run(), Integer.class)).isOne();

        jdbc.update("UPDATE " + TABLES.plannerRuntime()
                + " SET runtime_mode='ACTIVE' WHERE instance_id='mode:2026-27'");
        SyncOperations localOff = new SyncOperations(jdbc, tx, Clock.fixed(NOW, ZoneOffset.UTC), TABLES);
        assertThatThrownBy(() -> localOff.retry(failed, 7))
                .isInstanceOf(SyncOperations.StaleOperatorRequestException.class)
                .hasMessageContaining("OFF");
    }

    @Test
    void exhaustedAlertRedriveIsAdditiveAndPreservesFailureEvidence() {
        long failed = jdbc.queryForObject("INSERT INTO " + TABLES.alert()
                        + "(incident_key,occurrence,event_type,category,severity,payload,delivery_state,"
                        + "delivery_attempts,last_delivery_error,created_at) VALUES "
                        + "('delivery',1,'INCIDENT','TEST','CRITICAL','{}'::jsonb,'FAILED',3,'refused',?) RETURNING id",
                Long.class, Timestamp.from(NOW));

        long redrive = operations.redriveAlert(failed);

        assertThat(redrive).isGreaterThan(failed);
        assertThat(jdbc.queryForObject("SELECT delivery_state FROM " + TABLES.alert()
                + " WHERE id=?", String.class, failed)).isEqualTo("SUPERSEDED");
        assertThat(jdbc.queryForObject("SELECT last_delivery_error FROM " + TABLES.alert()
                + " WHERE id=?", String.class, failed)).isEqualTo("refused");
        assertThat(jdbc.queryForObject("SELECT event_type FROM " + TABLES.alert()
                + " WHERE id=?", String.class, redrive)).isEqualTo("REDRIVE");
        assertThat(jdbc.queryForObject("SELECT delivery_state FROM " + TABLES.alert()
                + " WHERE id=?", String.class, redrive)).isEqualTo("PENDING");

        jdbc.update("UPDATE " + TABLES.alert()
                + " SET delivery_state='FAILED',delivery_attempts=3,last_delivery_error='refused again' WHERE id=?",
                redrive);
        long secondRedrive = operations.redriveAlert(redrive);
        assertThat(secondRedrive).isGreaterThan(redrive);
        assertThat(jdbc.queryForObject("SELECT occurrence FROM " + TABLES.alert()
                + " WHERE id=?", Integer.class, secondRedrive)).isOne();
    }

    @Test
    void configuredModeHealthIsDownWhenAlertDeliveryIsUnavailableOrHasNoHeartbeat() {
        jdbc.update("INSERT INTO " + TABLES.plannerRuntime()
                        + "(instance_id,started_at,heartbeat_at) VALUES (?,?,?),(?,?,?)",
                "healthy", Timestamp.from(NOW), Timestamp.from(NOW),
                "watchdog:2026-27", Timestamp.from(NOW), Timestamp.from(NOW));
        SyncOperations unconfigured = new SyncOperations(jdbc, tx, Clock.fixed(NOW, ZoneOffset.UTC),
                TABLES, OwnedSyncMode.ACTIVE, true, false);
        assertThat(unconfigured.healthy()).isFalse();

        SyncOperations configured = new SyncOperations(jdbc, tx, Clock.fixed(NOW, ZoneOffset.UTC),
                TABLES, OwnedSyncMode.ACTIVE, true, true);
        assertThat(configured.healthy()).isFalse();
        jdbc.update("INSERT INTO " + TABLES.plannerRuntime()
                        + "(instance_id,started_at,heartbeat_at) VALUES (?,?,?)",
                "delivery", Timestamp.from(NOW), Timestamp.from(NOW));
        assertThat(configured.healthy()).isTrue();
        jdbc.update("INSERT INTO " + TABLES.alert()
                        + "(incident_key,occurrence,event_type,category,severity,payload,delivery_state,created_at) "
                        + "VALUES ('unconfigured',1,'INCIDENT','TEST','CRITICAL','{}','UNCONFIGURED',?)",
                Timestamp.from(NOW));
        assertThat(configured.healthy()).isFalse();
    }

    @Test
    void healthUsesOnlySuccessfulHeartbeatAndFailsClosed() {
        jdbc.update("INSERT INTO " + TABLES.plannerRuntime()
                        + "(instance_id,started_at,heartbeat_at,last_error,last_error_at) VALUES (?,?,NULL,?,?)",
                "failed-only", Timestamp.from(NOW), "planner failed", Timestamp.from(NOW));
        assertThat(operations.healthy()).isFalse();
        jdbc.update("INSERT INTO " + TABLES.plannerRuntime()
                        + "(instance_id,started_at,heartbeat_at) VALUES (?,?,?),(?,?,?),(?,?,?)",
                "healthy", Timestamp.from(NOW), Timestamp.from(NOW),
                "watchdog:2026-27", Timestamp.from(NOW), Timestamp.from(NOW),
                "delivery", Timestamp.from(NOW), Timestamp.from(NOW));
        assertThat(operations.healthy()).isTrue();
        jdbc.update("INSERT INTO " + TABLES.alert()
                        + "(incident_key,occurrence,event_type,category,severity,payload,delivery_state,"
                        + "delivery_attempts,created_at) VALUES ('delivery',1,'INCIDENT','TEST','CRITICAL',"
                        + "'{}'::jsonb,'FAILED',3,?)", Timestamp.from(NOW));
        assertThat(operations.healthy()).isFalse();
    }

    @Test
    void importerHealthRequiresItsOwnHeartbeatRatherThanARecentLegacyPlanner() {
        SyncOperations importer = new SyncOperations(jdbc, tx, Clock.fixed(NOW, ZoneOffset.UTC),
                TABLES, OwnedSyncMode.ACTIVE, false, false, true, true);
        SyncOperations legacy = new SyncOperations(jdbc, tx, Clock.fixed(NOW, ZoneOffset.UTC),
                TABLES, OwnedSyncMode.ACTIVE, false, false, true);
        jdbc.update("INSERT INTO " + TABLES.plannerRuntime()
                        + "(instance_id,started_at,heartbeat_at) VALUES (?,?,?),(?,?,?)",
                "legacy-planner", Timestamp.from(NOW), Timestamp.from(NOW),
                "watchdog:2026-27", Timestamp.from(NOW), Timestamp.from(NOW));
        assertThat(legacy.healthy()).isTrue();
        assertThat(importer.healthy()).isFalse();
        jdbc.update("INSERT INTO " + TABLES.plannerRuntime()
                        + "(instance_id,started_at,heartbeat_at) VALUES (?,?,?)",
                "importer:current", Timestamp.from(NOW), Timestamp.from(NOW));
        assertThat(importer.healthy()).isTrue();
        jdbc.update("UPDATE " + TABLES.plannerRuntime() + " SET heartbeat_at=?"
                + " WHERE instance_id='legacy-planner'", Timestamp.from(NOW.minusSeconds(601)));
        assertThat(legacy.healthy()).isFalse();
        assertThat(importer.healthy()).isTrue();
    }

    @Test
    void importerStatusDisclosesActualBudgetAndNextCoordinatorWithoutClaimingAdmission() {
        jdbc.update("DELETE FROM " + TABLES.requestAttempt());
        jdbc.update("INSERT INTO " + TABLES.requestAttempt()
                        + "(permit_id,admitted_at,permit_expires_at,completed_at) VALUES (?::uuid,?,?,?)",
                java.util.UUID.randomUUID().toString(), Timestamp.from(NOW.minusSeconds(10)),
                Timestamp.from(NOW.plusSeconds(110)), Timestamp.from(NOW.minusSeconds(9)));
        long run = failedRun(7, "ACTIVE");
        jdbc.update("UPDATE " + TABLES.run() + " SET kind='PLAYER_HISTORY',producer_key='INCREMENTAL_CORE',"
                + "state='PENDING',failed_at=NULL,last_error='HTTP budget deferred: CAPACITY_RESERVED' WHERE generation=?", run);
        var importer = new SyncOperations(jdbc, tx, Clock.fixed(NOW, ZoneOffset.UTC), TABLES,
                OwnedSyncMode.ACTIVE, false, false, true, true, null, false, 6000);
        var status = importer.status();
        assertThat(status.budget().attemptsLast24Hours()).isOne();
        assertThat(status.budget().limit()).isEqualTo(6000);
        assertThat(status.budget().nextAttemptExpiresAt()).isEqualTo(NOW.minusSeconds(10).plusSeconds(86400));
        assertThat(status.pendingRuns()).singleElement().satisfies(pending -> {
            assertThat(pending.generation()).isEqualTo(run);
            assertThat(pending.reason()).isEqualTo("HTTP budget deferred: CAPACITY_RESERVED");
            assertThat(pending.nextCoordinatorAt()).isEqualTo(NOW.minusSeconds(60));
        });
        jdbc.update("DELETE FROM " + TABLES.requestAttempt());
    }

    @Test
    void importerCapacityIncidentNeverPagesButRealFailureAndStaleHeartbeatStillDo() {
        SyncOperations importer = new SyncOperations(jdbc, tx, Clock.fixed(NOW, ZoneOffset.UTC),
                TABLES, OwnedSyncMode.ACTIVE, false, false, true, true);
        jdbc.update("INSERT INTO " + TABLES.plannerRuntime()
                        + "(instance_id,started_at,heartbeat_at) VALUES (?,?,?),(?,?,?)",
                "importer:current", Timestamp.from(NOW), Timestamp.from(NOW),
                "watchdog:2026-27", Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("INSERT INTO " + TABLES.alert()
                        + "(incident_key,occurrence,event_type,category,severity,payload,delivery_state,created_at) "
                        + "VALUES ('budget:2026-27',1,'INCIDENT','REQUEST_BUDGET_BLOCKED','CRITICAL','{}','EXTERNAL',?)",
                Timestamp.from(NOW));
        assertThat(importer.healthy()).isTrue();
        jdbc.update("INSERT INTO " + TABLES.alert()
                        + "(incident_key,occurrence,event_type,category,severity,payload,delivery_state,created_at) "
                        + "VALUES ('broken',1,'INCIDENT','REPEATED_FAILURE','CRITICAL','{}','EXTERNAL',?)",
                Timestamp.from(NOW));
        assertThat(importer.healthy()).isFalse();
        jdbc.update("INSERT INTO " + TABLES.alert()
                        + "(incident_key,occurrence,event_type,category,severity,payload,delivery_state,created_at) "
                        + "VALUES ('broken',1,'RECOVERY','REPEATED_FAILURE','INFO','{}','EXTERNAL',?)",
                Timestamp.from(NOW));
        assertThat(importer.healthy()).isTrue();
        jdbc.update("UPDATE " + TABLES.plannerRuntime() + " SET heartbeat_at=? WHERE instance_id LIKE 'importer:%'",
                Timestamp.from(NOW.minusSeconds(601)));
        assertThat(importer.healthy()).isFalse();
    }

    @Test
    void externalMonitorHealthTracksIncidentsAndTheirExactRecoveryWithoutClaimingDelivery() {
        SyncOperations external = new SyncOperations(jdbc, tx, Clock.fixed(NOW, ZoneOffset.UTC),
                TABLES, OwnedSyncMode.STAGING, true, false, true);
        assertThat(external.healthy()).isFalse();
        jdbc.update("INSERT INTO " + TABLES.plannerRuntime()
                        + "(instance_id,started_at,heartbeat_at) VALUES (?,?,?),(?,?,?)",
                "planner", Timestamp.from(NOW), Timestamp.from(NOW),
                "watchdog:2026-27", Timestamp.from(NOW), Timestamp.from(NOW));
        // No webhook configuration/delivery heartbeat is needed in explicitly delegated mode.
        assertThat(external.healthy()).isTrue();
        assertThatThrownBy(() -> external.redriveAlert(1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("external monitor");
        jdbc.update("INSERT INTO " + TABLES.alert()
                        + "(incident_key,occurrence,event_type,category,severity,payload,delivery_state,created_at) "
                        + "VALUES ('fixture',1,'INCIDENT','TEST','CRITICAL','{}','EXTERNAL',?)",
                Timestamp.from(NOW));
        assertThat(external.healthy()).isFalse();
        jdbc.update("INSERT INTO " + TABLES.alert()
                        + "(incident_key,occurrence,event_type,category,severity,payload,delivery_state,created_at) "
                        + "VALUES ('fixture',1,'RECOVERY','TEST','INFO','{}','EXTERNAL',?)",
                Timestamp.from(NOW));
        assertThat(external.healthy()).isTrue();
        jdbc.update("INSERT INTO " + TABLES.alert()
                        + "(incident_key,occurrence,event_type,category,severity,payload,delivery_state,created_at) "
                        + "VALUES ('fixture',2,'INCIDENT','TEST','CRITICAL','{}','EXTERNAL',?)",
                Timestamp.from(NOW));
        assertThat(external.healthy()).isFalse(); // occurrence 1 cannot clear occurrence 2
        jdbc.update("INSERT INTO " + TABLES.alert()
                        + "(incident_key,occurrence,event_type,category,severity,payload,delivery_state,created_at) "
                        + "VALUES ('fixture',2,'RECOVERY','TEST','INFO','{}','EXTERNAL',?)",
                Timestamp.from(NOW));
        assertThat(external.healthy()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.alert()
                + " WHERE delivered_at IS NOT NULL OR delivery_attempts<>0", Integer.class)).isZero();
        jdbc.update("UPDATE " + TABLES.plannerRuntime() + " SET heartbeat_at=? WHERE instance_id LIKE 'watchdog:%'",
                Timestamp.from(NOW.minusSeconds(601)));
        assertThat(external.healthy()).isFalse();
        assertThat(new SyncOperations(jdbc, tx, Clock.fixed(NOW, ZoneOffset.UTC),
                TABLES, OwnedSyncMode.OFF, false, false, true).healthy()).isFalse();
    }

    private long failedRun(long epoch, String mode) {
        jdbc.update("INSERT INTO " + TABLES.receipt()
                + "(season,publication_key) VALUES ('2026-27','fixture:77')");
        return jdbc.queryForObject("INSERT INTO " + TABLES.run()
                        + "(kind,season,coalescing_key,publication_key,priority,due_at,requested_source_after,"
                        + "runtime_mode,runtime_epoch,state,failed_at,created_at,updated_at) "
                        + "VALUES ('FIXTURE_VERIFICATION','2026-27','fixture:77','fixture:77',0,?,?,?,?,'FAILED',?,?,?) "
                        + "RETURNING generation", Long.class, Timestamp.from(NOW.minusSeconds(60)),
                Timestamp.from(NOW.minusSeconds(60)), mode, epoch, Timestamp.from(NOW.minusSeconds(30)),
                Timestamp.from(NOW.minusSeconds(60)), Timestamp.from(NOW.minusSeconds(30)));
    }

    private void mode(String mode, long epoch) {
        jdbc.update("INSERT INTO " + TABLES.plannerRuntime()
                        + "(instance_id,started_at,heartbeat_at,runtime_mode,runtime_epoch) VALUES (?,?,?,?,?)",
                "mode:2026-27", Timestamp.from(NOW), Timestamp.from(NOW), mode, epoch);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
