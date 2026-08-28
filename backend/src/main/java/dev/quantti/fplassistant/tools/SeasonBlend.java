package dev.quantti.fplassistant.tools;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Merges a player's metrics from the season being played with his metrics from the previous one,
 * weighted by how much of this season he has actually played.
 *
 * <p><b>This replaces a switch.</b> Until 2026-08-28 every ranking read <em>one</em> season: the
 * previous one until the season being played had five completed gameweeks, then that season
 * alone. Binary and league-wide, so in GW4 a player's own four matches counted for nothing, in
 * GW5 last season counted for nothing, and a player who had missed the opening rounds was judged
 * from GW5 on however little he had — the switch counted the league's gameweeks, not his.
 *
 * <p>The ramp is the owner's (KAR-38), keyed on the <b>player's own</b> qualifying matches — 60
 * minutes or more, the same bar the clean-sheet and defensive-contribution terms use.
 *
 * <p><b>Rates blend; evidence counts add.</b> {@code xg90} at two matches is 30% this season and
 * 70% last, but {@code mins} is both seasons' minutes together, because the floors that read it
 * ({@link ExpectedPoints#MIN_WINDOW_MINUTES}, {@link ExpectedPoints#MIN_DEFCON_MATCHES}) ask how
 * much evidence exists, and evidence from either season counts.
 *
 * <p>A player with no previous record is ranked on this season alone once the floor is cleared,
 * and {@link Blended#hasPrevious()} is false so the reply can say so. His {@link Blended#weight()}
 * is still the table's value: the honest description is "30%, and here is why it could not be
 * applied", not a silent 100%.
 *
 * <p><b>What the measurement said.</b> Predicting a player's later {@code xG/90} from his first
 * few matches across 2024-25 → 2025-26, the ramp beats both single-season baselines at two
 * matches (+2.4%) and loses to last-season-only from three onward, by 25% at eight — a full
 * previous season is simply a bigger sample than eight matches. Split by whether the player
 * changed club, the loss is confined to players who stayed: for a mover the ramp is neutral to
 * better, which is the case it exists for. Recorded in the plan's Task 0 outcome and in
 * {@code docs/STATUS.md}; the table is the owner's decision and is not tuned here.
 */
public final class SeasonBlend {

    /** Weight on the season being played, indexed by the player's qualifying matches in it. */
    public static final double[] WEIGHT_BY_MATCHES =
            {0, 0.10, 0.30, 0.60, 0.80, 0.90, 0.90, 0.90, 1.0};

    /** Matches at and beyond which the season being played carries everything. */
    public static final int FULL_WEIGHT_MATCHES = WEIGHT_BY_MATCHES.length - 1;

    public static final String CURRENT_PREFIX = "cur_";
    public static final String PREVIOUS_PREFIX = "prev_";

    /** Per-90 and per-match figures: weighted. */
    static final List<String> RATE_KEYS =
            List.of("xg90", "xa90", "pts90", "xgc_per_match", "defcon_avg");

    /** Evidence counts: added. {@code fixtures} exists only on the squad builder's rows. */
    static final List<String> COUNT_KEYS = List.of("mins", "matches", "fixtures", "defcon_matches");

    /** The ramp's input, read from the current side only. */
    static final String MATCHES_THIS_SEASON = "matches60";

    public record Blended(Map<String, Object> metrics, double weight, int matchesThisSeason,
                          boolean hasPrevious) {
    }

    private SeasonBlend() {
    }

    public static double weight(int qualifyingMatchesThisSeason) {
        int n = Math.max(0, Math.min(qualifyingMatchesThisSeason, FULL_WEIGHT_MATCHES));
        return WEIGHT_BY_MATCHES[n];
    }

    /**
     * @param current this season's aggregate for the player, or null when he has no row in it
     * @param previous last season's aggregate, or null when he has none there
     * @return the merged record, or null when the two together are under the minutes floor
     */
    public static Blended blend(Map<String, Object> current, Map<String, Object> previous) {
        if (current == null && previous == null) {
            return null;
        }
        int matches = current == null ? 0
                : (int) ExpectedPoints.numeric(current.get(MATCHES_THIS_SEASON));
        double tableWeight = weight(matches);
        double w = previous == null ? 1.0 : tableWeight;

        Map<String, Object> out = new HashMap<>();
        for (String key : RATE_KEYS) {
            Object value = rate(current, previous, key, w);
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
        if (ExpectedPoints.numeric(out.get("mins")) < ExpectedPoints.MIN_WINDOW_MINUTES) {
            return null;
        }
        out.put("blend_weight", tableWeight);
        out.put("blend_matches", matches);
        out.put("blend_has_previous", previous != null);
        return new Blended(out, tableWeight, matches, previous != null);
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
                               String key, double w) {
        Object a = current == null ? null : current.get(key);
        Object b = previous == null ? null : previous.get(key);
        if (a instanceof Number x && b instanceof Number y) {
            return w * x.doubleValue() + (1 - w) * y.doubleValue();
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
