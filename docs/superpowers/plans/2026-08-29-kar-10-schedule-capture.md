# KAR-10 — Gameweeks and fixtures from FPL's own API, independent of tapas: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Progress is tracked on the single `**PROGRESS:**` line below, per `AGENTS.md` — no per-step checkboxes.

**PROGRESS:** Task 0/7 done

**Goal:** `getGameweekInfo` and `getUpcomingFixtures` answer for the season being played — deadlines, current/next gameweek, upcoming fixtures with FDR and kickoff — from a daily capture of FPL's own `bootstrap-static.events` and `/api/fixtures/`, so they keep working across the season changeover and whenever tapas' weekly sync lags.

**Architecture:** Two new snapshot tables, `fpl_gameweek` and `fpl_fixture`, one row per gameweek / fixture per season, upserted daily by a `ScheduleSyncService` in the same shape as `DailyPriceSyncService` (own season code from the UK date, own team names from the payload, owes tapas nothing). A `ScheduleSource` reads the latest capture and supplies its provenance line. `GameweekTools` and `FixtureTools` read the capture when it exists and fall back to their present tapas queries when it does not, so the existing tests and the pre-first-capture state keep working. Gameweek status is **derived from deadlines at the time of asking** (`deadline_time <= now()`), not from FPL's `is_current` flag frozen at 02:10 — a deadline is the one fact a manager must not be told a day late.

**Tech Stack:** Spring Boot 4.1, Java 25, `RestClient`, `JdbcTemplate`, `TransactionTemplate`, `@Scheduled`; JUnit 5 + AssertJ; Jackson for the JSON test fixtures.

**Spec:** Linear KAR-10 (https://linear.app/quantti/issue/KAR-10). The pattern is `docs/superpowers/specs/2026-08-15-phase4-enrichment-design.md`'s daily price capture.

## Global Constraints

- New tables are `fpl_`-prefixed; the schema is shared with tapas (`AGENTS.md`). Never alter tapas tables.
- External APIs only at sync time. The tools read Supabase.
- Provenance: the capture path uses its own line (season + capture date), a documented exception like `DailyPriceSource.provenance()`; the tapas path keeps `SeasonContext.provenance()`. `AGENTS.md` gets the exception written down (Task 7).
- Tests seed their own rows and clean them by season code `T-…`; the test profile points both tables at `_test` names; schedulers are off under `test`.
- `DailyPriceSyncService.UK`, `seasonCode(LocalDate)` and `responseBelongsToAFinishedSeason(Map, LocalDate)` become `public static` so the new service reuses them rather than copying the season rule.

## Decisions taken here (flag to the owner if disagreed)

1. **Snapshot, not a dated series.** Prices need history; a fixture list needs to be current. One row per (season, id), overwritten daily, with rows FPL no longer publishes deleted in the same transaction.
2. **Capture first, tapas as fallback**, chosen by whether the capture table has rows — not by comparing seasons. The capture is the fresher, independent source whenever it exists.
3. **`is_current`/`is_next` derived from deadlines at read time.** FPL flips its flag at the deadline; a 02:10 capture would carry yesterday's flag for most of Saturday. The stored flags are kept for reference only.
4. **Scope is the two tools the ticket names.** `FixtureDifficultyTools`, `PickTools.avgFdrByTeam`, `HorizonScorer` and `SquadEnrichment` still read the tapas `fixture` table; moving them is a follow-up once this capture has run for a while.

## File structure

| File | Responsibility |
|---|---|
| `schedule/ScheduleSchema.java` | Idempotent DDL for both tables, RLS on. |
| `schedule/ScheduleSyncService.java` | Fetch `bootstrap-static` + `/api/fixtures/`, map to rows (pure, package-private `toGameweekRows`/`toFixtureRows`), write in one transaction. |
| `schedule/ScheduleScheduler.java` | `@Scheduled` daily at 02:10 UK, gated by `fpl.schedule.scheduled`. |
| `schedule/ScheduleRunner.java` | `--sync-schedule` CLI, exit code 0/1. |
| `schedule/ScheduleSource.java` | `latest()` snapshot and `provenance(...)`; the read-side helper both tools use. |
| `tools/GameweekTools.java`, `tools/FixtureTools.java` | Capture path first, tapas path unchanged as fallback. |
| `resources/application.yml`, `test/resources/application-test.yml` | `fpl.schedule.*` |
| `test/resources/fpl-bootstrap-events-fixture.json`, `fpl-fixtures-fixture.json` | Trimmed real payload shapes. |
| Docs | `AGENTS.md`, `docs/DEVELOPMENT.md`, `docs/STATUS.md`, `prompts/system-prompt.st`. |

---

### Task 1: Schema and configuration

**Files:**
- Create: `backend/src/main/java/fi/karivanttinen/fplassistant/schedule/ScheduleSchema.java`
- Modify: `backend/src/main/resources/application.yml` (after the `daily-price` block), `backend/src/test/resources/application-test.yml`
- Test: `backend/src/test/java/fi/karivanttinen/fplassistant/schedule/ScheduleSchemaTest.java`

**Interfaces:**
- Produces: properties `fpl.schedule.gameweek-table-name`, `fpl.schedule.fixture-table-name`, `fpl.schedule.fixtures-url`, `fpl.schedule.fetch-at`, `fpl.schedule.fetch-zone`, `fpl.schedule.scheduled`. Tables with the columns below.

- **Step 1: Failing test** — the tables exist under the test names with RLS on (same assertion `RowLevelSecurityTest` makes for the other `fpl_` tables; read it and mirror its query):

```java
package fi.karivanttinen.fplassistant.schedule;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ScheduleSchemaTest {

    @Autowired JdbcTemplate jdbc;
    @Value("${fpl.schedule.gameweek-table-name}") String gameweekTable;
    @Value("${fpl.schedule.fixture-table-name}") String fixtureTable;

    @Test
    void bothTablesExistWithRowLevelSecurityOn() {
        assertThat(gameweekTable).endsWith("_test");
        assertThat(fixtureTable).endsWith("_test");
        for (String table : new String[] {gameweekTable, fixtureTable}) {
            Boolean rls = jdbc.queryForObject(
                    "SELECT relrowsecurity FROM pg_class WHERE oid = to_regclass(?)", Boolean.class, table);
            assertThat(rls).as(table).isTrue();
        }
    }
}
```

- **Step 2: Run it.** Expected: context fails to start — the properties do not exist.

- **Step 3: Configuration.** `application.yml`, after `daily-price`:

```yaml
  schedule:
    gameweek-table-name: fpl_gameweek
    fixture-table-name: fpl_fixture
    # FPL's own fixture list: every fixture of the season with event, kickoff, difficulty and
    # scores. Gameweeks come from bootstrap-static's `events` block, already fetched for prices.
    fixtures-url: https://fantasy.premierleague.com/api/fixtures/
    # Ten minutes after the price capture, on the same clock and for the same reason: the
    # machine is up, FPL's overnight jobs have settled. Gameweek status is not read from this
    # capture's flags but derived from deadlines at the time of asking, so the cadence only
    # bounds how old scores and postponements can be.
    fetch-at: "0 10 2 * * *"
    fetch-zone: Europe/London
    scheduled: true
```

`application-test.yml`, beside the other `_test` names and `scheduled: false` lines:

```yaml
  schedule:
    gameweek-table-name: fpl_gameweek_test
    fixture-table-name: fpl_fixture_test
    fixtures-url: https://fantasy.premierleague.com/api/fixtures/
    fetch-at: "0 10 2 * * *"
    fetch-zone: Europe/London
    scheduled: false
```

- **Step 4: Schema.**

```java
package fi.karivanttinen.fplassistant.schedule;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Idempotent DDL for {@code fpl_gameweek} and {@code fpl_fixture}: the season's gameweeks and
 * fixtures as FPL publishes them, captured daily. Same shape as {@code DailyPriceSchema} —
 * every statement {@code IF NOT EXISTS}, table names parameterised so the test profile writes
 * to {@code _test} tables.
 *
 * <p><b>Why these exist.</b> {@code GameweekTools} and {@code FixtureTools} read tapas-owned
 * tables that are synced weekly and empty for weeks across the season changeover, so the
 * assistant had no current-season deadline or fixture until tapas caught up. These are the
 * schedule counterpart of {@code fpl_daily_price}: read straight from FPL, keyed by a season
 * code derived from the date, owing tapas nothing.
 *
 * <p><b>Snapshot, not a series.</b> One row per gameweek and per fixture per season, overwritten
 * on every capture; a fixture FPL stops publishing is deleted in the same transaction. Prices
 * need history, a fixture list needs to be current. {@code is_current}/{@code is_next} are
 * stored as published but the tools derive status from {@code deadline_time} at read time, so a
 * deadline that passed this morning is reported as passed this morning, not tomorrow at 02:10.
 */
@Component
public class ScheduleSchema {

    private final JdbcTemplate jdbc;
    private final String gameweekTable;
    private final String fixtureTable;

    public ScheduleSchema(JdbcTemplate jdbc,
                          @Value("${fpl.schedule.gameweek-table-name}") String gameweekTable,
                          @Value("${fpl.schedule.fixture-table-name}") String fixtureTable) {
        this.jdbc = jdbc;
        this.gameweekTable = gameweekTable;
        this.fixtureTable = fixtureTable;
    }

    @PostConstruct
    void ensureSchema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    season VARCHAR(10) NOT NULL,
                    id INTEGER NOT NULL,
                    name VARCHAR(50),
                    deadline_time TIMESTAMPTZ NOT NULL,
                    finished BOOLEAN NOT NULL DEFAULT false,
                    data_checked BOOLEAN NOT NULL DEFAULT false,
                    is_current BOOLEAN NOT NULL DEFAULT false,
                    is_next BOOLEAN NOT NULL DEFAULT false,
                    average_entry_score INTEGER,
                    highest_score INTEGER,
                    most_captained INTEGER,
                    most_transferred_in INTEGER,
                    captured_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    PRIMARY KEY (season, id)
                )
                """.formatted(gameweekTable));
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    season VARCHAR(10) NOT NULL,
                    id INTEGER NOT NULL,
                    gameweek INTEGER,
                    team_h INTEGER NOT NULL,
                    team_a INTEGER NOT NULL,
                    team_h_name VARCHAR(100),
                    team_h_short VARCHAR(10),
                    team_a_name VARCHAR(100),
                    team_a_short VARCHAR(10),
                    team_h_difficulty SMALLINT,
                    team_a_difficulty SMALLINT,
                    kickoff_time TIMESTAMPTZ,
                    started BOOLEAN NOT NULL DEFAULT false,
                    finished BOOLEAN NOT NULL DEFAULT false,
                    team_h_score INTEGER,
                    team_a_score INTEGER,
                    captured_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    PRIMARY KEY (season, id)
                )
                """.formatted(fixtureTable));
        // Every fixture read is "this team's fixtures from this gameweek on".
        jdbc.execute(("CREATE INDEX IF NOT EXISTS idx_%s_season_gw ON %s(season, gameweek)")
                .formatted(fixtureTable, fixtureTable));
        // Same reason as every other fpl_ table: PostgREST publishes public tables to the anon
        // key, and RLS with no policies is what makes that exposure empty.
        jdbc.execute("ALTER TABLE %s ENABLE ROW LEVEL SECURITY".formatted(gameweekTable));
        jdbc.execute("ALTER TABLE %s ENABLE ROW LEVEL SECURITY".formatted(fixtureTable));
    }
}
```

- **Step 5: Run the test.** Expected: PASS. Also run `RowLevelSecurityTest` — if it enumerates `fpl_` tables by name, add the two new ones to its list.

- **Step 6: Commit.**

```bash
git add backend/src/main/java/fi/karivanttinen/fplassistant/schedule/ScheduleSchema.java \
        backend/src/main/resources/application.yml backend/src/test/resources/application-test.yml \
        backend/src/test/java/fi/karivanttinen/fplassistant/schedule/ScheduleSchemaTest.java
git commit -m "feat: fpl_gameweek and fpl_fixture tables for the schedule capture"
```

---

### Task 2: Mapping the payloads to rows (pure)

**Files:**
- Create: `backend/src/main/java/fi/karivanttinen/fplassistant/schedule/ScheduleSyncService.java` (mapping half)
- Modify: `backend/src/main/java/fi/karivanttinen/fplassistant/price/DailyPriceSyncService.java:47,195,208` — `UK`, `seasonCode`, `responseBelongsToAFinishedSeason` become `public`
- Create: `backend/src/test/resources/fpl-bootstrap-events-fixture.json`, `backend/src/test/resources/fpl-fixtures-fixture.json`
- Test: `backend/src/test/java/fi/karivanttinen/fplassistant/schedule/ScheduleSyncServiceTest.java`

**Interfaces:**
- Produces: `static List<Object[]> toGameweekRows(Map<String,Object> bootstrap, String season)` — columns in the order `season, id, name, deadline_time (OffsetDateTime), finished, data_checked, is_current, is_next, average_entry_score, highest_score, most_captained, most_transferred_in`; `static List<Object[]> toFixtureRows(List<Map<String,Object>> fixtures, Map<Integer, String[]> teams, String season)` — columns `season, id, gameweek, team_h, team_a, team_h_name, team_h_short, team_a_name, team_a_short, team_h_difficulty, team_a_difficulty, kickoff_time (OffsetDateTime or null), started, finished, team_h_score, team_a_score`; `static Map<Integer, String[]> teamsOf(Map<String,Object> bootstrap)` → id → `{name, short_name}`.

- **Step 1: Fixtures.** Real shapes, trimmed. `fpl-bootstrap-events-fixture.json`:

```json
{
  "events": [
    {"id": 1, "name": "Gameweek 1", "deadline_time": "2026-08-14T17:30:00Z", "finished": true,
     "data_checked": true, "is_previous": true, "is_current": false, "is_next": false,
     "average_entry_score": 57, "highest_score": 127, "most_captained": 381, "most_transferred_in": 14},
    {"id": 2, "name": "Gameweek 2", "deadline_time": "2026-08-22T10:00:00Z", "finished": false,
     "data_checked": false, "is_previous": false, "is_current": true, "is_next": false,
     "average_entry_score": null, "highest_score": null, "most_captained": null, "most_transferred_in": null},
    {"id": 3, "name": "Gameweek 3", "deadline_time": "2026-08-29T10:00:00Z", "finished": false,
     "data_checked": false, "is_previous": false, "is_current": false, "is_next": true,
     "average_entry_score": null, "highest_score": null, "most_captained": null, "most_transferred_in": null}
  ],
  "teams": [
    {"id": 1, "name": "Arsenal", "short_name": "ARS"},
    {"id": 2, "name": "Aston Villa", "short_name": "AVL"},
    {"id": 3, "name": "Burnley", "short_name": "BUR"}
  ],
  "elements": []
}
```

`fpl-fixtures-fixture.json`:

```json
[
  {"id": 1, "code": 2561895, "event": 1, "finished": true, "finished_provisional": true, "started": true,
   "kickoff_time": "2026-08-15T14:00:00Z", "minutes": 90, "team_a": 2, "team_a_score": 1,
   "team_h": 1, "team_h_score": 3, "team_h_difficulty": 3, "team_a_difficulty": 4},
  {"id": 12, "code": 2561906, "event": 2, "finished": false, "finished_provisional": false, "started": false,
   "kickoff_time": "2026-08-23T13:00:00Z", "minutes": 0, "team_a": 1, "team_a_score": null,
   "team_h": 3, "team_h_score": null, "team_h_difficulty": 5, "team_a_difficulty": 2},
  {"id": 21, "code": 2561915, "event": null, "finished": false, "finished_provisional": false, "started": false,
   "kickoff_time": null, "minutes": 0, "team_a": 3, "team_a_score": null,
   "team_h": 2, "team_h_score": null, "team_h_difficulty": 2, "team_a_difficulty": 3}
]
```

The third fixture is the postponed shape (`event` and `kickoff_time` null) `FixtureTools.window` already handles.

- **Step 2: Failing tests.**

```java
package fi.karivanttinen.fplassistant.schedule;

import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduleSyncServiceTest {

    private static Map<String, Object> bootstrap() throws Exception {
        try (InputStream in = ScheduleSyncServiceTest.class.getResourceAsStream("/fpl-bootstrap-events-fixture.json")) {
            return new ObjectMapper().readValue(in, new TypeReference<>() { });
        }
    }

    private static List<Map<String, Object>> fixtures() throws Exception {
        try (InputStream in = ScheduleSyncServiceTest.class.getResourceAsStream("/fpl-fixtures-fixture.json")) {
            return new ObjectMapper().readValue(in, new TypeReference<>() { });
        }
    }

    @Test
    void mapsEveryEventToAGameweekRow() throws Exception {
        List<Object[]> rows = ScheduleSyncService.toGameweekRows(bootstrap(), "2026-27");

        assertThat(rows).hasSize(3);
        Object[] gw1 = rows.getFirst();
        assertThat(gw1[0]).isEqualTo("2026-27");
        assertThat(gw1[1]).isEqualTo(1);
        assertThat(gw1[2]).isEqualTo("Gameweek 1");
        assertThat(gw1[3]).isEqualTo(OffsetDateTime.parse("2026-08-14T17:30:00Z"));
        assertThat(gw1[4]).isEqualTo(true);    // finished
        assertThat(gw1[8]).isEqualTo(57);      // average_entry_score
        assertThat(gw1[10]).isEqualTo(381);    // most_captained
    }

    @Test
    void anEventWithoutADeadlineIsSkippedRatherThanWrittenWithoutOne() throws Exception {
        Map<String, Object> body = bootstrap();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) body.get("events");
        events.getFirst().put("deadline_time", null);

        assertThat(ScheduleSyncService.toGameweekRows(body, "2026-27")).hasSize(2);
    }

    @Test
    void mapsFixturesWithTeamNamesFromTheBootstrapTeamsBlock() throws Exception {
        Map<Integer, String[]> teams = ScheduleSyncService.teamsOf(bootstrap());
        List<Object[]> rows = ScheduleSyncService.toFixtureRows(fixtures(), teams, "2026-27");

        assertThat(rows).hasSize(3);
        Object[] played = rows.getFirst();
        assertThat(played[1]).isEqualTo(1);
        assertThat(played[2]).isEqualTo(1);              // gameweek
        assertThat(played[3]).isEqualTo(1);              // team_h
        assertThat(played[5]).isEqualTo("Arsenal");      // team_h_name
        assertThat(played[8]).isEqualTo("AVL");          // team_a_short
        assertThat(played[9]).isEqualTo(3);              // team_h_difficulty
        assertThat(played[11]).isEqualTo(OffsetDateTime.parse("2026-08-15T14:00:00Z"));
        assertThat(played[13]).isEqualTo(true);          // finished
        assertThat(played[14]).isEqualTo(3);             // team_h_score
    }

    @Test
    void aPostponedFixtureKeepsNullGameweekAndKickoff() throws Exception {
        List<Object[]> rows = ScheduleSyncService.toFixtureRows(fixtures(),
                ScheduleSyncService.teamsOf(bootstrap()), "2026-27");
        Object[] postponed = rows.get(2);

        assertThat(postponed[2]).isNull();
        assertThat(postponed[11]).isNull();
        assertThat(postponed[14]).isNull();
    }

    @Test
    void aTeamMissingFromTheBootstrapBlockLeavesNullNamesNotAnException() throws Exception {
        List<Object[]> rows = ScheduleSyncService.toFixtureRows(fixtures(), Map.of(), "2026-27");

        assertThat(rows.getFirst()[5]).isNull();
        assertThat(rows.getFirst()[6]).isNull();
    }
}
```

- **Step 3: Run.** Expected: compile failure — class does not exist.

- **Step 4: Implement the mapping half** (the fetch/write half is Task 3; leave `sync()` out for now):

```java
package fi.karivanttinen.fplassistant.schedule;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Captures FPL's gameweeks and fixtures into {@code fpl_gameweek} and {@code fpl_fixture}.
 *
 * <p>The schedule counterpart of {@code DailyPriceSyncService}, and built on the same three
 * rules: the season code comes from the UK date, not from the database; team names come from
 * the payload's own {@code teams} block, not from tapas; and the response is refused when it
 * still describes a finished season. The mapping methods are static and package-private so
 * they are unit-tested from captured payloads without a network.
 */
@Component
public class ScheduleSyncService {

    static List<Object[]> toGameweekRows(Map<String, Object> bootstrap, String season) {
        List<Object[]> rows = new ArrayList<>();
        if (!(bootstrap.get("events") instanceof List<?> events)) {
            return rows;
        }
        for (Object o : events) {
            if (!(o instanceof Map<?, ?> e)) {
                continue;
            }
            Integer id = asInt(e.get("id"));
            OffsetDateTime deadline = asTime(e.get("deadline_time"));
            if (id == null || deadline == null) {
                continue;   // deadline_time is NOT NULL: a gameweek without one is not a gameweek
            }
            rows.add(new Object[] {
                    season, id, asString(e.get("name")), deadline,
                    Boolean.TRUE.equals(e.get("finished")),
                    Boolean.TRUE.equals(e.get("data_checked")),
                    Boolean.TRUE.equals(e.get("is_current")),
                    Boolean.TRUE.equals(e.get("is_next")),
                    asInt(e.get("average_entry_score")), asInt(e.get("highest_score")),
                    asInt(e.get("most_captained")), asInt(e.get("most_transferred_in"))
            });
        }
        return rows;
    }

    /** id → {name, short_name} from the bootstrap {@code teams} block. */
    static Map<Integer, String[]> teamsOf(Map<String, Object> bootstrap) {
        Map<Integer, String[]> teams = new HashMap<>();
        if (bootstrap.get("teams") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> t) {
                    Integer id = asInt(t.get("id"));
                    if (id != null) {
                        teams.put(id, new String[] {asString(t.get("name")), asString(t.get("short_name"))});
                    }
                }
            }
        }
        return teams;
    }

    static List<Object[]> toFixtureRows(List<Map<String, Object>> fixtures, Map<Integer, String[]> teams,
                                        String season) {
        List<Object[]> rows = new ArrayList<>(fixtures.size());
        for (Map<String, Object> f : fixtures) {
            Integer id = asInt(f.get("id"));
            Integer home = asInt(f.get("team_h"));
            Integer away = asInt(f.get("team_a"));
            if (id == null || home == null || away == null) {
                continue;
            }
            String[] h = teams.getOrDefault(home, new String[2]);
            String[] a = teams.getOrDefault(away, new String[2]);
            rows.add(new Object[] {
                    season, id, asInt(f.get("event")), home, away,
                    h[0], h[1], a[0], a[1],
                    asInt(f.get("team_h_difficulty")), asInt(f.get("team_a_difficulty")),
                    asTime(f.get("kickoff_time")),
                    Boolean.TRUE.equals(f.get("started")), Boolean.TRUE.equals(f.get("finished")),
                    asInt(f.get("team_h_score")), asInt(f.get("team_a_score"))
            });
        }
        return rows;
    }

    private static Integer asInt(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static OffsetDateTime asTime(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value.toString());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
```

Make the three `DailyPriceSyncService` members `public static` (`UK`, `seasonCode`, `responseBelongsToAFinishedSeason`); nothing else there changes.

- **Step 5: Run.** Expected: 5 pass.

- **Step 6: Commit.**

```bash
git add backend/src/main/java/fi/karivanttinen/fplassistant/schedule/ScheduleSyncService.java \
        backend/src/main/java/fi/karivanttinen/fplassistant/price/DailyPriceSyncService.java \
        backend/src/test/resources/fpl-bootstrap-events-fixture.json backend/src/test/resources/fpl-fixtures-fixture.json \
        backend/src/test/java/fi/karivanttinen/fplassistant/schedule/ScheduleSyncServiceTest.java
git commit -m "feat: map FPL events and fixtures to schedule rows"
```

---

### Task 3: Fetch, write, schedule, CLI

**Files:**
- Modify: `schedule/ScheduleSyncService.java` (constructor, `sync()`, `write`)
- Create: `schedule/ScheduleScheduler.java`, `schedule/ScheduleRunner.java`
- Test: `backend/src/test/java/fi/karivanttinen/fplassistant/schedule/ScheduleWriteIntegrationTest.java`

**Interfaces:**
- Produces: `public int sync()` (rows written, gameweeks + fixtures); package-private `int write(String season, List<Object[]> gameweeks, List<Object[]> fixtures)` used by the test to bypass the network.

- **Step 1: Failing integration test** — write twice, the second time without one fixture, and the missing one is gone:

```java
package fi.karivanttinen.fplassistant.schedule;

import java.time.OffsetDateTime;
import java.util.List;

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
class ScheduleWriteIntegrationTest {

    private static final String SEASON = "T-2097-98";

    @Autowired ScheduleSyncService service;
    @Autowired JdbcTemplate jdbc;
    @Value("${fpl.schedule.gameweek-table-name}") String gameweekTable;
    @Value("${fpl.schedule.fixture-table-name}") String fixtureTable;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM " + gameweekTable + " WHERE season = ?", SEASON);
        jdbc.update("DELETE FROM " + fixtureTable + " WHERE season = ?", SEASON);
    }

    private static Object[] gameweek(int id, String deadline) {
        return new Object[] {SEASON, id, "Gameweek " + id, OffsetDateTime.parse(deadline),
                false, false, false, false, null, null, null, null};
    }

    private static Object[] fixture(int id, int gameweek) {
        return new Object[] {SEASON, id, gameweek, 9001, 9002, "Ashfield", "ASH", "Northmoor", "NMC",
                2, 3, OffsetDateTime.parse("2097-08-15T14:00:00Z"), false, false, null, null};
    }

    @Test
    void aSecondCaptureOverwritesAndRemovesWhatFplNoLongerPublishes() {
        service.write(SEASON, List.of(gameweek(1, "2097-08-14T17:30:00Z")),
                List.of(fixture(1, 1), fixture(2, 1)));
        service.write(SEASON, List.of(gameweek(1, "2097-08-14T18:00:00Z")),
                List.of(fixture(1, 1)));

        Integer fixtures = jdbc.queryForObject(
                "SELECT count(*) FROM " + fixtureTable + " WHERE season = ?", Integer.class, SEASON);
        OffsetDateTime deadline = jdbc.queryForObject(
                "SELECT deadline_time FROM " + gameweekTable + " WHERE season = ? AND id = 1",
                OffsetDateTime.class, SEASON);

        assertThat(fixtures).isEqualTo(1);
        assertThat(deadline).isEqualTo(OffsetDateTime.parse("2097-08-14T18:00:00Z"));
    }
}
```

- **Step 2: Run.** Expected: `write` does not exist.

- **Step 3: Implement the rest of the service.** Add to `ScheduleSyncService`:

```java
private static final Logger log = LoggerFactory.getLogger(ScheduleSyncService.class);
private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
private static final ParameterizedTypeReference<Map<String, Object>> OBJECT = new ParameterizedTypeReference<>() { };
private static final ParameterizedTypeReference<List<Map<String, Object>>> ARRAY = new ParameterizedTypeReference<>() { };

private final RestClient restClient;
private final JdbcTemplate jdbc;
private final TransactionTemplate tx;
private final String gameweekTable;
private final String fixtureTable;
private final String bootstrapUrl;
private final String fixturesUrl;

public ScheduleSyncService(RestClient.Builder restClientBuilder, JdbcTemplate jdbc, TransactionTemplate tx,
                           @Value("${fpl.schedule.gameweek-table-name}") String gameweekTable,
                           @Value("${fpl.schedule.fixture-table-name}") String fixtureTable,
                           @Value("${fpl.daily-price.bootstrap-url}") String bootstrapUrl,
                           @Value("${fpl.schedule.fixtures-url}") String fixturesUrl) {
    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
    factory.setReadTimeout(READ_TIMEOUT);
    this.restClient = restClientBuilder.requestFactory(factory).build();
    this.jdbc = jdbc;
    this.tx = tx;
    this.gameweekTable = gameweekTable;
    this.fixtureTable = fixtureTable;
    this.bootstrapUrl = bootstrapUrl;
    this.fixturesUrl = fixturesUrl;
}

/** @return gameweek rows plus fixture rows written, for logging and exit codes */
public int sync() {
    Map<String, Object> bootstrap = restClient.get().uri(bootstrapUrl).retrieve().body(OBJECT);
    List<Map<String, Object>> fixtures = restClient.get().uri(fixturesUrl).retrieve().body(ARRAY);
    LocalDate today = LocalDate.now(DailyPriceSyncService.UK);
    if (bootstrap == null || fixtures == null) {
        log.warn("schedule sync: empty response; nothing written");
        return 0;
    }
    if (DailyPriceSyncService.responseBelongsToAFinishedSeason(bootstrap, today)) {
        log.warn("schedule sync: bootstrap-static still carries last season; nothing written");
        return 0;
    }
    String season = DailyPriceSyncService.seasonCode(today);
    List<Object[]> gameweeks = toGameweekRows(bootstrap, season);
    List<Object[]> fixtureRows = toFixtureRows(fixtures, teamsOf(bootstrap), season);
    // Both blocks, not just one: the write ends by deleting whatever this capture did not
    // carry, so an empty fixtures array (FPL has served one during maintenance) would
    // otherwise wipe the season's fixtures rather than leave yesterday's standing.
    if (gameweeks.isEmpty() || fixtureRows.isEmpty()) {
        log.warn("schedule sync: {} events and {} fixtures in the response; nothing written",
                gameweeks.size(), fixtureRows.size());
        return 0;
    }
    int written = tx.execute(status -> write(season, gameweeks, fixtureRows));
    log.info("schedule sync: {} rows captured for {}", written, season);
    return written;
}

/**
 * Upserts every row, then deletes the season's rows this capture did not carry — by id, so the
 * result does not depend on clocks. Called inside {@code tx.execute} from {@link #sync()} so a
 * reader never sees a half-written season; public so a test can drive it without the network.
 * (Do NOT replace the template with {@code @Transactional} on this method: {@code sync()} calls it
 * on {@code this}, and Spring's proxy does not intercept self-invocation, so the annotation would
 * silently do nothing.)
 */
public int write(String season, List<Object[]> gameweeks, List<Object[]> fixtures) {
    int written = 0;
    for (int c : jdbc.batchUpdate("""
            INSERT INTO %s (season, id, name, deadline_time, finished, data_checked, is_current, is_next,
                            average_entry_score, highest_score, most_captained, most_transferred_in)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (season, id) DO UPDATE SET
                name = EXCLUDED.name, deadline_time = EXCLUDED.deadline_time,
                finished = EXCLUDED.finished, data_checked = EXCLUDED.data_checked,
                is_current = EXCLUDED.is_current, is_next = EXCLUDED.is_next,
                average_entry_score = EXCLUDED.average_entry_score, highest_score = EXCLUDED.highest_score,
                most_captained = EXCLUDED.most_captained, most_transferred_in = EXCLUDED.most_transferred_in,
                captured_at = NOW()
            """.formatted(gameweekTable), gameweeks)) {
        written += Math.max(c, 0);
    }
    for (int c : jdbc.batchUpdate("""
            INSERT INTO %s (season, id, gameweek, team_h, team_a, team_h_name, team_h_short,
                            team_a_name, team_a_short, team_h_difficulty, team_a_difficulty,
                            kickoff_time, started, finished, team_h_score, team_a_score)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (season, id) DO UPDATE SET
                gameweek = EXCLUDED.gameweek, team_h = EXCLUDED.team_h, team_a = EXCLUDED.team_a,
                team_h_name = EXCLUDED.team_h_name, team_h_short = EXCLUDED.team_h_short,
                team_a_name = EXCLUDED.team_a_name, team_a_short = EXCLUDED.team_a_short,
                team_h_difficulty = EXCLUDED.team_h_difficulty, team_a_difficulty = EXCLUDED.team_a_difficulty,
                kickoff_time = EXCLUDED.kickoff_time, started = EXCLUDED.started, finished = EXCLUDED.finished,
                team_h_score = EXCLUDED.team_h_score, team_a_score = EXCLUDED.team_a_score,
                captured_at = NOW()
            """.formatted(fixtureTable), fixtures)) {
        written += Math.max(c, 0);
    }
    deleteAllExcept(gameweekTable, season, ids(gameweeks));
    deleteAllExcept(fixtureTable, season, ids(fixtures));
    return written;
}

/** Column 1 of every row is the id; see the Interfaces block of Task 2. */
private static Integer[] ids(List<Object[]> rows) {
    return rows.stream().map(r -> (Integer) r[1]).toArray(Integer[]::new);
}

private void deleteAllExcept(String table, String season, Integer[] keep) {
    jdbc.update("DELETE FROM %s WHERE season = ? AND NOT (id = ANY(?))".formatted(table), ps -> {
        ps.setString(1, season);
        ps.setArray(2, ps.getConnection().createArrayOf("integer", keep));
    });
}
```

`write` is public so `ScheduleWriteIntegrationTest` can call it directly; outside a transaction the id-based deletion is still correct, which is the point of not keying it on `now()`. Imports: `org.springframework.core.ParameterizedTypeReference`, `org.springframework.http.client.JdkClientHttpRequestFactory`, `org.springframework.web.client.RestClient`, `org.springframework.transaction.support.TransactionTemplate`, `java.net.http.HttpClient`, `java.time.Duration`, `java.time.LocalDate`, `org.slf4j.*`, `org.springframework.beans.factory.annotation.Value`.

Add a second integration test beside the first — an empty fixtures array must leave yesterday's rows standing, which is `sync()`'s guard, but `write` itself must also cope with an empty list without deleting everything (a caller may legitimately have zero fixtures for a season that has not been published yet):

```java
@Test
void anEmptyFixtureListLeavesTheSeasonUntouchedRatherThanEmptyingIt() {
    service.write(SEASON, List.of(gameweek(1, "2097-08-14T17:30:00Z")), List.of(fixture(1, 1)));
    service.write(SEASON, List.of(gameweek(1, "2097-08-14T17:30:00Z")), List.of());

    Integer fixtures = jdbc.queryForObject(
            "SELECT count(*) FROM " + fixtureTable + " WHERE season = ?", Integer.class, SEASON);
    assertThat(fixtures).isEqualTo(1);
}
```

To make it pass, `write` skips the fixture upsert *and* the fixture deletion when `fixtures.isEmpty()` (same for gameweeks); wrap each half in `if (!rows.isEmpty())`.

- **Step 4: Scheduler and runner**, mirroring `DailyPriceScheduler`/`DailyPriceRunner` exactly:

```java
@Component
@ConditionalOnProperty(name = "fpl.schedule.scheduled", havingValue = "true")
public class ScheduleScheduler {
    private static final Logger log = LoggerFactory.getLogger(ScheduleScheduler.class);
    private final ScheduleSyncService syncService;
    public ScheduleScheduler(ScheduleSyncService syncService) { this.syncService = syncService; }

    @Scheduled(cron = "${fpl.schedule.fetch-at}", zone = "${fpl.schedule.fetch-zone}")
    public void captureSchedule() {
        try {
            log.info("scheduled schedule capture: {} rows", syncService.sync());
        } catch (Exception e) {
            log.error("scheduled schedule capture failed; gameweeks and fixtures keep yesterday's"
                    + " capture until the next run or a manual --sync-schedule", e);
        }
    }
}
```

```java
@Component
public class ScheduleRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(ScheduleRunner.class);
    private final ScheduleSyncService syncService;
    public ScheduleRunner(ScheduleSyncService syncService) { this.syncService = syncService; }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption("sync-schedule")) {
            return;
        }
        try {
            int written = syncService.sync();
            log.info("--sync-schedule complete: {} rows; exiting", written);
            System.exit(written > 0 ? 0 : 1);
        } catch (Exception e) {
            log.error("--sync-schedule failed", e);
            System.exit(1);
        }
    }
}
```

- **Step 5: Run the schedule tests, then the suite.** Expected: green. (`DailyPriceScheduleTest` pins the price cron; add the equivalent one-line assertion for `fpl.schedule.fetch-at` there if it reads properties generically, otherwise skip.)

- **Step 6: Commit.**

```bash
git add backend/src/main/java/fi/karivanttinen/fplassistant/schedule/ \
        backend/src/test/java/fi/karivanttinen/fplassistant/schedule/ScheduleWriteIntegrationTest.java
git commit -m "feat: capture FPL gameweeks and fixtures daily"
```

---

### Task 4: `ScheduleSource` — the latest capture and its provenance

**Files:**
- Create: `backend/src/main/java/fi/karivanttinen/fplassistant/schedule/ScheduleSource.java`
- Test: `backend/src/test/java/fi/karivanttinen/fplassistant/schedule/ScheduleSourceTest.java`

**Interfaces:**
- Produces: `public record Snapshot(String season, Instant capturedAt)`; `public Optional<Snapshot> latest()`; `public static String provenance(String season, Instant capturedAt, Instant now)`; `public String gameweekTable()`, `public String fixtureTable()`.

- **Step 1: Failing test** (pure part first; `latest()` is exercised by the tool tests in Tasks 5–6):

```java
package fi.karivanttinen.fplassistant.schedule;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduleSourceTest {

    private static final Instant NOW = Instant.parse("2026-08-29T12:00:00Z");

    @Test
    void namesTheSeasonAndTheCaptureAge() {
        String line = ScheduleSource.provenance("2026-27", Instant.parse("2026-08-29T01:10:00Z"), NOW);

        assertThat(line).startsWith("Data source: FPL's own gameweek and fixture list for season 2026-27");
        assertThat(line).contains("captured today");
    }

    @Test
    void anOldCaptureSaysHowOldItIs() {
        String line = ScheduleSource.provenance("2026-27", Instant.parse("2026-08-26T01:10:00Z"), NOW);

        assertThat(line).contains("captured 3 days ago");
        assertThat(line).contains("scores and postponements may have changed since");
    }
}
```

- **Step 2: Run.** Expected: class missing.

- **Step 3: Implement.**

```java
package fi.karivanttinen.fplassistant.schedule;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Read side of the schedule capture, shared by {@code GameweekTools} and {@code FixtureTools}.
 *
 * <p>The provenance line is this capture's own, not {@code SeasonContext.provenance()}: the
 * season is read from the rows, which during the changeover is the season being played while
 * the resolver still names the completed one — the same reasoning as
 * {@code DailyPriceSource.provenance()}, and a documented exception in {@code AGENTS.md}.
 */
@Component
public class ScheduleSource {

    private static final ZoneId UK = ZoneId.of("Europe/London");

    public record Snapshot(String season, Instant capturedAt) {
    }

    private final JdbcTemplate jdbc;
    private final String gameweekTable;
    private final String fixtureTable;

    public ScheduleSource(JdbcTemplate jdbc,
                          @Value("${fpl.schedule.gameweek-table-name}") String gameweekTable,
                          @Value("${fpl.schedule.fixture-table-name}") String fixtureTable) {
        this.jdbc = jdbc;
        this.gameweekTable = gameweekTable;
        this.fixtureTable = fixtureTable;
    }

    public String gameweekTable() {
        return gameweekTable;
    }

    public String fixtureTable() {
        return fixtureTable;
    }

    /** The most recently captured season, or empty before the first capture. */
    public Optional<Snapshot> latest() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT season, MAX(captured_at) AS captured_at FROM %s
                GROUP BY season ORDER BY MAX(captured_at) DESC LIMIT 1
                """.formatted(gameweekTable));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.getFirst();
        // pgjdbc hands a TIMESTAMPTZ back as java.sql.Timestamp from an untyped getObject —
        // UkTime.format documents the same fact. Not OffsetDateTime; that cast throws.
        Instant at = ((java.sql.Timestamp) row.get("captured_at")).toInstant();
        return Optional.of(new Snapshot((String) row.get("season"), at));
    }

    public static String provenance(String season, Instant capturedAt, Instant now) {
        long daysOld = Math.max(0, ChronoUnit.DAYS.between(
                LocalDate.ofInstant(capturedAt, UK), LocalDate.ofInstant(now, UK)));
        String age = switch ((int) Math.min(daysOld, 2)) {
            case 0 -> "captured today";
            case 1 -> "captured yesterday";
            default -> "captured %d days ago".formatted(daysOld);
        };
        return ("Data source: FPL's own gameweek and fixture list for season %s, %s. Deadlines are as"
                + " FPL publishes them and gameweek status is judged against the clock now; scores"
                + " and postponements may have changed since the capture.").formatted(season, age);
    }
}
```

- **Step 4: Run.** Expected: 2 pass.

- **Step 5: Commit.**

```bash
git add backend/src/main/java/fi/karivanttinen/fplassistant/schedule/ScheduleSource.java \
        backend/src/test/java/fi/karivanttinen/fplassistant/schedule/ScheduleSourceTest.java
git commit -m "feat: ScheduleSource reads the latest schedule capture"
```

---

### Task 5: `GameweekTools` reads the capture first

**Files:**
- Modify: `backend/src/main/java/fi/karivanttinen/fplassistant/tools/GameweekTools.java`
- Test: `backend/src/test/java/fi/karivanttinen/fplassistant/tools/GameweekToolsCaptureTest.java` (new); `GameweekToolsTest.java` stays as the fallback test

**Interfaces:**
- Consumes: `ScheduleSource.latest()`, `ScheduleSource.provenance(...)`, `ScheduleSource.gameweekTable()`.

- **Step 1: Failing test.** Deadlines relative to the clock, so the derivation is what is tested:

```java
package fi.karivanttinen.fplassistant.tools;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link GameweekTools} with a schedule capture present: it wins over tapas, and status is derived from the clock. */
@SpringBootTest
@ActiveProfiles("test")
class GameweekToolsCaptureTest {

    private static final String SEASON = "T-2096-97";

    @Autowired GameweekTools tools;
    @Autowired JdbcTemplate jdbc;
    @Value("${fpl.schedule.gameweek-table-name}") String table;

    @BeforeEach
    void seed() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        // GW1 finished; GW2's deadline passed yesterday and it is being played; GW3 is next.
        // FPL's own flags are stale on purpose — is_current still says GW1 — to prove the
        // tool judges by deadline, not by flag.
        gameweek(1, now.minusDays(8), true, true, false, 57, 127);
        gameweek(2, now.minusDays(1), false, false, false, null, null);
        gameweek(3, now.plusDays(6), false, false, true, null, null);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM " + table + " WHERE season = ?", SEASON);
    }

    private void gameweek(int id, OffsetDateTime deadline, boolean finished, boolean current, boolean next,
                          Integer average, Integer highest) {
        jdbc.update("INSERT INTO " + table + " (season, id, name, deadline_time, finished, data_checked,"
                + " is_current, is_next, average_entry_score, highest_score, most_captained, most_transferred_in)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 9203, 9202)",
                SEASON, id, "Gameweek " + id, deadline, finished, finished, current, next, average, highest);
    }

    @Test
    void reportsTheGameweekBeingPlayedAndTheNextOneFromDeadlinesNotFlags() {
        String result = tools.getGameweekInfo();

        assertThat(result).startsWith("Data source: FPL's own gameweek and fixture list for season " + SEASON);
        assertThat(result).contains("Gameweek 2 (id=2)").contains("Status: CURRENT");
        assertThat(result).contains("Gameweek 3 (id=3)").contains("Status: NEXT");
        assertThat(result).doesNotContain("Gameweek 1 (id=1)");
        assertThat(result).doesNotContain("Data source: season");
    }

    @Test
    void theDeadlineIsPrintedInUkTime() {
        String result = tools.getGameweekInfo();

        assertThat(result).containsPattern("Deadline: \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2} UK");
    }
}
```

- **Step 2: Run.** Expected: FAIL — the reply still starts with `Data source: season` (tapas path).

- **Step 3: Implement.** Inject `ScheduleSource schedule` into the constructor. In `getGameweekInfo()`, before the tapas query:

```java
Optional<ScheduleSource.Snapshot> captured = schedule.latest();
if (captured.isPresent()) {
    return fromCapture(captured.get());
}
```

and:

```java
/**
 * The capture path. Status comes from the deadline against the clock, not from FPL's flags as
 * captured at 02:10: the gameweek whose deadline has passed most recently and is not finished is
 * being played, and the first with a deadline still ahead is next.
 */
private String fromCapture(ScheduleSource.Snapshot snapshot) {
    List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT id, name, deadline_time, finished, average_entry_score, highest_score,
                   most_captained, most_transferred_in,
                   deadline_time <= now() AS deadline_passed
            FROM %s WHERE season = ?
            ORDER BY deadline_time
            """.formatted(schedule.gameweekTable()), snapshot.season());
    Map<String, Object> current = null;
    Map<String, Object> next = null;
    for (Map<String, Object> r : rows) {
        if (Boolean.TRUE.equals(r.get("deadline_passed"))) {
            current = r;
        } else if (next == null) {
            next = r;
        }
    }
    String provenance = ScheduleSource.provenance(snapshot.season(), snapshot.capturedAt(), Instant.now());
    if (current == null && next == null) {
        return provenance + "\nNo gameweek data captured for this season.";
    }
    StringBuilder out = new StringBuilder(provenance).append("\n\n");
    if (current != null) {
        out.append(formatCaptured(current, Boolean.TRUE.equals(current.get("finished")) ? "FINISHED" : "CURRENT"));
    }
    if (current != null && next != null) {
        out.append("\n---\n");
    }
    if (next != null) {
        out.append(formatCaptured(next, "NEXT"));
    }
    return out.toString();
}

private String formatCaptured(Map<String, Object> r, String status) {
    return """
            %s (id=%s)
            Status: %s
            Deadline: %s
            Average score: %s | Highest: %s
            Most captained player_id: %s | Most transferred in: %s
            """.formatted(r.get("name"), r.get("id"), status,
            UkTime.format(r.get("deadline_time"), "deadline unknown"),
            r.get("average_entry_score"), r.get("highest_score"),
            r.get("most_captained"), r.get("most_transferred_in"));
}
```

Imports: `java.time.Instant`, `java.util.Optional`, `fi.karivanttinen.fplassistant.schedule.ScheduleSource`. Update the `@Tool` description: add "Gameweek status is judged against the clock at the time of asking." Keep the existing tapas path and its `formatGameweek` untouched below.

- **Step 4: Make the fallback test independent of cleanup.** `GameweekToolsTest` exercises the tapas path only while `fpl_gameweek_test` is empty. A capture test that dies mid-way leaves rows behind and the fallback test then fails for a reason that has nothing to do with it. Add to `GameweekToolsTest`:

```java
@Value("${fpl.schedule.gameweek-table-name}") String scheduleTable;

@BeforeEach
void seed() {
    TapasSeed.ensure(jdbc);
    // The tapas path is what this class tests; it is taken only while no schedule capture exists.
    // This project's own _test table, so clearing it is allowed.
    jdbc.update("DELETE FROM " + scheduleTable);
}
```

- **Step 5: Run both Gameweek test classes.** Expected: both pass.

- **Step 6: Commit.**

```bash
git add backend/src/main/java/fi/karivanttinen/fplassistant/tools/GameweekTools.java \
        backend/src/test/java/fi/karivanttinen/fplassistant/tools/GameweekToolsCaptureTest.java \
        backend/src/test/java/fi/karivanttinen/fplassistant/tools/GameweekToolsTest.java
git commit -m "feat: getGameweekInfo reads the schedule capture first"
```

---

### Task 6: `FixtureTools` reads the capture first

**Files:**
- Modify: `backend/src/main/java/fi/karivanttinen/fplassistant/tools/FixtureTools.java`
- Test: `backend/src/test/java/fi/karivanttinen/fplassistant/tools/FixtureToolsCaptureTest.java` (new)

**Interfaces:**
- Consumes: `ScheduleSource` as in Task 5; reuses `FixtureTools.window` and `formatFixture` unchanged by producing the same column aliases (`gameweek, home_short, away_short, team_h_difficulty, team_a_difficulty, kickoff_time, finished, team_h_score, team_a_score, is_home`).

- **Step 1: Failing test.**

```java
package fi.karivanttinen.fplassistant.tools;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class FixtureToolsCaptureTest {

    private static final String SEASON = "T-2095-96";

    @Autowired FixtureTools tools;
    @Autowired JdbcTemplate jdbc;
    @Value("${fpl.schedule.gameweek-table-name}") String gameweekTable;
    @Value("${fpl.schedule.fixture-table-name}") String fixtureTable;

    @BeforeEach
    void seed() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("INSERT INTO " + gameweekTable + " (season, id, name, deadline_time, finished) VALUES"
                + " (?, 1, 'Gameweek 1', ?, true), (?, 2, 'Gameweek 2', ?, false), (?, 3, 'Gameweek 3', ?, false)",
                SEASON, now.minusDays(8), SEASON, now.minusDays(1), SEASON, now.plusDays(6));
        fixture(1, 1, 9001, 9002, now.minusDays(7), true, 2, 1);
        fixture(2, 2, 9002, 9001, now.plusHours(20), false, null, null);   // being played this week
        fixture(3, 3, 9001, 9003, now.plusDays(7), false, null, null);
        fixture(4, 3, 9003, 9001, now.plusDays(8), false, null, null);     // a double gameweek
        fixture(5, null, 9001, 9002, null, false, null, null);             // postponed
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM " + fixtureTable + " WHERE season = ?", SEASON);
        jdbc.update("DELETE FROM " + gameweekTable + " WHERE season = ?", SEASON);
    }

    private void fixture(int id, Integer gameweek, int home, int away, OffsetDateTime kickoff,
                         boolean finished, Integer homeScore, Integer awayScore) {
        String[][] names = {{"Ashfield Rovers", "ASH"}, {"Northmoor City", "NMC"}, {"Selby Town", "SEL"}};
        String[] h = names[home - 9001];
        String[] a = names[away - 9001];
        jdbc.update("INSERT INTO " + fixtureTable + " (season, id, gameweek, team_h, team_a, team_h_name,"
                + " team_h_short, team_a_name, team_a_short, team_h_difficulty, team_a_difficulty, kickoff_time,"
                + " started, finished, team_h_score, team_a_score)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 2, 4, ?, ?, ?, ?, ?)",
                SEASON, id, gameweek, home, away, h[0], h[1], a[0], a[1], kickoff, finished, finished,
                homeScore, awayScore);
    }

    @Test
    void listsUpcomingFixturesFromTheCurrentGameweekWithFdrAndTheCaptureLine() {
        String result = tools.getUpcomingFixtures("Ashfield", 2);

        assertThat(result).startsWith("Data source: FPL's own gameweek and fixture list for season " + SEASON);
        assertThat(result).contains("Upcoming fixtures for Ashfield Rovers (ASH)");
        assertThat(result).contains("GW2: vs NMC (A) | FDR: 4");
        assertThat(result).contains("GW3: vs SEL (H) | FDR: 2").contains("GW3: vs SEL (A) | FDR: 4");
        assertThat(result).doesNotContain("GW1:");
        assertThat(result).contains("Postponed, no rescheduled gameweek yet:");
    }

    @Test
    void matchesTheShortNameToo() {
        assertThat(tools.getUpcomingFixtures("nmc", 1)).contains("for Northmoor City (NMC)");
    }

    /** Both paths say "Team not found"; the banner proves which one answered. */
    @Test
    void anUnknownTeamIsNotFoundOnTheCapturePath() {
        String result = tools.getUpcomingFixtures("Woodbridge", 1);

        assertThat(result).startsWith("Data source: FPL's own gameweek and fixture list");
        assertThat(result).contains("Team not found");
    }

    /**
     * Once the gameweek being played is finished, the list starts at the next one. The tapas
     * path shows the finished round's fixtures with their scores because it starts at
     * `is_current`; the capture path judges by deadline and finish, so a Tuesday question about
     * upcoming fixtures does not open with Saturday's result. Deliberate, and recorded in STATUS.
     */
    @Test
    void aFinishedCurrentGameweekIsSkippedInFavourOfTheNext() {
        jdbc.update("UPDATE " + gameweekTable + " SET finished = true WHERE season = ? AND id = 2", SEASON);

        String result = tools.getUpcomingFixtures("Ashfield", 1);

        assertThat(result).doesNotContain("GW2:");
        assertThat(result).contains("GW3: vs SEL (H)");
    }
}
```

Also make `FixtureToolsTest` (the tapas-path class) clear the capture tables in its `@BeforeEach`, exactly as Task 5 did for `GameweekToolsTest`:

```java
@Value("${fpl.schedule.gameweek-table-name}") String scheduleGameweekTable;
@Value("${fpl.schedule.fixture-table-name}") String scheduleFixtureTable;
// in seed():
jdbc.update("DELETE FROM " + scheduleFixtureTable);
jdbc.update("DELETE FROM " + scheduleGameweekTable);
```

- **Step 2: Run.** Expected: FAIL — tapas path answers (or "Team not found" for the fictitious names).

- **Step 3: Implement.** Inject `ScheduleSource schedule`. At the top of `getUpcomingFixtures`, after computing `gwLimit` and `pattern`:

```java
Optional<ScheduleSource.Snapshot> captured = schedule.latest();
if (captured.isPresent()) {
    return fromCapture(captured.get(), teamName, pattern, gwLimit);
}
```

and:

```java
private String fromCapture(ScheduleSource.Snapshot snapshot, String teamName, String pattern, int gwLimit) {
    String provenance = ScheduleSource.provenance(snapshot.season(), snapshot.capturedAt(), Instant.now());
    List<Map<String, Object>> teams = jdbc.queryForList("""
            SELECT team_id, team_name, team_short FROM (
                SELECT team_h AS team_id, team_h_name AS team_name, team_h_short AS team_short FROM %1$s WHERE season = ?
                UNION
                SELECT team_a, team_a_name, team_a_short FROM %1$s WHERE season = ?
            ) t
            WHERE LOWER(team_name) LIKE ? OR LOWER(team_short) = LOWER(?)
            ORDER BY team_name LIMIT 1
            """.formatted(schedule.fixtureTable()),
            snapshot.season(), snapshot.season(), pattern, teamName);
    if (teams.isEmpty()) {
        return provenance + "\nTeam not found: " + teamName;
    }
    int teamId = ((Number) teams.getFirst().get("team_id")).intValue();
    String resolvedName = (String) teams.getFirst().get("team_name");
    String resolvedShort = (String) teams.getFirst().get("team_short");

    // The gameweek being played (latest passed deadline, unfinished), else the next, else the first.
    Integer startGw = jdbc.queryForObject("""
            SELECT COALESCE(
                (SELECT MAX(id) FROM %1$s WHERE season = ? AND deadline_time <= now() AND finished = false),
                (SELECT MIN(id) FROM %1$s WHERE season = ? AND deadline_time > now()),
                (SELECT MIN(id) FROM %1$s WHERE season = ?))
            """.formatted(schedule.gameweekTable()),
            Integer.class, snapshot.season(), snapshot.season(), snapshot.season());

    List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT gameweek, team_h_short AS home_short, team_a_short AS away_short,
                   team_h_difficulty, team_a_difficulty, kickoff_time, finished,
                   team_h_score, team_a_score, team_h = ? AS is_home
            FROM %s
            WHERE season = ? AND (team_h = ? OR team_a = ?)
              AND (gameweek >= ? OR (gameweek IS NULL AND finished = false))
            ORDER BY gameweek NULLS LAST, kickoff_time
            """.formatted(schedule.fixtureTable()),
            teamId, snapshot.season(), teamId, teamId, startGw);

    FixtureWindow window = window(rows, gwLimit);
    if (window.scheduled().isEmpty() && window.postponed().isEmpty()) {
        return provenance + "\nNo upcoming fixtures found for " + resolvedName;
    }
    StringBuilder out = new StringBuilder(provenance)
            .append("\n\nUpcoming fixtures for %s (%s):\n".formatted(resolvedName, resolvedShort))
            .append(window.scheduled().stream().map(this::formatFixture).collect(Collectors.joining("\n")));
    if (!window.postponed().isEmpty()) {
        out.append("\nPostponed, no rescheduled gameweek yet:\n")
                .append(window.postponed().stream().map(this::formatFixture).collect(Collectors.joining("\n")));
    }
    return out.toString();
}
```

`startGw` may be null when the season has no gameweek rows at all; guard: `if (startGw == null) return provenance + "\nNo gameweek data captured for this season.";`. Imports as in Task 5. Update the `@Tool` description's last sentence: "The reply begins with which source the fixtures come from — FPL's own list captured daily when available, otherwise the synced season — and the season."

- **Step 4: Run the four fixture test classes** (`FixtureToolsTest`, `FixtureWindowingTest`, `FixtureToolsCaptureTest`, `FixtureDifficultyTools*`). Expected: green.

- **Step 5: Commit.**

```bash
git add backend/src/main/java/fi/karivanttinen/fplassistant/tools/FixtureTools.java \
        backend/src/test/java/fi/karivanttinen/fplassistant/tools/FixtureToolsCaptureTest.java \
        backend/src/test/java/fi/karivanttinen/fplassistant/tools/FixtureToolsTest.java
git commit -m "feat: getUpcomingFixtures reads the schedule capture first"
```

---

### Task 7: Docs, prompt, first production capture, PR

**Files:**
- Modify: `AGENTS.md` (architecture table list, daily-capture exception bullet), `docs/DEVELOPMENT.md` (CLI flags, table list), `backend/src/main/resources/prompts/system-prompt.st`, `docs/STATUS.md`

- **Step 1: AGENTS.md.** Add `fpl_gameweek`, `fpl_fixture` to the list of tables this project adds. In the `fpl_daily_price` bullet's exception sentence, add: "**`GameweekTools` and `FixtureTools` are the same kind of exception when a schedule capture exists: they read `fpl_gameweek`/`fpl_fixture` (FPL's own `events` and `/api/fixtures/`, captured daily at 02:10 UK) under `ScheduleSource.provenance()`, and fall back to the tapas tables under `SeasonContext.provenance()` only before the first capture. Gameweek status on the capture path is derived from `deadline_time` against the clock, never from the captured `is_current` flag.**" Add to decision 4's follow-up list that `FixtureDifficultyTools`, `PickTools.avgFdrByTeam`, `HorizonScorer` and `SquadEnrichment` still read tapas' `fixture`.

- **Step 2: DEVELOPMENT.md.** Add `./gradlew bootRun --args="--sync-schedule"   # capture FPL gameweeks and fixtures, then exit` beside `--sync-prices`, and the two tables to any table list there.

- **Step 3: System prompt.** Where `getGameweekInfo` / `getUpcomingFixtures` are described, add one sentence: "When the reply begins with `Data source: FPL's own gameweek and fixture list`, the deadline is live and current; when it begins with `Data source: season …`, it came from the synced tables and may lag."

- **Step 4: STATUS.md.** Append `## Gameweeks and fixtures captured from FPL (KAR-10, 2026-MM-DD)`: the four decisions from the top of this plan, the by-id deletion and why it is not keyed on `now()` (self-invocation would have made `@Transactional` inert and autocommit would have deleted every row just written), the empty-payload guard, the fallback rule, and one behavioural difference between the paths: the capture path starts the fixture list at the next unfinished round while the tapas path starts at `is_current` and can open with a finished round's scores. And the follow-up list.

- **Step 5: Suite green, PR, merge.** `./gradlew test`; push; PR body ≤ 8 lines. After merge, deploy runs; then the first capture: from a machine with `backend/.env.supabase`, `set -a; source backend/.env.supabase; set +a; ./gradlew bootRun --args="--sync-schedule"` (never on the 512 MB production machine — `docs/EMAIL-SETUP.md` 5.1 explains why). Exit code 0 and `SELECT count(*) FROM fpl_gameweek` = 38 in the Supabase SQL editor.

- **Step 6: Verify in production.** Ask the deployed assistant "when is the next deadline?" — the reply starts with `Data source: FPL's own gameweek and fixture list for season 2026-27, captured today` and names the right gameweek. Ask for Arsenal's next three fixtures — same banner, FDR present. Linear: comment with both replies' first lines; KAR-10 Done.

**Done when:** both production checks pass and the scheduled 02:10 capture has run once on its own (check the log the next morning, or `MAX(captured_at)` in the table).

---

## Self-review notes

- Ticket coverage: "no current-season fixtures, deadlines or gameweek info until tapas syncs" → deadlines/gameweeks (Task 5), fixtures with FDR (Task 6), source independent of tapas (Tasks 1–3). The ticket's observation that `bootstrap-static` carries `events` and fixtures is one call away is exactly the fetch in Task 3.
- Placeholder scan: none; every step carries its code. The one "read it and mirror" instruction (RLS query in Task 1) names the file to read.
- Type consistency: `ScheduleSource.Snapshot(season, capturedAt)` and `provenance(String, Instant, Instant)` are used identically in Tasks 4–6; row column orders in Task 2's `Interfaces` block match the `INSERT` column lists in Task 3 and the test helpers in Tasks 3, 5 and 6.
- Left out on purpose: a freshness check for the schedule (the price check already proves the machine wakes at 02:00; a schedule miss leaves yesterday's rows, which the provenance line dates), and history.
