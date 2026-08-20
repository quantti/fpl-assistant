package dev.quantti.fplassistant.chat;

import dev.quantti.fplassistant.tools.FplTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
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
     * Merges field by field, keeping the largest number a provider has reported for each, and
     * the model name from whichever chunk carried one.
     *
     * <p>Both providers report usage cumulatively per chunk (Spring AI runs chunk usage through
     * {@code UsageCalculator.getCumulativeUsage}, across tool-call round trips as well), so the
     * largest value seen is the total — and a trailing chunk carrying an empty measurement
     * cannot erase it. What this cannot survive is a provider reporting deltas meant to be
     * summed; Task 8 checks the recorded numbers against a real answer, which is the only way to
     * know.
     *
     * <p>A reported <em>zero</em> is kept, not discarded. A cache miss reports zero cache reads,
     * and that is a measurement — the one Task 8 exists to make. What gets skipped is a chunk
     * carrying neither a measurement nor a model name: every text chunk has metadata, and most
     * hold Spring AI's {@code EmptyUsage}, which is zeros with no cache figures beside them.
     */
    private static void capture(ChatResponse response, AtomicReference<TokenUsage> reported) {
        ChatResponseMetadata metadata = response.getMetadata();
        if (metadata == null) {
            return;
        }
        String model = metadata.getModel() == null || metadata.getModel().isBlank()
                ? null : metadata.getModel();
        Usage usage = metadata.getUsage();
        Integer prompt = usage == null ? null : usage.getPromptTokens();
        Integer completion = usage == null ? null : usage.getCompletionTokens();
        Long cacheReads = usage == null ? null : usage.getCacheReadInputTokens();
        Long cacheWrites = usage == null ? null : usage.getCacheWriteInputTokens();

        boolean measured = !unreported(prompt) || !unreported(completion)
                || cacheReads != null || cacheWrites != null;
        if (!measured && model == null) {
            return;
        }

        reported.updateAndGet(previous -> new TokenUsage(
                model != null ? model : previous == null ? null : previous.model(),
                larger(prompt, previous == null ? null : previous.inputTokens()),
                larger(cacheReads == null ? null : cacheReads.intValue(),
                        previous == null ? null : previous.cachedTokens()),
                larger(cacheWrites == null ? null : cacheWrites.intValue(),
                        previous == null ? null : previous.cacheWriteTokens()),
                larger(completion, previous == null ? null : previous.outputTokens())));
    }

    private static boolean unreported(Integer count) {
        return count == null || count == 0;
    }

    private static Integer larger(Integer reported, Integer previous) {
        if (reported == null) {
            return previous;
        }
        return previous == null ? reported : Math.max(reported, previous);
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
