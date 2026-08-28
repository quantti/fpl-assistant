package fi.karivanttinen.fplassistant.squad;

import java.util.Optional;

import fi.karivanttinen.fplassistant.testsupport.TapasSeed;
import fi.karivanttinen.fplassistant.tools.SeasonContext;
import fi.karivanttinen.fplassistant.tools.SeasonResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * I5: {@link CandidatePool#metricWindow} must not bridge to a previous season that is itself a
 * weaker source than the season being played.
 *
 * <p>{@code PickTools.metricSeason} only falls back when the previous season's completed
 * gameweeks exceed the season being played's own count — its comment says a previous season with
 * one synced gameweek "is not evidence to prefer over this season's four". {@code
 * CandidatePool.metricWindow} stated the same rule independently but dropped that comparison and
 * fell back unconditionally whenever the season being played held fewer than
 * {@code MIN_SAMPLE_GAMEWEEKS}. {@link TapasSeed#POPULATED_SEASON_ID} has exactly 2 completed
 * gameweeks, so any season being played with 3 or 4 of its own — thin, but not as thin as the
 * "previous" one — must be judged on its own numbers, not bridged.
 *
 * <p>Both tools now share the decision through {@link SeasonResolver#betterMetricSeason}, so this
 * class also guards against the two drifting apart again.
 */
@SpringBootTest
@ActiveProfiles("test")
class CandidatePoolMetricWindowTest {

    private static final int CURRENT = TapasSeed.CURRENT_SEASON_ID;
    private static final int TEAM = 9001;
    private static final int PLAYER = 9430;
    private static final int GW1 = 171;
    private static final int GW2 = 172;
    private static final int GW3 = 173;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CandidatePool pool;

    @Autowired
    SeasonResolver seasonResolver;

    @BeforeEach
    void seed() {
        TapasSeed.ensure(jdbc);
        beginTheNewSeasonWithThreeCompletedGameweeks();
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM player_fixture_stats WHERE season_id = ? AND player_id = ?",
                CURRENT, PLAYER);
        jdbc.update("DELETE FROM player WHERE season_id = ? AND id = ?", CURRENT, PLAYER);
        jdbc.update("DELETE FROM gameweek WHERE season_id = ? AND id IN (?, ?, ?)",
                CURRENT, GW1, GW2, GW3);
        jdbc.update("DELETE FROM team WHERE season_id = ? AND id = ?", CURRENT, TEAM);
    }

    /**
     * Enough of the new season for {@link SeasonResolver} to prefer it, and three completed
     * gameweeks of its own — below {@code MIN_SAMPLE_GAMEWEEKS} (5), but above the previous
     * season's 2.
     */
    private void beginTheNewSeasonWithThreeCompletedGameweeks() {
        jdbc.execute("""
                INSERT INTO team (id, season_id, code, name, short_name, strength) VALUES
                    (9001, 9027, 9001, 'Ashfield Rovers', 'ASH', 4)
                ON CONFLICT (id, season_id) DO NOTHING
                """);
        jdbc.execute("""
                INSERT INTO player (id, season_id, team_id, first_name, second_name, web_name,
                                    element_type, now_cost, status, total_points, minutes,
                                    goals_scored, assists, form, selected_by_percent) VALUES
                    (9430, 9027, 9001, 'Present', 'Player', 'Presentplayer', 3, 55, 'a', 12, 270,
                     1, 1, 5.0, 4.0)
                ON CONFLICT (id, season_id) DO NOTHING
                """);
        jdbc.update("""
                INSERT INTO gameweek (id, season_id, name, deadline_time, finished, data_checked,
                                      is_current, is_next) VALUES
                    (?, 9027, 'Gameweek 1', TIMESTAMPTZ '2026-08-15 17:30:00+00', true, true, false, false),
                    (?, 9027, 'Gameweek 2', TIMESTAMPTZ '2026-08-22 17:30:00+00', true, true, false, false),
                    (?, 9027, 'Gameweek 3', TIMESTAMPTZ '2026-08-29 17:30:00+00', true, true, true, false)
                ON CONFLICT (id, season_id) DO NOTHING
                """, GW1, GW2, GW3);
        for (int i = 0; i < 3; i++) {
            jdbc.update("""
                    INSERT INTO player_fixture_stats (fixture_id, player_id, season_id, gameweek,
                            player_team_id, opponent_team_id, was_home, minutes, total_points,
                            value)
                    VALUES (?, ?, 9027, ?, 9001, 9001, true, 90, 2, 55)
                    ON CONFLICT (fixture_id, player_id, season_id) DO NOTHING
                    """, 9990 + i, PLAYER, GW1 + i);
        }
    }

    @Test
    void aPreviousSeasonWithFewerCompletedGameweeksIsNotUsedAsTheBridge() {
        SeasonContext resolved = seasonResolver.resolve();
        assertThat(resolved.seasonId()).as("the resolver must prefer the new season now that it"
                + " holds a gameweek and a player").isEqualTo(CURRENT);
        assertThat(resolved.currentSeason()).isTrue();

        Optional<SeasonContext> previous = seasonResolver.previousSeason(resolved);
        assertThat(previous).as("the previous (tapas) season must exist and hold only 2"
                + " completed gameweeks, fewer than the 3 this test gave the new season")
                .isPresent();
        assertThat(seasonResolver.completedGameweeks(previous.orElseThrow().seasonId()))
                .isEqualTo(2);
        assertThat(seasonResolver.completedGameweeks(resolved.seasonId())).isEqualTo(3);

        CandidatePool.MetricWindow window = pool.metricWindow(resolved);

        assertThat(window.isResolvedSeason()).as("3 completed gameweeks beat the previous"
                + " season's 2, so the season being played must be used even though it is below"
                + " MIN_SAMPLE_GAMEWEEKS — bridging to a thinner season is not an improvement")
                .isTrue();
        assertThat(window.seasonId()).isEqualTo(CURRENT);
    }
}
