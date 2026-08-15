package fi.karivanttinen.fplassistant.historical;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import fi.karivanttinen.fplassistant.tools.SeasonContext;
import fi.karivanttinen.fplassistant.tools.SeasonResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Against the isolated test table. Uses a mocked {@link SeasonResolver} rather than the real
 * one, because — as of this writing — the real one resolves to 2025-26 (2026-27 has no
 * gameweek/player data yet), which means the precedence-guard path is what actually runs in
 * production right now and cannot exercise the write path on demand. Both branches are tested
 * explicitly instead of hoping the calendar cooperates.
 */
@SpringBootTest
@ActiveProfiles("test")
class FootballDataSyncServiceIntegrationTest {

    private static final String SOURCE_TAG = "football-data";

    @Autowired
    JdbcTemplate jdbc;

    @Value("${fpl.historical.table-name}")
    String tableName;

    @BeforeEach
    void refuseToRunAgainstProduction() {
        assertThat(tableName)
                .as("football-data sync tests must be pinned to the isolated table; check @ActiveProfiles(\"test\")")
                .endsWith("_test");
        jdbc.update("DELETE FROM " + tableName + " WHERE source = '" + SOURCE_TAG + "'");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM " + tableName + " WHERE source = '" + SOURCE_TAG + "'");
    }

    private static final String SAMPLE_RESPONSE = """
            {
              "matches": [
                {
                  "utcDate": "2026-08-22T14:00:00Z",
                  "status": "FINISHED",
                  "matchday": 1,
                  "homeTeam": { "name": "Arsenal FC" },
                  "awayTeam": { "name": "Chelsea FC" },
                  "score": { "fullTime": { "home": 2, "away": 1 } }
                },
                {
                  "utcDate": "2026-08-23T14:00:00Z",
                  "status": "SCHEDULED",
                  "matchday": 2,
                  "homeTeam": { "name": "Liverpool FC" },
                  "awayTeam": { "name": "Everton FC" },
                  "score": { "fullTime": { "home": null, "away": null } }
                }
              ]
            }
            """;

    private FootballDataSyncService serviceWith(boolean isCurrentSeason) {
        SeasonResolver mockResolver = mock(SeasonResolver.class);
        SeasonContext context = new SeasonContext(
                99, "2026-27", isCurrentSeason, "2026-27", LocalDate.of(2026, 8, 21));
        when(mockResolver.resolve()).thenReturn(context);
        FootballDataSource source = () -> SAMPLE_RESPONSE;
        return new FootballDataSyncService(source, mockResolver, jdbc, tableName);
    }

    @Test
    void syncsOnlyFinishedMatchesWhenTheResolvedSeasonIsTheOneInProgress() {
        int written = serviceWith(true).sync();

        assertThat(written).isEqualTo(1); // only the FINISHED match

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM " + tableName + " WHERE source = '" + SOURCE_TAG + "'");
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("season")).isEqualTo("2026-27");
        assertThat(rows.getFirst().get("home_goals")).isEqualTo(2);
    }

    @Test
    void doesNothingWhenTheResolvedSeasonIsACompletedFallback() {
        // This is the precedence guard: vaastav is authoritative for completed seasons, so
        // football-data must never write into one just because SeasonResolver fell back to it.
        int written = serviceWith(false).sync();

        assertThat(written).isEqualTo(0);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + tableName + " WHERE source = '" + SOURCE_TAG + "'", Integer.class);
        assertThat(count).isZero();
    }

    @Test
    void reRunningIsIdempotent() {
        FootballDataSyncService service = serviceWith(true);
        service.sync();
        service.sync();

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + tableName + " WHERE source = '" + SOURCE_TAG + "'", Integer.class);
        assertThat(count).isEqualTo(1);
    }
}
