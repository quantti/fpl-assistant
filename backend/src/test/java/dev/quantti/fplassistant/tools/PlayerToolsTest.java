package dev.quantti.fplassistant.tools;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import dev.quantti.fplassistant.testsupport.DataAgeClock;
import dev.quantti.fplassistant.testsupport.SharedStatsSeed;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PlayerToolsTest {

    static final Instant AS_OF = DataAgeClock.AS_OF;

    /** Built with the fixed clock rather than autowired; see {@link DataAgeClock}. */
    PlayerTools playerTools;

    @Autowired
    SeasonResolver seasonResolver;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DailyPriceSource daily;

    @Autowired
    PlayerNameSearch names;

    @BeforeEach
    void buildTools() {
        playerTools = new PlayerTools(jdbc, seasonResolver, daily, DataAgeClock.provider(), names);
    }

    @Value("${fpl.daily-price.table-name}")
    String dailyTable;

    private static final String CURRENT_SEASON = "2099-00";

    /** Ids this class inserts into the (shared-owned) current season, and removes again. */
    private static final int FLIP_GAMEWEEK_ID = 101;
    private static final int FLIP_TEAM_ID = 9001;
    private static final List<Integer> FLIP_PLAYER_IDS = List.of(9301, 9302);

    @BeforeEach
    void seed() {
        SharedStatsSeed.ensure(jdbc);
        assertThat(dailyTable).as("must use the isolated table").endsWith("_test");
        clearDaily();
        // Also before, not only after: a hard abort mid-class would otherwise leave the flip in
        // place for the next run.
        unflipSeason();
    }

    @AfterEach
    void clearDaily() {
        jdbc.update("DELETE FROM " + dailyTable + " WHERE season = ?", CURRENT_SEASON);
    }

    /**
     * Undoes {@link #flipToCurrentSeason} so every other test still sees the fallback. Deletes
     * only the rows this class inserts, by id: the current season is shared-owned and shared with
     * {@link SharedStatsSeed}, and a blanket delete on {@code season_id} would silently wipe another
     * class's rows the day one of them seeds that season.
     */
    @AfterEach
    void unflipSeason() {
        // The transferred player's current-season row goes with them: it references the flipped
        // team, so it must be gone before that team is.
        jdbc.update("DELETE FROM player WHERE season_id = ? AND id IN (?, ?, ?)",
                SharedStatsSeed.CURRENT_SEASON_ID, FLIP_PLAYER_IDS.get(0), FLIP_PLAYER_IDS.get(1),
                TRANSFERRED_CURRENT_ID);
        jdbc.update("DELETE FROM gameweek WHERE season_id = ? AND id = ?",
                SharedStatsSeed.CURRENT_SEASON_ID, FLIP_GAMEWEEK_ID);
        jdbc.update("DELETE FROM team WHERE season_id = ? AND id = ?",
                SharedStatsSeed.CURRENT_SEASON_ID, FLIP_TEAM_ID);
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
        captureToday(SharedStatsSeed.PLAYER_WEB_NAME, SharedStatsSeed.PLAYER_FULL_NAME);

        String result = playerTools.getPlayerStats(SharedStatsSeed.PLAYER_WEB_NAME);

        assertThat(result).contains("Data source: season " + SharedStatsSeed.CURRENT_SEASON_CODE
                + " (the season currently being played)");
        assertThat(result).contains("Price: £8.0m"); // this season's plain label
        assertThat(result).contains("Minutes: 180"); // this season's small sample
        assertThat(result).contains("Data source: season " + SharedStatsSeed.POPULATED_SEASON_CODE
                + " — a COMPLETED PREVIOUS season");
        assertThat(result).contains("is under way");
        assertThat(result).contains("Minutes: 265"); // last season's evidence still on the card
        assertThat(result).contains("Price (" + SharedStatsSeed.POPULATED_SEASON_CODE + ", historical)");
    }

    @Test
    void afterTheFlipAPlayerWithNoCurrentSeasonRowIsStillAnsweredFromLastSeason() {
        flipToCurrentSeason(false); // Vasquez populates the current season; Okafor is not in it
        captureToday(SharedStatsSeed.PLAYER_WEB_NAME, SharedStatsSeed.PLAYER_FULL_NAME);

        String result = playerTools.getPlayerStats(SharedStatsSeed.PLAYER_WEB_NAME);

        assertThat(result).contains("Data source: season " + SharedStatsSeed.POPULATED_SEASON_CODE
                + " — a COMPLETED PREVIOUS season");
        assertThat(result).contains("Minutes: 265");
        assertThat(result).doesNotContain("(the season currently being played)");
        // The season being played holds data, but none of it is on this card: saying it "is
        // under way" beside numbers that are not there implies a comparison the reply cannot make.
        assertThat(result).doesNotContain("is under way");
        assertThat(result).contains("No " + SharedStatsSeed.CURRENT_SEASON_CODE
                + " numbers accompany these");
    }

    /**
     * Absence has to be claimed from the daily capture alone. Once the season has flipped, a
     * name the capture missed but the player table carries would otherwise be declared
     * unpickable directly above that same season's statistics.
     */
    @Test
    void afterTheFlipTheAbsenceClaimIsSoftenedWhenThisSeasonHasStatisticsForTheName() {
        flipToCurrentSeason(true); // Okafor has a current-season row
        captureToday("Someoneelse", "Someone Else Fictitious"); // ...but is not in today's capture

        String result = playerTools.getPlayerStats(SharedStatsSeed.PLAYER_WEB_NAME);

        assertThat(result).doesNotContain("NOT IN THIS SEASON'S FPL GAME");
        assertThat(result).contains("NOT CONFIRMED IN THIS SEASON'S FPL GAME");
        assertThat(result).contains("the season currently being played");
        assertThat(result).contains("Minutes: 180");
    }

    /** One row of the daily capture: this season's squad, as FPL's own API reports it. */
    private void captureToday(String webName, String fullName) {
        captureToday(webName, fullName, "TST", "Testville United");
    }

    private void captureToday(String webName, String fullName, String teamShort, String teamName) {
        jdbc.update("INSERT INTO " + dailyTable + " (season, price_date, element_id, web_name,"
                        + " full_name, team_short, team_name, element_type, now_cost,"
                        + " cost_change_event, cost_change_start, selected_by_percent, status, news)"
                        + " VALUES (?, ?, 9901, ?, ?, ?, ?, 2, 55, 0, 0,"
                        + " 12.3, 'a', '')",
                CURRENT_SEASON, LocalDate.now(), webName, fullName, teamShort, teamName);
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

        assertThat(result).contains("Data source: season " + season.code());
    }

    /**
     * The provenance paragraph is four lines on the fallback path; printing it as a banner and
     * again as the block's own header spends the model's attention twice on the same sentence.
     */
    @Test
    void theSeasonIsNamedOnceNotTwice() {
        String result = playerTools.getPlayerStats(SharedStatsSeed.PLAYER_WEB_NAME);
        SeasonContext season = seasonResolver.resolve();

        assertThat(result).containsOnlyOnce("Data source: season " + season.code());
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
        assertThat(result).doesNotContain("Stored-data age");
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
        // Somebody else was captured today, so the squad list is known — and this name is not in
        // it. That is what makes the claim safe to make.
        captureToday("Someoneelse", "Someone Else Fictitious");

        String result = playerTools.getPlayerStats(SharedStatsSeed.PLAYER_WEB_NAME);

        assertThat(result).contains("NOT IN THIS SEASON'S FPL GAME");
        assertThat(result).containsIgnoringCase(SharedStatsSeed.PLAYER_WEB_NAME);
    }

    /**
     * Not knowing the squad is not the same as knowing the player is absent from it. With no
     * capture at all — a fresh deploy, or a capture that has never run — claiming absence would
     * answer every question with a confident falsehood.
     */
    @Test
    void withNoCaptureAtAllNoClaimIsMadeAboutThisSeason() {
        String result = playerTools.getPlayerStats(SharedStatsSeed.PLAYER_WEB_NAME);

        assertThat(result).contains("squad list is unavailable");
        assertThat(result).doesNotContain("NOT IN THIS SEASON'S FPL GAME");
        assertThat(result).containsIgnoringCase(SharedStatsSeed.PLAYER_WEB_NAME);
    }

    /** Sets one player row's write time; the player table has no update trigger, so it sticks. */
    private void writtenAt(int playerId, int seasonId, Instant at) {
        OffsetDateTime value = at == null ? null : OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
        int updated = jdbc.update("UPDATE player SET updated_at = ? WHERE id = ? AND season_id = ?",
                value, playerId, seasonId);
        assertThat(updated).isOne();
        assertThat(jdbc.queryForObject("SELECT updated_at FROM player WHERE id = ? AND season_id = ?",
                OffsetDateTime.class, playerId, seasonId))
                .as("the seeded write time must stick").isEqualTo(value);
    }

    /** Each seasonal block is dated by its own row: a fresh current row does not refresh last season's. */
    @Test
    @Transactional
    void eachSeasonBlockCarriesItsOwnRowWriteAge() {
        flipToCurrentSeason(true);
        captureToday(SharedStatsSeed.PLAYER_WEB_NAME, SharedStatsSeed.PLAYER_FULL_NAME);
        writtenAt(9301, SharedStatsSeed.CURRENT_SEASON_ID, AS_OF.minus(Duration.ofHours(2)));
        writtenAt(9201, SharedStatsSeed.POPULATED_SEASON_ID, Instant.parse("2026-08-01T12:00:00Z"));

        String result = playerTools.getPlayerStats(SharedStatsSeed.PLAYER_WEB_NAME);

        String currentAge = "Stored-data age: player record for Okafor, season " + SharedStatsSeed.CURRENT_SEASON_CODE
                + ", last recorded write 2026-09-10 11:00 UK (2 hours ago)."
                + " This is a database record time, not a live FPL check.";
        String previousAge = "Stored-data age: player record for Okafor, season " + SharedStatsSeed.POPULATED_SEASON_CODE
                + ", last recorded write 2026-08-01 13:00 UK (40 days ago).";
        assertThat(result).containsOnlyOnce(currentAge).containsOnlyOnce(previousAge);
        int currentBanner = result.indexOf("(the season currently being played)");
        int previousBanner = result.indexOf("a COMPLETED PREVIOUS season");
        assertThat(result.indexOf(currentAge)).isBetween(currentBanner, previousBanner);
        assertThat(result.indexOf(previousAge)).isGreaterThan(previousBanner);
        // Values and banners are unchanged by the age lines.
        assertThat(result).contains("Minutes: 180").contains("Minutes: 265")
                .contains("Price (" + SharedStatsSeed.POPULATED_SEASON_CODE + ", historical)");
    }

    /** Today's daily capture dates the identity block only; it does not freshen old statistics. */
    @Test
    @Transactional
    void aFreshDailyCaptureDoesNotRefreshOldPlayerStatistics() {
        captureToday(SharedStatsSeed.PLAYER_WEB_NAME, SharedStatsSeed.PLAYER_FULL_NAME);
        writtenAt(9201, SharedStatsSeed.POPULATED_SEASON_ID, Instant.parse("2026-06-17T12:00:00Z"));

        String result = playerTools.getPlayerStats(SharedStatsSeed.PLAYER_WEB_NAME);

        assertThat(result).startsWith("Data source: FPL live prices for season " + CURRENT_SEASON);
        String dailyBlock = result.substring(0, result.indexOf("Data source: season "));
        assertThat(dailyBlock).contains("this season: £5.5m").doesNotContain("Stored-data age");
        assertThat(result).containsOnlyOnce("Stored-data age: player record for Okafor, season "
                + SharedStatsSeed.POPULATED_SEASON_CODE + ", last recorded write 2026-06-17 13:00 UK (85 days ago).");
    }

    @Test
    @Transactional
    void nullAndFutureWriteTimesStayUnknown() {
        writtenAt(9201, SharedStatsSeed.POPULATED_SEASON_ID, null);
        assertThat(playerTools.getPlayerStats(SharedStatsSeed.PLAYER_WEB_NAME))
                .contains("Stored-data age: player record for Okafor, season " + SharedStatsSeed.POPULATED_SEASON_CODE
                        + ", write time unavailable.")
                .doesNotContain("ago");

        writtenAt(9201, SharedStatsSeed.POPULATED_SEASON_ID, Instant.parse("2026-09-15T12:00:00Z"));
        assertThat(playerTools.getPlayerStats(SharedStatsSeed.PLAYER_WEB_NAME))
                .contains("last recorded write 2026-09-15 13:00 UK (future-dated anomaly; age unavailable).")
                .doesNotContain("ago");
    }

    /**
     * Name preference runs before the card is dated: a higher-ranked namesake the substring search
     * also found, but which is filtered out of the display, cannot lend the card its fresh write.
     */
    @Test
    @Transactional
    void aNamesakeFilteredOutOfTheCardCannotDetermineItsAge() {
        jdbc.execute("""
                INSERT INTO player (id, season_id, team_id, first_name, second_name, web_name,
                                    element_type, now_cost, status, total_points, minutes, updated_at)
                VALUES (9391, 9026, 9002, 'Chidi', 'Okaforo', 'Okaforo', 3, 60, 'a', 99, 900,
                        TIMESTAMPTZ '2026-09-10 11:30:00+00')
                """);
        writtenAt(9201, SharedStatsSeed.POPULATED_SEASON_ID, Instant.parse("2026-08-11T12:00:00Z"));
        captureToday(SharedStatsSeed.PLAYER_WEB_NAME, SharedStatsSeed.PLAYER_FULL_NAME);

        String result = playerTools.getPlayerStats(SharedStatsSeed.PLAYER_WEB_NAME);

        assertThat(result).doesNotContain("Okaforo").doesNotContain("<1 hour ago");
        assertThat(result).containsOnlyOnce("Stored-data age:")
                .contains("player record for Okafor, season " + SharedStatsSeed.POPULATED_SEASON_CODE
                        + ", last recorded write 2026-08-11 13:00 UK (30 days ago).");
    }

    @Test
    void likeWildcardsAreEscapedNotInterpreted() {
        // Unescaped, "%" matches every player and returns the season's top scorers.
        String result = playerTools.getPlayerStats("%");

        assertThat(result).containsIgnoringCase("not found");
    }

    /** Id this class inserts for KAR-57 accent/club-hint coverage, and removes again. */
    private static final int ACCENTED_PLAYER_ID = 9311;

    private void seedAccentedPlayer() {
        jdbc.update("""
                INSERT INTO player (id, season_id, team_id, first_name, second_name, web_name,
                                    element_type, now_cost, status, total_points, minutes,
                                    goals_scored, assists, form, selected_by_percent)
                VALUES (9311, ?, ?, 'Tarik', 'Muharemović', 'Muharemović', 2, 45, 'a', 20, 360,
                        0, 0, 3.0, 1.5)
                ON CONFLICT (id, season_id) DO NOTHING
                """, SharedStatsSeed.POPULATED_SEASON_ID, SharedStatsSeed.TEAM_ASHFIELD);
    }

    @AfterEach
    void deleteAccentedPlayer() {
        jdbc.update("DELETE FROM player WHERE season_id = ? AND id = ?",
                SharedStatsSeed.POPULATED_SEASON_ID, ACCENTED_PLAYER_ID);
    }

    @Test
    void unaccentedFullNameFindsTheAccentedPlayersSeasonStats() {
        seedAccentedPlayer();
        String result = playerTools.getPlayerStats("Tarik Muharemovic");
        assertThat(result).contains("Muharemović").doesNotContain("No player matched");
    }

    @Test
    void aClubAfterTheNameStillFindsThePlayer() {
        seedAccentedPlayer();
        String result = playerTools.getPlayerStats("Muharemovic Ashfield");
        assertThat(result).contains("Muharemović").doesNotContain("No player matched");
    }

    /** Ids this class inserts for the summer-transfer and NULL-name cases, and removes again. */
    private static final int TRANSFERRED_PREVIOUS_ID = 9321;
    private static final int TRANSFERRED_CURRENT_ID = 9322;
    private static final int NULL_NAMED_PLAYER_ID = 9331;
    private static final String TRANSFERRED_WEB_NAME = "Trevelyan";

    @AfterEach
    void deleteTransferredAndNullNamedPlayers() {
        jdbc.update("DELETE FROM player WHERE season_id = ? AND id IN (?, ?)",
                SharedStatsSeed.POPULATED_SEASON_ID, TRANSFERRED_PREVIOUS_ID, NULL_NAMED_PLAYER_ID);
    }

    /** One player, one web name, two clubs: Northmoor City last season, Ashfield this one. */
    private void seedTransferredPlayer() {
        jdbc.update("""
                INSERT INTO player (id, season_id, team_id, first_name, second_name, web_name,
                                    element_type, now_cost, status, total_points, minutes,
                                    goals_scored, assists, form, selected_by_percent)
                VALUES (9321, ?, ?, 'Owen', 'Trevelyan', 'Trevelyan', 3, 70, 'a', 44, 2700,
                        7, 4, 5.0, 9.0)
                ON CONFLICT (id, season_id) DO NOTHING
                """, SharedStatsSeed.POPULATED_SEASON_ID, SharedStatsSeed.TEAM_NORTHMOOR_CITY);
        jdbc.update("""
                INSERT INTO player (id, season_id, team_id, first_name, second_name, web_name,
                                    element_type, now_cost, status, total_points, minutes,
                                    goals_scored, assists, form, selected_by_percent)
                VALUES (9322, ?, ?, 'Owen', 'Trevelyan', 'Trevelyan', 3, 75, 'a', 9, 175,
                        1, 0, 3.0, 4.0)
                ON CONFLICT (id, season_id) DO NOTHING
                """, SharedStatsSeed.CURRENT_SEASON_ID, FLIP_TEAM_ID);
    }

    /**
     * The prompt tells the model to pass the club, but each season's rows carry that season's
     * club: matching the season statistics on the raw query drops the season a summer signing
     * spent at his old club — and September is when that player is asked about.
     */
    @Test
    void aClubTokenDoesNotHideThePreviousSeasonOfATransferredPlayer() {
        flipToCurrentSeason(true);
        seedTransferredPlayer();
        captureToday(TRANSFERRED_WEB_NAME, "Owen Trevelyan",
                SharedStatsSeed.ASHFIELD_SHORT, SharedStatsSeed.ASHFIELD);

        String result = playerTools.getPlayerStats(
                TRANSFERRED_WEB_NAME + " " + SharedStatsSeed.ASHFIELD_SHORT);

        assertThat(result).contains("Data source: season " + SharedStatsSeed.CURRENT_SEASON_CODE
                + " (the season currently being played)");
        assertThat(result).contains("Minutes: 175"); // this season, at the new club
        assertThat(result).contains("Data source: season " + SharedStatsSeed.POPULATED_SEASON_CODE
                + " — a COMPLETED PREVIOUS season");
        assertThat(result).contains("Minutes: 2700"); // last season, at the old one
    }

    /** Both name columns are nullable in the shared schema, and one NULL nulls the whole chain. */
    @Test
    void aPlayerWithNoStoredFirstNameIsStillFoundByWebName() {
        jdbc.update("""
                INSERT INTO player (id, season_id, team_id, first_name, second_name, web_name,
                                    element_type, now_cost, status, total_points, minutes,
                                    goals_scored, assists, form, selected_by_percent)
                VALUES (9331, ?, ?, NULL, NULL, 'Pendlebury', 2, 45, 'a', 11, 450,
                        0, 1, 2.0, 0.8)
                ON CONFLICT (id, season_id) DO NOTHING
                """, SharedStatsSeed.POPULATED_SEASON_ID, SharedStatsSeed.TEAM_ASHFIELD);

        assertThat(playerTools.getPlayerStats("Pendlebury"))
                .contains("Pendlebury").doesNotContain("No player matched");
    }
}
