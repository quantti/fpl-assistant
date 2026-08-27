package dev.quantti.fplassistant.squad;

import java.util.List;

/**
 * A parsed squad request. Every field is already validated for shape; whether it can be
 * satisfied is {@code Feasibility}'s question, not this record's.
 *
 * @param budget in tenths of a million, as everywhere else in this project
 * @param formation a forced formation such as "3-4-3", or null to let the solver choose
 */
public record SquadConstraints(
        List<String> mustInclude,
        List<String> mustExclude,
        List<ClubQuota> clubQuotas,
        int budget,
        int horizon,
        String formation
) {

    public enum Comparator { EXACTLY, AT_LEAST, AT_MOST }

    /**
     * How many players the user wants from one club. The comparator matters: an exact quota is
     * the most common source of infeasibility, and forcing "at least two" through an equality
     * manufactures refusals nobody asked for.
     */
    public record ClubQuota(String club, Comparator comparator, int count) {
    }

    /** Whether the budget is the from-scratch £100.0m, which is the only exactly-priced case. */
    public boolean isFromScratchBudget() {
        return budget == ConstraintParser.BUDGET_DEFAULT;
    }
}
