package fi.karivanttinen.fplassistant.chat;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.core.JsonField;
import com.openai.core.JsonValue;
import com.openai.core.http.AsyncStreamResponse;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.completions.CompletionUsage;
import com.openai.services.async.ChatServiceAsync;
import com.openai.services.async.chat.ChatCompletionServiceAsync;
import fi.karivanttinen.fplassistant.tools.FplTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.annotation.Tool;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Provider-boundary regression for Spring AI 2.0.0 and openai-java-core 4.39.1. The SDK clients
 * are mocks, but every chunk is a real OpenAI SDK value and the provider model, ChatClient
 * advisor chain and tool loop are production objects.
 */
class OpenAiRoundTripUsageTest {

    private static final UUID USER = UUID.fromString("11111111-2222-4333-8444-555555555555");

    @Test
    void toolRoundsAndFinalAnswerSumEndUsageWithoutCountingCacheTwice() {
        SdkBoundary sdk = new SdkBoundary(List.of(
                stream(List.of(
                        tool("reused-id", "first", "same-tool-id"),
                        end("reused-id", ChatCompletionChunk.Choice.FinishReason.TOOL_CALLS,
                                usage(100, 8, 0)))),
                stream(List.of(
                        tool("", "second", "same-tool-id"),
                        end("", ChatCompletionChunk.Choice.FinishReason.TOOL_CALLS,
                                usageWithoutCacheDetails(90, 6)),
                        usageOnly("", usage(90, 6, 70)))),
                stream(List.of(
                        metadata("reused-id"),
                        text("reused-id", "provider answer"),
                        end("reused-id", ChatCompletionChunk.Choice.FinishReason.STOP,
                                usageWithoutCacheDetails(20, 4)),
                        usageOnly("reused-id", usageWithoutCacheDetails(20, 4))))));
        List<TokenUsage> reported = new ArrayList<>();
        ProbeTools tools = new ProbeTools();

        StepVerifier.create(service(sdk, List.of(tools)).stream(
                        List.of(new ChatTurn(ChatTurn.USER, "use both probes")), USER, reported::add))
                .expectNext("provider answer")
                .verifyComplete();

        assertThat(sdk.requests()).isEqualTo(3);
        assertThat(tools.firstCalls()).isOne();
        assertThat(tools.secondCalls()).isOne();
        verify(sdk.completions(), times(3)).createStreaming(any(ChatCompletionCreateParams.class));
        assertThat(reported.getLast())
                .as("rounds: 100/0/8 + 20/70/6 + 20/unknown/4; repeated end usage counts once")
                .isEqualTo(new TokenUsage("fixture-model", 140, 70, null, 18));
        assertThat(reported.getLast().inputTokens() + reported.getLast().cachedTokens()
                + reported.getLast().outputTokens())
                .as("normalized total equals 100+8 + 90+6 + 20+4")
                .isEqualTo(228);
    }

    @Test
    void laterRoundFailureBeforeUsagePreservesCompletedToolRound() {
        SdkBoundary sdk = new SdkBoundary(List.of(
                stream(List.of(
                        tool("first-id", "first", "tool-id"),
                        end("first-id", ChatCompletionChunk.Choice.FinishReason.TOOL_CALLS,
                                usage(30, 5, 10)))),
                failingStream(List.of(text("second-id", "partial")), "before usage")));
        List<TokenUsage> reported = new ArrayList<>();

        StepVerifier.create(service(sdk, List.of(new ProbeTools())).stream(
                        List.of(new ChatTurn(ChatTurn.USER, "fail later")), USER, reported::add))
                .expectNext("partial")
                .expectErrorMessage("before usage")
                .verify();

        assertThat(sdk.requests()).isEqualTo(2);
        assertThat(reported.getLast())
                .isEqualTo(new TokenUsage("fixture-model", 20, 10, null, 5));
    }

    @Test
    void laterRoundFailureAfterPartialUsageAddsItsLastReportedSnapshot() {
        SdkBoundary sdk = new SdkBoundary(List.of(
                stream(List.of(
                        tool("same-id", "first", "tool-id"),
                        end("same-id", ChatCompletionChunk.Choice.FinishReason.TOOL_CALLS,
                                usageWithoutCacheDetails(25, 4)))),
                failingStream(List.of(
                        metadata("same-id"),
                        usageOnly("same-id", usage(12, 2, 7))), "after usage")));
        List<TokenUsage> reported = new ArrayList<>();

        StepVerifier.create(service(sdk, List.of(new ProbeTools())).stream(
                        List.of(new ChatTurn(ChatTurn.USER, "fail after usage")), USER, reported::add))
                .expectErrorMessage("after usage")
                .verify();

        assertThat(sdk.requests()).isEqualTo(2);
        assertThat(reported.getLast())
                .as("25/unknown/4 + 5/7/2 survives the terminal SDK error")
                .isEqualTo(new TokenUsage("fixture-model", 30, 7, null, 6));
    }

    private static ChatService service(SdkBoundary sdk, List<FplTool> tools) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .apiKey("unused-fixture-key")
                .model("fixture-model")
                .maxTokens(100)
                .streamUsage(true)
                .build();
        OpenAiChatModel model = OpenAiChatModel.builder()
                .openAiClient(sdk.syncClient())
                .openAiClientAsync(sdk.asyncClient())
                .options(options)
                .build();
        return new ChatService(ChatClient.builder(model).build(), tools, "openai", 1);
    }

    private static ChatCompletionChunk metadata(String id) {
        return chunk(id, List.of(), null);
    }

    private static ChatCompletionChunk text(String id, String content) {
        ChatCompletionChunk.Choice.Delta delta = ChatCompletionChunk.Choice.Delta.builder()
                .content(content)
                .build();
        return chunk(id, List.of(choice(delta, null)), null);
    }

    private static ChatCompletionChunk tool(String id, String name, String toolId) {
        ChatCompletionChunk.Choice.Delta.ToolCall.Function function =
                ChatCompletionChunk.Choice.Delta.ToolCall.Function.builder()
                        .name(name)
                        .arguments("{}")
                        .build();
        ChatCompletionChunk.Choice.Delta.ToolCall toolCall =
                ChatCompletionChunk.Choice.Delta.ToolCall.builder()
                        .index(0)
                        .id(toolId)
                        .type(ChatCompletionChunk.Choice.Delta.ToolCall.Type.FUNCTION)
                        .function(function)
                        .build();
        ChatCompletionChunk.Choice.Delta delta = ChatCompletionChunk.Choice.Delta.builder()
                .toolCalls(List.of(toolCall))
                .build();
        return chunk(id, List.of(choice(delta, null)), null);
    }

    private static ChatCompletionChunk end(String id,
                                            ChatCompletionChunk.Choice.FinishReason finishReason,
                                            CompletionUsage usage) {
        return chunk(id, List.of(choice(ChatCompletionChunk.Choice.Delta.builder().build(),
                finishReason)), usage);
    }

    private static ChatCompletionChunk usageOnly(String id, CompletionUsage usage) {
        return chunk(id, List.of(), usage);
    }

    private static ChatCompletionChunk.Choice choice(ChatCompletionChunk.Choice.Delta delta,
                                                      ChatCompletionChunk.Choice.FinishReason reason) {
        ChatCompletionChunk.Choice.Builder builder = ChatCompletionChunk.Choice.builder()
                .index(0)
                .delta(delta);
        builder.finishReason(reason == null ? JsonField.ofNullable(null) : JsonField.of(reason));
        return builder.build();
    }

    private static ChatCompletionChunk chunk(String id, List<ChatCompletionChunk.Choice> choices,
                                              CompletionUsage usage) {
        ChatCompletionChunk.Builder builder = ChatCompletionChunk.builder()
                .id(id)
                .choices(choices)
                .created(1)
                .model("fixture-model")
                .object_(JsonValue.from("chat.completion.chunk"));
        if (usage != null) {
            builder.usage(usage);
        }
        return builder.build();
    }

    private static CompletionUsage usage(long prompt, long completion, long cached) {
        CompletionUsage.PromptTokensDetails details = CompletionUsage.PromptTokensDetails.builder()
                .cachedTokens(cached)
                .build();
        return CompletionUsage.builder()
                .promptTokens(prompt)
                .completionTokens(completion)
                .totalTokens(prompt + completion)
                .promptTokensDetails(details)
                .build();
    }

    private static CompletionUsage usageWithoutCacheDetails(long prompt, long completion) {
        return CompletionUsage.builder()
                .promptTokens(prompt)
                .completionTokens(completion)
                .totalTokens(prompt + completion)
                .build();
    }

    private static EventStream stream(List<ChatCompletionChunk> chunks) {
        return new EventStream(chunks, null);
    }

    private static EventStream failingStream(List<ChatCompletionChunk> chunks, String message) {
        return new EventStream(chunks, new IllegalStateException(message));
    }

    private record EventStream(List<ChatCompletionChunk> chunks, Throwable failure) {}

    private static final class SdkBoundary {
        private final OpenAIClient syncClient = mock(OpenAIClient.class);
        private final OpenAIClientAsync asyncClient = mock(OpenAIClientAsync.class);
        private final ChatServiceAsync chat = mock(ChatServiceAsync.class);
        private final ChatCompletionServiceAsync completions = mock(ChatCompletionServiceAsync.class);
        private final List<EventStream> rounds;
        private final AtomicInteger request = new AtomicInteger();

        private SdkBoundary(List<EventStream> rounds) {
            this.rounds = List.copyOf(rounds);
            when(asyncClient.chat()).thenReturn(chat);
            when(chat.completions()).thenReturn(completions);
            when(completions.createStreaming(any(ChatCompletionCreateParams.class))).thenAnswer(invocation -> {
                int index = request.getAndIncrement();
                if (index >= this.rounds.size()) {
                    throw new AssertionError("unexpected OpenAI request " + (index + 1));
                }
                return response(this.rounds.get(index));
            });
        }

        private static AsyncStreamResponse<ChatCompletionChunk> response(EventStream events) {
            return new AsyncStreamResponse<>() {
                private final CompletableFuture<Void> complete = new CompletableFuture<>();

                @Override
                public AsyncStreamResponse<ChatCompletionChunk> subscribe(
                        AsyncStreamResponse.Handler<? super ChatCompletionChunk> handler) {
                    events.chunks().forEach(handler::onNext);
                    if (events.failure() == null) {
                        complete.complete(null);
                    }
                    else {
                        complete.completeExceptionally(events.failure());
                    }
                    return this;
                }

                @Override
                public AsyncStreamResponse<ChatCompletionChunk> subscribe(
                        AsyncStreamResponse.Handler<? super ChatCompletionChunk> handler,
                        java.util.concurrent.Executor executor) {
                    executor.execute(() -> subscribe(handler));
                    return this;
                }

                @Override
                public CompletableFuture<Void> onCompleteFuture() {
                    return complete;
                }

                @Override
                public void close() {}
            };
        }

        private OpenAIClient syncClient() {
            return syncClient;
        }

        private OpenAIClientAsync asyncClient() {
            return asyncClient;
        }

        private ChatCompletionServiceAsync completions() {
            return completions;
        }

        private int requests() {
            return request.get();
        }
    }

    private static final class ProbeTools implements FplTool {
        private final AtomicInteger firstCalls = new AtomicInteger();
        private final AtomicInteger secondCalls = new AtomicInteger();

        @Tool
        String first() {
            firstCalls.incrementAndGet();
            return "first result";
        }

        @Tool
        String second() {
            secondCalls.incrementAndGet();
            return "second result";
        }

        private int firstCalls() {
            return firstCalls.get();
        }

        private int secondCalls() {
            return secondCalls.get();
        }
    }
}
