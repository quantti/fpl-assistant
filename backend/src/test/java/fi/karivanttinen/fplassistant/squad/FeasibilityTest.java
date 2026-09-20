package fi.karivanttinen.fplassistant.squad;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import fi.karivanttinen.fplassistant.schedule.HorizonFixtures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every way a request can be impossible, and the sentence each one earns.
 *
 * <p>"No solution" is not an answer a user can act on. Each check here exists so the refusal
 * names the constraint that binds and, where money is involved, the amount.
 */
class FeasibilityTest {

    private static SquadCandidate player(int id, String name, String club, int type, int cost) {
        return SquadCandidateTestData.eligible(id, name, club, type, cost, 5.0,
                Map.of("element_type", type, "xg90", 0.0, "xa90", 0.0, "pts90", 0.0));
    }

    /** A pool wide enough that nothing fails for lack of players unless a test means it to. */
    private static List<SquadCandidate> deepPool() {
        List<SquadCandidate> pool = new ArrayList<>();
        int id = 1;
        String[] clubs = {"Ashfield", "Barnwell", "Coleridge", "Dunmore", "Eastvale",
                "Fallowfield", "Granton", "Harewood"};
        for (String club : clubs) {
            for (int type = 1; type <= 4; type++) {
                for (int n = 0; n < 3; n++) {
                    pool.add(player(id++, club + type + n, club, type, 40 + n));
                }
            }
        }
        return pool;
    }

    private static SquadConstraints constraints(List<SquadConstraints.ClubQuota> quotas,
                                                int budget) {
        return new SquadConstraints(List.of(), List.of(), quotas, budget, 5, null);
    }

    /**
     * The ordinary case: every club of the pool plays at least once in the horizon, so only the
     * observed-history gate can shrink the starting capacity. Tests about the schedule itself
     * call {@link Feasibility#check} directly with the window they mean.
     */
    private static Optional<String> check(List<SquadCandidate> pool,
                                          SquadConstraints constraints,
                                          List<SquadCandidate> pinned) {
        return Feasibility.check(pool, constraints, pinned, SquadCandidateTestData.clubsOf(pool),
                SquadCandidateTestData.horizonForEveryClubIn(pool));
    }

    private static Optional<String> check(List<SquadCandidate> pool,
                                          SquadConstraints constraints,
                                          List<SquadCandidate> pinned,
                                          List<SquadCandidate> excluded) {
        return Feasibility.check(pool, constraints, pinned, excluded,
                SquadCandidateTestData.clubsOf(pool),
                SquadCandidateTestData.horizonForEveryClubIn(pool));
    }

    /**
     * A resolved quota — {@code teamId} follows {@link #player} and {@link #deepPool}'s own
     * {@code club.hashCode()} convention, the way {@code SquadTools.resolveClub} would have
     * resolved it against a real pool. {@code clubsHaveEnoughPlayers} and the pins-vs-quota
     * check both match candidates by this id, never by the display string.
     */
    private static SquadConstraints.ClubQuota quota(String club,
                                                     SquadConstraints.Comparator comparator,
                                                     int count) {
        return new SquadConstraints.ClubQuota(club, comparator, count, club.hashCode());
    }

    @Test
    void passesAPlainRequest() {
        Optional<String> refusal = check(deepPool(),
                constraints(List.of(), 1000), List.of());

        assertThat(refusal).isEmpty();
    }

    @Test
    void refusesAQuotaAboveTheGamesOwnThreePerClubLimit() {
        Optional<String> refusal = check(deepPool(), constraints(List.of(
                quota("Ashfield", SquadConstraints.Comparator.EXACTLY, 4)),
                1000), List.of());

        assertThat(refusal).isPresent();
        assertThat(refusal.get()).contains("Ashfield").contains("3");
    }

    @Test
    void refusesContradictoryQuotasForOneClub() {
        Optional<String> refusal = check(deepPool(), constraints(List.of(
                quota("Ashfield", SquadConstraints.Comparator.AT_LEAST, 3),
                quota("Ashfield", SquadConstraints.Comparator.AT_MOST, 2)),
                1000), List.of());

        assertThat(refusal).isPresent();
        assertThat(refusal.get()).contains("Ashfield");
    }

    @Test
    void refusesWhenClubMinimumsCannotFitInFifteenPlaces() {
        List<SquadConstraints.ClubQuota> quotas = new ArrayList<>();
        String[] clubs = {"Ashfield", "Barnwell", "Coleridge", "Dunmore", "Eastvale", "Fallowfield"};
        for (String club : clubs) {
            quotas.add(quota(club, SquadConstraints.Comparator.EXACTLY, 3));
        }
        // Six clubs at exactly three is eighteen players.
        Optional<String> refusal = check(deepPool(), constraints(quotas, 1000),
                List.of());

        assertThat(refusal).isPresent();
        assertThat(refusal.get()).contains("18").contains("15");
    }

    @Test
    void refusesWhenPinsExceedTheClubLimit() {
        List<SquadCandidate> pinned = List.of(
                player(901, "One", "Ashfield", 2, 50),
                player(902, "Two", "Ashfield", 3, 50),
                player(903, "Three", "Ashfield", 3, 50),
                player(904, "Four", "Ashfield", 4, 50));

        Optional<String> refusal = check(deepPool(), constraints(List.of(), 1000),
                pinned);

        assertThat(refusal).isPresent();
        assertThat(refusal.get()).contains("Ashfield").contains("4");
    }

    @Test
    void refusesWhenPinsExceedAPositionQuota() {
        List<SquadCandidate> pinned = List.of(
                player(901, "Keeper1", "Ashfield", 1, 45),
                player(902, "Keeper2", "Barnwell", 1, 45),
                player(903, "Keeper3", "Coleridge", 1, 45));

        Optional<String> refusal = check(deepPool(), constraints(List.of(), 1000),
                pinned);

        assertThat(refusal).isPresent();
        assertThat(refusal.get()).contains("goalkeeper").contains("2");
    }

    @Test
    void refusesWhenThePinsAndTheCheapestCompletionExceedTheBudgetAndSaysByHowMuch() {
        List<SquadCandidate> pinned = List.of(
                player(901, "Rich1", "Ashfield", 4, 150),
                player(902, "Rich2", "Barnwell", 4, 150),
                player(903, "Rich3", "Coleridge", 4, 150));

        // Twelve more at £4.0m is 480; the pins are 450; 930 fits 1000 but not 800.
        Optional<String> refusal = check(deepPool(), constraints(List.of(), 800),
                pinned);

        assertThat(refusal).isPresent();
        assertThat(refusal.get()).contains("£").contains("13.0m");
    }

    @Test
    void countsTheActualPoolRatherThanAssumingAClubIsDeep() {
        List<SquadCandidate> thin = new ArrayList<>(deepPool());
        thin.removeIf(c -> c.club().equals("Ashfield") && c.elementId() % 2 == 0);
        long remaining = thin.stream().filter(c -> c.club().equals("Ashfield")).count();

        Optional<String> refusal = check(thin, constraints(List.of(
                quota("Ashfield", SquadConstraints.Comparator.EXACTLY, 3)), 1000), List.of());

        // The thinned club still has players; this asserts the check reads the pool, not a
        // constant. With six or more left it must pass.
        assertThat(remaining).isGreaterThanOrEqualTo(3);
        assertThat(refusal).isEmpty();
    }

    @Test
    void refusesWhenAClubIsThinnedBelowItsQuota() {
        List<SquadCandidate> thin = new ArrayList<>(deepPool());
        // Remove all but one player from Ashfield.
        thin.removeIf(c -> c.club().equals("Ashfield") && c.elementId() > 1);
        long remaining = thin.stream().filter(c -> c.club().equals("Ashfield")).count();

        Optional<String> refusal = check(thin, constraints(List.of(
                quota("Ashfield", SquadConstraints.Comparator.EXACTLY, 2)), 1000), List.of());

        assertThat(remaining).isEqualTo(1);
        assertThat(refusal).isPresent();
        assertThat(refusal.get()).contains("Ashfield").contains("1");
    }

    @Test
    void refusesAPlayerBothPinnedAndExcluded() {
        // Resolving "Salah" for mustInclude and for mustExclude against the same pool returns
        // the same candidate, exactly as SquadTools.resolve would.
        SquadCandidate contradiction = player(901, "Salah", "Ashfield", 3, 130);

        Optional<String> refusal = check(deepPool(),
                new SquadConstraints(List.of(), List.of("Salah"), List.of(), 1000, 5, null),
                List.of(contradiction), List.of(contradiction));

        assertThat(refusal).isPresent();
        assertThat(refusal.get()).contains("Salah");
    }

    @Test
    void refusesAPinCollidingWithAClubQuota() {
        // I4: "no Arsenal except Saka" is an ordinary request. Pinning one Arsenal player while
        // also asking for zero Arsenal players must refuse and name both the pin and the quota —
        // not exhaust the relaxation ladder and answer with the generic "cannot all be met".
        SquadCandidate pin = player(901, "Saka", "Ashfield", 3, 80);

        Optional<String> refusal = check(deepPool(), constraints(List.of(
                quota("Ashfield", SquadConstraints.Comparator.EXACTLY, 0)), 1000),
                List.of(pin));

        assertThat(refusal).isPresent();
        assertThat(refusal.get()).contains("Saka").contains("Ashfield");
    }

    @Test
    void refusesWhenPoolLacksEnoughPlayersOfAPosition() {
        List<SquadCandidate> thin = new ArrayList<>(deepPool());
        // Remove all but one goalkeeper from the pool.
        thin.removeIf(c -> c.elementType() == 1 && c.elementId() > 1);
        long remaining = thin.stream().filter(c -> c.elementType() == 1).count();

        Optional<String> refusal = check(thin, constraints(List.of(), 1000),
                List.of());

        assertThat(remaining).isEqualTo(1);
        assertThat(refusal).isPresent();
        assertThat(refusal.get()).contains("goalkeeper").contains("1");
    }

    private static SquadConstraints withFormation(SquadConstraints.Formation formation) {
        return new SquadConstraints(List.of(), List.of(), List.of(), 1000, 5, formation);
    }

    @Test
    void passesALegalFormation() {
        Optional<String> refusal = check(deepPool(),
                withFormation(new SquadConstraints.Formation(3, 4, 3)), List.of());

        assertThat(refusal).isEmpty();
    }

    @Test
    void refusesWhenEligiblePlayersCannotFormAnEleven() {
        List<SquadCandidate> pool = new ArrayList<>(deepPool());
        for (int index = 0; index < pool.size(); index++) {
            if (pool.get(index).elementType() == 1) {
                pool.set(index, SquadCandidateTestData.ineligible(pool.get(index),
                        fi.karivanttinen.fplassistant.tools.MinutesModel.Eligibility.UNKNOWN_CURRENT_EVIDENCE));
            }
        }

        Optional<String> refusal = check(pool, constraints(List.of(), 1000), List.of());

        assertThat(refusal).hasValueSatisfying(message -> assertThat(message)
                .contains("goalkeeper").contains("complete current evidence").contains("budget"));
    }

    @Test
    void forcedFormationRequiresEnoughEligiblePlayersInEveryPosition() {
        List<SquadCandidate> pool = new ArrayList<>(deepPool());
        int leftEligible = 0;
        for (int index = 0; index < pool.size(); index++) {
            if (pool.get(index).elementType() == 4 && leftEligible++ >= 1) {
                pool.set(index, SquadCandidateTestData.ineligible(pool.get(index),
                        fi.karivanttinen.fplassistant.tools.MinutesModel.Eligibility.UNKNOWN_CURRENT_EVIDENCE));
            }
        }

        Optional<String> refusal = check(pool,
                withFormation(new SquadConstraints.Formation(3, 4, 3)), List.of());

        assertThat(refusal).hasValueSatisfying(message -> assertThat(message)
                .contains("3-4-3").contains("3 forwards").contains("only 1"));
    }

    /** Club ids of the pool, minus the ones this test means to strand without a fixture. */
    private static Set<Integer> playableExcept(List<SquadCandidate> pool, String... clubs) {
        Set<Integer> playable =
                new LinkedHashSet<>(SquadCandidateTestData.clubsOf(pool));
        for (String club : clubs) {
            playable.remove(club.hashCode());
        }
        return playable;
    }

    /**
     * The starting capacity counts both gates at once. Only one club plays, so a 3-4-3 cannot be
     * filled and the refusal must name the fixture condition.
     */
    @Test
    void forcedFormationRequiresEnoughEligiblePlayersAtClubsThatActuallyPlay() {
        List<SquadCandidate> pool = deepPool();
        Set<Integer> onlyAshfield = Set.of("Ashfield".hashCode());

        Optional<String> refusal = Feasibility.check(pool,
                withFormation(new SquadConstraints.Formation(3, 4, 3)), List.of(),
                onlyAshfield, SquadCandidateTestData.horizonForEveryClubIn(pool));

        assertThat(refusal).hasValueSatisfying(message -> assertThat(message)
                .contains("3-4-3").contains("4 midfielders").contains("only 3")
                .contains("GW4–8").contains("complete current evidence")
                .contains("neither a pin nor a higher budget can waive it"));
    }

    @Test
    void refusesWhenEveryClubIsBlankedAcrossTheWindow() {
        List<SquadCandidate> pool = deepPool();

        Optional<String> refusal = Feasibility.check(pool, constraints(List.of(), 1000),
                List.of(), Set.of(),
                SquadCandidateTestData.horizonWhere(List.of(),
                        SquadCandidateTestData.clubsOf(pool)));

        assertThat(refusal).hasValueSatisfying(message -> assertThat(message)
                .contains("goalkeeper").contains("GW4–8").contains("complete current evidence")
                .contains("higher budget"));
    }

    /**
     * A blanked club is a finding about the schedule, not about the player: the players it
     * strands must not be counted towards a formation the solver will then refuse to fill.
     */
    @Test
    void aBlankedClubsPlayersDoNotCountTowardsTheStartingCapacity() {
        List<SquadCandidate> pool = deepPool();

        assertThat(check(pool, withFormation(new SquadConstraints.Formation(5, 4, 1)), List.of()))
                .as("all eight clubs playing, this formation fits").isEmpty();
        assertThat(Feasibility.check(pool,
                withFormation(new SquadConstraints.Formation(5, 4, 1)), List.of(),
                playableExcept(pool, "Ashfield", "Barnwell", "Coleridge",
                        "Dunmore", "Eastvale", "Fallowfield", "Granton"),
                SquadCandidateTestData.horizonForEveryClubIn(pool)))
                .as("one club left playing cannot supply five defenders")
                .hasValueSatisfying(message -> assertThat(message).contains("5 defenders"));
    }

    @Test
    void refusesWhenTheCapturedScheduleCannotSupplyAHorizonAtAllAndSaysTheCountIsUnknown() {
        Optional<String> refusal = Feasibility.check(deepPool(), constraints(List.of(), 1000),
                List.of(), Set.of(), SquadCandidateTestData.horizonThatCannotBeDerived(
                        HorizonFixtures.UnknownReason.SCHEDULE_INCOMPLETE));

        assertThat(refusal).hasValueSatisfying(message -> assertThat(message)
                .contains("cannot supply a gameweek horizon (schedule incomplete)")
                .contains("unknown rather than zero")
                .contains("complete current evidence").contains("higher budget supplies neither")
                .doesNotContain("only 0"));
    }

    @Test
    void refusesAfterTheFinalDeadlineByNamingTheMissingGameweekRatherThanTheSquad() {
        Optional<String> refusal = Feasibility.check(deepPool(), constraints(List.of(), 1000),
                List.of(), Set.of(), SquadCandidateTestData.horizonThatCannotBeDerived(
                        HorizonFixtures.UnknownReason.NO_EDITABLE_GAMEWEEK));

        assertThat(refusal).hasValueSatisfying(message -> assertThat(message)
                .contains("No editable gameweek remains in the captured schedule")
                .contains("unknown rather than zero"));
    }

    @Test
    void refusesAFormationThatDoesNotSumToTenOutfieldPlayers() {
        Optional<String> refusal = check(deepPool(),
                withFormation(new SquadConstraints.Formation(4, 4, 4)), List.of());

        assertThat(refusal).isPresent();
        assertThat(refusal.get()).contains("4-4-4").contains("12").contains("10");
    }

    @Test
    void refusesAFormationWithTooFewDefenders() {
        Optional<String> refusal = check(deepPool(),
                withFormation(new SquadConstraints.Formation(2, 5, 3)), List.of());

        assertThat(refusal).isPresent();
        assertThat(refusal.get()).contains("2-5-3").contains("defenders");
    }

    @Test
    void refusesAFormationWithTooManyForwards() {
        Optional<String> refusal = check(deepPool(),
                withFormation(new SquadConstraints.Formation(3, 2, 5)), List.of());

        assertThat(refusal).isPresent();
        assertThat(refusal.get()).contains("3-2-5").contains("forwards");
    }

    @Test
    void refusesAFormationWithTooManyMidfielders() {
        // Legal on their own (def at its floor, fwd at its floor), but 10 outfield players
        // leaves 6 for midfield — one more than a squad holds in total.
        Optional<String> refusal = check(deepPool(),
                withFormation(new SquadConstraints.Formation(3, 6, 1)), List.of());

        assertThat(refusal).isPresent();
        assertThat(refusal.get()).contains("3-6-1").contains("midfielders");
    }
}
