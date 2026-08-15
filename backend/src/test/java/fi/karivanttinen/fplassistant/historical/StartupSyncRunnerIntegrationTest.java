package fi.karivanttinen.fplassistant.historical;

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
 * Against the isolated test table. {@code onApplicationEvent} is invoked directly here (not
 * through an actual application startup), which also means it runs synchronously in this test
 * — {@code @Async} only takes effect through the Spring proxy a real bean gets, so calling the
 * method directly on a plain instance is a deliberate, useful side effect: it removes the
 * nondeterminism a background thread would add while still exercising the exact same logic
 * {@code AsyncConfig} lets run off the request path in production.
 */
@SpringBootTest
@ActiveProfiles("test")
class StartupSyncRunnerIntegrationTest {

    private static final long STALE_AFTER_HOURS = 20;

    @Autowired
    JdbcTemplate jdbc;

    @Value("${fpl.historical.table-name}")
    String tableName;

    @BeforeEach
    void refuseToRunAgainstProduction() {
        assertThat(tableName)
                .as("startup sync tests must be pinned to the isolated table; check @ActiveProfiles(\"test\")")
                .endsWith("_test");
        jdbc.update("DELETE FROM " + tableName + " WHERE source = 'football-data'");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM " + tableName + " WHERE source = 'football-data'");
    }

    private StartupSyncRunner runner(FootballDataSyncService syncService) {
        return new StartupSyncRunner(syncService, jdbc, tableName, STALE_AFTER_HOURS);
    }

    @Test
    void syncsWhenNoSyncHasEverRun() {
        FootballDataSyncService mockSync = mock(FootballDataSyncService.class);

        runner(mockSync).onApplicationEvent(mock(ApplicationReadyEvent.class));

        verify(mockSync, times(1)).sync();
    }

    @Test
    void doesNotSyncWhenTheMarkerIsRecent() {
        insertMarker(1); // 1 hour ago, well within the 20h threshold

        FootballDataSyncService mockSync = mock(FootballDataSyncService.class);
        runner(mockSync).onApplicationEvent(mock(ApplicationReadyEvent.class));

        verifyNoInteractions(mockSync);
    }

    @Test
    void syncsAgainWhenTheMarkerIsOlderThanTheThreshold() {
        insertMarker(25); // older than the 20h threshold

        FootballDataSyncService mockSync = mock(FootballDataSyncService.class);
        runner(mockSync).onApplicationEvent(mock(ApplicationReadyEvent.class));

        verify(mockSync, times(1)).sync();
    }

    @Test
    void theMarkerIsPersistedNotInMemory_soASecondRunnerInstanceSeesIt() {
        // Simulates two separate application startups: a stopped Fly machine has no memory of
        // the first instance, so the second run must see the marker via the database, not a
        // field on the (long-gone) first StartupSyncRunner object.
        FootballDataSyncService realSync = mock(FootballDataSyncService.class);
        when(realSync.sync()).thenAnswer(inv -> {
            jdbc.update("INSERT INTO " + tableName
                    + " (season, home_team_code, away_team_code, home_team_name, away_team_name,"
                    + " home_goals, away_goals, source, ingested_at)"
                    + " VALUES ('t-sync-a', 9301, 9302, 'Marker Home FC', 'Marker Away FC', 1, 0,"
                    + " 'football-data', NOW())");
            return 1;
        });

        runner(realSync).onApplicationEvent(mock(ApplicationReadyEvent.class)); // first "startup"
        verify(realSync, times(1)).sync();

        // A brand-new StartupSyncRunner instance, as a fresh process would construct — no
        // shared state with the one above except the database.
        FootballDataSyncService secondMock = mock(FootballDataSyncService.class);
        runner(secondMock).onApplicationEvent(mock(ApplicationReadyEvent.class)); // second "startup"

        verifyNoInteractions(secondMock);
    }

    @Test
    void aSyncFailureIsCaughtAndDoesNotPropagate() {
        FootballDataSyncService failingSync = mock(FootballDataSyncService.class);
        when(failingSync.sync()).thenThrow(new IllegalStateException("football-data.org is down"));

        assertThatCode(() -> runner(failingSync).onApplicationEvent(mock(ApplicationReadyEvent.class)))
                .doesNotThrowAnyException();
    }

    private void insertMarker(int hoursAgo) {
        jdbc.update("INSERT INTO " + tableName
                + " (season, home_team_code, away_team_code, home_team_name, away_team_name,"
                + " home_goals, away_goals, source, ingested_at)"
                + " VALUES ('t-sync-a', 9301, 9302, 'Marker Home FC', 'Marker Away FC', 1, 0,"
                + " 'football-data', NOW() - (? || ' hours')::interval)", hoursAgo);
    }
}
