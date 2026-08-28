package fi.karivanttinen.fplassistant.squad;

import java.util.Map;

/**
 * One player the solver may choose, with everything needed to price him, place him and score
 * him.
 *
 * <p>Identity, club, price, ownership and availability come from {@code fpl_daily_price} — the
 * daily capture from FPL's own API. Metrics come from {@code player_fixture_stats}. Nothing here
 * is read from the tapas {@code player} table, which is synced weekly and goes stale.
 *
 * @param metrics the raw metric row ({@code element_type}, {@code xg90}, {@code xa90},
 *        {@code xgc_per_match}, {@code pts90}, plus {@code defcon_matches} and
 *        {@code defcon_avg}) as {@code ExpectedPoints.perNinety} expects it;
 *        empty for a {@link Tier#BODY}
 * @param minutesInWindow the player's minutes across the metric window
 * @param teamFixturesInWindow his club's distinct fixtures across the gameweek span his own
 *        windowed rows cover — not a count of those rows. tapas writes a
 *        {@code player_fixture_stats} row only for a fixture he was named in the squad for, so a
 *        plain row count silently shrinks for anyone dropped from the squad mid-window, which
 *        inflates his expected minutes per fixture rather than flagging the gap. Counting the
 *        club's actual fixtures between his first and last windowed gameweek catches that miss
 *        without penalising a player who simply was not at the club yet.
 */
public record SquadCandidate(
        int elementId,
        String webName,
        String fullName,
        String club,
        String clubShort,
        int elementType,
        int nowCost,
        double ownership,
        String status,
        String news,
        int teamId,
        double minutesInWindow,
        int teamFixturesInWindow,
        Map<String, Object> metrics,
        Tier tier
) {

    /**
     * Whether this player can be judged or only bought.
     *
     * <p>{@link #RANKABLE} has a real minutes record, so his expected points mean something and
     * he may start. {@link #BODY} does not: he is asserted to be worth nothing, is labelled a
     * body in the output, and can never be in the starting XI. The distinction is what keeps the
     * cheapest bench slot reachable without the solver claiming a non-player is good.
     */
    public enum Tier { RANKABLE, BODY }

    public boolean isDoubtful() {
        return "d".equals(status);
    }
}
