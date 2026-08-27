package fi.karivanttinen.fplassistant.squad;

import java.util.List;

/**
 * A squad the solver proved legal.
 *
 * @param bench in auto-substitution priority order: the reserve goalkeeper first, then the three
 *        outfield substitutes best-scoring first. Bench order is an output with meaning — FPL
 *        substitutes in this order and only when the resulting formation stays legal.
 * @param relaxed the structural defaults that had to give way, in the order they gave way, or
 *        empty. Rules of the game never appear here; they are never relaxed.
 */
public record SolvedSquad(
        List<SquadCandidate> squad,
        List<SquadCandidate> startingXi,
        List<SquadCandidate> bench,
        SquadCandidate captain,
        SquadCandidate viceCaptain,
        int totalCost,
        String formation,
        List<String> relaxed
) {
}
