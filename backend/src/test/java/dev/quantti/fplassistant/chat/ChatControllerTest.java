package dev.quantti.fplassistant.chat;

import java.util.List;
import java.util.UUID;

import dev.quantti.fplassistant.testsupport.LocalSigningKey;
import dev.quantti.fplassistant.testsupport.TestJwks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.client.MockMvcWebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The chat contract as it stands after Task 4: one message in, an answer streamed back, and a
 * conversation the server owns.
 *
 * <p>A {@code @SpringBootTest} rather than a slice, because the interesting behaviour is no
 * longer only the SSE shaping: it is who may reach a conversation and what survives a client
 * that leaves. That brings {@code RateLimitFilter} into the chain — 20 requests per minute per
 * IP, shared with every other test that posts here — so the HTTP tests below stay few on
 * purpose, and anything provable without HTTP is proved by calling the controller directly.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(LocalSigningKey.class)
class ChatControllerTest {

    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE =
            new ParameterizedTypeReference<>() {};

    private static final String USER_A = "3f0a4d1e-1111-4111-8111-aaaaaaaaaaaa";
    private static final String USER_B = "4a1b5e2f-2222-4222-8222-bbbbbbbbbbbb";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ChatController controller;

    @Autowired
    ConversationStore conversations;

    @MockitoBean
    ChatService chatService;

    @Value("${fpl.user.conversation-table-name}")
    String conversationTable;

    @Value("${fpl.user.usage-table-name}")
    String usageTable;

    @Value("${fpl.user.profile-table-name}")
    String profileTable;

    WebTestClient client;

    @BeforeEach
    void setup() {
        client = MockMvcWebTestClient.bindTo(mockMvc).build();
    }

    /**
     * Usage rows too, not just conversations: every chat here records one, and a suite that
     * leaves them behind eventually spends the account's weekly quota and fails a later run with
     * a 429 that has nothing to do with the code.
     */
    @AfterEach
    void clear() {
        for (String user : new String[] {USER_A, USER_B}) {
            UUID id = UUID.fromString(user);
            jdbc.update("DELETE FROM " + conversationTable + " WHERE user_id = ?", id);
            jdbc.update("DELETE FROM " + usageTable + " WHERE user_id = ?", id);
            jdbc.update("DELETE FROM " + profileTable + " WHERE user_id = ?", id);
        }
    }

    /**
     * The quota is checked before anything is written and before the stream opens: an SSE
     * response that has begun cannot become a 429, and a rejected turn must not leave an empty
     * conversation behind.
     */
    @Test
    void aSpentQuotaIsRejectedWithRetryAfterAndWritesNothing() {
        jdbc.update("INSERT INTO " + profileTable + " (user_id, weekly_message_limit) VALUES (?, 1)",
                UUID.fromString(USER_A));
        jdbc.update("INSERT INTO " + usageTable + " (user_id, event_type, created_at)"
                + " VALUES (?, 'chat', now() - interval '1 hour')", UUID.fromString(USER_A));

        client.post().uri("/api/chat")
                .header("Authorization", "Bearer " + TestJwks.validTokenFor(USER_A))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"message\":\"one too many\"}")
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().exists("Retry-After");

        assertThat(conversations.list(UUID.fromString(USER_A)))
                .as("a turn nobody was allowed to take leaves no conversation")
                .isEmpty();
        org.mockito.Mockito.verifyNoInteractions(chatService);
    }

    /**
     * The usage row is written when the call starts, so an abandoned answer still counts, and
     * the token columns are filled from what the provider reported once it finishes.
     */
    @Test
    void usageIsRecordedWhenTheCallStartsAndTokensWhenItEnds() {
        when(chatService.stream(any(), any())).thenAnswer(invocation -> {
            java.util.function.Consumer<TokenUsage> onUsage = invocation.getArgument(1);
            onUsage.accept(new TokenUsage("claude-sonnet-5", 1500, 1200, 800, 250));
            return Flux.just("answer");
        });

        post(USER_A, "{\"message\":\"question\"}");

        assertThat(jdbc.queryForList("SELECT event_type, provider, model, conversation_id,"
                + " input_tokens, cached_tokens, cache_write_tokens, output_tokens, total_tokens"
                + " FROM " + usageTable + " WHERE user_id = ?", UUID.fromString(USER_A)))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row).containsEntry("event_type", "chat");
                    assertThat(row.get("provider")).isNotNull();
                    assertThat(row).containsEntry("model", "claude-sonnet-5")
                            .containsEntry("input_tokens", 1500)
                            .containsEntry("cached_tokens", 1200)
                            .containsEntry("cache_write_tokens", 800)
                            .containsEntry("output_tokens", 250)
                            .containsEntry("total_tokens", 3750);
                    assertThat(row.get("conversation_id"))
                            .as("the turn names the conversation it was spent on")
                            .isNotNull();
                });
    }

    /**
     * When the oldest turns no longer fit, the user is told. An answer written without the
     * beginning of the conversation must not look like one written with it.
     */
    @Test
    void aTrimmedHistoryIsAnnouncedToTheClient() {
        when(chatService.stream(any(), any())).thenReturn(Flux.just("answer"));
        UUID id = conversations.create(UUID.fromString(USER_A), "long one");
        // The test profile's budget is 400 characters; five of these do not fit.
        for (int i = 0; i < 5; i++) {
            conversations.append(id, ChatTurn.USER, "question " + i + " " + "x".repeat(200));
            conversations.append(id, ChatTurn.ASSISTANT, "answer " + i + " " + "x".repeat(200));
        }

        List<ServerSentEvent<String>> events = post(USER_A,
                "{\"conversationId\":\"" + id + "\",\"message\":\"and now?\"}");

        assertThat(events).extracting(ServerSentEvent::event).contains("truncated");
        assertThat(events.stream().filter(e -> "truncated".equals(e.event())).findFirst().orElseThrow())
                .satisfies(event -> assertThat(Integer.parseInt(event.data())).isPositive());
    }

    /**
     * A stream that burned tokens and then failed keeps its charge. Only a failure that produced
     * nothing is refunded — otherwise a five-minute tool-calling turn that dies at the end would
     * be free, and free to repeat.
     */
    @Test
    void aFailureAfterTokensWereReportedKeepsTheCharge() {
        when(chatService.stream(any(), any())).thenAnswer(invocation -> {
            java.util.function.Consumer<TokenUsage> onUsage = invocation.getArgument(1);
            onUsage.accept(new TokenUsage("claude-sonnet-5", 30000, 0, 0, 900));
            return Flux.error(new IllegalStateException("the provider dropped the connection"));
        });

        Jwt token = Jwt.withTokenValue("test").header("alg", "none").subject(USER_A).build();
        StepVerifier.create(controller.chat(new ChatRequest(null, "expensive question"), token))
                .assertNext(event -> assertThat(event.event()).isEqualTo("conversation"))
                .assertNext(event -> assertThat(event.event()).isEqualTo("error"))
                .assertNext(event -> assertThat(event.event()).isEqualTo("done"))
                .verifyComplete();

        assertThat(jdbc.queryForList("SELECT total_tokens FROM " + usageTable
                + " WHERE user_id = ?", UUID.fromString(USER_A)))
                .singleElement()
                .satisfies(row -> assertThat(row).containsEntry("total_tokens", 30900));
    }

    /** Bounded wait for an asynchronous write. Fails the test rather than hanging the suite. */
    private void awaitMessageCount(UUID conversationId, int expected) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (conversations.messages(conversationId).size() >= expected) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("the assistant message was never persisted");
    }

    private List<ServerSentEvent<String>> post(String user, String body) {
        return client.post().uri("/api/chat")
                .header("Authorization", "Bearer " + TestJwks.validTokenFor(user))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(SSE_TYPE)
                .getResponseBody()
                .collectList()
                .block();
    }

    @Test
    void aFirstMessageOpensAConversationAndStreamsTheAnswer() {
        when(chatService.stream(any(), any())).thenReturn(Flux.just("Hello", " world"));

        List<ServerSentEvent<String>> events = post(USER_A, """
                {"message":"Who should I captain?"}
                """);

        // The conversation id comes first: the client needs it to send the next turn.
        assertThat(events.getFirst().event()).isEqualTo("conversation");
        UUID conversationId = UUID.fromString(events.getFirst().data().replace("\"", ""));

        assertThat(events).extracting(ServerSentEvent::data)
                .containsSubsequence("\"Hello\"", "\" world\"");
        assertThat(events.getLast().event()).isEqualTo("done");

        assertThat(conversations.messages(conversationId))
                .extracting(ConversationStore.StoredMessage::role,
                        ConversationStore.StoredMessage::content)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("user", "Who should I captain?"),
                        org.assertj.core.api.Assertions.tuple("assistant", "Hello world"));
    }

    @Test
    void asecondMessageContinuesTheSameConversation() {
        when(chatService.stream(any(), any())).thenReturn(Flux.just("first answer"));
        UUID id = UUID.fromString(post(USER_A, """
                {"message":"first question"}
                """).getFirst().data().replace("\"", ""));

        when(chatService.stream(any(), any())).thenReturn(Flux.just("second answer"));
        List<ServerSentEvent<String>> events = post(USER_A,
                "{\"conversationId\":\"" + id + "\",\"message\":\"second question\"}");

        assertThat(events.getFirst().data().replace("\"", ""))
                .as("the same conversation, not a new one")
                .isEqualTo(id.toString());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatTurn>> history = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(chatService, org.mockito.Mockito.atLeastOnce())
                .stream(history.capture(), any());
        assertThat(history.getValue()).extracting(ChatTurn::content)
                .as("the model is given what was said before, read back from the store")
                .containsExactly("first question", "first answer", "second question");
    }

    /** 404, not 403: an id that answers differently for its owner is an id you can probe for. */
    @Test
    void anotherUsersConversationDoesNotExist() {
        when(chatService.stream(any(), any())).thenReturn(Flux.just("answer"));
        UUID id = UUID.fromString(post(USER_A, """
                {"message":"mine"}
                """).getFirst().data().replace("\"", ""));

        client.post().uri("/api/chat")
                .header("Authorization", "Bearer " + TestJwks.validTokenFor(USER_B))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"conversationId\":\"" + id + "\",\"message\":\"yours?\"}")
                .exchange()
                .expectStatus().isNotFound();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + usageTable + " WHERE user_id = ?",
                Integer.class, UUID.fromString(USER_B)))
                .as("a turn that never happened is not charged")
                .isZero();
    }

    @Test
    void aMalformedConversationIdIsABadRequestAndABlankMessageIsToo() {
        client.post().uri("/api/chat")
                .header("Authorization", "Bearer " + TestJwks.validTokenFor(USER_A))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"conversationId\":\"not-a-uuid\",\"message\":\"hi\"}")
                .exchange()
                .expectStatus().isBadRequest();

        client.post().uri("/api/chat")
                .header("Authorization", "Bearer " + TestJwks.validTokenFor(USER_A))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"message\":\"   \"}")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void aMidStreamFailureEmitsAnErrorEventAndStoresNothing() {
        when(chatService.stream(any(), any()))
                .thenReturn(Flux.concat(Flux.just("partial"), Flux.error(new IllegalStateException("boom"))));

        List<ServerSentEvent<String>> events = post(USER_A, """
                {"message":"question"}
                """);
        UUID id = UUID.fromString(events.getFirst().data().replace("\"", ""));

        assertThat(events).extracting(ServerSentEvent::event).contains("error");
        assertThat(events.getLast().event()).isEqualTo("done");
        assertThat(conversations.messages(id))
                .as("the client discards the partial answer, so history must not keep it")
                .extracting(ConversationStore.StoredMessage::role)
                .containsExactly("user");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + usageTable + " WHERE user_id = ?",
                Integer.class, UUID.fromString(USER_A)))
                .as("a failure that generated nothing is not a charge — during a provider outage"
                        + " it would be everyone's week at once")
                .isZero();
    }

    /**
     * The guarantee that costs money if it breaks: a user who closes the tab has already paid
     * for the completion and must find it waiting when they come back.
     *
     * <p>Called directly rather than over HTTP because the point is what the returned publisher
     * does when its subscriber goes away, and MockMvc's SSE client will not walk out mid-stream.
     */
    @Test
    void anAnswerIsStoredEvenWhenTheClientLeavesMidStream() {
        Sinks.Many<String> model = Sinks.many().unicast().onBackpressureBuffer();
        when(chatService.stream(any(), any())).thenReturn(model.asFlux());

        Jwt token = Jwt.withTokenValue("test").header("alg", "none").subject(USER_A).build();
        Flux<ServerSentEvent<String>> response =
                controller.chat(new ChatRequest(null, "question"), token);

        // Read the conversation event and the first token, then leave.
        StepVerifier.create(response)
                .assertNext(event -> assertThat(event.event()).isEqualTo("conversation"))
                .then(() -> model.tryEmitNext("half an "))
                .assertNext(event -> assertThat(event.data()).isEqualTo("\"half an \""))
                .thenCancel()
                .verify();

        // The model keeps going after the client is gone.
        model.tryEmitNext("answer");
        model.tryEmitComplete();

        UUID id = conversations.list(UUID.fromString(USER_A)).getFirst().id();
        // The persist runs on a boundedElastic worker — the controller hops off the provider's
        // event loop before touching JDBC — so completion here is not the same instant as the
        // row landing.
        awaitMessageCount(id, 2);
        assertThat(conversations.messages(id))
                .extracting(ConversationStore.StoredMessage::role,
                        ConversationStore.StoredMessage::content)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("user", "question"),
                        org.assertj.core.api.Assertions.tuple("assistant", "half an answer"));
    }
}
