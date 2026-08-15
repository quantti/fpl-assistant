package fi.karivanttinen.fplassistant.historical;

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
}
