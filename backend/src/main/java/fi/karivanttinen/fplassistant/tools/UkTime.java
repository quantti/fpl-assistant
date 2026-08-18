package fi.karivanttinen.fplassistant.tools;

import java.sql.Timestamp;
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
     * Formats a JDBC timestamp as {@code yyyy-MM-dd HH:mm UK}, or returns {@code fallback}
     * when the value is null or is not a timestamp the driver handed back.
     *
     * <p>The fallback path is not defensive padding: {@code kickoff_time} is genuinely
     * nullable for postponed fixtures, and {@code pl_match_result} rows backfilled from
     * vaastav can carry an unparseable kickoff that was stored as NULL.
     */
    public static String format(Object value, String fallback) {
        if (value instanceof Timestamp ts) {
            return FORMAT.format(ts.toInstant().atZone(UK)) + " UK";
        }
        return value == null ? fallback : value.toString();
    }
}
