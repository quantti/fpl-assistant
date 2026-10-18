package fi.karivanttinen.fplassistant.teamxg;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UnderstatClientTest {
    @Test
    void fetchesOneLeaguePayloadAndDecodesGzip() throws Exception {
        var out = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(out)) { gzip.write("{\"dates\":[]}".getBytes(StandardCharsets.UTF_8)); }
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/getLeagueData/EPL/2026", exchange -> {
            requests.incrementAndGet();
            assertThat(exchange.getRequestHeaders().getFirst("X-Requested-With")).isEqualTo("XMLHttpRequest");
            exchange.getResponseHeaders().add("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(200, out.size());
            try (var body = exchange.getResponseBody()) { body.write(out.toByteArray()); }
        });
        server.start();
        try {
            assertThat(new UnderstatClient("http://127.0.0.1:" + server.getAddress().getPort())
                    .fetch(2026)).isEqualTo("{\"dates\":[]}");
            assertThat(requests).hasValue(1);
        } finally { server.stop(0); }
    }

    @Test
    void boundsDecompressedPayloadSize() throws Exception {
        var out = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(out)) { gzip.write(" ".repeat(5_000_001).getBytes(StandardCharsets.UTF_8)); }
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, out.size());
            try (var body = exchange.getResponseBody()) { body.write(out.toByteArray()); }
        });
        server.start();
        try {
            var client = new UnderstatClient("http://127.0.0.1:" + server.getAddress().getPort());
            assertThatThrownBy(() -> client.fetch(2026)).isInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("Understat payload exceeds 5 MB");
        } finally { server.stop(0); }
    }

    @Test
    void rejectsHttpFailureInsteadOfTreatingItAsAnEmptySeason() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> { exchange.sendResponseHeaders(503, -1); exchange.close(); });
        server.start();
        try {
            var client = new UnderstatClient("http://127.0.0.1:" + server.getAddress().getPort());
            assertThatThrownBy(() -> client.fetch(2026)).isInstanceOf(IllegalStateException.class);
        } finally { server.stop(0); }
    }
}
