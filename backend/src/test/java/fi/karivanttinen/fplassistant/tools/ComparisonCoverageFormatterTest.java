package fi.karivanttinen.fplassistant.tools;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ComparisonCoverageFormatterTest {

    private static final Instant AS_OF = Instant.parse("2026-09-09T12:00:00Z");

    @Test
    void provisionalPageEvidenceIsExplicitlyNotCalledFplCheckedOrFinal() {
        ComparisonCoverage coverage = ComparisonCoverage.assemble(List.of(), List.of(), true, true);

        assertThat(ComparisonCoverageFormatter.success(coverage))
                .contains("Match evidence status: provisional incremental-page evidence is present")
                .contains("no FPL-checked or final-import claim is inferred");
    }

    @Test
    void incompleteIncrementalPopulationIsNamedAsIncomplete() {
        ComparisonCoverage coverage = ComparisonCoverage.assemble(List.of(), List.of(
                new ComparisonCoverage.ClubGap(7, 11, 3,
                        ComparisonCoverage.GapReason.INCREMENTAL_POPULATION_INCOMPLETE)), true, true);

        assertThat(ComparisonCoverageFormatter.restricted(coverage, AS_OF))
                .contains("Match evidence status: incomplete")
                .contains("incremental population incomplete");
    }
}
