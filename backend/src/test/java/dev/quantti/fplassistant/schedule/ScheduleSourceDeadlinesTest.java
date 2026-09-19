package dev.quantti.fplassistant.schedule;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The cross-season deadline read the quota window is cut from.
 *
 * <p>Seeds and clears the whole gameweek table rather than one season's rows: the changeover is a
 * boundary between two seasons, so the case worth testing is precisely the one a season-scoped
 * clean-up would leave half of behind.
 */
@SpringBootTest
@ActiveProfiles("test")
class ScheduleSourceDeadlinesTest {

    private static final Instant CAPTURED = Instant.parse("2097-09-19T01:10:00Z");

    @Autowired ScheduleSource source;
    @Autowired JdbcTemplate jdbc;
    @Value("${fpl.schedule.gameweek-table-name}") String gameweekTable;

    @BeforeEach
    @AfterEach
    void clear() {
        jdbc.update("DELETE FROM " + gameweekTable);
    }

    private void seedEvent(String season, int id, String deadline) {
        jdbc.update("INSERT INTO " + gameweekTable
                + " (season, id, name, deadline_time, captured_at) VALUES (?, ?, ?, ?, ?)",
                season, id, "Gameweek " + id, Timestamp.from(Instant.parse(deadline)),
                Timestamp.from(CAPTURED));
    }

    @Test
    void deadlinesSpanEverySeasonInTimeOrder() {
        seedEvent("2096/97", 38, "2097-05-24T13:00:00Z");
        seedEvent("2097/98", 2, "2097-08-21T17:30:00Z");
        seedEvent("2097/98", 1, "2097-08-14T17:30:00Z");

        assertThat(source.deadlines())
                .extracting(ScheduleSource.Deadline::season, ScheduleSource.Deadline::gameweek)
                .containsExactly(tuple("2096/97", 38), tuple("2097/98", 1), tuple("2097/98", 2));
    }

    @Test
    void thereAreNoDeadlinesBeforeTheFirstCapture() {
        assertThat(source.deadlines()).isEmpty();
    }

    @Test
    void assessEventsAcceptsACleanSetAndRejectsAGap() {
        Optional<ScheduleSource.Snapshot> snapshot =
                Optional.of(new ScheduleSource.Snapshot("2097/98", CAPTURED));
        List<ScheduleSource.EventRow> complete = new ArrayList<>();
        for (int id = 1; id <= 38; id++) {
            complete.add(new ScheduleSource.EventRow(id, CAPTURED.plusSeconds(id * 86400L),
                    CAPTURED));
        }

        assertThat(ScheduleSource.assessEvents(snapshot, complete).complete()).isTrue();

        assertThat(ScheduleSource.assessEvents(snapshot, complete.subList(0, 37)).reason())
                .isEqualTo(ScheduleSource.CompletenessReason.INVALID_EVENT_SET);
    }

    @Test
    void assessEventsRejectsACaptureItDoesNotBelongTo() {
        Optional<ScheduleSource.Snapshot> snapshot =
                Optional.of(new ScheduleSource.Snapshot("2097/98", CAPTURED));
        List<ScheduleSource.EventRow> mixed = new ArrayList<>();
        for (int id = 1; id <= 38; id++) {
            mixed.add(new ScheduleSource.EventRow(id, CAPTURED.plusSeconds(id * 86400L),
                    id == 7 ? CAPTURED.minusSeconds(86400) : CAPTURED));
        }

        assertThat(ScheduleSource.assessEvents(snapshot, mixed).reason())
                .isEqualTo(ScheduleSource.CompletenessReason.MIXED_CAPTURE_TIMESTAMPS);
    }

    @Test
    void assessEventsRefusesWithoutACapture() {
        assertThat(ScheduleSource.assessEvents(Optional.empty(), List.of()).reason())
                .isEqualTo(ScheduleSource.CompletenessReason.NO_CAPTURE);
    }
}
