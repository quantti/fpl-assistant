# Status — Phase 1

**Progress:** Task 14/14 done — Phase 1 code complete, reviewed at two checkpoints (after
Task 10 and after Task 14), fixes applied both times. **The only unfinished step is the
Fly.io deploy itself:** `flyctl` is not installed and `fly launch`/`fly secrets
set`/`fly deploy` need the account owner (see Deployment below).

Plan: `docs/superpowers/plans/2026-06-27-phase1-core-chatbot.md`.
Spec: `docs/superpowers/specs/2026-06-27-fpl-assistant-design.md`.
Setup and commands: `docs/DEVELOPMENT.md`.

## Done

| Task | Commit | What exists now |
|---|---|---|
| 1 — Spring Boot scaffold | `a7fbffc` | Gradle build, Java 25 toolchain, Spring Boot 4.1.0, Spring AI 2.0.0 BOM, wrapper 9.7.0, `FplAssistantApplication` |
| 2 — DB connection | `aadb043` | `DbConnectionTest` queries the live Supabase `season` table and passes |
| 3 — System prompt + AI config | `d25586e` | `AiConfig` builds a `ChatClient` from the selected provider, `prompts/system-prompt.st` holds the FPL rules |
| — | `1c431d5` | Project instructions moved to `AGENTS.md`; `CLAUDE.md` is a one-line `@AGENTS.md` import |
| 4 — GameweekTools | `9ef1ef3` | `getGameweekInfo()` `@Tool`, plus `SeasonResolver` / `SeasonContext` used by every later tool |
| 5 — PlayerTools | `7711fc8` | `getPlayerStats(playerName)` `@Tool`: SeasonResolver + provenance prefix, LIKE wildcards escaped, `DataAccessException` returned as an error string |
| 6 — FixtureTools | `0422e1a` | `getUpcomingFixtures(teamName, nextN)` `@Tool`: all Amended 2026-08-14 guards — start GW `COALESCE(is_current, is_next, MIN)`, bound by distinct gameweeks (DGW-safe, unit-tested via extracted `window()`), postponed `gameweek IS NULL` fixtures listed separately. LIKE escape shared as `LikePatterns` |
| 7 — ChatService | (task7-chat-service) | `ChatService.stream(ChatRequest)` → `Flux<String>`: history windowed to last 20 messages and trimmed to open on a `user` turn (Anthropic 400s an assistant-first history — review catch), tools injected as `List<FplTool>` (marker interface — adding a tool class is registration-complete). `GameweekTools` harmonized to catch `DataAccessException` like the others |

| 8 — ChatController | `64513a7` | `POST /api/chat` SSE: validation caps (200 msgs / 4000 chars / role whitelist), per-IP rate limit (20/min), per-provider response-token caps, tokens JSON-encoded in `data:` (raw SSE drops leading spaces), `event: error` + terminal `event: done`. Live smoke verified: tool call streams progressively, provenance survives into the answer |
| 9 — Frontend scaffold | (tasks8-10 branch) | Vite 8.2 + React 19.2 + Mantine 9.5 + TS 6.0; `vite-env.d.ts` added (plan omitted it, CSS imports fail tsc without); `App.tsx` renders a placeholder until Task 13 wires ChatView (plan's version imported a not-yet-existing file) |

| 10 — MessageInput | `09d9899` | `MessageInput` component; IME composition guard added on review (plan's code submitted mid-composition Enter) |
| 11 — Message display | `b819c56` | `MessageThread` + `AssistantMessage` (react-markdown, streaming cursor) |
| 12 — useChat | `cea3fa6` | Speaks the Task 8 SSE contract: dispatches on the `event:` line, `JSON.parse`s token payloads, surfaces `event: error` instead of committing partial text, close without `event: done` = interrupted |
| 13 — ChatView | `8dc9d7f` | Full UI assembled; browser-verified headless (Chromium via Puppeteer) against the real backend through the Vite proxy — all three plan questions (gameweek, player stats, fixtures) answered correctly, provenance visible in the UI, markdown rendered, no console errors |
| 14 — Docker + Fly | (tasks11-14 branch) | Root `Dockerfile` (3-stage, `-XX:MaxRAMPercentage=75`) + root `fly.toml` (health check, pinned machine lifecycle) + `.dockerignore` + actuator health endpoint; image built and smoke-tested locally (container health UP, static frontend served, chat streams to `done`) |

## Deployment — the remaining manual steps

`flyctl` is installed at `~/.fly/bin/flyctl` (not on PATH by default — `export
PATH="$HOME/.fly/bin:$PATH"`), logged in as `kvanttinen@gmail.com`. The app is registered:

```
fly launch --no-deploy --name fpl-assistant --region mad --copy-config --yes
```

ran successfully — `fpl-assistant.fly.dev` exists in org `kari-vanttinen`, `fly.toml` at the
repo root is accepted, no machines exist yet (nothing deployed, no cost incurred). One
gotcha: `flyctl launch` ignored `--region mad` and registered the app at `cdg` (Paris) —
harmless since no machine had deployed yet, but `fly.toml`'s `primary_region` was corrected
back to `mad` by hand afterward (that field is what actually places the first machine).

Remaining, deliberately not run without the account owner present — needs real secrets and
is the point where the app goes live and can start costing money:

```bash
fly secrets set DATABASE_URL="jdbc:postgresql://aws-1-eu-west-1.pooler.supabase.com:6543/postgres?sslmode=require"
fly secrets set DB_USER="postgres.<project-ref>" DB_PASSWORD="<db-password>"
fly secrets set ANTHROPIC_API_KEY="sk-ant-..." OPENAI_API_KEY="sk-..."
fly secrets set SPRING_AI_PROVIDER="anthropic" CORS_ORIGINS="https://fpl-assistant.fly.dev"
fly deploy --ha=false
```

`--ha=false` matters: a first deploy otherwise creates two machines, and the in-memory
`RateLimitFilter` assumes one (two machines silently double the per-IP budget). Machines
auto-stop when idle (pinned in `fly.toml`), so the first request after idle pays a JVM cold
start — tens of seconds on the 512MB VM. If Supabase pooler blips ever flap the health check,
the knob is `management.health.db.enabled: false` (plan Task 14 amendment).

## The season problem, and the shape of the fix

This is the one design decision in Phase 1 that is not in the plan, and every remaining tool
depends on it.

The `season` row flagged `is_current` is created weeks before its data is synced. Right now
2026-27 is current and completely empty, while all 38 gameweeks and 841 players sit in the
finished 2025-26 season. The plan's filter —
`WHERE season_id = (SELECT id FROM season WHERE is_current = true)` — therefore matches nothing at
all, and would have kept matching nothing until the new season synced around 2026-08-21.

Three things resolve it, and all three matter:

1. **`SeasonResolver`** picks the season to query: the current one once it holds both gameweeks
   and players, otherwise the most recent season that does. When tapas syncs 2026/27 the resolver
   moves to it with no code change.
2. **`SeasonContext.provenance()`** opens every tool reply, naming the season and stating whether
   it is the one being played. Falling back silently would be worse than returning nothing: a
   finished season's numbers describe how a player performed then, not what they are worth now.
3. **The system prompt** requires the model to attribute those numbers to that season by name and
   never call them current form, price, ownership, or fixtures.

Later phases weight earlier seasons differently, and weight the current season by how far into it
we are. That weighting needs season provenance to be explicit rather than inferred, which is why
the label is part of the tool contract rather than a presentation detail.

Consequence for the remaining tools: **Tasks 5 and 6 must use `SeasonResolver` and prefix their
output with `provenance()`**, not the plan's `is_current` subquery.

## Deviations from the plan

Each is committed with its reasoning; collected here so the plan can still be read top to bottom
without acting on stale steps.

| Plan says | What was done | Why |
|---|---|---|
| Gradle 9.1 | 9.7.0 | SDKMAN no longer offers 9.1; the plan's real requirement is 9.x |
| `spring-boot-testcontainers` + `org.testcontainers:postgresql` dependencies | Removed | Boot 4.1's BOM does not manage the version, so `compileTestJava` failed outright. No task in the plan uses Testcontainers — every test runs against live Supabase |
| `.env.test` with `DB_USER=postgres`, pooler `aws-0` | `postgres.itmykooxrbrdbgqwsesb`, pooler `aws-1` | The plan's combination cannot authenticate; see `docs/DEVELOPMENT.md` |
| — | Added `spring.ai.model.chat: ${SPRING_AI_PROVIDER}` | Both providers' autoconfigurations loaded otherwise, and the unused one's blank API key failed context startup |
| `claude-sonnet-4-6` | `claude-sonnet-5` | Newer model in the same tier |
| System prompt for 2025/26 | Rewritten for 2026/27 | Four factual errors against the canonical `~/dev/tapas-fpl-app/backend/FPL_RULES.md`: wrong season; goalkeeper goals are 10 points, not 6; the AFCON GW16 free-transfer top-up does not apply this season; and "Free Hit cannot be used in both GW19 and GW20" is not a rule the reference states. Added the omitted saves / penalty-save / goals-conceded scoring and the 2026/27 changes (live bonus, live ranks, BPS tweaks) |
| `GameweekToolsTest` asserts `containsAnyOf("current", "next", "Gameweek")` | Asserts gameweek rows are present and the season is named | The original also passed on the empty-data message, because that message contains the word "current" |
| Tools filter on `is_current` | Filter via `SeasonResolver` | See above |
| OpenAI fallback `gpt-4o` | `gpt-5.6-terra` | GPT-4o retired from ChatGPT 2026-02 and is two generations behind; Terra is the current mid-tier API model |
| Task 5 name match on three single columns | Added `first_name \|\| ' ' \|\| second_name` predicate | LLMs pass full names; "Mohamed Salah" is contained in no single column, so the plan's SQL answered "not found" for a marquee player (review finding) |
| Task 5 `status != 'a'` → `"Unavailable (" + news + ")"` | `d` → "Doubtful", null/blank `news` suppressed | `d` means doubtful, not out; `news` is nullable, so the plan's format printed literally `Unavailable (null)` (review finding) |
| React 18 | React 19 | Mantine v9 requires React ≥19.2 — the plan's combination cannot install. Caught before Task 9, so only docs changed |

## 2026-08-14 design review — fixes applied

A three-agent review of the spec, plan, and architecture produced these same-day fixes (docs and
config only, no code):

- **Plan:** Global Constraints and the Self-Review table no longer teach the dead `is_current`
  filter. Inline **Amended 2026-08-14** blockquotes added to Tasks 5–8, 12 and 14 covering:
  SeasonResolver + provenance in all SQL, LIKE-wildcard escaping, FixtureTools guards (NULL
  current gameweek, postponed `gameweek IS NULL` fixtures, DGW-safe gameweek bound), history
  window (~20 messages), tool errors returned as strings, tool registration via injected
  collection, abuse caps (request validation, `max_tokens`, per-IP rate limit) and the SSE
  `event: error` / `event: done` contract, and a deploy that actually builds (root `fly.toml` +
  `Dockerfile`, no `dockerfile_path`, `-XX:MaxRAMPercentage=75` on the 512MB VM).
- **AGENTS.md + plan:** React 18 → 19.
- **`backend/build.gradle`:** removed `repo.spring.io/milestone` (Spring AI 2.0.0 GA is on Central).
- **`application.yml`:** OpenAI fallback model updated (table above).
- **`.gitignore`:** env patterns generalized to `**/.env*` + `!**/.env*.example` so a future
  `frontend/.env` cannot be committed silently.
- **`application.yml` + `DEVELOPMENT.md`:** `prepareThreshold=0` set via Hikari
  `data-source-properties` — the transaction pooler breaks pgjdbc server-side prepared statements
  under concurrent load. Deliberately not in the JDBC URL: an unquoted `&` breaks
  `set -a; source .env.test`.
- **Spec:** header now lists its superseded decisions; STATUS.md/AGENTS.md win where they differ.

Review findings noted but deliberately not applied yet: `SeasonResolver` throws on 0 or 2+
`is_current` season rows (needs a guard + optional short-TTL cache) and lives in `tools/` though
RAG/sync will need it; every test hits live Supabase, so CI needs Testcontainers fixtures or a
read-only secret; tool output carries no intra-day freshness (`player.updated_at` unused);
deadlines render in the JVM default timezone with no label; SPA deep links will 404 once Phase 5
adds routes (needs a forwarding controller).

## Known issues and things to watch

- **Task 6 needs a guard.** The plan's `FixtureTools` SQL bounds fixtures with
  `f.gameweek >= (SELECT id FROM gameweek WHERE is_current = true ...)`. If no gameweek is flagged
  current — true between seasons and during a changeover — the subquery yields NULL, the predicate
  is NULL, and the tool silently reports no fixtures. Fall back to the `is_next` gameweek.
- **A finished season keeps its last gameweek flagged `is_current`.** `GameweekTools` derives
  status from `finished` first for this reason; anything else reading that flag needs the same
  care.
- **`flyctl` is not installed** and will be needed for Task 14.
- **The frontend has not been started.** Tasks 9–13 are untouched; there is no `frontend/`
  directory yet.
- **Nothing is pushed.** No remote branch, no PR.
- **Plan tests that assume live 2026/27 data** (`getPlayerStats("Salah")`, Arsenal fixtures) pass
  today only because the resolver falls back to 2025-26, where `M.Salah` exists with 123 points at
  `now_cost=140`. Assertions should stay season-agnostic so they survive the sync.

---

# Status — Phase 2 (RAG)

**Progress:** Task 1/7 done.

Plan: `docs/superpowers/plans/2026-08-14-phase2-rag.md`.
Spec: `docs/superpowers/specs/2026-08-14-phase2-rag-design.md`.

## Done

| Task | Commit | What exists now |
|---|---|---|
| 1 — Prerequisites gate | (phase2-tasks1-4-rag-core branch) | `spring-ai-starter-vector-store-pgvector` added. DB prerequisites resolved with evidence (below) — no fallback needed. `OPENAI_API_KEY`-mandatory documented in AGENTS.md and DEVELOPMENT.md |

### Prerequisites gate — findings

Probed 2026-08-14 in a rolled-back transaction (no persisted change) against the live Supabase
instance:

- `CREATE EXTENSION IF NOT EXISTS vector` — **succeeds**
- `CREATE EXTENSION IF NOT EXISTS hstore` — **succeeds**
- `search_path` on the pooler connection: `"$user", public, extensions`
- `uuid-ossp` already installed in the `extensions` schema, and `uuid_generate_v4()` resolves
  without schema-qualifying because `extensions` is on the search path

The spec's Database prerequisites section flagged this as "the only step that can hard-block the
phase" and correctly required verifying before assuming. It does not block: `initialize-schema:
true` will succeed on first run with no manual dashboard step and no fallback DDL. The connecting
role is not superuser but evidently has the grants Supabase provides for trusted extensions.

The transaction was rolled back rather than committed, so the extensions are not actually
installed yet — that happens for real in Task 2 when the `VectorStore` bean initializes against
the live database, which is expected and is what "done" for Task 2 requires.

**Side-effect check.** Adding the starter dependency alone (before any `application.yml`
configuration) does autoconfigure a `PgVectorStore` bean, and every existing `@SpringBootTest`
now constructs one. Its log prints `Initializing PGVectorStore schema for table: vector_store in
schema: public` unconditionally — this looked alarming, so it was verified directly: querying
Supabase afterward shows neither `vector_store` nor `fpl_embeddings` exists, and neither
extension is installed. `initialize-schema` defaults to `false` (confirming the design review's
claim), so the log line prints before the flag is checked and no DDL actually runs. No effect on
the shared database from this task. Explicit `initialize-schema: true` in Task 2 is what will
make the real schema creation happen, once, deliberately.
