package dev.quantti.fplassistant.chat;

import dev.quantti.fplassistant.tools.FplTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    /** Anthropic unless a test says otherwise: the two differ in how a cached prompt is counted. */
    private String provider = "anthropic";

    /** Whoever is asking. The id itself is only interesting to the my-team tools. */
    private static final UUID USER = UUID.fromString("11111111-2222-4333-8444-555555555555");

    @Mock
    ChatClient chatClient;

    @Mock
    ChatClient.ChatClientRequestSpec requestSpec;

    @Mock
    ChatClient.StreamResponseSpec streamSpec;

    /** Text-only chunks, which is what most of these tests care about. */
    private ChatService serviceStreaming(List<FplTool> tools, Flux<String> content) {
        return service(tools, content.map(ChatServiceTest::response));
    }

    private ChatService service(List<FplTool> tools, Flux<ChatResponse> responses) {
        AtomicReference<StreamAdvisor> usageAdvisor = new AtomicReference<>();
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.messages(anyList())).thenReturn(requestSpec);
        when(requestSpec.tools(any(Object[].class))).thenReturn(requestSpec);
        when(requestSpec.toolContext(anyMap())).thenReturn(requestSpec);
        when(requestSpec.advisors(any(Advisor[].class))).thenAnswer(invocation -> {
            usageAdvisor.set(invocation.getArgument(0));
            return requestSpec;
        });
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.chatResponse()).thenAnswer(invocation -> {
            StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
            when(chain.nextStream(any())).thenReturn(responses.map(
                    response -> new ChatClientResponse(response, Map.of())));
            return usageAdvisor.get()
                    .adviseStream(mock(ChatClientRequest.class), chain)
                    .map(ChatClientResponse::chatResponse);
        });
        return new ChatService(chatClient, tools, provider, 15);
    }

    /** A chunk carrying text, as the provider streams it. */
    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    /** A chunk carrying only usage — what the last chunk of a real stream looks like. */
    private static ChatResponse usage(Integer prompt, Integer completion, Long cacheReads) {
        return usage(prompt, completion, cacheReads, null);
    }

    private static ChatResponse usage(Integer prompt, Integer completion, Long cacheReads,
                                      Long cacheWrites) {
        return new ChatResponse(List.of(),
                ChatResponseMetadata.builder()
                        .usage(new DefaultUsage(prompt, completion, null, null, cacheReads, cacheWrites))
                        .build());
    }

    /** A usage chunk as the google-genai path emits it: GoogleGenAiUsage, not DefaultUsage. */
    private static ChatResponse geminiUsage(Integer prompt, Integer candidates,
                                            Integer thoughts, Integer cached) {
        var builder = com.google.genai.types.GenerateContentResponseUsageMetadata.builder()
                .promptTokenCount(prompt)
                .candidatesTokenCount(candidates)
                .totalTokenCount(prompt + candidates + (thoughts == null ? 0 : thoughts));
        if (thoughts != null) {
            builder = builder.thoughtsTokenCount(thoughts);
        }
        if (cached != null) {
            builder = builder.cachedContentTokenCount(cached);
        }
        return new ChatResponse(List.of(),
                ChatResponseMetadata.builder()
                        .usage(org.springframework.ai.google.genai.metadata.GoogleGenAiUsage
                                .from(builder.build()))
                        .build());
    }

    /** Nothing to record: the usage callback must not fire with an empty measurement. */
    private static final java.util.function.Consumer<TokenUsage> IGNORE_USAGE = usage -> {};

    /**
     * The tools learn who is asking from the tool context and from nowhere else — a parameter
     * would be filled in by the model, and the model's input is the user's own text.
     */
    @Test
    void theRequestCarriesTheUserIdAndAFreshCallBudget() {
        ChatService service = serviceStreaming(List.of(), Flux.empty());

        service.stream(List.of(new ChatTurn(ChatTurn.USER, "how is my team?")), USER, IGNORE_USAGE)
                .blockLast();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> captor =
                ArgumentCaptor.forClass(java.util.Map.class);
        verify(requestSpec).toolContext(captor.capture());
        assertThat(captor.getValue())
                .containsEntry(dev.quantti.fplassistant.myteam.ToolUser.KEY, USER);
        assertThat(captor.getValue().get(dev.quantti.fplassistant.myteam.ToolUser.BUDGET))
                .isInstanceOfSatisfying(dev.quantti.fplassistant.myteam.FplBudget.class,
                        budget -> assertThat(budget.spent()).isZero());
    }

    @Test
    void streamReturnsFluxFromChatClient() {
        ChatService service = serviceStreaming(List.of(), Flux.just("Hello", " FPL", " fan"));

        Flux<String> result = service.stream(
                List.of(new ChatTurn(ChatTurn.USER, "Who should I captain?")), USER, IGNORE_USAGE);

        StepVerifier.create(result)
                .expectNext("Hello", " FPL", " fan")
                .verifyComplete();
    }

    /**
     * The whole history is sent — bounding it is HistoryBudget's job — but it must still open on
     * a user turn: the Anthropic API rejects a history that starts with an assistant message,
     * and dropping the oldest turns to fit can leave one there.
     */
    @Test
    void theHistoryIsSentWholeAndAlwaysOpensOnAUserMessage() {
        ChatService service = serviceStreaming(List.of(), Flux.empty());
        List<ChatTurn> turns = IntStream.range(0, 25)
                .mapToObj(i -> new ChatTurn(i % 2 == 0 ? ChatTurn.ASSISTANT : ChatTurn.USER, "msg-" + i))
                .toList();

        service.stream(turns, USER, IGNORE_USAGE).blockLast();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(requestSpec).messages(captor.capture());
        List<Message> sent = captor.getValue();
        assertThat(sent).as("everything except the leading assistant turn").hasSize(24);
        assertThat(sent.getFirst().getText()).isEqualTo("msg-1");
        assertThat(sent.getFirst()).isInstanceOf(UserMessage.class);
        assertThat(sent.getLast().getText()).isEqualTo("msg-24");
        assertThat(sent.getLast()).isInstanceOf(AssistantMessage.class);
    }

    /**
     * A failed answer leaves the user's message stored with nothing after it, so the next
     * question lands directly behind it. Anthropic rejects two user turns in a row; the frontend
     * used to merge them from the history it held, and holds none since Task 4.
     */
    @Test
    void consecutiveTurnsOfTheSameRoleAreMerged() {
        ChatService service = serviceStreaming(List.of(), Flux.empty());

        service.stream(List.of(
                new ChatTurn(ChatTurn.USER, "first question"),
                new ChatTurn(ChatTurn.USER, "second question"),
                new ChatTurn(ChatTurn.ASSISTANT, "an answer")), USER, IGNORE_USAGE).blockLast();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(requestSpec).messages(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue().getFirst().getText())
                .isEqualTo("first question\n\nsecond question");
    }

    /** Within one model invocation, the richest cumulative chunk is kept as one whole snapshot. */
    @Test
    void theRichestChunksMeasurementIsTheOneKept() {
        ChatService service = service(List.of(), Flux.just(
                response("Hello"),
                usage(1200, 100, 900L),
                response(" world"),
                usage(1200, 300, 900L)));

        List<TokenUsage> reported = new java.util.ArrayList<>();
        StepVerifier.create(service.stream(List.of(new ChatTurn(ChatTurn.USER, "hi")), USER, reported::add))
                .expectNext("Hello", " world")
                .verifyComplete();

        assertThat(reported.getLast()).isEqualTo(new TokenUsage(null, 1200, 900, null, 300));
    }

    /**
     * Cache writes are billed at a premium and cache reads at a fraction, and Anthropic reports
     * the prompt's uncached remainder as the input count — a cost computed without the write
     * figure is always an underestimate.
     */
    @Test
    void cacheWritesAreCapturedAlongsideReads() {
        ChatService service = service(List.of(), Flux.just(
                response("text"),
                usage(400, 250, 900L, 1600L)));

        List<TokenUsage> reported = new java.util.ArrayList<>();
        StepVerifier.create(service.stream(List.of(new ChatTurn(ChatTurn.USER, "hi")), USER, reported::add))
                .expectNext("text")
                .verifyComplete();

        assertThat(reported.getLast()).isEqualTo(new TokenUsage(null, 400, 900, 1600, 250));
    }

    /**
     * Spring AI's EmptyUsage reports zero prompt and completion tokens on every chunk, and some
     * providers stamp the model name on every chunk as well. Taken together that would write a
     * measured-looking zero into every row — the exact thing null exists to distinguish.
     */
    @Test
    void aChunkCarryingOnlyAModelNameRecordsNoZeroes() {
        ChatResponse named = new ChatResponse(
                List.of(new Generation(new AssistantMessage("text"))),
                ChatResponseMetadata.builder()
                        .model("some-model")
                        .usage(new org.springframework.ai.chat.metadata.EmptyUsage())
                        .build());
        ChatService service = service(List.of(), Flux.just(named));

        List<TokenUsage> reported = new java.util.ArrayList<>();
        StepVerifier.create(service.stream(List.of(new ChatTurn(ChatTurn.USER, "hi")), USER, reported::add))
                .expectNext("text")
                .verifyComplete();

        assertThat(reported.getLast())
                .isEqualTo(new TokenUsage("some-model", null, null, null, null));
    }

    /**
     * OpenAI counts cached tokens inside prompt_tokens and reports them again as cache reads;
     * Anthropic reports the uncached remainder. Storing OpenAI's number as-is would count the
     * cached part twice in every total.
     */
    @Test
    void openAisCachedTokensAreTakenOutOfTheInputCount() {
        provider = "openai";
        ChatService service = service(List.of(), Flux.just(
                response("text"),
                usage(1000, 200, 400L)));

        List<TokenUsage> reported = new java.util.ArrayList<>();
        StepVerifier.create(service.stream(List.of(new ChatTurn(ChatTurn.USER, "hi")), USER, reported::add))
                .expectNext("text")
                .verifyComplete();

        assertThat(reported.getLast())
                .as("1000 prompt tokens of which 400 were cached is 600 uncached, not 1000")
                .isEqualTo(new TokenUsage(null, 600, 400, null, 200));
    }

    @Test
    void anthropicsInputCountIsAlreadyTheUncachedRemainder() {
        ChatService service = service(List.of(), Flux.just(
                response("text"),
                usage(1000, 200, 400L)));

        List<TokenUsage> reported = new java.util.ArrayList<>();
        StepVerifier.create(service.stream(List.of(new ChatTurn(ChatTurn.USER, "hi")), USER, reported::add))
                .expectNext("text")
                .verifyComplete();

        assertThat(reported.getLast()).isEqualTo(new TokenUsage(null, 1000, 400, null, 200));
    }

    /**
     * Gemini reports the prompt with cache reads counted inside it (like OpenAI) and thinking
     * tokens beside the completion, not in it. Stored: the uncached remainder, and an output
     * that includes the thoughts — they are billed as output, and total_tokens is a generated
     * sum of the four columns, so a thought not stored here is a thought not billed anywhere.
     */
    @Test
    void geminiThoughtsLandInOutputAndCachedInsideThePromptIsUnfolded() {
        provider = "google-genai";
        ChatService service = service(List.of(), Flux.just(
                response("text"),
                geminiUsage(7550, 700, 1500, 4450)));

        List<TokenUsage> reported = new java.util.ArrayList<>();
        StepVerifier.create(service.stream(List.of(new ChatTurn(ChatTurn.USER, "hi")), USER, reported::add))
                .expectNext("text")
                .verifyComplete();

        assertThat(reported.getLast())
                .as("7550 prompt less 4450 cached is 3100 uncached; 700 answer plus 1500 thoughts is 2200 out")
                .isEqualTo(new TokenUsage(null, 3100, 4450, null, 2200));
    }

    @Test
    void geminiWithoutThoughtsKeepsThePlainCompletionCount() {
        provider = "google-genai";
        ChatService service = service(List.of(), Flux.just(
                response("text"),
                geminiUsage(1000, 700, null, null)));

        List<TokenUsage> reported = new java.util.ArrayList<>();
        StepVerifier.create(service.stream(List.of(new ChatTurn(ChatTurn.USER, "hi")), USER, reported::add))
                .expectNext("text")
                .verifyComplete();

        assertThat(reported.getLast()).isEqualTo(new TokenUsage(null, 1000, null, null, 700));
    }

    /** A cache miss is a measurement: zero reads must be stored as zero, not null. */
    @Test
    void geminiCacheMissIsRecordedAsZeroNotNull() {
        provider = "google-genai";
        ChatService service = service(List.of(), Flux.just(
                geminiUsage(1000, 700, 1500, 0)));

        List<TokenUsage> reported = new java.util.ArrayList<>();
        StepVerifier.create(service.stream(List.of(new ChatTurn(ChatTurn.USER, "hi")), USER, reported::add))
                .verifyComplete();

        assertThat(reported.getLast()).isEqualTo(new TokenUsage(null, 1000, 0, null, 2200));
    }

    /** Equal totals prefer the snapshot with more reported fields, without losing later metadata. */
    @Test
    void equalTotalLaterSnapshotWithCacheDetailReplacesTheWholeEarlierSnapshot() {
        provider = "openai";
        ChatResponse trailingModelOnly = new ChatResponse(List.of(), ChatResponseMetadata.builder()
                .model("late-model")
                .usage(new org.springframework.ai.chat.metadata.EmptyUsage())
                .build());
        ChatService service = service(List.of(), Flux.just(
                usage(1000, 200, null),
                usage(1000, 200, 400L),
                trailingModelOnly));

        List<TokenUsage> reported = new java.util.ArrayList<>();
        StepVerifier.create(service.stream(List.of(new ChatTurn(ChatTurn.USER, "hi")), USER, reported::add))
                .verifyComplete();

        assertThat(reported.getLast())
                .as("equal-total cache detail wins whole; trailing model metadata erases no counts")
                .isEqualTo(new TokenUsage("late-model", 600, 400, null, 200));
    }

    /**
     * Field-by-field maxima could assemble a row out of two chunks: on the OpenAI path a chunk
     * reporting the prompt without cache details normalises to the full prompt, and a later
     * chunk reporting both would leave that un-normalised figure beside a populated cache count —
     * the generated total then counts the cached tokens twice.
     */
    @Test
    void aRowIsOneChunksMeasurement() {
        provider = "openai";
        ChatService service = service(List.of(), Flux.just(
                response("text"),
                usage(1000, 50, null),
                usage(1200, 200, 500L)));

        List<TokenUsage> reported = new java.util.ArrayList<>();
        StepVerifier.create(service.stream(List.of(new ChatTurn(ChatTurn.USER, "hi")), USER, reported::add))
                .expectNext("text")
                .verifyComplete();

        assertThat(reported.getLast())
                .as("the richest chunk, kept whole: 1200 prompt less 500 cached is 700 uncached")
                .isEqualTo(new TokenUsage(null, 700, 500, null, 200));
    }

    @Test
    void aStreamThatReportsNoUsageReportsNothing() {
        ChatService service = serviceStreaming(List.of(), Flux.just("only text"));

        List<TokenUsage> reported = new java.util.ArrayList<>();
        StepVerifier.create(service.stream(List.of(new ChatTurn(ChatTurn.USER, "hi")), USER, reported::add))
                .expectNext("only text")
                .verifyComplete();

        assertThat(reported).as("null reads as not measured; an empty record would read as zero")
                .isEmpty();
    }

    @Test
    void everyInjectedToolIsRegistered() {
        List<FplTool> tools = List.of(new FplTool() {}, new FplTool() {}, new FplTool() {});
        ChatService service = serviceStreaming(tools, Flux.empty());

        service.stream(List.of(new ChatTurn(ChatTurn.USER, "hi")), USER, IGNORE_USAGE).blockLast();

        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(requestSpec).tools(captor.capture());
        assertThat(captor.getValue()).containsExactlyElementsOf(tools);
    }
}
