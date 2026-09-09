package fi.karivanttinen.fplassistant.squad;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import fi.karivanttinen.fplassistant.schedule.ScheduleSource;
import fi.karivanttinen.fplassistant.testsupport.FictitiousTestData;
import fi.karivanttinen.fplassistant.testsupport.RankingSeed;
import fi.karivanttinen.fplassistant.testsupport.TapasSeed;
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
    }

    @AfterEach
    void cleanUp() {
        if (jdbc == null) {
            return;
        }
        TapasSeed.ensure(jdbc);
        jdbc.update("DELETE FROM player_fixture_stats WHERE season_id = ?"
                + " AND player_id BETWEEN ? AND ?", SEASON_ID, FIRST_ID, LAST_ID);
        jdbc.update("DELETE FROM player WHERE season_id = ? AND id BETWEEN ? AND ?",
                SEASON_ID, FIRST_ID, LAST_ID);
        jdbc.update("DELETE FROM " + dailyTable + " WHERE season = ?"
                + " AND element_id BETWEEN ? AND ?", SEASON, FIRST_ID, LAST_ID);
        RankingSeed.cleanSchedule(jdbc, scheduleSource.gameweekTable(),
                scheduleSource.fixtureTable(), SEASON);
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
                fullName, team, "C%02d".formatted(clubIndex), CLUBS[clubIndex], position, cost,
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
    void aPlainRequestReturnsFifteenRealEligiblePlayersAndComputedEvidence() {
        String output = tools.buildSquad("", "", "", 100.0, 5, "");

        assertThat(output).contains("Starting XI").contains("Bench").contains("Captain")
                .contains("observed current-club 60+ 3/3")
                .contains("Schedule evidence: complete FPL capture");
    }

    @Test
    void theCaptainIsTheDeliberatelyBestOutfieldPlayer() {
        assertThat(tools.buildSquad("", "", "", 100.0, 5, ""))
                .contains("Captain: " + STAR_NAME + " (");
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
        String output = tools.buildSquad("", "", "C00:3", 100.0, 5, "");

        assertThat(output).contains("C00: 3/3").doesNotContain("could not find a club");
    }

    @Test
    void unknownNamesAndClubsAreRefused() {
        assertThat(tools.buildSquad("Nobodyatall", "", "", 100.0, 5, ""))
                .containsIgnoringCase("Nobodyatall").doesNotContain("Starting XI");
        assertThat(tools.buildSquad("", "", "Nonexistent FC:>=1", 100.0, 5, ""))
                .contains("Nonexistent FC").doesNotContain("Starting XI");
    }

    @Test
    void anAmbiguousNameListsTheMatchesAndStops() {
        insert(12_410, "Ambiguoustest", "First Ambiguous", 0, 3, 60, 2.0, 0.2);
        insert(12_411, "Ambiguoustest", "Second Ambiguous", 1, 3, 65, 2.0, 0.2);

        assertThat(tools.buildSquad("Ambiguoustest", "", "", 100.0, 5, ""))
                .containsIgnoringCase("Ambiguoustest").doesNotContain("Starting XI");
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
    void anIneligiblePinCanOnlyBeCarriedOnTheBench() {
        int id = 12_414;
        insert(id, "CameoPin", "Cameo Pin", 0, 3, 40, 1.0, 2.0);
        jdbc.update("UPDATE player_fixture_stats SET minutes = 20 WHERE season_id = ?"
                + " AND player_id = ?", SEASON_ID, id);

        String output = tools.buildSquad("CameoPin", "", "", 100.0, 5, "");

        assertThat(output).contains("CameoPin: included ✓")
                .contains("CameoPin").contains("bench only");
        String xi = output.substring(output.indexOf("Starting XI"), output.indexOf("Bench,"));
        assertThat(xi).doesNotContain("CameoPin");
    }

    @Test
    void anUnscoreableHorizonDisplaysNaRatherThanZeroForecasts() {
        jdbc.update("UPDATE " + scheduleSource.fixtureTable()
                + " SET started = true WHERE season = ? AND gameweek > 3", SEASON);
        try {
            String output = tools.buildSquad("", "", "", 100.0, 5, "");
            assertThat(output).contains("Starting XI").contains("xPts n/a")
                    .containsIgnoringCase("unranked").doesNotContain("0.00 xPts");
        } finally {
            jdbc.update("UPDATE " + scheduleSource.fixtureTable()
                    + " SET started = false WHERE season = ? AND gameweek > 3", SEASON);
        }
    }

    @Test
    void aMalformedQuotaIsExplainedRatherThanIgnored() {
        assertThat(tools.buildSquad("", "", "Ashfield Rovers:three", 100.0, 5, ""))
                .contains("Ashfield Rovers:three").doesNotContain("Starting XI");
    }
}
