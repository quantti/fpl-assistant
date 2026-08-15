package dev.quantti.fplassistant.historical;

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
 * across three seasons — this exercises {@code HistoricalTools}/{@code HistoricalTeamResolver}
 * SQL and formatting, not CSV parsing (which {@link VaastavFixtureParserTest} and
 * {@link VaastavBackfillIntegrationTest} already cover against real files). Fictitious codes
 * (9101/9102) and fictitious seasons avoid colliding with real backfilled data in the shared
 * test table, the same reasoning applied in {@code HistoricalSchemaTest}.
 */
@SpringBootTest
@ActiveProfiles("test")
class HistoricalToolsIntegrationTest {

    private static final int UNITED_CODE = 9101;
    private static final int CITY_CODE = 9102;
    private static final String SOURCE_TAG = "hist-tools-test";

    @Autowired
    HistoricalTools historicalTools;

    @Autowired
    HistoricalTeamResolver teamResolver;

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

        insert("2098-99", 1, UNITED_CODE, CITY_CODE, "Test United", "Test City", 3, 1, "2098-08-15T15:00:00Z");
        insert("2099-00", 5, CITY_CODE, UNITED_CODE, "Test City", "Test United", 2, 2, "2099-09-20T15:00:00Z");
        insert("2100-01", 10, UNITED_CODE, CITY_CODE, "Test United", "Test City", 1, 0, "2100-10-25T15:00:00Z");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM " + tableName + " WHERE source = '" + SOURCE_TAG + "'");
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
        // Newest (2100-01, W 1-0 at home) must appear before the oldest (2098-99).
        assertThat(result.indexOf("2100-01")).isLessThan(result.indexOf("2098-99"));
        assertThat(result).contains("2100-01 GW10");
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

        assertThat(forward).contains("2098-99").contains("2099-00").contains("2100-01");
        assertThat(backward).contains("2098-99").contains("2099-00").contains("2100-01");
    }

    @Test
    void headToHeadOnUnknownClubReturnsNotFound() {
        assertThat(historicalTools.getHeadToHead("Test United", "Nonexistent FC", 5))
                .containsIgnoringCase("not found");
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
