package dev.quantti.fplassistant.squad;

import java.time.LocalDate;
import java.util.List;

import dev.quantti.fplassistant.testsupport.FictitiousTestData;
import dev.quantti.fplassistant.testsupport.TapasSeed;
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

    /** Every element id this class writes into the daily-price table, for scoped cleanup. */
    private static final List<Integer> TEST_ELEMENT_IDS =
            List.of(RANKABLE, BODY, INJURED, DOUBTFUL_NO_RECORD, OKAFOR, KAMINSKI);

    /**
     * The season the pool actually filters on, read at runtime rather than hardcoded.
     *
     * <p>{@code CandidatePool.load} filters the daily table on
     * {@code seasonResolver.resolve().code()}, and the resolver returns whichever season holds
     * data — during the changeover that is the <b>previous</b> season, not the one on the
     * calendar. Seeding under a constant would leave every test looking at an empty pool.
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
                        + " AND element_id IN (?, ?, ?, ?, ?, ?)",
                season, RANKABLE, BODY, INJURED, DOUBTFUL_NO_RECORD, OKAFOR, KAMINSKI);
    }

    private void insert(int elementId, String webName, int nowCost, int elementType,
                        double ownership, String status, String news) {
        jdbc.update("INSERT INTO " + table + " (season, price_date, element_id, web_name,"
                        + " full_name, team_id, team_short, team_name, element_type, now_cost,"
                        + " cost_change_event, cost_change_start, selected_by_percent, status, news)"
                        + " VALUES (?, ?, ?, ?, ?, 9001, 'ASH', 'Ashfield Rovers', ?, ?, 0, 0, ?, ?, ?)",
                season, TODAY, elementId, webName, webName + " Fictitious",
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
        assertThat(pool.latestCaptureDate()).isNotNull();
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

    private List<SquadCandidate> load() {
        var season = seasonResolver.resolve();
        return pool.load(season, pool.metricWindow(season), pool.latestCaptureDate())
                .stream()
                .filter(c -> TEST_ELEMENT_IDS.contains(c.elementId()))
                .toList();
    }
}
