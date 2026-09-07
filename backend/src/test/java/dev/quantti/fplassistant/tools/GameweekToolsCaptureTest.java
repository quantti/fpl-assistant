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

/** {@link GameweekTools} with a schedule capture present: it wins over tapas, and status is derived from the clock. */
@SpringBootTest
@ActiveProfiles("test")
class GameweekToolsCaptureTest {

    private static final String SEASON = "T-2096-97";

    @Autowired GameweekTools tools;
    @Autowired JdbcTemplate jdbc;
    @Value("${fpl.schedule.gameweek-table-name}") String table;

    @BeforeEach
    void seed() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        // GW1 finished; GW2's deadline passed yesterday and it is being played; GW3 is next.
        // FPL's own flags are stale on purpose — is_current still says GW1 — to prove the
        // tool judges by deadline, not by flag.
        gameweek(1, now.minusDays(8), true, true, false, 57, 127);
        gameweek(2, now.minusDays(1), false, false, false, null, null);
        gameweek(3, now.plusDays(6), false, false, true, null, null);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM " + table + " WHERE season = ?", SEASON);
    }

    private void gameweek(int id, OffsetDateTime deadline, boolean finished, boolean current, boolean next,
                          Integer average, Integer highest) {
        jdbc.update("INSERT INTO " + table + " (season, id, name, deadline_time, finished, data_checked,"
                + " is_current, is_next, average_entry_score, highest_score, most_captained, most_transferred_in)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 9203, 9202)",
                SEASON, id, "Gameweek " + id, deadline, finished, finished, current, next, average, highest);
    }

    @Test
    void reportsTheGameweekBeingPlayedAndTheNextOneFromDeadlinesNotFlags() {
        String result = tools.getGameweekInfo();

        assertThat(result).startsWith("Data source: FPL's own gameweek and fixture list for season " + SEASON);
        assertThat(result).contains("Gameweek 2 (id=2)").contains("Status: CURRENT");
        assertThat(result).contains("Gameweek 3 (id=3)").contains("Status: NEXT");
        assertThat(result).doesNotContain("Gameweek 1 (id=1)");
        assertThat(result).doesNotContain("Data source: season");
    }

    @Test
    void theDeadlineIsPrintedInUkTime() {
        String result = tools.getGameweekInfo();

        assertThat(result).containsPattern("Deadline: \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2} UK");
    }
}
