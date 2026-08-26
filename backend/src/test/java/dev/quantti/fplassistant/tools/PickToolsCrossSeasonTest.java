package dev.quantti.fplassistant.tools;

import dev.quantti.fplassistant.testsupport.SharedStatsSeed;
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
 * and shared keeps no stable player code; the whole-season window; the deliberately low weight on
 * last season's realised points; and the second provenance line. Without this class all of it
 * would first run in production, on the day the new season's first gameweek syncs.
 */
@SpringBootTest
@ActiveProfiles("test")
class PickToolsCrossSeasonTest {

    private static final int CURRENT = SharedStatsSeed.CURRENT_SEASON_ID;
    private static final int GAMEWEEK = 101;
    private static final int TEAM = 9001;

    @Autowired
    PickTools tools;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        SharedStatsSeed.ensure(jdbc);
        beginTheNewSeason();
    }

    /**
     * Removes only the rows this class inserts, by id. The current season is shared-owned and
     * shared with {@link SharedStatsSeed}, so a blanket delete on {@code season_id} would wipe another
     * class's rows the day one of them seeds it.
     */
    @AfterEach
    void endTheNewSeason() {
        jdbc.update("DELETE FROM player WHERE season_id = ? AND id BETWEEN 9401 AND 9410", CURRENT);
        jdbc.update("DELETE FROM player WHERE season_id = ? AND id = 9210",
                SharedStatsSeed.POPULATED_SEASON_ID);
        jdbc.update("DELETE FROM gameweek WHERE season_id = ? AND id = ?", CURRENT, GAMEWEEK);
        jdbc.update("DELETE FROM team WHERE season_id = ? AND id = ?", CURRENT, TEAM);
    }

    /**
     * Enough of the new season for {@link SeasonResolver} to prefer it — a season counts as
     * populated once it holds both gameweeks and players — but no {@code player_fixture_stats},
     * which is exactly the early-season position: a squad, a price, and nothing to judge.
     *
     * <p>The element ids deliberately differ from last season's for the same players. That is not
     * tidiness: it is what FPL does every summer, and it is the reason the bridge cannot use ids.
     */
    private void beginTheNewSeason() {
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
        jdbc.execute("""
                INSERT INTO player (id, season_id, team_id, first_name, second_name, web_name,
                                    element_type, now_cost, status, total_points, minutes,
                                    goals_scored, assists, form, selected_by_percent) VALUES
                    (9401, 9027, 9001, 'Emeka', 'Okafor',   'Okafor',   3, 80, 'a', 12, 90, 1, 0, 6.0,  4.0),
                    (9402, 9027, 9001, 'Diego', 'Vasquez',  'Vasquez',  4, 96, 'a', 15, 90, 2, 0, 7.5,  6.0),
                    (9403, 9027, 9001, 'Tiago', 'Ferreira', 'Ferreira', 2, 45, 'a',  6, 90, 0, 0, 3.0,  5.0),
                    -- Shares a web_name with a player who exists in last season's data too, so
                    -- the name bridge has two candidates and no way to tell them apart.
                    (9404, 9027, 9001, 'Nils',  'Rask',     'Rask',     3, 55, 'a',  4, 90, 0, 0, 2.0,  3.0),
                    (9405, 9027, 9001, 'Otto',  'Rask',     'Rask',     2, 45, 'a',  3, 90, 0, 0, 1.5,  2.0)
                ON CONFLICT (id, season_id) DO NOTHING
                """);
        jdbc.execute("""
                INSERT INTO player (id, season_id, team_id, first_name, second_name, web_name,
                                    element_type, now_cost, status, total_points, minutes,
                                    goals_scored, assists, form, selected_by_percent) VALUES
                    (9210, 9026, 9001, 'Nils', 'Rask', 'Rask', 3, 55, 'a', 30, 270, 1, 1, 4.0, 3.0)
                ON CONFLICT (id, season_id) DO NOTHING
                """);
    }

    /**
     * The point of the whole path: this season's squad, judged on last season's football. The
     * player is a different row with a different id from the one that holds the numbers.
     */
    @Test
    void thisSeasonsSquadIsRankedOnLastSeasonsUnderlyingNumbers() {
        String result = tools.getDifferentialPicks("MID", 100, 3);

        assertThat(result).contains(SharedStatsSeed.PLAYER_WEB_NAME);
        assertThat(result).containsPattern("xG/90 [0-9]\\.[0-9]{2}");
        assertThat(result).contains("Ranked from");
    }

    /**
     * The banner names the season being played; every number below it is last season's. AGENTS.md
     * requires each block to carry its own attribution rather than leaving one line to cover both.
     */
    @Test
    void theMetricsCarryTheirOwnProvenanceLineAndNotJustTheBanner() {
        String result = tools.getDifferentialPicks("any", 100, 3);

        assertThat(result).contains("Data source: season " + SharedStatsSeed.POPULATED_SEASON_CODE
                + " — a COMPLETED PREVIOUS season.");
        assertThat(result).contains("The underlying numbers are " + SharedStatsSeed.POPULATED_SEASON_CODE
                + "'s because this season has 0 completed gameweek(s)");
        assertThat(result).contains("never call them this season's form");
    }

    /**
     * A finished season is read whole. Taking its last ten gameweeks would mean its run-in —
     * rotation, coasting, relegated sides — and the earlier version did exactly that while
     * calling it "the previous season".
     */
    @Test
    void aFinishedSeasonIsReadWholeRatherThanByItsRunIn() {
        String result = tools.getCaptainCandidates(3);

        assertThat(result).contains("Ranked by Score over all of " + SharedStatsSeed.POPULATED_SEASON_CODE);
        assertThat(result).doesNotContain("last 10 matches");
    }

    /**
     * Last season's returns are weighted low on purpose, and the reason has to be the true one:
     * not a small sample — it is a whole season — but that roles, penalties and set-piece duty
     * change between seasons.
     */
    @Test
    void lastSeasonsRealisedPointsAreWeightedLowForTheStatedReason() {
        String result = tools.getCaptainCandidates(3);

        assertThat(result).contains("deliberately low");
        assertThat(result).contains("change between seasons");
        assertThat(result).doesNotContain("as the sample grows");
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
}
