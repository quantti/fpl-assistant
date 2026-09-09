package fi.karivanttinen.fplassistant.squad;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import fi.karivanttinen.fplassistant.tools.MinutesModel;
import fi.karivanttinen.fplassistant.tools.RankingMetrics;
import fi.karivanttinen.fplassistant.tools.SeasonBlend;

final class SquadCandidateTestData {

    private static final RankingMetrics.MinutesWindow ELIGIBLE_WINDOW =
            new RankingMetrics.MinutesWindow(true, RankingMetrics.WindowReason.COMPLETE, 1,
                    Instant.parse("2026-08-01T12:00:00Z"),
                    Instant.parse("2026-08-03T12:00:00Z"),
                    3, 3, 270, 3, 3, List.of());
    private static final SeasonBlend.Exposure FULL_EXPOSURE =
            new SeasonBlend.Exposure(3, 3, 3, 270, 1, 1, 0, 90, 4);

    private SquadCandidateTestData() {
    }

    static SquadCandidate eligible(int id, String name, String club, int type, int cost,
                                    double ownership, Map<String, Object> metrics) {
        return new SquadCandidate(id, name, name + " Fictitious", club, club.substring(0, 3),
                type, cost, ownership, "a", null, club.hashCode(), 90, metrics,
                SquadCandidate.Tier.RANKABLE, ELIGIBLE_WINDOW, FULL_EXPOSURE,
                MinutesModel.Eligibility.ELIGIBLE);
    }

    static SquadCandidate withStatus(SquadCandidate source, String status, String news) {
        return new SquadCandidate(source.elementId(), source.webName(), source.fullName(),
                source.club(), source.clubShort(), source.elementType(), source.nowCost(),
                source.ownership(), status, news, source.teamId(), source.expectedMinutes(),
                source.metrics(), source.tier(), source.minutesWindow(), source.exposure(),
                source.eligibility());
    }

    static SquadCandidate ineligible(SquadCandidate source, MinutesModel.Eligibility eligibility) {
        return new SquadCandidate(source.elementId(), source.webName(), source.fullName(),
                source.club(), source.clubShort(), source.elementType(), source.nowCost(),
                source.ownership(), source.status(), source.news(), source.teamId(),
                source.expectedMinutes(), source.metrics(), source.tier(), source.minutesWindow(),
                source.exposure(), eligibility);
    }

    static SquadCandidate body(int id, String name, String club, int type, int cost) {
        return new SquadCandidate(id, name, name + " Fictitious", club, club.substring(0, 3),
                type, cost, 0.1, "a", null, club.hashCode(), 0, Map.of(),
                SquadCandidate.Tier.BODY, null, null,
                MinutesModel.Eligibility.UNKNOWN_CURRENT_EVIDENCE);
    }
}
