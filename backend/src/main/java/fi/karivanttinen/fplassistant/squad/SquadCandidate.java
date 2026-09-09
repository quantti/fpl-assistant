package fi.karivanttinen.fplassistant.squad;

import java.util.Map;

import fi.karivanttinen.fplassistant.tools.MinutesModel;
import fi.karivanttinen.fplassistant.tools.RankingMetrics;
import fi.karivanttinen.fplassistant.tools.SeasonBlend;

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

    /** Ability controls scoring; only {@link MinutesModel.Eligibility#ELIGIBLE} controls the XI. */
    public enum Tier { RANKABLE, BODY }

    public boolean isDoubtful() {
        return "d".equals(status);
    }

    public boolean mayStart() {
        return tier == Tier.RANKABLE && eligibility == MinutesModel.Eligibility.ELIGIBLE;
    }
}
