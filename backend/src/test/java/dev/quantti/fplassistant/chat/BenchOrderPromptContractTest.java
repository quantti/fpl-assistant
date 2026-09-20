package dev.quantti.fplassistant.chat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Deterministic bench-order instructions, not a live-model generation test. */
class BenchOrderPromptContractTest {

    private static String prompt() throws IOException {
        try (var in = BenchOrderPromptContractTest.class.getResourceAsStream(
                "/prompts/system-prompt.st")) {
            if (in == null) {
                throw new IOException("system prompt missing");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void outfieldBenchIsListedFromFirstSubstituteToLast() throws IOException {
        assertThat(prompt()).containsSubsequence(
                "## Bench order and automatic substitutions",
                "1. First substitute — highest-priority outfield cover.",
                "2. Second substitute — next in priority.",
                "3. Third substitute — last in priority.");
        assertThat(prompt()).contains(
                "not the order in which you remove players from the XI",
                "Never use \"first out\" to label the first substitute",
                "the explanations must match the numbered slots",
                "a player described as the first substitute belongs at 1, not 3");
    }

    @Test
    void reserveGoalkeeperIsSeparateFromOutfieldPriority() throws IOException {
        assertThat(prompt()).contains(
                "Number only the three outfield substitutes",
                "List the reserve goalkeeper separately under \"Reserve goalkeeper\"",
                "not as a fourth outfield option",
                "can replace only the starting goalkeeper");
    }

    @Test
    void priorityDoesNotPromiseAnIllegalOrCameoReplacement() throws IOException {
        assertThat(prompt()).contains(
                "substitutes who played, while preserving a legal formation",
                "Formation rules can require skipping a higher-priority outfielder",
                "explain that exception without reversing the bench order",
                "A starter's one-minute cameo prevents an automatic replacement");
    }
}
