package fi.karivanttinen.fplassistant.chat;

import fi.karivanttinen.fplassistant.myteam.FplBudget;
import fi.karivanttinen.fplassistant.myteam.ToolUser;
import fi.karivanttinen.fplassistant.tools.FplTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.execution.ToolCallResultConverter;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runtime characterization of Spring AI 2.0.0's real streaming tool loop. The model is local and
 * deterministic, but the ChatClient, advisor chain, tool execution, recursion and filtering are
 * the production framework objects rather than a mocked final client flux.
 */
class TurnUsageAdvisorTest {

    private static final UUID USER = UUID.fromString("11111111-2222-4333-8444-555555555555");

    @Test
    void twoToolRoundsAndFinalAnswerMustSumCoherentRoundSnapshots() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                Flux.just(
                        usageOnly(100, 10, 40, 5, "round-1"),
                        toolCall("first", "firstTool", 100, 10, 40, 5, "round-1")),
                Flux.just(
                        usageOnly(60, 8, 20, 2, "round-2"),
                        toolCall("second", "secondTool", 60, 8, 20, 2, "round-2")),
                Flux.just(
                        modelOnly("final"),
                        answer("The ", 50, 10, 10, 0, "final"),
                        answer("answer.", 50, 10, 10, 0, "final"))));
        BoundaryProbe probe = new BoundaryProbe();
        TwoTools tools = new TwoTools();
        ChatService service = service(model, probe, List.of(tools));
        List<TokenUsage> reported = new ArrayList<>();

        StepVerifier.create(service.stream(
                        List.of(new ChatTurn(ChatTurn.USER, "use both tools")), USER, reported::add))
                .expectNext("The ", "answer.")
                .verifyComplete();

        assertThat(model.invocations()).isEqualTo(3);
        assertThat(tools.calls()).containsExactly("first", "second");
        assertThat(tools.users()).containsOnly(USER);
        assertThat(tools.budgets()).hasSize(2);
        assertThat(tools.budgets().get(0)).isSameAs(tools.budgets().get(1));
        assertThat(tools.contextKeys()).allSatisfy(keys -> assertThat(keys)
                .containsExactlyInAnyOrder(ToolUser.KEY, ToolUser.BUDGET));
        assertThat(model.prompts()).allSatisfy(prompt -> {
            assertThat(prompt.getInstructions()).extracting(Message::getText)
                    .as("accounting state never becomes provider-visible message content")
                    .allMatch(text -> text == null || !text.contains("TurnUsageAdvisor"));
            assertThat(((ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks())
                    .allSatisfy(callback -> assertThat(callback.getToolDefinition().inputSchema())
                            .as("accounting and request identity stay out of tool schemas")
                            .doesNotContain("TurnUsageAdvisor", "TokenUsage", "onUsage", "userId", "budget"));
        });
        assertThat(probe.invocations()).as("the advisor immediately inside ToolCallingAdvisor")
                .isEqualTo(3);
        assertThat(probe.responseIds()).as("tool-call rounds are visible before outer filtering")
                .contains("round-1", "round-2", "final");
        assertThat(probe.responsesPerInvocation())
                .as("repeated snapshots and metadata-only chunks stay inside their request")
                .containsExactly(2, 2, 3);

        assertThat(reported.getLast())
                .as("100/40/5/10 + 60/20/2/8 + 50/10/0/10, with cached prompt removed")
                .isEqualTo(new TokenUsage("stub-model", 140, 70, 7, 28));
    }

    @Test
    void noToolCallIsOneRequestWithUnchangedTextAndNormalizedUsage() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(Flux.just(
                modelOnly("single"),
                answer("plain answer", 20, 4, 5, 0, "single"))));
        BoundaryProbe probe = new BoundaryProbe();
        List<TokenUsage> reported = new ArrayList<>();

        StepVerifier.create(service(model, probe, List.of()).stream(
                        List.of(new ChatTurn(ChatTurn.USER, "answer directly")), USER, reported::add))
                .expectNext("plain answer")
                .verifyComplete();

        assertThat(model.invocations()).isOne();
        assertThat(probe.invocations()).isOne();
        assertThat(reported.getLast()).isEqualTo(new TokenUsage("stub-model", 15, 5, 0, 4));
    }

    @Test
    void repeatedSubscriptionsCreateIndependentTurnState() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                Flux.just(answer("one", 20, 4, 5, 0, "first")),
                Flux.just(answer("two", 20, 4, 5, 0, "second"))));
        List<TokenUsage> reported = new ArrayList<>();
        Flux<String> turn = service(model, new BoundaryProbe(), List.of()).stream(
                List.of(new ChatTurn(ChatTurn.USER, "same publisher")), USER, reported::add);

        StepVerifier.create(turn).expectNext("one").verifyComplete();
        assertThat(reported.getLast()).isEqualTo(new TokenUsage("stub-model", 15, 5, 0, 4));
        StepVerifier.create(turn).expectNext("two").verifyComplete();

        assertThat(model.invocations()).isEqualTo(2);
        assertThat(reported.getLast())
                .as("the second subscription must not inherit the first subscription's slot")
                .isEqualTo(new TokenUsage("stub-model", 15, 5, 0, 4));
    }

    @Test
    void concurrentTurnsDoNotShareSlotsOrUsage() {
        ChatModel model = new ConcurrentChatModel();
        ChatService service = new ChatService(ChatClient.builder(model).build(), List.of(), "openai", 15);
        List<TokenUsage> first = new CopyOnWriteArrayList<>();
        List<TokenUsage> second = new CopyOnWriteArrayList<>();

        CompletableFuture<Void> a = CompletableFuture.runAsync(() -> service.stream(
                List.of(new ChatTurn(ChatTurn.USER, "turn-a")), USER, first::add).blockLast());
        CompletableFuture<Void> b = CompletableFuture.runAsync(() -> service.stream(
                List.of(new ChatTurn(ChatTurn.USER, "turn-b")), USER, second::add).blockLast());
        CompletableFuture.allOf(a, b).join();

        assertThat(first.getLast()).isEqualTo(new TokenUsage("stub-model", 15, 5, 0, 4));
        assertThat(second.getLast()).isEqualTo(new TokenUsage("stub-model", 30, 10, 0, 8));
    }

    @Test
    void failureBeforeUsageReportsNothing() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                Flux.error(new IllegalStateException("failed before usage"))));
        List<TokenUsage> reported = new ArrayList<>();

        StepVerifier.create(service(model, new BoundaryProbe(), List.of()).stream(
                        List.of(new ChatTurn(ChatTurn.USER, "fail")), USER, reported::add))
                .expectErrorMessage("failed before usage")
                .verify();

        assertThat(reported).isEmpty();
    }

    @Test
    void laterRoundFailureBeforeUsageKeepsEarlierRound() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                Flux.just(toolCall("first", "firstTool", 100, 10, 40, 5, "round-1")),
                Flux.error(new IllegalStateException("later round failed before usage"))));
        List<TokenUsage> reported = new ArrayList<>();

        StepVerifier.create(service(model, new BoundaryProbe(), List.of(new TwoTools())).stream(
                        List.of(new ChatTurn(ChatTurn.USER, "fail later")), USER, reported::add))
                .expectErrorMessage("later round failed before usage")
                .verify();

        assertThat(reported.getLast()).isEqualTo(new TokenUsage("stub-model", 60, 40, 5, 10));
    }

    @Test
    void failureAfterUsageKeepsEarlierRoundsAndThePartialRound() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                Flux.just(toolCall("first", "firstTool", 100, 10, 40, 5, "round-1")),
                Flux.concat(
                        Flux.just(usageOnly(60, 8, 20, 2, "round-2")),
                        Flux.error(new IllegalStateException("failed after usage")))));
        List<TokenUsage> reported = new ArrayList<>();

        StepVerifier.create(service(model, new BoundaryProbe(), List.of(new TwoTools())).stream(
                        List.of(new ChatTurn(ChatTurn.USER, "fail later")), USER, reported::add))
                .expectErrorMessage("failed after usage")
                .verify();

        assertThat(reported.getLast()).isEqualTo(new TokenUsage("stub-model", 100, 60, 7, 18));
    }

    @Test
    void returnDirectToolResultDoesNotInventAFollowUpModelRequest() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(Flux.just(
                toolCall("direct", "directTool", 12, 3, null, null, "direct"))));
        BoundaryProbe probe = new BoundaryProbe();
        DirectTool tool = new DirectTool();
        List<TokenUsage> reported = new ArrayList<>();

        StepVerifier.create(service(model, probe, List.of(tool)).stream(
                        List.of(new ChatTurn(ChatTurn.USER, "return the tool result")), USER, reported::add))
                .expectNext("direct result")
                .verifyComplete();

        assertThat(tool.calls()).isOne();
        assertThat(model.invocations()).isOne();
        assertThat(probe.invocations()).isOne();
        assertThat(reported.getLast()).isEqualTo(new TokenUsage("stub-model", 12, null, null, 3));
    }

    private static ChatService service(ScriptedChatModel model, BoundaryProbe probe,
                                       List<FplTool> tools) {
        ChatClient client = ChatClient.builder(model)
                .defaultAdvisors(probe)
                .build();
        return new ChatService(client, tools, "openai", 15);
    }

    private static ChatResponse usageOnly(int prompt, int completion, Integer cacheReads,
                                          Integer cacheWrites, String id) {
        return response(null, List.of(), prompt, completion, cacheReads, cacheWrites, id);
    }

    private static ChatResponse modelOnly(String id) {
        return new ChatResponse(List.of(), ChatResponseMetadata.builder()
                .id(id)
                .model("stub-model")
                .build());
    }

    private static ChatResponse answer(String text, int prompt, int completion,
                                       Integer cacheReads, Integer cacheWrites, String id) {
        return response(text, List.of(), prompt, completion, cacheReads, cacheWrites, id);
    }

    private static ChatResponse toolCall(String callId, String name, int prompt, int completion,
                                         Integer cacheReads, Integer cacheWrites, String responseId) {
        return response(null, List.of(new AssistantMessage.ToolCall(callId, "function", name, "{}")),
                prompt, completion, cacheReads, cacheWrites, responseId);
    }

    private static ChatResponse response(String text, List<AssistantMessage.ToolCall> toolCalls,
                                         int prompt, int completion, Integer cacheReads,
                                         Integer cacheWrites, String id) {
        AssistantMessage output = AssistantMessage.builder()
                .content(text)
                .toolCalls(toolCalls)
                .build();
        return new ChatResponse(List.of(new Generation(output)), ChatResponseMetadata.builder()
                .id(id)
                .model("stub-model")
                .usage(new DefaultUsage(prompt, completion, null, null,
                        cacheReads == null ? null : cacheReads.longValue(),
                        cacheWrites == null ? null : cacheWrites.longValue()))
                .build());
    }

    private static final class ScriptedChatModel implements ChatModel {
        private final List<Flux<ChatResponse>> rounds;
        private final AtomicInteger invocation = new AtomicInteger();
        private final List<Prompt> prompts = new CopyOnWriteArrayList<>();

        private ScriptedChatModel(List<Flux<ChatResponse>> rounds) {
            this.rounds = rounds;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException("the test exercises streaming only");
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            assertThat(prompt.getOptions()).isInstanceOf(ToolCallingChatOptions.class);
            prompts.add(prompt);
            int round = invocation.getAndIncrement();
            if (round >= rounds.size()) {
                return Flux.error(new AssertionError("unexpected model invocation " + (round + 1)));
            }
            return rounds.get(round);
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }

        private int invocations() {
            return invocation.get();
        }

        private List<Prompt> prompts() {
            return prompts;
        }
    }

    private static final class ConcurrentChatModel implements ChatModel {
        @Override
        public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException("the test exercises streaming only");
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            boolean first = prompt.getContents().contains("turn-a");
            return Flux.just(answer(first ? "a" : "b", first ? 20 : 40, first ? 4 : 8,
                    first ? 5 : 10, 0, first ? "a" : "b"));
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }
    }

    /** Test-only probe at the boundary Task 2 will use; it performs no accounting. */
    private static final class BoundaryProbe implements StreamAdvisor {
        private final AtomicInteger invocations = new AtomicInteger();
        private final List<Integer> responsesPerInvocation = new CopyOnWriteArrayList<>();
        private final List<String> responseIds = new CopyOnWriteArrayList<>();

        @Override
        public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
            invocations.incrementAndGet();
            AtomicInteger responses = new AtomicInteger();
            return chain.nextStream(request)
                    .doOnNext(clientResponse -> {
                        responses.incrementAndGet();
                        if (clientResponse.chatResponse() != null
                                && clientResponse.chatResponse().getMetadata().getId() != null) {
                            responseIds.add(clientResponse.chatResponse().getMetadata().getId());
                        }
                    })
                    .doOnComplete(() -> responsesPerInvocation.add(responses.get()));
        }

        @Override
        public int getOrder() {
            return ToolCallingAdvisor.DEFAULT_ORDER + 1;
        }

        @Override
        public String getName() {
            return "KAR-48 request-boundary probe";
        }

        private int invocations() {
            return invocations.get();
        }

        private List<Integer> responsesPerInvocation() {
            return responsesPerInvocation;
        }

        private List<String> responseIds() {
            return responseIds;
        }
    }

    private static final class TwoTools implements FplTool {
        private final List<String> calls = new CopyOnWriteArrayList<>();
        private final List<UUID> users = new CopyOnWriteArrayList<>();
        private final List<FplBudget> budgets = new CopyOnWriteArrayList<>();
        private final List<java.util.Set<String>> contextKeys = new CopyOnWriteArrayList<>();

        @Tool
        String firstTool(ToolContext context) {
            recordContext(context);
            calls.add("first");
            return "first result";
        }

        @Tool
        String secondTool(ToolContext context) {
            recordContext(context);
            calls.add("second");
            return "second result";
        }

        private void recordContext(ToolContext context) {
            users.add(ToolUser.required(context));
            budgets.add(ToolUser.budget(context, 99));
            contextKeys.add(context.getContext().keySet());
        }

        private List<String> calls() {
            return calls;
        }

        private List<UUID> users() {
            return users;
        }

        private List<FplBudget> budgets() {
            return budgets;
        }

        private List<java.util.Set<String>> contextKeys() {
            return contextKeys;
        }
    }

    private static final class DirectTool implements FplTool {
        private final AtomicInteger calls = new AtomicInteger();

        @Tool(returnDirect = true, resultConverter = RawStringConverter.class)
        String directTool() {
            calls.incrementAndGet();
            return "direct result";
        }

        private int calls() {
            return calls.get();
        }
    }

    public static final class RawStringConverter implements ToolCallResultConverter {
        @Override
        public String convert(Object result, java.lang.reflect.Type returnType) {
            return result.toString();
        }
    }
}
