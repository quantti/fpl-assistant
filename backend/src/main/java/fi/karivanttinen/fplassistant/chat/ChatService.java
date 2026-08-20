package fi.karivanttinen.fplassistant.chat;

import fi.karivanttinen.fplassistant.tools.FplTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Service
public class ChatService {

    // The bound on per-request cost and context growth. Since Task 4 the history is the
    // server's, so this is also how much of a conversation is read back per turn.
    public static final int HISTORY_WINDOW = 20;

    private final ChatClient chatClient;
    private final List<FplTool> tools;

    public ChatService(ChatClient chatClient, List<FplTool> tools) {
        this.chatClient = chatClient;
        this.tools = tools;
    }

    /**
     * Streams the answer to a conversation whose turns are given oldest-first. The caller has
     * already bounded them to {@link #HISTORY_WINDOW}; trimming here is about the shape of the
     * window rather than its size.
     */
    public Flux<String> stream(List<ChatTurn> turns, Consumer<TokenUsage> onUsage) {
        List<ChatTurn> window = turns.subList(Math.max(0, turns.size() - HISTORY_WINDOW), turns.size());
        // The Anthropic API rejects a history that opens on an assistant turn, and an
        // even-sized window over an odd-length alternating conversation always does.
        while (!window.isEmpty() && !ChatTurn.USER.equals(window.getFirst().role())) {
            window = window.subList(1, window.size());
        }
        List<Message> history = merged(window).stream()
                .map(m -> (Message) switch (m.role()) {
                    case ChatTurn.USER -> new UserMessage(m.content());
                    default -> new AssistantMessage(m.content());
                })
                .toList();

        AtomicReference<TokenUsage> reported = new AtomicReference<>();
        return chatClient.prompt()
                .messages(history)
                .tools(tools.toArray())
                .stream()
                // .chatResponse() rather than .content(): the token counts live in the response
                // metadata, and .content() throws that away. The two lines below are what
                // .content() does internally — a chunk carrying only metadata has no text.
                .chatResponse()
                .doOnNext(response -> capture(response, reported))
                .map(ChatService::textOf)
                .filter(text -> !text.isEmpty())
                .doOnComplete(() -> {
                    TokenUsage usage = reported.get();
                    if (usage != null) {
                        onUsage.accept(usage);
                    }
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
     * Merges field by field, keeping the most recent number a provider gave for each.
     *
     * <p>Providers differ in where usage arrives: some report prompt tokens on the first chunk
     * and completion tokens on the last, others repeat a running total. Keeping only the final
     * chunk's usage loses the input count under the first style; overwriting per field survives
     * both. What it cannot survive is a provider that reports deltas meant to be summed — Task 8
     * checks the recorded numbers against a real answer, which is the only way to know.
     */
    private static void capture(ChatResponse response, AtomicReference<TokenUsage> reported) {
        if (response.getMetadata() == null || response.getMetadata().getUsage() == null) {
            return;
        }
        Usage usage = response.getMetadata().getUsage();
        Long cacheReads = usage.getCacheReadInputTokens();
        reported.updateAndGet(previous -> {
            TokenUsage merged = new TokenUsage(
                    pick(usage.getPromptTokens(), previous == null ? null : previous.inputTokens()),
                    pick(cacheReads == null ? null : cacheReads.intValue(),
                            previous == null ? null : previous.cachedTokens()),
                    pick(usage.getCompletionTokens(), previous == null ? null : previous.outputTokens()));
            // Every chunk carries metadata, and most carry an empty usage inside it. A record of
            // three nulls is not a measurement, and recording one would claim the provider
            // answered a question it was never asked.
            boolean measured = merged.inputTokens() != null || merged.cachedTokens() != null
                    || merged.outputTokens() != null;
            return measured ? merged : previous;
        });
    }

    /** A zero from a provider that has not counted yet must not overwrite a real number. */
    private static Integer pick(Integer reported, Integer previous) {
        return reported == null || reported == 0 ? previous : reported;
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
