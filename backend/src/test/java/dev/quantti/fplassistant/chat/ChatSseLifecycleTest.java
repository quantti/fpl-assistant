package dev.quantti.fplassistant.chat;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Stream;

import dev.quantti.fplassistant.chat.SseLifecycleProbe.Ordering;
import dev.quantti.fplassistant.chat.SseLifecycleProbe.Run;
import dev.quantti.fplassistant.testsupport.LocalSigningKey;
import dev.quantti.fplassistant.testsupport.TestJwks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.client.MockMvcWebTestClient;
import reactor.core.publisher.Sinks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * KAR-42: the chat SSE lifecycle under controlled phase orderings, over MockMvc and over the
 * embedded servlet container.
 *
 * <p>The two scenarios are the two CI failures of run 34366423923. Each runs in three orderings
 * forced by {@link SseLifecycleProbe}; every ordering is asserted to have occurred before its
 * outcome is judged. The invariant checked last is the demonstrated cause: while the request
 * thread is still unwinding through the filter chain, the emitter thread's first commit writes
 * the security headers ({@code HeaderWriterFilter}'s on-commit hook) and the request thread
 * writes them again from {@code HeaderWriterFilter}'s {@code finally} — two threads mutating
 * one response's headers with nothing ordering them.
 *
 * <p>The full 12-case matrix stays in the normal suite: it is deterministic, takes about 3 s with
 * no loops or retries, and its non-regression cases carry the wire-contract assertions over both
 * transports.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({LocalSigningKey.class, SseLifecycleProbe.Config.class})
// A context no other class shares: close it, or its connection pool stays cached and the suite
// exhausts the test database's max_connections.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ChatSseLifecycleTest {

    private static final String USER = "6b7c8d9e-4242-4242-8242-424242424242";
    private static final Duration CASE_TIMEOUT = Duration.ofSeconds(20);
    /** TEST-NET-2 addresses: each case its own bucket of the shared 20/minute limit. */
    private static final AtomicInteger IP = new AtomicInteger(10);
    /** A leading space and an embedded newline: both must survive SSE framing. */
    private static final String AWKWARD_TOKEN = " an\nswer";

    enum Transport { MOCK_MVC, SERVLET }

    enum Scenario { READ_CEILING_TRUNCATED, MID_STREAM_FAILURE }

    record Exchange(int status, String contentType, String body) {}

    record Event(String name, String data) {}

    @LocalServerPort
    int port;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ConversationStore conversations;

    @Autowired
    SseLifecycleProbe probe;

    @MockitoBean
    ChatService chatService;

    @Value("${fpl.user.conversation-table-name}")
    String conversationTable;

    @Value("${fpl.user.usage-table-name}")
    String usageTable;

    @Value("${fpl.user.message-table-name}")
    String messageTable;

    @Value("${fpl.user.profile-table-name}")
    String profileTable;

    static Stream<Arguments> cases() {
        List<Arguments> cases = new ArrayList<>();
        for (Transport transport : Transport.values()) {
            for (Scenario scenario : Scenario.values()) {
                for (Ordering ordering : Ordering.values()) {
                    cases.add(Arguments.of(transport, scenario, ordering));
                }
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} {1} {2}")
    @MethodSource("cases")
    void lifecycle(Transport transport, Scenario scenario, Ordering ordering) throws Exception {
        String caseId = transport + "-" + scenario + "-" + ordering;
        Run run = probe.arm(caseId, ordering,
                transport == Transport.SERVLET && ordering == Ordering.TERMINAL_DURING_DISPATCH);
        Sinks.Many<String> model = Sinks.many().unicast().onBackpressureBuffer();
        AtomicBoolean streamed = new AtomicBoolean();
        when(chatService.stream(any(), any(), any())).thenAnswer(invocation -> {
            if (scenario == Scenario.READ_CEILING_TRUNCATED) {
                // Measured usage, so the completion path always ends in recordTokens: the
                // detached work then has one observable last write to await.
                Consumer<TokenUsage> onUsage = invocation.getArgument(2);
                onUsage.accept(new TokenUsage("fake-model", 10, 0, 0, 5));
            }
            streamed.set(true);
            return model.asFlux();
        });

        UUID conversation = null;
        String body = "{\"message\":\"question\"}";
        if (scenario == Scenario.READ_CEILING_TRUNCATED) {
            conversation = conversations.create(UUID.fromString(USER), "very long one");
            jdbc.update("INSERT INTO " + messageTable + " (conversation_id, role, content)"
                    + " SELECT ?, CASE WHEN i % 2 = 0 THEN 'user' ELSE 'assistant' END, 'turn ' || i"
                    + " FROM generate_series(1, ?) AS i",
                    conversation, ConversationStore.MAX_MESSAGES + 20);
            body = "{\"conversationId\":\"" + conversation + "\",\"message\":\"and now?\"}";
        }

        ExecutorService clientThread = Executors.newSingleThreadExecutor(r -> new Thread(r, "kar42-client"));
        HttpClient http = HttpClient.newBuilder().connectTimeout(SseLifecycleProbe.PHASE_TIMEOUT).build();
        Throwable failure = null;
        try {
            String requestBody = body;
            String ip = "198.51.100." + IP.getAndIncrement();
            Future<Exchange> exchange = clientThread.submit(() -> transport == Transport.MOCK_MVC
                    ? viaMockMvc(run, ip, requestBody)
                    : viaServlet(http, run, ip, requestBody));

            // The model answers only once the request thread has left the filter chain, so the
            // start of the stream is decided by the ordering alone.
            assertThat(run.requestExited.await(CASE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                    .as("the request thread left the filter chain").isTrue();
            if (scenario == Scenario.READ_CEILING_TRUNCATED) {
                assertThat(model.tryEmitNext(AWKWARD_TOKEN).isSuccess()).isTrue();
                assertThat(model.tryEmitComplete().isSuccess()).isTrue();
            }
            else {
                assertThat(model.tryEmitNext("partial").isSuccess()).isTrue();
                assertThat(model.tryEmitError(new IllegalStateException("boom")).isSuccess()).isTrue();
            }
            Exchange result = exchange.get(CASE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

            assertThat(run.timeouts).as("probe holds that ran out of time").isEmpty();
            assertOrderingOccurred(run, transport);
            assertContract(result, scenario, conversation);
            assertThat(run.overlaps)
                    .as("response headers mutated by the request thread while the emitter thread"
                            + " was committing the same response (KAR-42 cause)")
                    .isEmpty();
        }
        catch (Throwable t) {
            failure = t;
            throw t;
        }
        finally {
            teardown(run, model, scenario == Scenario.READ_CEILING_TRUNCATED, streamed.get(),
                    clientThread, http, caseId, failure);
        }
    }

    /**
     * A browser that closes a real HTTP response mid-answer: the model finishes afterwards, and
     * the answer is still stored exactly once and its measured usage recorded. No probe ordering
     * is armed; this is the plain servlet path.
     */
    @Test
    void anAnswerOutlivesABrowserThatClosedTheResponse() throws Exception {
        String caseId = "SERVLET-BROWSER-DISCONNECT";
        Sinks.Many<String> model = Sinks.many().unicast().onBackpressureBuffer();
        AtomicReference<Consumer<TokenUsage>> usage = new AtomicReference<>();
        AtomicBoolean streamed = new AtomicBoolean();
        when(chatService.stream(any(), any(), any())).thenAnswer(invocation -> {
            usage.set(invocation.getArgument(2));
            streamed.set(true);
            return model.asFlux();
        });

        ExecutorService clientThread = Executors.newSingleThreadExecutor(r -> new Thread(r, "kar42-client"));
        HttpClient http = HttpClient.newBuilder().connectTimeout(SseLifecycleProbe.PHASE_TIMEOUT).build();
        CountDownLatch sawConversation = new CountDownLatch(1);
        Throwable failure = null;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/chat"))
                    .timeout(CASE_TIMEOUT)
                    .header("Authorization", "Bearer " + TestJwks.validTokenFor(USER))
                    .header("Fly-Client-IP", "198.51.100." + IP.getAndIncrement())
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .header("Accept", MediaType.TEXT_EVENT_STREAM_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"question\"}"))
                    .build();
            // Reads the conversation event and the first token, then closes the body.
            Future<List<String>> read = clientThread.submit(() -> {
                HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                assertThat(response.statusCode()).isEqualTo(200);
                List<String> lines = new ArrayList<>();
                try (BufferedReader body = new BufferedReader(
                        new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                    int data = 0;
                    for (String line; data < 2 && (line = body.readLine()) != null; ) {
                        lines.add(line);
                        if (line.equals("event:conversation")) {
                            sawConversation.countDown();
                        }
                        if (line.startsWith("data:")) {
                            data++;
                        }
                    }
                }
                return lines;
            });

            assertThat(sawConversation.await(CASE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                    .as("the client read the conversation event").isTrue();
            assertThat(model.tryEmitNext("half an ").isSuccess()).isTrue();
            List<String> lines = read.get(CASE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(lines).first().isEqualTo("event:conversation");
            assertThat(lines).last().isEqualTo("data:\"half an \"");
            UUID conversation = UUID.fromString(lines.get(1).substring("data:".length()).replace("\"", ""));

            // The browser is gone; the model finishes and reports what it measured.
            usage.get().accept(new TokenUsage("fake-model", 10, 0, 0, 5));
            assertThat(model.tryEmitNext("answer").isSuccess()).isTrue();
            assertThat(model.tryEmitComplete().isSuccess()).isTrue();

            assertThat(await(() -> detachedWorkDone(true)))
                    .as("the single usage row carries the measured tokens").isTrue();
            assertThat(jdbc.queryForList("SELECT content FROM " + messageTable
                    + " WHERE conversation_id = ? AND role = 'assistant'", String.class, conversation))
                    .as("the whole answer, stored exactly once")
                    .containsExactly("half an answer");
            assertThat(jdbc.queryForObject("SELECT total_tokens FROM " + usageTable + " WHERE user_id = ?",
                    Integer.class, UUID.fromString(USER))).isEqualTo(15);
        }
        catch (Throwable t) {
            failure = t;
            throw t;
        }
        finally {
            teardown(null, model, true, streamed.get(), clientThread, http, caseId, failure);
        }
    }

    private Exchange viaMockMvc(Run run, String ip, String body) {
        EntityExchangeResult<String> result = MockMvcWebTestClient.bindTo(mockMvc).build()
                .post().uri("/api/chat")
                .header("Authorization", "Bearer " + TestJwks.validTokenFor(USER))
                .header("Fly-Client-IP", ip)
                .header(SseLifecycleProbe.RUN_HEADER, run.id)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .exchange()
                .expectBody(String.class)
                .returnResult();
        run.mark("client.response-adapted", "");
        return new Exchange(result.getStatus().value(),
                result.getResponseHeaders().getFirst(HttpHeaders.CONTENT_TYPE), result.getResponseBody());
    }

    private Exchange viaServlet(HttpClient http, Run run, String ip, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/chat"))
                .timeout(CASE_TIMEOUT)
                .header("Authorization", "Bearer " + TestJwks.validTokenFor(USER))
                .header("Fly-Client-IP", ip)
                .header(SseLifecycleProbe.RUN_HEADER, run.id)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .header("Accept", MediaType.TEXT_EVENT_STREAM_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<Stream<String>> response = http.send(request, HttpResponse.BodyHandlers.ofLines());
        StringBuilder text = new StringBuilder();
        try (Stream<String> lines = response.body()) {
            lines.forEach(line -> {
                text.append(line).append('\n');
                if (line.equals("event:done")) {
                    run.mark("client.saw-done", "");
                    run.clientSawDone.countDown();
                }
            });
        }
        run.mark("client.end-of-stream", "");
        return new Exchange(response.statusCode(),
                response.headers().firstValue("Content-Type").orElse(null), text.toString());
    }

    private static void assertOrderingOccurred(Run run, Transport transport) {
        assertThat(run.sendTasks.get()).as("send tasks went through the probed MVC executor").isPositive();
        String requestThread = run.thread("request.enter");
        switch (run.ordering) {
            case EARLY_QUEUED, TERMINAL_DURING_DISPATCH -> {
                assertThat(run.seq("send-task-1.end"))
                        .as("the first send task ran while the request thread waited before initialize")
                        .isPositive()
                        .isLessThan(run.seq("before-initialize.released"));
                assertThat(run.thread("commit"))
                        .as("initialize flushed the queued events on the request thread")
                        .isEqualTo(requestThread);
                assertThat(run.seq("commit")).isLessThan(run.seq("emitter.initialized"));
            }
            case FIRST_FLUSH_DURING_UNWIND -> {
                assertThat(run.seq("send-task-1.released"))
                        .as("the first send task started only after initialize returned")
                        .isGreaterThan(run.seq("emitter.initialized"));
                // A header write or a flush: a repair that stops the on-commit header writes must
                // still satisfy this precondition and be judged by the overlap assertion alone.
                assertThat(run.seq("emitter.reached-response"))
                        .as("the emitter thread reached the response before the request thread unwound")
                        .isPositive()
                        .isLessThan(run.seq("request.exit"));
                assertThat(run.thread("commit"))
                        .as("the first commit was the emitter thread's, not initialize's")
                        .isNotEqualTo(requestThread);
            }
        }
        if (run.ordering == Ordering.TERMINAL_DURING_DISPATCH) {
            int resultSet = run.seq("result-set.before-async-dispatch");
            assertThat(resultSet).as("the completion set the deferred result").isPositive();
            assertThat(run.seq("async.enter"))
                    .as("the async dispatch followed the terminal flush and result")
                    .isGreaterThan(resultSet);
            assertThat(run.lateEmitterOps)
                    .as("no response operation by the completing thread after its result was set")
                    .isEmpty();
            if (transport == Transport.SERVLET) {
                assertThat(run.seq("client.saw-done"))
                        .as("the client read the terminal event while completion was held")
                        .isPositive()
                        .isLessThan(run.seq("completion.released"));
            }
            else {
                assertThat(run.seq("client.response-adapted")).isGreaterThan(run.seq("async.exit"));
            }
        }
    }

    private void assertContract(Exchange result, Scenario scenario, UUID existing) {
        assertThat(result.status()).isEqualTo(200);
        assertThat(MediaType.parseMediaType(result.contentType()).isCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .isTrue();
        List<Event> events = events(result.body());
        UUID conversation = UUID.fromString(events.getFirst().data().replace("\"", ""));
        if (scenario == Scenario.READ_CEILING_TRUNCATED) {
            assertThat(events).extracting(Event::name)
                    .containsExactly("conversation", "truncated", "message", "done");
            // On the wire the token is one JSON-string line: the leading space survives the
            // decoder, and the newline cannot split the event.
            assertThat(result.body().split("\n")).contains("data:\" an\\nswer\"");
            assertThat(events.get(2).data()).isEqualTo("\" an\\nswer\"");
            assertThat(conversation).isEqualTo(existing);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + messageTable
                    + " WHERE conversation_id = ? AND role = 'assistant' AND content = ?",
                    Integer.class, conversation, AWKWARD_TOKEN)).isEqualTo(1);
        }
        else {
            assertThat(events).extracting(Event::name)
                    .containsExactly("conversation", "message", "error", "done");
            assertThat(events.get(1).data()).isEqualTo("\"partial\"");
            assertThat(result.body().split("\n")).contains("data:" + ChatController.GENERATION_FAILED);
            assertThat(conversations.messages(conversation))
                    .extracting(ConversationStore.StoredMessage::role)
                    .containsExactly("user");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + usageTable + " WHERE user_id = ?",
                    Integer.class, UUID.fromString(USER))).isZero();
        }
    }

    /** SSE wire framing: blank-line separated blocks of {@code event:} and {@code data:} lines. */
    private static List<Event> events(String body) {
        List<Event> events = new ArrayList<>();
        for (String block : body.split("\n\n")) {
            String name = "message";
            String data = null;
            for (String line : block.split("\n")) {
                if (line.startsWith("event:")) {
                    name = line.substring("event:".length());
                }
                else if (line.startsWith("data:")) {
                    data = line.substring("data:".length());
                }
            }
            if (data != null) {
                events.add(new Event(name, data));
            }
        }
        return events;
    }

    /**
     * Releases every probe hold, ends the fake model, waits for the client and for the detached
     * subscription's last write, closes the client and only then deletes the seeded rows — on
     * the failure path as much as the passing one.
     */
    private void teardown(Run run, Sinks.Many<String> model, boolean measured, boolean streamed,
                          ExecutorService clientThread, HttpClient http, String caseId, Throwable failure) {
        List<String> problems = new ArrayList<>();
        if (run != null) {
            run.releaseAll();
        }
        if (measured) {
            model.tryEmitComplete();
        }
        else {
            model.tryEmitError(new IllegalStateException("teardown"));
        }
        clientThread.shutdown();
        try {
            if (!clientThread.awaitTermination(CASE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                clientThread.shutdownNow();
                if (!clientThread.awaitTermination(SseLifecycleProbe.PHASE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    problems.add("client thread did not stop");
                }
            }
            if (streamed && !await(() -> detachedWorkDone(measured))) {
                problems.add("the detached persistence/accounting did not finish");
            }
            http.shutdownNow();
            if (!http.awaitTermination(SseLifecycleProbe.PHASE_TIMEOUT)) {
                problems.add("HTTP client did not terminate");
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            problems.add("interrupted during teardown");
        }
        finally {
            probe.disarm();
            if (run != null) {
                writeLog(run, caseId, failure, problems);
            }
            UUID id = UUID.fromString(USER);
            jdbc.update("DELETE FROM " + conversationTable + " WHERE user_id = ?", id);
            jdbc.update("DELETE FROM " + usageTable + " WHERE user_id = ?", id);
            jdbc.update("DELETE FROM " + profileTable + " WHERE user_id = ?", id);
        }
        if (failure == null && !problems.isEmpty()) {
            throw new AssertionError("teardown: " + problems);
        }
    }

    /**
     * The last write either terminal path makes: the single usage row's tokens recorded, or the
     * charge discarded. Keyed on a stored column: {@code total_tokens} is a generated sum of
     * COALESCEd columns, so it is 0, never NULL, from the moment the turn is claimed.
     */
    private boolean detachedWorkDone(boolean measured) {
        UUID id = UUID.fromString(USER);
        if (measured) {
            return Boolean.TRUE.equals(jdbc.queryForObject("SELECT count(*) = 1"
                    + " AND bool_and(input_tokens IS NOT NULL) FROM " + usageTable
                    + " WHERE user_id = ?", Boolean.class, id));
        }
        return jdbc.queryForObject("SELECT count(*) FROM " + usageTable + " WHERE user_id = ?",
                Integer.class, id) == 0;
    }

    private static boolean await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + SseLifecycleProbe.PHASE_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(25);
        }
        return condition.getAsBoolean();
    }

    private static void writeLog(Run run, String caseId, Throwable failure, List<String> problems) {
        StringBuilder out = new StringBuilder("case: " + caseId + "\n");
        run.log().forEach(line -> out.append(line).append('\n'));
        out.append("overlaps: ").append(run.overlaps).append('\n');
        out.append("timeouts: ").append(run.timeouts).append('\n');
        out.append("teardown problems: ").append(problems).append('\n');
        if (failure != null) {
            StringWriter stack = new StringWriter();
            failure.printStackTrace(new PrintWriter(stack));
            out.append("failure:\n").append(stack);
        }
        SseLifecycleProbe.writeDiagnostic("lifecycle/" + caseId + ".log", out.toString());
    }
}
