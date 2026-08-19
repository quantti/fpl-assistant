package dev.quantti.fplassistant.tools;

import dev.quantti.fplassistant.testsupport.TapasSeed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FixtureDifficultyTools} against the seeded fixture league: team resolution, the ratings
 * branch, the empty branch and the ambiguity note. Before the move to a seeded database this
 * class could only reach the empty branch — no season in the shared database had a
 * {@code finished = false} fixture — so the Average FDR line went untested here and was pinned
 * only by {@link FixtureDifficultyToolsFormatTest}.
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

    @BeforeEach
    void seed() {
        TapasSeed.ensure(jdbc);
    }

    @Test
    void unknownTeamReturnsNotFound() {
        assertThat(tools.getFixtureDifficulty("Definitely Not A Real Club", 5))
                .contains("Team not found");
    }

    @Test
    void upcomingFixturesAreRatedAndAveraged() {
        int seasonId = seasonResolver.resolve().seasonId();
        Integer unfinished = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fixture WHERE season_id = ? AND finished = false",
                Integer.class, seasonId);
        assertThat(unfinished)
                .as("the ratings branch needs fixtures still to play")
                .isPositive();

        String result = tools.getFixtureDifficulty(TapasSeed.ASHFIELD, 5);

        assertThat(result).contains("Data source: season");
        assertThat(result).contains("Average FDR over the next");
        assertThat(result).doesNotContain("No upcoming fixtures found");
    }

    @Test
    void aTeamWithNothingLeftToPlaySaysSoExplicitly() {
        String result = tools.getFixtureDifficulty(TapasSeed.TEAM_WITHOUT_FIXTURES, 5);

        assertThat(result).contains("Data source: season");
        assertThat(result).contains("No upcoming fixtures found for");
        assertThat(result).doesNotContain("Average FDR");
    }

    @Test
    void anAmbiguousTeamNameSaysWhichOtherClubsItMatched() {
        String result = tools.getFixtureDifficulty(TapasSeed.AMBIGUOUS_TEAM_PREFIX, 5);

        assertThat(result).contains("The name also matched:");
    }
}
