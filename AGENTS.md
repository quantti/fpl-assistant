# AGENTS.md

Project instructions for coding agents. Claude Code loads this via the `@AGENTS.md` import in `CLAUDE.md`.

## Project

General-purpose Fantasy Premier League (FPL) chatbot. Web chat UI backed by an LLM with three knowledge layers: static FPL rules in the system prompt, strategy docs via RAG (pgvector), and live/historical stats via tool calls against Supabase.

Auth (Supabase JWT) + Stripe billing are deferred to the last phase. Build the chatbot first.

## Architecture

- **Backend:** Spring Boot 4.1, Java 25, Spring AI 2.0.0, Gradle. Serves both REST API and the built frontend as static resources from a single Fly.io app.
- **Frontend:** Vite 8.x + React 19 + TypeScript + Mantine v9 + CSS Modules. No Tailwind. (Mantine v9 requires React ≥19.2 — React 18 will not install.)
- **DB:** Supabase PostgreSQL — shared with the `tapas-fpl-app` project (`~/dev/tapas-fpl-app`). FPL stats (players, fixtures, gw stats, ownership, xG/xA) already live there. New tables added by this project: `fpl_embeddings`, `pl_match_result`, `conversation`, `message`, `user_subscription`.
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

- All DB queries filter by a season resolved through `SeasonResolver`, not by `is_current` directly. A season row is created weeks before its data is synced, so `WHERE season_id = (SELECT id FROM season WHERE is_current = true)` returns nothing across the changeover. The resolver prefers the current season once it holds data and otherwise falls back to the most recent season that does. **The one deliberate exception is `HistoricalTools` (`getTeamForm`, `getHeadToHead`), which must NOT filter through `SeasonResolver`** — spanning seasons is the point, and `pl_match_result` carries `season` on every row. Do not bolt season filtering onto those tools.
- Season data is never interchangeable, and every reply must attribute its numbers. One discipline, three schemes, one per knowledge layer:
  - **Stats tools** (`getGameweekInfo`, `getPlayerStats`, `getUpcomingFixtures`, `getPlayerExpectedStats`, `getTeamExpectedGoals`, `getDifferentialPicks`, `getCaptainCandidates`, `getFixtureDifficulty`): prefix every reply with `SeasonContext.provenance()`, which names the season and states whether it is the one being played. Fallback data must always be labelled historical — the system prompt instructs the model to attribute it by season and never call it current form. Later phases weight earlier seasons differently, and the current season by how far into it we are; that weighting depends on this provenance being explicit.
  - **RAG strategy tool** (`StrategyTools.searchStrategyDocs`): does NOT prefix with `SeasonContext.provenance()` — the corpus is not resolved through `SeasonResolver`, and a single banner line cannot describe a mix of season-long reference and dated timely documents. Instead every retrieved chunk carries its own attribution: document `title`, `section`, `kind` (`reference`/`timely`) and the `valid_from`/`valid_until` window, and the system prompt instructs the model to weigh `timely` content by age. This is the RAG-side counterpart of the provenance discipline, for the same reason. Do not "fix" this tool back into the banner convention.
  - **Historical tools** (`HistoricalTools.getTeamForm`, `getHeadToHead`): every match names its own season and the reply states the range covered; `getTeamForm` also reports the date of its most recent known match so a stalled sync shows up as visibly old data. Old results are evidence about tendencies, not statements about current form — a 2019/20 result must not read as recent, and the system prompt instructs the model to attribute by season and never call historical results current form.
- Do NOT alter existing tapas tables. Only add new tables.
- Player prices stored as integers ×10 (`now_cost=75` = £7.5m). `element_type`: 1=GK, 2=DEF, 3=MID, 4=FWD.
- External APIs (vaastav CSVs, football-data.org, FPL `bootstrap-static`) are hit only at sync time, never at chat time — everything queries Supabase.
- **Today's price and today's injury flags come from `fpl_daily_price`, not from the tapas `player` table.** That table is synced weekly by `tapas-fpl-app` and was measured 85 days stale; `fpl_daily_price` is captured daily at 02:00 UK from FPL's own API (prices change at 00:00 UK) and owes tapas nothing, so it also survives the season changeover. It carries `status`/`news` — the availability picture — alongside `now_cost`. Dated rows, so it answers *when* a flag appeared; it cannot be backfilled, since FPL publishes only today's state. **The daily-capture tools (`getPriceHistory`, `getPlayerAvailability`, `getFlaggedPlayers`) are a documented exception like `HistoricalTools`: they use `DailyPriceSource.provenance()` (season + capture date) instead of `SeasonContext.provenance()`, because the resolver would attribute changeover-window data to the wrong season. Do not "fix" them back onto `SeasonContext.provenance()`.** Note that `element_id` in this table is season-scoped (FPL renumbers every season) — every query keyed on it must also filter on `season`.
- Tools query Supabase via `JdbcTemplate`, annotated with Spring AI `@Tool`.
- **Tests seed the data they assert on and never depend on production content.** The suite runs
  against a disposable container (`scripts/test-db.sh`), which holds the tapas schema
  (`scripts/dump-test-schema.sh` regenerates the dump) and nothing else. Anything reading
  tapas-owned tables seeds through `TapasSeed`, which refuses to run against the shared
  database; everything else seeds its own rows in the `_test` tables. A test that needs a whole
  synced season as its oracle is tagged `realdata` and excluded from `./gradlew test` — there is
  exactly one, and adding another should feel like a decision, not a convenience.

## Progress tracking

Plans use a single `**PROGRESS:**` line (e.g. `Task 3/14 done`), updated once per task. Do NOT use per-step `- [ ]` checkboxes — they waste tokens on every flip.

## Deployment

Single Fly.io app (region `cdg` — `mad` is deprecated and can no longer provision machines)
serving API + frontend. Frontend built in a Docker multi-stage build, copied into Spring Boot
`static/`. Secrets via `fly secrets set`. Remote: `origin` → `github.com/quantti/fpl-assistant`
(private).

**Deploying is `.github/workflows/deploy.yml`, not a manual command.** A push to `master` runs
the suite against an ephemeral Postgres container and, if it passes, runs
`flyctl deploy --ha=false --remote-only` and smoke-tests `/actuator/health` from outside. Pull
requests run the tests only. `workflow_dispatch` re-deploys without a push. A manual
`fly deploy` is for emergencies, and is the one path that can ship a red suite.

Env files are gitignored (only `*.example` templates are tracked) and there are two:
`backend/.env.test` points at the local test container, `backend/.env.supabase` at production.
Tests must never run against Supabase — see the testing convention below.
