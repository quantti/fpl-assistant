package dev.quantti.fplassistant.user;

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
    private static FplEntryLookup stubLookup;
    /** Port 1 refuses connections, which is what an unreachable FPL looks like from here. */
    private static final FplEntryLookup unreachableLookup =
            lookup("http://127.0.0.1:1/entry/%d/");

    @BeforeAll
    static void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // The stub encodes the outcome in the path: /entry/404/ answers 404, and so on.
        server.createContext("/entry/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            int status = path.contains("/404/") ? 404
                    : path.contains("/500/") ? 500
                    : path.contains("/301/") ? 301
                    : 200;
            byte[] body = status == 200 ? "{\"id\":1,\"name\":\"A Team\"}".getBytes() : new byte[0];
            if (status == 301) {
                exchange.getResponseHeaders().add("Location", "/entry/1/");
            }
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();
        stubLookup = lookup("http://127.0.0.1:" + server.getAddress().getPort() + "/entry/%d/");
    }

    @AfterAll
    static void stopStub() {
        server.stop(0);
    }

    @Test
    void anEntryFplKnowsExists() {
        assertThat(stubLookup.lookup(1234567)).isEqualTo(FplEntryLookup.Result.EXISTS);
    }

    @Test
    void anEntryFplDoesNotKnowIsNotFound() {
        assertThat(stubLookup.lookup(404)).isEqualTo(FplEntryLookup.Result.NOT_FOUND);
    }

    @Test
    void anErrorFromFplIsUnavailableRatherThanNotFound() {
        assertThat(stubLookup.lookup(500)).isEqualTo(FplEntryLookup.Result.UNAVAILABLE);
    }

    @Test
    void anUnreachableFplIsUnavailable() {
        // The id is perfectly valid; the answer must not blame it.
        assertThat(unreachableLookup.lookup(1234567))
                .isEqualTo(FplEntryLookup.Result.UNAVAILABLE);
    }

    /**
     * A redirect is not a confirmation. The JDK client does not follow them and Spring does not
     * treat them as errors, so a lookup written as "not 404, therefore it exists" would validate
     * every id the moment FPL puts a redirect in front of the API — or the moment the configured
     * URL loses its trailing slash.
     */
    @Test
    void aRedirectIsNotProofTheTeamExists() {
        assertThat(stubLookup.lookup(301)).isNotEqualTo(FplEntryLookup.Result.EXISTS);
    }

    private static FplEntryLookup lookup(String url) {
        return new FplEntryLookup(RestClient.builder(), url);
    }
}
