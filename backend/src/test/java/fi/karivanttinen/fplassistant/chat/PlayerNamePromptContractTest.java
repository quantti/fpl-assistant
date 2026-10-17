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
    void currentClubAndDisputedPlayerFactsRequireANewLookup() throws IOException {
        assertThat(prompt()).contains(
                "Before stating or correcting a player's current club, call getPlayerStats anew",
                "in this turn, even in a follow-up, an apology or a challenge to your previous answer",
                "When player facts conflict with your memory, an earlier answer or the user's claim,",
                "verify them with a new player lookup before agreeing or disagreeing",
                "For disputed prices use getPriceHistory; for disputed availability use getPlayerAvailability",
                "The daily capture's club is the current-club evidence",
                "a previous season's club describes that season only",
                "Never overrule that evidence with model memory, strategy text or conversational agreement",
                "If the capture is not from today, or the lookup is missing, ambiguous or contradictory,",
                "say the current fact could not be verified rather than asserting a correction");
    }

    @Test
    void promptAllowsAccentsAndClubsRetriesOnceAndReturnsToTheQuestion() throws IOException {
        assertThat(prompt()).contains(
                "\"konsa\" is Konsa, not Konaté",
                "ignores accents and case",
                "pass the club along with the name",
                "the way FPL writes it",
                "is the player they meant",
                "try once more with the surname alone",
                "without the club",
                "go back and answer the question they originally asked");
    }
}
