package dev.quantti.fplassistant.user;

import java.time.Instant;

/**
 * The period one message allowance covers.
 *
 * <p>Bounds are half-open, {@code from} inclusive and {@code until} exclusive. On a real window
 * {@code from} is always known: a period whose start nothing records is not a window but the
 * fallback, because counting from the beginning of time would charge an account for every message
 * it has ever sent. {@code until} may be null — the off-season after the last captured deadline,
 * before the new season is published.
 *
 * <p>A {@link Kind#FALLBACK} window carries no bounds, no gameweek and no season. Its counting
 * lives in the rolling-window query it delegates to, and a second copy of those bounds here would
 * only invite someone to read it.
 *
 * @param gameweek the round this window is the run-up to, on a {@link Kind#GAMEWEEK} window only.
 *                 The matches played during the window belong to the <em>previous</em> round,
 *                 which is why nothing user-facing calls this "the current gameweek"
 */
public record QuotaWindow(Kind kind, Instant from, Instant until, int limit,
                          Integer gameweek, String season) {

    public enum Kind {
        /** Between two deadlines of one season. */
        GAMEWEEK,
        /** The gap between two seasons, or either edge of the captured timeline. */
        OFF_SEASON,
        /** No usable schedule: the rolling seven days, exactly as before this window existed. */
        FALLBACK
    }

    public boolean fallback() {
        return kind == Kind.FALLBACK;
    }
}
