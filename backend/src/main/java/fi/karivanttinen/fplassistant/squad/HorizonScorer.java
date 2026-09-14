package fi.karivanttinen.fplassistant.squad;

import java.util.Optional;
import java.util.OptionalDouble;

import fi.karivanttinen.fplassistant.schedule.HorizonFixtures;
import fi.karivanttinen.fplassistant.tools.ClubRates;
import fi.karivanttinen.fplassistant.tools.ExpectedPoints;

/**
 * Turns the shared per-fixture expectation into what a player is worth over the fixtures his club
 * actually plays in the editable gameweek window. Current XI admission is a separate hard
 * observed-history rule; expected minutes shape the point components but never grant permission to
 * start.
 *
 * <p>The window itself is {@link HorizonFixtures}' — the same derivation the pick tools rank on,
 * over the same captured schedule that supplied the current-role evidence. This class used to
 * derive its own weekly window, which is how the squad builder and the pick lists could disagree
 * about which fixtures a club plays.
 *
 * <p>The horizon is <b>summed, not averaged</b>. A double gameweek is literally two fixtures and
 * comes out right without special handling; a blank is no fixture and comes out as a real zero,
 * which is not the same statement as "unknown".
 *
 * <p>FDR scales the attacking term only. The clean sheet is projected per fixture from both
 * clubs' expected-goal rates and the venue ({@link ClubRates}, KAR-30), so scaling it by FDR as
 * well would count the opponent twice; appearance and DefCon are not fixture-scaled.
 */
public final class HorizonScorer {

    /**
     * @param optimizerPoints the summed per-fixture expectation; zero whenever the projection is
     *        unavailable, which is an optimiser weight and never a printed forecast
     * @param available whether the number means anything. A known window holding no fixtures is
     *        available with zero — a real blank — while an unknown window is not available at all
     * @param partial whether an expected component was unavailable inside a counted fixture
     * @param fixtures how many countable fixtures the sum ran over
     * @param neutralFdrLegs how many of those carried no usable FDR and were scored at the
     *        neutral 3, so the reply can say so rather than implying every leg was rated
     * @param horizonReason why the club's window could not be established, or null when it could
     */
    public record Projection(double optimizerPoints, boolean available, boolean partial,
                             int fixtures, int neutralFdrLegs,
                             HorizonFixtures.UnknownReason horizonReason) {
    }

    /** Score multiplier per FDR step away from a neutral 3. */
    public static final double FDR_SWING = 0.1;

    static final int NEUTRAL_FDR = 3;

    /** No rankable ability record at all: unscored, and never a zero forecast. */
    private static final Projection UNSCOREABLE = new Projection(0, false, false, 0, 0, null);

    private HorizonScorer() {
    }

    /** The blended per-season rate, never a ratio of added cross-season evidence counts. */
    public static double expectedMinutes(SquadCandidate candidate) {
        return candidate.exposure() == null ? 0 : candidate.exposure().expectedMinutes();
    }

    public static double fdrAdjust(int fdr) {
        return 1 + (NEUTRAL_FDR - fdr) * FDR_SWING;
    }

    /**
     * Expected FPL points across the club's countable fixtures. A body scores nothing by
     * definition — he is bought for his price, and asserting anything else about him is what the
     * tier exists to prevent.
     *
     * <p>Three outcomes, kept apart on purpose: no rankable record (unscored), an unknown window
     * (unscored, with the schedule's own reason), and a known window that happens to be empty
     * (a genuine zero over zero fixtures).
     */
    public static Projection projection(SquadCandidate candidate,
                                        HorizonFixtures.ClubWindow window,
                                        ClubRates.Rates rates) {
        if (candidate.tier() == SquadCandidate.Tier.BODY || candidate.exposure() == null) {
            return UNSCOREABLE;
        }
        if (!window.known()) {
            return ExpectedPoints.perFixture(candidate.metrics(), candidate.exposure(),
                    OptionalDouble.empty()).isEmpty()
                    ? UNSCOREABLE
                    : new Projection(0, false, false, 0, 0, window.unknownReason());
        }
        double total = 0;
        int neutralFdrLegs = 0;
        boolean partial = false;
        for (HorizonFixtures.Fixture fixture : window.fixtures()) {
            OptionalDouble goalsAgainst = ClubRates.projectedGoalsAgainst(rates,
                    candidate.teamId(), fixture.opponentTeamId(), fixture.home());
            Optional<ExpectedPoints.PerFixture> perFixture = ExpectedPoints.perFixture(
                    candidate.metrics(), candidate.exposure(), goalsAgainst);
            if (perFixture.isEmpty()) {
                return UNSCOREABLE;
            }
            ExpectedPoints.PerFixture components = perFixture.orElseThrow();
            boolean rated = fixture.hasValidDifficulty();
            if (!rated) {
                neutralFdrLegs++;
            }
            total += components.appearance() + components.defensiveContribution()
                    + (components.cleanSheet() == null ? 0 : components.cleanSheet())
                    + (components.attack() == null ? 0
                            : components.attack() * fdrAdjust(
                                    rated ? fixture.difficulty() : NEUTRAL_FDR));
            partial |= components.attack() == null || components.cleanSheet() == null
                    || components.partialDefcon();
        }
        return new Projection(total, true, partial, window.count(), neutralFdrLegs, null);
    }

    public static double score(SquadCandidate candidate, HorizonFixtures.ClubWindow window,
                               ClubRates.Rates rates) {
        return projection(candidate, window, rates).optimizerPoints();
    }
}
