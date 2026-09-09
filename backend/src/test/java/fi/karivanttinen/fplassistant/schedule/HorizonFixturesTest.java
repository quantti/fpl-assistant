package fi.karivanttinen.fplassistant.schedule;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;

import fi.karivanttinen.fplassistant.testsupport.RankingSeed;
import fi.karivanttinen.fplassistant.tools.UkTime;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * The calendar-gameweek window, over a structurally complete captured season. Doubles and blanks
 * are made by moving a fixture between gameweeks, never by dropping a row: a view missing a
 * fixture is an incomplete capture, which is a different question.
 */
class HorizonFixturesTest {

    private static final String SEASON = RankingSeed.VIEW_SEASON;
    private static final Instant FIRST_DEADLINE = Instant.parse("2026-08-14T17:30:00Z");
    private static final Instant AS_OF = Instant.parse("2026-08-29T12:00:00Z");
    private static final Instant CAPTURED = Instant.parse("2026-08-29T02:10:00Z");

    /** Gameweek 4 is the first round still editable at {@link #AS_OF}. */
    private static final Instant GW4_DEADLINE = FIRST_DEADLINE.plus(21, ChronoUnit.DAYS);

    /** After every gameweek-6 Saturday kickoff, so the second leg of the double sorts last. */
    private static final Instant SECOND_LEG_KICKOFF = Instant.parse("2026-09-20T19:00:00Z");

    /** A day after the gameweek-10 deadline, where the blanked fixture is replayed. */
    private static final Instant REARRANGED_KICKOFF = Instant.parse("2026-10-17T14:00:00Z");

    /** Midweek after the gameweek-4 deadline, where a gameweek-4 double is replayed. */
    private static final Instant GW4_REPLAY_KICKOFF = Instant.parse("2026-09-08T19:00:00Z");

    @Test
    void aDoubleGameweekAddsAFixtureWithoutLosingTheLastGameweek() {
        int club = RankingSeed.team(1);
        List<ScheduleSource.FixtureRow> seeded = RankingSeed.fixtures(CAPTURED, FIRST_DEADLINE);
        int secondLeg = fixtureFor(seeded, club, 10);
        ScheduleSource.View view = view(CAPTURED,
                movedTo(seeded, secondLeg, 6, SECOND_LEG_KICKOFF));

        HorizonFixtures.Result result = HorizonFixtures.derive(view, AS_OF, SEASON, 5);

        assertThat(result.status()).isEqualTo(HorizonFixtures.Status.KNOWN);
        assertThat(result.reason()).isNull();
        assertThat(result.startGameweek()).isEqualTo(4);
        assertThat(result.endGameweek()).isEqualTo(8);
        assertThat(result.actualGameweeks()).isEqualTo(5);
        assertThat(result.truncated()).isFalse();

        HorizonFixtures.ClubWindow window = result.club(club);
        assertThat(window.known()).isTrue();
        assertThat(window.count()).isEqualTo(6);
        assertThat(window.fixtures()).extracting(HorizonFixtures.Fixture::id).containsExactly(
                fixtureFor(seeded, club, 4), fixtureFor(seeded, club, 5),
                fixtureFor(seeded, club, 6), secondLeg,
                fixtureFor(seeded, club, 7), fixtureFor(seeded, club, 8));
        assertThat(window.fixtures()).extracting(HorizonFixtures.Fixture::gameweek)
                .containsExactly(4, 5, 6, 6, 7, 8);
        assertThat(window.fixtures()).extracting(HorizonFixtures.Fixture::id)
                .doesNotContain(fixtureFor(seeded, club, 9));
    }

    @Test
    void aBlankGameweekShortensTheListWithoutExtendingTheWindow() {
        int club = RankingSeed.team(2);
        List<ScheduleSource.FixtureRow> seeded = RankingSeed.fixtures(CAPTURED, FIRST_DEADLINE);
        int blanked = fixtureFor(seeded, club, 7);
        ScheduleSource.View view = view(CAPTURED,
                movedTo(seeded, blanked, 10, REARRANGED_KICKOFF));

        HorizonFixtures.Result result = HorizonFixtures.derive(view, AS_OF, SEASON, 5);

        assertThat(result.startGameweek()).isEqualTo(4);
        assertThat(result.endGameweek()).isEqualTo(8);
        assertThat(result.actualGameweeks()).isEqualTo(5);
        assertThat(result.truncated()).isFalse();

        HorizonFixtures.ClubWindow window = result.club(club);
        assertThat(window.count()).isEqualTo(4);
        assertThat(window.fixtures()).extracting(HorizonFixtures.Fixture::id).containsExactly(
                fixtureFor(seeded, club, 4), fixtureFor(seeded, club, 5),
                fixtureFor(seeded, club, 6), fixtureFor(seeded, club, 8));
        assertThat(window.fixtures()).extracting(HorizonFixtures.Fixture::id)
                .doesNotContain(blanked, fixtureFor(seeded, club, 9));
    }

    @Test
    void theWindowOpensAtTheFirstDeadlineStrictlyAfterAsOf() {
        Instant captured = capturedFor(GW4_DEADLINE);
        ScheduleSource.View view = view(captured,
                RankingSeed.fixtures(captured, FIRST_DEADLINE));

        assertThat(HorizonFixtures.derive(view, GW4_DEADLINE.minusSeconds(1), SEASON, 5))
                .extracting(HorizonFixtures.Result::startGameweek,
                        HorizonFixtures.Result::endGameweek)
                .containsExactly(4, 8);
        assertThat(HorizonFixtures.derive(view, GW4_DEADLINE, SEASON, 5))
                .extracting(HorizonFixtures.Result::startGameweek,
                        HorizonFixtures.Result::endGameweek)
                .containsExactly(5, 9);
        assertThat(HorizonFixtures.derive(view, GW4_DEADLINE.plusSeconds(1), SEASON, 5))
                .extracting(HorizonFixtures.Result::startGameweek,
                        HorizonFixtures.Result::endGameweek)
                .containsExactly(5, 9);
    }

    @Test
    void aRemainingMatchOfTheJustLockedRoundIsNotCounted() {
        int club = RankingSeed.team(1);
        Instant captured = capturedFor(GW4_DEADLINE);
        List<ScheduleSource.FixtureRow> seeded = RankingSeed.fixtures(captured, FIRST_DEADLINE);
        int stillToKickOff = fixtureFor(seeded, club, 4);
        ScheduleSource.View view = view(captured, movedTo(seeded, stillToKickOff, 4,
                GW4_DEADLINE.plus(90, ChronoUnit.MINUTES)));

        HorizonFixtures.Result before =
                HorizonFixtures.derive(view, GW4_DEADLINE.minusSeconds(1), SEASON, 5);
        assertThat(before.startGameweek()).isEqualTo(4);
        assertThat(before.club(club).fixtures()).extracting(HorizonFixtures.Fixture::id)
                .contains(stillToKickOff);

        HorizonFixtures.Result locked = HorizonFixtures.derive(view, GW4_DEADLINE, SEASON, 5);
        assertThat(locked.startGameweek()).isEqualTo(5);
        assertThat(locked.club(club).fixtures()).extracting(HorizonFixtures.Fixture::id)
                .doesNotContain(stillToKickOff);
        assertThat(locked.club(club).fixtures()).extracting(HorizonFixtures.Fixture::gameweek)
                .containsExactly(5, 6, 7, 8, 9);
    }

    @Test
    void theSeasonEndTruncatesTheWindowAndThenLeavesNoEditableGameweek() {
        Instant lastEditable = FIRST_DEADLINE.plus(7L * 36, ChronoUnit.DAYS).minusSeconds(1);
        Instant captured = capturedFor(lastEditable);

        HorizonFixtures.Result truncated = HorizonFixtures.derive(
                view(captured, RankingSeed.fixtures(captured, FIRST_DEADLINE)),
                lastEditable, SEASON, 5);

        assertThat(truncated.status()).isEqualTo(HorizonFixtures.Status.KNOWN);
        assertThat(truncated.startGameweek()).isEqualTo(37);
        assertThat(truncated.endGameweek()).isEqualTo(38);
        assertThat(truncated.actualGameweeks()).isEqualTo(2);
        assertThat(truncated.truncated()).isTrue();

        Instant afterTheLastDeadline =
                FIRST_DEADLINE.plus(7L * 37, ChronoUnit.DAYS).plusSeconds(1);
        Instant lastCapture = capturedFor(afterTheLastDeadline);

        HorizonFixtures.Result over = HorizonFixtures.derive(
                view(lastCapture, RankingSeed.fixtures(lastCapture, FIRST_DEADLINE)),
                afterTheLastDeadline, SEASON, 5);

        assertThat(over.status()).isEqualTo(HorizonFixtures.Status.NO_EDITABLE_GAMEWEEK);
        assertThat(over.reason()).isEqualTo(HorizonFixtures.UnknownReason.NO_EDITABLE_GAMEWEEK);
        assertThat(over.startGameweek()).isNull();
        assertThat(over.endGameweek()).isNull();
        assertThat(over.actualGameweeks()).isZero();
        assertThat(over.clubs()).isEmpty();
        assertThat(over.club(RankingSeed.team(1)).unknownReason())
                .isEqualTo(HorizonFixtures.UnknownReason.NO_EDITABLE_GAMEWEEK);
    }

    @Test
    void everyCapturedClubHasAWindowAndNoOtherIdDoes() {
        ScheduleSource.View view = view(CAPTURED, RankingSeed.fixtures(CAPTURED, FIRST_DEADLINE));

        HorizonFixtures.Result result = HorizonFixtures.derive(view, AS_OF, SEASON, 5);

        assertThat(result.source()).contains(new ScheduleSource.Snapshot(SEASON, CAPTURED));
        assertThat(result.clubs().keySet()).containsExactlyInAnyOrderElementsOf(
                IntStream.rangeClosed(1, 20).mapToObj(RankingSeed::team).toList());
        assertThat(result.clubs().values()).allMatch(HorizonFixtures.ClubWindow::known);
        assertThat(result.club(RankingSeed.team(1)).count()).isEqualTo(5);

        HorizonFixtures.ClubWindow foreign = result.club(RankingSeed.TEAM_BASE + 99);
        assertThat(foreign.known()).isFalse();
        assertThat(foreign.unknownReason())
                .isEqualTo(HorizonFixtures.UnknownReason.CLUB_NOT_IN_SCHEDULE);
        assertThat(foreign.fixtures()).isEmpty();
        assertThatIllegalStateException().isThrownBy(foreign::count);
    }

    /**
     * Rule 2 before rule 3: an outstanding fixture with no gameweek is unplaced everywhere, so it
     * cannot be assumed to fall outside the window however far away it was last assigned.
     */
    @Test
    void anOutstandingFixtureWithNoGameweekMakesBothItsClubsUnknown() {
        int club = RankingSeed.team(3);
        List<ScheduleSource.FixtureRow> seeded = RankingSeed.fixtures(CAPTURED, FIRST_DEADLINE);
        int unplaced = fixtureFor(seeded, club, 20);
        int opponent = opponentOf(seeded, unplaced, club);
        ScheduleSource.View view = view(CAPTURED, unassigned(seeded, unplaced));

        HorizonFixtures.Result result = HorizonFixtures.derive(view, AS_OF, SEASON, 5);

        assertThat(result.status()).isEqualTo(HorizonFixtures.Status.KNOWN);
        assertThat(result.club(club).known()).isFalse();
        assertThat(result.club(club).unknownReason())
                .isEqualTo(HorizonFixtures.UnknownReason.UNASSIGNED_FIXTURE);
        assertThat(result.club(club).fixtures()).isEmpty();
        assertThat(result.club(opponent).unknownReason())
                .isEqualTo(HorizonFixtures.UnknownReason.UNASSIGNED_FIXTURE);
        assertThat(result.clubs().values().stream()
                .filter(HorizonFixtures.ClubWindow::known)).hasSize(18);
    }

    @Test
    void aStartedFixtureIsIgnoredEvenWhenItsTimestampsSayOtherwise() {
        int club = RankingSeed.team(4);
        List<ScheduleSource.FixtureRow> seeded = RankingSeed.fixtures(CAPTURED, FIRST_DEADLINE);
        int underway = fixtureFor(seeded, club, 5);
        int opponent = opponentOf(seeded, underway, club);
        ScheduleSource.View view = view(CAPTURED,
                played(seeded, underway, AS_OF.plus(200, ChronoUnit.DAYS)));

        HorizonFixtures.Result result = HorizonFixtures.derive(view, AS_OF, SEASON, 5);

        assertThat(result.endGameweek()).isEqualTo(8);
        assertThat(result.club(club).known()).isTrue();
        assertThat(result.club(club).count()).isEqualTo(4);
        assertThat(result.club(club).fixtures()).extracting(HorizonFixtures.Fixture::gameweek)
                .containsExactly(4, 6, 7, 8);
        assertThat(result.club(club).fixtures()).extracting(HorizonFixtures.Fixture::id)
                .doesNotContain(underway);
        assertThat(result.club(opponent).count()).isEqualTo(4);
    }

    /** Rule 1 before rule 2: a played fixture is out of the reckoning, gameweek or no gameweek. */
    @Test
    void aPlayedFixtureWithNoGameweekIsIgnoredRatherThanUnknown() {
        int club = RankingSeed.team(5);
        List<ScheduleSource.FixtureRow> seeded = RankingSeed.fixtures(CAPTURED, FIRST_DEADLINE);
        int withdrawn = fixtureFor(seeded, club, 6);
        ScheduleSource.View view = view(CAPTURED,
                unassigned(played(seeded, withdrawn, AS_OF.plus(200, ChronoUnit.DAYS)), withdrawn));

        HorizonFixtures.Result result = HorizonFixtures.derive(view, AS_OF, SEASON, 5);

        assertThat(result.club(club).known()).isTrue();
        assertThat(result.club(club).fixtures()).extracting(HorizonFixtures.Fixture::gameweek)
                .containsExactly(4, 5, 7, 8);
    }

    @Test
    void anUnresolvedKickoffInsideTheWindowMakesTheClubUnknown() {
        int club = RankingSeed.team(6);
        List<ScheduleSource.FixtureRow> seeded = RankingSeed.fixtures(CAPTURED, FIRST_DEADLINE);
        int undated = fixtureFor(seeded, club, 6);
        int opponent = opponentOf(seeded, undated, club);
        ScheduleSource.View view = view(CAPTURED, withKickoff(seeded, undated, null));

        HorizonFixtures.Result result = HorizonFixtures.derive(view, AS_OF, SEASON, 5);

        assertThat(result.club(club).unknownReason())
                .isEqualTo(HorizonFixtures.UnknownReason.UNRESOLVED_KICKOFF);
        assertThat(result.club(club).fixtures()).isEmpty();
        assertThat(result.club(opponent).unknownReason())
                .isEqualTo(HorizonFixtures.UnknownReason.UNRESOLVED_KICKOFF);
        assertThat(result.clubs().values().stream()
                .filter(HorizonFixtures.ClubWindow::known)).hasSize(18);
    }

    /**
     * Rule 4 in its own order: an elapsed kickoff whose {@code finished} flag has not flipped is
     * unknown completion, and stays that reason even though such a kickoff also precedes its
     * round's deadline.
     */
    @Test
    void anElapsedKickoffWithBothFlagsFalseMakesTheClubUnknown() {
        int club = RankingSeed.team(7);
        List<ScheduleSource.FixtureRow> seeded = RankingSeed.fixtures(CAPTURED, FIRST_DEADLINE);
        int overdue = fixtureFor(seeded, club, 4);
        int opponent = opponentOf(seeded, overdue, club);

        HorizonFixtures.Result atTheKickoff = HorizonFixtures.derive(
                view(CAPTURED, withKickoff(seeded, overdue, AS_OF)), AS_OF, SEASON, 5);
        assertThat(atTheKickoff.club(club).unknownReason())
                .isEqualTo(HorizonFixtures.UnknownReason.ELAPSED_KICKOFF);
        assertThat(atTheKickoff.club(opponent).unknownReason())
                .isEqualTo(HorizonFixtures.UnknownReason.ELAPSED_KICKOFF);

        HorizonFixtures.Result afterIt = HorizonFixtures.derive(
                view(CAPTURED, withKickoff(seeded, overdue, AS_OF.minusSeconds(1))),
                AS_OF, SEASON, 5);
        assertThat(afterIt.club(club).unknownReason())
                .isEqualTo(HorizonFixtures.UnknownReason.ELAPSED_KICKOFF);
        assertThat(afterIt.club(club).fixtures()).isEmpty();
    }

    @Test
    void aKickoffNotAfterItsOwnRoundsDeadlineMakesTheClubUnknown() {
        int club = RankingSeed.team(8);
        List<ScheduleSource.FixtureRow> seeded = RankingSeed.fixtures(CAPTURED, FIRST_DEADLINE);
        int contradicted = fixtureFor(seeded, club, 5);
        int opponent = opponentOf(seeded, contradicted, club);

        HorizonFixtures.Result atTheDeadline = HorizonFixtures.derive(
                view(CAPTURED, withKickoff(seeded, contradicted, deadline(5))), AS_OF, SEASON, 5);
        assertThat(atTheDeadline.club(club).unknownReason())
                .isEqualTo(HorizonFixtures.UnknownReason.KICKOFF_BEFORE_DEADLINE);
        assertThat(atTheDeadline.club(club).fixtures()).isEmpty();
        assertThat(atTheDeadline.club(opponent).unknownReason())
                .isEqualTo(HorizonFixtures.UnknownReason.KICKOFF_BEFORE_DEADLINE);

        HorizonFixtures.Result justBeforeIt = HorizonFixtures.derive(
                view(CAPTURED, withKickoff(seeded, contradicted, deadline(5).minusSeconds(1))),
                AS_OF, SEASON, 5);
        assertThat(justBeforeIt.club(club).unknownReason())
                .isEqualTo(HorizonFixtures.UnknownReason.KICKOFF_BEFORE_DEADLINE);
        assertThat(justBeforeIt.clubs().values().stream()
                .filter(HorizonFixtures.ClubWindow::known)).hasSize(18);
    }

    @Test
    void anUnratedFixtureStillCountsButLeavesNoAverageDifficulty() {
        int club = RankingSeed.team(1);
        List<ScheduleSource.FixtureRow> seeded = RankingSeed.fixtures(CAPTURED, FIRST_DEADLINE);
        int replay = fixtureFor(seeded, club, 10);
        List<ScheduleSource.FixtureRow> doubled =
                movedTo(seeded, replay, 4, GW4_REPLAY_KICKOFF);

        HorizonFixtures.Result rated =
                HorizonFixtures.derive(view(CAPTURED, doubled), AS_OF, SEASON, 1);
        assertThat(rated.endGameweek()).isEqualTo(4);
        assertThat(rated.club(club).count()).isEqualTo(2);
        assertThat(rated.club(club).averageDifficulty()).hasValue(3.0);

        HorizonFixtures.Result unrated = HorizonFixtures.derive(
                view(CAPTURED, withDifficulty(doubled, replay, null, null)), AS_OF, SEASON, 1);
        assertThat(unrated.club(club).count()).isEqualTo(2);
        assertThat(unrated.club(club).fixtures()).extracting(HorizonFixtures.Fixture::id)
                .contains(replay);
        assertThat(unrated.club(club).averageDifficulty()).isEmpty();

        HorizonFixtures.Result outOfRange = HorizonFixtures.derive(
                view(CAPTURED, withDifficulty(doubled, replay, 6, 6)), AS_OF, SEASON, 1);
        assertThat(outOfRange.club(club).count()).isEqualTo(2);
        assertThat(outOfRange.club(club).averageDifficulty()).isEmpty();
    }

    @Test
    void aMissingCaptureLeavesNoHorizonAtAll() {
        ScheduleSource.View view = new ScheduleSource.View(Optional.empty(), List.of(), List.of(),
                new ScheduleSource.Completeness(false, ScheduleSource.CompletenessReason.NO_CAPTURE));

        HorizonFixtures.Result result = HorizonFixtures.derive(view, AS_OF, SEASON, 5);

        assertThat(result.status()).isEqualTo(HorizonFixtures.Status.UNKNOWN);
        assertThat(result.reason()).isEqualTo(HorizonFixtures.UnknownReason.NO_CAPTURE);
        assertThat(result.source()).isEmpty();
        assertThat(result.startGameweek()).isNull();
        assertThat(result.endGameweek()).isNull();
        assertThat(result.actualGameweeks()).isZero();
        assertThat(result.truncated()).isFalse();
        assertThat(result.clubs()).isEmpty();
        assertThat(result.club(RankingSeed.team(1)).unknownReason())
                .isEqualTo(HorizonFixtures.UnknownReason.NO_CAPTURE);
    }

    @Test
    void aPartialCaptureLeavesNoHorizonAtAll() {
        Optional<ScheduleSource.Snapshot> snapshot =
                Optional.of(new ScheduleSource.Snapshot(SEASON, CAPTURED));
        List<ScheduleSource.EventRow> events = RankingSeed.events(CAPTURED, FIRST_DEADLINE);
        List<ScheduleSource.FixtureRow> seeded = RankingSeed.fixtures(CAPTURED, FIRST_DEADLINE);
        int missing = fixtureFor(seeded, RankingSeed.team(1), 6);
        List<ScheduleSource.FixtureRow> partial =
                seeded.stream().filter(row -> row.id() != missing).toList();
        ScheduleSource.Completeness completeness =
                ScheduleSource.assess(snapshot, events, partial);
        assertThat(completeness.reason())
                .isEqualTo(ScheduleSource.CompletenessReason.INVALID_FIXTURE_COUNT);

        HorizonFixtures.Result result = HorizonFixtures.derive(
                new ScheduleSource.View(snapshot, events, partial, completeness),
                AS_OF, SEASON, 5);

        assertThat(result.status()).isEqualTo(HorizonFixtures.Status.UNKNOWN);
        assertThat(result.reason()).isEqualTo(HorizonFixtures.UnknownReason.SCHEDULE_INCOMPLETE);
        assertThat(result.clubs()).isEmpty();
        assertThat(result.club(RankingSeed.team(1)).unknownReason())
                .isEqualTo(HorizonFixtures.UnknownReason.SCHEDULE_INCOMPLETE);
    }

    /** The 02:10 UK capture is due by 03:00 UK: before that the previous day's is the current one. */
    @Test
    void aCaptureFromTheWrongDayIsStale() {
        Instant yesterday = CAPTURED.minus(1, ChronoUnit.DAYS);
        ScheduleSource.View yesterdays =
                view(yesterday, RankingSeed.fixtures(yesterday, FIRST_DEADLINE));

        HorizonFixtures.Result stale = HorizonFixtures.derive(yesterdays, AS_OF, SEASON, 5);
        assertThat(stale.status()).isEqualTo(HorizonFixtures.Status.UNKNOWN);
        assertThat(stale.reason()).isEqualTo(HorizonFixtures.UnknownReason.CAPTURE_STALE);
        assertThat(stale.startGameweek()).isNull();
        assertThat(stale.clubs()).isEmpty();

        Instant beforeTodaysRun = Instant.parse("2026-08-29T01:30:00Z");
        assertThat(HorizonFixtures.derive(yesterdays, beforeTodaysRun, SEASON, 5))
                .extracting(HorizonFixtures.Result::status, HorizonFixtures.Result::startGameweek)
                .containsExactly(HorizonFixtures.Status.KNOWN, 4);
        assertThat(HorizonFixtures.derive(
                        view(CAPTURED, RankingSeed.fixtures(CAPTURED, FIRST_DEADLINE)),
                        beforeTodaysRun, SEASON, 5).reason())
                .isEqualTo(HorizonFixtures.UnknownReason.CAPTURE_STALE);
    }

    /** FPL renumbers ids every season, so a capture of another season describes other clubs. */
    @Test
    void aCaptureOfAnotherSeasonIsNotThisSeasonsHorizon() {
        ScheduleSource.View view = view(CAPTURED, RankingSeed.fixtures(CAPTURED, FIRST_DEADLINE));

        HorizonFixtures.Result result =
                HorizonFixtures.derive(view, AS_OF, SEASON + "-REUSED", 5);

        assertThat(result.status()).isEqualTo(HorizonFixtures.Status.UNKNOWN);
        assertThat(result.reason()).isEqualTo(HorizonFixtures.UnknownReason.SEASON_MISMATCH);
        assertThat(result.startGameweek()).isNull();
        assertThat(result.clubs()).isEmpty();
        assertThat(result.club(RankingSeed.team(1)).unknownReason())
                .isEqualTo(HorizonFixtures.UnknownReason.SEASON_MISMATCH);
    }

    @Test
    void anUnboundedRequestStopsAtTheFinalGameweekWithoutOverflowing() {
        ScheduleSource.View view = view(CAPTURED, RankingSeed.fixtures(CAPTURED, FIRST_DEADLINE));

        HorizonFixtures.Result result =
                HorizonFixtures.derive(view, AS_OF, SEASON, Integer.MAX_VALUE);

        assertThat(result.startGameweek()).isEqualTo(4);
        assertThat(result.endGameweek()).isEqualTo(38);
        assertThat(result.actualGameweeks()).isEqualTo(35);
        assertThat(result.truncated()).isTrue();
        assertThat(result.club(RankingSeed.team(1)).count()).isEqualTo(35);
    }

    /** A half-known result is not a state a consumer should have to defend against. */
    @Test
    void anUnknownResultCarriesNeitherHalfARangeNorClubWindows() {
        assertThatIllegalArgumentException().isThrownBy(() -> new HorizonFixtures.Result(
                AS_OF, Optional.empty(), 5, HorizonFixtures.Status.UNKNOWN,
                HorizonFixtures.UnknownReason.NO_CAPTURE, 4, null, Map.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new HorizonFixtures.Result(
                AS_OF, Optional.empty(), 5, HorizonFixtures.Status.UNKNOWN,
                HorizonFixtures.UnknownReason.NO_CAPTURE, null, null,
                Map.of(RankingSeed.team(1),
                        new HorizonFixtures.ClubWindow(RankingSeed.team(1), List.of(), null))));
    }

    @Test
    void aRequestOfFewerThanOneGameweekIsRejected() {
        ScheduleSource.View view = view(CAPTURED, RankingSeed.fixtures(CAPTURED, FIRST_DEADLINE));

        assertThatIllegalArgumentException().isThrownBy(
                () -> HorizonFixtures.derive(view, AS_OF, SEASON, 0));
        assertThatIllegalArgumentException().isThrownBy(
                () -> HorizonFixtures.derive(view, AS_OF, SEASON, -3));
    }

    private static ScheduleSource.View view(Instant capturedAt,
                                            List<ScheduleSource.FixtureRow> fixtures) {
        Optional<ScheduleSource.Snapshot> snapshot =
                Optional.of(new ScheduleSource.Snapshot(SEASON, capturedAt));
        List<ScheduleSource.EventRow> events = RankingSeed.events(capturedAt, FIRST_DEADLINE);
        ScheduleSource.Completeness completeness =
                ScheduleSource.assess(snapshot, events, fixtures);
        assertThat(completeness.complete())
                .as("moving fixtures between gameweeks must keep the capture structurally complete")
                .isTrue();
        return new ScheduleSource.View(snapshot, events, fixtures, completeness);
    }

    private static List<ScheduleSource.FixtureRow> replaced(
            List<ScheduleSource.FixtureRow> rows, int fixtureId,
            UnaryOperator<ScheduleSource.FixtureRow> change) {
        return rows.stream().map(row -> row.id() == fixtureId ? change.apply(row) : row).toList();
    }

    private static List<ScheduleSource.FixtureRow> movedTo(
            List<ScheduleSource.FixtureRow> rows, int fixtureId, int gameweek, Instant kickoff) {
        return replaced(rows, fixtureId, row -> new ScheduleSource.FixtureRow(row.id(), gameweek,
                row.homeTeamId(), row.awayTeamId(), kickoff, row.started(), row.finished(),
                row.homeDifficulty(), row.awayDifficulty(), row.capturedAt()));
    }

    private static List<ScheduleSource.FixtureRow> withKickoff(
            List<ScheduleSource.FixtureRow> rows, int fixtureId, Instant kickoff) {
        return replaced(rows, fixtureId, row -> new ScheduleSource.FixtureRow(row.id(),
                row.gameweek(), row.homeTeamId(), row.awayTeamId(), kickoff, row.started(),
                row.finished(), row.homeDifficulty(), row.awayDifficulty(), row.capturedAt()));
    }

    private static List<ScheduleSource.FixtureRow> withDifficulty(
            List<ScheduleSource.FixtureRow> rows, int fixtureId, Integer home, Integer away) {
        return replaced(rows, fixtureId, row -> new ScheduleSource.FixtureRow(row.id(),
                row.gameweek(), row.homeTeamId(), row.awayTeamId(), row.kickoff(), row.started(),
                row.finished(), home, away, row.capturedAt()));
    }

    /** The fixture as FPL leaves it when it withdraws the assignment: still outstanding. */
    private static List<ScheduleSource.FixtureRow> unassigned(
            List<ScheduleSource.FixtureRow> rows, int fixtureId) {
        return replaced(rows, fixtureId, row -> new ScheduleSource.FixtureRow(row.id(), null,
                row.homeTeamId(), row.awayTeamId(), row.kickoff(), row.started(), row.finished(),
                row.homeDifficulty(), row.awayDifficulty(), row.capturedAt()));
    }

    /** Started and finished, with a kickoff the flags contradict. */
    private static List<ScheduleSource.FixtureRow> played(
            List<ScheduleSource.FixtureRow> rows, int fixtureId, Instant kickoff) {
        return replaced(rows, fixtureId, row -> new ScheduleSource.FixtureRow(row.id(),
                row.gameweek(), row.homeTeamId(), row.awayTeamId(), kickoff, true, true,
                row.homeDifficulty(), row.awayDifficulty(), row.capturedAt()));
    }

    private static int fixtureFor(List<ScheduleSource.FixtureRow> rows, int club, int gameweek) {
        return rows.stream()
                .filter(row -> row.gameweek() != null && row.gameweek() == gameweek)
                .filter(row -> row.homeTeamId() == club || row.awayTeamId() == club)
                .mapToInt(ScheduleSource.FixtureRow::id).findFirst().orElseThrow();
    }

    private static int opponentOf(List<ScheduleSource.FixtureRow> rows, int fixtureId, int club) {
        ScheduleSource.FixtureRow row = rows.stream().filter(candidate -> candidate.id() == fixtureId)
                .findFirst().orElseThrow();
        return row.homeTeamId() == club ? row.awayTeamId() : row.homeTeamId();
    }

    private static Instant deadline(int gameweek) {
        return FIRST_DEADLINE.plus(7L * (gameweek - 1), ChronoUnit.DAYS);
    }

    /** The 02:10 UK capture of the day {@code asOf} falls on, so the view is fresh for it. */
    private static Instant capturedFor(Instant asOf) {
        return LocalDate.ofInstant(asOf, UkTime.zone()).atTime(2, 10)
                .atZone(UkTime.zone()).toInstant();
    }
}
