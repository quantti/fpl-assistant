# My-Team-Aware Advice Implementation Plan

**Spec:** `docs/superpowers/specs/2026-08-23-my-team-advice-design.md` — read it first;
this plan argues from it and does not repeat its reasoning.

**Goal:** Turn the stored `fpl_user_profile.fpl_team_id` into personalised advice. When a
user asks how to improve their team, the assistant fetches the squad FPL locked at the last
deadline, their bank and value, and the season stats for those players, then compares them
with the existing stats tools.

**Architecture:** `MyTeamService` fetches the user-specific FPL data (entry + picks +
chip history) at chat time, fills in player identity and season stats from a cached
`bootstrap-static`, adds Supabase depth when the resolved season matches, renders text, and
caches by entry. `MyTeamTools` exposes it. `MiniLeagueService` / `MiniLeagueTools` add the
mini-league view. The user id reaches the tools through Spring AI's `ToolContext`, never as
a tool parameter.

**Tech stack:** Spring Boot 4.1 / Java 25, `RestClient`, `JdbcTemplate`; no new
dependencies. React unchanged — this phase has no frontend.

**PROGRESS:** Task 0/13 done

## Global constraints

- **The user id comes from the JWT and only from the JWT.** No `@Tool` method takes a user
  id, a team id, or anything else that identifies an account: the model fills tool
  parameters from the conversation, and a user id it can write is a user id an attacker can
  ask it to write. Same for the team id — always looked up from the authenticated user's
  profile.
- No schema changes. No new tables, no new columns.
- FPL is called at chat time, which is a documented exception to "external APIs only at
  sync time" — recorded in `AGENTS.md` in Task 12 alongside the existing `FplEntryLookup`
  exception.
- **At most 15 outbound FPL requests per chat turn, at most 10 opponent squads per
  mini-league call.** Enforced in the service, not left to the tool description.
- `element` ids are season-scoped. Nothing joins a pick's `element` against Supabase rows
  unless the resolved season is the season the entry is playing (Task 5).
- Every failure distinguishes "no team id", "no such team", and "FPL unreachable", as
  `FplEntryLookup` does.
- Tools return `String`, like all thirteen existing ones, with the provenance lines the
  spec defines.

## Decisions this plan makes

1. **The picks gameweek is FPL's `current_event`, not `SeasonResolver`.** The resolver
   falls back to the last populated season by design, which during the changeover would
   have us fetch GW38 of a finished season. FPL's number arrives in a call we already make.
2. **Player identity and season-long stats come from `bootstrap-static`, cached app-wide.**
   It is the only source certain to be on the same season as the pick ids. Supabase adds
   what FPL does not publish (fixture difficulty, xG/xA, price history) and only when the
   seasons agree.
3. **One tool per question, returning text.** `getMyTeam` returns the whole picture in one
   call — splitting it would cost extra model round trips — and returns rendered text so
   the provenance discipline and the token budget stay under our control.
4. **The cache is a ~40-line TTL map, not a new cache library.** Three caches with fixed
   TTLs do not justify a dependency, `@EnableCaching`, and a `CacheConfig` this project
   does not have today.

## File structure

| File | Responsibility |
|---|---|
| `chat/ChatController.java` (modify) | Pass the JWT's user id into `ChatService.stream`. |
| `chat/ChatService.java` (modify) | Put the user id on the request via `.toolContext(...)`. |
| `myteam/ToolUser.java` (new) | The `ToolContext` key and the read-it-or-refuse helper. |
| `myteam/TtlCache.java` (new) | Small in-process TTL map. |
| `myteam/FplEntryClient.java` (new) | `entry/{id}/`, `entry/{id}/event/{ev}/picks/`, `entry/{id}/history/`. |
| `myteam/FplBootstrap.java` (new) | Cached `bootstrap-static`: elements, events, chip schedule. |
| `myteam/MyTeamService.java` (new) | Compose, enrich, cache, render. |
| `myteam/SquadEnrichment.java` (new) | Supabase depth, gated on season match. |
| `myteam/MyTeamTools.java` (new) | `@Tool getMyTeam(ToolContext)`. |
| `myteam/MiniLeagueService.java` (new) | Leagues, standings pages, opponent differences. |
| `myteam/MiniLeagueTools.java` (new) | `@Tool getMyMiniLeagues`, `@Tool getMiniLeagueOpponents`. |
| `resources/prompts/system-prompt.st` (modify) | When to call each tool. |
| `resources/application.yml` (modify) | The four FPL URLs, TTLs, and the request caps. |
| Tests | `ToolContextUserTest`, `FplEntryClientTest`, `MyTeamServiceTest`, `MyTeamToolsTest`, `MiniLeagueServiceTest`, `MiniLeagueToolsTest`. |

---

### Task 0: Get the user id to the tools — safely

**Files:** `chat/ChatController.java`, `chat/ChatService.java`, `myteam/ToolUser.java`.

Nothing else can be built first: today `ChatService` is a singleton whose `stream(turns,
onUsage)` carries no user, tool calls run on the provider's reactive threads where
`SecurityContextHolder` is empty, and no existing tool takes a user id.

1. `ChatService.stream(List<ChatTurn> turns, UUID userId, Consumer<TokenUsage> onUsage)`
   adds `.toolContext(Map.of(ToolUser.KEY, userId))` to the prompt.
2. `ChatController` passes `TokenSubject.userId(jwt)` — it already resolves it for the
   quota and the conversation.
3. `ToolUser.required(ToolContext)` returns the `UUID` or throws; tools translate that into
   a one-line refusal and a `WARN`. It must never default to any user.

**Verify:** a test registers a probe `FplTool` that records the id it saw, drives a chat
request with a stubbed model, and asserts the id equals the token's subject; a second
asserts the tool's JSON schema has no user id property (Spring AI excludes `ToolContext`
from the schema — pin it, because the whole safety argument rests on it).

---

### Task 1: FPL entry client

**Files:** create `myteam/FplEntryClient.java`; `application.yml`.

Typed access to three endpoints, all `GET`, all public:
`entry/{entry}/`, `entry/{entry}/event/{event}/picks/`, `entry/{entry}/history/`.

1. Sealed `Result`: `Ok(T body)`, `NotFound`, `Unavailable` — the same three-way split
   `FplEntryLookup` makes, for the same reason.
2. Records for what we read (verified against the live API on 2026-08-25):
   - entry: `id`, `name`, `player_first_name`, `player_last_name`,
     `summary_overall_rank`, `summary_overall_points`, `current_event` (nullable),
     `leagues.classic[]` with `id`, `name`, `short_name`, `league_type`, `entry_rank`,
     `entry_last_rank`, `rank_count`.
   - picks: `active_chip`, `entry_history.{bank,value,points,event_transfers,
     event_transfers_cost,points_on_bench}`, `picks[].{element,position,multiplier,
     is_captain,is_vice_captain,element_type}`. **There is no `selling_price` and no
     `purchase_price` on this endpoint** — they are behind the manager's own session.
   - history: `chips[].{name,event}` — chips already played.
3. Timeouts and the `JdkClientHttpRequestFactory` setup copied from `FplEntryLookup`
   (3s connect, 5s read, no retries). Log the failure type only: the URI embeds the entry
   id, which links an account to a real person's public profile.

**Tests:** `HttpServer` stub — 200 entry + 200 picks → `Ok`; 404 entry → `NotFound`;
**404 picks → `Ok` with no squad for that event, not an error** (this is what FPL answers
for any gameweek whose deadline has not passed — verified); 500 → `Unavailable`.

---

### Task 2: Cached bootstrap-static

**Files:** create `myteam/FplBootstrap.java`, `myteam/TtlCache.java`.

**Produces:** `elementsById()` — web name, team short name, element type, `now_cost`,
`form`, `total_points`, `points_per_game`, `selected_by_percent`, `status`, `news`;
`seasonEvents()` — the current event and its deadline; `chipSchedule()` — the
`start_event`/`stop_event` windows, which now come in two sets a season.

One app-wide entry, TTL 6 hours, refreshed on read. The payload is ~1MB, so it is parsed
into the trimmed records above and the response is dropped. When the fetch fails, the last
good value is served if there is one, and only a cold failure degrades the tool.

**Tests:** stub serves a trimmed bootstrap; second call inside the TTL makes no second
request; a failing refresh keeps serving the previous value.

---

### Task 3: Chips available

**Files:** `myteam/MyTeamService.java`.

`chipSchedule()` windows containing the current event, minus the names in the entry's
`history.chips[]` played inside that window. If either source is missing, chips are omitted
from the reply — never guessed. FPL's names (`wildcard`, `freehit`, `bboost`, `3xc`) are
mapped to the words the prompt already uses.

**Tests:** nothing played → all four; wildcard played in GW3 → the GW1–19 window shows
three, and the GW20–38 window still shows four.

---

### Task 4: Compose the squad

**Files:** `myteam/MyTeamService.java`.

`myTeam(UUID userId)` → a result type covering every outcome the spec names:

1. `UserProfileService.teamIdOf(userId)` — empty → `NoTeamId`.
2. Fetch the entry — `NotFound` → `TeamNotFound`, `Unavailable` → `Unavailable`.
3. `current_event` null → `NoSquadYet` (an entry that has not played a gameweek).
4. Cache lookup by `(entryId, currentEvent)` — **keyed by entry, not by user**, so editing
   the stored team id cannot serve the old team's squad.
5. Fetch picks for `current_event`; fetch history for chips.
6. Fill each pick from `FplBootstrap`, then `SquadEnrichment` (Task 5).
7. Render (Task 6), cache the rendered text for 5 minutes, return.

---

### Task 5: Supabase depth, gated on the season

**Files:** create `myteam/SquadEnrichment.java`.

For the fifteen `element` ids: average FDR over the next five fixtures (the
`getFixtureDifficulty` logic), xG/xA where present, and today's price and flags from
`fpl_daily_price` (season + `element_id`, as that table requires).

**The gate:** run this only when `SeasonResolver.resolve()` names the season the entry is
playing. Otherwise the pick ids belong to a season the database has not synced, the join
matches *different players*, and the reply would be confidently wrong. When the gate is
closed the reply says the deeper numbers are not yet available for the new season.

**Tests:** the enrichment query filters on season and on the id set (one batched query per
source, not fifteen); with a mismatched season nothing is joined and the reply says so.

---

### Task 6: Rendering and provenance

**Files:** `myteam/MyTeamService.java`.

The layout in the spec: snapshot line, entry line, the funds caveat, chips, then
`SeasonContext.provenance()` above the stats block, starting XI in pick order, bench,
flagged players. About twenty lines — it re-enters the prompt on every later turn, and the
free tier meters messages rather than tokens.

The snapshot line names the gameweek and its deadline and never says "current": the squad
is the one locked at the last deadline, and a transfer made since is invisible to us.

---

### Task 7: The tool

**Files:** create `myteam/MyTeamTools.java`.

```java
@Tool(description = """
    The user's own FPL squad as locked at the last deadline, with bank, squad value,
    chips and season stats. Use it before advising on transfers, captaincy, chips or
    squad structure. Takes no arguments - the account is known from the session.
    """)
public String getMyTeam(ToolContext context)
```

Implements `FplTool`, so `ChatService` registers it automatically. Each outcome from Task 4
becomes one sentence the model can act on — a missing team id tells the user where to set
it; an unreachable FPL says to try again rather than blaming the id.

---

### Task 8: System prompt

**Files:** `resources/prompts/system-prompt.st`.

Add the "my team" instruction from the spec and replace the existing line at 119 ("If you
can see the user's squad, factor in what they already own; otherwise give general
advice…"), which assumes the squad is hypothetical. Include the two warnings the tool's
data forces: the squad is last deadline's, and selling prices are approximate.

---

### Task 9: Tests for the core

**Files:** `myteam/MyTeamServiceTest.java`, `myteam/MyTeamToolsTest.java`.

`HttpServer` stubs for FPL, as `SupabaseAdminClientTest` does. Anything reading tapas
tables seeds through `TapasSeed`; the `fpl_daily_price` rows are seeded by the test, per the
project's testing convention. Cover: no team id; team id FPL does not know; FPL down; happy
path with bank, captain, chips and stats; a second call inside the TTL making no second
request; a changed team id returning the new squad rather than the cached one; the
season-mismatch path; and no user id in the tool context.

---

### Task 10: Mini-league service

**Files:** create `myteam/MiniLeagueService.java`.

- `myLeagues(userId)` — `leagues.classic` filtered to `league_type == "x"`. The system
  leagues (`"s"`: overall, club, region) are every manager's, and a million-entry league in
  the list is noise the model will reason about.
- `opponents(userId, leagueId, focus)`:
  - `"nearby"` (default, and what a null means) — the user's page from
    `ceil(entry_rank / 50)`, checked for the user's own entry and stepped one page either
    way at most once, since ties make `rank` lag the row position.
  - `"topN"` — page 1, N teams, e.g. `"top10"`; N is clamped to ten.
  - `"entry:12345"` — that entry alone, returned in full rather than as a difference.
  - `"top50"` and friends are accepted and **clamped to the 10-squad cap, with the clamp
    stated in the reply** so the model cannot claim to have read the whole league. Ten fits
    the per-turn budget: one standings page, ten picks calls, and the user's own squad
    (usually already cached).
- Each opponent's squad comes from the same picks call and cache as the user's own.
- The reply lists only what an opponent owns that the user does not, plus their captain:
  that is the part that changes advice, and it is two lines instead of fifteen.
- **`player_name` is dropped.** It is a third party's legal name, and it would travel to
  the model provider on every subsequent turn.
- A per-turn counter enforces the 15-request cap; an opponent that fails is dropped from
  the reply with a note, not retried, and does not fail the answer.

**Caching:** standings by `(leagueId, event, page)` and squads by `(entryId, event)`.
Neither is keyed by user — standings are the same for everyone who asks.

---

### Task 11: Mini-league tools and prompt

**Files:** create `myteam/MiniLeagueTools.java`; `resources/prompts/system-prompt.st`.

```java
@Tool(description = """
    The user's FPL mini-leagues (the ones people created and invited each other to),
    with their rank in each. Use it when they ask about a mini-league without naming one.
    """)
public String getMyMiniLeagues(ToolContext context)

@Tool(description = """
    Teams from one of the user's mini-leagues, with what they own that the user does not.
    focus="nearby" for the managers just above and below (default), "top10" (any N,
    capped at ten) for the leaders, or "entry:12345" for one team. At most ten teams are
    returned.
    """)
public String getMiniLeagueOpponents(int leagueId,
                                     @ToolParam(required = false) String focus,
                                     ToolContext context)
```

`leagueId` stays a parameter — it names a league, not an account, and the service checks
that the authenticated user is a member of it before fetching anything.

---

### Task 12: Docs and close-out

**Files:** `AGENTS.md`, `docs/DEVELOPMENT.md`, `docs/STATUS.md`.

- `AGENTS.md`: record the chat-time FPL exception (user-specific squad plus cached
  `bootstrap-static`) beside the existing `FplEntryLookup` one, and the rule that no tool
  takes an identifier the model can write.
- `DEVELOPMENT.md`: what `bank`, `value` and the deadline snapshot mean, and why selling
  prices are absent.
- `STATUS.md`: one entry — the feature, the two exceptions, the caps, and the suite result.

---

## Out of scope

- Live gameweek points (`/api/event/{event}/live/`).
- `/api/my-team/{entry}/`, which needs the manager's own FPL session.
- Persisting picks or standings; a frontend page; head-to-head leagues.
