package fi.karivanttinen.fplassistant.rag;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for the expiry logic, with fixed dates so they never rot as real time
 * passes (the integration test cannot pin those dates this way — it uses a fixed Clock).
 */
class StrategyToolsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 14);

    @Test
    void absentValidUntilNeverExpires() {
        assertThat(StrategyTools.isCurrentlyValid(Map.of(), TODAY)).isTrue();
    }

    @Test
    void blankValidUntilNeverExpires() {
        assertThat(StrategyTools.isCurrentlyValid(Map.of("valid_until", ""), TODAY)).isTrue();
    }

    @Test
    void expiryIsInclusiveOnTheDayItself() {
        assertThat(StrategyTools.isCurrentlyValid(Map.of("valid_until", "2026-08-14"), TODAY)).isTrue();
    }

    @Test
    void pastValidUntilExpires() {
        assertThat(StrategyTools.isCurrentlyValid(Map.of("valid_until", "2026-08-10"), TODAY)).isFalse();
        assertThat(StrategyTools.isCurrentlyValid(Map.of("valid_until", "2026-08-13"), TODAY)).isFalse();
    }

    @Test
    void undatableValidUntilIsExcludedRatherThanCrashing() {
        // Ingestion rejects malformed dates, but a hand-edited row must not kill the stream.
        assertThat(StrategyTools.isCurrentlyValid(Map.of("valid_until", "not-a-date"), TODAY)).isFalse();
    }
}
