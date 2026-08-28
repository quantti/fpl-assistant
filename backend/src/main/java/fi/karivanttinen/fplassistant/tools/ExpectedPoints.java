package fi.karivanttinen.fplassistant.tools;

import java.util.Map;

/**
 * FPL's own scoring applied to expected numbers — the one figure that compares a forward with a
 * goalkeeper, and an attacking full-back with a stopper.
 *
 * <p>A goal is worth 6 to a defender and 4 to a forward; a clean sheet 4 to a defender, 1 to a
 * midfielder and nothing to a forward. Scoring on that scale is what lets the two kinds of
 * defender compete on their own strengths instead of being ranked against each other's.
 *
 * <p>Extracted from {@code PickTools} when a second tool needed the same formula. Ranking and
 * squad building must agree about what a player is worth; two copies would not.
 *
 * <p>Not modelled: defensive-contribution points (issue #46), save points and penalty saves.
 * Goalkeepers and ball-winning defenders are understated, which callers say in their output.
 */
public final class ExpectedPoints {

    /** Indexed by {@code element_type}: 1=GK, 2=DEF, 3=MID, 4=FWD. */
    public static final double[] GOAL_POINTS = {0, 6, 6, 5, 4};
    public static final double[] CLEAN_SHEET_POINTS = {0, 4, 4, 1, 0};
    public static final double ASSIST_POINTS = 3;
    public static final double APPEARANCE_POINTS = 2;

    /**
     * Matches of underlying data read per player when the metrics come from the season being
     * played: recent form, which is what "last 10" should mean.
     *
     * <p>A finished season is read whole instead ({@link #WHOLE_SEASON}). Taking its last ten
     * gameweeks means GW28-38 — the run-in, where a side chasing Europe rotates, a safe
     * mid-table side coasts and a relegated one has stopped trying. That is the least
     * representative quarter of the season.
     *
     * <p>Shared by {@code PickTools} and {@code CandidatePool} so the two cannot rank the same
     * player two different ways in one conversation by drifting apart on what "recent form"
     * means.
     */
    public static final int WINDOW_MATCHES = 10;

    /** Every gameweek of a finished season; no season has more fixtures than this per player. */
    public static final int WHOLE_SEASON = 60;

    /**
     * Minutes a player must have inside the window to be ranked at all. Two full matches: below
     * that, a per-90 figure is arithmetic on noise, and a cameo with one big chance outranks a
     * regular starter.
     */
    public static final int MIN_WINDOW_MINUTES = 180;

    /**
     * Completed gameweeks the season being played needs before its own numbers are used
     * outright, without even consulting the previous season. See
     * {@code SeasonResolver#betterMetricSeason}.
     */
    public static final int MIN_SAMPLE_GAMEWEEKS = 5;

    private ExpectedPoints() {
    }

    /**
     * Expected FPL points per 90 minutes. Appearance points are a constant and do not affect the
     * order — they are here so the number reads as points rather than as an index.
     */
    public static double perNinety(Map<String, Object> row) {
        int position = ((Number) row.get("element_type")).intValue();
        double points = APPEARANCE_POINTS
                + numeric(row.get("xg90")) * goalPoints(position)
                + numeric(row.get("xa90")) * ASSIST_POINTS;
        Object xgc = row.get("xgc_per_match");
        if (hasCleanSheetSignal(xgc) && cleanSheetPoints(position) > 0) {
            points += cleanSheetProbability(numeric(xgc)) * cleanSheetPoints(position);
        }
        return points;
    }

    /** Poisson: the chance a side with this expected concession lets in none. */
    public static double cleanSheetProbability(double expectedGoalsConceded) {
        return Math.exp(-expectedGoalsConceded);
    }

    /**
     * Bounds-guarded: the column has no CHECK constraint and FPL has since added managers as a
     * fifth type, so an out-of-range value must degrade rather than throw out of a tool call.
     */
    public static double goalPoints(int position) {
        return position >= 1 && position < GOAL_POINTS.length ? GOAL_POINTS[position] : 0;
    }

    public static double cleanSheetPoints(int position) {
        return position >= 1 && position < CLEAN_SHEET_POINTS.length
                ? CLEAN_SHEET_POINTS[position] : 0;
    }

    public static double numeric(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    /**
     * Whether a captured expected-concession figure can be believed. The column is
     * {@code NOT NULL DEFAULT 0}, so an unpopulated season reads as 0.00 — and {@code exp(-0)} is
     * a 100% clean sheet. No real side has an expected concession of exactly zero across a match,
     * so zero means nobody wrote it.
     */
    static boolean hasCleanSheetSignal(Object xgc) {
        return xgc != null && numeric(xgc) > 0;
    }
}
