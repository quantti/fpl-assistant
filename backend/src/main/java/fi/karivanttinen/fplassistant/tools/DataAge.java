package fi.karivanttinen.fplassistant.tools;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Collections;

/**
 * Renders the age of stored Tapas rows (KAR-9): recorded write times, the dates of a stored
 * match sample, and the fixed notice that the core-stat refresh time of
 * {@code player_fixture_stats} is unknowable.
 *
 * <p>Pure formatting. The caller supplies the timestamps of the rows it actually displayed and
 * one explicit {@code asOf}; nothing here reads a clock, queries, caches or grades freshness.
 * Age is elapsed duration — {@code <1 hour}, then whole hours, then whole 24-hour days, rounded
 * down — so a DST change can never shift it by an hour. A null timestamp, no rows at all and a
 * future-dated timestamp each say so rather than reading as fresh.
 *
 * <p>Only instant-bearing values are accepted ({@link Timestamp}, {@link OffsetDateTime},
 * {@link Instant}). A {@code LocalDateTime} or anything else is refused: its age cannot be
 * computed without inventing the zone it was written in.
 */
public final class DataAge {

    /** Why PFS {@code updated_at} is never shown as metric freshness: the DefCon writers touch it. */
    public static final String METRIC_REFRESH = "Metric refresh: the core-stat sync time for player_fixture_stats "
            + "is unavailable; row writes can update defensive contribution alone. "
            + "Fixture dates describe the sample, not its refresh.";

    /** Tapas can insert a placeholder gameweek row locally, so its write time is not an FPL fetch. */
    public static final String GAMEWEEK_CAVEAT =
            "A gameweek row can be created locally, so its write time does not certify an FPL refresh.";

    private static final String RECORD_TIME = " This is a database record time, not a live FPL check.";
    private static final String STORED_ROWS_ONLY =
            " Stored rows only; this does not show that every match through that date is present.";
    private static final String NO_ROWS = "no stored rows used; age unavailable";
    private static final String FUTURE = "future-dated anomaly; age unavailable";

    private DataAge() {
    }

    /**
     * The timestamps of a set of rows: oldest and newest known instants, total rows and how many
     * carried no timestamp. Build it from the rows' values, or from SQL MIN/MAX and counts.
     */
    public record Span(Instant oldest, Instant newest, long rows, long undated) {

        public static Span of(Collection<?> values) {
            Instant oldest = null;
            Instant newest = null;
            long undated = 0;
            for (Object value : values) {
                Instant at = instant(value);
                if (at == null) {
                    undated++;
                    continue;
                }
                oldest = oldest == null || at.isBefore(oldest) ? at : oldest;
                newest = newest == null || at.isAfter(newest) ? at : newest;
            }
            return new Span(oldest, newest, values.size(), undated);
        }

        public static Span aggregate(Object oldest, Object newest, long rows, long datedRows) {
            return new Span(instant(oldest), instant(newest), rows, rows - datedRows);
        }

        public Span plus(Span other) {
            return new Span(min(oldest, other.oldest), max(newest, other.newest),
                    rows + other.rows, undated + other.undated);
        }

        private static Instant min(Instant a, Instant b) {
            return a == null ? b : b == null || a.isBefore(b) ? a : b;
        }

        private static Instant max(Instant a, Instant b) {
            return a == null ? b : b == null || a.isAfter(b) ? a : b;
        }
    }

    /** Elapsed age of a past instant; callers handle future instants before asking. */
    static String age(Instant at, Instant asOf) {
        Duration elapsed = Duration.between(at, asOf);
        long hours = elapsed.toHours();
        if (hours < 1) {
            return "<1 hour ago";
        }
        if (hours < 24) {
            return plural(hours, "hour") + " ago";
        }
        return plural(elapsed.toDays(), "day") + " ago";
    }

    /** {@code Stored-data age:} line for one row. */
    public static String storedDataAge(String scope, Object writtenAt, Instant asOf) {
        return storedDataAge(scope, "record", Span.of(Collections.singletonList(writtenAt)), asOf);
    }

    /** {@code Stored-data age:} line for the rows in {@code writes}; {@code noun} is singular, e.g. "fixture record". */
    public static String storedDataAge(String scope, String noun, Span writes, Instant asOf) {
        String prefix = "Stored-data age: " + scope + ", ";
        if (writes.rows() == 0) {
            return prefix + NO_ROWS + ".";
        }
        if (writes.oldest() == null) {
            return prefix + "write time unavailable"
                    + (writes.rows() == 1 ? "" : " for all " + plural(writes.rows(), noun)) + ".";
        }
        return prefix + range(writes, "last recorded write", "record writes from", asOf)
                + missing(writes, noun, "no write timestamp") + "." + RECORD_TIME;
    }

    /** {@code Stored match coverage:} line for the kickoff times of the stored rows of one season. */
    public static String matchCoverage(String scope, Span kickoffs, Instant asOf) {
        String prefix = "Stored match coverage: " + scope + ", ";
        if (kickoffs.rows() == 0) {
            return prefix + NO_ROWS + ".";
        }
        if (kickoffs.oldest() == null) {
            return prefix + "match date unavailable"
                    + (kickoffs.rows() == 1 ? "" : " for all " + plural(kickoffs.rows(), "stored row")) + ".";
        }
        return prefix + range(kickoffs, "known match date", "known match dates from", asOf)
                + missing(kickoffs, "stored row", "no match date") + "." + STORED_ROWS_ONLY;
    }

    /** Short age to attach to one displayed value from a single row, e.g. {@code written 7 days ago}. */
    public static String annotation(Object writtenAt, Instant asOf) {
        return annotation(Span.of(Collections.singletonList(writtenAt)), asOf);
    }

    /** Short age to attach to one displayed value computed from several rows. */
    public static String annotation(Span writes, Instant asOf) {
        if (writes.rows() == 0) {
            return "no stored rows; age unavailable";
        }
        if (writes.oldest() == null) {
            return "write time unavailable";
        }
        if (writes.newest().isAfter(asOf)) {
            return "future-dated write; age unavailable";
        }
        String oldest = age(writes.oldest(), asOf);
        String newest = age(writes.newest(), asOf);
        String written = oldest.equals(newest)
                ? "written " + newest
                : "written " + oldest + " to " + newest;
        return writes.undated() == 0 ? written
                : written + "; " + writes.undated() + " of " + writes.rows() + " undated";
    }

    private static String range(Span span, String single, String from, Instant asOf) {
        if (span.oldest().equals(span.newest())) {
            return single + " " + stamp(span.newest(), asOf);
        }
        return from + " " + stamp(span.oldest(), asOf) + " to " + stamp(span.newest(), asOf);
    }

    private static String stamp(Instant at, Instant asOf) {
        return UkTime.format(at, null) + " (" + (at.isAfter(asOf) ? FUTURE : age(at, asOf)) + ")";
    }

    private static String missing(Span span, String noun, String what) {
        if (span.undated() == 0) {
            return "";
        }
        return "; %d of %s %s %s".formatted(span.undated(), plural(span.rows(), noun),
                span.undated() == 1 ? "has" : "have", what);
    }

    private static String plural(long count, String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
    }

    private static Instant instant(Object value) {
        return switch (value) {
            case null -> null;
            case Timestamp ts -> ts.toInstant();
            case OffsetDateTime odt -> odt.toInstant();
            case Instant instant -> instant;
            default -> throw new IllegalArgumentException(
                    "Not an instant-bearing timestamp, refusing to guess its zone: " + value.getClass().getName());
        };
    }
}
