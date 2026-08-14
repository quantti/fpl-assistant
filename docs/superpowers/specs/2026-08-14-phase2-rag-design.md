# FPL Assistant — Phase 2: RAG Design Spec

**Date:** 2026-08-14 (revised same day after architectural review)
**Status:** Approved

**Supersedes** these parts of `docs/superpowers/specs/2026-06-27-fpl-assistant-design.md`:
- the "RAG knowledge base" section (`:131-144`) — per-message retrieval replaced by tool-based retrieval
- the data-flow step "Spring Boot builds prompt: system prompt + RAG retrieval on user query" (`:50`)
- the Phase 2 bullet "RAG retrieval injected into prompt" (`:306`)
- `rag/VectorStoreConfig.java` in the project structure (`:244`) — named `RagConfig` here; either name is acceptable

---

## Idea

Phase 1 delivered two of the three knowledge layers: FPL rules in the system prompt, and live/historical stats through `GameweekTools`/`PlayerTools`/`FixtureTools`. The third — strategy knowledge — exists nowhere. "How many goals has Haaland scored" and "who should I captain" currently get the same treatment: the second one is answered from the model's training-time priors, unsourced and uncheckable.

Phase 2 adds that layer as a **tool** the model calls deliberately, not as context injected into every prompt. Stat questions stay cheap and unpolluted; advice questions get sourced answers from a corpus the project controls.

## Why tool-based, not per-message RAG

The original design injected the top-3 similarity matches into every prompt regardless of question type. Two problems: it spends a retrieval on every stat question that never needed strategy content, and similarity search always returns *something* — there is no natural "nothing relevant here" outcome, so irrelevant chunks get injected into stat answers.

A `@Tool` follows the pattern the three existing tools already established. This is architecturally free: Phase 1's `FplTool` marker + `List<FplTool>` injection (`ChatService` passes `tools.toArray()`) means a new tool class joins the roster with zero changes to `ChatService`, `ChatController`, `AiConfig`, or the SSE contract.

### Corpus growth, and why that settles the approach

The corpus starts small — seven documents, ~9,300 tokens. At *that* size a tool returning one or two whole documents by topic would beat chunk retrieval outright: better answers, no embedding latency, no ingestion pipeline.

It will not stay that size. The intent is to add documents throughout the season — press pieces, social-media analysis, community takes — potentially several per week. Over a 38-gameweek season that is a corpus one to two orders of magnitude larger than today's, which is squarely retrieval territory. Embeddings are the right call for where this is going, not for where it is.

Two consequences follow, and the second one is the important one.

**Re-embedding stays cheap.** Even a few hundred documents is well under a dollar to re-embed at `text-embedding-3-small` pricing, so truncate-and-reload (below) remains viable far past the current size. Revisit it if ingestion time becomes annoying, not on cost grounds.

**Document freshness becomes a correctness problem.** Evergreen strategy reference and a dated news article are different kinds of content. An injury report from GW3 retrieved in GW20 is not merely stale — it is actively wrong, and the model has no way to know unless the chunk says so. This is the same failure Phase 1 spent real effort preventing for database rows (`SeasonResolver`, `SeasonContext.provenance()`); RAG must not reintroduce it through the back door. Hence the metadata requirements below, which are cheap to build in now and expensive to retrofit once dozens of undated documents exist.

## System design

### New components

```
backend/src/main/java/fi/karivanttinen/fplassistant/rag/
├── RagConfig.java        # VectorStore bean config (may be pure application.yml + no class)
├── IngestionRunner.java  # ApplicationRunner behind an --ingest flag
└── StrategyTools.java    # @Tool searchStrategyDocs(query), implements FplTool
```

- **Vector store:** Spring AI's `PgVectorStore`, not hand-written `JdbcTemplate` SQL. This departs from the existing tools' style deliberately: embedding, chunk storage, and cosine search are exactly what the abstraction exists for. The existing stat tools stay as they are.
- **New table:** `fpl_embeddings` in the shared Supabase project. Same "new tables only, never touch tapas tables" rule as everywhere else.
- **Embedding provider is always OpenAI**, regardless of `SPRING_AI_PROVIDER` — Anthropic has no embeddings API. Consequence: `OPENAI_API_KEY` becomes mandatory in every deployment, including Anthropic-only chat. This is a *startup* dependency, not just a deploy-secrets matter (see Configuration below).

### Configuration (binding — do not leave to inference)

These exact settings are required. Every one of them has a wrong default that fails silently:

| Setting | Value | Why it must be explicit |
|---|---|---|
| `spring.ai.model.embedding` | `openai` | The OpenAI embedding autoconfiguration is *not* gated by `spring.ai.model.chat`; pin it the same way chat is pinned, so provider switching stays predictable |
| `spring.ai.openai.embedding.model` | `text-embedding-3-small` | Default is a legacy model at ~5× the cost with *identical* 1536 dimensions — nothing breaks visibly, so nobody notices. **Note:** `spring.ai.openai.embedding.options.model` is deprecated in Spring AI 2.0.0; use the property named here |
| `spring.ai.vectorstore.pgvector.table-name` | `fpl_embeddings` | Default is `vector_store`. Without this, the table this spec names never gets created |
| `spring.ai.vectorstore.pgvector.dimensions` | `1536` | Left unset, the store resolves dimensions by making a **live OpenAI call at every context startup** — which would make the whole test suite and every `bootRun` depend on OpenAI reachability |
| `spring.ai.vectorstore.pgvector.initialize-schema` | `true` | Defaults to `false` in Spring AI 2.0 (changed from earlier versions). The single most likely silent failure in this phase. `CREATE EXTENSION`/`CREATE TABLE IF NOT EXISTS` — idempotent, safe on every startup |
| `spring.ai.vectorstore.pgvector.schema-validation` | **do not enable** | Verified against the 2.0.0 bytecode: `PgVectorStore.afterPropertiesSet()` runs validation *unconditionally before* `initialize-schema`'s table creation. The two together can never succeed from an empty table — validation always fails first, every time, including on a fresh Supabase instance. There is no bootstrap sequence that makes this combination work; leave validation off and rely on `initialize-schema`'s own idempotent `CREATE TABLE IF NOT EXISTS` |
| `spring.ai.vectorstore.pgvector.remove-existing-vector-store-table` | **never set it** | It drops and recreates the table on *every application boot*, not on ingest. It looks like the truncate-and-reload switch and is not |

### Database prerequisites — verify before anything else

Probed against the live instance on 2026-08-14:

- `vector` extension: available (0.8.0) but **not installed**
- `hstore`: **not installed**
- `uuid-ossp`: installed
- Connecting role: `postgres`, **not superuser**, but holds `CREATE` on `public`

`PgVectorStore`'s schema initialization issues `CREATE EXTENSION IF NOT EXISTS` for all three (`vector`, `hstore`, `uuid-ossp`), and its generated DDL defaults the id column to `uuid_generate_v4()`. Supabase installs extensions into the `extensions` schema rather than `public`, so that function resolves only if `extensions` is on the role's `search_path` — and this project connects through the *transaction pooler*, which is not Supabase's recommended path for DDL.

This is the only step that can hard-block the phase, and it touches a database shared with `tapas-fpl-app`. If automatic schema init cannot create the extensions, the fallback is: enable them once through the Supabase dashboard SQL editor, then set `initialize-schema: false` and create the table with hand-written DDL. **Creating extensions on the shared database is a decision to confirm with the project owner, not a side effect to discover mid-implementation.**

### The tool

`StrategyTools.searchStrategyDocs(String query)` — free text in, matching chunks out, same calling convention as `getPlayerStats(playerName)`.

Three requirements distinguish it from the stat tools:

1. **No `SeasonContext.provenance()` prefix**, because that record describes *database rows* resolved through `SeasonResolver` — it has nothing to resolve here. But the corpus is **not** timeless (see Document metadata below), so each retrieved chunk must carry its own attribution: document title, section, kind, and validity date. A single static banner line is not sufficient once dated content enters the corpus. **`AGENTS.md`'s "every tool prefixes its reply with `SeasonContext.provenance()`" must be amended in the same task**, or a future agent will "fix" this tool back.
2. **Empty-store behavior must be distinguishable from no-match.** Before first ingestion — the state of every fresh deploy, since ingestion is manual — search returns nothing. A bare "no results" invites the model to answer from training priors while a tool call creates false assurance. Return an explicit instruction instead, e.g. `No strategy documents are loaded — say so rather than improvising strategy advice.`
3. **The tool description states when to reach for it** — advice, transfer, captaincy, chip and wildcard questions; not stat lookups. Description and system prompt must agree, since the model reads both.

Error handling follows the convention every tool in this codebase now follows: catch `DataAccessException`, log with context, return a plain-text error string rather than letting it escape and kill the SSE stream.

Per-call cost is negligible (~$4×10⁻⁷ per query); the real cost is one extra OpenAI round-trip of **latency** mid-stream. Not worth caching — stated here so nobody builds a cache.

### System prompt

Add an explicit instruction: for advice-shaped questions, call **both** a stats tool and `searchStrategyDocs`. Don't rely on tool descriptions alone, the same reasoning that made the seasonal-provenance instruction explicit in Phase 1.

Exact wording is not fixed here — prompt language will be revised repeatedly as real usage exposes gaps. What is fixed: both tool families get consulted for advice questions, and the phrasing must already accommodate a future squad-aware tool (see Phase 5 below) rather than needing restructuring later.

### Data flow

"Who should I transfer in?" → advice instruction applies → model calls stat tools (form candidates, fixture difficulty) → model calls `searchStrategyDocs("who to transfer in, form vs fixtures")` → Spring AI aggregates both into the streamed answer, unchanged from Phase 1's orchestration.

"How many goals has Haaland scored?" → stat tool only. `searchStrategyDocs` is never called.

### Documents and ingestion

**Location is fixed, not an implementer choice:** the documents must live under `backend/src/main/resources/docs/`. The Dockerfile copies only `backend/` into the build stage, so a Gradle task sourcing the repo-root `strategy_docs/` works locally and produces an empty corpus in the deployed image — passing Task 3's local verification and failing at deploy. Whether the repo-root copy is deleted or kept as the editing source with a documented sync step is an implementer choice; where the app reads from is not.

**Chunk by markdown section, not by token window.** The original 500-token/50-overlap figure cannot be implemented as written — Spring AI 2.0.0's `TokenTextSplitter` has **no overlap parameter** (its builder exposes chunk size, min chunk chars, min length to embed, max chunks, keep-separator, punctuation marks, encoding type — nothing else). More importantly, token windows are wrong for *these specific documents*:

- Three near-identical sections exist ("Common chip mistakes", "Wildcard mistakes", "Free Hit mistakes"). A token window strips the header from the chunk holding the bullets, so a retrieved chunk is ambiguous about which chip it describes — the model will misattribute.
- Markdown tables get severed from their header rows (`strategy-transfers.md`, `strategy-wildcard-and-free-hit.md`, `captain-selection-and-triple-captain.md` all end in decision tables). Half a table without `| Situation | Chip |` is unreadable.
- Sections here run 100–250 words: already self-contained and answer-shaped, i.e. already the right retrieval unit.

So: split on markdown sections (`spring-ai-markdown-document-reader` is in the 2.0.0 BOM), prefix each chunk's body with `<doc title> > <section header>` so a retrieved chunk carries its own context, and store the metadata described next.

### Document metadata and shelf life

Documents fall into two lifespans, and the difference is load-bearing:

- **Season-long** — the strategy reference material. Valid until the rules change.
- **Short-lived** — a gameweek preview, an injury report, a "who to captain this week" take. Valid for a week or two, then not merely stale but wrong.

Every document carries front matter; every chunk inherits it into vector-store metadata:

| Field | Required | Purpose |
|---|---|---|
| `title` | yes | Human-readable name, prefixed into chunk text and cited in answers |
| `kind` | yes | `reference` (season-long) or `timely` (tied to a moment) |
| `valid_from` | yes | ISO date. For `reference`, the season start it was written for; for `timely`, publication date |
| `valid_until` | only for `timely` | ISO date after which the content should no longer be treated as live. Absent means no expiry |
| `section` | — | Populated by the splitter, not the author |

Use ISO dates (`2026-09-15`) rather than gameweek numbers: they sort lexicographically, so no gameweek lookup is needed at query time and nothing couples this to `SeasonResolver`. Authors are free to think in gameweeks and write the corresponding date.

**Expiry is enforced at retrieval, not at ingestion.** `searchStrategyDocs` filters to documents that are currently valid — no `valid_until`, or `valid_until` on or after today. Spring AI supports this natively: `SearchRequest.filterExpression()` with `FilterExpressionBuilder`'s comparison operators, which `PgVectorStore` translates into SQL against the metadata column.

Write that expression as `kind == "reference" || valid_until >= today`, not as `valid_until >= today` alone. The pgvector converter emits a jsonpath predicate, and a jsonpath comparison against a *missing* key is false — so the naive form silently excludes every reference document (they have no `valid_until`), and the converter rejects `isNull`/`isNotNull`, so there is nothing to OR the missing case in with. Reaching the same result through `kind` works because ingestion guarantees every `timely` document has a `valid_until`. Keep a Java-side check as a second pass: a `reference` document is permitted to carry a `valid_until`, and would otherwise pass on its `kind` alone. Expired documents stay in the store (storage is free, re-ingestion would restore them anyway) but stop competing for the top-k slots, which matters much more once the corpus holds hundreds of documents and a handful of dead previews would otherwise crowd out live reference material.

Retrieval-time enforcement rather than ingestion-time pruning also means an expired document becomes invisible the day it expires, without anyone re-running ingestion.

`searchStrategyDocs` must surface `kind` and the validity window for each retrieved chunk, and the system prompt must instruct the model to weigh `timely` content by age and say when it is citing something written weeks ago. A GW3 injury report must reach the model *labelled as a GW3 injury report* — it cannot infer that from the text.

This is the RAG-side counterpart of the `SeasonContext.provenance()` discipline Phase 1 established for database rows, for the same reason: retrieved content that does not announce its own age gets presented as current.

The seven existing documents are all `kind: reference` with no expiry. Adding their front matter is part of Task 3. Whoever adds documents later owns their front matter; ingestion must fail loudly on a document missing required fields, or on a `timely` document with no `valid_until`, rather than silently ingesting content that will never expire.

**Retrieval:** top-k **5** (`SearchRequest`'s default is 4 — whatever value is chosen must be set explicitly). Five chunks of a ~16–21 chunk corpus is roughly 2.5k tokens, trivially affordable, and gives compound questions enough material. No similarity threshold: the tool-based gate already does the job a threshold would have done.

**Re-ingestion:** `--ingest` deletes the existing rows and reloads from the current doc set. No content-hash dedup — wrong amount of complexity for seven infrequently-edited files. `VectorStore` exposes no truncate operation (only `delete(ids)` / `delete(Filter.Expression)`), so the runner does this itself; a plain `DELETE FROM fpl_embeddings` is acceptable and does not undermine the choice to use the abstraction for search. Note this leaves a window where the deployed app sees an empty corpus — which requirement 2 above makes visible rather than silent.

The runner must not execute during normal `bootRun` or production startup, and **must terminate the process when it finishes** (an `ApplicationRunner` in a web app otherwise leaves a server running after ingestion).

### Testing

**Ingestion tests must never write to `fpl_embeddings`.** There is one Supabase project, no Testcontainers, and five existing `@SpringBootTest` classes that boot against it. Without isolation, `./gradlew test` truncates the real strategy corpus and refills it with test fixtures — silently, after which the deployed app answers advice questions from fixture junk. Pin a separate table for the test profile (`table-name: fpl_embeddings_test` in `src/test/resources/application.yml`, or a test-only store bean).

Test against a small **fixture** corpus, not the real documents: their content changes independently of the code, and tests shouldn't depend on it. Cover ingestion mechanics, retrieval of known fixture content, and the error/empty paths.

Mechanics tests cannot tell you whether retrieval is any *good*. That needs a checked-in list of ~8 real questions mapped to the document that should be retrieved — "should I use my wildcard now" → `strategy-wildcard-and-free-hit.md`; "premium or mid-price forward" → `player-archetypes.md`/`strategy-team-building.md`; "is a -4 worth it" → `strategy-transfers.md` — run manually against the real corpus with results recorded in `STATUS.md`. This is what catches chunking failures; a fixture assertion never will.

### Phase 5 extension point (not built now)

Transfer advice is much better when the model knows the user's current 15-player squad — it shouldn't suggest players already owned, and should reason about who to sell. That needs a user account (Supabase auth, Phase 5) and is explicitly deferred.

Phase 2 must not make Phase 5 harder:

- `FplTool` + `List<FplTool>` injection already means a future `getUserSquad()` is just another `@Component implements FplTool` — no changes to `ChatService`, `ChatController`, or `StrategyTools`.
- The advice instruction in the system prompt should be phrased with a conditional that already accommodates a squad tool ("if the user's squad is known, factor in what they already own; otherwise ask or proceed with general advice") rather than phrasing that needs restructuring when Phase 5 lands. Constraint on shape, not a fixed sentence.
- Whoever writes or revises strategy docs should treat "the user's current squad" as a factor the advice already accounts for. Content-authoring note for the doc owner, not a code task.

## Out of scope

Server-side history, auth, billing (Phase 5). `pl_match_result` and historical sync (Phase 3). New stat tools (Phase 3/4). Building `getUserSquad()` (Phase 5).

## Left to the implementer

Class and method shapes beyond the three named components; the exact system prompt sentence; whether top-k 5 needs tuning after real use; whether the repo-root `strategy_docs/` copy is deleted or kept as an editing source. These are judgment calls inside the constraints above — not open design questions.
