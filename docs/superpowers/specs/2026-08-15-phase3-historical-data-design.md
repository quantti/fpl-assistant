# FPL Assistant — Phase 3: Historical Data Design Spec

**Date:** 2026-08-15
**Status:** Draft for review

**Supersedes** these parts of `docs/superpowers/specs/2026-06-27-fpl-assistant-design.md`:
- the `pl_match_result` schema (`:150-165`) — keyed on the wrong column, see "The team identity problem"
- "Ongoing sync: Weekly scheduled task" (`:169`) — incompatible with the deployment Phase 1 shipped
- the Phase 3 tool list (`:313`) — `getTeamFixtureDifficulty` cannot be built from match results

---

## Idea

The assistant can answer "how is this player doing" from the current season and "what does strategy say" from the RAG corpus. It cannot answer anything that needs *history*: whether a team's recent results are actually good, how these two clubs tend to play each other, whether a fixture run is genuinely kind or merely looks it.

Phase 3 gives it a match-results table spanning many seasons, a way to keep that table current, and tools over it.

## What already exists, and what does not

Probed against the live database on 2026-08-15, because the original spec's assumptions predate the data actually landing:

| Fact | Consequence for this phase |
|---|---|
| `season` holds exactly two rows: 2025-26 (complete) and 2026-27 (current, empty until ~2026-08-21) | The "historical depth" the project has today is **one** completed season |
| `fixture` holds 380 rows for 2025-26, all with scores | Results for that season already exist — Phase 3 must decide whether to duplicate them |
| `pl_match_result` does not exist | Nothing to migrate; free choice of schema |
| `player_gw_stats` is **empty** | Per-player historical form cannot be computed from tapas data. Phase 3 is limited to **team-level** history, which is what its tools need anyway |

So the vaastav backfill is not redundant — it adds seasons that exist nowhere in this database. But it overlaps `fixture` for 2025-26, which forces a decision (below).

## The team identity problem

The original spec keys the table on `home_team_id`/`away_team_id` and maps vaastav team names to "FPL team IDs via a hardcoded name→id lookup table (20 PL teams, stable within a season)".

That parenthetical is the bug. `team.id` is 1–20 assigned alphabetically **per season** and reassigned whenever a club is promoted or relegated — verified: in 2025-26, Arsenal is id 1, Burnley id 3, Bournemouth id 4. Next season those shift. A single lookup table cannot span 2016/17–2024/25, and results keyed on `team.id` without a season qualifier silently attribute matches to the wrong clubs.

`team.code` is the stable identifier — FPL's persistent per-club code (Arsenal 3, Aston Villa 7, Chelsea 8, Brighton 36). It does not change between seasons.

**`pl_match_result` is therefore keyed on team code, not team id.** Joining to `team` for display names requires the season, which the row already carries. Any name→identity mapping the importer needs maps names to *codes*.

## Scope decision: one table for all seasons

`pl_match_result` holds completed match results for **every** season it can get, including ones `fixture` already covers.

The alternative — restrict it to pre-tapas seasons and have tools union two tables — keeps each match stored once, but every tool then needs two query shapes, two identity schemes (code vs season-scoped id), and a rule for the boundary. That cost recurs in every tool, forever, to save duplicating a few hundred rows.

The duplication is real but bounded, and the tables answer different questions: `fixture` is the current season's schedule including *unplayed* matches with FDR; `pl_match_result` is the historical record of *played* matches across seasons. Tools that ask "what happened" read `pl_match_result`. Tools that ask "what's coming" keep reading `fixture`, as `FixtureTools` already does.

## Data sources

**Backfill (one-time):** vaastav/Fantasy-Premier-League CSVs, seasons 2016/17 onward. Public, no rate limits, no key. Team names map to codes via a lookup the importer owns.

**Ongoing:** football-data.org free tier for the current season's results as they are played.

Both are hit **only at sync time, never at chat time** — the existing `AGENTS.md` convention, unchanged.

## The scheduling problem

The original spec says "Weekly scheduled task hits football-data.org API". That cannot work on the deployment Phase 1 shipped: `fly.toml` sets `auto_stop_machines = 'stop'` and `min_machines_running = 0`, so the machine is stopped whenever no one is chatting. A Spring `@Scheduled` method never fires on a stopped machine, and a low-traffic hobby app is stopped most of the week. The sync would appear to work in development and silently never run in production — the same class of failure as the Docker build context in Phase 1 and the corpus path in Phase 2.

Three workable shapes, in the order I'd pick them:

1. **External scheduler calling a protected endpoint.** GitHub Actions cron (or any external cron) issues a request that wakes the machine — `auto_start_machines = true` already handles the wake — and triggers the sync. Costs nothing, leaves the machine lifecycle alone, and the schedule lives somewhere visible and version-controlled. Needs the endpoint protected by a shared secret, since the app has no auth until Phase 5.
2. **Fly scheduled machines.** A separate machine that runs the sync on a schedule and exits. Native to the platform, no new endpoint or secret, but a second deployable to keep in step with the main image.
3. **Manual CLI flag**, exactly like Phase 2's `--ingest`. Consistent with a pattern already in the codebase and trivially correct, but relies on someone remembering weekly.

Whichever is chosen, the sync must be **idempotent** — safe to run twice for the same gameweek — because every one of these can fire more than once.

## The tools

The original spec lists `getTeamForm`, `getHeadToHead`, `getTeamFixtureDifficulty`. The third does not belong here: fixture difficulty is `fixture.team_h_difficulty`/`team_a_difficulty`, a per-season FPL rating that exists only for scheduled matches. It cannot be derived from historical results, and `FixtureTools.getUpcomingFixtures` already surfaces it. Dropping it from this phase.

Two tools, both `FplTool` implementations following the established conventions (catch `DataAccessException`, log, return an error string, never throw into the SSE stream):

- **`getTeamForm(teamName, lastN)`** — recent completed results for a team: opponent, home/away, score, outcome. The question behind it is "is this team actually in form", which the model currently answers from vibes.
- **`getHeadToHead(teamA, teamB, limit)`** — past meetings between two clubs across seasons.

Team name resolution reuses the escaping and matching approach `FixtureTools` already established (`LikePatterns`, `ORDER BY` before `LIMIT` for determinism).

### Attribution across seasons

These tools deliberately span seasons, so `SeasonContext.provenance()` — which names *one* resolved season — does not fit, exactly as it did not fit the RAG tool. Each returned match carries its own season, and the reply states the range covered. A result from 2019/20 must not read as though it happened recently: squads, managers and tactics have all changed, and the model cannot infer the year from a scoreline.

This is the third instance of the same discipline (database rows, retrieved documents, historical matches). `AGENTS.md`'s conventions section should say so once, rather than accumulating per-tool exceptions.

## Testing

The established pattern holds: pure unit tests for logic that has no business touching a database (CSV parsing, name→code mapping, form aggregation), integration tests against live Supabase for the rest.

Two Phase 3 specific requirements:

- **The importer must be tested against real vaastav CSV fixtures checked into test resources**, not hand-written rows. Every historical import bug this phase can have — a renamed club, a changed column, a season with a different header — lives in the real files' shape.
- **Sync idempotency must be proven, not assumed**: running the same sync twice leaves the same rows. This is the failure that silently doubles a team's match history.

Writes go to an isolated table under the test profile, as Phase 2 established. The whole test source set already defaults to that profile.

## Out of scope

Per-player historical stats (`player_gw_stats` is empty and filling it is a tapas concern, not this project's). Understat xG (Phase 4). Price trends and captain differentials (Phase 4). Anything requiring auth (Phase 5).

## Open decisions for the implementer to raise, not settle alone

- **How far back to backfill.** 2016/17 is the original spec's answer, but each extra season is import effort and storage for questions that get less relevant the further back they go. Worth confirming before writing the importer.
- **Which scheduling shape** from the three above. This one has a cost and an operational commitment attached, so it is the project owner's call.
