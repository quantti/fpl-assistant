package fi.karivanttinen.fplassistant.tools;

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
 * so. Defensive contribution is modelled from short- and long-appearance action rates in
 * {@link #perFixture}; a missing required class is labelled partial rather than read as zero.
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
     * Minimum minutes for both the ability floor and the separate current-role gate. The latter
     * additionally requires complete current-season evidence and 60+ in every expected fixture;
     * cross-season aggregate minutes cannot satisfy it.
     */
    public static final int MIN_WINDOW_MINUTES = 180;

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
     * Observations in each used appearance class that a DefCon action mean must rest on.
     * Below it that class is unavailable rather than estimated; another class may remain valid,
     * and callers label the combined component partial.
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
     * Expected components over one fixture at the supplied observed exposure rates.
     *
     * <p>The clean-sheet term is the fixture's: {@code exp(-λ)} on the concession
     * {@link ClubRates#projectedGoalsAgainst} projects for this club against this opponent at
     * this venue, conditional on reaching sixty minutes. When no projection exists the term is
     * absent — {@code null}, printed n/a and labelled partial — never the player's season
     * average and never a certain clean sheet. Appearance, attack and DefCon do not depend on the
     * fixture and are identical across a club's window.
     */
    public static Optional<PerFixture> perFixture(Map<String, Object> row,
                                                   SeasonBlend.Exposure exposure,
                                                   OptionalDouble projectedGoalsAgainst) {
        if (exposure == null) {
            return Optional.empty();
        }
        int position = ((Number) row.get("element_type")).intValue();
        double appearance = exposure.appearanceRate() + exposure.sixtyRate();
        Double attack = exposure.expectedMinutes() == 0 ? 0.0
                : row.get("xg90") instanceof Number xg && row.get("xa90") instanceof Number xa
                ? (goalPoints(position) * xg.doubleValue() + ASSIST_POINTS * xa.doubleValue())
                        * exposure.expectedMinutes() / 90
                : null;

        Double cleanSheet = 0.0;
        if (cleanSheetPoints(position) > 0 && exposure.sixtyRate() > 0) {
            cleanSheet = projectedGoalsAgainst.isPresent()
                    ? cleanSheetPoints(position) * exposure.sixtyRate()
                            * cleanSheetProbability(projectedGoalsAgainst.getAsDouble())
                    : null;
        }

        DefconFixture defcon = defconPerFixture(row, position, exposure);
        double points = appearance + (attack == null ? 0 : attack)
                + (cleanSheet == null ? 0 : cleanSheet)
                + defcon.points();
        return Optional.of(new PerFixture(points, appearance, attack, cleanSheet,
                defcon.points(), defcon.partial()));
    }

    private record DefconFixture(double points, boolean partial) {
    }

    private static DefconFixture defconPerFixture(Map<String, Object> row, int position,
                                                   SeasonBlend.Exposure exposure) {
        if (defconThreshold(position) == 0) {
            return new DefconFixture(0, false);
        }
        double weightedTail = 0;
        boolean partial = false;
        if (exposure.shortRate() > 0) {
            if (hasClassSignal(row, "defcon_short_avg", "defcon_short_matches")) {
                weightedTail += exposure.shortRate() * thresholdProbability(
                        numeric(row.get("defcon_short_avg")), defconThreshold(position));
            } else {
                partial = true;
            }
        }
        if (exposure.sixtyRate() > 0) {
            String mean = row.containsKey("defcon_long_avg") ? "defcon_long_avg" : "defcon_avg";
            String count = row.containsKey("defcon_long_matches")
                    ? "defcon_long_matches" : "defcon_matches";
            if (hasClassSignal(row, mean, count)) {
                weightedTail += exposure.sixtyRate() * thresholdProbability(
                        numeric(row.get(mean)), defconThreshold(position));
            } else {
                partial = true;
            }
        }
        return new DefconFixture(DEFCON_POINTS * weightedTail, partial);
    }

    private static boolean hasClassSignal(Map<String, Object> row, String meanKey, String countKey) {
        return numeric(row.get(countKey)) >= MIN_DEFCON_MATCHES
                && row.get(meanKey) instanceof Number && numeric(row.get(meanKey)) >= 0;
    }

    /**
     * Legacy explanatory per-90 statistic. Rankings use {@link #perFixture} so appearance and
     * clean-sheet boundaries are not smoothed into a mean-minutes multiplier.
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
     * Legacy long-appearance defensive-contribution term for {@link #perNinety}, estimated from
     * the player's action mean rather than a coarse count of past threshold crossings.
     *
     * <p>The average answers the threshold question once read through a distribution. The points land at a
     * per-match threshold, so the question is how often the player clears it — and a defender
     * averaging 9.8 actions clears 10 about half the time, which neither the bare average nor a
     * tally of past crossings says on its own.
     */
    public static double defconPerNinety(Map<String, Object> row, int position) {
        return defconProbability(row, position) * DEFCON_POINTS;
    }

    /**
     * The chance this player reaches his position's threshold in a match, or zero when there is
     * no readable record. Estimated from his mean actions per match through
     * {@link #thresholdProbability}, over matches of an hour or more — the same discipline the
     * clean sheet uses, because the actions arrive with the minutes and a cameo would otherwise
     * read as a match he failed to contribute in.
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
