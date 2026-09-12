package fi.karivanttinen.fplassistant.myteam;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one payload that is certain to describe the same season as a manager's pick ids, held for
 * a few hours and shared by every conversation.
 *
 * <p>Two behaviours matter beyond parsing. A second reader inside the TTL must not cost a second
 * request — ten opponents only fit the per-turn budget because most reads are free — and a failed
 * refresh must keep serving the previous snapshot, because a squad card built from slightly old
 * ownership beats no squad card at all.
 */
class FplBootstrapTest {

    private static final String BODY = """
            {"teams": [{"id": 14, "short_name": "LIV"}],
             "elements": [{"id": 302, "web_name": "Salah", "team": 14, "element_type": 3,
                           "now_cost": 126, "form": "8.5", "total_points": 12,
                           "points_per_game": "12.0", "selected_by_percent": "42.1",
                           "status": "d", "news": "Knock", "chance_of_playing_next_round": 75}],
             "chips": [{"name": "wildcard", "start_event": 2, "stop_event": 19},
                       {"name": "bboost", "start_event": 1, "stop_event": 19}],
             "events": [{"id": 1, "deadline_time": "2026-08-21T17:30:00Z", "is_current": true,
                         "finished": true, "data_checked": false},
                        {"id": 2, "deadline_time": "2026-08-28T17:30:00Z", "is_next": true}]}
            """;

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicBoolean broken = new AtomicBoolean();

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/bootstrap-static/", exchange -> {
            requests.incrementAndGet();
            byte[] bytes = BODY.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            if (broken.get()) {
                exchange.sendResponseHeaders(500, 0);
                exchange.getResponseBody().close();
                return;
            }
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.getResponseBody().close();
        });
        server.start();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    @Test
    void aPlayerCarriesEverythingASquadCardPrints() {
        FplBootstrap.Snapshot snapshot = bootstrap(360).snapshot(new FplBudget(5)).orElseThrow();

        assertThat(snapshot.currentEvent()).isEqualTo(1);
        assertThat(snapshot.nextEvent())
                .as("the deadline a chip or a transfer made now would be played on")
                .isEqualTo(2);
        assertThat(snapshot.deadline(1)).isPresent();
        assertThat(snapshot.element(302)).hasValueSatisfying(element -> {
            assertThat(element.webName()).isEqualTo("Salah");
            assertThat(element.teamShort()).isEqualTo("LIV");
            assertThat(element.nowCost()).isEqualTo(126);
            assertThat(element.availability()).isEqualTo("doubtful (75% chance) - Knock");
        });
    }

    /**
     * The windows differ per chip. The wildcard's first one opens at GW2 because transfers are
     * unlimited until the GW1 deadline — not because a manager cannot wildcard during GW1, which
     * they can: it is played on GW2.
     */
    @Test
    void chipWindowsAreReadPerChipRatherThanAssumed() {
        FplBootstrap.Snapshot snapshot = bootstrap(360).snapshot(new FplBudget(5)).orElseThrow();

        assertThat(snapshot.chips()).extracting(FplBootstrap.ChipWindow::name)
                .containsExactly("wildcard", "bboost");
        assertThat(snapshot.chips().getFirst().covers(1)).isFalse();
        assertThat(snapshot.chips().getLast().covers(1)).isTrue();
    }

    @Test
    void asecondReadInsideTheTtlCostsNothing() {
        FplBootstrap bootstrap = bootstrap(360);
        FplBudget budget = new FplBudget(5);

        bootstrap.snapshot(budget);
        bootstrap.snapshot(budget);

        assertThat(requests.get()).isEqualTo(1);
        assertThat(budget.spent()).as("a cache hit spends no allowance").isEqualTo(1);
    }

    @Test
    void aFailedRefreshKeepsServingThePreviousSnapshot() {
        FplBootstrap bootstrap = bootstrap(0);
        assertThat(bootstrap.snapshot(new FplBudget(5))).isPresent();

        broken.set(true);

        assertThat(bootstrap.snapshot(new FplBudget(5)))
                .hasValueSatisfying(snapshot -> assertThat(snapshot.element(302)).isPresent());
        assertThat(requests.get()).isEqualTo(2);
    }

    @Test
    void eachEventCarriesFplsOwnFinishedAndCheckedFlags() {
        FplBootstrap.Snapshot snapshot = bootstrap(360).snapshot(new FplBudget(1)).orElseThrow();

        assertThat(snapshot.status(1)).contains(new FplBootstrap.EventStatus(true, false));
        assertThat(snapshot.status(2))
                .as("absent flags are false, not unknown - FPL omits nothing on a real event")
                .contains(new FplBootstrap.EventStatus(false, false));
        assertThat(snapshot.status(3)).as("an event the payload does not carry").isEmpty();
    }

    /** Out of allowance is not out of data: what is already cached is still served. */
    @Test
    void aSpentBudgetServesWhatIsCachedAndFetchesNothing() {
        FplBootstrap bootstrap = bootstrap(0);
        bootstrap.snapshot(new FplBudget(5));

        assertThat(bootstrap.snapshot(new FplBudget(0))).isPresent();
        assertThat(requests.get()).isEqualTo(1);
    }

    private FplBootstrap bootstrap(long ttlMinutes) {
        return new FplBootstrap(RestClient.builder(),
                "http://127.0.0.1:" + server.getAddress().getPort() + "/bootstrap-static/",
                ttlMinutes);
    }
}
