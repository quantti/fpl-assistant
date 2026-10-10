package dev.quantti.fplassistant.tools;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import dev.quantti.fplassistant.schedule.ScheduleSource;
import dev.quantti.fplassistant.testsupport.FictitiousTestData;
import dev.quantti.fplassistant.testsupport.RankingSeed;
import dev.quantti.fplassistant.testsupport.SharedStatsSeed;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Pick-tool contract against a fresh, complete and entirely test-owned FPL snapshot. */
@SpringBootTest
@ActiveProfiles("test")
@Import(PickToolsIntegrationTest.FixedClock.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PickToolsIntegrationTest {

    private static final Instant AS_OF = Instant.parse("2026-09-09T12:00:00Z");
    private static final Instant CAPTURED_AT = Instant.parse("2026-09-09T10:00:00Z");
    private static final Instant FIRST_DEADLINE = Instant.parse("2026-08-22T17:30:00Z");
    private static final LocalDate CAPTURE_DATE = LocalDate.of(2026, 9, 9);
    private static final int SEASON_ID = SharedStatsSeed.CURRENT_SEASON_ID;
    private static final String SEASON = SharedStatsSeed.CURRENT_SEASON_CODE;
    private static final int PRESENCE_GAMEWEEK = 152;
    private static final int FIRST_ID = 13_000;
    private static final int LAST_ID = 13_099;

    private static final int RELIABLE_MID = 13_001;
    private static final int STRIKER = 13_002;
    private static final int DEFENDER = 13_003;
    private static final int KEEPER = 13_004;
    private static final int CAMEO = 13_005;
    private static final int DOUBTFUL = 13_006;
    private static final int ZERO = 13_007;
    private static final int NO_RECORD = 13_008;
    private static final int UNAVAILABLE = 13_009;
    private static final int DOUBLE_MID = 13_010;
    private static final int NEAR_MID = 13_011;
    private static final int FAR_MID = 13_012;
    private static final int MIXED_MID = 13_013;

    /** A midweek second leg, after every gameweek-6 kickoff the seeded schedule lays down. */
    private static final Instant SECOND_LEG_KICKOFF = Instant.parse("2026-09-29T18:00:00Z");

    /** Where a blanked fixture is replayed: gameweek 9, after that round's deadline. */
    private static final Instant BLANK_REPLAY_KICKOFF = Instant.parse("2026-10-20T18:00:00Z");

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock rankingClock() {
            return Clock.fixed(AS_OF, ZoneOffset.UTC);
        }
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PickTools tools;

    @Autowired
    ScheduleSource scheduleSource;

    @Value("${fpl.daily-price.table-name}")
    String dailyTable;

    @BeforeEach
    void seed() {
        SharedStatsSeed.ensure(jdbc);
        FictitiousTestData.assertSafeSeason(SEASON);
        cleanUp();
        RankingSeed.seedSchedule(jdbc, scheduleSource.gameweekTable(),
                scheduleSource.fixtureTable(), SEASON, CAPTURED_AT, FIRST_DEADLINE);
        jdbc.update("UPDATE " + scheduleSource.fixtureTable()
                + " SET started = true, finished = true WHERE season = ? AND gameweek <= 3", SEASON);
        seedTeamsAndPresence();

        seedPlayer(RELIABLE_MID, "Reliable", 1, 3, 61, 4.0, "a", 60, 0.45, 0.25, 1.0, null);
        seedPlayer(STRIKER, "Striker", 2, 4, 79, 12.0, "a", 90, 0.80, 0.20, 1.2, 13);
        seedPlayer(DEFENDER, "Defender", 3, 2, 52, 3.0, "a", 90, 0.08, 0.12, 0.55, 12);
        seedPlayer(KEEPER, "Keeper", 4, 1, 45, 2.0, "a", 90, 0, 0, 0.70, null);
        seedPlayer(CAMEO, "Cameo", 5, 3, 50, 1.0, "a", 20, 2.0, 1.0, 1.0, 12);
        seedPlayer(DOUBTFUL, "Doubtful", 6, 4, 70, 2.0, "d", 90, 2.0, 1.0, 1.0, 12);
        seedPlayer(ZERO, "LostRole", 7, 3, 50, 1.0, "a", 0, 2.0, 1.0, 1.0, null);
        seedPlayer(NO_RECORD, "Newcomer", 8, 3, 50, 1.0, "a", null, 0, 0, 1.0, null);
        seedPlayer(UNAVAILABLE, "Injured", 9, 4, 70, 1.0, "i", 90, 2.0, 1.0, 1.0, 12);
        seedPlayer(MIXED_MID, "Mixed", 10, 3, 55, 1.0, "a", 90, 0.55, 0.25, 1.0, 12);
        int mixedFixture = RankingSeed.fixtures(CAPTURED_AT, FIRST_DEADLINE).stream()
                .filter(row -> row.gameweek() == 1 && (row.homeTeamId() == RankingSeed.team(10)
                        || row.awayTeamId() == RankingSeed.team(10)))
                .findFirst().orElseThrow().id();
        jdbc.update("UPDATE player_fixture_stats SET minutes = 30, starts = 0"
                        + " WHERE season_id = ? AND player_id = ? AND fixture_id = ?",
                SEASON_ID, MIXED_MID, mixedFixture);
        seedAverageLeague(1.20, 1.20);
    }

    /** One ghost row per club side for gameweeks 1-3: an average league every club belongs to. */
    private static final int GHOST_FIRST = 13_050;

    private void seedAverageLeague(double xg, double xgc) {
        for (ScheduleSource.FixtureRow row : RankingSeed.fixtures(CAPTURED_AT, FIRST_DEADLINE)) {
            if (row.gameweek() > 3) {
                continue;
            }
            seedGhostSide(row, row.homeTeamId(), xg, xgc);
            seedGhostSide(row, row.awayTeamId(), xg, xgc);
        }
    }

    private void seedGhostSide(ScheduleSource.FixtureRow row, int club, double xg, double xgc) {
        int ghost = GHOST_FIRST + (club - RankingSeed.team(1));
        boolean home = row.homeTeamId() == club;
        jdbc.update("""
                INSERT INTO player_fixture_stats (
                    fixture_id, player_id, season_id, gameweek, player_team_id,
                    opponent_team_id, was_home, kickoff_time, minutes, total_points,
                    expected_goals, expected_assists, expected_goals_conceded, value, starts)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 90, 2, ?, 0, ?, 45, 1)
                """, row.id(), ghost, SEASON_ID, row.gameweek(), club,
                home ? row.awayTeamId() : row.homeTeamId(), home,
                Timestamp.from(row.kickoff()), xg, xgc);
    }

    @AfterEach
    void cleanUp() {
        if (jdbc == null) {
            return;
        }
        SharedStatsSeed.ensure(jdbc);
        jdbc.update("DELETE FROM player_fixture_stats WHERE season_id = ?"
                + " AND player_id BETWEEN ? AND ?", SEASON_ID, FIRST_ID, LAST_ID);
        jdbc.update("DELETE FROM player WHERE season_id = ? AND id BETWEEN ? AND ?",
                SEASON_ID, FIRST_ID, LAST_ID);
        jdbc.update("DELETE FROM " + dailyTable + " WHERE season = ?"
                + " AND element_id BETWEEN ? AND ?", SEASON, FIRST_ID, LAST_ID);
        RankingSeed.cleanSchedule(jdbc, scheduleSource.gameweekTable(),
                scheduleSource.fixtureTable(), SEASON);
        jdbc.update("DELETE FROM fixture WHERE season_id = ? AND id BETWEEN ? AND ?",
                SEASON_ID, RankingSeed.FIXTURE_BASE, RankingSeed.FIXTURE_BASE + 999);
        jdbc.update("DELETE FROM gameweek WHERE season_id = ? AND id = ?",
                SEASON_ID, PRESENCE_GAMEWEEK);
        jdbc.update("DELETE FROM team WHERE season_id = ? AND id BETWEEN ? AND ?",
                SEASON_ID, RankingSeed.team(1), RankingSeed.team(20));
    }

    private void seedTeamsAndPresence() {
        for (int index = 1; index <= 20; index++) {
            jdbc.update("""
                    INSERT INTO team (id, season_id, code, name, short_name, strength)
                    VALUES (?, ?, ?, ?, ?, 3) ON CONFLICT (id, season_id) DO NOTHING
                    """, RankingSeed.team(index), SEASON_ID, RankingSeed.team(index),
                    "KAR Club " + index, "K%02d".formatted(index));
        }
        jdbc.update("""
                INSERT INTO gameweek (id, season_id, name, deadline_time, finished, data_checked,
                                      is_current, is_next)
                VALUES (?, ?, 'KAR-31 presence', ?, false, false, true, false)
                ON CONFLICT (id, season_id) DO UPDATE SET deadline_time = EXCLUDED.deadline_time
                """, PRESENCE_GAMEWEEK, SEASON_ID, Timestamp.from(FIRST_DEADLINE));
    }

    private void seedPlayer(int id, String name, int clubIndex, int position, int cost,
                            double ownership, String status, Integer minutes, double xg,
                            double xa, double xgc, Integer defensiveContribution) {
        int club = RankingSeed.team(clubIndex);
        jdbc.update("""
                INSERT INTO %s (season, price_date, element_id, web_name, full_name,
                    team_id, team_short, team_name, element_type, now_cost,
                    cost_change_event, cost_change_start, selected_by_percent, status, news,
                    captured_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?, ?, ?, ?)
                """.formatted(dailyTable), SEASON, Date.valueOf(CAPTURE_DATE), id, name,
                name + " Fictitious", club, "K%02d".formatted(clubIndex),
                "KAR Club " + clubIndex, position, cost, ownership, status,
                "d".equals(status) ? "75% chance of playing" : null,
                Timestamp.from(CAPTURED_AT));
        jdbc.update("""
                INSERT INTO player (id, season_id, team_id, first_name, second_name, web_name,
                                    element_type, now_cost, status, total_points, minutes,
                                    form, selected_by_percent)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 18, ?, 4.0, ?)
                """, id, SEASON_ID, club, name, name, name, position, cost, status,
                minutes == null ? 0 : minutes * 3, ownership);
        if (minutes == null) {
            return;
        }
        List<ScheduleSource.FixtureRow> fixtures = RankingSeed.fixtures(CAPTURED_AT, FIRST_DEADLINE)
                .stream().filter(row -> row.gameweek() <= 3)
                .filter(row -> row.homeTeamId() == club || row.awayTeamId() == club)
                .toList();
        assertThat(fixtures).hasSize(3);
        for (ScheduleSource.FixtureRow fixture : fixtures) {
            boolean home = fixture.homeTeamId() == club;
            int opponent = home ? fixture.awayTeamId() : fixture.homeTeamId();
            jdbc.update("""
                    INSERT INTO player_fixture_stats (
                        fixture_id, player_id, season_id, gameweek, player_team_id,
                        opponent_team_id, was_home, kickoff_time, minutes, total_points,
                        expected_goals, expected_assists, expected_goals_conceded, value, starts,
                        defensive_contribution)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 6, ?, ?, ?, ?, ?, ?)
                    """, fixture.id(), id, SEASON_ID, fixture.gameweek(), club, opponent, home,
                    Timestamp.from(fixture.kickoff()), minutes, xg, xa, xgc, cost,
                    minutes > 0 ? 1 : 0, defensiveContribution);
        }
    }

    @Test
    void bothPickListsLabelRestrictedComparisonBeforeRowsAndKeepSourceLines() {
        ScheduleSource.FixtureRow incident = RankingSeed.fixtures(CAPTURED_AT, FIRST_DEADLINE)
                .stream().filter(row -> row.gameweek() == 4
                        && row.homeTeamId() <= RankingSeed.team(9)
                        && row.awayTeamId() <= RankingSeed.team(9))
                .findFirst().orElseThrow();
        jdbc.update("UPDATE " + scheduleSource.fixtureTable()
                        + " SET kickoff_time=?, started=true, finished=false WHERE season=? AND id=?",
                Timestamp.from(AS_OF.minusSeconds(1800)), SEASON, incident.id());

        for (String result : List.of(tools.getDifferentialPicks("any", 100, 5),
                tools.getCaptainCandidates(5))) {
            assertThat(result).contains("Data source: FPL live prices", "Data source: season " + SEASON,
                            "Data source: FPL's own gameweek and fixture list")
                    .contains("RESTRICTED COMPARISON POPULATION")
                    .contains("fixture " + incident.id() + " (GW4)")
                    .contains("not a league-wide best list")
                    .contains("Player comparison population:");
            int coverage = result.indexOf("RESTRICTED COMPARISON POPULATION");
            int firstRow = result.indexOf("  ", result.indexOf("Differential picks"));
            if (firstRow < 0) {
                firstRow = result.indexOf("  ", result.indexOf("Captain candidates"));
            }
            assertThat(coverage).isLessThan(firstRow);
        }

        jdbc.update("UPDATE " + scheduleSource.fixtureTable()
                + " SET finished=true WHERE season=? AND id=?", SEASON, incident.id());
        assertThat(tools.getDifferentialPicks("any", 100, 5))
                .contains("completed side missing usable core rows");
        seedGhostSide(incident, incident.homeTeamId(), 1.2, 1.2);
        seedGhostSide(incident, incident.awayTeamId(), 1.2, 1.2);
        assertThat(tools.getDifferentialPicks("any", 100, 5))
                .contains("Comparison coverage: all clubs")
                .doesNotContain("RESTRICTED COMPARISON POPULATION");
    }

    @Test
    void returnsExpectedPointsRankingsAndFactualRecentMinuteNotes() {
        String result = tools.getDifferentialPicks("any", 10, 5);

        assertThat(result).contains("Differential picks")
                .contains("Reliable", "Mixed", "Defender", "Keeper", "Cameo")
                .contains("expected points/fixture", "horizon expected points")
                .contains("complete FPL schedule capture at " + CAPTURED_AT)
                .contains("Avg FDR")
                .contains("minutes note: 20 minutes vs")
                .contains("minutes note: 30 minutes vs")
                .doesNotContain(" Doubtful (").doesNotContain(" LostRole (")
                .doesNotContain(" Newcomer (").doesNotContain(" Injured (")
                .doesNotContain("realised points/fixture").doesNotContain("Pts/90");
        assertThat(tools.getCaptainCandidates(5)).contains(" Mixed (", " Cameo (")
                .doesNotContain(" LostRole (");
    }

    /**
     * Three sources, in source order, and the schedule's evidence sentence carrying the exact
     * capture instant, the instant it was judged at and the window it produced. Both tools: a
     * captain list rests on the same schedule a differential list does.
     */
    @Test
    void bothRepliesAttributeThePriceMetricAndScheduleSources() {
        for (String result : List.of(tools.getDifferentialPicks("any", 10, 5),
                tools.getCaptainCandidates(5))) {
            assertThat(result)
                    .contains("Data source: FPL live prices for season " + SEASON)
                    .contains("Data source: season " + SEASON)
                    .contains("Data source: FPL's own gameweek and fixture list for season "
                            + SEASON + ", captured today")
                    .contains("complete FPL schedule capture at " + CAPTURED_AT
                            + ", judged as of " + AS_OF)
                    .contains("Editable window GW4–8 (5 of the 5 calendar gameweeks asked for)")
                    .contains("Removed by the schedule alone: 0 with no fixture in the window,"
                            + " 0 whose club window could not be read");
            assertThat(result.indexOf("Data source: season " + SEASON))
                    .isLessThan(result.indexOf("Data source: FPL's own gameweek"));
            assertThat(result.lines().limit(4).toList())
                    .as("three contiguous source lines, then the unknown-refresh notice")
                    .satisfiesExactly(
                            line -> assertThat(line).startsWith("Data source: FPL live prices"),
                            line -> assertThat(line).startsWith("Data source: season " + SEASON),
                            line -> assertThat(line).startsWith("Data source: FPL's own gameweek"),
                            line -> assertThat(line).isEqualTo(DataAge.RANKING_METRIC_REFRESH));
            assertThat(result.indexOf(DataAge.RANKING_METRIC_REFRESH))
                    .as("before the schedule evidence and every numeric row")
                    .isLessThan(result.indexOf("Schedule evidence:"))
                    .isLessThan(result.indexOf("horizon expected points"));
        }
    }

    /** The one reply with no ranked player still names all three sources it consulted. */
    @Test
    void aRefusalWithNoQualifyingPlayerStillNamesAllThreeSources() {
        String result = tools.getDifferentialPicks("any", 0, 5);

        assertThat(result).contains("No differentials qualify")
                .contains("Data source: FPL live prices for season " + SEASON)
                .contains("Data source: season " + SEASON)
                .contains("Data source: FPL's own gameweek and fixture list for season " + SEASON)
                .contains("complete FPL schedule capture at " + CAPTURED_AT
                        + ", judged as of " + AS_OF)
                .containsSubsequence("Data source: FPL's own gameweek", DataAge.RANKING_METRIC_REFRESH,
                        "Schedule evidence:", "No differentials qualify");
    }

    /**
     * Row-write timestamps are not evidence about football: a fresh {@code player} or shared
     * {@code fixture} write, and a DefCon-only UPDATE that fires {@code trg_pfs_updated_at}
     * without changing a value, must leave both replies byte-for-byte the same — order, Scores,
     * eligibility and every printed date.
     */
    @Test
    void rowWriteTimestampsAloneChangeNeitherTheRankingNorItsText() {
        seedSharedStatsFixtureMirrors(Timestamp.from(Instant.parse("2025-01-01T00:00:00Z")));
        String differentials = tools.getDifferentialPicks("any", 100, 5);
        String captains = tools.getCaptainCandidates(5);
        Instant pfsWrittenBefore = pfsWrite(RELIABLE_MID);

        Timestamp fresh = Timestamp.from(AS_OF);
        assertThat(jdbc.update("UPDATE player SET updated_at = ? WHERE season_id = ?"
                + " AND id BETWEEN ? AND ?", fresh, SEASON_ID, FIRST_ID, LAST_ID)).isPositive();
        assertThat(jdbc.update("UPDATE fixture SET updated_at = ? WHERE season_id = ?"
                + " AND id BETWEEN ? AND ?", fresh, SEASON_ID, RankingSeed.FIXTURE_BASE,
                RankingSeed.FIXTURE_BASE + 999)).isPositive();
        assertThat(jdbc.update("UPDATE player_fixture_stats"
                + " SET defensive_contribution = defensive_contribution WHERE season_id = ?"
                + " AND player_id BETWEEN ? AND ?", SEASON_ID, FIRST_ID, LAST_ID)).isPositive();
        assertThat(pfsWrite(RELIABLE_MID)).as("the trigger restamps the row")
                .isAfter(pfsWrittenBefore);

        assertThat(tools.getDifferentialPicks("any", 100, 5)).isEqualTo(differentials);
        assertThat(tools.getCaptainCandidates(5)).isEqualTo(captains);
        assertThat(differentials).contains(" Reliable (", "horizon expected points",
                DataAge.RANKING_METRIC_REFRESH);
        assertThat(captains).contains(" Striker (", "horizon expected points",
                DataAge.RANKING_METRIC_REFRESH);
    }

    @Test
    void captainListContainsAttackersOnlyAndExcludesDoubtfulStar() {
        String result = tools.getCaptainCandidates(5);

        assertThat(result).contains("Striker", "Reliable")
                .doesNotContain(" Defender (").doesNotContain(" Keeper (")
                .doesNotContain(" Doubtful (")
                .contains("availability risk 2")
                .doesNotContainIgnoringCase("guaranteed starter");
    }

    @Test
    void outputUsesDailyPriceAndLabelsPartialExpectedComponents() {
        String result = tools.getDifferentialPicks("any", 10, 5);

        assertThat(result).contains("Defender").contains("£5.2m")
                .contains("CS ").contains("DC ").containsIgnoringCase("partial")
                .contains("captured today (2026-09-09)");
    }

    @Test
    void eachPickListsItsCleanSheetChancePerFixtureWithOpponentAndVenue() {
        String defender = lineFor(tools.getDifferentialPicks("DEF", 10, 2), "Defender");

        // Clubs 10-20 have no captured player here, so an opponent may print as "team <id>";
        // in production every club has one and prints its short name.
        assertThat(defender).contains("CS ").contains(" over 2 fixtures (vs ")
                .containsPattern("vs (K\\d\\d|team \\d+) [ha] \\d+% at xGA \\d+\\.\\d\\d")
                .doesNotContain("season-average").doesNotContain("at xGC");
    }

    /**
     * The Score and the printed {@code CS} total are two independent summations over the same
     * horizon — one inside {@code expected_per_fixture}, one in the printed leg list. This pins
     * both against the legs themselves: the total is the model's own {@code 4 × exp(-xGA)} per
     * fixture, and the
     * per-fixture expectation is the fixture-independent components plus that total over the
     * window. Tolerance 0.011 because every printed figure is rounded to two decimals.
     */
    @Test
    void theCleanSheetTotalAndTheExpectedPerFixtureAgreeWithThePrintedLegs() {
        String defender = lineFor(tools.getDifferentialPicks("DEF", 10, 2), "Defender");

        List<Double> goalsAgainst = allNumbers(defender, "at xGA (\\d+\\.\\d\\d)");
        double total = number(defender, "CS (\\d+\\.\\d\\d) over 2 fixtures");
        double appearance = number(defender, "appearance (\\d+\\.\\d\\d)");
        double attack = number(defender, "attack (\\d+\\.\\d\\d)");
        double defcon = number(defender, "DC (\\d+\\.\\d\\d)");
        double perFixture = number(defender, "expected points/fixture (\\d+\\.\\d\\d)");

        assertThat(goalsAgainst).hasSize(2);
        assertThat(total).as("the printed CS total is 4 x exp(-xGA) summed over the legs: " + defender)
                .isCloseTo(4 * (Math.exp(-goalsAgainst.get(0)) + Math.exp(-goalsAgainst.get(1))),
                        within(0.011));
        assertThat(perFixture).as("expected points/fixture averages the same summation: " + defender)
                .isCloseTo(appearance + attack + defcon + total / 2, within(0.011));
    }

    private static List<Double> allNumbers(String line, String pattern) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(line);
        List<Double> found = new ArrayList<>();
        while (m.find()) {
            found.add(Double.parseDouble(m.group(1)));
        }
        return found;
    }

    private static double number(String line, String pattern) {
        List<Double> found = allNumbers(line, pattern);
        assertThat(found).as("'" + pattern + "' in: " + line).hasSize(1);
        return found.getFirst();
    }

    @Test
    void aStrongerOpponentLowersTheCleanSheetTermForThatFixtureOnly() {
        int club = RankingSeed.team(3);                                      // the Defender's club
        int firstFixture = fixtureFor(club, 4);                   // GW4, the first editable round
        int opponent = jdbc.queryForObject("SELECT CASE WHEN team_h = ? THEN team_a ELSE team_h END"
                + " FROM " + scheduleSource.fixtureTable() + " WHERE season = ? AND id = ?",
                Integer.class, club, SEASON, firstFixture);
        String before = lineFor(tools.getDifferentialPicks("DEF", 10, 2), "Defender");

        // Triple the opponent's attack in every side it has played.
        jdbc.update("UPDATE player_fixture_stats SET expected_goals = expected_goals * 3"
                + " WHERE season_id = ? AND player_id = ?", SEASON_ID,
                GHOST_FIRST + (opponent - RankingSeed.team(1)));
        String after = lineFor(tools.getDifferentialPicks("DEF", 10, 2), "Defender");

        assertThat(horizonScore(after)).isLessThan(horizonScore(before));
        assertThat(after).contains("at xGA ");
    }

    private static double horizonScore(String line) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("horizon expected points (\\d+\\.\\d\\d)").matcher(line);
        assertThat(m.find()).as("horizon expected points in: " + line).isTrue();
        return Double.parseDouble(m.group(1));
    }

    @Test
    void goalkeeperShowsKnownZeroDefconRatherThanMissingEvidence() {
        String result = tools.getDifferentialPicks("GK", 10, 5);

        assertThat(result).contains("Keeper").contains("CS ").contains("DC 0.00")
                .doesNotContain("DC n/a");
    }

    @Test
    void absurdOwnershipAndUnknownPositionReturnFactualMessages() {
        assertThat(tools.getDifferentialPicks("any", 0, 5))
                .contains("No differentials qualify").contains("Checked 0 matching capture players");
        assertThat(tools.getDifferentialPicks("sweeper", 10, 5))
                .contains("Unrecognised position").contains("sweeper")
                .as("a malformed request reads no metrics, so it carries no refresh notice")
                .doesNotContain(DataAge.RANKING_METRIC_REFRESH);
    }

    @Test
    void exclusionCountsDistinguishAvailabilityAndUnknownEvidence() {
        String result = tools.getDifferentialPicks("any", 100, 5);

        assertThat(result).contains("availability risk 2")
                .contains("unknown current evidence 2")
                .doesNotContain("consistently under-60");
    }

    /**
     * A club that plays twice inside the five editable gameweeks has six fixtures in the window,
     * not five. Taking the next five fixtures instead drops the last gameweek it was asked about.
     */
    @Test
    void theAverageFdrCoversEveryFixtureOfADoubleGameweekWindow() {
        int club = RankingSeed.team(1);
        ScheduleSource.FixtureRow secondLeg = laterFixtureAgainstAnUnusedClub(club);
        moveToGameweek(secondLeg.id(), 6, SECOND_LEG_KICKOFF);
        for (int gameweek = 4; gameweek <= 7; gameweek++) {
            setClubDifficulty(club, fixtureFor(club, gameweek), 2);
        }
        setClubDifficulty(club, secondLeg.id(), 2);
        setClubDifficulty(club, fixtureFor(club, 8), 5);

        String line = lineFor(tools.getDifferentialPicks("any", 10, 5), " Reliable (");

        // 2,2,2,2,2 over GW4-7 plus the second leg, and 5 in GW8: six fixtures, not the five a
        // next-N-fixtures window would have averaged.
        assertThat(line).contains("Avg FDR 2.5");
    }

    /**
     * Three midfielders identical but for their expected goals, so the per-fixture order is
     * Faraway, Nearly, Doubler by construction. Over the window Doubler plays six times and the
     * other two five, which must lift him past Nearly without letting him overtake Faraway.
     */
    @Test
    void aDoubleGameweekOutranksACloseSingleButNotAFarBetterOne() {
        seedPlayer(DOUBLE_MID, "Doubler", 10, 3, 50, 5.0, "a", 90, 0.52, 0, 1.0, null);
        seedPlayer(NEAR_MID, "Nearly", 11, 3, 50, 5.0, "a", 90, 0.62, 0, 1.0, null);
        seedPlayer(FAR_MID, "Faraway", 12, 3, 50, 5.0, "a", 90, 1.00, 0, 1.0, null);
        moveToGameweek(laterFixtureAgainstAnUnusedClub(RankingSeed.team(10)).id(), 6,
                SECOND_LEG_KICKOFF);

        String reply = tools.getDifferentialPicks("MID", 100, 5);

        assertThat(reply).contains(" Doubler (", " Nearly (", " Faraway (");
        assertThat(reply.indexOf(" Faraway ("))
                .as("one far better fixture still beats two lesser ones")
                .isLessThan(reply.indexOf(" Doubler ("));
        assertThat(reply.indexOf(" Doubler ("))
                .as("two fixtures beat one slightly better fixture")
                .isLessThan(reply.indexOf(" Nearly ("));
    }

    /**
     * Two different reasons a club cannot contribute a fixture, counted apart from each other:
     * one club is blanked out of a one-gameweek window, the other has
     * an outstanding fixture FPL has not assigned to a gameweek at all. Neither is a forecast of
     * zero points, so neither name may appear in the list.
     */
    @Test
    void horizonExclusionsSeparateAKnownBlankFromAnUnresolvedSchedule() {
        moveToGameweek(fixtureFor(RankingSeed.team(2), 4), 9, BLANK_REPLAY_KICKOFF);
        unassignGameweek(fixtureFor(RankingSeed.team(1), 4));

        String result = tools.getDifferentialPicks("any", 100, 1);

        assertThat(result).contains(" Defender (", " Keeper (")
                .doesNotContain(" Striker (").doesNotContain(" Reliable (")
                .contains("no fixtures in the horizon 1")
                .contains("unknown horizon 1");
    }

    /**
     * The clock is fixed for the whole class, so the schedule moves instead: pushing every
     * deadline up to gameweek 36 into the past leaves GW37 as the first editable round, which is
     * two gameweeks short of the five asked for.
     */
    @Test
    void theFooterNamesTheEditableRangeAndDisclosesTruncation() {
        passDeadlinesUpTo(36);

        String result = tools.getDifferentialPicks("any", 100, 5);

        assertThat(result).contains(" Reliable (")
                .contains("fixtures 2 in GW37–38")
                .contains("next 5 calendar gameweeks, GW37–38")
                .contains("truncated to 2");
    }

    @Test
    void afterTheFinalDeadlineThereIsNoHorizonRatherThanAForecastOfZero() {
        passDeadlinesUpTo(38);

        String result = tools.getDifferentialPicks("any", 100, 5);

        assertThat(result).contains("No differentials qualify")
                .contains("No editable gameweek remains")
                .contains("unknown horizon 7")
                .contains("Data source: FPL's own gameweek and fixture list for season " + SEASON)
                .contains("No editable window (no editable gameweek)")
                .contains(DataAge.RANKING_METRIC_REFRESH)
                .doesNotContain(" Reliable (").doesNotContain("horizon expected points");
    }

    /** SharedStats {@code fixture} rows for the seeded schedule's finished fixtures, written at a known time. */
    private void seedSharedStatsFixtureMirrors(Timestamp writtenAt) {
        for (ScheduleSource.FixtureRow row : RankingSeed.fixtures(CAPTURED_AT, FIRST_DEADLINE)) {
            if (row.gameweek() <= 3) {
                jdbc.update("""
                        INSERT INTO fixture (id, season_id, gameweek, code, team_h, team_a,
                                             kickoff_time, started, finished, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, true, true, ?)
                        """, row.id(), SEASON_ID, row.gameweek(), row.id(), row.homeTeamId(),
                        row.awayTeamId(), Timestamp.from(row.kickoff()), writtenAt);
            }
        }
    }

    private Instant pfsWrite(int playerId) {
        return jdbc.queryForObject("SELECT MAX(updated_at) FROM player_fixture_stats"
                + " WHERE season_id = ? AND player_id = ?", Timestamp.class, SEASON_ID, playerId)
                .toInstant();
    }

    /** A later fixture of this club against a club none of the seeded players belongs to. */
    private static ScheduleSource.FixtureRow laterFixtureAgainstAnUnusedClub(int club) {
        return RankingSeed.fixtures(CAPTURED_AT, FIRST_DEADLINE).stream()
                .filter(row -> row.gameweek() > 8)
                .filter(row -> row.homeTeamId() == club || row.awayTeamId() == club)
                .filter(row -> (row.homeTeamId() == club ? row.awayTeamId() : row.homeTeamId())
                        >= RankingSeed.team(13))
                .findFirst().orElseThrow();
    }

    private static int fixtureFor(int club, int gameweek) {
        return RankingSeed.fixtureFor(RankingSeed.fixtures(CAPTURED_AT, FIRST_DEADLINE),
                club, gameweek);
    }

    private void moveToGameweek(int fixtureId, int gameweek, Instant kickoff) {
        jdbc.update("UPDATE " + scheduleSource.fixtureTable()
                        + " SET gameweek = ?, kickoff_time = ? WHERE season = ? AND id = ?",
                gameweek, Timestamp.from(kickoff), SEASON, fixtureId);
    }

    /** As FPL leaves a postponement it has not rescheduled: outstanding, with no gameweek. */
    private void unassignGameweek(int fixtureId) {
        jdbc.update("UPDATE " + scheduleSource.fixtureTable()
                + " SET gameweek = NULL WHERE season = ? AND id = ?", SEASON, fixtureId);
    }

    /** Moves the deadlines of gameweeks 1..{@code gameweek} behind the fixed clock. */
    private void passDeadlinesUpTo(int gameweek) {
        jdbc.update("UPDATE " + scheduleSource.gameweekTable()
                + " SET deadline_time = deadline_time - INTERVAL '252 days'"
                + " WHERE season = ? AND id <= ?", SEASON, gameweek);
    }

    /** Sets this club's own side of the fixture's difficulty, leaving the opponent's alone. */
    private void setClubDifficulty(int club, int fixtureId, int difficulty) {
        jdbc.update("UPDATE " + scheduleSource.fixtureTable()
                        + " SET team_h_difficulty ="
                        + " CASE WHEN team_h = ? THEN ? ELSE team_h_difficulty END,"
                        + " team_a_difficulty ="
                        + " CASE WHEN team_a = ? THEN ? ELSE team_a_difficulty END"
                        + " WHERE season = ? AND id = ?",
                club, difficulty, club, difficulty, SEASON, fixtureId);
    }

    private static String lineFor(String reply, String marker) {
        return reply.lines().filter(line -> line.contains(marker)).findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no pick line matching '" + marker + "' in:\n" + reply));
    }
}
