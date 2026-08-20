package dev.quantti.fplassistant.chat;

import dev.quantti.fplassistant.tools.FplTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
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
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

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
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.messages(anyList())).thenReturn(requestSpec);
        when(requestSpec.tools(any(Object[].class))).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.chatResponse()).thenReturn(responses);
        return new ChatService(chatClient, tools);
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

    /** Nothing to record: the usage callback must not fire with an empty measurement. */
    private static final java.util.function.Consumer<TokenUsage> IGNORE_USAGE = usage -> {};

    @Test
    void streamReturnsFluxFromChatClient() {
        ChatService service = serviceStreaming(List.of(), Flux.just("Hello", " FPL", " fan"));

        Flux<String> result = service.stream(
                List.of(new ChatTurn(ChatTurn.USER, "Who should I captain?")), IGNORE_USAGE);

        StepVerifier.create(result)
                .expectNext("Hello", " FPL", " fan")
                .verifyComplete();
    }

    @Test
    void historyIsWindowedAndAlwaysOpensOnAUserMessage() {
        ChatService service = serviceStreaming(List.of(), Flux.empty());
        // 25 alternating messages: the raw 20-message window would open on assistant msg-5,
        // which the Anthropic API rejects — the window must trim itself to user msg-6.
        List<ChatTurn> turns = IntStream.range(0, 25)
                .mapToObj(i -> new ChatTurn(i % 2 == 0 ? ChatTurn.USER : ChatTurn.ASSISTANT, "msg-" + i))
                .toList();

        service.stream(turns, IGNORE_USAGE);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(requestSpec).messages(captor.capture());
        List<Message> sent = captor.getValue();
        assertThat(sent).hasSize(19);
        assertThat(sent.getFirst().getText()).isEqualTo("msg-6");
        assertThat(sent.getFirst()).isInstanceOf(UserMessage.class);
        assertThat(sent.getLast().getText()).isEqualTo("msg-24");
        assertThat(sent.getLast()).isInstanceOf(UserMessage.class);
        assertThat(sent.get(1)).isInstanceOf(AssistantMessage.class);
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
                new ChatTurn(ChatTurn.ASSISTANT, "an answer")), IGNORE_USAGE);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(requestSpec).messages(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue().getFirst().getText())
                .isEqualTo("first question\n\nsecond question");
    }

    /**
     * Providers differ in where the numbers arrive — prompt tokens early, completion tokens on
     * the last chunk. Keeping only the final chunk's usage would record the answer's size and
     * lose the prompt it was paid for.
     */
    @Test
    void tokenCountsAreMergedAcrossChunksAndReportedOnce() {
        ChatService service = service(List.of(), Flux.just(
                response("Hello"),
                usage(1200, null, 900L),
                response(" world"),
                usage(null, 300, null)));

        List<TokenUsage> reported = new java.util.ArrayList<>();
        StepVerifier.create(service.stream(List.of(new ChatTurn(ChatTurn.USER, "hi")), reported::add))
                .expectNext("Hello", " world")
                .verifyComplete();

        assertThat(reported.getLast())
                .as("the running measurement, merged across the chunks that reported one")
                .isEqualTo(new TokenUsage(null, 1200, 900, null, 300));
    }

    /**
     * A cache miss reports zero reads. Recording that as null would make "the cache did not hit"
     * indistinguishable from "the provider said nothing" — and telling those apart is the whole
     * reason the column exists.
     */
    @Test
    void aReportedZeroIsAMeasurementAndAnEmptyChunkIsNot() {
        ChatService service = service(List.of(), Flux.just(
                response("text"),
                usage(1200, 300, 0L),
                response("more text")));

        List<TokenUsage> reported = new java.util.ArrayList<>();
        StepVerifier.create(service.stream(List.of(new ChatTurn(ChatTurn.USER, "hi")), reported::add))
                .expectNext("text", "more text")
                .verifyComplete();

        assertThat(reported.getLast()).isEqualTo(new TokenUsage(null, 1200, 0, null, 300));
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
        StepVerifier.create(service.stream(List.of(new ChatTurn(ChatTurn.USER, "hi")), reported::add))
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
        StepVerifier.create(service.stream(List.of(new ChatTurn(ChatTurn.USER, "hi")), reported::add))
                .expectNext("text")
                .verifyComplete();

        assertThat(reported.getLast())
                .isEqualTo(new TokenUsage("some-model", null, null, null, null));
    }

    @Test
    void aStreamThatReportsNoUsageReportsNothing() {
        ChatService service = serviceStreaming(List.of(), Flux.just("only text"));

        List<TokenUsage> reported = new java.util.ArrayList<>();
        StepVerifier.create(service.stream(List.of(new ChatTurn(ChatTurn.USER, "hi")), reported::add))
                .expectNext("only text")
                .verifyComplete();

        assertThat(reported).as("null reads as not measured; an empty record would read as zero")
                .isEmpty();
    }

    @Test
    void everyInjectedToolIsRegistered() {
        List<FplTool> tools = List.of(new FplTool() {}, new FplTool() {}, new FplTool() {});
        ChatService service = serviceStreaming(tools, Flux.empty());

        service.stream(List.of(new ChatTurn(ChatTurn.USER, "hi")), IGNORE_USAGE);

        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(requestSpec).tools(captor.capture());
        assertThat(captor.getValue()).containsExactlyElementsOf(tools);
    }
}
