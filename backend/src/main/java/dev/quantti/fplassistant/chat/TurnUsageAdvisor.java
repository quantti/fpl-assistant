package dev.quantti.fplassistant.chat;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.google.genai.metadata.GoogleGenAiUsage;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Sums Spring AI 2.0.0 usage snapshots across every model invocation in one subscribed user turn.
 * Provider SDK retries below the advisor are visible only when they emit mapped usage.
 */
final class TurnUsageAdvisor implements StreamAdvisor {

    private final boolean cachedCountedInsideInput;
    private final Consumer<TokenUsage> onUsage;
    private final List<Slot> slots = new ArrayList<>();
    private final Object stateLock = new Object();
    private final Object publicationLock = new Object();
    private long version;
    private long publishedVersion;

    TurnUsageAdvisor(boolean cachedCountedInsideInput, Consumer<TokenUsage> onUsage) {
        this.cachedCountedInsideInput = cachedCountedInsideInput;
        this.onUsage = onUsage;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return Flux.defer(() -> {
            Slot slot = new Slot();
            synchronized (stateLock) {
                slots.add(slot);
            }
            return chain.nextStream(request).doOnNext(response -> capture(slot, response));
        });
    }

    private void capture(Slot slot, ChatClientResponse clientResponse) {
        if (clientResponse == null || clientResponse.chatResponse() == null) {
            return;
        }
        ChatResponseMetadata metadata = clientResponse.chatResponse().getMetadata();
        if (metadata == null) {
            return;
        }
        String model = metadata.getModel() == null || metadata.getModel().isBlank()
                ? null : metadata.getModel();
        Usage usage = metadata.getUsage();
        Integer prompt = usage == null ? null : usage.getPromptTokens();
        Integer completion = usage == null ? null : usage.getCompletionTokens();
        if (usage instanceof GoogleGenAiUsage gemini && gemini.getThoughtsTokenCount() != null) {
            completion = (completion == null ? 0 : completion) + gemini.getThoughtsTokenCount();
        }
        Long cacheReads = usage == null ? null : usage.getCacheReadInputTokens();
        Long cacheWrites = usage == null ? null : usage.getCacheWriteInputTokens();
        boolean measured = !unreported(prompt) || !unreported(completion)
                || cacheReads != null || cacheWrites != null;
        if (!measured && model == null) {
            return;
        }

        Integer uncachedPrompt = cachedCountedInsideInput && prompt != null && cacheReads != null
                ? Math.max(0, prompt - cacheReads.intValue()) : prompt;
        TokenUsage candidate = new TokenUsage(model,
                unreported(uncachedPrompt) ? null : uncachedPrompt,
                cacheReads == null ? null : cacheReads.intValue(),
                cacheWrites == null ? null : cacheWrites.intValue(),
                unreported(completion) ? null : completion);

        Publication publication;
        synchronized (stateLock) {
            slot.usage = richer(slot.usage, candidate);
            publication = new Publication(++version, sum());
        }
        publish(publication);
    }

    private void publish(Publication publication) {
        synchronized (publicationLock) {
            if (publication.version <= publishedVersion) {
                return;
            }
            publishedVersion = publication.version;
            onUsage.accept(publication.usage);
        }
    }

    private TokenUsage sum() {
        String model = null;
        Integer input = null;
        Integer cached = null;
        Integer writes = null;
        Integer output = null;
        for (Slot slot : slots) {
            TokenUsage usage = slot.usage;
            if (usage == null) {
                continue;
            }
            if (usage.model() != null) {
                model = usage.model();
            }
            input = add(input, usage.inputTokens());
            cached = add(cached, usage.cachedTokens());
            writes = add(writes, usage.cacheWriteTokens());
            output = add(output, usage.outputTokens());
        }
        return new TokenUsage(model, input, cached, writes, output);
    }

    private static Integer add(Integer total, Integer contribution) {
        if (contribution == null) {
            return total;
        }
        return (total == null ? 0 : total) + contribution;
    }

    private static boolean unreported(Integer count) {
        return count == null || count == 0;
    }

    /** Keeps the richest cumulative chunk for one invocation as one coherent snapshot. */
    private static TokenUsage richer(TokenUsage previous, TokenUsage candidate) {
        if (previous == null) {
            return candidate;
        }
        long candidateTotal = total(candidate);
        long previousTotal = total(previous);
        TokenUsage winner = candidateTotal > previousTotal
                || candidateTotal == previousTotal && reportedFields(candidate) >= reportedFields(previous)
                ? candidate : previous;
        String model = candidate.model() != null ? candidate.model() : previous.model();
        return new TokenUsage(model, winner.inputTokens(), winner.cachedTokens(),
                winner.cacheWriteTokens(), winner.outputTokens());
    }

    private static long total(TokenUsage usage) {
        return value(usage.inputTokens()) + value(usage.cachedTokens())
                + value(usage.cacheWriteTokens()) + value(usage.outputTokens());
    }

    private static int reportedFields(TokenUsage usage) {
        int fields = 0;
        fields += usage.inputTokens() == null ? 0 : 1;
        fields += usage.cachedTokens() == null ? 0 : 1;
        fields += usage.cacheWriteTokens() == null ? 0 : 1;
        fields += usage.outputTokens() == null ? 0 : 1;
        return fields;
    }

    private static long value(Integer count) {
        return count == null ? 0 : count;
    }

    @Override
    public int getOrder() {
        return ToolCallingAdvisor.DEFAULT_ORDER + 1;
    }

    @Override
    public String getName() {
        return "turn usage";
    }

    private static final class Slot {
        private TokenUsage usage;
    }

    private record Publication(long version, TokenUsage usage) {}
}
