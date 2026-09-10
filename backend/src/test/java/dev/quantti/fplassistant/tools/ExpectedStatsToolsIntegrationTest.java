package dev.quantti.fplassistant.tools;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import dev.quantti.fplassistant.testsupport.DataAgeClock;
import dev.quantti.fplassistant.testsupport.TapasSeed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ExpectedStatsTools} against the seeded {@code player_fixture_stats} rows. The real
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

    @Autowired
    SeasonResolver seasonResolver;

    @BeforeEach
    void buildTools() {
        tools = new ExpectedStatsTools(jdbc, seasonResolver, DataAgeClock.provider());
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
    void getTeamExpectedGoalsReportsBothAttackAndDefence() {
        String result = tools.getTeamExpectedGoals(TapasSeed.ASHFIELD, 5);

        assertThat(result).contains("Expected output for");
        assertThat(result).contains("Attack over window");
        assertThat(result).contains("Defence over window");
        assertThat(result).contains("xGA");
        assertThat(result).contains("conceded");
        assertThat(result).contains("Data source: season");
    }

    /**
     * The one aggregation in these tools that is not a plain SUM. FPL writes the team's
     * expected-goals-conceded and goals-conceded onto <em>every</em> player who was on the pitch,
     * so summing them multiplies the team's figure by its squad size; the tool uses MAX instead.
     * This pins that choice against the {@code fixture} table's own scoreline. The seed carries
     * the same shape: goals_conceded is the team figure repeated on each player row.
     */
    @Test
    void goalsConcededPerFixtureMatchesTheScoreline() {
        int seasonId = seasonResolver.resolve().seasonId();
        int teamId = TapasSeed.TEAM_ASHFIELD;

        List<Map<String, Object>> checked = jdbc.queryForList("""
                SELECT f.id,
                       CASE WHEN f.team_h = ? THEN f.team_a_score ELSE f.team_h_score END AS scoreline_conceded,
                       (SELECT MAX(pfs.goals_conceded) FROM player_fixture_stats pfs
                        WHERE pfs.season_id = f.season_id AND pfs.fixture_id = f.id
                          AND pfs.player_team_id = ?) AS aggregated_conceded
                FROM fixture f
                WHERE f.season_id = ? AND (f.team_h = ? OR f.team_a = ?) AND f.finished = true
                ORDER BY f.kickoff_time DESC
                LIMIT 10
                """, teamId, teamId, seasonId, teamId, teamId);

        assertThat(checked).as("expected finished fixtures to cross-check against").isNotEmpty();
        for (Map<String, Object> row : checked) {
            assertThat(row.get("aggregated_conceded"))
                    .as("fixture %s: MAX(goals_conceded) must equal the goals the team actually conceded",
                            row.get("id"))
                    .isEqualTo(row.get("scoreline_conceded"));
        }
    }

    @Test
    void unknownPlayerReturnsNotFound() {
        assertThat(tools.getPlayerExpectedStats("Definitely Not A Real Player", 5))
                .contains("Player not found");
    }

    @Test
    void unknownTeamReturnsNotFound() {
        assertThat(tools.getTeamExpectedGoals("Definitely Not A Real Club", 5))
                .contains("Team not found");
    }

    @Test
    void anAmbiguousTeamNameSaysWhichOtherClubsItMatched() {
        // The seeded league has two Northmoor clubs; answering for one silently reads as the
        // only match.
        String result = tools.getTeamExpectedGoals(TapasSeed.AMBIGUOUS_TEAM_PREFIX, 3);

        assertThat(result).contains("The name also matched:");
    }

    // --- KAR-9: sample dates and the unknown core-sync time --------------------------------

    private static final String STORED_ROWS_ONLY =
            " Stored rows only; this does not show that every match through that date is present.";

    /** The notice and coverage sit after the season banner and before the first numeric row. */
    @Test
    void playerCoverageNamesTheReturnedMatchDatesBeforeTheRows() {
        String result = tools.getPlayerExpectedStats(TapasSeed.PLAYER_WEB_NAME, 5);

        String coverage = "Stored match coverage: Okafor, season " + TapasSeed.POPULATED_SEASON_CODE
                + " (3 returned fixture rows), known match dates from 2025-08-16 15:00 UK (389 days ago)"
                + " to 2025-08-24 15:00 UK (381 days ago)." + STORED_ROWS_ONLY;
        assertThat(result).containsOnlyOnce(DataAge.METRIC_REFRESH).containsOnlyOnce(coverage);
        int banner = result.indexOf("Data source: season " + TapasSeed.POPULATED_SEASON_CODE);
        assertThat(banner).isZero();
        assertThat(result.indexOf(DataAge.METRIC_REFRESH)).isGreaterThan(banner)
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
    void teamCoverageCountsStoredPlayerRowsSeparatelyFromFixtures() {
        String result = tools.getTeamExpectedGoals(TapasSeed.ASHFIELD, 5);

        // Ashfield: 9101 (2 player rows), 9103 (2), 9110 (2).
        assertThat(result).containsOnlyOnce(DataAge.METRIC_REFRESH).contains(
                "Stored match coverage: " + TapasSeed.ASHFIELD + ", season " + TapasSeed.POPULATED_SEASON_CODE
                        + " (3 returned fixtures from 6 stored player rows), known match dates from"
                        + " 2025-08-16 15:00 UK (389 days ago) to 2025-08-24 15:00 UK (381 days ago)."
                        + STORED_ROWS_ONLY);
        assertThat(result.indexOf("Stored match coverage")).isLessThan(result.indexOf("Expected output for"));
    }

    @Test
    void noStoredRowsIsNotAZeroDayOldRecord() {
        String team = tools.getTeamExpectedGoals(TapasSeed.TEAM_WITHOUT_FIXTURES, 5);
        assertThat(team).contains("No fixture-level expected stats yet").contains(DataAge.METRIC_REFRESH)
                .contains("Stored match coverage: Woodbridge Athletic, season " + TapasSeed.POPULATED_SEASON_CODE
                        + ", no stored rows used; age unavailable.")
                .doesNotContain(" ago");

        String player = tools.getPlayerExpectedStats(TapasSeed.PLAYER_WITHOUT_UNDERLYING, 5);
        assertThat(player).contains("No fixture-level expected stats yet")
                .contains("Stored match coverage: Novak, season " + TapasSeed.POPULATED_SEASON_CODE
                        + ", no stored rows used; age unavailable.")
                .doesNotContain(" ago");
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

        assertThat(after).isEqualTo(before);
        assertThat(after).contains(DataAge.METRIC_REFRESH)
                .contains("Stored match coverage: Lindqvist, season " + TapasSeed.POPULATED_SEASON_CODE
                        + " (1 returned fixture row), known match date 2025-09-01 15:00 UK (373 days ago).")
                .contains("77 min, xG 0.42");
        String restamped = UkTime.format(((java.sql.Timestamp) row.get("updated_at")).toInstant(), null);
        assertThat(after).doesNotContain(restamped.substring(0, 10)).doesNotContain("2025-09-02");
    }

    /**
     * A team aggregate whose source rows are partly or wholly undated: MIN/MAX alone would hide
     * them, so the counts disclose them, and no fixture or total is dropped for want of a date.
     */
    @Test
    @Transactional
    void partlyAndWhollyUndatedTeamGroupsAreDisclosedNotDropped() {
        jdbc.update("""
                INSERT INTO team (id, season_id, code, name, short_name, strength)
                VALUES (9091, 9026, 9091, 'Quarry Vale', 'QVA', 2)
                """);
        jdbc.update("""
                INSERT INTO player_fixture_stats (fixture_id, player_id, season_id, gameweek,
                        player_team_id, opponent_team_id, was_home, kickoff_time, minutes,
                        goals_scored, expected_goals, expected_assists, expected_goal_involvements,
                        goals_conceded, expected_goals_conceded, value) VALUES
                    (9191, 9291, 9026, 7, 9091, 9001, true,  TIMESTAMPTZ '2025-09-20 14:00:00+00', 90, 1, 0.50, 0.10, 0.60, 2, 1.40, 50),
                    (9191, 9292, 9026, 7, 9091, 9001, true,  NULL,                                  90, 0, 0.20, 0.30, 0.50, 2, 1.40, 50),
                    (9192, 9291, 9026, 6, 9091, 9002, false, NULL,                                  90, 0, 0.10, 0.00, 0.10, 1, 0.70, 50),
                    (9192, 9292, 9026, 6, 9091, 9002, false, NULL,                                  90, 0, 0.30, 0.00, 0.30, 1, 0.70, 50),
                    (9193, 9291, 9026, 5, 9091, 9004, true,  TIMESTAMPTZ '2025-09-13 14:00:00+00', 90, 2, 0.90, 0.00, 0.90, 0, 0.30, 50)
                """);

        String all = tools.getTeamExpectedGoals("Quarry Vale", 3);

        assertThat(all).contains("Stored match coverage: Quarry Vale, season " + TapasSeed.POPULATED_SEASON_CODE
                + " (3 returned fixtures from 5 stored player rows), known match dates from"
                + " 2025-09-13 15:00 UK (361 days ago) to 2025-09-20 15:00 UK (354 days ago);"
                + " 3 of 5 stored rows have no match date.");
        assertThat(all).contains("GW7 vs ASH (H): xG 0.70").contains("GW6 vs NMC (A): xG 0.40")
                .contains("GW5 vs SEL (H): xG 0.90")
                .contains("Attack over window: xG 2.00 | xA 0.40 | xGI 2.40 | Goals 3")
                .contains("Defence over window: xGA 2.40 | Goals conceded 3");

        String latest = tools.getTeamExpectedGoals("Quarry Vale", 1);

        assertThat(latest).contains("(1 returned fixture from 2 stored player rows), known match date"
                + " 2025-09-20 15:00 UK (354 days ago); 1 of 2 stored rows has no match date.");
        assertThat(latest).doesNotContain("2025-09-13");
    }
}
