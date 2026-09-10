package fi.karivanttinen.fplassistant.myteam;

import java.time.Instant;
import java.util.Set;

import fi.karivanttinen.fplassistant.testsupport.TapasSeed;
import fi.karivanttinen.fplassistant.tools.DataAge;

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
    private static final Instant ANCIENT = Instant.parse("2020-01-01T00:00:00Z");
    private static final Instant SEP_01 = Instant.parse("2026-09-01T07:00:00Z");
    private static final Instant SEP_02 = Instant.parse("2026-09-02T07:00:00Z");
    private static final Instant SEP_03 = Instant.parse("2026-09-03T07:00:00Z");
    private static final Instant SEP_05 = Instant.parse("2026-09-05T07:00:00Z");

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
        assertThat(result.startingGameweek())
                .as("no starting gameweek is looked up for a season the gate refused")
                .isNull();
    }

    /** KAR-9: each player's xG/xA carries its own row's write time, and a null one keeps its depth. */
    @Test
    void eachPlayerCarriesItsOwnRecordWriteAndANullOneKeepsItsDepth() {
        fillCurrentSeason();
        jdbc.update("UPDATE player SET updated_at = ? WHERE season_id = ? AND id = ?",
                java.sql.Timestamp.from(SEP_01), TapasSeed.CURRENT_SEASON_ID, STRIKER);
        jdbc.update("UPDATE player SET updated_at = NULL WHERE season_id = ? AND id = ?",
                TapasSeed.CURRENT_SEASON_ID, KEEPER);

        SquadEnrichment.Enrichment result = enrichment.forElements(Set.of(STRIKER, KEEPER));

        assertThat(result.of(STRIKER).playerWrite()).isEqualTo(new DataAge.Span(SEP_01, SEP_01, 1, 0));
        assertThat(result.of(KEEPER).playerWrite())
                .as("an undated row is disclosed as undated, not borrowed from the striker")
                .isEqualTo(new DataAge.Span(null, null, 1, 1));
        assertThat(result.of(KEEPER).expectedAssists().doubleValue()).isEqualTo(0.10);
        assertThat(result.of(KEEPER).nextFiveFdr()).isEqualTo(2.5);
    }

    /**
     * KAR-9: a club's FDR age covers exactly the fixtures its average admitted. A fixture unrated
     * for the home side feeds the away club's run and age but neither of the home club's, and a
     * null write is counted as undated without dropping its rating.
     */
    @Test
    void eachClubsFdrAgeCoversOnlyTheFixturesAdmittedToItsAverage() {
        fillCurrentSeason();
        setFixtureWrite(9901, SEP_02);
        setFixtureWrite(9902, SEP_03);
        setFixtureWrite(9903, null);
        setFixtureWrite(9904, SEP_05);
        jdbc.update("""
                INSERT INTO fixture (id, season_id, code, gameweek, team_h, team_a,
                                     team_h_difficulty, team_a_difficulty, updated_at)
                VALUES (9905, ?, 9905, 5, ?, ?, NULL, 1, ?)
                """, TapasSeed.CURRENT_SEASON_ID, HOME_TEAM, AWAY_TEAM, java.sql.Timestamp.from(ANCIENT));

        SquadEnrichment.Enrichment result = enrichment.forElements(Set.of(STRIKER, KEEPER));

        assertThat(result.of(STRIKER).nextFiveFdr()).isEqualTo(3.5);
        assertThat(result.of(STRIKER).fdrWrites())
                .as("the home club's unrated fixture moves neither its average nor its age")
                .isEqualTo(new DataAge.Span(SEP_02, SEP_05, 4, 1));
        assertThat(result.of(KEEPER).nextFiveFdr()).isEqualTo(2.2);
        assertThat(result.of(KEEPER).fdrWrites())
                .as("the away club's own run, which did admit the fifth fixture")
                .isEqualTo(new DataAge.Span(ANCIENT, SEP_05, 5, 1));
    }

    @Test
    void aSixthFixtureMovesNeitherTheAverageNorItsAge() {
        fillCurrentSeason();
        addFixture(9905, 5, 1);
        for (int id = 9901; id <= 9905; id++) {
            setFixtureWrite(id, SEP_02);
        }
        addFixture(9906, 6, 5);
        setFixtureWrite(9906, SEP_05);

        SquadEnrichment.Depth striker = enrichment.forElements(Set.of(STRIKER)).of(STRIKER);

        assertThat(striker.nextFiveFdr()).isEqualTo(3.0);
        assertThat(striker.fdrWrites()).isEqualTo(new DataAge.Span(SEP_02, SEP_02, 5, 0));
    }

    /** The row that chose the run's start, with its own write time and the existing precedence. */
    @Test
    void theStartingGameweekRowComesBackWithItsOwnWriteTime() {
        fillCurrentSeason();
        jdbc.update("UPDATE gameweek SET updated_at = ? WHERE season_id = ? AND id = 1",
                java.sql.Timestamp.from(SEP_01), TapasSeed.CURRENT_SEASON_ID);
        jdbc.update("""
                INSERT INTO gameweek (id, season_id, name, deadline_time, finished, data_checked,
                                      is_current, is_next, updated_at)
                VALUES (2, ?, 'Gameweek 2', TIMESTAMPTZ '2026-08-28 17:30:00+00', false, false,
                        false, true, ?)
                """, TapasSeed.CURRENT_SEASON_ID, java.sql.Timestamp.from(SEP_05));

        assertThat(enrichment.forElements(Set.of(STRIKER)).startingGameweek())
                .as("the current row wins over a fresher next row")
                .isEqualTo(new SquadEnrichment.StartingGameweek(1, new DataAge.Span(SEP_01, SEP_01, 1, 0)));

        jdbc.update("UPDATE gameweek SET is_current = false WHERE season_id = ? AND id = 1",
                TapasSeed.CURRENT_SEASON_ID);

        assertThat(enrichment.forElements(Set.of(STRIKER)).startingGameweek())
                .isEqualTo(new SquadEnrichment.StartingGameweek(2, new DataAge.Span(SEP_05, SEP_05, 1, 0)));
    }

    /** KAR-9: with no FDR on the card, the row that would have started a run is not dated. */
    @Test
    void noFdrRunMeansNoStartingGameweekAge() {
        fillCurrentSeason();
        jdbc.update("DELETE FROM fixture WHERE season_id = ?", TapasSeed.CURRENT_SEASON_ID);

        SquadEnrichment.Enrichment result = enrichment.forElements(Set.of(STRIKER));

        assertThat(result.available()).isTrue();
        assertThat(result.of(STRIKER).nextFiveFdr()).isNull();
        assertThat(result.startingGameweek()).isNull();
    }

    private void setFixtureWrite(int id, Instant writtenAt) {
        jdbc.update("UPDATE fixture SET updated_at = ? WHERE season_id = ? AND id = ?",
                writtenAt == null ? null : java.sql.Timestamp.from(writtenAt),
                TapasSeed.CURRENT_SEASON_ID, id);
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
