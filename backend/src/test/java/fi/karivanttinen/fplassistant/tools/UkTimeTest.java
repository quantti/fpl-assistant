package fi.karivanttinen.fplassistant.tools;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.TimeZone;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bug these pin down: deadlines and kickoffs were rendered with a bare
 * {@code Timestamp.toString()}, which formats in the JVM default zone and prints no zone at
 * all. On Fly that zone is UTC, so for the roughly seven months a year Britain is on BST the
 * app told users a deadline an hour earlier than FPL's own published time, with nothing in
 * the string to reveal which zone was meant.
 *
 * <p>The JVM default is forced to a third zone here on purpose. Asserting against a UTC
 * default would let a formatter that simply echoes the default pass the winter case, which is
 * exactly the failure mode being guarded — the test has to be able to fail.
 */
class UkTimeTest {

    private static TimeZone original;

    @BeforeAll
    static void useAForeignDefaultZone() {
        original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
    }

    @AfterAll
    static void restoreDefaultZone() {
        TimeZone.setDefault(original);
    }

    @Test
    void rendersSummerDeadlineInBritishSummerTime() {
        // 2026-08-21T10:30:00Z — GW1 deadline territory, when London is UTC+1.
        Timestamp ts = Timestamp.from(Instant.parse("2026-08-21T10:30:00Z"));

        assertThat(UkTime.format(ts, "unused")).isEqualTo("2026-08-21 11:30 UK");
    }

    @Test
    void rendersWinterDeadlineInGreenwichMeanTime() {
        // 2026-12-26T11:30:00Z — Boxing Day, when London is back on UTC+0.
        Timestamp ts = Timestamp.from(Instant.parse("2026-12-26T11:30:00Z"));

        assertThat(UkTime.format(ts, "unused")).isEqualTo("2026-12-26 11:30 UK");
    }

    @Test
    void alwaysLabelsTheZoneSoTheReaderCanCheckIt() {
        Timestamp ts = Timestamp.from(Instant.parse("2026-08-21T10:30:00Z"));

        assertThat(UkTime.format(ts, "unused")).endsWith(" UK");
    }

    @Test
    void rendersAnOffsetDateTimeInUkTime() {
        // The value carries its own offset; the instant is unambiguous, so it must render in
        // the house style rather than a raw toString.
        OffsetDateTime odt = OffsetDateTime.parse("2026-08-21T11:30:00+01:00");

        assertThat(UkTime.format(odt, "unused")).isEqualTo("2026-08-21 11:30 UK");
    }

    @Test
    void rendersAnInstantInUkTime() {
        Instant instant = Instant.parse("2026-08-21T10:30:00Z");

        assertThat(UkTime.format(instant, "unused")).isEqualTo("2026-08-21 11:30 UK");
    }

    @Test
    void fallsBackWhenTheTimestampIsMissing() {
        // Genuinely reachable: kickoff_time is nullable for postponed fixtures, and vaastav
        // rows with an unparseable kickoff are stored as NULL.
        assertThat(UkTime.format(null, "kickoff TBD")).isEqualTo("kickoff TBD");
    }

    @Test
    void passesThroughAValueThatIsNotATimestamp() {
        assertThat(UkTime.format("already a string", "unused")).isEqualTo("already a string");
    }
}
