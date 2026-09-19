package dev.quantti.fplassistant.chat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import dev.quantti.fplassistant.myteam.ToolUser;
import dev.quantti.fplassistant.tools.FplTool;
import dev.quantti.fplassistant.trace.ChatTraceStore;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatTraceInstrumentationTest {

    private static final UUID USER = UUID.fromString("11111111-2222-4333-8444-555555555555");
    private static final UUID CONVERSATION = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");
    private static final UUID TRACE = UUID.fromString("99999999-8888-4777-8666-555555555555");

    @Test
    void toolLoopCapturesEffectivePromptsToolsSummariesTimingsTokensAndFinalAnswer() {
        ChatTraceStore store = mock(ChatTraceStore.class);
        AtomicReference<String> startThread = new AtomicReference<>();
        when(store.start(any(), any(), any())).thenAnswer(call -> {
            startThread.set(Thread.currentThread().getName());
            return TRACE;
        });
        AtomicReference<JsonNode> completed = new AtomicReference<>();
        AtomicReference<String> completeThread = new AtomicReference<>();
        when(store.complete(any(), any())).thenAnswer(call -> {
            completeThread.set(Thread.currentThread().getName());
            completed.set(call.getArgument(1));
            return true;
        });
        ChatTraceSession trace = new ChatTraceSession(store, USER, CONVERSATION);
        ContextTool tool = new ContextTool();
        ChatService service = new ChatService(ChatClient.builder(new ToolLoopModel())
                .defaultSystem("system instructions")
                .build(), List.of(tool), "openai", 15);

        String answer = service.stream(List.of(new ChatTurn(ChatTurn.USER, "question")), USER,
                        ignored -> {}, trace)
                .collectList().map(parts -> String.join("", parts)).block();
        trace.complete(answer);

        await(completed);
        JsonNode payload = completed.get();
        assertThat(payload.path("status").asText()).isEqualTo("completed");
        assertThat(payload.path("finalAnswer").asText()).isEqualTo("final answer");
        assertThat(payload.path("modelRounds")).hasSize(2);
        assertThat(payload.path("modelRounds").get(0).path("messages").toString())
                .contains("system instructions", "question");
        assertThat(payload.path("modelRounds").get(1).path("messages").toString())
                .contains("tool result", "lookup", "value", "x")
                .doesNotContain("private tool decision");
        assertThat(payload.path("modelRounds").get(0).path("tokens").path("input").asInt())
                .isEqualTo(80);
        assertThat(payload.path("modelRounds").get(0).path("durationMs").canConvertToLong()).isTrue();
        assertThat(payload.path("modelRounds").get(1).path("reasoningSummaries").get(0).asText())
                .isEqualTo("Checked the tool result.");
        assertThat(payload.toString()).doesNotContain("raw private chain", "signature-secret");
        assertThat(payload.path("tools")).singleElement().satisfies(node -> {
            assertThat(node.path("name").asText()).isEqualTo("lookup");
            assertThat(node.path("arguments").asText()).isEqualTo("{\"value\":\"x\"}");
            assertThat(node.path("result").asText()).isEqualTo("\"tool result\"");
        });
        assertThat(tool.contexts).singleElement().satisfies(context ->
                assertThat(context.getContext()).containsEntry(ToolUser.KEY, USER));
        verify(store).start(eq(USER), eq(CONVERSATION), org.mockito.ArgumentMatchers.any());
        assertThat(startThread.get()).startsWith("boundedElastic");
        assertThat(completeThread.get()).startsWith("boundedElastic");
    }

    @Test
    void failuresPersistPartialOutputAndRedactCredentialShapedErrorText() {
        ChatTraceStore store = mock(ChatTraceStore.class);
        when(store.start(any(), any(), any())).thenReturn(TRACE);
        AtomicReference<JsonNode> completed = new AtomicReference<>();
        when(store.complete(any(), any())).thenAnswer(call -> {
            completed.set(call.getArgument(1));
            return true;
        });
        ChatTraceSession trace = new ChatTraceSession(store, USER, CONVERSATION);

        trace.fail("partial answer password=hunter2", new IllegalStateException(
                "provider failed authorization: Bearer abc.def.ghi api_key=sk-secretsecret"));

        await(completed);
        JsonNode payload = completed.get();
        assertThat(payload.path("status").asText()).isEqualTo("error");
        assertThat(payload.path("partialAnswer").asText())
                .startsWith("partial answer")
                .contains("[REDACTED]")
                .doesNotContain("hunter2");
        assertThat(payload.path("error").path("type").asText())
                .isEqualTo(IllegalStateException.class.getName());
        assertThat(payload.path("error").path("message").asText())
                .contains("[REDACTED]")
                .doesNotContain("abc.def.ghi", "sk-secretsecret");
    }

    @Test
    void sanitizesCredentialsAcrossPersistedTextAndJsonStringLeaves() {
        ChatTraceCollector collector = new ChatTraceCollector();
        AssistantMessage thinkingToolCall = AssistantMessage.builder()
                .content("private tool decision password=thought-secret")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "lookup",
                        "{\"note\":\"Bearer leaf-secret\",\"api_key\":\"key-secret\",\"ordinary\":\"keep\"}")))
                .properties(Map.of("thinking", true, "signature", "metadata-secret"))
                .build();
        ToolResponseMessage toolResponse = ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("call-1", "lookup",
                        "{\"answer\":\"authorization=tool-secret\",\"ordinary\":\"result\"}")))
                .build();
        ChatTraceCollector.RoundHandle round = collector.startRound(new ChatClientRequest(
                new Prompt(List.of(new UserMessage("question password=prompt-secret"),
                        thinkingToolCall, toolResponse)), Map.of()));
        collector.capture(round, new ChatClientResponse(response("visible", List.of(),
                Map.of("reasoningSummary", "summary token=summary-secret"), 1, 1), Map.of()), null);
        collector.finishRound(round, null);
        ChatTraceCollector.ToolHandle tool = collector.startTool("lookup",
                "{\"nested\":[\"Bearer event-secret\"],\"ordinary\":\"argument\"}");
        collector.finishTool(tool,
                "{\"password\":\"result-secret\",\"ordinary\":\"event result\"}", null);
        collector.complete("answer sk-abcdefgh");

        String payload = collector.snapshot().toString();
        assertThat(payload).contains("question", "lookup", "keep", "result", "argument",
                        "event result", "[REDACTED]")
                .doesNotContain("prompt-secret", "thought-secret", "metadata-secret", "leaf-secret",
                        "key-secret", "tool-secret", "summary-secret", "event-secret",
                        "result-secret", "sk-abcdefgh", "private tool decision");
    }

    @Test
    void redactsQuotedCredentialsEmbeddedInPersistedProse() {
        ChatTraceCollector collector = new ChatTraceCollector();
        ChatTraceCollector.RoundHandle round = collector.startRound(new ChatClientRequest(
                new Prompt(List.of(new UserMessage(
                        "Before {\"password\":\"hunter2\"} after; \"password\" is ordinary text."))),
                Map.of()));
        collector.capture(round, new ChatClientResponse(response("visible", List.of(), Map.of(
                "reasoningSummary", "Summary { \"api_key\" : \" spaced value \" } remains."),
                1, 1), Map.of()), null);
        collector.finishRound(round, new IllegalStateException(
                "Failure {\"secret\":\"escaped \\\"quote\\\" value\"} follows."));
        collector.complete("Answer {\"access_token\":\"two words\"} done.");

        JsonNode payload = collector.snapshot();
        assertThat(payload.path("modelRounds").get(0).path("messages").get(0).path("content").asText())
                .isEqualTo("Before {\"password\":\"[REDACTED]\"} after; \"password\" is ordinary text.");
        assertThat(payload.path("modelRounds").get(0).path("reasoningSummaries").get(0).asText())
                .isEqualTo("Summary { \"api_key\" : \"[REDACTED]\" } remains.");
        assertThat(payload.path("modelRounds").get(0).path("error").path("message").asText())
                .isEqualTo("Failure {\"secret\":\"[REDACTED]\"} follows.");
        assertThat(payload.path("finalAnswer").asText())
                .isEqualTo("Answer {\"access_token\":\"[REDACTED]\"} done.");
        assertThat(payload.toString()).doesNotContain(
                "hunter2", "spaced value", "escaped", "quote", "two words");
    }

    @Test
    void visibleGeminiAnswerIsNotDroppedWhenThoughtSignatureIsPresent() {
        ChatModel model = new SingleResponseModel(response("visible Gemini answer", List.of(),
                Map.of("isThought", false, "thoughtSignatures", List.of(new byte[] {1, 2, 3})),
                1, 1));
        ChatService service = new ChatService(ChatClient.builder(model).build(), List.of(),
                "google-genai", 15);

        StepVerifier.create(service.stream(List.of(new ChatTurn(ChatTurn.USER, "question")), USER,
                        ignored -> {}))
                .expectNext("visible Gemini answer")
                .verifyComplete();
    }

    @Test
    void concurrentToolCallbacksDoNotLoseOrCrossWireEvents() {
        ChatTraceCollector collector = new ChatTraceCollector();
        ToolCallback delegate = new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("parallel").description("test")
                        .inputSchema("{}").build();
            }
            @Override public ToolMetadata getToolMetadata() { return ToolMetadata.builder().build(); }
            @Override public String call(String input) { return "result-" + input; }
        };
        TracingToolCallback callback = new TracingToolCallback(delegate, collector);

        CompletableFuture.allOf(
                CompletableFuture.runAsync(() -> callback.call("a")),
                CompletableFuture.runAsync(() -> callback.call("b"))).join();

        JsonNode tools = collector.snapshot().path("tools");
        assertThat(tools).hasSize(2);
        assertThat(tools.toString()).contains("result-a", "result-b");
        assertThat(tools.get(0).path("sequence").asLong())
                .isLessThan(tools.get(1).path("sequence").asLong());
    }

    private static void await(AtomicReference<JsonNode> value) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (value.get() == null && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        assertThat(value.get()).as("the asynchronous trace write completed").isNotNull();
    }

    private static ChatResponse response(String text, List<AssistantMessage.ToolCall> calls,
                                         Map<String, Object> properties, int prompt, int completion) {
        AssistantMessage output = AssistantMessage.builder().content(text).toolCalls(calls)
                .properties(properties).build();
        return new ChatResponse(List.of(new Generation(output)), ChatResponseMetadata.builder()
                .model("stub-model")
                .usage(new DefaultUsage(prompt, completion, null, null, 20L, 0L))
                .build());
    }

    private static final class ToolLoopModel implements ChatModel {
        private final AtomicInteger invocation = new AtomicInteger();

        @Override public ChatResponse call(Prompt prompt) { throw new UnsupportedOperationException(); }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            if (invocation.getAndIncrement() == 0) {
                return Flux.just(response("private tool decision",
                        List.of(new AssistantMessage.ToolCall("call-1", "function", "lookup",
                                "{\"value\":\"x\"}")),
                        Map.of("thinking", true, "signature", "signature-secret"), 100, 5));
            }
            return Flux.just(response("final answer", List.of(),
                    Map.of("reasoningSummary", "Checked the tool result.",
                            "reasoningContent", "raw private chain"), 60, 8));
        }

        @Override public ChatOptions getOptions() { return ToolCallingChatOptions.builder().build(); }
    }

    private record SingleResponseModel(ChatResponse response) implements ChatModel {
        @Override public ChatResponse call(Prompt prompt) { return response; }
        @Override public Flux<ChatResponse> stream(Prompt prompt) { return Flux.just(response); }
        @Override public ChatOptions getOptions() { return ToolCallingChatOptions.builder().build(); }
    }

    private static final class ContextTool implements FplTool {
        private final List<ToolContext> contexts = new CopyOnWriteArrayList<>();

        @Tool
        String lookup(String value, ToolContext context) {
            contexts.add(context);
            return "tool result";
        }
    }
}
