# Gameweek quota window implementation plan (KAR-61)

**PROGRESS:** Task 7/7 done

**Goal:** Replace the rolling seven-day message quota with a window bounded by FPL deadlines: 20
messages between two deadlines of one season, 100 for the off-season gap, and today's rolling
window as the fallback when the schedule cannot be read.

**Architecture:** A new `QuotaWindows` resolves the window for an instant from every captured
season's deadlines, read as one timeline. `UsageService` counts inside that window instead of
`now() - interval '7 days'`. The response and the UI name the deadline the window ends at.

**Tech Stack:** Spring Boot 4.1 / Java 25 / JdbcTemplate; React 19 + Mantine v9 + Vitest.

**Spec:** `docs/superpowers/specs/2026-09-19-quota-gameweek-window-design.md` — read it first; the
reasoning for every rule below lives there, including the two deliberate exceptions.

## Global Constraints

- Window rule: `[last deadline at or before asOf, first deadline strictly after asOf)`, over every
  captured season ordered by `deadline_time`. Strict `isAfter`, matching `HorizonFixtures.derive`.
- `GAMEWEEK` (both ends present, same season) → 20. Everything else → `OFF_SEASON` → 100. One
  off-season window for the whole break; it never resets inside itself.
- **Freshness is NOT checked.** The schedule sync refuses to write all summer, so requiring it
  would abolish the off-season window. Only the latest season's event set must be a clean 1..38
  with one `captured_at`.
- The fallback is today's code path, unchanged, including the `OFFSET (used - limit)` helper.
- A per-user `fpl_user_profile.weekly_message_limit` override replaces the window's limit whatever
  the kind. Negative means unlimited, `0` suspended. The column is NOT renamed.
- The UI never says "this gameweek". It names the deadline.
- Tests seed their own `fpl_gameweek_test` rows and clear them afterwards. Other suites seed that
  table too, so a quota test that assumes it is empty is order-dependent and will flake.

---

### Task 1: `ScheduleSource.deadlines()` and the event-only completeness check

**Files:**
- Modify: `backend/src/main/java/fi/karivanttinen/fplassistant/schedule/ScheduleSource.java`
- Test: `backend/src/test/java/fi/karivanttinen/fplassistant/schedule/ScheduleSourceDeadlinesTest.java` (create)

**Interfaces produced:**
```java
public record Deadline(String season, int gameweek, Instant deadline) {}
public List<Deadline> deadlines();                       // all seasons, ORDER BY deadline_time
public static Completeness assessEvents(Optional<Snapshot> snapshot, List<EventRow> events);
```

**Step 1 — failing test.** `ScheduleSourceDeadlinesTest`, `@SpringBootTest @ActiveProfiles("test")`,
autowiring `ScheduleSource` and `JdbcTemplate` with `@Value("${fpl.schedule.gameweek-table-name}")`.
`@BeforeEach` and `@AfterEach` both `DELETE FROM <gameweekTable>` so the suite's other seeders
cannot leak in.

```java
private void seedEvent(String season, int id, Instant deadline, Instant capturedAt) {
    jdbc.update("INSERT INTO " + gameweekTable
            + " (season, id, name, deadline_time, captured_at) VALUES (?, ?, ?, ?, ?)",
            season, id, "Gameweek " + id, Timestamp.from(deadline), Timestamp.from(capturedAt));
}

@Test
void deadlinesSpanEverySeasonInTimeOrder() {
    Instant captured = Instant.parse("2026-09-19T01:10:00Z");
    seedEvent("2025/26", 38, Instant.parse("2026-05-24T13:00:00Z"), captured);
    seedEvent("2026/27", 1, Instant.parse("2026-08-14T17:30:00Z"), captured);
    seedEvent("2026/27", 2, Instant.parse("2026-08-21T17:30:00Z"), captured);

    assertThat(source.deadlines()).extracting(ScheduleSource.Deadline::season,
                    ScheduleSource.Deadline::gameweek)
            .containsExactly(tuple("2025/26", 38), tuple("2026/27", 1), tuple("2026/27", 2));
}

@Test
void assessEventsAcceptsACleanSetAndRejectsAGap() {
    Instant captured = Instant.parse("2026-09-19T01:10:00Z");
    Optional<ScheduleSource.Snapshot> snapshot =
            Optional.of(new ScheduleSource.Snapshot("2026/27", captured));
    List<ScheduleSource.EventRow> complete = new ArrayList<>();
    for (int id = 1; id <= 38; id++) {
        complete.add(new ScheduleSource.EventRow(id, captured.plusSeconds(id * 86400L), captured));
    }
    assertThat(ScheduleSource.assessEvents(snapshot, complete).complete()).isTrue();

    List<ScheduleSource.EventRow> missingOne = complete.subList(0, 37);
    assertThat(ScheduleSource.assessEvents(snapshot, missingOne).reason())
            .isEqualTo(ScheduleSource.CompletenessReason.INVALID_EVENT_SET);
}
```

**Step 2 — run it, expect compilation failure** (`deadlines`/`assessEvents` do not exist):
`cd backend && ./gradlew test --tests '*ScheduleSourceDeadlinesTest'`

**Step 3 — implement.** Add the record beside `EventRow`:

```java
/** One deadline on the cross-season timeline the quota window is cut from. */
public record Deadline(String season, int gameweek, Instant deadline) {}
```

and the read, which unlike `readView()` is not scoped to the latest season — the season changeover
is a boundary between two seasons' rows and cannot be seen from one of them:

```java
public List<Deadline> deadlines() {
    return jdbc.query("""
            SELECT season, id, deadline_time FROM %s ORDER BY deadline_time
            """.formatted(gameweekTable), (rs, rowNum) -> new Deadline(
                    rs.getString("season"), rs.getInt("id"),
                    instantOf(rs.getObject("deadline_time"))));
}
```

Then extract the event-id block already inside `assess` into a private helper and build
`assessEvents` on it. **Do not reorder `assess`'s existing checks** — the combined
event-and-fixture `MIXED_CAPTURE_TIMESTAMPS` check must stay where it is, or a payload that trips
two rules starts reporting the other one and an existing test changes meaning.

```java
/** Duplicate ids, then the 1..38 set. Shared so "a clean event set" has one definition. */
private static Completeness eventIds(List<EventRow> events) {
    Set<Integer> eventIds = new HashSet<>();
    for (EventRow event : events) {
        if (!eventIds.add(event.id())) {
            return incomplete(CompletenessReason.DUPLICATE_EVENT_ID);
        }
    }
    if (eventIds.size() != 38
            || !eventIds.equals(java.util.stream.IntStream.rangeClosed(1, 38)
                    .boxed().collect(java.util.stream.Collectors.toUnmodifiableSet()))) {
        return incomplete(CompletenessReason.INVALID_EVENT_SET);
    }
    // Completeness' compact constructor requires the reason and the flag to agree: a complete
    // result carries COMPLETE, never null.
    return new Completeness(true, CompletenessReason.COMPLETE);
}

/**
 * The event half of {@link #assess}, for a caller with no interest in fixtures. The quota window
 * needs deadlines to be a coherent 1..38 and needs nothing at all from the fixture list.
 */
public static Completeness assessEvents(Optional<Snapshot> snapshot, List<EventRow> events) {
    if (snapshot.isEmpty()) {
        return incomplete(CompletenessReason.NO_CAPTURE);
    }
    Instant capturedAt = snapshot.get().capturedAt();
    if (events.stream().anyMatch(row -> !capturedAt.equals(row.capturedAt()))) {
        return incomplete(CompletenessReason.MIXED_CAPTURE_TIMESTAMPS);
    }
    return eventIds(events);
}
```

In `assess`, replace the inlined duplicate/1..38 block with `Completeness ids = eventIds(events);
if (!ids.complete()) { return ids; }`, keeping every surrounding check in place. The local
`eventIds` set is used further down for `INVALID_ASSIGNED_GAMEWEEK`; rebuild it there as
`Set<Integer> eventIds = events.stream().map(EventRow::id).collect(toSet());` rather than leaving
the loop in place.

**Step 4 — run.** `./gradlew test --tests '*ScheduleSourceDeadlinesTest' --tests '*ScheduleSourceTest' --tests '*ScheduleSourceViewIntegrationTest'`. All pass; the existing two prove the extraction changed nothing.

**Step 5 — commit.** `Add a cross-season deadline read to ScheduleSource (KAR-61)`

---

### Task 2: `QuotaWindow` and `QuotaWindows`

**Files:**
- Create: `backend/src/main/java/fi/karivanttinen/fplassistant/user/QuotaWindow.java`
- Create: `backend/src/main/java/fi/karivanttinen/fplassistant/user/QuotaWindows.java`
- Modify: `backend/src/main/resources/application.yml:143-148`
- Test: `backend/src/test/java/fi/karivanttinen/fplassistant/user/QuotaWindowTest.java` (create)

**Interfaces consumed:** `ScheduleSource.deadlines()`, `ScheduleSource.assessEvents(...)` (Task 1).

**Interfaces produced:**
```java
public record QuotaWindow(Kind kind, Instant from, Instant until, int limit,
                          Integer gameweek, String season) {
    public enum Kind { GAMEWEEK, OFF_SEASON, FALLBACK }
}
public QuotaWindow current(Instant asOf);                                  // on QuotaWindows
static QuotaWindow derive(List<ScheduleSource.Deadline> deadlines, Instant asOf,
                          int gameweekLimit, int offSeasonLimit);          // package-private, pure
```

**Step 1 — properties.** In `application.yml` replace the `free-messages-per-week` entry:

```yaml
  quota:
    # The free tier's allowance inside one gameweek window — between two deadlines of the same
    # season — and the fallback for every account with no override in
    # fpl_user_profile.weekly_message_limit. NULL there means this, -1 unlimited, 0 suspended.
    # Public information: it is the offer, and it is on the marketing page either way.
    free-messages-per-gameweek: ${QUOTA_FREE_MESSAGES_PER_GAMEWEEK:20}
    # The allowance for the gap between two seasons, which is one window from the last deadline
    # of a season to the first of the next — not a weekly reset. Larger because it is months.
    free-messages-off-season: ${QUOTA_FREE_MESSAGES_OFF_SEASON:100}
```

Also grep for the old key: `grep -rn "free-messages-per-week\|QUOTA_FREE_MESSAGES_PER_WEEK" --include=*.yml --include=*.java --include=*.md .` and update `backend/src/test/resources/application-test.yml` and `UsageServiceTest`'s `@Value` in the tasks that touch them.

**Step 2 — failing test.** `QuotaWindowTest` is a plain JUnit test on the pure `derive`; no Spring
context, because the rule is arithmetic on a list and a database adds nothing but slowness.

```java
class QuotaWindowTest {

    private static final Instant CAPTURED = Instant.parse("2026-09-19T01:10:00Z");

    private static ScheduleSource.Deadline deadline(String season, int gw, String at) {
        return new ScheduleSource.Deadline(season, gw, Instant.parse(at));
    }

    /** GW12 on the 19th, GW13 on the 26th, both this season. */
    private static List<ScheduleSource.Deadline> twoRounds() {
        return List.of(deadline("2026/27", 12, "2026-09-19T10:30:00Z"),
                       deadline("2026/27", 13, "2026-09-26T10:30:00Z"));
    }

    @Test
    void midRoundIsAGameweekWindowEndingAtTheNextDeadline() {
        QuotaWindow window = QuotaWindows.derive(twoRounds(),
                Instant.parse("2026-09-22T09:00:00Z"), 20, 100);

        assertThat(window.kind()).isEqualTo(QuotaWindow.Kind.GAMEWEEK);
        assertThat(window.from()).isEqualTo(Instant.parse("2026-09-19T10:30:00Z"));
        assertThat(window.until()).isEqualTo(Instant.parse("2026-09-26T10:30:00Z"));
        assertThat(window.gameweek()).isEqualTo(13);
        assertThat(window.limit()).isEqualTo(20);
    }

    /** At the deadline instant the round is locked, so that instant opens the next window. */
    @Test
    void theDeadlineInstantItselfOpensTheNewWindow() {
        QuotaWindow window = QuotaWindows.derive(twoRounds(),
                Instant.parse("2026-09-19T10:30:00Z"), 20, 100);

        assertThat(window.from()).isEqualTo(Instant.parse("2026-09-19T10:30:00Z"));
        assertThat(window.gameweek()).isEqualTo(13);
    }

    @Test
    void theGapBetweenTwoSeasonsIsOneOffSeasonWindow() {
        List<ScheduleSource.Deadline> across = List.of(
                deadline("2025/26", 38, "2026-05-24T13:00:00Z"),
                deadline("2026/27", 1, "2026-08-14T17:30:00Z"));

        QuotaWindow window = QuotaWindows.derive(across,
                Instant.parse("2026-07-01T00:00:00Z"), 20, 100);

        assertThat(window.kind()).isEqualTo(QuotaWindow.Kind.OFF_SEASON);
        assertThat(window.from()).isEqualTo(Instant.parse("2026-05-24T13:00:00Z"));
        assertThat(window.until()).isEqualTo(Instant.parse("2026-08-14T17:30:00Z"));
        assertThat(window.limit()).isEqualTo(100);
        assertThat(window.gameweek()).isNull();
    }

    @Test
    void beforeTheFirstDeadlineEverCapturedTheWindowIsUnboundedBackwards() {
        QuotaWindow window = QuotaWindows.derive(twoRounds(),
                Instant.parse("2026-08-01T00:00:00Z"), 20, 100);

        assertThat(window.kind()).isEqualTo(QuotaWindow.Kind.OFF_SEASON);
        assertThat(window.from()).isNull();
        assertThat(window.until()).isEqualTo(Instant.parse("2026-09-19T10:30:00Z"));
    }

    @Test
    void afterTheLastCapturedDeadlineTheWindowIsUnboundedForwards() {
        QuotaWindow window = QuotaWindows.derive(twoRounds(),
                Instant.parse("2027-06-01T00:00:00Z"), 20, 100);

        assertThat(window.kind()).isEqualTo(QuotaWindow.Kind.OFF_SEASON);
        assertThat(window.from()).isEqualTo(Instant.parse("2026-09-26T10:30:00Z"));
        assertThat(window.until()).isNull();
    }

    @Test
    void noDeadlinesAtAllIsTheFallback() {
        QuotaWindow window = QuotaWindows.derive(List.of(), Instant.now(), 20, 100);

        assertThat(window.kind()).isEqualTo(QuotaWindow.Kind.FALLBACK);
        assertThat(window.limit()).isEqualTo(20);
        assertThat(window.from()).isNull();
        assertThat(window.until()).isNull();
    }
}
```

**Step 3 — run, expect compilation failure.**
`./gradlew test --tests '*QuotaWindowTest'`

**Step 4 — implement `QuotaWindow`.**

```java
package fi.karivanttinen.fplassistant.user;

import java.time.Instant;

/**
 * The period one allowance covers.
 *
 * <p>Bounds are half-open and nullable: null means unbounded, which is what the off-season is
 * before the first deadline has ever been captured and after the last one. A FALLBACK window
 * carries no bounds, no gameweek and no season — its counting lives in the rolling-window query
 * it delegates to, and a second copy here would only invite someone to read it.
 *
 * @param gameweek the round this window is the run-up to, on a GAMEWEEK window only. The matches
 *                 being played during it belong to the previous round, which is why nothing
 *                 user-facing calls this "the current gameweek"
 */
public record QuotaWindow(Kind kind, Instant from, Instant until, int limit,
                          Integer gameweek, String season) {

    public enum Kind { GAMEWEEK, OFF_SEASON, FALLBACK }

    public boolean fallback() {
        return kind == Kind.FALLBACK;
    }
}
```

**Step 5 — implement `QuotaWindows`.**

```java
package fi.karivanttinen.fplassistant.user;

/**
 * Resolves the window an allowance is counted in.
 *
 * <p>The deadlines of every captured season are read as one timeline. Reading only the latest
 * season would leave the preseason of a new season with no lower bound at all, and it would
 * count the whole of the previous season against a user.
 *
 * <p>Freshness is deliberately not checked. The schedule sync legitimately refuses to write
 * across the summer, so requiring a fresh capture would mean the off-season window — the reason
 * this class exists — could never occur. Deadlines do not change retroactively; a stale capture
 * moves a boundary by hours, where a stale fixture list makes a ranking horizon meaningless.
 * Do not "fix" this onto ScheduleFreshnessCheck.
 */
@Component
public class QuotaWindows {

    private static final Logger log = LoggerFactory.getLogger(QuotaWindows.class);

    /** The deadline list changes at most daily; the window is always derived from a live asOf. */
    private static final Duration CACHE_FOR = Duration.ofMinutes(5);

    private final ScheduleSource schedule;
    private final int gameweekLimit;
    private final int offSeasonLimit;

    private volatile List<ScheduleSource.Deadline> cached = List.of();
    private volatile Instant cachedAt = Instant.MIN;

    public QuotaWindows(ScheduleSource schedule,
                        @Value("${fpl.quota.free-messages-per-gameweek}") int gameweekLimit,
                        @Value("${fpl.quota.free-messages-off-season}") int offSeasonLimit) {
        this.schedule = schedule;
        this.gameweekLimit = gameweekLimit;
        this.offSeasonLimit = offSeasonLimit;
    }

    public QuotaWindow current(Instant asOf) {
        return derive(usableDeadlines(asOf), asOf, gameweekLimit, offSeasonLimit);
    }

    /**
     * The deadlines the window may be cut from, or empty when they cannot be trusted — which
     * derive() turns into the fallback. Only the latest season is validated: a request cannot
     * fall inside a completed season, and a missing old deadline can only lengthen an off-season
     * window without changing its limit.
     */
    private List<ScheduleSource.Deadline> usableDeadlines(Instant asOf) {
        try {
            List<ScheduleSource.Deadline> all = cachedDeadlines(asOf);
            if (all.isEmpty()) {
                return List.of();
            }
            Optional<ScheduleSource.Snapshot> snapshot = schedule.latest();
            if (snapshot.isEmpty()) {
                return List.of();
            }
            String season = snapshot.get().season();
            // assessEvents wants EventRows; the capture timestamp is already known to be the
            // snapshot's, so the rows are built with it and the check reduces to the 1..38 set.
            List<ScheduleSource.EventRow> events = all.stream()
                    .filter(d -> d.season().equals(season))
                    .map(d -> new ScheduleSource.EventRow(d.gameweek(), d.deadline(),
                            snapshot.get().capturedAt()))
                    .toList();
            if (!ScheduleSource.assessEvents(snapshot, events).complete()) {
                log.warn("quota window: {} events are not a clean 1..38; using the rolling window",
                        season);
                return List.of();
            }
            return all;
        } catch (DataAccessException e) {
            log.warn("quota window: schedule unreadable; using the rolling window", e);
            return List.of();
        }
    }

    private List<ScheduleSource.Deadline> cachedDeadlines(Instant asOf) {
        if (asOf.isBefore(cachedAt.plus(CACHE_FOR))) {
            return cached;
        }
        List<ScheduleSource.Deadline> fresh = schedule.deadlines();
        cached = fresh;
        cachedAt = asOf;
        return fresh;
    }

    /** Pure, so the rule is tested without a database. */
    static QuotaWindow derive(List<ScheduleSource.Deadline> deadlines, Instant asOf,
                              int gameweekLimit, int offSeasonLimit) {
        if (deadlines.isEmpty()) {
            return new QuotaWindow(QuotaWindow.Kind.FALLBACK, null, null, gameweekLimit,
                    null, null);
        }
        ScheduleSource.Deadline previous = null;
        ScheduleSource.Deadline next = null;
        for (ScheduleSource.Deadline d : deadlines) {
            // Strictly after: at the deadline instant the round is already locked, so that
            // instant opens the new window rather than closing it late.
            if (d.deadline().isAfter(asOf)) {
                if (next == null || d.deadline().isBefore(next.deadline())) {
                    next = d;
                }
            } else if (previous == null || d.deadline().isAfter(previous.deadline())) {
                previous = d;
            }
        }
        boolean oneRound = previous != null && next != null
                && previous.season().equals(next.season());
        if (oneRound) {
            return new QuotaWindow(QuotaWindow.Kind.GAMEWEEK, previous.deadline(),
                    next.deadline(), gameweekLimit, next.gameweek(), next.season());
        }
        return new QuotaWindow(QuotaWindow.Kind.OFF_SEASON,
                previous == null ? null : previous.deadline(),
                next == null ? null : next.deadline(), offSeasonLimit, null,
                next != null ? next.season() : previous.season());
    }
}
```

The loop does not assume the list is sorted even though the SQL orders it — a min/max scan costs
nothing over 38 rows and removes a silent dependency between two files.

**Step 6 — run.** `./gradlew test --tests '*QuotaWindowTest'` — all pass.

**Step 7 — commit.** `Resolve the quota window from FPL deadlines (KAR-61)`

---

### Task 3: `UsageService` counts inside the window

**Files:**
- Modify: `backend/src/main/java/fi/karivanttinen/fplassistant/user/UsageService.java`
- Modify: `backend/src/test/java/fi/karivanttinen/fplassistant/user/UsageServiceTest.java`
- Modify: `backend/src/test/resources/application-test.yml:` the `fpl.quota` block

**Interfaces consumed:** `QuotaWindows.current(Instant)`, `QuotaWindow` (Task 2).

**Interfaces produced:**
```java
public record Quota(Plan plan, int used, Integer limit,
                    Instant nextFreeAt, Instant resetsAt, QuotaWindow window)
```

**Step 1 — failing tests.** Add to `UsageServiceTest`. It must own `fpl_gameweek_test`: add the
table's `@Value`, and `@BeforeEach`/`@AfterEach` `DELETE FROM` it, because other suites seed it and
an inherited capture would silently change this suite's window.

```java
@Value("${fpl.schedule.gameweek-table-name}")
String gameweekTable;

@Value("${fpl.quota.free-messages-off-season}")
int offSeasonLimit;

private void seedDeadlines(String season, Instant... deadlines) {
    Instant captured = Instant.now();
    for (int i = 0; i < 38; i++) {
        // A clean 1..38 is required; only the seeded ones need meaningful times.
        Instant deadline = i < deadlines.length ? deadlines[i]
                : Instant.parse("2030-01-01T00:00:00Z").plusSeconds(i * 86400L);
        jdbc.update("INSERT INTO " + gameweekTable
                + " (season, id, name, deadline_time, captured_at) VALUES (?, ?, ?, ?, ?)",
                season, i + 1, "Gameweek " + (i + 1), Timestamp.from(deadline),
                Timestamp.from(captured));
    }
}

@Test
void messagesSentBeforeTheWindowOpenedAreNotCounted() {
    seedDeadlines("2026/27", Instant.now().minus(Duration.ofDays(2)),
            Instant.now().plus(Duration.ofDays(5)));
    seedEvents(3, Duration.ofDays(3), UsageService.CHAT_EVENT);   // before the GW1 deadline
    seedEvents(2, Duration.ofHours(6), UsageService.CHAT_EVENT);  // inside the window

    UsageService.Quota quota = usage.quotaOf(USER);

    assertThat(quota.window().kind()).isEqualTo(QuotaWindow.Kind.GAMEWEEK);
    assertThat(quota.used()).isEqualTo(2);
    assertThat(quota.resetsAt()).isNotNull();
}

@Test
void theOffSeasonGapCarriesItsOwnLimit() {
    // Season 2025/26 finished; 2026/27 has not been captured, so there is no later deadline.
    seedDeadlines("2025/26", Instant.now().minus(Duration.ofDays(40)));
    seedEvents(1, Duration.ofDays(3), UsageService.CHAT_EVENT);

    UsageService.Quota quota = usage.quotaOf(USER);

    assertThat(quota.window().kind()).isEqualTo(QuotaWindow.Kind.OFF_SEASON);
    assertThat(quota.limit()).isEqualTo(offSeasonLimit);
    assertThat(quota.resetsAt()).isNull();
}

@Test
void aPerUserOverrideReplacesTheWindowsOwnLimit() {
    seedDeadlines("2025/26", Instant.now().minus(Duration.ofDays(40)));
    setLimit(5);

    assertThat(usage.quotaOf(USER).limit()).isEqualTo(5);
}

@Test
void withNoCaptureTheRollingWindowStillApplies() {
    seedEvents(freeLimit, Duration.ofDays(1), UsageService.CHAT_EVENT);

    UsageService.Quota quota = usage.quotaOf(USER);

    assertThat(quota.window().kind()).isEqualTo(QuotaWindow.Kind.FALLBACK);
    assertThat(quota.exceeded()).isTrue();
    assertThat(quota.resetsAt()).isNull();
    assertThat(quota.nextFreeAt()).isNotNull();
}
```

The existing tests in this file seed no deadlines, so they all take the fallback path and must keep
passing unchanged. Rename the `freeLimit` `@Value` to `${fpl.quota.free-messages-per-gameweek}`.

**Step 2 — run, expect failures.** `./gradlew test --tests '*UsageServiceTest'`

**Step 3 — implement.** `Quota` gains two components; `retryAfterSeconds()` is unchanged and still
reads `nextFreeAt`. `quotaOf` becomes:

```java
public Quota quotaOf(UUID userId) {
    Plan plan = planOf(userId);
    QuotaWindow window = windows.current(Instant.now());
    Integer limit = resolveLimit(userId, plan, window);
    if (limit == null) {
        return new Quota(plan, 0, null, null, null, window);
    }
    if (window.fallback()) {
        int used = countSince(userId, Instant.now().minus(WINDOW));
        return new Quota(plan, used, limit, nextFreeAt(userId, used, limit), null, window);
    }
    int used = countBetween(userId, window.from(), window.until());
    // A fixed window has one reset instant, so "when may I send again" and "when does the count
    // start over" are the same answer — unlike the fallback, where nothing starts over.
    Instant resetsAt = window.until();
    return new Quota(plan, used, limit, used >= limit ? resetsAt : null, resetsAt, window);
}
```

`countBetween` builds the predicate from whichever bounds are present:

```java
private int countBetween(UUID userId, Instant from, Instant until) {
    StringBuilder sql = new StringBuilder("SELECT count(*) FROM " + usageTable
            + " WHERE user_id = ? AND event_type = ?");
    List<Object> args = new ArrayList<>(List.of(userId, CHAT_EVENT));
    if (from != null) {
        sql.append(" AND created_at >= ?");
        args.add(Timestamp.from(from));
    }
    if (until != null) {
        sql.append(" AND created_at < ?");
        args.add(Timestamp.from(until));
    }
    return jdbc.queryForObject(sql.toString(), Integer.class, args.toArray());
}
```

Keep the existing seven-day count as `countSince(UUID, Instant)` and keep `nextFreeAt(...)`
exactly as it is — it serves the fallback and nothing else now. `resolveLimit` takes the window and
uses `window.limit()` in place of `limitFor(plan)`; `limitFor(Plan)` is removed along with its
switch, since the window now decides the tier-level number. Update the class javadoc: the window is
a gameweek, not a week.

**Step 4 — run.** `./gradlew test --tests '*UsageServiceTest' --tests '*UnlimitedDefaultQuotaTest'`

**Step 5 — commit.** `Count the quota inside the gameweek window (KAR-61)`

---

### Task 4: the 429's message and `Retry-After`

**Files:**
- Modify: `backend/src/main/java/fi/karivanttinen/fplassistant/user/QuotaExceededException.java`
- Modify: `backend/src/test/java/fi/karivanttinen/fplassistant/chat/ChatControllerTest.java`

**Interfaces consumed:** `Quota.window()` (Task 3).

**Step 1 — failing test.** In `ChatControllerTest`, beside the existing 429 test, assert the
gameweek wording and that `Retry-After` points at the window's end. Seed 38 deadlines for the
current season with the next one a known distance away, exactly as Task 3's `seedDeadlines` does
(copy the helper; the two suites are independent and sharing it through a base class couples them
for two methods). Assert the body contains `"messages for gameweek "` and that `Retry-After` is
within a minute of the seconds to that deadline.

**Step 2 — run, expect failure** on the body text.

**Step 3 — implement.** `message(quota)` branches on the window:

```java
private static String message(UsageService.Quota quota) {
    if (quota.limit() != null && quota.limit() == 0) {
        return "This account cannot send messages.";
    }
    if (quota.plan() != Plan.FREE || quota.limit() == null) {
        return "You have used this period's messages.";
    }
    return switch (quota.window().kind()) {
        case GAMEWEEK -> "You have used all " + quota.limit() + " of your messages for gameweek "
                + quota.window().gameweek() + ".";
        case OFF_SEASON -> "You have used all " + quota.limit() + " of your off-season messages.";
        case FALLBACK -> "You have used all " + quota.limit() + " of this week's messages.";
    };
}
```

Update the class javadoc's first line — it says "weekly message quota".

**Step 4 — run.** `./gradlew test --tests '*ChatControllerTest'`

**Step 5 — commit.** `Name the window in the quota refusal (KAR-61)`

---

### Task 5: `/api/usage` response

**Files:**
- Modify: `backend/src/main/java/fi/karivanttinen/fplassistant/user/UsageController.java`
- Modify: `backend/src/test/java/fi/karivanttinen/fplassistant/user/UsageControllerTest.java`

**Interfaces produced (the JSON the frontend consumes in Task 6):**
```json
{ "plan": "FREE", "remainingPercent": 40, "unlimited": false, "used": 12, "limit": 20,
  "window": { "kind": "GAMEWEEK", "gameweek": 13, "season": "2026/27" },
  "resetsAt": "2026-09-26T10:30:00Z", "nextFreeAt": null }
```

**Step 1 — failing test.** Assert, for a seeded gameweek window, that `window.kind` is `GAMEWEEK`,
`window.gameweek` is the next round, `resetsAt` is the next deadline, and `nextFreeAt` is absent
while messages remain. Assert for the fallback that `window.kind` is `FALLBACK` and `resetsAt` is
absent. Assert an unlimited account still carries no `used`/`limit`.

**Step 2 — run, expect failure.**

**Step 3 — implement.**

```java
/**
 * @param window    which period the numbers describe. The frontend needs it to choose its
 *                  wording: a rolling fallback has no deadline to name, and naming one anyway
 *                  would be the page inventing a fact
 * @param resetsAt  when the count returns to zero, absent on a fallback (nothing resets) and on
 *                  an off-season window whose next season has not been captured
 * @param nextFreeAt when the caller may send again, absent while messages remain
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UsageResponse(Plan plan, int remainingPercent, boolean unlimited,
                            Instant nextFreeAt, Integer used, Integer limit,
                            WindowResponse window, Instant resetsAt) {}

@JsonInclude(JsonInclude.Include.NON_NULL)
public record WindowResponse(QuotaWindow.Kind kind, Integer gameweek, String season) {}
```

`usage(...)` maps `quota.window()` into `WindowResponse` and passes `quota.resetsAt()`. The
`statesTheNumbers` gate and `remainingPercent` are untouched.

**Step 4 — run.** `./gradlew test --tests '*UsageControllerTest'`

**Step 5 — commit.** `Send the quota window to the client (KAR-61)`

---

### Task 6: the usage page

**Files:**
- Modify: `frontend/src/types.ts:69`
- Modify: `frontend/src/components/UsagePage/UsagePage.tsx`
- Test: `frontend/src/components/UsagePage/UsagePage.test.ts` (create)

**Interfaces consumed:** the JSON in Task 5.

**Step 1 — types.**

```ts
export type QuotaWindowKind = 'GAMEWEEK' | 'OFF_SEASON' | 'FALLBACK';

export interface QuotaWindow {
  kind: QuotaWindowKind;
  gameweek?: number;
  season?: string;
}

export interface Usage {
  plan: 'FREE';
  remainingPercent: number;
  unlimited: boolean;
  nextFreeAt: string | null;
  used?: number;
  limit?: number;
  window: QuotaWindow;
  resetsAt?: string;
}
```

**Step 2 — failing test.** `UsagePage.test.ts` mocks `apiFetch` and asserts the three wordings.
Follow `AdminPage.test.ts` for the existing mocking and rendering idiom in this repo.

```ts
it('names the deadline the gameweek window ends at', async () => {
  mockUsage({ plan: 'FREE', remainingPercent: 40, unlimited: false, nextFreeAt: null,
    used: 12, limit: 20, window: { kind: 'GAMEWEEK', gameweek: 13, season: '2026/27' },
    resetsAt: '2026-09-26T10:30:00Z' });

  render(UsagePage);

  expect(await screen.findByText(/messages for gameweek 13 remaining/)).toBeTruthy();
  expect(screen.getByText(/12 of 20 messages used/)).toBeTruthy();
  expect(screen.getByText(/Resets at the gameweek 13 deadline/)).toBeTruthy();
  expect(screen.queryByText(/this week/)).toBeNull();
});

it('says off-season when there is no round to name', async () => { /* kind OFF_SEASON, limit 100 */ });
it('falls back to the rolling week when the schedule is unreadable', async () => { /* kind FALLBACK */ });
```

**Step 3 — run, expect failure.** `cd frontend && npx vitest run src/components/UsagePage`

**Step 4 — implement.** The wording is chosen from `window.kind` in one place, so the three
strings sit next to each other and cannot drift:

```tsx
/* Never "this gameweek": during the window [GW12 deadline, GW13 deadline) the matches being
   played are GW12's, which GameweekTools calls the current round, while the window is the
   run-up to GW13. A deadline is one instant and reads only one way. */
const wording = {
  GAMEWEEK: {
    lede: `of your messages for gameweek ${usage.window.gameweek} remaining`,
    label: `Messages remaining for gameweek ${usage.window.gameweek}`,
    reset: (at: string) =>
      `Resets at the gameweek ${usage.window.gameweek} deadline on ${new Date(at).toLocaleString()}.`,
  },
  OFF_SEASON: {
    lede: 'of your off-season messages remaining',
    label: 'Off-season messages remaining',
    reset: (at: string) => `Resets at the first deadline of the new season on ${new Date(at).toLocaleString()}.`,
  },
  FALLBACK: {
    lede: "of this week's messages remaining",
    label: 'Messages remaining this week',
    reset: () => '',
  },
}[usage.window.kind];
```

The count line becomes `{used} of {limit} messages used.` for the fixed windows and
`{used} of {limit} messages used in the last seven days.` for `FALLBACK`. `resetsAt` renders
whenever it is present, not only at zero — a reset a user can see coming is the point of the
change. The existing `nextFreeAt` line stays as it is, gated on `remainingPercent === 0`, which on
a fixed window repeats the reset instant and on the fallback is the only timestamp there is.
`unlimited` keeps "This account has no weekly limit." → "This account has no message limit."

**Step 5 — run.** `npx vitest run src/components/UsagePage && npm run lint && npm run format:check`

**Step 6 — commit.** `Name the quota window on the usage page (KAR-61)`

---

### Task 7: documentation

**Files:**
- Modify: `AGENTS.md` (Conventions)
- Modify: `docs/STATUS.md`

**Step 1 — `AGENTS.md`.** Add one convention paragraph, in the register of the ones around it:

> **The message quota is counted in one gameweek window, `QuotaWindows`.** The window runs from
> the last deadline at or before now to the first deadline strictly after it, cut from every
> captured season's deadlines read as one timeline — the latest season alone cannot see the
> changeover, and a preseason request would count the whole previous season. Both ends present and
> the same season means a gameweek window and the gameweek allowance; anything else is the
> off-season window and its own, larger one, as a single window for the whole break.
> **The capture's freshness is deliberately not checked**, unlike `HorizonFixtures`: the schedule
> sync refuses to write all summer, so requiring it would abolish the off-season window, and a
> deadline that moved late shifts a quota boundary by hours where a moved fixture makes a horizon
> meaningless. Only the latest season's events must be a clean 1..38. Without a usable capture the
> count falls back to the rolling seven days on the untouched old path. Nothing user-facing says
> "this gameweek" — the round being played during a window is the previous one — so the replies and
> the page name the deadline.

**Step 2 — `docs/STATUS.md`.** One entry: what changed, that the off-season allowance is a single
window, the `QUOTA_FREE_MESSAGES_PER_WEEK` → `QUOTA_FREE_MESSAGES_PER_GAMEWEEK` rename and that
the Fly secret must be re-set if it exists, and that counts restart from the last deadline on
deploy.

**Step 3 — commit.** `Record the gameweek quota window convention (KAR-61)`

---

## Finishing

Push `feat/kar-61-quota-gameweek-window` and open a PR against `master` with
`Closes KAR-61`. Never push to `master`. The PR's GitHub Actions run is the full-suite gate; a red
run blocks the ticket.
