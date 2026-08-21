package dev.quantti.fplassistant.tools;

import java.time.LocalDate;

/**
 * Which season a tool's numbers came from, and whether that is the season currently being played.
 *
 * <p>Every tool prefixes its output with {@link #provenance()}. Season data is never
 * interchangeable: a player's form in a finished season says something different from their form
 * this season, and later phases weight earlier seasons differently again. Answering from a previous
 * season without saying so would let the model present stale numbers as current.
 */
public record SeasonContext(
        int seasonId,
        String code,
        boolean currentSeason,
        String currentSeasonCode,
        LocalDate currentSeasonStart
) {

    public String provenance() {
        if (currentSeason) {
            return "Data source: season " + code + " (the season currently being played).";
        }
        return "Data source: season " + code + " — a COMPLETED PREVIOUS season, not the season now"
                + " being played. The current season " + currentSeasonCode + " starts "
                + currentSeasonStart + " and has no data yet. Say so when you use these numbers,"
                + " and do not describe them as current form, current price, or current ownership.";
    }

    /**
     * Provenance for a completed season shown <b>alongside</b> the current one — the
     * dual-season player card. Distinct from {@link #provenance()}'s fallback text, which says
     * the current season "has no data yet": here the current season is being played and holds
     * data, which is the whole reason the previous one is shown next to it.
     */
    public static String previousSeasonProvenance(String code, String currentSeasonCode) {
        return "Data source: season " + code + " — a COMPLETED PREVIOUS season. The current season "
                + currentSeasonCode + " is under way; these are last season's numbers, not current"
                + " form, price, or ownership.";
    }
}
