package fi.karivanttinen.fplassistant.historical;

/**
 * Where a football-data.org API response body comes from — abstracted the same way
 * {@link VaastavSource} is, so the sync logic can be tested against a captured or hand-built
 * response without a network call or a real API key.
 */
public interface FootballDataSource {

    /** @throws IllegalStateException naming the failure on any fetch problem */
    String fetchCurrentSeasonMatches();
}
