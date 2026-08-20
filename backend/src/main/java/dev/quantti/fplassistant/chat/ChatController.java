package dev.quantti.fplassistant.chat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;

import dev.quantti.fplassistant.config.TokenSubject;
import dev.quantti.fplassistant.user.QuotaExceededException;
import dev.quantti.fplassistant.user.UsageService;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
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
    private final UsageService usage;
    private final String provider;
    // Local instance: Boot 4's web starter no longer guarantees an ObjectMapper bean, and
    // encoding a bare String needs no configuration.
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ChatController(ChatService chatService, ConversationStore conversations,
                          UsageService usage,
                          @Value("${fpl.ai.provider:anthropic}") String provider) {
        this.chatService = chatService;
        this.conversations = conversations;
        this.usage = usage;
        // Recorded per event: the two providers price differently, so usage that does not say
        // which one answered cannot be turned into a cost.
        this.provider = provider;
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

        // Before anything is written and before the stream opens. Once an SSE response has
        // begun, its status is already on the wire and cannot become a 429 — and a quota
        // failure that created a conversation would leave an empty one behind.
        UsageService.Quota quota = usage.quotaOf(userId);
        if (quota.exceeded()) {
            throw new QuotaExceededException(quota);
        }

        UUID conversationId = resolve(request == null ? null : request.conversationId(), userId, message);
        conversations.append(conversationId, ChatTurn.USER, message);
        List<ChatTurn> history = conversations.recentTurns(conversationId, ChatService.HISTORY_WINDOW);

        // Recorded here, where the cost is incurred, rather than on completion: an answer the
        // user abandons has still been paid for, and recording at the end would make aborting
        // and retrying free.
        long usageEventId = usage.recordChatStart(userId, provider);
        AtomicReference<TokenUsage> reported = new AtomicReference<>();

        // Token events carry the token as a JSON string: raw SSE data cannot round-trip a
        // leading space (the decoder strips the one after "data:"), and LLM tokens usually
        // start with one. The frontend JSON.parses each token event's data.
        Flux<ServerSentEvent<String>> tokens =
                answerOutlivingTheClient(chatService.stream(history, reported::set),
                        conversationId, usageEventId, reported)
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
    private Flux<String> answerOutlivingTheClient(Flux<String> source, UUID conversationId,
                                                  long usageEventId,
                                                  AtomicReference<TokenUsage> reported) {
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
                    recordTokens(usageEventId, reported.get());
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

    /**
     * The usage row exists either way — the call was made — so a provider that reports nothing
     * leaves the token columns null rather than zero. Null reads as "not measured"; zero reads
     * as a measurement, and would drag every average this data exists to inform.
     */
    private void recordTokens(long usageEventId, TokenUsage tokens) {
        if (tokens == null) {
            return;
        }
        try {
            usage.recordTokens(usageEventId, tokens.inputTokens(), tokens.cachedTokens(),
                    tokens.outputTokens());
        } catch (RuntimeException e) {
            log.error("could not record token usage for event {}", usageEventId, e);
        }
    }

    /**
     * The quota answer, with {@code Retry-After} when waiting actually helps. A suspended
     * account (limit 0) gets no header: there is no number of seconds that changes the answer.
     */
    @ExceptionHandler(QuotaExceededException.class)
    ResponseEntity<String> quotaExceeded(QuotaExceededException e) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .contentType(MediaType.TEXT_PLAIN);
        e.retryAfterSeconds().ifPresent(seconds -> response.header("Retry-After", Long.toString(seconds)));
        return response.body(e.getMessage());
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
