package fi.karivanttinen.fplassistant.tools;

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

    @Test
    void getPlayerExpectedStatsReturnsAggregatesForAKnownPlayer() {
        String result = tools.getPlayerExpectedStats("Salah", 5);

        assertThat(result).contains("Expected stats for");
        assertThat(result).contains("xG");
        assertThat(result).contains("Totals over window");
        assertThat(result).contains("Data source: season 2025-26");
    }

    @Test
    void getTeamExpectedGoalsReturnsAggregatesForAKnownTeam() {
        String result = tools.getTeamExpectedGoals("Liverpool", 5);

        assertThat(result).contains("Attacking expected output for");
        assertThat(result).contains("xG");
        assertThat(result).contains("Totals over window");
        assertThat(result).contains("Data source: season 2025-26");
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
}
