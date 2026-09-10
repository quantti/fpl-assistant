package dev.quantti.fplassistant.schedule;

import java.time.Instant;

import dev.quantti.fplassistant.testsupport.RankingSeed;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** The horizon over the view the database actually returns, flags and all. */
@SpringBootTest
@ActiveProfiles("test")
class HorizonFixturesIntegrationTest {

    private static final String SEASON = "K35-VIEW";
    private static final Instant FIRST_DEADLINE = Instant.parse("2026-08-22T17:30:00Z");
    private static final Instant CAPTURED = Instant.parse("2026-09-09T02:10:00Z");
    private static final Instant AS_OF = Instant.parse("2026-09-09T12:00:00Z");

    @Autowired ScheduleSource source;
    @Autowired JdbcTemplate jdbc;
    @Value("${fpl.schedule.gameweek-table-name}") String gameweekTable;
    @Value("${fpl.schedule.fixture-table-name}") String fixtureTable;

    @AfterEach
    void cleanUp() {
        RankingSeed.cleanSchedule(jdbc, gameweekTable, fixtureTable, SEASON);
    }

    /**
     * The captured {@code is_current}/{@code is_next} flags describe 02:10 that morning, not the
     * instant of the call, so a capture that still calls gameweek 1 current must not move the
     * window. Deadlines decide, and only deadlines.
     */
    @Test
    void staleCapturedCurrentAndNextFlagsDoNotMoveTheWindow() {
        RankingSeed.seedSchedule(jdbc, gameweekTable, fixtureTable, SEASON, CAPTURED,
                FIRST_DEADLINE);
        jdbc.update("UPDATE " + gameweekTable
                + " SET is_current = (id = 1), is_next = (id = 2) WHERE season = ?", SEASON);
        ScheduleSource.View view = source.readView();
        assertThat(view.snapshot()).contains(new ScheduleSource.Snapshot(SEASON, CAPTURED));

        HorizonFixtures.Result result = HorizonFixtures.derive(view, AS_OF, SEASON, 5);

        assertThat(result.status()).isEqualTo(HorizonFixtures.Status.KNOWN);
        assertThat(result.startGameweek()).isEqualTo(4);
        assertThat(result.endGameweek()).isEqualTo(8);
        assertThat(result.club(RankingSeed.team(1)).count()).isEqualTo(5);
    }
}
