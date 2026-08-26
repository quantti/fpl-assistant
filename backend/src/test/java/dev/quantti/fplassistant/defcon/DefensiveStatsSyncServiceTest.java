package dev.quantti.fplassistant.defcon;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the mapping, against a body shaped like a real {@code element-summary}
 * response (captured from the live endpoint on 2026-08-26). No network, no database.
 */
class DefensiveStatsSyncServiceTest {

    private static Map<String, Object> body(Object... historyRows) {
        return Map.of("history", List.of(historyRows));
    }

    private static Map<String, Object> match(int fixture, Integer defcon, Integer cbi,
                                             Integer recoveries, Integer tackles) {
        return Map.of("element", 1, "fixture", fixture, "round", 1, "minutes", 90,
                "defensive_contribution", defcon,
                "clearances_blocks_interceptions", cbi,
                "recoveries", recoveries,
                "tackles", tackles);
    }

    @Test
    void everyMatchBecomesOneUpdateKeyedByFixturePlayerAndSeason() {
        List<Object[]> updates = DefensiveStatsSyncService.toUpdates(
                body(match(11, 2, 6, 9, 4), match(12, 0, 1, 8, 0)), 42, 7);

        assertThat(updates).hasSize(2);
        // defcon, cbi, recoveries, tackles, fixture, player, season, minutes
        assertThat(updates.getFirst()).containsExactly(2, 6, 9, 4, 11, 42, 7, 90);
        assertThat(updates.get(1)).containsExactly(0, 1, 8, 0, 12, 42, 7, 90);
    }

    /**
     * Zero is a real reading — a striker who did no defensive work — and must be written as 0,
     * not skipped. The column is nullable precisely so that "nothing happened" and "never
     * fetched" stay distinguishable, and collapsing them here would defeat that.
     */
    @Test
    void aZeroIsWrittenRatherThanTreatedAsMissing() {
        List<Object[]> updates = DefensiveStatsSyncService.toUpdates(
                body(match(11, 0, 0, 0, 0)), 42, 7);

        assertThat(updates.getFirst()).startsWith(0, 0, 0, 0);
    }

    /** A row FPL sends without the fields at all leaves them null, not zero. */
    @Test
    void absentFieldsStayNullSoTheyAreNotMistakenForNoActions() {
        Map<String, Object> withoutDefcon =
                Map.of("element", 1, "fixture", 11, "round", 1, "minutes", 90);

        List<Object[]> updates =
                DefensiveStatsSyncService.toUpdates(body(withoutDefcon), 42, 7);

        assertThat(updates.getFirst()).startsWith(null, null, null, null);
    }

    /**
     * Minutes travel into the WHERE clause. FPL's history carries whatever season it is serving,
     * which need not be the one the database flags current, and element ids are reused across
     * seasons — so a colliding key must not be allowed to overwrite another player's match.
     */
    @Test
    void minutesAreCarriedLastSoTheyCanGuardTheUpdate() {
        Object[] update = DefensiveStatsSyncService
                .toUpdates(body(match(11, 2, 6, 9, 4)), 42, 7).getFirst();

        assertThat(update[update.length - 1]).isEqualTo(90);
    }

    /** No minutes means no guard, so there is nothing safe to write. */
    @Test
    void aRowWithoutMinutesIsSkipped() {
        Map<String, Object> noMinutes = Map.of("element", 1, "fixture", 11, "round", 1,
                "defensive_contribution", 2);

        assertThat(DefensiveStatsSyncService.toUpdates(body(noMinutes), 42, 7)).isEmpty();
    }

    /** No fixture id means no key to update on; the row is skipped rather than guessed at. */
    @Test
    void aRowWithNoFixtureIsSkipped() {
        Map<String, Object> noFixture = Map.of("element", 1, "round", 1, "minutes", 90);

        assertThat(DefensiveStatsSyncService.toUpdates(body(noFixture), 42, 7)).isEmpty();
    }

    @Test
    void aBodyWithNoHistoryYieldsNothing() {
        assertThat(DefensiveStatsSyncService.toUpdates(Map.of(), 42, 7)).isEmpty();
        assertThat(DefensiveStatsSyncService.toUpdates(Map.of("history", List.of()), 42, 7))
                .isEmpty();
    }
}
