package dev.quantti.fplassistant.historical;

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

import dev.quantti.fplassistant.tools.SeasonContext;
import dev.quantti.fplassistant.tools.SeasonResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Against the isolated test table. Uses a mocked {@link SeasonResolver} rather than the real
 * one, because — as of this writing — the real one resolves to 2025-26 (2026-27 has no
 * gameweek/player data yet). The write guard keys on the season the <b>response</b> serves
 * versus the season the database flags current, so both branches are exercised here by varying
 * the context's flagged-current code — hoping the calendar cooperates is not a test strategy.
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
                  "season": { "startDate": "2026-08-21" },
                  "homeTeam": { "name": "Arsenal FC" },
                  "awayTeam": { "name": "Chelsea FC" },
                  "score": { "fullTime": { "home": 2, "away": 1 } }
                },
                {
                  "utcDate": "2026-08-23T14:00:00Z",
                  "status": "SCHEDULED",
                  "matchday": 2,
                  "season": { "startDate": "2026-08-21" },
                  "homeTeam": { "name": "Liverpool FC" },
                  "awayTeam": { "name": "Everton FC" },
                  "score": { "fullTime": { "home": null, "away": null } }
                }
              ]
            }
            """;

    private FootballDataSyncService serviceWith(SeasonContext context) {
        SeasonResolver mockResolver = mock(SeasonResolver.class);
        when(mockResolver.resolve()).thenReturn(context);
        FootballDataSource source = () -> SAMPLE_RESPONSE;
        return new FootballDataSyncService(source, mockResolver, jdbc, tableName);
    }

    /**
     * What {@code SeasonResolver} returns while tapas has not synced the new season: resolved
     * to the completed previous season, with the flagged-current season code carried alongside.
     */
    private static SeasonContext completedFallbackContext() {
        return new SeasonContext(99, "2025-26", false, "2026-27", LocalDate.of(2026, 8, 21));
    }

    @Test
    void writesFinishedMatchesEvenWhenTheResolverIsOnACompletedFallback() {
        // Production's exact shape at the 2026-27 kickoff: the resolver has fallen back (tapas
        // has synced nothing), but the response serves the season being played — and must be
        // written, or getTeamForm reports May 2026 as the freshest match for weeks.
        int written = serviceWith(completedFallbackContext()).sync();

        assertThat(written).isEqualTo(1); // only the FINISHED match

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM " + tableName + " WHERE source = '" + SOURCE_TAG + "'");
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("season")).isEqualTo("2026-27");
        assertThat(rows.getFirst().get("home_goals")).isEqualTo(2);
    }

    @Test
    void skipsWhenTheResponseServesADifferentSeasonThanTheDatabaseFlagsCurrent() {
        // The precedence guard's remaining form: over the summer the API can lag the calendar
        // and still serve the just-finished season; writing it would double-source matches
        // vaastav owns. The season is taken from the payload, so a mismatch skips the response.
        SeasonContext nextSeasonFlagged =
                new SeasonContext(99, "2026-27", true, "2027-28", LocalDate.of(2027, 8, 20));

        int written = serviceWith(nextSeasonFlagged).sync();

        assertThat(written).isEqualTo(0);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + tableName + " WHERE source = '" + SOURCE_TAG + "'", Integer.class);
        assertThat(count).isZero();
    }

    @Test
    void reRunningIsIdempotent() {
        FootballDataSyncService service = serviceWith(completedFallbackContext());
        service.sync();
        service.sync();

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + tableName + " WHERE source = '" + SOURCE_TAG + "'", Integer.class);
        assertThat(count).isEqualTo(1);
    }
}
