package dev.quantti.fplassistant.squad;

import java.util.List;
import java.util.Map;

import dev.quantti.fplassistant.schedule.HorizonFixtures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reply's contract.
 *
 * <p>The constraint receipt is the important part: a user who asked for three Arsenal players
 * must be able to see, without counting, that they got three. The failure this whole feature
 * replaces was a reply that claimed a constraint it had not met.
 */
class SquadFormatterTest {

    private static SquadCandidate player(int id, String club, int type, int cost) {
        return SquadCandidateTestData.eligible(id, "Name" + id, club, type, cost, 10.0,
                Map.of("element_type", type));
    }

    private static SolvedSquad solved() {
        List<SquadCandidate> squad = List.of(
                player(1, "Arsenal", 1, 50), player(2, "Brentford", 1, 40),
                player(3, "Arsenal", 2, 60), player(4, "Arsenal", 2, 55),
                player(5, "Chelsea", 2, 50), player(6, "Everton", 2, 45),
                player(7, "Fulham", 2, 40),
                player(8, "Chelsea", 3, 90), player(9, "Chelsea", 3, 75),
                player(10, "Liverpool", 3, 70), player(11, "Newcastle", 3, 60),
                player(12, "Everton", 3, 45),
                player(13, "Man City", 4, 150), player(14, "Brighton", 4, 80),
                player(15, "Fulham", 4, 45));
        // 1 GK, 4 DEF, 4 MID, 2 FWD
        List<SquadCandidate> startingXi = List.of(squad.get(0), squad.get(2), squad.get(3),
                squad.get(4), squad.get(5), squad.get(7), squad.get(8), squad.get(9),
                squad.get(10), squad.get(12), squad.get(13));
        List<SquadCandidate> bench = List.of(squad.get(1), squad.get(11), squad.get(6),
                squad.get(14));
        // 955 is the sum of the fifteen prices above. The formatter prints what it is given
        // rather than summing, but a fixture whose total disagrees with its own squad is a
        // fixture nobody can reason about.
        return new SolvedSquad(squad, startingXi, bench, squad.get(12), squad.get(7),
                955, "4-4-2", List.of());
    }

    private static SquadConstraints constraints() {
        return new SquadConstraints(List.of("Name13"), List.of(), List.of(
                new SquadConstraints.ClubQuota("Arsenal", SquadConstraints.Comparator.EXACTLY, 3,
                        "Arsenal".hashCode()),
                new SquadConstraints.ClubQuota("Chelsea", SquadConstraints.Comparator.AT_LEAST, 2,
                        "Chelsea".hashCode())),
                1000, 5, null);
    }

    /** {@code constraints()}'s "Name13" resolved to the real candidate — see {@code player(13, ...)}. */
    private static List<SquadCandidate> resolvedMustInclude() {
        return List.of(player(13, "Man City", 4, 150));
    }

    private static String format(SolvedSquad solved, SquadConstraints constraints) {
        return format(solved, constraints, resolvedMustInclude(), List.of());
    }

    private static String format(SolvedSquad solved, SquadConstraints constraints,
                                 List<SquadCandidate> resolvedIncludes,
                                 List<SquadCandidate> resolvedExcludes) {
        return SquadFormatter.format(solved, constraints, "PRICE PROVENANCE",
                "METRIC PROVENANCE", 12, resolvedIncludes, resolvedExcludes, Map.of(),
                false, null, horizon());
    }

    /** The window every projection below was summed over: GW4-8, all five gameweeks asked for. */
    private static HorizonFixtures.Result horizon() {
        return SquadCandidateTestData.horizonForEveryClubIn(solved().squad());
    }

    @Test
    void carriesBothProvenanceLines() {
        String output = format(solved(), constraints());

        assertThat(output).contains("PRICE PROVENANCE").contains("METRIC PROVENANCE");
    }

    @Test
    void showsEveryConstraintBesideWhatWasAchieved() {
        String output = format(solved(), constraints());

        assertThat(output).contains("Arsenal: 3/3");
        assertThat(output).contains("Chelsea: 3/at least 2");
        assertThat(output).contains("Name13: included");
    }

    @Test
    void printsTheComputedTotalRatherThanAnEstimate() {
        String output = format(solved(), constraints());

        assertThat(output).contains("Total £95.5m");
        // "£4.5m" alone would match two player prices in this fixture; the money left is only
        // proved by the phrase that reports it.
        assertThat(output).contains("£4.5m left");
    }

    @Test
    void namesTheFormationAndTheBenchOrder() {
        String output = format(solved(), constraints());

        assertThat(output).contains("4-4-2");
        assertThat(output).contains("Bench");
        assertThat(output.indexOf("Name2")).isGreaterThan(output.indexOf("Bench"));
    }

    @Test
    void printsObservedDateRangeAndLabelsAShortWindow() {
        String output = format(solved(), constraints());

        assertThat(output).contains("2026-08-01 to 2026-08-03")
                .contains("short window: 3 fixtures");
    }

    @Test
    void namesTheCaptainAndTheViceCaptain() {
        String output = format(solved(), constraints());

        assertThat(output).contains("Captain").contains("Vice-captain");
    }

    @Test
    void saysHowManyPlayersWereSetAsideAndThatTheyCanBePinned() {
        String output = format(solved(), constraints());

        assertThat(output).contains("12 players were left out");
        assertThat(output).containsIgnoringCase("pin does not make them eligible");
    }

    @Test
    void warnsThatOwnedPlayersArePricedAsPurchasesWhenTheBudgetIsNotTheFromScratchHundred() {
        var wildcard = new SquadConstraints(List.of(), List.of(), List.of(), 1017, 5, null);

        String output = format(solved(), wildcard, List.of(), List.of());

        assertThat(output).containsIgnoringCase("selling price");
    }

    @Test
    void saysNothingAboutSellingPricesForAFromScratchBuild() {
        String output = format(solved(), constraints());

        assertThat(output).doesNotContainIgnoringCase("selling price");
    }

    @Test
    void saysWhichStructuralDefaultGaveWay() {
        SolvedSquad relaxed = new SolvedSquad(solved().squad(), solved().startingXi(),
                solved().bench(), solved().captain(), solved().viceCaptain(), 955, "4-4-2",
                List.of(SquadSolver.RELAXED_TEMPLATE));

        String output = format(relaxed, constraints());

        assertThat(output).contains(SquadSolver.RELAXED_TEMPLATE);
    }

    @Test
    void labelsBodyTierPlayersAsNoPlayingRecord() {
        // A body player has no playing history to judge; explain that in the output
        SquadCandidate body =
                SquadCandidateTestData.body(99, "BodyName", "Arsenal", 1, 40);
        List<SquadCandidate> squad = List.of(body, player(2, "Brentford", 1, 40),
                player(3, "Arsenal", 2, 60), player(4, "Arsenal", 2, 55),
                player(5, "Chelsea", 2, 50), player(6, "Everton", 2, 45),
                player(7, "Fulham", 2, 40),
                player(8, "Chelsea", 3, 90), player(9, "Chelsea", 3, 75),
                player(10, "Liverpool", 3, 70), player(11, "Newcastle", 3, 60),
                player(12, "Everton", 3, 45),
                player(13, "Man City", 4, 150), player(14, "Brighton", 4, 80),
                player(15, "Fulham", 4, 45));
        List<SquadCandidate> bench = List.of(body, player(12, "Everton", 3, 45),
                player(7, "Fulham", 2, 40), player(15, "Fulham", 4, 45));
        SolvedSquad withBody = new SolvedSquad(squad, squad.subList(1, 12), bench,
                squad.get(13), squad.get(8), 955, "4-4-2", List.of());

        String output = format(withBody, constraints());

        assertThat(output).contains("bench body, no rankable ability record; unscored");
    }

    @Test
    void markDoubtfulPlayersWithTheirNews() {
        // A doubtful player (d status) is marked in the output with their news
        SquadCandidate doubtful = SquadCandidateTestData.withStatus(
                SquadCandidateTestData.eligible(98, "DoubtfulName", "Arsenal", 2, 50, 10.0,
                        Map.of("element_type", 2)),
                "d", "Ankle injury");
        List<SquadCandidate> squad = List.of(
                player(1, "Arsenal", 1, 50), player(2, "Brentford", 1, 40),
                doubtful, player(4, "Arsenal", 2, 55),
                player(5, "Chelsea", 2, 50), player(6, "Everton", 2, 45),
                player(7, "Fulham", 2, 40),
                player(8, "Chelsea", 3, 90), player(9, "Chelsea", 3, 75),
                player(10, "Liverpool", 3, 70), player(11, "Newcastle", 3, 60),
                player(12, "Everton", 3, 45),
                player(13, "Man City", 4, 150), player(14, "Brighton", 4, 80),
                player(15, "Fulham", 4, 45));
        List<SquadCandidate> startingXi = List.of(squad.get(0), squad.get(2), squad.get(3),
                squad.get(4), squad.get(5), squad.get(7), squad.get(8), squad.get(9),
                squad.get(10), squad.get(12), squad.get(13));
        List<SquadCandidate> bench = List.of(squad.get(1), squad.get(11), squad.get(6),
                squad.get(14));
        SolvedSquad withDoubtful = new SolvedSquad(squad, startingXi, bench, squad.get(12), squad.get(7),
                955, "4-4-2", List.of());

        String output = format(withDoubtful, constraints());

        assertThat(output).contains("DOUBTFUL: Ankle injury");
    }

    @Test
    void marksUnmetConstraintsWithFailureIndicator() {
        // When a constraint is not met, mark it with ✗ instead of ✓
        SquadCandidate other = SquadCandidateTestData.eligible(99, "WrongClub",
                "Manchester United", 1, 40, 10.0, Map.of("element_type", 1));
        List<SquadCandidate> squad = List.of(
                other, player(2, "Brentford", 1, 40),
                player(3, "Arsenal", 2, 60), player(4, "Arsenal", 2, 55),
                player(5, "Chelsea", 2, 50), player(6, "Everton", 2, 45),
                player(7, "Fulham", 2, 40),
                player(8, "Chelsea", 3, 90), player(9, "Chelsea", 3, 75),
                player(10, "Liverpool", 3, 70), player(11, "Newcastle", 3, 60),
                player(12, "Everton", 3, 45),
                player(13, "Man City", 4, 150), player(14, "Brighton", 4, 80),
                player(15, "Fulham", 4, 45));
        List<SquadCandidate> startingXi = List.of(squad.get(0), squad.get(2), squad.get(3),
                squad.get(4), squad.get(5), squad.get(7), squad.get(8), squad.get(9),
                squad.get(10), squad.get(12), squad.get(13));
        List<SquadCandidate> bench = List.of(squad.get(1), squad.get(11), squad.get(6),
                squad.get(14));
        // Only 2 Arsenal players, not the required 3
        SolvedSquad unmet = new SolvedSquad(squad, startingXi, bench, squad.get(12), squad.get(7),
                955, "4-4-2", List.of());

        String output = format(unmet, constraints());

        assertThat(output).contains("Arsenal: 2/3 ✗");
    }

    @Test
    void marksAbsentMustIncludeWithFailure() {
        // When a must-include player is absent, mark with ✗. Resolved to a real candidate with
        // an element id that is not in the squad — the receipt must match on that id, not on the
        // display string, so this also proves the match isn't a coincidental string equality.
        SquadConstraints requireAbsent = new SquadConstraints(List.of("AbsentPlayer"),
                List.of(), List.of(), 1000, 5, null);
        SquadCandidate absent = player(999, "Nowhere", 1, 40);

        String output = format(solved(), requireAbsent, List.of(absent), List.of());

        assertThat(output).contains("AbsentPlayer: included ✗");
    }

    @Test
    void marksPresentMustExcludeWithFailure() {
        // When a must-exclude player is present, mark with ✗. Resolved to the real squad.get(0)
        // candidate by element id.
        SquadConstraints excludeFail = new SquadConstraints(List.of(),
                List.of("Name1"), List.of(), 1000, 5, null);
        SquadCandidate name1 = player(1, "Arsenal", 1, 50);

        String output = format(solved(), excludeFail, List.of(), List.of(name1));

        assertThat(output).contains("Name1: excluded ✗");
    }

    @Test
    void marksOverBudgetWithFailure() {
        // When total cost exceeds budget, mark with ✗
        SquadConstraints tightBudget = new SquadConstraints(List.of(), List.of(),
                List.of(), 950, 5, null);

        String output = format(solved(), tightBudget, List.of(), List.of());

        assertThat(output).contains("Budget: £95.5m of £95.0m ✗");
    }

    @Test
    void printsExpectedPointsPerPlayerAndBenchExpectedMinutes() {
        // I6: the output must carry expected points per player and expected minutes on bench
        // rows, so the model has something to quote when asked "why this squad?" instead of
        // reaching elsewhere or inventing a reason.
        Map<Integer, HorizonScorer.Projection> projections = Map.of(
                1, new HorizonScorer.Projection(4.5, true, false, 2, 0, null),
                2, new HorizonScorer.Projection(1.2, true, false, 1, 0, null));

        String output = SquadFormatter.format(solved(), constraints(), "PRICE PROVENANCE",
                "METRIC PROVENANCE", 12, resolvedMustInclude(), List.of(), projections,
                false, null, horizon());

        // Name1 (element id 1) is in the starting XI.
        assertThat(output).contains("4.50 xPts over 2 fixtures");
        // Name2 (element id 2) is on the bench.
        assertThat(output).contains("1.20 xPts over 1 fixture");
        assertThat(output).contains("90 expected mins/fixture");
    }

    @Test
    void labelsAPartialProjectionInsteadOfPresentingItAsComplete() {
        Map<Integer, HorizonScorer.Projection> projections = Map.of(
                1, new HorizonScorer.Projection(4.5, true, true, 2, 0, null));

        String output = SquadFormatter.format(solved(), constraints(), "PRICE PROVENANCE",
                "METRIC PROVENANCE", 12, resolvedMustInclude(), List.of(), projections,
                false, null, horizon());

        assertThat(output).contains("4.50 xPts")
                .contains("partial: one or more expected components unavailable");
    }

    @Test
    void warnsWhenNoCandidateCouldBeScored() {
        // M4: an unscoreable horizon must be disclosed, not silently presented as a ranked squad.
        String output = SquadFormatter.format(solved(), constraints(), "PRICE PROVENANCE",
                "METRIC PROVENANCE", 12, resolvedMustInclude(), List.of(), Map.of(),
                true, null, horizon());

        assertThat(output).contains("n/a").containsIgnoringCase("unranked");
    }

    @Test
    void saysNothingAboutAnUnscoreableHorizonWhenScoringWorked() {
        String output = format(solved(), constraints());

        assertThat(output).doesNotContainIgnoringCase("unranked");
    }

    private static String withProjections(Map<Integer, HorizonScorer.Projection> projections,
                                          HorizonFixtures.Result horizon) {
        return SquadFormatter.format(solved(), constraints(), "PRICE PROVENANCE",
                "METRIC PROVENANCE", 12, resolvedMustInclude(), List.of(), projections,
                false, null, horizon);
    }

    @Test
    void namesTheEditableRangeAndHowManyCalendarGameweeksItCovers() {
        assertThat(format(solved(), constraints()))
                .contains("Horizon: GW4–8 (5 calendar gameweeks).");
    }

    @Test
    void disclosesThatTheHorizonRanOutOfSeasonBeforeTheGameweeksAsked() {
        HorizonFixtures.Result truncated = new HorizonFixtures.Result(
                java.time.Instant.parse("2026-09-09T12:00:00Z"), java.util.Optional.empty(), 5,
                HorizonFixtures.Status.KNOWN, null, 37, 38, Map.of());

        assertThat(withProjections(Map.of(), truncated))
                .contains("Horizon: GW37–38 (2 calendar gameweeks); 5 requested, truncated to 2");
    }

    /**
     * The distinction the whole task turns on, asserted on a bench row rather than a footer: a
     * club that plays nothing has a real zero, a club whose window could not be read has none.
     */
    @Test
    void tellsABlankedClubApartFromAnUnreadableOneOnEveryBenchRow() {
        // Name2 (id 2) and Name12 (id 12) are both on the bench of solved().
        Map<Integer, HorizonScorer.Projection> projections = Map.of(
                2, new HorizonScorer.Projection(0, true, false, 0, 0, null),
                12, new HorizonScorer.Projection(0, false, false, 0, 0,
                        HorizonFixtures.UnknownReason.UNASSIGNED_FIXTURE));

        String output = withProjections(projections, horizon());
        String bench = output.substring(output.indexOf("Bench,"));

        assertThat(bench).contains("0.00 xPts (no fixtures in GW4–8)")
                .contains("xPts n/a (horizon unknown: unassigned fixture)");
    }

    @Test
    void aRankableRowWithNoProjectionAtAllStaysTheOldUnavailableWording() {
        assertThat(format(solved(), constraints()))
                .contains("xPts n/a (projection unavailable)")
                .doesNotContain("horizon unknown");
    }

    /**
     * {@code SquadTools} refuses before it ever formats a horizon it could not derive, but the
     * reply must still be able to say so rather than print a range that does not exist.
     */
    @Test
    void saysSoWhenThereIsNoRangeToName() {
        Map<Integer, HorizonScorer.Projection> unknown = Map.of(
                1, new HorizonScorer.Projection(0, false, false, 0, 0,
                        HorizonFixtures.UnknownReason.NO_EDITABLE_GAMEWEEK));

        String output = withProjections(unknown, SquadCandidateTestData
                .horizonThatCannotBeDerived(HorizonFixtures.UnknownReason.NO_EDITABLE_GAMEWEEK));

        assertThat(output).contains("Horizon: unknown (no editable gameweek)")
                .contains("xPts n/a (horizon unknown: no editable gameweek)");
    }

    @Test
    void saysWhichLegsWereScoredAtTheNeutralFdr() {
        Map<Integer, HorizonScorer.Projection> projections = Map.of(
                1, new HorizonScorer.Projection(8.0, true, false, 2, 1, null));

        assertThat(withProjections(projections, horizon()))
                .contains("8.00 xPts over 2 fixtures")
                .contains("FDR n/a for 1 fixture, neutral 3 assumed");
    }
}
