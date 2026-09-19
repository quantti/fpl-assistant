package fi.karivanttinen.fplassistant.user;

import java.time.Instant;
import java.util.List;

import fi.karivanttinen.fplassistant.schedule.ScheduleSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The window rule itself, which is arithmetic over a list of deadlines. No Spring context: a
 * database would add minutes to the run and nothing to the question.
 */
class QuotaWindowTest {

    private static final int GAMEWEEK_LIMIT = 20;
    private static final int OFF_SEASON_LIMIT = 100;

    private static ScheduleSource.Deadline deadline(String season, int gameweek, String at) {
        return new ScheduleSource.Deadline(season, gameweek, Instant.parse(at));
    }

    /** GW12 closes on the 19th, GW13 on the 26th; both belong to the season being played. */
    private static List<ScheduleSource.Deadline> twoRounds() {
        return List.of(deadline("2097/98", 12, "2097-09-19T10:30:00Z"),
                deadline("2097/98", 13, "2097-09-26T10:30:00Z"));
    }

    private static QuotaWindow derive(List<ScheduleSource.Deadline> deadlines, String asOf) {
        return QuotaWindows.derive(deadlines, Instant.parse(asOf), GAMEWEEK_LIMIT,
                OFF_SEASON_LIMIT);
    }

    @Test
    void midRoundIsAGameweekWindowEndingAtTheNextDeadline() {
        QuotaWindow window = derive(twoRounds(), "2097-09-22T09:00:00Z");

        assertThat(window.kind()).isEqualTo(QuotaWindow.Kind.GAMEWEEK);
        assertThat(window.from()).isEqualTo(Instant.parse("2097-09-19T10:30:00Z"));
        assertThat(window.until()).isEqualTo(Instant.parse("2097-09-26T10:30:00Z"));
        assertThat(window.gameweek()).isEqualTo(13);
        assertThat(window.season()).isEqualTo("2097/98");
        assertThat(window.limit()).isEqualTo(GAMEWEEK_LIMIT);
    }

    /** At the deadline instant the round is already locked, so that instant opens the next window. */
    @Test
    void theDeadlineInstantItselfOpensTheNewWindow() {
        QuotaWindow window = derive(twoRounds(), "2097-09-19T10:30:00Z");

        assertThat(window.from()).isEqualTo(Instant.parse("2097-09-19T10:30:00Z"));
        assertThat(window.gameweek()).isEqualTo(13);
    }

    @Test
    void theGapBetweenTwoSeasonsIsOneOffSeasonWindow() {
        List<ScheduleSource.Deadline> across = List.of(
                deadline("2096/97", 38, "2097-05-24T13:00:00Z"),
                deadline("2097/98", 1, "2097-08-14T17:30:00Z"));

        QuotaWindow window = derive(across, "2097-07-01T00:00:00Z");

        assertThat(window.kind()).isEqualTo(QuotaWindow.Kind.OFF_SEASON);
        assertThat(window.from()).isEqualTo(Instant.parse("2097-05-24T13:00:00Z"));
        assertThat(window.until()).isEqualTo(Instant.parse("2097-08-14T17:30:00Z"));
        assertThat(window.limit()).isEqualTo(OFF_SEASON_LIMIT);
        assertThat(window.gameweek()).isNull();
    }

    /**
     * Before the first deadline anyone captured there is no instant the window began at, and an
     * unbounded count would charge the account for every message it has ever sent — months of
     * history against one off-season allowance. Nothing is known, so nothing is claimed: this is
     * the fallback, the same answer as no schedule at all.
     */
    @Test
    void beforeTheFirstDeadlineEverCapturedThereIsNoWindowToCount() {
        QuotaWindow window = derive(twoRounds(), "2097-08-01T00:00:00Z");

        assertThat(window.kind()).isEqualTo(QuotaWindow.Kind.FALLBACK);
        assertThat(window.from()).isNull();
        assertThat(window.until()).isNull();
        assertThat(window.limit()).isEqualTo(GAMEWEEK_LIMIT);
    }

    /**
     * The changeover gap is a different case and stays an off-season window: the previous
     * season's last deadline is a real instant to start counting from.
     */
    @Test
    void theOffSeasonGapIsBoundedByTheSeasonThatEnded() {
        List<ScheduleSource.Deadline> across = List.of(
                deadline("2096/97", 38, "2097-05-24T13:00:00Z"),
                deadline("2097/98", 1, "2097-08-14T17:30:00Z"));

        assertThat(derive(across, "2097-07-01T00:00:00Z").from())
                .isEqualTo(Instant.parse("2097-05-24T13:00:00Z"));
    }

    @Test
    void afterTheLastCapturedDeadlineTheWindowIsUnboundedForwards() {
        QuotaWindow window = derive(twoRounds(), "2098-06-01T00:00:00Z");

        assertThat(window.kind()).isEqualTo(QuotaWindow.Kind.OFF_SEASON);
        assertThat(window.from()).isEqualTo(Instant.parse("2097-09-26T10:30:00Z"));
        assertThat(window.until()).isNull();
    }

    @Test
    void noDeadlinesAtAllIsTheFallbackAtTheGameweekLimit() {
        QuotaWindow window = derive(List.of(), "2097-09-22T09:00:00Z");

        assertThat(window.kind()).isEqualTo(QuotaWindow.Kind.FALLBACK);
        assertThat(window.limit()).isEqualTo(GAMEWEEK_LIMIT);
        assertThat(window.from()).isNull();
        assertThat(window.until()).isNull();
        assertThat(window.gameweek()).isNull();
    }

    /**
     * A schedule read that blows up is a missing capture, not an outage. {@code instantOf} throws
     * {@link IllegalStateException} rather than a {@code DataAccessException} when a timestamp
     * column comes back as an unexpected type, and this runs inside every chat turn's claim — so
     * catching only the database's own exception would 500 every message instead of falling back.
     */
    @Test
    void aScheduleReadThatThrowsFallsBackRatherThanFailingTheTurn() {
        ScheduleSource broken = mock(ScheduleSource.class);
        when(broken.deadlines()).thenThrow(new IllegalStateException("captured_at came back as null"));

        QuotaWindow window = new QuotaWindows(broken, GAMEWEEK_LIMIT, OFF_SEASON_LIMIT)
                .current(Instant.parse("2097-09-22T09:00:00Z"));

        assertThat(window.kind()).isEqualTo(QuotaWindow.Kind.FALLBACK);
    }

    /**
     * The SQL orders the list, but the rule does not lean on it: a scan over 38 rows costs nothing
     * and removes a silent dependency between two files.
     */
    @Test
    void theRuleDoesNotDependOnTheListBeingSorted() {
        List<ScheduleSource.Deadline> shuffled = List.of(
                deadline("2097/98", 13, "2097-09-26T10:30:00Z"),
                deadline("2097/98", 11, "2097-09-12T10:30:00Z"),
                deadline("2097/98", 12, "2097-09-19T10:30:00Z"));

        QuotaWindow window = derive(shuffled, "2097-09-22T09:00:00Z");

        assertThat(window.from()).isEqualTo(Instant.parse("2097-09-19T10:30:00Z"));
        assertThat(window.until()).isEqualTo(Instant.parse("2097-09-26T10:30:00Z"));
        assertThat(window.gameweek()).isEqualTo(13);
    }
}
