# TODO — deferred and not-done items

Consolidated list of code and documentation items that were decided but not built, or explicitly
deferred. Detailed history and reasoning live in `docs/STATUS.md`; this file is the working
backlog. Status column: `open` = not started, `in progress` = being worked on, `deferred` = pushed
to a later date or season by owner decision.

## This session (2026-08-16)

| Item | Type | Status | Notes |
|---|---|---|---|
| Strategy docs: defence/clean sheets (incl. DefCon), FDR/fixture planning, goalkeeper strategy, mini-league/H2H | Doc | done | `strategy-defence-and-clean-sheets.md`, `strategy-fixture-planning-and-fdr.md`, `strategy-goalkeepers.md`, `strategy-mini-league-and-h2h.md` (2026-08-16, ingested) |
| Automated freshness tracking for `penalty-and-set-piece-takers.md` | Code | deferred | Owner decision: keep the document manual (update via LLM-assisted research, then `--ingest`). The manual update procedure is documented in the document itself ("Sources and how to update this document"). If revisited: a startup sync runner in the `StartupPriceSyncRunner` pattern (persisted staleness marker, fetch FFScout hub, diff against the doc, alert on change — not auto-edit) |
| Strategy doc: Blank and Double Gameweeks (BGW/DGW) planning | Doc | deferred | Explicitly deferred to a later season. The single biggest structural gap — chip interplay (BB in DGW, FH in BGW, WC into DGW), transfer planning around schedule changes |
| Strategy doc: set-and-forget / low-maintenance playstyle | Doc | open | From gap analysis; lower priority |
| Strategy docs: evaluating promoted clubs and new signings; deadline/team-news management | Doc | done | Two separate files per owner request: `strategy-promoted-clubs-and-new-signings.md` (promoted clubs and new signings as separate frameworks in one doc) and `strategy-deadline-and-team-news.md` (own file) (2026-08-16, ingested) |

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
| CI: every test hits live Supabase — needs Testcontainers fixtures or a read-only secret | Infra | open | No CI yet; deploy is manual (`fly deploy --ha=false --remote-only`) |
| Tool output intra-day freshness (`player.updated_at` unused) | Code | open | Recorded in STATUS.md Phase 1 review notes |
| Deadline and kickoff rendering in JVM default timezone, no label | Code | done | Fixed 2026-08-18. `UkTime.format()` renders every FPL timestamp as `yyyy-MM-dd HH:mm UK` via `Europe/London` (carries the GMT/BST switch, so it is right in both halves of the year). Applied to the gameweek deadline, `FixtureTools` kickoffs and `HistoricalTools` kickoffs — the Phase 4 fix had only covered `FixtureDifficultyTools`. `UkTimeTest` pins both DST sides with the JVM default forced to a third zone |
| Frontend design system (all 6 tasks) | Code | done | Merged to `master` 2026-08-17 (`4b79f7e`) and deployed. Final whole-branch review + one fix wave applied; the four follow-ups it deferred (type scale tokens, shared turn typography, `--redcard` on errors, disabled-state tokens) are also done (`69519e6`) |
| iOS keyboard-covers-composer fallback | Code | open | Open product question from the frontend design system work; `interactive-widget=resizes-content` is Chrome/Firefox-only, and WebKit has not implemented it (bug 259770). `height: 100dvh` + safe-area padding carry it today; no JS fallback exists |
| Disabled-state contrast is deliberately low (2.38:1 / 2.53:1) | Design | open | Recorded, not a defect: WCAG exempts inactive components and a disabled control that measures like body text does not read as disabled. Revisit only if user testing says the send button looks broken rather than inactive |
