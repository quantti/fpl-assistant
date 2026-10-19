package fi.karivanttinen.fplassistant.price;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PricePredictionHttpTest {
    private HttpServer server;
    private URI uri;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void returnsExactBytesAndStableFingerprint() throws Exception {
        byte[] bytes = PricePredictionFixtures.bytes(PricePredictionFixtures.bootstrap());
        server.createContext("/", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        try (var http = new PricePredictionHttp(Duration.ofSeconds(2))) {
            var response = http.get(uri);
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body()).isEqualTo(bytes);
            assertThat(PricePredictionWorker.fingerprint(response.body())).hasSize(64)
                    .isEqualTo(PricePredictionWorker.fingerprint(bytes));
        }
    }

    @Test
    void noRedirectsOrRetryAndRetryAfterPreserved() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            exchange.getResponseHeaders().add("Location", "/elsewhere");
            exchange.getResponseHeaders().add("Retry-After", "1200");
            exchange.sendResponseHeaders(429, -1);
            exchange.close();
        });
        server.createContext("/redirect", exchange -> {
            calls.incrementAndGet();
            exchange.getResponseHeaders().add("Location", "/elsewhere");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        try (var http = new PricePredictionHttp(Duration.ofSeconds(2))) {
            assertThat(http.get(uri).retryAfter()).isEqualTo("1200");
            assertThat(http.get(uri.resolve("redirect")).status()).isEqualTo(302);
            assertThat(calls).hasValue(2);
        }
    }

    @Test
    void declaredOversizeIsRejected() {
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, PricePredictionHttp.MAX_BYTES + 1L);
            exchange.close();
        });
        assertThatThrownBy(() -> {
            try (var http = new PricePredictionHttp(Duration.ofSeconds(1))) { http.get(uri); }
        }).isInstanceOf(java.io.IOException.class);
    }

    @Test
    void stalledBodyHasTotalDeadline() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, 100);
            exchange.getResponseBody().write(1);
            exchange.getResponseBody().flush();
            try { release.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        try (var http = new PricePredictionHttp(Duration.ofMillis(150))) {
            assertThatThrownBy(() -> http.get(uri)).isInstanceOf(java.io.IOException.class);
        } finally {
            release.countDown();
        }
    }
}
