package fi.karivanttinen.fplassistant.squad;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import fi.karivanttinen.fplassistant.schedule.ScheduleSource;
import fi.karivanttinen.fplassistant.schedule.HorizonFixtures;
import fi.karivanttinen.fplassistant.tools.ClubRates;
import fi.karivanttinen.fplassistant.tools.SeasonResolver;
import fi.karivanttinen.fplassistant.testsupport.FictitiousTestData;
import fi.karivanttinen.fplassistant.testsupport.RankingSeed;
import fi.karivanttinen.fplassistant.testsupport.TapasSeed;
import fi.karivanttinen.fplassistant.tools.DataAge;
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

/** End-to-end squad acceptance against one complete, fresh and test-owned ranking snapshot. */
@SpringBootTest
@ActiveProfiles("test")
@Import(SquadToolsIntegrationTest.FixedClock.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SquadToolsIntegrationTest {

    private static final Instant AS_OF = Instant.parse("2026-09-09T12:00:00Z");
    private static final Instant CAPTURED_AT = Instant.parse("2026-09-09T10:00:00Z");
    private static final Instant FIRST_DEADLINE = Instant.parse("2026-08-22T17:30:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 9);
    private static final int SEASON_ID = TapasSeed.CURRENT_SEASON_ID;
    private static final String SEASON = TapasSeed.CURRENT_SEASON_CODE;
    private static final int PRESENCE_GAMEWEEK = 151;
    private static final int FIRST_ID = 12_000;
    private static final int LAST_ID = 12_500;
    private static final int STAR_ID = 12_400;
    private static final String STAR_NAME = "Beststriker";

    /**
     * The cheap defender of {@code CLUBS[8]} and {@code CLUBS[9]} — clubs 9 and 10 of
     * {@link RankingSeed} — following {@link #seedCompleteLeague}'s own id arithmetic:
     * {@code FIRST_ID + club * 8 + (type - 1) * 2 + tier}.
     */
    private static final String BLANKED_CLUB_PLAYER = "P" + (FIRST_ID + 8 * 8 + 2 + 1);
    private static final String UNRESOLVED_CLUB_PLAYER = "P" + (FIRST_ID + 9 * 8 + 2 + 1);

    private static final String[] CLUBS = {
            "Ashfield Rovers", "Barnwell Town", "Coleridge City", "Dunmore Athletic",
            "Eastvale United", "Fallowfield FC", "Granton Park", "Harewood Albion",
            "Ilkeston Vale", "Jarrow Wanderers", "Kelvedon Town", "Langford City"
    };

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
    SquadTools tools;
    @Autowired CandidatePool pool;
    @Autowired SeasonResolver seasonResolver;
    @Autowired fi.karivanttinen.fplassistant.teamxg.UnderstatStore understat;

    @Autowired
    ScheduleSource scheduleSource;

    @Value("${fpl.daily-price.table-name}")
    String dailyTable;

    @BeforeEach
    void seedCompleteLeague() {
        TapasSeed.ensure(jdbc);
        FictitiousTestData.assertSafeSeason(SEASON);
        assertThat(dailyTable).endsWith("_test");
        cleanUp();

        RankingSeed.seedSchedule(jdbc, scheduleSource.gameweekTable(),
                scheduleSource.fixtureTable(), SEASON, CAPTURED_AT, FIRST_DEADLINE);
        jdbc.update("UPDATE " + scheduleSource.fixtureTable()
                + " SET started = true, finished = true WHERE season = ? AND gameweek <= 3", SEASON);
        seedCurrentSeasonPresence();
        seedTeams();

        int id = FIRST_ID;
        for (int club = 0; club < CLUBS.length; club++) {
            for (int type = 1; type <= 4; type++) {
                for (int tier = 0; tier < 2; tier++) {
                    insert(id, "P" + id, "Player " + id, club, type,
                            tier == 0 ? 105 : 45, tier == 1 ? 35.0 : 1.5,
                            defaultXg(type));
                    id++;
                }
            }
        }
        insert(STAR_ID, STAR_NAME, STAR_NAME + " Fictitious", 0, 4, 88, 5.0, 1.50);
        RankingSeed.seedTeamXg(understat, 2026, CAPTURED_AT, FIRST_DEADLINE);
        // Opponents need identities and complete FPL sides, but no selectable extra players.
        for (int club = CLUBS.length + 1; club <= 20; club++) {
            insert(LAST_ID - club, "Identity" + club, "Reserve Player " + club,
                    club - 1, 4, 45, 0, defaultXg(4));
            jdbc.update("UPDATE " + dailyTable + " SET status='u' WHERE season=? AND element_id=?",
                    SEASON, LAST_ID - club);
        }
    }

    @AfterEach
    void cleanUp() {
        if (jdbc == null) {
            return;
        }
        TapasSeed.ensure(jdbc);
        jdbc.update("DELETE FROM fpl_understat_capture_test");
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

    private void seedCurrentSeasonPresence() {
        jdbc.update("""
                INSERT INTO gameweek (id, season_id, name, deadline_time, finished, data_checked,
                                      is_current, is_next)
                VALUES (?, ?, 'KAR-31 presence', ?, false, false, true, false)
                ON CONFLICT (id, season_id) DO UPDATE SET deadline_time = EXCLUDED.deadline_time
                """, PRESENCE_GAMEWEEK, SEASON_ID, Timestamp.from(FIRST_DEADLINE));
    }

    private void seedTeams() {
        for (int index = 1; index <= 20; index++) {
            String name = index <= CLUBS.length ? CLUBS[index - 1] : "Reserve Club " + index;
            jdbc.update("""
                    INSERT INTO team (id, season_id, code, name, short_name, strength)
                    VALUES (?, ?, ?, ?, ?, 3) ON CONFLICT (id, season_id) DO NOTHING
                    """, RankingSeed.team(index), SEASON_ID, RankingSeed.team(index), name,
                    "K%02d".formatted(index));
        }
    }

    private static double defaultXg(int position) {
        return switch (position) {
            case 4 -> 0.40;
            case 3 -> 0.20;
            case 2 -> 0.02;
            default -> 0.00;
        };
    }

    private void insert(int elementId, String webName, String fullName, int clubIndex,
                        int position, int cost, double ownership, double xg) {
        int team = RankingSeed.team(clubIndex + 1);
        jdbc.update("""
                INSERT INTO %s (season, price_date, element_id, web_name, full_name,
                    team_id, team_short, team_name, element_type, now_cost,
                    cost_change_event, cost_change_start, selected_by_percent, status, news,
                    captured_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?, 'a', NULL, ?)
                """.formatted(dailyTable), SEASON, Date.valueOf(TODAY), elementId, webName,
                fullName, team, "K%02d".formatted(clubIndex + 1),
                clubIndex < CLUBS.length ? CLUBS[clubIndex] : "Reserve Club " + (clubIndex + 1), position, cost,
                ownership, Timestamp.from(CAPTURED_AT));
        jdbc.update("""
                INSERT INTO player (id, season_id, team_id, first_name, second_name, web_name,
                                    element_type, now_cost, status, total_points, minutes,
                                    form, selected_by_percent)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'a', 18, 270, 4.0, ?)
                """, elementId, SEASON_ID, team, webName, webName, webName, position, cost,
                ownership);
        List<ScheduleSource.FixtureRow> finished = RankingSeed.fixtures(CAPTURED_AT, FIRST_DEADLINE)
                .stream().filter(row -> row.gameweek() <= 3)
                .filter(row -> row.homeTeamId() == team || row.awayTeamId() == team)
                .toList();
        assertThat(finished).hasSize(3);
        for (ScheduleSource.FixtureRow fixture : finished) {
            boolean home = fixture.homeTeamId() == team;
            int opponent = home ? fixture.awayTeamId() : fixture.homeTeamId();
            double xa = position >= 3 ? 0.15 : 0.03;
            double xgc = position <= 2 ? 1.0 : 1.2;
            jdbc.update("""
                    INSERT INTO player_fixture_stats (
                        fixture_id, player_id, season_id, gameweek, player_team_id,
                        opponent_team_id, was_home, kickoff_time, minutes, total_points,
                        expected_goals, expected_assists, expected_goals_conceded, value, starts)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 90, 6, ?, ?, ?, ?, 1)
                    """, fixture.id(), elementId, SEASON_ID, fixture.gameweek(), team, opponent,
                    home, Timestamp.from(fixture.kickoff()), xg, xa, xgc, cost);
        }
    }

    @Test
    void understatClubRatesAttachToTheSquadAndChangeDefenderProjectionWithoutChangingPlayerStats() {
        var capture = pool.latestCapture().orElseThrow();
        var metric = seasonResolver.metricSeasons(seasonResolver.resolve());
        var before = pool.load(capture, metric);
        assertThat(before.snapshot().clubRates().clubs()).hasSize(20);
        var defender = before.candidates().stream()
                .filter(c -> c.elementId() == FIRST_ID + 3).findFirst().orElseThrow();
        assertThat(defender.mayStart()).isTrue();
        var horizon = HorizonFixtures.derive(before.snapshot().schedule(), AS_OF, SEASON, 5);
        var window = horizon.club(defender.teamId());
        assertThat(window.fixtures()).hasSize(5).allSatisfy(fixture ->
                assertThat(ClubRates.projectedGoalsAgainst(before.snapshot().clubRates(),
                        defender.teamId(), fixture.opponentTeamId(), fixture.home())).isPresent());
        var initial = HorizonScorer.projection(defender, window, before.snapshot().clubRates());
        assertThat(initial.available()).isTrue();
        assertThat(tools.buildSquad(defender.webName(), "", "", 100.0, 5, "").lines()
                .filter(line -> line.contains(defender.webName()) && line.contains("xPts over")))
                .singleElement().asString().contains(String.format(java.util.Locale.ROOT,
                        "%.2f xPts over 5 fixtures", initial.optimizerPoints()));

        jdbc.update("UPDATE fpl_understat_match_test SET away_xg=away_xg*3 WHERE home_team='K01'");
        jdbc.update("UPDATE fpl_understat_match_test SET home_xg=home_xg*3 WHERE away_team='K01'");
        var after = pool.load(capture, metric);
        var sameDefender = after.candidates().stream()
                .filter(c -> c.elementId() == defender.elementId()).findFirst().orElseThrow();
        assertThat(sameDefender.metrics()).isEqualTo(defender.metrics());
        var changed = HorizonScorer.projection(sameDefender, window, after.snapshot().clubRates());
        assertThat(changed.available()).isTrue();
        assertThat(changed.optimizerPoints()).isLessThan(initial.optimizerPoints());
        assertThat(tools.buildSquad(defender.webName(), "", "", 100.0, 5, "").lines()
                .filter(line -> line.contains(defender.webName()) && line.contains("xPts over")))
                .singleElement().asString().contains(String.format(java.util.Locale.ROOT,
                        "%.2f xPts over 5 fixtures", changed.optimizerPoints()));
    }

    @Test
    void aPlainRequestReturnsFifteenRealEligiblePlayersAndComputedEvidence() {
        String output = tools.buildSquad("", "", "", 100.0, 5, "");

        assertThat(output).contains("Starting XI").contains("Bench").contains("Captain")
                .doesNotContain("observed current-club 60+")
                .contains("Schedule evidence: complete FPL capture")
                .contains("Comparison coverage: available recorded player rows")
                .contains("Player comparison population: 97 eligible of 105");
    }

    @Test
    void matchStatusNeverBlocksTheSquadAndLivePlayerRowsAreLabelled() {
        ScheduleSource.FixtureRow incident = RankingSeed.fixtures(CAPTURED_AT, FIRST_DEADLINE)
                .stream().filter(row -> row.gameweek() == 4
                        && row.homeTeamId() <= RankingSeed.team(CLUBS.length)
                        && row.awayTeamId() <= RankingSeed.team(CLUBS.length))
                .findFirst().orElseThrow();
        jdbc.update("UPDATE " + scheduleSource.fixtureTable()
                        + " SET kickoff_time=?, started=true, finished=false WHERE season=? AND id=?",
                Timestamp.from(AS_OF.minus(30, ChronoUnit.MINUTES)), SEASON, incident.id());

        String pending = tools.buildSquad("", "", "", 100.0, 5, "");
        assertThat(pending).contains("Starting XI", "missing rows are unknown, not zero")
                .doesNotContain("COMPARISON_DATA_INCOMPLETE");
        assertThat(tools.buildSquad("P12000", "P12008", "Ashfield Rovers:<=0",
                120.0, 5, "4-4-2")).doesNotContain("COMPARISON_DATA_INCOMPLETE");

        seedPlayedSide(incident, incident.homeTeamId(),
                FIRST_ID + (incident.homeTeamId() - RankingSeed.team(1)) * 8);
        seedPlayedSide(incident, incident.awayTeamId(),
                FIRST_ID + (incident.awayTeamId() - RankingSeed.team(1)) * 8);
        String live = tools.buildSquad("", "", "", 100.0, 5, "");
        assertThat(live).contains("Starting XI", "In progress: 1")
                .doesNotContain("COMPARISON_DATA_INCOMPLETE");
        jdbc.update("UPDATE " + scheduleSource.fixtureTable()
                + " SET finished_provisional=true WHERE season=? AND id=?", SEASON, incident.id());
        assertThat(tools.buildSquad("", "", "", 100.0, 5, ""))
                .contains("Starting XI", "Finished (provisional stats): 1")
                .doesNotContain("COMPARISON_DATA_INCOMPLETE");
        jdbc.update("UPDATE " + scheduleSource.fixtureTable()
                + " SET finished=true WHERE season=? AND id=?", SEASON, incident.id());
        assertThat(tools.buildSquad("", "", "", 100.0, 5, ""))
                .contains("Starting XI", "Comparison coverage: available recorded player rows")
                .doesNotContain("COMPARISON_DATA_INCOMPLETE");
    }

    /**
     * Three sources, in source order, with the schedule's evidence sentence carrying the exact
     * capture instant, the instant it was judged at and what the window leaves startable.
     */
    @Test
    void theReplyAttributesThePriceMetricAndScheduleSources() {
        String output = tools.buildSquad("", "", "", 100.0, 5, "");

        assertThat(output)
                .contains("Data source: FPL live prices for season " + SEASON)
                .contains("Data source: season " + SEASON)
                .contains("Data source: FPL's own gameweek and fixture list for season " + SEASON
                        + ", captured today")
                .contains("complete FPL capture at " + CAPTURED_AT + ", judged as of " + AS_OF)
                .contains("20 of 20 captured clubs play at least once in GW4–8");
        assertThat(output.indexOf("Data source: season " + SEASON))
                .isLessThan(output.indexOf("Data source: FPL's own gameweek"));
        assertThat(output.lines().limit(4).toList())
                .as("three contiguous source lines, then the unknown-refresh notice")
                .satisfiesExactly(
                        line -> assertThat(line).startsWith("Data source: FPL live prices"),
                        line -> assertThat(line).startsWith("Data source: season " + SEASON),
                        line -> assertThat(line).startsWith("Data source: FPL's own gameweek"),
                        line -> assertThat(line).isEqualTo(DataAge.RANKING_METRIC_REFRESH));
        assertThat(output).containsSubsequence(DataAge.RANKING_METRIC_REFRESH, "Schedule evidence:",
                "Horizon:", "Starting XI");
    }

    /** A refusal reaches the same evidence header, so it names all three sources too. */
    @Test
    void aRefusalCarriesTheSameThreeSourceHeader() {
        String output = tools.buildSquad("", "", "Ashfield Rovers:4", 100.0, 5, "");

        assertThat(output).doesNotContain("Starting XI")
                .contains("Data source: FPL live prices for season " + SEASON)
                .contains("Data source: season " + SEASON)
                .contains("Data source: FPL's own gameweek and fixture list for season " + SEASON)
                .containsSubsequence("Data source: FPL's own gameweek", DataAge.RANKING_METRIC_REFRESH,
                        "Schedule evidence:");
    }

    @Test
    void theReplyExplainsTheProjectedCleanSheetAndItsClubRates() {
        assertThat(tools.buildSquad("", "", "", 100.0, 5, ""))
                .contains("Clean sheets are projected per fixture")
                .contains("club expected-goal rates");
    }

    /** An empty pool still consulted all three sources, so its refusal carries the notice. */
    @Test
    void anEmptyPoolRefusalCarriesTheRefreshNotice() {
        jdbc.update("UPDATE " + dailyTable + " SET status = 'i' WHERE season = ?"
                + " AND element_id BETWEEN ? AND ?", SEASON, FIRST_ID, LAST_ID);

        assertThat(tools.buildSquad("", "", "", 100.0, 5, ""))
                .contains("No players are available to pick from today's capture.")
                .containsSubsequence("Data source: FPL's own gameweek", DataAge.RANKING_METRIC_REFRESH,
                        "Schedule evidence:", "No players are available");
    }

    /**
     * Row-write timestamps are not evidence about football: a fresh {@code player} or tapas
     * {@code fixture} write, and a DefCon-only UPDATE that fires {@code trg_pfs_updated_at}
     * without changing a value, must leave the squad, its XI and every projection unchanged.
     */
    @Test
    void rowWriteTimestampsAloneChangeNeitherTheSquadNorItsText() {
        seedTapasFixtureMirrors(Timestamp.from(Instant.parse("2025-01-01T00:00:00Z")));
        String before = tools.buildSquad("", "", "", 100.0, 5, "");
        Instant pfsWrittenBefore = pfsWrite(STAR_ID);

        Timestamp fresh = Timestamp.from(AS_OF);
        assertThat(jdbc.update("UPDATE player SET updated_at = ? WHERE season_id = ?"
                + " AND id BETWEEN ? AND ?", fresh, SEASON_ID, FIRST_ID, LAST_ID)).isPositive();
        assertThat(jdbc.update("UPDATE fixture SET updated_at = ? WHERE season_id = ?"
                + " AND id BETWEEN ? AND ?", fresh, SEASON_ID, RankingSeed.FIXTURE_BASE,
                RankingSeed.FIXTURE_BASE + 999)).isPositive();
        assertThat(jdbc.update("UPDATE player_fixture_stats"
                + " SET defensive_contribution = defensive_contribution WHERE season_id = ?"
                + " AND player_id BETWEEN ? AND ?", SEASON_ID, FIRST_ID, LAST_ID)).isPositive();
        // A wall clock can move backwards; a row write need not have a monotonic timestamp.
        assertThat(pfsWrite(STAR_ID)).as("the trigger restamps the row").isNotEqualTo(pfsWrittenBefore);

        assertThat(tools.buildSquad("", "", "", 100.0, 5, "")).isEqualTo(before);
        assertThat(before).contains("Captain: " + STAR_NAME, "xPts over", DataAge.RANKING_METRIC_REFRESH);
    }

    /**
     * The window this fixture's schedule actually offers: the clock sits between gameweek 3's
     * deadline and gameweek 4's, so five calendar gameweeks are GW4 to GW8.
     */
    @Test
    void theReplyNamesTheEditableGameweekRangeItScoredOver() {
        assertThat(tools.buildSquad("", "", "", 100.0, 5, ""))
                .contains("Horizon: GW4–8 (5 calendar gameweeks).");
    }

    @Test
    void theCaptainIsTheDeliberatelyBestOutfieldPlayer() {
        String output = tools.buildSquad("", "", "", 100.0, 5, "");

        assertThat(output).contains("Captain: " + STAR_NAME + " (");
        assertThat(startingXi(output)).as("the armband can only go to a starter")
                .contains(STAR_NAME).contains(named(output, "Vice-captain: "));
    }

    @Test
    void threeFromEachOfThreeClubsIsHonouredExactly() {
        String output = tools.buildSquad("", "",
                "Ashfield Rovers:3, Barnwell Town:3, Coleridge City:3", 100.0, 5, "");

        assertThat(output).contains("Ashfield Rovers: 3/3")
                .contains("Barnwell Town: 3/3").contains("Coleridge City: 3/3");
    }

    @Test
    void aForcedFormationAndClubQuotaRemainHardConstraints() {
        String output = tools.buildSquad("", "", "Ashfield Rovers:3", 100.0, 5, "4-4-2");

        assertThat(output).contains("Starting XI (4-4-2)").contains("Ashfield Rovers: 3/3");
    }

    @Test
    void anImpossibleClubQuotaIsRefusedByNameRatherThanFudged() {
        String output = tools.buildSquad("", "", "Ashfield Rovers:4", 100.0, 5, "");

        assertThat(output).contains("Ashfield Rovers").contains("3")
                .doesNotContain("Starting XI");
    }

    @Test
    void aClubQuotaWrittenAsTheShortNameStillResolves() {
        String output = tools.buildSquad("", "", "K01:3", 100.0, 5, "");

        assertThat(output).contains("K01: 3/3").doesNotContain("could not find a club");
    }

    /** Name-resolution refusals carry no data block, so they carry no refresh notice either. */
    @Test
    void unknownNamesAndClubsAreRefused() {
        assertThat(tools.buildSquad("Nobodyatall", "", "", 100.0, 5, ""))
                .containsIgnoringCase("Nobodyatall").doesNotContain("Starting XI")
                .doesNotContain(DataAge.METRIC_REFRESH);
        assertThat(tools.buildSquad("", "", "Nonexistent FC:>=1", 100.0, 5, ""))
                .contains("Nonexistent FC").doesNotContain("Starting XI")
                .doesNotContain(DataAge.METRIC_REFRESH);
    }

    @Test
    void anAmbiguousNameListsTheMatchesAndStops() {
        insert(12_410, "Ambiguoustest", "First Ambiguous", 0, 3, 60, 2.0, 0.2);
        insert(12_411, "Ambiguoustest", "Second Ambiguous", 1, 3, 65, 2.0, 0.2);

        assertThat(tools.buildSquad("Ambiguoustest", "", "", 100.0, 5, ""))
                .containsIgnoringCase("Ambiguoustest").doesNotContain("Starting XI")
                .doesNotContain(DataAge.METRIC_REFRESH);
    }

    @Test
    void aNameWrittenWithoutItsAccentStillResolvesAndReceiptsThePin() {
        insert(12_412, "Jõao", "Jõao Fictitious", 0, 4, 76, 5.0, 0.4);

        String output = tools.buildSquad("Joao", "", "", 100.0, 5, "");

        assertThat(output).contains("Starting XI").contains("Jõao")
                .contains("Joao: included ✓");
    }

    @Test
    void anExclusionWrittenAsAFullNameAppliesToTheWebName() {
        insert(12_413, "G.Jesus", "Gabriel Jesus", 0, 4, 90, 8.0, 1.50);

        String output = tools.buildSquad("", "Gabriel Jesus", "", 100.0, 5, "");

        assertThat(output).contains("Starting XI").doesNotContain("G.Jesus")
                .contains("Gabriel Jesus: excluded ✓");
    }

    @Test
    void anUnderSixtyPinCanStartWhenItsExpectedPointsAreStrong() {
        int id = 12_414;
        insert(id, "CameoPin", "Cameo Pin", 0, 3, 40, 1.0, 2.0);
        jdbc.update("UPDATE player_fixture_stats SET minutes = 20 WHERE season_id = ?"
                + " AND player_id = ?", SEASON_ID, id);

        String output = tools.buildSquad("CameoPin", "", "", 100.0, 5, "");

        assertThat(output).contains("CameoPin: included ✓");
        assertThat(startingXi(output)).contains("CameoPin");
    }

    @Test
    void aMixedMinutePinCanStartWhenItsProjectionIsStrong() {
        int id = 12_415;
        insert(id, "MixedPin", "Mixed Pin", 0, 3, 40, 1.0, 2.0);
        int fixture = jdbc.queryForObject("SELECT fixture_id FROM player_fixture_stats"
                        + " WHERE season_id = ? AND player_id = ?"
                        + " ORDER BY kickoff_time DESC, fixture_id DESC LIMIT 1", Integer.class,
                SEASON_ID, id);
        jdbc.update("UPDATE player_fixture_stats SET minutes = 30, starts = 0"
                + " WHERE season_id = ? AND player_id = ? AND fixture_id = ?",
                SEASON_ID, id, fixture);

        String output = tools.buildSquad("MixedPin", "", "", 100.0, 5, "");

        assertThat(output).contains("MixedPin: included ✓");
        assertThat(startingXi(output)).contains("MixedPin");
    }

    /**
     * A club that plays nothing across the window is a finding about the schedule, so its player
     * stays purchasable for the bench and carries a real zero over the range the reply names.
     */
    @Test
    void aClubBlankedAcrossTheWindowIsBoughtForTheBenchAtARealZero() {
        blankAcrossTheWindow(RankingSeed.team(9));

        String output = tools.buildSquad(BLANKED_CLUB_PLAYER, "", "", 100.0, 5, "");

        assertThat(output).contains(BLANKED_CLUB_PLAYER + ": included ✓");
        assertThat(startingXi(output)).doesNotContain(BLANKED_CLUB_PLAYER);
        assertThat(bench(output)).contains(BLANKED_CLUB_PLAYER)
                .contains("0.00 xPts (no fixtures in GW4–8)");
    }

    /**
     * A postponement FPL has not rescheduled is not a blank: nobody knows how many fixtures the
     * club plays, so the row is n/a with the schedule's own reason rather than a zero.
     */
    @Test
    void aClubWithAnUnresolvedFixtureIsBoughtForTheBenchAtNotApplicable() {
        unassignGameweek(RankingSeed.fixtureFor(
                RankingSeed.fixtures(CAPTURED_AT, FIRST_DEADLINE), RankingSeed.team(10), 6));

        String output = tools.buildSquad(UNRESOLVED_CLUB_PLAYER, "", "", 100.0, 5, "");

        assertThat(output).contains(UNRESOLVED_CLUB_PLAYER + ": included ✓");
        assertThat(startingXi(output)).doesNotContain(UNRESOLVED_CLUB_PLAYER);
        assertThat(bench(output)).contains(UNRESOLVED_CLUB_PLAYER)
                .contains("xPts n/a (horizon unknown: unassigned fixture)");
    }

    /**
     * Past the final deadline no club has a fixture population at all, so the tool refuses and
     * names the schedule — a squad of zero-point forecasts would be the wrong answer twice over.
     */
    @Test
    void afterTheFinalDeadlineTheReplyRefusesAndNamesTheMissingGameweek() {
        passDeadlinesUpTo(38);

        String output = tools.buildSquad("", "", "", 100.0, 5, "");

        assertThat(output).doesNotContain("Starting XI")
                .contains("No editable gameweek remains in the captured schedule")
                .contains("unknown rather than zero").contains("complete current evidence")
                .containsSubsequence(DataAge.RANKING_METRIC_REFRESH, "No editable gameweek remains");
    }

    @Test
    void aMalformedQuotaIsExplainedRatherThanIgnored() {
        assertThat(tools.buildSquad("", "", "Ashfield Rovers:three", 100.0, 5, ""))
                .contains("Ashfield Rovers:three").doesNotContain("Starting XI")
                .doesNotContain(DataAge.METRIC_REFRESH);
    }

    private void seedPlayedSide(ScheduleSource.FixtureRow fixture, int club, int playerId) {
        boolean home = fixture.homeTeamId() == club;
        int opponent = home ? fixture.awayTeamId() : fixture.homeTeamId();
        jdbc.update("""
                INSERT INTO player_fixture_stats (
                    fixture_id, player_id, season_id, gameweek, player_team_id,
                    opponent_team_id, was_home, kickoff_time, minutes, total_points,
                    expected_goals, expected_assists, expected_goals_conceded, value, starts)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 90, 2, 0.1, 0.1, 1.0, 45, 1)
                """, fixture.id(), playerId, SEASON_ID, fixture.gameweek(), club, opponent,
                home, Timestamp.from(AS_OF.minus(30, ChronoUnit.MINUTES)));
    }

    /** Tapas {@code fixture} rows for the seeded schedule's finished fixtures, written at a known time. */
    private void seedTapasFixtureMirrors(Timestamp writtenAt) {
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

    private static String startingXi(String output) {
        return output.substring(output.indexOf("Starting XI"), output.indexOf("Bench,"));
    }

    private static String bench(String output) {
        return output.substring(output.indexOf("Bench,"), output.indexOf("Captain:"));
    }

    /** The name on a {@code Captain: }/{@code Vice-captain: } line, without its club. */
    private static String named(String output, String label) {
        int from = output.indexOf(label) + label.length();
        return output.substring(from, output.indexOf(" (", from));
    }

    /** Moves every one of this club's in-window fixtures out past the horizon. */
    private void blankAcrossTheWindow(int club) {
        int target = 20;
        for (ScheduleSource.FixtureRow row : RankingSeed.fixtures(CAPTURED_AT, FIRST_DEADLINE)) {
            if (row.gameweek() >= 4 && row.gameweek() <= 8
                    && (row.homeTeamId() == club || row.awayTeamId() == club)) {
                jdbc.update("UPDATE " + scheduleSource.fixtureTable()
                                + " SET gameweek = ?, kickoff_time = ? WHERE season = ? AND id = ?",
                        target, Timestamp.from(FIRST_DEADLINE.plus(
                                7L * (target - 1) + 1, ChronoUnit.DAYS)), SEASON, row.id());
                target++;
            }
        }
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
}
