package dev.quantti.fplassistant.tools;

import java.time.LocalDate;
import java.util.List;

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
 * {@link DailyPriceSource#matchPlayers} routed through {@link PlayerNameSearch} (KAR-57):
 * accent folding, multi-token full-name matching and club narrowing, against the isolated
 * {@code fpl_daily_price_test} table.
 */
@SpringBootTest
@ActiveProfiles("test")
class PlayerNameSearchIntegrationTest {

    private static final String SEASON = "test-ns"; // season columns are VARCHAR(10)
    private static final LocalDate DAY = LocalDate.of(2026, 9, 18);

    private static final int MUHAREMOVIC = 9201;
    private static final int HALL_NEWCASTLE = 9202;
    private static final int HALLGRIMSSON = 9203;
    private static final int HALL_BURNLEY = 9204;
    private static final int KONATE = 9205;
    private static final int ODEGAARD = 9206;

    static {
        FictitiousTestData.assertSafeSeason(SEASON);
        FictitiousTestData.assertSafeCode(MUHAREMOVIC);
        FictitiousTestData.assertSafeCode(HALL_NEWCASTLE);
        FictitiousTestData.assertSafeCode(HALLGRIMSSON);
        FictitiousTestData.assertSafeCode(HALL_BURNLEY);
        FictitiousTestData.assertSafeCode(KONATE);
        FictitiousTestData.assertSafeCode(ODEGAARD);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DailyPriceSource source;

    @Value("${fpl.daily-price.table-name}")
    String table;

    @BeforeEach
    void refuseProductionResetAndSeedFixture() {
        assertThat(table).as("player name search tests must use the isolated table").endsWith("_test");
        deleteTestRows();
        insert(MUHAREMOVIC, "Muharemović", "Tarik Muharemović", "LEE", "Leeds", 45);
        insert(HALL_NEWCASTLE, "Hall", "Lewis Hall", "NEW", "Newcastle", 55);
        // Pricier than Hall/NEW: must not outrank it once "Hall" is an exact web_name match.
        insert(HALLGRIMSSON, "Hallgrimsson", "Jon Hallgrimsson", "NEW", "Newcastle", 60);
        insert(HALL_BURNLEY, "Hall", "Grant Hall", "BUR", "Burnley", 40);
        insert(KONATE, "Konaté", "Ibrahima Konaté", "LIV", "Liverpool", 60);
        insert(ODEGAARD, "Ødegaard", "Martin Ødegaard", "ARS", "Arsenal", 80);
    }

    @AfterEach
    void cleanUp() {
        deleteTestRows();
    }

    private void deleteTestRows() {
        jdbc.update("DELETE FROM " + table + " WHERE season = ?", SEASON);
    }

    private void insert(int elementId, String webName, String fullName, String teamShort,
                        String teamName, int price) {
        jdbc.update("INSERT INTO " + table + " (season, price_date, element_id, web_name,"
                        + " full_name, team_short, team_name, element_type, now_cost, cost_change_event,"
                        + " cost_change_start, status, news) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 3, ?, 0, 0, 'a', '')",
                SEASON, DAY, elementId, webName, fullName, teamShort, teamName, price);
    }

    private List<String> names(String query) {
        return source.matchPlayers(query, DAY).stream()
                .map(r -> r.get("web_name") + "/" + r.get("team_short")).toList();
    }

    @Test void unaccentedSurnameFindsTheAccentedPlayer() {
        assertThat(names("Muharemovic")).containsExactly("Muharemović/LEE");
    }
    @Test void unaccentedFullNameFindsTheAccentedPlayer() {
        assertThat(names("Tarik Muharemovic")).containsExactly("Muharemović/LEE");
    }
    @Test void aClubNarrowsAnAmbiguousSurnameAndExactWebNameBeatsPrice() {
        assertThat(names("Hall Newcastle")).containsExactly("Hall/NEW", "Hallgrimsson/NEW");
    }
    @Test void aClubShortNameNarrowsToo() {
        assertThat(names("hall NEW").getFirst()).isEqualTo("Hall/NEW");
    }
    @Test void strokedLettersFoldAsWell() {
        assertThat(names("Odegaard")).containsExactly("Ødegaard/ARS");
    }
    @Test void aClubAloneIsNotAPlayer() {
        assertThat(names("Newcastle")).isEmpty();
    }
    @Test void foldingNeverTurnsOneNameIntoASimilarOne() {
        assertThat(names("konsa")).isEmpty();          // Konaté is seeded; Konsa is not
    }
}
