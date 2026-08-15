# FPL Assistant — Phase 4: Enrichment Design Spec

**Supersedes:** Where this spec differs from `docs/superpowers/specs/2026-06-27-fpl-assistant-design.md`, this document wins.

**Status:** draft pending review — do not implement before the owner has signed off on both this spec and `docs/superpowers/plans/2026-08-15-phase4-enrichment.md`.

---

## Idea

Phases 1–3 built the chatbot, RAG strategy layer, and historical match data. Phase 4 enriches the advice the assistant can give by exposing data that already exists in the shared Supabase (`tapas-fpl-app`) plus one new table for current-season player-price trends. The phase is deliberately bounded: no auth, no billing, no squad import — those are Phase 5. The goal is to make the assistant useful for squad-building decisions beyond "what happened" and "what is scheduled".

New capabilities:

- **Expected goals (xG) tools** — aggregate expected goals, expected assists, and expected goal involvements (FPL's own expected-stats columns in `player_fixture_stats`) by team or player over recent fixtures/GWs.
- **Differential / captain picks** — surface low-ownership, high-form players with good fixtures.
- **Fixture-difficulty outlook** — team-specific view of upcoming FDR ratings.
- **Price trend tools** — current-season price history so the user can see whether a player's price is rising or falling, judge buy timing, and manage team value.

## What already exists, and what does not

The shared Supabase project already contains:

| Table | Relevant columns |
|---|---|
| `player` | `now_cost`, `selected_by_percent`, `form`, `total_points`, `expected_goals`, `expected_assists`, `expected_goal_involvements`, `element_type`, `team_id`, `status`, `news`, `code` |
| `player_fixture_stats` | `expected_goals`, `expected_assists`, `expected_goal_involvements`, `minutes`, `total_points`, `goals_scored`, `assists`, per fixture |
| `player_gw_stats` | per-GW points, minutes, goals, assists, etc. (no xG columns) |
| `fixture` | `team_h`, `team_a`, `team_h_difficulty`, `team_a_difficulty`, `gameweek`, `finished`, `kickoff_time` |

What does **not** exist:

- **Per-GW player price history for the current season.** `player.now_cost` is overwritten on every tapas sync. The FPL API exposes cumulative `cost_change_start`/`cost_change_event` and per-GW `value` inside `element-summary/{id}/history`, but only for the in-progress season. A persistent snapshot is needed so the assistant can answer "has this player's price been rising or falling?" without relying on a single overwritten column.
- **Price risers/fallers view.** Not a separate table, but a query over price snapshots that surfaces momentum.

## Why price history matters

Price movements are a core FPL squad-management signal:

- A rising price means the player is in demand; buying before the rise locks in future team value.
- A falling price may create a buying opportunity, but can also erode team value if the player is already owned.
- Team value directly affects budget for future transfers — a squad whose value rises faster than the market has more flexibility later in the season.

The assistant must therefore expose **trend** (rising/falling), **magnitude**, and **timing**, not just the current price.

## New table: `fpl_player_price`

Additive only — no tapas table is modified. Tracks only the season currently being played (the one resolved by `SeasonResolver`).

The key is the season-scoped FPL element id (vaastav's `element` / tapas `player.id`), **not** the persistent FPL `code`: the tapas `player` table does not carry `code`, and since this table only ever holds one season, cross-season identity is not needed.

```text
season            VARCHAR(10) NOT NULL    -- the in-progress season, e.g. '2026-27'
gameweek          INTEGER NOT NULL        -- 1..38; 0 for the latest snapshot before the GW is locked
element_id        INTEGER NOT NULL        -- season-scoped FPL element id (== tapas player.id == vaastav element)
player_name       VARCHAR(100) NOT NULL   -- denormalised, same rationale as pl_match_result
price             INTEGER NOT NULL        -- in 0.1m units, e.g. 140 = £14.0m
source            VARCHAR(20) NOT NULL    -- 'vaastav' or 'snapshot'
ingested_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
UNIQUE (season, gameweek, element_id, source)
```

`gameweek = 0` is reserved for the latest startup snapshot of `player.now_cost`. It is updated in place (upsert on `(season, 0, element_id, 'snapshot')`) so there is always one current "live" price row between GWs. When a GW finishes and the vaastav sync runs, rows with `gameweek = 1..38` and `source = 'vaastav'` are written/upserted, freezing the official end-of-GW price.

The sync only runs when `SeasonResolver` resolves to the season currently being played (`season.currentSeason() == true`). During the pre-season changeover, when the flagged-current 2026-27 season has no data and the resolver falls back to 2025-26, the sync skips with a log message.

## Data sources and sync rules

1. **vaastav `gws/merged_gw.csv` for the current season.** Parsed by a real RFC 4180 reader (Apache Commons CSV, already on the classpath). Each row's `value` is the player's price at that GW. Only rows with a numeric GW and a resolvable player code are written. This gives the official per-GW price after each GW without making 500+ FPL API calls. The file does not exist before the season starts (vaastav creates the `gws/` directory after GW1); a 404 or empty directory is expected and must be handled gracefully — the snapshot then carries the only price data.

2. **Startup price snapshot** — for the season resolved by `SeasonResolver` once it holds data, snapshot `player.now_cost` for every player into `fpl_player_price` with `gameweek = 0`, `source = 'snapshot'`. Runs on `ApplicationReadyEvent` off the request path, behind a persisted staleness marker (`MAX(ingested_at) WHERE source = 'snapshot'`), exactly like the football-data.org sync from Phase 3. The snapshot is intentionally shallow (one row per player) and only touches the in-progress season.

Both sources are hit **only at sync time**, never at chat time. The sync also checks `SeasonContext.currentSeason()` before writing — if `SeasonResolver` has fallen back to a completed season (as it does before 2026-27 holds data), the price sync is a no-op rather than writing stale fallback data into a current-season table. This mirrors the football-data.org precedence guard from Phase 3.

## Tools

All new stats tools implement `FplTool`, catch `DataAccessException`, log, and return an error string. They prefix replies with `SeasonContext.provenance()` and follow the `SeasonResolver` convention.

### `getPriceHistory(playerName, lastN)`

Returns the player's per-GW prices for the current season, newest first, plus the latest live snapshot. Includes a short trend summary: price at the start of the window, current/live price, total change, and direction. The direction is descriptive ("price has risen X since GW3"), not predictive. If the season has not started, the reply says so plainly.

This is the only price tool — the model can cite it for buy/sell timing discussions, but must not present the trend as a prediction of future price movement.

### `getPlayerExpectedStats(playerName, lastN)`

Aggregates `player_fixture_stats.expected_goals`, `expected_assists`, `expected_goal_involvements`, plus actual goals/assists/minutes, over the player's last `lastN` fixtures in the resolved season. Useful for "is Salah overperforming his xG?".

### `getTeamExpectedGoals(teamName, lastN)`

Team-level xG/xA aggregate over the last `lastN` fixtures in the resolved season. Distinguishes home/away optionally. Useful for "which attack looks threatening over the last 6 fixtures?".

### `getDifferentialPicks(position, maxOwnership, nextN)`

Returns players in `position` (GK/DEF/MID/FWD) with ownership ≤ `maxOwnership` who have reasonable form/fixtures. Uses `player.selected_by_percent`, `player.form`, `player.total_points`, and upcoming FDR from `fixture`. Does **not** replace RAG strategy advice; it gives the model data to cite.

### `getCaptainCandidates(nextN)`

Returns high-form attackers/midfielders with favourable upcoming fixtures. Combines ownership (so the user can see template vs differential captains), recent points, and the average FDR of the next `nextN` fixtures.

### `getFixtureDifficulty(teamName, nextN)`

Returns upcoming fixtures with `team_h_difficulty` / `team_a_difficulty` ratings. This is distinct from `FixtureTools.getUpcomingFixtures` in that it is team-scoped and focused on the FDR trend, not scores/kickoff times.

## Strategy documents

Add 2–3 new markdown files to `backend/src/main/resources/docs/` with the existing front-matter contract. Proposed topics:

1. **Price-change mechanics and team value** — why prices move, when to buy before a rise / sell before a fall, how rising team value creates future budget, why selling a falling owned player can protect value.
2. **Using xG, xA, xGI and xGA in FPL** — over/underperformance, sample-size caveats, team-level vs player-level xG, why xA matters for midfielders, why xGI captures goal involvement, and why xGA is mainly relevant for defenders/GKs. Note that other advanced stats can be added later if the data is available.

(Captaincy is already covered by the existing Phase 2 strategy document; do not duplicate it.)

Ingest via the existing `--ingest` command.

## Prompt and conventions

The system prompt gains a short "Enrichment tools" section telling the model:

- Use `getPriceHistory` for buy/sell timing and team-value questions. The trend is descriptive ("price has risen/fallen X over the last N GWs"), not a prediction of future movement. Combine with `getPlayerStats` for current price and ownership.
- Use `getPlayerExpectedStats` / `getTeamExpectedGoals` for underlying-performance questions, not raw goal counts. These use FPL's own `expected_goals`, `expected_assists`, and `expected_goal_involvements` columns.
- Use `getDifferentialPicks` / `getCaptainCandidates` when the user asks for picks, differentials, or captaincy advice — combine with RAG strategy docs and upcoming fixtures.
- Use `getFixtureDifficulty` for fixture-run outlook.
- xG, form, and price trends describe tendencies; fixtures describe opportunity. None of them guarantee future returns.

`AGENTS.md` needs no new exceptions beyond what already exists, but the list of tools that use `SeasonResolver` and `SeasonContext.provenance()` should be updated to name the new stats tools.

## Out of scope

- Price history for past seasons.
- Auth, subscriptions, user squads, server-side conversation history — Phase 5.
- Predictive models or betting-style forecasts.
- Real-time price alerts or webhooks.
- Modifying any tapas table.

## Decisions to settle before implementation

- **Should the snapshot also capture `selected_by_percent` and `form`?** If yes, the table widens from a pure price table to a player-snapshot table. Recommendation: keep it price-only for Phase 4; `player` already exposes current ownership/form via `getPlayerStats`.
- **Should `getCaptainCandidates` and `getDifferentialPicks` be one tool or two?** Two keeps the tool descriptions focused and the SQL simple; one combined "pick advice" tool risks becoming a kitchen sink.
