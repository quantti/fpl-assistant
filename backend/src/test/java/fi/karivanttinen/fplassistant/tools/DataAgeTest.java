package fi.karivanttinen.fplassistant.tools;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the wording of the stored-data age evidence (KAR-9). The JVM default zone is forced to a
 * third zone, as in {@link UkTimeTest}, so a formatter that echoes the default cannot pass.
 */
class DataAgeTest {

    private static final Instant AS_OF = Instant.parse("2026-09-10T06:00:00Z"); // 07:00 BST
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

    private static Timestamp ts(String instant) {
        return Timestamp.from(Instant.parse(instant));
    }

    // --- elapsed age -------------------------------------------------------------------------

    @Test
    void ageIsElapsedDurationRoundedDown() {
        assertThat(DataAge.age(AS_OF, AS_OF)).isEqualTo("<1 hour ago");
        assertThat(DataAge.age(AS_OF.minusSeconds(3599), AS_OF)).isEqualTo("<1 hour ago");
        assertThat(DataAge.age(AS_OF.minusSeconds(3600), AS_OF)).isEqualTo("1 hour ago");
        assertThat(DataAge.age(AS_OF.minusSeconds(2 * 3600), AS_OF)).isEqualTo("2 hours ago");
        assertThat(DataAge.age(AS_OF.minusSeconds(24 * 3600 - 1), AS_OF)).isEqualTo("23 hours ago");
        assertThat(DataAge.age(AS_OF.minusSeconds(24 * 3600), AS_OF)).isEqualTo("1 day ago");
        assertThat(DataAge.age(AS_OF.minusSeconds(48 * 3600 - 1), AS_OF)).isEqualTo("1 day ago");
        assertThat(DataAge.age(Instant.parse("2026-06-02T06:00:00Z"), AS_OF)).isEqualTo("100 days ago");
    }

    @Test
    void ageAcrossTheSpringClockChangeIsElapsedHoursNotCalendarDays() {
        // 12:30 GMT on 28 March to 13:00 BST on 29 March: one calendar day apart on a London
        // wall clock, but only 23.5 hours have elapsed.
        assertThat(DataAge.age(Instant.parse("2026-03-28T12:30:00Z"), Instant.parse("2026-03-29T12:00:00Z")))
                .isEqualTo("23 hours ago");
    }

    @Test
    void ageAcrossTheAutumnClockChangeIsElapsedHoursNotCalendarDays() {
        // 12:00 BST on 24 October to 11:30 GMT on 25 October: less than a day on the wall
        // clock, 24.5 hours elapsed.
        assertThat(DataAge.age(Instant.parse("2026-10-24T11:00:00Z"), Instant.parse("2026-10-25T11:30:00Z")))
                .isEqualTo("1 day ago");
    }

    // --- single recorded write ---------------------------------------------------------------

    @Test
    void singleWriteNamesUkTimeAndAge() {
        assertThat(DataAge.storedDataAge("player record for Okafor, season 2026-27",
                ts("2026-09-03T06:00:00Z"), AS_OF))
                .isEqualTo("Stored-data age: player record for Okafor, season 2026-27, last recorded write "
                        + "2026-09-03 07:00 UK (7 days ago). This is a database record time, not a live FPL check.");
    }

    @Test
    void singleWriteInWinterRendersGmt() {
        assertThat(DataAge.storedDataAge("player record for Okafor, season 2025-26",
                ts("2026-01-15T09:00:00Z"), Instant.parse("2026-01-15T10:00:00Z")))
                .isEqualTo("Stored-data age: player record for Okafor, season 2025-26, last recorded write "
                        + "2026-01-15 09:00 UK (1 hour ago). This is a database record time, not a live FPL check.");
    }

    @Test
    void singleWriteAcceptsOffsetDateTimeAndInstant() {
        String expected = "Stored-data age: x, last recorded write 2026-09-09 07:00 UK (1 day ago). "
                + "This is a database record time, not a live FPL check.";
        assertThat(DataAge.storedDataAge("x", OffsetDateTime.parse("2026-09-09T07:00:00+01:00"), AS_OF))
                .isEqualTo(expected);
        assertThat(DataAge.storedDataAge("x", Instant.parse("2026-09-09T06:00:00Z"), AS_OF))
                .isEqualTo(expected);
    }

    @Test
    void nullWriteIsUnavailableNotFresh() {
        assertThat(DataAge.storedDataAge("player record for Okafor, season 2026-27", null, AS_OF))
                .isEqualTo("Stored-data age: player record for Okafor, season 2026-27, write time unavailable.");
    }

    @Test
    void futureWriteIsAnAnomalyNeverClampedToToday() {
        assertThat(DataAge.storedDataAge("gameweek 4 record", ts("2026-09-12T06:00:00Z"), AS_OF))
                .isEqualTo("Stored-data age: gameweek 4 record, last recorded write 2026-09-12 07:00 UK "
                        + "(future-dated anomaly; age unavailable). "
                        + "This is a database record time, not a live FPL check.");
    }

    @Test
    void refusesToInventAZoneForALocalDateTime() {
        assertThatThrownBy(() -> DataAge.storedDataAge("x", LocalDateTime.parse("2026-09-03T07:00:00"), AS_OF))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LocalDateTime");
        assertThatThrownBy(() -> DataAge.storedDataAge("x", "2026-09-03 07:00", AS_OF))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- multi-row write spans ---------------------------------------------------------------

    @Test
    void rangeNamesOldestAndNewestWrites() {
        DataAge.Span span = DataAge.Span.of(List.of(
                ts("2026-09-03T06:00:00Z"), ts("2026-09-01T06:00:00Z"), ts("2026-09-10T05:30:00Z")));

        assertThat(DataAge.storedDataAge("fixture records for GW4-8", "fixture record", span, AS_OF))
                .isEqualTo("Stored-data age: fixture records for GW4-8, record writes from "
                        + "2026-09-01 07:00 UK (9 days ago) to 2026-09-10 06:30 UK (<1 hour ago). "
                        + "This is a database record time, not a live FPL check.");
    }

    @Test
    void equalEndpointsCollapseToOneTimestamp() {
        DataAge.Span span = DataAge.Span.of(List.of(ts("2026-09-03T06:00:00Z"), ts("2026-09-03T06:00:00Z")));

        assertThat(DataAge.storedDataAge("fixture records", "fixture record", span, AS_OF))
                .isEqualTo("Stored-data age: fixture records, last recorded write 2026-09-03 07:00 UK (7 days ago). "
                        + "This is a database record time, not a live FPL check.");
    }

    @Test
    void mixedOldNewAndNullReportsTheUndatedCount() {
        DataAge.Span one = DataAge.Span.of(Arrays.asList(
                ts("2026-08-01T06:00:00Z"), null, ts("2026-09-09T06:00:00Z")));
        DataAge.Span two = DataAge.Span.of(Arrays.asList(ts("2026-09-09T06:00:00Z"), null, null));

        assertThat(DataAge.storedDataAge("fixture records", "fixture record", one, AS_OF))
                .isEqualTo("Stored-data age: fixture records, record writes from 2026-08-01 07:00 UK (40 days ago) "
                        + "to 2026-09-09 07:00 UK (1 day ago); 1 of 3 fixture records has no write timestamp. "
                        + "This is a database record time, not a live FPL check.");
        assertThat(DataAge.storedDataAge("fixture records", "fixture record", two, AS_OF))
                .contains("; 2 of 3 fixture records have no write timestamp.");
    }

    @Test
    void allNullIsDistinctFromNoRows() {
        DataAge.Span allNull = DataAge.Span.of(Arrays.asList(null, null, null));

        assertThat(DataAge.storedDataAge("fixture records", "fixture record", allNull, AS_OF))
                .isEqualTo("Stored-data age: fixture records, write time unavailable for all 3 fixture records.");
        assertThat(DataAge.storedDataAge("fixture records", "fixture record", DataAge.Span.of(List.of()), AS_OF))
                .isEqualTo("Stored-data age: fixture records, no stored rows used; age unavailable.");
        assertThat(DataAge.storedDataAge("fixture records", "fixture record",
                DataAge.Span.of(Arrays.asList((Object) null)), AS_OF))
                .isEqualTo("Stored-data age: fixture records, write time unavailable.");
    }

    @Test
    void rangeContainingAFutureWriteDisclosesItRatherThanClaimingAnAge() {
        DataAge.Span span = DataAge.Span.of(List.of(ts("2026-09-03T06:00:00Z"), ts("2026-09-11T06:00:00Z")));

        assertThat(DataAge.storedDataAge("fixture records", "fixture record", span, AS_OF))
                .isEqualTo("Stored-data age: fixture records, record writes from 2026-09-03 07:00 UK (7 days ago) "
                        + "to 2026-09-11 07:00 UK (future-dated anomaly; age unavailable). "
                        + "This is a database record time, not a live FPL check.");
    }

    @Test
    void aggregateSpansMergeMinMaxAndCounts() {
        // Team aggregates arrive as per-group MIN/MAX plus total/dated counts.
        DataAge.Span a = DataAge.Span.aggregate(ts("2026-08-20T14:00:00Z"), ts("2026-08-20T14:00:00Z"), 11, 11);
        DataAge.Span b = DataAge.Span.aggregate(ts("2026-08-30T14:00:00Z"), ts("2026-08-31T14:00:00Z"), 12, 10);
        DataAge.Span c = DataAge.Span.aggregate(null, null, 3, 0);

        DataAge.Span merged = a.plus(b).plus(c);

        assertThat(merged).isEqualTo(new DataAge.Span(Instant.parse("2026-08-20T14:00:00Z"),
                Instant.parse("2026-08-31T14:00:00Z"), 26, 5));
        assertThat(c.plus(DataAge.Span.of(List.of()))).isEqualTo(new DataAge.Span(null, null, 3, 3));
    }

    // --- PFS match coverage ------------------------------------------------------------------

    @Test
    void matchCoverageNamesKnownMatchDatesAndUndatedRows() {
        DataAge.Span kickoffs = DataAge.Span.of(Arrays.asList(
                ts("2026-08-16T13:00:00Z"), null, ts("2026-09-01T19:00:00Z")));

        assertThat(DataAge.matchCoverage("season 2026-27", kickoffs, AS_OF))
                .isEqualTo("Stored match coverage: season 2026-27, known match dates from 2026-08-16 14:00 UK "
                        + "(24 days ago) to 2026-09-01 20:00 UK (8 days ago); 1 of 3 stored rows has no match date. "
                        + "Stored rows only; this does not show that every match through that date is present.");
    }

    @Test
    void matchCoverageForOneDateAndForPreviousSeasonInGmt() {
        DataAge.Span kickoffs = DataAge.Span.of(List.of(ts("2026-05-24T15:00:00Z")));

        assertThat(DataAge.matchCoverage("season 2025-26", kickoffs, AS_OF))
                .isEqualTo("Stored match coverage: season 2025-26, known match date 2026-05-24 16:00 UK "
                        + "(108 days ago). "
                        + "Stored rows only; this does not show that every match through that date is present.");
        assertThat(DataAge.matchCoverage("season 2025-26",
                DataAge.Span.of(List.of(ts("2025-12-26T15:00:00Z"))), AS_OF))
                .startsWith("Stored match coverage: season 2025-26, known match date 2025-12-26 15:00 UK (");
    }

    @Test
    void matchCoverageWithoutDatesOrRows() {
        assertThat(DataAge.matchCoverage("season 2026-27", DataAge.Span.of(Arrays.asList(null, null)), AS_OF))
                .isEqualTo("Stored match coverage: season 2026-27, match date unavailable for all 2 stored rows.");
        assertThat(DataAge.matchCoverage("season 2026-27", DataAge.Span.of(List.of()), AS_OF))
                .isEqualTo("Stored match coverage: season 2026-27, no stored rows used; age unavailable.");
    }

    @Test
    void matchCoverageFlagsAFutureMatchDate() {
        DataAge.Span kickoffs = DataAge.Span.of(List.of(ts("2026-09-01T19:00:00Z"), ts("2026-09-13T13:00:00Z")));

        assertThat(DataAge.matchCoverage("season 2026-27", kickoffs, AS_OF))
                .contains("to 2026-09-13 14:00 UK (future-dated anomaly; age unavailable)");
    }

    // --- short per-value annotation ----------------------------------------------------------

    @Test
    void annotationsAreShort() {
        assertThat(DataAge.annotation(ts("2026-09-03T06:00:00Z"), AS_OF)).isEqualTo("written 7 days ago");
        assertThat(DataAge.annotation((Object) null, AS_OF)).isEqualTo("write time unavailable");
        assertThat(DataAge.annotation(ts("2026-09-11T06:00:00Z"), AS_OF))
                .isEqualTo("future-dated write; age unavailable");

        assertThat(DataAge.annotation(DataAge.Span.of(List.of(
                ts("2026-09-01T06:00:00Z"), ts("2026-09-03T06:00:00Z"))), AS_OF))
                .isEqualTo("written 9 days ago to 7 days ago");
        assertThat(DataAge.annotation(DataAge.Span.of(Arrays.asList(
                ts("2026-09-03T05:00:00Z"), ts("2026-09-03T06:00:00Z"), null)), AS_OF))
                .isEqualTo("written 7 days ago; 1 of 3 undated");
        assertThat(DataAge.annotation(DataAge.Span.of(List.of(
                ts("2026-09-01T06:00:00Z"), ts("2026-09-11T06:00:00Z"))), AS_OF))
                .isEqualTo("future-dated write; age unavailable");
        assertThat(DataAge.annotation(DataAge.Span.of(List.of()), AS_OF)).isEqualTo("no stored rows; age unavailable");
    }

    // --- fixed notices -----------------------------------------------------------------------

    @Test
    void metricRefreshNoticeIsPinned() {
        assertThat(DataAge.METRIC_REFRESH).isEqualTo(
                "Metric refresh: the core-stat sync time for player_fixture_stats is unavailable; row writes "
                        + "can update defensive contribution alone. Fixture dates describe the sample, not its refresh.");
    }

    @Test
    void gameweekCaveatIsPinned() {
        assertThat(DataAge.GAMEWEEK_CAVEAT).isEqualTo(
                "A gameweek row can be created locally, so its write time does not certify an FPL refresh.");
    }
}
