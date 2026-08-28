package dev.quantti.fplassistant.tools;

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
 * <p>Not modelled: save points and penalty saves, so goalkeepers are understated and callers say
 * so. Defensive-contribution points are modelled — see {@link #defconPerNinety} — from the
 * per-match rows {@code player_fixture_stats} carries; a row without them simply loses the term.
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

    /**
     * Defensive actions a player must reach in a match to be awarded {@link #DEFCON_POINTS},
     * indexed by {@code element_type}: 1=GK, 2=DEF, 3=MID, 4=FWD.
     *
     * <p>Ten for a defender — clearances, blocks, interceptions and tackles — and twelve for a
     * midfielder or forward, the same plus recoveries. Zero for a goalkeeper: FPL awards them
     * none, and all 20 keepers with an hour behind them read 0 in the live 2026-27 data.
     *
     * <p>Unchanged for 2026/27. What changed that season is when the numbers become final — see
     * {@link #defconHitRate}.
     *
     * <p>These are rules of the game held here, not data read from anywhere:
     * {@code bootstrap-static} does not publish
     * {@code element_types[].defensive_contribution_threshold}.
     */
    public static final int[] DEFCON_THRESHOLD = {0, 0, 10, 12, 12};

    /** What clearing the threshold pays, once, in the match it is cleared in. */
    public static final double DEFCON_POINTS = 2;

    /**
     * Matches of 60 minutes or more the hit rate must rest on before it is read at all.
     *
     * <p>Two, which is the same sample {@link #MIN_WINDOW_MINUTES} already admits a player to the
     * ranking on: no single component is held to a stricter standard than the row itself. Below
     * it the term is dropped rather than estimated, and callers print {@code DC n/a}.
     */
    public static final int MIN_DEFCON_MATCHES = 2;

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
        return points + defconPerNinety(row, position);
    }

    /**
     * Expected defensive-contribution points per qualifying match, from how often the player
     * has actually crossed the threshold rather than from his average.
     *
     * <p>Per match rather than per 90, like the clean-sheet term beside it: both are the chance
     * of a per-match event, counted over matches of an hour or more, and both are added to a
     * per-90 figure on the understanding that a player who qualifies has played most of the 90.
     *
     * <p>The average cannot answer this. The points land at a per-match threshold, so a defender
     * steady on 9.8 actions scores nothing while one averaging 9.5 with a wide spread scores
     * often. The hit rate reads the distribution the mean hides.
     */
    public static double defconPerNinety(Map<String, Object> row, int position) {
        return defconHitRate(row, position) * DEFCON_POINTS;
    }

    /**
     * The share of the player's qualifying matches in which he cleared his position's threshold,
     * or zero when there is no readable record. Counted over matches of an hour or more — the
     * same discipline the clean sheet uses, because the actions arrive with the minutes and a
     * cameo would otherwise read as a match he failed to contribute in.
     *
     * <p><b>Provisional for a day, new in 2026/27.</b> Gameweek scores are final at 09:00 UK on
     * the day after the gameweek's last match rather than an hour after the whistle, so that
     * post-match Opta review can feed into defensive-contribution points. A hit rate feels that
     * more than an average would: a review lifting a defender from 9 actions to 10 flips a whole
     * match from miss to hit. Nothing is done about it here because
     * {@code DefensiveStatsSyncService} rewrites every match row on every run, so the next
     * nightly sync replaces a provisional figure with the reviewed one.
     */
    public static double defconHitRate(Map<String, Object> row, int position) {
        if (!hasDefconSignal(row, position)) {
            return 0;
        }
        double matches = numeric(row.get("defcon_matches"));
        double hits = numeric(row.get(position == 2 ? "defcon_hits_def" : "defcon_hits_mid"));
        return hits / matches;
    }

    /**
     * Whether this row carries a defensive-contribution record worth reading: a position FPL
     * awards the points to, and enough qualifying matches to make a rate mean something. The
     * columns are nullable with no default, so an unfetched season reads as no matches — which
     * must drop the term, not estimate it at zero actions.
     */
    public static boolean hasDefconSignal(Map<String, Object> row, int position) {
        return defconThreshold(position) > 0
                && numeric(row.get("defcon_matches")) >= MIN_DEFCON_MATCHES;
    }

    /** Bounds-guarded like {@link #goalPoints}: an unknown element type earns no term. */
    public static int defconThreshold(int position) {
        return position >= 1 && position < DEFCON_THRESHOLD.length
                ? DEFCON_THRESHOLD[position] : 0;
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
