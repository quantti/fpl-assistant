package dev.quantti.fplassistant.chat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.quantti.fplassistant.shortlist.PlayerCatalogueStore;
import dev.quantti.fplassistant.testsupport.LocalSigningKey;
import dev.quantti.fplassistant.testsupport.TestJwks;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.client.MockMvcWebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(LocalSigningKey.class)
class ChatCatalogueMetadataTest {

    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE =
            new ParameterizedTypeReference<>() {};
    private static final UUID USER = UUID.fromString("75111111-1111-4111-8111-111111111111");
    private static final String SEASON_PREFIX = "M-";
    private static final LocalDate DAY = LocalDate.of(2094, 8, 1);
    private static final Instant CAPTURED = Instant.parse("2094-08-01T01:00:00Z");

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ChatController controller;
    @Autowired ConversationStore conversations;
    @MockitoBean ChatService chatService;
    @MockitoSpyBean PlayerCatalogueStore catalogues;
    @Value("${fpl.daily-price.table-name}") String dailyTable;
    @Value("${fpl.shortlist.catalogue-table-name}") String catalogueTable;
    @Value("${fpl.shortlist.catalogue-player-table-name}") String playerTable;
    @Value("${fpl.shortlist.catalogue-alias-table-name}") String aliasTable;
    @Value("${fpl.user.conversation-table-name}") String conversationTable;
    @Value("${fpl.user.usage-table-name}") String usageTable;
    @Value("${fpl.user.profile-table-name}") String profileTable;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM " + conversationTable + " WHERE user_id = ?", USER);
        jdbc.update("DELETE FROM " + usageTable + " WHERE user_id = ?", USER);
        jdbc.update("DELETE FROM " + profileTable + " WHERE user_id = ?", USER);
        jdbc.update("DELETE FROM " + aliasTable + " WHERE catalogue_id IN (SELECT id FROM "
                + catalogueTable + " WHERE season LIKE ?)", SEASON_PREFIX + "%");
        jdbc.update("DELETE FROM " + playerTable + " WHERE catalogue_id IN (SELECT id FROM "
                + catalogueTable + " WHERE season LIKE ?)", SEASON_PREFIX + "%");
        jdbc.update("DELETE FROM " + catalogueTable + " WHERE season LIKE ?", SEASON_PREFIX + "%");
        jdbc.update("DELETE FROM " + dailyTable + " WHERE season LIKE ?", SEASON_PREFIX + "%");
    }

    @Test
    void oneCatalogueIsEmittedBeforeTokensAndBoundToDetachedCompletedHistory() throws Exception {
        seed("M-BOUND", DAY, CAPTURED, 17, "Saka", "Bukayo Saka");
        PlayerCatalogueStore.Catalogue expected = catalogues.latest().orElseThrow();
        org.mockito.Mockito.clearInvocations(catalogues);

        Sinks.Many<String> model = Sinks.many().unicast().onBackpressureBuffer();
        AtomicInteger subscriptions = new AtomicInteger();
        when(chatService.stream(any(), any(), any()))
                .thenReturn(model.asFlux().doOnSubscribe(ignored -> subscriptions.incrementAndGet()));
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "none").subject(USER.toString()).build();
        Flux<ServerSentEvent<String>> response = controller.chat(new ChatRequest(null, "question"), jwt);

        UUID conversation = conversations.list(USER).getFirst().id();
        StepVerifier.create(response)
                .assertNext(event -> assertThat(event.event()).isEqualTo("conversation"))
                .assertNext(event -> {
                    assertThat(event.event()).isEqualTo("catalogue");
                    JsonNode json = json(event.data());
                    assertThat(json.path("catalogueId").asText()).isEqualTo(expected.id().toString());
                    assertThat(json.path("season").asText()).isEqualTo("M-BOUND");
                    assertThat(json.path("players").get(0).path("aliases").get(0).asText())
                            .isEqualTo("Bukayo Saka");
                })
                .thenCancel()
                .verify();

        // A newer identity capture arriving after the turn started must not rebind its answer.
        seed("M-NEWER", DAY.plusDays(1), CAPTURED.plusSeconds(86_400),
                99, "Different", "Different Player");
        model.tryEmitNext("Saka is a strong option.");
        model.tryEmitComplete();
        awaitMessages(conversation, 2);

        assertThat(subscriptions).hasValue(1);
        verify(catalogues, times(1)).latest();
        assertThat(conversations.messages(conversation).getLast())
                .satisfies(message -> {
                    assertThat(message.content()).isEqualTo("Saka is a strong option.");
                    assertThat(message.catalogueId()).isEqualTo(expected.id());
                });
        conversations.append(conversation, ChatTurn.ASSISTANT, "Saka remains the pick.", expected.id());
        org.mockito.Mockito.clearInvocations(catalogues);

        WebTestClient client = MockMvcWebTestClient.bindTo(mockMvc).build();
        client.get().uri("/api/conversations/" + conversation + "/messages")
                .header("Authorization", "Bearer " + TestJwks.validTokenFor(USER.toString()))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.messages[0].catalogueId").doesNotExist()
                .jsonPath("$.messages[1].catalogueId").isEqualTo(expected.id().toString())
                .jsonPath("$.messages[2].catalogueId").isEqualTo(expected.id().toString())
                .jsonPath("$.catalogues.length()").isEqualTo(1)
                .jsonPath("$['catalogues']['" + expected.id() + "'].catalogueId")
                .isEqualTo(expected.id().toString())
                .jsonPath("$['catalogues']['" + expected.id() + "'].season").isEqualTo("M-BOUND")
                .jsonPath("$['catalogues']['" + expected.id() + "'].players[0].elementId")
                .isEqualTo(17);
        verify(catalogues, times(1)).find(expected.id());
    }

    @Test
    void catalogueFailureDegradesToPlainChatAndPlainHistory() {
        doThrow(new IllegalStateException("catalogue unavailable")).when(catalogues).latest();
        when(chatService.stream(any(), any(), any())).thenReturn(Flux.just("plain answer"));

        List<ServerSentEvent<String>> events = post("plain question");
        UUID conversation = UUID.fromString(events.getFirst().data().replace("\"", ""));

        assertThat(events).extracting(ServerSentEvent::event)
                .containsExactly("conversation", null, "done");
        assertThat(conversations.messages(conversation).getLast().catalogueId()).isNull();
    }

    @Test
    void catalogueMetadataNeverEntersModelHistoryAndLegacyRowsStayPlain() {
        seed("M-TEXT", DAY, CAPTURED, 17, "Saka", "Bukayo Saka");
        UUID conversation = conversations.create(USER, "legacy");
        conversations.append(conversation, ChatTurn.USER, "legacy question");
        conversations.append(conversation, ChatTurn.ASSISTANT, "legacy Saka answer");
        when(chatService.stream(any(), any(), any())).thenReturn(Flux.just("new answer"));

        post(conversation, "next question");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatTurn>> history = ArgumentCaptor.forClass(List.class);
        verify(chatService).stream(history.capture(), any(), any());
        assertThat(history.getValue()).extracting(ChatTurn::content).containsExactly(
                "legacy question", "legacy Saka answer", "next question");
        assertThat(conversations.messages(conversation).get(1).catalogueId()).isNull();
    }

    private List<ServerSentEvent<String>> post(String message) {
        return post(null, message);
    }

    private List<ServerSentEvent<String>> post(UUID conversation, String message) {
        String body = conversation == null
                ? "{\"message\":\"" + message + "\"}"
                : "{\"conversationId\":\"" + conversation + "\",\"message\":\"" + message + "\"}";
        return MockMvcWebTestClient.bindTo(mockMvc).build().post().uri("/api/chat")
                .header("Authorization", "Bearer " + TestJwks.validTokenFor(USER.toString()))
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body).exchange().expectStatus().isOk().returnResult(SSE)
                .getResponseBody().collectList().block();
    }

    private void seed(String season, LocalDate date, Instant capturedAt, int elementId,
                      String webName, String fullName) {
        jdbc.update("""
                INSERT INTO %s (season, price_date, element_id, web_name, full_name, team_id,
                                team_short, team_name, element_type, now_cost, captured_at)
                VALUES (?, ?, ?, ?, ?, 1, 'ARS', 'Arsenal', 3, 50, ?)
                """.formatted(dailyTable), season, date, elementId, webName, fullName,
                Timestamp.from(capturedAt));
    }

    private static JsonNode json(String value) {
        try {
            return new ObjectMapper().readTree(value);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    private void awaitMessages(UUID conversation, int count) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline && conversations.messages(conversation).size() < count) {
            Thread.sleep(25);
        }
        assertThat(conversations.messages(conversation)).hasSize(count);
    }
}
