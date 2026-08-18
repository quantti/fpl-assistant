package fi.karivanttinen.fplassistant.historical;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Triggers {@link FootballDataSyncService} on startup, behind a staleness marker persisted in
 * the database — not an in-memory field, which a stopped Fly machine would lose, causing every
 * cold start to re-sync. The marker is simply {@code MAX(ingested_at) WHERE source =
 * 'football-data'} in {@code pl_match_result} itself: no separate table, since every successful
 * upsert already timestamps itself.
 *
 * <p>Runs {@code @Async} (see {@link AsyncConfig}), which Spring dispatches through this bean's
 * proxy the same as any externally-invoked method — {@code onApplicationEvent} genuinely returns
 * immediately and the sync continues on a background thread, so the request that woke a
 * stopped machine is answered from whatever data is already stored, not held up waiting for
 * football-data.org. Every exception is caught and logged here: a sync failure (API down, key
 * missing, football-data.org rejecting the request) must never prevent the application from
 * having started.
 */
@Component
// Disabled under the test profile, for the same reason as StartupPriceSyncRunner: the tests
// construct this runner directly, and a startup sync firing behind the suite is a network call
// nobody asked for. Without an API key it no-ops, but silently rather than by decision.
@ConditionalOnProperty(name = "fpl.historical.startup-sync", havingValue = "true", matchIfMissing = true)
public class StartupSyncRunner implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(StartupSyncRunner.class);

    private final FootballDataSyncService syncService;
    private final JdbcTemplate jdbc;
    private final String tableName;
    private final Duration staleAfter;

    public StartupSyncRunner(FootballDataSyncService syncService, JdbcTemplate jdbc,
                             @Value("${fpl.historical.table-name}") String tableName,
                             @Value("${fpl.historical.sync-stale-after-hours:20}") long staleAfterHours) {
        this.syncService = syncService;
        this.jdbc = jdbc;
        this.tableName = tableName;
        this.staleAfter = Duration.ofHours(staleAfterHours);
    }

    @Override
    @Async
    public void onApplicationEvent(ApplicationReadyEvent event) {
        try {
            if (isStale()) {
                log.info("Last football-data sync is stale (or has never run) — syncing now");
                syncService.sync();
            } else {
                log.debug("football-data sync ran recently enough — skipping");
            }
        } catch (RuntimeException e) {
            // Never let a sync failure look like an application failure: this runs after the
            // application has already started and is serving requests from whatever data it
            // already has. Log it and move on.
            log.error("Startup football-data sync failed; the application continues serving"
                    + " with existing data", e);
        }
    }

    private boolean isStale() {
        Timestamp lastSync = jdbc.queryForObject(
                "SELECT MAX(ingested_at) FROM " + tableName + " WHERE source = 'football-data'",
                Timestamp.class);
        return lastSync == null || lastSync.toInstant().isBefore(Instant.now().minus(staleAfter));
    }
}
