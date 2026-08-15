# FPL Assistant — Phase 3: Historical Data Design Spec

**Date:** 2026-08-15 (revised same day after architectural review)
**Status:** Approved

**Supersedes** these parts of `docs/superpowers/specs/2026-06-27-fpl-assistant-design.md`:
- the `pl_match_result` schema (`:150-165`) — keyed on the wrong column, see "The team identity problem"
- the Backfill paragraph (`:167`) — its season range is not achievable as stated, and its "name→id lookup table" describes data the CSVs do not contain
- "Ongoing sync: Weekly scheduled task" (`:169`) — incompatible with the deployment Phase 1 shipped
- `getTeamFixtureDifficulty` in the tools table (`:118-120`) and the Phase 3 list (`:313`) — cannot be built from match results
- `sync/FootballDataSyncJob.java` in the project structure (`:248`) — the name encodes the scheduled-job shape this spec replaces

---

## Idea

The assistant can answer "how is this player doing" from the current season and "what does strategy say" from the RAG corpus. It cannot answer anything needing *history*: whether a team's recent results are actually good, or how two clubs tend to play each other.

Phase 3 gives it a match-results table spanning several seasons, a way to keep it current, and two tools over it.

## What already exists, and what does not

Probed against the live database on 2026-08-15, because the original spec's assumptions predate the data landing:

| Fact | Consequence |
|---|---|
| `season` holds two rows: 2025-26 (complete) and 2026-27 (current, empty until ~2026-08-21) | The historical depth in this database today is **one** completed season |
| `fixture` holds 380 rows for 2025-26, all with scores | Results for that season already exist — and make a free correctness oracle (see Testing) |
| `pl_match_result` does not exist | Free choice of schema |
| `player_gw_stats` is **empty** | No per-player history from tapas |
| `team` holds rows for `season_id = 1` only; `season` has no rows before 2025-26 | **Historical team codes cannot be joined to anything in this database.** Names must travel with the row |

Per-player history is out of scope, but not because vaastav lacks it — `gws/merged_gw.csv` carries per-player-per-gameweek rows back to 2016-17. The real obstacle is identity: players have no stable cross-season key the way teams have `code`, so matching a player across seasons is a name-and-club heuristic. That is its own project.

## The team identity problem

The original spec keys the table on `home_team_id`/`away_team_id` and maps names to "FPL team IDs via a hardcoded name→id lookup table (20 PL teams, stable within a season)".

`team.id` is assigned by FPL per season and reshuffled whenever a club is promoted or relegated — verified: in 2025-26 Arsenal is id 1, Burnley 3, Bournemouth 4. A lookup keyed that way cannot span seasons, and rows keyed on it without a season qualifier silently attribute matches to the wrong clubs.

`team.code` is stable across seasons (Arsenal 3, Aston Villa 7, Chelsea 8, Brighton 36) — verified in both the live table and vaastav's `teams.csv` for 2019-20 and 2024-25.

**`pl_match_result` is keyed on team code.** And because no `team` row exists for any historical season, **each row also stores both clubs' names as they were at the time**. A club's name in a given season is a historical fact, not a lookup — denormalising it is correct here, not a shortcut.

## Schema

Loose on formatting, binding on these points:

- `season VARCHAR(10)` holding the `season.code` format (`'2018-19'`). **Not a foreign key** — `season` has no historical rows. Using the same format as `SeasonResolver` returns means current-season rows line up with the rest of the system.
- `home_team_code` / `away_team_code` — integers, FPL persistent codes.
- `home_team_name` / `away_team_name` — the club names as of that season.
- `home_goals` / `away_goals`.
- `gameweek` — the FPL gameweek where known. football-data.org's `matchday` is **not** the same thing: a match postponed from matchday 12 keeps `matchday: 12` there while FPL reassigns it to the gameweek it is actually played in. Store the FPL gameweek where the source provides one, and record the source so the difference is traceable.
- `kickoff_time`, `source` (`'vaastav'` | `'football-data'`), and an ingestion timestamp (see "Making staleness visible").
- `team_h_difficulty` / `team_a_difficulty` — present in vaastav's `fixtures.csv` from 2018-19 onward. Store them while importing even though no Phase 3 tool reads them; adding the columns later means a full re-import.
- **`UNIQUE (season, home_team_code, away_team_code)`** — the natural key. A round-robin league plays each ordered pair once per season, and Premier League matches are not replayed.

Indexing: at roughly 380 rows per season over a handful of seasons this is a few thousand rows. One index per team-code column is enough; do not over-engineer for a table this size.

## Idempotency and source precedence

Both importers write to the same table, and vaastav publishes `fixtures.csv` for the season *currently being played* as well as finished ones — so the backfill and the ongoing sync overlap, not just `fixture` and `pl_match_result`.

- Both importers **upsert on the natural key** (`INSERT ... ON CONFLICT (season, home_team_code, away_team_code) DO UPDATE`). Not "insert if absent" keyed on a source id, which would store the same match twice under two sources.
- **Precedence: vaastav wins for any completed season; football-data.org writes only the season in progress.** Without this the `source` column flips depending on which importer ran last.
- Running either importer twice must leave the table unchanged. This is a test requirement, not an aspiration — see Testing.

## Scope decision: one table for all seasons

`pl_match_result` holds completed results for every season it can get, including 2025-26 which `fixture` already covers.

The alternative — historical table plus `fixture` for recent seasons, unioned in the tools — stores each match once but forces every tool to carry two query shapes and two identity schemes (code vs season-scoped id) forever, to save a few hundred duplicated rows.

The duplication is real and the spec does not wave it away: both sources ultimately derive from the FPL API, so for 2025-26 the two tables must agree **exactly**. That makes `fixture` an oracle rather than a liability (see Testing).

Note the tools are not as cleanly split as "past vs future" suggests: `FixtureTools.getUpcomingFixtures` already prints scores for finished fixtures, and with `SeasonResolver` falling back to 2025-26 it currently answers "what happened" for that season too. Phase 3 does not change `FixtureTools`; it accepts the overlap and relies on the reconciliation check to keep the two consistent.

## Data sources, and how far back is actually reachable

The original spec says "2016/17–2024/25". That range is not a matter of judgement — it is a cliff, verified against the repository:

| Seasons | What exists | Cost |
|---|---|---|
| **2019-20 onward** | `fixtures.csv` **and** `teams.csv` per season. `teams.csv` gives `code`,`id`,`name` directly | One parser, no hand-curation. **The cheap floor** |
| **2018-19** | `fixtures.csv` yes, `teams.csv` **no** | Needs `master_team_list.csv` (`season,team,team_name`, covers 2016-17..2023-24) for id→name, then name→code hand-sourced for clubs appearing in no `teams.csv` (Cardiff, Huddersfield) |
| **2016-17, 2017-18** | **No `fixtures.csv` at all** | Results exist only as per-player rows in `gws/merged_gw.csv` and must be de-duplicated into matches — a second, structurally different importer |

The vaastav mapping the importer needs is therefore **`(season, vaastav team id) → code`**, taken from each season's `teams.csv` — *not* a name→code table. Names only enter the picture for seasons without `teams.csv`.

Two parsing constraints, both verified:

- **The `fixtures.csv` header order changes between seasons** (2018-19 has `deadline_time`/`event_day` and no `pulse_id`). Parse by header name, never by position.
- **The file cannot be split on commas.** Its `stats` column is a quoted blob containing hundreds of commas and quotes. Use a real RFC 4180 reader; no CSV library is on the classpath yet.

Only completed matches belong in the table: filter on the `finished` flag and non-null scores. vaastav's 2026-27 `fixtures.csv` already exists and is entirely unplayed.

**Ongoing:** football-data.org free tier, current season only (verified: an anonymous request for an older season returns `403 restricted`). Its team names are a different alias set again ("Wolverhampton Wanderers FC" where FPL says "Wolves"), so it needs its **own** name→code mapping, independently verified — the vaastav mapping cannot serve it.

Both sources are hit **only at sync time, never at chat time** — the existing `AGENTS.md` convention, unchanged.

## The scheduling problem

"Weekly scheduled task" cannot work on the deployment Phase 1 shipped: `fly.toml` sets `auto_stop_machines = 'stop'` and `min_machines_running = 0`, so the machine is stopped whenever nobody is chatting. A Spring `@Scheduled` method never fires on a stopped machine, and a low-traffic hobby app is stopped most of the week. It would work in development and silently never run in production — the same failure class as Phase 1's Docker build context and Phase 2's corpus path.

Four shapes, recommendation first:

1. **Sync on startup behind a staleness marker.** On `ApplicationReadyEvent`, if the last successful sync is older than a threshold, run it. The machine only matters when it is awake; if nobody chats for a month, nobody needed fresher data. No endpoint, no secret, no second deployable, no external service, nothing to disable itself after 60 days. Cost: work on an already-slow cold start, so it must not block serving the request that woke the machine.
2. **External scheduler calling a protected endpoint.** GitHub Actions cron wakes the machine (`auto_start_machines = true` handles the wake). Note the prerequisites this project has not met: **there is no git remote and the app has never been deployed**. Also, GitHub disables scheduled workflows after 60 days of repository inactivity — a silent-stop failure identical to the one this section exists to prevent.
3. **Fly scheduled machines.** Native, but accepts only fixed intervals (`hourly`/`daily`/`weekly`/`monthly`), not cron expressions, and adds a second deployable to keep in step with the main image.
4. **Manual CLI flag**, like Phase 2's `--ingest`. Trivially correct, relies on someone remembering.

Whichever is chosen, the sync must be idempotent — every one of these can fire twice.

### Making staleness visible

Every option above can silently stop. Nothing in the phase would notice, which is the same failure the section exists to prevent, one level up. So: rows carry an ingestion timestamp, and `getTeamForm` states the date of the most recent match it knows about. A stalled sync then shows up in an answer as visibly old data rather than as quietly confident stale advice.

## The tools

`getTeamFixtureDifficulty` is dropped from this phase. FDR is a per-season FPL rating attached to *scheduled* matches, `FixtureTools.getUpcomingFixtures` already surfaces it, and it answers a different question from a team's own form. The difficulty columns are stored during import anyway, so a Phase 4 tool over historical FDR remains cheap.

Two tools, both `FplTool` implementations following the established conventions (catch `DataAccessException`, log, return an error string, never throw into the SSE stream):

- **`getTeamForm(teamName, lastN)`** — recent completed results: opponent, venue, score, outcome, plus the date of the latest match known.
- **`getHeadToHead(teamA, teamB, limit)`** — past meetings between two clubs across seasons, order-insensitive.

### Name resolution

These tools **cannot** reuse `FixtureTools`' team lookup. That query is `SELECT id, name, short_name FROM team WHERE season_id = ? AND (...)`: it is inline rather than extractable, it returns a season-scoped `team.id` rather than a code, and it only ever sees the resolved season's twenty clubs — so `getTeamForm("Leicester")` or `getHeadToHead("Southampton","Watford")`, precisely the queries a historical tool exists for, would resolve to nothing.

Resolve names against the clubs actually present in `pl_match_result` (or the importer's code map), producing a **code**. Reuse from `FixtureTools` only `LikePatterns.escape` and the "`ORDER BY` before `LIMIT`" determinism rule.

### Attribution across seasons

These tools deliberately span seasons, so `SeasonContext.provenance()` — which names one resolved season — does not fit, exactly as it did not fit the RAG tool. Each match carries its own season and the reply states the range covered. A 2019/20 result must not read as recent: squads, managers and tactics have all changed, and the model cannot infer the year from a scoreline.

This is the third instance of one discipline (database rows, retrieved documents, historical matches). Both relevant `AGENTS.md` conventions need amending — not only the provenance bullet but the "**all** DB queries filter by a season resolved through `SeasonResolver`" one, which if followed literally would destroy the cross-season behaviour this phase exists for.

## Testing

Established pattern: pure unit tests for logic with no business touching a database (CSV parsing, id→code mapping, form aggregation), integration tests against live Supabase for the rest, writes pinned to the isolated test table under the `test` profile (already the default for the whole test source set).

Phase 3 specifics:

- **Real vaastav CSV fixtures checked into test resources**, not hand-written rows. Every bug this phase can have — a renamed club, a changed header, a season with a different shape — lives in the real files.
- **`fixture` is the acceptance oracle for 2025-26.** After backfilling that season, every `pl_match_result` row must match `fixture` on both scores, 380/380. Both derive from the FPL API, so any mismatch is an import bug, and the check needs no hand-curated expectations.
- **Idempotency proven, not assumed**: run each importer twice, assert the table is unchanged.
- **football-data.org cannot be verified end-to-end before GW1** — 2026-27 starts 2026-08-21 with zero completed matches, and the free tier serves only the current season, so there is no historical response to test against either. Prove the parser against a captured response body checked into test resources; record the live verification as an open item in `STATUS.md` until the first real gameweek.

## Out of scope

Per-player historical stats (see above). Understat xG, price trends, captain differentials, historical-FDR tooling (Phase 4). Anything requiring auth (Phase 5).

## Open decision for the project owner

**How far back to backfill**, framed as the cost ladder above rather than a relevance judgement: 2019-20 is the clean floor, 2018-19 costs a fallback mapping and a few hand-sourced codes, and 2016-17 costs a second importer.

The scheduling shape is a second decision, but it gates only the ongoing sync — not the schema, not the backfill, not the tools.
