package fi.karivanttinen.fplassistant.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import fi.karivanttinen.fplassistant.sync.GameweekFinality;
import fi.karivanttinen.fplassistant.sync.GameweekFinality.ComponentCoverage;
import fi.karivanttinen.fplassistant.sync.GameweekFinality.FinalReceipt;
import fi.karivanttinen.fplassistant.sync.GameweekFinalityReader;
import fi.karivanttinen.fplassistant.sync.HistoryParser;
import fi.karivanttinen.fplassistant.sync.OwnedSyncStore.SourceRead;
import org.junit.jupiter.api.Test;

class GameweekFinalityFormatterTest {

    private static final Instant CHECKED_AT = Instant.parse("2026-09-20T15:00:00Z");

    @Test
    void scopesMixedProvisionalPendingAndFinalRowsAndKeepsUnsupportedDistinctFromZero() {
        Map<Integer, GameweekFinalityReader.Result> states = Map.of(
                1, new GameweekFinalityReader.Result(1, GameweekFinality.State.FINALIZED,
                        true, true, true, CHECKED_AT, receipt()),
                2, new GameweekFinalityReader.Result(2, GameweekFinality.State.FINAL_PENDING,
                        true, true, true, CHECKED_AT, null),
                3, new GameweekFinalityReader.Result(3, GameweekFinality.State.OPEN,
                        false, false, false, null, null),
                4, new GameweekFinalityReader.Result(4, GameweekFinality.State.OPEN,
                        false, true, false, null, null),
                5, new GameweekFinalityReader.Result(5, GameweekFinality.State.OPEN,
                        true, true, false, null, null));
        Map<Integer, PfsReadSource.IncrementalRows> rows = Map.of(
                1, new PfsReadSource.IncrementalRows(2, 0),
                2, new PfsReadSource.IncrementalRows(1, 1),
                3, new PfsReadSource.IncrementalRows(3, 0),
                4, new PfsReadSource.IncrementalRows(1, 0),
                5, new PfsReadSource.IncrementalRows(1, 0));

        String output = GameweekFinalityFormatter.summary("2026-27", rows, states, Map.of(
                1, Set.of(101, 102), 2, Set.of(201), 3, Set.of(301),
                4, Set.of(401), 5, Set.of(501)));

        assertThat(output).contains("metric rows in this returned fixture window")
                .contains("not a season-wide claim")
                .contains("GW1 fixtures 101, 102: FPL checked; final import completed at")
                .contains("GW2 fixtures 201: FPL checked; final import pending/incomplete (first observed checked at "
                        + CHECKED_AT + ")")
                .contains("GW3 fixtures 301: provisional incremental rows")
                .contains("GW4 fixtures 401: FPL data_checked=true, but the event is not finished")
                .doesNotContain("GW4 fixtures 401: FPL checked; final import pending/incomplete")
                .contains("GW5 fixtures 501: FPL marked the event checked/finished, but not all assigned fixtures are finished")
                .doesNotContain("GW5 fixtures 501: FPL checked; final import pending/incomplete")
                .contains("Incremental rows in the shown fixture window: 1.")
                .contains("1 other/non-incremental row(s) are outside this finality receipt")
                .contains("expected_goals 1/2 present")
                .contains("expected_assists 0/2 present")
                .contains("influence unsupported")
                .doesNotContain("influence 0/2 present");
    }

    @Test
    void emitsNoClaimForRowsOutsideTheRequestedFixtureWindow() {
        String output = GameweekFinalityFormatter.summary("2026-27",
                Map.of(7, new PfsReadSource.IncrementalRows(0, 2)), Map.of(), Map.of(7, Set.of(701)));

        assertThat(output).isEmpty();
    }

    private static FinalReceipt receipt() {
        Map<HistoryParser.Component, ComponentCoverage> coverage =
                new EnumMap<>(HistoryParser.Component.class);
        for (HistoryParser.Component component : HistoryParser.Component.values()) {
            coverage.put(component, new ComponentCoverage(2, 0, true));
        }
        coverage.put(HistoryParser.Component.EXPECTED_GOALS, new ComponentCoverage(2, 1, true));
        coverage.put(HistoryParser.Component.INFLUENCE, new ComponentCoverage(0, 0, false));
        return new FinalReceipt("a".repeat(64), "b".repeat(64), 4,
                CHECKED_AT.minusSeconds(30), 2, CHECKED_AT.plusSeconds(4),
                new SourceRead(CHECKED_AT.minusSeconds(20), CHECKED_AT), true, coverage);
    }
}
