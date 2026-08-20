package dev.quantti.fplassistant.chat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import dev.quantti.fplassistant.config.TokenSubject;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    static final int MAX_CONTENT_CHARS = 4000;

    /**
     * How long one answer may take before the detached subscription gives up. Generous: a
     * tool-calling answer makes several model round trips. It exists to bound a provider that
     * hangs without closing, not to police slow answers.
     */
    private static final Duration ANSWER_TIMEOUT = Duration.ofMinutes(5);

    private final ChatService chatService;
    private final ConversationStore conversations;
    // Local instance: Boot 4's web starter no longer guarantees an ObjectMapper bean, and
    // encoding a bare String needs no configuration.
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ChatController(ChatService chatService, ConversationStore conversations) {
        this.chatService = chatService;
        this.conversations = conversations;
    }

    /**
     * One user turn in, one answer streamed back.
     *
     * <p>The conversation id is the stream's first event rather than a response header: the
     * client needs it to send the next turn, and an SSE client already parses events, while a
     * header on a streaming response is the one part of it that cannot be assembled from what
     * the model produced.
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(@RequestBody ChatRequest request,
                                              @AuthenticationPrincipal Jwt jwt) {
        UUID userId = TokenSubject.userId(jwt);
        String message = validated(request);
        UUID conversationId = resolve(request == null ? null : request.conversationId(), userId, message);

        conversations.append(conversationId, ChatTurn.USER, message);
        List<ChatTurn> history = conversations.recentTurns(conversationId, ChatService.HISTORY_WINDOW);

        // Token events carry the token as a JSON string: raw SSE data cannot round-trip a
        // leading space (the decoder strips the one after "data:"), and LLM tokens usually
        // start with one. The frontend JSON.parses each token event's data.
        Flux<ServerSentEvent<String>> tokens =
                answerOutlivingTheClient(chatService.stream(history), conversationId)
                        .map(token -> ServerSentEvent.builder(json(token)).build())
                        .onErrorResume(e -> Flux.just(ServerSentEvent.<String>builder()
                                .event("error")
                                .data("The assistant hit a temporary problem. Please try again.")
                                .build()));

        return Flux.concat(
                Flux.just(ServerSentEvent.<String>builder()
                        .event("conversation")
                        .data(json(conversationId.toString()))
                        .build()),
                tokens,
                Flux.just(ServerSentEvent.<String>builder().event("done").data("").build()));
    }

    /**
     * Subscribes to the model once, here, and hands the client a replay of that subscription.
     *
     * <p>Returning the model's own {@code Flux} would tie it to the response: a client that
     * closes the tab cancels the response, the cancellation propagates upstream, and the answer
     * is neither finished nor stored — while the tokens already generated have been paid for.
     * This subscription is nobody's to cancel, so the answer completes and is persisted whether
     * or not anyone is still reading.
     *
     * <p>A failed stream stores nothing, not even the part that arrived. The client discards a
     * partial answer when it sees the error event, and a history holding text the user was told
     * to ignore would be a worse lie than one turn short.
     */
    private Flux<String> answerOutlivingTheClient(Flux<String> source, UUID conversationId) {
        Sinks.Many<String> sink = Sinks.many().replay().all();
        StringBuilder answer = new StringBuilder();
        source
                // The provider delivers terminal signals on its own event-loop thread, and this
                // subscription ends in two blocking JDBC round trips. Persisting there would
                // stall every other connection multiplexed onto that loop, for as long as Hikari
                // takes to hand out a connection — up to its 30-second timeout under load.
                .publishOn(Schedulers.boundedElastic())
                // Nothing else can end this subscription: it is deliberately detached from the
                // response, so a provider that stops sending without closing the connection
                // would leave the answer unpersisted, the sink retained, and no way to cancel.
                .timeout(ANSWER_TIMEOUT)
                .subscribe(
                token -> {
                    answer.append(token);
                    sink.tryEmitNext(token);
                },
                error -> {
                    log.error("chat stream failed", error);
                    sink.tryEmitError(error);
                },
                () -> {
                    persist(conversationId, answer.toString());
                    sink.tryEmitComplete();
                });
        return sink.asFlux();
    }

    private void persist(UUID conversationId, String answer) {
        if (answer.isBlank()) {
            return;
        }
        try {
            conversations.append(conversationId, ChatTurn.ASSISTANT, answer);
            conversations.touch(conversationId);
        } catch (RuntimeException e) {
            // The client has the answer either way; losing it from history is worth a log, not a
            // broken stream.
            log.error("could not persist the assistant message for conversation {}",
                    conversationId, e);
        }
    }

    /** Returns the message. ChatService relies on this: nothing invalid may pass this point. */
    private String validated(ChatRequest request) {
        String message = request == null ? null : request.message();
        if (message == null || message.isBlank()) {
            bad("message must not be blank");
        }
        if (message.length() > MAX_CONTENT_CHARS) {
            bad("message too long (max " + MAX_CONTENT_CHARS + " chars)");
        }
        return message;
    }

    /**
     * A conversation id that is not the caller's own is {@code 404}, never {@code 403}: telling
     * an attacker that an id exists but is someone else's is the answer they were asking for.
     * A malformed id is a bad request rather than a miss — it cannot be anyone's.
     */
    private UUID resolve(String conversationId, UUID userId, String message) {
        if (conversationId == null || conversationId.isBlank()) {
            return conversations.create(userId, message);
        }
        UUID id;
        try {
            id = UUID.fromString(conversationId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "conversationId is not a UUID");
        }
        return conversations.findOwned(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such conversation"));
    }

    private static void bad(String reason) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    private String json(String value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // Unreachable for a String value; surfaces via the stream's error event if it ever is.
            throw new IllegalStateException(e);
        }
    }
}
