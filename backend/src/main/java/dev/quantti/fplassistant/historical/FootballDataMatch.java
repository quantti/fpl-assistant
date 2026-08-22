package dev.quantti.fplassistant.historical;

/**
 * One parsed match from a football-data.org {@code /v4/competitions/PL/matches} response,
 * before team-name resolution. {@code homeTeamName}/{@code awayTeamName} are football-data's
 * full club names ("Wolverhampton Wanderers FC") — resolve via {@link FootballDataTeamAliases}
 * before writing, never via {@link VaastavTeamMapper} or {@link HistoricalTeamResolver}, both
 * of which speak a different name set. {@code seasonCode} is this database's
 * {@code season.code} for the season the match belongs to, derived from the match's own
 * {@code season.startDate} by the parser — the sync's write guard compares it against the
 * season the database flags current, and stamps it on the row. It is {@code null} when the
 * response carried no usable date, which the sync treats as "skip this match".
 */
public record FootballDataMatch(
        String status,
        String homeTeamName,
        String awayTeamName,
        Integer homeGoals,
        Integer awayGoals,
        Integer matchday,
        String utcDate,
        String seasonCode
) {
    public boolean isFinished() {
        return "FINISHED".equals(status);
    }
}
