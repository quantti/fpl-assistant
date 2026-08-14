package fi.karivanttinen.fplassistant.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.util.List;

@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    // Abuse bounds for the unauthenticated endpoint. The cost bound is ChatService's
    // 20-message window; these only stop pathological payloads.
    static final int MAX_MESSAGES = 200;
    static final int MAX_CONTENT_CHARS = 4000;

    private final ChatService chatService;
    // Local instance: Boot 4's web starter no longer guarantees an ObjectMapper bean, and
    // encoding a bare String needs no configuration.
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(@RequestBody ChatRequest request) {
        validate(request);
        // Token events carry the token as a JSON string: raw SSE data cannot round-trip a
        // leading space (the decoder strips the one after "data:"), and LLM tokens usually
        // start with one. The frontend JSON.parses each token event's data.
        return chatService.stream(request)
                .map(token -> ServerSentEvent.builder(json(token)).build())
                .onErrorResume(e -> {
                    log.error("chat stream failed", e);
                    return Flux.just(ServerSentEvent.<String>builder()
                            .event("error")
                            .data("The assistant hit a temporary problem. Please try again.")
                            .build());
                })
                .concatWith(Flux.just(ServerSentEvent.<String>builder()
                        .event("done")
                        .data("")
                        .build()));
    }

    /**
     * ChatService deliberately relies on this: it maps unknown roles to assistant messages and
     * NPEs on nulls, so nothing invalid may pass this point.
     */
    private void validate(ChatRequest request) {
        List<ChatRequest.MessageDto> messages = request == null ? null : request.messages();
        if (messages == null || messages.isEmpty()) {
            bad("messages must not be empty");
        }
        if (messages.size() > MAX_MESSAGES) {
            bad("too many messages (max " + MAX_MESSAGES + ")");
        }
        for (ChatRequest.MessageDto m : messages) {
            if (m == null || m.content() == null || m.content().isBlank()) {
                bad("message content must not be blank");
            }
            if (m.content().length() > MAX_CONTENT_CHARS) {
                bad("message too long (max " + MAX_CONTENT_CHARS + " chars)");
            }
            if (!"user".equals(m.role()) && !"assistant".equals(m.role())) {
                bad("unknown role: " + m.role());
            }
        }
    }

    private static void bad(String reason) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    private String json(String token) {
        try {
            return objectMapper.writeValueAsString(token);
        } catch (JsonProcessingException e) {
            // Unreachable for a String value; surfaces via the stream's error event if it ever is.
            throw new IllegalStateException(e);
        }
    }
}
