package dev.quantti.fplassistant.schedule;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;

import dev.quantti.fplassistant.tools.UkTime;

/**
 * Pure derivation: each club's countable fixtures in the next editable calendar-gameweek window.
 *
 * <p>The ranking tools used to take a club's next N <em>fixtures</em>, which silently drops the
 * last intended gameweek whenever a club doubles inside the window — the club that plays twice in
 * GW6 loses GW8 from a five-gameweek horizon. The window is a range of calendar gameweeks, so a
 * double adds a fixture rather than displacing one, and a blank removes one rather than pulling a
 * later gameweek in.
 *
 * <p>The window opens at the first deadline <b>strictly after</b> the caller's {@code asOf}
 * instant: these tools advise transfers, captains and a starting XI, so a round that is already
 * locked cannot benefit the new selection, and remaining matches of a just-locked round are not
 * countable however far away their kickoff still is. Captured deadlines decide that, never the
 * captured {@code is_current}/{@code is_next} flags — those describe the moment of the capture,
 * not the moment of the call.
 */
public final class HorizonFixtures {

    public enum Status { KNOWN, NO_EDITABLE_GAMEWEEK, UNKNOWN }

    /**
     * Why a horizon carries no numbers. The first five describe the whole result — the view
     * cannot support any club's window; the rest describe one club inside an otherwise usable
     * view, so its neighbours stay rankable.
     */
    public enum UnknownReason {
        NO_CAPTURE,
        CAPTURE_STALE,
        SCHEDULE_INCOMPLETE,
        SEASON_MISMATCH,
        NO_EDITABLE_GAMEWEEK,
        /** Outstanding fixture with no assigned gameweek: its placement is not established. */
        UNASSIGNED_FIXTURE,
        UNRESOLVED_KICKOFF,
        ELAPSED_KICKOFF,
        KICKOFF_BEFORE_DEADLINE,
        CLUB_NOT_IN_SCHEDULE
    }

    public record Fixture(int id, int gameweek, boolean home, int opponentTeamId,
                          Instant kickoff, Integer difficulty) {

        public boolean hasValidDifficulty() {
            return difficulty != null && difficulty >= 1 && difficulty <= 5;
        }
    }

    /** Known fixtures (possibly empty) or unknown with a reason — never both. */
    public record ClubWindow(int teamId, List<Fixture> fixtures, UnknownReason unknownReason) {

        public ClubWindow {
            fixtures = List.copyOf(fixtures);
            if (unknownReason != null && !fixtures.isEmpty()) {
                throw new IllegalArgumentException("an unknown club window carries no fixtures");
            }
        }

        public boolean known() {
            return unknownReason == null;
        }

        public int count() {
            if (!known()) {
                throw new IllegalStateException("no fixture count for an unknown window: "
                        + unknownReason);
            }
            return fixtures.size();
        }

        /**
         * Empty when the window is unknown, when it is empty, or when <b>any</b> counted fixture
         * lacks a valid difficulty — never the average of the valid subset, which would report a
         * partly unknown run as though it were fully rated.
         */
        public OptionalDouble averageDifficulty() {
            if (!known() || fixtures.isEmpty()
                    || !fixtures.stream().allMatch(Fixture::hasValidDifficulty)) {
                return OptionalDouble.empty();
            }
            return OptionalDouble.of(fixtures.stream()
                    .mapToInt(Fixture::difficulty).average().orElseThrow());
        }
    }

    public record Result(Instant asOf, Optional<ScheduleSource.Snapshot> source,
                         int requestedGameweeks, Status status, UnknownReason reason,
                         Integer startGameweek, Integer endGameweek,
                         Map<Integer, ClubWindow> clubs) {

        public Result {
            Objects.requireNonNull(asOf);
            source = Objects.requireNonNull(source);
            Objects.requireNonNull(status);
            clubs = Map.copyOf(clubs);
            boolean known = status == Status.KNOWN;
            if (known == (reason != null)) {
                throw new IllegalArgumentException("result status and reason disagree");
            }
            if (known != (startGameweek != null) || known != (endGameweek != null)) {
                throw new IllegalArgumentException(
                        "a gameweek range exists exactly when the horizon is known");
            }
            if (!known && !clubs.isEmpty()) {
                throw new IllegalArgumentException("an unknown horizon carries no club windows");
            }
        }

        public int actualGameweeks() {
            return status == Status.KNOWN ? endGameweek - startGameweek + 1 : 0;
        }

        public boolean truncated() {
            return status == Status.KNOWN && actualGameweeks() < requestedGameweeks;
        }

        /** Never null: an id the result has no window for is unknown, with the reason why. */
        public ClubWindow club(int teamId) {
            ClubWindow window = clubs.get(teamId);
            if (window != null) {
                return window;
            }
            return new ClubWindow(teamId, List.of(),
                    status == Status.KNOWN ? UnknownReason.CLUB_NOT_IN_SCHEDULE : reason);
        }
    }

    /** Playing order within a club's window; a counted fixture always has a kickoff. */
    private static final Comparator<Fixture> IN_PLAYING_ORDER =
            Comparator.comparingInt(Fixture::gameweek)
                    .thenComparing(Fixture::kickoff)
                    .thenComparingInt(Fixture::id);

    private HorizonFixtures() {
    }

    /**
     * @param requestedGameweeks already normalised by the caller; must be at least 1
     */
    public static Result derive(ScheduleSource.View view, Instant asOf, String captureSeason,
                                int requestedGameweeks) {
        Objects.requireNonNull(view);
        Objects.requireNonNull(asOf);
        if (requestedGameweeks < 1) {
            throw new IllegalArgumentException(
                    "requestedGameweeks must be at least 1, was " + requestedGameweeks);
        }

        UnknownReason blocked = viewProblem(view, asOf, captureSeason);
        if (blocked == null) {
            OptionalInt editable = firstEditableGameweek(view.events(), asOf);
            if (editable.isPresent()) {
                int start = editable.getAsInt();
                // Long arithmetic: a caller may legitimately ask for more gameweeks than remain,
                // and Integer.MAX_VALUE must land on gameweek 38 rather than wrap.
                int end = (int) Math.min((long) start + requestedGameweeks, 39L) - 1;
                return new Result(asOf, view.snapshot(), requestedGameweeks, Status.KNOWN, null,
                        start, end, windows(view, asOf, start, end));
            }
            blocked = UnknownReason.NO_EDITABLE_GAMEWEEK;
        }
        Status status = blocked == UnknownReason.NO_EDITABLE_GAMEWEEK
                ? Status.NO_EDITABLE_GAMEWEEK : Status.UNKNOWN;
        return new Result(asOf, view.snapshot(), requestedGameweeks, status, blocked,
                null, null, Map.of());
    }

    /** The first thing wrong with the view as a whole, or null when it can carry a horizon. */
    private static UnknownReason viewProblem(ScheduleSource.View view, Instant asOf,
                                             String captureSeason) {
        Optional<ScheduleSource.Snapshot> snapshot = view.snapshot();
        if (snapshot.isEmpty()) {
            return UnknownReason.NO_CAPTURE;
        }
        LocalDate capturedDay = LocalDate.ofInstant(snapshot.get().capturedAt(), UkTime.zone());
        if (!ScheduleFreshnessCheck.isFresh(capturedDay, asOf)) {
            return UnknownReason.CAPTURE_STALE;
        }
        if (!view.completeness().complete()) {
            return UnknownReason.SCHEDULE_INCOMPLETE;
        }
        if (!snapshot.get().season().equals(captureSeason)) {
            return UnknownReason.SEASON_MISMATCH;
        }
        return null;
    }

    /**
     * The round the window opens at: the lowest event whose captured deadline is strictly after
     * {@code asOf}. At the deadline itself the round is locked, so it cannot be selected into.
     */
    private static OptionalInt firstEditableGameweek(List<ScheduleSource.EventRow> events,
                                                     Instant asOf) {
        return events.stream()
                .filter(event -> event.deadline().isAfter(asOf))
                .mapToInt(ScheduleSource.EventRow::id)
                .min();
    }

    private static Map<Integer, ClubWindow> windows(ScheduleSource.View view, Instant asOf,
                                                    int start, int end) {
        // A complete view holds all 38 events, and every assigned gameweek is one of them.
        Map<Integer, Instant> deadlines = new HashMap<>();
        for (ScheduleSource.EventRow event : view.events()) {
            deadlines.put(event.id(), event.deadline());
        }

        Set<Integer> clubs = new LinkedHashSet<>();
        Map<Integer, UnknownReason> unknown = new HashMap<>();
        Map<Integer, List<Fixture>> counted = new HashMap<>();
        for (ScheduleSource.FixtureRow row : view.fixtures()) {
            clubs.add(row.homeTeamId());
            clubs.add(row.awayTeamId());
            if (row.started() || row.finished()) {
                continue;                                   // played, whatever its timestamps say
            }
            if (row.gameweek() == null) {
                markUnknown(unknown, row, UnknownReason.UNASSIGNED_FIXTURE);
                continue;                                   // placement not established anywhere
            }
            int gameweek = row.gameweek();
            if (gameweek < start || gameweek > end) {
                continue;                                   // outside the window, so irrelevant
            }
            UnknownReason unresolved = schedulingProblem(row, asOf, deadlines.get(gameweek));
            if (unresolved != null) {
                markUnknown(unknown, row, unresolved);
                continue;
            }
            counted.computeIfAbsent(row.homeTeamId(), id -> new ArrayList<>())
                    .add(new Fixture(row.id(), gameweek, true, row.awayTeamId(), row.kickoff(),
                            row.homeDifficulty()));
            counted.computeIfAbsent(row.awayTeamId(), id -> new ArrayList<>())
                    .add(new Fixture(row.id(), gameweek, false, row.homeTeamId(), row.kickoff(),
                            row.awayDifficulty()));
        }

        Map<Integer, ClubWindow> windows = new HashMap<>();
        for (int club : clubs) {
            UnknownReason reason = unknown.get(club);
            List<Fixture> fixtures = reason != null ? List.of()
                    : counted.getOrDefault(club, List.of()).stream()
                            .sorted(IN_PLAYING_ORDER).toList();
            windows.put(club, new ClubWindow(club, fixtures, reason));
        }
        return windows;
    }

    /**
     * Why an in-range, outstanding fixture cannot be counted, or null when it can. An unresolved
     * or already-elapsed kickoff with both flags still false is unknown scheduling, not a future
     * fixture; a kickoff at or before its own round's deadline contradicts the assignment.
     */
    private static UnknownReason schedulingProblem(ScheduleSource.FixtureRow row, Instant asOf,
                                                   Instant deadline) {
        if (row.kickoff() == null) {
            return UnknownReason.UNRESOLVED_KICKOFF;
        }
        if (!row.kickoff().isAfter(asOf)) {
            return UnknownReason.ELAPSED_KICKOFF;
        }
        if (!row.kickoff().isAfter(deadline)) {
            return UnknownReason.KICKOFF_BEFORE_DEADLINE;
        }
        return null;
    }

    /** The first reason a club met wins; it keeps no fixtures either way. */
    private static void markUnknown(Map<Integer, UnknownReason> unknown,
                                    ScheduleSource.FixtureRow row, UnknownReason reason) {
        unknown.putIfAbsent(row.homeTeamId(), reason);
        unknown.putIfAbsent(row.awayTeamId(), reason);
    }
}
