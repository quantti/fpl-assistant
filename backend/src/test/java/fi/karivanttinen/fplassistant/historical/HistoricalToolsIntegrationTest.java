package fi.karivanttinen.fplassistant.historical;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Against the isolated test table, seeded with hand-written rows for two fictitious clubs
 * across three fictitious seasons — this exercises {@code HistoricalTools}/
 * {@code HistoricalTeamResolver} SQL and formatting, not CSV parsing (which
 * {@link VaastavFixtureParserTest} and {@link VaastavBackfillIntegrationTest} already cover
 * against real files).
 *
 * <p>{@link #relegatedClubResolvesAndReturnsFormAgainstRealBackfilledData()} additionally runs
 * its own small backfill of real 2019-20 data and queries Watford — the plan's acceptance
 * criterion for this task is "correct results... including a relegated one, verified against
 * the real table", which the fictitious-club tests above cannot exercise. It does not rely on
 * another test class having already populated the table: relying on test execution order would
 * make this pass or fail depending on what ran first.
 */
@SpringBootTest
@ActiveProfiles("test")
class HistoricalToolsIntegrationTest {

    private static final int UNITED_CODE = 9101;
    private static final int CITY_CODE = 9102;
    private static final String SOURCE_TAG = "hist-tools-test";
    private static final String SEASON_A = "t-tool-a";
    private static final String SEASON_B = "t-tool-b";
    private static final String SEASON_C = "t-tool-c";

    static {
        FictitiousTestData.assertSafeCode(UNITED_CODE);
        FictitiousTestData.assertSafeCode(CITY_CODE);
        FictitiousTestData.assertSafeSeason(SEASON_A);
        FictitiousTestData.assertSafeSeason(SEASON_B);
        FictitiousTestData.assertSafeSeason(SEASON_C);
    }

    @Autowired
    HistoricalTools historicalTools;

    @Autowired
    HistoricalTeamResolver teamResolver;

    @Autowired
    VaastavBackfillService backfillService;

    @Autowired
    JdbcTemplate jdbc;

    @Value("${fpl.historical.table-name}")
    String tableName;

    @BeforeEach
    void seedFixtureData() {
        assertThat(tableName)
                .as("historical tools tests must be pinned to the isolated table; check @ActiveProfiles(\"test\")")
                .endsWith("_test");
        jdbc.update("DELETE FROM " + tableName + " WHERE source = '" + SOURCE_TAG + "'");

        insert(SEASON_A, 1, UNITED_CODE, CITY_CODE, "Test United", "Test City", 3, 1, "2098-08-15T15:00:00Z");
        insert(SEASON_B, 5, CITY_CODE, UNITED_CODE, "Test City", "Test United", 2, 2, "2099-09-20T15:00:00Z");
        insert(SEASON_C, 10, UNITED_CODE, CITY_CODE, "Test United", "Test City", 1, 0, "2100-10-25T15:00:00Z");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM " + tableName + " WHERE source = '" + SOURCE_TAG + "'");
        jdbc.update("DELETE FROM " + tableName + " WHERE source = 'vaastav' AND season = '2019-20'");
    }

    @Test
    void resolverFindsAFictitiousClubByPartialName() {
        assertThat(teamResolver.resolve("test united"))
                .isEqualTo(new TeamIdentity(UNITED_CODE, "Test United"));
    }

    @Test
    void resolverReturnsNullForAnUnknownClub() {
        assertThat(teamResolver.resolve("Definitely Not A Real Club FC")).isNull();
    }

    @Test
    void getTeamFormReturnsResultsNewestFirstWithCorrectOutcomesAndFreshness() {
        String result = historicalTools.getTeamForm("Test United", 10);

        assertThat(result).contains("Test United");
        // Newest (SEASON_C, W 1-0 at home) must appear before the oldest (SEASON_A).
        assertThat(result.indexOf(SEASON_C)).isLessThan(result.indexOf(SEASON_A));
        assertThat(result).contains(SEASON_C + " GW10");
        assertThat(result).containsPattern("\\(W\\)");
        assertThat(result).containsPattern("\\(D\\)"); // the 2-2 draw
        assertThat(result).contains("Most recent match known:");
        assertThat(result).contains("2100-10-25");
    }

    @Test
    void getTeamFormOnUnknownClubReturnsNotFound() {
        assertThat(historicalTools.getTeamForm("Nonexistent FC", 5)).containsIgnoringCase("not found");
    }

    @Test
    void headToHeadIsOrderInsensitive() {
        String forward = historicalTools.getHeadToHead("Test United", "Test City", 10);
        String backward = historicalTools.getHeadToHead("Test City", "Test United", 10);

        assertThat(forward).contains(SEASON_A).contains(SEASON_B).contains(SEASON_C);
        assertThat(backward).contains(SEASON_A).contains(SEASON_B).contains(SEASON_C);
    }

    @Test
    void headToHeadOnUnknownClubReturnsNotFound() {
        assertThat(historicalTools.getHeadToHead("Test United", "Nonexistent FC", 5))
                .containsIgnoringCase("not found");
    }

    @Test
    void relegatedClubResolvesAndReturnsFormAgainstRealBackfilledData() {
        // Real backfill of one real season, into the isolated test table, cleaned up in
        // @AfterEach — independent of whatever VaastavBackfillIntegrationTest has or hasn't run.
        VaastavSource fileSource = (season, fileName) -> {
            try {
                return java.nio.file.Files.readString(java.nio.file.Path.of(
                        "src/test/resources/vaastav-fixtures/" + season + "-"
                                + fileName.replace(".csv", "") + ".csv"));
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        };
        new VaastavBackfillService(fileSource, jdbc, tableName).backfill(java.util.List.of("2019-20"));

        // Watford were relegated after 2019-20 and are absent from the current season's team
        // table — exactly the case FixtureTools' resolver structurally cannot serve.
        assertThat(teamResolver.resolve("Watford")).isEqualTo(new TeamIdentity(57, "Watford"));

        String result = historicalTools.getTeamForm("Watford", 5);
        assertThat(result).contains("Watford");
        assertThat(result).doesNotContainIgnoringCase("not found");
        assertThat(result).contains("2019-20");
        assertThat(result).containsPattern("\\((W|D|L)\\)");
    }

    private void insert(String season, int gameweek, int homeCode, int awayCode,
                        String homeName, String awayName, int homeGoals, int awayGoals, String kickoff) {
        jdbc.update("""
                INSERT INTO %s (season, gameweek, home_team_code, away_team_code,
                                 home_team_name, away_team_name, home_goals, away_goals,
                                 kickoff_time, source)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.formatted(tableName),
                season, gameweek, homeCode, awayCode, homeName, awayName,
                homeGoals, awayGoals, java.sql.Timestamp.from(java.time.Instant.parse(kickoff)), SOURCE_TAG);
    }
}
