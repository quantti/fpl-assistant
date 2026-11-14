package dev.quantti.fplassistant.price;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNoException;

class DailyPriceEmailAlertTest {
    private HttpServer server;
    private ExecutorService executor;
    private final BlockingQueue<Request> requests = new LinkedBlockingQueue<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicInteger delayMs = new AtomicInteger();
    private final LocalDate expected = LocalDate.of(2026, 10, 13);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Logger logger;

    @BeforeEach
    void start() throws Exception {
        logger = (Logger) LoggerFactory.getLogger(DailyPriceEmailAlert.class);
        logs.start();
        logger.addAppender(logs);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/emails", exchange -> {
            requests.add(new Request(exchange.getRequestMethod(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            try {
                Thread.sleep(delayMs.get());
                exchange.getResponseHeaders().set("Location", baseUrl() + "/redirected");
                byte[] body = "{\"id\":\"mail-1\",\"message\":\"sensitive-provider-body\"}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status.get(), body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.createContext("/redirected", exchange -> {
            requests.add(new Request("REDIRECTED", "", "", ""));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        executor.shutdownNow();
        executor.close();
        logger.detachAppender(logs);
        logs.stop();
    }

    @Test
    void sendsPlainTextDatesToOwnerUsingBearerAuthentication() throws Exception {
        sender(true, "test-key", Duration.ofSeconds(2)).send(expected.minusDays(1), expected);
        assertThat(requests).hasSize(1);
        Request request = requests.remove();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.authorization()).isEqualTo("Bearer test-key");
        assertThat(request.contentType()).startsWith("application/json");
        var body = JsonMapper.builder().build().readTree(request.body());
        assertThat(body.get("from").asText()).isEqualTo("FPL Sidekick <noreply@fpl-sidekick.com>");
        assertThat(body.get("to").get(0).asText()).isEqualTo("operator@example.com");
        assertThat(body.get("subject").asText()).contains("FPL Sidekick", "2026-10-13");
        assertThat(body.get("text").asText()).contains("2026-10-12", "2026-10-13", "Europe/London")
                .doesNotContain("--sync-prices");
        assertThat(body.has("html")).isFalse();
        assertThat(messages()).contains("accepted").doesNotContain("delivered", "test-key");
    }

    @Test
    void emptyTableIsExplicitRatherThanInventingALatestDate() {
        sender(true, "test-key", Duration.ofSeconds(2)).send(null, expected);
        assertThat(requests).hasSize(1);
        assertThat(requests.remove().body()).contains("no capture", "2026-10-13");
    }

    @Test
    void disabledOrIncompleteConfigurationMakesNoRequest() {
        sender(false, "test-key", Duration.ofSeconds(2)).send(null, expected);
        assertThat(messages()).isEmpty();
        sender(true, " ", Duration.ofSeconds(2)).send(null, expected);
        new DailyPriceEmailAlert(RestClient.builder(), true, "test-key", "", "operator@example.com",
                baseUrl(), Duration.ofSeconds(2)).send(null, expected);
        new DailyPriceEmailAlert(RestClient.builder(), true, "test-key", "noreply@fpl-sidekick.com", " ",
                baseUrl(), Duration.ofSeconds(2)).send(null, expected);
        assertThat(requests).isEmpty();
        assertThat(logs.list).hasSize(3).allSatisfy(event -> {
            assertThat(event.getLevel().toString()).isEqualTo("ERROR");
            assertThat(event.getFormattedMessage()).contains("configuration");
        });
    }

    @Test
    void rejectionIsContainedWithoutRetriesOrSensitiveLogs() {
        for (int code : new int[]{401, 429, 500}) {
            status.set(code);
            assertThatNoException().isThrownBy(() ->
                    sender(true, "test-key", Duration.ofSeconds(2)).send(null, expected));
        }
        assertThat(requests).hasSize(3);
        assertThat(messages()).contains("401", "429", "500")
                .doesNotContain("test-key", "sensitive-provider-body", "accepted");
        assertThat(logs.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
    }

    @Test
    void malformedCredentialIsContainedWithoutLeakingIt() {
        assertThatNoException().isThrownBy(() ->
                sender(true, "test-key\r\ninvalid", Duration.ofSeconds(2)).send(null, expected));
        assertThat(requests).isEmpty();
        assertThat(messages()).contains("failed").doesNotContain("test-key", "invalid", "accepted");
    }

    @Test
    void redirectsAreNotFollowedOrReportedAsAccepted() {
        status.set(307);
        sender(true, "test-key", Duration.ofSeconds(2)).send(null, expected);
        assertThat(requests).hasSize(1);
        assertThat(messages()).contains("307").doesNotContain("accepted");
    }

    @Test
    void timeoutIsBoundedAndNextScheduledAttemptCanSucceed() {
        delayMs.set(1000);
        DailyPriceEmailAlert sender = sender(true, "test-key", Duration.ofMillis(150));
        long start = System.nanoTime();
        assertThatCode(() -> sender.send(null, expected)).doesNotThrowAnyException();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
        assertThat(requests).hasSize(1);
        assertThat(messages()).contains("failed").doesNotContain("test-key", "accepted");
        delayMs.set(0);
        sender.send(null, expected);
        assertThat(requests).hasSize(2);
        assertThat(messages()).contains("accepted");
    }

    private DailyPriceEmailAlert sender(boolean enabled, String key, Duration timeout) {
        return new DailyPriceEmailAlert(RestClient.builder(), enabled, key,
                "FPL Sidekick <noreply@fpl-sidekick.com>", "operator@example.com", baseUrl(), timeout);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private String messages() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage)
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private record Request(String method, String authorization, String contentType, String body) {}
}
