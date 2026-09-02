package dev.quantti.fplassistant.tools;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class FixtureToolsCaptureTest {

    private static final String SEASON = "T-2095-96";

    @Autowired FixtureTools tools;
    @Autowired JdbcTemplate jdbc;
    @Value("${fpl.schedule.gameweek-table-name}") String gameweekTable;
    @Value("${fpl.schedule.fixture-table-name}") String fixtureTable;

    @BeforeEach
    void seed() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("INSERT INTO " + gameweekTable + " (season, id, name, deadline_time, finished) VALUES"
                + " (?, 1, 'Gameweek 1', ?, true), (?, 2, 'Gameweek 2', ?, false), (?, 3, 'Gameweek 3', ?, false)",
                SEASON, now.minusDays(8), SEASON, now.minusDays(1), SEASON, now.plusDays(6));
        fixture(1, 1, 9001, 9002, now.minusDays(7), true, 2, 1);
        fixture(2, 2, 9002, 9001, now.plusHours(20), false, null, null);   // being played this week
        fixture(3, 3, 9001, 9003, now.plusDays(7), false, null, null);
        fixture(4, 3, 9003, 9001, now.plusDays(8), false, null, null);     // a double gameweek
        fixture(5, null, 9001, 9002, null, false, null, null);             // postponed
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM " + fixtureTable + " WHERE season = ?", SEASON);
        jdbc.update("DELETE FROM " + gameweekTable + " WHERE season = ?", SEASON);
    }

    private void fixture(int id, Integer gameweek, int home, int away, OffsetDateTime kickoff,
                         boolean finished, Integer homeScore, Integer awayScore) {
        String[][] names = {{"Ashfield Rovers", "ASH"}, {"Northmoor City", "NMC"}, {"Selby Town", "SEL"}};
        String[] h = names[home - 9001];
        String[] a = names[away - 9001];
        jdbc.update("INSERT INTO " + fixtureTable + " (season, id, gameweek, team_h, team_a, team_h_name,"
                + " team_h_short, team_a_name, team_a_short, team_h_difficulty, team_a_difficulty, kickoff_time,"
                + " started, finished, team_h_score, team_a_score)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 2, 4, ?, ?, ?, ?, ?)",
                SEASON, id, gameweek, home, away, h[0], h[1], a[0], a[1], kickoff, finished, finished,
                homeScore, awayScore);
    }

    @Test
    void listsUpcomingFixturesFromTheCurrentGameweekWithFdrAndTheCaptureLine() {
        String result = tools.getUpcomingFixtures("Ashfield", 2);

        assertThat(result).startsWith("Data source: FPL's own gameweek and fixture list for season " + SEASON);
        assertThat(result).contains("Upcoming fixtures for Ashfield Rovers (ASH)");
        assertThat(result).contains("GW2: vs NMC (A) | FDR: 4");
        assertThat(result).contains("GW3: vs SEL (H) | FDR: 2").contains("GW3: vs SEL (A) | FDR: 4");
        assertThat(result).doesNotContain("GW1:");
        assertThat(result).contains("Postponed, no rescheduled gameweek yet:");
    }

    @Test
    void matchesTheShortNameToo() {
        assertThat(tools.getUpcomingFixtures("nmc", 1)).contains("for Northmoor City (NMC)");
    }

    /** Both paths say "Team not found"; the banner proves which one answered. */
    @Test
    void anUnknownTeamIsNotFoundOnTheCapturePath() {
        String result = tools.getUpcomingFixtures("Woodbridge", 1);

        assertThat(result).startsWith("Data source: FPL's own gameweek and fixture list");
        assertThat(result).contains("Team not found");
    }

    /**
     * Once the gameweek being played is finished, the list starts at the next one. The shared
     * path shows the finished round's fixtures with their scores because it starts at
     * `is_current`; the capture path judges by deadline and finish, so a Tuesday question about
     * upcoming fixtures does not open with Saturday's result. Deliberate, and recorded in STATUS.
     */
    @Test
    void aFinishedCurrentGameweekIsSkippedInFavourOfTheNext() {
        jdbc.update("UPDATE " + gameweekTable + " SET finished = true WHERE season = ? AND id = 2", SEASON);

        String result = tools.getUpcomingFixtures("Ashfield", 1);

        assertThat(result).doesNotContain("GW2:");
        assertThat(result).contains("GW3: vs SEL (H)");
    }

    /**
     * End of season: every deadline passed and every round finished. The listing is the last
     * round's results under the neutral "Fixtures" header — the old MIN(id) fallback opened a
     * June answer with opening day's scores labelled "Upcoming".
     */
    @Test
    void aFinishedSeasonListsItsLastRoundNotOpeningDay() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("UPDATE " + gameweekTable + " SET finished = true, deadline_time = ?"
                + " WHERE season = ? AND id = 2", now.minusDays(20), SEASON);
        jdbc.update("UPDATE " + gameweekTable + " SET finished = true, deadline_time = ?"
                + " WHERE season = ? AND id = 3", now.minusDays(10), SEASON);
        jdbc.update("UPDATE " + fixtureTable + " SET finished = true, started = true,"
                + " kickoff_time = ?, team_h_score = 1, team_a_score = 0"
                + " WHERE season = ? AND id IN (2, 3, 4)", now.minusDays(9), SEASON);

        String result = tools.getUpcomingFixtures("Ashfield", 2);

        assertThat(result).contains("Fixtures for Ashfield Rovers");
        assertThat(result).doesNotContain("Upcoming fixtures");
        assertThat(result).contains("GW3:");
        assertThat(result).doesNotContain("GW1:");
        assertThat(result).doesNotContain("GW2:");
    }
}
