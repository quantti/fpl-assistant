package dev.quantti.fplassistant.squad;

import java.util.List;
import java.util.Map;

import dev.quantti.fplassistant.testsupport.TapasSeed;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link HorizonScorer#fixtureDifficulties} against a seeded fixture league — the one piece of
 * SQL in the class, and otherwise the only method {@link HorizonScorerTest} cannot reach, since
 * that class is a plain unit test with no database.
 *
 * <p>Own season, own teams, own single gameweek row: {@code fixtureDifficulties} is called with
 * an explicit season id (never through {@link dev.quantti.fplassistant.tools.SeasonResolver}),
 * and {@code fixture.gameweek} carries no foreign key to the {@code gameweek} table, so only the
 * one row needed to resolve "the current gameweek" has to exist — the rest of the fixture list's
 * gameweek numbers are plain integers.
 *
 * <p>Four clubs, one each for the shape that matters: an ordinary run of single fixtures, a club
 * with a double gameweek inside the horizon, a club with a blank gameweek inside the horizon, and
 * a club whose current-gameweek fixture has already kicked off and finished. All four share one
 * opponent (Rival United) purely to satisfy the fixture table's team foreign keys; nothing is
 * asserted about the opponent's own aggregate.
 */
@SpringBootTest
@ActiveProfiles("test")
class HorizonScorerIntegrationTest {

    private static final int SEASON = 9600;
    /** Gameweeks 1, 2 and 3 are in range; gameweek 4 must never appear in a result. */
    private static final int HORIZON = 3;

    private static final int SINGLE = 9601;
    private static final int DOUBLE = 9602;
    private static final int BLANK = 9603;
    private static final int ALREADY_PLAYED = 9604;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    HorizonScorer scorer;

    @BeforeEach
    void seed() {
        TapasSeed.ensure(jdbc);
        deleteTestRows();

        // is_current=false: the table enforces at most one current season across the whole
        // suite (idx_season_current), and TapasSeed's own current season already holds that
        // slot. This class never resolves a season through SeasonResolver anyway — the season
        // id is passed to fixtureDifficulties directly.
        jdbc.execute("""
                INSERT INTO season (id, code, name, start_date, end_date, is_current)
                VALUES (9600, 'T-HORIZON', 'Horizon scorer test season', DATE '2026-08-01',
                        NULL, false)
                ON CONFLICT (id) DO NOTHING
                """);

        jdbc.execute("""
                INSERT INTO team (id, season_id, code, name, short_name, strength) VALUES
                    (9601, 9600, 9601, 'Single Town',  'SGT', 3),
                    (9602, 9600, 9602, 'Double City',  'DBC', 3),
                    (9603, 9600, 9603, 'Blank Rovers', 'BLK', 3),
                    (9604, 9600, 9604, 'Played FC',    'PLD', 3),
                    (9605, 9600, 9605, 'Rival United', 'RIV', 3)
                ON CONFLICT (id, season_id) DO NOTHING
                """);

        jdbc.execute("""
                INSERT INTO gameweek (id, season_id, name, deadline_time, finished, data_checked,
                                      is_current, is_next)
                VALUES (1, 9600, 'Gameweek 1', TIMESTAMPTZ '2026-08-15 17:30:00+00', false, false,
                        true, false)
                ON CONFLICT (id, season_id) DO NOTHING
                """);

        jdbc.execute("""
                INSERT INTO fixture (id, season_id, gameweek, code, team_h, team_a,
                                     team_h_difficulty, team_a_difficulty, kickoff_time, finished) VALUES
                    -- Single Town: one fixture per gameweek, gameweeks 1-4. Gameweek 4 sits
                    -- outside horizon=3 and must never surface in the result.
                    (1, 9600, 1, 1, 9601, 9605, 3, 3, TIMESTAMPTZ '2026-08-15 14:00:00+00', false),
                    (2, 9600, 2, 2, 9605, 9601, 3, 2, TIMESTAMPTZ '2026-08-22 14:00:00+00', false),
                    (3, 9600, 3, 3, 9601, 9605, 4, 3, TIMESTAMPTZ '2026-08-29 14:00:00+00', false),
                    (4, 9600, 4, 4, 9601, 9605, 1, 3, TIMESTAMPTZ '2026-09-05 14:00:00+00', false),

                    -- Double City: doubles in gameweek 2. Bounding by fixture count (the
                    -- earlier, wrong version of this query) capped this club at 3 entries and
                    -- dropped gameweek 3's fixture (fdr 1) entirely; bounding by gameweek range
                    -- keeps all four.
                    (5, 9600, 1, 5, 9602, 9605, 3, 3, TIMESTAMPTZ '2026-08-15 16:00:00+00', false),
                    (6, 9600, 2, 6, 9605, 9602, 3, 2, TIMESTAMPTZ '2026-08-22 12:00:00+00', false),
                    (7, 9600, 2, 7, 9602, 9605, 5, 3, TIMESTAMPTZ '2026-08-24 18:00:00+00', false),
                    (8, 9600, 3, 8, 9605, 9602, 3, 1, TIMESTAMPTZ '2026-08-29 16:00:00+00', false),

                    -- Blank Rovers: no fixture at all in gameweek 2.
                    (9,  9600, 1, 9,  9603, 9605, 3, 3, TIMESTAMPTZ '2026-08-15 18:00:00+00', false),
                    (10, 9600, 3, 10, 9605, 9603, 3, 4, TIMESTAMPTZ '2026-08-29 18:00:00+00', false),

                    -- Played FC: its gameweek-1 fixture already kicked off and finished, even
                    -- though gameweek 1 is still flagged is_current above — exactly the
                    -- multi-day-gameweek situation the finished=false filter exists for.
                    (11, 9600, 1, 11, 9604, 9605, 3, 3, TIMESTAMPTZ '2026-08-15 12:00:00+00', true),
                    (12, 9600, 2, 12, 9605, 9604, 3, 2, TIMESTAMPTZ '2026-08-22 12:00:00+00', false),
                    (13, 9600, 3, 13, 9604, 9605, 4, 3, TIMESTAMPTZ '2026-08-29 12:00:00+00', false)
                ON CONFLICT (id, season_id) DO NOTHING
                """);
    }

    @AfterEach
    void cleanUp() {
        deleteTestRows();
    }

    /**
     * Deleted in foreign-key order. Season id 9600 is used nowhere else in the suite, so this
     * can safely tear down the whole thing rather than scoping by row id.
     */
    private void deleteTestRows() {
        jdbc.update("DELETE FROM fixture WHERE season_id = ?", SEASON);
        jdbc.update("DELETE FROM gameweek WHERE season_id = ?", SEASON);
        jdbc.update("DELETE FROM team WHERE season_id = ?", SEASON);
        jdbc.update("DELETE FROM season WHERE id = ?", SEASON);
    }

    @Test
    void anOrdinaryClubGetsOneEntryPerGameweekAndNothingBeyondTheHorizon() {
        Map<Integer, List<Integer>> byTeam = scorer.fixtureDifficulties(SEASON, HORIZON);

        assertThat(byTeam.get(SINGLE)).containsExactly(3, 2, 4);
    }

    @Test
    void aDoubleGameweekAddsAFixtureRatherThanDisplacingALaterOne() {
        Map<Integer, List<Integer>> byTeam = scorer.fixtureDifficulties(SEASON, HORIZON);

        assertThat(byTeam.get(DOUBLE)).containsExactly(3, 2, 5, 1);
    }

    @Test
    void aBlankGameweekContributesNothingRatherThanAPhantomEntry() {
        Map<Integer, List<Integer>> byTeam = scorer.fixtureDifficulties(SEASON, HORIZON);

        assertThat(byTeam.get(BLANK)).containsExactly(3, 4);
    }

    @Test
    void aFixtureAlreadyPlayedInTheCurrentGameweekIsExcluded() {
        Map<Integer, List<Integer>> byTeam = scorer.fixtureDifficulties(SEASON, HORIZON);

        assertThat(byTeam.get(ALREADY_PLAYED)).containsExactly(2, 4);
    }
}
