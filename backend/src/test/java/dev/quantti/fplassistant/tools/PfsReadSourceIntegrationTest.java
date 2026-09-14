package dev.quantti.fplassistant.tools;

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

import dev.quantti.fplassistant.sync.HistoryParser;
import dev.quantti.fplassistant.testsupport.SharedStatsSeed;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class PfsReadSourceIntegrationTest {

    private static final String FIXTURES = "pfs_reader_fixture_test";
    private static final String EVIDENCE = "pfs_reader_evidence_test";

    @Autowired JdbcTemplate jdbc;

    @BeforeAll
    static void setUp(@Autowired JdbcTemplate jdbc) {
        SharedStatsSeed.ensure(jdbc);
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
                    source_read_started_at TIMESTAMPTZ NOT NULL,
                    source_read_finished_at TIMESTAMPTZ NOT NULL,
                    PRIMARY KEY (season_id, fixture_id, player_id, generation))
                """);
    }

    @AfterAll
    static void tearDown(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("DROP TABLE IF EXISTS " + EVIDENCE);
        jdbc.execute("DROP TABLE IF EXISTS " + FIXTURES);
    }

    @Test
    void offWithoutTablesKeepsLegacyArchiveAndActiveFailsClosed() {
        assertThat(rows(new PfsReadSource(jdbc, "OFF", "", "missing_fixture_table", "missing_evidence_table"),
                9101)).isNotEmpty();
        assertThat(rows(new PfsReadSource(jdbc, "ACTIVE", SharedStatsSeed.POPULATED_SEASON_CODE,
                "missing_fixture_table", "missing_evidence_table"), 9101)).isEmpty();
    }

    @Test
    void stagingDiscoveryDoesNotClaimLegacyRowsButActiveStillFailsClosed() {
        discovery(9101);

        assertThat(rows(source("STAGING"), 9101)).isNotEmpty();
        assertThat(rows(source("OFF"), 9101)).isNotEmpty();
        assertThat(rows(source("ACTIVE"), 9101)).isEmpty();
        assertThat(rows(new PfsReadSource(jdbc, "OFF", "", FIXTURES,
                "missing_evidence_table"), 9101)).isNotEmpty();
    }

    @Test
    void offWithReceiptTableButMissingEvidenceStillExcludesOwnedFixtures() {
        fixture(9101, "VERIFIED", 99L, 1);
        PfsReadSource partial = new PfsReadSource(jdbc, "OFF", "", FIXTURES,
                "missing_evidence_table");

        assertThat(rows(partial, 9101)).isEmpty();
        assertThat(rows(partial, 9103)).isNotEmpty();
    }

    @Test
    void bulkReceiptCoverageDistinguishesLegacyVerifiedAndUnverifiedOwnedFixtures() {
        jdbc.update("DELETE FROM " + EVIDENCE);
        jdbc.update("DELETE FROM " + FIXTURES);
        fixture(9101, "WAITING_STATS", null, 1);
        fixture(9103, "VERIFIED", 12L, 2);

        assertThat(source("OFF").fixtureReceipts(SharedStatsSeed.POPULATED_SEASON_ID,
                SharedStatsSeed.POPULATED_SEASON_CODE, java.util.Set.of(9101, 9102, 9103)))
                .containsEntry(9101, PfsReadSource.FixtureReceipt.UNVERIFIED_OWNED)
                .containsEntry(9102, PfsReadSource.FixtureReceipt.LEGACY)
                .containsEntry(9103, PfsReadSource.FixtureReceipt.VERIFIED);
        assertThat(source("ACTIVE").fixtureReceipts(SharedStatsSeed.POPULATED_SEASON_ID,
                SharedStatsSeed.POPULATED_SEASON_CODE, java.util.Set.of(9102)))
                .containsEntry(9102, PfsReadSource.FixtureReceipt.UNVERIFIED_OWNED);
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
                SharedStatsSeed.POPULATED_SEASON_ID, 9101, 9201);
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
        return new PfsReadSource(jdbc, mode, SharedStatsSeed.POPULATED_SEASON_CODE, FIXTURES, EVIDENCE);
    }

    private List<Map<String, Object>> rows(PfsReadSource source, int fixtureId) {
        return jdbc.queryForList("WITH " + source.cte()
                + " SELECT expected_goals, expected_assists, source_read_finished_at, verified_published_at"
                + " FROM pfs_read WHERE season_id=? AND fixture_id=? AND player_id=?",
                SharedStatsSeed.POPULATED_SEASON_ID, fixtureId, 9201);
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
                SharedStatsSeed.POPULATED_SEASON_ID, fixtureId, state, (long) hour, generation, generation, hour);
    }

    private void discovery(int fixtureId) {
        jdbc.update("INSERT INTO " + FIXTURES
                        + "(season_id, fixture_id, state) VALUES (?, ?, 'SCHEDULED')"
                        + " ON CONFLICT (season_id, fixture_id) DO UPDATE SET state='SCHEDULED',"
                        + " observation_generation=NULL, current_verified_generation=NULL,"
                        + " last_verified_generation=NULL",
                SharedStatsSeed.POPULATED_SEASON_ID, fixtureId);
    }

    private void evidence(int fixtureId, int playerId, long generation, long mask) {
        jdbc.update("INSERT INTO " + EVIDENCE
                        + "(season_id, fixture_id, player_id, generation, component_presence,"
                        + " source_read_started_at, source_read_finished_at)"
                        + " VALUES (?, ?, ?, ?, ?, TIMESTAMPTZ '2025-08-24T00:00:00Z',"
                        + " TIMESTAMPTZ '2025-08-24T00:01:00Z') ON CONFLICT DO NOTHING",
                SharedStatsSeed.POPULATED_SEASON_ID, fixtureId, playerId, generation, mask);
    }
}
