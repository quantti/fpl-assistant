# KAR-15 — Live gameweek points in the squad card

**PROGRESS:** Task 0/5 — plan written, nothing implemented.

Issue: [KAR-15](https://linear.app/quantti/issue/KAR-15/live-gameweek-points-in-the-squad-card).
Checked in Linear on 2026-09-12: Todo, Medium (3), labels `deferred`, `enhancement`, related to
KAR-50. Migrated from GitHub #30 (2026-08-25), written when the my-team tools shipped and before
the My team page (KAR-14, 2026-09-08) existed. The ticket is one sentence:
`/api/event/{event}/live/` "would add one cached call and one number per pick".

Baseline: `origin/master` at `13be2ae`. Branch `kar-15-live-gameweek-points`, own worktree.
Paths below are relative to `backend/src/` unless stated otherwise.

## Goal and scope

Every squad the my-team tool renders for a gameweek says how many points each pick has scored in
that gameweek so far, plus FPL's own running total for the entry, each labelled with whether the
gameweek is still in progress, finished but unconfirmed, or final. The same numbers reach the My
team page through `GET /api/my-team`. One new FPL call per gameweek, cached app-wide, spent from
the turn's `FplBudget` last so it can never cost the squad itself.

Not in scope: mini-league opponents' live points (`MiniLeagueService` already shows standings'
`event_total`), a recomputed squad total, bonus projection from BPS, auto-sub prediction, the
continuing squad's points (it does not score in the Free Hit gameweek), polling on the page.

## The endpoint

`GET https://fantasy.premierleague.com/api/event/{event}/live/` answers for any gameweek whose
deadline has passed, and 404 before that. The body is `{"elements": [{"id", "stats": {...},
"explain": [...]}]}` — one entry per player in the season, `stats` carrying `minutes`,
`total_points`, `bonus`, `bps` and the rest of the scoring lines. During a round the numbers
move every minute; `total_points` includes bonus only once FPL has added it after each match.
The body is around a megabyte with `explain`, so it gets the `FplBootstrap` timeouts (5 s
connect, 15 s read), not `FplEntryClient`'s 3 s/5 s.

Two bootstrap flags say what the numbers mean. `events[].finished` is true once every fixture of
the round has finished; `events[].data_checked` is true once FPL has confirmed bonus and applied
automatic substitutions (the prompt already tells the model scores are final at 09:00 UK the day
after the last match). Neither is parsed today: `FplBootstrap.Snapshot` reads only deadlines,
`is_current` and `is_next`.

`FplEntryClient.LockedSquad.eventPoints` already carries `entry_history.points` from the picks
endpoint — FPL's own gameweek total for the entry — and nothing renders it. Task 3 puts it on the
card. **Verify once against a real entry (executor step in Task 3):** confirm on a finished
gameweek whether `entry_history.points` is before or after `event_transfers_cost`; the wording
below says "before transfer costs" and must be corrected if that is wrong.

## Decisions

1. **The points belong to the locked gameweek, and only to the scoring squad.** The card is
   built around `Result.Squad.event`; that is the gameweek whose live feed is read. During a
   Free Hit the locked fifteen are the scoring squad and get points; the continuing block gets
   none and already says it does not score. No second live call for `event - 1`.
2. **Two numbers per pick, not one:** points and minutes. Zero points after 90 minutes and zero
   points before kick-off are different facts for advice, and both come from the same stat map.
3. **No squad total is computed here.** `entry_history.points` is FPL's figure and is shown as
   such; the card says the player lines may not sum to it (captain multiplier, bench, automatic
   substitutions applied only when final). The pick's `multiplier` is shown as `counts x2`/`x3`
   beside the captain's raw points; bench picks show raw points.
4. **Status wording is one function, `MyTeamService.gameweekStatus`,** driven by the two bootstrap
   flags: not finished → provisional; finished and not checked → bonus and automatic
   substitutions may still change; checked → final; no event row → unknown. It qualifies both
   the entry total and the player lines.
5. **Cache: `TtlCache<Integer, Live>` keyed by gameweek, app-wide, inside the new component**,
   TTL `fpl.myteam.cache-ttl-minutes` (5), four entries. The cached value carries its own
   `fetchedAt`, which is what the card dates the player lines by — `Result.Squad.fetchedAt` is
   `Instant.now()` at assembly even on a cache hit (KAR-14 plan) and must not be used for this.
   No stale-on-failure serving like `FplBootstrap`: an expired entry plus a failed refresh means
   no per-player points on that card, and the card says so.
6. **Budget order: entry → picks → bootstrap → previous picks (Free Hit) → chips → live.** Live
   is last because it is the most dispensable. A refused budget or a failed call drops the
   player lines, never the squad; the entry total from `eventPoints` still shows.
7. **The page gets the same data, as Task 4.** The ticket names the chat card and predates the
   page; the KAR-14 plan listed live points among that ticket's exclusions, not as a decision
   against the page. Task 4 is self-contained and is the one task the owner can drop without
   touching the others.
8. **Documented chat-time exception, extended not added.** `AGENTS.md` already lists the my-team
   fetches (`entry`, `picks`, `history`, `bootstrap-static`); the live feed joins that sentence.

## File structure

| File | Change |
| --- | --- |
| `main/.../myteam/FplBootstrap.java` | `EventStatus` record; `Snapshot.eventStatus` map and `status(int)` |
| `main/.../myteam/FplLivePoints.java` | **new** — client, parser, cache, budget |
| `main/resources/application.yml` | `fpl.myteam.live-url` |
| `main/.../myteam/MyTeamService.java` | ctor param, `Result.Squad` fields, fetch, render |
| `main/.../myteam/MyTeamResponse.java` | `GameweekPoints` block, `Player` fields |
| `main/resources/prompts/system-prompt.st` | one paragraph in "The user's own team" |
| `frontend/src/components/MyTeamPage/myTeam.ts` | types and validators |
| `frontend/src/components/MyTeamPage/MyTeamPage.tsx`, `SquadSection.tsx` | render |
| `test/.../myteam/FplBootstrapTest.java`, `FplLivePointsTest.java` (new), `MyTeamServiceTest.java`, `MyTeamResponseTest.java`, `MyTeamControllerTest.java`, `MiniLeagueServiceTest.java` | tests and constructor sites |
| `frontend/src/components/MyTeamPage/myTeam.test.ts` | validator and fixture tests |
| `AGENTS.md`, `docs/STATUS.md`, this plan | docs |

## Test prelude

Run once per shell from the worktree root (`scripts/test-db.sh up` already done;
`backend/.env.test` targets `localhost:55432`):

```bash
cd backend
source "$HOME/.sdkman/bin/sdkman-init.sh"
set -a; source .env.test; set +a
```

## Task 1 — `FplBootstrap.Snapshot` reads `finished` and `data_checked`

**Files:** `main/.../myteam/FplBootstrap.java`, `test/.../myteam/FplBootstrapTest.java`.

**Produces:** `FplBootstrap.EventStatus(boolean finished, boolean dataChecked)`;
`Snapshot.status(int event)` returning `Optional<EventStatus>`, empty when the payload has no
such event.

**Step 1 — failing test.** In `FplBootstrapTest`, change the `BODY` events to

```json
"events": [{"id": 1, "deadline_time": "2026-08-21T17:30:00Z", "is_current": true,
            "finished": true, "data_checked": false},
           {"id": 2, "deadline_time": "2026-08-28T17:30:00Z", "is_next": true}]
```

and add

```java
@Test
void eachEventCarriesFplsOwnFinishedAndCheckedFlags() {
    FplBootstrap.Snapshot snapshot = bootstrap().snapshot(new FplBudget(1)).orElseThrow();

    assertThat(snapshot.status(1)).contains(new FplBootstrap.EventStatus(true, false));
    assertThat(snapshot.status(2))
            .as("absent flags are false, not unknown - FPL omits nothing on a real event")
            .contains(new FplBootstrap.EventStatus(false, false));
    assertThat(snapshot.status(3)).as("an event the payload does not carry").isEmpty();
}
```

(`bootstrap()` is the existing helper at line ~135 of the test.)

Run: `./gradlew test --tests "*FplBootstrapTest"` — expected: compilation failure, `EventStatus`
and `status` undefined.

**Step 2 — implement.** In `FplBootstrap`:

```java
/**
 * FPL's own flags for a gameweek: {@code finished} once every fixture of the round has finished,
 * {@code dataChecked} once bonus is confirmed and automatic substitutions are applied. Until the
 * second, live points are provisional.
 */
public record EventStatus(boolean finished, boolean dataChecked) {
}
```

Add `Map<Integer, EventStatus> eventStatus` as the last `Snapshot` component and

```java
public Optional<EventStatus> status(int event) {
    return Optional.ofNullable(eventStatus.get(event));
}
```

In `parse`, beside `deadlines`, declare `Map<Integer, EventStatus> status = new HashMap<>();`
and inside the events loop after the `id == null` guard:

```java
status.put(id, new EventStatus(Boolean.TRUE.equals(event.get("finished")),
        Boolean.TRUE.equals(event.get("data_checked"))));
```

Pass `Map.copyOf(status)` to the constructor. `parse` at line 217 is the only constructor site
(verified: `grep -rn "new Snapshot(" backend/src` finds no other for this class).

Run: `./gradlew test --tests "*FplBootstrapTest"` — expected: PASS.

**Step 3 — commit.**

```bash
git add backend/src/main/java/fi/karivanttinen/fplassistant/myteam/FplBootstrap.java \
        backend/src/test/java/fi/karivanttinen/fplassistant/myteam/FplBootstrapTest.java
git commit -m "Read finished and data_checked flags from bootstrap events"
```

## Task 2 — `FplLivePoints`: one cached call per gameweek

**Files:** create `main/.../myteam/FplLivePoints.java` and
`test/.../myteam/FplLivePointsTest.java`; modify `main/resources/application.yml`.

**Produces:**

```java
public class FplLivePoints {
    public record ElementPoints(int points, int minutes) { }
    public record Live(int event, Instant fetchedAt, Map<Integer, ElementPoints> elements) {
        public Optional<ElementPoints> of(int elementId);
    }
    public FplLivePoints(RestClient.Builder builder, String liveUrl, long ttlMinutes);
    public Optional<Live> forEvent(int event, FplBudget budget);
    static Live parse(int event, Instant fetchedAt, Map<String, Object> body);
}
```

**Step 1 — config.** In `application.yml` under `fpl.myteam`, after `history-url`:

```yaml
    # Points scored so far in one gameweek, for every player at once. Read when the squad card
    # is built (KAR-15), cached per gameweek for cache-ttl-minutes, one call per turn at most.
    live-url: https://fantasy.premierleague.com/api/event/%d/live/
```

**Step 2 — failing tests.** `FplLivePointsTest`, same stub shape as `FplEntryClientTest`:

```java
package fi.karivanttinen.fplassistant.myteam;

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
```

Run: `./gradlew test --tests "*FplLivePointsTest"` — expected: compilation failure, class missing.

**Step 3 — implement.**

```java
package fi.karivanttinen.fplassistant.myteam;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Points scored so far in one gameweek, for every player at once: FPL's
 * {@code /event/{event}/live/}. One call per gameweek, cached app-wide for the same TTL as the
 * squad, because the numbers are the same whoever asks.
 *
 * <p>A chat-time read under the same documented exception as {@link FplEntryClient}: the answer
 * changes every minute of a match, so there is nothing to pre-sync. The body is around a
 * megabyte, hence {@link FplBootstrap}'s timeouts rather than the entry client's.
 *
 * <p>Unlike the bootstrap, nothing stale is served: an expired entry plus a failed refresh means
 * no per-player points, and the card says so. The cached value carries the instant it was read,
 * which is what the card dates the numbers by — the squad's own {@code fetchedAt} is assembly
 * time, even on a cache hit.
 */
@Component
public class FplLivePoints {

    private static final Logger log = LoggerFactory.getLogger(FplLivePoints.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);
    /** Only the gameweek being played and its neighbours are ever asked for. */
    private static final int MAX_EVENTS = 4;

    private static final ParameterizedTypeReference<Map<String, Object>> JSON =
            new ParameterizedTypeReference<>() { };

    public record ElementPoints(int points, int minutes) {
    }

    /** One gameweek's feed and the instant it was read from FPL. */
    public record Live(int event, Instant fetchedAt, Map<Integer, ElementPoints> elements) {
        public Optional<ElementPoints> of(int elementId) {
            return Optional.ofNullable(elements.get(elementId));
        }
    }

    private final RestClient restClient;
    private final String liveUrl;
    private final TtlCache<Integer, Live> cache;

    public FplLivePoints(RestClient.Builder restClientBuilder,
                         @Value("${fpl.myteam.live-url}") String liveUrl,
                         @Value("${fpl.myteam.cache-ttl-minutes}") long ttlMinutes) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(READ_TIMEOUT);
        this.restClient = restClientBuilder.requestFactory(factory).build();
        this.liveUrl = liveUrl;
        this.cache = new TtlCache<>(Duration.ofMinutes(ttlMinutes), MAX_EVENTS);
    }

    /** The gameweek's feed, from cache when fresh; empty when refused, failed or not yet open. */
    public Optional<Live> forEvent(int event, FplBudget budget) {
        Optional<Live> cached = cache.get(event);
        if (cached.isPresent()) {
            return cached;
        }
        if (!budget.take()) {
            return Optional.empty();
        }
        try {
            ResponseEntity<Map<String, Object>> response = restClient.get()
                    .uri(liveUrl.formatted(event))
                    .retrieve()
                    .onStatus(status -> status.value() == 404, (request, ignored) -> { })
                    .toEntity(JSON);
            Map<String, Object> body = response.getBody();
            if (!response.getStatusCode().is2xxSuccessful() || body == null) {
                // 404 is the normal answer before the deadline; anything else is worth a line.
                if (response.getStatusCode().value() != 404) {
                    log.warn("FPL answered {} for the live feed of gameweek {}",
                            response.getStatusCode().value(), event);
                }
                return Optional.empty();
            }
            Live live = parse(event, Instant.now(), body);
            cache.put(event, live);
            return Optional.of(live);
        } catch (RestClientException | ClassCastException e) {
            log.warn("Could not read the live feed of gameweek {} ({})", event,
                    e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    static Live parse(int event, Instant fetchedAt, Map<String, Object> body) {
        Map<Integer, ElementPoints> elements = new HashMap<>();
        if (body.get("elements") instanceof List<?> raw) {
            for (Object item : raw) {
                Map<String, Object> element = asMap(item);
                Integer id = asInt(element.get("id"));
                if (id == null) {
                    continue;
                }
                Map<String, Object> stats = asMap(element.get("stats"));
                elements.put(id, new ElementPoints(orZero(asInt(stats.get("total_points"))),
                        orZero(asInt(stats.get("minutes")))));
            }
        }
        return new Live(event, fetchedAt, Map.copyOf(elements));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static Integer asInt(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}
```

Run: `./gradlew test --tests "*FplLivePointsTest"` — expected: PASS (6 tests).

**Step 4 — commit.**

```bash
git add backend/src/main/java/fi/karivanttinen/fplassistant/myteam/FplLivePoints.java \
        backend/src/test/java/fi/karivanttinen/fplassistant/myteam/FplLivePointsTest.java \
        backend/src/main/resources/application.yml
git commit -m "Add FplLivePoints: one cached live-feed call per gameweek"
```

## Task 3 — The squad card carries gameweek points

**Files:** `main/.../myteam/MyTeamService.java`; `test/.../myteam/MyTeamServiceTest.java`;
constructor sites `test/.../myteam/MyTeamResponseTest.java:117,161`,
`test/.../myteam/MyTeamControllerTest.java:114,231`, `test/.../myteam/MiniLeagueServiceTest.java:414`.

**Consumes:** `FplBootstrap.EventStatus`, `Snapshot.status(int)` (Task 1);
`FplLivePoints.forEvent`, `Live`, `ElementPoints` (Task 2).

**Produces:** `Result.Squad` gains two trailing components,
`FplBootstrap.EventStatus eventStatus` (null when the bootstrap has no row for the gameweek) and
`FplLivePoints.Live live` (null when not read). `MyTeamService` constructor gains
`FplLivePoints live` after `SquadEnrichment enrichment`. A static
`String gameweekStatus(int event, EventStatus status)` for the wording.

**Step 1 — stub and constructor sites.** In `MyTeamServiceTest`:

- Add fields `private boolean finished = false; private boolean dataChecked = false;` and a
  `LIVE` body; route `/event/<n>/live/` in the stub before the picks branches:

```java
} else if (path.matches("/event/\\d+/live/")) {
    respond(exchange, 200, LIVE);
```

```java
private static final String LIVE = """
        {"elements": [
          {"id": 302, "stats": {"minutes": 90, "total_points": 8}, "explain": []},
          {"id": 17, "stats": {"minutes": 0, "total_points": 0}, "explain": []},
          {"id": 44, "stats": {"minutes": 65, "total_points": 2}, "explain": []}]}
        """;
```

- Make the bootstrap's current event carry the flags: change the `events` line of `BOOTSTRAP` to
  `{"id": %d, "deadline_time": "2026-08-21T17:30:00Z", "is_current": true, "finished": %s,
  "data_checked": %s}` and `bootstrap()` to
  `BOOTSTRAP.formatted(currentEvent, finished, dataChecked, currentEvent + 1)`.
- `service()`: build `FplLivePoints live = new FplLivePoints(RestClient.builder(),
  base + "/event/%d/live/", 5);` and pass it:
  `new MyTeamService(profiles, client, bootstrap, enrichment, live, 5, 100)`.
- `MiniLeagueServiceTest:414`: same, with that test's base URL. Its stub answers `200` with the
  entry body for unknown paths, which `parse` reads as an empty element map — harmless, and the
  test asserts nothing about live points.
- `MyTeamResponseTest:117,161` and `MyTeamControllerTest:114,231`: append `null, null` to each
  `new MyTeamService.Result.Squad(...)`. Task 4 replaces those nulls with fixtures.

**Step 2 — failing tests.** Add to `MyTeamServiceTest`:

```java
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
```

Extend the existing `asecondQuestionInTheSameConversationRefetchesNothing` with
`assertThat(requests.get("/entry/" + ... ).get())` unchanged and add
`assertThat(requests.get("/event/" + currentEvent + "/live/").get()).isEqualTo(1);`.
Change `aSpentBudgetDropsTheChipsRatherThanTheSquad` nothing — with 3 units chips and live are
both refused and its assertions still hold.

Run: `./gradlew test --tests "*MyTeamServiceTest"` — expected: compilation failure.

**Step 3 — implement.** In `MyTeamService`:

- Field `private final FplLivePoints live;`, constructor parameter after `enrichment`.
- `Result.Squad`: append `FplBootstrap.EventStatus eventStatus, FplLivePoints.Live live` after
  `Instant fetchedAt`, with Javadoc `@param eventStatus FPL's finished/checked flags for {@code event}; null when the bootstrap has no row for it`
  and `@param live points so far in {@code event} per element; null when the feed was not read (budget spent or a failed call)`.
- In `myTeam`, after the chips block and before `return`:

```java
// Last, because it is the most dispensable: a refused budget or a failed call loses the
// per-player points, never the squad. KAR-15.
FplLivePoints.Live livePoints = this.live.forEvent(event, budget).orElse(null);
FplBootstrap.EventStatus eventStatus = snapshot.get().status(event).orElse(null);

return new Result.Squad(summary, event, snapshot.get().deadline(event), locked, players,
        chipEvent, chips.playable(), chips.openingLater(), chips.opensAt(), chips.known(),
        depth, continuing, Instant.now(), eventStatus, livePoints);
```

- Wording, one place:

```java
/** How far FPL has got with the gameweek — the qualifier on every points figure on the card. */
static String gameweekStatus(int event, FplBootstrap.EventStatus status) {
    if (status == null) {
        return "Whether Gameweek " + event + " is finished could not be read from FPL.";
    }
    if (!status.finished()) {
        return "Gameweek " + event + " is not finished: points are provisional, bonus is added"
                + " after each match, and automatic substitutions apply only once FPL marks"
                + " the gameweek final.";
    }
    if (!status.dataChecked()) {
        return "Gameweek " + event + " has finished but FPL has not confirmed it: bonus and"
                + " automatic substitutions may still change.";
    }
    return "Gameweek " + event + " is final.";
}
```

- In `renderSquad`, after the `Active chip` sentence (the `out.append(".\n\n")` at the end of
  that block) and before the enrichment block, insert:

```java
out.append("Gameweek ").append(squad.event()).append(" points: ");
if (squad.locked().eventPoints() == null) {
    out.append("not published by FPL");
} else {
    out.append(squad.locked().eventPoints())
            .append(" (FPL's own total for this entry, before transfer costs)");
}
out.append(". ").append(gameweekStatus(squad.event(), squad.eventStatus())).append(" ");
if (squad.live() == null) {
    out.append("Per-player gameweek points could not be read from FPL just now.\n\n");
} else {
    out.append("Player gameweek points read ")
            .append(UkTime.format(squad.live().fetchedAt(), "just now"))
            .append(" from FPL's live feed; the player lines need not sum to the entry total")
            .append(" (captain multiplier, bench, automatic substitutions).\n\n");
}
```

- `appendPicks(StringBuilder, List<SquadPlayer>, Instant, FplLivePoints.Live live)`: pass
  `squad.live()` for the locked squad and `null` for the continuing one; `line(player, asOf, live)`
  appends, after the CAPTAIN/vice marker and before `" - form "`:

```java
if (live != null) {
    line.append(" - GW").append(live.event()).append(": ");
    live.of(player.pick().element()).ifPresentOrElse(
            points -> {
                line.append(points.points()).append(" pts, ").append(points.minutes())
                        .append(" min");
                if (player.pick().multiplier() > 1) {
                    line.append(", counts x").append(player.pick().multiplier());
                }
            },
            () -> line.append("n/a"));
}
```

Run: `./gradlew test --tests "*MyTeamServiceTest" --tests "*MyTeamResponseTest" --tests "*MyTeamControllerTest" --tests "*MiniLeagueServiceTest"`
— expected: PASS. Then `./gradlew test --tests "*MyTeamToolsTest"` — the tool wrapper mocks the
service and should be untouched; expected PASS.

**Step 4 — verify the `entry_history.points` wording against FPL once** (the plan's open fact):

```bash
curl -s 'https://fantasy.premierleague.com/api/entry/<the owner's entry id>/event/<a finished GW>/picks/' \
  | python3 -c 'import json,sys; h=json.load(sys.stdin)["entry_history"]; print(h["points"], h["event_transfers_cost"])'
```

Compare `points` with the gameweek score shown on the FPL points page for that entry. If
`points` is already net of the hit, change "before transfer costs" to "after transfer costs" in
`renderSquad` and in the test. Record the outcome in the STATUS entry (Task 5).

**Step 5 — commit.**

```bash
git add backend/src/main/java/fi/karivanttinen/fplassistant/myteam/MyTeamService.java \
        backend/src/test/java/fi/karivanttinen/fplassistant/myteam/
git commit -m "Show each pick's gameweek points and FPL's entry total on the squad card"
```

## Task 4 — The My team page shows the same numbers

**Files:** `main/.../myteam/MyTeamResponse.java`, `test/.../myteam/MyTeamResponseTest.java`,
`test/.../myteam/MyTeamControllerTest.java`; `frontend/src/components/MyTeamPage/myTeam.ts`,
`myTeam.test.ts`, `MyTeamPage.tsx`, `SquadSection.tsx`.

**Consumes:** `Result.Squad.eventStatus`, `Result.Squad.live`, `MyTeamService.gameweekStatus`
is *not* reused — the page carries a status code and words it in TypeScript, as it does for chips.

**Produces (JSON):**

```json
"gameweekPoints": {"gameweek": 5, "status": "in_progress", "entryTotal": 61,
                   "playersAsOf": "2026-09-12T15:32:00Z"}
```

`status` ∈ `in_progress | finished_unchecked | final | unknown`; `entryTotal` null when FPL
published none; `playersAsOf` null when the feed was not read. Each `Player` gains
`gameweekPoints` and `gameweekMinutes`, both `Integer`, null when not read or (continuing squad)
not applicable.

**Step 1 — failing backend tests.** In `MyTeamResponseTest`, `baseSquad(locked, players,
chipsKnown, continuing, deadline)` gains two trailing parameters,
`FplBootstrap.EventStatus eventStatus, FplLivePoints.Live live`, passed through to
`Result.Squad` in place of Task 3's `null, null`. `ordinarySquad` passes
`new FplBootstrap.EventStatus(true, false)` and
`new FplLivePoints.Live(5, LOADED, Map.of(302, new FplLivePoints.ElementPoints(8, 90)))`;
`aPickMissingFromBootstrapIsNotDroppedOrTurnedIntoZeroes` passes `null, null`. Then add:

```java
@Test
void gameweekPointsTravelWithTheirStatusAndPerPlayer() {
    MyTeamResponse.Ready response = (MyTeamResponse.Ready) MyTeamResponse.from(ordinarySquad(true));

    assertThat(response.gameweekPoints()).isEqualTo(
            new MyTeamResponse.GameweekPoints(5, "finished_unchecked", 50, LOADED));
    assertThat(response.lockedSquad().players().get(0).gameweekPoints()).isEqualTo(8);
    assertThat(response.lockedSquad().players().get(0).gameweekMinutes()).isEqualTo(90);
    assertThat(response.lockedSquad().players().get(1).gameweekPoints())
            .as("an element the feed does not carry").isNull();
}

@Test
void anUnreadFeedAndAnUnknownStatusAreNullNotZero() {
    MyTeamService.Result.Squad squad = baseSquad(locked(null, 75, 1001, salahPick()),
            players(salahPick()), true, null, Optional.of(DEADLINE), null, null);

    MyTeamResponse.Ready response = (MyTeamResponse.Ready) MyTeamResponse.from(squad);

    assertThat(response.gameweekPoints().status()).isEqualTo("unknown");
    assertThat(response.gameweekPoints().playersAsOf()).isNull();
    assertThat(response.lockedSquad().players().get(0).gameweekPoints()).isNull();
}
```

`laterChipWindowAndMissingDeadlineArePreserved` builds `Result.Squad` directly: append
`null, null` there. The continuing block's players are mapped with `live = null`, so add to the
existing Free Hit test: `assertThat(available.players().get(0).gameweekPoints()).isNull();`.

In `MyTeamControllerTest`, `readySquad()` (line ~231) replaces Task 3's `null, null` with
`new FplBootstrap.EventStatus(true, false)` and
`new FplLivePoints.Live(5, Instant.parse("2026-09-08T12:34:56Z"),
Map.of(302, new FplLivePoints.ElementPoints(8, 90)))`; the `partial` squad in
`aPartialReadySquadPreservesNullFieldsAndOmitsUnknownContinuingFunds` keeps `null, null`. Add to
`aReadySquadHasThePurposeBuiltJsonAndIsNeverBrowserCached`:

```java
.andExpect(jsonPath("$.gameweekPoints.gameweek").value(5))
.andExpect(jsonPath("$.gameweekPoints.status").value("finished_unchecked"))
.andExpect(jsonPath("$.gameweekPoints.entryTotal").value(50))
.andExpect(jsonPath("$.lockedSquad.players[0].gameweekPoints").value(8))
.andExpect(jsonPath("$.continuing.players[0].gameweekPoints").value(nullValue()))
```

(`nullValue` is `org.hamcrest.Matchers.nullValue`, already used in the file) and to the partial test:

```java
.andExpect(jsonPath("$.gameweekPoints.status").value("unknown"))
.andExpect(jsonPath("$.gameweekPoints.playersAsOf").hasJsonPath())
.andExpect(jsonPath("$.gameweekPoints.playersAsOf").value(nullValue()))
.andExpect(jsonPath("$.lockedSquad.players[0].gameweekPoints").hasJsonPath())
.andExpect(jsonPath("$.lockedSquad.players[0].gameweekPoints").value(nullValue()))
```

Run: `./gradlew test --tests "*MyTeamResponseTest" --tests "*MyTeamControllerTest"` — expected:
compilation failure.

**Step 2 — implement `MyTeamResponse`.**

```java
record Ready(String status, Entry entry, int lockedGameweek, Instant deadline,
             Instant loadedAt, PageSquad lockedSquad, Chips chips,
             Continuation continuing, GameweekPoints gameweekPoints) implements MyTeamResponse { }

/**
 * @param status in_progress | finished_unchecked | final | unknown
 * @param entryTotal FPL's own total for the entry; null when it published none
 * @param playersAsOf when the per-player numbers were read; null when they were not
 */
record GameweekPoints(int gameweek, String status, Integer entryTotal, Instant playersAsOf) { }

record Player(int elementId, int pickPosition, boolean captain, boolean viceCaptain,
              String position, String name, String club, Integer marketPrice,
              String availability, Integer gameweekPoints, Integer gameweekMinutes) { }
```

`ready(...)` passes `gameweekPoints(squad)`:

```java
private static GameweekPoints gameweekPoints(MyTeamService.Result.Squad squad) {
    FplBootstrap.EventStatus status = squad.eventStatus();
    String code = status == null ? "unknown"
            : status.dataChecked() ? "final"
            : status.finished() ? "finished_unchecked"
            : "in_progress";
    return new GameweekPoints(squad.event(), code, squad.locked().eventPoints(),
            squad.live() == null ? null : squad.live().fetchedAt());
}
```

`pageSquad(locked, players, live)` and `players(players, live)` take the feed;
`ready` passes `squad.live()` for the locked squad, `continuation` passes `null`. In `player`:

```java
FplLivePoints.ElementPoints points = live == null ? null
        : live.of(pick.element()).orElse(null);
... , points == null ? null : points.points(), points == null ? null : points.minutes());
```

Run the two test classes — expected: PASS.

**Step 3 — failing frontend tests.** In `myTeam.test.ts`, the `ready()` fixture (line ~247)
gains `gameweekPoints: { gameweek: 5, status: 'in_progress', entryTotal: 61, playersAsOf: '2026-09-12T15:32:00Z' }`
after `continuing`, and its one player gains `gameweekPoints: 8, gameweekMinutes: 90`. Tests
that spread that player into other squads (`...response.lockedSquad.players[0]`) inherit the
fields, which is fine: the validator only requires integer-or-null. Add:

```ts
it('accepts absent gameweek points as null, never as zero', async () => {
  const response = ready()
  response.gameweekPoints = { gameweek: 5, status: 'unknown', entryTotal: null, playersAsOf: null }
  response.lockedSquad.players[0].gameweekPoints = null
  response.lockedSquad.players[0].gameweekMinutes = null
  apiFetchMock.mockResolvedValue(new Response(JSON.stringify(response), { status: 200 }))

  await expect(loadMyTeam(new AbortController().signal)).resolves.toEqual(response)
})

it('rejects an unknown gameweek status or non-integer points', async () => {
  const badStatus = ready()
  ;(badStatus.gameweekPoints as { status: string }).status = 'live'
  apiFetchMock.mockResolvedValue(new Response(JSON.stringify(badStatus), { status: 200 }))
  await expect(loadMyTeam(new AbortController().signal))
    .resolves.toEqual({ status: 'invalid_response' })

  const badPoints = ready()
  ;(badPoints.lockedSquad.players[0] as { gameweekPoints: unknown }).gameweekPoints = '8'
  apiFetchMock.mockResolvedValue(new Response(JSON.stringify(badPoints), { status: 200 }))
  await expect(loadMyTeam(new AbortController().signal))
    .resolves.toEqual({ status: 'invalid_response' })
})

it('words each gameweek status without promising a final score early', () => {
  expect(describeGameweekStatus(5, 'in_progress')).toContain('not finished')
  expect(describeGameweekStatus(5, 'finished_unchecked')).toContain('not confirmed')
  expect(describeGameweekStatus(5, 'final')).toBe('GW 5 is final.')
  expect(describeGameweekStatus(5, 'unknown')).toContain('could not be read')
})
```

Run: `cd frontend && npm test` — expected: FAIL (missing export, invalid fixture).

**Step 4 — implement frontend.** `myTeam.ts`:

```ts
export type GameweekStatus = 'in_progress' | 'finished_unchecked' | 'final' | 'unknown'

export interface GameweekPoints {
  gameweek: number
  status: GameweekStatus
  entryTotal: number | null
  playersAsOf: string | null
}
```

`SquadPlayer` gains `gameweekPoints: number | null` and `gameweekMinutes: number | null`;
`ReadyResponse` gains `gameweekPoints: GameweekPoints`. Validators: `isPlayer` adds
`&& (value.gameweekPoints === null || isInteger(value.gameweekPoints))
&& (value.gameweekMinutes === null || isInteger(value.gameweekMinutes))`; `isReady` adds
`&& isGameweekPoints(value.gameweekPoints)`:

```ts
function isGameweekPoints(value: unknown): value is GameweekPoints {
  return isRecord(value)
    && isInteger(value.gameweek)
    && (value.status === 'in_progress' || value.status === 'finished_unchecked'
      || value.status === 'final' || value.status === 'unknown')
    && (value.entryTotal === null || isInteger(value.entryTotal))
    && (value.playersAsOf === null || typeof value.playersAsOf === 'string')
}

export function describeGameweekStatus(gameweek: number, status: GameweekStatus): string {
  switch (status) {
    case 'in_progress':
      return `GW ${gameweek} is not finished: points are provisional, bonus is added after each match, and automatic substitutions apply only once FPL marks the gameweek final.`
    case 'finished_unchecked':
      return `GW ${gameweek} has finished but FPL has not confirmed it: bonus and automatic substitutions may still change.`
    case 'final':
      return `GW ${gameweek} is final.`
    case 'unknown':
      return `Whether GW ${gameweek} is finished could not be read from FPL.`
  }
}
```

`MyTeamPage.tsx`: a section between the snapshot header and the Free Hit notice:

```tsx
<section className={classes.chips} aria-labelledby="gameweek-points-title">
  <h2 id="gameweek-points-title" className={classes.sectionTitle}>GW {data.gameweekPoints.gameweek} points</h2>
  <p>{data.gameweekPoints.entryTotal === null
    ? 'FPL has not published a total for this entry.'
    : `${data.gameweekPoints.entryTotal} points — FPL's own total for this entry, before transfer costs.`}</p>
  <p>{describeGameweekStatus(data.gameweekPoints.gameweek, data.gameweekPoints.status)}</p>
  <p>{data.gameweekPoints.playersAsOf === null
    ? 'Per-player points could not be read from FPL just now.'
    : `Per-player points read ${formatTimestamp(data.gameweekPoints.playersAsOf)} from FPL's live feed; player lines need not sum to the total.`}</p>
</section>
```

(`classes.chips` is the existing plain section style; no new CSS.) `SquadSection.tsx`, in
`playerMeta` before the market price:

```tsx
{player.gameweekPoints !== null && (
  <>{`GW ${gameweek}: ${player.gameweekPoints} pts · ${player.gameweekMinutes ?? 0} min`}{' · '}</>
)}
```

`gameweek` is `PlayerGroup`'s existing prop, which is the picks' own gameweek — for the
continuing squad the numbers are null and nothing prints.

Run: `cd frontend && npm test && npm run build` — expected: all green, `tsc -b` clean.

**Step 5 — browser check** (headless, as KAR-14 did): `npm run dev` against a running backend
with a signed-in session, open `/my-team`, confirm the section renders and the player meta line
shows `GW N: x pts · y min` on the locked squad only. If no session is at hand, record in the
STATUS entry that the page was verified by tests and build only.

**Step 6 — commit.**

```bash
git add backend/src/main/java/fi/karivanttinen/fplassistant/myteam/MyTeamResponse.java \
        backend/src/test/java/fi/karivanttinen/fplassistant/myteam/ \
        frontend/src/components/MyTeamPage/
git commit -m "Show gameweek points on the My team page"
```

## Task 5 — Prompt, docs, full suite, PR

**Files:** `main/resources/prompts/system-prompt.st`, `AGENTS.md`, `docs/STATUS.md`, this plan.

**Step 1 — prompt.** In "The user's own team", after the paragraph ending "relay that age when
you lean on them.", add:

```
The card also shows each pick's points and minutes so far in the locked gameweek, read from
FPL's live feed at the time stated, and FPL's own running total for the entry. Read the status
sentence first: while the gameweek is not final, bonus can still be added and automatic
substitutions have not been applied, so never present a provisional score as the result. A
gameweek's points are one round of evidence, not form — use form and season totals for that.
The continuing squad carries no gameweek points because it does not score in a Free Hit week.
```

**Step 2 — `AGENTS.md`.** In the my-team tools bullet, after "These reads also fetch
`bootstrap-static` (cached app-wide for six hours) because ... names *different players*.", add
one sentence: "`getMyTeam` also reads FPL's `/event/{event}/live/` for the locked gameweek
(`FplLivePoints`, cached app-wide per gameweek for the squad TTL, spent from the budget last so a
refusal loses the player points and never the squad); the card dates those numbers by the
feed's own read time, never by the squad's assembly time."

**Step 3 — full suite.**

```bash
./gradlew cleanTest test
cd ../frontend && npm test && npm run build
```

Expected: 0 failures, 0 skips. Record the test counts in the STATUS entry.

**Step 4 — STATUS entry** at the end of `docs/STATUS.md`:

```markdown
## Gameweek points on the squad card and the My team page (KAR-15, <date>)

Plan: `docs/superpowers/plans/2026-09-12-kar-15-live-gameweek-points.md`. `FplLivePoints` reads
FPL's `/event/{event}/live/` once per gameweek, cached app-wide for the squad TTL, and is spent
from the turn's budget last so it can only ever lose the player lines. The card and the page show
each pick's points and minutes in the locked gameweek, FPL's own entry total
(`entry_history.points`, parsed since the my-team tools shipped and unrendered until now), and
one status sentence from the bootstrap's `finished`/`data_checked` flags: provisional, finished
but unconfirmed, final, or unknown. The continuing squad carries none. No total is recomputed.

Verified: <backend count> tests, <frontend count> vitest, build green; `entry_history.points`
checked against entry <id> GW <n>: <before|after> transfer costs. <Browser check outcome.>
```

**Step 5 — plan progress, commit, push, PR.**

Set `**PROGRESS:** Task 5/5 done.` at the top of this file.

```bash
git add AGENTS.md docs/STATUS.md docs/superpowers/plans/2026-09-12-kar-15-live-gameweek-points.md \
        backend/src/main/resources/prompts/system-prompt.st
git commit -m "Close out KAR-15: prompt, AGENTS and STATUS"
git push -u origin kar-15-live-gameweek-points
```

PR title: `Show live gameweek points on the squad card and My team page`. Body, at most eight
lines: what changed, why, verification line, plan path, `Closes KAR-15`, then the attribution
lines. Never push `master`.

## Self-review

- Ticket coverage: one cached call (Task 2), one number per pick — two, with the reason in
  Decision 2 (Task 3), card (Task 3), page (Task 4).
- Names used across tasks: `FplBootstrap.EventStatus`, `Snapshot.status(int)`,
  `FplLivePoints.Live`, `.of(int)`, `.fetchedAt()`, `ElementPoints(points, minutes)`,
  `Result.Squad(..., fetchedAt, eventStatus, live)`, `MyTeamService.gameweekStatus`,
  `MyTeamResponse.GameweekPoints(gameweek, status, entryTotal, playersAsOf)`,
  `Player(..., gameweekPoints, gameweekMinutes)`, `describeGameweekStatus` — consistent.
- Open fact: gross vs net `entry_history.points`, resolved by Task 3 Step 4, recorded in STATUS.
