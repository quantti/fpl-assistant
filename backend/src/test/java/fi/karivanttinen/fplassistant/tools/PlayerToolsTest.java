package fi.karivanttinen.fplassistant.tools;

import fi.karivanttinen.fplassistant.testsupport.TapasSeed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PlayerToolsTest {

    @Autowired
    PlayerTools playerTools;

    @Autowired
    SeasonResolver seasonResolver;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        TapasSeed.ensure(jdbc);
    }

    @Test
    void getPlayerStatsByWebName() {
        String result = playerTools.getPlayerStats(TapasSeed.PLAYER_WEB_NAME);

        assertThat(result).containsIgnoringCase(TapasSeed.PLAYER_WEB_NAME);
        assertThat(result).doesNotContain("not found");
    }

    @Test
    void everyAnswerNamesTheSeasonItCameFrom() {
        String result = playerTools.getPlayerStats(TapasSeed.PLAYER_WEB_NAME);
        SeasonContext season = seasonResolver.resolve();

        assertThat(result).startsWith("Data source: season " + season.code());
    }

    @Test
    void fullNameMatchesPlayer() {
        // LLMs pass full names despite the description saying "web name or surname";
        // no single column contains "Emeka Okafor", so this needs the concatenated match.
        String result = playerTools.getPlayerStats(TapasSeed.PLAYER_FULL_NAME);

        assertThat(result).containsIgnoringCase(TapasSeed.PLAYER_WEB_NAME);
        assertThat(result).doesNotContain("not found");
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
