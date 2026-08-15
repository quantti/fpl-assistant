package dev.quantti.fplassistant.tools;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for the pick ranking composite and position parsing.
 *
 * <p>The composite cannot be exercised through the database today: no season has an unfinished
 * fixture, so every integration run resolves an empty FDR map and the ranking collapses to form
 * alone. The tool descriptions promise "ranked by form, then fixture difficulty, then
 * ownership/points", so that promise is pinned here instead.
 */
class PickToolsRankingTest {

    private static Map<String, Object> player(String name, double form, int teamId,
                                              double ownership, int points) {
        Map<String, Object> row = new HashMap<>();
        row.put("web_name", name);
        row.put("form", BigDecimal.valueOf(form));
        row.put("team_id", teamId);
        row.put("selected_by_percent", BigDecimal.valueOf(ownership));
        row.put("total_points", points);
        return row;
    }

    @Test
    void equalFormIsBrokenByEasierFixtures() {
        List<Map<String, Object>> ranked = PickTools.rank(
                List.of(player("Hard", 6.0, 1, 5.0, 100),
                        player("Easy", 6.0, 2, 5.0, 100)),
                Map.of(1, 4.5, 2, 2.0), true);

        assertThat(ranked.getFirst().get("web_name")).isEqualTo("Easy");
    }

    @Test
    void formStillOutranksFixtures() {
        List<Map<String, Object>> ranked = PickTools.rank(
                List.of(player("EasyButCold", 3.0, 2, 5.0, 100),
                        player("HotButHard", 8.0, 1, 5.0, 100)),
                Map.of(1, 5.0, 2, 1.0), true);

        assertThat(ranked.getFirst().get("web_name")).isEqualTo("HotButHard");
    }

    @Test
    void equalFormAndFixturesFallBackToLowerOwnershipForDifferentials() {
        List<Map<String, Object>> ranked = PickTools.rank(
                List.of(player("Template", 6.0, 1, 40.0, 100),
                        player("Differential", 6.0, 1, 2.0, 100)),
                Map.of(1, 3.0), true);

        assertThat(ranked.getFirst().get("web_name")).isEqualTo("Differential");
    }

    @Test
    void equalFormAndFixturesFallBackToMorePointsForCaptains() {
        List<Map<String, Object>> ranked = PickTools.rank(
                List.of(player("Fewer", 6.0, 1, 20.0, 80),
                        player("More", 6.0, 1, 20.0, 200)),
                Map.of(1, 3.0), false);

        assertThat(ranked.getFirst().get("web_name")).isEqualTo("More");
    }

    @Test
    void aTeamWithNoKnownFixturesSinksBelowOneWithFixtures() {
        List<Map<String, Object>> ranked = PickTools.rank(
                List.of(player("Unknown", 6.0, 99, 5.0, 100),
                        player("Known", 6.0, 1, 5.0, 100)),
                Map.of(1, 4.9), true);

        assertThat(ranked.getFirst().get("web_name")).isEqualTo("Known");
    }

    @Test
    void positionsParseFromNumbersAndAbbreviations() {
        assertThat(PickTools.parsePosition("1")).isEqualTo(1);
        assertThat(PickTools.parsePosition("GK")).isEqualTo(1);
        assertThat(PickTools.parsePosition("gkp")).isEqualTo(1);
        assertThat(PickTools.parsePosition("DEF")).isEqualTo(2);
        assertThat(PickTools.parsePosition(" mid ")).isEqualTo(3);
        assertThat(PickTools.parsePosition("forward")).isEqualTo(4);
    }

    @Test
    void anyAndBlankMeanNoPositionFilter() {
        assertThat(PickTools.parsePosition("any")).isZero();
        assertThat(PickTools.parsePosition("")).isZero();
        assertThat(PickTools.parsePosition(null)).isZero();
    }

    @Test
    void unrecognisedPositionIsRejectedRatherThanSilentlyWidenedToAny() {
        // Silently treating a typo as "any" answers a question the user did not ask.
        assertThat(PickTools.parsePosition("striker")).isNull();
        assertThat(PickTools.parsePosition("7")).isNull();
    }
}
