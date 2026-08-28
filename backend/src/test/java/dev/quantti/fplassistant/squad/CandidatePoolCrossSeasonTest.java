package dev.quantti.fplassistant.squad;

import java.time.LocalDate;
import java.util.List;

import dev.quantti.fplassistant.testsupport.SharedStatsSeed;
import dev.quantti.fplassistant.tools.ExpectedPoints;
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
 * {@link CandidatePool} when the season being played has begun but holds too little data to
 * judge anyone on — the state every season spends its first weeks in, and the state
 * {@link CandidatePoolIntegrationTest} never reaches because it never seeds a season more recent
 * than {@link SharedStatsSeed#POPULATED_SEASON_ID}.
 *
 * <p>Follows {@code PickToolsCrossSeasonTest}'s precedent exactly: a handful of rows land in
 * {@link SharedStatsSeed#CURRENT_SEASON_ID} so {@link SeasonResolver} prefers it, and
 * {@code player_fixture_stats} is left empty there so {@code CandidatePool} has to bridge back to
 * {@link SharedStatsSeed#POPULATED_SEASON_ID} by name.
 */
@SpringBootTest
@ActiveProfiles("test")
class CandidatePoolCrossSeasonTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 27);
    private static final int CURRENT = SharedStatsSeed.CURRENT_SEASON_ID;
    private static final int GAMEWEEK = 111;
    private static final int TEAM = 9001;

    /** This season's element id for a player who shares shared' Okafor's {@code web_name}. */
    private static final int BRIDGED = 9421;

    /** A second current-season player also named "Okafor" — the ambiguity case. */
    private static final int AMBIGUOUS = 9422;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CandidatePool pool;

    @Autowired
    SeasonResolver seasonResolver;

    @Value("${fpl.daily-price.table-name}")
    String table;

    /** Read at runtime: once the new season holds a gameweek and a player, the resolver prefers
     * it over {@link SharedStatsSeed#POPULATED_SEASON_ID}, so daily-price rows must be seeded under
     * its own code, not a hardcoded one. */
    String season;

    @BeforeEach
    void seed() {
        SharedStatsSeed.ensure(jdbc);
        beginTheNewSeason();
        season = seasonResolver.resolve().code();
        deleteTestRows();
        jdbc.update("INSERT INTO " + table + " (season, price_date, element_id, web_name,"
                        + " full_name, team_id, team_short, team_name, element_type, now_cost,"
                        + " cost_change_event, cost_change_start, selected_by_percent, status, news)"
                        + " VALUES (?, ?, ?, 'Okafor', 'Okafor Fictitious', 9001, 'ASH',"
                        + " 'Ashfield Rovers', 3, 80, 0, 0, 12.0, 'a', NULL)",
                season, TODAY, BRIDGED);
    }

    /**
     * Removes only the rows this class inserts, by id. Both the current season's {@code player}
     * rows and the daily-price rows are shared tables other test classes also write to, so a
     * blanket delete by {@code season_id} (shared tables) or by {@code season} code (daily price)
     * would risk wiping another class's rows — the same reasoning
     * {@code PickToolsCrossSeasonTest} documents for the shared side, extended to the daily-price
     * table because this class is the first to key it to the resolver's live season code too.
     */
    @AfterEach
    void cleanUp() {
        deleteTestRows();
        jdbc.update("DELETE FROM player_fixture_stats WHERE season_id = ? AND fixture_id IN (9121, 9122)",
                CURRENT);
        jdbc.update("DELETE FROM fixture WHERE season_id = ? AND id IN (9121, 9122)", CURRENT);
        jdbc.update("DELETE FROM team WHERE season_id = ? AND id = 9002", CURRENT);
        jdbc.update("DELETE FROM player WHERE season_id = ? AND id IN (?, ?)", CURRENT, BRIDGED, AMBIGUOUS);
        jdbc.update("DELETE FROM gameweek WHERE season_id = ? AND id = ?", CURRENT, GAMEWEEK);
        jdbc.update("DELETE FROM team WHERE season_id = ? AND id = ?", CURRENT, TEAM);
    }

    private void deleteTestRows() {
        jdbc.update("DELETE FROM " + table + " WHERE season = ? AND element_id IN (?, ?)",
                season, BRIDGED, AMBIGUOUS);
    }

    /**
     * Enough of the new season for {@link SeasonResolver} to prefer it — populated means holding
     * both gameweeks and players — but no {@code player_fixture_stats}, exactly the early-season
     * position: a squad, a price, and nothing to judge.
     */
    private void beginTheNewSeason() {
        jdbc.execute("""
                INSERT INTO gameweek (id, season_id, name, deadline_time, finished, data_checked,
                                      is_current, is_next) VALUES
                    (111, 9027, 'Gameweek 1', TIMESTAMPTZ '2026-08-21 17:30:00+00', true, true,
                     true, false)
                ON CONFLICT (id, season_id) DO NOTHING
                """);
        jdbc.execute("""
                INSERT INTO team (id, season_id, code, name, short_name, strength) VALUES
                    (9001, 9027, 9001, 'Ashfield Rovers', 'ASH', 4),
                    (9002, 9027, 9002, 'Northmoor City', 'NMC', 3)
                ON CONFLICT (id, season_id) DO NOTHING
                """);
        // Two played fixtures so a candidate can have matches this season. Ids 9121/9122 rather
        // than NewSeasonSeed's 9111/9112, so the two classes never touch each other's rows.
        jdbc.execute("""
                INSERT INTO fixture (id, season_id, gameweek, code, team_h, team_a,
                                     team_h_score, team_a_score, team_h_difficulty,
                                     team_a_difficulty, kickoff_time, started, finished) VALUES
                    (9121, 9027, 111, 9121, 9001, 9002, 1, 1, 2, 3,
                        TIMESTAMPTZ '2026-08-15 14:00:00+00', true, true),
                    (9122, 9027, 111, 9122, 9002, 9001, 0, 0, 3, 2,
                        TIMESTAMPTZ '2026-08-16 14:00:00+00', true, true)
                ON CONFLICT (id, season_id) DO NOTHING
                """);
        jdbc.execute("""
                INSERT INTO player (id, season_id, team_id, first_name, second_name, web_name,
                                    element_type, now_cost, status, total_points, minutes,
                                    goals_scored, assists, form, selected_by_percent) VALUES
                    (9421, 9027, 9001, 'Emeka', 'Okafor', 'Okafor', 3, 80, 'a', 12, 90, 1, 0, 6.0, 4.0)
                ON CONFLICT (id, season_id) DO NOTHING
                """);
    }

    /**
     * The point of the whole path: this season's candidate, judged on last season's football,
     * with shared' Okafor's actual minutes and metrics attached through the name bridge.
     */
    @Test
    void aCurrentSeasonCandidateIsBridgedToLastSeasonsRecordByName() {
        SquadCandidate okafor = candidate(BRIDGED);

        assertThat(okafor.tier()).isEqualTo(SquadCandidate.Tier.RANKABLE);
        assertThat(okafor.minutesInWindow()).isEqualTo(270.0);
        assertThat(((Number) okafor.metrics().get("xg90")).doubleValue()).isGreaterThan(0);
    }

    /**
     * Two current-season players share "Okafor". Without a pool-side uniqueness guard, both
     * would inherit shared' single Okafor's numbers through the bridge — the exact failure the
     * name-matching discipline exists to prevent. {@code CandidatePool} uses a {@code LEFT JOIN}
     * on purpose, unlike {@code PickTools}'s {@code INNER JOIN}: neither player may vanish from
     * the pool over this, because both are still fit and purchasable. They just lose the metric
     * attachment and fall back to {@code BODY}.
     */
    @Test
    void aNameAmbiguousInThePoolsOwnSeasonKeepsBothPlayersAsBodies() {
        jdbc.execute("""
                INSERT INTO player (id, season_id, team_id, first_name, second_name, web_name,
                                    element_type, now_cost, status, total_points, minutes,
                                    goals_scored, assists, form, selected_by_percent) VALUES
                    (9422, 9027, 9001, 'Someone', 'Okafor', 'Okafor', 2, 45, 'a', 5, 90, 0, 0, 1.0, 1.0)
                ON CONFLICT (id, season_id) DO NOTHING
                """);
        jdbc.update("INSERT INTO " + table + " (season, price_date, element_id, web_name,"
                        + " full_name, team_id, team_short, team_name, element_type, now_cost,"
                        + " cost_change_event, cost_change_start, selected_by_percent, status, news)"
                        + " VALUES (?, ?, ?, 'Okafor', 'Second Okafor Fictitious', 9001, 'ASH',"
                        + " 'Ashfield Rovers', 2, 45, 0, 0, 1.0, 'a', NULL)",
                season, TODAY, AMBIGUOUS);

        List<SquadCandidate> candidates = load();

        assertThat(candidates).extracting(SquadCandidate::elementId)
                .containsExactlyInAnyOrder(BRIDGED, AMBIGUOUS);
        assertThat(candidates).allSatisfy(
                c -> assertThat(c.tier()).isEqualTo(SquadCandidate.Tier.BODY));
    }

    private SquadCandidate candidate(int elementId) {
        return load().stream().filter(c -> c.elementId() == elementId).findFirst().orElseThrow();
    }

    private List<SquadCandidate> load() {
        var resolved = seasonResolver.resolve();
        CandidatePool.Capture capture = pool.latestCapture().orElseThrow();
        return pool.load(capture, seasonResolver.metricSeasons(resolved))
                .stream()
                .filter(c -> c.elementId() == BRIDGED || c.elementId() == AMBIGUOUS)
                .toList();
    }

    /**
     * Two full matches this season is 30% on the ramp. SharedStats' Okafor (the bridge target) has
     * 1.25 xG across 270 minutes last season — 0.4167 xG/90 — and 0.25 in each of two matches
     * here, so the blend is 0.3 x 0.25 + 0.7 x 0.4167.
     */
    @Test
    void twoMatchesThisSeasonBlendAtThirtyPercentInTheMetricsMap() {
        jdbc.execute("""
                INSERT INTO player_fixture_stats (fixture_id, player_id, season_id, gameweek,
                        player_team_id, opponent_team_id, was_home, kickoff_time, minutes,
                        total_points, goals_scored, assists, expected_goals, expected_assists,
                        expected_goal_involvements, clean_sheets, goals_conceded,
                        expected_goals_conceded, value) VALUES
                    (9121, 9421, 9027, 111, 9001, 9002, true,  TIMESTAMPTZ '2026-08-15 14:00:00+00', 90, 2, 0, 0, 0.25, 0.00, 0.25, 0, 1, 1.20, 80),
                    (9122, 9421, 9027, 111, 9001, 9002, false, TIMESTAMPTZ '2026-08-16 14:00:00+00', 90, 6, 0, 0, 0.25, 0.00, 0.25, 1, 0, 0.80, 80)
                ON CONFLICT (fixture_id, player_id, season_id) DO NOTHING
                """);

        SquadCandidate okafor = load().stream()
                .filter(c -> c.elementId() == BRIDGED).findFirst().orElseThrow();

        assertThat(okafor.tier()).isEqualTo(SquadCandidate.Tier.RANKABLE);
        assertThat(okafor.metrics().get("blend_weight")).isEqualTo(0.30);
        assertThat(ExpectedPoints.numeric(okafor.metrics().get("xg90")))
                .isCloseTo(0.3 * 0.25 + 0.7 * (1.25 * 90 / 270), within(1e-9));
        // Both seasons' minutes feed expected minutes per fixture: 180 now, 270 last season.
        assertThat(okafor.minutesInWindow()).isEqualTo(450.0);
    }
}
