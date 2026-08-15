package dev.quantti.fplassistant.historical;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests against real {@code fixtures.csv} fixtures. Both real files have a different
 * column order (2019-20 puts {@code stats} before the team columns; 2025-26 puts it after) —
 * parsing both correctly is what proves this reads by header name, not position.
 */
class VaastavFixtureParserTest {

    private static String readFixture(String name) {
        try {
            return Files.readString(Path.of("src/test/resources/vaastav-fixtures/" + name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void parses2025_26WhichHasFinishedMatchesWithScores() {
        List<VaastavFixtureRow> rows = VaastavFixtureParser.parse(
                readFixture("2025-26-fixtures.csv"), "2025-26");

        assertThat(rows).hasSize(380);
        assertThat(rows).allMatch(VaastavFixtureRow::finished);
        assertThat(rows).allMatch(r -> r.teamHScore() != null && r.teamAScore() != null);
        assertThat(rows).allMatch(r -> r.gameweek() != null && r.gameweek() >= 1 && r.gameweek() <= 38);
    }

    @Test
    void parses2019_20WhoseColumnOrderDiffersFrom2025_26() {
        List<VaastavFixtureRow> rows = VaastavFixtureParser.parse(
                readFixture("2019-20-fixtures.csv"), "2019-20");

        assertThat(rows).hasSize(380);
        // First row of the real file: 1059702,1,True,True,1,2019-08-09T19:00:00Z,90,False,True,
        // "[stats blob]",14,5,1,10,2,4 -> team_h=10, team_a=14, team_h_score=4, team_a_score=1.
        VaastavFixtureRow first = rows.getFirst();
        assertThat(first.finished()).isTrue();
        assertThat(first.teamHId()).isEqualTo(10);
        assertThat(first.teamAId()).isEqualTo(14);
        assertThat(first.teamHScore()).isEqualTo(4);
        assertThat(first.teamAScore()).isEqualTo(1);
        assertThat(first.gameweek()).isEqualTo(1);
    }

    @Test
    void wholeNumberFloatsAreAcceptedInNumericColumns() {
        // vaastav sometimes writes "1.0" instead of "1" in numeric columns.
        String csv = "team_h,team_a,finished,team_h_score,team_a_score,event\n1.0,2.0,True,3.0,1.0,5.0\n";

        List<VaastavFixtureRow> rows = VaastavFixtureParser.parse(csv, "test");

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().teamHId()).isEqualTo(1);
        assertThat(rows.getFirst().teamAId()).isEqualTo(2);
        assertThat(rows.getFirst().teamHScore()).isEqualTo(3);
    }

    @Test
    void aRealFractionalValueFailsLoudlyRatherThanBeingSilentlyTruncated() {
        // Truncating "1.5" to 1 for team_h would resolve to the wrong team with no error —
        // exactly the silent-corruption failure this phase's fail-loud design exists to prevent.
        String csv = "team_h,team_a,finished\n1.5,2,True\n";

        assertThatThrownBy(() -> VaastavFixtureParser.parse(csv, "test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("team_h")
                .hasMessageContaining("1.5");
    }

    @Test
    void theStatsColumnCommasAndQuotesDoNotCorruptOtherColumns() {
        // If this were split(","), the stats blob's internal commas would shift every column
        // after it — team_h_score etc. would come back garbage or throw. They don't.
        List<VaastavFixtureRow> rows = VaastavFixtureParser.parse(
                readFixture("2019-20-fixtures.csv"), "2019-20");

        assertThat(rows).allSatisfy(r -> {
            assertThat(r.teamHId()).isBetween(1, 20);
            assertThat(r.teamAId()).isBetween(1, 20);
        });
    }
}
