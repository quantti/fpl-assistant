package fi.karivanttinen.fplassistant.chat;

import fi.karivanttinen.fplassistant.tools.FplTool;
import org.springframework.beans.factory.annotation.Value;
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

    private final ChatClient chatClient;
    private final List<FplTool> tools;
    private final boolean cachedCountedInsideInput;

    public ChatService(ChatClient chatClient, List<FplTool> tools,
                       @Value("${fpl.ai.provider:anthropic}") String provider) {
        this.chatClient = chatClient;
        this.tools = tools;
        // The two providers count a cached prompt differently, and the difference decides whether
        // a total is right or double: Anthropic reports input_tokens as the uncached remainder
        // with cache reads counted beside it, while OpenAI folds cached tokens into prompt_tokens
        // and reports them again under prompt_tokens_details. Spring AI hands both to us in the
        // same two fields — verified against OpenAiChatModel.getDefaultUsage, which maps
        // promptTokensDetails().cachedTokens() straight into the cache-read slot — so the
        // normalisation belongs here. What is stored is always the uncached remainder, which is
        // what lets total_tokens add the columns up.
        this.cachedCountedInsideInput = "openai".equalsIgnoreCase(provider);
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
    public Flux<String> stream(List<ChatTurn> turns, Consumer<TokenUsage> onUsage) {
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

        AtomicReference<TokenUsage> reported = new AtomicReference<>();
        return chatClient.prompt()
                .messages(history)
                .tools(tools.toArray())
                .stream()
                // .chatResponse() rather than .content(): the token counts live in the response
                // metadata, and .content() throws that away. The two lines below are what
                // .content() does internally — a chunk carrying only metadata has no text.
                .chatResponse()
                .doOnNext(response -> capture(response, reported, onUsage))
                .map(ChatService::textOf)
                .filter(text -> !text.isEmpty());
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
    private void capture(ChatResponse response, AtomicReference<TokenUsage> reported,
                         Consumer<TokenUsage> onUsage) {
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

        // Stored as the uncached remainder whichever provider answered — see the constructor.
        Integer uncachedPrompt = cachedCountedInsideInput && prompt != null && cacheReads != null
                ? Math.max(0, prompt - cacheReads.intValue())
                : prompt;

        // A prompt or completion count of zero is Spring AI's EmptyUsage, not a measurement:
        // every chunk carries one, and on a provider that also stamps the model on every chunk
        // that would write zeros into every row — indistinguishable from a real measurement of
        // nothing. The cache figures are different: they are null when absent, so a zero there
        // is a genuine cache miss, which is the number worth having.
        TokenUsage merged = reported.updateAndGet(previous -> new TokenUsage(
                model != null ? model : previous == null ? null : previous.model(),
                larger(unreported(uncachedPrompt) ? null : uncachedPrompt,
                        previous == null ? null : previous.inputTokens()),
                larger(cacheReads == null ? null : cacheReads.intValue(),
                        previous == null ? null : previous.cachedTokens()),
                larger(cacheWrites == null ? null : cacheWrites.intValue(),
                        previous == null ? null : previous.cacheWriteTokens()),
                larger(unreported(completion) ? null : completion,
                        previous == null ? null : previous.outputTokens())));

        // Reported as it arrives rather than on completion: a stream that fails or times out has
        // no completion, and the tokens it burned before failing were still spent. The caller
        // keeps the latest, so being told more than once costs nothing.
        onUsage.accept(merged);
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
