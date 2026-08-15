package fi.karivanttinen.fplassistant.tools;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure unit tests for the price-history rendering and the descriptive trend line. */
class PriceToolsFormatTest {

    private static Map<String, Object> row(int gameweek, int price, String source) {
        Map<String, Object> r = new HashMap<>();
        r.put("gameweek", gameweek);
        r.put("price", price);
        r.put("source", source);
        return r;
    }

    @Test
    void describesARiseBetweenTheOldestGameweekAndTheLiveSnapshot() {
        String out = PriceTools.formatHistory("M.Salah", List.of(
                row(0, 145, "snapshot"), row(3, 143, "vaastav"), row(2, 141, "vaastav"),
                row(1, 140, "vaastav")));

        assertThat(out).contains("live: £14.5m (snapshot)");
        assertThat(out).contains("GW1: £14.0m (vaastav)");
        assertThat(out).contains("Trend: price has risen by £0.5m between GW1 and the live snapshot");
        assertThat(out).contains("descriptive, not a prediction");
    }

    @Test
    void describesAFall() {
        String out = PriceTools.formatHistory("Havertz", List.of(
                row(0, 78, "snapshot"), row(1, 80, "vaastav")));

        assertThat(out).contains("Trend: price has fallen by £0.2m between GW1 and the live snapshot");
    }

    @Test
    void aSinglePricePointIsNotDescribedAsATrend() {
        // The normal state between the season's first sync and the first played gameweek: the
        // snapshot exists and vaastav has published nothing. Comparing that row to itself used
        // to render "held steady between live snapshot and live snapshot".
        String out = PriceTools.formatHistory("M.Salah", List.of(row(0, 145, "snapshot")));

        assertThat(out).contains("only one price point is stored so far (£14.5m)");
        assertThat(out).contains("no trend");
        assertThat(out).doesNotContain("held steady");
    }

    @Test
    void anUnchangedPriceIsReportedAsSteady() {
        String out = PriceTools.formatHistory("Raya", List.of(
                row(0, 55, "snapshot"), row(2, 55, "vaastav"), row(1, 55, "vaastav")));

        assertThat(out).contains("held steady by £0.0m between GW1 and the live snapshot");
    }
}
