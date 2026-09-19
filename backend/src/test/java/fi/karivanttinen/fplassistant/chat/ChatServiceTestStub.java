package fi.karivanttinen.fplassistant.chat;

import reactor.core.publisher.Flux;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** Stubs the package-private traced stream overload for tests outside the chat package. */
public final class ChatServiceTestStub {

    private ChatServiceTestStub() {
    }

    public static void replyWith(ChatService service, Flux<String> reply) {
        when(service.stream(any(), any(), any(), any())).thenReturn(reply);
    }
}
