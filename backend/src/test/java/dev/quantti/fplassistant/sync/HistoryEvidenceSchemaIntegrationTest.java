package dev.quantti.fplassistant.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import dev.quantti.fplassistant.testsupport.SharedStatsSeed;

class HistoryEvidenceSchemaIntegrationTest {

    private static final OwnedSyncTables TABLES = OwnedSyncTables.tests();
    private static JdbcTemplate jdbc;
    private static long generation;

    @BeforeAll
    static void initialize() {
        DataSource dataSource = new DriverManagerDataSource(requiredEnv("DATABASE_URL"),
                requiredEnv("DB_USER"), requiredEnv("DB_PASSWORD"));
        refuseProduction(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        SharedStatsSeed.ensure(jdbc);
        new OwnedSyncSchema(jdbc, TABLES).initialize();
        jdbc.execute("TRUNCATE " + TABLES.playerFixtureEvidence());
        jdbc.update("INSERT INTO " + TABLES.receipt()
                + "(season, publication_key) VALUES (?, ?) ON CONFLICT DO NOTHING",
                SharedStatsSeed.POPULATED_SEASON_CODE, "history-evidence-schema");
        generation = jdbc.queryForObject("""
                INSERT INTO %s(kind, season, coalescing_key, publication_key, priority, due_at,
                    requested_source_after, state, created_at, updated_at)
                VALUES ('PLAYER_HISTORY', ?, 'schema-test', 'history-evidence-schema', 1,
                    now(), now(), 'PENDING', now(), now()) RETURNING generation
                """.formatted(TABLES.run()), Long.class, SharedStatsSeed.POPULATED_SEASON_CODE);
    }

    @AfterAll
    static void cleanUp() {
        if (jdbc == null) return;
        jdbc.execute("TRUNCATE " + TABLES.playerFixtureEvidence());
        jdbc.update("DELETE FROM " + TABLES.run() + " WHERE generation = ?", generation);
        jdbc.update("DELETE FROM " + TABLES.receipt()
                + " WHERE season = ? AND publication_key = 'history-evidence-schema'",
                SharedStatsSeed.POPULATED_SEASON_CODE);
    }

    @Test
    void explicitSchemaHasRlsAndStoresPresenceWithoutMetricCopies() {
        Boolean rls = jdbc.queryForObject("SELECT relrowsecurity FROM pg_class WHERE oid = to_regclass(?)",
                Boolean.class, TABLES.playerFixtureEvidence());
        var columns = jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = ? ORDER BY ordinal_position
                """, String.class, TABLES.playerFixtureEvidence());

        assertThat(rls).isTrue();
        assertThat(columns).containsExactly("season_id", "season_code", "fixture_id", "player_id",
                "generation", "component_presence", "source_read_started_at",
                "source_read_finished_at");
        assertThat(columns).noneMatch(name -> name.startsWith("expected_")
                || name.equals("influence") || name.equals("defensive_contribution"));
    }

    @Test
    void generationsAreAppendOnlyAndDuplicateEvidenceIsRejected() {
        long firstMask = ComponentMask.EXPECTED_GOALS_AND_DEFCON;
        insert(generation, firstMask);
        long correctionGeneration = createRun("schema-correction");
        try {
            insert(correctionGeneration, 0);

            assertThat(jdbc.query("SELECT generation, component_presence FROM "
                    + TABLES.playerFixtureEvidence() + " WHERE fixture_id = 9101 AND player_id = 9201"
                    + " ORDER BY generation", (rs, row) -> ListRow.of(
                            rs.getLong("generation"), rs.getLong("component_presence"))))
                    .containsExactly(ListRow.of(generation, firstMask), ListRow.of(correctionGeneration, 0));
            assertThatThrownBy(() -> insert(generation, firstMask))
                    .isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> jdbc.update("INSERT INTO " + TABLES.playerFixtureEvidence()
                            + "(season_id, season_code, fixture_id, player_id, generation,"
                            + " component_presence, source_read_started_at, source_read_finished_at)"
                            + " VALUES (?, 'wrong-code', 9102, 9201, ?, 0, now(), now())",
                    SharedStatsSeed.POPULATED_SEASON_ID, generation))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("season id/code mismatch");
            assertThatThrownBy(() -> jdbc.update("UPDATE " + TABLES.playerFixtureEvidence()
                    + " SET component_presence = 0 WHERE generation = ?", generation))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("immutable");
        } finally {
            jdbc.execute("TRUNCATE " + TABLES.playerFixtureEvidence());
            jdbc.update("DELETE FROM " + TABLES.run() + " WHERE generation = ?", correctionGeneration);
            jdbc.update("DELETE FROM " + TABLES.receipt()
                    + " WHERE season = ? AND publication_key = 'schema-correction'",
                    SharedStatsSeed.POPULATED_SEASON_CODE);
        }
    }

    private static void insert(long runGeneration, long mask) {
        jdbc.update("INSERT INTO " + TABLES.playerFixtureEvidence() + "(season_id, season_code,"
                        + " fixture_id, player_id, generation, component_presence,"
                        + " source_read_started_at, source_read_finished_at)"
                        + " VALUES (?, ?, 9101, 9201, ?, ?, TIMESTAMPTZ '2025-08-16 16:00:00Z',"
                        + " TIMESTAMPTZ '2025-08-16 16:00:01Z')",
                SharedStatsSeed.POPULATED_SEASON_ID, SharedStatsSeed.POPULATED_SEASON_CODE, runGeneration, mask);
    }

    private static long createRun(String key) {
        jdbc.update("INSERT INTO " + TABLES.receipt()
                + "(season, publication_key) VALUES (?, ?) ON CONFLICT DO NOTHING",
                SharedStatsSeed.POPULATED_SEASON_CODE, key);
        return jdbc.queryForObject("""
                INSERT INTO %s(kind, season, coalescing_key, publication_key, priority, due_at,
                    requested_source_after, state, created_at, updated_at)
                VALUES ('PLAYER_HISTORY', ?, ?, ?, 1, now(), now(), 'PENDING', now(), now())
                RETURNING generation
                """.formatted(TABLES.run()), Long.class, SharedStatsSeed.POPULATED_SEASON_CODE, key, key);
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private static void refuseProduction(DataSource dataSource) {
        JdbcTemplate probe = new JdbcTemplate(dataSource);
        if (probe.queryForObject("SELECT to_regclass('public.manager') IS NOT NULL", Boolean.class)) {
            throw new IllegalStateException("Refusing to initialize test schema on shared database");
        }
    }

    private record ListRow(long generation, long mask) {
        static ListRow of(long generation, long mask) { return new ListRow(generation, mask); }
    }

    private static final class ComponentMask {
        static final long EXPECTED_GOALS_AND_DEFCON = HistoryParser.Component.EXPECTED_GOALS.bit()
                | HistoryParser.Component.DEFENSIVE_CONTRIBUTION.bit();
    }
}
