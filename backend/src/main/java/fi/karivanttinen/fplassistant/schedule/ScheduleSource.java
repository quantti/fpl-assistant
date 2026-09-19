package fi.karivanttinen.fplassistant.schedule;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import fi.karivanttinen.fplassistant.tools.UkTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Read side of the schedule capture, shared by {@code GameweekTools} and {@code FixtureTools}.
 *
 * <p>The provenance line is this capture's own, not {@code SeasonContext.provenance()}: the
 * season is read from the rows, which during the changeover is the season being played while
 * the resolver still names the completed one — the same reasoning as
 * {@code DailyPriceSource.provenance()}, and a documented exception in {@code AGENTS.md}.
 */
@Component
public class ScheduleSource {

    public record Snapshot(String season, Instant capturedAt) {
    }

    public record EventRow(int id, Instant deadline, Instant capturedAt) {
    }

    /**
     * One deadline on the cross-season timeline the message quota's window is cut from. Carries
     * its season, which {@link EventRow} does not: the season changeover is a boundary between
     * two seasons' rows and is invisible from either one alone.
     */
    public record Deadline(String season, int gameweek, Instant deadline) {
    }

    public record FixtureRow(int id, Integer gameweek, int homeTeamId, int awayTeamId,
                             Instant kickoff, boolean started, boolean finished,
                             Integer homeDifficulty, Integer awayDifficulty, Instant capturedAt) {
    }

    public enum CompletenessReason {
        COMPLETE,
        NO_CAPTURE,
        MIXED_CAPTURE_TIMESTAMPS,
        DUPLICATE_EVENT_ID,
        INVALID_EVENT_SET,
        DUPLICATE_FIXTURE_ID,
        INVALID_FIXTURE_COUNT,
        INVALID_CLUB,
        SELF_FIXTURE,
        INVALID_ASSIGNED_GAMEWEEK,
        DUPLICATE_PAIRING,
        INVALID_CLUB_SET
    }

    public record Completeness(boolean complete, CompletenessReason reason) {
        public Completeness {
            Objects.requireNonNull(reason);
            if (complete != (reason == CompletenessReason.COMPLETE)) {
                throw new IllegalArgumentException("completeness status and reason disagree");
            }
        }
    }

    public record View(Optional<Snapshot> snapshot, List<EventRow> events,
                       List<FixtureRow> fixtures, Completeness completeness) {
        public View {
            snapshot = Objects.requireNonNull(snapshot);
            events = List.copyOf(events);
            fixtures = List.copyOf(fixtures);
            Objects.requireNonNull(completeness);
        }
    }

    private final JdbcTemplate jdbc;
    private final String gameweekTable;
    private final String fixtureTable;

    public ScheduleSource(JdbcTemplate jdbc,
                          @Value("${fpl.schedule.gameweek-table-name}") String gameweekTable,
                          @Value("${fpl.schedule.fixture-table-name}") String fixtureTable) {
        this.jdbc = jdbc;
        this.gameweekTable = gameweekTable;
        this.fixtureTable = fixtureTable;
    }

    public String gameweekTable() {
        return gameweekTable;
    }

    public String fixtureTable() {
        return fixtureTable;
    }

    /** The most recently captured season, or empty before the first capture. */
    public Optional<Snapshot> latest() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT season, MAX(captured_at) AS captured_at FROM %s
                GROUP BY season ORDER BY MAX(captured_at) DESC LIMIT 1
                """.formatted(gameweekTable));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.getFirst();
        return Optional.of(new Snapshot((String) row.get("season"), instantOf(row.get("captured_at"))));
    }

    /**
     * Every captured season's deadlines in time order.
     *
     * <p>Unlike {@link #readView()} this is not scoped to the newest season. The quota window it
     * serves has to see across a changeover, and the last deadline before an August request is
     * the previous season's GW38.
     */
    public List<Deadline> deadlines() {
        return jdbc.query("""
                SELECT season, id, deadline_time FROM %s ORDER BY deadline_time
                """.formatted(gameweekTable), (rs, rowNum) -> new Deadline(
                        rs.getString("season"), rs.getInt("id"),
                        instantOf(rs.getObject("deadline_time"))));
    }

    /**
     * A bounded, immutable view of the newest captured season. Structural completeness is
     * deliberately independent of freshness: fixture-listing tools may still display an old or
     * partial capture, while ranking consumers decide whether it can support current-role claims.
     * Callers that need one coherent view while a writer commits a replacement own the enclosing
     * read-only REPEATABLE READ transaction.
     */
    public View readView() {
        Optional<Snapshot> snapshot = latest();
        if (snapshot.isEmpty()) {
            return new View(Optional.empty(), List.of(), List.of(),
                    new Completeness(false, CompletenessReason.NO_CAPTURE));
        }

        Snapshot selected = snapshot.get();
        List<EventRow> events = jdbc.query("""
                SELECT id, deadline_time, captured_at FROM %s
                WHERE season = ? ORDER BY id
                """.formatted(gameweekTable), (rs, rowNum) -> new EventRow(
                        rs.getInt("id"), instantOf(rs.getObject("deadline_time")),
                        instantOf(rs.getObject("captured_at"))), selected.season());
        List<FixtureRow> fixtures = jdbc.query("""
                SELECT id, gameweek, team_h, team_a, kickoff_time, started, finished,
                       team_h_difficulty, team_a_difficulty, captured_at
                FROM %s WHERE season = ? ORDER BY id
                """.formatted(fixtureTable), (rs, rowNum) -> new FixtureRow(
                        rs.getInt("id"), nullableInteger(rs.getObject("gameweek")),
                        rs.getInt("team_h"), rs.getInt("team_a"),
                        nullableInstant(rs.getObject("kickoff_time")),
                        rs.getBoolean("started"), rs.getBoolean("finished"),
                        nullableInteger(rs.getObject("team_h_difficulty")),
                        nullableInteger(rs.getObject("team_a_difficulty")),
                        instantOf(rs.getObject("captured_at"))), selected.season());
        return new View(snapshot, events, fixtures, assess(snapshot, events, fixtures));
    }

    /**
     * Duplicate ids, then the 1..38 set. Shared with {@link #assessEvents} so "a clean event set"
     * has one definition rather than two that drift.
     */
    private static Completeness eventIds(List<EventRow> events) {
        Set<Integer> ids = new HashSet<>();
        for (EventRow event : events) {
            if (!ids.add(event.id())) {
                return incomplete(CompletenessReason.DUPLICATE_EVENT_ID);
            }
        }
        if (ids.size() != 38
                || !ids.equals(java.util.stream.IntStream.rangeClosed(1, 38)
                        .boxed().collect(java.util.stream.Collectors.toUnmodifiableSet()))) {
            return incomplete(CompletenessReason.INVALID_EVENT_SET);
        }
        return new Completeness(true, CompletenessReason.COMPLETE);
    }

    /**
     * The event half of {@link #assess}, for a caller with no interest in fixtures.
     *
     * <p>The message quota's window needs the deadlines to be a coherent 1..38 from one capture
     * and needs nothing at all from the fixture list: a half-written season would put a window
     * boundary in the wrong place, while a fixture is not part of the question.
     */
    public static Completeness assessEvents(Optional<Snapshot> snapshot, List<EventRow> events) {
        if (snapshot.isEmpty()) {
            return incomplete(CompletenessReason.NO_CAPTURE);
        }
        Instant capturedAt = snapshot.get().capturedAt();
        if (events.stream().anyMatch(row -> !capturedAt.equals(row.capturedAt()))) {
            return incomplete(CompletenessReason.MIXED_CAPTURE_TIMESTAMPS);
        }
        return eventIds(events);
    }

    /**
     * Assesses the structural whole-league invariant independently of database loading.
     * Owned-sync catalogue mapping composes this same check before publication so ranking and
     * ingestion cannot drift on what constitutes a complete schedule.
     */
    public static Completeness assess(Optional<Snapshot> snapshot, List<EventRow> events,
                                      List<FixtureRow> fixtures) {
        if (snapshot.isEmpty()) {
            return incomplete(CompletenessReason.NO_CAPTURE);
        }
        Instant capturedAt = snapshot.get().capturedAt();
        if (events.stream().anyMatch(row -> !capturedAt.equals(row.capturedAt()))
                || fixtures.stream().anyMatch(row -> !capturedAt.equals(row.capturedAt()))) {
            return incomplete(CompletenessReason.MIXED_CAPTURE_TIMESTAMPS);
        }

        Completeness eventIdCheck = eventIds(events);
        if (!eventIdCheck.complete()) {
            return eventIdCheck;
        }
        Set<Integer> eventIds = events.stream().map(EventRow::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        Set<Integer> fixtureIds = new HashSet<>();
        for (FixtureRow fixture : fixtures) {
            if (!fixtureIds.add(fixture.id())) {
                return incomplete(CompletenessReason.DUPLICATE_FIXTURE_ID);
            }
        }
        if (fixtures.size() != 380) {
            return incomplete(CompletenessReason.INVALID_FIXTURE_COUNT);
        }

        Set<Integer> clubs = new HashSet<>();
        Set<Long> pairings = new HashSet<>();
        boolean hasSelfFixture = false;
        for (FixtureRow fixture : fixtures) {
            if (fixture.homeTeamId() <= 0 || fixture.awayTeamId() <= 0) {
                return incomplete(CompletenessReason.INVALID_CLUB);
            }
            hasSelfFixture |= fixture.homeTeamId() == fixture.awayTeamId();
            if (fixture.gameweek() != null && !eventIds.contains(fixture.gameweek())) {
                return incomplete(CompletenessReason.INVALID_ASSIGNED_GAMEWEEK);
            }
            clubs.add(fixture.homeTeamId());
            clubs.add(fixture.awayTeamId());
            long pairing = ((long) fixture.homeTeamId() << 32)
                    ^ (fixture.awayTeamId() & 0xffffffffL);
            pairings.add(pairing);
        }
        if (clubs.size() != 20) {
            return incomplete(CompletenessReason.INVALID_CLUB_SET);
        }
        if (hasSelfFixture) {
            return incomplete(CompletenessReason.SELF_FIXTURE);
        }
        if (pairings.size() != 380) {
            return incomplete(CompletenessReason.DUPLICATE_PAIRING);
        }
        return new Completeness(true, CompletenessReason.COMPLETE);
    }

    private static Completeness incomplete(CompletenessReason reason) {
        return new Completeness(false, reason);
    }

    private static Integer nullableInteger(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    private static Instant nullableInstant(Object value) {
        return value == null ? null : instantOf(value);
    }

    /**
     * pgjdbc usually hands a TIMESTAMPTZ back as {@code java.sql.Timestamp} from an untyped
     * getObject, but {@code UkTime}'s javadoc records that it can be an {@code OffsetDateTime}
     * depending on configuration — and a blind cast would escape the tools'
     * {@code catch (DataAccessException)} as a raw ClassCastException.
     */
    private static Instant instantOf(Object value) {
        return switch (value) {
            case java.sql.Timestamp ts -> ts.toInstant();
            case OffsetDateTime odt -> odt.toInstant();
            case Instant i -> i;
            default -> throw new IllegalStateException(
                    "captured_at came back as " + (value == null ? "null" : value.getClass().getName()));
        };
    }

    /**
     * The gameweek the fixture listing starts from: the round being played (latest passed
     * deadline, unfinished), else the next round, else the season's last — a finished season
     * lists its final round rather than opening day. This is deliberately not the same question
     * {@code GameweekTools} answers with its current/next scan: that reports the round being
     * played even once it is finished, this names where an "upcoming fixtures" answer begins.
     * One derivation here so the two tools cannot disagree on the deadline arithmetic.
     */
    public Integer startGameweek(String season) {
        return jdbc.queryForObject("""
                SELECT COALESCE(
                    (SELECT MAX(id) FROM %1$s WHERE season = ? AND deadline_time <= now() AND finished = false),
                    (SELECT MIN(id) FROM %1$s WHERE season = ? AND deadline_time > now()),
                    (SELECT MAX(id) FROM %1$s WHERE season = ?))
                """.formatted(gameweekTable),
                Integer.class, season, season, season);
    }

    public static String provenance(String season, Instant capturedAt, Instant now) {
        long daysOld = Math.max(0, ChronoUnit.DAYS.between(
                LocalDate.ofInstant(capturedAt, UkTime.zone()), LocalDate.ofInstant(now, UkTime.zone())));
        String age = switch ((int) Math.min(daysOld, 2)) {
            case 0 -> "captured today";
            case 1 -> "captured yesterday";
            default -> "captured %d days ago".formatted(daysOld);
        };
        return ("Data source: FPL's own gameweek and fixture list for season %s, %s. Deadlines are as"
                + " FPL publishes them and gameweek status is judged against the clock now; scores"
                + " and postponements may have changed since the capture.").formatted(season, age);
    }
}
