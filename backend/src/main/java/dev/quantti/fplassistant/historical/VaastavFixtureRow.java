package dev.quantti.fplassistant.historical;

/**
 * One parsed {@code fixtures.csv} row, before team-id resolution. {@code teamHId}/{@code teamAId}
 * are vaastav's season-scoped ids — resolve them via {@link VaastavTeamMapper} before writing.
 */
public record VaastavFixtureRow(
        boolean finished,
        int teamHId,
        int teamAId,
        Integer teamHScore,
        Integer teamAScore,
        Integer gameweek,
        Integer teamHDifficulty,
        Integer teamADifficulty,
        String kickoffTime
) {
}
