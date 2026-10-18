package dev.quantti.fplassistant.tools;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;

import dev.quantti.fplassistant.testsupport.DataAgeClock;
import dev.quantti.fplassistant.testsupport.TapasSeed;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Player observations against seeded {@code player_fixture_stats}; direct team totals are
 * covered separately by {@link TeamUnderstatIntegrationTest}. The real
 * {@link SeasonResolver} resolves to the completed seeded season, which is fine here — these
 * tests exercise the aggregation and formatting, and the provenance line correctly labels the
 * data as historical.
 */
@SpringBootTest
@ActiveProfiles("test")
class ExpectedStatsToolsIntegrationTest {

    static final Instant AS_OF = DataAgeClock.AS_OF;

    @Autowired
    JdbcTemplate jdbc;

    /** Built with the fixed clock rather than autowired; see {@link DataAgeClock}. */
    ExpectedStatsTools tools;
    @Autowired dev.quantti.fplassistant.teamxg.UnderstatStore understat;

    @Autowired
    SeasonResolver seasonResolver;

    @Autowired
    PlayerNameSearch names;

    @BeforeEach
    void buildTools() {
        tools = new ExpectedStatsTools(jdbc, seasonResolver, DataAgeClock.provider(), names, understat);
    }

    @BeforeEach
    void seed() {
        TapasSeed.ensure(jdbc);
    }

    @Test
    void getPlayerExpectedStatsReturnsAggregatesForAKnownPlayer() {
        String result = tools.getPlayerExpectedStats(TapasSeed.PLAYER_WEB_NAME, 5);

        assertThat(result).contains("Expected stats for");
        assertThat(result).contains("xG");
        assertThat(result).contains("Totals over window");
        assertThat(result).contains("Actual vs expected goal involvements");
        assertThat(result).contains("Data source: season");
    }

    @Test
    void unknownPlayerReturnsNotFound() {
        assertThat(tools.getPlayerExpectedStats("Definitely Not A Real Player", 5))
                .contains("Player not found");
    }

    private static final int ACCENTED_PLAYER_ID = 9311;

    private void seedAccentedPlayer() {
        jdbc.update("""
                INSERT INTO player (id, season_id, team_id, first_name, second_name, web_name,
                                    element_type, now_cost, status, total_points, minutes,
                                    goals_scored, assists, form, selected_by_percent)
                VALUES (9311, ?, ?, 'Tarik', 'Muharemović', 'Muharemović', 2, 45, 'a', 20, 360,
                        0, 0, 3.0, 1.5)
                ON CONFLICT (id, season_id) DO NOTHING
                """, TapasSeed.POPULATED_SEASON_ID, TapasSeed.TEAM_ASHFIELD);
    }

    @AfterEach
    void deleteAccentedPlayer() {
        jdbc.update("DELETE FROM player WHERE season_id = ? AND id = ?",
                TapasSeed.POPULATED_SEASON_ID, ACCENTED_PLAYER_ID);
    }

    @Test
    void unaccentedFullNameFindsTheAccentedPlayersExpectedStats() {
        seedAccentedPlayer();
        String result = tools.getPlayerExpectedStats("Tarik Muharemovic", 5);
        // The web name, not only the absence of "Player not found": the database-error reply
        // satisfies that on its own, and would leave a broken query at this site green.
        assertThat(result).contains("Muharemović").doesNotContain("Player not found");
    }

    @Test
    void aClubAfterTheNameStillFindsThePlayersExpectedStats() {
        seedAccentedPlayer();
        String result = tools.getPlayerExpectedStats("Muharemovic Ashfield", 5);
        assertThat(result).contains("Muharemović").doesNotContain("Player not found");
    }

    // --- KAR-9: sample dates and the unknown core-sync time --------------------------------

    private static final String STORED_ROWS_ONLY =
            " Stored rows only; this does not show that every match through that date is present.";
    private static final String METRIC_REFRESH = "Metric refresh: season "
            + TapasSeed.POPULATED_SEASON_CODE + ", returned sample;";

    /** The notice and coverage sit after the season banner and before the first numeric row. */
    @Test
    void playerCoverageNamesTheReturnedMatchDatesBeforeTheRows() {
        String result = tools.getPlayerExpectedStats(TapasSeed.PLAYER_WEB_NAME, 5);

        String coverage = "Stored match coverage: Okafor, season " + TapasSeed.POPULATED_SEASON_CODE
                + " (3 returned fixture rows), known match dates from 2025-08-16 15:00 UK (389 days ago)"
                + " to 2025-08-24 15:00 UK (381 days ago)." + STORED_ROWS_ONLY;
        assertThat(result).containsOnlyOnce(METRIC_REFRESH).containsOnlyOnce(coverage);
        int banner = result.indexOf("Data source: season " + TapasSeed.POPULATED_SEASON_CODE);
        assertThat(banner).isZero();
        assertThat(result.indexOf(METRIC_REFRESH)).isGreaterThan(banner)
                .isLessThan(result.indexOf(coverage));
        assertThat(result.indexOf(coverage)).isLessThan(result.indexOf("Expected stats for"));
        assertThat(result).doesNotContain("Stored-data age").doesNotContain("Data source: season "
                + TapasSeed.CURRENT_SEASON_CODE);
    }

    /** Coverage describes the limited window that was returned, not the player's whole history. */
    @Test
    void coverageDescribesOnlyTheReturnedWindow() {
        String result = tools.getPlayerExpectedStats(TapasSeed.PLAYER_WEB_NAME, 2);

        assertThat(result).contains("Stored match coverage: Okafor, season " + TapasSeed.POPULATED_SEASON_CODE
                + " (2 returned fixture rows), known match dates from 2025-08-23 15:00 UK (382 days ago)"
                + " to 2025-08-24 15:00 UK (381 days ago).");
        assertThat(result).doesNotContain("2025-08-16");
    }

    @Test
    void noStoredRowsIsNotAZeroDayOldRecord() {
        String player = tools.getPlayerExpectedStats(TapasSeed.PLAYER_WITHOUT_UNDERLYING, 5);
        assertThat(player).contains("No fixture-level expected stats yet")
                .contains("Stored match coverage: Novak, season " + TapasSeed.POPULATED_SEASON_CODE
                        + ", no stored rows used; age unavailable.")
                .doesNotContain(" ago");
    }

    // --- KAR-58: defensive contribution on the named-player reply --------------------------

    private void seedDefconPlayer(int elementType) {
        jdbc.update("""
                INSERT INTO player (id, season_id, team_id, first_name, second_name, web_name,
                                    element_type, now_cost, status, total_points, minutes)
                VALUES (9291, 9026, 9004, 'Aksel', 'Lindqvist', 'Lindqvist', ?, 50, 'a', 9, 270)
                """, elementType);
    }

    private void seedDefconRow(int fixtureId, int gameweek, String kickoff, int minutes, Integer defcon) {
        jdbc.update("""
                INSERT INTO player_fixture_stats (fixture_id, player_id, season_id, gameweek,
                        player_team_id, opponent_team_id, was_home, kickoff_time, minutes,
                        goals_scored, assists, expected_goals, expected_assists,
                        expected_goal_involvements, value, defensive_contribution)
                VALUES (?, 9291, 9026, ?, 9004, 9001, true, ?::timestamptz, ?, 0, 0, 0.05, 0.02, 0.07, 50, ?)
                """, fixtureId, gameweek, kickoff, minutes, defcon);
    }

    /**
     * A comparison of two defenders is built from this reply, so it has to carry the DefCon
     * record: each match's count, how often the threshold was reached, and the rate. A match
     * with no reading is unknown, not a miss, and a match he did not play is not an appearance.
     */
    @Test
    @Transactional
    void aDefendersReplyCarriesHisDefconRecord() {
        seedDefconPlayer(2);
        seedDefconRow(9191, 4, "2025-09-01 14:00:00+00", 90, 12);
        seedDefconRow(9192, 5, "2025-09-08 14:00:00+00", 90, 8);
        seedDefconRow(9193, 6, "2025-09-15 14:00:00+00", 90, null);
        seedDefconRow(9194, 7, "2025-09-22 14:00:00+00", 0, 0);

        String result = tools.getPlayerExpectedStats("Lindqvist", 5);

        assertThat(result).contains("GW4 vs ASH (H): 90 min, xG 0.05 xA 0.02 (G0 A0) | DefCon actions 12")
                .contains("GW5 vs ASH (H): 90 min, xG 0.05 xA 0.02 (G0 A0) | DefCon actions 8")
                .contains("(G0 A0) | DefCon actions n/a")
                .contains("Defensive contribution, season " + TapasSeed.POPULATED_SEASON_CODE + " (DEF: 2 pts at 10+ CBIT):"
                        + " reached in 1 of 2 appearances with a reading (1 of 2 at 60+ min)"
                        + " | 10.00 actions per 90 | 1 appearance without a reading.");
    }

    /** A midfielder needs 12, recoveries included, so 11 is a miss where a defender's 11 is a hit. */
    @Test
    @Transactional
    void aMidfieldersDefconIsJudgedAtTwelve() {
        seedDefconPlayer(3);
        seedDefconRow(9191, 4, "2025-09-01 14:00:00+00", 90, 11);
        seedDefconRow(9192, 5, "2025-09-08 14:00:00+00", 45, 13);

        String result = tools.getPlayerExpectedStats("Lindqvist", 5);

        assertThat(result).contains("Defensive contribution, season " + TapasSeed.POPULATED_SEASON_CODE + " (MID: 2 pts at 12+ CBIRT):"
                + " reached in 1 of 2 appearances with a reading (0 of 1 at 60+ min) | 16.00 actions per 90.");
    }

    @Test
    @Transactional
    void noReadingAtAllIsSaidNotShownAsZero() {
        seedDefconPlayer(2);
        seedDefconRow(9191, 4, "2025-09-01 14:00:00+00", 90, null);

        String result = tools.getPlayerExpectedStats("Lindqvist", 5);

        assertThat(result).contains("Defensive contribution, season " + TapasSeed.POPULATED_SEASON_CODE + " (DEF: 2 pts at 10+ CBIT):"
                + " no reading in this window — unknown, not zero.");
    }

    @Test
    @Transactional
    void aGoalkeeperEarnsNoDefcon() {
        seedDefconPlayer(1);
        seedDefconRow(9191, 4, "2025-09-01 14:00:00+00", 90, 3);

        String result = tools.getPlayerExpectedStats("Lindqvist", 5);

        assertThat(result).contains("Defensive contribution, season " + TapasSeed.POPULATED_SEASON_CODE + ": not awarded to goalkeepers.")
                .doesNotContain("DefCon actions");
    }

    /**
     * The real {@code trg_pfs_updated_at} trigger stamps {@code updated_at} on any UPDATE, so a
     * DefCon-only enrichment write makes an old football sample look freshly written. The reply
     * must not move: its sample dates come from kickoff, and the core-sync time stays unknown.
     */
    @Test
    @Transactional
    void aDefConOnlyWriteRefreshesUpdatedAtButNotTheReportedSample() {
        OffsetDateTime oldWrite = OffsetDateTime.parse("2025-09-02T08:00:00Z");
        jdbc.update("""
                INSERT INTO player (id, season_id, team_id, first_name, second_name, web_name,
                                    element_type, now_cost, status, total_points, minutes)
                VALUES (9291, 9026, 9004, 'Aksel', 'Lindqvist', 'Lindqvist', 3, 50, 'a', 9, 90)
                """);
        jdbc.update("""
                INSERT INTO player_fixture_stats (fixture_id, player_id, season_id, gameweek,
                        player_team_id, opponent_team_id, was_home, kickoff_time, minutes,
                        goals_scored, assists, expected_goals, expected_assists,
                        expected_goal_involvements, value, updated_at)
                VALUES (9191, 9291, 9026, 5, 9004, 9001, true, TIMESTAMPTZ '2025-09-01 14:00:00+00',
                        77, 1, 0, 0.42, 0.11, 0.53, 50, ?)
                """, oldWrite);
        String before = tools.getPlayerExpectedStats("Lindqvist", 5);

        int updated = jdbc.update("""
                UPDATE player_fixture_stats SET defensive_contribution = 7
                WHERE fixture_id = 9191 AND player_id = 9291 AND season_id = 9026
                """);
        assertThat(updated).isOne();
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT updated_at, minutes, expected_goals FROM player_fixture_stats
                WHERE fixture_id = 9191 AND player_id = 9291 AND season_id = 9026
                """);
        assertThat(((java.sql.Timestamp) row.get("updated_at")).toInstant())
                .as("the trigger restamps the row").isAfter(oldWrite.toInstant());
        assertThat(((Number) row.get("minutes")).intValue()).isEqualTo(77);
        assertThat(row.get("expected_goals").toString()).isEqualTo("0.42");

        String after = tools.getPlayerExpectedStats("Lindqvist", 5);

        // KAR-58: the write moves the DefCon cell and line, and nothing else in the reply.
        assertThat(before).contains("DefCon actions n/a").contains("no reading in this window");
        assertThat(after).contains("DefCon actions 7").contains("reached in 0 of 1 appearance with a reading");
        assertThat(withoutDefcon(after)).isEqualTo(withoutDefcon(before));
        assertThat(after).contains(METRIC_REFRESH)
                .contains("Stored match coverage: Lindqvist, season " + TapasSeed.POPULATED_SEASON_CODE
                        + " (1 returned fixture row), known match date 2025-09-01 15:00 UK (373 days ago).")
                .contains("77 min, xG 0.42");
        String restamped = UkTime.format(((java.sql.Timestamp) row.get("updated_at")).toInstant(), null);
        assertThat(after).doesNotContain(restamped.substring(0, 10)).doesNotContain("2025-09-02");
    }

    private static String withoutDefcon(String reply) {
        return reply.replaceAll(" \\| DefCon actions \\S+", "")
                .replaceAll("(?m)^Defensive contribution.*\\R", "");
    }

    /**
     * A team aggregate whose source rows are partly or wholly undated: MIN/MAX alone would hide
     * them, so the counts disclose them, and no fixture or total is dropped for want of a date.
     */
}
