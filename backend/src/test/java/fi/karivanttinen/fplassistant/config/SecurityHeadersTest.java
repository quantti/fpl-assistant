package fi.karivanttinen.fplassistant.config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import fi.karivanttinen.fplassistant.chat.ChatService;
import fi.karivanttinen.fplassistant.myteam.MyTeamService;
import fi.karivanttinen.fplassistant.testsupport.LocalSigningKey;
import fi.karivanttinen.fplassistant.testsupport.TestJwks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * KAR-42: the headers the application sets itself survive {@code SecurityConfig}'s eager
 * security-header writing unchanged and unduplicated, read off real responses over MockMvc and
 * over the embedded container, with every repeated header line visible.
 *
 * <p>Only {@code Vary} may legitimately repeat. Everything asserted here reads the same whether
 * the security headers are written eagerly or on commit.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(LocalSigningKey.class)
// A context no other class shares: close it, or its connection pool stays cached and the suite
// exhausts the test database's max_connections.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SecurityHeadersTest {

    private static final String USER = "7c8d9e0f-4343-4343-8343-434343434343";
    private static final String QUOTA_USER = "7c8d9e0f-4444-4444-8444-444444444444";
    private static final String ORIGIN = "http://localhost:5173";
    /**
     * RFC 2544 benchmarking addresses, a block no other test uses: each request its own bucket of
     * the shared 20/minute limit, even when another class shares this cached context.
     */
    private static final AtomicInteger IP = new AtomicInteger(10);
    private static final List<String> SECURITY_CACHE_CONTROL =
            List.of("no-cache, no-store, max-age=0, must-revalidate");

    record Reply(int status, Map<String, List<String>> headers) {
        Reply {
            headers.forEach((name, values) -> {
                if (!name.equalsIgnoreCase("Vary")) {
                    assertThat(values).as("header %s appears once", name).hasSize(1);
                }
            });
        }

        List<String> values(String name) {
            return headers.getOrDefault(name, List.of());
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    MyTeamService myTeam;

    @MockitoBean
    ChatService chatService;

    @Value("${fpl.user.conversation-table-name}")
    String conversationTable;

    @Value("${fpl.user.usage-table-name}")
    String usageTable;

    @Value("${fpl.user.profile-table-name}")
    String profileTable;

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @AfterEach
    void clear() {
        for (String user : new String[] {USER, QUOTA_USER}) {
            UUID id = UUID.fromString(user);
            jdbc.update("DELETE FROM " + conversationTable + " WHERE user_id = ?", id);
            jdbc.update("DELETE FROM " + usageTable + " WHERE user_id = ?", id);
            jdbc.update("DELETE FROM " + profileTable + " WHERE user_id = ?", id);
        }
    }

    @Test
    void theMyTeamResponseIsNoStoreExactlyOnceOnSuccessAndOnError() throws Exception {
        for (MyTeamService.Result result : List.of(new MyTeamService.Result.NoTeamId(),
                new MyTeamService.Result.Unavailable())) {
            when(myTeam.myTeam(any(), any())).thenReturn(result);
            int status = result instanceof MyTeamService.Result.Unavailable ? 503 : 200;

            for (Reply reply : List.of(mock(authenticated(get("/api/my-team"), USER)),
                    servlet(request("/api/my-team", USER).GET()))) {
                assertThat(reply.status()).isEqualTo(status);
                assertThat(reply.values("Cache-Control")).containsExactly("no-store");
            }
        }
    }

    @Test
    void staticResourcesCarryOneSetOfCacheHeaders() throws Exception {
        for (Reply reply : List.of(mock(get("/assets/header-probe.css").header("Fly-Client-IP", ip())),
                servlet(request("/assets/header-probe.css", null).GET()))) {
            assertThat(reply.status()).isEqualTo(200);
            assertThat(reply.values("Content-Type")).containsExactly("text/css");
            assertThat(reply.values("Cache-Control")).isEqualTo(SECURITY_CACHE_CONTROL);
            assertThat(reply.values("Pragma")).containsExactly("no-cache");
            assertThat(reply.values("Expires")).containsExactly("0");
            assertThat(reply.values("Last-Modified")).hasSize(1);
        }
    }

    @Test
    void errorResponsesKeepTheirOwnHeaders() throws Exception {
        jdbc.update("INSERT INTO " + profileTable + " (user_id, weekly_message_limit) VALUES (?, 1)",
                UUID.fromString(QUOTA_USER));
        jdbc.update("INSERT INTO " + usageTable + " (user_id, event_type, created_at)"
                + " VALUES (?, 'chat', now() - interval '1 hour')", UUID.fromString(QUOTA_USER));
        String body = "{\"message\":\"one too many\"}";
        for (Reply quota : List.of(
                mock(authenticated(post("/api/chat"), QUOTA_USER)
                        .contentType("application/json").content(body)),
                servlet(request("/api/chat", QUOTA_USER).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))))) {
            assertThat(quota.status()).isEqualTo(429);
            assertThat(quota.values("Retry-After")).singleElement().asString().containsOnlyDigits();
            assertThat(quota.values("Content-Type")).containsExactly("text/plain");
            assertThat(quota.values("Cache-Control")).isEqualTo(SECURITY_CACHE_CONTROL);
        }

        for (Reply unauthenticated : List.of(mock(get("/api/my-team").header("Fly-Client-IP", ip())),
                servlet(request("/api/my-team", null).GET()))) {
            assertThat(unauthenticated.status()).isEqualTo(401);
            assertThat(unauthenticated.values("WWW-Authenticate")).hasSize(1);
        }

        for (Reply missing : List.of(mock(authenticated(get("/api/does-not-exist"), USER)),
                servlet(request("/api/does-not-exist", USER).GET()))) {
            assertThat(missing.status()).isEqualTo(404);
            assertThat(missing.values("Content-Type")).containsExactly("application/problem+json");
        }

        // Authenticated: the rate limit sits behind the security chain, so a request without a
        // token is answered 401 before it is counted.
        when(myTeam.myTeam(any(), any())).thenReturn(new MyTeamService.Result.NoTeamId());
        String limited = ip();
        String token = "Bearer " + TestJwks.validTokenFor(USER);
        for (int i = 0; i < RateLimitFilter.MAX_REQUESTS_PER_MINUTE; i++) {
            mockMvc.perform(get("/api/my-team").header("Fly-Client-IP", limited).header("Authorization", token));
        }
        Reply rateLimited = mock(get("/api/my-team").header("Fly-Client-IP", limited)
                .header("Authorization", token));
        assertThat(rateLimited.status()).isEqualTo(429);
        assertThat(rateLimited.values("Content-Type")).containsExactly("text/plain");
    }

    @Test
    void corsHeadersAreTheConfiguredOnes() throws Exception {
        for (Reply preflight : List.of(
                mock(options("/api/chat").header("Fly-Client-IP", ip()).header("Origin", ORIGIN)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,content-type")),
                servlet(request("/api/chat", null).header("Origin", ORIGIN)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,content-type")
                        .method("OPTIONS", HttpRequest.BodyPublishers.noBody())))) {
            assertThat(preflight.status()).isEqualTo(200);
            assertThat(preflight.values("Access-Control-Allow-Origin")).containsExactly(ORIGIN);
            assertThat(preflight.values("Access-Control-Allow-Methods"))
                    .containsExactly("GET,POST,PUT,DELETE,OPTIONS");
            assertThat(preflight.values("Access-Control-Allow-Headers"))
                    .containsExactly("authorization, content-type");
            assertThat(preflight.values("Access-Control-Expose-Headers")).containsExactly("Retry-After");
        }

        when(myTeam.myTeam(any(), any())).thenReturn(new MyTeamService.Result.NoTeamId());
        for (Reply actual : List.of(mock(authenticated(get("/api/my-team"), USER).header("Origin", ORIGIN)),
                servlet(request("/api/my-team", USER).header("Origin", ORIGIN).GET()))) {
            assertThat(actual.status()).isEqualTo(200);
            assertThat(actual.values("Access-Control-Allow-Origin")).containsExactly(ORIGIN);
            assertThat(actual.values("Access-Control-Expose-Headers")).containsExactly("Retry-After");
            assertThat(actual.values("Vary"))
                    .containsExactly("Origin", "Access-Control-Request-Method", "Access-Control-Request-Headers");
            assertThat(actual.values("Cache-Control")).containsExactly("no-store");
        }
    }

    @Test
    void theChatStreamIsServerSentEvents() throws Exception {
        when(chatService.stream(any(), any(), any())).thenAnswer(invocation -> Flux.just("hi"));
        String body = "{\"message\":\"hello\"}";

        MvcResult started = mockMvc.perform(authenticated(post("/api/chat"), USER)
                .contentType("application/json").accept("text/event-stream").content(body)).andReturn();
        Reply dispatched = reply(mockMvc.perform(asyncDispatch(started)).andReturn().getResponse());
        Reply served = servlet(request("/api/chat", USER).header("Content-Type", "application/json")
                .header("Accept", "text/event-stream").POST(HttpRequest.BodyPublishers.ofString(body)));

        for (Reply reply : List.of(dispatched, served)) {
            assertThat(reply.status()).isEqualTo(200);
            assertThat(reply.values("Content-Type")).containsExactly("text/event-stream");
            assertThat(reply.values("Cache-Control")).isEqualTo(SECURITY_CACHE_CONTROL);
        }
    }

    private static MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder builder,
                                                               String user) {
        return builder.header("Fly-Client-IP", ip())
                .header("Authorization", "Bearer " + TestJwks.validTokenFor(user));
    }

    private HttpRequest.Builder request(String path, String user) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Fly-Client-IP", ip());
        return user == null ? builder
                : builder.header("Authorization", "Bearer " + TestJwks.validTokenFor(user));
    }

    private static String ip() {
        return "198.18.42." + IP.incrementAndGet();
    }

    private Reply mock(MockHttpServletRequestBuilder request) throws Exception {
        return reply(mockMvc.perform(request).andReturn().getResponse());
    }

    private static Reply reply(MockHttpServletResponse response) {
        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String name : response.getHeaderNames()) {
            List<String> values = new ArrayList<>();
            response.getHeaderValues(name).forEach(value -> values.add(String.valueOf(value)));
            headers.put(name, values);
        }
        return new Reply(response.getStatus(), headers);
    }

    private Reply servlet(HttpRequest.Builder request) throws Exception {
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.putAll(response.headers().map());
        return new Reply(response.statusCode(), headers);
    }
}
