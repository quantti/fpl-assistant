package fi.karivanttinen.fplassistant.chat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Deterministic prompt contract; deliberately does not call a real language model. */
class PlayerNamePromptContractTest {

    private static String prompt() throws IOException {
        try (var in = PlayerNamePromptContractTest.class.getResourceAsStream(
                "/prompts/system-prompt.st")) {
            if (in == null) {
                throw new IOException("system prompt missing");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void promptAllowsAccentsAndClubsRetriesOnceAndReturnsToTheQuestion() throws IOException {
        assertThat(prompt()).contains(
                "\"konsa\" is Konsa, not Konaté",
                "ignores accents and case",
                "pass the club along with the name",
                "is the player they meant",
                "try once more with the surname alone",
                "go back and answer the question they originally asked");
    }
}
