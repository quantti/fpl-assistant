package fi.karivanttinen.fplassistant.chat;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole history is sent — the owner accepted the cost of that. This is the guard that keeps a
 * conversation usable when it outgrows what the model will accept: without it, such a
 * conversation would fail on every turn from then on, permanently.
 */
class HistoryBudgetTest {

    private static final int BUDGET = 10_000;
    private static final HistoryBudget budget = new HistoryBudget(BUDGET);

    @Test
    void aHistoryThatFitsIsSentWhole() {
        List<ChatTurn> turns = List.of(
                new ChatTurn(ChatTurn.USER, "first"),
                new ChatTurn(ChatTurn.ASSISTANT, "second"),
                new ChatTurn(ChatTurn.USER, "third"));

        assertThat(budget.fit(turns)).isEqualTo(turns);
    }

    @Test
    void aHistoryTooLargeToSendKeepsItsRecentEnd() {
        String long_ = "x".repeat(BUDGET / 2);
        List<ChatTurn> turns = List.of(
                new ChatTurn(ChatTurn.USER, "oldest " + long_),
                new ChatTurn(ChatTurn.ASSISTANT, "middle " + long_),
                new ChatTurn(ChatTurn.USER, "newest"));

        assertThat(budget.fit(turns))
                .as("as much of the recent end as fits, oldest dropped first")
                .containsExactly(
                        new ChatTurn(ChatTurn.ASSISTANT, "middle " + long_),
                        new ChatTurn(ChatTurn.USER, "newest"));
    }

    @Test
    void oneTurnOverBudgetIsStillTheTurnBeingAnswered() {
        List<ChatTurn> huge = List.of(new ChatTurn(ChatTurn.USER, "x".repeat(BUDGET * 3)));

        assertThat(budget.fit(huge))
                .as("refusing it would be a conversation that cannot be continued at all")
                .isEqualTo(huge);
    }
}
