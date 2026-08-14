package fi.karivanttinen.fplassistant.tools;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class GameweekToolsTest {

    @Autowired
    GameweekTools gameweekTools;

    @Autowired
    SeasonResolver seasonResolver;

    @Test
    void getGameweekInfoReturnsGameweekData() {
        String result = gameweekTools.getGameweekInfo();

        assertThat(result).contains("Gameweek");
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

    @Test
    void fallbackToAPreviousSeasonIsLabelledAsHistorical() {
        SeasonContext season = seasonResolver.resolve();

        if (season.currentSeason()) {
            assertThat(season.provenance()).contains("currently being played");
        } else {
            assertThat(season.provenance())
                    .contains("COMPLETED PREVIOUS season")
                    .contains(season.currentSeasonCode());
        }
    }
}
