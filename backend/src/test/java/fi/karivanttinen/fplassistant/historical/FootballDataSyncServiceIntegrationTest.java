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
import java.util.concurrent.atomic.AtomicInteger;

import fi.karivanttinen.fplassistant.testsupport.TapasSeed;
import fi.karivanttinen.fplassistant.tools.SeasonContext;
import fi.karivanttinen.fplassistant.tools.SeasonResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Against the isolated test table. Mostly uses a mocked {@link SeasonResolver} rather than the
 * real one, because — as of this writing — the real one resolves to 2025-26 (2026-27 has no
 * gameweek/player data yet). The write guard keys on the season each <b>match</b> serves versus
 * the season the database flags current, so every branch is exercised here by varying the
 * context and the payload — hoping the calendar cooperates is not a test strategy. One test
 * deliberately uses the real resolver: the two sides of the comparison come from different
 * owners, and only that test proves they still agree.
 */
@SpringBootTest
@ActiveProfiles("test")
class FootballDataSyncServiceIntegrationTest {

    private static final String SOURCE_TAG = "football-data";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SeasonResolver realSeasonResolver;

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
        return serviceWith(context, SAMPLE_RESPONSE);
    }

    private FootballDataSyncService serviceWith(SeasonContext context, String responseBody) {
        SeasonResolver mockResolver = mock(SeasonResolver.class);
        when(mockResolver.resolve()).thenReturn(context);
        FootballDataSource source = () -> responseBody;
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
        int written = serviceWith(completedFallbackContext(), lastSeasonResponse()).sync();

        assertThat(written).isEqualTo(0);
        assertThat(rowCount()).isZero();
    }

    /**
     * A response carrying two seasons — a rollover, or a filtered fetch — must contribute only
     * its flagged-current half. Stamping the first match's season on every row would write last
     * season's results under this season's key, overwriting vaastav on the same natural key:
     * the precise corruption the guard exists to prevent.
     */
    @Test
    void aMixedSeasonResponseWritesOnlyTheCurrentSeasonsMatches() {
        String mixed = """
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
                      "utcDate": "2026-05-24T14:00:00Z",
                      "status": "FINISHED",
                      "matchday": 38,
                      "season": { "startDate": "2025-08-15" },
                      "homeTeam": { "name": "Liverpool FC" },
                      "awayTeam": { "name": "Everton FC" },
                      "score": { "fullTime": { "home": 4, "away": 0 } }
                    }
                  ]
                }
                """;

        int written = serviceWith(completedFallbackContext(), mixed).sync();

        assertThat(written).isEqualTo(1);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM " + tableName + " WHERE source = '" + SOURCE_TAG + "'");
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("season")).isEqualTo("2026-27");
        assertThat(rows.getFirst().get("away_goals")).isEqualTo(1); // Arsenal-Chelsea, not the 4-0
    }

    /** A match the parser could not date is skipped, not written under a guessed season. */
    @Test
    void aMatchWithNoSeasonInThePayloadIsSkippedWithoutStoppingTheRest() {
        String oneUndated = """
                {
                  "matches": [
                    {
                      "utcDate": "2026-08-22T14:00:00Z",
                      "status": "FINISHED",
                      "matchday": 1,
                      "homeTeam": { "name": "Liverpool FC" },
                      "awayTeam": { "name": "Everton FC" },
                      "score": { "fullTime": { "home": 1, "away": 1 } }
                    },
                    {
                      "utcDate": "2026-08-22T16:30:00Z",
                      "status": "FINISHED",
                      "matchday": 1,
                      "season": { "startDate": "2026-08-21" },
                      "homeTeam": { "name": "Arsenal FC" },
                      "awayTeam": { "name": "Chelsea FC" },
                      "score": { "fullTime": { "home": 2, "away": 1 } }
                    }
                  ]
                }
                """;

        int written = serviceWith(completedFallbackContext(), oneUndated).sync();

        assertThat(written).isEqualTo(1);
        assertThat(rowCount()).isEqualTo(1);
    }

    /**
     * Before kickoff the API can only be serving the season vaastav owns, so nothing is fetched
     * at all. Without this, every cold start would spend a request on a response that is then
     * discarded — and since a skipped sync writes nothing, {@code StartupSyncRunner}'s staleness
     * marker never advances, so the next start would fetch again.
     */
    @Test
    void nothingIsFetchedBeforeTheFlaggedSeasonHasKickedOff() {
        AtomicInteger fetches = new AtomicInteger();
        SeasonResolver mockResolver = mock(SeasonResolver.class);
        when(mockResolver.resolve()).thenReturn(
                new SeasonContext(99, "2026-27", true, "2027-28", LocalDate.now().plusDays(1)));
        FootballDataSource counting = () -> {
            fetches.incrementAndGet();
            return SAMPLE_RESPONSE;
        };

        int written = new FootballDataSyncService(counting, mockResolver, jdbc, tableName).sync();

        assertThat(written).isZero();
        assertThat(fetches).hasValue(0);
    }

    /**
     * The guard compares a season code derived from the payload against tapas' own
     * {@code season.code}, which this project does not own. Every other test hand-writes both
     * sides, so this one runs the real resolver against a real {@code season} row: if the two
     * formats ever stop comparing equal, the sync becomes a permanent silent no-op, which is
     * the same weeks-of-stale-results bug it was written to fix.
     */
    @Test
    void theRealResolversSeasonCodeStillComparesEqualToThePayloads() {
        TapasSeed.ensure(jdbc);

        int written = new FootballDataSyncService(
                () -> SAMPLE_RESPONSE, realSeasonResolver, jdbc, tableName).sync();

        assertThat(written)
                .as("season code '%s' from the database must name the same season as the"
                        + " payload's '2026-27'", realSeasonResolver.resolve().currentSeasonCode())
                .isEqualTo(1);
    }

    private Integer rowCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + tableName + " WHERE source = '" + SOURCE_TAG + "'",
                Integer.class);
    }

    /** The summer shape: the API still serving the season vaastav owns. */
    private static String lastSeasonResponse() {
        return SAMPLE_RESPONSE.replace("2026-08-21", "2025-08-15");
    }

    @Test
    void reRunningIsIdempotent() {
        FootballDataSyncService service = serviceWith(completedFallbackContext());
        service.sync();
        service.sync();

        assertThat(rowCount()).isEqualTo(1);
    }
}
