package fi.karivanttinen.fplassistant.tools;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** The fixture-evidence ramp and ability merge. */
class SeasonBlendTest {

    private static Map<String, Object> season(int fixtures, int mins, double xg90, double xa90,
                                              Double xgc, Double defconAvg, int defconMatches) {
        Map<String, Object> row = new HashMap<>();
        row.put("fixtures", fixtures);
        row.put("mins", mins);
        row.put("matches", fixtures);
        row.put("xg90", BigDecimal.valueOf(xg90));
        row.put("xa90", BigDecimal.valueOf(xa90));
        row.put("xgc_per_match", xgc == null ? null : BigDecimal.valueOf(xgc));
        row.put("defcon_avg", defconAvg == null ? null : BigDecimal.valueOf(defconAvg));
        row.put("defcon_matches", defconMatches);
        return row;
    }

    @Test
    void theRampUsesCompletedFixturesAndSaturatesAtEight() {
        assertThat(SeasonBlend.weight(0)).isEqualTo(0.0);
        assertThat(SeasonBlend.weight(1)).isEqualTo(0.10);
        assertThat(SeasonBlend.weight(2)).isEqualTo(0.30);
        assertThat(SeasonBlend.weight(3)).isEqualTo(0.60);
        assertThat(SeasonBlend.weight(4)).isEqualTo(0.80);
        assertThat(SeasonBlend.weight(5)).isEqualTo(0.90);
        assertThat(SeasonBlend.weight(7)).isEqualTo(0.90);
        assertThat(SeasonBlend.weight(8)).isEqualTo(1.0);
        assertThat(SeasonBlend.weight(30)).isEqualTo(1.0);
        assertThat(SeasonBlend.weight(-1)).isEqualTo(0.0);
    }

    @Test
    void twoCompletedFixturesWeighThisSeasonAtThirtyPercent() {
        Map<String, Object> current = season(2, 20, 0.60, 0.20, 1.0, 9.0, 2);
        Map<String, Object> previous = season(38, 2700, 0.20, 0.10, 1.5, 6.0, 30);

        SeasonBlend.Blended blended = SeasonBlend.blend(current, previous);

        assertThat(blended.weight()).isEqualTo(0.30);
        assertThat(blended.matchesThisSeason()).isEqualTo(2);
        assertThat(blended.hasPrevious()).isTrue();
        assertThat(ExpectedPoints.numeric(blended.metrics().get("xg90")))
                .isCloseTo(0.3 * 0.60 + 0.7 * 0.20, within(1e-9));
        assertThat(blended.metrics().get("mins")).isEqualTo(2720.0);
        assertThat(blended.metrics().get("fixtures")).isEqualTo(40.0);
    }

    @Test
    void lowOrZeroMinutesDoNotCreateAnAbilityFloor() {
        Map<String, Object> current = season(1, 0, 0.5, 0.2, null, null, 0);

        SeasonBlend.Blended blended = SeasonBlend.blend(current, null);

        assertThat(blended).isNotNull();
        assertThat(blended.weight()).isEqualTo(0.10);
        assertThat(ExpectedPoints.numeric(blended.metrics().get("xg90"))).isEqualTo(0.5);
        assertThat(SeasonBlend.blend(null, null)).isNull();
    }

    @Test
    void noCurrentFixturesUsesPreviousAbilityAlone() {
        Map<String, Object> current = season(0, 45, 2.0, 2.0, null, null, 0);
        Map<String, Object> previous = season(38, 2700, 0.20, 0.10, 1.5, 6.0, 30);

        SeasonBlend.Blended blended = SeasonBlend.blend(current, previous);

        assertThat(blended.weight()).isZero();
        assertThat(ExpectedPoints.numeric(blended.metrics().get("xg90"))).isCloseTo(0.20,
                within(1e-9));
    }

    @Test
    void missingWeightedAttackEvidenceRemainsUnknown() {
        Map<String, Object> current = season(2, 180, 0.60, 0.20, 1.0, 9.0, 2);
        current.remove("xg90");
        Map<String, Object> previous = season(38, 2700, 0.20, 0.10, 1.5, 6.0, 30);

        assertThat(SeasonBlend.blend(current, previous).metrics())
                .doesNotContainKey("xg90").containsKey("xa90");
    }

    @Test
    void sideSplitsAPrefixedQueryRow() {
        Map<String, Object> row = new HashMap<>();
        row.put("web_name", "Ferreira");
        row.put("cur_mins", 180);
        row.put("cur_fixtures", 2);
        row.put("cur_xg90", BigDecimal.valueOf(0.4));
        row.put("prev_mins", null);

        assertThat(SeasonBlend.side(row, SeasonBlend.CURRENT_PREFIX))
                .containsEntry("mins", 180).containsEntry("fixtures", 2)
                .containsEntry("xg90", BigDecimal.valueOf(0.4))
                .doesNotContainKey("web_name");
        assertThat(SeasonBlend.side(row, SeasonBlend.PREVIOUS_PREFIX)).isNull();
    }
}
