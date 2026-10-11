package fi.karivanttinen.fplassistant.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import fi.karivanttinen.fplassistant.testsupport.TapasSeed;

class HistoryEvidenceSchemaIntegrationTest {

    private static final OwnedSyncTables TABLES = OwnedSyncTables.tests();
    private static final int ISOLATED_SEASON_ID = 929_926;
    private static final String ISOLATED_SEASON_CODE = "I-2026-27";
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static long generation;
    private static long incrementalGeneration;

    @BeforeAll
    static void initialize() {
        DataSource dataSource = new DriverManagerDataSource(requiredEnv("DATABASE_URL"),
                requiredEnv("DB_USER"), requiredEnv("DB_PASSWORD"));
        refuseProduction(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        TapasSeed.ensure(jdbc);
        jdbc.update("INSERT INTO season (id, code, name, start_date, is_current)"
                        + " VALUES (?, ?, 'Incremental schema test', DATE '2026-08-21', false)"
                        + " ON CONFLICT (id) DO NOTHING", ISOLATED_SEASON_ID, ISOLATED_SEASON_CODE);
        if (!ISOLATED_SEASON_CODE.equals(jdbc.queryForObject(
                "SELECT code FROM season WHERE id=?", String.class, ISOLATED_SEASON_ID))) {
            throw new IllegalStateException("Isolated incremental test season id is already in use");
        }
        new OwnedSyncSchema(jdbc, TABLES).initialize();
        jdbc.execute("TRUNCATE " + TABLES.playerFixtureCurrent() + ", "
                + TABLES.playerFixtureEvidence() + " CASCADE");
        generation = createRun("history-evidence-schema", TapasSeed.POPULATED_SEASON_CODE);
        incrementalGeneration = createRun("incremental-schema-test", ISOLATED_SEASON_CODE);
    }

    @AfterAll
    static void cleanUp() {
        if (jdbc == null) return;
        jdbc.execute("TRUNCATE " + TABLES.playerFixtureCurrent() + ", "
                + TABLES.playerFixtureEvidence() + " CASCADE");
        jdbc.update("DELETE FROM " + TABLES.gameweekStatSync() + " WHERE season_id=?", ISOLATED_SEASON_ID);
        jdbc.update("DELETE FROM " + TABLES.run() + " WHERE generation IN (?, ?)",
                generation, incrementalGeneration);
        jdbc.update("DELETE FROM " + TABLES.receipt()
                + " WHERE publication_key IN ('history-evidence-schema', 'incremental-schema-test')");
        jdbc.update("DELETE FROM season WHERE id = ?", ISOLATED_SEASON_ID);
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

        assertThat(OwnedSyncTables.defaults().playerFixtureCurrent()).isEqualTo("fpl_player_fixture_current");
        assertThat(OwnedSyncTables.tests().playerFixtureCurrent()).isEqualTo("fpl_player_fixture_current_test");
        assertThat(OwnedSyncTables.defaults().gameweekStatSync()).isEqualTo("fpl_gameweek_stat_sync");
        assertThat(OwnedSyncTables.tests().gameweekStatSync()).isEqualTo("fpl_gameweek_stat_sync_test");
        assertThat(new OwnedSyncTables("fpl_recov_run_test", "payload", "receipt")
                .playerFixtureCurrent()).isEqualTo("fpl_recov_run_test_pfs_current");
        assertThat(new OwnedSyncTables("fpl_schown_run_test", "payload", "receipt")
                .playerFixtureCurrent()).isEqualTo("fpl_schown_run_test_pfs_current");
        for (String table : List.of(TABLES.playerFixtureCurrent(), TABLES.gameweekStatSync())) {
            assertThat(jdbc.queryForObject("SELECT relrowsecurity FROM pg_class WHERE oid=to_regclass(?)",
                    Boolean.class, table)).isTrue();
        }
        var currentColumns = jdbc.queryForList("SELECT column_name FROM information_schema.columns"
                + " WHERE table_schema=current_schema() AND table_name=? ORDER BY ordinal_position", String.class,
                TABLES.playerFixtureCurrent());
        assertThat(currentColumns).containsExactlyInAnyOrder("season_id", "season_code", "fixture_id", "player_id",
                "row_state", "source_run_generation", "evidence_generation", "row_fingerprint",
                "physical_fingerprint", "source_read_started_at", "source_read_finished_at", "updated_at");
        assertThat(currentColumns).noneMatch(name -> name.startsWith("expected_")
                || name.equals("defensive_contribution"));
    }

    @Test
    void currentRowEvidenceIsVisibleBeforeGameweekFinalityAndSupportsWithdrawal() {
        tx.executeWithoutResult(status -> {
            insertIncrementalEvidence(93101, 94101);
            insertCurrent(93101, 94101, "VALID", incrementalGeneration, "a".repeat(64));
            insertCurrent(93102, 94102, "WITHDRAWN", null, null);

            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.gameweekStatSync()
                    + " WHERE season_id=? AND gameweek=1", Integer.class, ISOLATED_SEASON_ID)).isZero();
            assertThat(jdbc.queryForList("SELECT row_state FROM " + TABLES.playerFixtureCurrent()
                    + " WHERE season_id=? ORDER BY fixture_id", String.class, ISOLATED_SEASON_ID))
                    .containsExactly("VALID", "WITHDRAWN");
            assertThat(jdbc.queryForObject("SELECT evidence_generation FROM " + TABLES.playerFixtureCurrent()
                    + " WHERE season_id=? AND fixture_id=93101", Long.class, ISOLATED_SEASON_ID))
                    .isEqualTo(incrementalGeneration);
            assertThat(jdbc.queryForObject("SELECT evidence_generation FROM " + TABLES.playerFixtureCurrent()
                    + " WHERE season_id=? AND fixture_id=93102", Long.class, ISOLATED_SEASON_ID)).isNull();
            assertThat(jdbc.queryForObject("SELECT source_run_generation FROM " + TABLES.playerFixtureCurrent()
                    + " WHERE season_id=? AND fixture_id=93102", Long.class, ISOLATED_SEASON_ID))
                    .isEqualTo(incrementalGeneration);
            status.setRollbackOnly();
        });

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            insertIncrementalEvidence(93201, 94201);
            insertCurrent(93202, 94201, "VALID", incrementalGeneration, "b".repeat(64));
        })).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                insertCurrent(93301, 94301, "VALID", null, "c".repeat(64))))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            insertIncrementalEvidence(93302, 94302);
            insertCurrent(93302, 94302, "VALID", incrementalGeneration, null);
        })).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                insertCurrent(93401, 94401, "WITHDRAWN", incrementalGeneration, "d".repeat(64))))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                insertCurrent(ISOLATED_SEASON_ID, "wrong-code", 93501, 94501,
                        "WITHDRAWN", null, null))).isInstanceOf(DataAccessException.class);
    }

    @Test
    void gameweekSchemaAllowsBlankFinalManifestButRejectsUncheckedOrUnfinishedFinality() {
        String signature = "e".repeat(64);
        tx.executeWithoutResult(status -> {
            insertBlankFinalGameweek(38, optionalCoverageJson());
            assertThat(jdbc.queryForObject("SELECT state FROM " + TABLES.gameweekStatSync()
                    + " WHERE season_id=? AND gameweek=38", String.class, ISOLATED_SEASON_ID))
                    .isEqualTo("FINALIZED");
            assertThat(jdbc.queryForObject("SELECT cardinality(final_attempt_required_player_ids)"
                    + " FROM " + TABLES.gameweekStatSync()
                    + " WHERE season_id=? AND gameweek=38", Integer.class, ISOLATED_SEASON_ID)).isZero();
            assertThat(jdbc.queryForObject("SELECT finalized_row_revision_fingerprint FROM "
                    + TABLES.gameweekStatSync() + " WHERE season_id=? AND gameweek=38",
                    String.class, ISOLATED_SEASON_ID)).isEqualTo("d".repeat(64));
            assertThat(jdbc.queryForObject("SELECT cardinality(final_attempt_outstanding_player_ids)"
                    + " FROM " + TABLES.gameweekStatSync()
                    + " WHERE season_id=? AND gameweek=38", Integer.class, ISOLATED_SEASON_ID)).isZero();
            java.time.Instant finalizedFloor = jdbc.queryForObject("SELECT finalized_source_floor FROM "
                    + TABLES.gameweekStatSync() + " WHERE season_id=? AND gameweek=38",
                    java.sql.Timestamp.class, ISOLATED_SEASON_ID).toInstant();
            java.time.Instant attemptFloor = finalizedFloor.plusSeconds(60);
            jdbc.update("UPDATE " + TABLES.gameweekStatSync()
                            + " SET state='FINAL_PENDING', current_invalidation_revision=1,"
                            + " current_invalidation_floor=?, final_attempt_source_floor=?,"
                            + " final_attempt_invalidation_revision=1 WHERE season_id=? AND gameweek=38",
                    java.sql.Timestamp.from(attemptFloor), java.sql.Timestamp.from(attemptFloor),
                    ISOLATED_SEASON_ID);
            assertThat(jdbc.queryForObject("SELECT finalized_source_floor FROM " + TABLES.gameweekStatSync()
                    + " WHERE season_id=? AND gameweek=38", java.sql.Timestamp.class, ISOLATED_SEASON_ID)
                    .toInstant()).isEqualTo(finalizedFloor);
            assertThat(jdbc.queryForObject("SELECT final_attempt_source_floor FROM " + TABLES.gameweekStatSync()
                    + " WHERE season_id=? AND gameweek=38", java.sql.Timestamp.class, ISOLATED_SEASON_ID)
                    .toInstant()).isEqualTo(attemptFloor);
            status.setRollbackOnly();
        });

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            insertBlankFinalGameweek(36, optionalCoverageJson());
            jdbc.update("UPDATE " + TABLES.gameweekStatSync()
                            + " SET current_invalidation_revision=1, current_invalidation_floor=now()"
                            + " WHERE season_id=? AND gameweek=36", ISOLATED_SEASON_ID);
            jdbc.update("UPDATE " + TABLES.gameweekStatSync()
                            + " SET current_invalidation_revision=0 WHERE season_id=? AND gameweek=36",
                    ISOLATED_SEASON_ID);
        })).isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                insertBlankFinalGameweek(37, "{}"))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                insertBlankFinalGameweek(37, null)))
                .as("FINALIZED receipt with valid fingerprints cannot omit optional coverage")
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                insertBlankFinalGameweek(37, optionalCoverageJson(
                        "\"sourceRows\":\"0\",\"presentRows\":0,\"supported\":true"))))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                insertBlankFinalGameweek(37, optionalCoverageJson(
                        "\"sourceRows\":1,\"presentRows\":2,\"supported\":true"))))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                insertBlankFinalGameweek(37, optionalCoverageJson(
                        "\"sourceRows\":2147483648,\"presentRows\":0,\"supported\":true"))))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                insertBlankFinalGameweek(37, optionalCoverageJson(
                        "\"sourceRows\":1,\"presentRows\":1,\"supported\":false"))))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                insertBlankFinalGameweek(37, optionalCoverageJson(), null)))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            insertBlankFinalGameweek(37, optionalCoverageJson());
            jdbc.update("UPDATE " + TABLES.gameweekStatSync()
                    + " SET final_attempt_population_fingerprint=NULL WHERE season_id=? AND gameweek=37",
                    ISOLATED_SEASON_ID);
        })).as("SQL NULL cannot detach a checkpoint from its population fingerprint")
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            insertBlankFinalGameweek(37, optionalCoverageJson());
            jdbc.update("UPDATE " + TABLES.gameweekStatSync()
                            + " SET final_attempt_required_player_ids=ARRAY[10],"
                            + " final_attempt_completed_player_ids=ARRAY[],"
                            + " final_attempt_outstanding_player_ids=ARRAY[10]"
                            + " WHERE season_id=? AND gameweek=37",
                    ISOLATED_SEASON_ID);
        })).isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO %s(season_id, season_code, gameweek, state, event_finished, data_checked,
                    fixture_manifest_complete, all_assigned_fixtures_finished, source_signature, updated_at)
                VALUES (?, ?, 1, 'FINAL_PENDING', TRUE, NULL, TRUE, TRUE, ?, now())
                """.formatted(TABLES.gameweekStatSync()), ISOLATED_SEASON_ID,
                ISOLATED_SEASON_CODE, signature))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO %s(season_id, season_code, gameweek, state, event_finished, data_checked,
                    fixture_manifest_complete, all_assigned_fixtures_finished, source_signature, updated_at)
                VALUES (?, ?, 2, 'FINAL_PENDING', TRUE, TRUE, TRUE, FALSE, ?, now())
                """.formatted(TABLES.gameweekStatSync()), ISOLATED_SEASON_ID,
                ISOLATED_SEASON_CODE, signature))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO %s(season_id, season_code, gameweek, state, event_finished, data_checked,
                    fixture_manifest_complete, all_assigned_fixtures_finished, source_signature,
                    final_attempt_source_signature, final_attempt_source_floor,
                    final_attempt_required_player_ids, final_attempt_completed_player_ids,
                    final_attempt_outstanding_player_ids, updated_at)
                VALUES (?, ?, 3, 'FINAL_PENDING', TRUE, TRUE, TRUE, TRUE, ?, ?, now(),
                    ARRAY[10,11], ARRAY[10], ARRAY[]::INTEGER[], now())
                """.formatted(TABLES.gameweekStatSync()), ISOLATED_SEASON_ID,
                ISOLATED_SEASON_CODE, signature, signature))).isInstanceOf(DataAccessException.class);
    }

    @Test
    void initializationReplacesOldNullPermittingReceiptConstraint() {
        String constraint = "ck_" + TABLES.gameweekStatSync() + "_receipt";
        jdbc.execute("ALTER TABLE " + TABLES.gameweekStatSync() + " DROP CONSTRAINT " + constraint);
        jdbc.execute("""
                ALTER TABLE %s ADD CONSTRAINT %s CHECK (
                    (finalized_source_signature IS NULL AND finalized_row_revision_fingerprint IS NULL
                        AND finalized_source_floor IS NULL AND finalized_invalidation_revision IS NULL
                        AND finalized_run_generation IS NULL AND finalized_at IS NULL
                        AND finalized_mandatory_manifest_complete IS NULL
                        AND finalized_optional_component_coverage IS NULL)
                    OR (finalized_source_signature IS NOT NULL
                        AND finalized_row_revision_fingerprint IS NOT NULL
                        AND finalized_row_revision_fingerprint ~ '^[0-9a-f]{64}$'
                        AND finalized_source_floor IS NOT NULL
                        AND finalized_invalidation_revision IS NOT NULL
                        AND finalized_run_generation IS NOT NULL AND finalized_at IS NOT NULL
                        AND finalized_source_floor <= finalized_at
                        AND finalized_mandatory_manifest_complete IS TRUE
                        AND fpl_owned_sync_optional_coverage_valid(
                            finalized_optional_component_coverage)))
                """.formatted(TABLES.gameweekStatSync(), constraint));

        tx.executeWithoutResult(status -> {
            insertBlankFinalGameweek(35, null);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + TABLES.gameweekStatSync()
                    + " WHERE season_id=? AND gameweek=35", Integer.class, ISOLATED_SEASON_ID)).isEqualTo(1);
            status.setRollbackOnly();
        });

        new OwnedSyncSchema(jdbc, TABLES).initialize();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> insertBlankFinalGameweek(35, null)))
                .as("reinitialized schema must reject SQL NULL from the STRICT coverage validator")
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void initializationSafelyRetiresOldPendingAndFinalizedGameweekProofs() {
        OwnedSyncTables oldTables = new OwnedSyncTables("fpl_gwmig_run", "fpl_gwmig_payload",
                "fpl_gwmig_receipt", "fpl_gwmig_reqstate", "fpl_gwmig_reqattempt",
                "fpl_gwmig_fixture", "fpl_gwmig_pfsevidence", "fpl_gwmig_slot",
                "fpl_gwmig_runtime", "fpl_gwmig_deps");
        String table = oldTables.gameweekStatSync();
        jdbc.execute("DROP TABLE IF EXISTS " + table);
        jdbc.execute("""
                CREATE TABLE %s (
                    season_id INTEGER NOT NULL,
                    season_code VARCHAR(10) NOT NULL,
                    gameweek INTEGER NOT NULL,
                    state VARCHAR(20) NOT NULL,
                    event_finished BOOLEAN,
                    data_checked BOOLEAN,
                    fixture_manifest_complete BOOLEAN NOT NULL,
                    all_assigned_fixtures_finished BOOLEAN NOT NULL,
                    source_signature VARCHAR(64),
                    first_checked_at TIMESTAMPTZ,
                    current_invalidation_revision BIGINT NOT NULL DEFAULT 0,
                    current_invalidation_floor TIMESTAMPTZ,
                    final_attempt_source_signature VARCHAR(64),
                    final_attempt_source_floor TIMESTAMPTZ,
                    final_attempt_invalidation_revision BIGINT,
                    final_attempt_required_player_ids INTEGER[] NOT NULL DEFAULT '{}',
                    final_attempt_completed_player_ids INTEGER[] NOT NULL DEFAULT '{}',
                    final_attempt_outstanding_player_ids INTEGER[] NOT NULL DEFAULT '{}',
                    final_attempt_page_read_started_at TIMESTAMPTZ,
                    final_attempt_page_read_finished_at TIMESTAMPTZ,
                    finalized_source_signature VARCHAR(64),
                    finalized_row_revision_fingerprint VARCHAR(64),
                    finalized_source_floor TIMESTAMPTZ,
                    finalized_invalidation_revision BIGINT,
                    finalized_run_generation BIGINT,
                    finalized_at TIMESTAMPTZ,
                    finalized_mandatory_manifest_complete BOOLEAN,
                    finalized_optional_component_coverage JSONB,
                    updated_at TIMESTAMPTZ NOT NULL,
                    PRIMARY KEY (season_id, gameweek)
                )
                """.formatted(table));
        java.time.Instant writtenAt = java.time.Instant.parse("2026-09-01T12:00:00Z");
        jdbc.update("""
                INSERT INTO %s(season_id,season_code,gameweek,state,event_finished,data_checked,
                    fixture_manifest_complete,all_assigned_fixtures_finished,source_signature,
                    first_checked_at,current_invalidation_revision,current_invalidation_floor,
                    final_attempt_source_signature,final_attempt_source_floor,final_attempt_invalidation_revision,
                    final_attempt_required_player_ids,final_attempt_completed_player_ids,
                    final_attempt_outstanding_player_ids,updated_at)
                VALUES (?,?,31,'FINAL_PENDING',TRUE,TRUE,TRUE,TRUE,?,?,0,?,?,?,?,ARRAY[10,11],ARRAY[10],ARRAY[11],?)
                """.formatted(table), ISOLATED_SEASON_ID, ISOLATED_SEASON_CODE, "a".repeat(64),
                java.sql.Timestamp.from(writtenAt), java.sql.Timestamp.from(writtenAt), "a".repeat(64),
                java.sql.Timestamp.from(writtenAt), 0, java.sql.Timestamp.from(writtenAt));
        jdbc.update("""
                INSERT INTO %s(season_id,season_code,gameweek,state,event_finished,data_checked,
                    fixture_manifest_complete,all_assigned_fixtures_finished,source_signature,
                    first_checked_at,current_invalidation_revision,current_invalidation_floor,
                    final_attempt_source_signature,final_attempt_source_floor,final_attempt_invalidation_revision,
                    final_attempt_required_player_ids,final_attempt_completed_player_ids,
                    final_attempt_outstanding_player_ids,finalized_source_signature,
                    finalized_row_revision_fingerprint,finalized_source_floor,finalized_invalidation_revision,
                    finalized_run_generation,finalized_at,finalized_mandatory_manifest_complete,
                    finalized_optional_component_coverage,updated_at)
                VALUES (?,?,32,'FINALIZED',TRUE,TRUE,TRUE,TRUE,?,?,0,?,?,?,?,ARRAY[12],ARRAY[12],ARRAY[]::INTEGER[],
                    ?,?,?,0,?,?,TRUE,?::jsonb,?)
                """.formatted(table), ISOLATED_SEASON_ID, ISOLATED_SEASON_CODE, "b".repeat(64),
                java.sql.Timestamp.from(writtenAt), java.sql.Timestamp.from(writtenAt), "b".repeat(64),
                java.sql.Timestamp.from(writtenAt), 0, "b".repeat(64), "c".repeat(64),
                java.sql.Timestamp.from(writtenAt), incrementalGeneration, java.sql.Timestamp.from(writtenAt),
                optionalCoverageJson(), java.sql.Timestamp.from(writtenAt));

        new OwnedSyncSchema(jdbc, oldTables).initialize();
        assertInvalidatedOldGameweeks(table, writtenAt);
        String archivedProof = jdbc.queryForObject("SELECT legacy_finality_evidence::text FROM " + table
                + " WHERE season_id=? AND gameweek=32", String.class, ISOLATED_SEASON_ID);
        assertThat(archivedProof).contains("FINALIZED", "c".repeat(64), "finalized_at");

        new OwnedSyncSchema(jdbc, oldTables).initialize();
        assertInvalidatedOldGameweeks(table, writtenAt);
        assertThat(jdbc.queryForObject("SELECT legacy_finality_evidence::text FROM " + table
                + " WHERE season_id=? AND gameweek=32", String.class, ISOLATED_SEASON_ID))
                .isEqualTo(archivedProof);
        jdbc.update("DELETE FROM " + table + " WHERE season_id=?", ISOLATED_SEASON_ID);
    }

    private void assertInvalidatedOldGameweeks(String table, java.time.Instant writtenAt) {
        assertThat(jdbc.query("""
                SELECT gameweek,state,final_attempt_source_signature,final_attempt_source_floor,
                       final_attempt_required_player_ids,finalized_source_signature,finalized_at,
                       legacy_finality_evidence,updated_at
                FROM %s WHERE season_id=? ORDER BY gameweek
                """.formatted(table), (rs, row) ->
                new LegacyGameweek(rs.getInt("gameweek"), rs.getString("state"),
                        rs.getString("final_attempt_source_signature"),
                        rs.getTimestamp("final_attempt_source_floor"), rs.getArray("final_attempt_required_player_ids"),
                        rs.getString("finalized_source_signature"), rs.getTimestamp("finalized_at"),
                        rs.getString("legacy_finality_evidence"), rs.getTimestamp("updated_at")), ISOLATED_SEASON_ID))
                .hasSize(2).allSatisfy(row -> {
                    assertThat(row.state()).isEqualTo("FINAL_PENDING");
                    assertThat(row.attemptSignature()).isNull();
                    assertThat(row.attemptFloor()).isNull();
                    assertThat(row.requiredIds()).isNotNull();
                    assertThat(row.finalizedSignature()).isNull();
                    assertThat(row.finalizedAt()).isNull();
                    assertThat(row.legacyEvidence()).isNotBlank();
                    assertThat(row.updatedAt().toInstant()).isEqualTo(writtenAt);
                });
        assertThat(jdbc.queryForList("SELECT cardinality(final_attempt_required_player_ids) FROM " + table
                + " WHERE season_id=? ORDER BY gameweek", Integer.class, ISOLATED_SEASON_ID))
                .containsExactly(0, 0);
        assertThat(jdbc.queryForList("SELECT final_attempt_source_read_started_at FROM " + table
                + " WHERE season_id=? ORDER BY gameweek", java.sql.Timestamp.class, ISOLATED_SEASON_ID))
                .containsExactly(null, null);
        assertThat(jdbc.queryForList("SELECT final_attempt_initial_source_read_finished_at FROM " + table
                + " WHERE season_id=? ORDER BY gameweek", java.sql.Timestamp.class, ISOLATED_SEASON_ID))
                .containsExactly(null, null);
    }

    private record LegacyGameweek(int gameweek, String state, String attemptSignature,
                                  java.sql.Timestamp attemptFloor, java.sql.Array requiredIds,
                                  String finalizedSignature, java.sql.Timestamp finalizedAt,
                                  String legacyEvidence, java.sql.Timestamp updatedAt) { }

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
                    TapasSeed.POPULATED_SEASON_ID, generation))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("season id/code mismatch");
            assertThatThrownBy(() -> jdbc.update("UPDATE " + TABLES.playerFixtureEvidence()
                    + " SET component_presence = 0 WHERE generation = ?", generation))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("immutable");
        } finally {
            jdbc.execute("TRUNCATE " + TABLES.playerFixtureCurrent() + ", "
                    + TABLES.playerFixtureEvidence() + " CASCADE");
            jdbc.update("DELETE FROM " + TABLES.run() + " WHERE generation = ?", correctionGeneration);
            jdbc.update("DELETE FROM " + TABLES.receipt()
                    + " WHERE season = ? AND publication_key = 'schema-correction'",
                    TapasSeed.POPULATED_SEASON_CODE);
        }
    }

    private static void insert(long runGeneration, long mask) {
        jdbc.update("INSERT INTO " + TABLES.playerFixtureEvidence() + "(season_id, season_code,"
                        + " fixture_id, player_id, generation, component_presence,"
                        + " source_read_started_at, source_read_finished_at)"
                        + " VALUES (?, ?, 9101, 9201, ?, ?, TIMESTAMPTZ '2025-08-16 16:00:00Z',"
                        + " TIMESTAMPTZ '2025-08-16 16:00:01Z')",
                TapasSeed.POPULATED_SEASON_ID, TapasSeed.POPULATED_SEASON_CODE, runGeneration, mask);
    }

    private static long createRun(String key) {
        return createRun(key, TapasSeed.POPULATED_SEASON_CODE);
    }

    private static long createRun(String key, String seasonCode) {
        jdbc.update("INSERT INTO " + TABLES.receipt()
                + "(season, publication_key) VALUES (?, ?) ON CONFLICT DO NOTHING", seasonCode, key);
        return jdbc.queryForObject("""
                INSERT INTO %s(kind, season, coalescing_key, publication_key, priority, due_at,
                    requested_source_after, state, created_at, updated_at)
                VALUES ('PLAYER_HISTORY', ?, ?, ?, 1, now(), now(), 'PENDING', now(), now())
                RETURNING generation
                """.formatted(TABLES.run()), Long.class, seasonCode, key, key);
    }

    private static void insertIncrementalEvidence(int fixtureId, int playerId) {
        jdbc.update("INSERT INTO " + TABLES.playerFixtureEvidence() + "(season_id, season_code,"
                        + " fixture_id, player_id, generation, component_presence,"
                        + " source_read_started_at, source_read_finished_at)"
                        + " VALUES (?, ?, ?, ?, ?, 0, TIMESTAMPTZ '2026-08-22 14:00:00Z',"
                        + " TIMESTAMPTZ '2026-08-22 14:00:01Z')",
                ISOLATED_SEASON_ID, ISOLATED_SEASON_CODE, fixtureId, playerId, incrementalGeneration);
    }

    private static void insertCurrent(int fixtureId, int playerId, String state,
                                      Long evidenceGeneration, String fingerprint) {
        insertCurrent(ISOLATED_SEASON_ID, ISOLATED_SEASON_CODE, fixtureId, playerId, state,
                evidenceGeneration, fingerprint);
    }

    private static void insertCurrent(int seasonId, String seasonCode, int fixtureId, int playerId,
                                      String state, Long evidenceGeneration, String fingerprint) {
        long sourceGeneration = evidenceGeneration == null ? incrementalGeneration : evidenceGeneration;
        jdbc.update("INSERT INTO " + TABLES.playerFixtureCurrent() + "(season_id, season_code,"
                        + " fixture_id, player_id, row_state, source_run_generation, evidence_generation,"
                        + " row_fingerprint, source_read_started_at, source_read_finished_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, TIMESTAMPTZ '2026-08-22 14:00:00Z',"
                        + " TIMESTAMPTZ '2026-08-22 14:00:01Z', now())",
                seasonId, seasonCode, fixtureId, playerId, state, sourceGeneration,
                evidenceGeneration, fingerprint);
    }

    private static void insertBlankFinalGameweek(int gameweek, String coverage) {
        insertBlankFinalGameweek(gameweek, coverage, "d".repeat(64));
    }

    private static void insertBlankFinalGameweek(int gameweek, String coverage, String rowFingerprint) {
        String signature = "e".repeat(64);
        jdbc.update("""
                INSERT INTO %s(season_id, season_code, gameweek, state, event_finished, data_checked,
                    fixture_manifest_complete, all_assigned_fixtures_finished, source_signature,
                    final_attempt_source_signature, final_attempt_source_floor,
                    final_attempt_invalidation_revision, final_attempt_population_fingerprint,
                    final_attempt_population_manifest,final_attempt_source_read_started_at,
                    final_attempt_initial_source_read_finished_at,final_attempt_source_read_finished_at,
                    finalized_source_signature,
                    finalized_population_fingerprint,finalized_row_revision_fingerprint,finalized_source_floor,
                    finalized_validation_read_started_at,finalized_validation_read_finished_at,
                    finalized_source_read_started_at,finalized_source_read_finished_at,
                    finalized_invalidation_revision, finalized_run_generation, finalized_page_evidence,
                    finalized_at, finalized_mandatory_manifest_complete, finalized_optional_component_coverage,
                    updated_at)
                VALUES (?, ?, ?, 'FINALIZED', TRUE, TRUE, TRUE, TRUE, ?, ?, now(), 0, ?,
                    ('{"bootstrapFingerprint":"ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",'
                    || '"catalogue":{},"participants":{},"known":{},"fixtureIdentities":{}}')::jsonb,
                    now(),now(),now(),?, ?, ?, now(), now(), now(), now(), now(), 0, ?, '{}'::jsonb, now(), TRUE, ?::jsonb, now())
                """.formatted(TABLES.gameweekStatSync()), ISOLATED_SEASON_ID, ISOLATED_SEASON_CODE,
                gameweek, signature, signature, "f".repeat(64), signature, "f".repeat(64), rowFingerprint,
                incrementalGeneration, coverage);
    }

    private static String optionalCoverageJson() {
        return optionalCoverageJson("\"sourceRows\":0,\"presentRows\":0,\"supported\":true");
    }

    private static String optionalCoverageJson(String componentCoverage) {
        return "{" + java.util.Arrays.stream(HistoryParser.Component.values())
                .map(component -> "\"" + component.name() + "\":{" + componentCoverage + "}")
                .collect(java.util.stream.Collectors.joining(",")) + "}";
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
