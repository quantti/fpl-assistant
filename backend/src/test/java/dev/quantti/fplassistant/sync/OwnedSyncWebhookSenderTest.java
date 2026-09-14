package dev.quantti.fplassistant.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

class OwnedSyncWebhookSenderTest {

    @Test
    void productionRejectsHttpAndTestSeamAllowsLoopbackOnly() throws Exception {
        assertThatThrownBy(() -> new OwnedSyncWatchdog.HttpsWebhookSender("http://127.0.0.1/hook"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> new OwnedSyncWatchdog.HttpsWebhookSender(
                URI.create("http://example.test/hook"), HttpClient.newHttpClient(), true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> new OwnedSyncWatchdog.HttpsWebhookSender("file:///tmp/hook"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> new OwnedSyncWatchdog.HttpsWebhookSender("https://token@example.test/hook"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("userinfo");
        assertThatThrownBy(() -> new OwnedSyncWatchdog.HttpsWebhookSender("https://example.test/hook?token=secret"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("query");

        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            URI local = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/hook");
            var sender = new OwnedSyncWatchdog.HttpsWebhookSender(local,
                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(), true);
            sender.send("{\"event\":\"test-only\"}");
            assertThat(body.get()).isEqualTo("{\"event\":\"test-only\"}");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void payloadBoundIsEnforcedBeforeRequest() {
        var sender = new OwnedSyncWatchdog.HttpsWebhookSender(
                URI.create("http://127.0.0.1:1/hook"), HttpClient.newHttpClient(), true);
        assertThatThrownBy(() -> sender.send("x".repeat(OwnedSyncWatchdog.MAX_WEBHOOK_BYTES + 1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bound");
    }
}
