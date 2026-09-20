package dev.quantti.fplassistant.squad;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import dev.quantti.fplassistant.testsupport.FictitiousTestData;
import dev.quantti.fplassistant.testsupport.TapasSeed;
import dev.quantti.fplassistant.tools.DataAge;
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

/**
 * {@link SquadTools#buildSquad} when the season being played has begun but holds too little data
 * to judge anyone on — the state {@link CandidatePoolCrossSeasonTest} exercises for the pool
 * alone, carried through here to the tool's own metric-provenance line.
 *
 * <p>Follows {@code CandidatePoolCrossSeasonTest}'s precedent exactly: a handful of rows land in
 * {@link TapasSeed#CURRENT_SEASON_ID} so {@link SeasonResolver} prefers it, and no
 * {@code player_fixture_stats} exist there, so {@code CandidatePool} has to bridge back to
 * {@link TapasSeed#POPULATED_SEASON_ID} by name.
 *
 * <p>{@link SquadToolsIntegrationTest}'s fixture can never reach this branch — it never seeds a
 * season more recent than {@code POPULATED_SEASON_ID} — which is exactly how a bug here survived:
 * {@code buildSquad} called {@code previousSeasonProvenance} on the resolved (current, too-thin)
 * season instead of on the actual previous one, so the reply named the season being played as a
 * "COMPLETED PREVIOUS season" in a sentence that contradicted itself.
 */
@SpringBootTest
@ActiveProfiles("test")
class SquadToolsCrossSeasonTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 27);

    private static final int CURRENT = TapasSeed.CURRENT_SEASON_ID;
    private static final int GAMEWEEK = 151;
    private static final int PRESENCE_TEAM = 9001;
    private static final int PRESENCE_PLAYER = 9440;

    /** This season's pool: six clubs, four positions, two price tiers — enough for a legal 15. */
    private static final int FIRST_ID = 9900;
    private static final int LAST_ID = 9947;
    private static final String[] CLUBS = {"Season Rovers", "Changeover Town", "Bridge City",
            "Fallback Athletic", "Interim United", "Carryover FC"};

    /** The metric bridge, scoped to {@link TapasSeed#POPULATED_SEASON_ID} — see {@link #insert}. */
    private static final int METRIC_TEAM = 9950;
    private static final int METRIC_OPPONENT = 9951;
    private static final int METRIC_FIXTURE_GW1 = 9970;
    private static final int METRIC_FIXTURE_GW2 = 9971;

    /**
     * The season the tool actually resolves to, read at runtime after {@link #beginTheNewSeason}
     * rather than assumed — the same discipline {@code CandidatePoolCrossSeasonTest} follows.
     */
    String season;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SquadTools tools;

    @Autowired
    SeasonResolver seasonResolver;

    @Value("${fpl.daily-price.table-name}")
    String table;

    @BeforeEach
    void seed() {
        TapasSeed.ensure(jdbc);
        beginTheNewSeason();
        season = seasonResolver.resolve().code();
        FictitiousTestData.assertSafeSeason(season);
        assertThat(table).as("squad tests must use the isolated table").endsWith("_test");
        assertThat(season).as("the presence rows below must actually flip the resolver")
                .isEqualTo(TapasSeed.CURRENT_SEASON_CODE);
        deleteTestRows();
        seedMetricScaffold();

        int id = FIRST_ID;
        for (String club : CLUBS) {
            for (int type = 1; type <= 4; type++) {
                for (int n = 0; n < 2; n++) {
                    insert(id, "Q%d".formatted(id), club, type,
                            n == 0 ? 105 : 45, n == 1 ? 35.0 : 1.5);
                    id++;
                }
            }
        }
    }

    @AfterEach
    void cleanUp() {
        deleteTestRows();
        jdbc.update("DELETE FROM player_fixture_stats WHERE season_id = ?"
                        + " AND player_id BETWEEN ? AND ?",
                TapasSeed.POPULATED_SEASON_ID, FIRST_ID, LAST_ID);
        jdbc.update("DELETE FROM player WHERE season_id = ? AND id BETWEEN ? AND ?",
                TapasSeed.POPULATED_SEASON_ID, FIRST_ID, LAST_ID);
        jdbc.update("DELETE FROM fixture WHERE season_id = ? AND id IN (?, ?)",
                TapasSeed.POPULATED_SEASON_ID, METRIC_FIXTURE_GW1, METRIC_FIXTURE_GW2);
        jdbc.update("DELETE FROM team WHERE season_id = ? AND id IN (?, ?)",
                TapasSeed.POPULATED_SEASON_ID, METRIC_TEAM, METRIC_OPPONENT);
        jdbc.update("DELETE FROM player WHERE season_id = ? AND id = ?", CURRENT, PRESENCE_PLAYER);
        jdbc.update("DELETE FROM gameweek WHERE season_id = ? AND id = ?", CURRENT, GAMEWEEK);
        jdbc.update("DELETE FROM team WHERE season_id = ? AND id = ?", CURRENT, PRESENCE_TEAM);
    }

    private void deleteTestRows() {
        jdbc.update("DELETE FROM " + table + " WHERE season = ? AND element_id BETWEEN ? AND ?",
                TapasSeed.CURRENT_SEASON_CODE, FIRST_ID, LAST_ID);
    }

    /**
     * Enough of the new season for {@link SeasonResolver} to prefer it — populated means holding
     * both a gameweek and a player — but no {@code player_fixture_stats} there, exactly the
     * early-season position {@code CandidatePoolCrossSeasonTest} documents.
     */
    private void beginTheNewSeason() {
        jdbc.execute("""
                INSERT INTO gameweek (id, season_id, name, deadline_time, finished, data_checked,
                                      is_current, is_next) VALUES
                    (%d, 9027, 'Gameweek 1', TIMESTAMPTZ '2026-08-21 17:30:00+00', true, true,
                     true, false)
                ON CONFLICT (id, season_id) DO NOTHING
                """.formatted(GAMEWEEK));
        jdbc.execute("""
                INSERT INTO team (id, season_id, code, name, short_name, strength) VALUES
                    (9001, 9027, 9001, 'Ashfield Rovers', 'ASH', 4)
                ON CONFLICT (id, season_id) DO NOTHING
                """);
        jdbc.execute("""
                INSERT INTO player (id, season_id, team_id, first_name, second_name, web_name,
                                    element_type, now_cost, status, total_points, minutes,
                                    goals_scored, assists, form, selected_by_percent) VALUES
                    (%d, 9027, 9001, 'Presence', 'Only', 'PresenceOnly', 3, 50, 'a', 0, 0, 0, 0,
                     0.0, 0.0)
                ON CONFLICT (id, season_id) DO NOTHING
                """.formatted(PRESENCE_PLAYER));
    }

    /**
     * One fictitious club playing itself over two finished gameweeks, scoped to
     * {@link TapasSeed#POPULATED_SEASON_ID} — the season the bridge falls back to once
     * {@link #beginTheNewSeason} has made the resolver prefer the current one.
     */
    private void seedMetricScaffold() {
        jdbc.update("INSERT INTO team (id, season_id, code, name, short_name, strength)"
                        + " VALUES (?, ?, ?, 'Bridge Town', 'BRT', 3)"
                        + " ON CONFLICT (id, season_id) DO NOTHING",
                METRIC_TEAM, TapasSeed.POPULATED_SEASON_ID, METRIC_TEAM);
        jdbc.update("INSERT INTO team (id, season_id, code, name, short_name, strength)"
                        + " VALUES (?, ?, ?, 'Bridge Rivals', 'BRR', 3)"
                        + " ON CONFLICT (id, season_id) DO NOTHING",
                METRIC_OPPONENT, TapasSeed.POPULATED_SEASON_ID, METRIC_OPPONENT);
        jdbc.update("INSERT INTO fixture (id, season_id, gameweek, code, team_h, team_a,"
                        + " team_h_score, team_a_score, team_h_difficulty, team_a_difficulty,"
                        + " kickoff_time, started, finished)"
                        + " VALUES (?, ?, 1, ?, ?, ?, 1, 1, 3, 3,"
                        + " TIMESTAMPTZ '2025-08-16 14:00:00+00', true, true)"
                        + " ON CONFLICT (id, season_id) DO NOTHING",
                METRIC_FIXTURE_GW1, TapasSeed.POPULATED_SEASON_ID, METRIC_FIXTURE_GW1,
                METRIC_TEAM, METRIC_OPPONENT);
        jdbc.update("INSERT INTO fixture (id, season_id, gameweek, code, team_h, team_a,"
                        + " team_h_score, team_a_score, team_h_difficulty, team_a_difficulty,"
                        + " kickoff_time, started, finished)"
                        + " VALUES (?, ?, 2, ?, ?, ?, 1, 1, 3, 3,"
                        + " TIMESTAMPTZ '2025-08-23 14:00:00+00', true, true)"
                        + " ON CONFLICT (id, season_id) DO NOTHING",
                METRIC_FIXTURE_GW2, TapasSeed.POPULATED_SEASON_ID, METRIC_FIXTURE_GW2,
                METRIC_OPPONENT, METRIC_TEAM);
    }

    /**
     * The daily-price row lives under this season's own code; the metric that bridges to it lives
     * under {@link TapasSeed#POPULATED_SEASON_ID} and is matched by {@code web_name} rather than
     * by id, since {@code CandidatePool} bridges by name whenever the metric season differs from
     * the pool's own — which is exactly the state this class forces.
     */
    private void insert(int elementId, String webName, String club, int elementType, int nowCost,
                        double ownership) {
        jdbc.update("INSERT INTO " + table + " (season, price_date, element_id, web_name,"
                        + " full_name, team_id, team_short, team_name, element_type, now_cost,"
                        + " cost_change_event, cost_change_start, selected_by_percent, status, news)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?, 'a', NULL)",
                TapasSeed.CURRENT_SEASON_CODE, TODAY, elementId, webName, webName + " Fictitious",
                club.hashCode() & 0x7fff, club.substring(0, 3).toUpperCase(java.util.Locale.ROOT),
                club, elementType, nowCost, ownership);

        double xg = switch (elementType) {
            case 4 -> 0.40;
            case 3 -> 0.20;
            case 2 -> 0.02;
            default -> 0.00;
        };
        double xa = elementType >= 3 ? 0.15 : 0.03;
        double xgc = elementType == 1 || elementType == 2 ? 1.00 : 1.20;

        jdbc.update("INSERT INTO player (id, season_id, team_id, first_name, second_name,"
                        + " web_name, element_type, now_cost, status, total_points, minutes,"
                        + " form, selected_by_percent)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'a', 12, 180, 4.0, ?)"
                        + " ON CONFLICT (id, season_id) DO NOTHING",
                elementId, TapasSeed.POPULATED_SEASON_ID, METRIC_TEAM, webName, webName, webName,
                elementType, nowCost, ownership);
        seedAppearance(elementId, METRIC_FIXTURE_GW1, 1, true, xg, xa, xgc);
        seedAppearance(elementId, METRIC_FIXTURE_GW2, 2, false, xg, xa, xgc);
    }

    private void seedAppearance(int elementId, int fixtureId, int gameweek, boolean wasHome,
                                double xg, double xa, double xgc) {
        jdbc.update("INSERT INTO player_fixture_stats (fixture_id, player_id, season_id,"
                        + " gameweek, player_team_id, opponent_team_id, was_home, kickoff_time,"
                        + " minutes, total_points, expected_goals, expected_assists,"
                        + " expected_goal_involvements, goals_conceded, expected_goals_conceded,"
                        + " value)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 90, 6, ?, ?, ?, 1, ?, 50)"
                        + " ON CONFLICT (fixture_id, player_id, season_id) DO NOTHING",
                fixtureId, elementId, TapasSeed.POPULATED_SEASON_ID, gameweek, METRIC_TEAM,
                METRIC_OPPONENT, wasHome, OffsetDateTime.parse("2025-08-16T14:00:00Z"), xg, xa,
                xg + xa, xgc);
    }

    /** Historical ability can inform a projection, but cannot certify a current role. */
    @Test
    void historicalOnlyEvidenceRefusesInsteadOfInventingAnEligibleEleven() {
        String output = tools.buildSquad("", "", "", 100.0, 5, "");

        assertThat(output).doesNotContain("Starting XI");
        assertThat(output).contains("complete current evidence").containsIgnoringCase("budget")
                .as("an infeasible-XI refusal still rests on PFS metrics")
                .contains(DataAge.RANKING_METRIC_REFRESH);
        assertThat(output).doesNotContain("Data source: season " + TapasSeed.CURRENT_SEASON_CODE
                + " — a COMPLETED PREVIOUS season");
    }

}
