# FPL Assistant — Phase 4: Enrichment Implementation Plan

**Spec:** `docs/superpowers/specs/2026-08-15-phase4-enrichment-design.md` — read it fully first. Its data-source findings, schema, sync rules, tool boundaries, and out-of-scope list are binding.

**PROGRESS:** Task 7/7 done — Phase 4 complete, merged to `master`. Two full-phase reviews applied (see `docs/STATUS.md`).

Design altitude: each task states what it must achieve and how you know it is done. Class shapes, SQL, and test layout are yours within the spec's constraints.

## Conventions that apply throughout

- Supabase access per `docs/DEVELOPMENT.md`; tools implement `FplTool` and catch `DataAccessException`, log, return an error string.
- All stats tools filter by a season resolved through `SeasonResolver` and prefix their reply with `SeasonContext.provenance()`.
- External APIs (vaastav CSVs) are hit only at sync time, never at chat time.
- Only add new tables; do not alter existing tapas tables.
- New tables are parameterised by name and pinned to an isolated `_test` version under the `test` profile.
- Tests use real Supabase for integration; pure unit tests for parsing/logic.
- Commit per task; update `docs/STATUS.md` after every task.

---

### Task 1 — Schema and current-season price sync

Create `fpl_player_price` to the spec's Schema section: keyed on the in-progress season, `gameweek` (1..38 or 0 for live snapshot), season-scoped FPL `element_id` (== vaastav `element` == tapas `player.id` — the tapas `player` table has no `code` column, and single-season storage needs no cross-season identity), denormalised name, price in 0.1m units, `source`, and a unique key that keeps vaastav and snapshot rows apart.

Build two sync paths, both gated by `SeasonContext.currentSeason()` so they only run for the season actually being played (not a completed fallback):

1. **vaastav current-season `gws/merged_gw.csv`** — read the `value` column for each GW; the `element` column is already the season-scoped id that matches tapas `player.id`, so no `(season, element) → code` mapping is needed. Upsert on the unique key so re-runs don't duplicate. The file is absent before GW1; handle 404/empty directory as a no-op rather than a failure.
2. **Startup price snapshot** — on `ApplicationReadyEvent`, off the request path, behind a persisted staleness marker (`MAX(ingested_at) WHERE source = 'snapshot'`), snapshot `player.now_cost` for every player into `gameweek = 0`, `source = 'snapshot'`. Reuse the pattern from `StartupSyncRunner` / `FootballDataSyncService`.

Both run only for the season resolved by `SeasonResolver` once it holds data. Past seasons are intentionally not backfilled.

**Done when:** the table exists; the vaastav sync is idempotent and handles a missing current-season file gracefully; the snapshot sync is idempotent and skips when the resolved season is a completed fallback; tests never touch production data.

> **Design note:** vaastav's `merged_gw.csv` uses `element` (the season-scoped FPL id, == tapas `player.id`), so it joins directly with no mapping. The original plan's `(season, element) → code` mapping is unnecessary because the tapas `player` table has no `code` column and the table only holds one season.

### Task 2 — `getPriceHistory`

Implement the price trend tool:

- `getPriceHistory(playerName, lastN)` — per-GW prices for the current season, newest first, plus the latest live snapshot. Include a short trend summary: window-start price, current/live price, total change, direction.
- The direction must be phrased descriptively ("price has risen/fallen X over the last N GWs"), never as a prediction of future movement.

**Done when:** the tool returns correct, attributed results for known players; unknown names and DB failures return intended strings; the snapshot freshness line is visible; the trend wording is descriptive only.

### Task 3 — xG tools

Implement `getPlayerExpectedStats(playerName, lastN)` and `getTeamExpectedGoals(teamName, lastN)`:

- `getPlayerExpectedStats`: aggregate `player_fixture_stats` over the last `lastN` fixtures in the resolved season — expected_goals, expected_assists, expected_goal_involvements, plus actual goals/assists/minutes. Limit `lastN` to a sane max (e.g. 10).
- `getTeamExpectedGoals`: aggregate `player_fixture_stats` for a club's players over the last `lastN` fixtures, split home/away or combined. Attribute by season via `SeasonContext.provenance()`.

**Done when:** both tools return correct aggregates for known players/teams; unknown names and DB failures are handled; integration tests run against the isolated test table with realistic fixtures.

### Task 4 — Differential and captain tools

Implement `getDifferentialPicks(position, maxOwnership, nextN)` and `getCaptainCandidates(nextN)`:

- `position`: 1=GK, 2=DEF, 3=MID, 4=FWD, or "any".
- `maxOwnership`: decimal percent, e.g. `10` for ≤10% owned.
- Combine `player.selected_by_percent`, `player.form`, `player.total_points`, and the average FDR of the next `nextN` fixtures from `fixture`. Order by a simple composite (e.g. form desc, FDR asc, ownership asc).
- Captain candidates should bias toward high-form attackers/midfielders with kind fixtures.

**Done when:** both tools return ordered lists with reason fields; the SQL uses `SeasonResolver`; invalid position values are rejected or normalised; unknown filters return empty rather than throwing.

### Task 5 — Fixture-difficulty tool

Implement `getFixtureDifficulty(teamName, nextN)`:

- Resolve the team name to a `team.id` in the resolved season.
- Return the next `nextN` fixtures with FDR ratings (`team_h_difficulty` if the team is home, `team_a_difficulty` if away).
- Include opponent name, kickoff time, and venue.

This must not duplicate `FixtureTools.getUpcomingFixtures`; it is narrowly about the FDR outlook.

**Done when:** returns correct FDR ratings for known teams; uses `SeasonResolver`; unknown teams return intended string.

### Task 6 — Strategy docs, prompt, and conventions

Add 2–3 new markdown files to `backend/src/main/resources/docs/` with the existing front-matter contract (title, kind, valid_from; add `valid_until` only for timely docs). Proposed:

- `price-change-mechanics-and-team-value.md` — why prices move, buy-before-rise / sell-before-fall timing, how rising team value creates future budget.
- `using-xg-xa-xgi-xga-in-fpl.md` — over/underperformance, sample-size caveats, xA for midfielders, xGI for goal involvement, xGA mainly for defenders/GKs. These are FPL's own expected-stats columns, not a third-party model. Note that other advanced stats can be added later if available.

(Captaincy is already covered by the existing Phase 2 strategy document; do not duplicate it.)

Update `system-prompt.st` with an "Enrichment tools" section: when to consult price history, xG, differentials, captains, and FDR; remind the model that price trend is descriptive, not predictive, and that team value matters for future budget.

Update `AGENTS.md` to list the new stats tools under the `SeasonResolver` / `SeasonContext.provenance()` convention; no new exceptions are needed.

**Done when:** ingestion succeeds; new docs appear in retrieval tests for relevant questions; the prompt tells the model when to use each new tool; `AGENTS.md` reflects what the code does.

### Task 7 — End-to-end verification, deploy, and documentation

Through the real chat flow, verify:

- A price-history question uses `getPriceHistory` and shows trend direction (descriptive, not predictive).
- An xG question uses `getPlayerExpectedStats` or `getTeamExpectedGoals` and distinguishes actual vs expected returns. These tools read FPL's own `expected_goals` / `expected_assists` / `expected_goal_involvements` columns.
- A captain/differential question uses `getCaptainCandidates` / `getDifferentialPicks` and draws on RAG strategy docs.
- An upcoming FDR question uses `getFixtureDifficulty`.
- A pure stats question (e.g. "How many goals has Salah scored?") still uses `getPlayerStats` and does not drag in enrichment tools.

Deploy the new version to Fly.io (`fly deploy --ha=false --remote-only`). Update `docs/STATUS.md` with what was built, deviations, and the one genuinely open item if any (price snapshots only populate when the app wakes; vaastav rows only appear after each GW).

**Done when:** all question types behave correctly against the running application, and the documentation reflects what exists.

> **Review checkpoint.** Request a full-phase review before considering Phase 4 complete.

## Pre-implementation verification — outcomes

All four checks were run against the live database and the live vaastav repository. Recorded as
prose rather than checkboxes, per the progress-tracking convention in `AGENTS.md`.

| Check | Outcome |
|---|---|
| `SeasonResolver.currentSeason()` guard prevents writing 2025-26 fallback data into `fpl_player_price` before 2026-27 holds data | **Holds.** `PriceSyncService.sync()` returns 0 and logs the skip; production `fpl_player_price` is empty, confirmed by direct query. Both branches pinned by `PriceSyncServiceIntegrationTest` with a mocked resolver |
| vaastav current-season `gws/merged_gw.csv` 404 is handled as a no-op, not a boot failure | **Holds.** `GET .../data/2026-27/gws/merged_gw.csv` returns 404 (verified live); `VaastavSource.fetchIfPresent` maps it to null and the sync logs "snapshot only" |
| `player_fixture_stats` expected-stats columns exist and are non-null for 2025-26 fixtures | **Holds.** 29,747 rows for 2025-26, zero null `expected_goal_involvements`, zero null `kickoff_time` |
| New strategy docs do not duplicate `captain-selection-and-triple-captain.md` | **Holds.** Price mechanics and expected stats are both new subjects; captaincy is referenced, not restated. Corpus is 9 docs / 76 chunks |
