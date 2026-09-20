package dev.quantti.fplassistant.squad;

import java.util.Map;

import dev.quantti.fplassistant.tools.MinutesModel;
import dev.quantti.fplassistant.tools.RankingMetrics;
import dev.quantti.fplassistant.tools.SeasonBlend;

/** One purchasable player, with ability and current-role evidence kept separate. */
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
        double expectedMinutes,
        Map<String, Object> metrics,
        Tier tier,
        RankingMetrics.MinutesWindow minutesWindow,
        SeasonBlend.Exposure exposure,
        MinutesModel.Eligibility eligibility
) {

    /** Ability controls scoring; eligibility enforces availability and the recent-minute exclusion. */
    public enum Tier { RANKABLE, BODY }

    public boolean isDoubtful() {
        return "d".equals(status);
    }

    public boolean mayStart() {
        return tier == Tier.RANKABLE && eligibility == MinutesModel.Eligibility.ELIGIBLE;
    }
}
