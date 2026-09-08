package dev.quantti.fplassistant.myteam;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import dev.quantti.fplassistant.user.UserProfileService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mini-league view: which leagues count, who is fetched, and what is said about them.
 *
 * <p>Three of these are about restraint rather than output. A league the user is not in is
 * refused before anything is fetched; a request for fifty teams is clamped to ten and says so;
 * and one turn's worth of questions stays inside the fifteen-call budget, which only adds up
 * because the entry, the squad and the standings are cached.
 */
@SpringBootTest
@ActiveProfiles("test")
class MiniLeagueServiceTest {

    private static final UUID USER = UUID.fromString("7d2e3f40-5555-4555-8555-666666666666");
    private static final int ENTRY = 111;
    private static final int MINI_LEAGUE = 12;
    private static final int GLOBAL_LEAGUE = 314;
    /** Rank 105 puts the user on page 3 of fifty-row pages. */
    private static final int USER_RANK = 105;

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
    private boolean leaguesAreGlobalOnly;
    private int extraLeagues;
    private int currentEvent = 1;
    private final Map<Integer, String> activeChips = new ConcurrentHashMap<>();
    private final Map<Integer, String> previousPicks = new ConcurrentHashMap<>();
    private int missingPreviousStatus = 404;
    private int bootstrapStatus = 200;

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requests.computeIfAbsent(path, ignored -> new AtomicInteger()).incrementAndGet();
            if (path.equals("/bootstrap-static/")) {
                respond(exchange, bootstrapStatus, bootstrapStatus == 200 ? bootstrap() : "{}");
            } else if (path.startsWith("/leagues-classic/")) {
                respond(exchange, 200, standings(page(exchange)));
            } else if (path.endsWith("/history/")) {
                respond(exchange, 200, "{\"chips\": []}");
            } else if (path.contains("/event/")) {
                int entryId = entryIdIn(path);
                int event = Integer.parseInt(path.split("/")[4]);
                if (event == currentEvent) {
                    respond(exchange, 200, picks(entryId));
                } else if (event == currentEvent - 1 && previousPicks.containsKey(entryId)) {
                    respond(exchange, 200, previousPicks.get(entryId));
                } else {
                    respond(exchange, missingPreviousStatus, "{}");
                }
            } else {
                respond(exchange, 200, entryBody(entryIdIn(path)));
            }
        });
        server.start();
        profiles.setTeamId(USER, ENTRY);
    }

    @AfterEach
    void stopStubAndClearProfile() {
        server.stop(0);
        jdbc.update("DELETE FROM " + profileTable + " WHERE user_id = ?", USER);
    }

    @Test
    void onlyLeaguesSomebodyCreatedAreListed() {
        String answer = miniLeagues().myLeagues(USER, budget());

        assertThat(answer).contains("Office League", "id 12", "105th of 400");
        assertThat(answer).as("FPL's global leagues are everybody's, and are not advice")
                .doesNotContain("Overall");
    }

    /**
     * A live account came back with sixteen mini-leagues, and a heavy player has more; the whole
     * list would re-enter the prompt on every later turn. The small ones are the ones a user can
     * move in, so they are the ones that survive the cut.
     */
    @Test
    void aLongListIsCutToTheSmallestLeaguesWithTheRestCountedOff() {
        extraLeagues = 15;

        String answer = miniLeagues().myLeagues(USER, budget());

        assertThat(answer.lines().filter(line -> line.startsWith("- ")).count())
                .isEqualTo(MiniLeagueService.MAX_LEAGUES_LISTED);
        assertThat(answer)
                .contains("smallest first")
                .contains("...and 4 larger mini-leagues not listed")
                .as("the eight-entry league is the actionable one")
                .contains("Office League")
                .doesNotContain("Big League 15");
    }

    @Test
    void anAccountInNoMiniLeagueIsToldSoRatherThanShownGlobalOnes() {
        leaguesAreGlobalOnly = true;

        assertThat(miniLeagues().myLeagues(USER, budget()))
                .contains("not in any mini-league that somebody created");
    }

    /** Membership is checked against the user's own entry, before anything is fetched for it. */
    @Test
    void aLeagueTheUserIsNotInIsRefusedWithoutFetchingIt() {
        assertThat(miniLeagues().opponents(USER, 999, "top10", budget()))
                .contains("not in league 999", "getMyMiniLeagues");
        assertThat(requests.keySet()).noneMatch(path -> path.contains("leagues-classic"));
    }

    @Test
    void aGlobalLeagueIsRefusedAsNotAMiniLeague() {
        assertThat(miniLeagues().opponents(USER, GLOBAL_LEAGUE, "nearby", budget()))
                .contains("global leagues", "not a mini-league");
    }

    @Test
    void nearbyFetchesThePageTheUserIsActuallyOnAndReportsTheDifference() {
        String answer = miniLeagues().opponents(USER, MINI_LEAGUE, null, budget());

        assertThat(requests).containsKey("/leagues-classic/12/standings/");
        assertThat(answer)
                .contains("Office League (id 12)", "the user is 105th of 400")
                .contains("Standings and squads as at Gameweek 1")
                // Five either side of rank 105, minus whatever the page boundary cuts off:
                // the page holds 101-150, so four above and five below.
                .contains("101st", "110th")
                .as("the user's own row is not one of their opponents")
                .doesNotContain(" 105th  Team");
        assertThat(answer).contains("owns that the user does not: Haaland");
    }

    /** The manager's legal name is published by FPL and is not ours to forward to a model. */
    @Test
    void noRealNamesReachTheAnswer() {
        assertThat(miniLeagues().opponents(USER, MINI_LEAGUE, "top10", budget()))
                .doesNotContain("Jeremy Wang");
    }

    @Test
    void aRequestForFiftyTeamsIsClampedToTenAndSaysSo() {
        String answer = miniLeagues().opponents(USER, MINI_LEAGUE, "top50", budget());

        assertThat(answer).contains("Asked for 50 teams; 10 is the maximum");
        assertThat(countOpponentLines(answer)).isEqualTo(10);
    }

    @Test
    void oneNamedEntryComesBackOnItsOwn() {
        assertThat(miniLeagues().opponents(USER, MINI_LEAGUE, "entry:2001", budget()))
                .contains("Team 2001", "entry 2001", "owns that the user does not");
    }

    /** Both comparisons must use like-for-like squads, whichever side played the Free Hit. */
    @ParameterizedTest
    @CsvSource({
            "top1, 2001, false, true, Wood",
            "nearby, 2101, false, true, Wood",
            "entry:2001, 2001, false, true, Wood",
            "top1, 2001, true, false, Salah",
            "nearby, 2101, true, false, Salah",
            "entry:2001, 2001, true, false, Salah",
            "top1, 2001, true, true, Sels",
            "nearby, 2101, true, true, Sels",
            "entry:2001, 2001, true, true, Sels"
    })
    void freeHitComparisonsSeparateScoringPicksFromContinuingOwnership(
            String focus, int opponent, boolean userFreeHit, boolean opponentFreeHit,
            String continuingDifference) {
        currentEvent = 4;
        if (userFreeHit) {
            activeChips.put(ENTRY, "freehit");
            previousPicks.put(ENTRY, picks(null, 355, 44));
        }
        if (opponentFreeHit) {
            activeChips.put(opponent, "freehit");
            previousPicks.put(opponent, picks(null, 17, 44));
        }

        String answer = miniLeagues().opponents(USER, MINI_LEAGUE, focus, budget());

        assertThat(answer).contains("Standings and squads as at Gameweek 4", "fetched ",
                "transfers since that deadline are not visible");
        assertThat(answer).containsSubsequence("Team " + opponent,
                "Gameweek 4 locked picks only - owns that the user does not: Haaland",
                "Continuing squads - owns that the user does not: " + continuingDifference + "\n");
        assertThat(answer).contains("restored for transfers at the Gameweek 4 deadline",
                "from Gameweek 3 picks");
        if (userFreeHit) {
            assertThat(answer).contains("User: FREE HIT in Gameweek 4");
        }
        if (opponentFreeHit) {
            assertThat(answer).contains("Opponent: FREE HIT in Gameweek 4");
        }
    }

    /** Missing, failed and empty previous picks are unknown ownership, never an empty team. */
    @ParameterizedTest
    @CsvSource({"true, 404", "false, 404", "true, 503", "false, 503", "true, 200", "false, 200"})
    void unreadableContinuingSquadsDoNotFallBackToFreeHitPicks(boolean userFreeHit, int status) {
        currentEvent = 4;
        missingPreviousStatus = status;
        activeChips.put(userFreeHit ? ENTRY : 2001, "freehit");

        String answer = miniLeagues().opponents(USER, MINI_LEAGUE, "top1", budget());

        assertThat(answer).contains((userFreeHit ? "User" : "Opponent") + ": FREE HIT in Gameweek 4",
                "Gameweek 3 picks could not be read", "Continuing-squad comparison unavailable",
                "keep forward-looking advice conditional",
                "Gameweek 4 locked picks only - owns that the user does not: Haaland")
                .doesNotContain("Continuing squads - owns", "the same fifteen players");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aGameweekOneFreeHitNeverFetchesGameweekZero(boolean userFreeHit) {
        activeChips.put(userFreeHit ? ENTRY : 2001, "freehit");

        String answer = miniLeagues().opponents(USER, MINI_LEAGUE, "entry:2001", budget());

        assertThat(answer).contains("FREE HIT in Gameweek 1", "no earlier squad",
                "Continuing-squad comparison unavailable");
        assertThat(requests.keySet()).noneMatch(path -> path.contains("/event/0/"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aSpentBudgetCannotTurnFreeHitPicksIntoContinuingOwnership(boolean userFreeHit) {
        currentEvent = 4;
        int entry = userFreeHit ? ENTRY : 2001;
        activeChips.put(entry, "freehit");
        previousPicks.put(entry, picks(null, 17, 44));
        MyTeamService myTeam = myTeamService();
        myTeam.bootstrap(new FplBudget(1));
        FplBudget budget = new FplBudget(userFreeHit ? 2 : 4);

        String answer = miniLeagues(myTeam).opponents(USER, MINI_LEAGUE, "top1", budget);

        assertThat(answer).contains("FREE HIT in Gameweek 4", "continuing squad unavailable",
                "keep forward-looking advice conditional")
                .doesNotContain("Continuing squads - owns");
        assertThat(budget.spent()).isEqualTo(userFreeHit ? 2 : 4);
        assertThat(requests).doesNotContainKey("/entry/" + entry + "/event/3/picks/");
    }

    @ParameterizedTest
    @ValueSource(strings = {"wildcard", "bboost", "3xc"})
    void otherChipsKeepTheLockedSquadWithoutAnExtraFetch(String chip) {
        currentEvent = 4;
        activeChips.put(ENTRY, chip);
        activeChips.put(2001, chip);

        String answer = miniLeagues().opponents(USER, MINI_LEAGUE, "top1", budget());

        assertThat(answer).contains("owns that the user does not: Haaland")
                .doesNotContain("FREE HIT", "Continuing squad");
        assertThat(requests.keySet()).noneMatch(path -> path.contains("/event/3/"));
    }

    @Test
    void freeHitComparisonsReuseBothGameweeksFromTheSharedSquadCache() {
        currentEvent = 4;
        activeChips.put(ENTRY, "freehit");
        activeChips.put(2001, "freehit");
        previousPicks.put(ENTRY, picks(null, 355, 44));
        previousPicks.put(2001, picks(null, 17, 44));
        MyTeamService myTeam = myTeamService();
        MiniLeagueService leagues = miniLeagues(myTeam);
        FplBudget budget = budget();
        myTeam.myTeam(USER, budget);
        leagues.opponents(USER, MINI_LEAGUE, "top1", budget);

        String cached = leagues.opponents(USER, MINI_LEAGUE, "top1", new FplBudget(0));

        assertThat(cached).contains("Continuing squads - owns that the user does not: Sels");
        for (int entry : new int[] {ENTRY, 2001}) {
            for (int event : new int[] {3, 4}) {
                assertThat(requests.get("/entry/" + entry + "/event/" + event + "/picks/").get())
                        .isEqualTo(1);
            }
        }
    }

    @Test
    void aFailedBootstrapRefreshIsNotRetriedForTheContinuingComparison() {
        currentEvent = 4;
        activeChips.put(ENTRY, "freehit");
        activeChips.put(2001, "freehit");
        previousPicks.put(ENTRY, picks(null, 355, 44));
        previousPicks.put(2001, picks(null, 17, 44));
        MyTeamService myTeam = myTeamService(0);
        myTeam.bootstrap(new FplBudget(1));
        bootstrapStatus = 503;

        String answer = miniLeagues(myTeam).opponents(USER, MINI_LEAGUE, "top1", budget());

        assertThat(answer).contains(
                "Gameweek 4 locked picks only - owns that the user does not: Haaland",
                "Continuing squads - owns that the user does not: Sels");
        assertThat(requests.get("/bootstrap-static/").get()).isEqualTo(2);
    }

    @Test
    void extraFreeHitFetchesStayWithinTheExistingTurnBudget() {
        currentEvent = 4;
        IntStream.rangeClosed(2001, 2011).forEach(entry -> {
            activeChips.put(entry, "freehit");
            previousPicks.put(entry, picks(null, 17, 44));
        });
        FplBudget budget = budget();

        String answer = miniLeagues().opponents(USER, MINI_LEAGUE, "top10", budget);

        assertThat(budget.spent()).isEqualTo(15);
        assertThat(answer).contains("Some teams were left out", "Continuing-squad comparison unavailable");
        assertThat(countOpponentLines(answer)).isBetween(1, 9);
    }

    /**
     * The whole point of the cap arithmetic: getMyTeam's three calls, then a top-ten question
     * that re-reads the entry and the user's squad from cache and spends its allowance on
     * opponents. Fourteen of fifteen, with the app-wide bootstrap snapshot already warm.
     */
    @Test
    void aSquadQuestionAndATopTenQuestionFitInsideOneTurnsBudget() {
        MyTeamService myTeam = myTeamService();
        MiniLeagueService miniLeagues = miniLeagues(myTeam);
        myTeam.bootstrap(new FplBudget(1));
        FplBudget budget = budget();

        myTeam.myTeam(USER, budget);
        String answer = miniLeagues.opponents(USER, MINI_LEAGUE, "top10", budget);

        assertThat(budget.spent()).isEqualTo(14);
        assertThat(answer).doesNotContain("left out");
        assertThat(countOpponentLines(answer)).isEqualTo(10);
    }

    /** A budget that runs out drops opponents with a note; it never fails the whole answer. */
    @Test
    void opponentsBeyondTheBudgetAreDroppedWithANote() {
        String answer = miniLeagues().opponents(USER, MINI_LEAGUE, "top10", new FplBudget(6));

        assertThat(answer).contains("Some teams were left out");
        assertThat(countOpponentLines(answer)).isBetween(1, 9);
    }

    private static int countOpponentLines(String answer) {
        return (int) answer.lines().filter(line -> line.matches(" \\d+.*owns that the user does not.*"))
                .count();
    }

    private FplBudget budget() {
        return new FplBudget(15);
    }

    private MiniLeagueService miniLeagues() {
        return miniLeagues(myTeamService());
    }

    private MiniLeagueService miniLeagues(MyTeamService myTeam) {
        return new MiniLeagueService(profiles, client(), myTeam, 5, 100, 10);
    }

    private MyTeamService myTeamService() {
        return myTeamService(360);
    }

    private MyTeamService myTeamService(long bootstrapTtlMinutes) {
        return new MyTeamService(profiles, client(), new FplBootstrap(RestClient.builder(),
                base() + "/bootstrap-static/", bootstrapTtlMinutes), enrichment, 5, 100);
    }

    private FplEntryClient client() {
        return new FplEntryClient(RestClient.builder(), base() + "/entry/%d/",
                base() + "/entry/%d/event/%d/picks/", base() + "/entry/%d/history/",
                base() + "/leagues-classic/%d/standings/?page_standings=%d");
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    // ------------------------------------------------------------------ stub bodies

    private static int entryIdIn(String path) {
        return Integer.parseInt(path.split("/")[2]);
    }

    private static int page(HttpExchange exchange) {
        String query = exchange.getRequestURI().getQuery();
        return query == null ? 1 : Integer.parseInt(query.replaceAll("\\D", ""));
    }

    private String entryBody(int entryId) {
        String extra = IntStream.rangeClosed(1, extraLeagues).mapToObj(index -> """
                {"id": %d, "name": "Big League %d", "league_type": "x", "entry_rank": 10,
                 "entry_last_rank": 11, "rank_count": %d},
                """.formatted(500 + index, index, 1000 + index)).collect(Collectors.joining());
        String leagues = leaguesAreGlobalOnly ? globalLeague()
                : """
                  {"id": 12, "name": "Office League", "league_type": "x", "entry_rank": %d,
                   "entry_last_rank": 106, "rank_count": 400}, %s%s
                  """.formatted(USER_RANK, extra, globalLeague());
        return """
                {"id": %d, "name": "Team %d", "player_first_name": "Jeremy",
                 "player_last_name": "Wang", "summary_overall_rank": 420132,
                 "summary_overall_points": 41, "current_event": %d,
                 "leagues": {"classic": [%s]}}
                """.formatted(entryId, entryId, currentEvent, leagues);
    }

    private static String globalLeague() {
        return """
               {"id": 314, "name": "Overall", "league_type": "s", "entry_rank": 900000,
                "entry_last_rank": 0, "rank_count": 1089086}
               """;
    }

    /** The user owns 302 and 17; every opponent owns 302 and Haaland (355) instead of 17. */
    private String picks(int entryId) {
        return picks(activeChips.get(entryId), 302, entryId == ENTRY ? 17 : 355);
    }

    private static String picks(String chip, int first, int second) {
        return """
                {"active_chip": %s,
                 "entry_history": {"bank": 12, "value": 1003, "points": 55},
                 "picks": [
                   {"element": %d, "position": 1, "multiplier": 2, "is_captain": true,
                    "is_vice_captain": false, "element_type": 3},
                   {"element": %d, "position": 2, "multiplier": 1, "is_captain": false,
                    "is_vice_captain": true, "element_type": 4}]}
                """.formatted(chip == null ? "null" : "\"" + chip + "\"", first, second);
    }

    /** Fifty rows a page, as FPL serves them: page 3 holds ranks 101-150, the user at 105. */
    private static String standings(int page) {
        int firstRank = (page - 1) * 50 + 1;
        String rows = IntStream.range(0, 50).mapToObj(offset -> {
            int rank = firstRank + offset;
            int entryId = rank == USER_RANK ? ENTRY : 2000 + rank;
            return """
                   {"entry": %d, "entry_name": "Team %d", "player_name": "Jeremy Wang",
                    "rank": %d, "last_rank": %d, "total": %d, "event_total": 55}
                   """.formatted(entryId, entryId, rank, rank + 1, 400 - rank);
        }).collect(Collectors.joining(","));
        return """
                {"league": {"id": 12, "name": "Office League"},
                 "standings": {"has_next": true, "page": %d, "results": [%s]}}
                """.formatted(page, rows);
    }

    private String bootstrap() {
        return """
                {"teams": [{"id": 14, "short_name": "LIV"}, {"id": 4, "short_name": "MCI"}],
                 "elements": [
                   {"id": 302, "web_name": "Salah", "team": 14, "element_type": 3, "now_cost": 126,
                    "form": "8.5", "total_points": 12, "points_per_game": "12.0",
                    "selected_by_percent": "42.1", "status": "a", "news": ""},
                   {"id": 17, "web_name": "Sels", "team": 4, "element_type": 1, "now_cost": 50,
                    "form": "2.0", "total_points": 2, "points_per_game": "2.0",
                    "selected_by_percent": "18.3", "status": "a", "news": ""},
                   {"id": 355, "web_name": "Haaland", "team": 4, "element_type": 4,
                    "now_cost": 150, "form": "9.0", "total_points": 15, "points_per_game": "15.0",
                    "selected_by_percent": "62.0", "status": "a", "news": ""},
                   {"id": 44, "web_name": "Wood", "team": 4, "element_type": 4,
                    "now_cost": 71, "form": "5.0", "total_points": 18, "points_per_game": "6.0",
                    "selected_by_percent": "25.0", "status": "a", "news": ""}],
                 "chips": [{"name": "bboost", "start_event": 1, "stop_event": 19}],
                 "events": [{"id": %d, "deadline_time": "2026-08-21T17:30:00Z", "is_current": true}]}
                """.formatted(currentEvent);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().close();
    }
}
