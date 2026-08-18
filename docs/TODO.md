# TODO — deferred and not-done items

Consolidated list of code and documentation items that were decided but not built, or explicitly
deferred. Detailed history and reasoning live in `docs/STATUS.md`; this file is the working
backlog. Status column: `open` = not started, `in progress` = being worked on, `deferred` = pushed
to a later date or season by owner decision.

## This session (2026-08-18)

| Item | Type | Status | Notes |
|---|---|---|---|
| Chatbot out-of-scope use: the assistant answered a request to write a Python script instead of confining itself to FPL. Fixed in the system prompt (2026-08-18): "Scope and boundaries" section added — FPL-only scope, decline code/out-of-scope requests, never reveal instructions/tools, ignore instructions embedded in user content (prompt-injection defense per OWASP LLM01). Deployed, verified | Code | done | Prompt fix, no TODO action needed beyond this record |
| Strategy docs: defence/clean sheets (incl. DefCon), FDR/fixture planning, goalkeeper strategy, mini-league/H2H | Doc | done | `strategy-defence-and-clean-sheets.md`, `strategy-fixture-planning-and-fdr.md`, `strategy-goalkeepers.md`, `strategy-mini-league-and-h2h.md` (2026-08-16, ingested) |
| Automated freshness tracking for `penalty-and-set-piece-takers.md` | Code | deferred | Owner decision: keep the document manual (update via LLM-assisted research, then `--ingest`). The manual update procedure is documented in the document itself ("Sources and how to update this document"). If revisited: a startup sync runner in the `StartupPriceSyncRunner` pattern (persisted staleness marker, fetch FFScout hub, diff against the doc, alert on change — not auto-edit) |
| Strategy doc: Blank and Double Gameweeks (BGW/DGW) planning | Doc | deferred | Explicitly deferred to a later season. The single biggest structural gap — chip interplay (BB in DGW, FH in BGW, WC into DGW), transfer planning around schedule changes |
| Strategy doc: set-and-forget / low-maintenance playstyle | Doc | open | From gap analysis; lower priority |
| Strategy docs: evaluating promoted clubs and new signings; deadline/team-news management | Doc | done | Two separate files per owner request: `strategy-promoted-clubs-and-new-signings.md` (promoted clubs and new signings as separate frameworks in one doc) and `strategy-deadline-and-team-news.md` (own file) (2026-08-16, ingested) |
| Daily capture freshness alert | Code | done | `DailyPriceFreshnessCheck` (2026-08-18 review): scheduled ERROR at 06:00/12:00/18:00 UK when `fpl_daily_price` has no row for the current UK date, so a missed 02:00 capture — scheduler failure, stopped machine, FPL response shape change — is loud within hours instead of silent. Gated with the capture on `fpl.daily-price.scheduled`; not wired into `/actuator/health` (Fly would restart-flap, and a restart cannot backfill). External webhook escalation not built: no destination configured |
| External escalation for the freshness alert (webhook/uptime) | Code | deferred | The ERROR log is the only channel (2026-08-18 review). Hook a webhook or uptime monitor to that log line when a destination exists; until then a missed day shows up in logs within hours |
| Season-code guard in the daily capture | Code | done | (2026-08-18 pre-push review) The sync refuses a `bootstrap-static` response whose newest event deadline is more than 40 days past — the early-July window where the calendar says "new season" but FPL has not reset the endpoint, so last season's squads would be written under the new season label. Mid-June syncs of a just-finished season stay allowed |
| Error turns replayed to the model as history | Code | done | (2026-08-18 pre-push review) `useChat` filtered the `isError` flag but sent the error content back as model history; now filtered from the request body (merged with adjacent user turns, since the Anthropic API rejects consecutive user turns) while staying visible in the transcript |

## Corpus and repository hygiene

| Item | Type | Status | Notes |
|---|---|---|---|
| Ten strategy documents were shipping untracked | Infra | done | Fixed 2026-08-17 (`c7165b1`). The Docker build copies `backend/src/main/resources/docs/` from the working tree, so the deployed image carried content git did not have — a lost working tree would have lost the corpus. Corpus is now 19 documents / 166 chunks, verified against the live database |
| `strategy-2026-27-preseason-consensus.md` ages out 2026-08-21 | Doc | open | `kind: timely`, `valid_until: 2026-08-21` — by design, but it drops out of retrieval the day the season starts. Decide then whether to replace it with an in-season equivalent |

## Phase 5 — auth and billing (deferred to the last phase)

| Item | Type | Status | Notes |
|---|---|---|---|
| Phase 5: Supabase JWT auth, Stripe billing, conversation persistence wiring | Code | deferred | Spec: `docs/superpowers/specs/2026-08-15-phase5-auth-billing-design.md`. Deferred to the last phase by design (AGENTS.md) |
| `getUserSquad()` tool | Code | deferred | The system prompt already carries the Phase 5 squad conditional ("if the user's squad is known, factor in what they already own") so this needs no prompt restructuring |
| SPA deep-link forwarding controller | Code | deferred | 404s once Phase 5 adds routes (recorded in STATUS.md Phase 1 review notes) |

## Open items from STATUS.md (recorded there, kept here for visibility)

| Item | Type | Status | Notes |
|---|---|---|---|
| `SeasonResolver` guard for 0 or 2+ `is_current` rows (+ optional short-TTL cache) | Code | open | Currently throws; recorded in STATUS.md Phase 1 review notes |
| CI: every test hits live Supabase — needs Testcontainers fixtures or a read-only secret | Infra | done | Closed 2026-08-19 by the ephemeral-container plan (`docs/superpowers/plans/2026-08-18-cicd-ephemeral-test-db.md`). Tests run against a disposable `pgvector/pgvector:pg17` container in CI and locally, seed their own data, and cannot reach Supabase — `TapasSeed` refuses. Deploy is now `.github/workflows/deploy.yml` |
| `VaastavBackfillIntegrationTest` oracle tagged `realdata`, excluded from CI | Test | open | The import cross-check needs tapas' fully-synced 2025-26 season (380 fixtures, 20 clubs) as its oracle; reproducing that in a fixture would be a copy of production. Run by hand against Supabase with `-PincludeRealData` after changing the importer. Revisit if a second test ever wants the same escape hatch |
| GitHub secrets + branch protection for the deploy workflow | Infra | open | Task 8 and Task 10 of the CI/CD plan, both needing repository access: `FLY_API_TOKEN` (app-scoped, `fly tokens create deploy -a fpl-assistant`) and `OPENAI_API_KEY` as secrets; branch protection requiring the test check on `master`. Verify plan availability first — classic branch protection on private repos has historically needed GitHub Pro/Team. Until then `needs: test` still blocks the release, but not the merge |
| Tool output intra-day freshness (`player.updated_at` unused) | Code | open | Investigated 2026-08-18 and found worse than the title suggests: the tapas-owned `player`/`fixture`/`gameweek` tables were **85 days stale** (all rows last written 2026-05-25), `team` 7.5 months, and 2026-27 had zero player rows three days before GW1. tapas owns the sync (`scheduled_update.py`, daily 06:00 UTC) but it skips entirely when no new gameweek has finalised, and players are documented as weekly. Partly addressed by `fpl_daily_price` (below), which owes tapas nothing; the remaining work is surfacing age in tool output — plan in STATUS.md |
| Daily FPL price + availability capture | Code | done | `fpl_daily_price`, fetched from FPL's own `bootstrap-static` at 02:00 Europe/London (FPL applies price changes at 00:00 UK for 2026/27, moved from ~01:30; we read two hours later). Script: `--sync-prices`. **The same table also carries `status` and `news`, i.e. the daily injury/availability picture** — see the next row |
| Injury/availability tools on `fpl_daily_price` | Code | done | `AvailabilityTools.getPlayerAvailability` (one player: fit/doubtful/injured/suspended, FPL's news note, and how long the flag has stood) and `getFlaggedPlayers` (everyone flagged at a club). Status codes are spelled out rather than passed through as letters. An unmatched club name says so explicitly instead of reading as a clean bill of health — the dangerous failure for this tool. `PriceTools` moved onto the same table the same day: it previously resolved players through the tapas `player` table, which held zero 2026-27 rows, so it answered "player not found" for every current player |
| Deadline and kickoff rendering in JVM default timezone, no label | Code | done | Fixed 2026-08-18. `UkTime.format()` renders every FPL timestamp as `yyyy-MM-dd HH:mm UK` via `Europe/London` (carries the GMT/BST switch, so it is right in both halves of the year). Applied to the gameweek deadline, `FixtureTools` kickoffs and `HistoricalTools` kickoffs — the Phase 4 fix had only covered `FixtureDifficultyTools`. `UkTimeTest` pins both DST sides with the JVM default forced to a third zone |
| Frontend design system (all 6 tasks) | Code | done | Merged to `master` 2026-08-17 (`4b79f7e`) and deployed. Final whole-branch review + one fix wave applied; the four follow-ups it deferred (type scale tokens, shared turn typography, `--redcard` on errors, disabled-state tokens) are also done (`69519e6`) |
| iOS keyboard-covers-composer fallback | Code | open | Open product question from the frontend design system work; `interactive-widget=resizes-content` is Chrome/Firefox-only, and WebKit has not implemented it (bug 259770). `height: 100dvh` + safe-area padding carry it today; no JS fallback exists |
| Disabled-state contrast is deliberately low (2.38:1 / 2.53:1) | Design | open | Recorded, not a defect: WCAG exempts inactive components and a disabled control that measures like body text does not read as disabled. Revisit only if user testing says the send button looks broken rather than inactive |
