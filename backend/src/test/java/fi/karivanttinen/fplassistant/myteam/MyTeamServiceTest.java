package fi.karivanttinen.fplassistant.myteam;

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

import fi.karivanttinen.fplassistant.testsupport.TapasSeed;
import fi.karivanttinen.fplassistant.tools.DataAge;
import fi.karivanttinen.fplassistant.user.UserProfileService;

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
    private boolean finished = false;
    private boolean dataChecked = false;
    private String chipsPlayed = "[]";
    private String activeChip = null;
    /** Body for the previous gameweek's picks; null makes the stub answer 404. */
    private String previousPicks = null;
    /** Appended to this gameweek's picks array; empty by default. */
    private String extraPick = "";

    /** Tapas club ids for the stub's LIV and NFO, which Tapas numbers independently of FPL. */
    private static final int CLUB_LIV = 9111;
    private static final int CLUB_NFO = 9112;

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
            } else if (path.matches("/event/\\d+/live/")) {
                respond(exchange, 200, LIVE);
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
        int season = TapasSeed.CURRENT_SEASON_ID;
        jdbc.update("DELETE FROM fixture WHERE season_id = ?", season);
        jdbc.update("DELETE FROM player WHERE season_id = ?", season);
        jdbc.update("DELETE FROM gameweek WHERE season_id = ?", season);
        jdbc.update("DELETE FROM team WHERE season_id = ?", season);
        jdbc.update("DELETE FROM player WHERE season_id = ? AND id = 302",
                TapasSeed.POPULATED_SEASON_ID);
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
     * At the Free Hit deadline FPL locks its picks for scoring and restores the previous squad
     * for transfers, but serves the scoring picks as "the" event squad all week — so the card
     * must show both and say which is which.
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
                .contains("These picks score in Gameweek 4 only and do not carry forward")
                .contains("At the Gameweek 4 deadline, FPL restored the continuing squad")
                .contains("Continuing squad (restored for transfers at the Gameweek 4 deadline"
                        + " from the Gameweek 3 picks)")
                .contains("apply to THIS squad")
                .contains("Restored bank was GBP 0.5m")
                .contains("These were the FREE HIT squad's funds and did not carry forward")
                .contains("the continuing squad's bank is shown with that block below");
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
                .contains("picks above do not carry forward")
                .contains("squad used for transfers and future Gameweeks could not be shown")
                .contains("no picks for Gameweek 3")
                .doesNotContain("Continuing squad (restored for transfers")
                .as("the card must not promise a block it does not deliver")
                .doesNotContain("FPL restored the continuing squad");
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
     * A starved budget must refuse the previous-gameweek fetch honestly - not fall back to
     * treating the Free Hit fifteen as the continuing squad. Entry, this gameweek's picks and
     * bootstrap consume the three units before the GW3 fetch is attempted, so its refusal is the
     * budget, not a 404 - {@code previousPicks} is left set to a body that would succeed if it
     * were ever requested.
     */
    @Test
    void aFreeHitWhoseReturningSquadFetchIsRefusedByBudgetGetsAWarning() {
        currentEvent = 4;
        activeChip = "freehit";
        previousPicks = PREVIOUS_PICKS;
        storeTeamId(ENTRY);
        MyTeamService service = service();

        String card = service.render(service.myTeam(USER, new FplBudget(3)));

        assertThat(card)
                .contains("FREE HIT squad for Gameweek 4")
                .contains("squad used for transfers and future Gameweeks could not be shown")
                .contains("could not be reached")
                .doesNotContain("Continuing squad (restored for transfers");
        assertThat(requests.keySet())
                .as("the budget, not the network, is what stops the GW3 fetch")
                .noneMatch(path -> path.contains("/event/3/picks/"));
    }

    /** A wildcard squad persists past its gameweek, so it needs none of the Free Hit handling. */
    @Test
    void aWildcardSquadGetsNoFreeHitHandling() {
        currentEvent = 4;
        activeChip = "wildcard";
        storeTeamId(ENTRY);
        MyTeamService service = service();

        String card = service.render(service.myTeam(USER, budget()));

        assertThat(card)
                .doesNotContain("FREE HIT")
                .doesNotContain("Continuing squad");
        assertThat(requests.keySet())
                .as("a wildcard squad persists, so the previous gameweek is never fetched")
                .noneMatch(path -> path.contains("/event/3/picks/"));
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

    /**
     * The changeover case: the database is a season behind, so only FPL's own numbers are used.
     * Last season's row that happens to carry the same element id must lend neither its
     * numbers nor its write time to this season's pick.
     */
    @Test
    void aSeasonTheDatabaseHasNotLoadedYetLeavesOutTheDeeperNumbers() {
        TapasSeed.ensure(jdbc);
        jdbc.update("""
                INSERT INTO player (id, season_id, team_id, first_name, second_name, web_name,
                                    element_type, now_cost, status, total_points,
                                    expected_goals, expected_assists, updated_at)
                VALUES (302, ?, ?, 'Old', 'Namesake', 'Namesake', 3, 80, 'a', 90, 9.99, 8.88,
                        now() - interval '400 days')
                """, TapasSeed.POPULATED_SEASON_ID, TapasSeed.TEAM_ASHFIELD);
        storeTeamId(ENTRY);
        MyTeamService service = service();

        assertThat(service.render(service.myTeam(USER, budget())))
                .contains("Fixture difficulty and expected goals are not available yet")
                .doesNotContain("next 5 FDR")
                .doesNotContain("9.99", "8.88", "written", "Stored-data age");
    }

    /**
     * KAR-9: the squad is fetched from FPL just now, but its xG/xA and FDR are stored rows with
     * their own ages. Each displayed value is tagged with the rows behind it — per player, per
     * club — and the gameweek row that chose the FDR run is dated once with the provenance.
     */
    @Test
    void aFreshFplFetchDoesNotMakeStoredExpectedGoalsOrFdrLookFresh() {
        fillCurrentSeason();
        extraPick = MISSING_FROM_BOOTSTRAP_PICK;
        storeTeamId(ENTRY);
        MyTeamService service = service();

        String card = service.render(service.myTeam(USER, budget()));

        assertThat(card)
                .contains("Salah (LIV, MID) GBP 12.6m - CAPTAIN - GW1: 8 pts, 90 min, counts x2 -"
                        + " form 8.5, 12 pts, 12.0/game,"
                        + " 42.1% owned, next 5 FDR 3.0 (fixture records written 12 days ago),"
                        + " xG 3.40, xA 1.20 (player record written 9 days ago) - doubtful")
                .as("another club's run, and a null player write that keeps its xG/xA")
                .contains("Sels (NFO, GK) GBP 5.0m - vice - GW1: 0 pts, 0 min - form 2.0, 2 pts,"
                        + " 2.0/game,"
                        + " 18.3% owned, next 5 FDR 2.0 (fixture records written 20 days ago to"
                        + " 12 days ago), xG 0.00, xA 0.10 (player record: write time unavailable)")
                .as("a pick FPL's player list lacks shows no stored values, so no age either")
                .contains("element 999 (DEF) - not in the current player list")
                .doesNotContain("7.77")
                .contains("xG/xA and next-5 FDR are stored database values")
                .contains("Stored-data age: starting-gameweek selection for the FDR run"
                        + " (gameweek row id=1), season " + TapasSeed.CURRENT_SEASON_CODE
                        + ", last recorded write ")
                .contains("(30 days ago). This is a database record time")
                .contains(DataAge.GAMEWEEK_CAVEAT)
                .containsOnlyOnce("Stored-data age:");
        assertThat(card).containsSubsequence("fetched ", "Stored-data age:", "Starting XI", "Salah");
    }

    /** Both Free Hit squads map each element to its own stored row; the selection is dated once. */
    @Test
    void aFreeHitsTwoSquadsEachCarryTheirOwnPlayersAges() {
        fillCurrentSeason();
        currentEvent = 4;
        activeChip = "freehit";
        previousPicks = PREVIOUS_PICKS;
        storeTeamId(ENTRY);
        MyTeamService service = service();

        String card = service.render(service.myTeam(USER, budget()));

        assertThat(card)
                .contains("Wood (NFO, FWD) GBP 7.1m - CAPTAIN - form 5.0, 18 pts, 6.0/game,"
                        + " 25.0% owned, next 5 FDR 2.0 (fixture records written 20 days ago to"
                        + " 12 days ago), xG 2.10, xA 0.40 (player record written 3 days ago)")
                .contains("Prices, form, points and ownership on the lines below are today's FPL"
                        + " player data")
                .contains("any xG/xA and FDR are stored database values")
                .doesNotContain("statistics on the lines below are today's")
                .containsOnlyOnce("Stored-data age:");
        assertThat(card).containsSubsequence("FREE HIT squad", "Salah",
                "(player record written 9 days ago)", "Continuing squad", "Wood (NFO, FWD)",
                "(player record written 3 days ago)", "Sels", "write time unavailable");
    }

    /** The current season, loaded, with element ids matching the stub's picks. */
    private void fillCurrentSeason() {
        TapasSeed.ensure(jdbc);
        int season = TapasSeed.CURRENT_SEASON_ID;
        jdbc.update("""
                INSERT INTO team (id, season_id, code, name, short_name, strength) VALUES
                    (?, ?, 9111, 'Liverpool', 'LIV', 5),
                    (?, ?, 9112, 'Nottingham Forest', 'NFO', 3)
                """, CLUB_LIV, season, CLUB_NFO, season);
        jdbc.update("""
                INSERT INTO gameweek (id, season_id, name, deadline_time, finished, data_checked,
                                      is_current, is_next, updated_at)
                VALUES (1, ?, 'Gameweek 1', TIMESTAMPTZ '2026-08-21 17:30:00+00', true, true,
                        true, false, now() - interval '30 days 1 hour')
                """, season);
        jdbc.update("""
                INSERT INTO player (id, season_id, team_id, first_name, second_name, web_name,
                                    element_type, now_cost, status, total_points,
                                    expected_goals, expected_assists, updated_at) VALUES
                    (302, ?, ?, 'M', 'Salah', 'Salah', 3, 126, 'a', 12, 3.40, 1.20,
                        now() - interval '9 days 1 hour'),
                    (17,  ?, ?, 'M', 'Sels',  'Sels',  1, 50,  'a', 2,  0.00, 0.10, NULL),
                    (44,  ?, ?, 'C', 'Wood',  'Wood',  4, 71,  'a', 18, 2.10, 0.40,
                        now() - interval '3 days 1 hour'),
                    (999, ?, ?, 'N', 'Ew',    'Ew',    2, 45,  'a', 0,  7.77, 0.00, now())
                """, season, CLUB_LIV, season, CLUB_NFO, season, CLUB_NFO, season, CLUB_NFO);
        // LIV rated 3 in three fixtures; the fourth is unrated for LIV, so only NFO admits it.
        jdbc.update("""
                INSERT INTO fixture (id, season_id, code, gameweek, team_h, team_a,
                                     team_h_difficulty, team_a_difficulty, updated_at) VALUES
                    (9911, ?, 9911, 1, ?, ?, 3, 2, now() - interval '12 days 1 hour'),
                    (9912, ?, 9912, 2, ?, ?, 3, 2, now() - interval '12 days 1 hour'),
                    (9913, ?, 9913, 3, ?, ?, 3, 2, now() - interval '12 days 1 hour'),
                    (9914, ?, 9914, 4, ?, ?, NULL, 2, now() - interval '20 days 1 hour')
                """, season, CLUB_LIV, CLUB_NFO, season, CLUB_LIV, CLUB_NFO,
                season, CLUB_LIV, CLUB_NFO, season, CLUB_LIV, CLUB_NFO);
    }

    @Test
    void theCardShowsEachPicksGameweekPointsAndFplsOwnEntryTotal() {
        storeTeamId(ENTRY);
        MyTeamService service = service();

        String card = service.render(service.myTeam(USER, budget()));

        assertThat(card)
                .contains("Gameweek 1 points: 55 (FPL's own total for this entry, before transfer costs)")
                .contains("Gameweek 1 is not finished")
                .contains("Player gameweek points read ")
                .contains("Salah (LIV, MID) GBP 12.6m - CAPTAIN - GW1: 8 pts, 90 min, counts x2")
                .contains("Sels (NFO, GK) GBP 5.0m - vice - GW1: 0 pts, 0 min");
        assertThat(requests.get("/event/1/live/").get()).isEqualTo(1);
    }

    @Test
    void aFinishedGameweekThatFplHasNotCheckedSaysBonusMayStillChange() {
        finished = true;
        storeTeamId(ENTRY);
        MyTeamService service = service();

        String card = service.render(service.myTeam(USER, budget()));

        assertThat(card).contains("Gameweek 1 has finished but FPL has not confirmed it");
    }

    @Test
    void aCheckedGameweekIsFinal() {
        finished = true;
        dataChecked = true;
        storeTeamId(ENTRY);
        MyTeamService service = service();

        String card = service.render(service.myTeam(USER, budget()));

        assertThat(card).contains("Gameweek 1 is final").doesNotContain("provisional");
    }

    /** Entry, picks, bootstrap and chips spend four units; the live feed is asked last and refused. */
    @Test
    void aSpentBudgetDropsPlayerGameweekPointsButKeepsTheEntryTotal() {
        storeTeamId(ENTRY);
        MyTeamService service = service();

        String card = service.render(service.myTeam(USER, new FplBudget(4)));

        assertThat(card)
                .contains("Gameweek 1 points: 55")
                .contains("Per-player gameweek points could not be read from FPL just now")
                .doesNotContain("GW1: 8 pts");
        assertThat(requests).doesNotContainKey("/event/1/live/");
    }

    @Test
    void theContinuingSquadCarriesNoGameweekPoints() {
        currentEvent = 4;
        activeChip = "freehit";
        previousPicks = PREVIOUS_PICKS;
        storeTeamId(ENTRY);
        MyTeamService service = service();

        String card = service.render(service.myTeam(USER, budget()));

        String continuing = card.substring(card.indexOf("Continuing squad (restored"));
        assertThat(card.substring(0, card.indexOf("Continuing squad (restored")))
                .contains("Salah (LIV, MID) GBP 12.6m - CAPTAIN - GW4: 8 pts");
        assertThat(continuing).doesNotContain("GW4:").doesNotContain("GW3:");
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
        assertThat(requests.get("/event/" + currentEvent + "/live/").get()).isEqualTo(1);
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
        FplLivePoints live = new FplLivePoints(RestClient.builder(),
                base + "/event/%d/live/", 5);
        return new MyTeamService(profiles, client, bootstrap, enrichment, live, 5, 100);
    }

    private String entryBody(int id, String name) {
        return """
                {"id": %d, "name": "%s", "player_first_name": "A", "player_last_name": "B",
                 "summary_overall_rank": 420132, "summary_overall_points": 41,
                 "current_event": %d, "leagues": {"classic": []}}
                """.formatted(id, name, currentEvent);
    }

    private String picks() {
        return PICKS.formatted(activeChip == null ? "null" : "\"" + activeChip + "\"", extraPick);
    }

    private static final String PICKS = """
            {"active_chip": %s,
             "entry_history": {"bank": 12, "value": 1003, "points": 55},
             "picks": [
               {"element": 302, "position": 1, "multiplier": 2, "is_captain": true,
                "is_vice_captain": false, "element_type": 3},
               {"element": 17, "position": 12, "multiplier": 0, "is_captain": false,
                "is_vice_captain": true, "element_type": 1}%s]}
            """;

    /** A pick the bootstrap's player list does not know, though the database does. */
    private static final String MISSING_FROM_BOOTSTRAP_PICK = """
            , {"element": 999, "position": 13, "multiplier": 0, "is_captain": false,
               "is_vice_captain": false, "element_type": 2}""";

    private static final String LIVE = """
            {"elements": [
              {"id": 302, "stats": {"minutes": 90, "total_points": 8}, "explain": []},
              {"id": 17, "stats": {"minutes": 0, "total_points": 0}, "explain": []},
              {"id": 44, "stats": {"minutes": 65, "total_points": 2}, "explain": []}]}
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
        return BOOTSTRAP.formatted(currentEvent, finished, dataChecked, currentEvent + 1);
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
             "events": [{"id": %d, "deadline_time": "2026-08-21T17:30:00Z", "is_current": true,
                         "finished": %s, "data_checked": %s},
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
