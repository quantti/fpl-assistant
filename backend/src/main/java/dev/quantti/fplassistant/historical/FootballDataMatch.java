package dev.quantti.fplassistant.historical;

/**
 * One parsed match from a football-data.org {@code /v4/competitions/PL/matches} response,
 * before team-name resolution. {@code homeTeamName}/{@code awayTeamName} are football-data's
 * full club names ("Wolverhampton Wanderers FC") — resolve via {@link FootballDataTeamAliases}
 * before writing, never via {@link VaastavTeamMapper} or {@link HistoricalTeamResolver}, both
 * of which speak a different name set.
 */
public record FootballDataMatch(
        String status,
        String homeTeamName,
        String awayTeamName,
        Integer homeGoals,
        Integer awayGoals,
        Integer matchday,
        String utcDate
) {
    public boolean isFinished() {
        return "FINISHED".equals(status);
    }
}
