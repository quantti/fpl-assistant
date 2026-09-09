package fi.karivanttinen.fplassistant.testsupport;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import fi.karivanttinen.fplassistant.schedule.ScheduleSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Deterministic 20-club, 38-event and 380-fixture schedule owned by ranking tests. */
public final class RankingSeed {

    public static final String VIEW_SEASON = "K31-VIEW";
    public static final int TEAM_BASE = 9700;
    public static final int FIXTURE_BASE = 970_000;

    private RankingSeed() {
    }

    public static int team(int oneBasedIndex) {
        if (oneBasedIndex < 1 || oneBasedIndex > 20) {
            throw new IllegalArgumentException("team index must be 1..20");
        }
        return TEAM_BASE + oneBasedIndex;
    }

    public static int fixtureId(int homeTeam, int awayTeam) {
        return FIXTURE_BASE + (homeTeam - team(1)) * 20 + (awayTeam - team(1));
    }

    public static List<ScheduleSource.EventRow> events(Instant capturedAt, Instant firstDeadline) {
        return IntStream.rangeClosed(1, 38)
                .mapToObj(id -> new ScheduleSource.EventRow(id,
                        firstDeadline.plus(7L * (id - 1), ChronoUnit.DAYS), capturedAt))
                .toList();
    }

    public static List<ScheduleSource.FixtureRow> fixtures(Instant capturedAt, Instant firstDeadline) {
        List<Integer> rotation = IntStream.rangeClosed(1, 20).boxed().collect(
                java.util.stream.Collectors.toCollection(ArrayList::new));
        List<ScheduleSource.FixtureRow> rows = new ArrayList<>(380);
        for (int round = 0; round < 19; round++) {
            for (int pair = 0; pair < 10; pair++) {
                int left = team(rotation.get(pair));
                int right = team(rotation.get(19 - pair));
                int home = ((round + pair) & 1) == 0 ? left : right;
                int away = home == left ? right : left;
                addFixture(rows, capturedAt, firstDeadline, round + 1, pair, home, away);
                addFixture(rows, capturedAt, firstDeadline, round + 20, pair, away, home);
            }
            Integer last = rotation.removeLast();
            rotation.add(1, last);
        }
        return List.copyOf(rows);
    }

    private static void addFixture(List<ScheduleSource.FixtureRow> rows, Instant capturedAt,
                                   Instant firstDeadline, int gameweek, int pair,
                                   int home, int away) {
        Instant kickoff = firstDeadline.plus(1L + 7L * (gameweek - 1), ChronoUnit.DAYS)
                .plus(pair * 2L, ChronoUnit.HOURS);
        rows.add(new ScheduleSource.FixtureRow(fixtureId(home, away), gameweek, home, away,
                kickoff, false, false, 3, 3, capturedAt));
    }

    public static void seedSchedule(JdbcTemplate jdbc, String gameweekTable, String fixtureTable,
                                    String season, Instant capturedAt, Instant firstDeadline) {
        FictitiousTestData.assertSafeSeason(season);
        cleanSchedule(jdbc, gameweekTable, fixtureTable, season);
        jdbc.batchUpdate("""
                INSERT INTO %s (season, id, name, deadline_time, captured_at)
                VALUES (?, ?, ?, ?, ?)
                """.formatted(gameweekTable), events(capturedAt, firstDeadline).stream()
                        .map(row -> new Object[] {season, row.id(), "Gameweek " + row.id(),
                                Timestamp.from(row.deadline()), Timestamp.from(row.capturedAt())})
                        .toList());
        jdbc.batchUpdate("""
                INSERT INTO %s (season, id, gameweek, team_h, team_a, kickoff_time,
                                started, finished, team_h_difficulty, team_a_difficulty, captured_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.formatted(fixtureTable), fixtures(capturedAt, firstDeadline).stream()
                        .map(row -> new Object[] {season, row.id(), row.gameweek(), row.homeTeamId(),
                                row.awayTeamId(), Timestamp.from(row.kickoff()), row.started(),
                                row.finished(), row.homeDifficulty(), row.awayDifficulty(),
                                Timestamp.from(row.capturedAt())})
                        .toList());
    }

    public static void cleanSchedule(JdbcTemplate jdbc, String gameweekTable, String fixtureTable,
                                     String season) {
        jdbc.update("DELETE FROM " + fixtureTable + " WHERE season = ?", season);
        jdbc.update("DELETE FROM " + gameweekTable + " WHERE season = ?", season);
    }
}
