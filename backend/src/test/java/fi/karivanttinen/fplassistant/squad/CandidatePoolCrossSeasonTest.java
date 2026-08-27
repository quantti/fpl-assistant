package fi.karivanttinen.fplassistant.squad;

import java.time.LocalDate;
import java.util.List;

import fi.karivanttinen.fplassistant.testsupport.TapasSeed;
import fi.karivanttinen.fplassistant.tools.SeasonResolver;
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
 * {@link CandidatePool} when the season being played has begun but holds too little data to
 * judge anyone on — the state every season spends its first weeks in, and the state
 * {@link CandidatePoolIntegrationTest} never reaches because it never seeds a season more recent
 * than {@link TapasSeed#POPULATED_SEASON_ID}.
 *
 * <p>Follows {@code PickToolsCrossSeasonTest}'s precedent exactly: a handful of rows land in
 * {@link TapasSeed#CURRENT_SEASON_ID} so {@link SeasonResolver} prefers it, and
 * {@code player_fixture_stats} is left empty there so {@code CandidatePool} has to bridge back to
 * {@link TapasSeed#POPULATED_SEASON_ID} by name.
 */
@SpringBootTest
@ActiveProfiles("test")
class CandidatePoolCrossSeasonTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 27);
    private static final int CURRENT = TapasSeed.CURRENT_SEASON_ID;
    private static final int GAMEWEEK = 111;
    private static final int TEAM = 9001;

    /** This season's element id for a player who shares tapas' Okafor's {@code web_name}. */
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
     * it over {@link TapasSeed#POPULATED_SEASON_ID}, so daily-price rows must be seeded under
     * its own code, not a hardcoded one. */
    String season;

    @BeforeEach
    void seed() {
        TapasSeed.ensure(jdbc);
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
     * blanket delete by {@code season_id} (tapas tables) or by {@code season} code (daily price)
     * would risk wiping another class's rows — the same reasoning
     * {@code PickToolsCrossSeasonTest} documents for the tapas side, extended to the daily-price
     * table because this class is the first to key it to the resolver's live season code too.
     */
    @AfterEach
    void cleanUp() {
        deleteTestRows();
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
                    (9001, 9027, 9001, 'Ashfield Rovers', 'ASH', 4)
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
     * with tapas' Okafor's actual minutes and metrics attached through the name bridge.
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
     * would inherit tapas' single Okafor's numbers through the bridge — the exact failure the
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
        return pool.load(capture, pool.metricWindow(resolved))
                .stream()
                .filter(c -> c.elementId() == BRIDGED || c.elementId() == AMBIGUOUS)
                .toList();
    }
}
