package dev.quantti.fplassistant.myteam;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One call per gameweek, shared by everyone asking during the TTL. A failure answers empty and
 * caches nothing, so the next reader tries again; a spent budget answers empty without a request.
 */
class FplLivePointsTest {

    private static final String BODY = """
            {"elements": [
              {"id": 302, "stats": {"minutes": 90, "total_points": 8, "bonus": 3}, "explain": []},
              {"id": 17, "stats": {"minutes": 0, "total_points": 0}, "explain": []}]}
            """;

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicBoolean broken = new AtomicBoolean();
    private FplLivePoints live;

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/event/5/live/", exchange -> {
            requests.incrementAndGet();
            respond(exchange, broken.get() ? 500 : 200, BODY);
        });
        server.createContext("/event/6/live/", exchange -> respond(exchange, 404, "{}"));
        server.start();
        live = new FplLivePoints(RestClient.builder(),
                "http://127.0.0.1:" + server.getAddress().getPort() + "/event/%d/live/", 5);
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    @Test
    void pointsAndMinutesAreReadPerElement() {
        FplLivePoints.Live points = live.forEvent(5, new FplBudget(1)).orElseThrow();

        assertThat(points.event()).isEqualTo(5);
        assertThat(points.of(302)).contains(new FplLivePoints.ElementPoints(8, 90));
        assertThat(points.of(17)).contains(new FplLivePoints.ElementPoints(0, 0));
        assertThat(points.of(999)).isEmpty();
        assertThat(points.fetchedAt()).isNotNull();
    }

    @Test
    void aSecondReaderInsideTheTtlCostsNoRequestAndNoBudget() {
        live.forEvent(5, new FplBudget(1));
        FplBudget second = new FplBudget(1);

        assertThat(live.forEvent(5, second)).isPresent();
        assertThat(requests.get()).isEqualTo(1);
        assertThat(second.spent()).isZero();
    }

    @Test
    void aSpentBudgetAnswersEmptyWithoutAsking() {
        FplBudget spent = new FplBudget(0);

        assertThat(live.forEvent(5, spent)).isEmpty();
        assertThat(requests.get()).isZero();
    }

    @Test
    void aGameweekFplHasNotOpenedYetIsEmpty() {
        assertThat(live.forEvent(6, new FplBudget(1))).isEmpty();
    }

    @Test
    void aFailedCallIsEmptyAndNotCached() {
        broken.set(true);
        assertThat(live.forEvent(5, new FplBudget(1))).isEmpty();

        broken.set(false);
        assertThat(live.forEvent(5, new FplBudget(1))).isPresent();
        assertThat(requests.get()).as("the failure was not cached").isEqualTo(2);
    }

    @Test
    void anUnreachableFplIsEmpty() {
        FplLivePoints unreachable = new FplLivePoints(RestClient.builder(),
                "http://127.0.0.1:1/event/%d/live/", 5);

        assertThat(unreachable.forEvent(5, new FplBudget(1))).isEmpty();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().close();
    }
}
