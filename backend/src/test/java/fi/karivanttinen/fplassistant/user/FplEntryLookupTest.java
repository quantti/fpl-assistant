package fi.karivanttinen.fplassistant.user;

import java.io.IOException;
import java.net.InetSocketAddress;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three outcomes, against a stub that answers the way FPL does. The distinction that matters
 * is the last two: "no such team" and "could not ask" must never collapse into one answer.
 */
class FplEntryLookupTest {

    private static HttpServer server;
    private static String baseUrl;

    @BeforeAll
    static void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // The stub encodes the outcome in the path: /entry/404/ answers 404, and so on.
        server.createContext("/entry/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            int status = path.contains("/404/") ? 404 : path.contains("/500/") ? 500 : 200;
            byte[] body = status == 200 ? "{\"id\":1,\"name\":\"A Team\"}".getBytes() : new byte[0];
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/entry/%d/";
    }

    @AfterAll
    static void stopStub() {
        server.stop(0);
    }

    @Test
    void anEntryFplKnowsExists() {
        assertThat(lookup(baseUrl).lookup(1234567)).isEqualTo(FplEntryLookup.Result.EXISTS);
    }

    @Test
    void anEntryFplDoesNotKnowIsNotFound() {
        assertThat(lookup(baseUrl).lookup(404)).isEqualTo(FplEntryLookup.Result.NOT_FOUND);
    }

    @Test
    void anErrorFromFplIsUnavailableRatherThanNotFound() {
        assertThat(lookup(baseUrl).lookup(500)).isEqualTo(FplEntryLookup.Result.UNAVAILABLE);
    }

    @Test
    void anUnreachableFplIsUnavailable() {
        // Port 1 on localhost refuses connections, which is what a network failure looks like
        // from here. The id is perfectly valid; the answer must not blame it.
        assertThat(lookup("http://127.0.0.1:1/entry/%d/").lookup(1234567))
                .isEqualTo(FplEntryLookup.Result.UNAVAILABLE);
    }

    private static FplEntryLookup lookup(String url) {
        return new FplEntryLookup(RestClient.builder(), url);
    }
}
