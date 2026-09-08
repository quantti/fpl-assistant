package dev.quantti.fplassistant.myteam;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import dev.quantti.fplassistant.user.UserProfileService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The squad card, end to end, against a stub that answers the way FPL does.
 *
 * <p>The test database holds a populated <i>completed</i> season beside an empty current one —
 * the changeover, reproduced deliberately — so the enrichment gate is closed here and the card
 * is built from FPL's own payload. That is the honest case: joining this season's pick ids
 * against last season's rows would name different players.
 */
@SpringBootTest
@ActiveProfiles("test")
class MyTeamServiceTest {

    private static final UUID USER = UUID.fromString("6c1d2e3f-4444-4444-8444-555555555555");
    private static final int ENTRY = 111;
    private static final int OTHER_ENTRY = 333;
    private static final int UNKNOWN_ENTRY = 222;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UserProfileService profiles;

    @Autowired
    SquadEnrichment enrichment;

    @Value("${fpl.user.profile-table-name}")
    String profileTable;

    private HttpServer server;
    private final Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();
    /** The gameweek being played; the next deadline is always the one after it. */
    private int currentEvent = 1;
    private String chipsPlayed = "[]";
    private String activeChip = null;
    /** Body for the previous gameweek's picks; null makes the stub answer 404. */
    private String previousPicks = null;

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requests.computeIfAbsent(path, ignored -> new AtomicInteger()).incrementAndGet();
            if (path.startsWith("/entry/" + UNKNOWN_ENTRY)) {
                respond(exchange, 404, "{}");
            } else if (path.equals("/bootstrap-static/")) {
                respond(exchange, 200, bootstrap());
            } else if (path.endsWith("/history/")) {
                respond(exchange, 200, "{\"chips\": " + chipsPlayed + "}");
            } else if (currentEvent > 1 && path.contains("/event/" + (currentEvent - 1) + "/picks/")) {
                if (previousPicks == null) {
                    respond(exchange, 404, "{}");
                } else {
                    respond(exchange, 200, previousPicks);
                }
            } else if (path.contains("/event/" + currentEvent + "/picks/")) {
                respond(exchange, 200, picks());
            } else if (path.startsWith("/entry/" + OTHER_ENTRY)) {
                respond(exchange, 200, entryBody(OTHER_ENTRY, "Second Team"));
            } else {
                respond(exchange, 200, entryBody(ENTRY, "Rangers FC"));
            }
        });
        server.start();
    }

    @AfterEach
    void stopStubAndClearProfile() {
        server.stop(0);
        jdbc.update("DELETE FROM " + profileTable + " WHERE user_id = ?", USER);
    }

    @Test
    void anAccountWithNoTeamIdIsToldWhereToSetIt() {
        String card = service().render(service().myTeam(USER, budget()));

        assertThat(card).contains("No FPL team id is stored", "profile");
        assertThat(requests).as("nothing is fetched for an account with no team id").isEmpty();
    }

    @Test
    void aTeamIdFplDoesNotKnowIsReportedAsAWrongId() {
        storeTeamId(UNKNOWN_ENTRY);

        assertThat(service().render(service().myTeam(USER, budget())))
                .contains("does not know entry " + UNKNOWN_ENTRY, "check it");
    }

    /** "Could not ask" is not "your id is wrong": the difference decides what the user does next. */
    @Test
    void anUnreachableFplIsAServiceProblemRatherThanAWrongId() {
        storeTeamId(ENTRY);
        MyTeamService offline = service("http://127.0.0.1:1");

        String card = offline.render(offline.myTeam(USER, budget()));

        assertThat(card).contains("could not be reached", "says nothing about the stored team id");
    }

    @Test
    void theCardNamesTheGameweekItDescribesAndTheMoneyItFound() {
        storeTeamId(ENTRY);
        MyTeamService service = service();

        String card = service.render(service.myTeam(USER, budget()));

        assertThat(card)
                .contains("Squad as locked for Gameweek 1")
                .contains("Rangers FC (entry 111)")
                .contains("Bank GBP 1.2m", "squad value GBP 100.3m", "total GBP 101.5m")
                .contains("selling prices")
                .contains("Salah (LIV, MID) GBP 12.6m - CAPTAIN")
                .contains("Bench")
                .contains("Flagged: Salah (LIV) - doubtful")
                .doesNotContain("Continuing squad");
    }

    /**
     * A Free Hit squad plays one gameweek and reverts, and FPL serves it as "the" squad all
     * week — so the card must show the team that comes back, and say which is which.
     */
    @Test
    void aFreeHitCardShowsTheContinuingSquadBesideIt() {
        currentEvent = 4;
        activeChip = "freehit";
        previousPicks = PREVIOUS_PICKS;
        storeTeamId(ENTRY);
        MyTeamService service = service();

        String card = service.render(service.myTeam(USER, budget()));

        assertThat(card)
                .contains("FREE HIT squad for Gameweek 4")
                .contains("reverts to the continuing squad")
                .contains("Continuing squad (as locked at the Gameweek 3 deadline)")
                .contains("apply to THIS squad")
                .contains("Bank then was GBP 0.5m");
        assertThat(card).containsSubsequence(
                "FREE HIT squad", "Salah", "Continuing squad", "Wood (NFO, FWD)");
    }

    /** FPL down or GW-1 picks missing must not silently render the FH squad as the real team. */
    @Test
    void aFreeHitWhosePreviousPicksAre404GetsAWarningNotAContinuingBlock() {
        currentEvent = 4;
        activeChip = "freehit";
        previousPicks = null;   // the stub answers 404 for GW3
        storeTeamId(ENTRY);
        MyTeamService service = service();

        String card = service.render(service.myTeam(USER, budget()));

        assertThat(card)
                .contains("FREE HIT squad for Gameweek 4")
                .contains("returning squad could not be shown")
                .contains("no picks for Gameweek 3")
                .doesNotContain("Continuing squad (as locked");
    }

    /** There is no gameweek zero: a GW1 Free Hit must say so, not fetch. */
    @Test
    void aFreeHitInGameweekOneSaysThereIsNoEarlierSquad() {
        currentEvent = 1;
        activeChip = "freehit";
        storeTeamId(ENTRY);
        MyTeamService service = service();

        String card = service.render(service.myTeam(USER, budget()));

        assertThat(card)
                .contains("FREE HIT squad for Gameweek 1")
                .contains("no earlier squad");
        assertThat(requests.keySet())
                .as("no gameweek-zero fetch is attempted")
                .noneMatch(path -> path.contains("/event/0/picks/"));
    }

    /**
     * Chips answer for the <b>next deadline</b>, not for the gameweek being played. During GW1 a
     * manager can absolutely activate a wildcard — it is played on GW2, which is where FPL's own
     * window (2-19) starts. Answering "GW1: no wildcard" would be true about the wrong question.
     */
    @Test
    void chipsAreOfferedForTheDeadlineTheyWouldBePlayedOn() {
        storeTeamId(ENTRY);
        MyTeamService service = service();

        assertThat(service.render(service.myTeam(USER, budget())))
                .contains("Squad as locked for Gameweek 1")
                .contains("Playable at the next deadline (GW2): wildcard, bench boost,"
                        + " triple captain");
    }

    /** A chip spent inside its own window is gone until the next window opens. */
    @Test
    void aWildcardPlayedInThisHalfIsNotOfferedAgainUntilTheSecond() {
        currentEvent = 3;
        chipsPlayed = "[{\"name\": \"wildcard\", \"event\": 3}]";
        storeTeamId(ENTRY);
        MyTeamService service = service();

        assertThat(service.render(service.myTeam(USER, budget())))
                .contains("Playable at the next deadline (GW4): bench boost, triple captain")
                .contains("Opening in GW20: wildcard");
    }

    /** The changeover case: the database is a season behind, so only FPL's own numbers are used. */
    @Test
    void aSeasonTheDatabaseHasNotLoadedYetLeavesOutTheDeeperNumbers() {
        storeTeamId(ENTRY);
        MyTeamService service = service();

        assertThat(service.render(service.myTeam(USER, budget())))
                .contains("Fixture difficulty and expected goals are not available yet")
                .doesNotContain("next 5 FDR");
    }

    @Test
    void asecondQuestionInTheSameConversationRefetchesNothing() {
        storeTeamId(ENTRY);
        MyTeamService service = service();
        FplBudget budget = budget();

        service.myTeam(USER, budget);
        int afterFirst = budget.spent();
        service.myTeam(USER, budget);

        assertThat(requests.get("/entry/111/event/" + currentEvent + "/picks/").get()).isEqualTo(1);
        assertThat(budget.spent() - afterFirst)
                .as("only the chip history is re-read; everything else is cached")
                .isEqualTo(1);
    }

    /** The cache is keyed by entry, so editing the stored id cannot serve the old team. */
    @Test
    void changingTheStoredTeamIdChangesTheSquad() {
        storeTeamId(ENTRY);
        MyTeamService service = service();
        assertThat(service.render(service.myTeam(USER, budget()))).contains("Rangers FC");

        storeTeamId(OTHER_ENTRY);

        assertThat(service.render(service.myTeam(USER, budget()))).contains("Second Team");
    }

    /** Chips are the one part that may go missing without spoiling the card. */
    @Test
    void aSpentBudgetDropsTheChipsRatherThanTheSquad() {
        storeTeamId(ENTRY);
        MyTeamService service = service();

        String card = service.render(service.myTeam(USER, new FplBudget(3)));

        assertThat(card).contains("Squad as locked for Gameweek 1")
                .doesNotContain("Playable at the next deadline");
    }

    private void storeTeamId(int entryId) {
        profiles.setTeamId(USER, entryId);
    }

    private FplBudget budget() {
        return new FplBudget(15);
    }

    private MyTeamService service() {
        return service("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private MyTeamService service(String base) {
        FplEntryClient client = new FplEntryClient(RestClient.builder(),
                base + "/entry/%d/", base + "/entry/%d/event/%d/picks/",
                base + "/entry/%d/history/",
                base + "/leagues-classic/%d/standings/?page_standings=%d");
        FplBootstrap bootstrap = new FplBootstrap(RestClient.builder(),
                base + "/bootstrap-static/", 360);
        return new MyTeamService(profiles, client, bootstrap, enrichment, 5, 100);
    }

    private String entryBody(int id, String name) {
        return """
                {"id": %d, "name": "%s", "player_first_name": "A", "player_last_name": "B",
                 "summary_overall_rank": 420132, "summary_overall_points": 41,
                 "current_event": %d, "leagues": {"classic": []}}
                """.formatted(id, name, currentEvent);
    }

    private String picks() {
        return PICKS.formatted(activeChip == null ? "null" : "\"" + activeChip + "\"");
    }

    private static final String PICKS = """
            {"active_chip": %s,
             "entry_history": {"bank": 12, "value": 1003, "points": 55},
             "picks": [
               {"element": 302, "position": 1, "multiplier": 2, "is_captain": true,
                "is_vice_captain": false, "element_type": 3},
               {"element": 17, "position": 12, "multiplier": 0, "is_captain": false,
                "is_vice_captain": true, "element_type": 1}]}
            """;

    private static final String PREVIOUS_PICKS = """
            {"active_chip": null,
             "entry_history": {"bank": 5, "value": 995, "points": 48},
             "picks": [
               {"element": 44, "position": 1, "multiplier": 2, "is_captain": true,
                "is_vice_captain": false, "element_type": 4},
               {"element": 17, "position": 12, "multiplier": 0, "is_captain": false,
                "is_vice_captain": true, "element_type": 1}]}
            """;

    private String bootstrap() {
        return BOOTSTRAP.formatted(currentEvent, currentEvent + 1);
    }

    private static final String BOOTSTRAP = """
            {"teams": [{"id": 14, "short_name": "LIV"}, {"id": 4, "short_name": "NFO"}],
             "elements": [
               {"id": 302, "web_name": "Salah", "team": 14, "element_type": 3, "now_cost": 126,
                "form": "8.5", "total_points": 12, "points_per_game": "12.0",
                "selected_by_percent": "42.1", "status": "d", "news": "Knock",
                "chance_of_playing_next_round": 75},
               {"id": 17, "web_name": "Sels", "team": 4, "element_type": 1, "now_cost": 50,
                "form": "2.0", "total_points": 2, "points_per_game": "2.0",
                "selected_by_percent": "18.3", "status": "a", "news": ""},
               {"id": 44, "web_name": "Wood", "team": 4, "element_type": 4, "now_cost": 71,
                "form": "5.0", "total_points": 18, "points_per_game": "6.0",
                "selected_by_percent": "25.0", "status": "a", "news": ""}],
             "chips": [{"name": "wildcard", "start_event": 2, "stop_event": 19},
                       {"name": "bboost", "start_event": 1, "stop_event": 19},
                       {"name": "3xc", "start_event": 1, "stop_event": 19},
                       {"name": "wildcard", "start_event": 20, "stop_event": 38},
                       {"name": "bboost", "start_event": 20, "stop_event": 38},
                       {"name": "3xc", "start_event": 20, "stop_event": 38}],
             "events": [{"id": %d, "deadline_time": "2026-08-21T17:30:00Z", "is_current": true},
                        {"id": %d, "deadline_time": "2026-08-28T17:30:00Z", "is_next": true}]}
            """;

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().close();
    }
}
