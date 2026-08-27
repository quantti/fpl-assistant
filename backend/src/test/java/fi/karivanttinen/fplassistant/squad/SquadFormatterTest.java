package fi.karivanttinen.fplassistant.squad;

import java.util.List;
import java.util.Map;

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
        return new SquadCandidate(id, "Name" + id, "Full Name " + id, club, club.substring(0, 3),
                type, cost, 10.0, "a", null, club.hashCode(), 900, 10,
                Map.of("element_type", type), SquadCandidate.Tier.RANKABLE);
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
                new SquadConstraints.ClubQuota("Arsenal", SquadConstraints.Comparator.EXACTLY, 3),
                new SquadConstraints.ClubQuota("Chelsea", SquadConstraints.Comparator.AT_LEAST, 2)),
                1000, 5, null);
    }

    private static String format(SolvedSquad solved, SquadConstraints constraints) {
        return SquadFormatter.format(solved, constraints, "PRICE PROVENANCE",
                "METRIC PROVENANCE", 12);
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
        assertThat(output).contains("Name13");
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
    void namesTheCaptainAndTheViceCaptain() {
        String output = format(solved(), constraints());

        assertThat(output).contains("Captain").contains("Vice-captain");
    }

    @Test
    void saysHowManyPlayersWereSetAsideAndThatTheyCanBePinned() {
        String output = format(solved(), constraints());

        assertThat(output).contains("12");
        assertThat(output).containsIgnoringCase("name them");
    }

    @Test
    void warnsThatOwnedPlayersArePricedAsPurchasesWhenTheBudgetIsNotTheFromScratchHundred() {
        var wildcard = new SquadConstraints(List.of(), List.of(), List.of(), 1017, 5, null);

        String output = format(solved(), wildcard);

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
}
