package fi.karivanttinen.fplassistant.schedule;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ScheduleWriteIntegrationTest {

    private static final String SEASON = "T-2097-98";

    @Autowired ScheduleSyncService service;
    @Autowired JdbcTemplate jdbc;
    @Value("${fpl.schedule.gameweek-table-name}") String gameweekTable;
    @Value("${fpl.schedule.fixture-table-name}") String fixtureTable;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM " + gameweekTable + " WHERE season = ?", SEASON);
        jdbc.update("DELETE FROM " + fixtureTable + " WHERE season = ?", SEASON);
    }

    private static Object[] gameweek(int id, String deadline) {
        return new Object[] {SEASON, id, "Gameweek " + id, OffsetDateTime.parse(deadline),
                false, false, false, false, null, null, null, null};
    }

    private static Object[] fixture(int id, int gameweek) {
        return new Object[] {SEASON, id, gameweek, 9001, 9002, "Ashfield", "ASH", "Northmoor", "NMC",
                2, 3, OffsetDateTime.parse("2097-08-15T14:00:00Z"), false, false, null, null};
    }

    @Test
    void aSecondCaptureOverwritesAndRemovesWhatFplNoLongerPublishes() {
        service.write(SEASON, List.<Object[]>of(gameweek(1, "2097-08-14T17:30:00Z")),
                List.of(fixture(1, 1), fixture(2, 1)));
        service.write(SEASON, List.<Object[]>of(gameweek(1, "2097-08-14T18:00:00Z")),
                List.<Object[]>of(fixture(1, 1)));

        Integer fixtures = jdbc.queryForObject(
                "SELECT count(*) FROM " + fixtureTable + " WHERE season = ?", Integer.class, SEASON);
        OffsetDateTime deadline = jdbc.queryForObject(
                "SELECT deadline_time FROM " + gameweekTable + " WHERE season = ? AND id = 1",
                OffsetDateTime.class, SEASON);

        assertThat(fixtures).isEqualTo(1);
        assertThat(deadline).isEqualTo(OffsetDateTime.parse("2097-08-14T18:00:00Z"));
    }

    @Test
    void anEmptyFixtureListLeavesTheSeasonUntouchedRatherThanEmptyingIt() {
        service.write(SEASON, List.<Object[]>of(gameweek(1, "2097-08-14T17:30:00Z")), List.<Object[]>of(fixture(1, 1)));
        service.write(SEASON, List.<Object[]>of(gameweek(1, "2097-08-14T17:30:00Z")), List.of());

        Integer fixtures = jdbc.queryForObject(
                "SELECT count(*) FROM " + fixtureTable + " WHERE season = ?", Integer.class, SEASON);
        assertThat(fixtures).isEqualTo(1);
    }
}
