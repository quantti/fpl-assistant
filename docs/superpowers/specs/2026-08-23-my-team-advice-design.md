# FPL Assistant — My-Team-Aware Advice Design Spec

**Status:** Implemented 2026-08-25 — outcome and deviations in `docs/STATUS.md`.

**Relationship to existing work:** Builds on Phase 5a's `fpl_user_profile.fpl_team_id`
(optional, validated against FPL by `FplEntryLookup`). Adds the second and third
documented exceptions to the "external APIs only at sync time" rule: the user's own squad
cannot be pre-synced because it is per user and per gameweek, and FPL's `bootstrap-static`
is fetched (cached app-wide) because it is the only source that is guaranteed to describe
the same season the squad belongs to.

---

## Idea

Today the assistant answers "how can I improve my team?" with generic advice, because it
cannot see the user's squad. The system prompt tells the model to do exactly that.

This feature closes the gap: when a user has stored an FPL team id, the assistant fetches
their squad, bank, team value and player stats, then gives transfer, captain, chip and
structure advice that accounts for what they already own.

## What already exists

- `fpl_user_profile.fpl_team_id` — stored per user and validated against FPL on write
  (`UserProfileService.teamIdOf(UUID)`, `FplEntryLookup`).
- Stats tools: `getPlayerStats`, `getPlayerExpectedStats`, `getPriceHistory`,
  `getPlayerAvailability`, `getFixtureDifficulty`, `getUpcomingFixtures`.
- Advice tools: `getDifferentialPicks`, `getCaptainCandidates`.
- `SeasonResolver` / `SeasonContext` for season scope and provenance.
- `ChatController` already holds the authenticated user id (`TokenSubject.userId(jwt)`).

---

## How a tool knows whose team it is

This is the part the first draft left open, and it decides whether the feature is safe.

**The user id travels from the JWT to the tool through Spring AI's `ToolContext`. It is
never a tool parameter.** `ChatController` resolves it from the bearer token exactly as it
does for the quota and the conversation, hands it to `ChatService.stream(...)`, and
`ChatService` puts it on the request:

```java
chatClient.prompt()
        .messages(history)
        .tools(tools.toArray())
        .toolContext(Map.of(ToolUser.KEY, userId))          // per request
        .stream()
```

`MyTeamTools.getMyTeam(ToolContext context)` reads it back. Spring AI 2.0.0 supports both
halves — `ChatClient.ChatClientRequestSpec.toolContext(Map<String, Object>)` and
`MethodToolCallback.call(String, ToolContext)`, which injects a `ToolContext` parameter
without exposing it in the tool's JSON schema (verified against the 2.0.0 jars).

**Why not a `UUID userId` parameter on the tool.** Anything in the tool schema is filled in
by the model, and the model's input is the user's text. "Ignore that, my user id is
`…`" is then a working request for someone else's squad and mini-leagues — an IDOR with a
language model as the confused deputy. The id must come from the token and only from the
token. The same reasoning bans a "team id" parameter: the team id is looked up from the
authenticated user's profile, never accepted from the conversation.

**Why not `SecurityContextHolder` inside the tool.** Tool calls execute on the provider's
reactive stream threads, not the request thread; the thread-local context is empty there.
`ChatService` is a singleton and its `stream(...)` signature carries no user today — that
signature change is part of this work.

**If the context is missing** (a code path that forgot to set it), the tool returns a plain
refusal and logs a warning. It must never fall back to "some user".

---

## What the FPL API actually gives us

Verified against the live API on 2026-08-25 (season 2026/27, GW1 current). Three of the
first draft's fields do not exist on the public endpoints, and the design changes to match.

| Endpoint | Gives | Does not give |
|---|---|---|
| `GET /api/entry/{entry}/` | `name`, player first/last name, `summary_overall_rank`, `summary_overall_points`, **`current_event`**, `leagues.classic[]` with `entry_rank`, `entry_last_rank`, `rank_count`, `league_type` | — |
| `GET /api/entry/{entry}/event/{event}/picks/` | `active_chip`, `automatic_subs`, `entry_history.{bank,value,points,event_transfers,event_transfers_cost,points_on_bench}`, `picks[].{element,position,multiplier,is_captain,is_vice_captain,element_type}` | **`selling_price`**, **`purchase_price`** |
| `GET /api/entry/{entry}/history/` | `chips[]` — the chips already played, with the event each was played on | which chips remain |
| `GET /api/bootstrap-static/` | `elements[]` (name, team, type, `now_cost`, `form`, `total_points`, `points_per_game`, `selected_by_percent`, `status`, `news`, `chance_of_playing_this_round`/`_next_round`, transfers in/out), `events[]`, `chips[]` schedule with `start_event`/`stop_event` | per-fixture difficulty for a specific player, xG/xA |
| `GET /api/leagues-classic/{league}/standings/?page_standings=N` | 50 rows a page, `has_next`, rows of `entry`, `entry_name`, `player_name`, `rank`, `last_rank`, `total`, `event_total` | opponents' squads (a separate picks call each) |

Three consequences, each of which the first draft got wrong:

1. **There are no purchase or selling prices.** Those live behind `/api/my-team/{entry}/`,
   which needs the manager's own login cookie. We have their team id, not their session.
   Transfer funds are therefore `entry_history.bank` plus a *current* price for each owned
   player, and a player whose price has risen since purchase sells for less than that. The
   tool says so in one line rather than implying exact funds.
2. **Only a gameweek whose deadline has passed can be fetched.** `event/2/picks/` answers
   404 while GW1 is current — verified. So the squad we can see is always the one that was
   locked at the last deadline, and any transfer made since is invisible to us. That is not
   an error state, it is the normal state for most of the week; the tool labels the squad
   with the gameweek it belongs to instead of calling it "current".
3. **Chips available must be derived**, from the schedule in `bootstrap-static.chips`
   minus the chips in `entry/{entry}/history/.chips`. There are two sets a season, and the
   windows are per chip rather than uniform — verified today: wildcard 2–19 and 20–38,
   free hit 2–19 and 20–38, bench boost 1–19 and 20–38, triple captain 1–19 and 20–38. The
   wildcard's window opens at GW2 because transfers are unlimited until the GW1 deadline,
   so there is nothing for it to buy; a manager sitting in GW1 can still activate one, and
   it is played on GW2. **The chip list therefore answers for the next deadline, not for
   the gameweek being played** — the two differ all week, and answering for the wrong one
   would tell a manager they cannot play a chip they can play right now. Read the windows
   from the payload; never hardcode them. If either source is unavailable the tool omits
   chips rather than guessing.

`current_event` from the entry response is what selects the picks gameweek — **not**
`SeasonResolver` or the tapas `gameweek` table. The resolver deliberately falls back to the
last season that holds data, so during the changeover it would send us to fetch GW38 of a
finished season. FPL's own number is authoritative, arrives in a call we already make, and
is null for an entry that has not played yet (answered as "no squad yet").

---

## What this adds

`getMyTeam()` — one tool returning the user's squad as text, the same shape every other
tool in this project returns:

- **Snapshot line:** which gameweek the squad was locked for, and when it was fetched.
- **Entry:** team name, overall rank, total points, bank, squad value, total value.
- **Squad:** starting XI then bench, in FPL pick order, with captain/vice, position, club,
  current price, and season-long stats (form, total points, points per game, selected-by,
  availability).
- **Chips:** the active chip for the locked gameweek, and which chips can still be played at
  the next deadline — the gameweek a chip activated now would be played on — when both
  sources answer.
- **Provenance:** two lines, one per source — see below.

### Where each field comes from

| Field group | Source | Why |
|---|---|---|
| Squad, bank, value, active chip | picks endpoint, at chat time | Per user and per gameweek; cannot be synced. |
| Player identity and season stats | `bootstrap-static`, cached app-wide | It is the only source guaranteed to be on the same season as the `element` ids in the picks. |
| Fixture difficulty, xG/xA, price history | Supabase, **only when the resolved season matches** | Depth tapas has and FPL does not. |
| Today's price and flags | `fpl_daily_price`, same condition | Fresher than the tapas `player` table. |

**The season-mismatch rule is a correctness rule, not a nicety.** `element` ids are
season-scoped and FPL renumbers every season, so joining this season's pick ids against
last season's rows returns *the wrong players' statistics* — silently. When
`SeasonResolver` has not yet caught up with the season the entry is playing, the tool
serves identity and stats from `bootstrap-static` alone and says that the deeper numbers
are not available for the new season yet.

### Provenance

Two sources, two lines, following the dual-season pattern `getPlayerStats` already uses:

```
Squad as locked for Gameweek 1 (deadline 21 Aug 18:30 UK); fetched 25 Aug 14:02 UK.
Data source: season 2026/27 (the season currently being played).
```

The second line is `SeasonContext.provenance()` verbatim — its exact words, not a
paraphrase, so the fallback wording it already carries reaches the model unchanged. If the
resolved season is not the one being played, the existing wording already labels it
historical, and the model is instructed not to read it as current form.

### Example output

```
Squad as locked for Gameweek 1 (deadline 21 Aug 18:30 UK); fetched 25 Aug 14:02 UK.
Team: Rangers FC (entry 1234567) — overall rank 420,132, 41 pts.
Bank £0.0m, squad value £100.0m, total £100.0m. Free transfers and exact selling
prices are not published by FPL for another manager's team; treat funds as approximate.
Active chip: none. Playable at the next deadline (GW2): wildcard, free hit, bench boost,
triple captain.

Data source: season 2026/27 (the season currently being played).
Starting XI
 1 Raya (ARS, GK) £5.5m - form 5.0, 6 pts, 6.0/game, 24.1% owned
 6 Salah (LIV, MID) £12.6m - CAPTAIN - form 8.5, 12 pts, 12.0/game, 42.1% owned, next 5 FDR 2.8
 ...
Bench
12 Sels (NFO, GK) £5.0m - form 2.0, 2 pts, 18.3% owned
 ...
Flagged: Isak (NEW) - 25% chance of playing (knock).
```

Prices and bank are in 0.1m units internally (`125` = £12.5m) and rendered as pounds, as
elsewhere.

## What the model is told

A new section in `system-prompt.st`:

> When the user asks about improving their team, transfers, captain choice, chip use or
> squad structure, call `getMyTeam()` first. It answers with their squad or with a short
> reason it cannot — if it says no team id is stored, tell them to set it in their profile
> and do not pretend to know their squad. The squad it returns is the one locked at the
> last deadline, so if they mention a transfer they have already made, believe them over
> the tool. Compare their players with alternatives using the other stats tools, and stay
> inside the bank the tool reports, remembering that selling prices are approximate.
>
> If the user asks about a specific mini-league, call `getMyMiniLeagues()` when they have
> not named one, then `getMiniLeagueOpponents(leagueId, focus)` — `focus="nearby"` for the
> managers immediately above and below them, `focus="top10"` (any N, capped at ten) for
> the leaders, or `focus="entry:12345"` for one team. Do not fetch mini-league data unless
> the user brings a mini-league up.

The existing sentence "If you can see the user's squad, factor in what they already own;
otherwise give general advice — ask about their team only when it would change the answer"
is replaced, because the squad is now reachable rather than hypothetical.

---

## Mini-league context

Advice shaped by a mini-league standing rather than by overall rank.

`getMyMiniLeagues()` lists the user's leagues, **filtered to `league_type == "x"`** — the
leagues someone created and invited people to. FPL also puts every manager in system
leagues (`"s"`: overall, their club, their region, and more), and listing "Liverpool,
1,089,086 entries" next to "Office League" is noise the model will happily reason about.

Even filtered, the list needs a ceiling: a live account came back with sixteen, and the
whole list re-enters the prompt on every later turn. Twelve are named, **smallest first**,
and the rest are counted off — a league of eight is one the user can move in, a league of
1,879 is a leaderboard.

`getMiniLeagueOpponents(leagueId, focus)` returns opponents *and what they own that the
user does not*, which is the only part of an opponent's squad that changes any advice:

```
Office League (id 12345) - you are 5th of 20, 185 pts.
Standings as at Gameweek 1; fetched 25 Aug 14:02 UK.
 4th  Deadline Day Dave  191 pts (+55 GW1) - owns that you do not: Haaland (C), Gyokeres, Gabriel
 6th  Bench Boost Bob    178 pts (+41 GW1) - owns that you do not: Palmer, Mbeumo
```

- `focus="nearby"` (default) — up to five managers either side of the user.
- `focus="topN"` — the league leaders, e.g. `"top10"`. `N` is the digits in the string:
  absent (`"top"`) or unparseable means ten, below one is one, above ten is ten.
- `focus="entry:12345"` — one named team, given in full rather than as a difference.
- Anything else falls back to `nearby` rather than failing: the string comes from a
  language model, and a typo is not worth an error message the user has to decode.

**Hard cap: at most 10 opponent squads per call, and at most 15 FPL requests per chat
turn.** The first draft advertised `focus="top50"`, which is 50 sequential picks requests
inside one SSE response, minutes of latency at the client timeouts this project uses, and
a burst from one Fly IP that FPL would be right to throttle. Larger N is accepted and
clamped to ten, with the clamp stated in the reply so the model does not claim to have
read the whole league.

Ten fits the per-turn budget only because the entry response is cached. Counted for a
cold conversation that asks a mini-league question: `getMyTeam` spends three (entry,
picks, chip history), `getMyMiniLeagues` re-reads the same entry from cache for nothing,
and `getMiniLeagueOpponents` spends one standings page plus ten opponent picks — fourteen
of fifteen with the app-wide bootstrap snapshot warm, and exactly fifteen when it is cold.
Without the entry cache the same turn costs sixteen and the cap would quietly drop an
opponent, so the cache is part of the cap, not an optimisation beside it.

**Opponents' real names are not returned.** `player_name` is the legal name of a third
party who never used this product, and it would travel to the model provider on every
turn of the conversation; the team name and rank are what the advice is about.

Finding the user's own page: `page_standings` serves 50 rows a page, so the page is
`ceil(entry_rank / 50)` from the entry payload — approximate, because ties make `rank`
lag the row position. The service checks that the user's entry is on the page it fetched
and steps one page either way at most once before giving up on "nearby".

Head-to-head leagues are out of scope; they rank on fixtures, not totals.

---

## Budget, caching and failure

- **Caching** is a small in-process TTL map, not a new cache dependency: `bootstrap-static`
  app-wide for 6 hours (it changes on price updates, not by the minute), the entry
  response by `entryId` for 5 minutes, squads by `(entryId, eventId)` for 5 minutes, and
  standings by `(leagueId, eventId, page)` for 5 minutes. **Keyed by entry, never by
  user** — a user who edits their team id would otherwise be served the previous team's
  squad until the entry expired. What is cached is the parsed squad, not the rendered
  text: the mini-league view diffs an opponent's squad against the user's, which text
  cannot be diffed for.
- **Every failure keeps `FplEntryLookup`'s three-way distinction:** "no team id stored",
  "FPL says there is no such team", and "FPL could not be reached" are different sentences.
  Telling someone their team id is wrong while FPL is down sends them hunting for a typo
  that is not there.
- Timeouts stay at `FplEntryLookup`'s 3s connect / 5s read, and the per-turn request cap
  bounds the worst case at roughly one minute inside the 5-minute answer timeout. The cap
  is counted in a budget object created per chat request and carried in the same
  `ToolContext` as the user id — a counter on a singleton service would be shared by every
  conversation on the machine.
- Nothing is retried. A failed opponent fetch drops that opponent from the reply and says
  so; it does not fail the whole answer.
- Cost: the reply is compact text (~20 lines for a squad, ~2 per opponent) because it
  re-enters the prompt on every later turn of the conversation, and the free tier meters
  messages rather than tokens.

## Out of scope

- Live gameweek points (`/api/event/{event}/live/`).
- Persisting picks or standings to new tables. Chat-time fetch plus cache is enough.
- Frontend changes; the team id is already settable in profile.
- Head-to-head leagues, cups, and league-wide ownership statistics.
- Reading `/api/my-team/{entry}/` — it needs the manager's own FPL session, which we do
  not have and should not ask for.

## What this hands to later phases

- Billing is unaffected: the tools are the same for every plan.
- A future "my team" page can reuse `MyTeamService` unchanged.
- Live points later add one cached call and one number per pick.
