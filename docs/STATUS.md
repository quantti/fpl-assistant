# Status — Phase 1

**Progress:** Task 14/14 done — Phase 1 code complete, reviewed at two checkpoints (after
Task 10 and after Task 14), fixes applied both times. **Deployed 2026-08-15** — see Deployment
below.

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

## Deployment — done (2026-08-15)

Live at **https://fpl-assistant.fly.dev** (org `kari-vanttinen`, region `cdg`). `flyctl` lives
at `~/.fly/bin/flyctl` (not on PATH — `export PATH="$HOME/.fly/bin:$PATH"`), logged in as
`kvanttinen@gmail.com`.

```
fly secrets set DATABASE_URL=... DB_USER=... DB_PASSWORD=... OPENAI_API_KEY=... \
    FOOTBALL_DATA_API_KEY=... SPRING_AI_PROVIDER=openai CORS_ORIGINS=https://fpl-assistant.fly.dev
fly deploy --ha=false --remote-only
```

Verification after deploy: `/actuator/health` UP, Vite frontend served at `/`, and a live
chat smoke (`POST /api/chat`) answered using `getGameweekInfo` + `getPlayerStats` with
provenance intact (2025/26 completed season labelled as such).

Two deploy-time corrections to the Phase 1 plan, both now reflected in `fly.toml`:

- **`mad` (Madrid) is deprecated and can no longer provision new machines.** The first
  `fly deploy` built the image fine and failed at machine creation with "Region mad is
deprecated… consider cdg". `primary_region` was set to `cdg` — which is where `flyctl
launch` had originally registered the app anyway (the Phase 1 "gotcha" note below).
- **`SPRING_AI_PROVIDER=anthropic` from the original plan command was wrong for this
  project.** The project uses OpenAI for chat (`SPRING_AI_PROVIDER=openai` in `.env.test`;
  no Anthropic key exists). The first chat smoke failed with Anthropic `401: x-api-key
  header is required`. With `openai` set, the Anthropic autoconfiguration is gated off by
  `spring.ai.model.chat` and no Anthropic key is needed at all — the empty
  `${ANTHROPIC_API_KEY:}` slot in `application.yml` is inert under the `openai` gate.

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
- **Deployed 2026-08-15 to `fpl-assistant.fly.dev` (cdg); pushed to private repo
  `github.com/quantti/fpl-assistant`** (all 9 branches; master is the default). Both supersede
the older notes below.
- **`flyctl` is not installed** and will be needed for Task 14. — *Resolved: installed and
  deploy completed.*
- **The frontend has not been started.** Tasks 9–13 are untouched; there is no `frontend/`
  directory yet. — *Resolved: Tasks 9–13 shipped and the built frontend is served in
  production.*
- **Nothing is pushed.** No remote branch, no PR. — *Resolved: see above.*
- **Plan tests that assume live 2026/27 data** (`getPlayerStats("Salah")`, Arsenal fixtures) pass
  today only because the resolver falls back to 2025-26, where `M.Salah` exists with 123 points at
  `now_cost=140`. Assertions should stay season-agnostic so they survive the sync.

---

# Status — Phase 2 (RAG)

**Progress:** Task 7/7 done — Phase 2 complete, merged to `master`. Two full-phase reviews
applied (2026-08-14 and an independent second pass 2026-08-15).

Plan: `docs/superpowers/plans/2026-08-14-phase2-rag.md`.
Spec: `docs/superpowers/specs/2026-08-14-phase2-rag-design.md`.

## Done

| Task | Commit | What exists now |
|---|---|---|
| 1 — Prerequisites gate | `815923b` | `spring-ai-starter-vector-store-pgvector` added. DB prerequisites resolved with evidence (below) — no fallback needed. `OPENAI_API_KEY`-mandatory documented in AGENTS.md and DEVELOPMENT.md |
| 2 — Vector store wiring | `ab98e78` | `fpl_embeddings` live in Supabase (`id uuid`, `content text`, `metadata json`, `embedding vector`); `vector`/`hstore`/`uuid-ossp` now actually installed. App starts cleanly under both `SPRING_AI_PROVIDER=openai` and `=anthropic` with a populated OpenAI key. Full Phase 1 suite green with the starter on the classpath |
| 3 — Ingestion | `b3eb452` | Seven docs moved to `backend/src/main/resources/docs/` with front matter (`kind: reference`, `valid_from: 2026-08-21`); `IngestionRunner` (`--ingest` flag, exits the JVM when done) + `IngestionService` (front matter validation fails the run loudly before anything is deleted, section chunking via `MarkdownDocumentReader`, `<doc title> > <section>` prefixed into every chunk body, metadata carries title/section/kind/valid_from/valid_until, truncate-then-reload). Real run: 7 docs → 61 chunks into `fpl_embeddings`, verified row-by-row. Tests: pure unit (front matter + chunking) + integration against `fpl_embeddings_test` proving the production table is never touched, re-runs don't duplicate, a bad document aborts before truncation |
| 4 — searchStrategyDocs | `b355d8a` | `StrategyTools` `@Tool` registered via `FplTool`: per-chunk attribution (title, section, kind, validity window), distinguishable empty-store and all-expired messages, error strings for both database and embedding failures, top-k 5 explicit. Expiry filtered in the database via `SearchRequest.filterExpression` (`kind == "reference" || valid_until >= today`), with the Java check kept as a second pass — see the 2026-08-15 review section for why the naive expression fails and this one does not. `AGENTS.md`'s provenance convention amended to exempt the RAG tool. Tests: expiry logic unit-tested with fixed dates, integration retrieval/expiry/empty-store against `fpl_embeddings_test` under a pinned Clock, error path via a stubbed store |
| 5 — System prompt | `1b155d0` | "Strategy advice" section added to `system-prompt.st`: advice questions consult BOTH stats tools and `searchStrategyDocs`; phrasing carries the Phase 5 squad conditional ("if the user's squad is known, factor in what they already own") so a future `getUserSquad()` needs no prompt restructuring; companion instruction on retrieved-content age mirrors the seasonal-provenance discipline (weigh `timely` by age, say so when citing weeks-old content) |
| 6 — Retrieval baseline | `93e3e38` | 8 questions run against the live 61-chunk corpus; all 8 retrieve from the expected document (table in STATUS.md above). No chunking/top-k change warranted |
| 7 — End-to-end | `3b98b3c` | Real chat flow verified: advice question fires stats tools + `searchStrategyDocs` and answers from retrieved content; pure stat question does NOT call `searchStrategyDocs` (negative case proven) |

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

## Task 2 findings — `schema-validation` cannot combine with `initialize-schema` from empty

The spec's Configuration table originally said to enable both `initialize-schema: true` and
`schema-validation: true`. Enabling both together made every `@SpringBootTest` fail with `Table
fpl_embeddings does not exist in schema public` — against a table that `initialize-schema` should
have just created.

Decompiled `PgVectorStore.afterPropertiesSet()` (Spring AI 2.0.0) to find out why:

```
if (schemaValidation) schemaValidator.validateTableSchema(...);   // runs unconditionally, first
if (!initializeSchema) return;
jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");
...
```

Validation runs *before* schema creation, always, with no ordering flag to change it. The two
settings can never succeed together starting from an empty table — not a race condition, not
environment-specific, a fixed ordering in the bytecode. Spec and plan corrected to say
`schema-validation` must not be enabled; `initialize-schema`'s own `CREATE TABLE IF NOT EXISTS` is
sufficient and idempotent on every subsequent startup.

With that removed: `fpl_embeddings` now exists in Supabase (`id uuid`, `content text`, `metadata
json`, `embedding vector`), and `vector`/`hstore`/`uuid-ossp` are installed for real (Task 1's
rolled-back transaction only proved they *could* be). Full suite green under both
`SPRING_AI_PROVIDER=openai` and `=anthropic`, confirming the embedding autoconfiguration activates
independently of the chat provider switch as the spec's Configuration table requires.

## Task 3 findings — the markdown reader's real API, and two config traps

Checked against the actual `spring-ai-markdown-document-reader` 2.0.0 sources before
implementing (the caution the plan's TokenTextSplitter note established):

- **The reader splits by heading, one `Document` per section** — the spec's "split on markdown
  sections" is exactly what the library does. `visit(Heading)` flushes the previous section.
- **Heading text goes into `metadata.title`, NOT into the chunk body.** The spec's "prefix each
  chunk's body with `<doc title> > <section header>`" therefore had to be implemented by
  `IngestionService` itself; the reader cannot be configured to do it. The service reads the
  reader's `title`, moves it to a `section` metadata key, and rewrites the body with the prefix.
- **A heading with no body below it produces no chunk** (the visitor only flushes non-empty
  paragraph buffers). In the real docs this drops `## The archetypes` and `## Wildcard strategy`
  (pure heading parents) and keeps the leaf sections — the leaf headings are self-describing and
  the `<doc title> > <section>` prefix disambiguates the three near-identical chip-mistake
  sections, which was the whole point.
- **The reader builds a plain commonmark parser (no GFM extensions)** — tables stay inside their
  section as flattened text (pipe characters intact). That is acceptable and actually the
  requirement: a retrieved chunk keeps its table header row, which token-window chunking severed.
- **Front matter must be stripped before the reader sees it.** commonmark parses the `---`
  delimiters as thematic breaks and the YAML lines as a paragraph, so the front matter would leak
  into the first chunk's body. `IngestionService` parses and removes it first.

Two config traps hit during Task 3, both now fixed and covered by tests:

- **SnakeYAML resolves bare `YYYY-MM-DD` scalars into `java.util.Date` objects.** A front matter
  date `2026-09-15` arrived at validation as `"Tue Sep 15 02:00:00 CEST 2026"` and failed the ISO
  regex. Fixed with a custom `Resolver` that downgrades `Tag.TIMESTAMP` to `Tag.STR` — dates stay
  the literal strings the spec writes. (Note: this is why `valid_from: 2026-08-21` — the season
  start, which is in the future relative to today — must not be rejected; nothing checks
  past/future, only format.)
- **Spring Boot 4.1 treats a same-named `application.yml` on the test classpath as REPLACING the
  main one.** The spec's suggested `src/test/resources/application.yml` (table-name override)
  silently dropped `spring.datasource.url` and every other main-config key, so every
  `@SpringBootTest` failed with "Failed to determine a suitable driver class". The override now
  lives in `src/test/resources/application-test.yml`, activated with `@ActiveProfiles("test")` on
  the ingestion integration test — profile-specific config merges with the base file instead of
  shadowing it. Isolation is unchanged: ingestion tests can only ever touch `fpl_embeddings_test`.

Other Task 3 decisions: the repo-root `strategy_docs/` copy was deleted rather than kept as an
editing source — one canonical location (`backend/src/main/resources/docs/`), no drift-prone sync
step. Real ingestion run: 7 docs → 61 chunks into `fpl_embeddings`, row-by-row verified (content
prefixes, `title`/`section`/`kind`/`valid_from` metadata, no `valid_until` on reference docs).

## Task 4 findings — why the obvious expiry filter fails, and what works instead

> **Superseded in part on 2026-08-15.** The analysis below of *why* the naive expression fails is
> correct and worth keeping. Its conclusion was not: the filter is expressible after all, and the
> code now does it in the database. See the resolution at the end of this section.

The spec requires expiry filtering at query time via `SearchRequest.filterExpression`
(``FilterExpressionBuilder``), so expired `timely` documents never reach the model. Verified
against the real 2.0.0 converter and the live database that the *obvious* form of this cannot
express "no `valid_until`, or `valid_until` >= today":

- The pgvector filter converter emits a jsonpath predicate (`metadata::jsonb @@ '...'::jsonpath`)
  and supports only the comparison operators (and/or/eq/ne/gt/gte/lt/lte/in/nin). The
  `ExpressionType.ISNULL/ISNOTNULL/NOT` values are not handled — they throw "Not supported
  expression type".
- **A jsonpath comparison against a missing key evaluates to false**: `$.valid_until >= "2026-08-14"`
  matches 0 of the 61 reference rows (which have no `valid_until`), verified with a direct SQL
  query. So the simple filter would make every season-long document invisible, and there is no
  supported operator to OR the missing-key case in.
- `exists($.valid_until)` is valid jsonpath but the converter cannot emit it.

**Resolution (corrected 2026-08-15):** the missing-key case does not need an existence operator —
it can be reached through `kind`, because ingestion enforces that every `timely` document carries
a `valid_until` and every document is one of `reference`/`timely`. So
`kind == "reference" || valid_until >= today` is equivalent to "not expired", uses only supported
operators, and matches 61 of 61 production chunks where the naive form matched 0. That is what
`StrategyTools` sends to the database.

The Java check runs afterwards as a second pass rather than as the mechanism: a `reference`
document is *permitted* to carry a `valid_until`, and such a row would pass the SQL filter on its
`kind` alone. A small candidate widening (10) absorbs whatever the second pass removes.

The original Java-only resolution was not merely inelegant — it had a failure mode. Filtering a
fixed candidate pool means that once several `timely` documents are added weekly, a query
semantically close to old previews could have most of its pool expired, returning fewer than five
chunks or none at all while the entire reference corpus went unretrieved. Filtering in the
database removes that at the source. The expiry logic itself is a pure static
method (`StrategyTools.isCurrentlyValid`), unit-tested with fixed dates so it cannot rot as real
time passes; the integration test pins a fixed `Clock` (2026-08-14) so the fixture corpus's
expiry assertions are deterministic forever, and the `docs-fixtures` corpus gained a third
fixture, `fixture-expired.md` (valid until 2026-08-10, permanently in the past).

Also this task: `AGENTS.md`'s "every tool prefixes its reply with `SeasonContext.provenance()`"
convention now names `StrategyTools` as the deliberate exception — the corpus carries its own
per-chunk attribution and a banner line cannot describe a mix of reference and timely content.

## Task 6 — retrieval quality baseline (real corpus)

Eight questions run against the live 61-chunk corpus (2026-08-14) with the default top-k 5.
All eight retrieved from the expected document; no chunking fix needed.

| # | Question | Expected | Top-5 result |
|---|---|---|---|
| 1 | when should I play my wildcard | strategy-wildcard-and-free-hit | ✓ all 5 from the expected doc (WC1/WC2/"When to play" windows) |
| 2 | should I buy a premium forward or a mid-priced one | player-archetypes / team-building | ✓ mixed: Premium allocation, Mid-priced, Price-point targeting, Premium/talisman, Squad structure implications |
| 3 | is taking a -4 hit worth it to bring someone in | strategy-transfers | ✓ top-2 (Taking hits: when justified, Decision summary); tail is wildcard/FH decision-summary tables — semantically adjacent, acceptable noise |
| 4 | who should I captain and should I use my TC chip | captain-selection-and-triple-captain | ✓ top-4 from the expected doc + FPL Chip Strategy TC section |
| 5 | how should I time my bench boost | strategy-chips | ✓ Bench Boost strategy + Bench Boost interplay + Team Building Bench strategy; 5th is "Common chip mistakes" — minor tail noise |
| 6 | what ownership percentage counts as a differential | strategy-differentials | ✓ all 5 from the expected doc |
| 7 | how should I structure my squad formation and budget | strategy-team-building | ✓ all 5 from the expected doc |
| 8 | free hit in a blank gameweek or wildcard for DGWs | strategy-wildcard-and-free-hit | ✓ all 5 from the expected doc |

Observations: section chunking is doing its job — every retrieved chunk arrives with its own
section header and doc title (the three near-identical "mistakes" sections never appear
misattributed), and decision-summary tables stay intact inside their chunks. The only noise is
semantic (decision-summary table chunks ranking 3–5 on adjacent questions), which top-k 5
absorbs without harm; no chunking or top-k change warranted.

## Task 7 — end-to-end verification (real chat flow)

Verified against the running application via `POST /api/chat` (SSE), real Supabase, real
OpenAI embeddings, claude-sonnet-5:

- **Advice question** ("Who should I captain in Gameweek 1 and should I save my triple captain
  chip for later?"): the model reported using `getGameweekInfo`, `getPlayerStats` **and**
  `searchStrategyDocs`, and the answer visibly drew on retrieved content — the DGW guidance
  ("premium attacker with two favourable fixtures—ideally both at home") and the pre-activation
  checks (fixture confirmation, team news, minutes/rotation risk, penalty duty) match the
  captaincy document's "When to play" and "Pre-activation checklist" sections, which the model
  cannot otherwise see. Provenance discipline held: the 2025/26 stats were explicitly labelled
  completed-season evidence, not current form.
- **Pure stat question** ("How many goals and assists did Mohamed Salah get last season, and
  what is his current price?"): answered purely from `getPlayerStats` (7 goals, 7 assists,
  £14.0m in 2025/26, flagged historical). The answer mentions no strategy document and no
  `searchStrategyDocs` — the negative case proves the tool-based design: stat questions never
  spend a retrieval.

Both question types behave as the spec describes.

## Full-phase review (2026-08-14) — applied

Second review pass over the whole phase found no critical issues. Applied: `formatChunk`
renders missing metadata as `<unknown>` instead of literal `"null"` strings (guards hand-edited
rows); the system-prompt squad conditional now says "if you can see the user's squad" — the
model can only factor in a squad it can observe, which prevents hallucinated squad knowledge
and over-asking while preserving the Phase 5 extension shape; the tool description is marked as
synced with the prompt's "Strategy advice" section. Deferred as accepted: `--ingest`'s
DELETE-then-ADD window (the spec's re-ingestion section explicitly accepts an empty-corpus
window, made visible by the distinguishable empty-store message).

## Independent second review (2026-08-15) — applied

A second, independent full-phase review ran against the branch before merge. It confirmed test
isolation was correct as built and found no critical defects. Applied:

- **Expiry filtering moved into the database, as the spec always asked.** The previous code
  filtered expired chunks in Java over a widened candidate pool, with a javadoc claiming the
  spec's `SearchRequest.filterExpression` route was "not expressible". That claim was wrong in a
  way that mattered: the naive `valid_until >= today` really is unusable (a jsonpath comparison
  against a missing key is false, so it drops every reference document — verified live: **0 of 61
  chunks**), but `kind == "reference" || valid_until >= today` is equivalent, uses only operators
  the pgvector converter implements, and returns **61 of 61**. The Java pass is kept as a second
  filter, because a `reference` document is permitted to carry a `valid_until` and would pass the
  SQL filter on its `kind` alone. This closes a real quality cliff: with several `timely`
  documents added weekly, a query near old previews could have had most of its candidate pool
  expired, returning fewer than five chunks — or none — while the whole reference corpus sat
  unretrieved. The filter expression is now pinned by a unit test, since its failure mode is a
  silently empty corpus rather than an error.
- **Isolation gained defence in depth.** `src/test/resources/application.properties` activates
  the `test` profile for the entire test source set, so every `@SpringBootTest` — including the
  Phase 1 ones that have nothing to do with RAG — points at `fpl_embeddings_test`. Those tests
  were previously re-issuing `CREATE EXTENSION`/`CREATE TABLE`/`CREATE INDEX` against the
  production table on the database shared with `tapas-fpl-app`; harmless (`IF NOT EXISTS`) but
  unasked-for DDL. Both integration tests also gained a `@BeforeEach` asserting the configured
  table ends in `_test`, which fails *before* any `DELETE` — the existing production-row-count
  check runs after the ingest and so detects damage rather than preventing it. (A `.properties`
  file, not `application.yml`: a same-named YAML on the test classpath replaces the main config
  entirely. Verified by running the suite.)
- **`searchStrategyDocs` now catches more than `DataAccessException`.** It is the first tool that
  makes an outbound call — the query is embedded by OpenAI before the search — so a 429/5xx
  surfaces as a REST/AI exception the DB-only catch missed, leaving the model to relay a raw
  exception message. Second catch added with its own message.
- **Expiry rolls over at UK midnight**, not the container's zone (`Europe/London` via
  `ObjectProvider<Clock>`, which also replaces the deprecated `org.springframework.lang.Nullable`).
  On Fly the container runs UTC, which during British Summer Time would end a `valid_until` day an
  hour early.
- **System prompt covers future-dated content.** It instructed the model about *old* timely
  content only; a preview ingested ahead of its gameweek would have been presented as something
  that already happened.
- **Corpus reload documented in `docs/DEVELOPMENT.md`.** `AGENTS.md` points there for commands and
  `--ingest` appeared only in a javadoc; the plan requires ingestion be runnable by whoever adds
  documents.
- **`.dockerignore` comment** explaining that `docs`/`*.md` are root-anchored and must not become
  `**/docs`/`**/*.md`, which would silently ship an image with an empty strategy library.

Reviewed and dismissed: the review flagged that `## Historical TC outcomes` was removed from
`captain-selection-and-triple-captain.md` during the Task 3 move without documentation. The
removal was an intentional editorial decision by the corpus owner — the section carried no
practical value. Recorded here so it is not re-flagged as content loss.

---

# Status — Phase 3 (Historical Data)

**Progress:** Task 7/7 done — Phase 3 complete. Full-phase review applied (see below).

Plan: `docs/superpowers/plans/2026-08-15-phase3-historical-data.md`.
Spec: `docs/superpowers/specs/2026-08-15-phase3-historical-data-design.md`.
Both owner decisions are settled before implementation started: backfill 2019-20 onward,
sync on startup behind a persisted staleness marker.

## Done

| Task | Commit | What exists now |
|---|---|---|
| 1 — Schema | `8ff25ba` | New table `pl_match_result` in the shared Supabase project, created idempotently at startup (`HistoricalSchema`, `@PostConstruct`, same pattern as `PgVectorStore`'s schema init) — no migration tool in this project. Keyed on team `code` (not `team.id`, which is season-scoped and reassigned on promotion/relegation), club names denormalised onto the row (no `team` row exists for historical seasons to join against), `season` as `VARCHAR(10)` and explicitly not a foreign key. `UNIQUE (season, home_team_code, away_team_code)` is the natural key both importers will upsert on. Table name parameterised (`fpl.historical.table-name`) and pinned to `pl_match_result_test` under the `test` profile — this table is never truncated, so an unpinned test would permanently pollute production with fictitious matches, the same reasoning Phase 2 applied to `fpl_embeddings`. Verified both the test table (via the test suite) and the production table (a real `bootRun`, then queried directly) get created |
| 2 — vaastav team mapping | `0cab241` | `VaastavTeamMapper.resolve(season, vaastavId)` → `TeamIdentity(code, name)`, parsed from each season's `teams.csv` via Apache Commons CSV (RFC 4180 — `fixtures.csv`'s upcoming `stats` column defeats a hand-rolled split). Pure logic, no database. Fails loudly naming the season and id on an unresolved club, or the season on one never loaded. Tested against the real `teams.csv` for 2019-20 and 2025-26 (checked into `src/test/resources/vaastav-fixtures/`): resolves clubs present in both seasons, resolves Watford/Norwich (relegated after 2019-20, absent from 2025-26 — exactly what `FixtureTools`' resolver cannot do), and proves id-reassignment doesn't cross-talk (vaastav id 3 is Bournemouth in 2019-20, Burnley in 2025-26 — kept in separate per-season maps, not merged) |
| 3 — vaastav backfill | `513a6c8` | `VaastavBackfillService` upserts on `(season, home_team_code, away_team_code)` so re-runs don't duplicate; only finished matches with both scores are written. `VaastavSource` abstracts the fetch so tests read checked-in CSVs, not the network; `HttpVaastavSource` is the only thing that ever hits GitHub, and only at sync time. **Ran for real against production Supabase: 2660 matches across all 7 seasons (380 each).** The spec's oracle passes — every 2025-26 row matches the pre-existing `fixture` table exactly on both scores, 380/380, joined through `team.code`. Idempotency proven by running it twice in a test, not assumed |
| 4 — getTeamForm / getHeadToHead | `e190b37` | `HistoricalTeamResolver` resolves names to codes against clubs actually present in `pl_match_result` (not `FixtureTools`' resolver, which would miss relegated clubs). `HistoricalTools` implements `FplTool`; neither method filters through `SeasonResolver` or prefixes with `SeasonContext.provenance()` — every match names its own season instead, and `getTeamForm` reports the date of its most recent known match so a stalled Task 5 sync becomes visible in an answer rather than silent |
| 5 — football-data.org sync | `36173c3` | `StartupSyncRunner` (ApplicationReadyEvent behind a **persisted** staleness marker — `MAX(ingested_at) WHERE source = 'football-data'` in `pl_match_result` itself, no separate table) + `AsyncConfig` (`@EnableAsync`) so the sync runs off the request path; `FootballDataSyncService` upserts on the natural key with the spec's precedence guard (writes only when `SeasonResolver`'s resolved season is the one in progress — vaastav stays authoritative for completed seasons); `FootballDataSource`/`HttpFootballDataSource` (explicit 10s connect/read timeouts, per the Task 4 review note about not inheriting `HttpVaastavSource`'s unbounded client; 429 gets its own "rate limit exceeded" message per the plan); `FootballDataResponseParser` + `FootballDataTeamAliases` (own alias set — football-data's "Wolverhampton Wanderers FC" vs vaastav's "Wolves"); `FOOTBALL_DATA_API_KEY` in `.env.test.example` + `DEVELOPMENT.md`; `fpl.historical.sync-stale-after-hours: 20`. Parser + aliases **verified against a real captured response 2026-08-15** (see Task 5 findings below — the check found and fixed the two promoted clubs missing from the alias table) |
| 6 — Prompt and conventions | `7721118` | `system-prompt.st` "Historical results" section; both `AGENTS.md` season conventions amended, RAG exception + provenance bullet consolidated into one three-scheme attribution rule |
| 7 — End-to-end verification | `6ad7891` | Verified through the real chat flow against the running app (below); STATUS.md reflects what exists |

## Shared-database change

`pl_match_result` was added to the Supabase project shared with `tapas-fpl-app`. Additive only —
no existing tapas table touched, per convention.

## Tasks 1–4 review checkpoint (2026-08-15) — applied

Independent review verified the 380/380 oracle and the 2660-row production backfill directly
against the live database (not just trusting this file), confirmed test isolation is genuinely
solid, and found three Important issues, all fixed without touching the schema, upsert shape,
or re-running the production backfill:

- **`VaastavFixtureParser`'s number parsing contradicted the phase's own fail-loud design.**
  `team_h`/`team_a` were parsed via `Double.parseDouble` cast to `int` to tolerate vaastav's
  occasional whole-number floats ("1.0") — but that silently truncated a genuine fractional
  value ("3.5" → 3) instead of failing, which would resolve to the *wrong team* with no error.
  Neither real fixture file actually contains a fractional value in these columns (checked
  both programmatically), so the tolerance was solving a problem that doesn't occur in the
  data this phase imports. Now rejects any value with a real fractional part.
- **Task 4's own acceptance criterion — "verified against the real table" for a relegated
  club — was not actually tested.** `HistoricalToolsIntegrationTest` used only fictitious
  clubs; Watford/Norwich were verified at the mapper and importer layers but never through
  `HistoricalTeamResolver`/`HistoricalTools` themselves. Added a test that runs its own small
  real backfill (2019-20, into the isolated table, cleaned up after) and confirms `getTeamForm`
  and the resolver both handle Watford correctly — independent of whatever other test classes
  have or haven't populated the shared test table, so it can't pass or fail by accident of
  execution order.
- **The natural-key collision class (real codes + real season colliding with backfilled data
  on a key that doesn't distinguish by `source`) had already recurred once and was guarded
  only by a comment.** Added `FictitiousTestData`, asserted at class-load time in every test
  using fictitious data: codes must be ≥9000, and season labels must not match the shape of a
  real FPL season (`\d{4}-\d{2}`) at all — not just avoid the *currently* backfilled range,
  since a future range widening should never retroactively collide with a season a test picked
  because it looked safely far away.

Also fixed while applying the above: `season VARCHAR(10)` rejected the first round of
fictitious season labels (`"test-schema-a"` etc. — 13 chars); shortened to fit. `kickoff_time`
now prints "kickoff unknown" instead of a literal `null` string when absent (this exact bug
class already hit once in Phase 1). `parseKickoff`'s swallowed `DateTimeParseException` now
logs a warning naming the match, instead of silently dropping the timestamp.

Noted but deliberately not fixed here: `HttpVaastavSource` has no connect/read timeout — a
non-issue for a manually-invoked 14-request backfill, but Task 5's football-data client will
likely copy this shape, and an unbounded client there would tie up a background thread
indefinitely on a hang. Task 5 should not inherit it without a timeout. Ambiguous substring
name resolution (e.g. "man" matching both Manchester clubs) is inherited from `FixtureTools`'
identical pattern, not a regression.

## Task 5 findings — the async dispatch, the precedence guard, and what stays unverified

Task 5 is the first place this codebase runs work off the request path, so the two load-bearing
claims of the design were verified rather than assumed:

- **`@Async` on an `ApplicationListener` genuinely works, verified two ways.** Spring Boot
  4.1's `EventPublishingRunListener.ready()` publishes `ApplicationReadyEvent` via
  `context.publishEvent(...)` (decompiled bytecode), so a `@Component` listener is reached
  through the context's event multicaster as its AOP proxy — the `@Async` interceptor
  dispatches to the executor and the multicaster returns immediately. Confirmed live with a
  real `bootRun`: `Started FplAssistantApplication in 4.273 seconds` on `main`, then
  `StartupSyncRunner` logging on `task-1`. The request that wakes a stopped Fly machine is
  therefore answered from stored data, never held up by the sync.
- **The precedence guard is what actually runs today, and it is visible in the boot log.**
  With 2026-27 empty, `SeasonResolver` resolves to the completed 2025-26 fallback, so
  `FootballDataSyncService.sync()` logs "Skipping … a completed fallback" and writes nothing —
  vaastav's authoritative backfill is untouchable even though the sync would otherwise fire.
  The write path itself is covered by the integration test with a mocked resolver (both
  branches, since the calendar cannot be relied on to exercise them on demand).
- **The 429 case got its own message.** The plan requires a rate-limit failure to say so rather
  than read like a network error; the first cut wrapped everything in "failed to fetch".
  `HttpClientErrorException.TooManyRequests` is now caught separately with an explicit
  "football-data.org rate limit exceeded (429)" message.

**Open item resolved 2026-08-15:** the plan required the parser be proven against a captured
real response body; this project had no football-data.org key when Task 5 landed, so the first
cut used a hand-built body and recorded the gap as an open item. The account owner registered
a free key, and the live check ran: a real `GET /v4/competitions/PL/matches` response (380
matches, season 2026, all SCHEDULED/TIMED — GW1 unplayed, as expected) was captured into
`src/test/resources/football-data-fixtures/pl-matches-2026-27.json` and a new
`FootballDataCapturedResponseTest` proves the parser parses all 380 and every distinct team
name resolves through the alias table. <b>The check found a real gap</b>: Coventry City and
Hull City — promoted for 2026-27, absent from the 2019-20..2025-26 backfill the table was
built from — did not resolve. Their codes (9 and 88, from vaastav's 2026-27 `teams.csv`, the
same code source the 380/380 oracle validated) were added; all 20 of the 2026-27 clubs now
resolve. What remains genuinely open until GW1 is only the <i>write path</i>: the sync
upserts only finished matches, and there are none before 2026-08-21. The parser, the alias
table and the precedence guard are all now verified against the real API or real data.

## Task 7 — end-to-end verification (real chat flow)

Verified against the running application via `POST /api/chat` (SSE), real Supabase,
claude-sonnet-5:

- **Form question** ("How have Liverpool been playing recently?"): the model used
  `getTeamForm`, presented the last 8 league matches with scores/outcomes, attributed them
  to the completed **2025/26 season** and explicitly flagged them as historical form, not a
  measure of 2026/27 performance — and surfaced the freshness line (most recent known
  result: Brentford 1–1 Liverpool, 24 May 2026), which is exactly the stalled-sync
  visibility the spec asked for.
- **Head-to-head question** ("What is the head-to-head record between Manchester City and
  Arsenal?"): used `getHeadToHead`, returned the last 10 meetings as a season-by-season
  table (2021/22–2025/26), every row attributed to its season, order-insensitive resolution
  confirmed. The answer's own closing line cites the tool and the range covered.
- **Negative case** ("What are Arsenal's next 3 fixtures?"): went to `getUpcomingFixtures`
  only — no historical tool was touched. The answer correctly explains 2026/27 fixtures are
  unpublished yet and surfaces the fallback 2025/26 data labelled as such (GW38 Crystal
  Palace (A), FDR 3). Upcoming-fixture questions do not drag in history.

## Full-phase review (2026-08-15) — applied

One finding, same bug class as the Tasks 1–4 review's vaastav fix, in the one piece of
code that review could not reach (it did not exist yet):

- **`FootballDataResponseParser.intOrNull` silently truncated non-integral scores.**
  `JsonNode.asInt()` returns 3 for `3.5` and 0 for `"abc"` — the exact silent-corruption
  class the Tasks 1–4 review fixed in `VaastavFixtureParser`, and this parser is the
  unverified-against-live-API one. Now strict: integral numbers pass, textual values are
  `Integer.parseInt`-ed, anything else fails loudly naming the offending value. Covered by
  `fractionalOrNonNumericScoreFailsLoudlyInsteadOfTruncating`.

Reviewed and dismissed: the `sync()` write path remains unexercised in production until GW1
(the resolved season is a completed fallback, so the precedence guard no-ops — verified in
real boot logs twice), which is why both branches of that guard are pinned by the mocked-
resolver integration test instead; the free tier's 10 req/min limit is structurally a
non-issue (one sync = one request), and the 429 message is a belt-and-braces measure.

## 2026-08-15 live-API verification (post-review, key registered)

The account owner registered a free football-data.org key and set it in `.env.test`; the
plan's "captured real response body" criterion is now met:

- A live `GET /v4/competitions/PL/matches` returned 200 with all 380 matches of season 2026
  (all SCHEDULED/TIMED — played: 0, GW1 is 2026-08-21, exactly as the plan anticipated).
  Response saved unmodified to `backend/src/test/resources/football-data-fixtures/pl-matches-2026-27.json`.
- `FootballDataCapturedResponseTest` (3 tests) parses all 380, asserts no finished match
  carries a score, all 38 matchdays present, and — the point of the exercise — that all 20
  distinct team names resolve through `FootballDataTeamAliases`.
- **The check found a real bug that only a real response could surface:** the two clubs
  promoted for 2026-27 — Coventry City (code 9) and Hull City (code 88) — were missing from
  the alias table, which had been built from the 2019-20..2025-26 backfill only. The sync
  would have failed loudly with "Unknown football-data team name" on its first real run.
  Both added; the fail-loud design surfaced the gap exactly as intended.
- Docs updated: STATUS.md, DEVELOPMENT.md (key now exists; captured-response test replaces
  the "never registered one" caveat), `.env.test.example` comment, and the two class
  javadocs that still said "unverified".

## Task 6 — prompt and conventions

`system-prompt.st` gained a "Historical results" section (when to consult `getTeamForm`/
`getHeadToHead` — actual-record questions, never the upcoming schedule; old results are
evidence about tendencies, not current form; surface a visibly stale most-recent-match date).
Both affected `AGENTS.md` conventions were amended: the "all DB queries filter through
`SeasonResolver`" bullet now names `HistoricalTools` as the one deliberate exception (bolting
season filtering onto cross-season tools would destroy the behaviour), and the separate RAG
"Exception" bullet plus the provenance bullet were consolidated into one attribution rule
listing the three schemes — stats-tool banner, per-chunk RAG attribution, per-match
historical attribution — instead of accumulating a third per-tool exception.
---

# Status — Phase 4 (Enrichment)

**Progress:** Task 7/7 done — Phase 4 complete, merged to `master`. Two full-phase reviews applied (see below).

Plan: `docs/superpowers/plans/2026-08-15-phase4-enrichment.md`.
Spec: `docs/superpowers/specs/2026-08-15-phase4-enrichment-design.md`.

## Done

| Task | Commit | What exists now |
|---|---|---|
| 1 — Schema + price sync | `9f729fe` | `fpl_player_price` table (current season only, keyed on season-scoped `element_id` — the tapas `player` table has no `code` column, and single-season storage needs no cross-season identity). `PriceSyncService` writes two sources gated by `SeasonContext.currentSeason()`: a startup snapshot of `player.now_cost` (`gameweek=0`, `source=snapshot`) and a vaastav `gws/merged_gw.csv` pull for played GWs (`source=vaastav`). `StartupPriceSyncRunner` (`ApplicationReadyEvent`, `@Async`, persisted staleness marker `MAX(ingested_at) WHERE source='snapshot'`). `VaastavSource.fetchIfPresent` (404 → null) so the missing pre-season `merged_gw.csv` is a no-op, not a boot failure. Table pinned to `fpl_player_price_test` under the test profile |
| 2 — getPriceHistory | `0cf3a0f` | `PriceTools.getPriceHistory` — current-season per-GW prices (vaastav) + latest live snapshot, descriptive trend (risen/fallen by X, never a prediction). Live snapshot sorts as the most recent row |
| 3 — xG tools | `55f94d4` | `ExpectedStatsTools`: `getPlayerExpectedStats` and `getTeamExpectedGoals` aggregate FPL's own `expected_goals`/`expected_assists`/`expected_goal_involvements` in `player_fixture_stats` over the last N fixtures (max 10), with actuals and per-fixture breakdown |
| 4 — Differential + captain | `6fc2d01` | `PickTools`: `getDifferentialPicks` (low-ownership, form + fixture-ease ordered) and `getCaptainCandidates` (high-form MID/FWD with ownership and avg FDR of next N fixtures). Position "any" uses a 0 sentinel to avoid null SQL binding |
| 5 — Fixture difficulty | `7aad173` | `FixtureDifficultyTools.getFixtureDifficulty` — team-scoped upcoming FDR outlook (next N, FDR 1-5, opponent, venue, kickoff, average FDR); does not duplicate `getUpcomingFixtures` |
| 6 — Docs + prompt + conventions | `c8d7805` | Two new reference docs ingested (9 docs → 76 chunks in `fpl_embeddings`): `price-change-mechanics-and-team-value.md`, `using-xg-xa-xgi-xga-in-fpl.md`. `system-prompt.st` "Enrichment tools" section. `AGENTS.md` stats-tools list updated to name all Phase 4 tools under the SeasonResolver/provenance convention |
| 7 — E2E + deploy + docs | `613ee62` | Verified through the real chat flow (below); STATUS.md updated |

## Task 7 — end-to-end verification (real chat flow)

Verified against the running application via `POST /api/chat` (SSE), real Supabase, OpenAI chat
provider. The real `SeasonResolver` resolves to 2025-26 (a completed fallback, since 2026-27
has no data yet), so every answer is correctly attributed as historical — which is the right
behaviour at this point in the calendar and what these tests exercise:

- **xG question** ("Is Salah overperforming/underperforming his xG?"): used
  `getPlayerExpectedStats`, reported xG vs actual goals, attributed to 2025/26 historical data.
- **Captain question**: used `getCaptainCandidates` + `getGameweekInfo` + `searchStrategyDocs`.
- **Differential question**: used `getDifferentialPicks` + `searchStrategyDocs`
  (differential-selection framework).
- **FDR question** ("Arsenal's fixture difficulty outlook"): used `getFixtureDifficulty`,
  correctly reported no 2026/27 data and only completed 2025/26.
- **Price question** ("has Salah's price risen/fallen?"): used `getPriceHistory`, correctly
  reported no 2026/27 price data yet (the sync skips until the current season holds data).
- **Negative case** ("how many goals/assists did Salah get last season?"): used `getPlayerStats`
  only — no enrichment tool was dragged in.

All six question types behave correctly.

## Deviations from the spec/plan

| Spec/plan says | What was done | Why |
|---|---|---|
| `fpl_player_price` keyed on persistent FPL `code` | Keyed on season-scoped `element_id` instead | The tapas `player` table has no `code` column (verified against the live DB). Since the table only holds the current season, cross-season identity is unnecessary; `element_id` (== vaastav `element` == tapas `player.id`) joins directly with no mapping |
| Plan Task 1 "open design question: `(season, element) → code` mapping" | Resolved by not needing `code` at all | Same reason — using `element_id` removes the mapping step entirely |
| Past-season price backfill (2019-20..2025-26) | Not done | Owner decision: price history is wanted only for the current season; old seasons are out of scope |
| `getPriceRisersFallers` tool | Removed | Owner decision: it is descriptive of past weeks and not predictive, and `getPriceHistory` already carries the trend. Competing with dedicated price-prediction tools is not the goal |
| "Understat-derived xG" | "FPL's own expected-stats columns" | The data in `player_fixture_stats` is FPL's, not Understat's |
| "Captain differentials" strategy doc | Not added | Captaincy is already covered by the existing Phase 2 `captain-selection-and-triple-captain.md` |

## Open items

- **Current-season price data is empty until 2026-27 holds data.** `SeasonResolver` falls back to
  2025-26 (completed), so `PriceSyncService.sync()` skips (the `currentSeason()` guard) and
  `getPriceHistory` returns "No price history yet". This is correct behaviour, not a bug — the
  first real price snapshots appear once tapas syncs 2026-27 and the resolver moves to it. Until
  then the xG, differential, captain and FDR tools work against real 2025-26 data; only price
  history is genuinely awaiting the new season.
- **vaastav current-season `gws/merged_gw.csv` does not exist before GW1** (verified: 404). The
  sync handles this as a no-op via `fetchIfPresent`. Once GW1 is played, vaastav publishes the
  file and the vaastav sync populates `gameweek=1..38` rows.

## Full-phase review (2026-08-15) — applied

Review pass over the whole phase found two issues, both fixed:

- **`ExpectedStatsTools.formatTeam` had a broken `teamIdFromFixture` helper** that returned 0 and
  a confused `player_team_id` filter (it queried with the opponent id in one branch). Rewritten to
  pass the resolved `teamId` directly into the per-fixture aggregation query — the team's attack
  is the sum of its own players' xG in each fixture, filtered by `player_team_id = teamId`.
- **`PickTools` "any" position passed `null` into `(? IS NULL OR p.element_type = ?)`, which pgjdbc
  binding of null rejected as a DataAccessException.** Replaced the null sentinel with `0`: the SQL
  is now `(? = 0 OR p.element_type = ?)`, so "any" matches all rows without any null binding.

Reviewed and dismissed: the price-sync `currentSeason()` guard means no price rows exist yet in
production — both the snapshot and vaastav paths are no-ops until 2026-27 holds data, exactly as
the historical football-data sync was pre-GW1. The integration tests cover both write branches
with a mocked resolver; the live write path is an open item until the new season, recorded above.

## Second full-phase review (2026-08-15) — all findings fixed

An independent review of all seven tasks re-verified the phase against the live database rather
than against this file, and found thirteen issues. All are fixed. Suite: **171 tests, 0 failures**
(was 141).

**What the review verified first, so the findings rest on evidence:** `fpl_player_price` exists in
production with the right columns and unique constraint and is empty (correct — the
`currentSeason()` guard); `fpl_player_price_test` is clean; the corpus really is 9 docs / 76
chunks; the Fly deploy (v5, 11:33Z) postdates the Task 7 commit, so Phase 4 is live; vaastav's
2026-27 `merged_gw.csv` really does 404. Two code paths that **no test can reach** — no season in
the database has a single `finished = false` fixture — were exercised by hand against real rows
with the predicate flipped: the FDR SQL binds correctly and picks the team's own side of the
fixture, and `getTeamExpectedGoals`' aggregation matches the `fixture` scoreline 5/5.

### Correctness and safety

- **The pick tools recommended injured players.** `getDifferentialPicks`/`getCaptainCandidates`
  never read `player.status` or `news` — 19 of the 406 players with form > 0 in 2025-26 are
  injured, suspended or otherwise out, and `form` is a rolling average that stays high for weeks
  after an injury. `PlayerTools` has handled this correctly since Phase 1; the tools whose whole
  job is *recommending* did not. Both now filter to `status IN ('a','d')` and flag doubtful
  players with their news line, which the prompt tells the model to relay.
- **`getDifferentialPicks`' description contradicted its SQL.** It promised "ordered by form, then
  fixture ease, then ownership"; the SQL ordered by form, ownership and points, and the FDR was
  computed afterwards for display only — it never affected the ranking, which is also why the
  query fetched 60 rows to show 10. The composite the plan asked for is now real: a `rank()`
  method orders on form, then average FDR of the next N fixtures, then ownership (differentials)
  or total points (captains). Unit-tested, because an integration run always resolves an empty FDR
  map and so cannot see the composite at all.
- **The prompt claimed a tool reported xGA when it did not.** It told the model
  `getTeamExpectedGoals` gives "team xGA implicitly via goals conceded", while the tool returned
  neither — and the new strategy doc tells the model xGA is *the* clean-sheet signal for
  defenders and keepers. `getTeamExpectedGoals` now genuinely reports xGA and goals conceded per
  fixture. The aggregation is the subtle part: FPL writes the team's `expected_goals_conceded` and
  `goals_conceded` onto **every** player who was on the pitch, so `SUM` would multiply the team's
  xGA by its squad size (24.86 instead of 2.26 in a checked fixture). The tool uses `MAX`, and a
  test pins `MAX(goals_conceded)` against the `fixture` scoreline over ten fixtures.
- **`getPriceHistory` spent one of `lastN` on the live snapshot** (asking for 5 gameweeks returned
  4 and the snapshot), and with only the snapshot stored it compared that row to itself and
  printed "price has held steady between live snapshot and live snapshot". That lone-snapshot state
  is the normal one between the first sync of 2026-27 and the first played gameweek — i.e. the
  first thing users would have seen. The snapshot and the gameweeks are now fetched separately, and
  a single data point is reported as a price, not a trend.
- **`getFixtureDifficulty` counted an unrated fixture as FDR 0**, printing a value outside the
  documented 1–5 range and dragging the average down. Unrated fixtures now print "FDR unknown" and
  are excluded from the average, which names how many fixtures it covers. Kickoff times render in
  UK time and say so (the container runs UTC on Fly, so an unlabelled hour misleads during BST),
  and postponed fixtures — `gameweek IS NULL`, previously dropped silently by the `gameweek >=`
  bound — are listed in their own section, as `FixtureTools` already did.

### Test coverage that did not cover what it claimed

- **Task 5's acceptance criterion was met by no test.** `FixtureDifficultyToolsIntegrationTest`
  asserted `containsAnyOf("Fixture difficulty for", "No upcoming fixtures found")` — which passes
  on the empty branch, and since no season has an unfinished fixture it *always* took the empty
  branch. That is the same always-green assertion Phase 1's `GameweekToolsTest` was corrected for.
  `format()` is now a database-free package-private method with six unit tests covering home/away
  rating selection, the average, unrated fixtures, UK kickoff rendering, missing kickoffs and the
  postponed section; the integration test now asserts the empty branch *specifically*, and first
  asserts that the branch is the one actually being taken.
- **The snapshot writer had zero coverage.** Every `PriceSyncServiceIntegrationTest` case used a
  fictitious season id with no tapas `player` rows, so `snapshot()` always wrote nothing — Task 1's
  "the snapshot sync is idempotent" was never demonstrated. Three tests now pair a **real** season
  id (so real players are copied) with a **fictitious** season label (so nothing real is written):
  one row per player, the price and denormalised name match the `player` table, and re-running
  updates in place instead of accumulating.

### Efficiency

- **The price sync did ~30,000 single-statement round-trips per run, every 6 hours.** A full
  season's `merged_gw.csv` is 29,757 data rows (measured against the live file), each upserted with
  its own `jdbc.update`, re-writing every historical gameweek every time — on the pooled database
  shared with `tapas-fpl-app`. Phase 3's backfill survived this shape only because it wrote 2,660
  rows once. Writes are now batched (1,000 per statement) and the vaastav path is incremental:
  gameweeks below the highest already stored are frozen and skipped, so steady-state cost is one
  gameweek's rows rather than the season's. The highest stored gameweek is still rewritten, since
  it may have been captured mid-gameweek.
- **N+1 queries at chat time are gone.** `getTeamExpectedGoals` issued one aggregate plus one
  opponent-name lookup per fixture (~21 round-trips at `lastN=10`) and now answers from one query;
  `getPlayerExpectedStats` joins the opponent name instead of looking it up per row; the pick tools
  resolved FDR once per displayed player — repeating the request for team-mates — and now resolve
  every team's FDR in a single windowed query.

### Consistency and hygiene

- **Ambiguous name matches are no longer silent.** `web_name` is not unique (2025-26 has two
  Andersons, two Onanas, two Barneses) and "man" matches both Manchester clubs. Every Phase 4 tool
  took `LIMIT 1` and answered as though it were the only reading. They now fetch up to four matches
  and append a note naming the alternatives. The Phase 1 tools keep their existing behaviour —
  changing them was outside this review's scope.
- **`FictitiousTestData` now reaches every phase.** Phase 3's review introduced it precisely to stop
  test data colliding with real data, but it was package-private in the `historical` package, so
  Phase 4's tests could not use it and `PriceToolsIntegrationTest` wrote rows under the real season
  label `2025-26`. Moved to a `testsupport` package and applied. It also gained a length check:
  both `season` columns are `VARCHAR(10)`, and an over-long fictitious label has now broken a test
  run in two separate phases, so the guard catches it at class-load time instead.
- **`parsePosition` no longer widens a typo into "any".** "striker" or "7" silently returned every
  position; they now return a message naming the valid values. "GKP", "goalkeeper", "defender" and
  friends are accepted.
- **Documentation matches the code.** The Phase 4 spec said "draft pending review — do not
  implement" on a phase that had shipped, still posed its two settled decisions as open, and still
  listed the dropped risers/fallers view as a gap; the plan's pre-implementation checklist was four
  unticked `- [ ]` boxes, which is also the checkbox style `AGENTS.md` forbids. All corrected, with
  the checklist rewritten as a table of outcomes and evidence. `HttpVaastavSource.fetch` and
  `fetchIfPresent` were near-identical copies and now share one private method.

# Status — Frontend Design System

**Progress:** Task 3/6 done, on branch `frontend-design-system` (not yet merged). Executed via
subagent-driven-development; ledger at
`.superpowers/sdd/2026-08-15-frontend-design-system/progress.md`.

Plan: `docs/superpowers/plans/2026-08-15-frontend-design-system.md`.
Spec: `docs/superpowers/specs/2026-08-15-frontend-design-system.md`.

This is a cross-cutting rebuild of the chat UI (tokens, layout, transcript, composer, theme
toggle) done between Phase 4 and Phase 5 so Phase 5's new screens are styled once. Not part of
the Phase 1–5 numbering above.

## Done

| Task | Commit | What exists now |
|---|---|---|
| 1 — Tokens, fonts, contrast guard | `628c374` | `tokens.css` (full light/dark palette + spacing scale), self-hosted Archivo/Newsreader variable fonts, Mantine theme wired to the same custom properties, `check-contrast.mjs` WCAG guard wired into `npm run build`. No component consumes the tokens yet. Review: clean. |
| 2 — Layout shell, mobile fixes | `12c62fa` | `ChatView.module.css` root as flex column (`height: 100dvh` — see below), single 640px breakpoint (full bleed below with 16px gutter from existing child padding, centred 660px column above), 48/56px header. `index.html` viewport meta gains `viewport-fit=cover, interactive-widget=resizes-content`. `scroll-padding-bottom: 16px` on `MessageThread`'s `.thread`. Review: clean, 3 minors deferred (below). |
| 3 — Transcript thread | `62c28e6..00959cb` | Bubbles removed from `MessageThread`/`AssistantMessage`; each turn is a small-caps `--font-ui` attribution label (`--muted` user / `--pitch` assistant) plus a `--rule-section` hairline plus body (`--font-reading` 18px/1.65 assistant, `--font-ui` user), 24px turn rhythm, no backgrounds/borders/shadows/radii beyond the hairline and table rules. `remark-gfm@4.0.1` added; tables styled with `--rule-row`/`--rule-section`. Provenance line (`SeasonContext.provenance()`'s `"Data source: ..."` prefix) split out and rendered muted. Review found 1 Important bug (provenance-split could swallow the whole reply when it had no newline) — fixed in round 1 (`00959cb`), re-review clean. |

Deviation: Task 2 ships `height: 100dvh` on `.root`, not the plan's literal `min-height: 100dvh`.
Proven necessary with an isolated Puppeteer harness — with only `min-height`, a flex column's
`flex:1` child can't resolve a bounded main size once content overflows, so the thread's
`overflow-y:auto` never gets anything to clip against and the whole page grows instead (composer
ends up ~2600px off-screen at 375×667 with 40 injected messages). `height: 100dvh` is what the
plan's own adjacent requirement ("thread region scrolls") actually needs. Full measurements in
`.superpowers/sdd/2026-08-15-frontend-design-system/task-2-report.md`.

Known gap, not a defect: `interactive-widget=resizes-content` (added in Task 2) has no effect on
Safari/iOS — WebKit has not implemented it (bug 259770) as of Aug 2026; only Chrome 108+/Firefox
132+ honour it. The iOS keyboard-covers-composer problem is *not* solved by this meta value;
`height: 100dvh` plus Task 4's planned safe-area padding are what keep the composer reachable on
iOS. No JS fallback exists for iOS specifically — open product question, not scoped to any task.

Deferred minors (non-blocking, from Task 2 review): `scroll-padding-bottom: 16px` and `.header`'s
`height: 48px` are literals rather than `var(--space-16)`/`var(--space-48)` — on-scale values,
just inconsistent with the same diff's other literal→token conversions.

## Remaining

Tasks 4 (composer, safe-area insets), 5 (theme toggle, quality floor), 6 (verification, deploy,
docs) — see the plan for each task's done-when criteria.
