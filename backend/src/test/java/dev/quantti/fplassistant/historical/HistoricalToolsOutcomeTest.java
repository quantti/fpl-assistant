package dev.quantti.fplassistant.historical;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure unit tests for the W/D/L outcome logic — no Spring context, no database. */
class HistoricalToolsOutcomeTest {

    @Test
    void moreGoalsForIsAWin() {
        assertThat(HistoricalTools.outcome(3, 1)).isEqualTo("W");
    }

    @Test
    void fewerGoalsForIsALoss() {
        assertThat(HistoricalTools.outcome(0, 2)).isEqualTo("L");
    }

    @Test
    void equalGoalsIsADraw() {
        assertThat(HistoricalTools.outcome(1, 1)).isEqualTo("D");
        assertThat(HistoricalTools.outcome(0, 0)).isEqualTo("D");
    }
}
