package fi.karivanttinen.fplassistant.squad;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import fi.karivanttinen.fplassistant.schedule.ScheduleSource;
import fi.karivanttinen.fplassistant.tools.ExpectedPoints;
import org.springframework.stereotype.Component;

/**
 * Turns the shared per-fixture expectation into what a player is worth over the next N fixtures.
 * Current XI admission is a separate hard observed-history rule; expected minutes shape the
 * point components but never grant permission to start.
 *
 * <p>The horizon is <b>summed, not averaged</b>. A double gameweek is literally two fixtures and
 * comes out right without special handling; a blank is no fixture and comes out as nothing.
 *
 * <p>The fixture adjustment is coarse: one multiplier on the whole score, where a fixture should
 * move the clean-sheet term more than the attacking terms. Issue #47 replaces it with a team
 * xG/xGC projection.
 */
@Component
public class HorizonScorer {

    public record Projection(double optimizerPoints, boolean available, boolean partial) {
    }

    /** Score multiplier per FDR step away from a neutral 3. */
    public static final double FDR_SWING = 0.1;

    private static final int NEUTRAL_FDR = 3;

    /** The blended per-season rate, never a ratio of added cross-season evidence counts. */
    public static double expectedMinutes(SquadCandidate candidate) {
        return candidate.exposure() == null ? 0 : candidate.exposure().expectedMinutes();
    }

    public static double fdrAdjust(int fdr) {
        return 1 + (NEUTRAL_FDR - fdr) * FDR_SWING;
    }

    /**
     * Expected FPL points across the given fixtures. A body scores nothing by definition — he is
     * bought for his price, and asserting anything else about him is what the tier exists to
     * prevent.
     */
    public static Projection projection(SquadCandidate candidate,
                                        List<Integer> fixtureDifficulties) {
        if (candidate.tier() == SquadCandidate.Tier.BODY || candidate.exposure() == null
                || fixtureDifficulties.isEmpty()) {
            return new Projection(0, false, false);
        }
        var perFixture = ExpectedPoints.perFixture(candidate.metrics(), candidate.exposure());
        if (perFixture.isEmpty()) {
            return new Projection(0, false, false);
        }
        ExpectedPoints.PerFixture components = perFixture.orElseThrow();
        double total = 0;
        for (int fdr : fixtureDifficulties) {
            total += components.points() * fdrAdjust(fdr);
        }
        return new Projection(total, true,
                components.cleanSheet() == null || components.partialDefcon());
    }

    public static double score(SquadCandidate candidate, List<Integer> fixtureDifficulties) {
        return projection(candidate, fixtureDifficulties).optimizerPoints();
    }

    /**
     * Each club's difficulty across the next {@code horizon} gameweeks from the same immutable
     * FPL schedule view that supplied the current-role evidence. Doubles contribute twice and
     * blanks contribute nothing; an unresolved fixture is excluded until FPL assigns a round.
     */
    public Map<Integer, List<Integer>> fixtureDifficulties(
            ScheduleSource.View view, Instant asOf, int horizon) {
        if (!view.completeness().complete() || view.snapshot().isEmpty()) {
            return Map.of();
        }

        Set<Integer> roundsWithUnstartedFixtures = view.fixtures().stream()
                .filter(fixture -> fixture.gameweek() != null && !fixture.started()
                        && !fixture.finished())
                .map(ScheduleSource.FixtureRow::gameweek)
                .collect(Collectors.toSet());
        Integer from = view.events().stream()
                .filter(event -> !event.deadline().isAfter(asOf)
                        && roundsWithUnstartedFixtures.contains(event.id()))
                .map(ScheduleSource.EventRow::id)
                .max(Integer::compareTo)
                .orElseGet(() -> view.events().stream()
                        .filter(event -> event.deadline().isAfter(asOf))
                        .map(ScheduleSource.EventRow::id)
                        .min(Integer::compareTo)
                        .orElse(null));
        if (from == null) {
            return Map.of();
        }
        int upTo = from + horizon;

        List<ScheduleSource.FixtureRow> fixtures = view.fixtures().stream()
                .filter(fixture -> fixture.gameweek() != null
                        && fixture.gameweek() >= from && fixture.gameweek() < upTo
                        && !fixture.started() && !fixture.finished())
                .sorted(Comparator.comparing(ScheduleSource.FixtureRow::gameweek)
                        .thenComparing(fixture -> fixture.kickoff() == null
                                ? Instant.MAX : fixture.kickoff())
                        .thenComparing(ScheduleSource.FixtureRow::id))
                .toList();

        Map<Integer, List<Integer>> byTeam = new HashMap<>();
        for (ScheduleSource.FixtureRow fixture : fixtures) {
            byTeam.computeIfAbsent(fixture.homeTeamId(), key -> new ArrayList<>())
                    .add(fixture.homeDifficulty() == null
                            ? NEUTRAL_FDR : fixture.homeDifficulty());
            byTeam.computeIfAbsent(fixture.awayTeamId(), key -> new ArrayList<>())
                    .add(fixture.awayDifficulty() == null
                            ? NEUTRAL_FDR : fixture.awayDifficulty());
        }
        return byTeam;
    }
}
