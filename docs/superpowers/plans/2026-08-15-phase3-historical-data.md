# FPL Assistant — Phase 3: Historical Data Implementation Plan

**Spec:** `docs/superpowers/specs/2026-08-15-phase3-historical-data-design.md` — read it fully first. Its Schema, Idempotency, "how far back is actually reachable" and Scheduling sections are binding; this plan does not repeat them.

**PROGRESS:** Task 7/7 done — Phase 3 complete, merged to `master`.

Design altitude, as Phase 2's plan was: each task states what it must achieve and how you know it is done. Class shapes, SQL and test layout are yours within the spec's constraints.

**Conventions that apply throughout** (Phases 1–2): Supabase access per `docs/DEVELOPMENT.md`; tools implement `FplTool` and catch `DataAccessException`, log, return an error string; external APIs only at sync time; tests accompany the task that introduces the behavior; commit per task; update `PROGRESS` and `docs/STATUS.md` after **every** task.

---

### Task 1 — Schema

Both owner decisions are settled — nothing in this phase is blocked. Backfill depth is **2019-20 onward**, seven seasons through 2025-26; every one has both `fixtures.csv` and `teams.csv`, so there is no fallback mapping and no hand-sourced code here. Do not widen the range without asking. Sync scheduling is **startup behind a persisted staleness marker** (Task 5).

Create `pl_match_result` to the spec's Schema section: keyed on team **code**, carrying both club names, `season` as `VARCHAR(10)` and not a foreign key, the difficulty columns, an ingestion timestamp, and the `UNIQUE (season, home_team_code, away_team_code)` constraint the upserts depend on.

This adds a table to the database shared with `tapas-fpl-app` — additive and allowed, but state plainly in `STATUS.md` what was created.

**Done when:** the table, unique constraint and indexes exist; `STATUS.md` documents the shared-database change.

### Task 2 — vaastav team identity mapping

Build `(season, vaastav team id) → FPL team code` from each season's `teams.csv`, plus the club name for that season. All seven seasons in scope have one, so no name-based fallback is needed — if you find yourself needing one, something is wrong with the season range.

It must fail loudly on an unresolvable club rather than dropping the match or inventing a code. Promoted and relegated clubs mean every historical season contains clubs the current one does not; a silent miss corrupts history quietly, which is the worst way for this phase to fail.

**Done when:** every club across 2019-20…2025-26 resolves to a code and a name; an unknown id fails with a message naming it and the file; unit-tested with no database.

### Task 3 — vaastav backfill importer

Import completed historical results into `pl_match_result`. One-shot, invoked deliberately — follow Phase 2's `--ingest` precedent rather than inventing a new invocation.

Constraints that will bite if ignored, all in the spec: parse by header name (the column order changes between seasons), use a real RFC 4180 reader (the `stats` column contains commas and quotes — no CSV library is on the classpath yet), filter to finished matches with non-null scores, and upsert on the natural key so a second run changes nothing.

Tests use **real CSV fixtures** checked into test resources.

**Done when:** all seven seasons are in the table with correct codes, names and scores (a full season is 380 matches, so expect ~2,660 rows once complete — 2019-20 and 2020-21 were COVID-disrupted in scheduling but still played 380); **the 2025-26 rows match `fixture` on both scores, 380/380** (the spec's oracle — no hand-curated expectations needed); a second run leaves the table unchanged; test writes never touch production data.

### Task 4 — `getTeamForm` and `getHeadToHead`

Both tools together: they share name resolution and attribution, and splitting them would duplicate both.

Resolve names to **codes** against the clubs present in `pl_match_result` — explicitly *not* `FixtureTools`' resolver, which returns a season-scoped id and only sees the current season's twenty clubs, so relegated clubs would resolve to nothing. Reuse only `LikePatterns.escape` and the `ORDER BY`-before-`LIMIT` rule.

Every match names its season, the reply states the range covered, and `getTeamForm` reports the date of the most recent match it knows about — that last one is what makes a stalled sync visible instead of quietly stale.

These tools must **not** filter through `SeasonResolver`; spanning seasons is the point.

**Done when:** both return correct results for known clubs including a relegated one, verified against the real table; head-to-head is order-insensitive; unknown clubs and database failures return their intended strings rather than throwing; season attribution and the freshness line are present.

> **Review checkpoint.** Request a code review of Tasks 1–4 before the sync and prompt work. Apply findings before continuing.

### Task 5 — football-data.org sync

Triggered on `ApplicationReadyEvent` behind a **persisted** staleness marker (decided; see the spec for why the alternatives lost). Three properties the spec calls out because they are where this shape fails: the marker lives in the database, not a field — a stopped machine loses memory and would re-sync every cold start; the sync runs **off the request path**, so the chat request that woke the machine is answered from stored data rather than waiting; and a failure (API down, key missing) must log and let the application start, never poison the boot.

Keep the current season current, upserting on the same natural key with the spec's source precedence (vaastav authoritative for completed seasons; this writer only touches the season in progress). Needs its own name→code alias set — football-data's names differ from both FPL's and vaastav's.

Respect the free tier's rate limit and fail in a way that says so rather than looking like a network error. `FOOTBALL_DATA_API_KEY` needs adding to `.env.test.example` and `DEVELOPMENT.md`.

**Done when:** the parser is proven against a captured real response body checked into test resources; a second run changes nothing; a boot with the sync failing still starts and serves chat; the staleness marker survives a restart (a second boot inside the threshold does not re-sync); the trigger and its threshold are documented where an operator will find it; **live end-to-end verification is recorded in `STATUS.md` as an open item until GW1** — 2026-27 has no completed matches before 2026-08-21 and the free tier serves no older season.

### Task 6 — Prompt and conventions

Tell the model when historical results are worth consulting, and that old results are evidence about tendencies rather than statements about current form.

Amend **both** affected `AGENTS.md` conventions: the provenance bullet, and the "all DB queries filter by a season resolved through `SeasonResolver`" bullet — an agent following the latter literally would bolt season filtering onto these tools and destroy the cross-season behaviour. Consolidate the multi-season attribution rule rather than adding a third per-tool exception.

**Done when:** the instruction sits coherently in `system-prompt.st`, and `AGENTS.md` no longer contradicts what the code does.

### Task 7 — End-to-end verification and documentation

Through the real chat flow, not mocks: a form or head-to-head question uses the new tools and attributes results to their seasons; a question about *upcoming* fixtures still goes to `FixtureTools` and does not drag in history.

Update `docs/STATUS.md` with what was built, deviations, and anything the spec failed to anticipate.

**Done when:** both question types behave correctly against the running application, and the documentation reflects what exists.

> **Review checkpoint.** Full-phase review. Apply findings before considering Phase 3 complete.
