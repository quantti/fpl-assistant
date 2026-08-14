# FPL Assistant — Phase 2: RAG Design Spec

**Date:** 2026-08-14
**Status:** Approved
**Supersedes:** the "RAG knowledge base" section of `docs/superpowers/specs/2026-06-27-fpl-assistant-design.md` (that section describes per-message retrieval; this spec replaces it with tool-based retrieval, decided during brainstorming on 2026-08-14).

---

## Idea

Phase 1 gave the assistant three knowledge layers as designed: static FPL rules in the system prompt, and live/historical stats via `GameweekTools`/`PlayerTools`/`FixtureTools`. The third layer — strategy knowledge (captaincy, transfers, chips, differentials, squad building) — does not live anywhere yet. A stats question ("how many goals has Haaland scored") and an advice question ("who should I captain") currently get the same treatment: whatever the model already knows, unsourced.

Phase 2 adds that third layer as a **tool**, not a per-message context injection. The model decides when strategy knowledge is relevant and asks for it explicitly, the same way it already decides when to call `getPlayerStats`. This keeps stat-only questions cheap and unpolluted, and gives advice questions a sourced, current answer instead of the model's static training-time opinion.

## Why tool-based, not per-message RAG

The original design spec's data flow injected a `[CONTEXT]` block from the top-3 similarity matches into *every* prompt, regardless of question type. Two problems: it spends a retrieval on every stat question that never needed strategy content, and it has no way to *not* inject when nothing relevant exists (top-3 always returns something, relevant or not).

A `@Tool` follows the exact pattern already established by `GameweekTools`/`PlayerTools`/`FixtureTools`: the model calls it when the question calls for it, skips it otherwise. This is architecturally free — Phase 1's `List<FplTool>` registration pattern (Task 7) means adding this tool is exactly as much work as adding any other tool, with no changes to `ChatService`, `ChatController`, or the SSE contract.

## System design

### New components

```
backend/src/main/java/fi/karivanttinen/fplassistant/rag/
├── RagConfig.java        # VectorStore bean (Spring AI PgVectorStore), embedding model config
├── IngestionRunner.java  # ApplicationRunner, active behind an --ingest flag
└── StrategyTools.java    # @Tool searchStrategyDocs(query), implements FplTool
```

- **Vector store:** Spring AI's built-in `PgVectorStore` abstraction, not hand-rolled `JdbcTemplate` + pgvector queries. This is a deliberate departure from the existing tools' style (which query Supabase directly): embedding, chunk storage, and cosine-similarity search are exactly what `PgVectorStore` exists to handle, and re-deriving that by hand would be reinventing a well-tested wheel for no benefit. The existing tools stay as they are — this is the one place in the codebase where the framework abstraction earns its keep.
- **New table:** `fpl_embeddings` in the shared Supabase project, schema owned and initialized by `PgVectorStore` (`initialize-schema: true`). Same "new tables only, never touch tapas tables" convention as the rest of the project.
- **Embedding provider is always OpenAI**, regardless of `SPRING_AI_PROVIDER`. Anthropic has no embeddings API, so this isn't a real choice — but it has a real consequence: `OPENAI_API_KEY` becomes a mandatory secret for every deployment, including an Anthropic-only chat configuration. This must be stated plainly in `AGENTS.md` and `docs/STATUS.md`/`DEVELOPMENT.md` — Phase 1's review found this undocumented in the original spec, and it should not repeat here.

### The tool

`StrategyTools.searchStrategyDocs(String query)` — free-text query, same calling convention as `PlayerTools.getPlayerStats(playerName)`: the model passes natural language, the tool embeds it and returns the top-k matching chunks as formatted text.

Two things distinguish it from the other three tools:

- **No `SeasonContext.provenance()` prefix.** Strategy knowledge is evergreen — "how to think about differentials" doesn't belong to a season the way a player's goal count does. Prefixing it with season provenance would be applying a convention outside the problem it exists to solve.
- **The tool description states when to use it**, in the same terms as the system prompt instruction below: strategy, transfer, captaincy, chip, and wildcard questions, not stat lookups. Tool descriptions and system prompt should agree, since the model reads both.

### System prompt

Add an explicit instruction: for advice-shaped questions (who to captain, who to transfer in, when to play a chip), call **both** a stats tool and `searchStrategyDocs` — don't let good tool descriptions alone carry that responsibility, the way `GameweekTools`'s provenance instruction is spelled out explicitly rather than left implicit.

The exact wording is not fixed by this spec — system prompt language is expected to be revised repeatedly as real usage surfaces gaps, the same way Task 3's prompt already went through corrections. What's fixed is the *behavior*: both tool families get consulted for advice questions, and the phrasing should be written so it survives Phase 5 without a rewrite (see below).

### Data flow — compound question example

"Who should I transfer in?"

1. System prompt's advice-question instruction applies.
2. Model calls stat tools (e.g. `getPlayerStats` for form candidates, `getUpcomingFixtures` for FDR).
3. Model calls `searchStrategyDocs("who to transfer in, form vs fixtures")`.
4. Spring AI aggregates both tool results into the final streamed answer, unchanged from Task 7/8's orchestration.

"How many goals has Haaland scored?" — stats tool only; `searchStrategyDocs` is never called, because the system prompt scopes it to advice questions and the tool description reinforces that.

### Ingestion

- **Source docs:** `strategy_docs/` at the repo root already contains seven markdown files (user-supplied, reviewed and corrected for the 2026/27 two-chip-set season during this design phase — see `strategy-chips.md`, `strategy-wildcard-and-free-hit.md`, `strategy-transfers.md`). These need to end up under the backend's classpath (`resources/docs/`, per the original spec's project structure) so `IngestionRunner` can read them as resources; whether that's a move, a copy, or a build-time resource-path addition is an implementation choice, not a design one.
- **Chunking:** 500 tokens / 50-token overlap — the original spec's numbers, kept as a starting point given the doc count is small (7 files) and there's no evidence yet that they need tuning.
- **Retrieval:** top-3 chunks per `searchStrategyDocs` call. No similarity threshold — the tool-based gate (model decides whether to call it at all) already does the job a threshold would have done in the per-message design.
- **Re-ingestion:** `IngestionRunner --ingest` truncates `fpl_embeddings` and reloads from the current doc set on every run. No content-hash dedup, no incremental updates. This is the right amount of complexity for seven infrequently-edited files — dedup logic would be solving a problem this project doesn't have.
- `IngestionRunner` does not run during normal `bootRun` / production startup — only behind the `--ingest` flag, invoked manually when docs change.

### Testing

Existing tools test against live Supabase (established project convention, not changed here). RAG ingestion tests should run against a small **fixture** doc set checked into the test resources, not the full user-supplied `strategy_docs/` corpus — the corpus's content isn't something tests should depend on, and it will keep changing independently of the code. `StrategyTools` tests then verify retrieval behavior (a known query returns the known fixture chunk) rather than asserting anything about real strategy content.

### Error handling

`StrategyTools.searchStrategyDocs` follows the same convention every other tool in this codebase now follows (Task 5–8 pattern, including the Task 7 review's `GameweekTools` harmonization): catch `DataAccessException`-family failures (vector store queries go through the same JDBC infrastructure), log with context, return a plain-text error string rather than letting the exception escape and kill the SSE stream.

### Phase 5 extension point (not built now)

A brainstorming detour surfaced a real future requirement: transfer advice is much better when the model knows the user's *current* 15-player squad (so it doesn't suggest players already owned, and can reason about who to sell). That capability needs a user account (Supabase auth, Phase 5) — building it now is out of scope and was explicitly deferred.

What Phase 2 must not do is design itself into a corner that makes Phase 5 harder:

- The `FplTool` marker interface + `List<FplTool>` injection (Task 7) already means a future `getUserSquad()` tool is just another `@Component implements FplTool` — zero changes to `ChatService`, `ChatController`, or this phase's `StrategyTools`.
- The system prompt's advice-question instruction should be phrased with a conditional that already accommodates a squad tool's future existence (e.g., "if the user's squad is known, factor in what they already own; otherwise ask or proceed with general advice") rather than a phrasing that would need to be restructured when Phase 5 lands. The exact wording is left to whoever writes it, now or later — this is a constraint on shape, not a fixed sentence.
- Anyone writing or revising the strategy docs going forward should write with "the user's current squad" as a real factor the docs' advice already accounts for (e.g., "avoid transferring in a player you already own in another position") — this is a content-authoring note for the doc owner, not a code task.

## Out of scope for Phase 2

- Server-side conversation history, auth, billing — Phase 5, unchanged.
- `pl_match_result` / historical data sync — Phase 3.
- Any new stat tools (`getTeamForm`, `getHeadToHead`, price trends, etc.) — Phase 3/4.
- Building `getUserSquad()` or any user-account-backed feature — Phase 5, per above.

## Open questions left to the implementer

This spec deliberately does not fix: exact package/class shapes beyond the three named components, exact system prompt sentence, whether the 500/50/top-3 numbers need tuning after real use, and how the `strategy_docs/` → classpath resource path is wired. These are implementation judgment calls within the constraints stated above, not open design questions requiring another round of brainstorming.
