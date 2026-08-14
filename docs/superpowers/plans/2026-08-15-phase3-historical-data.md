# FPL Assistant — Phase 3: Historical Data Implementation Plan

**Spec:** `docs/superpowers/specs/2026-08-15-phase3-historical-data-design.md` — read it fully first. Its "team identity problem", "scope decision" and "scheduling problem" sections are binding; this plan does not repeat them.

**PROGRESS:** Task 0/7 done

Design altitude, as Phase 2's plan was: each task states what it must achieve and how you know it is done. Class shapes, SQL, parser structure and test layout are yours within the spec's constraints.

**Conventions that apply throughout** (established in Phases 1–2): Supabase access per `docs/DEVELOPMENT.md`; tools implement `FplTool` and catch `DataAccessException`, log, return an error string; external APIs are touched only at sync time; tests accompany the task that introduces the behavior; commit per task; keep `PROGRESS` and `docs/STATUS.md` current after **every** task.

---

### Task 1 — Decisions and schema

Two things in the spec are explicitly the project owner's call, and both change what you build: **how far back to backfill**, and **which of the three scheduling shapes** to use. Raise them before writing code; do not pick silently.

Then create `pl_match_result` — keyed on team **code**, not team id, for the reason the spec sets out. Include what the tools need (season, gameweek where known, both teams, both scores, kickoff time, source) and index for the query shapes Tasks 5–6 will use: by team within a season, and by team pair across seasons.

Adding a table to the database shared with `tapas-fpl-app` is additive and allowed, but it is a shared-database change: state plainly in `STATUS.md` what was created.

**Done when:** both decisions are recorded with their answers; the table and indexes exist; `STATUS.md` documents the shared-database change.

### Task 2 — Team identity mapping

Build the name → team code mapping the importers need, and make it fail loudly on an unrecognised club rather than dropping the match or inventing a code. Promoted and relegated clubs mean every historical season contains names the current season does not; a silent miss here corrupts history quietly, which is the worst way for this phase to fail.

Verify the mapping against the club names that actually appear across the seasons being imported, not against this season's twenty.

**Done when:** every club name in the target seasons resolves to a code; an unknown name fails with a message naming the club and the file; covered by unit tests with no database.

### Task 3 — Vaastav backfill importer

Import historical results from the vaastav CSVs into `pl_match_result`. One-shot, invoked deliberately — follow Phase 2's `--ingest` precedent for the invocation shape rather than inventing a new one.

Idempotent: running it twice must not double the rows. The spec requires this of the ongoing sync; the same property makes the backfill safely re-runnable while you are still getting it right.

Tests use **real CSV fixtures** checked into test resources, per the spec — the bugs this task can have are all in the real files' shape.

**Done when:** the target seasons are in `pl_match_result` with correct team codes and scores, spot-checked against known real results; a second run changes nothing; writes in tests never touch production data.

### Task 4 — football-data.org sync

Keep the current season current. Idempotent for the same reason and in the same way as Task 3 — every scheduling shape in the spec can fire twice.

Whatever shape Task 1 chose, the trigger must be operable by someone who did not write it: if it is an endpoint, it needs its secret documented in `DEVELOPMENT.md`; if it is a CLI flag, it belongs in the commands block alongside `--ingest`.

Free tier is rate-limited (10 req/min). Respect it, and fail in a way that says so rather than looking like a network error.

**Done when:** a sync run brings the current season's completed matches into `pl_match_result`; a second run changes nothing; the trigger is documented where an operator will find it.

### Task 5 — `getTeamForm`

Recent completed results for a team: opponent, venue, score, outcome. Reuse `FixtureTools`' team-name resolution rather than writing a third variant of it.

Per the spec, every match names its season and the reply states the range covered — a 2019/20 result must not read as recent.

**Done when:** the tool returns correct recent results for a known club, verified against the real table; an unknown club and a database failure both return their intended strings rather than throwing; season attribution is present in the output.

### Task 6 — `getHeadToHead`

Past meetings between two clubs across seasons, same attribution discipline.

**Done when:** a known fixture pairing returns real historical meetings; the same failure paths behave; the pair is order-insensitive (Arsenal vs Chelsea and Chelsea vs Arsenal give the same history).

> **Review checkpoint.** Request a code review of Tasks 1–6 before touching the prompt. Apply findings before continuing.

### Task 7 — Prompt, verification and documentation

Tell the model when historical results are worth consulting, and — the point of the attribution work — that old results are evidence about tendencies, not statements about current form. Consolidate the multi-season attribution rule into `AGENTS.md`'s conventions rather than adding a third per-tool exception.

Verify through the real chat flow, not mocks: a form question uses the new tools and attributes results to their seasons; a question about *upcoming* fixtures still goes to `FixtureTools` and does not drag in history.

Update `docs/STATUS.md` with what was built, deviations, and anything the spec failed to anticipate.

**Done when:** both question types behave correctly against the running application, and the documentation reflects what exists.

> **Review checkpoint.** Full-phase review. Apply findings before considering Phase 3 complete.
