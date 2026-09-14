package dev.quantti.fplassistant.price;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Triggers {@link PriceSyncService} on startup, behind a staleness marker persisted in the
 * database — the same shape as {@code StartupSyncRunner} for the football-data.org sync. The
 * marker is {@code MAX(ingested_at) WHERE source = 'snapshot'} in {@code fpl_player_price}
 * itself: no separate table, and the snapshot source is written on every sync so it is a
 * reliable freshness signal.
 *
 * <p>Runs {@code @Async} (see {@code AsyncConfig}) so {@code onApplicationEvent} returns
 * immediately and the chat request that woke a stopped Fly machine is answered from whatever
 * prices are already stored. Every exception is caught here: a sync failure must never prevent
 * the application from having started.
 */
@Component
// Disabled under the test profile: the tests construct this runner directly, and against an
// empty test database isStale() is always true, so every Spring context start would fetch the
// vaastav CSV over the network and read the shared player table while the tests are running.
@ConditionalOnProperty(name = "fpl.price.startup-sync", havingValue = "true", matchIfMissing = true)
@ConditionalOnExpression("'${fpl.owned-sync.mode:OFF}' != 'ACTIVE'")
public class StartupPriceSyncRunner implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(StartupPriceSyncRunner.class);

    private final PriceSyncService syncService;
    private final JdbcTemplate jdbc;
    private final String tableName;
    private final Duration staleAfter;

    public StartupPriceSyncRunner(PriceSyncService syncService, JdbcTemplate jdbc,
                                  @Value("${fpl.price.table-name}") String tableName,
                                  @Value("${fpl.price.sync-stale-after-hours:6}") long staleAfterHours) {
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
                log.info("Last price snapshot is stale (or has never run) — syncing now");
                syncService.sync();
            } else {
                log.debug("price sync ran recently enough — skipping");
            }
        } catch (RuntimeException e) {
            log.error("Startup price sync failed; the application continues serving"
                    + " with existing price data", e);
        }
    }

    private boolean isStale() {
        Timestamp lastSync = jdbc.queryForObject(
                "SELECT MAX(ingested_at) FROM " + tableName + " WHERE source = 'snapshot'",
                Timestamp.class);
        return lastSync == null || lastSync.toInstant().isBefore(Instant.now().minus(staleAfter));
    }
}
