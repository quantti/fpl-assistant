package dev.quantti.fplassistant.schedule;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import dev.quantti.fplassistant.testsupport.RankingSeed;
import dev.quantti.fplassistant.tools.UkTime;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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

    private static List<ScheduleSource.FixtureRow> movedTo(
            List<ScheduleSource.FixtureRow> rows, int fixtureId, int gameweek, Instant kickoff) {
        return rows.stream().map(row -> row.id() != fixtureId ? row
                : new ScheduleSource.FixtureRow(row.id(), gameweek, row.homeTeamId(),
                        row.awayTeamId(), kickoff, row.started(), row.finished(),
                        row.homeDifficulty(), row.awayDifficulty(), row.capturedAt())).toList();
    }

    private static int fixtureFor(List<ScheduleSource.FixtureRow> rows, int club, int gameweek) {
        return rows.stream()
                .filter(row -> row.gameweek() != null && row.gameweek() == gameweek)
                .filter(row -> row.homeTeamId() == club || row.awayTeamId() == club)
                .mapToInt(ScheduleSource.FixtureRow::id).findFirst().orElseThrow();
    }

    /** The 02:10 UK capture of the day {@code asOf} falls on, so the view is fresh for it. */
    private static Instant capturedFor(Instant asOf) {
        return LocalDate.ofInstant(asOf, UkTime.zone()).atTime(4, 0)
                .atZone(UkTime.zone()).toInstant();
    }
}
