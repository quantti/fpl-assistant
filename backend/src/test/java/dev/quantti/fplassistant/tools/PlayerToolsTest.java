package dev.quantti.fplassistant.tools;

import java.time.LocalDate;

import dev.quantti.fplassistant.testsupport.TapasSeed;
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
        TapasSeed.ensure(jdbc);
        assertThat(dailyTable).as("must use the isolated table").endsWith("_test");
        clearDaily();
    }

    @AfterEach
    void clearDaily() {
        jdbc.update("DELETE FROM " + dailyTable + " WHERE season = ?", CURRENT_SEASON);
    }

    /** Undoes {@link #flipToCurrentSeason} so every other test still sees the fallback. */
    @AfterEach
    void unflipSeason() {
        jdbc.update("DELETE FROM player WHERE season_id = " + TapasSeed.CURRENT_SEASON_ID);
        jdbc.update("DELETE FROM gameweek WHERE season_id = " + TapasSeed.CURRENT_SEASON_ID);
        jdbc.update("DELETE FROM team WHERE season_id = " + TapasSeed.CURRENT_SEASON_ID);
    }

    /**
     * Seeds just enough of the current season for {@link SeasonResolver} to pick it over the
     * completed fallback — a season counts as populated once it holds both gameweeks and
     * players. With {@code withOkafor}, Okafor has a current-season row (180 minutes — the
     * one-or-two-match sample); without him, someone else populates the season and Okafor
     * exists only in last season's data.
     */
    private void flipToCurrentSeason(boolean withOkafor) {
        jdbc.execute("""
                INSERT INTO gameweek (id, season_id, name, deadline_time, finished, data_checked,
                                      is_current, is_next) VALUES
                    (101, 9027, 'Gameweek 1', TIMESTAMPTZ '2026-08-21 17:30:00+00', true, true,
                     true, false)
                ON CONFLICT (id, season_id) DO NOTHING
                """);
        jdbc.execute("""
                INSERT INTO team (id, season_id, code, name, short_name, strength) VALUES
                    (9001, 9027, 9001, 'Ashfield Rovers', 'ASH', 4)
                ON CONFLICT (id, season_id) DO NOTHING
                """);
        if (withOkafor) {
            jdbc.execute("""
                    INSERT INTO player (id, season_id, team_id, first_name, second_name, web_name,
                                        element_type, now_cost, status, total_points, minutes,
                                        goals_scored, assists, form, selected_by_percent) VALUES
                        (9301, 9027, 9001, 'Emeka', 'Okafor', 'Okafor', 3, 80, 'a', 12, 180,
                         1, 0, 6.0, 13.0)
                    ON CONFLICT (id, season_id) DO NOTHING
                    """);
        } else {
            jdbc.execute("""
                    INSERT INTO player (id, season_id, team_id, first_name, second_name, web_name,
                                        element_type, now_cost, status, total_points, minutes,
                                        goals_scored, assists, form, selected_by_percent) VALUES
                        (9302, 9027, 9001, 'Diego', 'Vasquez', 'Vasquez', 4, 96, 'a', 15, 180,
                         2, 0, 7.5, 26.0)
                    ON CONFLICT (id, season_id) DO NOTHING
                    """);
        }
    }

    /**
     * The season flip: once the current season holds data, last season's evidence must stay on
     * the card next to it — that is what the prompt's early-season rule weighs. Before this
     * change the flip silently replaced 38 matches of evidence with a two-match sample.
     */
    @Test
    void afterTheFlipBothSeasonsAreShownSideBySide() {
        flipToCurrentSeason(true);
        captureToday(TapasSeed.PLAYER_WEB_NAME, TapasSeed.PLAYER_FULL_NAME);

        String result = playerTools.getPlayerStats(TapasSeed.PLAYER_WEB_NAME);

        assertThat(result).contains("Data source: season " + TapasSeed.CURRENT_SEASON_CODE
                + " (the season currently being played)");
        assertThat(result).contains("Price: £8.0m"); // this season's plain label
        assertThat(result).contains("Minutes: 180"); // this season's small sample
        assertThat(result).contains("Data source: season " + TapasSeed.POPULATED_SEASON_CODE
                + " — a COMPLETED PREVIOUS season");
        assertThat(result).contains("is under way");
        assertThat(result).contains("Minutes: 265"); // last season's evidence still on the card
        assertThat(result).contains("Price (" + TapasSeed.POPULATED_SEASON_CODE + ", historical)");
    }

    @Test
    void afterTheFlipAPlayerWithNoCurrentSeasonRowIsStillAnsweredFromLastSeason() {
        flipToCurrentSeason(false); // Vasquez populates the current season; Okafor is not in it
        captureToday(TapasSeed.PLAYER_WEB_NAME, TapasSeed.PLAYER_FULL_NAME);

        String result = playerTools.getPlayerStats(TapasSeed.PLAYER_WEB_NAME);

        assertThat(result).contains("Data source: season " + TapasSeed.POPULATED_SEASON_CODE
                + " — a COMPLETED PREVIOUS season");
        assertThat(result).contains("Minutes: 265");
        assertThat(result).doesNotContain("(the season currently being played)");
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

    /**
     * This season's squad comes from FPL's own daily capture, not from the tapas table — which
     * held no rows at all for the season being played. Answering only from there is what let a
     * question about one player be answered about another who happened to exist in the season
     * the resolver had fallen back to.
     */
    @Test
    void aPlayerInThisSeasonsSquadIsAnsweredFromTheDailyCapture() {
        captureToday(TapasSeed.PLAYER_WEB_NAME, TapasSeed.PLAYER_FULL_NAME);

        String result = playerTools.getPlayerStats(TapasSeed.PLAYER_WEB_NAME);

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
        // Somebody else was captured today, so the squad list is known — and this name is not in
        // it. That is what makes the claim safe to make.
        captureToday("Someoneelse", "Someone Else Fictitious");

        String result = playerTools.getPlayerStats(TapasSeed.PLAYER_WEB_NAME);

        assertThat(result).contains("NOT IN THIS SEASON'S FPL GAME");
        assertThat(result).containsIgnoringCase(TapasSeed.PLAYER_WEB_NAME);
    }

    /**
     * Not knowing the squad is not the same as knowing the player is absent from it. With no
     * capture at all — a fresh deploy, or a capture that has never run — claiming absence would
     * answer every question with a confident falsehood.
     */
    @Test
    void withNoCaptureAtAllNoClaimIsMadeAboutThisSeason() {
        String result = playerTools.getPlayerStats(TapasSeed.PLAYER_WEB_NAME);

        assertThat(result).contains("squad list is unavailable");
        assertThat(result).doesNotContain("NOT IN THIS SEASON'S FPL GAME");
        assertThat(result).containsIgnoringCase(TapasSeed.PLAYER_WEB_NAME);
    }

    @Test
    void likeWildcardsAreEscapedNotInterpreted() {
        // Unescaped, "%" matches every player and returns the season's top scorers.
        String result = playerTools.getPlayerStats("%");

        assertThat(result).containsIgnoringCase("not found");
    }
}
