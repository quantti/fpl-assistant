package fi.karivanttinen.fplassistant.price;

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

import fi.karivanttinen.fplassistant.historical.VaastavSource;
import fi.karivanttinen.fplassistant.tools.SeasonContext;
import fi.karivanttinen.fplassistant.tools.SeasonResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Against the isolated {@code fpl_player_price_test} table. Uses a mocked
 * {@link SeasonResolver} so the write path can be exercised on demand (the real resolver
 * currently falls back to 2025-26 with {@code currentSeason=false}, which makes
 * {@link PriceSyncService#sync()} skip). Fictitious element ids are ≥9100 and the season label
 * is not a real FPL season shape, so they can never collide with production data.
 */
@SpringBootTest
@ActiveProfiles("test")
class PriceSyncServiceIntegrationTest {

    private static final String TEST_SEASON = "test-price";
    private static final int SEASON_ID = 990027; // fictitious; no tapas player rows for this id

    @Autowired
    JdbcTemplate jdbc;

    @Value("${fpl.price.table-name}")
    String tableName;

    @BeforeEach
    void refuseToRunAgainstProduction() {
        assertThat(tableName)
                .as("price sync tests must be pinned to the isolated table; check @ActiveProfiles(\"test\")")
                .endsWith("_test");
        jdbc.update("DELETE FROM " + tableName + " WHERE season = '" + TEST_SEASON + "'");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM " + tableName + " WHERE season = '" + TEST_SEASON + "'");
    }

    private static final String MERGED_GW_CSV = """
            name,element,round,value
            Test Player A,9101,1,55
            Test Player A,9101,2,56
            Test Player B,9102,1,80
            """;

    private PriceSyncService serviceWith(boolean isCurrentSeason, VaastavSource vaastavSource) {
        SeasonResolver mockResolver = mock(SeasonResolver.class);
        SeasonContext context = new SeasonContext(
                SEASON_ID, TEST_SEASON, isCurrentSeason, "2026-27", LocalDate.of(2026, 8, 21));
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

    @Test
    void writesVaastavRowsWhenTheResolvedSeasonIsCurrent() {
        int written = serviceWith(true, vaastavReturning(MERGED_GW_CSV)).sync();
        // snapshot writes 0 (no tapas players for the fictitious season id), vaastav writes 3.
        assertThat(written).isEqualTo(3);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT gameweek, element_id, price, source FROM " + tableName
                        + " WHERE season = '" + TEST_SEASON + "' ORDER BY gameweek, element_id");
        assertThat(rows).hasSize(3);
        assertThat(rows.getFirst().get("gameweek")).isEqualTo(1);
        assertThat(rows.getFirst().get("element_id")).isEqualTo(9101);
        assertThat(rows.getFirst().get("price")).isEqualTo(55);
        assertThat(rows.getFirst().get("source")).isEqualTo("vaastav");
        // name falls back to the CSV's name column because the element id is not in the tapas player table
        assertThat(jdbc.queryForObject(
                "SELECT player_name FROM " + tableName + " WHERE season = '" + TEST_SEASON
                        + "' AND element_id = 9101 AND gameweek = 1", String.class))
                .isEqualTo("Test Player A");
    }

    @Test
    void doesNothingWhenTheResolvedSeasonIsACompletedFallback() {
        int written = serviceWith(false, vaastavReturning(MERGED_GW_CSV)).sync();
        assertThat(written).isZero();
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + tableName + " WHERE season = '" + TEST_SEASON + "'", Integer.class);
        assertThat(count).isZero();
    }

    @Test
    void missingMergedGwIsHandledAsNoOpNotAFailure() {
        // Pre-season: no gws/merged_gw.csv yet. Snapshot writes 0 (fictitious season), vaastav 0.
        int written = serviceWith(true, vaastavAbsent()).sync();
        assertThat(written).isZero();
    }

    @Test
    void reRunningIsIdempotent() {
        PriceSyncService service = serviceWith(true, vaastavReturning(MERGED_GW_CSV));
        service.sync();
        service.sync();
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + tableName + " WHERE season = '" + TEST_SEASON + "'", Integer.class);
        assertThat(count).isEqualTo(3);
    }
}
