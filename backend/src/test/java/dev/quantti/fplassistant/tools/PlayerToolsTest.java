package dev.quantti.fplassistant.tools;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PlayerToolsTest {

    @Autowired
    PlayerTools playerTools;

    @Autowired
    SeasonResolver seasonResolver;

    @Test
    void getPlayerStatsByWebName() {
        String result = playerTools.getPlayerStats("Salah");

        assertThat(result).containsIgnoringCase("salah");
        assertThat(result).doesNotContain("not found");
    }

    @Test
    void everyAnswerNamesTheSeasonItCameFrom() {
        String result = playerTools.getPlayerStats("Salah");
        SeasonContext season = seasonResolver.resolve();

        assertThat(result).startsWith("Data source: season " + season.code());
    }

    @Test
    void noMatchReturnsNotFound() {
        String result = playerTools.getPlayerStats("zzznomatch999");

        assertThat(result).containsIgnoringCase("not found");
    }

    @Test
    void likeWildcardsAreEscapedNotInterpreted() {
        // Unescaped, "%" matches every player and returns the season's top scorers.
        String result = playerTools.getPlayerStats("%");

        assertThat(result).containsIgnoringCase("not found");
    }
}
