package dev.quantti.fplassistant.tools;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Merges a player's ability metrics from the season being played with the previous one.
 *
 * <p>The current-season weight is based on the player's own completed fixtures, not league
 * gameweeks or minutes. Ability rates blend; evidence counts add. This does not replace
 * {@link MinutesModel}'s availability and complete-current-evidence checks.
 */
public final class SeasonBlend {

    /** Weight on the season being played, indexed by completed current-season fixtures. */
    public static final double[] WEIGHT_BY_MATCHES =
            {0, 0.10, 0.30, 0.60, 0.80, 0.90, 0.90, 0.90, 1.0};

    /** Fixtures at and beyond which the season being played carries everything. */
    public static final int FULL_WEIGHT_MATCHES = WEIGHT_BY_MATCHES.length - 1;

    public static final String CURRENT_PREFIX = "cur_";
    public static final String PREVIOUS_PREFIX = "prev_";

    /** Per-90 and per-match ability figures: weighted. */
    static final List<String> RATE_KEYS = List.of("xg90", "xa90", "defcon_avg");

    /** Evidence counts are added; rates are never reconstructed from these cross-season sums. */
    static final List<String> COUNT_KEYS = List.of("mins", "fixtures", "defcon_matches");

    /** The ramp's input, read from the current side only. */
    static final String MATCHES_THIS_SEASON = "fixtures";

    public record Blended(Map<String, Object> metrics, double weight, int matchesThisSeason,
                          boolean hasPrevious) {
    }

    private SeasonBlend() {
    }

    public static double weight(int completedFixturesThisSeason) {
        int n = Math.max(0, Math.min(completedFixturesThisSeason, FULL_WEIGHT_MATCHES));
        return WEIGHT_BY_MATCHES[n];
    }

    /**
     * @param current this season's aggregate for the player, or null when he has no row in it
     * @param previous last season's aggregate, or null when he has none there
     * @return the merged ability record, or null when neither season has an ability record
     */
    public static Blended blend(Map<String, Object> current, Map<String, Object> previous) {
        if (current == null && previous == null) {
            return null;
        }
        int fixtures = current == null ? 0
                : (int) ExpectedPoints.numeric(current.get(MATCHES_THIS_SEASON));
        double tableWeight = weight(fixtures);
        double currentWeight = previous == null ? 1.0 : tableWeight;

        Map<String, Object> out = new HashMap<>();
        for (String key : RATE_KEYS) {
            Object value = rate(current, previous, key, currentWeight);
            if (value != null) {
                out.put(key, value);
            }
        }
        for (String key : COUNT_KEYS) {
            Object value = count(current, previous, key);
            if (value != null) {
                out.put(key, value);
            }
        }
        out.put("blend_weight", tableWeight);
        out.put("blend_matches", fixtures);
        out.put("blend_has_previous", previous != null);
        return new Blended(out, tableWeight, fixtures, previous != null);
    }

    /**
     * One side of a query row that carries both seasons under {@code cur_}/{@code prev_}
     * prefixes, or null when that side has no {@code mins} — the query's LEFT JOINs leave every
     * column of an absent season null.
     */
    public static Map<String, Object> side(Map<String, Object> row, String prefix) {
        if (row.get(prefix + "mins") == null) {
            return null;
        }
        Map<String, Object> side = new HashMap<>();
        for (String key : RATE_KEYS) {
            side.put(key, row.get(prefix + key));
        }
        for (String key : COUNT_KEYS) {
            side.put(key, row.get(prefix + key));
        }
        side.put(MATCHES_THIS_SEASON, row.get(prefix + MATCHES_THIS_SEASON));
        return side;
    }

    private static Object rate(Map<String, Object> current, Map<String, Object> previous,
                               String key, double currentWeight) {
        Object a = current == null ? null : current.get(key);
        Object b = previous == null ? null : previous.get(key);
        if (a instanceof Number x && b instanceof Number y) {
            return currentWeight * x.doubleValue() + (1 - currentWeight) * y.doubleValue();
        }
        if ((key.equals("xg90") || key.equals("xa90")) && current != null && previous != null
                && (currentWeight > 0 && !(a instanceof Number)
                        || currentWeight < 1 && !(b instanceof Number))) {
            return null;
        }
        return a instanceof Number ? a : b;
    }

    private static Object count(Map<String, Object> current, Map<String, Object> previous,
                                String key) {
        Object a = current == null ? null : current.get(key);
        Object b = previous == null ? null : previous.get(key);
        if (a == null && b == null) {
            return null;
        }
        return ExpectedPoints.numeric(a) + ExpectedPoints.numeric(b);
    }
}
