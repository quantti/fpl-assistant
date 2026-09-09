package dev.quantti.fplassistant.schedule;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

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
            if (known != (startGameweek != null && endGameweek != null)) {
                throw new IllegalArgumentException(
                        "a gameweek range exists exactly when the horizon is known");
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

    private HorizonFixtures() {
    }

    /**
     * @param requestedGameweeks already normalised by the caller; must be at least 1
     */
    public static Result derive(ScheduleSource.View view, Instant asOf, String captureSeason,
                                int requestedGameweeks) {
        throw new UnsupportedOperationException("KAR-35 Task 2");
    }
}
