# KAR-32 — PickTools reads price and availability from the daily capture: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Progress is tracked on the single `**PROGRESS:**` line below, per `AGENTS.md` — no per-step checkboxes.

**PROGRESS:** Task 0/5 done

**Goal:** `getDifferentialPicks` and `getCaptainCandidates` build their candidate pool — identity, club, price, ownership, availability, news — from `fpl_daily_price`, never from the weekly tapas `player` table, and say which day's capture the prices came from.

**Architecture:** The pool CTE in `PickTools.candidates` switches from `player` to the daily table at its latest capture, exactly as `squad/CandidatePool` already does. Metrics still come from `player_fixture_stats` and are attached by element id when the capture and the resolved season are the same season, and by unique `web_name` when they are not (the changeover: the capture is already the new season, the resolver still names the completed one, and FPL has renumbered every player in between). Provenance becomes two lines — the capture's, then the metrics' — the documented shape `SquadTools` and the my-team tools already use. When no capture exists the tool refuses, like `SquadTools`, rather than silently reverting to the table the ticket is about.

**Tech Stack:** Spring Boot 4.1, Java 25, `JdbcTemplate`, JUnit 5 + AssertJ against the disposable test container (`scripts/test-db.sh`).

**Spec:** Linear KAR-32 (https://linear.app/quantti/issue/KAR-32), section "What has to be worked out". `AGENTS.md` conventions on provenance and on `fpl_daily_price`.

## Global Constraints

- `fpl_daily_price.element_id` is season-scoped; every read filters on `season` too (`AGENTS.md`).
- Tests seed what they assert on; tapas tables only through `TapasSeed`; daily-price rows cleaned up by element id, never by season code (other classes share the table).
- The two ranking tools carry **one** `SeasonContext` banner; the previous season is named per pick and in the footer, never on a second `COMPLETED PREVIOUS` line (`AGENTS.md`). This plan adds the *capture* line above that banner, which describes a different source, not a second season.
- No `@Tool` parameter identifies an account; unchanged.
- Commit subjects ≤ ~60 chars, imperative; PR body ≤ 8 lines.

## Decisions taken here (flag to the owner if disagreed)

1. **No fallback to the tapas `player` pool when the capture is empty.** The ticket says "the pool should not empty out"; this plan refuses instead, with the same sentence `SquadTools` uses. A pool built from a table measured 85 days stale *is* the bug, and labelling it does not make a wrong price right. The capture runs daily and `DailyPriceFreshnessCheck` alerts when it misses, so "no capture" is a first-day-in-production state, not an operating one. Flipping this later is one branch in `candidates`.
2. **The captain tie-break moves from `total_points` to blended minutes (`mins`).** The capture does not carry `total_points`, and adding a column to a time series for a tie-break is the wrong trade. `total_points ≈ pts90 × minutes`, so what the old tie-break rewarded between two equal Scores was playing time — the nailed starter over the rotation option — and `mins` (already blended across seasons and on every row, it is what the "Mins" column prints) keeps exactly that intent. `Pts/90` would not: it is a rate, and it is already inside the Score.
3. **`selected_by_percent` comes from the capture** — it is there (`DailyPriceSchema`), captured daily, so the ticket's open question resolves to "yes, and no staleness note needed".

## File structure

| File | Change |
|---|---|
| `tools/DailyPriceSource.java` | Add `record Capture(String season, LocalDate date)` and `Optional<Capture> latestCapture()`. `CandidatePool` keeps its own copy for now (see follow-ups). |
| `tools/PickTools.java` | Pool from the daily table; id-or-name metric join; two-line provenance; refusal when no capture; tie-break on `pts90`; drop the `(season)` qualifier on price and ownership. |
| `test/.../tools/PickToolsRankingTest.java` | Captain tie-break asserts `mins`. |
| `test/.../tools/PickToolsIntegrationTest.java` | Seeds daily rows for the seeded players; new cases for price source, capture-side availability, refusal, provenance order, name bridge across a capture/resolver mismatch. |
| `test/.../tools/PickToolsCrossSeasonTest.java` | Seeds daily rows for `NewSeasonSeed`'s players under the resolved season. |
| `AGENTS.md`, `prompts/system-prompt.st`, `docs/STATUS.md` | Convention text, model guidance, decision record. |

---

### Task 1: Captain tie-break on blended minutes instead of total points

Independent of the source switch and makes Task 3 smaller. `total_points` is the one pool column the capture does not have; `mins` is on every ranked row already (it is what the `Mins` column prints, summed across the blend).

**Files:**
- Modify: `backend/src/main/java/fi/karivanttinen/fplassistant/tools/PickTools.java:147-162` (tool description), `:594-596` (`rank`)
- Test: `backend/src/test/java/fi/karivanttinen/fplassistant/tools/PickToolsRankingTest.java:200-211`

- **Step 1: Change the failing test.** In `PickToolsRankingTest.equalScoresAndFixturesFallBackToMorePointsForCaptains`, replace the two `total_points` puts with `mins` and keep the assertion that the higher one ranks first. Read the existing method first; the shape is:

```java
/** Between two equal Scores the captain pick is the one who plays more — the old total-points
 *  tie-break rewarded the same thing, since total points is rate times minutes. */
@Test
void equalScoresAndFixturesFallBackToMoreMinutesForCaptains() {
    Map<String, Object> fewer = row();
    fewer.put("mins", 450.0);
    Map<String, Object> more = row();
    more.put("mins", 900.0);
    List<Map<String, Object>> ranked = PickTools.rank(List.of(fewer, more), Map.of(), false, 0.0);
    assertThat(ranked.getFirst()).isSameAs(more);
}
```

`mins` is not read by `score()`, so the two rows tie on Score whatever weight is passed; `0.0` is used only to keep the case free of the realised term. Remove `row.put("total_points", 50)` from the `row()` helper; if `row()` already sets `mins`, the two puts above override it.

- **Step 2: Run it.**

Run: `cd backend && set -a && source .env.test && set +a && ./gradlew test --tests '*PickToolsRankingTest*'`
Expected: the renamed test FAILS (the tie-break still reads `total_points`, absent on both rows → `numeric(null)` = 0 → order is insertion order → `fewer` first).

- **Step 3: Implement.** In `rank`:

```java
Comparator<Map<String, Object>> tieBreak = ownershipTieBreak
        ? Comparator.comparingDouble(r -> numeric(r.get("selected_by_percent")))
        : Comparator.comparingDouble((Map<String, Object> r) -> numeric(r.get("mins"))).reversed();
```

In the `getCaptainCandidates` description, change "then total points, each shown with ownership" to "then minutes played, each shown with ownership". In `footer`, change `"ownership" : "total points"` to `"ownership" : "minutes played"`.

- **Step 4: Run the ranking tests.** Expected: all pass.

- **Step 5: Commit.**

```bash
git add backend/src/main/java/fi/karivanttinen/fplassistant/tools/PickTools.java \
        backend/src/test/java/fi/karivanttinen/fplassistant/tools/PickToolsRankingTest.java
git commit -m "refactor: break captain ties on minutes, not total points"
```

---

### Task 2: `DailyPriceSource.latestCapture()`

**Files:**
- Modify: `backend/src/main/java/fi/karivanttinen/fplassistant/tools/DailyPriceSource.java`
- Test: `backend/src/test/java/fi/karivanttinen/fplassistant/tools/DailyPriceSourceCaptureTest.java` (new)

**Interfaces:**
- Produces: `public record Capture(String season, LocalDate date)` nested in `DailyPriceSource`; `public Optional<Capture> latestCapture()`.

- **Step 1: Failing test.** The table is shared with other classes, so the assertion is "at least as new as what I inserted", not equality.

```java
package fi.karivanttinen.fplassistant.tools;

import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class DailyPriceSourceCaptureTest {

    private static final String SEASON = "T-2098-99";
    private static final int ELEMENT = 9901;

    @Autowired JdbcTemplate jdbc;
    @Autowired DailyPriceSource source;
    @Value("${fpl.daily-price.table-name}") String table;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM " + table + " WHERE season = ? AND element_id = ?", SEASON, ELEMENT);
    }

    @Test
    void theLatestCaptureIsTheNewestDateInTheTable() {
        LocalDate day = LocalDate.now().plusYears(50); // newer than anything another class seeds
        jdbc.update("INSERT INTO " + table + " (season, price_date, element_id, web_name, full_name,"
                + " team_short, team_name, element_type, now_cost, status, news)"
                + " VALUES (?, ?, ?, 'Capturetest', 'Capturetest Fictitious', 'TST', 'Testville', 3, 50, 'a', '')",
                SEASON, day, ELEMENT);

        Optional<DailyPriceSource.Capture> latest = source.latestCapture();

        assertThat(latest).isPresent();
        assertThat(latest.get().date()).isEqualTo(day);
        assertThat(latest.get().season()).isEqualTo(SEASON);
    }
}
```

- **Step 2: Run it.** Expected: compile error — `Capture` / `latestCapture` do not exist.

- **Step 3: Implement** in `DailyPriceSource`, below `latestCaptureDate()`:

```java
/** The season and date of the most recent capture, read from the capture itself. */
public record Capture(String season, LocalDate date) {
}

/**
 * The most recent capture, or empty when nothing has been captured yet. Read from the table
 * rather than resolved through {@code SeasonResolver}: during the changeover the resolver still
 * names the completed season while the capture already carries the new one, and a reader that
 * filtered the daily table on the resolver's season would find nothing at all.
 */
public Optional<Capture> latestCapture() {
    List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT season, price_date FROM " + table + " ORDER BY price_date DESC LIMIT 1");
    if (rows.isEmpty()) {
        return Optional.empty();
    }
    Map<String, Object> row = rows.getFirst();
    return Optional.of(new Capture((String) row.get("season"),
            ((java.sql.Date) row.get("price_date")).toLocalDate()));
}
```

Add `import java.util.Optional;`.

- **Step 4: Run.** Expected: PASS.

- **Step 5: Commit.**

```bash
git add backend/src/main/java/fi/karivanttinen/fplassistant/tools/DailyPriceSource.java \
        backend/src/test/java/fi/karivanttinen/fplassistant/tools/DailyPriceSourceCaptureTest.java
git commit -m "feat: expose the latest daily capture from DailyPriceSource"
```

---

### Task 3: The pool comes from the capture; provenance is two lines

The core change. Write the tests first — they define what "from the capture" means.

**Files:**
- Modify: `backend/src/main/java/fi/karivanttinen/fplassistant/tools/PickTools.java`
- Test: `backend/src/test/java/fi/karivanttinen/fplassistant/tools/PickToolsIntegrationTest.java`

**Interfaces:**
- Consumes: `DailyPriceSource.latestCapture()`, `DailyPriceSource.provenance(String, LocalDate, LocalDate)` (both public), `SeasonResolver.metricSeasons`.
- Produces: no new public API. Reply shape: line 1 `Data source: FPL live prices for season …`, line 2 `Data source: season …`, blank line, the picks.

- **Step 1: Rewrite the test class's seeding.** Read the whole existing class first (it is ~150 lines; the tail after line 120 updates a tapas `player` row's `status` to prove exclusion — that case moves to the daily table below). Replace the seeding and add the helpers:

```java
private static final LocalDate TODAY = LocalDate.now();
private static final int[] SEEDED = {9201, 9202, 9203, 9204, 9205, 9206, 9207, 9208};

@Autowired JdbcTemplate jdbc;
@Autowired PickTools tools;
@Autowired SeasonResolver seasonResolver;
@Value("${fpl.daily-price.table-name}") String table;

/** The resolved season's code, which the capture rows must carry for the id join to apply. */
String season;

@BeforeEach
void seed() {
    TapasSeed.ensure(jdbc);
    clearDefensiveContribution();
    season = seasonResolver.resolve().code();
    deleteDailyRows();
    // TapasSeed's players under their own element ids, so metrics attach by id. Okafor is priced
    // differently from the tapas row (82 vs 75) so a test can tell which table a price came from.
    daily(9201, "Okafor",    9001, "ASH", 3, 82, 12.50, "a", null);
    daily(9202, "Bramley",   9002, "NMC", 3, 55,  3.20, "a", null);
    daily(9203, "Vasquez",   9001, "ASH", 4, 96, 25.00, "a", null);
    daily(9204, "Halvorsen", 9003, "NMU", 4, 84,  2.00, "i", "Knee injury - expected back late September");
    daily(9205, "Ferreira",  9003, "NMU", 2, 45,  8.00, "a", null);
    daily(9206, "Kaminski",  9004, "SEL", 1, 45,  6.00, "a", null);
    daily(9207, "Novak",     9005, "WBA", 3, 60, 50.00, "a", null);
    daily(9208, "Halloran",  9003, "NMU", 2, 45,  4.00, "a", null);
}

@AfterEach
void cleanUp() {
    clearDefensiveContribution();
    deleteDailyRows();
}

private void daily(int elementId, String webName, int teamId, String teamShort, int elementType,
                   int nowCost, double ownership, String status, String news) {
    jdbc.update("INSERT INTO " + table + " (season, price_date, element_id, web_name, full_name,"
                    + " team_id, team_short, team_name, element_type, now_cost, cost_change_event,"
                    + " cost_change_start, selected_by_percent, status, news)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?, ?, ?)",
            season, TODAY, elementId, webName, webName + " Fictitious", teamId, teamShort,
            teamShort + " Fictitious FC", elementType, nowCost, ownership, status, news);
}

private void deleteDailyRows() {
    for (int id : SEEDED) {
        jdbc.update("DELETE FROM " + table + " WHERE element_id = ?", id);
    }
}
```

`TEAM_*` short names: `TapasSeed` teams are 9001 ASH, 9002 NMC, 9003 NMU, 9004 SEL, 9005 WBA — the `team_id` must match tapas' so `avgFdrByTeam` (still on the tapas `fixture` table) finds them.

- **Step 2: Add the new cases** (keep the existing ones; they should pass unchanged once seeding is in place, except `unavailablePlayersAreNeverRecommended`, which is replaced by the second case below):

```java
/** The price printed is the capture's, not the weekly table's. */
@Test
void thePriceComesFromTheDailyCapture() {
    String result = tools.getDifferentialPicks("MID", 100, 3);

    assertThat(result).contains("Okafor (ASH) £8.2m");
    assertThat(result).doesNotContain("£7.5m");
}

/** Flagged in today's capture, fit in the weekly table: the capture wins. */
@Test
void aPlayerFlaggedInTheCaptureIsExcludedWhateverTheWeeklyTableSays() {
    jdbc.update("UPDATE " + table + " SET status = 'i', news = 'Hamstring' WHERE element_id = 9203");

    String result = tools.getCaptainCandidates(3);

    assertThat(result).doesNotContain("Vasquez");
    assertThat(result).contains("Okafor");
}

@Test
void aDoubtfulPlayerIsListedWithTheCapturesNews() {
    jdbc.update("UPDATE " + table + " SET status = 'd', news = 'Knock - 75% chance of playing'"
            + " WHERE element_id = 9201");

    String result = tools.getDifferentialPicks("MID", 100, 3);

    assertThat(result).contains("Okafor").contains("DOUBTFUL: Knock - 75% chance of playing");
}

@Test
void provenanceIsTheCaptureLineThenTheMetricsBanner() {
    String result = tools.getCaptainCandidates(3);
    String[] lines = result.split("\n");

    assertThat(lines[0]).startsWith("Data source: FPL live prices for season " + season)
            .contains("captured today");
    assertThat(lines[1]).startsWith("Data source: season " + season);
}

/**
 * With nothing captured the tool says so rather than reading the weekly table. A stubbed source
 * rather than an emptied table: the daily-price table is shared by every class in the suite,
 * and a blanket DELETE would be the one thing here that could break another test.
 */
@Test
void refusesWhenNoCaptureExists() {
    DailyPriceSource empty = Mockito.mock(DailyPriceSource.class);
    Mockito.when(empty.latestCapture()).thenReturn(Optional.empty());
    PickTools withoutCapture = new PickTools(jdbc, seasonResolver, empty, table);

    String result = withoutCapture.getCaptainCandidates(3);

    assertThat(result).contains("No player prices have been captured yet");
    assertThat(result).doesNotContain("Captain candidates");
}
```

(`import org.mockito.Mockito;`, `import java.util.Optional;` — Mockito is already on the test classpath; `SignupControllerTest` uses it.)

```java

/**
 * The changeover: the capture already carries the new season under new element ids, while the
 * resolver still names the completed one. Metrics attach by unique name, and both lines say
 * which season they are.
 */
@Test
void aCaptureFromANewerSeasonIsBridgedToTheResolvedSeasonByName() {
    String captureSeason = "T-2099-00";
    int newOkafor = 9601;
    int newBramley = 9602;
    try {
        jdbc.update("INSERT INTO " + table + " (season, price_date, element_id, web_name, full_name,"
                + " team_id, team_short, team_name, element_type, now_cost, cost_change_event,"
                + " cost_change_start, selected_by_percent, status, news) VALUES"
                + " (?, ?, ?, 'Okafor', 'Okafor Fictitious', 9001, 'ASH', 'Ashfield', 3, 90, 0, 0, 10.0, 'a', NULL),"
                + " (?, ?, ?, 'Bramley', 'Bramley Fictitious', 9002, 'NMC', 'Northmoor', 3, 60, 0, 0, 2.0, 'a', NULL)",
                captureSeason, TODAY.plusDays(1), newOkafor,
                captureSeason, TODAY.plusDays(1), newBramley);

        String result = tools.getDifferentialPicks("MID", 100, 3);

        assertThat(result).startsWith("Data source: FPL live prices for season " + captureSeason);
        assertThat(result).contains("Data source: season " + season + " — a COMPLETED PREVIOUS season");
        assertThat(result).contains("Okafor (ASH) £9.0m");
        assertThat(result).containsPattern("Okafor .*xG/90 [0-9]\\.[0-9]{2}");
    } finally {
        jdbc.update("DELETE FROM " + table + " WHERE season = ?", captureSeason);
    }
}
```

- **Step 3: Run the class.** Expected: the new cases FAIL (prices still `£7.5m`, no capture line, no refusal).

- **Step 4: Implement in `PickTools`.**

Constructor and fields:

```java
private final JdbcTemplate jdbc;
private final SeasonResolver seasonResolver;
private final DailyPriceSource daily;
private final String dailyTable;

public PickTools(JdbcTemplate jdbc, SeasonResolver seasonResolver, DailyPriceSource daily,
                 @Value("${fpl.daily-price.table-name}") String dailyTable) {
    this.jdbc = jdbc;
    this.seasonResolver = seasonResolver;
    this.daily = daily;
    this.dailyTable = dailyTable;
}
```

(`import org.springframework.beans.factory.annotation.Value;`, `import java.time.LocalDate;`, `import java.util.Optional;`.)

Both tool methods, after `SeasonContext season = seasonResolver.resolve();`:

```java
Optional<DailyPriceSource.Capture> captured = daily.latestCapture();
if (captured.isEmpty()) {
    return NO_CAPTURE;
}
DailyPriceSource.Capture capture = captured.get();
String provenance = DailyPriceSource.provenance(capture.season(), capture.date(), LocalDate.now())
        + "\n" + season.provenance();
```

with

```java
/** Same sentence as SquadTools: a pool from the weekly table is the failure KAR-32 is about. */
private static final String NO_CAPTURE = "No player prices have been captured yet, so I cannot"
        + " rank picks. Tell the user the daily price capture has not run.";
```

Every `season.provenance() + ...` return in the two tool methods becomes `provenance + ...`. Pass `capture` into `candidates(...)` and `poolSize(...)`.

`candidates`:

```java
private List<Map<String, Object>> candidates(DailyPriceSource.Capture capture, SeasonContext season,
                                             MetricSeasons metric, int elementType, double maxOwnership) {
    // Same season on both sides: FPL's element id is the join. Different seasons — the capture
    // has moved on and the resolver has not — and the id names a different player, so the join
    // is the name, and only where the name is unique on both sides.
    boolean sameSeason = capture.season().equals(season.code());
    String metricJoin = sameSeason
            ? "c.player_id = pool.id"
            : "c.k = LOWER(pool.web_name) AND c.unique_name AND pool.name_count = 1";

    List<Map<String, Object>> rows = jdbc.queryForList("""
            WITH pool AS (
                SELECT d.element_id AS id, d.web_name, d.element_type, d.now_cost,
                       d.selected_by_percent, d.team_id, d.team_short, d.status, d.news,
                       COUNT(*) OVER (PARTITION BY LOWER(d.web_name)) AS name_count
                FROM %s d
                WHERE d.season = ? AND d.price_date = ?
                  AND d.status IN ('a', 'd')
                  AND d.selected_by_percent <= ?
                  %s
            ),
            recent AS (
                -- unchanged from today
            ),
            windowed AS (
                -- unchanged from today
            ),
            cur AS (
                SELECT w.*, LOWER(mp.web_name) AS k, %s AS unique_name
                FROM windowed w
                JOIN player mp ON mp.id = w.player_id AND mp.season_id = w.season_id
                WHERE w.season_id = ?
            ),
            prev AS (
                -- unchanged from today
            )
            SELECT pool.*,
                   c.matches60 AS cur_matches60, c.mins AS cur_mins, c.matches AS cur_matches,
                   c.xg90 AS cur_xg90, c.xa90 AS cur_xa90, c.pts90 AS cur_pts90,
                   c.xgc_per_match AS cur_xgc_per_match,
                   c.defcon_matches AS cur_defcon_matches, c.defcon_avg AS cur_defcon_avg,
                   pv.matches60 AS prev_matches60, pv.mins AS prev_mins,
                   pv.matches AS prev_matches, pv.xg90 AS prev_xg90, pv.xa90 AS prev_xa90,
                   pv.pts90 AS prev_pts90, pv.xgc_per_match AS prev_xgc_per_match,
                   pv.defcon_matches AS prev_defcon_matches, pv.defcon_avg AS prev_defcon_avg
            FROM pool
            LEFT JOIN cur c ON %s
            LEFT JOIN prev pv ON pv.k = LOWER(pool.web_name) AND pool.name_count = 1
            """.formatted(dailyTable, positionFilter(elementType, "d"),
                    metric.windowLimit(), ExpectedPoints.WHOLE_SEASON,
                    nameIsUnique("mp"), nameIsUnique("mp"), metricJoin),
            capture.season(), capture.date(), maxOwnership,
            season.seasonId(), metric.previousSeasonId(),
            season.seasonId(),
            season.seasonId(),
            metric.previousSeasonId());
    // ranking loop unchanged
}
```

Copy `recent`, `windowed` and `prev` verbatim from the current file — they are not shown here to keep the plan readable, but **they are unchanged**. Check the `%s` order: the `recent`/`windowed` block consumes `%d %d` (window limits) after the pool's position filter; `cur` consumes one `nameIsUnique`; `prev` consumes the other. Check the `?` order against the CTEs top to bottom: pool (3), recent (2), windowed (1), cur (1), prev (1).

`prev`'s old `LEFT JOIN prev pv ON pv.k = LOWER(pool.web_name) AND %s` used `nameIsUnique("pool")`, which read `pool.season_id` and `pool.id` against `player` — the pool is no longer a `player` row, so the uniqueness of the pool side is now `pool.name_count = 1` (the window function), the same device `CandidatePool` uses.

`poolSize`:

```java
private int poolSize(DailyPriceSource.Capture capture, int elementType, double maxOwnership) {
    Integer pool = jdbc.queryForObject("""
            SELECT count(*) FROM %s d
            WHERE d.season = ? AND d.price_date = ?
              AND d.status IN ('a', 'd')
              AND d.selected_by_percent <= ?
              %s
            """.formatted(dailyTable, positionFilter(elementType, "d")),
            Integer.class, capture.season(), capture.date(), maxOwnership);
    return pool == null ? 0 : pool;
}
```

`formatPicks`: remove the `qual` variable and both `%s` slots that printed it — price and ownership are today's from the capture, whatever season the metrics come from. The format string becomes `"  %s %s (%s) £%sm | Own %s%% | Score %s | ..."` with two fewer arguments. The signature becomes

```java
private String formatPicks(String heading, List<Map<String, Object>> rows,
                           DailyPriceSource.Capture capture, SeasonContext season,
                           MetricSeasons metric, int fixtureLimit, int elementType,
                           double maxOwnership, boolean ownershipTieBreak)
```

and its one use of `poolSize` becomes `poolSize(capture, elementType, maxOwnership)`; both call sites in the tool methods pass `capture` after `rows`.

Class javadoc: replace "Every reply is prefixed with `SeasonContext#provenance()`" with a paragraph in the `SquadTools` style — pool from the daily capture, metrics from `player_fixture_stats`, two provenance lines, and why the join is by id or by name.

- **Step 5: Run the class, then the whole suite.**

Run: `./gradlew test --tests '*PickTools*'` then `./gradlew test`
Expected: all green. If `PickToolsCrossSeasonTest` fails with an empty pool, that is Task 4 — do it before the full run.

- **Step 6: Commit.**

```bash
git add backend/src/main/java/fi/karivanttinen/fplassistant/tools/PickTools.java \
        backend/src/test/java/fi/karivanttinen/fplassistant/tools/PickToolsIntegrationTest.java
git commit -m "fix: pick tools read price and availability from the daily capture"
```

Body (the *why* is not in the diff): "Closes the source gap KAR-32 describes: the pool is fpl_daily_price at its latest capture, metrics attach by element id in-season and by unique name across the changeover, and no capture means a refusal, not the weekly table."

---

### Task 4: Cross-season test seeds the capture too

**Files:**
- Modify: `backend/src/test/java/fi/karivanttinen/fplassistant/tools/PickToolsCrossSeasonTest.java`

`NewSeasonSeed.begin` inserts current-season `player` rows 9401 Okafor, 9402 Vasquez, 9403 Ferreira, 9404 Rask, 9405 Rask (all team 9001) and makes the resolver prefer the new season. The pool now comes from the capture, so those five need capture rows under the resolved code.

- **Step 1: Add seeding and cleanup.**

```java
private static final LocalDate TODAY = LocalDate.now();
private static final int[] NEW_SEASON_ELEMENTS = {9401, 9402, 9403, 9404, 9405};

@Value("${fpl.daily-price.table-name}") String table;
@Autowired SeasonResolver seasonResolver;

@BeforeEach
void seed() {
    TapasSeed.ensure(jdbc);
    NewSeasonSeed.begin(jdbc);
    String season = seasonResolver.resolve().code();   // the new season, once begin() has run
    deleteDailyRows();
    daily(season, 9401, "Okafor",   3, 80, 4.0);
    daily(season, 9402, "Vasquez",  4, 96, 6.0);
    daily(season, 9403, "Ferreira", 2, 45, 5.0);
    daily(season, 9404, "Rask",     3, 55, 3.0);
    daily(season, 9405, "Rask",     2, 45, 2.0);
}

@AfterEach
void endTheNewSeason() {
    deleteDailyRows();
    NewSeasonSeed.end(jdbc);
}

private void daily(String season, int elementId, String webName, int elementType, int nowCost,
                   double ownership) {
    jdbc.update("INSERT INTO " + table + " (season, price_date, element_id, web_name, full_name,"
                    + " team_id, team_short, team_name, element_type, now_cost, cost_change_event,"
                    + " cost_change_start, selected_by_percent, status, news)"
                    + " VALUES (?, ?, ?, ?, ?, 9001, 'ASH', 'Ashfield Rovers', ?, ?, 0, 0, ?, 'a', NULL)",
            season, TODAY, elementId, webName, webName + " Fictitious", elementType, nowCost, ownership);
}

private void deleteDailyRows() {
    for (int id : NEW_SEASON_ELEMENTS) {
        jdbc.update("DELETE FROM " + table + " WHERE element_id = ?", id);
    }
}
```

- **Step 2: Adjust one assertion.** `theFooterAndEachPickNameThePreviousSeasonAndItsWeight` asserts `doesNotContain("Data source: season " + POPULATED_SEASON_CODE)` — still true. It should additionally assert the capture line comes first:

```java
assertThat(result).startsWith("Data source: FPL live prices for season " + TapasSeed.CURRENT_SEASON_CODE);
```

- **Step 3: Run the class.** Expected: all six pass — including `anAmbiguousNameIsDroppedRatherThanGivenAnotherPlayersRecord`, now via `pool.name_count = 2` for the two Rasks.

- **Step 4: Commit.**

```bash
git add backend/src/test/java/fi/karivanttinen/fplassistant/tools/PickToolsCrossSeasonTest.java
git commit -m "test: seed the daily capture in the pick tools' cross-season case"
```

---

### Task 5: Conventions, prompt, status, PR

**Files:**
- Modify: `AGENTS.md:40-47` (the two-ranking-tools sentence), `AGENTS.md` daily-capture bullet
- Modify: `backend/src/main/resources/prompts/system-prompt.st` (enrichment tools section)
- Modify: `docs/STATUS.md` (append)

- **Step 1: AGENTS.md.** In the stats-tools bullet, the sentence beginning **"The two ranking tools (`getDifferentialPicks`, `getCaptainCandidates`) blend both seasons per player and carry one banner only"** gains a clause: "…carry one *season* banner only — above it sits the daily capture's line, `DailyPriceSource.provenance()`, because their pool (identity, club, price, ownership, availability) is `fpl_daily_price` since KAR-32 and their metrics are `player_fixture_stats`; the two lines describe two sources, not two seasons." In the `fpl_daily_price` bullet, add `PickTools` to the list of readers beside `SquadTools.buildSquad`, with the id-or-name join rule in one sentence.

- **Step 2: System prompt.** In the enrichment-tools section, the paragraph "Check availability before recommending a transfer in, or a captain … the player table behind getPlayerStats is synced far less often" — add: "getDifferentialPicks, getCaptainCandidates and buildSquad read the same daily capture for price and availability, so their flags agree with getPlayerAvailability; getPlayerStats does not."

- **Step 3: STATUS.md.** Append `## Pick tools read the daily capture (KAR-32, 2026-MM-DD)`: the three decisions from the top of this plan, the id-or-name join and why, and the follow-ups below.

- **Step 4: Full suite, then PR.** `./gradlew test` green; push the branch; PR body ≤ 8 lines linking this plan and KAR-32. Linear: comment with the PR link; Done after merge.

**Done when:** suite green, PR merged, KAR-32 Done, and a production check: ask the deployed assistant for captain candidates and confirm the reply starts with `Data source: FPL live prices for season 2026-27, captured today`.

---

## Follow-ups found while planning (not in this plan)

- **`squad/CandidatePool.load` has the changeover gap this plan closes for `PickTools`:** it joins current-season metrics `ON c.player_id = pool.element_id` regardless of whether the capture's season is the resolver's. During the weeks the capture is the new season and the resolver still names the completed one, that attaches the completed season's numbers to whichever *new* player inherited the id. Filed as a Linear bug; the fix is the same `sameSeason ? id : name` clause. `CandidatePool.latestCapture()` should then delegate to `DailyPriceSource.latestCapture()`.
- `PickTools.avgFdrByTeam` still reads the tapas `fixture` table; KAR-10 gives it an FPL-sourced alternative.

## Self-review notes

- Ticket coverage: season-scoped join ✔ (Task 3, `season = ?` on the pool); ownership ✔ (decision 3); provenance ✔ (two lines, Task 3 + docs); empty capture ✔ (decision 1, refusal, tested).
- Placeholder scan: the CTE bodies marked "unchanged from today" are the one deliberate elision; they are copied verbatim, and the `%s`/`?` counting instructions are there so the copy cannot silently misalign.
- Type consistency: `DailyPriceSource.Capture(season, date)` is the record used in Tasks 2, 3 and 4; `candidates(capture, season, metric, elementType, maxOwnership)` and `poolSize(capture, elementType, maxOwnership)` are the signatures used throughout Task 3.
