package fi.karivanttinen.fplassistant.chat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Deterministic wording contract; deliberately does not call a real language model. */
class AvailabilityPromptContractTest {

    private static String prompt() throws IOException {
        try (var in = AvailabilityPromptContractTest.class.getResourceAsStream(
                "/prompts/system-prompt.st")) {
            if (in == null) {
                throw new IOException("system prompt missing");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void routineRepliesOmitNormalAvailabilityWithoutSkippingTheCheck() throws IOException {
        assertThat(prompt()).contains(
                "Check availability before recommending a transfer in, or a captain",
                "In player summaries and recommendations, mention availability only when there is a concern",
                "Omit routine availability lines for status 'a' (available)",
                "Do not add \"available\", \"no FPL flag\" or \"ei FPL-lippua\" to these replies");
    }

    @Test
    void concernsUsePlainLanguageAndUnknownOrOldDataRemainsQualified() throws IOException {
        assertThat(prompt()).contains(
                "doubtful, injured, suspended or otherwise unavailable",
                "\"pelaaminen epävarmaa\", \"loukkaantunut\" or \"pelikiellossa\"",
                "not \"FPL-lippu\" or \"liputettu\"",
                "If a required availability check is missing or out of date, say so",
                "do not silently treat it as available",
                "Keep the capture-date qualification");
    }

    @Test
    void directQuestionsStillGetAnAnswerWithoutPromisingPlayingTime() throws IOException {
        assertThat(prompt()).contains(
                "Answer a direct question about availability even when the player is marked available",
                "FPL has no reported concern in that capture",
                "not a guarantee of fitness, a starting place or minutes");
    }
}
