package fi.karivanttinen.fplassistant.tools;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser;
import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.FixtureRow;
import fi.karivanttinen.fplassistant.sync.FixtureVerification;
import fi.karivanttinen.fplassistant.sync.HistoryParser;
import fi.karivanttinen.fplassistant.sync.IncrementalAppearanceReceipt;
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
        jdbc.update("UPDATE player_fixture_stats SET expected_goals=0.55 WHERE season_id=?"
                + " AND fixture_id=9101 AND player_id=9201", TapasSeed.POPULATED_SEASON_ID);
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
                + " source_read_finished_at TIMESTAMPTZ, source_pair_fingerprint VARCHAR(64),"
                + " PRIMARY KEY (season,publication_key))");
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
    void groupsIncrementalAndOtherRowsOnlyInsideTheRequestedFixtureSet() {
        fixture(9101, "VERIFIED", 99L, 1);
        current(9101, 9201, "VALID");

        assertThat(source("OFF").incrementalRowsByGameweek(TapasSeed.POPULATED_SEASON_ID,
                java.util.Set.of(9101, 9102))).containsOnlyKeys(1)
                .containsEntry(1, new PfsReadSource.IncrementalRows(1, 3));
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
    void fixtureSidePopulationRequiresACompleteSourceRosterAndDoesNotRequireZeroMinuteRows() {
        fixture(9101, "WAITING_STATS", null, 1);
        Integer originalMinutes = jdbc.queryForObject("SELECT minutes FROM player_fixture_stats"
                + " WHERE season_id=? AND fixture_id=9101 AND player_id=9203", Integer.class,
                TapasSeed.POPULATED_SEASON_ID);
        jdbc.update("UPDATE player_fixture_stats SET minutes=0 WHERE season_id=? AND fixture_id=9101"
                + " AND player_id=9203", TapasSeed.POPULATED_SEASON_ID);
        try {
            current(9101, 9201, "VALID");
            current(9101, 9202, "VALID");
            current(9101, 9203, "VALID");
            PfsReadSource reader = source("OFF");
            Map<PfsReadSource.FixtureSide, PfsReadSource.SidePopulation> unknown =
                    reader.fixtureSidePopulation(TapasSeed.POPULATED_SEASON_ID,
                            TapasSeed.POPULATED_SEASON_CODE, "fixture", "season_id",
                            TapasSeed.POPULATED_SEASON_ID, Set.of(9101),
                            Instant.parse("2026-09-09T00:00:00Z"));
            assertThat(unknown.get(new PfsReadSource.FixtureSide(9101, 9001)))
                    .satisfies(population -> {
                        assertThat(population.sourceRosterKnown()).isFalse();
                        assertThat(population.complete()).isFalse();
                    });

            Instant started = Instant.parse("2025-08-24T00:00:00Z");
            Map<Integer, FixtureVerification.Appearance> appearances = Map.of(
                    9201, new FixtureVerification.Appearance(FixtureVerification.Side.HOME, 90),
                    9202, new FixtureVerification.Appearance(FixtureVerification.Side.AWAY, 90));
            FixtureVerification.FixtureEvidence evidence = new FixtureVerification.FixtureEvidence(true,
                    appearances.keySet(), appearances,
                    new CatalogueScheduleParser.ReadInterval(started, started.plusSeconds(60)));
            insertAppearanceSnapshot(9101, 1, 100, evidence);

            Map<PfsReadSource.FixtureSide, PfsReadSource.SidePopulation> complete =
                    reader.fixtureSidePopulation(TapasSeed.POPULATED_SEASON_ID,
                            TapasSeed.POPULATED_SEASON_CODE, "fixture", "season_id",
                            TapasSeed.POPULATED_SEASON_ID, Set.of(9101),
                            Instant.parse("2026-09-09T00:00:00Z"));
            assertThat(complete.get(new PfsReadSource.FixtureSide(9101, 9001)))
                    .satisfies(population -> {
                        assertThat(population.sourceRosterKnown()).isTrue();
                        assertThat(population.sourceRosterComplete()).isTrue();
                        assertThat(population.expectedPositive()).isEqualTo(1);
                        assertThat(population.matchedPositive()).isEqualTo(1);
                        assertThat(population.unexpectedPositive()).isZero();
                        assertThat(population.complete()).isTrue();
                    });
            assertThat(complete.get(new PfsReadSource.FixtureSide(9101, 9002)))
                    .satisfies(population -> assertThat(population.complete()).isTrue());
        } finally {
            jdbc.update("UPDATE player_fixture_stats SET minutes=? WHERE season_id=? AND fixture_id=9101"
                    + " AND player_id=9203", originalMinutes, TapasSeed.POPULATED_SEASON_ID);
        }
    }

    @Test
    void currentLiveRosterCannotPromoteLegacyAggregateWithoutCurrentPageBackedRows() {
        fixture(9101, "VERIFIED", 99L, 1);
        evidence(9101, 9201, 99, HistoryParser.Component.EXPECTED_GOALS.bit());
        evidence(9101, 9202, 99, HistoryParser.Component.EXPECTED_GOALS.bit());
        Instant started = Instant.parse("2025-08-24T00:10:00Z").plusNanos(123_456_789);
        Instant finished = started.plusSeconds(60);
        Map<Integer, FixtureVerification.Appearance> appearances = Map.of(
                9201, new FixtureVerification.Appearance(FixtureVerification.Side.HOME, 90),
                9202, new FixtureVerification.Appearance(FixtureVerification.Side.AWAY, 90));
        FixtureVerification.FixtureEvidence evidence = new FixtureVerification.FixtureEvidence(true,
                appearances.keySet(), appearances,
                new CatalogueScheduleParser.ReadInterval(started, finished));
        insertAppearanceSnapshot(9101, 1, 100, evidence);

        PfsReadSource reader = source("OFF");
        Map<PfsReadSource.FixtureSide, PfsReadSource.SidePopulation> sides = reader.fixtureSidePopulation(
                TapasSeed.POPULATED_SEASON_ID, TapasSeed.POPULATED_SEASON_CODE, "fixture", "season_id",
                TapasSeed.POPULATED_SEASON_ID, Set.of(9101), Instant.parse("2026-09-09T00:00:00Z"));

        assertThat(rows(reader, 9101)).hasSize(1);
        assertThat(reader.incrementalPagePlayers(TapasSeed.POPULATED_SEASON_CODE, Set.of(9201, 9202))).isEmpty();
        assertThat(sides.get(new PfsReadSource.FixtureSide(9101, 9001)))
                .satisfies(side -> {
                    assertThat(side.fixtureReceipt()).isEqualTo(PfsReadSource.FixtureReceipt.INCREMENTAL);
                    assertThat(side.sourceRosterComplete()).isTrue();
                    assertThat(side.expectedPositive()).isEqualTo(1);
                    assertThat(side.matchedPositive()).isZero();
                    assertThat(side.complete()).isFalse();
                });
        assertThat(sides.get(new PfsReadSource.FixtureSide(9101, 9002)).complete()).isFalse();
    }

    @Test
    void correctedFixtureSourceRevisionInvalidatesItsPriorAppearanceSnapshot() {
        fixture(9101, "WAITING_STATS", null, 1);
        String originalStats = jdbc.queryForObject("SELECT stats::text FROM fixture"
                + " WHERE season_id=? AND id=9101", String.class, TapasSeed.POPULATED_SEASON_ID);
        Instant started = Instant.parse("2025-08-24T00:10:00Z").plusNanos(123_456_789);
        Instant finished = started.plusSeconds(60);
        Map<Integer, FixtureVerification.Appearance> appearances = Map.of(
                9201, new FixtureVerification.Appearance(FixtureVerification.Side.HOME, 90),
                9202, new FixtureVerification.Appearance(FixtureVerification.Side.AWAY, 90));
        FixtureVerification.FixtureEvidence evidence = new FixtureVerification.FixtureEvidence(true,
                appearances.keySet(), appearances,
                new CatalogueScheduleParser.ReadInterval(started, finished));
        insertAppearanceSnapshot(9101, 1, 100, evidence);

        try {
            jdbc.update("UPDATE fixture SET stats=stats || '[{\"identifier\":\"revision_test\",\"h\":[],\"a\":[]}]'::jsonb"
                    + " WHERE season_id=? AND id=9101", TapasSeed.POPULATED_SEASON_ID);
            FixtureRow corrected = sourceFixture(9101);
            CatalogueScheduleParser.ReadInterval correctionRead = new CatalogueScheduleParser.ReadInterval(
                    finished.plusSeconds(1), finished.plusSeconds(2));
            insertReceipt(IncrementalAppearanceReceipt.sourceFixtureKey(9101), 101, correctionRead,
                    IncrementalAppearanceReceipt.fixtureRevisionFingerprint(corrected, corrected.gameweek()));

            Map<PfsReadSource.FixtureSide, PfsReadSource.SidePopulation> sides = source("OFF")
                    .fixtureSidePopulation(TapasSeed.POPULATED_SEASON_ID, TapasSeed.POPULATED_SEASON_CODE,
                            "fixture", "season_id", TapasSeed.POPULATED_SEASON_ID, Set.of(9101),
                            Instant.parse("2026-09-09T00:00:00Z"));

            assertThat(sides.get(new PfsReadSource.FixtureSide(9101, 9001)))
                    .satisfies(side -> {
                        assertThat(side.sourceRosterKnown()).isTrue();
                        assertThat(side.sourceRosterComplete()).isFalse();
                        assertThat(side.complete()).isFalse();
                    });
        } finally {
            jdbc.update("UPDATE fixture SET stats=?::jsonb WHERE season_id=? AND id=9101",
                    originalStats, TapasSeed.POPULATED_SEASON_ID);
        }
    }

    @Test
    void sourceCorrectionRequiresANewPageWatermarkEvenWithMatchingLiveRoster() {
        fixture(9101, "WAITING_STATS", null, 1);
        current(9101, 9201, "VALID");
        current(9101, 9202, "VALID");
        Map<Integer, FixtureVerification.Appearance> appearances = Map.of(
                9201, new FixtureVerification.Appearance(FixtureVerification.Side.HOME, 90),
                9202, new FixtureVerification.Appearance(FixtureVerification.Side.AWAY, 90));
        Instant firstStart = Instant.parse("2025-08-24T00:10:00Z");
        FixtureVerification.FixtureEvidence firstEvidence = new FixtureVerification.FixtureEvidence(true,
                appearances.keySet(), appearances,
                new CatalogueScheduleParser.ReadInterval(firstStart, firstStart.plusSeconds(60)));
        insertAppearanceSnapshot(9101, 1, 100, firstEvidence);
        String initialSourceFingerprint = jdbc.queryForObject("SELECT source_pair_fingerprint FROM "
                + PAGE_RECEIPTS + " WHERE season=? AND publication_key=?", String.class,
                TapasSeed.POPULATED_SEASON_CODE, IncrementalAppearanceReceipt.sourceFixtureKey(9101));
        refreshPageWatermark(9201, 101, firstStart.plusSeconds(120));
        refreshPageWatermark(9202, 101, firstStart.plusSeconds(120));

        PfsReadSource reader = source("OFF");
        Map<PfsReadSource.FixtureSide, PfsReadSource.SidePopulation> initial = reader.fixtureSidePopulation(
                TapasSeed.POPULATED_SEASON_ID, TapasSeed.POPULATED_SEASON_CODE, "fixture", "season_id",
                TapasSeed.POPULATED_SEASON_ID, Set.of(9101), Instant.parse("2026-09-09T00:00:00Z"));
        assertThat(initial.get(new PfsReadSource.FixtureSide(9101, 9001)).complete()).isTrue();

        Integer priorScore = jdbc.queryForObject("SELECT team_h_score FROM fixture WHERE season_id=? AND id=9101",
                Integer.class, TapasSeed.POPULATED_SEASON_ID);
        try {
            jdbc.update("UPDATE fixture SET team_h_score=COALESCE(team_h_score,0)+1"
                    + " WHERE season_id=? AND id=9101", TapasSeed.POPULATED_SEASON_ID);
            FixtureRow corrected = sourceFixture(9101);
            assertThat(IncrementalAppearanceReceipt.fixtureRevisionFingerprint(corrected, 1))
                    .isNotEqualTo(initialSourceFingerprint);
            Instant correctedStart = firstStart.plusSeconds(600);
            FixtureVerification.FixtureEvidence correctedEvidence = new FixtureVerification.FixtureEvidence(true,
                    appearances.keySet(), appearances, new CatalogueScheduleParser.ReadInterval(
                            correctedStart, correctedStart.plusSeconds(60)));
            insertAppearanceSnapshot(9101, 1, 102, correctedEvidence);

            Map<PfsReadSource.FixtureSide, PfsReadSource.SidePopulation> stalePages = reader.fixtureSidePopulation(
                    TapasSeed.POPULATED_SEASON_ID, TapasSeed.POPULATED_SEASON_CODE, "fixture", "season_id",
                    TapasSeed.POPULATED_SEASON_ID, Set.of(9101), Instant.parse("2026-09-09T00:00:00Z"));
            assertThat(stalePages.get(new PfsReadSource.FixtureSide(9101, 9001)))
                    .satisfies(side -> {
                        assertThat(side.sourceRosterComplete()).isTrue();
                        assertThat(side.expectedPositive()).isEqualTo(1);
                        assertThat(side.matchedPositive()).isZero();
                        assertThat(side.complete()).isFalse();
                    });

            refreshPageWatermark(9201, 103, correctedStart.plusSeconds(120));
            refreshPageWatermark(9202, 103, correctedStart.plusSeconds(120));
            Map<PfsReadSource.FixtureSide, PfsReadSource.SidePopulation> revalidated = reader.fixtureSidePopulation(
                    TapasSeed.POPULATED_SEASON_ID, TapasSeed.POPULATED_SEASON_CODE, "fixture", "season_id",
                    TapasSeed.POPULATED_SEASON_ID, Set.of(9101), Instant.parse("2026-09-09T00:00:00Z"));
            assertThat(revalidated.get(new PfsReadSource.FixtureSide(9101, 9001)).complete()).isTrue();
            assertThat(revalidated.get(new PfsReadSource.FixtureSide(9101, 9002)).complete()).isTrue();
        } finally {
            jdbc.update("UPDATE fixture SET team_h_score=? WHERE season_id=? AND id=9101",
                    priorScore, TapasSeed.POPULATED_SEASON_ID);
        }
    }

    @Test
    void absentOptionalComponentsStayNullWhileKnownZeroRemainsAReading() {
        fixture(9101, "WAITING_STATS", null, 1);
        BigDecimal originalGoals = jdbc.queryForObject("SELECT expected_goals FROM player_fixture_stats"
                + " WHERE season_id=? AND fixture_id=9101 AND player_id=9201", BigDecimal.class,
                TapasSeed.POPULATED_SEASON_ID);
        jdbc.update("UPDATE player_fixture_stats SET expected_goals=0 WHERE season_id=?"
                + " AND fixture_id=9101 AND player_id=9201", TapasSeed.POPULATED_SEASON_ID);
        evidence(9101, 9201, 100, HistoryParser.Component.EXPECTED_GOALS.bit());
        pageReceipt(9201, 100);
        String physical = physicalFingerprint(9101, 9201, HistoryParser.Component.EXPECTED_GOALS.bit());
        jdbc.update("INSERT INTO " + CURRENT + "(season_id,season_code,fixture_id,player_id,row_state,"
                        + "source_run_generation,evidence_generation,row_fingerprint,physical_fingerprint,"
                        + "source_read_started_at,source_read_finished_at) VALUES (?,?,?,?, 'VALID',?,?,?,?,"
                        + "TIMESTAMPTZ '2025-08-24T00:05:00Z',TIMESTAMPTZ '2025-08-24T00:06:00Z')",
                TapasSeed.POPULATED_SEASON_ID, TapasSeed.POPULATED_SEASON_CODE, 9101, 9201,
                100, 100, "a".repeat(64), physical);

        try {
            assertThat(rows(source("OFF"), 9101)).singleElement().satisfies(row -> {
                assertThat(row.get("expected_goals")).isEqualTo(BigDecimal.ZERO.setScale(2));
                assertThat(row.get("expected_assists")).isNull();
            });
        } finally {
            jdbc.update("UPDATE player_fixture_stats SET expected_goals=? WHERE season_id=?"
                            + " AND fixture_id=9101 AND player_id=9201",
                    originalGoals, TapasSeed.POPULATED_SEASON_ID);
        }
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

    private void insertAppearanceSnapshot(int fixtureId, int eventId, long generation,
                                           FixtureVerification.FixtureEvidence evidence) {
        CatalogueScheduleParser.ReadInterval read = evidence.liveRead();
        FixtureRow fixture = sourceFixture(fixtureId);
        CatalogueScheduleParser.ReadInterval parentRead = new CatalogueScheduleParser.ReadInterval(
                read.startedAt().minusSeconds(120), read.startedAt().minusSeconds(60));
        insertReceipt(IncrementalAppearanceReceipt.sourceFixtureKey(fixtureId), generation, parentRead,
                IncrementalAppearanceReceipt.fixtureRevisionFingerprint(fixture, eventId));
        insertReceipt(IncrementalAppearanceReceipt.LIVE_EVENT_PREFIX + eventId, generation, read, null);
        insertReceipt(IncrementalAppearanceReceipt.liveSourceKey(eventId, generation,
                read.startedAt(), read.completedAt()), generation, read, null);
        String snapshot = IncrementalAppearanceReceipt.snapshotKey(fixture, eventId, evidence, read);
        String fullFingerprint = IncrementalAppearanceReceipt.fingerprint(fixture, eventId, evidence);
        insertReceipt(snapshot, generation, read, fullFingerprint);
        List<String> previousAppearanceState = jdbc.query("SELECT source_pair_fingerprint FROM " + PAGE_RECEIPTS
                        + " WHERE season=? AND publication_key=?", (rs, row) -> rs.getString(1),
                TapasSeed.POPULATED_SEASON_CODE, IncrementalAppearanceReceipt.appearanceStateKey(fixtureId));
        if (previousAppearanceState.isEmpty()
                || !fullFingerprint.equals(previousAppearanceState.getFirst())) {
            insertReceipt(IncrementalAppearanceReceipt.appearanceStateKey(fixtureId), generation, read,
                    fullFingerprint);
        }
        evidence.appearances().forEach((playerId, appearance) -> insertReceipt(
                IncrementalAppearanceReceipt.playerKey(fixtureId, playerId, appearance, read), generation, read, null));
    }

    private FixtureRow sourceFixture(int fixtureId) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT f.id,f.code,f.gameweek,f.team_h,f.team_a,h.name AS home_name,h.short_name AS home_short,
                       a.name AS away_name,a.short_name AS away_short,f.team_h_score,f.team_a_score,
                       f.team_h_difficulty,f.team_a_difficulty,f.kickoff_time,f.started,f.finished,
                       f.finished_provisional,f.minutes,f.stats::text AS stats
                FROM fixture f JOIN team h ON h.id=f.team_h AND h.season_id=f.season_id
                JOIN team a ON a.id=f.team_a AND a.season_id=f.season_id
                WHERE f.season_id=? AND f.id=?
                """, TapasSeed.POPULATED_SEASON_ID, fixtureId);
        Map<String, Object> fields = new java.util.HashMap<>();
        fields.put("finished_provisional", row.get("finished_provisional"));
        fields.put("minutes", ((Number) row.get("minutes")).intValue());
        fields.put("stats", row.get("stats"));
        return new FixtureRow(TapasSeed.POPULATED_SEASON_ID, TapasSeed.POPULATED_SEASON_CODE,
                fixtureId, ((Number) row.get("code")).intValue(), (Integer) row.get("gameweek"),
                ((Number) row.get("team_h")).intValue(), ((Number) row.get("team_a")).intValue(),
                String.valueOf(row.get("home_name")), String.valueOf(row.get("home_short")),
                String.valueOf(row.get("away_name")), String.valueOf(row.get("away_short")),
                (Integer) row.get("team_h_score"), (Integer) row.get("team_a_score"),
                (Integer) row.get("team_h_difficulty"), (Integer) row.get("team_a_difficulty"),
                ((Timestamp) row.get("kickoff_time")).toInstant(), (Boolean) row.get("started"),
                (Boolean) row.get("finished"), fields);
    }

    private void insertReceipt(String key, long generation, CatalogueScheduleParser.ReadInterval read,
                               String fingerprint) {
        jdbc.update("INSERT INTO " + PAGE_RECEIPTS + " (season,publication_key,latest_generation,"
                        + "source_read_started_at,source_read_finished_at,source_pair_fingerprint)"
                        + " VALUES (?,?,?,?,?,?) ON CONFLICT (season,publication_key) DO UPDATE SET"
                        + " latest_generation=EXCLUDED.latest_generation,"
                        + " source_read_started_at=EXCLUDED.source_read_started_at,"
                        + " source_read_finished_at=EXCLUDED.source_read_finished_at,"
                        + " source_pair_fingerprint=EXCLUDED.source_pair_fingerprint",
                TapasSeed.POPULATED_SEASON_CODE, key, generation,
                Timestamp.from(read.startedAt()), Timestamp.from(read.completedAt()), fingerprint);
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
        current(fixtureId, playerId, state, HistoryParser.Component.EXPECTED_ASSISTS.bit());
    }

    private void current(int fixtureId, int playerId, String state, long presenceMask) {
        long generation = state.equals("VALID") ? 100L : 101L;
        if (state.equals("VALID")) {
            evidence(fixtureId, playerId, generation, presenceMask);
        }
        pageReceipt(playerId, generation);
        String physicalFingerprint = state.equals("VALID") ? physicalFingerprint(fixtureId, playerId,
                presenceMask) : null;
        jdbc.update("INSERT INTO " + CURRENT + "(season_id, season_code, fixture_id, player_id, row_state,"
                        + " source_run_generation, evidence_generation, row_fingerprint, physical_fingerprint,"
                        + " source_read_started_at, source_read_finished_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?,"
                        + " TIMESTAMPTZ '2025-08-24T00:05:00Z', TIMESTAMPTZ '2025-08-24T00:06:00Z')",
                TapasSeed.POPULATED_SEASON_ID, TapasSeed.POPULATED_SEASON_CODE,
                fixtureId, playerId, state, generation, state.equals("VALID") ? generation : null,
                state.equals("VALID") ? "a".repeat(64) : null, physicalFingerprint);
    }

    private void currentWithoutEvidence(int fixtureId, int playerId, long generation) {
        pageReceipt(playerId, generation);
        jdbc.update("INSERT INTO " + CURRENT + "(season_id, season_code, fixture_id, player_id, row_state,"
                        + " source_run_generation, evidence_generation, row_fingerprint, physical_fingerprint,"
                        + " source_read_started_at, source_read_finished_at) VALUES (?, ?, ?, ?, 'VALID', ?, ?, ?, NULL,"
                        + " TIMESTAMPTZ '2025-08-24T00:05:00Z', TIMESTAMPTZ '2025-08-24T00:06:00Z')",
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
                        + " VALUES (?, ?, ?, TIMESTAMPTZ '2025-08-24T00:05:00Z',"
                        + " TIMESTAMPTZ '2025-08-24T00:06:00Z') ON CONFLICT (season,publication_key)"
                        + " DO UPDATE SET latest_generation=EXCLUDED.latest_generation",
                TapasSeed.POPULATED_SEASON_CODE, "incremental-page:player:" + playerId, generation);
    }

    private void refreshPageWatermark(int playerId, long generation, Instant startedAt) {
        jdbc.update("UPDATE " + PAGE_RECEIPTS + " SET latest_generation=?,source_read_started_at=?,"
                        + "source_read_finished_at=? WHERE season=? AND publication_key=?",
                generation, Timestamp.from(startedAt), Timestamp.from(startedAt.plusSeconds(1)),
                TapasSeed.POPULATED_SEASON_CODE, "incremental-page:player:" + playerId);
    }

    private void evidence(int fixtureId, int playerId, long generation, long mask) {
        jdbc.update("INSERT INTO " + EVIDENCE
                        + "(season_id, fixture_id, player_id, generation, component_presence, season_code,"
                        + " source_read_started_at, source_read_finished_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, TIMESTAMPTZ '2025-08-24T00:05:00Z',"
                        + " TIMESTAMPTZ '2025-08-24T00:06:00Z') ON CONFLICT DO NOTHING",
                TapasSeed.POPULATED_SEASON_ID, fixtureId, playerId, generation, mask,
                TapasSeed.POPULATED_SEASON_CODE);
    }
}
