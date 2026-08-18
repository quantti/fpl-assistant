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
 * {@link AvailabilityTools} against the isolated {@code fpl_daily_price_test} table.
 *
 * <p>Availability is the most perishable fact in FPL, and the answers here are the ones that
 * decide transfers, so these tests check the two things that make the tool trustworthy rather
 * than merely responsive: that a flag is reported with FPL's own wording, and that the reply
 * always says which day's capture it came from.
 */
@SpringBootTest
@ActiveProfiles("test")
class AvailabilityToolsIntegrationTest {

    private static final String TEST_SEASON = "test-av"; // season columns are VARCHAR(10)
    private static final String OTHER_SEASON = "test-av-o"; // same element_id, different player
    private static final int INJURED = 9201;
    private static final int DOUBTFUL = 9202;
    private static final int FIT = 9203;
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 10);
    private static final LocalDate EARLIER = LocalDate.of(2026, 9, 8);
    private static final LocalDate LAST_SEASON_DAY = LocalDate.of(2026, 5, 20);

    static {
        FictitiousTestData.assertSafeSeason(TEST_SEASON);
        FictitiousTestData.assertSafeSeason(OTHER_SEASON);
        FictitiousTestData.assertSafeCode(INJURED);
        FictitiousTestData.assertSafeCode(DOUBTFUL);
        FictitiousTestData.assertSafeCode(FIT);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AvailabilityTools tools;

    @Value("${fpl.daily-price.table-name}")
    String table;

    @BeforeEach
    void refuseProductionAndResetFixture() {
        assertThat(table).as("availability tests must use the isolated table").endsWith("_test");
        deleteTestRows();
    }

    @AfterEach
    void cleanUp() {
        deleteTestRows();
    }

    private void deleteTestRows() {
        jdbc.update("DELETE FROM " + table + " WHERE season = ?", TEST_SEASON);
        jdbc.update("DELETE FROM " + table + " WHERE season = ?", OTHER_SEASON);
    }

    private void insert(int elementId, String webName, LocalDate date,
                        String status, String news) {
        insert(elementId, webName, date, "TST", "Testville United", status, news);
    }

    private void insert(int elementId, String webName, LocalDate date, String teamShort,
                        String teamName, String status, String news) {
        jdbc.update("INSERT INTO " + table + " (season, price_date, element_id, web_name,"
                        + " full_name, team_short, team_name, element_type, now_cost, cost_change_event,"
                        + " cost_change_start, status, news) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 3, 100, 0, 0, ?, ?)",
                TEST_SEASON, date, elementId, webName, webName + " Fictitious",
                teamShort, teamName, status, news);
    }

    private void insertOtherSeason(int elementId, String webName, LocalDate date,
                                   String status, String news) {
        jdbc.update("INSERT INTO " + table + " (season, price_date, element_id, web_name,"
                        + " full_name, team_short, team_name, element_type, now_cost, cost_change_event,"
                        + " cost_change_start, status, news) "
                        + "VALUES (?, ?, ?, ?, ?, 'TST', 'Testville United', 3, 100, 0, 0, ?, ?)",
                OTHER_SEASON, date, elementId, webName, webName + " Fictitious", status, news);
    }

    @Test
    void spellsOutTheStatusCodeRatherThanLeavingTheModelToGuess() {
        insert(INJURED, "Brokentest", TODAY, "i", "Hamstring injury - expected back 25 Sep");

        String result = tools.getPlayerAvailability("Brokentest");

        assertThat(result).contains("INJURED");
        assertThat(result).contains("Hamstring injury - expected back 25 Sep");
    }

    @Test
    void reportsAFitPlayerAsAvailableWithNoAlarm() {
        insert(FIT, "Healthytest", TODAY, "a", "");

        String result = tools.getPlayerAvailability("Healthytest");

        assertThat(result).contains("available");
        assertThat(result).doesNotContain("DOUBTFUL").doesNotContain("INJURED");
    }

    @Test
    void saysHowLongAFlagHasStood() {
        // The single thing a daily series can answer that a snapshot cannot.
        insert(DOUBTFUL, "Doubttest", EARLIER, "d", "Knock - 75% chance of playing");
        insert(DOUBTFUL, "Doubttest", TODAY, "d", "Knock - 75% chance of playing");

        String result = tools.getPlayerAvailability("Doubttest");

        assertThat(result).contains("DOUBTFUL");
        assertThat(result).contains("2026-09-08");
        assertThat(result).contains("2 days");
    }

    @Test
    void aFlagRaisedTodayIsNotDescribedAsLongStanding() {
        insert(DOUBTFUL, "Doubttest", EARLIER, "a", "");
        insert(DOUBTFUL, "Doubttest", TODAY, "d", "Knock");

        String result = tools.getPlayerAvailability("Doubttest");

        assertThat(result).contains("new as of today's capture");
    }

    @Test
    void aGapInTheCaptureDoesNotBreakTheFlagStreak() {
        // The daily capture can miss a day (a failed 02:00 run). The flag is still "since the
        // first day of this status" — the series is sparse but the status did not change, and
        // the "at least" wording covers the hole.
        insert(DOUBTFUL, "Doubttest", LocalDate.of(2026, 9, 7), "d", "Knock");
        insert(DOUBTFUL, "Doubttest", LocalDate.of(2026, 9, 9), "d", "Knock"); // 9-8 missing
        insert(DOUBTFUL, "Doubttest", TODAY, "d", "Knock");

        String result = tools.getPlayerAvailability("Doubttest");

        assertThat(result).contains("since at least 2026-09-07");
    }

    @Test
    void alwaysStatesWhichDaysCaptureTheAnswerCameFrom() {
        insert(INJURED, "Brokentest", TODAY, "i", "Out");

        assertThat(tools.getPlayerAvailability("Brokentest"))
                .contains("Data source: FPL live prices for season " + TEST_SEASON)
                .contains("2026-09-10");
    }

    @Test
    void aFlagDoesNotCarryAcrossSeasonsForTheSameElementId() {
        // FPL renumbers every season, so the element_id that was doubtful all last season
        // belongs to a different player now. Without the season predicate in flagAge, the
        // new player's brand-new flag would read as having stood since last season.
        insertOtherSeason(DOUBTFUL, "Olddoubttest", LAST_SEASON_DAY, "d", "Knock");
        insert(DOUBTFUL, "Doubttest", TODAY, "d", "Knock");

        String result = tools.getPlayerAvailability("Doubttest");

        assertThat(result).contains("new as of today's capture");
        assertThat(result).doesNotContain("2026-05-20");
    }

    @Test
    void listsEveryFlaggedPlayerAtAClubAndOmitsTheFitOnes() {
        insert(INJURED, "Brokentest", TODAY, "i", "Hamstring");
        insert(DOUBTFUL, "Doubttest", TODAY, "d", "Knock");
        insert(FIT, "Healthytest", TODAY, "a", "");

        String result = tools.getFlaggedPlayers("TST");

        // Single-club output: the header names the club, and rows carry no club-code prefix
        // (the prefix is the multi-club disambiguator).
        assertThat(result).contains("Flagged players (Testville United), 2 found");
        assertThat(result).doesNotContain("TST Brokentest");
        assertThat(result).contains("Brokentest").contains("INJURED").contains("Hamstring");
        assertThat(result).contains("Doubttest").contains("DOUBTFUL");
        assertThat(result).doesNotContain("Healthytest");
    }

    @Test
    void aListThatHitsTheCapReportsTheTrueTotalRatherThanTheCap() {
        // A broad substring can match more flagged players than the 30-row cap; the header
        // must state the real count and say the list is truncated, so no club silently
        // vanishes just because its players fell outside the top-30-by-price.
        for (int i = 0; i < 32; i++) {
            insert(9300 + i, "Captest" + i, TODAY, "i", "Hamstring");
        }

        String result = tools.getFlaggedPlayers("TST");

        assertThat(result).contains("Flagged players (Testville United), 32 found");
        assertThat(result).contains("showing the first 30");
        // Exactly 30 rows are listed, not 32 — and which 30 is unspecified (equal prices),
        // so the assertion counts listed lines rather than naming them.
        long listed = result.lines().filter(l -> l.contains("Captest") && l.contains("INJURED")).count();
        assertThat(listed).isEqualTo(30);
    }

    @Test
    void findsAClubByItsFullNameNotOnlyItsShortCode() {
        // The bug this pins: matching only on team_short meant "Arsenal" found nothing while
        // "ARS" worked — and users type the full name far more often than the three-letter code,
        // so in practice the tool reported "club not matched" for the normal question.
        insert(INJURED, "Brokentest", TODAY, "i", "Hamstring");

        assertThat(tools.getFlaggedPlayers("Testville United")).contains("Brokentest");
        assertThat(tools.getFlaggedPlayers("testville")).contains("Brokentest");
        assertThat(tools.getFlaggedPlayers("TST")).contains("Brokentest");
    }

    @Test
    void aSubstringMatchingSeveralClubsLabelsEachPlayerWithItsClub() {
        // A substring like "man" matches both Manchester clubs. Without per-row club codes
        // the reply would list both clubs' players under whichever club came first.
        insert(INJURED, "Citytest", TODAY, "MCI", "Manchester City", "i", "Hamstring");
        insert(DOUBTFUL, "Utdtest", TODAY, "MUN", "Manchester United", "d", "Knock");

        String result = tools.getFlaggedPlayers("Man");

        assertThat(result).contains("at 2 clubs, 2 found");
        assertThat(result).contains("MCI Citytest");
        assertThat(result).contains("MUN Utdtest");
    }

    @Test
    void anUnmatchedClubDoesNotReadAsACleanBillOfHealth() {
        insert(INJURED, "Brokentest", TODAY, "i", "Hamstring");

        String result = tools.getFlaggedPlayers("Nonexistent FC");

        // The dangerous failure: answering "nobody is injured" when the club name simply
        // did not match. The wording has to make the model check rather than reassure.
        assertThat(result).contains("did not match");
        assertThat(result).contains("check the name");
    }

    @Test
    void unknownPlayerReturnsNotFound() {
        insert(FIT, "Healthytest", TODAY, "a", "");

        assertThat(tools.getPlayerAvailability("Definitely Not A Real Player"))
                .contains("Player not found");
    }
}
