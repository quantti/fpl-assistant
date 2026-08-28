package fi.karivanttinen.fplassistant.tools;

import fi.karivanttinen.fplassistant.testsupport.NewSeasonSeed;
import fi.karivanttinen.fplassistant.testsupport.TapasSeed;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PickTools} when the season being played has begun but holds too little data to judge
 * anyone on — the state every season spends its first weeks in, and the one the rest of the
 * suite never reaches.
 *
 * <p>Everything specific to that state lives on this path and nowhere else: the bridge that
 * matches players across seasons <b>by name</b>, because FPL reassigns element ids every season
 * and tapas keeps no stable player code; the whole-season window; the deliberately low weight on
 * last season's realised points; and the second provenance line. Without this class all of it
 * would first run in production, on the day the new season's first gameweek syncs.
 */
@SpringBootTest
@ActiveProfiles("test")
class PickToolsCrossSeasonTest {

    private static final int CURRENT = TapasSeed.CURRENT_SEASON_ID;
    private static final int GAMEWEEK = 101;
    private static final int TEAM = 9001;

    @Autowired
    PickTools tools;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        TapasSeed.ensure(jdbc);
        NewSeasonSeed.begin(jdbc);
    }

    /**
     * Removes only the rows this class inserts, by id. The current season is tapas-owned and
     * shared with {@link TapasSeed}, so a blanket delete on {@code season_id} would wipe another
     * class's rows the day one of them seeds it.
     */
    @AfterEach
    void endTheNewSeason() {
        NewSeasonSeed.end(jdbc);
    }

    /**
     * Enough of the new season for {@link SeasonResolver} to prefer it — a season counts as
     * populated once it holds both gameweeks and players — but no {@code player_fixture_stats},
     * which is exactly the early-season position: a squad, a price, and nothing to judge.
     *
     * <p>The element ids deliberately differ from last season's for the same players. That is not
     * tidiness: it is what FPL does every summer, and it is the reason the bridge cannot use ids.
     */

    /**
     * The point of the whole path: this season's squad, judged on last season's football. The
     * player is a different row with a different id from the one that holds the numbers.
     */
    @Test
    void thisSeasonsSquadIsRankedOnLastSeasonsUnderlyingNumbers() {
        String result = tools.getDifferentialPicks("MID", 100, 3);

        assertThat(result).contains(TapasSeed.PLAYER_WEB_NAME);
        assertThat(result).containsPattern("xG/90 [0-9]\\.[0-9]{2}");
        assertThat(result).contains("Ranked from");
    }

    /**
     * The banner names the season being played; every number below it is last season's. AGENTS.md
     * requires each block to carry its own attribution rather than leaving one line to cover both.
     */
    /**
     * The previous season is named on every pick and in the footer, not on a second banner — a
     * second banner would claim every number below it is last season's, and none purely is.
     */
    @Test
    void theFooterAndEachPickNameThePreviousSeasonAndItsWeight() {
        String result = tools.getDifferentialPicks("any", 100, 3);

        assertThat(result).contains("Data source: season " + TapasSeed.CURRENT_SEASON_CODE);
        assertThat(result).doesNotContain("Data source: season " + TapasSeed.POPULATED_SEASON_CODE);
        assertThat(result).contains("The weight on " + TapasSeed.CURRENT_SEASON_CODE + " rises");
        assertThat(result).contains("blend 0% " + TapasSeed.CURRENT_SEASON_CODE + ", 100% "
                + TapasSeed.POPULATED_SEASON_CODE + " (0 mt this season)");
    }

    @Test
    void aFinishedSeasonIsReadWholeRatherThanByItsRunIn() {
        String result = tools.getCaptainCandidates(3);

        assertThat(result).contains("last 10 matches in " + TapasSeed.CURRENT_SEASON_CODE
                + ", blended with all of " + TapasSeed.POPULATED_SEASON_CODE);
    }

    /**
     * Last season's returns are weighted low on purpose, and the reason has to be the true one:
     * not a small sample — it is a whole season — but that roles, penalties and set-piece duty
     * change between seasons.
     */
    @Test
    void realisedPointsCarryNothingUntilThisSeasonHasCompletedGameweeks() {
        String result = tools.getCaptainCandidates(3);

        assertThat(result).contains("Realised points carry 0% of the weight here, rising towards");
    }

    /**
     * Two players called Rask, and no way to know which one last season's record belongs to.
     * Guessing would attach one player's numbers to another and print them as measured — the
     * failure the bridge exists to avoid — so both are dropped and the coverage line accounts
     * for them.
     */
    @Test
    void anAmbiguousNameIsDroppedRatherThanGivenAnotherPlayersRecord() {
        String result = tools.getDifferentialPicks("any", 100, 3);

        assertThat(result).doesNotContain(" Rask (");
        assertThat(result).containsPattern("Ranked from [0-9]+ of the [0-9]+ players matching");
    }

    /**
     * Two full matches this season is 30% on the ramp. Ferreira's xG/90 last season is 0.05
     * (0.05 + 0.05 across 180 minutes in TapasSeed); two matches at 0.25 make 0.25 this season;
     * the blend is 0.3 x 0.25 + 0.7 x 0.05 = 0.11.
     */
    @Test
    void twoMatchesThisSeasonBlendAtThirtyPercentAndTheLineSaysSo() {
        NewSeasonSeed.giveFerreiraTwoMatches(jdbc);

        String result = tools.getDifferentialPicks("DEF", 100, 3);

        assertThat(result).contains("Ferreira");
        assertThat(result).contains("xG/90 0.11");
        assertThat(result).contains("blend 30% " + TapasSeed.CURRENT_SEASON_CODE + ", 70% "
                + TapasSeed.POPULATED_SEASON_CODE + " (2 mt this season)");
        assertThat(result).contains("Ranked by Score over each player's last 10 matches in "
                + TapasSeed.CURRENT_SEASON_CODE + ", blended with all of "
                + TapasSeed.POPULATED_SEASON_CODE);
    }

    @Test
    void aPlayerWithNoMatchesThisSeasonIsRankedOnLastSeasonAndTheLineSaysZero() {
        String result = tools.getDifferentialPicks("DEF", 100, 3);

        assertThat(result).contains("Ferreira");
        assertThat(result).contains("xG/90 0.05");
        assertThat(result).contains("blend 0% " + TapasSeed.CURRENT_SEASON_CODE + ", 100% "
                + TapasSeed.POPULATED_SEASON_CODE + " (0 mt this season)");
    }
}
