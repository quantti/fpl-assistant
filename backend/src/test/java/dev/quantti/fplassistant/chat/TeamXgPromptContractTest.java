package dev.quantti.fplassistant.chat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TeamXgPromptContractTest {
    @Test
    void separatesUnderstatTeamsFromFplPlayersAndForbidsReconstructingMissingTeamTotals() throws Exception {
        try (var in = getClass().getResourceAsStream("/prompts/system-prompt.st")) {
            assertThat(in).isNotNull();
            String prompt = new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("\\s+", " ");
            assertThat(prompt).contains("direct Understat team xG/xGA", "not FPL player xGC",
                    "do not reconstruct missing team totals by calling and summing player tools",
                    "FPL's own xG, xA and xGI for one player")
                    .doesNotContain("team's attacking xG/xA/xGI");
        }
    }
}
