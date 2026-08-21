package dev.quantti.fplassistant.tools;

import java.time.LocalDate;

import dev.quantti.fplassistant.testsupport.SharedStatsSeed;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${fpl.daily-price.table-name}")
    String dailyTable;

    private static final String CURRENT_SEASON = "2099-00";

    @BeforeEach
    void seed() {
        SharedStatsSeed.ensure(jdbc);
        assertThat(dailyTable).as("must use the isolated table").endsWith("_test");
        clearDaily();
    }

    @AfterEach
    void clearDaily() {
        jdbc.update("DELETE FROM " + dailyTable + " WHERE season = ?", CURRENT_SEASON);
    }

    /** One row of the daily capture: this season's squad, as FPL's own API reports it. */
    private void captureToday(String webName, String fullName) {
        jdbc.update("INSERT INTO " + dailyTable + " (season, price_date, element_id, web_name,"
                        + " full_name, team_short, team_name, element_type, now_cost,"
                        + " cost_change_event, cost_change_start, selected_by_percent, status, news)"
                        + " VALUES (?, ?, 9901, ?, ?, 'TST', 'Testville United', 2, 55, 0, 0,"
                        + " 12.3, 'a', '')",
                CURRENT_SEASON, LocalDate.now(), webName, fullName);
    }

    @Test
    void getPlayerStatsByWebName() {
        String result = playerTools.getPlayerStats(SharedStatsSeed.PLAYER_WEB_NAME);

        assertThat(result).containsIgnoringCase(SharedStatsSeed.PLAYER_WEB_NAME);
        assertThat(result).doesNotContain("not found");
    }

    @Test
    void everyAnswerNamesTheSeasonItCameFrom() {
        String result = playerTools.getPlayerStats(SharedStatsSeed.PLAYER_WEB_NAME);
        SeasonContext season = seasonResolver.resolve();

        assertThat(result).startsWith("Data source: season " + season.code());
    }

    @Test
    void fullNameMatchesPlayer() {
        // LLMs pass full names despite the description saying "web name or surname";
        // no single column contains "Emeka Okafor", so this needs the concatenated match.
        String result = playerTools.getPlayerStats(SharedStatsSeed.PLAYER_FULL_NAME);

        assertThat(result).containsIgnoringCase(SharedStatsSeed.PLAYER_WEB_NAME);
        assertThat(result).doesNotContain("not found");
    }

    @Test
    void noMatchReturnsNotFound() {
        String result = playerTools.getPlayerStats("zzznomatch999");

        assertThat(result).containsIgnoringCase("not found");
    }

    /**
     * This season's squad comes from FPL's own daily capture, not from the shared table — which
     * held no rows at all for the season being played. Answering only from there is what let a
     * question about one player be answered about another who happened to exist in the season
     * the resolver had fallen back to.
     */
    @Test
    void aPlayerInThisSeasonsSquadIsAnsweredFromTheDailyCapture() {
        captureToday(SharedStatsSeed.PLAYER_WEB_NAME, SharedStatsSeed.PLAYER_FULL_NAME);

        String result = playerTools.getPlayerStats(SharedStatsSeed.PLAYER_WEB_NAME);

        assertThat(result).startsWith("Data source: FPL live prices for season " + CURRENT_SEASON);
        assertThat(result).contains("this season: £5.5m");
        assertThat(result).doesNotContain("NOT IN THIS SEASON");
    }

    /**
     * The case that produced the wrong answer: a player who exists in last season's data and not
     * in this season's game. Saying so is the point — a silent historical answer is exactly what
     * made a guessed name look like a successful lookup.
     */
    @Test
    void aPlayerMissingFromThisSeasonsSquadIsCalledOut() {
        String result = playerTools.getPlayerStats(SharedStatsSeed.PLAYER_WEB_NAME);

        assertThat(result).contains("NOT IN THIS SEASON'S FPL GAME");
        assertThat(result).containsIgnoringCase(SharedStatsSeed.PLAYER_WEB_NAME);
    }

    @Test
    void likeWildcardsAreEscapedNotInterpreted() {
        // Unescaped, "%" matches every player and returns the season's top scorers.
        String result = playerTools.getPlayerStats("%");

        assertThat(result).containsIgnoringCase("not found");
    }
}
