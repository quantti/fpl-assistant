package fi.karivanttinen.fplassistant.tools;

import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import fi.karivanttinen.fplassistant.testsupport.FictitiousTestData;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link PriceTools#getPriceHistory} against the isolated {@code fpl_player_price_test} table.
 * Resolves a real player (so the tapas {@code player} name lookup actually finds someone)
 * through a mocked {@link SeasonResolver} that reports the season as current, then verifies the
 * per-GW formatting and the descriptive trend summary.
 *
 * <p>The mocked context pairs a <em>real</em> season id with a <em>fictitious</em> season label:
 * the player lookup needs real rows, but everything written to the price table lands under a
 * label {@link FictitiousTestData} certifies cannot collide with real data. Writing test rows
 * under a real season label is the bug class that already recurred once in Phase 3.
 */
@SpringBootTest
@ActiveProfiles("test")
class PriceToolsIntegrationTest {

    private static final String TEST_SEASON = "test-pt"; // season columns are VARCHAR(10)

    static {
        FictitiousTestData.assertSafeSeason(TEST_SEASON);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Value("${fpl.price.table-name}")
    String priceTable;

    @BeforeEach
    void refuseProductionAndResetFixture() {
        assertThat(priceTable).as("price tools tests must use the isolated table").endsWith("_test");
        deleteTestRows();
    }

    @AfterEach
    void cleanUp() {
        deleteTestRows();
    }

    private void deleteTestRows() {
        jdbc.update("DELETE FROM " + priceTable + " WHERE season = ?", TEST_SEASON);
    }

    private int populatedSeasonId() {
        return jdbc.queryForObject(
                "SELECT season_id FROM player GROUP BY season_id ORDER BY COUNT(*) DESC LIMIT 1",
                Integer.class);
    }

    private Map<String, Object> salah() {
        return jdbc.queryForMap(
                "SELECT id, web_name FROM player WHERE season_id = ? AND LOWER(second_name) LIKE '%salah%' LIMIT 1",
                populatedSeasonId());
    }

    private PriceTools toolsFor(int seasonId) {
        SeasonResolver mockResolver = mock(SeasonResolver.class);
        when(mockResolver.resolve()).thenReturn(new SeasonContext(
                seasonId, TEST_SEASON, true, "2026-27", LocalDate.of(2026, 8, 21)));
        return new PriceTools(jdbc, mockResolver, priceTable);
    }

    private void insertPrice(int elementId, String name, int gameweek, int price, String source) {
        jdbc.update("INSERT INTO " + priceTable
                        + " (season, gameweek, element_id, player_name, price, source) VALUES (?, ?, ?, ?, ?, ?)",
                TEST_SEASON, gameweek, elementId, name, price, source);
    }

    @Test
    void returnsPriceHistoryAndDescriptiveTrend() {
        Map<String, Object> player = salah();
        int pid = ((Number) player.get("id")).intValue();
        String name = (String) player.get("web_name");
        insertPrice(pid, name, 1, 140, "vaastav");
        insertPrice(pid, name, 2, 143, "vaastav");
        insertPrice(pid, name, 0, 145, "snapshot");

        String result = toolsFor(populatedSeasonId()).getPriceHistory("Salah", 10);

        assertThat(result).contains("Price history for");
        assertThat(result).contains("GW1: £14.0m (vaastav)");
        assertThat(result).contains("GW2: £14.3m (vaastav)");
        assertThat(result).contains("live: £14.5m (snapshot)");
        assertThat(result).contains("Trend: price has risen by £0.5m between GW1 and the live snapshot");
    }

    @Test
    void lastNBoundsTheGameweeksWithoutTheLiveSnapshotSpendingOneOfThem() {
        Map<String, Object> player = salah();
        int pid = ((Number) player.get("id")).intValue();
        String name = (String) player.get("web_name");
        for (int gw = 1; gw <= 5; gw++) {
            insertPrice(pid, name, gw, 140 + gw, "vaastav");
        }
        insertPrice(pid, name, 0, 150, "snapshot");

        String result = toolsFor(populatedSeasonId()).getPriceHistory("Salah", 3);

        // Three gameweeks (5, 4, 3) plus the live snapshot — not two gameweeks and a snapshot.
        assertThat(result).contains("GW5").contains("GW4").contains("GW3").contains("live:");
        assertThat(result).doesNotContain("GW2:").doesNotContain("GW1:");
    }

    @Test
    void aLoneSnapshotIsNotDescribedAsATrend() {
        Map<String, Object> player = salah();
        insertPrice(((Number) player.get("id")).intValue(), (String) player.get("web_name"),
                0, 145, "snapshot");

        String result = toolsFor(populatedSeasonId()).getPriceHistory("Salah", 10);

        assertThat(result).contains("only one price point is stored so far");
        assertThat(result).doesNotContain("held steady");
    }

    @Test
    void unknownPlayerReturnsNotFound() {
        assertThat(toolsFor(populatedSeasonId()).getPriceHistory("Definitely Not A Real Player", 10))
                .contains("Player not found");
    }

    @Test
    void noPriceHistoryYetIsReportedPlainly() {
        assertThat(toolsFor(populatedSeasonId()).getPriceHistory("Salah", 10))
                .contains("No price history yet");
    }

    @Test
    void anAmbiguousNameSaysWhichOtherPlayersItMatched() {
        // web_name is not unique: 2025-26 carries two Andersons, two Onanas, two Barneses.
        // Answering for one of them silently reads as though it were the only match.
        String duplicated = jdbc.queryForObject("""
                SELECT web_name FROM player WHERE season_id = ?
                GROUP BY web_name HAVING COUNT(*) > 1 LIMIT 1
                """, String.class, populatedSeasonId());
        assertThat(duplicated).as("expected at least one duplicated web_name in the season").isNotNull();

        String result = toolsFor(populatedSeasonId()).getPriceHistory(duplicated, 10);

        assertThat(result).contains("The name also matched:");
    }
}
