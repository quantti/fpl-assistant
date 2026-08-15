package dev.quantti.fplassistant.tools;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ExpectedStatsTools} against real 2025-26 {@code player_fixture_stats} data. The real
 * {@link SeasonResolver} resolves to 2025-26 (a completed fallback, since 2026-27 has no data
 * yet), which is fine here — these tests exercise the aggregation and formatting, and the
 * provenance line correctly labels the data as historical.
 */
@SpringBootTest
@ActiveProfiles("test")
class ExpectedStatsToolsIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ExpectedStatsTools tools;

    @Autowired
    SeasonResolver seasonResolver;

    @Test
    void getPlayerExpectedStatsReturnsAggregatesForAKnownPlayer() {
        String result = tools.getPlayerExpectedStats("Salah", 5);

        assertThat(result).contains("Expected stats for");
        assertThat(result).contains("xG");
        assertThat(result).contains("Totals over window");
        assertThat(result).contains("Actual vs expected goal involvements");
        assertThat(result).contains("Data source: season");
    }

    @Test
    void getTeamExpectedGoalsReportsBothAttackAndDefence() {
        String result = tools.getTeamExpectedGoals("Liverpool", 5);

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
     * This pins that choice against the {@code fixture} table's own scoreline.
     */
    @Test
    void goalsConcededPerFixtureMatchesTheScoreline() {
        int seasonId = seasonResolver.resolve().seasonId();
        Integer teamId = jdbc.queryForObject(
                "SELECT id FROM team WHERE season_id = ? ORDER BY id LIMIT 1", Integer.class, seasonId);

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
        // "man" matches both Manchester clubs; answering for one silently reads as the only match.
        String result = tools.getTeamExpectedGoals("man", 3);

        assertThat(result).contains("The name also matched:");
    }
}
