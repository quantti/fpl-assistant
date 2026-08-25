package dev.quantti.fplassistant.myteam;

import java.util.Set;

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
 * The depth Supabase adds to a squad card, and the gate that decides whether it may be added at
 * all.
 *
 * <p>The gate is the point. An {@code element} id is season-scoped and FPL renumbers every
 * player every season, so joining this season's pick ids against a season the database has not
 * replaced yet returns other players' numbers with nothing to show for it. The shared fixture
 * league reproduces exactly that state — a populated completed season beside an empty current
 * one — and this test fills the current season in for its own duration to see the gate open.
 */
@SpringBootTest
@ActiveProfiles("test")
class SquadEnrichmentTest {

    private static final int STRIKER = 9301;
    private static final int KEEPER = 9302;
    private static final int HOME_TEAM = 9101;
    private static final int AWAY_TEAM = 9102;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SquadEnrichment enrichment;

    @BeforeEach
    void seed() {
        TapasSeed.ensure(jdbc);
    }

    @AfterEach
    void removeTheCurrentSeasonsData() {
        int season = TapasSeed.CURRENT_SEASON_ID;
        jdbc.update("DELETE FROM fixture WHERE season_id = ?", season);
        jdbc.update("DELETE FROM player WHERE season_id = ?", season);
        jdbc.update("DELETE FROM gameweek WHERE season_id = ?", season);
        jdbc.update("DELETE FROM team WHERE season_id = ?", season);
    }

    /** The changeover, which is the state the shared fixture league is left in. */
    @Test
    void aSeasonTheDatabaseHasNotLoadedIsRefusedRatherThanJoinedAnyway() {
        SquadEnrichment.Enrichment result = enrichment.forElements(Set.of(STRIKER, KEEPER));

        assertThat(result.available())
                .as("the resolver has fallen back to a completed season, whose ids mean something"
                        + " else entirely")
                .isFalse();
        assertThat(result.byElement()).isEmpty();
    }

    @Test
    void aLoadedCurrentSeasonBringsBackFixtureDifficultyAndExpectedGoals() {
        fillCurrentSeason();

        SquadEnrichment.Enrichment result = enrichment.forElements(Set.of(STRIKER, KEEPER));

        assertThat(result.available()).isTrue();
        assertThat(result.season().currentSeason()).isTrue();
        // Four scheduled fixtures at difficulty 2, 4, 3 and 5: the run averages 3.5.
        assertThat(result.of(STRIKER).nextFiveFdr()).isEqualTo(3.5);
        assertThat(result.of(STRIKER).expectedGoals()).isNotNull();
        assertThat(result.of(STRIKER).expectedGoals().doubleValue()).isEqualTo(3.40);
        assertThat(result.of(KEEPER).nextFiveFdr())
                .as("the other club's own run, not the striker's")
                .isEqualTo(2.5);
    }

    @Test
    void theRunStopsAfterFiveFixtures() {
        fillCurrentSeason();
        // A fifth fixture belongs in the run: 2, 4, 3, 5, 1 averages 3.0.
        addFixture(9905, 5, 1);
        assertThat(enrichment.forElements(Set.of(STRIKER)).of(STRIKER).nextFiveFdr())
                .isEqualTo(3.0);

        addFixture(9906, 6, 5);

        assertThat(enrichment.forElements(Set.of(STRIKER)).of(STRIKER).nextFiveFdr())
                .as("the sixth fixture is outside the window and must not move the average")
                .isEqualTo(3.0);
    }

    private void addFixture(int id, int gameweek, int difficulty) {
        jdbc.update("""
                INSERT INTO fixture (id, season_id, code, gameweek, team_h, team_a,
                                     team_h_difficulty, team_a_difficulty)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1)
                """, id, TapasSeed.CURRENT_SEASON_ID, id, gameweek, HOME_TEAM, AWAY_TEAM,
                difficulty);
    }

    private void fillCurrentSeason() {
        int season = TapasSeed.CURRENT_SEASON_ID;
        jdbc.update("""
                INSERT INTO team (id, season_id, code, name, short_name, strength) VALUES
                    (?, ?, 9101, 'Current Rovers', 'CUR', 4),
                    (?, ?, 9102, 'Present United', 'PRE', 3)
                """, HOME_TEAM, season, AWAY_TEAM, season);
        jdbc.update("""
                INSERT INTO gameweek (id, season_id, name, deadline_time, finished, data_checked,
                                      is_current, is_next)
                VALUES (1, ?, 'Gameweek 1', TIMESTAMPTZ '2026-08-21 17:30:00+00', true, true,
                        true, false)
                """, season);
        jdbc.update("""
                INSERT INTO player (id, season_id, team_id, first_name, second_name, web_name,
                                    element_type, now_cost, status, total_points,
                                    expected_goals, expected_assists) VALUES
                    (?, ?, ?, 'Test', 'Striker', 'Striker', 4, 100, 'a', 12, 3.40, 1.20),
                    (?, ?, ?, 'Test', 'Keeper',  'Keeper',  1, 50,  'a', 4,  0.00, 0.10)
                """, STRIKER, season, HOME_TEAM, KEEPER, season, AWAY_TEAM);
        // Home team: difficulty 2, 4, 3, 5. Away team: 3, 2, 2, 3 -> different runs on purpose.
        jdbc.update("""
                INSERT INTO fixture (id, season_id, code, gameweek, team_h, team_a,
                                     team_h_difficulty, team_a_difficulty) VALUES
                    (9901, ?, 9901, 1, ?, ?, 2, 3),
                    (9902, ?, 9902, 2, ?, ?, 4, 2),
                    (9903, ?, 9903, 3, ?, ?, 3, 2),
                    (9904, ?, 9904, 4, ?, ?, 5, 3)
                """, season, HOME_TEAM, AWAY_TEAM, season, HOME_TEAM, AWAY_TEAM,
                season, HOME_TEAM, AWAY_TEAM, season, HOME_TEAM, AWAY_TEAM);
    }
}
