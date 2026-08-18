package dev.quantti.fplassistant.tools;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Renders FPL timestamps in UK time with an explicit label.
 *
 * <p>FPL publishes every deadline and kickoff in UK time, and the whole game is organised
 * around them — "the deadline is 11:30" means 11:30 in London whatever the reader's clock
 * says. The Fly container runs UTC, so a raw {@link Timestamp#toString()} renders the JVM
 * default zone with no label at all: correct-looking, off by an hour for half the year
 * (Europe/London is UTC+1 under BST), and impossible for the reader to check because
 * nothing says which zone it is. A deadline is exactly the value where being quietly
 * wrong by an hour costs a user their whole gameweek.
 *
 * <p>{@code Europe/London} rather than a fixed offset: the zone carries the GMT/BST
 * transition, and FPL's own published times follow it.
 */
public final class UkTime {

    private static final ZoneId UK = ZoneId.of("Europe/London");
    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.UK);

    private UkTime() {
    }

    /**
     * The zone FPL's own day boundaries follow — price changes land at 00:00 here, so "today"
     * for anything price- or availability-related means today in London, not in the container.
     */
    public static ZoneId zone() {
        return UK;
    }

    /**
     * Formats a JDBC timestamp (or an {@link OffsetDateTime}/{@link Instant}, which pgjdbc can
     * hand back depending on configuration) as {@code yyyy-MM-dd HH:mm UK}, or returns
     * {@code fallback} when the value is null.
     *
     * <p>The fallback path is not defensive padding: {@code kickoff_time} is genuinely
     * nullable for postponed fixtures, and {@code pl_match_result} rows backfilled from
     * vaastav can carry an unparseable kickoff that was stored as NULL.
     *
     * <p>A {@code LocalDateTime} (or {@code java.util.Date}) falls through to
     * {@code toString()} unlabelled. That is deliberate rather than an oversight: a value
     * without a zone cannot be rendered "in UK time" without inventing the zone it was
     * captured in. Unreachable today — every rendered column is {@code TIMESTAMPTZ}, which
     * the driver maps to {@code Timestamp} — but a future {@code timestamp without time zone}
     * column would surface here as an unlabelled string, which is at least visible rather
     * than silently wrong.
     */
    public static String format(Object value, String fallback) {
        if (value instanceof Timestamp ts) {
            return FORMAT.format(ts.toInstant().atZone(UK)) + " UK";
        }
        // pgjdbc can hand back OffsetDateTime or Instant instead of Timestamp depending on
        // configuration; both carry the instant unambiguously, so render them in the house
        // style rather than falling through to a raw toString.
        if (value instanceof OffsetDateTime odt) {
            return FORMAT.format(odt.atZoneSameInstant(UK)) + " UK";
        }
        if (value instanceof Instant instant) {
            return FORMAT.format(instant.atZone(UK)) + " UK";
        }
        return value == null ? fallback : value.toString();
    }
}
