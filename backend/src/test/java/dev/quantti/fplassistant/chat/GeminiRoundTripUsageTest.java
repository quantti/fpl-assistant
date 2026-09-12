package dev.quantti.fplassistant.chat;

import com.google.genai.Client;
import com.google.genai.Models;
import com.google.genai.ResponseStream;
import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import dev.quantti.fplassistant.tools.FplTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.tool.annotation.Tool;
import reactor.test.StepVerifier;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Spliterators;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Provider-boundary regression for Spring AI 2.0.0 and google-genai 1.58.0. Every response is a
 * real Google SDK value; only the SDK Models streaming boundary is mocked, while the provider
 * model, ChatClient advisor chain and tool loop are production objects.
 */
class GeminiRoundTripUsageTest {

    private static final UUID USER = UUID.fromString("11111111-2222-4333-8444-555555555555");

    @Test
    void toolRoundsAndFinalAnswerSumCachedPromptCandidatesAndThoughtsOnce() throws Exception {
        SdkBoundary sdk = new SdkBoundary(List.of(
                stream(List.of(
                        tool("first"),
                        metadata(usage(100, 40, 5, 3)),
                        metadata(usage(100, 40, 5, 3)))),
                stream(List.of(
                        tool("second"),
                        metadata(usage(80, 0, 4, 2)))),
                stream(List.of(
                        metadata(null),
                        text("provider answer"),
                        metadata(null)))));
        List<TokenUsage> reported = new ArrayList<>();
        ProbeTools tools = new ProbeTools();

        StepVerifier.create(service(sdk, List.of(tools)).stream(
                        List.of(new ChatTurn(ChatTurn.USER, "use both probes")), USER, reported::add))
                .expectNext("provider answer")
                .verifyComplete();

        assertThat(sdk.requests()).isEqualTo(3);
        assertThat(tools.firstCalls()).isOne();
        assertThat(tools.secondCalls()).isOne();
        verify(sdk.models(), times(3)).generateContentStream(
                anyString(), anyList(), any(GenerateContentConfig.class));
        assertThat(reported.getLast())
                .as("rounds: 60/40/(5+3) + 80/0/(4+2); final round reports no usage")
                .isEqualTo(new TokenUsage("fixture-model", 140, 40, null, 14));
        assertThat(reported.getLast().inputTokens() + reported.getLast().cachedTokens()
                + reported.getLast().outputTokens())
                .as("cache is removed from prompt once and thoughts are added to candidates once")
                .isEqualTo(194);
    }

    @Test
    void partialStreamFailureAfterUsageRetainsItsLastMappedSnapshot() throws Exception {
        SdkBoundary sdk = new SdkBoundary(List.of(failingStream(List.of(
                metadata(usage(50, 12, 6, 4)),
                text("partial"),
                metadata(usage(50, 12, 6, 4))), "after usage")));
        List<TokenUsage> reported = new ArrayList<>();

        StepVerifier.create(service(sdk, List.of()).stream(
                        List.of(new ChatTurn(ChatTurn.USER, "fail")), USER, reported::add))
                .expectNext("partial")
                .expectErrorMessage("after usage")
                .verify();

        assertThat(sdk.requests()).isOne();
        assertThat(reported.getLast())
                .as("repeated cumulative usage before the SDK error is one request snapshot")
                .isEqualTo(new TokenUsage("fixture-model", 38, 12, null, 10));
    }

    private static ChatService service(SdkBoundary sdk, List<FplTool> tools) throws Exception {
        GoogleGenAiChatOptions options = GoogleGenAiChatOptions.builder()
                .model(GoogleGenAiChatModel.ChatModel.GEMINI_2_5_FLASH)
                .maxOutputTokens(100)
                .includeExtendedUsageMetadata(true)
                .build();
        GoogleGenAiChatModel model = GoogleGenAiChatModel.builder()
                .genAiClient(sdk.client())
                .options(options)
                .build();
        return new ChatService(ChatClient.builder(model).build(), tools, "google-genai", 1);
    }

    private static GenerateContentResponse tool(String name) {
        FunctionCall call = FunctionCall.builder()
                .id("same-tool-id")
                .name(name)
                .args(Map.of())
                .build();
        return response(List.of(Part.builder().functionCall(call).build()), null);
    }

    private static GenerateContentResponse text(String value) {
        return response(List.of(Part.fromText(value)), null);
    }

    private static GenerateContentResponse metadata(GenerateContentResponseUsageMetadata usage) {
        return response(List.of(), usage);
    }

    private static GenerateContentResponse response(List<Part> parts,
                                                     GenerateContentResponseUsageMetadata usage) {
        GenerateContentResponse.Builder response = GenerateContentResponse.builder()
                .modelVersion("fixture-model");
        if (!parts.isEmpty()) {
            Content content = Content.builder().role("model").parts(parts).build();
            response.candidates(Candidate.builder().index(0).content(content).build());
        }
        if (usage != null) {
            response.usageMetadata(usage);
        }
        return response.build();
    }

    private static GenerateContentResponseUsageMetadata usage(int prompt, int cached,
                                                               int candidates, int thoughts) {
        return GenerateContentResponseUsageMetadata.builder()
                .promptTokenCount(prompt)
                .cachedContentTokenCount(cached)
                .candidatesTokenCount(candidates)
                .thoughtsTokenCount(thoughts)
                .totalTokenCount(prompt + candidates + thoughts)
                .build();
    }

    private static EventStream stream(List<GenerateContentResponse> responses) {
        return new EventStream(responses, null);
    }

    private static EventStream failingStream(List<GenerateContentResponse> responses, String message) {
        return new EventStream(responses, new IllegalStateException(message));
    }

    private record EventStream(List<GenerateContentResponse> responses, Throwable failure) {}

    private static final class SdkBoundary {
        private final Client client = Client.builder().apiKey("unused-fixture-key").build();
        private final Models models = mock(Models.class);
        private final List<EventStream> rounds;
        private final AtomicInteger request = new AtomicInteger();

        private SdkBoundary(List<EventStream> rounds) throws Exception {
            this.rounds = List.copyOf(rounds);
            Field modelsField = Client.class.getField("models");
            modelsField.setAccessible(true);
            modelsField.set(client, models);
            when(models.generateContentStream(anyString(), anyList(),
                    any(GenerateContentConfig.class))).thenAnswer(invocation -> {
                int index = request.getAndIncrement();
                if (index >= this.rounds.size()) {
                    throw new AssertionError("unexpected Gemini request " + (index + 1));
                }
                return responseStream(this.rounds.get(index));
            });
        }

        @SuppressWarnings("unchecked")
        private static ResponseStream<GenerateContentResponse> responseStream(EventStream events) {
            ResponseStream<GenerateContentResponse> stream = mock(ResponseStream.class);
            when(stream.iterator()).thenAnswer(invocation -> iterator(events));
            when(stream.spliterator()).thenAnswer(invocation ->
                    Spliterators.spliteratorUnknownSize(iterator(events), 0));
            return stream;
        }

        private static Iterator<GenerateContentResponse> iterator(EventStream events) {
            Iterator<GenerateContentResponse> delegate = events.responses().iterator();
            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    if (delegate.hasNext()) {
                        return true;
                    }
                    if (events.failure() != null) {
                        if (events.failure() instanceof RuntimeException runtime) {
                            throw runtime;
                        }
                        throw new RuntimeException(events.failure());
                    }
                    return false;
                }

                @Override
                public GenerateContentResponse next() {
                    return delegate.next();
                }
            };
        }

        private Client client() {
            return client;
        }

        private Models models() {
            return models;
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
