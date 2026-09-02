package dev.quantti.fplassistant.schedule;

import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduleSyncServiceTest {

    private static Map<String, Object> bootstrap() throws Exception {
        try (InputStream in = ScheduleSyncServiceTest.class.getResourceAsStream("/fpl-bootstrap-events-fixture.json")) {
            return new ObjectMapper().readValue(in, new TypeReference<>() { });
        }
    }

    private static List<Map<String, Object>> fixtures() throws Exception {
        try (InputStream in = ScheduleSyncServiceTest.class.getResourceAsStream("/fpl-fixtures-fixture.json")) {
            return new ObjectMapper().readValue(in, new TypeReference<>() { });
        }
    }

    @Test
    void mapsEveryEventToAGameweekRow() throws Exception {
        List<Object[]> rows = ScheduleSyncService.toGameweekRows(bootstrap(), "2026-27");

        assertThat(rows).hasSize(3);
        Object[] gw1 = rows.getFirst();
        assertThat(gw1[0]).isEqualTo("2026-27");
        assertThat(gw1[1]).isEqualTo(1);
        assertThat(gw1[2]).isEqualTo("Gameweek 1");
        assertThat(gw1[3]).isEqualTo(OffsetDateTime.parse("2026-08-14T17:30:00Z"));
        assertThat(gw1[4]).isEqualTo(true);    // finished
        assertThat(gw1[8]).isEqualTo(57);      // average_entry_score
        assertThat(gw1[10]).isEqualTo(381);    // most_captained
    }

    @Test
    void anEventWithoutADeadlineIsSkippedRatherThanWrittenWithoutOne() throws Exception {
        Map<String, Object> body = bootstrap();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) body.get("events");
        events.getFirst().put("deadline_time", null);

        assertThat(ScheduleSyncService.toGameweekRows(body, "2026-27")).hasSize(2);
    }

    @Test
    void mapsFixturesWithTeamNamesFromTheBootstrapTeamsBlock() throws Exception {
        Map<Integer, String[]> teams = ScheduleSyncService.teamsOf(bootstrap());
        List<Object[]> rows = ScheduleSyncService.toFixtureRows(fixtures(), teams, "2026-27");

        assertThat(rows).hasSize(3);
        Object[] played = rows.getFirst();
        assertThat(played[1]).isEqualTo(1);
        assertThat(played[2]).isEqualTo(1);              // gameweek
        assertThat(played[3]).isEqualTo(1);              // team_h
        assertThat(played[5]).isEqualTo("Arsenal");      // team_h_name
        assertThat(played[8]).isEqualTo("AVL");          // team_a_short
        assertThat(played[9]).isEqualTo(3);              // team_h_difficulty
        assertThat(played[11]).isEqualTo(OffsetDateTime.parse("2026-08-15T14:00:00Z"));
        assertThat(played[13]).isEqualTo(true);          // finished
        assertThat(played[14]).isEqualTo(3);             // team_h_score
    }

    @Test
    void aPostponedFixtureKeepsNullGameweekAndKickoff() throws Exception {
        List<Object[]> rows = ScheduleSyncService.toFixtureRows(fixtures(),
                ScheduleSyncService.teamsOf(bootstrap()), "2026-27");
        Object[] postponed = rows.get(2);

        assertThat(postponed[2]).isNull();
        assertThat(postponed[11]).isNull();
        assertThat(postponed[14]).isNull();
    }

    @Test
    void aTeamMissingFromTheBootstrapBlockLeavesNullNamesNotAnException() throws Exception {
        List<Object[]> rows = ScheduleSyncService.toFixtureRows(fixtures(), Map.of(), "2026-27");

        assertThat(rows.getFirst()[5]).isNull();
        assertThat(rows.getFirst()[6]).isNull();
    }
}
