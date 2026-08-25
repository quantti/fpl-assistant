# My-Team-Aware Advice Implementation Plan

**Spec:** `docs/superpowers/specs/2026-08-23-my-team-advice-design.md` — read it first;
this plan argues from it and does not repeat its reasoning.

**Goal:** Turn the stored `fpl_user_profile.fpl_team_id` into actionable,
personalised advice. When a user asks how to improve their team, the assistant fetches
their actual FPL squad, bank balance, team value, and player-level stats, then compares
it to the existing stats tools to give recommendations that account for what they
already own.

**Architecture:** One new service (`MyTeamService`) fetches the user-specific FPL data
(entry summary + current gameweek picks) at chat time, enriches each pick from Supabase,
and caches the result briefly. One new tool (`MyTeamTools.getMyTeam`) exposes it to the
LLM. A mini-league extension (`MiniLeagueService` / `MiniLeagueTools`) lists the user's
classic leagues and fetches nearby opponents' squads when the user asks about a specific
league. The system prompt is updated so the model knows when to call each tool. Existing
tools and tables are reused; no new database tables are needed for the MVP.

**Tech stack:** Spring Boot 4.1 / Java 25, `RestClient`, `JdbcTemplate`, Caffeine cache;
React 19 + Mantine v9 unchanged for this phase.

**PROGRESS:** Task 0/12 done

## Global constraints

- The feature uses the existing `fpl_user_profile.fpl_team_id` column. No schema changes.
- FPL data is fetched **at chat time** because it is user-specific and gameweek-specific
  and cannot be pre-synced. This is a second documented exception to the
  "external APIs only at sync time" rule, alongside the existing team-id write
  validation.
- All prices and bank values are in 0.1m units, matching the existing data model.
- The cache is in-memory only; no new persistence.
- Every failure mode copies the `FplEntryLookup` pattern: "no team id", "team id not
  found", and "FPL unavailable" must be distinguishable for the user.

## Two decisions this plan makes that the spec leaves open

1. **The bank is taken from the current-gameweek picks endpoint** (`entry_history.bank`)
   rather than the entry summary (`last_deadline_bank`). It is the more current figure
   for the GW the squad belongs to, and it travels with the same response as the picks.
2. **One tool returns everything** (`getMyTeam`) rather than splitting into entry,
   squad, and bench tools. The LLM needs the whole picture to reason about transfers and
   captaincy; splitting it would force multiple round trips and increase latency.

## File structure

| File | Responsibility |
|---|---|
| `user/FplEntryLookup.java` (existing) | Reference for the FPL entry validation pattern and result type. |
| `user/UserProfileService.java` (existing) | Reference for reading `fpl_team_id`. |
| `myteam/MyTeamService.java` (new) | Fetch entry summary + picks, enrich, cache. |
| `myteam/MyTeamTools.java` (new) | `@Tool getMyTeam()`. |
| `myteam/EntrySnapshot.java` (new) | Record for the entry-level data returned. |
| `myteam/SquadPlayer.java` (new) | Record for one enriched pick. |
| `myteam/MyTeamCache.java` (new) | Caffeine cache bean + key helper. |
| `config/CacheConfig.java` (modify or new) | Expose a named Caffeine cache for `myTeam`. |
| `resources/prompts/system-prompt.st` | New section telling the model to call `getMyTeam` for squad-aware advice and `getMyMiniLeagues` / `getMiniLeagueOpponents` for mini-league questions. |
| `myteam/MyTeamToolsTest.java` (new) | Stub FPL with HttpServer; test no-id, not-found, unavailable, happy path, cache hit. |
| `myteam/MiniLeagueTools.java` (new) | `@Tool getMyMiniLeagues()` and `@Tool getMiniLeagueOpponents(leagueId)`. |
| `myteam/MiniLeagueService.java` (new) | Fetch user's leagues and nearby opponents with their squads. |
| `myteam/FplMiniLeagueClient.java` (new) | FPL endpoints for `/api/entry/{entry_id}/` leagues and `/api/leagues-classic/{league_id}/standings/`. |
| `myteam/MiniLeague.java` / `MiniLeagueOpponent.java` (new) | Records for the mini-league tool responses. |

---

### Task 1: FPL picks fetch client

**Files:** create `myteam/FplPicksClient.java`; reuse/adapt `user/FplEntryLookup.java`
result naming.

**Produces:** A typed client for two endpoints:
- `GET /api/entry/{entry_id}/`
- `GET /api/entry/{entry_id}/event/{event_id}/picks/`

**Why a separate client:** `FplEntryLookup` validates a single id with a single
`exists/not_found/unavailable` outcome. My-team needs the full JSON bodies, so the client
is richer but keeps the same bounded-timeout and failure-distinction discipline.

1. Define a `Result` sealed type: `Ok(EntryJson entry, PicksJson picks)`,
   `EntryNotFound`, `EventNotFound`, `Unavailable`.
2. Map FPL JSON to internal records. Important fields:
   - Entry: `id`, `name`, `player_first_name`, `player_last_name`, `summary_overall_rank`,
     `summary_overall_points`, `current_event`, `last_deadline_bank`,
     `last_deadline_value`.
   - Picks response: `entry_history.bank`, `entry_history.value`,
     `entry_history.event_points`, `active_chip`, `automatic_subs`, `picks[].element`,
     `picks[].position`, `picks[].multiplier`, `picks[].is_captain`,
     `picks[].is_vice_captain`, `picks[].selling_price`.
3. Use `RestClient` with the same timeout and URL-slash-repair patterns as
   `FplEntryLookup` and `SupabaseAdminClient`.

**Tests:** `FplPicksClientTest` with `HttpServer` stubs covering:
- 200 entry + 200 picks → `Ok`
- 404 entry → `EntryNotFound`
- 200 entry + 404 picks (invalid GW) → `EventNotFound`
- 500 from either endpoint → `Unavailable`

---

### Task 2: Resolve current gameweek

**Files:** `myteam/MyTeamService.java`; existing `tools/GameweekTools.java` or
`tools/SeasonResolver.java`.

**Produces:** A method `Optional<Integer> currentEventId()` that returns the current
gameweek FPL event id. This is needed to build the picks URL.

**Decision:** Use the existing `SeasonResolver`/`GameweekTools` path that reads from the
Supabase `gameweek` table (tapas-owned). It already resolves the current season and
which gameweek is current. If no current gameweek exists (changeover window), the tool
answers that the squad cannot be fetched right now.

---

### Task 3: Enrich picks with Supabase data

**Files:** `myteam/MyTeamService.java`; existing repositories or raw `JdbcTemplate`
queries.

**Produces:** For each `element` in the picks response, look up:
- `web_name`, `team_short_name`, `element_type` from tapas `player` / `team` tables.
- `now_cost`, `status`, `news` from `fpl_daily_price` (today's capture, season-scoped).
- `form`, `total_points`, `points_per_game`, `selected_by_percent`, `transfers_in_event`,
  `transfers_out_event` from the current season's player statistics.
- Upcoming fixture difficulty: average FDR over the next 5 fixtures from existing fixture
  data, using the same logic as `getFixtureDifficulty`.

**Performance:** One query per element is fine (15 players). Batch them in one
`WHERE element_id IN (...)` query per data source to keep it to a few round trips.

**Season scope:** `element_id` is season-scoped, so every query must also filter on the
resolved season. `fpl_daily_price` requires season + element_id.

---

### Task 4: Compose and cache `MyTeamService`

**Files:** `myteam/MyTeamService.java`, `myteam/MyTeamCache.java`,
`config/CacheConfig.java`.

**Produces:** `MyTeamService.fetchMyTeam(UUID userId)` returning
`Optional<MyTeam>` (or a result type).

**Flow:**
1. Read `fpl_team_id` from `UserProfileService.teamIdOf(userId)`. If absent, return
   `NoTeamId`.
2. Resolve current event id. If absent, return `NoCurrentEvent`.
3. Check cache by `(userId, eventId)`. If hit, return cached value.
4. Call `FplPicksClient` for entry + picks. Map `EntryNotFound` → `TeamNotFound`,
   `EventNotFound` → `NoCurrentEvent`, `Unavailable` → `Unavailable`.
5. Enrich picks.
6. Build `EntrySnapshot` and `List<SquadPlayer>`.
7. Cache and return.

**Cache:** Caffeine, TTL 5 minutes, max 1000 entries. Key is `(UUID userId, int eventId)`.

---

### Task 5: Expose `getMyTeam` tool

**Files:** `myteam/MyTeamTools.java`.

**Produces:** One `@Tool` method:

```java
@Tool(description = """
    Returns the user's own FPL squad, bank balance, team value, and key player stats.
    Use this when the user asks how to improve their team, who to transfer, who to
    captain, or whether to play a chip. If the user has not stored a team id, say how
    to set it instead.
    """)
public MyTeam getMyTeam()
```

The method reads the authenticated user's id from the `Jwt` (like other controllers) or
receives `UUID userId` from the tool invocation context if Spring AI provides it. The
project's existing tools receive `UUID` parameters where needed; mirror that pattern.

**Return type:** a single serializable record `MyTeam(EntrySnapshot entry,
List<SquadPlayer> squad)`.

---

### Task 6: Update the system prompt

**Files:** `resources/prompts/system-prompt.st`.

**Produces:** A new "My team" subsection under the advice/strategy section:

> When the user asks about improving their team, transfers, captain choice, chip use, or
> squad structure, first call `getMyTeam()`. If it returns a squad, base your answer on
> what they already own, their bank, and upcoming fixtures — use the other stats tools to
> compare their players with alternatives. If it says no team id is stored, tell them to
> set their FPL team id in profile and do not pretend to know their squad.

Also remove or update the existing sentence "If you can see the user's squad, factor in
what they already own; otherwise give general advice — ask about their team only when it
would change the answer." because the model can now see the squad conditionally.

---

### Task 7: Tests

**Files:** `myteam/MyTeamToolsTest.java`, `myteam/MyTeamServiceTest.java`,
`myteam/FplPicksClientTest.java`.

**Coverage:**
- No `fpl_team_id` stored → tool returns a clear "not set" message.
- Stored id but FPL returns 404 → "team id not found" message.
- FPL down → "FPL unavailable" message, distinct from not-found.
- Happy path → squad returned with bank, value, captain, enriched stats.
- Cache hit → second call within TTL does not make a second HTTP request.
- Season-scope enrichment → `fpl_daily_price` query includes season.

Use the same `HttpServer` stub pattern as `SupabaseAdminClientTest` and
`FplEntryLookup` tests.

---

### Task 8: Documentation and close-out

**Files:** `docs/DEVELOPMENT.md`, `docs/STATUS.md`.

**Produces:**
- `DEVELOPMENT.md`: a "My team advice" section explaining that FPL data is fetched at
  chat time and how to interpret `bank` / `teamValue`.
- `STATUS.md`: a short entry noting the feature, the chat-time exception, and the tests
  run (suite green count).

---

## Mini-league extension

This section extends the plan so the assistant can answer questions like *"how can I
improve my position in this mini-league?"*. The call is implicit: the model uses these
tools only when the user brings up a mini-league.

### Task 9: Mini-league client

**Files:** create `myteam/FplMiniLeagueClient.java`.

**Produces:** Typed clients for:
- `GET /api/entry/{entry_id}/` — reads the `leagues.classic` array from the entry summary.
- `GET /api/leagues-classic/{league_id}/standings/` — reads classic league standings
  (handles pagination if the user is not on the first page).

**Result type:** keep the same three-way distinction as `FplPicksClient`:
`Ok(...)`, `NotFound`, `Unavailable`.

### Task 10: Mini-league service and cache

**Files:** create `myteam/MiniLeagueService.java`; extend `myteam/MyTeamCache.java`.

**Produces:**
- `List<MiniLeague> getMyMiniLeagues(UUID userId)` — from the entry summary's
  `leagues.classic` list.
- `MiniLeagueOpponents getMiniLeagueOpponents(UUID userId, int leagueId)` — finds the
  user's rank in the standings, fetches the entries ranked within ±5 positions, and
  returns their squads (reusing `FplPicksClient` and the same enrichment logic as
  `MyTeamService`).

**Caching:** cache standings and opponent squads for 5 minutes keyed by
`(userId, leagueId, currentEventId)`.

### Task 11: Expose mini-league tools

**Files:** create `myteam/MiniLeagueTools.java`.

**Produces:**

```java
@Tool(description = """
    Lists the user's FPL classic mini-leagues with current rank and league size.
    Use this when the user asks about improving their position in a mini-league and
    has not yet named one.
    """)
public List<MiniLeague> getMyMiniLeagues()

@Tool(description = """
    Returns the FPL teams of opponents ranked near the user in a specific classic
    mini-league. Use this after the user has named or selected a league, then compare
    the returned squads to the user's own team to suggest moves that gain ground.
    """)
public MiniLeagueOpponents getMiniLeagueOpponents(int leagueId)
```

### Task 12: Update prompt and tests for mini-leagues

**Files:** `resources/prompts/system-prompt.st`; create `myteam/MiniLeagueToolsTest.java`.

**Produces:**
- Prompt text already added in Task 6 is extended with the mini-league instruction
  (see spec). If Task 6 is done first, this is a small amendment.
- Tests covering:
  - User with no mini-leagues.
  - League not found.
  - Standings page pagination (user not on first page).
  - Nearby opponents fetched with squads.
  - No opponent squads fetched when FPL is unavailable.

---

## Out of scope

- Live gameweek points (`/api/event/{event_id}/live/`).
- A frontend "my team" page or dashboard.
- Persisting picks to a new table.
- Automatic tool invocation (the model decides based on the prompt).
