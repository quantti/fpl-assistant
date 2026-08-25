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

**Corpus as of 2026-08-17: 19 documents, 166 chunks in `fpl_embeddings`** — verified against the
live database, and every document on disk has matching rows. The per-task figures further down
this file (e.g. "9 docs → 76 chunks") are dated records of what each phase did, not the current
count. Ten of the nineteen were only committed on 2026-08-17 (`c7165b1`); they had been ingested
and were shipping in the image, but git did not have them, so the Docker build was copying
untracked working-tree content. One document is `kind: timely` and ages out by design:
`strategy-2026-27-preseason-consensus.md`, `valid_until: 2026-08-21`.

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

# Status — Daily price and availability capture (2026-08-18)

Not a numbered phase: a gap found while investigating tool freshness, fixed the same day.

## Why it exists

Nothing in the project knew today's player price. The two existing sources both lag:

- **vaastav** publishes prices only once a gameweek has been played.
- **The tapas `player` table**, which this project reads, is synced by the other project's
  `scheduled_update.py` (daily 06:00 UTC cron) — but that run **skips entirely when no new
  gameweek has finalised**, and players are documented as syncing weekly.

Measured against the live database on 2026-08-18: every `player`, `fixture` and `gameweek` row
was last written **2026-05-25 — 85 days earlier**; `team` dated from 2026-01-04; and season
2026-27 had **zero player rows**, three days before its first deadline. Meanwhile FPL's own API
already carried the full 590-player 2026/27 squad.

FPL moves prices **once a day at 00:00 UK** (changed from ~01:30 GMT / 02:30 BST for 2026/27 —
[premierleague.com](https://www.premierleague.com/en/news/4680462/whats-new-in-202627-fantasy-price-change-predictor/)),
so a weekly source can be seven price changes behind on a value the game is built around.

## What exists now

`fpl_daily_price`, keyed `(season, price_date, element_id)` — one row per player per UK
calendar date, fetched straight from FPL's `bootstrap-static`. It depends on neither
`SeasonResolver` nor the tapas tables, which is the point: it has to keep working across the
season changeover, exactly when the tapas-backed paths go quiet.

| Piece | Where |
|---|---|
| Table DDL | `DailyPriceSchema` (idempotent, `_test` table under the test profile) |
| Fetch and upsert | `DailyPriceSyncService` (parsing is package-private and unit-tested against a captured fixture) |
| Script | `DailyPriceRunner` — `java -jar app.jar --sync-prices`, exit 0/1 for a scheduler |
| Schedule | `DailyPriceScheduler`, cron `02:00 Europe/London` (two hours after the price change) |

Verified end to end against the production database: 590 rows captured, re-running the same day
overwrites rather than duplicates, 182 tests pass.

## The table also carries the injury picture — deliberately

`bootstrap-static` returns availability on the same elements as the price, so `status` and
`news` are captured in the same request at no extra cost, on the same daily cadence:

- **`status`** — `a` available, `d` doubtful, `i` injured, `s` suspended, `u` unavailable,
  `n` on loan / not in squad. **103 of 590 players were flagged on 2026-08-18.**
- **`news`** — FPL's free-text note ("Knock - 75% chance of playing"), empty when available.

This is as important as the price. The tapas `player` table is the only other source for
availability and is weekly, so a stale `status` would tell a user a player is fit after he has
been ruled out. Because the rows are dated, the series answers what a snapshot cannot: when a
flag first appeared, and whether a doubt is clearing or worsening.

**No tool reads these columns yet** — the capture deliberately runs ahead of the tools, because
FPL publishes only today's state and a day not captured is gone for good.

## Known dependency

The schedule works because the Fly machine now stays up (`min_machines_running = 1`,
`auto_stop_machines = false`). Under the previous stop-when-idle configuration a 02:00 cron
would never have fired — nothing would have been running to fire it. **If that setting is
reverted the price capture stops** — the freshness alert below makes the miss visible as an
ERROR once the app runs, and the app-not-running case itself shows up on Fly's
`/actuator/health` check. A Fly scheduled machine would be immune; it was not chosen because
the always-on setting made the simpler option correct.

## Freshness alert (added in the 2026-08-18 review)

`DailyPriceFreshnessCheck` runs at 06:00 / 12:00 / 18:00 UK and logs **ERROR** when
`fpl_daily_price` has no row for the current UK date. The capture writes one row per UK date
at 02:00 UK, so by 06:00 a missing row means a missed capture, whatever the cause — scheduler
failure, machine stopped, or an FPL response whose shape changed so the sync wrote zero rows.
The check shares the `fpl.daily-price.scheduled` gate: disabling the capture disables the
alert. Not wired into `/actuator/health`: Fly restarts on a non-UP health check, and a
restart cannot backfill a missed day — it would flap instead of healing. If an external
webhook alert is ever wanted, the ERROR log is the hook point.

## Review follow-ups (2026-08-18)

Whole-branch review of the six commits landing the daily capture, applied the same day (freshness
alert above is part of it):

- `getFlaggedPlayers` matched several clubs for a substring like "man" and listed all their
  players under whichever club came first — each player now carries its own club code, and the
  header names the club count. The 30-player cap is stated in the tool description.
- `UkTime.format` now renders `OffsetDateTime`/`Instant` in the house style instead of a raw
  `toString`, in case pgjdbc hands either back.
- `getPriceHistory` description now states that the first row is today's price, so the model
  answers "what does X cost now" from it. A dedicated `getCurrentPrice` tool was considered and
  not built — the description change covers the case without new surface.
- `PriceTools.formatHistory` empty-list guard considered and not added: the query is anchored to
  a matched player, so the list cannot be empty (project rule: no handling for impossible
  scenarios).
- System prompt has grown long with the new tool guidance; watch for drift and trim when a
  natural moment comes.

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

**Progress:** Task 6/6 done plus one whole-branch review fix wave, on branch
`frontend-design-system` — **deployed** (2026-08-16, `fly deploy` exit 0, health UP at
https://fpl-assistant.fly.dev/), **not yet merged**. The fix wave below landed after that deploy
and therefore needs a redeploy. Executed via subagent-driven-development; ledger at
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
| 3 — Transcript thread | `62c28e6..00959cb` | Bubbles removed from `MessageThread`/`AssistantMessage`; each turn is a small-caps `--font-ui` attribution label (`--muted` user / `--pitch` assistant) plus a `--rule-section` hairline plus body (`--font-reading` 18px/1.65 assistant, `--font-ui` user), 24px turn rhythm, no backgrounds/borders/shadows/radii beyond the hairline and table rules. `remark-gfm@4.0.1` added; tables styled with `--rule-row`/`--rule-section`. Also shipped a muted style for the provenance line (`SeasonContext.provenance()`'s `"Data source: ..."` prefix) — **since deleted in `c2f0bd8`**, see "Resolved: the provenance muted-aside never fired" below; there is no provenance-specific styling in the build. Review found 1 Important bug (provenance-split could swallow the whole reply when it had no newline) — fixed in round 1 (`00959cb`), re-review clean. |
| 4 — Composer | `ae9b281..c57dfd7` | `MessageInput` restyled: `--paper` background, `--rule-section` top rule, `padding-bottom: max(var(--space-12), env(safe-area-inset-bottom))`, textarea caps at 5 rows then scrolls, IME composition guard preserved. New `--pitch-contrast` token (white in light, linked to `--ink-900` in dark) fixes a dark-mode-only bug Mantine's `autoContrast` couldn't have caught (white-on-light-green send button, ~1.45:1) — computed/verified at 5.01:1 light / 11.52:1 dark. Review found 2 Important issues (an unscoped `!important` bled into the disabled-state colour; the dark-mode literal duplicated `--ink`'s hex with no drift guard) — both fixed in round 1 (`c57dfd7`): `.sendButton:not(:disabled)` scoping, and `--ink-900` as the single canonical literal both `--ink` and `--pitch-contrast` reference. `check-contrast.mjs` gained a small `var()`-reference resolver as necessary fallout; re-review confirmed it fails loud rather than masking a broken reference. Re-review clean. |
| 5 — Theme toggle, quality floor | `e3e3840..3e4c0cf` | Header toggle wired to Mantine's own `useMantineColorScheme`/`useComputedColorScheme` (single persisted source of truth, no parallel storage) syncing `document.documentElement.dataset.theme` on every change. Global `:focus-visible` ring in `--pitch` added to `tokens.css`; needed `!important` to beat Mantine's own `Textarea` focus-outline suppression (verified real specificity conflict, not a misdiagnosis — disabled elements can't be focused, so no blast-radius risk like Task 4's). `prefers-reduced-motion` stops the streaming cursor's blink, stays solid. Review found 1 Important issue (`data-theme` sync ran in `useEffect`, post-paint, risking a theme flash on reload when a persisted choice opposes the OS preference) — fixed in round 1 (`3e4c0cf`) by switching to `useLayoutEffect`, matching Mantine's own pre-paint pattern. Re-review clean. |
| 6 — Verification, deploy, docs | — (no frontend code changes, verification only) | Verified against the real running backend (5 live `POST /api/chat` calls, not mocked) at 375px/1024px, both themes: transcript/hairline/reading-face rendering confirmed, a real markdown table rendered correctly at both widths/themes, no horizontal scroll, `npm run check-contrast` passes. Built the Docker image locally and extracted `app.jar` to definitively confirm both font files (`archivo-latin-*.woff2`, `newsreader-latin-*.woff2`) are present at the exact path the shipped CSS references — genuine WOFF2 binaries, byte-size matched, independently re-extracted and confirmed by the task reviewer too. `fly deploy` and this file's update were held for the human's go-ahead at the time; the go-ahead came and the deploy ran on 2026-08-16 (see Progress above). |
| Whole-branch review fix wave | this commit | Mantine's own surface colours brought under the token system via `cssVariablesResolver` + `variantColorResolver`, `prefers-reduced-motion` gate on the transcript's auto-scroll, dark-mode `--muted`, two contrast-guard hardenings. Detailed below. |

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

Deferred minors (from Task 2 review): `scroll-padding-bottom` was tokenized to `var(--space-16)`
in Task 3 and is no longer a literal. The one remaining literal is `.header`'s `height: 48px`
(and its `56px` sibling above the breakpoint), and that is deliberate rather than deferred: `56`
is not on the spacing scale and has no token, and both are element *sizes*, not spacing. Giving
one of them `var(--space-48)` would imply a relationship to the scale that does not exist.

### Resolved: the provenance muted-aside never fired in real usage — styling dropped

Task 3 built a dedicated muted style for the "Data source: ..." line every stats tool prefixes
onto its reply. It was correctly implemented for the input shape it was designed for — Task 3's
synthetic tests proved that — but Task 6's live verification found it never fired once across 5
real chat queries: the model paraphrases season attribution into its own words per
`system-prompt.st`'s instruction, rather than relaying the tool's literal `"Data source: ..."`
string, so `PROVENANCE_RE` never matched real output.

Owner's decision: keep the season mention as ordinary body text — the model already satisfies
`AGENTS.md`'s provenance discipline in its own prose, and the app should not otherwise call out
which season a reply draws on beyond what the model already says. Backend/system-prompt behaviour
is unchanged (still attributes by season in prose, per `AGENTS.md`'s binding discipline for all
tools) — this was a frontend-only styling question, not a backend data-honesty one. Fixed by
deleting the dead `splitProvenance`/`PROVENANCE_RE` logic and the orphaned `.provenance` CSS rule
(commit `c2f0bd8`); the season mention now renders exactly like the rest of the answer, in the
reading face.

The spec carried the same mandate and now carries the same correction: see
`docs/superpowers/specs/2026-08-15-frontend-design-system.md`'s "Amendment 2026-08-17". The spec
is what Phase 5 builds from, so recording the removal only here would have let it be re-added.

### Whole-branch review fix wave (2026-08-17)

The plan's review checkpoint ran against the deployed branch and found four Important issues.
All are fixed; the pass touched `theme.ts`, `main.tsx`, `tokens.css`, three CSS modules,
`MessageThread.tsx`, `check-contrast.mjs`, this file and the spec.

**Mantine's own surface colours were never under the token system.** Five tasks each restyled
what they built; nobody swept the elements nobody built, so the app was rendering colours that
appear nowhere in `tokens.css` — which is also a structural blind spot in `check-contrast.mjs`,
since it parses `tokens.css`. Three live symptoms, one root cause: the empty state's
`--mantine-color-dimmed` measured **3.14:1** on `--paper` (an AA failure on the first screen every
user sees); no element painted the page ground, so above 640px the desktop gutters showed
Mantine's `--mantine-color-body` (`#242424` beside a `#10151F` column in dark); and the composer's
`Textarea` kept Mantine's default filled, bordered, 8px-radius box, which the spec's "deliberately
absent" list forbids. Fixed at the provider rather than per component: a `cssVariablesResolver`
maps `--mantine-color-body`, `--mantine-color-text`, `--mantine-color-dimmed`,
`--mantine-color-placeholder` and `--mantine-color-default-border` onto tokens, and a
`variantColorResolver` writes `--pitch-contrast` into filled controls itself — which *removed* the
`.sendButton … !important` in `MessageInput.module.css` rather than fighting it, and pre-empts
every filled Mantine control Phase 5 adds. Empty state now measures **8.01:1 light / 8.09:1 dark**;
gutter and column sample identical in both themes at 1024px; textarea is `--paper` on a
1px `--rule-section` hairline at radius 0; send button unchanged at 5.01 / 11.52. The mapping goes
into the resolver's `light` *and* `dark` blocks, not the shared one — Mantine's own defaults land
in `:root[data-mantine-color-scheme="…"]` (0,2,0), which outranks a bare `:root`.

**`--muted` had no dark value.** The spec's dark palette table never listed one, so `--muted` kept
its light navy `#414D66` in dark mode: **2.16:1** against dark `--paper`, and that is the
transcript's "YOU" attribution label. Nothing caught it because the guard's dark section had no
muted row. Adding a dark `--muted` was also a precondition for the empty-state fix, which would
otherwise have regressed dark from 4.76 to 2.16. New value `#A2ADC3`, generated the same way as
the rest of the palette (same hue 220.5° and saturation 22% as `#414D66`, lightness inverted
32.7%→70%), measuring 8.09:1 — mirroring light's own ink/muted relationship. Gated in both themes
now.

**Auto-scroll ignored `prefers-reduced-motion`.** `MessageThread`'s `scrollIntoView({ behavior:
'smooth' })` fires on every streamed token and Chrome does not auto-downgrade an explicit
`'smooth'`. Task 5's quality floor was accepted against the spec's premise that the streaming
cursor is the only animation in the app; it was not. Now reads the media query and passes `'auto'`
when set — verified in a headless browser over a mocked SSE stream: 9/9 calls `smooth` at
no-preference, 9/9 `auto` under `reduce`, both themes.

**Two contrast-guard hardenings.** The parser did not strip `/* */` comments, so a comment like
`/* try --paper: #FFFFFF; */` placed after the real declaration made the guard report and gate on
the commented-out value — reproduced, then closed with one `replace()`. And the gated set
contained `white on pitch` but never `pitch-contrast on pitch`: the token components actually
render was ungated in both themes, passing in light only because `--pitch-contrast` happens to
equal `--white` today. Both pairings are gated now, plus `muted on ground`.

**Deliberately not changed.** The disabled send button still uses Mantine's own
`--mantine-color-disabled` / `--mantine-color-disabled-color` (`#E9ECEF`/`#ADB5BD` light,
`#2E2E2E`/`#696969` dark) — the last off-token surface in the build, visible on the empty screen.
WCAG exempts inactive components from contrast requirements, but the colours are off-palette. It
was left alone because the palette has no disabled token and inventing one is a design decision:
`--paper` fill plus a `--rule-section` glyph would arguably be right, but that is the owner's call,
not a review-fix. The send button's own 8px radius is likewise untouched — the finding named the
input's radius, and `theme.defaultRadius: 0` would have squared every future Mantine control on
the strength of a finding about one textarea.

## Remaining

Nothing left in this plan's scope, and nothing blocking.

- **Redeploy.** The 2026-08-16 deploy predates the fix wave above, so production is currently
  serving the branch without it. Run `fly deploy --ha=false --remote-only` (single Fly.io app
  `fpl-assistant` per `fly.toml` — the Docker build bundles the frontend's static build into
  Spring Boot's `static/`, so one deploy ships both; there is no separate frontend hosting).
- **Merge.** The branch is still unmerged. A scoped re-review of the fix range runs first; after
  that, use `finishing-a-development-branch`.

## Pre-push review (2026-08-18, evening) — all findings fixed

A review of everything ahead of `origin/master` (10 commits: the daily-price capture, the
price/availability tools on it, the two review-fix commits, the freshness alert, the frontend
design follow-ups, infra, docs). One critical, eight warnings, and most of the suggestions;
all fixed in the commits after `01688cd`. 216 tests (was 206), 0 failures; frontend build and
contrast gate clean.

**Critical: `element_id` reads were not season-scoped.** `getPriceHistory` and `flagAge`
filtered on `element_id` alone. FPL renumbers every season — the project's own artifacts say
so (`fpl_daily_price`'s PK is `(season, price_date, element_id)`; STATUS.md notes the ids are
season-scoped) — so at the 2027 changeover, the exact window this table was built to survive,
the trend would have mixed last season's rows for the same numeric id (a different player)
into this season's series, and a flag raised today could read as having stood since last
April. Both queries now filter on `season` (already in hand from the matched row), the
per-player index is replaced with a season-leading one (new name; the old index is dropped,
because `CREATE INDEX IF NOT EXISTS` would have kept the old definition everywhere the table
already existed), and the convention is recorded in AGENTS.md so a future agent does not
"fix" it back. Pinned by tests that insert another season's rows under the same element id.

**Capture hardening.** The sync's RestClient had no timeout on the shared scheduler thread —
a hung 02:00 connection would have held the 06:00/12:00/18:00 freshness checks hostage,
silencing the alert exactly when the capture failed. Bounded (10s connect / 30s read, the
generous read because `bootstrap-static` is a ~1-2 MB body) and `spring.task.scheduling.pool.size`
is now 2 so the capture and the check cannot block each other; the pool size is pinned in
`DailyPriceScheduleTest` like the crons. The day's rows are written in one transaction:
under autocommit a crash mid-batch left a partial day that the freshness check (which only
asserts a row exists for today) accepted as fresh forever, and that a mid-capture reader
anchored to `MAX(price_date)` could observe half-written — a club answering "nobody flagged"
because its injured players had not been inserted yet. `today` is sampled after the fetch,
not before, so a slow response cannot straddle a UK midnight and mislabel the day.

**Season-code guard.** The calendar rule (July ⇒ next season) could mislabel the early-July
window when FPL has not yet reset `bootstrap-static`: last season's squads written daily
under the new season label, freshness green, tools serving weeks-old data as "captured
today". The sync now refuses a response whose newest event deadline is more than 40 days
past — that response is a finished season FPL has not reset, and a mid-June sync of a
just-finished season (still correctly labeled by the calendar rule) stays allowed. Unit
tests pin the refuse/allow/pass-through cases.

**Freshness check: future-dated rows warn.** A row dated in the future (a manual
`--sync-prices` from a skewed laptop clock) suppresses staleness alerts for as long as it
stays newest; it now logs a WARN naming the skew instead of passing silently.

**`team_name` backfill.** The `ALTER … ADD COLUMN` left already-captured rows NULL and the
daily upsert only touches the current date, so full-name club lookups would have kept
missing until the next morning's capture. The schema now backfills NULLs from the most
recent row of the same player in the same season — an approximation (a January transfer can
change a player's club), but a day of the bug the column exists to fix is worse.

**Flagged-list totals.** The header's "%d found / at %d clubs" was computed after `LIMIT 30`,
so a broad substring ("united") understated the totals and silently hid every club whose
players fell outside the top-30-by-price cap. Totals now come from an uncapped `COUNT`, and
a truncated list says so ("showing the first 30 — narrow the club name to see the rest").
Tests: truncation at the cap, and the single-club header format (which no test pinned).

**Name matching: exact `web_name` first.** "Best match first" was actually "most expensive
match first" (`ORDER BY now_cost DESC`), so "son" could rank a pricier Wilson above Son and
the tool would answer for the wrong player. Exact `web_name` matches now rank first, then
price.

**Error turns replayed to the model.** `useChat` stripped the `isError` flag but sent the
error *content* back as model history on the next send — the model was told it had previously
said "Sorry, something went wrong". Error turns are now filtered from the request body, and
the consecutive user turns that filtering can produce (question, error, retry) are merged,
because the Anthropic API rejects two user turns in a row. The turns stay visible in the
transcript. `ChatRequest` was dead code whose type contradicted its own doc comment; it now
types the actual wire contract (`Array<Pick<Message, 'role' | 'content'>>`) and is used by
the fetch.

**Commit-message correction.** `d5aca5f`'s message claimed the system prompt "no longer
sends price questions to searchStrategyDocs" — no committed version of the prompt ever
contained that routing; the diff was purely additive. Reworded (rebase over the unpushed
range) to describe what the diff actually does. The substantive gap the finding pointed at —
the general advice rule routing transfer/value questions through documents with frozen
prices — is now closed in the prompt itself: strategy advice that turns on a current price or
ownership figure takes the figure from the stats tools.

**Smaller items.** AGENTS.md no longer lists `getPriceHistory` among the `SeasonContext.provenance()`
tools (it uses `DailyPriceSource.provenance()` by design, a documented exception like
`HistoricalTools`). The truncated chips sentence in the system prompt is completed ("the +1
comes the following gameweek", per FPL_RULES.md). `UkTime` javadoc updated (it still described
the pre-9a7ec14 fallback behaviour) and documents why `LocalDateTime` deliberately falls
through unformatted. `UkTimeTest` gained the spring clock-change pair (the commit message had
claimed "both 2026 switches" but only October was tested). `vite.config.ts` uses `||` so an
empty `FPL_API_PROXY` falls back rather than crashing the proxy, and `tokens.css` states the
actual 2.38:1 ratio plus why `--disabled-ink` coincides with `--rule-section`. `GameweekTools`
no longer selects the never-rendered `most_selected`.

**Considered and not done.** The freshness check's staleness rule keeps using the container
clock: a manual sync from a machine a day behind genuinely writes yesterday's data, and the
alert firing on that is correct, not a false positive. Contrast-gating an upper bound on the
disabled tokens (fail above ~3:1) was left out — "starts looking enabled" has no agreed
threshold, and the [info] line already records every drift.

---

# CI/CD — ephemeral test database (2026-08-19)

Plan: `docs/superpowers/plans/2026-08-18-cicd-ephemeral-test-db.md`. Tasks 1–7 and 9 done; 8 and
10 need repository access. The owner's binding constraint: **the test database is empty at the
start of every run and destroyed at the end.** No hosted test project, no cleanup script — a
container the runner throws away is the only guarantee that is mechanical rather than hopeful.

## What exists now

- `scripts/dump-test-schema.sh` → `backend/src/test/resources/db/tapas-schema.sql`: the DDL of the
  six tapas-owned tables this project reads but does not own. `--check` fails on drift against
  live Supabase. Re-run after any tapas migration.
- `scripts/test-db.sh up|reset|down`: the local container, same image as CI.
- `.github/workflows/deploy.yml`: tests on every PR, tests → deploy → external smoke test on
  every push to `master`.
- `TapasSeed`: the fixture league (four clubs, six players, four gameweeks, nine fixtures) the
  data-dependent tests assert on.
- Two env files: `backend/.env.test` (local container) and `backend/.env.supabase` (production).

218 tests, 0 failures, 25 seconds, against a database that did not exist half a minute earlier.

## What the plan's first draft got wrong

It treated the migration as one inventory task verified by "run the suite against empty Postgres;
every failure names a missing seed". Reading the tests first showed that was an order of magnitude
off, and the plan was rewritten before any code was written:

- **An empty database is not merely unseeded, it is schemaless.** Six tables belong to
  `tapas-fpl-app` and have no DDL here, so the failure is `relation "player" does not exist`.
- **41 of 216 tests failed**, and 32 of them asserted on production *content* — Salah's xG,
  Arsenal's fixture list, "no season has an unfinished fixture". A seeded season row satisfies
  almost none of those.
- **Dummy LLM keys do not carry the suite.** Both RAG integration classes embed for real.
- **The startup runners fire in every Spring context**, so each context start would have fetched
  the vaastav CSV over the network against an empty database.

## Findings worth keeping

- **`pg_dump` 17.11 emits a random `\restrict` token per run.** Left in the committed dump,
  `--check` would report drift on every invocation and stop meaning anything. Stripped, along with
  the two version-comment lines that change with the client image.
- **`pg_dump -t` does not dump trigger functions.** `player_fixture_stats` has a trigger calling
  `update_pfs_updated_at()`, and the dump would not load without it.
- **pg_dump needs the session pooler (5432)**, not the transaction pooler (6543) the application
  uses; the direct host is IPv6-only and unreachable from WSL2.
- **Spring AI's `PgVectorStore` creates `vector`, `hstore` and `uuid-ossp` itself** at context
  start, so the workflow's explicit `CREATE EXTENSION` is belt-and-braces.
- **`app_user` is not an available table name.** The shared database already carries tapas'
  `app_user` and `manager`; `CREATE TABLE IF NOT EXISTS app_user` here would bind to tapas' table
  and write our rows into it. Phase 5a uses `fpl_user_profile`.

## Coverage the move added

Not a side effect — two rules were untestable against production data and are now exercised:

- `FixtureDifficultyToolsIntegrationTest` could only reach its empty branch, because no season in
  the shared database had an unfinished fixture. It now tests the Average FDR branch, and keeps
  the empty branch through a club seeded with no fixtures.
- `FixtureToolsTest`'s double-gameweek rule ("bounds distinct gameweeks, not fixture rows") passed
  on single gameweeks. The seeded club plays twice in the window's first gameweek.

## Deliberate exceptions

- **One test is tagged `realdata` and excluded from `./gradlew test`**: the vaastav importer's
  cross-check against tapas' fully-synced 2025-26 fixtures. Reproducing that oracle would mean
  copying production and calling it a fixture. Run it by hand with `-PincludeRealData`.
- **`OPENAI_API_KEY` must be real in CI.** The alternative — a stub `EmbeddingModel` — would make
  the RAG tests offline and free, but would stop them proving that a document survives chunking,
  embedding and pgvector storage with its metadata intact.

## Live since 2026-08-19

PR #1 merged; the pipeline ran end to end on master: tests 2 m 34 s, deploy + smoke test
2 m 25 s, `/actuator/health` UP and the frontend serving. Deploying is now a merge.

`FLY_API_TOKEN` expires **2027-08-19** — the deploy job starts failing that day unless the token
is rotated.

## Superseded — what was open before the merge

- **Task 8, GitHub secrets:** `FLY_API_TOKEN` (app-scoped, `fly tokens create deploy -a
  fpl-assistant`) and `OPENAI_API_KEY`. The workflow is written and will fail without them.
- **Task 10, branch protection:** requires the test check on `master`. Verify plan availability
  first — classic branch protection on private repos has historically needed GitHub Pro/Team. If
  unavailable, `needs: test` still blocks the release, though not the merge; record the gap
  rather than upgrading by reflex.


---

# Phase 5a — Task 1: JWT authentication (2026-08-19, in progress)

Plan: `docs/superpowers/plans/2026-08-18-phase5a-auth-user-profile.md`. `/api/**` now requires a
bearer token verified against Supabase's published JWKS. 228 tests green.

## Probed against the live project

- **JWKS** `https://itmykooxrbrdbgqwsesb.supabase.co/auth/v1/.well-known/jwks.json` → 200, one
  key, **ES256** (P-256), kid `25af184b-a504-47f7-90dc-e09785d66077`.
- **Issuer** `https://itmykooxrbrdbgqwsesb.supabase.co/auth/v1`, confirmed by the OIDC discovery
  document, which the project also serves.
- Audience and `sub` shape still to be confirmed against a real token — `/auth/v1/settings` and
  every token-minting endpoint need the project's anon key, which is not in the repo.

## Findings

- **ES256, not RS256.** Spring's decoder accepts RS256 only unless told otherwise, so an
  unconfigured decoder would have rejected every real Supabase token. Configured explicitly.
- **The decoder is built from the JWKS URI, not the issuer URI.** An issuer-based decoder fetches
  the discovery document while the context starts, which would put a network call to Supabase in
  front of every boot and every test run. The JWKS-based one fetches on first use and caches.
- **An unknown key id triggered a JWKS fetch on every request.** Measured, not assumed: six
  requests carrying tokens signed by a key we never published produced six fetches. That is
  attacker-controlled amplification against our own auth path. The key set now sits behind a
  15-minute cache with a 30-second minimum refresh interval.
- **Rate-limiting that refresh turns a forged token into a 500**, because Spring maps a
  non-`BadJwtException` decode failure to `AuthenticationServiceException`. A token we cannot
  verify is now a 401 with a WARN carrying the real reason — otherwise anyone could paint the
  error log by sending nonsense. The cost: a genuine Supabase outage also answers 401, and the
  WARN is what tells the two apart.

## Probed with the anon key (2026-08-19)

`GET /auth/v1/settings`:

```
external.email:      true    → email sign-up is enabled, as the plan assumed
disable_signup:      false   → sign-up is OPEN to anyone
mailer_autoconfirm:  false   → a fresh sign-up gets no session token until the address is confirmed
anonymous_users:     false
```

Two consequences:

- **Sign-up being open contradicts the owner's decision of 2026-08-19** ("keep registration closed
  for the duration of 5a"). Without billing there is no card anchor and no cost ceiling per
  person, only the per-user quota. Turn it off in Authentication → Sign In / Up before anything
  is deployed with the frontend auth client.
- **A real token cannot be minted from the anon key alone.** Sign-up needs email confirmation, and
  Supabase additionally refuses `example.com` addresses (`email_address_invalid`). Confirming the
  claim shape needs either a user created in the dashboard with "Auto Confirm User", or the
  service_role key and the admin API.

The anon key is the new `sb_publishable_…` format rather than a legacy JWT, which matches the
asymmetric ES256 signing scheme the decoder is configured for.

## Gate closed: a real token, decoded (2026-08-19)

Password grant against a dashboard-created, auto-confirmed user:

```
header: {"alg":"ES256","kid":"25af184b-a504-47f7-90dc-e09785d66077","typ":"JWT"}
iss:    https://itmykooxrbrdbgqwsesb.supabase.co/auth/v1
aud:    'authenticated'        (a JSON string, not an array)
sub:    d40da936-30de-41e0-a182-1586f5b23e2b
role:   authenticated
lifetime: 3600 s
claims: aal amr app_metadata aud email exp iat is_anonymous iss phone role session_id sub
        user_metadata
```

Every value the configuration assumes is confirmed: ES256, the kid the JWKS publishes, the
issuer, the audience, and `sub` as the user's UUID.

**`aud` arrives as a string, and the validator declares `List<String>`.** Spring's claim converter
normalises a single audience into a one-element list, and the tests already cover exactly this
shape: Nimbus serialises `audience(List.of("authenticated"))` as `{aud=authenticated}`, the same
JSON Supabase sends. So the passing tests are not passing on a shape that never occurs.

Also done in the dashboard: public sign-up disabled (the 5a decision), and the test user exists
for future runs of the probe.


---

# Supabase RLS — the PostgREST door was open (2026-08-19)

Supabase's linter flagged "RLS Disabled in Public" on every table. It was not a formality.

## What was actually exposed

Supabase publishes the `public` schema as a REST API at `/rest/v1`, authenticated by the **anon
key — which is public by design and ships in the frontend bundle**. `anon` and `authenticated`
held SELECT, INSERT, UPDATE, DELETE and TRUNCATE on all 39 tables (Supabase's default grants),
and RLS was off on 34 of 35, so nothing constrained them. Measured with the real key, not
inferred:

```
player           [{"id":34,"season_id":1,"first_name":"Joe","second_name":"Gauci",…
fpl_daily_price  [{"price_date":"2026-08-18","element_id":23,"season":"2026-27",…
chip_usage       [{"id":7748,"manager_id":2346868,"gameweek":26,"chip_type":"bboost",…
```

**The timing is what made it urgent.** The key had not been published anywhere yet — no frontend
used `@supabase/supabase-js`, so the door had no handle. Phase 5a Task 6 adds exactly that
handle, and Task 2 creates `conversation` and `message`. Left alone, the day sign-in shipped
would have been the day every user's chat history became readable — and deletable — from any
browser's devtools.

## Fix

`scripts/sql/enable-rls.sql`, run against production: RLS enabled on all 35 tables, no policies
(deny-all for the PostgREST roles), and `ALTER DEFAULT PRIVILEGES` so tables created later do not
inherit the grants. `chip_usage`'s "Allow public read access" policy — the one deliberate hole,
granting PUBLIC an unrestricted read — was dropped on the owner's decision; nothing read it that
way.

Verified after: the anon key returns `[]` for every table and `42501 new row violates
row-level security policy` on a write attempt. Our own access is untouched (841 players, 1184
daily prices, 166 corpus chunks, production `/actuator/health` UP) because both this application
and `tapas-fpl-app` connect as `postgres`, which has `rolbypassrls = true`.

## Why it cannot regress quietly

`DailyPriceSchema`, `PriceSchema` and `HistoricalSchema` now enable RLS on the tables they create,
and `RowLevelSecurityTest` fails if any of them stops. One gap remains by construction:
`fpl_embeddings` is created by Spring AI's `PgVectorStore`, not by us, so a brand-new database
gets that table without RLS — production is covered by the run above, and the revoked default
privileges mean a new one would have no anon grants to exploit anyway.

## The same finding, restated for Phase 5a

RLS is now on everywhere, but it still is **not** the authorization mechanism for user data: this
application connects as a role that bypasses it. Every query touching `fpl_conversation`, `fpl_message`,
`fpl_usage_event` or `fpl_user_profile` filters on the JWT `sub` in application SQL. RLS is the
second lock, for the PostgREST path we do not use.


---

# Schema review fixes (2026-08-19)

Two reviews of the merged Phase 5a schema, applied on `phase5a-schema-review-fixes`.

## The finding worth the round

**The RLS guard test enumerated tables by hand**, so `fpl_embeddings` — the one table created by
Spring AI's `PgVectorStore` rather than by any DDL here — was the one table it could never catch.
Production was closed only because `scripts/sql/enable-rls.sql` was run by hand against tables
that already existed; a fresh Supabase project would have served the whole strategy corpus to the
anon key. The list is now derived (every `public` relation the tapas dump does not define), and it
failed on exactly that table before `VectorStoreRowLevelSecurity` was added.

The derived form then needed two corrections of its own: it passed vacuously for a table that had
stopped being created (so the corpus table's existence is asserted explicitly), and `relkind = 'r'`
skipped views — the very shape that walks past RLS via owner privileges.

## Renamed, and the originals dropped

`conversation`, `message` and `usage_event` became `fpl_conversation`, `fpl_message` and
`fpl_usage_event`. The `app_user` argument never depended on the name: tapas develops against the
same `public` schema, and `CREATE TABLE IF NOT EXISTS` on a name they take first binds this
project to their table — after which our `ALTER TABLE ... ENABLE ROW LEVEL SECURITY` would close
their PostgREST reads.

The merge of PR #4 had already deployed the old names to production. All three were empty (sign-up
is disabled, so no user has ever existed) and were dropped by `scripts/sql/drop-prerename-tables.sql`,
which refuses to drop a table with rows rather than trusting that they are empty.

## Also fixed

`VectorStoreRowLevelSecurity` hard-failed context startup when the pgvector autoconfiguration
backed off — a test supplying its own `@Primary VectorStore` leaves no table to ALTER, so the
suite passed only on test ordering nobody configured. Reproduced by dropping the table and running
`StrategyToolsErrorTest` alone; it now skips with a log line instead.

`updated_at` was maintained by nobody while being the sort key of the conversation list's only
index — now a `BEFORE UPDATE` trigger, created with `CREATE OR REPLACE TRIGGER` so no boot opens a
window where it is missing. `fpl_usage_event` gained `event_type` and `provider`: without them the
quota query counts every row as a chat message, and neither can be backfilled once rows exist.

The `app_user` guard test was asserting on the `_test` copy — trivially true, and blind to the
production value it existed to protect. It now binds `application.yml` and checks every configured
table name, not the four this phase happens to own.


---

# Production outage, 2026-08-19 — SUPABASE_URL was never a Fly secret

Found by a review of the merged Task 1 branch, not by monitoring. `application.yml` made
`${SUPABASE_URL}` a required placeholder; nothing set it in production. Every deploy since PR #3
merged failed its smoke test, the release rolled out anyway, and the app could not start:

```
PlaceholderResolutionException: Could not resolve placeholder 'SUPABASE_URL'
flyctl status → 1 total, 1 critical      curl /actuator/health → HTTP 000
```

Three master runs red in a row (13:43, 14:16, 18:19) and nobody looked. The pipeline deploys on
green tests and then smoke-tests *after* the release is live — it has no rollback, so a red smoke
test is a notification, not a gate.

## Fixed

- `fly secrets set SUPABASE_URL=…` — health UP within a minute.
- `supabase.url` now **defaults** to the project URL rather than requiring the variable. The URL
  is not a secret (it ships in every frontend), so a default is the honest value; a required
  placeholder that fails at startup buys nothing but an outage.
- The suite no longer depends on it either: `application-test.yml` pins `supabase.url` to
  `127.0.0.1:1`, so a test that forgets its `@Primary` decoder cannot reach production's JWKS.

## The second outage the same change caused

With the app booting again, `POST /api/chat` answered **401 to every user**: Task 1 required a
token on `/api/**` while the deployed frontend has no sign-in until Task 6. Two tasks that only
work together were merged apart, onto a branch that auto-deploys.

`/api/chat` is public again until Task 6 flips it in the same change that ships the auth client.
`/api/profile/**` stays authenticated. A test pins the intent so it cannot drift back silently.

## Also hardened while here

- `anyRequest().permitAll()` → `denyAll()` with explicit permits for the static frontend. The old
  catch-all made anything outside `/api/**` public by default — a new `/admin` controller or an
  actuator endpoint added to the exposure list would have been world-readable with no change here
  and no failing test.
- The decoder accepts RS256 alongside ES256: Supabase key rotation may move a project to RSA, and
  accepting only today's algorithm turns a rotation into a total authentication outage that the
  401 wrapper would report as suspected forgery.
- A trailing slash on `SUPABASE_URL` — the form the dashboard displays — produced an issuer with
  a double slash that matches no token. Normalised.

---

# Phase 5a — Task 6: sign-in, session, and SPA routing (2026-08-20)

The frontend now holds a session and sends the token, so `/api/chat` is authenticated again —
in the same change, which is the whole point: splitting them is what answered 401 to every user
on 2026-08-19.

## What exists now

- `SignIn` (sign-in only), `useSession`, and a gate in `App` that renders a loader until the
  stored session has been read — showing the sign-in screen first and replacing it a moment
  later reads as being signed out on every reload.
- `apiFetch` attaches the access token to `/api/**` and signs out on `401`. The token is read
  per request rather than captured: the client refreshes it in the background, and a captured
  copy is the expired one an hour into a conversation. `getSession()` is where the silent
  refresh happens.
- A sign-out button in the chat header, labelled rather than a glyph.
- `SpaForwardingController` forwards client routes to `index.html`, closing the Phase 1 known
  issue that deep links would 404 once Phase 5 added routes.

## No sign-up screen — the plan's "user can sign up" cannot be met by design

Public sign-up is disabled in the Supabase project (the 5a decision: without billing there is no
cost ceiling per person and no card anchoring an account). A form the API refuses is worse than
no form, so the screen says accounts are by invitation. Task 8's "settle the sign-up gate" is
therefore already settled — closed for the duration of 5a.

## The forwarding patterns, and what they cost

Three explicit segment levels, not a trailing `/**`. `/**` matches a segment with a file
extension in it, which forwarded `/assets/index-abc123.js` to the index page under a JavaScript
content type — a missing asset then surfaces as a syntax error in the console instead of a 404.
A client route deeper than three segments matches no pattern and falls through to `denyAll`, so
it answers 401 rather than loading the app; nothing needs four levels today, and Task 7's
conversation routes will not.

The permit those patterns need does weaken yesterday's `denyAll` default: every extension-less
`GET` outside `/api/**` and `/actuator/**` is now public, because the chain cannot tell a route
the browser owns from one the server does. A deep link arrives without a token — the script
holding one has not loaded yet — so there is no version of this that authenticates.

What replaces the lost default is a test, not a promise: `SpaForwardingTest` reads the
`RequestMappingHandlerMapping` and fails if any handler is mapped outside `/api/**`,
`/actuator/**` or Spring's own `/error`. **New endpoints go under `/api/`**; one that does not
turns the silent hole into a red suite.

## Review round

A `401` no longer signs the user out on the spot. The backend answers `401` for anything it
cannot verify — its own javadoc says an unreachable key server looks the same as a forged token —
so `apiFetch` now refreshes once and retries once, and only a second `401` ends the session. It
also ends it with `scope: 'local'`: `signOut()` defaults to `global`, which revokes every refresh
token the user holds, so one spurious `401` in one tab would have signed them out on their phone
as well.

The sign-in screen says *why* it is showing when a session ends that way. Without it, a backend
that rejects every valid token — a wrong `supabase.audience`, the 2026-08-19 class of bug — is an
unbreakable loop with no diagnostic anywhere in the UI.

Also from the round: `getSession()` and `signInWithPassword()` are guarded, because a browser
that refuses storage makes both reject and left the app on its loader, or the button in its
loading state, for good; the guard test's prefix matching now agrees with the security pattern
(`startsWith("/actuator")` also covered `/actuator-admin`, which the client-route permit makes
public); the stream is aborted on unmount, so signing out mid-answer stops a completion the
backend was still paying for.

**The publishable key is no longer in the source tree** (PR #8 review). It arrives as
`VITE_SUPABASE_ANON_KEY` — `frontend/.env.local` locally, a repository secret in CI, a
`--build-arg` on deploy — and `vite.config.ts` refuses to build without it. It is still in this
branch's history in commit `f87d15b`; rotating it in the Supabase dashboard is what actually
removes it.

## Not verified end to end yet

The suite is green and the frontend builds, but nobody has signed into the deployed application
and asked a question — that is Task 8, and it is the check that matters for this pair of
changes.

---

# Phase 5a — Task 4: server-side conversation history (2026-08-20)

`POST /api/chat` now takes one new user turn and the conversation it belongs to, not the whole
transcript. The server reads the history back from `fpl_message`, so a client can no longer
rewrite what was said, and what was said outlives the browser that said it. Reading it back into
the UI is Task 7 — until then a reload still starts a new conversation.

## The contract

```
POST /api/chat   { "conversationId": null | "<uuid>", "message": "…" }
    event: conversation   "<uuid>"     ← always first
    data: "token"…
    event: done
GET  /api/conversations              → [{ id, title, updatedAt }] newest first
GET  /api/conversations/{id}/messages → [{ role, content, createdAt }] oldest first
```

The conversation id is an event rather than a response header: the client needs it to send the
next turn, and a header is the one part of a streaming response that cannot be assembled from
what the model produced.

**A conversation id that is not the caller's own is `404`.** Not `403` — an id that answers
differently for its owner is an id you can probe for. The store makes this structural rather than
remembered: `findOwned(id, userId)` is the only lookup, so "someone else's" and "does not exist"
are the same empty result.

## The answer outlives the client

Returning the model's own `Flux` ties it to the response: a closed tab cancels the response, the
cancellation propagates upstream, and the answer is neither finished nor stored — after the
tokens have been paid for. The controller subscribes once itself and hands the client a replay of
that subscription, so nothing the client does can cancel the model. A test pins it by cancelling
mid-stream and asserting the finished answer is in the database.

A *failed* stream stores nothing, not even the part that arrived: the client discards a partial
answer when it sees the error event, and history holding text the user was told to ignore is a
worse lie than a turn that is missing.

## The merge the frontend used to do

The user's message is persisted before the model is called and a failed answer is not, so stored
history can hold two user turns in a row — which Anthropic rejects. `ChatService.merged()` joins
consecutive same-role turns. The frontend did this while it owned the history; it owns none now.

## Review round

The detached subscription ended in two blocking JDBC calls **on the provider's event loop**: the
terminal signal arrives on a reactor-netty thread, and Hikari can hold that thread for up to its
30-second connection timeout under load, stalling every other connection multiplexed onto the
same loop. It now hops to `boundedElastic` first. Nothing in the suite could have caught it — a
mocked `Flux.just(...)` completes on the servlet thread.

It also had no bound: nothing else can cancel a subscription that is deliberately nobody's to
cancel, so a provider that stops sending without closing would strand the answer unpersisted and
the sink retained forever. Five minutes, generous enough for a tool-calling answer.

`UUID.fromString(jwt.getSubject())` appeared in three new call sites, none with the guard
`UserProfileController` added a day earlier: a verified token whose `sub` is missing or not a
UUID answered 500 with a stack trace on a path anyone can reach. One `TokenSubject.userId(Jwt)`
now serves all four, and a test pins the 401.

Both reads were unbounded — `messages()` returns whole model answers, and neither endpoint
paginates. Capped at 500 messages and 200 conversations, read from the recent end.

## Not done here

Usage recording and the quota are Task 5, and the conversation list UI is Task 7 — the client
keeps the id in memory for the session and nothing lists past conversations yet.

---

# Phase 5a — Task 5: usage recording and the quota (2026-08-20)

One `fpl_usage_event` row per chat turn, written **when the call starts**. Recording on
completion would make an abandoned stream free to repeat, and the cost is incurred at the start
either way.

The quota is checked **before the stream opens and before anything is written**: once an SSE
response has begun its status is already on the wire and cannot become a `429`, and a rejected
turn that had created a conversation would leave an empty one behind. The answer is a real `429`
with `Retry-After` — seconds until the oldest counted message leaves the seven-day window —
rather than an SSE `error` event.

## The limit is per person

`fpl_user_profile.weekly_message_limit`: `NULL` means the configured default
(`fpl.quota.messages-per-week`, 50), `-1` unlimited, `0` suspended, *n* that many per rolling
seven days. A suspended account gets **no** `Retry-After`: there is no number of seconds that
turns it back on, and a header promising one would be a lie the client would act on.

The window is counted in the database against `now()`, not in this process — a count that shares
a clock with the timestamps it counts cannot drift from them. Only `event_type = 'chat'` counts,
so title generation or a background call cannot quietly spend someone's week.

## Token counts, and what null means

`ChatService` now streams `.chatResponse()` rather than `.content()`, because `.content()`
discards the metadata the token counts live in. Usage is merged field by field across chunks:
providers differ in where the numbers arrive — prompt tokens early, completion tokens on the last
chunk — and keeping only the final chunk would record the answer's size while losing the prompt
that was paid for.

**A provider that reports nothing leaves the columns null, never zero.** A stored zero is
indistinguishable from a real measurement and would drag every average this data exists to
inform. `cached_tokens` comes from `Usage.getCacheReadInputTokens()`; whether it is ever non-null
is exactly what Task 8 checks, since the spec's pricing assumes prompt caching applies.

What no test can settle: a provider reporting *deltas* meant to be summed would be recorded as
the last delta. Task 8 compares a real answer against its recorded row.

## Review round

The `429` promised a return that would not come: a suspended account normally *has* usage inside
the window, so a `Retry-After` could be computed for it — "This account cannot send messages. Try
again in 6 days." Suppressed on the limit rather than on the absence of a timestamp.

`nextFreeAt` was the oldest event plus the window, which is only right when usage equals the
limit. Lower someone's limit and they are over it: the client waits out the oldest event, retries
and is refused again by a margin nobody told it about. It now offsets by how many turns are still
owed.

The `-1` sentinel applied only to the per-user column, never to the configured default — so
`QUOTA_MESSAGES_PER_WEEK=-1`, documented as "unlimited" beside the property, would have locked
out every account without an override with "you have used all -1 messages for this week".

The check and the insert were a read followed by a write with nothing between them: two tabs at
one message remaining both passed. They are now one transaction behind a per-user advisory lock,
which serialises one person's own requests and nobody else's.

**A failed stream refunded even when it had burned tokens** — found by the second review round,
after the first one introduced the refund. `ChatService` reported usage from `doOnComplete`,
which never fires on an error or on the five-minute timeout, so the controller always saw "no
usage reported" and always discarded the row: a tool-calling turn that spent 30k tokens and then
lost the connection was free, and free to repeat. Usage is now reported as each chunk carries it,
so a failure can see what was already spent.

**Spring AI's `EmptyUsage` reports zero prompt and completion tokens on every chunk**, and some
providers stamp the model name on every chunk too. Together those passed the "did this chunk
measure anything" guard and wrote zeros into every row — the one thing null exists to
distinguish. Zero prompt/completion counts are now treated as unreported; the cache figures keep
their zeros, because they are null when absent and a zero there is a genuine cache miss.

**A stream that failed used to keep its charge.** A provider outage would have spent every user's
weekly allowance on answers nobody received. A failure that reported no usage now discards the
row; one that reported usage did consume tokens and keeps it.

A reported *zero* is now kept rather than discarded. A cache miss reports zero cache reads, and
"the cache did not hit" has to be distinguishable from "the provider said nothing" — that
distinction is the measurement Task 8 exists to make. Merging takes the largest value seen per
field, which is correct for the cumulative usage both providers report and immune to a trailing
empty chunk erasing the totals.

## Token accounting per turn, per user, per message (owner request)

`fpl_usage_event` gained `conversation_id`, `model`, `cache_write_tokens` and a generated
`total_tokens`. Queries in `docs/DEVELOPMENT.md`.

**No money is recorded.** A first pass stored a USD figure priced at the moment of the turn; the
owner rejected it as complexity that buys nothing — a price depends on the model, the date and
whatever promotion is running, so the stored number is wrong as soon as any of those move. The
question worth answering is how many tokens a message spent, and that is what the table now says.

The cache-write column stays, and it is the one that was actually missing: **Anthropic reports
`input_tokens` as the uncached remainder**, with reads and writes counted separately. A "total"
computed from input and output alone is an undercount whenever caching is on. `total_tokens` is
generated by the database — `input + cached + cache_write + output` — so no code path can forget
it and no two callers can disagree about the sum. A NULL column contributes nothing: unreported is
not zero, but it is not a number to add either.

Open, and worth checking before anyone switches provider: if OpenAI counts cached tokens inside
its prompt total, `total_tokens` double-counts them. Anthropic does not, and Anthropic is what
runs.

`conversation_id` carries **no foreign key and no cascade**, which is the point of it dangling:
usage counted from something the user can delete is a quota the user can refill by deleting a
conversation. A test pins that deleting the conversation leaves the usage row standing.

## Also

`GET /api/usage` returns used, limit and when the window next frees, for Task 7's indicator. It
does **not** report token counts — those are the owner's numbers, read with SQL. The
frontend now reads the `429` body and `Retry-After` instead of always saying "too quickly" —
there are two different `429`s now, and telling someone who has spent their week to wait a minute
is worse than saying nothing.


---

# Full conversation history (2026-08-20)

Owner decision: continuing a conversation sends **all** of it, not a window of the last 20 turns.
The cost of resending the whole thing every turn is accepted and will be watched — the number to
watch is `input_tokens` in `fpl_usage_event`.

`HistoryBudget` is what remains of the old window, and it is not a cost control. A request larger
than the model's context window is rejected outright, and a conversation past that point would
fail on **every** turn from then on, permanently: dropping its oldest turns is the lesser loss.
The budget is 800k characters — roughly 200k tokens at four characters each, well inside
claude-sonnet-5's window and far beyond anything this application has seen. The test profile pins
it at 400 so the suite can reach it without seeding a novel.

**When turns are dropped, the user is told.** The stream carries a `truncated` event with the
count, and the frontend renders it as a `Note` turn: the oldest messages are still on screen and
still theirs, but the assistant did not see them. An answer written without the beginning of a
conversation must not look like one written with it.

## Review round

The 500-message ceiling on the history read made the notice a half-truth: a conversation of 600
turns whose newest 500 fit the budget dropped 100 with nobody told, because "dropped" was counted
against what the read returned. It is now counted against `countTurns` — every turn the
conversation holds.

The leading-assistant fix-up moved into `HistoryBudget`. Downstream it made the number shown to
the user one too low: `ChatService` dropped a turn after the count had been taken.

A `TokenUsage` carrying only a model name is no longer treated as usage. Providers stamp the
model on chunks that measured nothing, so a stream that died before reporting any count kept its
charge — a message out of someone's week for a turn that produced nothing.

The claim happens before the conversation is written, and those writes were unguarded: a Hikari
timeout in `conversations.create` took a message out of the user's allowance for a turn that
never reached the provider. They now discard the charge and rethrow.

`Retry-After` is now in `exposedHeaders`. It is not CORS-safelisted, so a split-origin deployment
would have shown "you are out" without ever showing when it frees. Invisible today because the
Vite proxy and production are both same-origin.

Not changed: the reviewer read the 800k-character budget as equal to the context window. It is
about 200k tokens against claude-sonnet-5's 1M — the headroom is what the system prompt, the tool
schemas, the tool results and the reserved output live in, and that reasoning is now in the
class's javadoc rather than only in someone's head.

Two things that made this less obvious than it looks:

- The trimming lives in `HistoryBudget`, not `ChatService`, because `ChatController` is the one
  that has to know how many turns were dropped — and in the controller tests `ChatService` is a
  mock, so asking it would have measured the mock rather than the history.
- Server-side compaction (the Anthropic beta) was considered and rejected for now: it requires
  echoing the API's compaction blocks back on every turn, and this application stores plain text
  per message and speaks to the model through Spring AI, which does not surface them. Summarising
  the oldest turns ourselves is the shape that would fit — when the measured numbers say it is
  needed, and not before.


---

# Phase 5a — Task 7: conversations, usage and settings in the UI (2026-08-21)

The client reads its own history back at last. A left sidebar lists the caller's conversations,
opening one loads its transcript, and the thread continues it — the endpoints have existed since
Task 4 and nothing called them, so a reload started from nothing while the server remembered
everything.

## Shape, as the owner specified it

A hamburger menu in the top bar holds Usage, Settings, the theme and Sign out. There is no
"Sign in" item and there cannot be: the menu lives inside the signed-in shell, so anyone who can
open it is already signed in. Conversations are a left sidebar, the shape every chat product
uses — below 640px an overlay opened from the header, because 260px of chrome beside a 375px
screen would leave the thread with less than half the reading measure the design spec sets.

## The quota stops being a number (issue #11)

`GET /api/usage` answers `remainingPercent` / `unlimited` / `nextFreeAt`. No count used, no limit,
nothing to work the limit back out from. The percentage rounds **down**, but never to zero while a
message can still be sent — an indicator that reads empty before the door closes is worse than no
indicator — and reads exactly zero once the week is spent. The `429` says "You have used this
week's messages", naming nothing.

`Retry-After` stayed. It names no count, and without it someone who has run out has no idea when
they can continue. A suspended account still gets no header and no time on the page: no number of
seconds turns it back on.

## Routing without a router

Three top-level views, no route parameters — which conversation is open is state, not an address,
because it is chosen from the list beside it. Thirty lines of `pushState` + `popstate` instead of
a routing library answering questions this application does not ask. Task 6's
`SpaForwardingController` already makes `/usage` and `/settings` load on a deep link.

## Settings

One field: the FPL team id. Task 3's two failure modes stay apart in the UI, because they send the
user to different places — "FPL does not know that team" means look for a typo, "we could not
reach FPL" means the number may be perfectly good and nothing was saved. The field says outright
that nothing consumes it yet; an unexplained field that changes nothing reads as broken.

## Review round

**The provider is OpenAI, not Anthropic** (`SPRING_AI_PROVIDER=openai`), and that made the token
totals wrong. OpenAI folds cached tokens into `prompt_tokens` and reports them again under
`prompt_tokens_details`; Anthropic reports the uncached remainder with cache reads beside it.
Spring AI hands both to us in the same two fields — verified in `OpenAiChatModel.getDefaultUsage`,
which maps `promptTokensDetails().cachedTokens()` straight into the cache-read slot — so
`total_tokens` was counting the cached part twice on every turn. `ChatService` now normalises to
the uncached remainder whichever provider answered, and a test pins both.

Switching conversations mid-stream spliced the running answer into the wrong transcript: the
sidebar was never disabled, so the terminal `setMessages` appended conversation A's answer under
conversation B, and a reload made it vanish — the server had stored it against A all along.
`replaceMessages` now ends the stream as it swaps the thread, and a selection counter drops the
slower of two racing clicks.

The sidebar renders on every page but only mutated chat state, so clicking a conversation from
Settings closed the sidebar and appeared to do nothing. It navigates now.

The mobile overlay could not be closed: it painted over the button that opened it, with no
backdrop and no Escape. Both added. It also stayed in the tab order while translated off-screen —
`visibility` handles that, transitioned so it goes after the slide rather than during it.

Settings sent `NaN` as `null` for non-numeric input and reported the server's "field required" 400
as "FPL does not know that team", sending the user to look for an id they never transmitted.
Digits are checked before sending, and only a real 400 gets the unknown-team message; 401 says
nothing, because the session has already ended by then. A failed profile read no longer looks like
an empty field, which read as "nothing saved".

The usage page announced when a message "frees up" to someone at 70% remaining, which reads as
being blocked. Gated on nothing being left.

## Also

`--text-lg` and `--text-display` were added to `tokens.css`, which invites exactly that when a
screen needs a step. `--text-lg` was already referenced by the sign-in card's title before it
existed, which made that rule invalid and the size silently inherited.


---

# The assistant answered about a player nobody asked for (2026-08-21)

Asked "konsa vai calafiori?", the assistant compared **Konaté** with Calafiori. Reproduced against
the live application on the first try, so not a one-off.

## What actually happened

Not RAG, and not missing data. `fpl_daily_price` holds Konsa for 2026-27 and the tapas `player`
table holds him for 2025/26 — a lookup for "Konsa" would have worked. The model never made one:
it read an unfamiliar lowercase name as a typo, searched for "Konate" instead, got a real 2025/26
row back (Konaté played in the Premier League that season), and answered confidently about him.
Nothing in the reply looked wrong, which is what makes this the worst shape of error the assistant
can make.

Nothing stopped it. The system prompt said to use tools and attribute seasons; it never said the
player's name is the user's, not the model's.

## Fixed

- A **"Player names are the user's, not yours"** section in the system prompt: search the exact
  spelling, never correct a name into one that looks similar, say which player was matched when it
  differs from what was asked, and never quietly answer about someone else.
- `getPlayerStats`'s tool description says the same at the point where the argument is chosen.
- Its not-found reply now instructs rather than reports: "do not search for a similar-looking name
  or answer about a different player" — the failure mode was the model trying another spelling
  until something matched.

Verified against the live application after the change: the same question now answers for
**Konsa**, with the 2025/26 numbers attributed to that season and current availability from the
daily capture.

## Then fixed properly: this season's squad is the first place to look

The prompt rules stop the model from renaming a player. They do not fix why the wrong name
*worked*: `getPlayerStats` resolved names only through the tapas `player` table, which holds 841
rows for 2025/26 and **none at all for 2026-27**, so `SeasonResolver` falls back to last season and
every lookup answers from the one season where a departed player still exists.

We do have this season's squad — `fpl_daily_price`, captured daily from FPL's own API, with
`web_name`, `full_name`, club, position, price, ownership and availability. `getPlayerStats` now
reads it first (through `DailyPriceSource.matchPlayers`, the same ranked matcher the price and
availability tools use) and adds the completed season's statistics underneath, attributed. A name
that is **not** in this season's squad gets an explicit "NOT IN THIS SEASON'S FPL GAME" before
anything else — which is what turns a wrong guess from a plausible answer into a visible dead end.

Verified live after the change:

- "konsa vai calafiori?" answers for Konsa (£4.5m, available) and Calafiori (£5.5m), with the
  2025/26 numbers named as last season's.
- "onko konate hyvä ostos?" answers that no player by that name is in FPL's data and asks for the
  club or a fuller spelling, instead of answering about him.

## Also found while looking

The conversation sidebar read the list once on mount with no error handling, so a backend that was
down at that moment left the sidebar empty for the rest of the session — indistinguishable from
having no conversations, and with nothing to retry. It now says the list could not be loaded and
offers to try again.


---

# Phase 5a complete — verified against the running application (2026-08-21)

Eight tasks, eight merged pull requests, deployed. What exists now: Supabase JWT authentication,
per-user profiles with an FPL team id, server-side conversation history, a rolling seven-day
message quota with token accounting, and a frontend that signs in, lists conversations, and shows
what is left of the week.

## What was checked, and how

Through the running application against the production database, not through the suite:

| Check | Result |
|---|---|
| Sign in, send, answer, sign out | Owner-verified in the browser |
| Conversations listed, reopened, continued | Owner-verified; API returns the transcript |
| One account cannot see another's | Owner-verified; unknown id answers `404` on both the read and the chat endpoint |
| Every API path without a token | `401` on `/api/conversations`, `/api/usage`, `/api/profile` |
| Quota exhausted | `429`, `Retry-After: 601036` (6.95 days), matching `nextFreeAt`; the body names no number |
| Usage endpoint at the limit | `remainingPercent: 0`, `unlimited: false` |
| Token accounting from real answers | provider `openai`, model `gpt-5.6-terra`, `total_tokens` equal to the sum of its parts |

**Prompt caching is real, and now measured.** Every turn reads 4,300–4,600 tokens from cache
against 2,400–3,800 uncached — the system prompt and the tool schemas. The Phase 5 spec's pricing
assumed this and nothing had confirmed it; the `cached_tokens` column now says so from live data.
`cache_write_tokens` stays null because OpenAI neither charges for nor reports cache writes, which
is the difference from Anthropic the capture code normalises.

The quota check temporarily set the owner's `weekly_message_limit` to 1 and restored it to `NULL`
afterwards. `NULL` is the configured default, which is what it was.

## What this phase deliberately leaves undone

- **No billing, and therefore no identity anchor.** Every account gets free LLM usage bounded only
  by the quota, so public sign-up stays closed and accounts are created by hand. Stripe was the
  spec's abuse defence, and it arrives in 5b.
- No account deletion and no data export.
- No entitlement seam. Phase 5b's check goes where the quota check goes — before the stream opens.
- `getPlayerStats` reads this season's squad from the daily capture, but there are still **no
  2026-27 rows in the tapas `player` table**, so per-season statistics remain last season's until
  tapas syncs. The tools say which season they are quoting.

---

# Full-phase review of 5a (2026-08-21)

Six findings against the whole phase (`01fd706..master`, 86 files). All fixed.

**The serious one was introduced by the fix before it.** With no daily capture at all,
`getPlayerStats` answered every lookup with "NOT IN THIS SEASON'S FPL GAME" — a confident
falsehood on a fresh deploy, and the shape of error the player-name work existed to remove. Worse,
the test written for that work ran against an empty capture table, so it *pinned* the broken state
instead of catching it. Three outcomes now: no capture means the squad list is unavailable and no
claim is made; a capture that does not hold the name means the player is not in the game; a match
answers from this season.

`jwks-uri` was not getting the trailing-slash repair the issuer already had, though both are
derived from `SUPABASE_URL` — the value the dashboard shows with a slash on the end. A doubled
slash in the key-set path is routed by nothing, the fetch fails, and every user gets a 401
distinguishable from forged-token traffic only by a WARN line.

`FplEntryLookup` folded every non-2xx into NOT_FOUND, so a redirect — which is what FPL answers if
the configured URL loses its trailing slash — told users their valid team id does not exist. Only
a 404 says that now.

Token usage merged field by field across chunks, which could assemble a row from two of them: on
the OpenAI path an un-normalised prompt beside a populated cache count, and the generated total
counting cached tokens twice. The richest chunk is now kept whole. The test that documented
field-by-field merging described a shape neither provider produces — both report cumulatively —
so it was rewritten rather than worked around.

`updated_at` was bumped only when an answer was stored, so a conversation whose answer failed
stayed where it was in a list ordered by last use — the conversation the user had just written to
was the hardest to find. The touch moved to the user's turn.


---

# Early-season data: dual-season player card + football-data guard (2026-08-22)

Plan: `docs/superpowers/plans/2026-08-21-early-season-data.md`. Branch `early-season-data`,
PR pending. The review that started this: the prompt carried no question-type routing, no
multi-lookup rule, and no early-season weighting — and the tools could not have honoured the
weighting anyway: the day tapas syncs the new season, `SeasonResolver` flips and last
season's evidence would have vanished from every tool.

## Prompt (applied directly, `system-prompt.st`)

Two new sections. **"How to answer: pick the right knowledge first"** routes by question type:
rules/scoring/chips answered from the prompt's static sections with no tool calls; plain
statistics from the stats tools only; strategy concepts from `searchStrategyDocs`; advice from
both; club records from the historical tools — plus the multi-lookup rule (a transfer or
captaincy answer checks stats, today's price, availability, underlying numbers and fixtures
before answering). **"Early in the season: last season is the evidence"**: before GW5 lean on
last season and say so; from GW5 this season alone, for a player who has actually played — a
player with no 2026/27 minutes is still judged on last season, said explicitly. (The owner's
GW5 switch replaced the first draft's GW5→GW10 graduation.)

## Task 1 — `getPlayerStats` shows both seasons after the flip

When the resolver returns the current season, the reply now carries that season's aggregates
AND the previous season's beneath it, each under its own provenance line
(`SeasonContext.previousSeasonProvenance` — the fallback text's "has no data yet" would be
false there). `SeasonResolver.previousSeason` finds the earlier season by start_date. While
the resolver is on the fallback (production today), output shape is unchanged. The no-capture
and not-in-this-season's-game branches keep leading with their explicit statements; their
trailing line is now season-neutral ("the statistics below name the season each figure is
from"). Tested via a simulated flip: seeding one gameweek + one player into the current season
makes the real resolver pick it, and both season blocks appear, each attributed.

## Task 2 — football-data sync writes the genuinely current season

The precedence guard keyed on `SeasonContext.currentSeason()`, which is false for the whole
changeover — so `pl_match_result` would have stayed empty for 2026-27 until tapas synced, and
`getTeamForm` would have reported May 2026 as the freshest match well into autumn. The season
written is now derived from the response itself (every match's `season.startDate` → the DB's
`season.code` format, fail-loud when missing) and written only when it equals the database's
`is_current` code. The summer case keeps its protection: an API response still serving the
just-finished season mismatches `is_current` and is skipped whole. vaastav precedence is now
structural (completed seasons are never `is_current`; a later backfill upserts the same
natural key). The mocked-resolver precedence test changed meaning accordingly: "resolver on
fallback" now writes, "response season ≠ is_current" skips. Captured-response test pins all
380 matches deriving `2026-27`.

## Verification

Full suite green against the disposable container: 309 tests, 0 failures, including the two
simulated-flip tests and both guard branches. **Live smoke is pending the merge-deploy** (the
pipeline deploys from master, and booting a branch against production would fire the startup
syncs into a database this branch has no business writing). What to check after deploy, once
GW1 results exist: a form question surfaces 2026-27 results with the season named; a player
question still answers from 2025/26 (the flip has not happened — tapas has synced nothing);
after tapas syncs, the same question shows both seasons side by side.

## Still open

- No current-season fixtures, deadlines or gameweek info until tapas syncs —
  `GameweekTools`/`FixtureTools` have no fallback-independent source. The plan's deferred list
  names the bootstrap-static capture as the separate decision that would close it.
- `PickTools`/`ExpectedStatsTools` still rank on the resolved season alone — after the flip
  that is a 1–2-match sample. The prompt's weighting rule plus the dual-season player card are
  the mitigation; blending belongs to the later season-weighting work.

# PR #17 review: thirteen findings fixed (2026-08-22)

A full review of the early-season branch before merge. Thirteen findings, all fixed on the
branch. Suite: 315 tests, 0 failures against the disposable container (309 before, six new).

## The write guard was only as wide as the first match

`FootballDataSyncService` read `matches.getFirst().seasonCode()`, checked *that* against the
flagged-current season, then stamped it on every row it wrote — so a response carrying two
seasons (a rollover, a filtered fetch) would have written last season's results under this
season's key, overwriting vaastav on the shared natural key `(season, home_team_code,
away_team_code)`. That is the corruption the guard exists to prevent, reached through the guard
itself. The check is now per match, each row stamped with its own season, and a match the
parser could not date is skipped rather than written under a guess.

Related, same file: seasons are compared on their **start year**, not by string equality. The
two sides come from different owners — `season.code` is tapas', the payload's code is derived
from `season.startDate` — and any format drift (`2026/27`, `2026-2027`, a prefix) would have
turned the sync into a permanent silent no-op that logs at INFO. That is the same
weeks-of-stale-results bug this branch was written to fix, only harder to see, so the skip path
now logs at WARN and one integration test runs the **real** resolver against a real `season`
row rather than hand-writing both sides.

`FootballDataResponseParser` no longer throws on a match with no usable `season.startDate`: it
returns a null season for that match and the sync skips it. One rescheduled fixture or upstream
schema tweak was aborting all 380.

The pre-fetch guard removed in the previous commit came back in a form that cannot reintroduce
the original bug: nothing is fetched *before the flagged season's start date*. The old guard
keyed on `SeasonResolver`, which is exactly what kept the current season unwritten. Without
some pre-fetch guard, every Fly cold start spent an API request on a response that was then
discarded — and a skipped sync writes nothing, so `StartupSyncRunner`'s staleness marker never
advanced and the next start fetched again.

## The player card contradicted itself, and repeated itself

Three fixes in `PlayerTools`:

- The "NOT IN THIS SEASON'S FPL GAME — they cannot pick this player" branch could, after the
  flip, be followed by that same season's statistics: a name in tapas' current-season table but
  missing from today's capture (a de-registration, a capture that ran early, a spelling the
  daily match misses). The reply now distinguishes the two: absent from both is still an
  absence; present in one and not the other is "NOT CONFIRMED", with the disagreement named.
- The fallback provenance paragraph was printed twice — once as the banner, once as the block
  header the dual-season card added. The banner is gone from those branches; every block
  carries its own line. (`AGENTS.md`'s stats-tool convention now says so explicitly.)
- `previousSeasonProvenance` asserted "the current season is under way" even when the current
  season contributed no block, implying a comparison the card could not make. It is now an
  instance method taking whether the current season's block is actually beside it.

`SeasonStats` no longer stores a rendered provenance string next to the `SeasonContext` it was
rendered from — a block whose header names one season while its price is labelled with another
would have compiled cleanly. And both seasons' rows now come back in one query
(`ROW_NUMBER() OVER (PARTITION BY season_id)`, five per season) instead of two round-trips on
the chat hot path.

`SeasonResolver.previousSeason` skips seasons with no player rows, the same discipline
`resolve()` already applies — otherwise an unsynced season row sitting between the current one
and the last populated one drops last season's evidence off the card without a word.

## The GW5 rule had no way to know the gameweek

The prompt's early-season switch turned on a number nothing told the model how to obtain, and
the only tool that could — `getGameweekInfo` — reports *last* season's GW38 during exactly the
window the rule targets (a finished season keeps its last gameweek flagged `is_current`). The
rule would have misfired in the wrong direction: "past GW5, use this season's numbers alone",
in August. The prompt now routes "which gameweek is it" to the stats tools and tells the model
to read the tool's own "Data source" line first: a gameweek from a completed season means this
season has no gameweek data yet, which is *before* GW5, never past it.

## Test isolation

`PlayerToolsTest` was deleting every `player`/`gameweek`/`team` row for the tapas-owned current
season after every test, whether or not that test flipped — teardown reaching well beyond what
it created, working only because `TapasSeed` happens not to seed that season today. It now
deletes its own rows by id, and runs `@BeforeEach` as well, so an aborted run does not leave
the flip in place for the next one.

---

# Free tier: 20 messages a week, behind an invite code (2026-08-22)

Spec: `docs/superpowers/specs/2026-08-22-free-tier-design.md`.
Plan: `docs/superpowers/plans/2026-08-22-free-tier.md`.

A phase inserted between 5a (auth, done) and 5b (Stripe, not started). Before billing exists we
want testers using the assistant, and the only way in was an account created by hand in the
Supabase dashboard. This opens a permanent free tier — **20 messages per rolling seven days** —
reached through a sign-up form gated by a shared invite code with a hard account cap. Free stays
a real tier after Stripe ships; paid is the upgrade from it.

Almost none of the metering is new. Phase 5a's rolling seven-day count, its per-user advisory
lock and its `weekly_message_limit` override are unchanged. What changed is the number the
default resolves to, who can get an account, and what the user is told.

## The plan is derived, never stored

`Plan` is an enum with one constant, resolved in `UsageService.planOf` and carried on `Quota`. No
`plan` column and no subscription row: a column would be a second cache of Stripe's state sitting
beside 5b's own cache, and two caches of one truth drift — as a billing bug. 5b adds `PAID`, one
branch in `planOf`, and one arm in `limitFor`.

The enum exists because free and paid say different things. Issue #11 settled that `/api/usage`
returns a share and the `429` names no number; **that now holds for paid accounts only.** A free
tier's limit is the offer — it is on the marketing page either way, and hiding it only makes the
product look evasive about the one figure a prospective user needs. The paid limit stays the
owner's business: it is a cost-control number to be tuned from usage data. Both the response and
the exception branch on the same `Plan` so they cannot disagree.

## Sign-up is a backend call, because a browser one cannot be gated

`supabase.auth.signUp` is a client call anyone can make without our form, so a code checked in
the browser is decoration. Supabase's public sign-up stays off; `POST /api/auth/signup` creates
the account itself with the service-role key. It is the one `permitAll` route under `/api/**`
(POST only — a GET on the same path still answers `401`) and is rate limited per IP like the
other endpoints that cost something outside this process.

`403` bad code, `409` cap reached, `422` Supabase refused the address or the password, `503`
Supabase could not be asked — the last two kept apart for the reason `FplEntryLookup` already
keeps them apart: telling someone their password was rejected while the auth server is down sends
them rewriting a password that was fine.

## The failure direction at sign-up, chosen deliberately

One transaction cannot span Postgres and Supabase, so the only real choice is which way the pair
fails when it comes apart. The slot is reserved in `fpl_invite_redemption` **and committed**
before Supabase is called. Counting afterwards would fail toward undercounting — an account
nobody counted, and a cap drifting upward with every failure, silently. Reserving first fails
toward overcounting: a Supabase error leaves a committed row with a null `user_id`, burning one
slot. That is a visible row, released by hand with
`DELETE FROM fpl_invite_redemption WHERE user_id IS NULL`, and it errs toward refusing one person
too many rather than letting the tier grow past its ceiling with no trace. `user_id` is therefore
nullable and not the primary key.

The advisory lock is keyed on a constant rather than the user: the cap is a global count, so
every sign-up serialises against every other. That would be wrong for chat and is right here,
where total volume is a few dozen rows for the lifetime of the tier.

`code_label` holds the first eight hex characters of the code's SHA-256, not the code — enough to
tell one code from another after a rotation, and a ledger row is not a secret store.

## Configuration

| Property | Env | Default |
|---|---|---|
| `fpl.quota.free-messages-per-week` | `QUOTA_FREE_MESSAGES_PER_WEEK` | 20 (was `fpl.quota.messages-per-week`, 50) |
| `fpl.invite.code` | `INVITE_CODE` | none — unset refuses every sign-up |
| `fpl.invite.max-accounts` | `INVITE_MAX_ACCOUNTS` | 30 |
| `supabase.service-role-key` | `SUPABASE_SERVICE_ROLE_KEY` | empty — unset closes sign-up with `503` |

`QUOTA_MESSAGES_PER_WEEK` was never set as a Fly secret (verified 2026-08-22), so the rename had
nothing to reconcile. **The two new secrets are not set either: sign-up answers `503` until
`SUPABASE_SERVICE_ROLE_KEY` and `INVITE_CODE` are set with `fly secrets set`.** Both defaults
close the door rather than opening it, which is the point.

At the measured €0.0146 per message, 20 a week is about €1.27 a month per user who maxes the
tier, so the 30-account cap is a €38/month ceiling — and a ceiling, not an expectation.

## Left undone on purpose

- **Email verification.** `email_confirm: true` marks the address confirmed without sending
  anything, defensible only while the invite code and the cap are the gate. It stops being
  defensible the day the code comes off — a permanent free tier reachable without a code and
  without a verified address is refillable by anyone willing to register a new address. In
  `docs/TODO.md`.
- **Email enumeration is accepted**: an address that already exists answers differently. A fake
  success is worse for a real person mistyping their own address, and an attacker must already
  hold a valid code to ask the question.
- No per-invite single-use codes, no admin UI, no upgrade affordance. There is nothing to click
  until Stripe can take money, and a dead control for several weeks is worse than an honest
  absence.

## What this hands to Phase 5b

Two corrections to `2026-08-15-phase5-auth-billing-design.md`, which assumed a card at signup and
no free tier:

- A cancelled or lapsed subscriber **falls back to the free tier rather than meeting a wall**.
  That spec's `402` becomes a downgrade, not a door — it changes what the webhook does on
  `customer.subscription.deleted` and what the frontend shows for a payment problem.
- Checkout starts from an account that already exists and has no card on file: an upgrade flow
  for a signed-in user, not the signup flow that spec describes. Its abuse note weakens too —
  with a free tier, deleting an account and signing up again already yields a fresh 20 a week, so
  the card anchors the paid tier only.

## Note

`SupabaseAdminClient` repeats the doubled-slash repair on `SUPABASE_URL` inline. PR #17
(`early-season-data`, open at the time of writing) extracts the same regex as
`SecurityConfig.collapseDoubledSlash`; once that merges the two should become one call.

---

# Status — My-team-aware advice (2026-08-25)

Spec: `docs/superpowers/specs/2026-08-23-my-team-advice-design.md`.
Plan: `docs/superpowers/plans/2026-08-23-my-team-advice.md` (13 tasks, all done).

Three tools that answer from the user's own FPL squad: `getMyTeam`, `getMyMiniLeagues`,
`getMiniLeagueOpponents`. Everything is fetched at chat time from FPL's public API, filled in
from a cached `bootstrap-static`, and rendered as text like every other tool.

## How a tool knows whose team it is

The user id travels from the JWT to the tools in Spring AI's `ToolContext`
(`ChatController` → `ChatService.stream(turns, userId, onUsage)` → `.toolContext(...)`), never
as a tool parameter. Anything in a tool's schema is filled in by the model, and the model's
input is the user's own text — a `userId` parameter is a working request for somebody else's
squad the moment a user types one. `MyTeamToolsTest` asserts the generated schema carries no
identifier at all, which is the assertion the rest of the feature's safety rests on.
`SecurityContextHolder` is not an option: tool calls run on the provider's reactive threads.

The same context carries an `FplBudget` — fifteen FPL calls for the turn, created per request
because a counter on a singleton service would be shared by every conversation on the machine.

## What the public API does not have, and what changed because of it

Verified against the live API on 2026-08-25 (2026/27, GW1 current):

- **No selling or purchase prices.** They are behind `/api/my-team/{entry}/`, which needs the
  manager's own session. The card reports bank and current prices and says funds are
  approximate rather than implying a transfer is exactly affordable.
- **Only a gameweek whose deadline has passed can be fetched** — `event/2/picks/` answers 404
  while GW1 is current. So the squad is always the one locked at the last deadline; the card
  says which gameweek it describes, and the prompt tells the model to believe a user who says
  they have transferred since.
- **Chips remaining are not published.** They are derived from `bootstrap-static.chips` minus
  `entry/{id}/history/.chips`. The windows are per chip and there are two sets a season: in
  2026/27 the wildcard's first window is GW2–19 while the bench boost's is GW1–19, because
  transfers are unlimited until the GW1 deadline and a wildcard would buy nothing.
  **The chip list answers for the next deadline, not for the gameweek being played** — a
  manager sitting in GW1 can activate a wildcard, and it is played on GW2. The first cut of
  this compared the windows against `current_event` and told a GW1 manager the wildcard was
  not available, which is a true statement about the wrong question. `FplBootstrap.Snapshot`
  now carries `nextEvent` (FPL's `is_next`) and the card says "Playable at the next deadline
  (GW2)".

## Why bootstrap-static is fetched at chat time

`element` ids are season-scoped and FPL renumbers every player every season. Filling a squad
from a database that has not synced the new season yet would name *different players*, silently.
`bootstrap-static` is the one source certain to be on the same season as the picks, so identity,
price, form, ownership and availability come from it (one call, cached six hours, app-wide), and
Supabase adds only what FPL does not publish — next-five fixture difficulty and xG/xA — and only
while `SeasonResolver` names the season being played. Across the changeover the card says the
deeper numbers are not available yet instead of joining the wrong rows.

## Deviations from the plan

- **The standings endpoint lives in `FplEntryClient`, not a separate `FplMiniLeagueClient`.** One
  place owns the FPL timeouts, the three-way result and the "never log the URI" rule; a second
  client would have copied all three to add one method.
- **`fpl_daily_price` is not used for enrichment.** The plan had it supplying today's price and
  flags, but `bootstrap-static` already carries them live and is fetched anyway — two sources for
  one number is how they drift apart.
- **A cold `bootstrap-static` makes the budgeted turn fifteen calls rather than fourteen.** The
  plan's arithmetic assumed the app-wide snapshot was warm, which it is after the first
  conversation on a machine. Both fit; `MiniLeagueServiceTest` pins the warm number.
- **`nearby` is bounded by the page it fetched.** Five either side, minus whatever the page
  boundary cuts off — a user at rank 105 gets four above and five below rather than a second
  page fetched to even it up.

## Cost and limits

Ten opponent squads and fifteen FPL calls per turn, both enforced in the services rather than
left to the tool descriptions. `focus="top50"` is accepted, clamped, and says it was clamped, so
the model cannot claim to have read the whole league. Opponents are reported as the difference
against the user's own squad — two lines each instead of fifteen — because the reply re-enters
the prompt on every later turn and the free tier meters messages, not tokens.

Managers' real names (`player_name` in the standings) are never read: they belong to third
parties who never used this product, and everything here is sent to a model provider.

## Left undone on purpose

- Live gameweek points (`/api/event/{event}/live/`), a "my team" page, and head-to-head leagues.
- No persistence: squads and standings live in in-process TTL maps, keyed by FPL entry or league
  id rather than by user, so editing a stored team id cannot serve the previous team's squad.
