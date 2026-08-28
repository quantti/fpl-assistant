package dev.quantti.fplassistant.tools;

import java.util.Optional;

/**
 * Which seasons feed a ranking's metrics.
 *
 * <p>{@code played} is the resolved season and the one every pool row belongs to. {@code previous}
 * is present only when {@code played} is the season currently being played — that is when a blend
 * is meaningful. During the changeover the resolver names a completed season, and blending it with
 * the one before would rank on two finished seasons; then {@code previous} is empty and
 * {@code played} is read whole.
 *
 * <p>Replaces {@code PickTools.MetricSeason} and {@code CandidatePool.MetricWindow}, which each
 * carried a "which single season" decision that no longer exists.
 */
public record MetricSeasons(SeasonContext played, Optional<SeasonContext> previous,
                            int playedGameweeks, int latestGameweek) {

    /** Recent form for a season in progress, the whole season for a finished one. */
    public int windowLimit() {
        return played.currentSeason() ? ExpectedPoints.WINDOW_MATCHES : ExpectedPoints.WHOLE_SEASON;
    }

    /** A season id no row can carry, so a query's {@code IN (?, ?)} matches nothing extra. */
    public int previousSeasonId() {
        return previous.map(SeasonContext::seasonId).orElse(-1);
    }

    public boolean blending() {
        return previous.isPresent();
    }

    /** The previous season's code, for the sentences that name both. */
    public String previousCode() {
        return previous.map(SeasonContext::code).orElseThrow();
    }
}
