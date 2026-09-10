package dev.quantti.fplassistant.tools;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import dev.quantti.fplassistant.testsupport.DataAgeClock;
import dev.quantti.fplassistant.testsupport.TapasSeed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FixtureDifficultyTools} against the seeded fixture league: team resolution, the ratings
 * branch, the empty branch and the ambiguity note. Before the move to a seeded database this
 * class could only reach the empty branch — no season in the shared database had a
 * {@code finished = false} fixture — so the Average FDR line went untested here and was pinned
 * only by {@link FixtureDifficultyToolsFormatTest}.
 */
@SpringBootTest
@ActiveProfiles("test")
class FixtureDifficultyToolsIntegrationTest {

    static final Instant AS_OF = DataAgeClock.AS_OF;
    /** An hour before {@link #AS_OF}: every row nobody should be reading is written this recently. */
    static final Instant FRESH = Instant.parse("2026-09-10T11:00:00Z");

    private static final int SEASON = TapasSeed.POPULATED_SEASON_ID;
    private static final String GAMEWEEK_2_AGE = "Stored-data age: starting-gameweek selection (gameweek row id=2),"
            + " season " + TapasSeed.POPULATED_SEASON_CODE
            + ", last recorded write 2026-07-12 13:00 UK (60 days ago)."
            + " This is a database record time, not a live FPL check.";

    /** Built with the fixed clock rather than autowired; see {@link DataAgeClock}. */
    FixtureDifficultyTools tools;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SeasonResolver seasonResolver;

    @BeforeEach
    void buildTools() {
        tools = new FixtureDifficultyTools(jdbc, seasonResolver, DataAgeClock.provider());
    }

    @BeforeEach
    void seed() {
        TapasSeed.ensure(jdbc);
    }

    @Test
    void unknownTeamReturnsNotFound() {
        assertThat(tools.getFixtureDifficulty("Definitely Not A Real Club", 5))
                .contains("Team not found");
    }

    @Test
    void upcomingFixturesAreRatedAndAveraged() {
        int seasonId = seasonResolver.resolve().seasonId();
        Integer unfinished = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fixture WHERE season_id = ? AND finished = false",
                Integer.class, seasonId);
        assertThat(unfinished)
                .as("the ratings branch needs fixtures still to play")
                .isPositive();

        String result = tools.getFixtureDifficulty(TapasSeed.ASHFIELD, 5);

        assertThat(result).contains("Data source: season");
        assertThat(result).contains("Average FDR over the next");
        assertThat(result).doesNotContain("No upcoming fixtures found");
    }

    @Test
    void aTeamWithNothingLeftToPlaySaysSoExplicitly() {
        String result = tools.getFixtureDifficulty(TapasSeed.TEAM_WITHOUT_FIXTURES, 5);

        assertThat(result).contains("Data source: season");
        assertThat(result).contains("No upcoming fixtures found for");
        assertThat(result).doesNotContain("Average FDR");
    }

    @Test
    void anAmbiguousTeamNameSaysWhichOtherClubsItMatched() {
        String result = tools.getFixtureDifficulty(TapasSeed.AMBIGUOUS_TEAM_PREFIX, 5);

        assertThat(result).contains("The name also matched:");
    }

    /**
     * KAR-9: the age covers exactly the rows the limit returned plus the postponed ones — a
     * fresher fixture beyond the limit cannot become the newest write — and the selecting
     * gameweek row is dated on its own line.
     */
    @Test
    @Transactional
    void theOutlookIsDatedByTheRowsItShowsAndItsSelectingGameweek() {
        everyRowWrittenFresh();
        gameweekWrittenAt(2, Instant.parse("2026-07-12T12:00:00Z"));
        fixtureWrittenAt(9105, Instant.parse("2026-08-21T12:00:00Z"));
        // A kickoff a year ahead is a match date, not a write: it is no future-dated record.
        jdbc.update("UPDATE fixture SET kickoff_time = ? WHERE id = 9105 AND season_id = ?",
                utc(Instant.parse("2027-08-21T14:00:00Z")), SEASON);
        postponed(9190, Instant.parse("2026-06-02T12:00:00Z"));

        String result = tools.getFixtureDifficulty(TapasSeed.ASHFIELD, 1);

        assertThat(result).contains("  GW3: vs NMU (H) | FDR 2 | 2027-08-21 15:00 UK\n").doesNotContain("GW4:");
        assertThat(result).contains("Average FDR over the next 1 rated fixture(s): 2.0");
        assertThat(result).contains("  vs SEL (H) | FDR 2 | date to be confirmed");
        assertThat(result).containsOnlyOnce(GAMEWEEK_2_AGE);
        assertThat(result).containsOnlyOnce("Stored-data age: fixture records listed for Ashfield Rovers,"
                + " season " + TapasSeed.POPULATED_SEASON_CODE
                + ", record writes from 2026-06-02 13:00 UK (100 days ago) to 2026-08-21 13:00 UK (20 days ago)."
                + " This is a database record time, not a live FPL check.");
        assertThat(result).doesNotContain("future-dated");
        assertThat(result.indexOf("Stored-data age")).isGreaterThan(result.indexOf("date to be confirmed"));
        assertThat(result).containsOnlyOnce(DataAge.GAMEWEEK_CAVEAT);
    }

    /** An unrated fixture is still shown, so it still counts towards the age; its null write is disclosed. */
    @Test
    @Transactional
    void unknownRatingsAndUndatedRowsKeepTheirMeaning() {
        everyRowWrittenFresh();
        gameweekWrittenAt(2, Instant.parse("2026-07-12T12:00:00Z"));
        fixtureWrittenAt(9105, Instant.parse("2026-08-21T12:00:00Z"));
        fixtureWrittenAt(9107, null);
        jdbc.update("UPDATE fixture SET team_a_difficulty = NULL WHERE id = 9107 AND season_id = ?", SEASON);

        String result = tools.getFixtureDifficulty(TapasSeed.ASHFIELD, 2);

        assertThat(result).contains("  GW4: vs NMC (A) | FDR unknown |");
        assertThat(result).contains("Average FDR over the next 1 rated fixture(s): 2.0");
        assertThat(result).contains("(1 fixture(s) carry no FDR rating yet and are excluded from the average.)");
        assertThat(result).containsOnlyOnce("Stored-data age: fixture records listed for Ashfield Rovers,"
                + " season " + TapasSeed.POPULATED_SEASON_CODE
                + ", last recorded write 2026-08-21 13:00 UK (20 days ago);"
                + " 1 of 2 fixture records has no write timestamp."
                + " This is a database record time, not a live FPL check.");
    }

    /** The same precedence as before — current, then next, then the lowest id — dated by the row chosen. */
    @Test
    @Transactional
    void theSelectingRowFollowsTheNextThenMinimumFallback() {
        everyRowWrittenFresh();
        gameweekWrittenAt(3, Instant.parse("2026-08-31T12:00:00Z"));
        gameweekWrittenAt(1, Instant.parse("2026-05-03T12:00:00Z"));
        jdbc.update("UPDATE gameweek SET is_current = false WHERE season_id = ? AND id = 2", SEASON);

        assertThat(tools.getFixtureDifficulty(TapasSeed.ASHFIELD, 1))
                .contains("Stored-data age: starting-gameweek selection (gameweek row id=3), season "
                        + TapasSeed.POPULATED_SEASON_CODE + ", last recorded write 2026-08-31 13:00 UK (10 days ago).");

        jdbc.update("UPDATE gameweek SET is_next = false WHERE season_id = ? AND id = 3", SEASON);

        assertThat(tools.getFixtureDifficulty(TapasSeed.ASHFIELD, 1))
                .contains("Stored-data age: starting-gameweek selection (gameweek row id=1), season "
                        + TapasSeed.POPULATED_SEASON_CODE + ", last recorded write 2026-05-03 13:00 UK (130 days ago).");
    }

    /** No gameweek: the existing refusal stands, with no timestamp taken from the season. */
    @Test
    @Transactional
    void withoutAGameweekNoAgeIsInvented() {
        jdbc.update("DELETE FROM gameweek WHERE season_id = ?", SEASON);
        jdbc.update("""
                INSERT INTO team (id, season_id, code, name, short_name)
                VALUES (9001, ?, 9001, 'Ashfield Rovers', 'ASH')
                ON CONFLICT (id, season_id) DO NOTHING
                """, TapasSeed.CURRENT_SEASON_ID);

        String result = tools.getFixtureDifficulty(TapasSeed.ASHFIELD, 5);

        assertThat(result).contains("No gameweeks are scheduled yet for " + TapasSeed.CURRENT_SEASON_CODE);
        assertThat(result).doesNotContain("Stored-data age").doesNotContain("recorded write");
    }

    /** No fixture rows: the empty outlook does not inherit the gameweek's age. */
    @Test
    @Transactional
    void anEmptyOutlookHasNoFixtureAge() {
        everyRowWrittenFresh();
        gameweekWrittenAt(2, Instant.parse("2026-07-12T12:00:00Z"));

        String result = tools.getFixtureDifficulty(TapasSeed.TEAM_WITHOUT_FIXTURES, 5);

        assertThat(result).contains("No upcoming fixtures found for Woodbridge Athletic (WBA)");
        assertThat(result).containsOnlyOnce(GAMEWEEK_2_AGE);
        assertThat(result).containsOnlyOnce("Stored-data age: fixture records listed for Woodbridge Athletic, season "
                + TapasSeed.POPULATED_SEASON_CODE + ", no stored rows used; age unavailable.");
    }

    /** Only a row the reply really used can then carry an older write than this. */
    private void everyRowWrittenFresh() {
        jdbc.update("UPDATE fixture SET updated_at = ? WHERE season_id = ?", utc(FRESH), SEASON);
        jdbc.update("UPDATE gameweek SET updated_at = ? WHERE season_id = ?", utc(FRESH), SEASON);
    }

    private void fixtureWrittenAt(int fixtureId, Instant at) {
        writtenAt("fixture", fixtureId, at);
    }

    private void gameweekWrittenAt(int gameweekId, Instant at) {
        writtenAt("gameweek", gameweekId, at);
    }

    /** Neither table has an update trigger, so the seeded time must stick; checked, not assumed. */
    private void writtenAt(String table, int id, Instant at) {
        OffsetDateTime value = utc(at);
        assertThat(jdbc.update("UPDATE " + table + " SET updated_at = ? WHERE id = ? AND season_id = ?",
                value, id, SEASON)).isOne();
        assertThat(jdbc.queryForObject("SELECT updated_at FROM " + table + " WHERE id = ? AND season_id = ?",
                OffsetDateTime.class, id, SEASON))
                .as("the seeded write time must stick").isEqualTo(value);
    }

    /** Ashfield at home to Selby with no gameweek assigned. */
    private void postponed(int fixtureId, Instant writtenAt) {
        jdbc.update("""
                INSERT INTO fixture (id, season_id, gameweek, code, team_h, team_a, team_h_difficulty,
                                     team_a_difficulty, kickoff_time, started, finished, updated_at)
                VALUES (?, ?, NULL, ?, 9001, 9004, 2, 4, NULL, false, false, ?)
                """, fixtureId, SEASON, fixtureId, utc(writtenAt));
    }

    private static OffsetDateTime utc(Instant at) {
        return at == null ? null : OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
    }
}
