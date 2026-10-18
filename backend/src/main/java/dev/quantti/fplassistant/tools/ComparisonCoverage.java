package dev.quantti.fplassistant.tools;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Comparison-population evidence carried by the same snapshot as ranking metrics. */
public record ComparisonCoverage(List<AffectedClub> affectedClubs,
                                 List<PlayerExclusion> playerExclusions,
                                 int capturePlayers, int eligiblePlayers,
                                 boolean assessed, EvidenceStatus evidenceStatus) {

    public enum EvidenceStatus {
        INCOMPLETE,
        PROVISIONAL,
        CHECKED_FINAL,
        LEGACY
    }

    public enum GapReason {
        RELEVANT_ELAPSED_UNFINISHED_FIXTURE,
        COMPLETED_SIDE_MISSING_USABLE_CORE_ROWS,
        UNVERIFIED_OWNED_RECEIPT,
        INCREMENTAL_POPULATION_INCOMPLETE
    }

    public record FixtureGap(int fixtureId, Integer gameweek, GapReason reason) {
        public FixtureGap {
            Objects.requireNonNull(reason);
        }
    }

    public record AffectedClub(int teamId, String teamName, String teamShort,
                               int candidateCount, List<FixtureGap> fixtures) {
        public AffectedClub {
            fixtures = List.copyOf(fixtures);
        }
    }

    public record PlayerExclusion(int elementId, String webName, int teamId,
                                  MinutesModel.Eligibility eligibility,
                                  RankingMetrics.WindowReason windowReason) {
        public PlayerExclusion {
            Objects.requireNonNull(eligibility);
        }
    }

    public ComparisonCoverage {
        affectedClubs = List.copyOf(affectedClubs);
        playerExclusions = List.copyOf(playerExclusions);
        Objects.requireNonNull(evidenceStatus);
    }

    public boolean complete() {
        return assessed && affectedClubs.isEmpty();
    }

    public Map<MinutesModel.Eligibility, Integer> groupedPlayerExclusions() {
        Map<MinutesModel.Eligibility, Integer> counts =
                new EnumMap<>(MinutesModel.Eligibility.class);
        playerExclusions.forEach(row -> counts.merge(row.eligibility(), 1, Integer::sum));
        return Map.copyOf(counts);
    }

    static ComparisonCoverage assemble(List<RankingMetrics.Candidate> candidates,
                                       List<ClubGap> gaps) {
        return assemble(candidates, gaps, true);
    }

    static ComparisonCoverage assemble(List<RankingMetrics.Candidate> candidates,
                                       List<ClubGap> gaps, boolean assessed) {
        return assemble(candidates, gaps, assessed, false);
    }

    static ComparisonCoverage assemble(List<RankingMetrics.Candidate> candidates,
                                       List<ClubGap> gaps, boolean assessed,
                                       boolean provisionalEvidence) {
        return assemble(candidates, gaps, assessed, provisionalEvidence, false);
    }

    static ComparisonCoverage assemble(List<RankingMetrics.Candidate> candidates,
                                       List<ClubGap> gaps, boolean assessed,
                                       boolean provisionalEvidence, boolean checkedFinalEvidence) {
        Map<Integer, RankingMetrics.CapturePlayer> clubs = new LinkedHashMap<>();
        Map<Integer, Integer> clubCandidateCounts = new LinkedHashMap<>();
        List<PlayerExclusion> exclusions = new ArrayList<>();
        int eligible = 0;
        for (RankingMetrics.Candidate candidate : candidates) {
            RankingMetrics.CapturePlayer player = candidate.capture();
            if (player.teamId() != null) {
                clubs.putIfAbsent(player.teamId(), player);
                clubCandidateCounts.merge(player.teamId(), 1, Integer::sum);
            }
            if (candidate.eligibility() == MinutesModel.Eligibility.ELIGIBLE
                    && candidate.blended() != null) {
                eligible++;
                continue;
            }
            MinutesModel.Eligibility eligibility = candidate.eligibility()
                    == MinutesModel.Eligibility.ELIGIBLE
                    ? MinutesModel.Eligibility.UNKNOWN_CURRENT_EVIDENCE
                    : candidate.eligibility();
            exclusions.add(new PlayerExclusion(player.elementId(), player.webName(),
                    player.teamId() == null ? 0 : player.teamId(), eligibility,
                    candidate.current() == null ? null : candidate.current().window().reason()));
        }

        Map<Integer, Map<String, FixtureGap>> byClub = new LinkedHashMap<>();
        for (ClubGap gap : gaps) {
            FixtureGap fixture = new FixtureGap(gap.fixtureId(), gap.gameweek(), gap.reason());
            byClub.computeIfAbsent(gap.clubId(), ignored -> new LinkedHashMap<>())
                    .putIfAbsent(gap.fixtureId() + ":" + gap.reason(), fixture);
        }
        List<AffectedClub> affected = new ArrayList<>();
        byClub.forEach((clubId, fixtures) -> {
            RankingMetrics.CapturePlayer identity = clubs.get(clubId);
            affected.add(new AffectedClub(clubId,
                    identity == null ? "team " + clubId : identity.teamName(),
                    identity == null ? "team " + clubId : identity.teamShort(),
                    clubCandidateCounts.getOrDefault(clubId, 0), fixtures.values().stream()
                            .sorted(Comparator.comparingInt(FixtureGap::fixtureId)
                                    .thenComparing(FixtureGap::reason))
                            .toList()));
        });
        affected.sort(Comparator.comparing(AffectedClub::teamName,
                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        exclusions.sort(Comparator.comparingInt(PlayerExclusion::elementId));
        boolean incomplete = !assessed || !affected.isEmpty();
        EvidenceStatus status = incomplete ? EvidenceStatus.INCOMPLETE
                : checkedFinalEvidence ? EvidenceStatus.CHECKED_FINAL
                : provisionalEvidence ? EvidenceStatus.PROVISIONAL : EvidenceStatus.LEGACY;
        return new ComparisonCoverage(affected, exclusions, candidates.size(), eligible, assessed, status);
    }

    record ClubGap(int clubId, int fixtureId, Integer gameweek, GapReason reason) {
    }
}
