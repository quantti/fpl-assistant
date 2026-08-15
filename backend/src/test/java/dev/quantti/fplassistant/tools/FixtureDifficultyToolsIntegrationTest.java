package dev.quantti.fplassistant.tools;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FixtureDifficultyTools} against real data — team resolution, the season guard and the
 * empty path. The rendering itself is pinned by {@link FixtureDifficultyToolsFormatTest}: no
 * season in the shared database has a {@code finished = false} fixture, so an integration test
 * can only ever reach the empty branch. Asserting {@code containsAnyOf(...)} across both
 * branches would pass without exercising either.
 */
@SpringBootTest
@ActiveProfiles("test")
class FixtureDifficultyToolsIntegrationTest {

    @Autowired
    FixtureDifficultyTools tools;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SeasonResolver seasonResolver;

    @Test
    void unknownTeamReturnsNotFound() {
        assertThat(tools.getFixtureDifficulty("Definitely Not A Real Club", 5))
                .contains("Team not found");
    }

    @Test
    void aCompletedSeasonWithNoUnfinishedFixturesSaysSoExplicitly() {
        int seasonId = seasonResolver.resolve().seasonId();
        Integer unfinished = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fixture WHERE season_id = ? AND finished = false",
                Integer.class, seasonId);
        assertThat(unfinished)
                .as("this assertion is only valid while the resolved season has no upcoming fixtures")
                .isZero();

        String result = tools.getFixtureDifficulty("Arsenal", 5);

        assertThat(result).contains("Data source: season");
        assertThat(result).contains("No upcoming fixtures found for Arsenal");
        assertThat(result).doesNotContain("Average FDR");
    }

    @Test
    void anAmbiguousTeamNameSaysWhichOtherClubsItMatched() {
        String result = tools.getFixtureDifficulty("man", 5);

        assertThat(result).contains("The name also matched:");
    }
}
