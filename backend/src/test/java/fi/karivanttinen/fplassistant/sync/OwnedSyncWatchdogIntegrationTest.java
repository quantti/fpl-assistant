package fi.karivanttinen.fplassistant.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.SqlProvider;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import fi.karivanttinen.fplassistant.testsupport.TapasSeed;

class OwnedSyncWatchdogIntegrationTest {

    private static final OwnedSyncTables TABLES = OwnedSyncTables.tests();
    private static final Instant NOW = Instant.parse("2024-08-17T18:00:00Z");
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;

    @BeforeAll
    static void initialize() {
        dataSource = new DriverManagerDataSource(required("DATABASE_URL"),
                required("DB_USER"), required("DB_PASSWORD"));
        jdbc = new JdbcTemplate(dataSource);
        TapasSeed.ensureOwnedSyncFixture(jdbc);
        new OwnedSyncSchema(jdbc, TABLES).initialize();
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM " + TABLES.alert());
        jdbc.update("DELETE FROM " + TABLES.fixtureSync());
        jdbc.update("DELETE FROM " + TABLES.plannerSlot());
        jdbc.update("DELETE FROM " + TABLES.plannerRuntime());
        jdbc.update("DELETE FROM " + TABLES.payload());
        jdbc.update("DELETE FROM " + TABLES.run());
        jdbc.update("DELETE FROM " + TABLES.receipt());
        jdbc.update("DELETE FROM " + TABLES.requestAttempt());
        jdbc.update("UPDATE " + TABLES.requestState()
                + " SET last_admitted_at=NULL,cooldown_until=NULL WHERE singleton=true");
    }

    @AfterEach
    void cleanUp() {
        reset();
    }

    @Test
    void stalledPlannerDoesNotStopWatchdogAndExpiredLeaseAndOverdueFixtureAreDurable() {
        jdbc.update("INSERT INTO " + TABLES.plannerRuntime()
                        + "(instance_id,started_at,heartbeat_at) VALUES (?,?,?)",
                "worker", ts(NOW.minusSeconds(3600)), ts(NOW.minusSeconds(3600)));
        long generation = run("fixture:98101", "fixture:98101", "PENDING");
        jdbc.update("UPDATE " + TABLES.run()
                        + " SET state='LEASED',lease_owner='hung',lease_token=?,lease_expires_at=? WHERE generation=?",
                UUID.randomUUID(), ts(NOW.minusSeconds(1)), generation);
        jdbc.update("UPDATE " + TABLES.requestState() + " SET cooldown_until=? WHERE singleton=true",
                ts(NOW.plusSeconds(600)));
        jdbc.update("INSERT INTO " + TABLES.fixtureSync()
                        + "(season_id,season_code,fixture_id,state,expected_participants,received_participants,"
                        + "validation_failures,attempt_count,updated_at) VALUES (9825,'2024-25',98101,"
                        + "'WAITING_STATS',2,1,ARRAY['MISSING_HISTORY'],4,?)", ts(NOW.minusSeconds(60)));

        OwnedSyncWatchdog watchdog = watchdog(false, false, payload -> { });
        assertThat(watchdog.deliver()).isZero(); // fresh delivery heartbeat must not mask the planner
        OwnedSyncWatchdog.CheckResult result = watchdog.check("2024-25");

        assertThat(result.incidentsOpened()).isGreaterThanOrEqualTo(3);
        assertThat(categories()).contains("RUNTIME_STALLED", "EXPIRED_LEASE",
                "FIXTURE_VERIFICATION_OVERDUE", "MISSED_DAILY_CAPTURE", "REQUEST_BUDGET_BLOCKED");
        assertThat(jdbc.queryForObject("SELECT payload->'details'->>'status' FROM " + TABLES.alert()
                + " WHERE category='FIXTURE_VERIFICATION_OVERDUE'", String.class))
                .isEqualTo("verification overdue");
        assertThat(jdbc.queryForObject("SELECT delivery_state FROM " + TABLES.alert()
                + " WHERE category='EXPIRED_LEASE'", String.class)).isEqualTo("DISABLED");
        assertThat(watchdog(false, false, payload -> { throw new AssertionError("must not send"); }).deliver())
                .isZero();
    }

    @Test
    void externalNotificationIsRecordedWithoutSendingAndCanBeHandedBackToWebhook() {
        OwnedSyncWatchdog external = new OwnedSyncWatchdog(jdbc, tx, Clock.fixed(NOW, ZoneOffset.UTC),
                TABLES, new OwnedSyncWatchdog.Notification(false, false, true),
                payload -> { throw new AssertionError("external monitor must not send webhooks"); });
        assertThat(external.check("2024-25").incidentsOpened()).isPositive();
        assertThat(external.deliver()).isZero();
        assertThat(jdbc.queryForList("SELECT DISTINCT delivery_state FROM " + TABLES.alert(), String.class))
                .containsExactly("EXTERNAL");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.alert()
                + " WHERE delivered_at IS NOT NULL OR delivery_attempts<>0", Integer.class)).isZero();
        List<String> sent = new CopyOnWriteArrayList<>();
        assertThat(watchdog(true, true, sent::add).deliver()).isPositive();
        assertThat(sent).isNotEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.alert()
                + " WHERE delivery_state='SUPERSEDED'", Integer.class)).isPositive();
    }

    @Test
    void importerNeedsItsOwnHeartbeatAndRecoversOnlyAfterItsHeartbeat() {
        jdbc.update("INSERT INTO " + TABLES.plannerRuntime()
                + "(instance_id,started_at,heartbeat_at) VALUES ('legacy-worker',?,?)",
                ts(NOW), ts(NOW));
        OwnedSyncWatchdog watchdog = watchdog(false, false, payload -> { });
        watchdog.checkImporter("2024-25");
        assertThat(categories()).contains("RUNTIME_STALLED");
        jdbc.update("INSERT INTO " + TABLES.plannerRuntime()
                + "(instance_id,started_at,heartbeat_at) VALUES ('importer:ready',?,?)",
                ts(NOW), ts(NOW));
        watchdog.checkImporter("2024-25");
        assertThat(eventCount("runtime:2024-25", "RECOVERY")).isOne();
    }

    @Test
    void importerFlagsOverdueCheckedGameweekWithoutLegacyFixtureOrCaptureSignals() {
        jdbc.update("""
                INSERT INTO gameweek(id,season_id,name,deadline_time,finished,data_checked,is_current,is_next)
                VALUES (1,9825,'GW 1',?,true,true,false,false)
                ON CONFLICT(id,season_id) DO UPDATE SET finished=true,data_checked=true
                """, ts(NOW.minusSeconds(3 * 24 * 3600)));
        try {
            jdbc.update("INSERT INTO " + TABLES.gameweekStatSync()
                    + "(season_id,season_code,gameweek,state,event_finished,data_checked,"
                    + "fixture_manifest_complete,all_assigned_fixtures_finished,source_signature,"
                    + "first_checked_at,current_invalidation_revision,updated_at) "
                    + "VALUES (9825,'2024-25',1,'FINAL_PENDING',true,true,true,true,?,?,0,?)",
                    "a".repeat(64), ts(NOW.minusSeconds(4 * 3600)), ts(NOW));
            OwnedSyncWatchdog watchdog = watchdog(false, false, payload -> { });
            watchdog.checkImporter("2024-25");
            assertThat(categories()).contains("INCREMENTAL_GAMEWEEK_OVERDUE")
                    .doesNotContain("MISSED_DAILY_CAPTURE", "FIXTURE_VERIFICATION_OVERDUE");
            assertThat(eventCount("incremental-gameweek:2024-25:1", "INCIDENT")).isOne();
        } finally {
            jdbc.update("DELETE FROM " + TABLES.gameweekStatSync() + " WHERE season_id=9825");
            jdbc.update("DELETE FROM gameweek WHERE season_id=9825 AND id=1");
        }
    }

    @Test
    void importerCannotCallLegacyFixtureStateAProvedRecovery() {
        long generation = run("fixture:98101", "fixture:98101", "FAILED");
        jdbc.update("INSERT INTO " + TABLES.fixtureSync()
                        + "(season_id,season_code,fixture_id,state,current_verified_generation,"
                        + "last_verified_generation,updated_at) "
                        + "VALUES (9825,'2024-25',98101,'VERIFIED',?,?,?)",
                generation, generation, ts(NOW));
        jdbc.update("INSERT INTO " + TABLES.alert()
                        + "(incident_key,occurrence,event_type,category,severity,season,fixture_id,"
                        + "payload,delivery_state,created_at) "
                        + "VALUES ('fixture-overdue:2024-25:98101',1,'INCIDENT',"
                        + "'FIXTURE_VERIFICATION_OVERDUE','WARNING','2024-25',98101,'{}','DISABLED',?)",
                ts(NOW.minusSeconds(60)));

        watchdog(false, false, payload -> { }).checkImporter("2024-25");

        assertThat(eventCount("fixture-overdue:2024-25:98101", "RECOVERY")).isZero();
    }

    @Test
    void malformedPersistedImporterGameweekIncidentNeverClaimsRecoveryOrStopsWatchdog() {
        jdbc.update("INSERT INTO " + TABLES.alert()
                        + "(incident_key,occurrence,event_type,category,severity,season,"
                        + "payload,delivery_state,created_at) "
                        + "VALUES ('incremental-gameweek:2024-25:99',1,'INCIDENT',"
                        + "'INCREMENTAL_GAMEWEEK_OVERDUE','WARNING','2024-25','{}','DISABLED',?)",
                ts(NOW));
        watchdog(false, false, payload -> { }).checkImporter("2024-25");
        assertThat(eventCount("incremental-gameweek:2024-25:99", "RECOVERY")).isZero();
    }

    @Test
    void deduplicatesIncidentAndRecoversFixtureOnlyAfterVerifiedState() {
        long generation = run("fixture:98101", "fixture:98101", "FAILED");
        jdbc.update("INSERT INTO " + TABLES.fixtureSync()
                        + "(season_id,season_code,fixture_id,state,updated_at) "
                        + "VALUES (9825,'2024-25',98101,'WAITING_STATS',?)", ts(NOW));
        OwnedSyncWatchdog watchdog = watchdog(false, false, payload -> { });

        watchdog.check("2024-25");
        watchdog.check("2024-25");
        assertThat(eventCount("fixture-overdue:2024-25:98101", "INCIDENT")).isOne();

        // Deleting or losing the row is not verified recovery.
        jdbc.update("DELETE FROM " + TABLES.fixtureSync() + " WHERE season_id=9825 AND fixture_id=98101");
        watchdog.check("2024-25");
        assertThat(eventCount("fixture-overdue:2024-25:98101", "RECOVERY")).isZero();

        jdbc.update("INSERT INTO " + TABLES.fixtureSync()
                        + "(season_id,season_code,fixture_id,state,current_verified_generation,"
                        + "last_verified_generation,updated_at) VALUES (9825,'2024-25',98101,'VERIFIED',?,?,?)",
                generation, generation, ts(NOW));
        watchdog.check("2024-25");
        assertThat(eventCount("fixture-overdue:2024-25:98101", "RECOVERY")).isOne();
    }

    @Test
    void deliveryFailurePersistsAndIsBoundedWithoutChangingIncidents() {
        jdbc.update("INSERT INTO " + TABLES.plannerRuntime()
                        + "(instance_id,started_at,heartbeat_at) VALUES (?,?,?)",
                "stalled", ts(NOW.minusSeconds(3600)), ts(NOW.minusSeconds(3600)));
        OwnedSyncWatchdog watchdog = watchdog(true, true, payload -> {
            throw new IllegalStateException("local mock refused");
        });
        watchdog.check("2024-25");
        int incidents = jdbc.queryForObject("SELECT count(*) FROM " + TABLES.alert(), Integer.class);

        for (int attempt = 0; attempt < 3; attempt++) {
            jdbc.update("UPDATE " + TABLES.alert() + " SET next_delivery_at=? WHERE delivery_state='FAILED'", ts(NOW));
            watchdog.deliver();
        }

        assertThat(jdbc.queryForObject("SELECT delivery_state FROM " + TABLES.alert()
                + " ORDER BY id LIMIT 1", String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT delivery_attempts FROM " + TABLES.alert()
                + " ORDER BY id LIMIT 1", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT last_delivery_error FROM " + TABLES.alert()
                + " ORDER BY id LIMIT 1", String.class)).contains("local mock refused");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.alert(), Integer.class))
                .isEqualTo(incidents);
    }

    @Test
    void batchesHighCardinalityIncidentsAndRecoveriesWithinMonitorTransaction() {
        jdbc.update("INSERT INTO " + TABLES.plannerRuntime()
                        + "(instance_id,started_at,heartbeat_at) VALUES (?,?,?)",
                "healthy", ts(NOW), ts(NOW));
        jdbc.update("INSERT INTO " + TABLES.receipt()
                + "(season,publication_key) SELECT '2024-25','bulk:' || n FROM generate_series(1,700) n");
        jdbc.update("INSERT INTO " + TABLES.run()
                + "(kind,season,coalescing_key,publication_key,priority,due_at,requested_source_after,"
                + "runtime_mode,runtime_epoch,state,created_at,updated_at) SELECT "
                + "'PLAYER_HISTORY','2024-25','bulk:' || n,'bulk:' || n,3,?,?,?,?,"
                + "'PENDING',?,? FROM generate_series(1,700) n",
                ts(NOW.minusSeconds(600)), ts(NOW.minusSeconds(600)), "STAGING", 38,
                ts(NOW.minusSeconds(600)), ts(NOW.minusSeconds(600)));
        TransactionTemplate bounded = new TransactionTemplate(tx.getTransactionManager());
        bounded.setTimeout(5);
        OwnedSyncWatchdog watchdog = new OwnedSyncWatchdog(jdbc, bounded,
                Clock.fixed(NOW, ZoneOffset.UTC), TABLES,
                new OwnedSyncWatchdog.Notification(false, false, true), payload -> { });

        assertThat(watchdog.check("2024-25").incidentsOpened()).isGreaterThanOrEqualTo(700);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.alert()
                + " WHERE category='DUE_WORK' AND event_type='INCIDENT'", Integer.class)).isEqualTo(700);

        jdbc.update("UPDATE " + TABLES.run() + " SET state='COMPLETED',completed_at=?,updated_at=?",
                ts(NOW), ts(NOW));
        assertThat(watchdog.check("2024-25").recoveriesOpened()).isGreaterThanOrEqualTo(700);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.alert()
                + " WHERE category='DUE_WORK' AND event_type='RECOVERY'", Integer.class)).isEqualTo(700);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT occurrence) FROM " + TABLES.alert()
                + " WHERE category='DUE_WORK'", Integer.class)).isOne();
    }

    @Test
    void evidenceQueriesReturnOnlyRowsForNewSignalsAndOpenIncidents() {
        jdbc.update("INSERT INTO " + TABLES.plannerRuntime()
                        + "(instance_id,started_at,heartbeat_at) VALUES (?,?,?)",
                "healthy", ts(NOW), ts(NOW));
        jdbc.update("INSERT INTO " + TABLES.receipt()
                + "(season,publication_key) SELECT '2024-25','history:' || n FROM generate_series(1,1000) n");
        jdbc.update("INSERT INTO " + TABLES.run()
                + "(kind,season,coalescing_key,publication_key,priority,due_at,requested_source_after,"
                + "runtime_mode,runtime_epoch,state,created_at,updated_at) SELECT "
                + "'PLAYER_HISTORY','2024-25','history:' || n,'history:' || n,3,?,?,?,?,"
                + "'PENDING',?,? FROM generate_series(1,1000) n",
                ts(NOW.minusSeconds(600)), ts(NOW.minusSeconds(600)), "OFF", 0,
                ts(NOW.minusSeconds(600)), ts(NOW.minusSeconds(600)));
        jdbc.update("UPDATE " + TABLES.run() + " SET state='COMPLETED',completed_at=?,updated_at=? "
                + "WHERE publication_key LIKE 'history:%'", ts(NOW), ts(NOW));
        jdbc.update("INSERT INTO " + TABLES.alert()
                + "(incident_key,occurrence,event_type,category,severity,season,payload,delivery_state,created_at) "
                + "SELECT 'due-work:' || generation,1,'INCIDENT','DUE_WORK','WARNING','2024-25','{}',"
                + "'DISABLED',? FROM " + TABLES.run() + " WHERE publication_key LIKE 'history:%'", ts(NOW));
        jdbc.update("INSERT INTO " + TABLES.alert()
                + "(incident_key,occurrence,event_type,category,severity,season,payload,delivery_state,created_at) "
                + "SELECT 'due-work:' || generation,1,'RECOVERY','DUE_WORK','INFO','2024-25','{}',"
                + "'DISABLED',? FROM " + TABLES.run() + " WHERE publication_key LIKE 'history:%'", ts(NOW));

        long settled = run("settled", "settled", "PENDING");
        jdbc.update("UPDATE " + TABLES.run() + " SET state='COMPLETED',completed_at=?,updated_at=? "
                + "WHERE generation=?", ts(NOW), ts(NOW), settled);
        insertWatchdogAlert("due-work:" + settled, 3, "INCIDENT", "DUE_WORK");

        long recurring = run("recurring", "recurring", "PENDING");
        insertWatchdogAlert("due-work:" + recurring, 6, "INCIDENT", "DUE_WORK");
        insertWatchdogAlert("due-work:" + recurring, 6, "RECOVERY", "DUE_WORK");

        TrackingJdbcTemplate tracking = new TrackingJdbcTemplate(dataSource);
        OwnedSyncWatchdog watchdog = new OwnedSyncWatchdog(tracking, tx, Clock.fixed(NOW, ZoneOffset.UTC),
                TABLES, new OwnedSyncWatchdog.Notification(false, false), payload -> { });
        watchdog.check("2024-25");

        assertThat(tracking.rows()).containsEntry("next-occurrences", 1).containsEntry("completed-runs", 1);
        assertThat(tracking.rows()).doesNotContainKeys("verified-fixtures", "capture-reads");
        assertThat(jdbc.queryForObject("SELECT max(occurrence) FROM " + TABLES.alert()
                + " WHERE incident_key=? AND event_type='INCIDENT'", Integer.class,
                "due-work:" + recurring)).isEqualTo(7);
        assertThat(eventCount("due-work:" + settled, "RECOVERY")).isOne();
    }

    @Test
    void skipsBoundedQueriesWhenNoNewOrEvidenceBearingKeysExist() {
        OwnedSyncWatchdog first = watchdog(false, false, payload -> { });
        first.check("2024-25");
        jdbc.update("UPDATE " + TABLES.alert() + " SET category='OTHER'");

        TrackingJdbcTemplate tracking = new TrackingJdbcTemplate(dataSource);
        OwnedSyncWatchdog second = new OwnedSyncWatchdog(tracking, tx, Clock.fixed(NOW, ZoneOffset.UTC),
                TABLES, new OwnedSyncWatchdog.Notification(false, false), payload -> { });
        second.check("2024-25");

        assertThat(tracking.rows()).isEmpty();
    }

    @Test
    void dueWorkOnARunFencedInASupersededEpochRecoversButACurrentFailureStaysOpen() {
        jdbc.update("INSERT INTO " + TABLES.plannerRuntime()
                        + "(instance_id,started_at,heartbeat_at) VALUES (?,?,?)",
                "healthy", ts(NOW), ts(NOW));
        jdbc.update("INSERT INTO " + TABLES.plannerRuntime()
                        + "(instance_id,started_at,heartbeat_at,runtime_mode,runtime_epoch) VALUES (?,?,?,?,?)",
                "mode:2024-25", ts(NOW), ts(NOW), "ACTIVE", 9);
        jdbc.update("INSERT INTO " + TABLES.receipt()
                + "(season,publication_key) VALUES ('2024-25','fenced:1'),('2024-25','current:1')");
        String insert = "INSERT INTO " + TABLES.run()
                + "(kind,season,coalescing_key,publication_key,priority,due_at,requested_source_after,"
                + "runtime_mode,runtime_epoch,state,created_at,updated_at) "
                + "VALUES ('PLAYER_HISTORY','2024-25',?,?,3,?,?,?,?,'PENDING',?,?) RETURNING generation";
        long fenced = jdbc.queryForObject(insert, Long.class, "fenced:1", "fenced:1",
                ts(NOW.minusSeconds(600)), ts(NOW.minusSeconds(600)), "STAGING", 8,
                ts(NOW.minusSeconds(600)), ts(NOW.minusSeconds(600)));
        long current = jdbc.queryForObject(insert, Long.class, "current:1", "current:1",
                ts(NOW.minusSeconds(600)), ts(NOW.minusSeconds(600)), "ACTIVE", 9,
                ts(NOW.minusSeconds(600)), ts(NOW.minusSeconds(600)));
        insertWatchdogAlert("due-work:not-a-generation", 1, "INCIDENT", "DUE_WORK");
        OwnedSyncWatchdog watchdog = watchdog(false, false, payload -> { });

        watchdog.check("2024-25");
        assertThat(eventCount("due-work:" + fenced, "INCIDENT")).isOne();
        assertThat(eventCount("due-work:" + current, "INCIDENT")).isOne();

        // Deactivation fences the old epoch's run; the current epoch's run fails on its own.
        jdbc.update("UPDATE " + TABLES.run() + " SET state='FAILED',failed_at=?,last_error=?,updated_at=?"
                        + " WHERE generation IN (?,?)", ts(NOW), "fenced or failed", ts(NOW), fenced, current);
        watchdog.check("2024-25");

        // A fenced run's epoch is over and its work was planned again, so it is not overdue work.
        assertThat(eventCount("due-work:" + fenced, "RECOVERY")).isOne();
        // A failure inside the live activation is not evidence that anything recovered.
        assertThat(eventCount("due-work:" + current, "RECOVERY")).isZero();
        assertThat(eventCount("due-work:not-a-generation", "RECOVERY")).isZero();
    }

    @ParameterizedTest
    @EnumSource(OwnedSyncStore.IncrementalSupersedeReason.class)
    void supersededImporterRunRecoversDueWorkAndExpiredLeaseAndRestoresHealth(
            OwnedSyncStore.IncrementalSupersedeReason reason) {
        jdbc.update("INSERT INTO " + TABLES.plannerRuntime()
                        + "(instance_id,started_at,heartbeat_at) VALUES ('importer:ready',?,?),('watchdog:2024-25',?,?)",
                ts(NOW), ts(NOW), ts(NOW), ts(NOW));
        long generation = run("incremental-core-import", "incremental-core-import", "PENDING");
        jdbc.update("UPDATE " + TABLES.run()
                        + " SET kind='PLAYER_HISTORY',producer_key='INCREMENTAL_CORE',runtime_mode='ACTIVE',"
                        + "runtime_epoch=9 WHERE generation=?", generation);
        insertWatchdogAlert("due-work:" + generation, 1, "INCIDENT", "DUE_WORK");
        insertWatchdogAlert("expired-lease:" + generation, 1, "INCIDENT", "EXPIRED_LEASE");
        SyncOperations operations = new SyncOperations(jdbc, tx, Clock.fixed(NOW, ZoneOffset.UTC),
                TABLES, OwnedSyncMode.ACTIVE, false, false, true, true);
        assertThat(operations.healthy()).isFalse();

        jdbc.update("UPDATE " + TABLES.run()
                        + " SET state='SUPERSEDED',supersede_reason=?,completed_at=? WHERE generation=?",
                reason.code(), ts(NOW), generation);
        OwnedSyncWatchdog watchdog = watchdog(false, false, payload -> { });
        watchdog.checkImporter("2024-25");

        assertThat(eventCount("due-work:" + generation, "RECOVERY")).isOne();
        assertThat(eventCount("expired-lease:" + generation, "RECOVERY")).isOne();
        assertThat(operations.healthy()).isTrue();
        watchdog.checkImporter("2024-25");
        assertThat(eventCount("due-work:" + generation, "RECOVERY")).isOne();
        assertThat(eventCount("expired-lease:" + generation, "RECOVERY")).isOne();
    }

    @ParameterizedTest
    @ValueSource(strings = {"supersede_reason=NULL", "completed_at=NULL", "producer_key='LEGACY'",
            "last_error='unresolved'", "failed_at=completed_at", "kind='FIXTURE_VERIFICATION'",
            "coalescing_key='other'", "runtime_mode='OFF'"})
    void unprovedSupersessionDoesNotRecoverDueWork(String invalidEvidence) {
        long generation = run("incremental-core-import", "incremental-core-import", "PENDING");
        jdbc.update("UPDATE " + TABLES.run()
                        + " SET kind='PLAYER_HISTORY',producer_key='INCREMENTAL_CORE',runtime_mode='ACTIVE',state='SUPERSEDED',"
                        + "supersede_reason='VALIDATED_CANONICAL_SOURCE_PAIR',completed_at=? WHERE generation=?",
                ts(NOW), generation);
        jdbc.update("UPDATE " + TABLES.run() + " SET " + invalidEvidence + " WHERE generation=?", generation);
        insertWatchdogAlert("due-work:" + generation, 1, "INCIDENT", "DUE_WORK");

        watchdog(false, false, payload -> { }).checkImporter("2024-25");

        assertThat(eventCount("due-work:" + generation, "RECOVERY")).isZero();
    }

    @Test
    void importerCapacityWaitIsNotOverdueButUnsettledAdmissionStillAlerts() {
        jdbc.update("INSERT INTO " + TABLES.plannerRuntime()
                        + "(instance_id,started_at,heartbeat_at) VALUES (?,?,?)",
                "importer:healthy", ts(NOW), ts(NOW));
        long capacity = run("capacity", "capacity", "PENDING");
        jdbc.update("UPDATE " + TABLES.run() + " SET last_error='HTTP budget deferred: CAPACITY_RESERVED',"
                + "due_at=? WHERE generation=?", ts(NOW.minusSeconds(7200)), capacity);
        var watchdog = watchdog(false, false, payload -> { });
        watchdog.checkImporter("2024-25");
        assertThat(categories()).doesNotContain("DUE_WORK", "REPEATED_FAILURE");
        jdbc.update("UPDATE " + TABLES.run() + " SET updated_at=? WHERE generation=?",
                ts(NOW.minusSeconds(10800)), capacity);
        watchdog.checkImporter("2024-25");
        assertThat(eventCount("due-work:" + capacity, "INCIDENT")).isOne();
        jdbc.update("UPDATE " + TABLES.run() + " SET updated_at=? WHERE generation=?", ts(NOW), capacity);
        watchdog.checkImporter("2024-25");
        assertThat(eventCount("due-work:" + capacity, "RECOVERY")).isOne();
        jdbc.update("UPDATE " + TABLES.run() + " SET last_error='HTTP budget deferred: UNSETTLED'"
                + " WHERE generation=?", capacity);
        watchdog.checkImporter("2024-25");
        assertThat(categories()).contains("IMPORTER_ADMISSION_UNRESOLVED");
    }

    @Test
    void pendingRunAlertsOnActualConsecutiveFetchFailuresButNotHealthyDeferrals() {
        jdbc.update("INSERT INTO " + TABLES.plannerRuntime()
                        + "(instance_id,started_at,heartbeat_at) VALUES (?,?,?)",
                "healthy", ts(NOW), ts(NOW));
        long failedFetches = run("fetches", "fetches", "PENDING");
        jdbc.update("UPDATE " + TABLES.run()
                + " SET consecutive_fetch_failures=3,due_at=? WHERE generation=?", ts(NOW.plusSeconds(60)), failedFetches);
        long deferred = run("deferred", "deferred", "PENDING");
        jdbc.update("UPDATE " + TABLES.run()
                + " SET attempt_count=99,consecutive_fetch_failures=0,due_at=? WHERE generation=?",
                ts(NOW.plusSeconds(60)), deferred);

        watchdog(false, false, payload -> { }).check("2024-25");

        assertThat(categories()).contains("REPEATED_FAILURE");
        assertThat(jdbc.queryForObject("SELECT payload->'details'->>'consecutiveFailedFetches' FROM "
                + TABLES.alert() + " WHERE category='REPEATED_FAILURE'", String.class)).isEqualTo("3");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.alert()
                + " WHERE incident_key LIKE '%deferred%'", Integer.class)).isZero();
    }

    @Test
    void concurrentDeliverersCannotSendRecoveryBeforeItsIncident() throws Exception {
        insertAlert("ordered", 1, "INCIDENT", "PENDING");
        insertAlert("ordered", 1, "RECOVERY", "PENDING");
        List<String> delivered = new CopyOnWriteArrayList<>();
        CountDownLatch incidentStarted = new CountDownLatch(1);
        CountDownLatch releaseIncident = new CountDownLatch(1);
        OwnedSyncWatchdog watchdog = watchdog(true, true, payload -> {
            if (payload.contains("incident-body")) {
                incidentStarted.countDown();
                releaseIncident.await(5, TimeUnit.SECONDS);
                delivered.add("incident");
            } else delivered.add("recovery");
        });
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(watchdog::deliver);
            assertThat(incidentStarted.await(5, TimeUnit.SECONDS)).isTrue();
            var second = workers.submit(watchdog::deliver);
            assertThat(second.get(5, TimeUnit.SECONDS)).isZero();
            releaseIncident.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(2);
        }
        assertThat(delivered).containsExactly("incident", "recovery");
    }

    @Test
    void configurationRepairAdditivelyRedrivesOngoingUnconfiguredIncident() {
        insertAlert("repair", 1, "INCIDENT", "UNCONFIGURED");
        List<String> delivered = new CopyOnWriteArrayList<>();

        assertThat(watchdog(true, true, delivered::add).deliver()).isOne();

        assertThat(delivered).hasSize(1);
        assertThat(jdbc.queryForList("SELECT delivery_state FROM " + TABLES.alert()
                + " WHERE incident_key='repair' ORDER BY id", String.class))
                .containsExactly("SUPERSEDED", "DELIVERED");
        assertThat(jdbc.queryForList("SELECT event_type FROM " + TABLES.alert()
                + " WHERE incident_key='repair' ORDER BY id", String.class))
                .containsExactly("INCIDENT", "REDRIVE");
    }

    @Test
    void unconfiguredNotificationIsExplicitAndSchemaIsClosedByRls() {
        jdbc.update("INSERT INTO " + TABLES.plannerRuntime()
                        + "(instance_id,started_at,heartbeat_at) VALUES (?,?,?)",
                "healthy", ts(NOW), ts(NOW));
        watchdog(true, false, payload -> { }).check("2024-25");
        assertThat(jdbc.queryForList("SELECT DISTINCT delivery_state FROM " + TABLES.alert(), String.class))
                .containsOnly("UNCONFIGURED");
        assertThat(jdbc.queryForObject("SELECT relrowsecurity FROM pg_class WHERE oid=to_regclass(?)",
                Boolean.class, TABLES.alert())).isTrue();
    }

    private OwnedSyncWatchdog watchdog(boolean enabled, boolean configured,
                                        OwnedSyncWatchdog.AlertSender sender) {
        return new OwnedSyncWatchdog(jdbc, tx, Clock.fixed(NOW, ZoneOffset.UTC), TABLES,
                new OwnedSyncWatchdog.Notification(enabled, configured), sender);
    }

    private void insertAlert(String key, int occurrence, String event, String state) {
        String body = event.equals("INCIDENT") ? "incident-body" : "recovery-body";
        jdbc.update("INSERT INTO " + TABLES.alert()
                        + "(incident_key,occurrence,event_type,category,severity,payload,delivery_state,"
                        + "next_delivery_at,created_at) VALUES (?,?,?,?,?,jsonb_build_object('body',?),?,?,?)",
                key, occurrence, event, "TEST", "WARNING", body, state,
                state.equals("PENDING") ? ts(NOW) : null, ts(NOW));
    }

    private void insertWatchdogAlert(String key, int occurrence, String event, String category) {
        jdbc.update("INSERT INTO " + TABLES.alert()
                        + "(incident_key,occurrence,event_type,category,severity,season,payload,delivery_state,created_at) "
                        + "VALUES (?,?,?,?,?,'2024-25','{}','DISABLED',?)",
                key, occurrence, event, category, event.equals("INCIDENT") ? "WARNING" : "INFO", ts(NOW));
    }

    private long run(String coalescing, String publication, String state) {
        jdbc.update("INSERT INTO " + TABLES.receipt() + "(season,publication_key) VALUES ('2024-25',?)",
                publication);
        Long generation = jdbc.queryForObject("INSERT INTO " + TABLES.run()
                        + "(kind,season,coalescing_key,publication_key,priority,due_at,requested_source_after,"
                        + "runtime_mode,runtime_epoch,state,created_at,updated_at) "
                        + "VALUES ('FIXTURE_VERIFICATION','2024-25',?,?,0,?,?, 'OFF',0,?,?,?) RETURNING generation",
                Long.class, coalescing, publication, ts(NOW.minusSeconds(600)), ts(NOW.minusSeconds(600)),
                state, ts(NOW.minusSeconds(600)), ts(NOW.minusSeconds(600)));
        return generation;
    }

    private List<String> categories() {
        return jdbc.queryForList("SELECT category FROM " + TABLES.alert() + " WHERE event_type='INCIDENT'",
                String.class);
    }

    private int eventCount(String key, String event) {
        return jdbc.queryForObject("SELECT count(*) FROM " + TABLES.alert()
                + " WHERE incident_key=? AND event_type=?", Integer.class, key, event);
    }

    private static Timestamp ts(Instant value) { return Timestamp.from(value); }

    private static final class TrackingJdbcTemplate extends JdbcTemplate {
        private final Map<String, Integer> rows = new HashMap<>();

        private TrackingJdbcTemplate(DataSource dataSource) {
            super(dataSource);
        }

        @Override
        public <T> List<T> query(PreparedStatementCreator creator, RowMapper<T> rowMapper) {
            List<T> result = super.query(creator, rowMapper);
            if (creator instanceof SqlProvider provider) {
                String name = queryName(provider.getSql());
                if (name != null) rows.put(name, result.size());
            }
            return result;
        }

        private Map<String, Integer> rows() {
            return rows;
        }

        private static String queryName(String sql) {
            if (sql.contains("max(occurrence)+1")) return "next-occurrences";
            if (sql.contains("r.generation=ANY")) return "completed-runs";
            if (sql.contains("fixture_id=ANY")) return "verified-fixtures";
            if (sql.contains("publication_key=ANY")) return "capture-reads";
            return null;
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
