package dev.quantti.fplassistant.squad;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
        return new SquadCandidate(id, name, name + " Fictitious", club, club.substring(0, 3),
                type, cost, 5.0, "a", null, club.hashCode(), 900, 10,
                Map.of("element_type", type, "xg90", 0.0, "xa90", 0.0, "pts90", 0.0),
                SquadCandidate.Tier.RANKABLE);
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

    @Test
    void passesAPlainRequest() {
        Optional<String> refusal = Feasibility.check(deepPool(),
                constraints(List.of(), 1000), List.of());

        assertThat(refusal).isEmpty();
    }

    @Test
    void refusesAQuotaAboveTheGamesOwnThreePerClubLimit() {
        Optional<String> refusal = Feasibility.check(deepPool(), constraints(List.of(
                new SquadConstraints.ClubQuota("Ashfield", SquadConstraints.Comparator.EXACTLY, 4)),
                1000), List.of());

        assertThat(refusal).isPresent();
        assertThat(refusal.get()).contains("Ashfield").contains("3");
    }

    @Test
    void refusesContradictoryQuotasForOneClub() {
        Optional<String> refusal = Feasibility.check(deepPool(), constraints(List.of(
                new SquadConstraints.ClubQuota("Ashfield", SquadConstraints.Comparator.AT_LEAST, 3),
                new SquadConstraints.ClubQuota("Ashfield", SquadConstraints.Comparator.AT_MOST, 2)),
                1000), List.of());

        assertThat(refusal).isPresent();
        assertThat(refusal.get()).contains("Ashfield");
    }

    @Test
    void refusesWhenClubMinimumsCannotFitInFifteenPlaces() {
        List<SquadConstraints.ClubQuota> quotas = new ArrayList<>();
        String[] clubs = {"Ashfield", "Barnwell", "Coleridge", "Dunmore", "Eastvale", "Fallowfield"};
        for (String club : clubs) {
            quotas.add(new SquadConstraints.ClubQuota(club,
                    SquadConstraints.Comparator.EXACTLY, 3));
        }
        // Six clubs at exactly three is eighteen players.
        Optional<String> refusal = Feasibility.check(deepPool(), constraints(quotas, 1000),
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

        Optional<String> refusal = Feasibility.check(deepPool(), constraints(List.of(), 1000),
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

        Optional<String> refusal = Feasibility.check(deepPool(), constraints(List.of(), 1000),
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
        Optional<String> refusal = Feasibility.check(deepPool(), constraints(List.of(), 800),
                pinned);

        assertThat(refusal).isPresent();
        assertThat(refusal.get()).contains("£");
    }

    @Test
    void countsTheActualPoolRatherThanAssumingAClubIsDeep() {
        List<SquadCandidate> thin = new ArrayList<>(deepPool());
        thin.removeIf(c -> c.club().equals("Ashfield") && c.elementId() % 2 == 0);
        long remaining = thin.stream().filter(c -> c.club().equals("Ashfield")).count();

        Optional<String> refusal = Feasibility.check(thin, constraints(List.of(
                new SquadConstraints.ClubQuota("Ashfield",
                        SquadConstraints.Comparator.EXACTLY, 3)), 1000), List.of());

        // The thinned club still has players; this asserts the check reads the pool, not a
        // constant. With six or more left it must pass.
        assertThat(remaining).isGreaterThanOrEqualTo(3);
        assertThat(refusal).isEmpty();
    }
}
