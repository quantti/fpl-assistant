package dev.quantti.fplassistant.squad;

import java.time.LocalDate;
import java.util.List;

import dev.quantti.fplassistant.testsupport.SharedStatsSeed;
import dev.quantti.fplassistant.tools.ExpectedPoints;
import dev.quantti.fplassistant.tools.MetricSeasons;
import dev.quantti.fplassistant.tools.SeasonContext;
import dev.quantti.fplassistant.tools.SeasonResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * {@link CandidatePool} while the daily capture has moved to the new season but shared has not.
 * FPL reassigns element ids every summer, so metrics from the completed season can only cross
 * this boundary through an unambiguous player name.
 */
@SpringBootTest
@ActiveProfiles("test")
class CandidatePoolChangeoverTest {

    private static final LocalDate CAPTURE_DATE = LocalDate.of(2026, 9, 8);
    private static final int OKAFOR_OLD_ID = 9201;
    private static final int BRAMLEY_OLD_ID = 9202;
    private static final int CAPTURE_PLAYER_A = 9551;
    private static final int CAPTURE_PLAYER_B = 9552;
    private static final int NO_WEEKLY_PLAYER = 9558;
    private static final int METRIC_NAME_TWIN = 9559;
    private static final List<Integer> DAILY_IDS =
            List.of(OKAFOR_OLD_ID, BRAMLEY_OLD_ID, CAPTURE_PLAYER_A, CAPTURE_PLAYER_B,
                    NO_WEEKLY_PLAYER);

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CandidatePool pool;

    @Autowired
    SeasonResolver seasonResolver;

    @Value("${fpl.daily-price.table-name}")
    String table;

    MetricSeasons metric;

    @BeforeEach
    void seedCompletedSeasonOnly() {
        SharedStatsSeed.ensure(jdbc);
        assertThat(table).as("candidate-pool tests must use the isolated daily table")
                .endsWith("_test");
        cleanTestRows();

        SeasonContext resolved = seasonResolver.resolve();
        metric = seasonResolver.metricSeasons(resolved);
        assertThat(resolved.code()).isEqualTo(SharedStatsSeed.POPULATED_SEASON_CODE);
        assertThat(resolved.currentSeason()).isFalse();
        assertThat(metric.previous()).isEmpty();
    }

    @AfterEach
    void cleanUp() {
        cleanTestRows();
    }

    private void cleanTestRows() {
        for (int id : DAILY_IDS) {
            jdbc.update("DELETE FROM " + table
                            + " WHERE season IN (?, ?) AND price_date = ? AND element_id = ?",
                    SharedStatsSeed.CURRENT_SEASON_CODE, SharedStatsSeed.POPULATED_SEASON_CODE,
                    CAPTURE_DATE, id);
        }
        jdbc.update("DELETE FROM player_fixture_stats WHERE season_id = ? AND player_id = ?"
                        + " AND fixture_id IN (9101, 9103)",
                SharedStatsSeed.POPULATED_SEASON_ID, NO_WEEKLY_PLAYER);
        jdbc.update("DELETE FROM player WHERE season_id = ? AND id = ?",
                SharedStatsSeed.POPULATED_SEASON_ID, METRIC_NAME_TWIN);
    }

    @Test
    void swappedElementIdsStillAttachEachPlayersOwnMetricsByName() {
        daily(SharedStatsSeed.CURRENT_SEASON_CODE, BRAMLEY_OLD_ID, "oKaFoR", "Emeka Okafor",
                9561, "Newbridge Athletic", "NBA", 3, 81, 7.25, "a", null);
        daily(SharedStatsSeed.CURRENT_SEASON_CODE, OKAFOR_OLD_ID, "BRAMLEY", "Iker Bramley",
                9562, "Summer United", "SUM", 3, 62, 1.50, "d", "75% chance of playing");

        List<SquadCandidate> candidates = changeoverCandidates();

        assertThat(candidates).extracting(SquadCandidate::elementId)
                .containsExactlyInAnyOrder(BRAMLEY_OLD_ID, OKAFOR_OLD_ID);

        SquadCandidate okafor = candidate(candidates, BRAMLEY_OLD_ID);
        assertThat(okafor.webName()).isEqualTo("oKaFoR");
        assertThat(okafor.fullName()).isEqualTo("Emeka Okafor");
        assertThat(okafor.club()).isEqualTo("Newbridge Athletic");
        assertThat(okafor.clubShort()).isEqualTo("NBA");
        assertThat(okafor.teamId()).isEqualTo(9561);
        assertThat(okafor.nowCost()).isEqualTo(81);
        assertThat(okafor.ownership()).isEqualTo(7.25);
        assertThat(okafor.status()).isEqualTo("a");
        assertThat(okafor.tier()).isEqualTo(SquadCandidate.Tier.RANKABLE);
        assertThat(okafor.minutesWindow().totalMinutes()).isEqualTo(270);
        assertThat(okafor.minutesWindow().expected()).isEqualTo(3);
        assertThat(ExpectedPoints.numeric(okafor.metrics().get("xg90")))
                .isCloseTo(1.25 * 90 / 270, within(1e-9));

        SquadCandidate bramley = candidate(candidates, OKAFOR_OLD_ID);
        assertThat(bramley.webName()).isEqualTo("BRAMLEY");
        assertThat(bramley.fullName()).isEqualTo("Iker Bramley");
        assertThat(bramley.club()).isEqualTo("Summer United");
        assertThat(bramley.clubShort()).isEqualTo("SUM");
        assertThat(bramley.teamId()).isEqualTo(9562);
        assertThat(bramley.nowCost()).isEqualTo(62);
        assertThat(bramley.ownership()).isEqualTo(1.50);
        assertThat(bramley.status()).isEqualTo("d");
        assertThat(bramley.news()).isEqualTo("75% chance of playing");
        assertThat(bramley.tier()).isEqualTo(SquadCandidate.Tier.RANKABLE);
        assertThat(bramley.minutesWindow().totalMinutes()).isEqualTo(180);
        assertThat(bramley.minutesWindow().expected()).isEqualTo(2);
        assertThat(ExpectedPoints.numeric(bramley.metrics().get("xg90")))
                .isCloseTo(0.75 * 90 / 180, within(1e-9));
    }

    @Test
    void reusedIdsDoNotGiveUnmatchedPlayersSomeoneElsesMetrics() {
        daily(SharedStatsSeed.CURRENT_SEASON_CODE, OKAFOR_OLD_ID, "Newarrival", "New Arrival",
                9561, "Newbridge Athletic", "NBA", 3, 50, 0.25, "a", null);
        daily(SharedStatsSeed.CURRENT_SEASON_CODE, BRAMLEY_OLD_ID, "Freshdoubt", "Fresh Doubt",
                9562, "Summer United", "SUM", 3, 45, 0.10, "d", "Knock");

        List<SquadCandidate> candidates = changeoverCandidates();

        assertThat(candidates).extracting(SquadCandidate::elementId)
                .containsExactly(OKAFOR_OLD_ID);
        SquadCandidate newcomer = candidates.getFirst();
        assertThat(newcomer.tier()).isEqualTo(SquadCandidate.Tier.BODY);
        assertThat(newcomer.metrics()).isEmpty();
        assertThat(newcomer.minutesWindow()).isNull();
        assertThat(newcomer.exposure()).isNull();
    }

    @Test
    void twoFitCapturedNamesakesBothRemainBodies() {
        daily(SharedStatsSeed.CURRENT_SEASON_CODE, CAPTURE_PLAYER_A, "Okafor", "First Okafor",
                9561, "Newbridge Athletic", "NBA", 3, 50, 0.25, "a", null);
        daily(SharedStatsSeed.CURRENT_SEASON_CODE, CAPTURE_PLAYER_B, "OKAFOR", "Second Okafor",
                9562, "Summer United", "SUM", 3, 45, 0.10, "a", null);

        List<SquadCandidate> candidates = changeoverCandidates();

        assertThat(candidates).extracting(SquadCandidate::elementId)
                .containsExactlyInAnyOrder(CAPTURE_PLAYER_A, CAPTURE_PLAYER_B);
        assertThat(candidates).allSatisfy(candidate -> {
            assertThat(candidate.tier()).isEqualTo(SquadCandidate.Tier.BODY);
            assertThat(candidate.metrics()).isEmpty();
        });
    }

    @Test
    void anUnavailableCapturedNamesakeStillBlocksTheFitPlayersNameBridge() {
        daily(SharedStatsSeed.CURRENT_SEASON_CODE, CAPTURE_PLAYER_A, "Okafor", "Fit Okafor",
                9561, "Newbridge Athletic", "NBA", 3, 50, 0.25, "a", null);
        daily(SharedStatsSeed.CURRENT_SEASON_CODE, CAPTURE_PLAYER_B, "OKAFOR", "Injured Okafor",
                9562, "Summer United", "SUM", 3, 45, 0.10, "i", "Hamstring injury");

        List<SquadCandidate> candidates = changeoverCandidates();

        assertThat(candidates).extracting(SquadCandidate::elementId)
                .containsExactly(CAPTURE_PLAYER_A);
        assertThat(candidates.getFirst().tier()).isEqualTo(SquadCandidate.Tier.BODY);
        assertThat(candidates.getFirst().metrics()).isEmpty();
    }

    @Test
    void aMetricSeasonNamesakeWithoutStatsStillMakesTheNameAmbiguous() {
        jdbc.update("""
                INSERT INTO player (id, season_id, team_id, first_name, second_name, web_name,
                                    element_type, now_cost, status)
                VALUES (?, ?, ?, 'Other', 'Okafor', 'OKAFOR', 3, 45, 'a')
                """, METRIC_NAME_TWIN, SharedStatsSeed.POPULATED_SEASON_ID, SharedStatsSeed.TEAM_ASHFIELD);
        daily(SharedStatsSeed.CURRENT_SEASON_CODE, CAPTURE_PLAYER_A, "Okafor", "Current Okafor",
                9561, "Newbridge Athletic", "NBA", 3, 50, 0.25, "a", null);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM player_fixture_stats WHERE season_id = ? AND player_id = ?",
                Integer.class, SharedStatsSeed.POPULATED_SEASON_ID, METRIC_NAME_TWIN)).isZero();

        SquadCandidate candidate = changeoverCandidates().getFirst();

        assertThat(candidate.elementId()).isEqualTo(CAPTURE_PLAYER_A);
        assertThat(candidate.tier()).isEqualTo(SquadCandidate.Tier.BODY);
        assertThat(candidate.metrics()).isEmpty();
    }

    @Test
    void sameSeasonUsesIdsEvenWhenTheDisplayNameChangedOrTheWeeklyRowIsMissing() {
        daily(SharedStatsSeed.POPULATED_SEASON_CODE, OKAFOR_OLD_ID, "Summername", "Renamed Player",
                9561, "Newbridge Athletic", "NBA", 3, 82, 8.00, "a", null);
        daily(SharedStatsSeed.POPULATED_SEASON_CODE, NO_WEEKLY_PLAYER, "Missingweekly",
                "Missing Weekly Row", 9562, "Summer United", "SUM", 3, 48, 0.15, "a", null);
        seedMetricsWithoutWeeklyPlayer();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM player WHERE season_id = ? AND id = ?",
                Integer.class, SharedStatsSeed.POPULATED_SEASON_ID, NO_WEEKLY_PLAYER)).isZero();

        List<SquadCandidate> candidates = candidates(SharedStatsSeed.POPULATED_SEASON_CODE);

        assertThat(candidates).extracting(SquadCandidate::elementId)
                .containsExactlyInAnyOrder(OKAFOR_OLD_ID, NO_WEEKLY_PLAYER);
        SquadCandidate renamed = candidate(candidates, OKAFOR_OLD_ID);
        assertThat(renamed.webName()).isEqualTo("Summername");
        assertThat(renamed.tier()).isEqualTo(SquadCandidate.Tier.RANKABLE);
        assertThat(renamed.minutesWindow().totalMinutes()).isEqualTo(270);
        assertThat(ExpectedPoints.numeric(renamed.metrics().get("xg90")))
                .isCloseTo(1.25 * 90 / 270, within(1e-9));

        SquadCandidate missingWeekly = candidate(candidates, NO_WEEKLY_PLAYER);
        assertThat(missingWeekly.tier()).isEqualTo(SquadCandidate.Tier.RANKABLE);
        assertThat(missingWeekly.minutesWindow().totalMinutes()).isEqualTo(180);
        assertThat(missingWeekly.minutesWindow().expected()).isEqualTo(3);
        assertThat(ExpectedPoints.numeric(missingWeekly.metrics().get("xg90")))
                .isCloseTo(0.30, within(1e-9));
    }

    private void seedMetricsWithoutWeeklyPlayer() {
        jdbc.update("""
                INSERT INTO player_fixture_stats
                    (fixture_id, player_id, season_id, gameweek, player_team_id,
                     opponent_team_id, was_home, kickoff_time, minutes, total_points,
                     expected_goals, expected_assists, expected_goal_involvements,
                     clean_sheets, goals_conceded, expected_goals_conceded, value)
                VALUES
                    (9101, ?, ?, 1, ?, ?, true, TIMESTAMPTZ '2025-08-16 14:00:00+00',
                     90, 4, 0.20, 0.10, 0.30, 0, 1, 1.10, 48),
                    (9103, ?, ?, 2, ?, ?, false, TIMESTAMPTZ '2025-08-23 14:00:00+00',
                     90, 5, 0.40, 0.20, 0.60, 0, 1, 0.90, 48)
                """,
                NO_WEEKLY_PLAYER, SharedStatsSeed.POPULATED_SEASON_ID,
                SharedStatsSeed.TEAM_ASHFIELD, SharedStatsSeed.TEAM_NORTHMOOR_CITY,
                NO_WEEKLY_PLAYER, SharedStatsSeed.POPULATED_SEASON_ID,
                SharedStatsSeed.TEAM_ASHFIELD, SharedStatsSeed.TEAM_NORTHMOOR_CITY);
    }

    private void daily(String season, int elementId, String webName, String fullName,
                       int teamId, String teamName, String teamShort, int elementType,
                       int nowCost, double ownership, String status, String news) {
        jdbc.update("INSERT INTO " + table + " (season, price_date, element_id, web_name,"
                        + " full_name, team_id, team_short, team_name, element_type, now_cost,"
                        + " cost_change_event, cost_change_start, selected_by_percent, status, news)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?, ?, ?)",
                season, CAPTURE_DATE, elementId, webName, fullName, teamId, teamShort, teamName,
                elementType, nowCost, ownership, status, news);
    }

    private List<SquadCandidate> changeoverCandidates() {
        return candidates(SharedStatsSeed.CURRENT_SEASON_CODE);
    }

    private List<SquadCandidate> candidates(String captureSeason) {
        return pool.load(new CandidatePool.Capture(captureSeason, CAPTURE_DATE), metric)
                .candidates().stream()
                .filter(candidate -> DAILY_IDS.contains(candidate.elementId()))
                .toList();
    }

    private static SquadCandidate candidate(List<SquadCandidate> candidates, int elementId) {
        return candidates.stream().filter(candidate -> candidate.elementId() == elementId)
                .findFirst().orElseThrow();
    }
}
