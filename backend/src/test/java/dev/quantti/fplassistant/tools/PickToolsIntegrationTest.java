package dev.quantti.fplassistant.tools;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
    void returnsOnlyEligibleDifferentialsWithPerFixtureEvidence() {
        String result = tools.getDifferentialPicks("any", 10, 5);

        assertThat(result).contains("Differential picks")
                .contains("Reliable", "Defender", "Keeper")
                .contains("Score/fixture", "expected/fixture", "realised points/fixture")
                .contains("observed current-club 60+ 3/3")
                .contains("short window: 3 fixtures")
                .contains("complete FPL schedule capture at " + CAPTURED_AT)
                .contains("Avg FDR")
                .doesNotContain(" Cameo (").doesNotContain(" Doubtful (")
                .doesNotContain(" LostRole (").doesNotContain(" Newcomer (")
                .doesNotContain(" Injured (").doesNotContain("Pts/90");
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
                .contains("Unrecognised position").contains("sweeper");
    }

    @Test
    void exclusionCountsDistinguishAvailabilityMinutesAndUnknownEvidence() {
        String result = tools.getDifferentialPicks("any", 100, 5);

        assertThat(result).contains("availability risk 2")
                .contains("observed minutes risk 2")
                .contains("unknown current evidence 1");
    }
}
