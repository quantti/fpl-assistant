package dev.quantti.fplassistant.tools;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.quantti.fplassistant.schedule.ScheduleSource;
import dev.quantti.fplassistant.testsupport.DataAgeClock;
import dev.quantti.fplassistant.testsupport.SharedStatsSeed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class FixtureToolsTest {

    static final Instant AS_OF = DataAgeClock.AS_OF;
    /** An hour before {@link #AS_OF}: every row nobody should be reading is written this recently. */
    static final Instant FRESH = Instant.parse("2026-09-10T11:00:00Z");

    private static final int SEASON = SharedStatsSeed.POPULATED_SEASON_ID;

    /** Built with the fixed clock rather than autowired; see {@link DataAgeClock}. */
    FixtureTools fixtureTools;

    @Autowired
    SeasonResolver seasonResolver;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ScheduleSource schedule;

    @BeforeEach
    void buildTools() {
        fixtureTools = new FixtureTools(jdbc, seasonResolver, schedule, DataAgeClock.provider());
    }

    @Value("${fpl.schedule.gameweek-table-name}")
    String scheduleGameweekTable;

    @Value("${fpl.schedule.fixture-table-name}")
    String scheduleFixtureTable;

    @BeforeEach
    void seed() {
        SharedStatsSeed.ensure(jdbc);
        // The shared path is what this class tests; it is taken only while no schedule capture
        // exists. This project's own _test tables, so clearing them is allowed.
        jdbc.update("DELETE FROM " + scheduleFixtureTable);
        jdbc.update("DELETE FROM " + scheduleGameweekTable);
    }

    @Test
    void getUpcomingFixturesForKnownTeam() {
        String result = fixtureTools.getUpcomingFixtures(SharedStatsSeed.ASHFIELD, 5);

        assertThat(result).contains(SharedStatsSeed.ASHFIELD);
        assertThat(result).doesNotContain("not found");
        assertThat(result).containsPattern("GW\\d+:");
    }

    @Test
    void everyAnswerNamesTheSeasonItCameFrom() {
        String result = fixtureTools.getUpcomingFixtures(SharedStatsSeed.ASHFIELD, 5);
        SeasonContext season = seasonResolver.resolve();

        assertThat(result).startsWith("Data source: season " + season.code());
    }

    @Test
    void unknownTeamReturnsNotFound() {
        String result = fixtureTools.getUpcomingFixtures("ZZZUnknownFC", 5);

        assertThat(result).containsIgnoringCase("not found");
    }

    @Test
    void likeWildcardsAreEscapedNotInterpreted() {
        // Unescaped, "%" would match the first team in the table instead of failing.
        String result = fixtureTools.getUpcomingFixtures("%", 5);

        assertThat(result).containsIgnoringCase("not found");
    }

    /**
     * The seeded club plays twice in the first gameweek of the window, so asking for one gameweek
     * must return both of those fixtures and nothing from the gameweek after. Against production
     * data this test could only ever see single gameweeks and passed without exercising the rule.
     */
    @Test
    void nextNBoundsDistinctGameweeksNotFixtureRows() {
        String result = fixtureTools.getUpcomingFixtures(SharedStatsSeed.ASHFIELD, 1);

        // Guard against a vacuous pass: an empty result would also satisfy the size bound.
        assertThat(result).doesNotContain("not found");

        assertThat(gameweeksListed(result))
                .as("a double gameweek is two fixture lines, but still one gameweek")
                .hasSize(2)
                .containsOnly(String.valueOf(SharedStatsSeed.DOUBLE_GAMEWEEK));
    }

    /**
     * KAR-9: the selecting gameweek row and the listed fixture rows are dated separately, and
     * only by rows the reply used — never by a fresher row of another club, another season or a
     * gameweek past the window, and never by a fresh fixture hiding an old selection flag.
     */
    @Test
    @Transactional
    void theListingIsDatedByItsOwnFixtureRowsAndItsSelectingGameweek() {
        everyRowWrittenFresh();
        gameweekWrittenAt(2, Instant.parse("2026-07-12T12:00:00Z"));
        fixtureWrittenAt(9103, Instant.parse("2026-08-01T12:00:00Z"));
        fixtureWrittenAt(9110, Instant.parse("2026-08-11T12:00:00Z"));
        fixtureWrittenAt(9105, Instant.parse("2026-08-21T12:00:00Z"));
        // A kickoff a year ahead is a match date, not a write: it is no future-dated record.
        jdbc.update("UPDATE fixture SET kickoff_time = ? WHERE id = 9105 AND season_id = ?",
                utc(Instant.parse("2027-08-21T14:00:00Z")), SEASON);
        // The same club, freshly written, in another season.
        otherSeasonClubs();
        jdbc.update("""
                INSERT INTO fixture (id, season_id, gameweek, code, team_h, team_a, team_h_difficulty,
                                     team_a_difficulty, kickoff_time, started, finished, updated_at)
                VALUES (9195, ?, 3, 9195, 9001, 9004, 2, 4, NULL, false, false, ?)
                """, SharedStatsSeed.CURRENT_SEASON_ID, utc(FRESH));

        String result = fixtureTools.getUpcomingFixtures(SharedStatsSeed.ASHFIELD, 2);

        assertThat(gameweeksListed(result)).containsExactly("2", "2", "3");
        assertThat(result).contains("GW3: vs NMU (H) | FDR: 2 | 2027-08-21 15:00 UK");
        assertThat(result).containsOnlyOnce("Stored-data age: starting-gameweek selection (gameweek row id=2),"
                + " season " + SharedStatsSeed.POPULATED_SEASON_CODE
                + ", last recorded write 2026-07-12 13:00 UK (60 days ago)."
                + " This is a database record time, not a live FPL check.");
        assertThat(result).containsOnlyOnce("Stored-data age: fixture records listed for Ashfield Rovers,"
                + " season " + SharedStatsSeed.POPULATED_SEASON_CODE
                + ", record writes from 2026-08-01 13:00 UK (40 days ago) to 2026-08-21 13:00 UK (20 days ago)."
                + " This is a database record time, not a live FPL check.");
        assertThat(result).doesNotContain("future-dated");
        assertThat(result.indexOf("Stored-data age")).isGreaterThan(result.lastIndexOf("GW3:"));
        assertThat(result).containsOnlyOnce(DataAge.GAMEWEEK_CAVEAT).endsWith(DataAge.GAMEWEEK_CAVEAT);
    }

    /** A postponed row is listed, so its older write widens the span; an undated row is counted. */
    @Test
    @Transactional
    void anOlderPostponedRowAndAnUndatedRowAreBothDisclosed() {
        everyRowWrittenFresh();
        gameweekWrittenAt(2, Instant.parse("2026-07-12T12:00:00Z"));
        fixtureWrittenAt(9103, Instant.parse("2026-08-01T12:00:00Z"));
        fixtureWrittenAt(9110, Instant.parse("2026-08-11T12:00:00Z"));
        fixtureWrittenAt(9105, null);
        postponed(SEASON, 9190, Instant.parse("2026-06-02T12:00:00Z"));

        String result = fixtureTools.getUpcomingFixtures(SharedStatsSeed.ASHFIELD, 2);

        assertThat(gameweeksListed(result)).containsExactly("2", "2", "3");
        assertThat(result).contains("Postponed, no rescheduled gameweek yet:\nPostponed: vs SEL (H) | FDR: 2 | kickoff TBD");
        assertThat(result).containsOnlyOnce("Stored-data age: fixture records listed for Ashfield Rovers,"
                + " season " + SharedStatsSeed.POPULATED_SEASON_CODE
                + ", record writes from 2026-06-02 13:00 UK (100 days ago) to 2026-08-11 13:00 UK (30 days ago);"
                + " 1 of 4 fixture records has no write timestamp."
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

        String next = fixtureTools.getUpcomingFixtures(SharedStatsSeed.ASHFIELD, 1);

        assertThat(gameweeksListed(next)).containsExactly("3");
        assertThat(next).contains("Stored-data age: starting-gameweek selection (gameweek row id=3), season "
                + SharedStatsSeed.POPULATED_SEASON_CODE + ", last recorded write 2026-08-31 13:00 UK (10 days ago).");

        jdbc.update("UPDATE gameweek SET is_next = false WHERE season_id = ? AND id = 3", SEASON);

        String minimum = fixtureTools.getUpcomingFixtures(SharedStatsSeed.ASHFIELD, 1);

        assertThat(gameweeksListed(minimum)).containsExactly("1");
        assertThat(minimum).contains("Stored-data age: starting-gameweek selection (gameweek row id=1), season "
                + SharedStatsSeed.POPULATED_SEASON_CODE + ", last recorded write 2026-05-03 13:00 UK (130 days ago).");
    }

    /** No gameweek row at all: the selection has no age, and none is borrowed from the season or a fixture. */
    @Test
    @Transactional
    void withoutAGameweekTheSelectionHasNoAge() {
        jdbc.update("DELETE FROM gameweek WHERE season_id = ?", SEASON);
        otherSeasonClubs();
        postponed(SharedStatsSeed.CURRENT_SEASON_ID, 9191, Instant.parse("2026-08-01T12:00:00Z"));

        String result = fixtureTools.getUpcomingFixtures(SharedStatsSeed.ASHFIELD, 5);

        assertThat(result).startsWith("Data source: season " + SharedStatsSeed.CURRENT_SEASON_CODE);
        assertThat(result).contains("Postponed: vs SEL (H) | FDR: 2 | kickoff TBD");
        assertThat(result).containsOnlyOnce("Stored-data age: starting-gameweek selection, season "
                + SharedStatsSeed.CURRENT_SEASON_CODE + ", no stored rows used; age unavailable.");
        assertThat(result).containsOnlyOnce("Stored-data age: fixture records listed for Ashfield Rovers, season "
                + SharedStatsSeed.CURRENT_SEASON_CODE + ", last recorded write 2026-08-01 13:00 UK (40 days ago).");
        assertThat(result).doesNotContain(DataAge.GAMEWEEK_CAVEAT);
    }

    /** No fixture rows: the empty listing does not inherit the gameweek's age. */
    @Test
    @Transactional
    void anEmptyListingHasNoFixtureAge() {
        everyRowWrittenFresh();
        gameweekWrittenAt(2, Instant.parse("2026-07-12T12:00:00Z"));

        String result = fixtureTools.getUpcomingFixtures(SharedStatsSeed.TEAM_WITHOUT_FIXTURES, 5);

        assertThat(result).contains("No upcoming fixtures found for Woodbridge Athletic");
        assertThat(result).containsOnlyOnce("Stored-data age: starting-gameweek selection (gameweek row id=2), season "
                + SharedStatsSeed.POPULATED_SEASON_CODE + ", last recorded write 2026-07-12 13:00 UK (60 days ago).");
        assertThat(result).containsOnlyOnce("Stored-data age: fixture records listed for Woodbridge Athletic, season "
                + SharedStatsSeed.POPULATED_SEASON_CODE + ", no stored rows used; age unavailable.");
    }

    private static List<String> gameweeksListed(String result) {
        Matcher m = Pattern.compile("GW(\\d+):").matcher(result);
        return m.results().map(r -> r.group(1)).toList();
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
    private void postponed(int seasonId, int fixtureId, Instant writtenAt) {
        jdbc.update("""
                INSERT INTO fixture (id, season_id, gameweek, code, team_h, team_a, team_h_difficulty,
                                     team_a_difficulty, kickoff_time, started, finished, updated_at)
                VALUES (?, ?, NULL, ?, 9001, 9004, 2, 4, NULL, false, false, ?)
                """, fixtureId, seasonId, fixtureId, utc(writtenAt));
    }

    private void otherSeasonClubs() {
        jdbc.update("""
                INSERT INTO team (id, season_id, code, name, short_name) VALUES
                    (9001, ?, 9001, 'Ashfield Rovers', 'ASH'),
                    (9004, ?, 9004, 'Selby Town', 'SEL')
                ON CONFLICT (id, season_id) DO NOTHING
                """, SharedStatsSeed.CURRENT_SEASON_ID, SharedStatsSeed.CURRENT_SEASON_ID);
    }

    private static OffsetDateTime utc(Instant at) {
        return at == null ? null : OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
    }
}
