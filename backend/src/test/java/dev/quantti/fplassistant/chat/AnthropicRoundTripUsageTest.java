package dev.quantti.fplassistant.chat;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.AnthropicClientAsync;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.Headers;
import com.anthropic.core.http.HttpResponseFor;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.DirectCaller;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageDeltaUsage;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.RawContentBlockDeltaEvent;
import com.anthropic.models.messages.RawContentBlockStartEvent;
import com.anthropic.models.messages.RawContentBlockStopEvent;
import com.anthropic.models.messages.RawMessageDeltaEvent;
import com.anthropic.models.messages.RawMessageStartEvent;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.Usage;
import com.anthropic.services.async.MessageServiceAsync;
import dev.quantti.fplassistant.tools.FplTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Spliterators;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Provider-boundary regression for Spring AI 2.0.0 and anthropic-java-core 2.40.1. The SDK
 * clients are mocks, but every event is a real Anthropic SDK event and the provider model,
 * ChatClient advisor chain and tool loop are production objects.
 */
class AnthropicRoundTripUsageTest {

    private static final UUID USER = UUID.fromString("11111111-2222-4333-8444-555555555555");

    @Test
    void toolRoundAndFinalAnswerSumProviderMappedSnapshots() {
        SdkBoundary sdk = new SdkBoundary(List.of(
                events(List.of(
                        start(11, 70L, 20L),
                        toolStart("probe"),
                        toolArguments("{}"),
                        contentStop(),
                        delta(3, 7L, 2L, StopReason.TOOL_USE))),
                events(List.of(
                        start(13, null, null),
                        text("provider answer"),
                        delta(4, 0L, 0L, StopReason.END_TURN),
                        delta(4, 0L, 0L, StopReason.END_TURN)))));
        List<TokenUsage> reported = new ArrayList<>();
        ProbeTool tool = new ProbeTool();

        StepVerifier.create(service(sdk, List.of(tool)).stream(
                        List.of(new ChatTurn(ChatTurn.USER, "use the probe")), USER, reported::add))
                .expectNext("provider answer")
                .verifyComplete();

        assertThat(sdk.requests()).isEqualTo(2);
        assertThat(tool.calls()).isOne();
        assertThat(reported.getLast())
                .as("rounds: 11/7/2/3 + 13/0/0/4; repeated final cumulative delta counts once")
                .isEqualTo(new TokenUsage("fixture-model", 24, 7, 2, 7));
    }

    @Test
    void cacheFieldsReportedOnlyAtMessageStartRemainUnknown() {
        SdkBoundary sdk = new SdkBoundary(List.of(events(List.of(
                start(17, 9L, 4L),
                text("answer"),
                delta(3, null, null, StopReason.END_TURN)))));
        List<TokenUsage> reported = new ArrayList<>();

        StepVerifier.create(service(sdk, List.of()).stream(
                        List.of(new ChatTurn(ChatTurn.USER, "answer")), USER, reported::add))
                .expectNext("answer")
                .verifyComplete();

        assertThat(sdk.requests()).isOne();
        assertThat(reported.getLast())
                .as("Spring AI retains start input and delta output, but drops start-only cache fields")
                .isEqualTo(new TokenUsage("fixture-model", 17, null, null, 3));
    }

    @Test
    void failureBeforeUsageBearingMessageDeltaReportsNothing() {
        SdkBoundary sdk = new SdkBoundary(List.of(failingStream(
                List.of(start(19, 5L, 1L), text("partial")), "before message_delta")));
        List<TokenUsage> reported = new ArrayList<>();

        StepVerifier.create(service(sdk, List.of()).stream(
                        List.of(new ChatTurn(ChatTurn.USER, "fail")), USER, reported::add))
                .expectNext("partial")
                .expectErrorMessage("before message_delta")
                .verify();

        assertThat(sdk.requests()).isOne();
        assertThat(reported).isEmpty();
    }

    @Test
    void failureAfterUsageBearingMessageDeltaRetainsMappedUsage() {
        SdkBoundary sdk = new SdkBoundary(List.of(failingStream(
                List.of(start(23, null, null), delta(6, 8L, 3L, StopReason.END_TURN)),
                "after message_delta")));
        List<TokenUsage> reported = new ArrayList<>();

        StepVerifier.create(service(sdk, List.of()).stream(
                        List.of(new ChatTurn(ChatTurn.USER, "fail")), USER, reported::add))
                .expectErrorMessage("after message_delta")
                .verify();

        assertThat(sdk.requests()).isOne();
        assertThat(reported.getLast())
                .as("one mapped round before failure: input=23, read=8, write=3, output=6")
                .isEqualTo(new TokenUsage("fixture-model", 23, 8, 3, 6));
    }

    private static ChatService service(SdkBoundary sdk, List<FplTool> tools) {
        AnthropicChatOptions options = AnthropicChatOptions.builder()
                .apiKey("unused-fixture-key")
                .model(Model.of("fixture-model"))
                .maxTokens(100)
                .build();
        AnthropicChatModel model = AnthropicChatModel.builder()
                .anthropicClient(sdk.syncClient())
                .anthropicClientAsync(sdk.asyncClient())
                .options(options)
                .build();
        return new ChatService(ChatClient.builder(model).build(), tools, "anthropic", 1);
    }

    private static RawMessageStreamEvent start(int input, Long cacheRead, Long cacheWrite) {
        Usage.Builder usage = Usage.builder()
                .cacheCreation(Optional.empty())
                .cacheCreationInputTokens(Optional.ofNullable(cacheWrite))
                .cacheReadInputTokens(Optional.ofNullable(cacheRead))
                .inferenceGeo(Optional.empty())
                .inputTokens(input)
                .outputTokens(0)
                .outputTokensDetails(Optional.empty())
                .serverToolUse(Optional.empty())
                .serviceTier(Optional.empty());
        Message message = Message.builder()
                .id("same-id")
                .container(Optional.empty())
                .content(List.of())
                .model("fixture-model")
                .role(JsonValue.from("assistant"))
                .stopDetails(Optional.empty())
                .stopReason(Optional.empty())
                .stopSequence(Optional.empty())
                .type(JsonValue.from("message"))
                .usage(usage.build())
                .build();
        return RawMessageStreamEvent.ofMessageStart(RawMessageStartEvent.builder()
                .message(message)
                .type(JsonValue.from("message_start"))
                .build());
    }

    private static RawMessageStreamEvent toolStart(String name) {
        ToolUseBlock tool = ToolUseBlock.builder()
                .id("same-tool-id")
                .caller(DirectCaller.builder().type(JsonValue.from("direct")).build())
                .name(name)
                .input(JsonValue.from(java.util.Map.of()))
                .type(JsonValue.from("tool_use"))
                .build();
        return RawMessageStreamEvent.ofContentBlockStart(RawContentBlockStartEvent.builder()
                .index(0)
                .contentBlock(tool)
                .type(JsonValue.from("content_block_start"))
                .build());
    }

    private static RawMessageStreamEvent toolArguments(String json) {
        return RawMessageStreamEvent.ofContentBlockDelta(RawContentBlockDeltaEvent.builder()
                .index(0)
                .inputJsonDelta(json)
                .type(JsonValue.from("content_block_delta"))
                .build());
    }

    private static RawMessageStreamEvent text(String value) {
        return RawMessageStreamEvent.ofContentBlockDelta(RawContentBlockDeltaEvent.builder()
                .index(0)
                .textDelta(value)
                .type(JsonValue.from("content_block_delta"))
                .build());
    }

    private static RawMessageStreamEvent contentStop() {
        return RawMessageStreamEvent.ofContentBlockStop(RawContentBlockStopEvent.builder()
                .index(0)
                .type(JsonValue.from("content_block_stop"))
                .build());
    }

    private static RawMessageStreamEvent delta(int output, Long cacheRead, Long cacheWrite,
                                                StopReason stopReason) {
        MessageDeltaUsage.Builder usage = MessageDeltaUsage.builder()
                .cacheCreationInputTokens(Optional.ofNullable(cacheWrite))
                .cacheReadInputTokens(Optional.ofNullable(cacheRead))
                .inputTokens(Optional.empty())
                .outputTokens(output)
                .outputTokensDetails(Optional.empty())
                .serverToolUse(Optional.empty());
        RawMessageDeltaEvent.Delta eventDelta = RawMessageDeltaEvent.Delta.builder()
                .container(Optional.empty())
                .stopDetails(Optional.empty())
                .stopReason(stopReason)
                .stopSequence(Optional.empty())
                .build();
        return RawMessageStreamEvent.ofMessageDelta(RawMessageDeltaEvent.builder()
                .delta(eventDelta)
                .usage(usage.build())
                .type(JsonValue.from("message_delta"))
                .build());
    }

    private static EventStream events(List<RawMessageStreamEvent> events) {
        return events::stream;
    }

    private static EventStream failingStream(List<RawMessageStreamEvent> events, String message) {
        return () -> {
            Iterator<RawMessageStreamEvent> delegate = events.iterator();
            Iterator<RawMessageStreamEvent> failing = new Iterator<>() {
                @Override
                public boolean hasNext() {
                    if (delegate.hasNext()) {
                        return true;
                    }
                    throw new IllegalStateException(message);
                }

                @Override
                public RawMessageStreamEvent next() {
                    return delegate.next();
                }
            };
            return StreamSupport.stream(
                    Spliterators.spliteratorUnknownSize(failing, 0), false);
        };
    }

    @FunctionalInterface
    private interface EventStream {
        Stream<RawMessageStreamEvent> open();
    }

    private static final class SdkBoundary {
        private final AnthropicClient syncClient = mock(AnthropicClient.class);
        private final AnthropicClientAsync asyncClient = mock(AnthropicClientAsync.class);
        private final MessageServiceAsync messages = mock(MessageServiceAsync.class);
        private final MessageServiceAsync.WithRawResponse rawMessages =
                mock(MessageServiceAsync.WithRawResponse.class);
        private final List<EventStream> rounds;
        private final AtomicInteger request = new AtomicInteger();

        private SdkBoundary(List<EventStream> rounds) {
            this.rounds = List.copyOf(rounds);
            when(asyncClient.messages()).thenReturn(messages);
            when(messages.withRawResponse()).thenReturn(rawMessages);
            when(rawMessages.createStreaming(any(MessageCreateParams.class))).thenAnswer(invocation -> {
                int index = request.getAndIncrement();
                if (index >= this.rounds.size()) {
                    return CompletableFuture.failedFuture(
                            new AssertionError("unexpected Anthropic request " + (index + 1)));
                }
                return CompletableFuture.completedFuture(response(this.rounds.get(index).open()));
            });
        }

        @SuppressWarnings("unchecked")
        private static HttpResponseFor<StreamResponse<RawMessageStreamEvent>> response(
                Stream<RawMessageStreamEvent> events) {
            StreamResponse<RawMessageStreamEvent> streamResponse = mock(StreamResponse.class);
            when(streamResponse.stream()).thenReturn(events);
            HttpResponseFor<StreamResponse<RawMessageStreamEvent>> response = mock(HttpResponseFor.class);
            when(response.headers()).thenReturn(Headers.builder().build());
            when(response.parse()).thenReturn(streamResponse);
            return response;
        }

        private AnthropicClient syncClient() {
            return syncClient;
        }

        private AnthropicClientAsync asyncClient() {
            return asyncClient;
        }

        private int requests() {
            return request.get();
        }
    }

    private static final class ProbeTool implements FplTool {
        private final AtomicInteger calls = new AtomicInteger();

        @Tool
        String probe() {
            calls.incrementAndGet();
            return "probe result";
        }

        private int calls() {
            return calls.get();
        }
    }
}
