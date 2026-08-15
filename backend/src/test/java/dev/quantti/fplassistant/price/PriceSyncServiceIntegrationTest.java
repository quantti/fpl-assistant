package dev.quantti.fplassistant.price;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import dev.quantti.fplassistant.historical.VaastavSource;
import dev.quantti.fplassistant.testsupport.FictitiousTestData;
import dev.quantti.fplassistant.tools.SeasonContext;
import dev.quantti.fplassistant.tools.SeasonResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Against the isolated {@code fpl_player_price_test} table. Uses a mocked {@link SeasonResolver}
 * so the write path can be exercised on demand (the real resolver currently falls back to
 * 2025-26 with {@code currentSeason=false}, which makes {@link PriceSyncService#sync()} skip).
 *
 * <p>Two flavours of fictitious context, both writing under a season label
 * {@link FictitiousTestData} certifies cannot collide with real data:
 *
 * <ul>
 *   <li>{@link #FICTITIOUS_SEASON_ID} — no shared {@code player} rows, so only the vaastav writer
 *       produces anything. Used for the vaastav-path assertions.</li>
 *   <li>{@link #populatedSeasonId()} — a real season id, so the snapshot writer sees real
 *       players and actually writes. The rows still land under the fictitious season label, so
 *       nothing real is touched.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class PriceSyncServiceIntegrationTest {

    private static final String TEST_SEASON = "test-price";
    private static final int FICTITIOUS_SEASON_ID = 990027; // no shared player rows for this id

    static {
        FictitiousTestData.assertSafeSeason(TEST_SEASON);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Value("${fpl.price.table-name}")
    String tableName;

    @BeforeEach
    void refuseToRunAgainstProduction() {
        assertThat(tableName)
                .as("price sync tests must be pinned to the isolated table; check @ActiveProfiles(\"test\")")
                .endsWith("_test");
        deleteTestRows();
    }

    @AfterEach
    void cleanUp() {
        deleteTestRows();
    }

    private void deleteTestRows() {
        jdbc.update("DELETE FROM " + tableName + " WHERE season = ?", TEST_SEASON);
    }

    private static final String MERGED_GW_CSV = """
            name,element,round,value
            Test Player A,9101,1,55
            Test Player A,9101,2,56
            Test Player B,9102,1,80
            """;

    private PriceSyncService serviceWith(boolean isCurrentSeason, int seasonId, VaastavSource vaastavSource) {
        SeasonResolver mockResolver = mock(SeasonResolver.class);
        SeasonContext context = new SeasonContext(
                seasonId, TEST_SEASON, isCurrentSeason, "2026-27", LocalDate.of(2026, 8, 21));
        when(mockResolver.resolve()).thenReturn(context);
        return new PriceSyncService(vaastavSource, mockResolver, jdbc, tableName);
    }

    private VaastavSource vaastavReturning(String csv) {
        VaastavSource stub = mock(VaastavSource.class);
        when(stub.fetchIfPresent(TEST_SEASON, "gws/merged_gw.csv")).thenReturn(csv);
        return stub;
    }

    private VaastavSource vaastavAbsent() {
        VaastavSource stub = mock(VaastavSource.class);
        when(stub.fetchIfPresent(TEST_SEASON, "gws/merged_gw.csv")).thenReturn(null);
        return stub;
    }

    /** A real season that actually holds players, so the snapshot writer has something to copy. */
    private int populatedSeasonId() {
        return jdbc.queryForObject(
                "SELECT season_id FROM player GROUP BY season_id ORDER BY COUNT(*) DESC LIMIT 1",
                Integer.class);
    }

    private int countRows(String source) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + tableName
                + " WHERE season = ? AND source = ?", Integer.class, TEST_SEASON, source);
    }

    // --- vaastav path ---------------------------------------------------------------------

    @Test
    void writesVaastavRowsWhenTheResolvedSeasonIsCurrent() {
        int written = serviceWith(true, FICTITIOUS_SEASON_ID, vaastavReturning(MERGED_GW_CSV)).sync();
        // snapshot writes 0 (no shared players for the fictitious season id), vaastav writes 3.
        assertThat(written).isEqualTo(3);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT gameweek, element_id, price, source FROM " + tableName
                        + " WHERE season = ? ORDER BY gameweek, element_id", TEST_SEASON);
        assertThat(rows).hasSize(3);
        assertThat(rows.getFirst().get("gameweek")).isEqualTo(1);
        assertThat(rows.getFirst().get("element_id")).isEqualTo(9101);
        assertThat(rows.getFirst().get("price")).isEqualTo(55);
        assertThat(rows.getFirst().get("source")).isEqualTo("vaastav");
        // name falls back to the CSV's name column because the element id is not in the shared player table
        assertThat(jdbc.queryForObject("SELECT player_name FROM " + tableName
                        + " WHERE season = ? AND element_id = 9101 AND gameweek = 1",
                String.class, TEST_SEASON)).isEqualTo("Test Player A");
    }

    @Test
    void doesNothingWhenTheResolvedSeasonIsACompletedFallback() {
        int written = serviceWith(false, FICTITIOUS_SEASON_ID, vaastavReturning(MERGED_GW_CSV)).sync();
        assertThat(written).isZero();
        assertThat(countRows("vaastav")).isZero();
        assertThat(countRows("snapshot")).isZero();
    }

    @Test
    void missingMergedGwIsHandledAsNoOpNotAFailure() {
        // Pre-season: no gws/merged_gw.csv yet. Snapshot writes 0 (fictitious season), vaastav 0.
        int written = serviceWith(true, FICTITIOUS_SEASON_ID, vaastavAbsent()).sync();
        assertThat(written).isZero();
    }

    @Test
    void reRunningIsIdempotent() {
        PriceSyncService service = serviceWith(true, FICTITIOUS_SEASON_ID, vaastavReturning(MERGED_GW_CSV));
        service.sync();
        service.sync();
        assertThat(countRows("vaastav")).isEqualTo(3);
    }

    @Test
    void gameweeksAlreadyStoredAreNotRewritten() {
        // A full season's merged_gw.csv is ~30,000 rows; rewriting all of them on every sync
        // would hold a background thread against the shared database for minutes. Only the
        // highest stored gameweek (which may have been captured mid-gameweek) and anything
        // newer is written again.
        serviceWith(true, FICTITIOUS_SEASON_ID, vaastavReturning(MERGED_GW_CSV)).sync();
        Timestamp gw1Before = ingestedAt(9101, 1);
        Timestamp gw2Before = ingestedAt(9101, 2);

        String withGw3 = MERGED_GW_CSV + "Test Player A,9101,3,58\n";
        int written = serviceWith(true, FICTITIOUS_SEASON_ID, vaastavReturning(withGw3)).sync();

        // GW1 rows are frozen and skipped; GW2 (the previous high-water mark) and GW3 are written.
        assertThat(written).isEqualTo(2);
        assertThat(ingestedAt(9101, 1)).isEqualTo(gw1Before);
        assertThat(ingestedAt(9101, 2)).isAfter(gw2Before);
        assertThat(jdbc.queryForObject("SELECT price FROM " + tableName
                        + " WHERE season = ? AND element_id = 9101 AND gameweek = 3",
                Integer.class, TEST_SEASON)).isEqualTo(58);
    }

    private Timestamp ingestedAt(int elementId, int gameweek) {
        return jdbc.queryForObject("SELECT ingested_at FROM " + tableName
                        + " WHERE season = ? AND element_id = ? AND gameweek = ? AND source = 'vaastav'",
                Timestamp.class, TEST_SEASON, elementId, gameweek);
    }

    // --- snapshot path --------------------------------------------------------------------

    @Test
    void snapshotWritesOneLivePriceRowPerPlayerInTheResolvedSeason() {
        int seasonId = populatedSeasonId();
        int players = jdbc.queryForObject(
                "SELECT COUNT(*) FROM player WHERE season_id = ?", Integer.class, seasonId);
        assertThat(players).isPositive();

        int written = serviceWith(true, seasonId, vaastavAbsent()).sync();

        assertThat(written).isEqualTo(players);
        assertThat(countRows("snapshot")).isEqualTo(players);
        // Every snapshot row is the live price, keyed on gameweek 0.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + tableName
                        + " WHERE season = ? AND source = 'snapshot' AND gameweek <> 0",
                Integer.class, TEST_SEASON)).isZero();
    }

    @Test
    void snapshotCopiesTheCurrentPriceAndDenormalisedNameFromTheSharedStatsPlayerTable() {
        int seasonId = populatedSeasonId();
        Map<String, Object> player = jdbc.queryForMap("""
                SELECT id, now_cost, first_name, second_name FROM player
                WHERE season_id = ? AND first_name IS NOT NULL AND second_name IS NOT NULL
                ORDER BY now_cost DESC LIMIT 1
                """, seasonId);

        serviceWith(true, seasonId, vaastavAbsent()).sync();

        Map<String, Object> stored = jdbc.queryForMap("SELECT player_name, price FROM " + tableName
                        + " WHERE season = ? AND element_id = ? AND gameweek = 0 AND source = 'snapshot'",
                TEST_SEASON, player.get("id"));
        assertThat(stored.get("price")).isEqualTo(player.get("now_cost"));
        assertThat(stored.get("player_name"))
                .isEqualTo(player.get("first_name") + " " + player.get("second_name"));
    }

    @Test
    void reRunningTheSnapshotUpdatesInPlaceRatherThanAccumulatingRows() {
        int seasonId = populatedSeasonId();
        PriceSyncService service = serviceWith(true, seasonId, vaastavAbsent());
        service.sync();
        int afterFirst = countRows("snapshot");

        service.sync();

        assertThat(countRows("snapshot")).isEqualTo(afterFirst);
    }
}
