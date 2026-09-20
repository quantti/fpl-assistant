package fi.karivanttinen.fplassistant.squad;

import java.util.Map;

import fi.karivanttinen.fplassistant.tools.MinutesModel;
import fi.karivanttinen.fplassistant.tools.RankingMetrics;

/** One purchasable player, with ability and current-evidence checks kept separate. */
public record SquadCandidate(
        int elementId,
        String webName,
        String fullName,
        String club,
        String clubShort,
        int elementType,
        int nowCost,
        double ownership,
        String status,
        String news,
        int teamId,
        Map<String, Object> metrics,
        Tier tier,
        RankingMetrics.MinutesWindow minutesWindow,
        MinutesModel.Eligibility eligibility
) {

    /** Ability controls scoring; eligibility enforces availability and complete current evidence. */
    public enum Tier { RANKABLE, BODY }

    public boolean isDoubtful() {
        return "d".equals(status);
    }

    public boolean mayStart() {
        return tier == Tier.RANKABLE && eligibility == MinutesModel.Eligibility.ELIGIBLE;
    }
}
