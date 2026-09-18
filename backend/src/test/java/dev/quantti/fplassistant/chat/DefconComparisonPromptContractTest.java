package dev.quantti.fplassistant.chat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Deterministic prompt contract (KAR-58); deliberately does not call a real language model. */
class DefconComparisonPromptContractTest {

    private static String prompt() throws IOException {
        try (var in = DefconComparisonPromptContractTest.class.getResourceAsStream(
                "/prompts/system-prompt.st")) {
            if (in == null) {
                throw new IOException("system prompt missing");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void promptRequiresDefconInEveryDefenderAndMidfielderComparison() throws IOException {
        assertThat(prompt()).contains(
                "Defensive contribution is part of every comparison of defenders, and of midfielders",
                "call getPlayerExpectedStats for each player",
                "`Defensive contribution` line",
                "never argue DefCon from a player's role alone");
    }
}
