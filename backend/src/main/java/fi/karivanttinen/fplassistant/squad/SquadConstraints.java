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
     *
     * @param club the name as the user (or model) wrote it — kept for display in the constraint
     *        receipt, never compared against a candidate directly
     * @param teamId the pool's own team id, resolved against both {@code team_name} and
     *        {@code team_short} once {@code SquadTools} has a pool to resolve against.
     *        {@link #UNRESOLVED} until then — a quota fresh out of {@code ConstraintParser} does
     *        not have a pool yet. Every place that matches a quota against a candidate compares
     *        this id, never {@link #club}, so "Manchester City" and "Man City" are the same
     *        quota once resolved.
     */
    public record ClubQuota(String club, Comparator comparator, int count, int teamId) {

        /** Sentinel for a quota not yet resolved against the pool. */
        public static final int UNRESOLVED = Integer.MIN_VALUE;

        public ClubQuota(String club, Comparator comparator, int count) {
            this(club, comparator, count, UNRESOLVED);
        }

        public ClubQuota withTeamId(int teamId) {
            return new ClubQuota(club, comparator, count, teamId);
        }
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
