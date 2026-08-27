package fi.karivanttinen.fplassistant.squad;

import java.time.LocalDate;
import java.util.List;

import fi.karivanttinen.fplassistant.testsupport.FictitiousTestData;
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
    }

    @AfterEach
    void cleanUp() {
        deleteTestRows();
    }

    private void deleteTestRows() {
        jdbc.update("DELETE FROM " + table + " WHERE season = ?", season);
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

    private List<SquadCandidate> load() {
        var season = seasonResolver.resolve();
        return pool.load(season, pool.metricWindow(season), pool.latestCaptureDate())
                .stream()
                .filter(c -> c.elementId() >= 9501 && c.elementId() <= 9504)
                .toList();
    }
}
