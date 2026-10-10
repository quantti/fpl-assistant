package fi.karivanttinen.fplassistant.tools;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import fi.karivanttinen.fplassistant.sync.HistoryParser;
import fi.karivanttinen.fplassistant.sync.PlayerFixtureFingerprint;
import fi.karivanttinen.fplassistant.testsupport.TapasSeed;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class PfsReadSourceIntegrationTest {

    private static final String FIXTURES = "pfs_reader_fixture_test";
    private static final String EVIDENCE = "pfs_reader_evidence_test";
    private static final String CURRENT = "pfs_reader_current_test";
    private static final String PAGE_RECEIPTS = "pfs_reader_receipt_test";

    @Autowired JdbcTemplate jdbc;

    @BeforeAll
    static void setUp(@Autowired JdbcTemplate jdbc) {
        TapasSeed.ensure(jdbc);
        jdbc.execute("DROP TABLE IF EXISTS " + PAGE_RECEIPTS);
        jdbc.execute("DROP TABLE IF EXISTS " + CURRENT);
        jdbc.execute("DROP TABLE IF EXISTS " + EVIDENCE);
        jdbc.execute("DROP TABLE IF EXISTS " + FIXTURES);
        jdbc.execute("""
                CREATE TABLE pfs_reader_fixture_test (
                    season_id INTEGER NOT NULL, fixture_id INTEGER NOT NULL, state VARCHAR(24) NOT NULL,
                    observation_generation BIGINT, current_verified_generation BIGINT,
                    last_verified_generation BIGINT, last_verified_at TIMESTAMPTZ,
                    PRIMARY KEY (season_id, fixture_id))
                """);
        jdbc.execute("""
                CREATE TABLE pfs_reader_evidence_test (
                    season_id INTEGER NOT NULL, fixture_id INTEGER NOT NULL, player_id INTEGER NOT NULL,
                    generation BIGINT NOT NULL, component_presence BIGINT NOT NULL,
                    season_code VARCHAR(10) NOT NULL,
                    source_read_started_at TIMESTAMPTZ NOT NULL,
                    source_read_finished_at TIMESTAMPTZ NOT NULL,
                    PRIMARY KEY (season_id, fixture_id, player_id, generation))
                """);
        jdbc.execute("CREATE TABLE " + CURRENT + " (season_id INTEGER, season_code VARCHAR(10),"
                + " fixture_id INTEGER, player_id INTEGER, row_state VARCHAR(12),"
                + " source_run_generation BIGINT, evidence_generation BIGINT, row_fingerprint VARCHAR(64),"
                + " physical_fingerprint VARCHAR(64), source_read_started_at TIMESTAMPTZ,"
                + " source_read_finished_at TIMESTAMPTZ)");
        jdbc.execute("CREATE TABLE " + PAGE_RECEIPTS + " (season VARCHAR(10), publication_key VARCHAR(200),"
                + " latest_generation BIGINT, source_read_started_at TIMESTAMPTZ,"
                + " source_read_finished_at TIMESTAMPTZ)");
    }

    @org.junit.jupiter.api.BeforeEach
    void clearOwnedReaderMetadata() {
        jdbc.update("DELETE FROM " + PAGE_RECEIPTS);
        jdbc.update("DELETE FROM " + CURRENT);
        jdbc.update("DELETE FROM " + EVIDENCE);
        jdbc.update("DELETE FROM " + FIXTURES);
    }

    @AfterAll
    static void tearDown(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("DROP TABLE IF EXISTS " + PAGE_RECEIPTS);
        jdbc.execute("DROP TABLE IF EXISTS " + CURRENT);
        jdbc.execute("DROP TABLE IF EXISTS " + EVIDENCE);
        jdbc.execute("DROP TABLE IF EXISTS " + FIXTURES);
    }

    @Test
    void offWithoutTablesKeepsLegacyArchiveAndActiveFailsClosed() {
        assertThat(rows(new PfsReadSource(jdbc, "OFF", "", "missing_fixture_table", "missing_evidence_table",
                "missing_current_table", "missing_page_receipt_table"), 9101)).isNotEmpty();
        assertThat(rows(new PfsReadSource(jdbc, "ACTIVE", TapasSeed.POPULATED_SEASON_CODE,
                "missing_fixture_table", "missing_evidence_table", "missing_current_table",
                "missing_page_receipt_table"), 9101)).isEmpty();
    }

    @Test
    void stagingDiscoveryDoesNotClaimLegacyRowsButActiveStillFailsClosed() {
        discovery(9101);

        assertThat(rows(source("STAGING"), 9101)).isNotEmpty();
        assertThat(rows(source("OFF"), 9101)).isNotEmpty();
        assertThat(rows(source("ACTIVE"), 9101)).isEmpty();
        assertThat(rows(new PfsReadSource(jdbc, "OFF", "", FIXTURES,
                "missing_evidence_table", CURRENT, PAGE_RECEIPTS), 9101)).isNotEmpty();
    }

    @Test
    void offWithReceiptTableButMissingEvidenceStillExcludesOwnedFixtures() {
        fixture(9101, "VERIFIED", 99L, 1);
        PfsReadSource partial = new PfsReadSource(jdbc, "OFF", "", FIXTURES,
                "missing_evidence_table", CURRENT, PAGE_RECEIPTS);

        assertThat(rows(partial, 9101)).isEmpty();
        assertThat(rows(partial, 9103)).isNotEmpty();
    }

    @Test
    void bulkReceiptCoverageDistinguishesLegacyVerifiedAndUnverifiedOwnedFixtures() {
        jdbc.update("DELETE FROM " + EVIDENCE);
        jdbc.update("DELETE FROM " + FIXTURES);
        fixture(9101, "WAITING_STATS", null, 1);
        fixture(9103, "VERIFIED", 12L, 2);

        assertThat(source("OFF").fixtureReceipts(TapasSeed.POPULATED_SEASON_ID,
                TapasSeed.POPULATED_SEASON_CODE, java.util.Set.of(9101, 9102, 9103)))
                .containsEntry(9101, PfsReadSource.FixtureReceipt.UNVERIFIED_OWNED)
                .containsEntry(9102, PfsReadSource.FixtureReceipt.LEGACY)
                .containsEntry(9103, PfsReadSource.FixtureReceipt.VERIFIED);
        assertThat(source("ACTIVE").fixtureReceipts(TapasSeed.POPULATED_SEASON_ID,
                TapasSeed.POPULATED_SEASON_CODE, java.util.Set.of(9102)))
                .containsEntry(9102, PfsReadSource.FixtureReceipt.UNVERIFIED_OWNED);
    }

    @Test
    void legacyHistoryReceiptDoesNotHideAnOldVerifiedFixtureInOffOrActive() {
        fixture(9101, "VERIFIED", 99L, 1);
        evidence(9101, 9201, 99, HistoryParser.Component.EXPECTED_GOALS.bit());
        jdbc.update("INSERT INTO " + PAGE_RECEIPTS
                        + "(season, publication_key, latest_generation, source_read_started_at,"
                        + " source_read_finished_at) VALUES (?, 'player-history:9201', 100,"
                        + " TIMESTAMPTZ '2025-08-24T00:00:00Z', TIMESTAMPTZ '2025-08-24T00:01:00Z')",
                TapasSeed.POPULATED_SEASON_CODE);
        BigDecimal originalExpectedGoals = jdbc.queryForObject("SELECT expected_goals"
                + " FROM player_fixture_stats WHERE season_id=? AND fixture_id=? AND player_id=?",
                BigDecimal.class, TapasSeed.POPULATED_SEASON_ID, 9101, 9201);

        assertThat(rows(source("OFF"), 9101)).singleElement()
                .extracting(row -> row.get("expected_goals")).isEqualTo(originalExpectedGoals);
        assertThat(rows(source("ACTIVE"), 9101)).singleElement()
                .extracting(row -> row.get("expected_goals")).isEqualTo(originalExpectedGoals);
        assertThat(source("OFF").incrementalPagePlayers(TapasSeed.POPULATED_SEASON_CODE,
                java.util.Set.of(9201))).isEmpty();
    }

    @Test
    void offMasksNewOwnedPagesAndWithdrawalsEvenWithOldVerifiedOrNoFixtureReceipt() {
        fixture(9101, "VERIFIED", 99L, 1);
        evidence(9101, 9201, 99, HistoryParser.Component.EXPECTED_GOALS.bit());
        assertThat(rows(source("OFF"), 9101)).isNotEmpty();

        current(9101, 9201, "VALID");
        assertThat(rows(source("OFF"), 9101)).singleElement().satisfies(row -> {
            assertThat(row.get("expected_goals")).isNull();
            assertThat(row.get("expected_assists")).isNotNull();
            assertThat(row.get("verified_published_at")).isNull();
        });
        jdbc.update("UPDATE " + CURRENT + " SET row_state='WITHDRAWN' WHERE fixture_id=9101");
        assertThat(rows(source("OFF"), 9101)).isEmpty();

        // The completed page watermark keeps ownership after its fixture pointer is withdrawn.
        jdbc.update("DELETE FROM " + CURRENT + " WHERE fixture_id=9101 AND player_id=9201");
        assertThat(rows(source("OFF"), 9101)).isEmpty();

        // No fixture-wide legacy receipt exists for 9103; an incremental-only pointer must not raw-fallback.
        current(9103, 9201, "WITHDRAWN");
        assertThat(rows(source("OFF"), 9103)).isEmpty();
        assertThat(source("OFF").fixtureReceipts(TapasSeed.POPULATED_SEASON_ID,
                TapasSeed.POPULATED_SEASON_CODE, java.util.Set.of(9103)))
                .containsEntry(9103, PfsReadSource.FixtureReceipt.INCREMENTAL);
    }

    @Test
    void physicalCoreOrPresentOptionalTamperingMakesOwnedRowUnreadableWithoutFallback() {
        fixture(9101, "VERIFIED", 99L, 1);
        current(9101, 9201, "VALID");
        assertThat(rows(source("OFF"), 9101)).hasSize(1);
        BigDecimal originalAssists = jdbc.queryForObject("SELECT expected_assists"
                + " FROM player_fixture_stats WHERE season_id=? AND fixture_id=? AND player_id=?",
                BigDecimal.class, TapasSeed.POPULATED_SEASON_ID, 9101, 9201);
        Integer originalPoints = jdbc.queryForObject("SELECT total_points"
                + " FROM player_fixture_stats WHERE season_id=? AND fixture_id=? AND player_id=?",
                Integer.class, TapasSeed.POPULATED_SEASON_ID, 9101, 9201);
        try {
            jdbc.update("UPDATE player_fixture_stats SET total_points=total_points+1,"
                    + " expected_assists=expected_assists+0.01 WHERE season_id=? AND fixture_id=? AND player_id=?",
                    TapasSeed.POPULATED_SEASON_ID, 9101, 9201);

            assertThat(rows(source("OFF"), 9101)).isEmpty();
            assertThat(rows(source("ACTIVE"), 9101)).isEmpty();
            assertThat(jdbc.queryForObject("WITH " + source("OFF").cte()
                    + " SELECT count(*) FROM pfs_read WHERE season_id=? AND fixture_id=? AND player_id=?",
                    Integer.class, TapasSeed.POPULATED_SEASON_ID, 9101, 9201)).isZero();
        } finally {
            jdbc.update("UPDATE player_fixture_stats SET total_points=?, expected_assists=?"
                            + " WHERE season_id=? AND fixture_id=? AND player_id=?",
                    originalPoints, originalAssists, TapasSeed.POPULATED_SEASON_ID, 9101, 9201);
        }
    }

    @Test
    void absentOptionalPhysicalRetentionIsExcludedFromSemanticFingerprint() {
        fixture(9101, "WAITING_STATS", null, 1);
        current(9101, 9201, "VALID");
        BigDecimal originalGoals = jdbc.queryForObject("SELECT expected_goals"
                + " FROM player_fixture_stats WHERE season_id=? AND fixture_id=? AND player_id=?",
                BigDecimal.class, TapasSeed.POPULATED_SEASON_ID, 9101, 9201);
        try {
            jdbc.update("UPDATE player_fixture_stats SET expected_goals=expected_goals+0.01"
                    + " WHERE season_id=? AND fixture_id=? AND player_id=?",
                    TapasSeed.POPULATED_SEASON_ID, 9101, 9201);

            assertThat(rows(source("OFF"), 9101)).singleElement().satisfies(row -> {
                assertThat(row.get("expected_goals")).isNull();
                assertThat(row.get("expected_assists")).isNotNull();
            });
        } finally {
            jdbc.update("UPDATE player_fixture_stats SET expected_goals=?"
                            + " WHERE season_id=? AND fixture_id=? AND player_id=?",
                    originalGoals, TapasSeed.POPULATED_SEASON_ID, 9101, 9201);
        }
    }

    @Test
    void currentPointerReadsWithoutFixtureVerificationAndMasksEvidenceBeforeAggregation() {
        fixture(9101, "WAITING_STATS", null, 1);
        current(9101, 9201, "VALID");

        Map<String, Object> row = rows(source("OFF"), 9101).getFirst();
        assertThat(row.get("expected_goals")).isNull();
        assertThat(row.get("expected_assists")).isNotNull();
        assertThat(row.get("source_read_finished_at")).isNotNull();
        assertThat(row.get("verified_published_at")).isNull();
        String status = jdbc.queryForObject("WITH " + source("OFF").cte()
                + " SELECT evidence_status FROM pfs_read WHERE season_id=? AND fixture_id=? AND player_id=?",
                String.class, TapasSeed.POPULATED_SEASON_ID, 9101, 9201);
        assertThat(status).isEqualTo("PROVISIONAL");
        assertThat(source("OFF").incrementalPagePlayers(TapasSeed.POPULATED_SEASON_CODE,
                java.util.Set.of(9201))).containsExactly(9201);

        Map<String, Object> aggregate = jdbc.queryForMap("WITH " + source("OFF").cte()
                + " SELECT SUM(expected_goals) AS xg, COUNT(expected_goals) AS observed"
                + " FROM pfs_read WHERE season_id=? AND fixture_id=? AND player_id=?",
                TapasSeed.POPULATED_SEASON_ID, 9101, 9201);
        assertThat(aggregate.get("xg")).isNull();
        assertThat(((Number) aggregate.get("observed")).intValue()).isZero();

        assertThat(jdbc.queryForObject("WITH " + source("OFF").cte()
                + " SELECT count(*) FROM pfs_read WHERE season_id=? AND fixture_id=?",
                Integer.class, TapasSeed.POPULATED_SEASON_ID, 9101)).isEqualTo(1);
    }

    @Test
    void invalidCurrentPointerHidesVerifiedAndPhysicalFallback() {
        fixture(9101, "VERIFIED", 99L, 1);
        evidence(9101, 9201, 99, HistoryParser.Component.EXPECTED_GOALS.bit());
        currentWithoutEvidence(9101, 9201, 100L);

        assertThat(rows(source("OFF"), 9101)).isEmpty();
        assertThat(rows(source("ACTIVE"), 9101)).isEmpty();
    }

    @Test
    void activeConfiguredSeasonDoesNotRawFallbackForAnUnknownFixture() {
        jdbc.update("DELETE FROM " + EVIDENCE);
        jdbc.update("DELETE FROM " + FIXTURES);

        assertThat(rows(source("OFF"), 9101)).isNotEmpty();
        assertThat(rows(source("ACTIVE"), 9101)).isEmpty();
    }

    @Test
    void ownedMetadataIsHonouredEvenWhenRuntimeIsOffAndPendingIsExcluded() {
        fixture(9101, "WAITING_STATS", null, 1);
        assertThat(rows(source("OFF"), 9101)).isEmpty();

        fixture(9101, "VERIFIED", 7L, 2);
        evidence(9101, 9201, 7, HistoryParser.Component.EXPECTED_ASSISTS.bit());
        Map<String, Object> row = rows(source("OFF"), 9101).getFirst();
        assertThat(row.get("expected_goals")).isNull();
        assertThat(row.get("expected_assists")).isEqualTo(new BigDecimal("0.30"));
        assertThat(row.get("source_read_finished_at")).isNotNull();
        assertThat(row.get("verified_published_at")).isNotNull();
    }

    @Test
    void correctionUsesOnlyTheCurrentImmutableGenerationAndNeverRetainedValues() {
        fixture(9101, "VERIFIED", 8L, 3);
        evidence(9101, 9201, 8, HistoryParser.Component.EXPECTED_GOALS.bit());
        Map<String, Object> corrected = rows(source("OFF"), 9101).getFirst();
        assertThat(corrected.get("expected_goals")).isEqualTo(new BigDecimal("0.55"));
        assertThat(corrected.get("expected_assists")).isNull();
        Map<String, Object> aggregate = jdbc.queryForMap("WITH " + source("OFF").cte()
                + " SELECT SUM(expected_assists) AS xa, COUNT(expected_assists) AS valid"
                + " FROM pfs_read WHERE season_id=? AND fixture_id=? AND player_id=?",
                TapasSeed.POPULATED_SEASON_ID, 9101, 9201);
        assertThat(aggregate.get("xa")).isNull();
        assertThat(((Number) aggregate.get("valid")).intValue()).isZero();

        fixture(9101, "VERIFYING", null, 4);
        assertThat(rows(source("OFF"), 9101)).isEmpty();
    }

    @Test
    void eachDoubleGameweekLegUsesItsOwnVerifiedGeneration() {
        fixture(9101, "VERIFIED", 11L, 5);
        fixture(9103, "VERIFIED", 12L, 6);
        evidence(9101, 9201, 11, HistoryParser.Component.EXPECTED_GOALS.bit());
        evidence(9103, 9201, 12, HistoryParser.Component.EXPECTED_ASSISTS.bit());

        assertThat(rows(source("OFF"), 9101).getFirst().get("expected_goals")).isNotNull();
        Map<String, Object> second = rows(source("OFF"), 9103).getFirst();
        assertThat(second.get("expected_goals")).isNull();
        assertThat(second.get("expected_assists")).isNotNull();
    }

    private PfsReadSource source(String mode) {
        return new PfsReadSource(jdbc, mode, TapasSeed.POPULATED_SEASON_CODE,
                FIXTURES, EVIDENCE, CURRENT, PAGE_RECEIPTS);
    }

    private List<Map<String, Object>> rows(PfsReadSource source, int fixtureId) {
        return jdbc.queryForList("WITH " + source.cte()
                + " SELECT expected_goals, expected_assists, source_read_finished_at, verified_published_at"
                + " FROM pfs_read WHERE season_id=? AND fixture_id=? AND player_id=?",
                TapasSeed.POPULATED_SEASON_ID, fixtureId, 9201);
    }

    private void fixture(int fixtureId, String state, Long generation, int hour) {
        jdbc.update("INSERT INTO " + FIXTURES
                        + "(season_id, fixture_id, state, observation_generation,"
                        + " current_verified_generation, last_verified_generation, last_verified_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, make_timestamptz(2025,8,24,?,0,0,'UTC'))"
                        + " ON CONFLICT (season_id, fixture_id) DO UPDATE SET state=EXCLUDED.state,"
                        + " observation_generation=EXCLUDED.observation_generation,"
                        + " current_verified_generation=EXCLUDED.current_verified_generation,"
                        + " last_verified_generation=EXCLUDED.last_verified_generation,"
                        + " last_verified_at=EXCLUDED.last_verified_at",
                TapasSeed.POPULATED_SEASON_ID, fixtureId, state, (long) hour, generation, generation, hour);
    }

    private void discovery(int fixtureId) {
        jdbc.update("INSERT INTO " + FIXTURES
                        + "(season_id, fixture_id, state) VALUES (?, ?, 'SCHEDULED')"
                        + " ON CONFLICT (season_id, fixture_id) DO UPDATE SET state='SCHEDULED',"
                        + " observation_generation=NULL, current_verified_generation=NULL,"
                        + " last_verified_generation=NULL",
                TapasSeed.POPULATED_SEASON_ID, fixtureId);
    }

    private void current(int fixtureId, int playerId, String state) {
        long generation = state.equals("VALID") ? 100L : 101L;
        if (state.equals("VALID")) {
            evidence(fixtureId, playerId, generation, HistoryParser.Component.EXPECTED_ASSISTS.bit());
        }
        pageReceipt(playerId, generation);
        String physicalFingerprint = state.equals("VALID") ? physicalFingerprint(fixtureId, playerId,
                HistoryParser.Component.EXPECTED_ASSISTS.bit()) : null;
        jdbc.update("INSERT INTO " + CURRENT + "(season_id, season_code, fixture_id, player_id, row_state,"
                        + " source_run_generation, evidence_generation, row_fingerprint, physical_fingerprint,"
                        + " source_read_started_at, source_read_finished_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?,"
                        + " TIMESTAMPTZ '2025-08-24T00:00:00Z', TIMESTAMPTZ '2025-08-24T00:01:00Z')",
                TapasSeed.POPULATED_SEASON_ID, TapasSeed.POPULATED_SEASON_CODE,
                fixtureId, playerId, state, generation, state.equals("VALID") ? generation : null,
                state.equals("VALID") ? "a".repeat(64) : null, physicalFingerprint);
    }

    private void currentWithoutEvidence(int fixtureId, int playerId, long generation) {
        pageReceipt(playerId, generation);
        jdbc.update("INSERT INTO " + CURRENT + "(season_id, season_code, fixture_id, player_id, row_state,"
                        + " source_run_generation, evidence_generation, row_fingerprint, physical_fingerprint,"
                        + " source_read_started_at, source_read_finished_at) VALUES (?, ?, ?, ?, 'VALID', ?, ?, ?, NULL,"
                        + " TIMESTAMPTZ '2025-08-24T00:00:00Z', TIMESTAMPTZ '2025-08-24T00:01:00Z')",
                TapasSeed.POPULATED_SEASON_ID, TapasSeed.POPULATED_SEASON_CODE,
                fixtureId, playerId, generation, generation, "a".repeat(64));
    }

    private String physicalFingerprint(int fixtureId, int playerId, long presenceMask) {
        String expression = PlayerFixtureFingerprint.physicalSqlExpression("p", "s",
                Long.toString(presenceMask));
        return jdbc.queryForObject("SELECT " + expression + " FROM player_fixture_stats p"
                + " JOIN season s ON s.id=p.season_id WHERE p.season_id=? AND p.fixture_id=? AND p.player_id=?",
                String.class, TapasSeed.POPULATED_SEASON_ID, fixtureId, playerId);
    }

    private void pageReceipt(int playerId, long generation) {
        jdbc.update("INSERT INTO " + PAGE_RECEIPTS + "(season, publication_key, latest_generation,"
                        + " source_read_started_at, source_read_finished_at)"
                        + " VALUES (?, ?, ?, TIMESTAMPTZ '2025-08-24T00:00:00Z',"
                        + " TIMESTAMPTZ '2025-08-24T00:01:00Z')",
                TapasSeed.POPULATED_SEASON_CODE, "incremental-page:player:" + playerId, generation);
    }

    private void evidence(int fixtureId, int playerId, long generation, long mask) {
        jdbc.update("INSERT INTO " + EVIDENCE
                        + "(season_id, fixture_id, player_id, generation, component_presence, season_code,"
                        + " source_read_started_at, source_read_finished_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, TIMESTAMPTZ '2025-08-24T00:00:00Z',"
                        + " TIMESTAMPTZ '2025-08-24T00:01:00Z') ON CONFLICT DO NOTHING",
                TapasSeed.POPULATED_SEASON_ID, fixtureId, playerId, generation, mask,
                TapasSeed.POPULATED_SEASON_CODE);
    }
}
