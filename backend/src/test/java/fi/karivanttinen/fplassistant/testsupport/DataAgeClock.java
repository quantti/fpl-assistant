package fi.karivanttinen.fplassistant.testsupport;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * The one {@code asOf} the KAR-9 stored-data-age tests measure against; BST, so UK time is UTC+1.
 *
 * <p>Tests build the tool under test with {@link #provider()} instead of importing a
 * {@code @Primary} clock bean: every distinct test configuration is its own cached Spring context
 * with its own connection pool, and the extra contexts exhausted the test database's
 * {@code max_connections}. Only the tools read this clock, so nothing else changes.
 */
public final class DataAgeClock {

    public static final Instant AS_OF = Instant.parse("2026-09-10T12:00:00Z");

    private DataAgeClock() {
    }

    /** The tools' optional clock, fixed at {@link #AS_OF}. */
    public static ObjectProvider<Clock> provider() {
        return new StaticListableBeanFactory(Map.of("dataAgeClock", Clock.fixed(AS_OF, ZoneOffset.UTC)))
                .getBeanProvider(Clock.class);
    }
}
