package dev.quantti.fplassistant.chat;

import dev.quantti.fplassistant.myteam.FplBudget;
import dev.quantti.fplassistant.myteam.ToolUser;
import dev.quantti.fplassistant.tools.FplTool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

@Service
public class ChatService {

    private final ChatClient chatClient;
    private final List<FplTool> tools;
    private final boolean cachedCountedInsideInput;
    private final int fplAllowance;

    public ChatService(ChatClient chatClient, List<FplTool> tools,
                       @Value("${fpl.ai.provider:anthropic}") String provider,
                       @Value("${fpl.myteam.max-requests-per-turn}") int fplAllowance) {
        this.chatClient = chatClient;
        this.tools = tools;
        this.fplAllowance = fplAllowance;
        // The providers count a cached prompt differently, and the difference decides whether
        // a total is right or double: Anthropic reports input_tokens as the uncached remainder
        // with cache reads counted beside it, while OpenAI folds cached tokens into prompt_tokens
        // and reports them again under prompt_tokens_details. Spring AI hands both to us in the
        // same two fields — verified against OpenAiChatModel.getDefaultUsage, which maps
        // promptTokensDetails().cachedTokens() straight into the cache-read slot — so the
        // normalisation belongs here. What is stored is always the uncached remainder, which is
        // what lets total_tokens add the columns up. Gemini counts like OpenAI:
        // cachedContentTokenCount is a subset of promptTokenCount (verified against
        // GoogleGenAiUsage in Spring AI 2.0.0).
        this.cachedCountedInsideInput = "openai".equalsIgnoreCase(provider)
                || "google-genai".equalsIgnoreCase(provider);
    }

    /**
     * Streams the answer to a conversation whose turns are given oldest-first. Whatever is
     * given is sent: bounding the history is {@link HistoryBudget}'s job, and the caller's,
     * because the caller is the one who has to tell the user when something was left out.
     *
     * <p>{@code onUsage} is called with the running measurement each time a chunk reports one,
     * not once at the end: a stream that fails or times out never completes, and the tokens it
     * burned before failing were spent all the same.
     */
    public Flux<String> stream(List<ChatTurn> turns, UUID userId, Consumer<TokenUsage> onUsage) {
        List<ChatTurn> window = turns;
        // The Anthropic API rejects a history that opens on an assistant turn, and dropping the
        // oldest turns can leave one there.
        while (!window.isEmpty() && !ChatTurn.USER.equals(window.getFirst().role())) {
            window = window.subList(1, window.size());
        }
        List<Message> history = merged(window).stream()
                .map(m -> (Message) switch (m.role()) {
                    case ChatTurn.USER -> new UserMessage(m.content());
                    default -> new AssistantMessage(m.content());
                })
                .toList();

        return Flux.defer(() -> {
            TurnUsageAdvisor usageAdvisor = new TurnUsageAdvisor(cachedCountedInsideInput, onUsage);
            FplBudget budget = new FplBudget(fplAllowance);
            return chatClient.prompt()
                    .messages(history)
                    .tools(tools.toArray())
                    // Whose account is asking, and how many calls to FPL this turn may make. Both
                    // belong here rather than in a tool parameter: the model fills parameters from
                    // the user's own text, and a user id it can write is one an attacker can ask it
                    // to write. Spring AI keeps the tool context out of the schema the model sees.
                    .toolContext(Map.of(ToolUser.KEY, userId, ToolUser.BUDGET, budget))
                    .advisors(usageAdvisor)
                    .stream()
                    // Keep response chunks for text extraction; the inner advisor accounts for
                    // usage before ToolCallingAdvisor filters tool-call rounds from this stream.
                    .chatResponse()
                    .map(ChatService::textOf)
                    .filter(text -> !text.isEmpty());
        });
    }

    private static String textOf(ChatResponse response) {
        if (response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    /**
     * Consecutive turns of the same role, joined. The Anthropic API rejects a history with two
     * user turns in a row, and stored history produces exactly that whenever an answer failed:
     * the user's message is persisted before the model is called, the failed answer is not, and
     * the next question lands straight after it. The frontend used to do this merge on the
     * history it held; since Task 4 it holds none.
     */
    static List<ChatTurn> merged(List<ChatTurn> turns) {
        List<ChatTurn> out = new ArrayList<>();
        for (ChatTurn turn : turns) {
            if (!out.isEmpty() && out.getLast().role().equals(turn.role())) {
                ChatTurn previous = out.removeLast();
                out.add(new ChatTurn(turn.role(), previous.content() + "\n\n" + turn.content()));
            } else {
                out.add(turn);
            }
        }
        return out;
    }
}
