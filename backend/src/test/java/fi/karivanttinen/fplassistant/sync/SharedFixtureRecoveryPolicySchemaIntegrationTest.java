package fi.karivanttinen.fplassistant.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import fi.karivanttinen.fplassistant.testsupport.TapasSeed;

class SharedFixtureRecoveryPolicySchemaIntegrationTest {

    private static final OwnedSyncTables TABLES = new OwnedSyncTables(
            "fpl_recov_run_test", "fpl_recov_payload_test", "fpl_recov_receipt_test",
            "fpl_recov_req_state_test", "fpl_recov_req_attempt_test", "fpl_recov_fixture_test",
            "fpl_recov_pfs_test", "fpl_recov_plan_slot_test", "fpl_recov_plan_runtime_test",
            "fpl_recov_fixture_manifest_test");
    private static OwnedSyncMonitoringDatabase database;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void initializeDatabase() {
        database = OwnedSyncMonitoringDatabase.work(new MockEnvironment()
                .withProperty("spring.datasource.url", required("DATABASE_URL"))
                .withProperty("spring.datasource.username", required("DB_USER"))
                .withProperty("spring.datasource.password", required("DB_PASSWORD")));
        jdbc = database.jdbc();
        TapasSeed.ensure(jdbc);
        new OwnedSyncSchema(jdbc, TABLES).initialize();
    }

    @AfterAll
    static void closeDatabase() {
        try {
            if (jdbc != null) jdbc.update("DELETE FROM " + TABLES.fixtureSync());
        } finally {
            if (database != null) database.close();
        }
    }

    @BeforeEach
    void clearOwnFixtureRows() {
        jdbc.update("DELETE FROM " + TABLES.fixtureSync());
    }

    @Test
    void addsNullableRecoveryMetadataWithoutChangingLegacyDueMarkers() {
        dropRecoveryColumnsToModelAnOlderOwnedTable();
        Instant firstFinished = Instant.parse("2026-09-19T10:00:00Z");
        Instant legacyDue = Instant.parse("2026-09-20T02:00:00Z");
        Instant updated = Instant.parse("2026-09-19T12:00:00Z");
        jdbc.update("""
                INSERT INTO %s(season_id, season_code, fixture_id, state, first_finished_observed_at,
                    next_due_at, attempt_count, last_error, alert_state, updated_at)
                VALUES (?, ?, 30001, 'VERIFYING', ?, ?, 7, 'legacy transport marker', 'DAILY_ONLY', ?)
                """.formatted(TABLES.fixtureSync()), TapasSeed.POPULATED_SEASON_ID,
                TapasSeed.POPULATED_SEASON_CODE, timestamp(firstFinished), timestamp(legacyDue), timestamp(updated));

        new OwnedSyncSchema(jdbc, TABLES).initialize();

        Map<String, Object> row = jdbc.queryForMap("SELECT first_finished_observed_at, next_due_at, attempt_count, "
                + "last_error, alert_state, recovery_day, recovery_started_at, recovery_until, due_reason, "
                + "transport_retry_at FROM " + TABLES.fixtureSync() + " WHERE fixture_id=30001");
        assertThat(((java.sql.Timestamp) row.get("first_finished_observed_at")).toInstant()).isEqualTo(firstFinished);
        assertThat(((java.sql.Timestamp) row.get("next_due_at")).toInstant()).isEqualTo(legacyDue);
        assertThat(row.get("attempt_count")).isEqualTo(7);
        assertThat(row.get("last_error")).isEqualTo("legacy transport marker");
        assertThat(row.get("alert_state")).isEqualTo("DAILY_ONLY");
        assertThat(row).containsEntry("recovery_day", null).containsEntry("recovery_started_at", null)
                .containsEntry("recovery_until", null).containsEntry("due_reason", null)
                .containsEntry("transport_retry_at", null);

        List<String> nullableColumns = jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema=current_schema() AND table_name=? AND column_name IN
                    ('recovery_day', 'recovery_started_at', 'recovery_until', 'due_reason', 'transport_retry_at')
                  AND is_nullable='YES'
                ORDER BY column_name
                """, String.class, TABLES.fixtureSync());
        assertThat(nullableColumns).containsExactly("due_reason", "recovery_day", "recovery_started_at",
                "recovery_until", "transport_retry_at");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema="
                + "current_schema() AND table_name='player_fixture_stats' AND column_name IN "
                + "('recovery_day', 'recovery_started_at', 'recovery_until', 'due_reason', 'transport_retry_at')",
                Integer.class)).isZero();
    }

    @Test
    void schemaAcceptsExactlyTwelveElapsedHoursAndRejectsOneExtraMicrosecondAcrossDst() {
        Instant start = Instant.parse("2026-10-24T22:30:00Z"); // 23:30 BST before the clock change
        Instant exactLimit = start.plusSeconds(12 * 60 * 60);
        jdbc.update("""
                INSERT INTO %s(season_id, season_code, fixture_id, state, updated_at)
                VALUES (?, ?, 30003, 'VERIFYING', ?)
                """.formatted(TABLES.fixtureSync()), TapasSeed.POPULATED_SEASON_ID,
                TapasSeed.POPULATED_SEASON_CODE, timestamp(start));

        jdbc.update("UPDATE " + TABLES.fixtureSync()
                        + " SET recovery_day=?, recovery_started_at=?, recovery_until=? WHERE fixture_id=30003",
                LocalDate.parse("2026-10-24"), timestamp(start), timestamp(exactLimit));
        assertThat(jdbc.queryForObject("SELECT recovery_until FROM " + TABLES.fixtureSync()
                + " WHERE fixture_id=30003", java.sql.Timestamp.class).toInstant()).isEqualTo(exactLimit);

        assertThatThrownBy(() -> jdbc.update("UPDATE " + TABLES.fixtureSync()
                + " SET recovery_until=? WHERE fixture_id=30003", timestamp(exactLimit.plusNanos(1_000))))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void schemaAcceptsOnlyTypedReasonsAndCoherentRecoveryDateIntervals() {
        jdbc.update("""
                INSERT INTO %s(season_id, season_code, fixture_id, state, updated_at)
                VALUES (?, ?, 30002, 'VERIFYING', ?)
                """.formatted(TABLES.fixtureSync()), TapasSeed.POPULATED_SEASON_ID,
                TapasSeed.POPULATED_SEASON_CODE, timestamp(Instant.parse("2026-09-20T02:00:00Z")));

        jdbc.update("UPDATE " + TABLES.fixtureSync() + " SET due_reason='POLICY', transport_retry_at=?, "
                        + "recovery_day=?, recovery_started_at=?, recovery_until=? WHERE fixture_id=30002",
                timestamp(Instant.parse("2026-09-20T04:00:00Z")), LocalDate.parse("2026-09-20"),
                timestamp(Instant.parse("2026-09-20T02:00:00Z")),
                timestamp(Instant.parse("2026-09-20T14:00:00Z")));

        assertThatThrownBy(() -> jdbc.update("UPDATE " + TABLES.fixtureSync()
                + " SET due_reason='GUESSED' WHERE fixture_id=30002"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE " + TABLES.fixtureSync()
                + " SET recovery_day=?, recovery_started_at=?, recovery_until=? WHERE fixture_id=30002",
                LocalDate.parse("2026-09-20"), timestamp(Instant.parse("2026-09-20T01:00:00Z")),
                timestamp(Instant.parse("2026-09-20T13:00:00Z"))))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE " + TABLES.fixtureSync()
                + " SET recovery_until=recovery_started_at WHERE fixture_id=30002"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE " + TABLES.fixtureSync()
                + " SET recovery_day=NULL WHERE fixture_id=30002"))
                .isInstanceOf(DataAccessException.class);
    }

    private static void dropRecoveryColumnsToModelAnOlderOwnedTable() {
        jdbc.execute("ALTER TABLE " + TABLES.fixtureSync() + " DROP CONSTRAINT IF EXISTS ck_"
                + TABLES.fixtureSync() + "_recovery_episode");
        jdbc.execute("ALTER TABLE " + TABLES.fixtureSync() + " DROP CONSTRAINT IF EXISTS ck_"
                + TABLES.fixtureSync() + "_due_reason");
        jdbc.execute("ALTER TABLE " + TABLES.fixtureSync()
                + " DROP COLUMN IF EXISTS transport_retry_at, DROP COLUMN IF EXISTS due_reason,"
                + " DROP COLUMN IF EXISTS recovery_until, DROP COLUMN IF EXISTS recovery_started_at,"
                + " DROP COLUMN IF EXISTS recovery_day");
    }

    private static java.sql.Timestamp timestamp(Instant instant) {
        return java.sql.Timestamp.from(instant);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
