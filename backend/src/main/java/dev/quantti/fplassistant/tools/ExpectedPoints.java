package dev.quantti.fplassistant.tools;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

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
 * so. Defensive contribution is modelled from an action-rate estimate in
 * {@link #expectedPerFixture}; a missing required record is labelled partial rather than read as
 * zero.
 */
public final class ExpectedPoints {

    /** Indexed by {@code element_type}: 1=GK, 2=DEF, 3=MID, 4=FWD. */
    public static final double[] GOAL_POINTS = {0, 6, 6, 5, 4};
    public static final double[] CLEAN_SHEET_POINTS = {0, 4, 4, 1, 0};
    public static final double ASSIST_POINTS = 3;
    public static final double APPEARANCE_POINTS = 2;

    /**
     * Latest completed current-club fixtures read per player when metrics come from the season
     * being played. This is a bounded evidence window, not FPL's points-based "form".
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
     * Defensive actions a player must reach in a match to be awarded {@link #DEFCON_POINTS},
     * indexed by {@code element_type}: 1=GK, 2=DEF, 3=MID, 4=FWD.
     *
     * <p>Ten for a defender — clearances, blocks, interceptions and tackles — and twelve for a
     * midfielder or forward, the same plus recoveries. Zero for a goalkeeper: FPL awards them
     * none, and all 20 keepers with an hour behind them read 0 in the live 2026-27 data.
     *
     * <p>Unchanged for 2026/27. What changed that season is when the numbers become final — see
     * {@link #defconProbability}.
     *
     * <p>These are rules of the game held here, not data read from anywhere:
     * {@code bootstrap-static} does not publish
     * {@code element_types[].defensive_contribution_threshold}.
     */
    public static final int[] DEFCON_THRESHOLD = {0, 0, 10, 12, 12};

    /** What clearing the threshold pays, once, in the match it is cleared in. */
    public static final double DEFCON_POINTS = 2;

    /**
     * Observations a DefCon action mean must rest on. Below it the component is unavailable
     * rather than estimated, and callers label the projection partial.
     */
    public static final int MIN_DEFCON_MATCHES = 2;

    /**
     * How much wider the spread of a player's per-match defensive actions is than a Poisson
     * process would give: {@code variance = DEFCON_DISPERSION * mean}.
     *
     * <p>The within-player spread measured over 2025-26 (matches of 60+ minutes, players with 15
     * or more of them) is 1.34 at the median — 1.38 for defenders and midfielders, 1.19 for
     * forwards. Poisson assumes exactly 1.0, so Poisson is the wrong distribution here and a
     * negative binomial is the right one: it is the Poisson generalised to admit this spread.
     *
     * <p>1.6 is deliberately above the measured figure. The mean this is applied to comes from a
     * handful of matches and is itself uncertain, and the constant is the only place that
     * uncertainty can go; on a held-out half of 2025-26, prediction error falls as the value
     * rises from 1.0 to about 2.0 and flattens there. 1.6 is a middle choice, not an optimum,
     * and was not tuned on the data it was tested on. A posterior-predictive form — where the
     * spread widens by itself when the sample is short — is the principled version, and belongs
     * with the cross-season blending work (KAR-38).
     *
     * <p>Calibrated per player, not per league: over a held-out half-season the mean of the
     * per-player predictions is 0.216 against an actual 0.205, and within ±0.015 in every band
     * of predicted probability below 0.5. Above 0.5 it over-predicts by about 0.08 — regression
     * to the mean, which the blending work also addresses. Per position across the whole
     * season: defenders 0.255 predicted against 0.251, midfielders 0.167 against 0.157.
     */
    public static final double DEFCON_DISPERSION = 1.6;

    public record PerFixture(double points, double appearance, Double attack,
                             Double cleanSheet, double defensiveContribution,
                             boolean partialDefcon) {
    }

    private ExpectedPoints() {
    }

    /**
     * Expected FPL points for one assumed full fixture. The ranking deliberately does not use
     * observed appearance, 60+ or expected-minute rates: those facts are disclosed separately,
     * never converted into a recommendation advantage. Attack remains the player's xG/xA per-90
     * ability, clean sheets use the fixture's projected concession and DefCon uses its action-rate
     * estimate. A missing attack rate makes the expected-points projection unavailable rather than
     * substituting minutes or observed points.
     */
    public static Optional<PerFixture> expectedPerFixture(Map<String, Object> row,
                                                           OptionalDouble projectedGoalsAgainst) {
        if (!(row.get("xg90") instanceof Number xg) || !(row.get("xa90") instanceof Number xa)) {
            return Optional.empty();
        }
        int position = ((Number) row.get("element_type")).intValue();
        double appearance = APPEARANCE_POINTS;
        double attack = goalPoints(position) * xg.doubleValue() + ASSIST_POINTS * xa.doubleValue();
        Double cleanSheet;
        if (cleanSheetPoints(position) == 0) {
            cleanSheet = 0.0;
        } else if (projectedGoalsAgainst.isPresent()) {
            cleanSheet = cleanSheetPoints(position)
                    * cleanSheetProbability(projectedGoalsAgainst.getAsDouble());
        } else {
            cleanSheet = null;
        }
        boolean partialDefcon = defconThreshold(position) > 0 && !hasDefconSignal(row, position);
        double defensiveContribution = defconExpectedPoints(row, position);
        double points = appearance + attack + (cleanSheet == null ? 0 : cleanSheet)
                + defensiveContribution;
        return Optional.of(new PerFixture(points, appearance, attack, cleanSheet,
                defensiveContribution, partialDefcon));
    }

    /**
     * Expected defensive-contribution points in an assumed full fixture, estimated from the
     * player's action mean rather than a coarse count of past threshold crossings.
     */
    private static double defconExpectedPoints(Map<String, Object> row, int position) {
        return defconProbability(row, position) * DEFCON_POINTS;
    }

    /**
     * The chance this player reaches his position's threshold in a match, or zero when there is
     * no readable record. Estimated from the recorded action mean per fixture through
     * {@link #thresholdProbability}. The action-rate component is independent of the separate
     * factual minutes notice.
     *
     * <p><b>Modelled, not counted.</b> An earlier version divided past crossings by matches.
     * That reads only "over or under" and throws away the action counts themselves, so a
     * defender who made 9 in every match scored identically to one who made 2 — and on a
     * two-match record the only possible answers were 0%, 50% and 100%. Predicting the rest of
     * a player's 2025-26 season from his first few matches, this estimator's error is 28% lower
     * after 2 matches, 24% after 3, 20% after 5 and 7% after 10.
     *
     * <p><b>Provisional for a day, new in 2026/27.</b> Gameweek scores are final at 09:00 UK on
     * the day after the gameweek's last match rather than an hour after the whistle, so that
     * post-match Opta review can feed into defensive-contribution points. Modelling from the
     * mean absorbs that better than counting crossings would: a review lifting a defender from 9
     * actions to 10 moves his average slightly rather than flipping a whole match from miss to
     * hit. Nothing is done about it here because
     * {@code DefensiveStatsSyncService} rewrites every match row on every run, so the next
     * nightly sync replaces a provisional figure with the reviewed one.
     */
    public static double defconProbability(Map<String, Object> row, int position) {
        return hasDefconSignal(row, position)
                ? thresholdProbability(numeric(row.get("defcon_avg")), defconThreshold(position))
                : 0;
    }

    /**
     * Negative-binomial tail: the chance a player averaging {@code meanActions} per match reaches
     * {@code threshold} in one of them, with the spread {@link #DEFCON_DISPERSION} describes.
     *
     * <p>Summed forward from {@code P(0)} rather than called as a library function — the
     * threshold is 10 or 12, so this is a dozen multiplications and the project carries no
     * statistics dependency.
     */
    public static double thresholdProbability(double meanActions, int threshold) {
        if (meanActions <= 0 || threshold <= 0) {
            return 0;
        }
        double shape = meanActions / (DEFCON_DISPERSION - 1);
        double success = shape / (shape + meanActions);
        double term = Math.pow(success, shape);
        double cumulative = term;
        for (int i = 1; i < threshold; i++) {
            term *= (shape + i - 1) / i * (1 - success);
            cumulative += term;
        }
        return Math.min(1, Math.max(0, 1 - cumulative));
    }

    /**
     * Whether this row carries a defensive-contribution record worth reading: a position FPL
     * awards the points to, and enough qualifying matches to make a rate mean something. The
     * columns are nullable with no default, so an unfetched season reads as no matches — which
     * must drop the term, not estimate it at zero actions.
     */
    public static boolean hasDefconSignal(Map<String, Object> row, int position) {
        return defconThreshold(position) > 0
                && numeric(row.get("defcon_matches")) >= MIN_DEFCON_MATCHES
                && row.get("defcon_avg") instanceof Number
                && numeric(row.get("defcon_avg")) >= 0;
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

}
