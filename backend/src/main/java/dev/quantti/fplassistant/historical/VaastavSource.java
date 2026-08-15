package dev.quantti.fplassistant.historical;

/**
 * Where a vaastav CSV file's raw content comes from — abstracted so the import logic can be
 * tested against real files checked into test resources without a network call, per
 * {@code AGENTS.md}'s "external APIs only at sync time" convention: only {@link HttpVaastavSource}
 * touches the network, and only when a backfill or resolve is actually running.
 */
public interface VaastavSource {

    /**
     * @param season   season code, e.g. {@code "2019-20"}
     * @param fileName {@code "teams.csv"} or {@code "fixtures.csv"}
     * @throws IllegalStateException naming the season and file on any fetch failure
     */
    String fetch(String season, String fileName);

    /**
     * Like {@link #fetch} but returns {@code null} when the file is genuinely absent (HTTP 404)
     * rather than throwing — used by the price sync for the current-season {@code gws/merged_gw.csv},
     * which does not exist before GW1. Any other failure still throws. The default delegates to
     * {@link #fetch} so existing strict sources are unaffected.
     */
    default String fetchIfPresent(String season, String fileName) {
        return fetch(season, fileName);
    }
}
