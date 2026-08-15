package fi.karivanttinen.fplassistant.tools;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FixtureDifficultyTools} against real 2025-26 data. The fixtures for 2025-26 are all
 * finished, so with no upcoming (finished=false) fixtures the tool reports none — which is the
 * correct behaviour for a completed season and what these tests exercise.
 */
@SpringBootTest
@ActiveProfiles("test")
class FixtureDifficultyToolsIntegrationTest {

    @Autowired
    FixtureDifficultyTools tools;

    @Test
    void unknownTeamReturnsNotFound() {
        assertThat(tools.getFixtureDifficulty("Definitely Not A Real Club", 5))
                .contains("Team not found");
    }

    @Test
    void knownTeamEitherReturnsFixturesOrNoUpcomingMessage() {
        // 2025-26 is a completed season: every fixture is finished, so there are no upcoming
        // (finished=false) fixtures. The tool must say so cleanly, with provenance.
        String result = tools.getFixtureDifficulty("Arsenal", 5);
        assertThat(result).contains("Data source: season 2025-26");
        assertThat(result).containsAnyOf("Fixture difficulty for", "No upcoming fixtures found");
    }
}
