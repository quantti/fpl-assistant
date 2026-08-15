package dev.quantti.fplassistant.tools;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PickTools} against real 2025-26 data. The real {@link SeasonResolver} resolves to
 * 2025-26 (a completed fallback), so the provenance line labels the picks as historical —
 * which is the correct behaviour and what these tests assert.
 */
@SpringBootTest
@ActiveProfiles("test")
class PickToolsIntegrationTest {

    @Autowired
    PickTools tools;

    @Test
    void getDifferentialPicksReturnsLowOwnershipPlayers() {
        String result = tools.getDifferentialPicks("MID", 10, 5);

        assertThat(result).contains("Differential picks");
        assertThat(result).contains("Own ");
        assertThat(result).contains("Form ");
        assertThat(result).contains("Avg FDR");
        assertThat(result).contains("Data source: season 2025-26");
    }

    @Test
    void getDifferentialPicksAnyPositionWorks() {
        String result = tools.getDifferentialPicks("any", 15, 3);
        assertThat(result).contains("Differential picks");
    }

    @Test
    void getCaptainCandidatesReturnsAttackers() {
        String result = tools.getCaptainCandidates(5);

        assertThat(result).contains("Captain candidates");
        assertThat(result).contains("Form ");
        assertThat(result).contains("Avg FDR");
        assertThat(result).contains("Data source: season 2025-26");
    }

    @Test
    void noMatchesForAbsurdOwnershipFilter() {
        // No player has negative ownership, so this returns the empty-filter message.
        assertThat(tools.getDifferentialPicks("FWD", -1, 5)).contains("No differentials match");
    }
}
