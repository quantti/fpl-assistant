package fi.karivanttinen.fplassistant.squad;

import java.util.List;

/**
 * A parsed squad request. Every field is already validated for shape; whether it can be
 * satisfied is {@code Feasibility}'s question, not this record's.
 *
 * @param budget in tenths of a million, as everywhere else in this project
 * @param formation a forced formation such as 3-4-3, or null to let the solver choose. Syntax is
 *        validated by {@code ConstraintParser}; whether it is a legal starting XI is
 *        {@code Feasibility}'s question, same as everything else here.
 */
public record SquadConstraints(
        List<String> mustInclude,
        List<String> mustExclude,
        List<ClubQuota> clubQuotas,
        int budget,
        int horizon,
        Formation formation
) {

    public enum Comparator { EXACTLY, AT_LEAST, AT_MOST }

    /**
     * How many players the user wants from one club. The comparator matters: an exact quota is
     * the most common source of infeasibility, and forcing "at least two" through an equality
     * manufactures refusals nobody asked for.
     */
    public record ClubQuota(String club, Comparator comparator, int count) {
    }

    /** A forced starting-XI shape: defenders, midfielders, forwards (the goalkeeper is fixed). */
    public record Formation(int def, int mid, int fwd) {
        public String label() {
            return def + "-" + mid + "-" + fwd;
        }
    }

    /** Whether the budget is the from-scratch £100.0m, which is the only exactly-priced case. */
    public boolean isFromScratchBudget() {
        return budget == ConstraintParser.BUDGET_DEFAULT;
    }
}
