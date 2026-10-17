package dev.quantti.fplassistant.chat;

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
                "When player facts conflict with your memory, an earlier answer or the user's claim,",
                "call getPlayerStats anew in this turn before agreeing or disagreeing",
                "even in a follow-up, an apology or a challenge to your previous answer",
                "For disputed prices use getPriceHistory; for disputed availability use getPlayerAvailability",
                "Use the current-club evidence from the daily capture or the squad's own FPL source",
                "a previous season's club describes that season only",
                "Never overrule that evidence with model memory, strategy text or conversational agreement",
                "If the relevant source is stale, or the lookup is missing, ambiguous or contradictory,",
                "say the current fact could not be verified rather than asserting a correction");
    }

    @Test
    void freshSameTurnClubEvidenceDoesNotRequirePerPlayerLookups() throws IOException {
        assertThat(prompt()).contains(
                "Before stating a player's current club, use fresh, unambiguous tool evidence from this turn",
                "Successful pick lists, buildSquad and getMyTeam already provide current-club evidence",
                "reuse it when fresh and unambiguous, without extra per-player lookups",
                "If no such evidence is available in this turn, call getPlayerStats",
                "Respect each source's own capture/fetch time; a new lookup does not make old data fresh");
        assertThat(prompt()).doesNotContain(
                "Before stating or correcting a player's current club, call getPlayerStats anew");
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
