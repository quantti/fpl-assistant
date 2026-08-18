package dev.quantti.fplassistant.tools;

import java.time.LocalDate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import dev.quantti.fplassistant.testsupport.FictitiousTestData;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PriceTools#getPriceHistory} against the isolated {@code fpl_daily_price_test} table.
 *
 * <p>Rewritten on 2026-08-18 when the tool moved off {@code fpl_player_price}. The old version
 * had to resolve a real player through the shared {@code player} table so the name lookup would
 * find someone — and that dependency is precisely what broke in production, since that table
 * held no rows at all for the new season. The daily capture carries its own names, so these
 * tests now write entirely fictitious players and never touch a shared table.
 *
 * <p>Element ids are above {@link FictitiousTestData#MIN_SAFE_CODE} and the season label is
 * fictitious, so nothing here can collide with real data in the shared test database.
 */
@SpringBootTest
@ActiveProfiles("test")
class PriceToolsIntegrationTest {

    private static final String TEST_SEASON = "test-pt"; // season columns are VARCHAR(10)
    private static final int SALAH = 9101;
    private static final int TWIN_A = 9102;
    private static final int TWIN_B = 9103;
    private static final LocalDate DAY_3 = LocalDate.of(2026, 9, 3);
    private static final LocalDate DAY_2 = LocalDate.of(2026, 9, 2);
    private static final LocalDate DAY_1 = LocalDate.of(2026, 9, 1);

    static {
        FictitiousTestData.assertSafeSeason(TEST_SEASON);
        FictitiousTestData.assertSafeCode(SALAH);
        FictitiousTestData.assertSafeCode(TWIN_A);
        FictitiousTestData.assertSafeCode(TWIN_B);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PriceTools priceTools;

    @Value("${fpl.daily-price.table-name}")
    String table;

    @BeforeEach
    void refuseProductionAndResetFixture() {
        assertThat(table).as("price tools tests must use the isolated table").endsWith("_test");
        deleteTestRows();
    }

    @AfterEach
    void cleanUp() {
        deleteTestRows();
    }

    private void deleteTestRows() {
        jdbc.update("DELETE FROM " + table + " WHERE season = ?", TEST_SEASON);
    }

    private void insert(int elementId, String webName, LocalDate date, int price,
                        int changeEvent, int changeStart) {
        jdbc.update("INSERT INTO " + table + " (season, price_date, element_id, web_name,"
                        + " full_name, team_short, element_type, now_cost, cost_change_event,"
                        + " cost_change_start, status, news) "
                        + "VALUES (?, ?, ?, ?, ?, 'TST', 3, ?, ?, ?, 'a', '')",
                TEST_SEASON, date, elementId, webName, webName + " Fictitious", price,
                changeEvent, changeStart);
    }

    @Test
    void listsTheDailySeriesAndDescribesTheTrend() {
        insert(SALAH, "Salahtest", DAY_1, 140, 0, 0);
        insert(SALAH, "Salahtest", DAY_2, 143, 3, 3);
        insert(SALAH, "Salahtest", DAY_3, 145, 5, 5);

        String result = priceTools.getPriceHistory("Salahtest", 10);

        assertThat(result).contains("Price history for Salahtest");
        assertThat(result).contains("2026-09-01: £14.0m");
        assertThat(result).contains("2026-09-03: £14.5m");
        assertThat(result).contains("Trend: price has risen by £0.5m between 2026-09-01 and 2026-09-03");
        assertThat(result).contains("descriptive, not a prediction");
    }

    @Test
    void reportsFplsOwnCountersAlongsideOurSeries() {
        // Our series starts the day the capture began; FPL's counters cover the whole season,
        // which is the only way to describe movement from before we were watching.
        insert(SALAH, "Salahtest", DAY_3, 145, 2, 9);

        String result = priceTools.getPriceHistory("Salahtest", 10);

        assertThat(result).contains("FPL's own counters: +0.2m this gameweek, +0.9m since the season started");
    }

    @Test
    void lastNBoundsTheNumberOfDays() {
        insert(SALAH, "Salahtest", DAY_1, 140, 0, 0);
        insert(SALAH, "Salahtest", DAY_2, 143, 3, 3);
        insert(SALAH, "Salahtest", DAY_3, 145, 5, 5);

        String result = priceTools.getPriceHistory("Salahtest", 2);

        assertThat(result).contains("2026-09-03").contains("2026-09-02");
        assertThat(result).doesNotContain("2026-09-01:");
    }

    @Test
    void aSingleCapturedDayIsNotDescribedAsATrend() {
        insert(SALAH, "Salahtest", DAY_3, 145, 0, 0);

        String result = priceTools.getPriceHistory("Salahtest", 10);

        assertThat(result).contains("only one day has been captured so far");
        assertThat(result).doesNotContain("held steady");
    }

    @Test
    void statesWhichDaysCaptureTheAnswerComesFrom() {
        insert(SALAH, "Salahtest", DAY_3, 145, 0, 0);

        String result = priceTools.getPriceHistory("Salahtest", 10);

        // The whole point of the daily table: a price without its date is not usable.
        assertThat(result).contains("Data source: FPL live prices for season " + TEST_SEASON);
        assertThat(result).contains("2026-09-03");
        assertThat(result).contains("FPL changes prices once a day at 00:00 UK");
    }

    @Test
    void unknownPlayerReturnsNotFound() {
        insert(SALAH, "Salahtest", DAY_3, 145, 0, 0);

        assertThat(priceTools.getPriceHistory("Definitely Not A Real Player", 10))
                .contains("Player not found");
    }

    @Test
    void anAmbiguousNameSaysWhichOtherPlayersItMatched() {
        // web_name is not unique in real FPL data either (two Andersons, two Onanas).
        insert(TWIN_A, "Twintest", DAY_3, 150, 0, 0);
        insert(TWIN_B, "Twintest", DAY_3, 90, 0, 0);

        String result = priceTools.getPriceHistory("Twintest", 10);

        assertThat(result).contains("The name also matched:");
    }
}
