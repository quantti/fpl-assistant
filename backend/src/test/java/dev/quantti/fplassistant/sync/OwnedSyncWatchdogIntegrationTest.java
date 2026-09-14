package dev.quantti.fplassistant.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import dev.quantti.fplassistant.testsupport.SharedStatsSeed;

class OwnedSyncWatchdogIntegrationTest {

    private static final OwnedSyncTables TABLES = OwnedSyncTables.tests();
    private static final Instant NOW = Instant.parse("2024-08-17T18:00:00Z");
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;

    @BeforeAll
    static void initialize() {
        DataSource dataSource = new DriverManagerDataSource(required("DATABASE_URL"),
                required("DB_USER"), required("DB_PASSWORD"));
        jdbc = new JdbcTemplate(dataSource);
        SharedStatsSeed.ensureOwnedSyncFixture(jdbc);
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

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
