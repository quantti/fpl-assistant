package dev.quantti.fplassistant.chat;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * How much of a conversation may be sent to the model, counted in characters.
 *
 * <p>Owner decision (2026-08-20): a conversation is continued with its <em>whole</em> history,
 * and the cost of resending it is accepted. This is not a cost control — it is the guard that
 * keeps a conversation usable. A request larger than the model's context window is rejected
 * outright, and a conversation that has grown past it would fail on every turn from then on,
 * permanently. Dropping the oldest turns is the lesser loss, and the user is told when it
 * happens.
 *
 * <p>Characters, because counting tokens means asking the provider. Four characters per token is
 * the usual rough ratio for English, so the default is on the order of 200k tokens — well inside
 * claude-sonnet-5's window and far beyond any conversation this application has seen. The number
 * to watch before raising it is {@code input_tokens} in {@code fpl_usage_event}.
 */
@Component
public class HistoryBudget {

    private final int characters;

    public HistoryBudget(@Value("${fpl.chat.history-char-budget:800000}") int characters) {
        this.characters = characters;
    }

    /**
     * The longest suffix of the conversation that fits — the recent end, because that is what the
     * next answer depends on. Never empty: a single turn over budget on its own is still the turn
     * being answered, and refusing it would be a conversation that cannot be continued at all.
     */
    public List<ChatTurn> fit(List<ChatTurn> turns) {
        int used = 0;
        int first = turns.size();
        while (first > 0) {
            int next = used + turns.get(first - 1).content().length();
            if (next > characters && first < turns.size()) {
                break;
            }
            used = next;
            first--;
        }
        return turns.subList(first, turns.size());
    }
}
