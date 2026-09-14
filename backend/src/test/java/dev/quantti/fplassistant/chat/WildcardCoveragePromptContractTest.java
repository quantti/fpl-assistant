package dev.quantti.fplassistant.chat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import dev.quantti.fplassistant.tools.FplTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.annotation.Tool;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;

/** Deterministic prompt/chat contract; deliberately does not call a real language model. */
class WildcardCoveragePromptContractTest {

    private static String prompt() throws IOException {
        try (var in = WildcardCoveragePromptContractTest.class.getResourceAsStream(
                "/prompts/system-prompt.st")) {
            if (in == null) {
                throw new IOException("system prompt missing");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void promptSeparatesDataExclusionFromRotationAndForbidsManualFallbacks() throws IOException {
        assertThat(prompt()).contains(
                "Comparison-data refusals are not football judgements",
                "never relabel that exclusion as rotation risk",
                "not a league-wide best list",
                "do not use that list, remembered names or manual substitutions",
                "only propose a revised 15-player squad after a newly called",
                "Budget, pins, formation, club quotas and",
                "user challenges a specific club's");
    }

    @Test
    void scriptedChallengeRequiresANewSuccessfulSquadToolResultBeforeRevisedFifteen()
            throws IOException {
        StubSquadTool tool = new StubSquadTool();
        ScriptedModel model = new ScriptedModel(List.of(
                toolCall("initial", "buildSquad"),
                answer("Northbank FC was excluded by data coverage: fixture 55434 in GW7 has an"
                        + " unverified receipt. I cannot provide a fallback fifteen."),
                toolCall("retry", "buildSquad"),
                answer("The new verified build succeeded; here is the revised 15-player squad.")));
        ChatClient client = ChatClient.builder(model).defaultSystem(prompt()).build();
        ChatService service = new ChatService(client, List.of(tool), "openai", 15);

        java.util.UUID user = java.util.UUID.fromString(
                "11111111-2222-4333-8444-555555555555");
        List<String> initial = service.stream(List.of(
                new ChatTurn(ChatTurn.USER, "Build my wildcard squad")), user, ignored -> {})
                .collectList().block();
        String initialAnswer = String.join("", initial);
        assertThat(initialAnswer).contains("Northbank FC", "fixture 55434", "GW7", "data coverage")
                .doesNotContain("rotation", "replacement XI", "15-player squad:");

        List<String> followUp = service.stream(List.of(
                new ChatTurn(ChatTurn.USER, "Build my wildcard squad"),
                new ChatTurn(ChatTurn.ASSISTANT, initialAnswer),
                new ChatTurn(ChatTurn.USER, "Why no Northbank defenders? Give me a revised 15.")),
                user, ignored -> {}).collectList().block();

        assertThat(String.join("", followUp)).contains("new verified build succeeded",
                "revised 15-player squad");
        assertThat(tool.calls()).isEqualTo(2);
        assertThat(model.prompts().get(2).getContents())
                .contains("Why no Northbank defenders?", "fixture 55434", "fallback fifteen");
    }

    private static ChatResponse toolCall(String id, String name) {
        AssistantMessage message = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, "{}")))
                .build();
        return response(id, message);
    }

    private static ChatResponse answer(String text) {
        return response("answer", new AssistantMessage(text));
    }

    private static ChatResponse response(String id, AssistantMessage message) {
        return new ChatResponse(List.of(new Generation(message)),
                ChatResponseMetadata.builder().id(id).model("coverage-stub").build());
    }

    private static final class StubSquadTool implements FplTool {
        private final AtomicInteger calls = new AtomicInteger();

        @Tool
        String buildSquad() {
            return calls.getAndIncrement() == 0
                    ? "COMPARISON_DATA_INCOMPLETE: Affected clubs: Northbank FC"
                            + " [fixture 55434 (GW7): unverified owned receipt]."
                    : "VERIFIED BUILD: revised 15-player squad is available.";
        }

        int calls() {
            return calls.get();
        }
    }

    private static final class ScriptedModel implements ChatModel {
        private final List<ChatResponse> responses;
        private final AtomicInteger invocation = new AtomicInteger();
        private final List<Prompt> prompts = new ArrayList<>();

        private ScriptedModel(List<ChatResponse> responses) {
            this.responses = responses;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            prompts.add(prompt);
            int index = invocation.getAndIncrement();
            return Flux.just(responses.get(index));
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }

        List<Prompt> prompts() {
            return prompts;
        }
    }
}
