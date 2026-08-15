package fi.karivanttinen.fplassistant.price;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Against the isolated {@code fpl_player_price_test} table. Invokes
 * {@code onApplicationEvent} directly on a plain instance (so it runs synchronously —
 * {@code @Async} only takes effect through the Spring proxy a real bean gets), exercising the
 * exact same staleness logic {@code AsyncConfig} lets run off the request path in production.
 */
@SpringBootTest
@ActiveProfiles("test")
class StartupPriceSyncRunnerIntegrationTest {

    private static final long STALE_AFTER_HOURS = 6;
    private static final String TEST_SEASON = "test-price";

    @Autowired
    JdbcTemplate jdbc;

    @Value("${fpl.price.table-name}")
    String tableName;

    @BeforeEach
    void refuseToRunAgainstProduction() {
        assertThat(tableName)
                .as("startup price sync tests must be pinned to the isolated table; check @ActiveProfiles(\"test\")")
                .endsWith("_test");
        jdbc.update("DELETE FROM " + tableName + " WHERE season = '" + TEST_SEASON + "'");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM " + tableName + " WHERE season = '" + TEST_SEASON + "'");
    }

    private StartupPriceSyncRunner runner(PriceSyncService syncService) {
        return new StartupPriceSyncRunner(syncService, jdbc, tableName, STALE_AFTER_HOURS);
    }

    @Test
    void syncsWhenNoSnapshotHasEverRun() {
        PriceSyncService mockSync = mock(PriceSyncService.class);
        runner(mockSync).onApplicationEvent(mock(ApplicationReadyEvent.class));
        verify(mockSync, times(1)).sync();
    }

    @Test
    void doesNotSyncWhenTheSnapshotIsRecent() {
        insertSnapshotMarker(1); // 1 hour ago, within the 6h threshold
        PriceSyncService mockSync = mock(PriceSyncService.class);
        runner(mockSync).onApplicationEvent(mock(ApplicationReadyEvent.class));
        verifyNoInteractions(mockSync);
    }

    @Test
    void syncsAgainWhenTheSnapshotIsOlderThanTheThreshold() {
        insertSnapshotMarker(8); // older than the 6h threshold
        PriceSyncService mockSync = mock(PriceSyncService.class);
        runner(mockSync).onApplicationEvent(mock(ApplicationReadyEvent.class));
        verify(mockSync, times(1)).sync();
    }

    @Test
    void aSyncFailureIsCaughtAndDoesNotPropagate() {
        PriceSyncService failingSync = mock(PriceSyncService.class);
        when(failingSync.sync()).thenThrow(new IllegalStateException("vaastav is down"));
        assertThatCode(() -> runner(failingSync).onApplicationEvent(mock(ApplicationReadyEvent.class)))
                .doesNotThrowAnyException();
    }

    private void insertSnapshotMarker(int hoursAgo) {
        jdbc.update("INSERT INTO " + tableName
                + " (season, gameweek, element_id, player_name, price, source, ingested_at)"
                + " VALUES ('" + TEST_SEASON + "', 0, 9199, 'Marker', 50, 'snapshot',"
                + " NOW() - (? || ' hours')::interval)", hoursAgo);
    }
}
