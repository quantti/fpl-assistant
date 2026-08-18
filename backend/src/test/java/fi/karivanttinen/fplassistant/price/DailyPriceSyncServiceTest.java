package fi.karivanttinen.fplassistant.price;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parsing tests against a real captured {@code bootstrap-static} response
 * ({@code src/test/resources/fpl-bootstrap-fixture.json}, trimmed to three players and the
 * full teams block on 2026-08-18).
 *
 * <p>A fixture rather than a live call, for the reason Phase 3 captured a football-data
 * fixture: a test that hits FPL is a test that fails when FPL is down or rate-limits, and it
 * would silently start passing against changed field names without anyone noticing the change.
 */
class DailyPriceSyncServiceTest {

    private static Map<String, Object> fixture() throws Exception {
        try (InputStream in = DailyPriceSyncServiceTest.class
                .getResourceAsStream("/fpl-bootstrap-fixture.json")) {
            assertThat(in).as("captured bootstrap fixture must be on the test classpath").isNotNull();
            return new ObjectMapper().readValue(in, Map.class);
        }
    }

    @Test
    void mapsEveryElementToARow() throws Exception {
        List<Object[]> rows = DailyPriceSyncService.toRows(
                fixture(), LocalDate.of(2026, 8, 18), "2026-27");

        assertThat(rows).hasSize(3);
    }

    @Test
    void carriesPriceIdentityAndTeamFromTheResponseItself() throws Exception {
        List<Object[]> rows = DailyPriceSyncService.toRows(
                fixture(), LocalDate.of(2026, 8, 18), "2026-27");

        Object[] raya = rows.getFirst();
        assertThat(raya[0]).isEqualTo("2026-27");
        assertThat(raya[1]).isEqualTo(java.sql.Date.valueOf(LocalDate.of(2026, 8, 18)));
        assertThat(raya[2]).isEqualTo(1);              // element id
        assertThat(raya[3]).isEqualTo("Raya");         // web_name
        assertThat(raya[4]).isEqualTo("David Raya Martín");
        assertThat(raya[6]).isEqualTo("ARS");          // team short, resolved from teams block
        assertThat(raya[7]).isEqualTo("Arsenal");      // full club name — users ask by this
        assertThat(raya[9]).isEqualTo(60);             // now_cost, x10 per project convention
    }

    @Test
    void parsesSelectedByPercentWhichFplSendsAsAString() throws Exception {
        List<Object[]> rows = DailyPriceSyncService.toRows(
                fixture(), LocalDate.of(2026, 8, 18), "2026-27");

        // Guards the one field whose JSON type is not what its name suggests.
        assertThat(rows.getFirst()[12]).isEqualTo(new java.math.BigDecimal("34.1"));
    }

    @Test
    void skipsAnElementWithNoPriceRatherThanWritingANullRow() {
        Map<String, Object> body = Map.of(
                "teams", List.of(Map.of("id", 1, "short_name", "ARS")),
                "elements", List.of(
                        Map.of("id", 1, "web_name", "Real", "now_cost", 60, "team", 1),
                        Map.of("id", 2, "web_name", "NoPrice", "team", 1)));

        List<Object[]> rows = DailyPriceSyncService.toRows(body, LocalDate.of(2026, 8, 18), "2026-27");

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()[3]).isEqualTo("Real");
    }

    @Test
    void returnsNoRowsWhenTheResponseHasNoElements() {
        assertThat(DailyPriceSyncService.toRows(Map.of(), LocalDate.of(2026, 8, 18), "2026-27"))
                .isEmpty();
    }

    @Test
    void derivesTheSeasonCodeFromTheUkDate() {
        // A season opens in August and runs to May, so July already belongs to the next one.
        assertThat(DailyPriceSyncService.seasonCode(LocalDate.of(2026, 8, 21))).isEqualTo("2026-27");
        assertThat(DailyPriceSyncService.seasonCode(LocalDate.of(2026, 7, 1))).isEqualTo("2026-27");
        assertThat(DailyPriceSyncService.seasonCode(LocalDate.of(2027, 5, 24))).isEqualTo("2026-27");
        assertThat(DailyPriceSyncService.seasonCode(LocalDate.of(2027, 8, 1))).isEqualTo("2027-28");
        // Rolls the two-digit half correctly across a century boundary.
        assertThat(DailyPriceSyncService.seasonCode(LocalDate.of(2099, 9, 1))).isEqualTo("2099-00");
    }

    @Test
    void refusesAResponseThatStillCarriesLastSeasonsFixtures() {
        // The early-July window: the calendar already says "new season", but FPL has not
        // reset bootstrap-static, so every event deadline is weeks past. Writing that
        // response would label last season's squads with the new season code.
        Map<String, Object> body = Map.of("events", List.of(
                Map.of("deadline_time", "2026-05-24T10:00:00Z"),
                Map.of("deadline_time", "2026-05-31T10:00:00Z")));

        assertThat(DailyPriceSyncService.responseBelongsToAFinishedSeason(
                body, LocalDate.of(2026, 7, 20))).isTrue();
    }

    @Test
    void acceptsAResponseFromTheSeasonAboutToStart() {
        Map<String, Object> body = Map.of("events", List.of(
                Map.of("deadline_time", "2026-05-31T10:00:00Z"),
                Map.of("deadline_time", "2026-08-14T10:00:00Z")));

        assertThat(DailyPriceSyncService.responseBelongsToAFinishedSeason(
                body, LocalDate.of(2026, 7, 10))).isFalse();
    }

    @Test
    void acceptsASyncOfASeasonThatJustFinishedWhileItIsStillCurrent() {
        // Mid-June: the season that ended in May is still "the current season" by the
        // calendar rule, and capturing its rows under its own label is correct — the grace
        // window must not refuse it.
        Map<String, Object> body = Map.of("events", List.of(
                Map.of("deadline_time", "2026-05-31T10:00:00Z")));

        assertThat(DailyPriceSyncService.responseBelongsToAFinishedSeason(
                body, LocalDate.of(2026, 6, 20))).isFalse();
    }

    @Test
    void aBodyWithNoEventsBlockIsPassedThrough() {
        // The trimmed test fixture carries no events; a response that cannot be judged is
        // not refused on that account.
        assertThat(DailyPriceSyncService.responseBelongsToAFinishedSeason(
                Map.of("elements", List.of()), LocalDate.of(2026, 7, 10))).isFalse();
    }
}
