# Early-season data: dual-season player stats and current-season match results

**Date:** 2026-08-21 (GW1 deadline day — the 2026/27 season starts tonight)

**PROGRESS:** Task 3/3 done — code and docs complete on branch `early-season-data`; live smoke pending the merge-deploy (see Task 3 outcome in STATUS.md).

## Why this exists

The system prompt now carries the early-season policy (routing, multi-lookup rule, and the
GW5 switch: before GW5 lean on last season, from GW5 current season only — applied directly
to `system-prompt.st` 2026-08-21, not part of this plan). The
prompt is dead text without these two tool changes: once the season "flips", the tools stop
serving the last-season evidence the policy says to lean on.

Verified against production on 2026-08-21:

- tapas `player`, `gameweek`, `fixture`, `player_fixture_stats` hold **zero rows for
  2026-27**; `SeasonResolver` falls back to the completed 2025-26 everywhere.
- `pl_match_result` has zero 2026-27 rows and will stay that way:
  `FootballDataSyncService.sync()` no-ops whenever the resolver is on a fallback.
- `fpl_daily_price` is the only current-season source (599 players, captured daily).

The day tapas syncs 2026/27 (the "flip"), the resolver moves to the current season and:

1. `getPlayerStats`'s statistics block silently becomes 1–2-match current-season aggregates —
   2025/26's 38-match evidence becomes unreachable by any tool.
2. `PickTools` ranks on 1–2-match `form`; `ExpectedStatsTools` reads a 1–2-match xG sample.
3. `getTeamForm` still reports May 2026 as the freshest known match.

## Conventions that apply throughout

**Policy amendment (2026-08-22, owner):** the early-season rule is a hard switch at GW5, not a
GW5→GW10 graduation — before GW5 lean on last season, from GW5 the current season alone (for a
player who has actually played). The prompt says this; nothing in this plan's tasks keys on the
old thresholds.

Per AGENTS.md: tools implement `FplTool` and catch `DataAccessException`, log, return an error
string. Tests seed the data they assert on (`TapasSeed` for tapas-owned tables) against the
disposable container — never production content. Commit per task; update this file's PROGRESS
line and `docs/STATUS.md` after every task.

---

### Task 1 — `getPlayerStats` shows both seasons once the current one has data

Today the statistics block queries the resolved season only. After the flip that is the
current season's tiny sample, and last season disappears from the reply.

Change:

- When the resolver returns the **current** season, also query the season immediately before
  it (`start_date <` the resolved season's, latest first, limit 1) and render both blocks:
  the current season's aggregates first (labelled as the season being played), the previous
  season's beneath with its own provenance. If no earlier season exists, the second block is
  simply absent.
- When the resolved season is already a completed fallback (the case today), behaviour is
  unchanged: one block, existing provenance.
- The previous-season block must **not** reuse `SeasonContext.provenance()`'s fallback text —
  "the current season has no data yet" would be false after the flip. Add a sibling label
  along the lines of: `Data source: season 2025-26 — a COMPLETED PREVIOUS season. The current
  season 2026-27 is under way; these are last season's numbers, not current form.`
- The daily-capture "this season's squad" block at the top is untouched.
- The tool description is updated to say both seasons are returned when both exist, so the
  model expects the two-block shape and weighs them per the prompt's early-season rule.

**Done when:** with two seeded populated seasons (current + previous) the reply carries both
blocks, each attributed to its own season; with the resolver on a completed fallback the
output is identical in shape to today's; the no-capture and not-in-this-season's-game branches
still lead with their explicit statements; existing `PlayerTools` tests pass with the new
season added to their seed.

### Task 2 — football-data sync writes the genuinely current season regardless of the resolver

Today `sync()` returns early unless `SeasonContext.currentSeason()` — so while tapas hasn't
synced, no 2026-27 results are ever written and `getTeamForm` is stale for weeks.

The guard's real purpose is narrower: never write into a completed season, where vaastav is
authoritative. The API response itself carries the season — every match has a
`season.startDate` (verified in the captured real response
`pl-matches-2026-27.json`: `"startDate": "2026-08-21"` on all 380) — so derive the season code
from the payload (`2026-08-…` → `2026-27`, the `season.code` format) instead of trusting the
resolver:

- Write finished matches stamped with the **derived** code, only when it equals the DB's
  `is_current` season code (`SeasonContext.currentSeasonCode()` — already on the context, no
  extra query).
- If the API lags the calendar (in July it can still serve the just-finished season), the
  derived code differs from `is_current` and the sync skips — the old guard's protection,
  without depending on tapas having synced.
- vaastav precedence becomes structural rather than resolver-based: vaastav backfills
  completed seasons, which are never `is_current`, and a later vaastav backfill of a
  just-completed season upserts the same natural key `(season, home, away)` — an idempotent
  overwrite with authoritative scores.

Change surface: `FootballDataResponseParser`/`FootballDataMatch` expose the season code
(assert homogeneity across the response or derive from the first match — one competition
edition per response); `FootballDataSyncService` swaps the guard. The existing mocked-resolver
precedence test changes meaning: "resolver on fallback" no longer skips; the protected case is
now "API season ≠ DB `is_current` season". Rewrite it as derived-code tests.

Note: the staleness marker (`MAX(ingested_at) WHERE source='football-data'`) stays null until
the first write, so every boot fetches once — one request, already the case today.

**Done when:** mocked tests cover both branches (derived == `is_current` with the resolver on
a fallback → writes; derived != `is_current` → skips and logs); the captured-response test
pins the derived code as `2026-27` for all 380 matches; the vaastav-backfill idempotency
tests still pass.

### Task 3 — End-to-end verification and docs

The flip cannot be forced on the calendar, so verify per branch:

- **Now (resolver on 2025/26 fallback):** live chat smoke against the running app. The
  immediate payoff of Task 2: once GW1 matches are played, the next machine wake writes them,
  and a form question surfaces 2026-27 results alongside last season's instead of a stale
  May 2026 freshness line.
- **Simulated flip (container):** seed both seasons populated and assert `getPlayerStats`
  carries both seasons; confirm the prompt's early-season rule has both blocks to work from.
- Update `docs/STATUS.md` with what changed and the remaining gap: no current-season
  fixtures, deadlines or gameweek info until tapas syncs (`GameweekTools`/`FixtureTools`
  have no fallback-independent source). Check whether AGENTS.md's attribution convention
  needs a sentence for the two-block player reply.

---

## Considered and deferred

- **Season blending in `PickTools`/`ExpectedStatsTools`** (last season's points/form beside
  this season's in ranking rows): the prompt's weighting rule plus Task 1's dual-season player
  card give the model the evidence; weighted cross-season ranking is the "weight earlier
  seasons" work AGENTS.md already anticipates for later phases. Revisit if live answers show
  recommendations leaning on 1–2-match form.
- **Cross-season player identity**: `element_id` is season-scoped and tapas `player` has no
  stable cross-season key; matching would be by name. Task 1 sidesteps this by searching each
  season independently for the name the user gave — new signings and promoted players simply
  have no previous-season block, which reads correctly as "no PL evidence last season".
- **A scheduler for the football-data sync**: startup-only sync means results appear after the
  next machine wake. Acceptable while machines auto-wake on traffic; a `@Scheduled` sync like
  `DailyPriceScheduler` is a separate small change if staleness shows up in answers.
- **Current-season fixtures/deadlines from FPL's `bootstrap-static`** (the daily capture
  already fetches it): would close the fixtures/deadline gap without waiting for tapas, but
  it is a new table and a new tool, not a fix to an existing one. Separate decision.
