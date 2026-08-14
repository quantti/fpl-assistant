package dev.quantti.fplassistant.tools;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit test for the distinct-gameweek windowing — no Spring, no database. The live-data
 * tests cannot exercise a double gameweek or a postponed fixture on demand, and this is the
 * logic the Task 6 amendment exists for.
 */
class FixtureWindowingTest {

    private static Map<String, Object> fixture(Integer gameweek) {
        Map<String, Object> r = new HashMap<>();
        r.put("gameweek", gameweek);
        return r;
    }

    @Test
    void doubleGameweekCountsAsOneRoundWithBothFixturesKept() {
        List<Map<String, Object>> rows = List.of(
                fixture(29), fixture(30), fixture(30), fixture(31));

        FixtureTools.FixtureWindow window = FixtureTools.window(rows, 2);

        assertThat(window.scheduled())
                .extracting(r -> r.get("gameweek"))
                .containsExactly(29, 30, 30);
        assertThat(window.postponed()).isEmpty();
    }

    @Test
    void gameweeksBeyondTheLimitAreExcluded() {
        List<Map<String, Object>> rows = List.of(
                fixture(1), fixture(2), fixture(3), fixture(4));

        FixtureTools.FixtureWindow window = FixtureTools.window(rows, 3);

        assertThat(window.scheduled())
                .extracting(r -> r.get("gameweek"))
                .containsExactly(1, 2, 3);
    }

    @Test
    void postponedFixturesAreSetAsideAndDoNotConsumeTheWindow() {
        // Ordered as the SQL delivers them: gameweek NULLS LAST.
        List<Map<String, Object>> rows = List.of(
                fixture(10), fixture(11), fixture(null));

        FixtureTools.FixtureWindow window = FixtureTools.window(rows, 2);

        assertThat(window.scheduled())
                .extracting(r -> r.get("gameweek"))
                .containsExactly(10, 11);
        assertThat(window.postponed()).hasSize(1);
    }

    @Test
    void emptyInputYieldsEmptyWindow() {
        FixtureTools.FixtureWindow window = FixtureTools.window(List.of(), 5);

        assertThat(window.scheduled()).isEmpty();
        assertThat(window.postponed()).isEmpty();
    }
}
