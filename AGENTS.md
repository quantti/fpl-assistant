# AGENTS.md

Project instructions for coding agents. Claude Code loads this via the `@AGENTS.md` import in `CLAUDE.md`.

## Project

General-purpose Fantasy Premier League (FPL) chatbot. Web chat UI backed by an LLM with three knowledge layers: static FPL rules in the system prompt, strategy docs via RAG (pgvector), and live/historical stats via tool calls against Supabase.

Auth (Supabase JWT) + Stripe billing are deferred to the last phase. Build the chatbot first.

## Architecture

- **Backend:** Spring Boot 4.1, Java 25, Spring AI 2.0.0, Gradle. Serves both REST API and the built frontend as static resources from a single Fly.io app.
- **Frontend:** Vite 8.x + React 19 + TypeScript + Mantine v9 + CSS Modules. No Tailwind. (Mantine v9 requires React ≥19.2 — React 18 will not install.)
- **DB:** Supabase PostgreSQL — shared with the `tapas-fpl-app` project (`~/dev/tapas-fpl-app`). FPL stats (players, fixtures, gw stats, ownership, xG/xA) already live there. New tables added by this project, all `fpl_`-prefixed or otherwise unambiguous because the
schema is shared: `fpl_embeddings`, `pl_match_result`, `fpl_player_price`, `fpl_daily_price`,
`fpl_user_profile`, `fpl_conversation`, `fpl_message`, `fpl_usage_event`,
`fpl_invite_redemption`. Never take a generic
name — `CREATE TABLE IF NOT EXISTS` binds silently to tapas' table if they own it first.
- **LLM provider:** swappable via `SPRING_AI_PROVIDER` env var (`anthropic` default, or `openai`). `OPENAI_API_KEY` is required regardless of this setting from Phase 2 onward — RAG embeddings always use OpenAI (Anthropic has no embeddings API), and that autoconfiguration activates independently of the chat provider.
- **Chat transport:** `POST /api/chat` returns `text/event-stream` (SSE). Frontend reads via `fetch` + `ReadableStream`.

## Key references

- **Current status, deviations from the plan, known issues: `docs/STATUS.md`** — read this first when picking the work back up
- **Setup, commands, DB connection details: `docs/DEVELOPMENT.md`**
- Design spec: `docs/superpowers/specs/2026-06-27-fpl-assistant-design.md`
- Phase 1 plan: `docs/superpowers/plans/2026-06-27-phase1-core-chatbot.md` — several steps are stale; check `docs/STATUS.md` before following one
- Phase 2 (RAG) spec: `docs/superpowers/specs/2026-08-14-phase2-rag-design.md`, plan: `docs/superpowers/plans/2026-08-14-phase2-rag.md`
- Phase 3 (historical data) spec: `docs/superpowers/specs/2026-08-15-phase3-historical-data-design.md`, plan: `docs/superpowers/plans/2026-08-15-phase3-historical-data.md`
- Phase 4 (enrichment) spec: `docs/superpowers/specs/2026-08-15-phase4-enrichment-design.md`, plan: `docs/superpowers/plans/2026-08-15-phase4-enrichment.md`
- Source DB schema (tapas): `~/dev/tapas-fpl-app/backend/DB.md`
- FPL game rules: `~/dev/tapas-fpl-app/backend/FPL_RULES.md`

## Conventions

- All DB queries filter by a season resolved through `SeasonResolver`, not by `is_current` directly. A season row is created weeks before its data is synced, so `WHERE season_id = (SELECT id FROM season WHERE is_current = true)` returns nothing across the changeover. The resolver prefers the current season once it holds data and otherwise falls back to the most recent season that does. **The one deliberate exception among the tools is `HistoricalTools` (`getTeamForm`, `getHeadToHead`), which must NOT filter through `SeasonResolver`** — spanning seasons is the point, and `pl_match_result` carries `season` on every row. Do not bolt season filtering onto those tools. **On the write side, `FootballDataSyncService` is the same kind of exception:** it stamps each row with the season derived from the payload (`season.startDate` on the match itself), compared against the season the database flags `is_current`, never with the season `SeasonResolver` resolves. The resolver falls back to a completed season for as long as tapas has not synced the new one, so keying the write guard on it left the current season's results unwritten for weeks after kickoff. Do not "fix" that back onto `SeasonResolver`.
- Season data is never interchangeable, and every reply must attribute its numbers. One discipline, three schemes, one per knowledge layer:
  - **Stats tools** (`getGameweekInfo`, `getPlayerStats`, `getUpcomingFixtures`, `getPlayerExpectedStats`, `getTeamExpectedGoals`, `getDifferentialPicks`, `getCaptainCandidates`, `getFixtureDifficulty`): prefix every reply with `SeasonContext.provenance()`, which names the season and states whether it is the one being played. Fallback data must always be labelled historical — the system prompt instructs the model to attribute it by season and never call it current form. Later phases weight earlier seasons differently, and the current season by how far into it we are; that weighting depends on this provenance being explicit. One reply can carry two such lines: once the resolved season is the one being played, `getPlayerStats` shows the previous season's statistics beside it under `SeasonContext.previousSeasonProvenance(boolean)` — the prompt's early-season weighting rule needs both seasons on the card. In that dual-season reply the banner is not repeated: each block carries its own line and the surrounding text carries none.
    **The two ranking tools (`getDifferentialPicks`, `getCaptainCandidates`) blend both seasons
    per player and carry one banner only:** the previous season is named on every pick and in the
    footer, not on a second `Data source:` line, because a second banner would claim every number
    below it is last season's and under blending none purely is. `ExpectedStatsTools` is the
    opposite case and does get a second line — it shows fixtures as rows, fills a short
    current-season window from last season, labels each row with its own season, and the second
    banner therefore describes a labelled block rather than the whole reply.
  - **RAG strategy tool** (`StrategyTools.searchStrategyDocs`): does NOT prefix with `SeasonContext.provenance()` — the corpus is not resolved through `SeasonResolver`, and a single banner line cannot describe a mix of season-long reference and dated timely documents. Instead every retrieved chunk carries its own attribution: document `title`, `section`, `kind` (`reference`/`timely`) and the `valid_from`/`valid_until` window, and the system prompt instructs the model to weigh `timely` content by age. This is the RAG-side counterpart of the provenance discipline, for the same reason. Do not "fix" this tool back into the banner convention.
  - **Historical tools** (`HistoricalTools.getTeamForm`, `getHeadToHead`): every match names its own season and the reply states the range covered; `getTeamForm` also reports the date of its most recent known match so a stalled sync shows up as visibly old data. Old results are evidence about tendencies, not statements about current form — a 2019/20 result must not read as recent, and the system prompt instructs the model to attribute by season and never call historical results current form.
- Do NOT alter existing tapas tables. Only add new tables. **One deliberate exception, granted by
  the owner on 2026-08-26: `player_fixture_stats` carries four columns this project adds and
  fills — `defensive_contribution`, `clearances_blocks_interceptions`, `recoveries`, `tackles`.**
  FPL introduced defensive contribution for 2025/26 and tapas' schema predates it, so the values
  arrive in the `element-summary` payload tapas already reads and are dropped by its named column
  list. They belong at this table's grain (per player, per fixture) and a parallel `fpl_` table
  would duplicate the key and drift on every re-sync. `DefensiveStatsSchema` adds them
  (`ADD COLUMN IF NOT EXISTS`, nullable, no default — zero is a real reading and must stay
  distinct from "not fetched"), and `DefensiveStatsSyncService` only ever UPDATEs those four
  columns, never INSERTs a row: tapas owns the rows, this owns the columns. Neither writer can
  clear the other's values, because both write named column lists. Do not extend this exception to
  other tapas tables or other columns without the owner saying so.
- Player prices stored as integers ×10 (`now_cost=75` = £7.5m). `element_type`: 1=GK, 2=DEF, 3=MID, 4=FWD.
- External APIs (vaastav CSVs, football-data.org, FPL `bootstrap-static`) are hit only at sync time, never at chat time — everything queries Supabase.
- **Today's price and today's injury flags come from `fpl_daily_price`, not from the tapas `player` table.** That table is synced weekly by `tapas-fpl-app` and was measured 85 days stale; `fpl_daily_price` is captured daily at 02:00 UK from FPL's own API (prices change at 00:00 UK) and owes tapas nothing, so it also survives the season changeover. It carries `status`/`news` — the availability picture — and `penalties_order`/`direct_fk_order`/`corners_order` — the set-piece picture — alongside `now_cost`. Set-piece duty lives here rather than in the strategy corpus because a written list of takers is written before anyone has taken one, and the first weeks of a season revise it. Dated rows, so it answers *when* a flag appeared; it cannot be backfilled, since FPL publishes only today's state. **The daily-capture tools (`getPriceHistory`, `getPlayerAvailability`, `getFlaggedPlayers`, `getSetPieceTakers`) are a documented exception like `HistoricalTools`: they use `DailyPriceSource.provenance()` (season + capture date) instead of `SeasonContext.provenance()`, because the resolver would attribute changeover-window data to the wrong season. Do not "fix" them back onto `SeasonContext.provenance()`.** Note that `element_id` in this table is season-scoped (FPL renumbers every season) — every query keyed on it must also filter on `season`.
- Tools query Supabase via `JdbcTemplate`, annotated with Spring AI `@Tool`.
- **No `@Tool` method takes a parameter that identifies an account.** The model fills tool
  parameters from the conversation, so a `userId` (or a team id) in a tool's schema is a working
  request for someone else's data the moment a user asks for it. The user id travels from the JWT
  to the tools in Spring AI's `ToolContext` (`ChatController` → `ChatService.stream` →
  `.toolContext(...)`), which Spring AI keeps out of the generated schema; `MyTeamTools.getMyTeam`
  reads it through `ToolUser.required`. `SecurityContextHolder` does not work there — tool calls
  run on the provider's reactive threads. The same context carries the turn's `FplBudget`.
- **The my-team tools (`getMyTeam`, `getMyMiniLeagues`, `getMiniLeagueOpponents`) are a third
  documented chat-time exception to "external APIs only at sync time"**, alongside
  `FplEntryLookup`: a manager's squad is per user and per gameweek, so there is nothing to
  pre-sync. They also fetch `bootstrap-static` (cached app-wide for six hours) because it is the
  only source certain to be on the same season as the `element` ids in a manager's picks — FPL
  renumbers every player every season, so filling a squad from an unsynced database names
  *different players*. Supabase adds fixture difficulty and xG/xA only while `SeasonResolver`
  names the season being played. Their provenance is two lines, not one: the squad block states
  the gameweek it was locked for and when it was fetched, and the statistics block carries
  `SeasonContext.provenance()`. Do not fold them into the single-banner convention.
- **`SquadTools.buildSquad` is a fourth documented two-line-provenance exception**, alongside
  the my-team tools. Its candidate pool — identity, club, price, ownership, availability —
  comes from `fpl_daily_price` and carries `DailyPriceSource.provenance()`; its metrics come
  from `player_fixture_stats` and carry `SeasonContext.provenance()`. The two sources have
  different freshness and, during the changeover, different seasons, so a single banner would
  attribute one of them wrongly. Building the pool from the daily capture rather than the
  tapas `player` table is deliberate and is what keeps issue #50 from recurring here: it also
  means the tool works while tapas has not synced the season being played. Do not fold this
  into the single-banner convention. Its metrics line is followed by the blend sentence, which
  names both seasons and the ramp; each starter shows its own weight where that is under 100%.
- **Cross-season weighting is one ramp in one place, `SeasonBlend`.** Every ranking metric blends
  the season being played with the previous one on a weight driven by the player's own qualifying
  matches this season (60+ minutes): 1 → 10%, 2 → 30%, 3 → 60%, 4 → 80%, 5-7 → 90%, 8+ → 100%.
  Rates blend; evidence counts (`mins`, `matches`, `defcon_matches`) add, because the floors that
  read them ask how much evidence exists. A player with no previous-season record is ranked on
  this season alone once the minutes floor is cleared and the reply says so. Do not reintroduce a
  single-season switch, and do not write the ramp a second time in SQL.
- **Tests seed the data they assert on and never depend on production content.** The suite runs
  against a disposable container (`scripts/test-db.sh`), which holds the tapas schema
  (`scripts/dump-test-schema.sh` regenerates the dump) and nothing else. Anything reading
  tapas-owned tables seeds through `TapasSeed`, which refuses to run against the shared
  database; everything else seeds its own rows in the `_test` tables. A test that needs a whole
  synced season as its oracle is tagged `realdata` and excluded from `./gradlew test` — there is
  exactly one, and adding another should feel like a decision, not a convenience.

## Commits and pull requests

Short. The narrative belongs in `docs/STATUS.md` and the plan's outcome blocks, not in git
metadata — nobody reads a git log for a design discussion, and a long message hides the one line
that mattered.

- **Commit subject:** one line, imperative, ~60 chars. A body only when the *why* is not obvious
  from the diff, and then 1–3 sentences. Never restate what the diff shows, never paste test
  output, never list every file touched.
- **PR title:** one line. **PR body:** 8 lines at most — what changed, why, how it was verified,
  and a link to the plan if there is one. No headings, no checklists, no findings dumps.
- **Describe the change, not the project.** Environment limits, plan status, background the
  reader already lives with ("branch protection is unavailable on this plan") belong in
  `docs/TODO.md` or `docs/STATUS.md`. A PR body that explains the repository is padding.
- **Merge:** squash; the merge message is the PR title.
- If a decision needs more room than that, it is a `docs/STATUS.md` entry — write it there and
  keep the commit pointing at it.

## Progress tracking

Plans use a single `**PROGRESS:**` line (e.g. `Task 3/14 done`), updated once per task. Do NOT use per-step `- [ ]` checkboxes — they waste tokens on every flip.

## Deployment

Single Fly.io app (region `cdg` — `mad` is deprecated and can no longer provision machines)
serving API + frontend. Frontend built in a Docker multi-stage build, copied into Spring Boot
`static/`. Secrets via `fly secrets set`. Remote: `origin` → `github.com/quantti/fpl-assistant`
(private).

> ### NEVER PUSH TO `master`
>
> `master` deploys. A push there runs `.github/workflows/deploy.yml` and a green run ships
> straight to production — no review, no second pair of eyes. **Agents push a branch and open a
> pull request. Always.** The same tests run on the PR; the owner merges.
>
> There is no branch protection to catch a mistake: GitHub refuses it for private repositories on
> the Free plan (verified 2026-08-19 — the protection and rulesets APIs both answer 403 "Upgrade
> to GitHub Pro"). The substitute is the `scripts/hooks/pre-push` hook, which runs the suite and
> refuses a master push that has no terminal to confirm at — which is exactly the case for an
> agent. **Do not work around it: never `git push --no-verify`, never re-point `core.hooksPath`,
> never push to master "because the tests passed anyway".** If a push is refused, that is the
> intended outcome, not an obstacle to route around.

**Deploying is `.github/workflows/deploy.yml`, not a manual command.** A push to `master` runs
the suite against an ephemeral Postgres container and, if it passes, runs
`flyctl deploy --ha=false --remote-only` and smoke-tests `/actuator/health` from outside. Pull
requests run the tests only. `workflow_dispatch` re-deploys without a push. A manual
`fly deploy` is for emergencies, and is the one path that can ship a red suite.

Install the pre-push hook once per clone: `git config core.hooksPath scripts/hooks`.

A manual `fly deploy` always prompts first: `.claude/settings.json` routes any Bash command
that runs one through `scripts/hooks/ask-before-deploy.sh`, whatever path or wrapper it is
invoked by. It ships the working tree untested, so it is the emergency path, never the habit.

Env files are gitignored (only `*.example` templates are tracked) and there are two:
`backend/.env.test` points at the local test container, `backend/.env.supabase` at production.
Tests must never run against Supabase — see the testing convention below.
