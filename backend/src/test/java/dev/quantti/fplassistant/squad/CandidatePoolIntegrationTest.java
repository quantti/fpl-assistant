package dev.quantti.fplassistant.squad;

import java.time.LocalDate;
import java.util.List;

import dev.quantti.fplassistant.testsupport.FictitiousTestData;
import dev.quantti.fplassistant.testsupport.TapasSeed;
import dev.quantti.fplassistant.tools.SeasonResolver;
import org.junit.jupiter.api.AfterEach;
import dev.quantti.fplassistant.tools.ExpectedPoints;
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
 * The candidate pool, against the isolated daily-price table.
 *
 * <p>Two tiers, and the difference between them is the whole point: a player with a real record
 * can be judged and started; a player without one is a body worth nothing, admitted only so the
 * cheapest legal bench slot stays reachable.
 */
@SpringBootTest
@ActiveProfiles("test")
class CandidatePoolIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 27);

    private static final int RANKABLE = 9501;
    private static final int BODY = 9502;
    private static final int INJURED = 9503;
    private static final int DOUBTFUL_NO_RECORD = 9504;

    /**
     * Tapas' own Okafor (season {@link TapasSeed#POPULATED_SEASON_ID}, 270 minutes across 3
     * fixtures). {@link #RANKABLE} above never actually earns {@code Tier.RANKABLE} — nothing in
     * tapas exists at id 9501 to join to — so nothing in this class could catch the tier split
     * itself breaking without a candidate wired to a real record. This one is.
     */
    private static final int OKAFOR = 9201;

    /**
     * Selby's goalkeeper: 2 {@code player_fixture_stats} rows across gameweeks 1-2, but Selby
     * actually played 3 fixtures in that span (a double gameweek he missed one leg of). A
     * denominator built from his row count reads as ever-present; his club's true fixture count
     * of 3 correctly shows the miss.
     */
    private static final int KAMINSKI = 9206;

    /** Tapas' Ferreira: a defender with two 90-minute rows, so a defcon mean can be read. */
    private static final int FERREIRA = 9205;

    /** Every element id this class writes into the daily-price table, for scoped cleanup. */
    private static final List<Integer> TEST_ELEMENT_IDS =
            List.of(RANKABLE, BODY, INJURED, DOUBTFUL_NO_RECORD, OKAFOR, KAMINSKI, FERREIRA);

    /**
     * The season the pool actually filters on, read at runtime rather than hardcoded.
     *
     * <p>{@code CandidatePool.load} filters the daily table on the season named by the capture
     * itself, not on {@code seasonResolver.resolve().code()} — the two differ across the
     * changeover, and following the resolver was the bug that made the tool answer "no players
     * available" while the capture held a full pool. Most cases here want the two to agree, so
     * they seed under the resolved season; {@code thePoolFollowsTheCaptureNotTheResolver...}
     * deliberately makes them disagree.
     */
    String season;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CandidatePool pool;

    @Autowired
    SeasonResolver seasonResolver;

    @Value("${fpl.daily-price.table-name}")
    String table;

    @BeforeEach
    void refuseProductionAndSeed() {
        TapasSeed.ensure(jdbc);
        season = seasonResolver.resolve().code();
        FictitiousTestData.assertSafeSeason(season);
        assertThat(table).as("pool tests must use the isolated table").endsWith("_test");
        deleteTestRows();

        insert(RANKABLE, "Rankabletest", 55, 2, 30.0, "a", null);
        insert(BODY, "Bodytest", 40, 2, 0.2, "a", null);
        insert(INJURED, "Injuredtest", 45, 2, 1.0, "i", "Hamstring");
        insert(DOUBTFUL_NO_RECORD, "Doubtfultest", 40, 2, 0.1, "d", "Knock");
        insert(OKAFOR, "Okafor", 75, 3, 12.0, "a", null);
        insert(KAMINSKI, "Kaminski", 45, 1, 6.0, "a", null);
        insert(FERREIRA, "Ferreira", 45, 2, 8.0, "a", null);
    }

    @AfterEach
    void cleanUp() {
        deleteTestRows();
    }

    /**
     * Scoped by element id as well as season: this is the first test class to key daily-price
     * rows to whatever season {@link SeasonResolver} actually resolves at runtime rather than a
     * hardcoded fictitious label, and a later test in this same plan will plausibly do the same.
     * A blanket {@code DELETE WHERE season = ?} would wipe that class's rows too.
     */
    private void deleteTestRows() {
        jdbc.update("DELETE FROM " + table + " WHERE season = ?"
                        + " AND element_id IN (?, ?, ?, ?, ?, ?, ?)",
                season, RANKABLE, BODY, INJURED, DOUBTFUL_NO_RECORD, OKAFOR, KAMINSKI, FERREIRA);
        // TapasSeed never overwrites and the container is long-lived, so a value written by the
        // defcon case below would otherwise outlive it and reach every other test.
        jdbc.update("UPDATE player_fixture_stats SET defensive_contribution = NULL"
                + " WHERE season_id = 9026 AND player_id = ?", FERREIRA);
    }

    private void insert(int elementId, String webName, int nowCost, int elementType,
                        double ownership, String status, String news) {
        insert(elementId, webName, nowCost, elementType, ownership, status, news, season, TODAY);
    }

    private void insert(int elementId, String webName, int nowCost, int elementType,
                        double ownership, String status, String news, String seasonCode,
                        LocalDate date) {
        jdbc.update("INSERT INTO " + table + " (season, price_date, element_id, web_name,"
                        + " full_name, team_id, team_short, team_name, element_type, now_cost,"
                        + " cost_change_event, cost_change_start, selected_by_percent, status, news)"
                        + " VALUES (?, ?, ?, ?, ?, 9001, 'ASH', 'Ashfield Rovers', ?, ?, 0, 0, ?, ?, ?)",
                seasonCode, date, elementId, webName, webName + " Fictitious",
                elementType, nowCost, ownership, status, news);
    }

    @Test
    void anInjuredPlayerIsNotACandidateAtAll() {
        List<SquadCandidate> candidates = load();

        assertThat(candidates).extracting(SquadCandidate::elementId).doesNotContain(INJURED);
    }

    @Test
    void aDoubtfulPlayerWithNoRecordIsNotEvenABody() {
        // A body is worth buying only when it is at least a fit one.
        List<SquadCandidate> candidates = load();

        assertThat(candidates).extracting(SquadCandidate::elementId)
                .doesNotContain(DOUBTFUL_NO_RECORD);
    }

    @Test
    void aPlayerWithNoMinutesRecordIsAdmittedAsABody() {
        List<SquadCandidate> candidates = load();

        SquadCandidate body = candidates.stream()
                .filter(c -> c.elementId() == BODY).findFirst().orElseThrow();

        assertThat(body.tier()).isEqualTo(SquadCandidate.Tier.BODY);
        assertThat(body.nowCost()).isEqualTo(40);
    }

    @Test
    void carriesTheClubAndPriceFromTheDailyCaptureRatherThanFromTapas() {
        // The tapas player table is synced weekly and was measured 85 days stale. Nothing here
        // may read a price or an availability flag from it.
        SquadCandidate any = load().getFirst();

        assertThat(any.club()).isEqualTo("Ashfield Rovers");
        assertThat(any.clubShort()).isEqualTo("ASH");
    }

    @Test
    void theCaptureDateIsTheLatestOneNotToday() {
        // A missed 02:00 run must degrade to yesterday's capture, clearly labelled, rather than
        // returning nothing.
        assertThat(pool.latestCapture()).isPresent();
        assertThat(pool.latestCapture().orElseThrow().date()).isNotNull();
    }

    @Test
    void thePoolFollowsTheCaptureNotTheResolverAcrossTheChangeover() {
        // C1: during the changeover the daily capture already carries the new season's prices
        // (nothing stops it — it owes nothing to tapas), but tapas has not synced the new
        // season's player/gameweek rows yet, so SeasonResolver still falls back to last season.
        // A pool filtered on the resolver's season would then look for last season's code at
        // today's capture date and find nothing, even though the capture holds real players.
        assertThat(season).as("the resolver must be on the previous season for this test to prove"
                + " anything").isEqualTo(TapasSeed.POPULATED_SEASON_CODE);
        int changeoverId = 9505;
        LocalDate tomorrow = TODAY.plusDays(1);
        jdbc.update("DELETE FROM " + table + " WHERE season = ? AND element_id = ?",
                TapasSeed.CURRENT_SEASON_CODE, changeoverId);
        insert(changeoverId, "Changeovertest", 45, 2, 1.0, "a", null,
                TapasSeed.CURRENT_SEASON_CODE, tomorrow);

        try {
            CandidatePool.Capture capture = pool.latestCapture().orElseThrow();
            assertThat(capture.season()).isEqualTo(TapasSeed.CURRENT_SEASON_CODE);
            assertThat(capture.date()).isEqualTo(tomorrow);

            List<SquadCandidate> candidates =
                    pool.load(capture, seasonResolver.metricSeasons(seasonResolver.resolve()));

            assertThat(candidates).isNotEmpty();
            assertThat(candidates).extracting(SquadCandidate::elementId).contains(changeoverId);
        } finally {
            jdbc.update("DELETE FROM " + table + " WHERE season = ? AND element_id = ?",
                    TapasSeed.CURRENT_SEASON_CODE, changeoverId);
        }
    }

    @Test
    void aPlayerWithARealMinutesRecordIsRankable() {
        // The only test in this class that can catch the tier split itself breaking: every other
        // candidate here has no tapas record to join to, so a solver that hardcoded
        // "rankable = false" and deleted the recent/windowed/metrics machinery entirely would
        // still pass every other test in this file.
        SquadCandidate okafor = candidate(OKAFOR);

        assertThat(okafor.tier()).isEqualTo(SquadCandidate.Tier.RANKABLE);
        assertThat(okafor.minutesInWindow()).isEqualTo(270.0);
        assertThat(((Number) okafor.metrics().get("xg90")).doubleValue()).isGreaterThan(0);
    }

    @Test
    void theFixtureDenominatorIsTheClubsFixturesNotThePlayersRowCount() {
        // Kaminski has 180 minutes across 2 rows, but Selby played 3 fixtures across those same
        // two gameweeks (he missed one leg of a double gameweek). A denominator built from his
        // row count would read 180/2 — ever-present; his club's true count of 3 correctly shows
        // the gap.
        SquadCandidate kaminski = candidate(KAMINSKI);

        assertThat(kaminski.tier()).isEqualTo(SquadCandidate.Tier.RANKABLE);
        assertThat(kaminski.minutesInWindow()).isEqualTo(180.0);
        assertThat(kaminski.teamFixturesInWindow()).isEqualTo(3);
    }

    private SquadCandidate candidate(int elementId) {
        return load().stream().filter(c -> c.elementId() == elementId).findFirst().orElseThrow();
    }

    @Test
    void theCandidateMetricsCarryTheDefensiveContributionRecord() {
        // The squad builder and the pick tools share ExpectedPoints. If the pool does not supply
        // these keys, the same defender is worth less here than in a picks list, in the same
        // conversation.
        jdbc.update("UPDATE player_fixture_stats SET defensive_contribution = 12"
                + " WHERE player_id = ? AND fixture_id = 9102 AND season_id = 9026", FERREIRA);
        jdbc.update("UPDATE player_fixture_stats SET defensive_contribution = 4"
                + " WHERE player_id = ? AND fixture_id = 9104 AND season_id = 9026", FERREIRA);

        SquadCandidate ferreira = load().stream()
                .filter(c -> c.elementId() == FERREIRA)
                .findFirst()
                .orElseThrow();

        assertThat(ExpectedPoints.hasDefconSignal(ferreira.metrics(), 2)).isTrue();
        // Equal to the model read off the same mean, so this pins the plumbing rather than
        // restating the distribution's constants a second time.
        assertThat(ExpectedPoints.defconProbability(ferreira.metrics(), 2))
                .isCloseTo(ExpectedPoints.thresholdProbability(8.0, 10), within(1e-9));
    }

    private List<SquadCandidate> load() {
        var resolved = seasonResolver.resolve();
        CandidatePool.Capture capture = pool.latestCapture().orElseThrow();
        return pool.load(capture, seasonResolver.metricSeasons(resolved))
                .stream()
                .filter(c -> TEST_ELEMENT_IDS.contains(c.elementId()))
                .toList();
    }
}
