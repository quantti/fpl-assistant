package dev.quantti.fplassistant.myteam;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What FPL actually answers, against a stub that answers the same way.
 *
 * <p>The case that shapes the whole feature is {@link #aGameweekBeforeItsDeadlineIsAnEmptySquad}:
 * FPL serves picks only once a deadline has passed, so a 404 there is the normal state for most
 * of the week and must not be reported as a failure. The other one worth naming is that
 * {@code player_name} — a real person's legal name, on a manager who never used this product —
 * is not read out of the standings at all.
 */
class FplEntryClientTest {

    private static HttpServer server;
    private static FplEntryClient client;
    /** Port 1 refuses connections, which is what an unreachable FPL looks like from here. */
    private static FplEntryClient unreachable;

    @BeforeAll
    static void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/entry/1/", exchange -> {
            if (exchange.getRequestURI().getPath().endsWith("/history/")) {
                respond(exchange, 200, """
                        {"chips": [{"name": "wildcard", "event": 3}]}
                        """);
            } else if (exchange.getRequestURI().getPath().contains("/event/1/picks/")) {
                respond(exchange, 200, """
                        {"active_chip": "bboost",
                         "entry_history": {"bank": 12, "value": 1003, "points": 55},
                         "picks": [
                           {"element": 302, "position": 1, "multiplier": 2, "is_captain": true,
                            "is_vice_captain": false, "element_type": 3},
                           {"element": 17, "position": 12, "multiplier": 0, "is_captain": false,
                            "is_vice_captain": true, "element_type": 1}]}
                        """);
            } else if (exchange.getRequestURI().getPath().contains("/event/2/picks/")) {
                respond(exchange, 404, "{}");
            } else {
                respond(exchange, 200, """
                        {"id": 1, "name": "Rangers FC", "player_first_name": "Chris",
                         "player_last_name": "Musson", "summary_overall_rank": 420132,
                         "summary_overall_points": 41, "current_event": 1,
                         "leagues": {"classic": [
                           {"id": 12, "name": "Office League", "league_type": "x",
                            "entry_rank": 5, "entry_last_rank": 6, "rank_count": 20},
                           {"id": 314, "name": "Overall", "league_type": "s",
                            "entry_rank": 900000, "entry_last_rank": 0, "rank_count": 1089086}]}}
                        """);
            }
        });
        server.createContext("/entry/2/", exchange -> respond(exchange, 404, "{}"));
        server.createContext("/entry/3/", exchange -> respond(exchange, 500, "{}"));
        server.createContext("/leagues-classic/12/standings/", exchange -> respond(exchange, 200, """
                {"league": {"id": 12, "name": "Office League"},
                 "standings": {"has_next": true, "page": 1, "results": [
                   {"entry": 7654321, "entry_name": "League Leader", "player_name": "Jeremy Wang",
                    "rank": 1, "last_rank": 2, "total": 210, "event_total": 55}]}}
                """));
        server.start();
        client = client("http://127.0.0.1:" + server.getAddress().getPort());
        unreachable = client("http://127.0.0.1:1");
    }

    @AfterAll
    static void stopStub() {
        server.stop(0);
    }

    @Test
    void anEntryComesBackWithItsCurrentEventAndItsClassicLeagues() {
        assertThat(client.entry(1)).isInstanceOfSatisfying(
                FplEntryClient.Result.Ok.class, ok -> {
                    FplEntryClient.EntrySummary entry = (FplEntryClient.EntrySummary) ok.body();
                    assertThat(entry.teamName()).isEqualTo("Rangers FC");
                    assertThat(entry.managerName()).isEqualTo("Chris Musson");
                    assertThat(entry.currentEvent()).isEqualTo(1);
                    assertThat(entry.leagues()).hasSize(2);
                    assertThat(entry.leagues().getFirst().userCreated()).isTrue();
                    assertThat(entry.leagues().getLast().userCreated())
                            .as("FPL's own global leagues are not mini-leagues")
                            .isFalse();
                });
    }

    @Test
    void anEntryFplDoesNotKnowIsNotFound() {
        assertThat(client.entry(2)).isInstanceOf(FplEntryClient.Result.NotFound.class);
    }

    /** "Could not ask" must never read as "the answer is no" — the id may be perfectly good. */
    @Test
    void aServerErrorIsUnavailableRatherThanNotFound() {
        assertThat(client.entry(3)).isInstanceOf(FplEntryClient.Result.Unavailable.class);
        assertThat(unreachable.entry(1)).isInstanceOf(FplEntryClient.Result.Unavailable.class);
    }

    @Test
    void picksCarryTheBankTheValueTheCaptainAndTheBench() {
        assertThat(client.picks(1, 1)).isInstanceOfSatisfying(
                FplEntryClient.Result.Ok.class, ok -> {
                    @SuppressWarnings("unchecked")
                    FplEntryClient.LockedSquad squad =
                            ((Optional<FplEntryClient.LockedSquad>) ok.body()).orElseThrow();
                    assertThat(squad.bank()).isEqualTo(12);
                    assertThat(squad.value()).isEqualTo(1003);
                    assertThat(squad.activeChip()).isEqualTo("bboost");
                    assertThat(squad.picks()).hasSize(2);
                    assertThat(squad.picks().getFirst().captain()).isTrue();
                    assertThat(squad.picks().getFirst().starting()).isTrue();
                    assertThat(squad.picks().getLast().starting())
                            .as("position 12 is the bench")
                            .isFalse();
                });
    }

    /**
     * The normal mid-week state, not an error: FPL publishes a gameweek's picks only once its
     * deadline has passed, so asking for the next one answers 404 all week.
     */
    @Test
    void aGameweekBeforeItsDeadlineIsAnEmptySquad() {
        assertThat(client.picks(1, 2)).isInstanceOfSatisfying(
                FplEntryClient.Result.Ok.class,
                ok -> assertThat((Optional<?>) ok.body()).isEmpty());
    }

    @Test
    void chipsPlayedCarryTheGameweekTheyWerePlayedOn() {
        assertThat(client.chipsPlayed(1)).isInstanceOfSatisfying(
                FplEntryClient.Result.Ok.class,
                ok -> assertThat(ok.body())
                        .isEqualTo(List.of(new FplEntryClient.ChipPlayed("wildcard", 3))));
    }

    /** Ranks and team names travel; the manager's own name does not. */
    @Test
    void standingsCarryNoRealNames() {
        assertThat(client.standings(12, 1)).isInstanceOfSatisfying(
                FplEntryClient.Result.Ok.class, ok -> {
                    FplEntryClient.StandingsPage page = (FplEntryClient.StandingsPage) ok.body();
                    assertThat(page.leagueName()).isEqualTo("Office League");
                    assertThat(page.hasNext()).isTrue();
                    assertThat(page.rows()).singleElement().satisfies(row -> {
                        assertThat(row.entryName()).isEqualTo("League Leader");
                        assertThat(row.totalPoints()).isEqualTo(210);
                        assertThat(row.eventPoints()).isEqualTo(55);
                    });
                    assertThat(page.rows().toString()).doesNotContain("Jeremy Wang");
                });
    }

    private static FplEntryClient client(String base) {
        return new FplEntryClient(RestClient.builder(),
                base + "/entry/%d/",
                base + "/entry/%d/event/%d/picks/",
                base + "/entry/%d/history/",
                base + "/leagues-classic/%d/standings/?page_standings=%d");
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status,
                                String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().close();
    }
}
