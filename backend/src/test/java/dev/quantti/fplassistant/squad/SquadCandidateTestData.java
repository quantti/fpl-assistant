package dev.quantti.fplassistant.squad;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import dev.quantti.fplassistant.schedule.HorizonFixtures;
import dev.quantti.fplassistant.tools.MinutesModel;
import dev.quantti.fplassistant.tools.RankingMetrics;
import dev.quantti.fplassistant.tools.SeasonBlend;

final class SquadCandidateTestData {

    /** The window every hand-built horizon in these tests describes. */
    static final int HORIZON_START = 4;
    static final int HORIZON_END = 8;
    static final String HORIZON_RANGE = "GW4–8";

    private static final Instant AS_OF = Instant.parse("2026-09-09T12:00:00Z");
    private static final Instant KICKOFF = Instant.parse("2026-09-13T14:00:00Z");
    private static final int REQUESTED_GAMEWEEKS = 5;

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

    static Set<Integer> clubsOf(Collection<SquadCandidate> pool) {
        return pool.stream().map(SquadCandidate::teamId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** A known {@value #HORIZON_RANGE} window in which every club of the pool plays once. */
    static HorizonFixtures.Result horizonForEveryClubIn(Collection<SquadCandidate> pool) {
        return horizonWhere(clubsOf(pool), Set.of());
    }

    /**
     * A known {@value #HORIZON_RANGE} window: each club in {@code playing} has one fixture and
     * each club in {@code blanked} has a known window holding none. Any other id is outside the
     * captured schedule entirely.
     */
    static HorizonFixtures.Result horizonWhere(Collection<Integer> playing,
                                               Collection<Integer> blanked) {
        Map<Integer, HorizonFixtures.ClubWindow> clubs = new HashMap<>();
        int fixtureId = 1;
        for (int club : playing) {
            clubs.put(club, new HorizonFixtures.ClubWindow(club, List.of(
                    new HorizonFixtures.Fixture(fixtureId++, HORIZON_START, true, 0, KICKOFF, 3)),
                    null));
        }
        for (int club : blanked) {
            clubs.put(club, new HorizonFixtures.ClubWindow(club, List.of(), null));
        }
        return new HorizonFixtures.Result(AS_OF, Optional.empty(), REQUESTED_GAMEWEEKS,
                HorizonFixtures.Status.KNOWN, null, HORIZON_START, HORIZON_END, clubs);
    }

    /** A horizon the captured schedule could not supply at all. */
    static HorizonFixtures.Result horizonThatCannotBeDerived(
            HorizonFixtures.UnknownReason reason) {
        HorizonFixtures.Status status = reason == HorizonFixtures.UnknownReason.NO_EDITABLE_GAMEWEEK
                ? HorizonFixtures.Status.NO_EDITABLE_GAMEWEEK : HorizonFixtures.Status.UNKNOWN;
        return new HorizonFixtures.Result(AS_OF, Optional.empty(), REQUESTED_GAMEWEEKS, status,
                reason, null, null, Map.of());
    }
}
