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

    private ChatService service(List<FplTool> tools, Flux<String> content) {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.messages(anyList())).thenReturn(requestSpec);
        when(requestSpec.tools(any(Object[].class))).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.content()).thenReturn(content);
        return new ChatService(chatClient, tools);
    }

    @Test
    void streamReturnsFluxFromChatClient() {
        ChatService service = service(List.of(), Flux.just("Hello", " FPL", " fan"));

        Flux<String> result = service.stream(
                List.of(new ChatTurn(ChatTurn.USER, "Who should I captain?")));

        StepVerifier.create(result)
                .expectNext("Hello", " FPL", " fan")
                .verifyComplete();
    }

    @Test
    void historyIsWindowedAndAlwaysOpensOnAUserMessage() {
        ChatService service = service(List.of(), Flux.empty());
        // 25 alternating messages: the raw 20-message window would open on assistant msg-5,
        // which the Anthropic API rejects — the window must trim itself to user msg-6.
        List<ChatTurn> turns = IntStream.range(0, 25)
                .mapToObj(i -> new ChatTurn(i % 2 == 0 ? ChatTurn.USER : ChatTurn.ASSISTANT, "msg-" + i))
                .toList();

        service.stream(turns);

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
        ChatService service = service(List.of(), Flux.empty());

        service.stream(List.of(
                new ChatTurn(ChatTurn.USER, "first question"),
                new ChatTurn(ChatTurn.USER, "second question"),
                new ChatTurn(ChatTurn.ASSISTANT, "an answer")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(requestSpec).messages(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue().getFirst().getText())
                .isEqualTo("first question\n\nsecond question");
    }

    @Test
    void everyInjectedToolIsRegistered() {
        List<FplTool> tools = List.of(new FplTool() {}, new FplTool() {}, new FplTool() {});
        ChatService service = service(tools, Flux.empty());

        service.stream(List.of(new ChatTurn(ChatTurn.USER, "hi")));

        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(requestSpec).tools(captor.capture());
        assertThat(captor.getValue()).containsExactlyElementsOf(tools);
    }
}
