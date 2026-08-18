package fi.karivanttinen.fplassistant.tools;

import fi.karivanttinen.fplassistant.testsupport.TapasSeed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class GameweekToolsTest {

    @Autowired
    GameweekTools gameweekTools;

    @Autowired
    SeasonResolver seasonResolver;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        TapasSeed.ensure(jdbc);
    }

    @Test
    void getGameweekInfoReturnsGameweekData() {
        String result = gameweekTools.getGameweekInfo();

        // The seeded season has gameweek 2 current and gameweek 3 next; the tool reports both.
        assertThat(result).contains("Gameweek 2").contains("Gameweek 3");
        assertThat(result).contains("Status: NEXT");
        // The plan's original assertion (containsAnyOf "current", "next", "Gameweek") also passed
        // on the empty-data message, because that message contains the word "current". Assert the
        // absence of that message explicitly instead.
        assertThat(result).doesNotContain("No gameweek data");
    }

    @Test
    void everyAnswerNamesTheSeasonItCameFrom() {
        String result = gameweekTools.getGameweekInfo();
        SeasonContext season = seasonResolver.resolve();

        assertThat(result).startsWith("Data source: season " + season.code());
    }

    /**
     * The seed reproduces the production changeover deliberately: the current season exists but
     * holds no gameweeks or players, so the resolver falls back to the completed one and the
     * provenance line has to say so.
     */
    @Test
    void fallbackToAPreviousSeasonIsLabelledAsHistorical() {
        SeasonContext season = seasonResolver.resolve();

        assertThat(season.code()).isEqualTo(TapasSeed.POPULATED_SEASON_CODE);
        assertThat(season.currentSeason()).isFalse();
        assertThat(season.provenance())
                .contains("COMPLETED PREVIOUS season")
                .contains(TapasSeed.CURRENT_SEASON_CODE);
    }
}
