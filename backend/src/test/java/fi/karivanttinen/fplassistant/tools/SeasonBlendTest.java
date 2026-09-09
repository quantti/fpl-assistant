package fi.karivanttinen.fplassistant.tools;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The ramp and the merge, on their own. They live apart from the two tools that use them for the
 * reason {@link ExpectedPoints} does: a copy in each is a guarantee they diverge.
 */
class SeasonBlendTest {

    private static Map<String, Object> season(int mins, int matches60, double xg90, double xa90,
                                              Double xgc, Double defconAvg, int defconMatches) {
        Map<String, Object> row = new HashMap<>();
        row.put("mins", mins);
        row.put("matches", matches60);
        row.put("matches60", matches60);
        row.put("xg90", BigDecimal.valueOf(xg90));
        row.put("xa90", BigDecimal.valueOf(xa90));
        row.put("pts90", BigDecimal.valueOf(4.0));
        row.put("xgc_per_match", xgc == null ? null : BigDecimal.valueOf(xgc));
        row.put("defcon_avg", defconAvg == null ? null : BigDecimal.valueOf(defconAvg));
        row.put("defcon_matches", defconMatches);
        return row;
    }

    @Test
    void theRampIsTheOwnersTableAndSaturatesAtEight() {
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
    void twoMatchesThisSeasonWeighsThisSeasonAtThirtyPercent() {
        Map<String, Object> cur = season(180, 2, 0.60, 0.20, 1.0, 9.0, 2);
        Map<String, Object> prev = season(2700, 30, 0.20, 0.10, 1.5, 6.0, 30);

        SeasonBlend.Blended b = SeasonBlend.blend(cur, prev);

        assertThat(b.weight()).isEqualTo(0.30);
        assertThat(b.matchesThisSeason()).isEqualTo(2);
        assertThat(b.hasPrevious()).isTrue();
        assertThat(ExpectedPoints.numeric(b.metrics().get("xg90")))
                .isCloseTo(0.3 * 0.60 + 0.7 * 0.20, within(1e-9));
        assertThat(ExpectedPoints.numeric(b.metrics().get("xgc_per_match")))
                .isCloseTo(0.3 * 1.0 + 0.7 * 1.5, within(1e-9));
        assertThat(ExpectedPoints.numeric(b.metrics().get("defcon_avg")))
                .isCloseTo(0.3 * 9.0 + 0.7 * 6.0, within(1e-9));
        // Evidence counts add: the floors read the combined record.
        assertThat(b.metrics().get("mins")).isEqualTo(2880.0);
        assertThat(b.metrics().get("defcon_matches")).isEqualTo(32.0);
        assertThat(b.metrics().get("blend_weight")).isEqualTo(0.30);
        assertThat(b.metrics().get("blend_matches")).isEqualTo(2);
        assertThat(b.metrics().get("blend_has_previous")).isEqualTo(true);
    }

    @Test
    void noMatchesThisSeasonIsLastSeasonAlone() {
        Map<String, Object> cur = season(45, 0, 2.0, 2.0, null, null, 0);   // one cameo
        Map<String, Object> prev = season(2700, 30, 0.20, 0.10, 1.5, 6.0, 30);

        SeasonBlend.Blended b = SeasonBlend.blend(cur, prev);

        assertThat(b.weight()).isEqualTo(0.0);
        assertThat(ExpectedPoints.numeric(b.metrics().get("xg90"))).isCloseTo(0.20, within(1e-9));
        assertThat(ExpectedPoints.numeric(b.metrics().get("xgc_per_match")))
                .isCloseTo(1.5, within(1e-9));
    }

    @Test
    void aMissingCurrentSideIsLastSeasonAlone() {
        SeasonBlend.Blended b =
                SeasonBlend.blend(null, season(2700, 30, 0.20, 0.10, 1.5, 6.0, 30));

        assertThat(b.weight()).isEqualTo(0.0);
        assertThat(b.matchesThisSeason()).isZero();
        assertThat(ExpectedPoints.numeric(b.metrics().get("xg90"))).isCloseTo(0.20, within(1e-9));
    }

    @Test
    void noPreviousRecordIsThisSeasonAloneButStillReportsTheRampWeight() {
        // The owner's rule: with nothing to ramp from, this season carries everything once the
        // minutes floor is cleared — and the reply says so, which needs hasPrevious=false and the
        // table weight the ramp would have given.
        SeasonBlend.Blended b = SeasonBlend.blend(season(180, 2, 0.60, 0.20, 1.0, 9.0, 2), null);

        assertThat(b.hasPrevious()).isFalse();
        assertThat(b.weight()).isEqualTo(0.30);
        assertThat(ExpectedPoints.numeric(b.metrics().get("xg90"))).isCloseTo(0.60, within(1e-9));
    }

    @Test
    void aKeyPresentOnOneSideOnlyIsTakenFromThatSide() {
        // xGC needs a 60-minute match; this season's minutes were all in cameos.
        Map<String, Object> cur = season(100, 0, 0.60, 0.20, null, null, 0);
        Map<String, Object> prev = season(2700, 30, 0.20, 0.10, 1.5, 6.0, 30);

        SeasonBlend.Blended b = SeasonBlend.blend(cur, prev);

        assertThat(ExpectedPoints.numeric(b.metrics().get("xgc_per_match")))
                .isCloseTo(1.5, within(1e-9));
    }

    @Test
    void theMinutesFloorIsOnTheCombinedRecord() {
        assertThat(SeasonBlend.blend(season(100, 1, 0.5, 0.5, 1.0, 8.0, 1), null)).isNull();
        assertThat(SeasonBlend.blend(season(100, 1, 0.5, 0.5, 1.0, 8.0, 1),
                season(100, 1, 0.5, 0.5, 1.0, 8.0, 1))).isNotNull();
        assertThat(SeasonBlend.blend(null, null)).isNull();
    }

    @Test
    void exposureRatesBlendByTheRampRatherThanByUnequalFixtureCounts() {
        Map<String, Object> cur = season(180, 2, 0.60, 0.20, 1.0, 9.0, 2);
        Map<String, Object> prev = season(2700, 30, 0.20, 0.10, 1.5, 6.0, 30);
        SeasonBlend.Exposure current = exposure(2, 2, 2, 180, 8);
        SeasonBlend.Exposure previous = exposure(38, 30, 28, 2500, 152);

        SeasonBlend.Blended blended = SeasonBlend.blend(
                cur, prev, current, previous, true);

        assertThat(blended.exposure().appearanceRate())
                .isCloseTo(0.3 * 1.0 + 0.7 * (30.0 / 38), within(1e-9));
        assertThat(blended.exposure().sixtyRate())
                .isCloseTo(0.3 * 1.0 + 0.7 * (28.0 / 38), within(1e-9));
        assertThat(blended.exposure().fixtures()).isEqualTo(40);
        assertThat(blended.metrics().get("points_per_fixture"))
                .isEqualTo(blended.exposure().pointsPerFixture());
    }

    @Test
    void aLiveProjectionNeedsCurrentExposureButDoesNotNeedPreviousExposure() {
        Map<String, Object> cur = season(180, 0, 0.60, 0.20, 1.0, 9.0, 2);
        Map<String, Object> prev = season(2700, 30, 0.20, 0.10, 1.5, 6.0, 30);
        SeasonBlend.Exposure current = exposure(3, 3, 3, 180, 9);
        SeasonBlend.Exposure previous = exposure(38, 38, 38, 3420, 190);

        assertThat(SeasonBlend.blend(cur, prev, null, previous, true).exposure()).isNull();
        assertThat(SeasonBlend.blend(cur, prev, current, null, true).exposure())
                .isEqualTo(current);
        assertThat(SeasonBlend.blend(cur, prev, null, previous, false).exposure())
                .isEqualTo(previous);
    }

    @Test
    void exposureBundlesEnforceTheOfficialMinuteBounds() {
        assertThat(exposure(5, 4, 3, 250, 20).sixtyRate()).isLessThanOrEqualTo(
                exposure(5, 4, 3, 250, 20).appearanceRate());
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new SeasonBlend.Exposure(2, 1, 2, 120, 0.5, 1.0,
                        -0.5, 60, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sideSplitsAPrefixedQueryRow() {
        Map<String, Object> row = new HashMap<>();
        row.put("web_name", "Ferreira");
        row.put("cur_mins", 180);
        row.put("cur_xg90", BigDecimal.valueOf(0.4));
        row.put("prev_mins", null);
        row.put("prev_xg90", null);

        Map<String, Object> cur = SeasonBlend.side(row, SeasonBlend.CURRENT_PREFIX);

        assertThat(cur).containsEntry("mins", 180)
                .containsEntry("xg90", BigDecimal.valueOf(0.4))
                .doesNotContainKey("web_name");
        assertThat(SeasonBlend.side(row, SeasonBlend.PREVIOUS_PREFIX)).isNull();
    }

    private static SeasonBlend.Exposure exposure(int fixtures, int appearances, int sixty,
                                                  int minutes, double points) {
        double n = fixtures;
        double appearanceRate = appearances / n;
        double sixtyRate = sixty / n;
        return new SeasonBlend.Exposure(fixtures, appearances, sixty, minutes,
                appearanceRate, sixtyRate, appearanceRate - sixtyRate,
                minutes / n, points / n);
    }
}
