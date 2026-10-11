package dev.quantti.fplassistant.tools;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import dev.quantti.fplassistant.sync.CatalogueScheduleParser;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.FixtureRow;
import dev.quantti.fplassistant.sync.FixtureVerification;
import dev.quantti.fplassistant.sync.GameweekFinalityReader;
import dev.quantti.fplassistant.sync.HistoryParser;
import dev.quantti.fplassistant.sync.IncrementalAppearanceReceipt;
import dev.quantti.fplassistant.sync.IncrementalPlayerFixturePublisher;
import dev.quantti.fplassistant.sync.PlayerFixtureFingerprint;
import dev.quantti.fplassistant.testsupport.DataAgeClock;
import dev.quantti.fplassistant.testsupport.TapasSeed;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ExpectedStatsTools} against the seeded {@code player_fixture_stats} rows. The real
 * {@link SeasonResolver} resolves to the completed seeded season, which is fine here — these
 * tests exercise the aggregation and formatting, and the provenance line correctly labels the
 * data as historical.
 */
@SpringBootTest
@ActiveProfiles("test")
class ExpectedStatsToolsIntegrationTest {

    static final Instant AS_OF = DataAgeClock.AS_OF;
    private static final int INCREMENTAL_FIXTURE_ID = 9_991_001;
    private static final int LIVE_UNFINISHED_FIXTURE_ID = 9_991_002;
    private static final String READER_FIXTURES = "expected_reader_fixture_test";
    private static final String READER_EVIDENCE = "expected_reader_evidence_test";
    private static final String READER_CURRENT = "expected_reader_current_test";
    private static final String READER_RECEIPT = "expected_reader_receipt_test";

    @Autowired
    JdbcTemplate jdbc;

    /** Built with the fixed clock rather than autowired; see {@link DataAgeClock}. */
    ExpectedStatsTools tools;

    @Autowired
    SeasonResolver seasonResolver;

    @Autowired
    PlayerNameSearch names;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    GameweekFinalityReader finalityReader;

    @BeforeEach
    void buildTools() {
        tools = new ExpectedStatsTools(jdbc, seasonResolver, DataAgeClock.provider(), names);
    }

    @BeforeEach
    void seed() {
        TapasSeed.ensure(jdbc);
    }

    @Test
    void getPlayerExpectedStatsReturnsAggregatesForAKnownPlayer() {
        String result = tools.getPlayerExpectedStats(TapasSeed.PLAYER_WEB_NAME, 5);

        assertThat(result).contains("Expected stats for");
        assertThat(result).contains("xG");
        assertThat(result).contains("Totals over window");
        assertThat(result).contains("Actual vs expected goal involvements");
        assertThat(result).contains("Data source: season");
    }

    @Test
    void getTeamExpectedGoalsReportsBothAttackAndDefence() {
        String result = tools.getTeamExpectedGoals(TapasSeed.ASHFIELD, 5);

        assertThat(result).contains("Expected output for");
        assertThat(result).contains("Attack over window");
        assertThat(result).contains("Defence over window");
        assertThat(result).contains("xGA");
        assertThat(result).contains("conceded");
        assertThat(result).contains("Data source: season");
    }

    @Test
    void incrementalTeamTotalsHideAnIncompletePositiveRosterThenIncludeTheCompletedProvisionalSide() {
        createIncrementalTeamReaderTables();
        int seasonId = TapasSeed.POPULATED_SEASON_ID;
        int home = TapasSeed.TEAM_ASHFIELD;
        int away = 9002;
        Instant kickoff = Instant.parse("2025-08-26T14:00:00Z");
        jdbc.update("""
                INSERT INTO fixture (id,season_id,gameweek,code,team_h,team_a,team_h_score,team_a_score,
                                     kickoff_time,started,finished)
                VALUES (?,?,1,?,?,?,1,0,?,true,true)
                """, INCREMENTAL_FIXTURE_ID, seasonId, INCREMENTAL_FIXTURE_ID,
                home, away, Timestamp.from(kickoff));
        seedTeamRow(9201, INCREMENTAL_FIXTURE_ID, home, away, true, kickoff, 30, 0.10, 0);
        seedTeamRow(9203, INCREMENTAL_FIXTURE_ID, home, away, true, kickoff, 90, 0.90, 1);
        seedTeamRow(9202, INCREMENTAL_FIXTURE_ID, away, home, false, kickoff, 90, 0.25, 0);
        jdbc.update("UPDATE player_fixture_stats SET expected_goals_conceded=0.80"
                        + " WHERE season_id=? AND fixture_id=? AND player_id IN (9201,9203)",
                seasonId, INCREMENTAL_FIXTURE_ID);
        jdbc.update("UPDATE player_fixture_stats SET expected_goals_conceded=1.10"
                        + " WHERE season_id=? AND fixture_id=? AND player_id=9202",
                seasonId, INCREMENTAL_FIXTURE_ID);

        Instant sourceStarted = AS_OF.minusSeconds(600);
        Instant sourceFinished = sourceStarted.plusSeconds(1);
        createAppearanceSnapshot(INCREMENTAL_FIXTURE_ID, 10, Map.of(9201, 30, 9203, 90, 9202, 90),
                sourceStarted, sourceFinished);
        seedOwnedTeamRow(9201, INCREMENTAL_FIXTURE_ID, 10, sourceStarted, sourceFinished);
        seedOwnedTeamRow(9202, INCREMENTAL_FIXTURE_ID, 10, sourceStarted, sourceFinished);
        jdbc.update("INSERT INTO " + READER_FIXTURES + " (season_id,fixture_id,state,"
                        + "observation_generation,last_verified_generation) VALUES (?,?,'WAITING_STATS',10,9)",
                seasonId, INCREMENTAL_FIXTURE_ID);

        SeasonContext current = new SeasonContext(seasonId, TapasSeed.POPULATED_SEASON_CODE,
                true, TapasSeed.POPULATED_SEASON_CODE, java.time.LocalDate.of(2025, 8, 15));
        SeasonResolver resolver = Mockito.mock(SeasonResolver.class);
        Mockito.when(resolver.resolve()).thenReturn(current);
        Mockito.when(resolver.previousSeason(current)).thenReturn(Optional.empty());
        PfsReadSource reader = new PfsReadSource(jdbc, "OFF", current.code(), READER_FIXTURES,
                READER_EVIDENCE, READER_CURRENT, READER_RECEIPT);
        ExpectedStatsTools incrementalTools = new ExpectedStatsTools(jdbc, resolver,
                DataAgeClock.provider(), reader, names, finalityReader, transactionManager);

        String partial = incrementalTools.getTeamExpectedGoals(TapasSeed.ASHFIELD, 3);
        assertThat(partial).contains("fixture " + INCREMENTAL_FIXTURE_ID,
                "incomplete positive-appearance evidence", "n/a (incomplete team-side evidence)")
                .doesNotContain("xG 0.10");

        seedOwnedTeamRow(9203, INCREMENTAL_FIXTURE_ID, 11, sourceStarted, sourceFinished);
        String complete = incrementalTools.getTeamExpectedGoals(TapasSeed.ASHFIELD, 3);
        assertThat(complete).contains("xG 1.00", "(scored 1)", "final import pending/incomplete",
                        "Incremental rows in the shown fixture window: 2.")
                .doesNotContain("FPL checked; final import completed");
    }

    /**
     * The one aggregation in these tools that is not a plain SUM. FPL writes the team's
     * expected-goals-conceded and goals-conceded onto <em>every</em> player who was on the pitch,
     * so summing them multiplies the team's figure by its squad size; the tool uses MAX instead.
     * This pins that choice against the {@code fixture} table's own scoreline. The seed carries
     * the same shape: goals_conceded is the team figure repeated on each player row.
     */
    @Test
    void goalsConcededPerFixtureMatchesTheScoreline() {
        int seasonId = seasonResolver.resolve().seasonId();
        int teamId = TapasSeed.TEAM_ASHFIELD;

        List<Map<String, Object>> checked = jdbc.queryForList("""
                SELECT f.id,
                       CASE WHEN f.team_h = ? THEN f.team_a_score ELSE f.team_h_score END AS scoreline_conceded,
                       (SELECT MAX(pfs.goals_conceded) FROM player_fixture_stats pfs
                        WHERE pfs.season_id = f.season_id AND pfs.fixture_id = f.id
                          AND pfs.player_team_id = ?) AS aggregated_conceded
                FROM fixture f
                WHERE f.season_id = ? AND (f.team_h = ? OR f.team_a = ?) AND f.finished = true
                ORDER BY f.kickoff_time DESC
                LIMIT 10
                """, teamId, teamId, seasonId, teamId, teamId);

        assertThat(checked).as("expected finished fixtures to cross-check against").isNotEmpty();
        for (Map<String, Object> row : checked) {
            assertThat(row.get("aggregated_conceded"))
                    .as("fixture %s: MAX(goals_conceded) must equal the goals the team actually conceded",
                            row.get("id"))
                    .isEqualTo(row.get("scoreline_conceded"));
        }
    }

    @Test
    void unknownPlayerReturnsNotFound() {
        assertThat(tools.getPlayerExpectedStats("Definitely Not A Real Player", 5))
                .contains("Player not found");
    }

    @Test
    void unknownTeamReturnsNotFound() {
        assertThat(tools.getTeamExpectedGoals("Definitely Not A Real Club", 5))
                .contains("Team not found");
    }

    @Test
    void anAmbiguousTeamNameSaysWhichOtherClubsItMatched() {
        // The seeded league has two Northmoor clubs; answering for one silently reads as the
        // only match.
        String result = tools.getTeamExpectedGoals(TapasSeed.AMBIGUOUS_TEAM_PREFIX, 3);

        assertThat(result).contains("The name also matched:");
    }

    private void createIncrementalTeamReaderTables() {
        jdbc.execute("CREATE TABLE " + READER_FIXTURES + " (season_id INTEGER NOT NULL, fixture_id INTEGER NOT NULL,"
                + " state VARCHAR(24) NOT NULL, observation_generation BIGINT, current_verified_generation BIGINT,"
                + " last_verified_generation BIGINT, last_verified_at TIMESTAMPTZ,"
                + " PRIMARY KEY(season_id,fixture_id))");
        jdbc.execute("CREATE TABLE " + READER_EVIDENCE + " (season_id INTEGER NOT NULL, season_code VARCHAR(10)"
                + " NOT NULL, fixture_id INTEGER NOT NULL, player_id INTEGER NOT NULL, generation BIGINT NOT NULL,"
                + " component_presence BIGINT NOT NULL, source_read_started_at TIMESTAMPTZ NOT NULL,"
                + " source_read_finished_at TIMESTAMPTZ NOT NULL, PRIMARY KEY(season_id,fixture_id,player_id,generation))");
        jdbc.execute("CREATE TABLE " + READER_CURRENT + " (season_id INTEGER NOT NULL, season_code VARCHAR(10)"
                + " NOT NULL, fixture_id INTEGER NOT NULL, player_id INTEGER NOT NULL, row_state VARCHAR(12) NOT NULL,"
                + " source_run_generation BIGINT NOT NULL, evidence_generation BIGINT, row_fingerprint VARCHAR(64),"
                + " physical_fingerprint VARCHAR(64), source_read_started_at TIMESTAMPTZ NOT NULL,"
                + " source_read_finished_at TIMESTAMPTZ NOT NULL, PRIMARY KEY(season_id,fixture_id,player_id))");
        jdbc.execute("CREATE TABLE " + READER_RECEIPT + " (season VARCHAR(10) NOT NULL, publication_key VARCHAR(200)"
                + " NOT NULL, latest_generation BIGINT, source_read_started_at TIMESTAMPTZ,"
                + " source_read_finished_at TIMESTAMPTZ, source_pair_fingerprint VARCHAR(64),"
                + " PRIMARY KEY(season,publication_key))");
    }

    private void seedTeamRow(int playerId, int fixtureId, int club, int opponent, boolean home,
                             Instant kickoff, int minutes, double xg, int goals) {
        jdbc.update("""
                INSERT INTO player_fixture_stats (fixture_id,player_id,season_id,gameweek,player_team_id,
                    opponent_team_id,was_home,kickoff_time,minutes,total_points,goals_scored,assists,
                    expected_goals,expected_assists,expected_goal_involvements,goals_conceded,
                    expected_goals_conceded,value,starts)
                VALUES (?,?,?,1,?,?,?,?,?,4,?,0,?,0.10,?,0,0.80,50,1)
                """, fixtureId, playerId, TapasSeed.POPULATED_SEASON_ID, club, opponent, home,
                Timestamp.from(kickoff), minutes, goals, xg, xg);
    }

    private void createAppearanceSnapshot(int fixtureId, long generation, Map<Integer, Integer> minutes,
                                          Instant started, Instant finished) {
        FixtureRow fixture = sourceFixture(fixtureId);
        int gameweek = fixture.gameweek();
        CatalogueScheduleParser.ReadInterval read = new CatalogueScheduleParser.ReadInterval(started, finished);
        CatalogueScheduleParser.ReadInterval parentRead = new CatalogueScheduleParser.ReadInterval(
                started.minusSeconds(120), started.minusSeconds(60));
        insertReceipt(IncrementalAppearanceReceipt.sourceFixtureKey(fixtureId), generation, parentRead,
                IncrementalAppearanceReceipt.fixtureRevisionFingerprint(fixture, gameweek));
        Map<Integer, FixtureVerification.Appearance> appearances = new java.util.HashMap<>();
        minutes.forEach((playerId, played) -> {
            Boolean isHome = jdbc.queryForObject("SELECT was_home FROM player_fixture_stats"
                    + " WHERE season_id=? AND fixture_id=? AND player_id=?",
                    Boolean.class, TapasSeed.POPULATED_SEASON_ID, fixtureId, playerId);
            appearances.put(playerId, new FixtureVerification.Appearance(isHome
                    ? FixtureVerification.Side.HOME : FixtureVerification.Side.AWAY, played));
        });
        FixtureVerification.FixtureEvidence evidence = new FixtureVerification.FixtureEvidence(true,
                minutes.keySet(), appearances, read);
        String snapshot = IncrementalAppearanceReceipt.snapshotKey(fixture, gameweek, evidence, read);
        insertReceipt(IncrementalAppearanceReceipt.LIVE_EVENT_PREFIX + gameweek,
                generation, read, null);
        insertReceipt(IncrementalAppearanceReceipt.liveSourceKey(gameweek, generation,
                started, finished), generation, read, null);
        String fullFingerprint = IncrementalAppearanceReceipt.fingerprint(fixture, gameweek, evidence);
        insertReceipt(snapshot, generation, read, fullFingerprint);
        List<String> previousAppearanceState = jdbc.query("SELECT source_pair_fingerprint FROM " + READER_RECEIPT
                        + " WHERE season=? AND publication_key=?", (rs, row) -> rs.getString(1),
                TapasSeed.POPULATED_SEASON_CODE, IncrementalAppearanceReceipt.appearanceStateKey(fixtureId));
        if (previousAppearanceState.isEmpty()
                || !fullFingerprint.equals(previousAppearanceState.getFirst())) {
            insertReceipt(IncrementalAppearanceReceipt.appearanceStateKey(fixtureId), generation, read,
                    fullFingerprint);
        }
        evidence.appearances().forEach((playerId, appearance) -> insertReceipt(
                IncrementalAppearanceReceipt.playerKey(fixtureId, playerId, appearance, read),
                generation, read, null));
    }

    private FixtureRow sourceFixture(int fixtureId) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT f.id,f.code,f.gameweek,f.team_h,f.team_a,h.name AS home_name,h.short_name AS home_short,
                       a.name AS away_name,a.short_name AS away_short,f.team_h_score,f.team_a_score,
                       f.team_h_difficulty,f.team_a_difficulty,f.kickoff_time,f.started,f.finished,
                       f.finished_provisional,f.minutes,f.stats::text AS stats
                FROM fixture f JOIN team h ON h.id=f.team_h AND h.season_id=f.season_id
                JOIN team a ON a.id=f.team_a AND a.season_id=f.season_id
                WHERE f.season_id=? AND f.id=?
                """, TapasSeed.POPULATED_SEASON_ID, fixtureId);
        Map<String, Object> fields = new java.util.HashMap<>();
        fields.put("finished_provisional", row.get("finished_provisional"));
        fields.put("minutes", ((Number) row.get("minutes")).intValue());
        fields.put("stats", row.get("stats"));
        return new FixtureRow(TapasSeed.POPULATED_SEASON_ID, TapasSeed.POPULATED_SEASON_CODE,
                fixtureId, ((Number) row.get("code")).intValue(), (Integer) row.get("gameweek"),
                ((Number) row.get("team_h")).intValue(), ((Number) row.get("team_a")).intValue(),
                String.valueOf(row.get("home_name")), String.valueOf(row.get("home_short")),
                String.valueOf(row.get("away_name")), String.valueOf(row.get("away_short")),
                (Integer) row.get("team_h_score"), (Integer) row.get("team_a_score"),
                (Integer) row.get("team_h_difficulty"), (Integer) row.get("team_a_difficulty"),
                ((Timestamp) row.get("kickoff_time")).toInstant(), (Boolean) row.get("started"),
                (Boolean) row.get("finished"), fields);
    }

    private void seedOwnedTeamRow(int playerId, int fixtureId, long generation,
                                  Instant started, Instant finished) {
        started = finished.plusSeconds(1);
        finished = started.plusSeconds(1);
        int seasonId = TapasSeed.POPULATED_SEASON_ID;
        long presence = HistoryParser.Component.EXPECTED_GOALS.bit()
                | HistoryParser.Component.EXPECTED_ASSISTS.bit()
                | HistoryParser.Component.EXPECTED_GOAL_INVOLVEMENTS.bit()
                | HistoryParser.Component.EXPECTED_GOALS_CONCEDED.bit();
        jdbc.update("INSERT INTO " + READER_EVIDENCE + " (season_id,season_code,fixture_id,player_id,"
                        + "generation,component_presence,source_read_started_at,source_read_finished_at)"
                        + " VALUES (?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                seasonId, TapasSeed.POPULATED_SEASON_CODE, fixtureId, playerId, generation, presence,
                Timestamp.from(started), Timestamp.from(finished));
        String physical = jdbc.queryForObject("SELECT "
                + PlayerFixtureFingerprint.physicalSqlExpression("p", "s", Long.toString(presence))
                + " FROM player_fixture_stats p JOIN season s ON s.id=p.season_id"
                + " WHERE p.season_id=? AND p.fixture_id=? AND p.player_id=?",
                String.class, seasonId, fixtureId, playerId);
        jdbc.update("INSERT INTO " + READER_CURRENT + " (season_id,season_code,fixture_id,player_id,"
                        + "row_state,source_run_generation,evidence_generation,row_fingerprint,physical_fingerprint,"
                        + "source_read_started_at,source_read_finished_at) VALUES (?,?,?,?,'VALID',?,?,?,?,?,?)"
                        + " ON CONFLICT (season_id,fixture_id,player_id) DO UPDATE SET"
                        + " source_run_generation=EXCLUDED.source_run_generation,"
                        + " evidence_generation=EXCLUDED.evidence_generation,row_fingerprint=EXCLUDED.row_fingerprint,"
                        + " physical_fingerprint=EXCLUDED.physical_fingerprint,"
                        + " source_read_started_at=EXCLUDED.source_read_started_at,"
                        + " source_read_finished_at=EXCLUDED.source_read_finished_at",
                seasonId, TapasSeed.POPULATED_SEASON_CODE, fixtureId, playerId, generation, generation,
                "a".repeat(64), physical, Timestamp.from(started), Timestamp.from(finished));
        insertReceipt(IncrementalPlayerFixturePublisher.pageReceiptKey(playerId), generation,
                new CatalogueScheduleParser.ReadInterval(started, finished), null);
    }

    private void insertReceipt(String key, long generation, CatalogueScheduleParser.ReadInterval read,
                               String fingerprint) {
        jdbc.update("INSERT INTO " + READER_RECEIPT + " (season,publication_key,latest_generation,"
                        + "source_read_started_at,source_read_finished_at,source_pair_fingerprint)"
                        + " VALUES (?,?,?,?,?,?) ON CONFLICT (season,publication_key) DO UPDATE SET"
                        + " latest_generation=EXCLUDED.latest_generation,"
                        + " source_read_started_at=EXCLUDED.source_read_started_at,"
                        + " source_read_finished_at=EXCLUDED.source_read_finished_at,"
                        + " source_pair_fingerprint=EXCLUDED.source_pair_fingerprint",
                TapasSeed.POPULATED_SEASON_CODE, key, generation, Timestamp.from(read.startedAt()),
                Timestamp.from(read.completedAt()), fingerprint);
    }

    /** Id this class inserts for KAR-57 accent/club-hint coverage, and removes again. */
    private static final int ACCENTED_PLAYER_ID = 9311;

    private void seedAccentedPlayer() {
        jdbc.update("""
                INSERT INTO player (id, season_id, team_id, first_name, second_name, web_name,
                                    element_type, now_cost, status, total_points, minutes,
                                    goals_scored, assists, form, selected_by_percent)
                VALUES (9311, ?, ?, 'Tarik', 'Muharemović', 'Muharemović', 2, 45, 'a', 20, 360,
                        0, 0, 3.0, 1.5)
                ON CONFLICT (id, season_id) DO NOTHING
                """, TapasSeed.POPULATED_SEASON_ID, TapasSeed.TEAM_ASHFIELD);
    }

    @AfterEach
    void deleteAccentedPlayer() {
        jdbc.update("DELETE FROM player WHERE season_id = ? AND id = ?",
                TapasSeed.POPULATED_SEASON_ID, ACCENTED_PLAYER_ID);
    }

    @AfterEach
    void cleanLiveUnfinishedFixture() {
        jdbc.update("DELETE FROM fixture WHERE season_id=? AND id=?",
                TapasSeed.POPULATED_SEASON_ID, LIVE_UNFINISHED_FIXTURE_ID);
    }

    @AfterEach
    void cleanIncrementalTeamReader() {
        jdbc.update("DELETE FROM player_fixture_stats WHERE season_id=? AND fixture_id=?",
                TapasSeed.POPULATED_SEASON_ID, INCREMENTAL_FIXTURE_ID);
        jdbc.update("DELETE FROM fixture WHERE season_id=? AND id=?",
                TapasSeed.POPULATED_SEASON_ID, INCREMENTAL_FIXTURE_ID);
        jdbc.execute("DROP TABLE IF EXISTS " + READER_RECEIPT);
        jdbc.execute("DROP TABLE IF EXISTS " + READER_CURRENT);
        jdbc.execute("DROP TABLE IF EXISTS " + READER_EVIDENCE);
        jdbc.execute("DROP TABLE IF EXISTS " + READER_FIXTURES);
    }

    @Test
    void unaccentedFullNameFindsTheAccentedPlayersExpectedStats() {
        seedAccentedPlayer();
        String result = tools.getPlayerExpectedStats("Tarik Muharemovic", 5);
        // The web name, not only the absence of "Player not found": the database-error reply
        // satisfies that on its own, and would leave a broken query at this site green.
        assertThat(result).contains("Muharemović").doesNotContain("Player not found");
    }

    @Test
    void aClubAfterTheNameStillFindsThePlayersExpectedStats() {
        seedAccentedPlayer();
        String result = tools.getPlayerExpectedStats("Muharemovic Ashfield", 5);
        assertThat(result).contains("Muharemović").doesNotContain("Player not found");
    }

    // --- KAR-9: sample dates and the unknown core-sync time --------------------------------

    private static final String STORED_ROWS_ONLY =
            " Stored rows only; this does not show that every match through that date is present.";
    private static final String METRIC_REFRESH = "Metric refresh: season "
            + TapasSeed.POPULATED_SEASON_CODE + ", returned sample;";

    /** The notice and coverage sit after the season banner and before the first numeric row. */
    @Test
    void playerCoverageNamesTheReturnedMatchDatesBeforeTheRows() {
        String result = tools.getPlayerExpectedStats(TapasSeed.PLAYER_WEB_NAME, 5);

        String coverage = "Stored match coverage: Okafor, season " + TapasSeed.POPULATED_SEASON_CODE
                + " (3 returned fixture rows), known match dates from 2025-08-16 15:00 UK (389 days ago)"
                + " to 2025-08-24 15:00 UK (381 days ago)." + STORED_ROWS_ONLY;
        assertThat(result).containsOnlyOnce(METRIC_REFRESH).containsOnlyOnce(coverage);
        int banner = result.indexOf("Data source: season " + TapasSeed.POPULATED_SEASON_CODE);
        assertThat(banner).isZero();
        assertThat(result.indexOf(METRIC_REFRESH)).isGreaterThan(banner)
                .isLessThan(result.indexOf(coverage));
        assertThat(result.indexOf(coverage)).isLessThan(result.indexOf("Expected stats for"));
        assertThat(result).doesNotContain("Stored-data age").doesNotContain("Data source: season "
                + TapasSeed.CURRENT_SEASON_CODE);
    }

    /** Coverage describes the limited window that was returned, not the player's whole history. */
    @Test
    void coverageDescribesOnlyTheReturnedWindow() {
        String result = tools.getPlayerExpectedStats(TapasSeed.PLAYER_WEB_NAME, 2);

        assertThat(result).contains("Stored match coverage: Okafor, season " + TapasSeed.POPULATED_SEASON_CODE
                + " (2 returned fixture rows), known match dates from 2025-08-23 15:00 UK (382 days ago)"
                + " to 2025-08-24 15:00 UK (381 days ago).");
        assertThat(result).doesNotContain("2025-08-16");
    }

    @Test
    void teamCoverageCountsStoredPlayerRowsSeparatelyFromFixtures() {
        String result = tools.getTeamExpectedGoals(TapasSeed.ASHFIELD, 5);

        // Ashfield: 9101 (2 player rows), 9103 (2), 9110 (2).
        assertThat(result).containsOnlyOnce(METRIC_REFRESH).contains(
                "Stored match coverage: " + TapasSeed.ASHFIELD + ", season " + TapasSeed.POPULATED_SEASON_CODE
                        + " (3 returned fixtures from 6 stored player rows), known match dates from"
                        + " 2025-08-16 15:00 UK (389 days ago) to 2025-08-24 15:00 UK (381 days ago)."
                        + STORED_ROWS_ONLY);
        assertThat(result.indexOf("Stored match coverage")).isLessThan(result.indexOf("Expected output for"));
    }

    @Test
    void noStoredRowsIsNotAZeroDayOldRecord() {
        String team = tools.getTeamExpectedGoals(TapasSeed.TEAM_WITHOUT_FIXTURES, 5);
        assertThat(team).contains("No fixture-level expected stats yet").contains(METRIC_REFRESH)
                .contains("Stored match coverage: Woodbridge Athletic, season " + TapasSeed.POPULATED_SEASON_CODE
                        + ", no stored rows used; age unavailable.")
                .doesNotContain(" ago");

        String player = tools.getPlayerExpectedStats(TapasSeed.PLAYER_WITHOUT_UNDERLYING, 5);
        assertThat(player).contains("No fixture-level expected stats yet")
                .contains("Stored match coverage: Novak, season " + TapasSeed.POPULATED_SEASON_CODE
                        + ", no stored rows used; age unavailable.")
                .doesNotContain(" ago");
    }

    // --- KAR-58: defensive contribution on the named-player reply --------------------------

    private void seedDefconPlayer(int elementType) {
        jdbc.update("""
                INSERT INTO player (id, season_id, team_id, first_name, second_name, web_name,
                                    element_type, now_cost, status, total_points, minutes)
                VALUES (9291, 9026, 9004, 'Aksel', 'Lindqvist', 'Lindqvist', ?, 50, 'a', 9, 270)
                """, elementType);
    }

    private void seedDefconRow(int fixtureId, int gameweek, String kickoff, int minutes, Integer defcon) {
        jdbc.update("""
                INSERT INTO player_fixture_stats (fixture_id, player_id, season_id, gameweek,
                        player_team_id, opponent_team_id, was_home, kickoff_time, minutes,
                        goals_scored, assists, expected_goals, expected_assists,
                        expected_goal_involvements, value, defensive_contribution)
                VALUES (?, 9291, 9026, ?, 9004, 9001, true, ?::timestamptz, ?, 0, 0, 0.05, 0.02, 0.07, 50, ?)
                """, fixtureId, gameweek, kickoff, minutes, defcon);
    }

    /**
     * A comparison of two defenders is built from this reply, so it has to carry the DefCon
     * record: each match's count, how often the threshold was reached, and the rate. A match
     * with no reading is unknown, not a miss, and a match he did not play is not an appearance.
     */
    @Test
    @Transactional
    void aDefendersReplyCarriesHisDefconRecord() {
        seedDefconPlayer(2);
        seedDefconRow(9191, 4, "2025-09-01 14:00:00+00", 90, 12);
        seedDefconRow(9192, 5, "2025-09-08 14:00:00+00", 90, 8);
        seedDefconRow(9193, 6, "2025-09-15 14:00:00+00", 90, null);
        seedDefconRow(9194, 7, "2025-09-22 14:00:00+00", 0, 0);

        String result = tools.getPlayerExpectedStats("Lindqvist", 5);

        assertThat(result).contains("GW4 vs ASH (H): 90 min, xG 0.05 xA 0.02 (G0 A0) | DefCon actions 12")
                .contains("GW5 vs ASH (H): 90 min, xG 0.05 xA 0.02 (G0 A0) | DefCon actions 8")
                .contains("(G0 A0) | DefCon actions n/a")
                .contains("Defensive contribution, season " + TapasSeed.POPULATED_SEASON_CODE + " (DEF: 2 pts at 10+ CBIT):"
                        + " reached in 1 of 2 appearances with a reading (1 of 2 at 60+ min)"
                        + " | 10.00 actions per 90 | 1 appearance without a reading.");
    }

    /** A midfielder needs 12, recoveries included, so 11 is a miss where a defender's 11 is a hit. */
    @Test
    @Transactional
    void aMidfieldersDefconIsJudgedAtTwelve() {
        seedDefconPlayer(3);
        seedDefconRow(9191, 4, "2025-09-01 14:00:00+00", 90, 11);
        seedDefconRow(9192, 5, "2025-09-08 14:00:00+00", 45, 13);

        String result = tools.getPlayerExpectedStats("Lindqvist", 5);

        assertThat(result).contains("Defensive contribution, season " + TapasSeed.POPULATED_SEASON_CODE + " (MID: 2 pts at 12+ CBIRT):"
                + " reached in 1 of 2 appearances with a reading (0 of 1 at 60+ min) | 16.00 actions per 90.");
    }

    @Test
    @Transactional
    void noReadingAtAllIsSaidNotShownAsZero() {
        seedDefconPlayer(2);
        seedDefconRow(9191, 4, "2025-09-01 14:00:00+00", 90, null);

        String result = tools.getPlayerExpectedStats("Lindqvist", 5);

        assertThat(result).contains("Defensive contribution, season " + TapasSeed.POPULATED_SEASON_CODE + " (DEF: 2 pts at 10+ CBIT):"
                + " no reading in this window — unknown, not zero.");
    }

    @Test
    @Transactional
    void aGoalkeeperEarnsNoDefcon() {
        seedDefconPlayer(1);
        seedDefconRow(9191, 4, "2025-09-01 14:00:00+00", 90, 3);

        String result = tools.getPlayerExpectedStats("Lindqvist", 5);

        assertThat(result).contains("Defensive contribution, season " + TapasSeed.POPULATED_SEASON_CODE + ": not awarded to goalkeepers.")
                .doesNotContain("DefCon actions");
    }

    /**
     * The real {@code trg_pfs_updated_at} trigger stamps {@code updated_at} on any UPDATE, so a
     * DefCon-only enrichment write makes an old football sample look freshly written. The reply
     * must not move: its sample dates come from kickoff, and the core-sync time stays unknown.
     */
    @Test
    @Transactional
    void aDefConOnlyWriteRefreshesUpdatedAtButNotTheReportedSample() {
        OffsetDateTime oldWrite = OffsetDateTime.parse("2025-09-02T08:00:00Z");
        jdbc.update("""
                INSERT INTO player (id, season_id, team_id, first_name, second_name, web_name,
                                    element_type, now_cost, status, total_points, minutes)
                VALUES (9291, 9026, 9004, 'Aksel', 'Lindqvist', 'Lindqvist', 3, 50, 'a', 9, 90)
                """);
        jdbc.update("""
                INSERT INTO player_fixture_stats (fixture_id, player_id, season_id, gameweek,
                        player_team_id, opponent_team_id, was_home, kickoff_time, minutes,
                        goals_scored, assists, expected_goals, expected_assists,
                        expected_goal_involvements, value, updated_at)
                VALUES (9191, 9291, 9026, 5, 9004, 9001, true, TIMESTAMPTZ '2025-09-01 14:00:00+00',
                        77, 1, 0, 0.42, 0.11, 0.53, 50, ?)
                """, oldWrite);
        String before = tools.getPlayerExpectedStats("Lindqvist", 5);

        int updated = jdbc.update("""
                UPDATE player_fixture_stats SET defensive_contribution = 7
                WHERE fixture_id = 9191 AND player_id = 9291 AND season_id = 9026
                """);
        assertThat(updated).isOne();
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT updated_at, minutes, expected_goals FROM player_fixture_stats
                WHERE fixture_id = 9191 AND player_id = 9291 AND season_id = 9026
                """);
        assertThat(((java.sql.Timestamp) row.get("updated_at")).toInstant())
                .as("the trigger restamps the row").isAfter(oldWrite.toInstant());
        assertThat(((Number) row.get("minutes")).intValue()).isEqualTo(77);
        assertThat(row.get("expected_goals").toString()).isEqualTo("0.42");

        String after = tools.getPlayerExpectedStats("Lindqvist", 5);

        // KAR-58: the write moves the DefCon cell and line, and nothing else in the reply.
        assertThat(before).contains("DefCon actions n/a").contains("no reading in this window");
        assertThat(after).contains("DefCon actions 7").contains("reached in 0 of 1 appearance with a reading");
        assertThat(withoutDefcon(after)).isEqualTo(withoutDefcon(before));
        assertThat(after).contains(METRIC_REFRESH)
                .contains("Stored match coverage: Lindqvist, season " + TapasSeed.POPULATED_SEASON_CODE
                        + " (1 returned fixture row), known match date 2025-09-01 15:00 UK (373 days ago).")
                .contains("77 min, xG 0.42");
        String restamped = UkTime.format(((java.sql.Timestamp) row.get("updated_at")).toInstant(), null);
        assertThat(after).doesNotContain(restamped.substring(0, 10)).doesNotContain("2025-09-02");
    }

    private static String withoutDefcon(String reply) {
        return reply.replaceAll(" \\| DefCon actions \\S+", "")
                .replaceAll("(?m)^Defensive contribution.*\\R", "");
    }

    /**
     * A team aggregate whose source rows are partly or wholly undated: MIN/MAX alone would hide
     * them, so the counts disclose them, and no fixture or total is dropped for want of a date.
     */
    @Test
    void unfinishedLiveFixtureIsLabeledAsCurrentScoreAndNotCompletedMetrics() {
        jdbc.update("""
                INSERT INTO fixture(id,season_id,gameweek,code,team_h,team_a,team_h_score,team_a_score,
                                    kickoff_time,started,finished,finished_provisional,minutes)
                VALUES (?,?,4,?,?,?,1,0,TIMESTAMPTZ '2026-09-10T10:00:00Z',true,false,false,60)
                """, LIVE_UNFINISHED_FIXTURE_ID, TapasSeed.POPULATED_SEASON_ID,
                LIVE_UNFINISHED_FIXTURE_ID, TapasSeed.TEAM_ASHFIELD, 9002);

        String reply = tools.getTeamExpectedGoals(TapasSeed.ASHFIELD, 1);

        assertThat(reply).contains("GW4 vs NMC (H): xG n/a (live, unfinished)")
                .contains("scored 1")
                .contains("[LIVE, unfinished; score so far]")
                .contains("Attack over window: xG n/a (unknown; includes unfinished fixture)")
                .contains("Goals 1 (current score; includes unfinished fixture)")
                .doesNotContain("GW4 vs NMC (H): xG 0.00");
    }

    @Test
    @Transactional
    void partlyAndWhollyUndatedTeamGroupsAreDisclosedNotDropped() {
        jdbc.update("""
                INSERT INTO team (id, season_id, code, name, short_name, strength)
                VALUES (9091, 9026, 9091, 'Quarry Vale', 'QVA', 2)
                """);
        jdbc.update("""
                INSERT INTO player_fixture_stats (fixture_id, player_id, season_id, gameweek,
                        player_team_id, opponent_team_id, was_home, kickoff_time, minutes,
                        goals_scored, expected_goals, expected_assists, expected_goal_involvements,
                        goals_conceded, expected_goals_conceded, value) VALUES
                    (9191, 9291, 9026, 7, 9091, 9001, true,  TIMESTAMPTZ '2025-09-20 14:00:00+00', 90, 1, 0.50, 0.10, 0.60, 2, 1.40, 50),
                    (9191, 9292, 9026, 7, 9091, 9001, true,  NULL,                                  90, 0, 0.20, 0.30, 0.50, 2, 1.40, 50),
                    (9192, 9291, 9026, 6, 9091, 9002, false, NULL,                                  90, 0, 0.10, 0.00, 0.10, 1, 0.70, 50),
                    (9192, 9292, 9026, 6, 9091, 9002, false, NULL,                                  90, 0, 0.30, 0.00, 0.30, 1, 0.70, 50),
                    (9193, 9291, 9026, 5, 9091, 9004, true,  TIMESTAMPTZ '2025-09-13 14:00:00+00', 90, 2, 0.90, 0.00, 0.90, 0, 0.30, 50)
                """);

        String all = tools.getTeamExpectedGoals("Quarry Vale", 3);

        assertThat(all).contains("Stored match coverage: Quarry Vale, season " + TapasSeed.POPULATED_SEASON_CODE
                + " (3 returned fixtures from 5 stored player rows), known match dates from"
                + " 2025-09-13 15:00 UK (361 days ago) to 2025-09-20 15:00 UK (354 days ago);"
                + " 3 of 5 stored rows have no match date.");
        assertThat(all).contains("GW7 vs ASH (H): xG 0.70").contains("GW6 vs NMC (A): xG 0.40")
                .contains("GW5 vs SEL (H): xG 0.90")
                .contains("Attack over window: xG 2.00 | xA 0.40 | xGI 2.40 | Goals 3")
                .contains("Defence over window: xGA 2.40 | Goals conceded 3");

        String latest = tools.getTeamExpectedGoals("Quarry Vale", 1);

        assertThat(latest).contains("(1 returned fixture from 2 stored player rows), known match date"
                + " 2025-09-20 15:00 UK (354 days ago); 1 of 2 stored rows has no match date.");
        assertThat(latest).doesNotContain("2025-09-13");
    }
}
