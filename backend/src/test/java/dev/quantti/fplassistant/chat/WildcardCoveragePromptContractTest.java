package dev.quantti.fplassistant.chat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Deterministic prompt contract; deliberately does not call a real language model. */
class WildcardCoveragePromptContractTest {

    private static String prompt() throws IOException {
        try (var in = WildcardCoveragePromptContractTest.class.getResourceAsStream(
                "/prompts/system-prompt.st")) {
            if (in == null) {
                throw new IOException("system prompt missing");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void promptSeparatesDataExclusionFromRotationAndForbidsManualFallbacks() throws IOException {
        assertThat(prompt()).contains(
                "Comparison-data refusals are not football judgements",
                "never relabel that exclusion as rotation risk",
                "not a league-wide best list",
                "do not use that list, remembered names or manual substitutions",
                "only propose a revised 15-player squad after a newly called",
                "Budget, pins, formation, club quotas and",
                "user challenges a specific club's");
    }

    @Test
    void unfinishedMatchesNeverBlockAvailablePlayerDataOrImplyAHoldRecommendation() throws IOException {
        assertThat(prompt()).contains(
                "Match status is information, never an exclusion",
                "one missing player or fixture must not exclude a whole club",
                "Live/provisional figures may change",
                "An unfinished match alone is not a data gap",
                "Never turn a failed comparison into",
                "a recommendation to hold or sell a player");
        assertThat(prompt()).doesNotContain("no missing/invalid minutes or newer unresolved result");
    }
}
