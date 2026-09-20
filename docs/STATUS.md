# Status — Phase 1

## Expected-points-only ranking and factual minute notes (2026-09-20)

Primary picks, captain candidates and squad XI places require fresh available status, coherent
identity and complete observed current-club evidence. They have no minute-based admission gate.
Observed minutes, 60+ rates, expected minutes and realised points do not alter a recommendation
score, tie-break, eligibility result or XI place, including for goalkeepers and defenders.

`PickTools` and `HorizonScorer` now rank only on full-fixture expected FPL points: fixed
appearance points, xG/xA ability, fixture-specific clean-sheet probability and DefCon action-rate
evidence. The ability blend ramp is driven by completed current-season fixtures, not 60+ matches.
If any of the latest five complete observations is below 60 minutes, a pick row may state the
opponent and minutes as a neutral fact only. Fresh availability, identity/club coherence and
missing-evidence guards remain unchanged.

The system prompt and checked-in strategy corpus source now use the same rule; production embeddings
remain unchanged until an explicit post-review corpus re-ingest. Local Gradle verification could
not run on this checkout host because it has neither Java nor `backend/.env.test`; the full
[PR CI run 35526675477](https://github.com/quantti/fpl-assistant/actions/runs/35526675477) passed.
No local test database, corpus reload, production write or deployment was attempted.

## Owned sync — remove redundant parent work (2026-09-20)

The first bounded follow-up to the attended egress test removes fixture verification's
unconditional parent refresh after fetching its own public pair. A fresh verification read is
not itself a correction to the published parents. Missing canonical history parents, actual
history contradictions, changed/absent targets and publication database mismatches still request
parent refreshes; independently scheduled catalogue/schedule updates are unchanged.

Repeated missing-canonical dependency notifications now use `enqueuePreservingActiveDemand`:
an already active parent's source floor stays fixed, so a bootstrap checkpoint survives a later
notification while fixtures admission is deferred. Genuine correction/refresh requests still
advance the floor and reject older evidence. No source validation or verification guard changed.

Verification: all 24 focused history/fixture and catalogue/schedule dispatcher integration tests
passed against disposable Postgres. Regressions cover no fetch-only parent jobs, resumed fixture
fetches, real queue acquisition of history work, repeated missing-parent notification with an
unchecked bootstrap checkpoint, subsequent parent completion and history publication. Existing
contradiction and genuinely newer-demand rejection tests also pass. This is not a reproduction of
all 46 fixtures/667 histories or a free-tier egress acceptance result; full-suite verification is
on the PR. The first rerun failed only because the local env named port 15432 while the disposable
container used 55432; the passing run explicitly selected the container's local connection.

Production remains OFF epoch 63 from the attended test; no production changes were made for this
implementation. Remaining before sustained activation: dependency-body traffic, recurring
planner/watchdog metadata, retention recheck and bounded rate/budget monitoring. The test evidence
and correction sequence are recorded in documentation PR #125.

## Privacy vendor and retention research (2026-09-19)

[`docs/PRIVACY-OPERATIONS.md`](PRIVACY-OPERATIONS.md) is the canonical current record of the owner
walkthrough and the official Google Gemini, OpenAI, Supabase, Resend and Fly sources. Approved but
unimplemented rules are: chats for the account lifetime with no 365/90-day age expiry; usage events
for 90 days while always protecting the active quota window; ordinary application logs at most 14
days; no inactivity deletion during testing; and invite erasure that removes identity without
restoring the consumed place. Existing diagnostic traces already have 14-day cleanup. Email DSAR
handling with proportionate minimum verification and the ordinary one-month response period is
approved, but safe export/erasure tooling is not implemented. No overall GDPR-compliance claim is
made.

The DPA review distinguishes incorporation from execution. Google's paid Gemini terms incorporate
its processor DPA and its service list names Gemini API Paid Services; Supabase and Resend likewise
incorporate their DPAs through their service agreements. Those public contract routes do not prove
blanket account compliance or separate signatures. OpenAI's own FAQ directs API customers to its DPA
execution form, so a customer-specific copy remains blocked on the form's company-only fields. Fly's
downloaded DPA has an invalid company-name field and misaligned annex entries and cannot yet be
relied upon.

## Current privacy disclosure and blockers

The public privacy page now states the known current facts without presenting approved targets as
active features. Production chat uses the billed, API-key Gemini Developer API; developer logging is
off and datasets are empty, while Google's separate 55-day abuse monitoring remains. OpenAI is used
only for embeddings. Its API logging is off with Global residency, and sharing was disabled for or
excluded from the FPL use case after all three categories had been enabled, but the resulting
organisation/project scope was not recorded and earlier sharing is not undone. The page makes no
retrospective no-training promise.

Supabase is Free in eu-west-1 (Ireland), with no owner-managed backups and no documented Free-plan
daily-backup entitlement. Resend Free's accepted provider retention is 30 days and open/click
tracking is off. Ordinary app-log policy is separate: 14 days maximum, with no external log or error
service. Kari Vänttinen is the sole human administrator and reports Google MFA for Supabase and Fly.
The approved incident procedure is in `PRIVACY-OPERATIONS.md`.

Pending work remains explicit:

- [KAR-62](https://linear.app/quantti/issue/KAR-62) — **High / Todo:** encrypted daily backup,
  seven-day retention, restore test and post-restore re-deletion.
- [KAR-63](https://linear.app/quantti/issue/KAR-63) — **Normal / Todo:** correct the Fly individual
  contracting field and annex misalignment before relying on its DPA.
- [KAR-64](https://linear.app/quantti/issue/KAR-64) — **High / Todo:** provision and verify the new
  privacy mailbox. Do not publish it yet; `kvanttinen@gmail.com` remains the working public contact.
- [KAR-65](https://linear.app/quantti/issue/KAR-65) — **Normal / Todo:** resolve OpenAI's
  company-field-only DPA route without inventing a company or placeholder data.

Also pending are the OpenAI sharing-scope evidence, contracting-customer records, an owner-approved
short DSAR case-record period, and implementation/testing of deletion, quota-safe usage cleanup,
backup and DSAR tooling. Approved targets must not be described as active behavior.

The browser-storage notice is informational, not a consent banner: Supabase auth tokens/session
and Mantine's theme preference use `localStorage`, while the “Got it” acknowledgement uses only
`sessionStorage` under `fpl-storage-notice-seen`. If storage is blocked it dismisses in memory and
returns after reload. There are no optional analytics or advertising trackers to accept or reject.

## Public landing page and app entry

`/` is now a public marketing page with “Now free”, an explicit testing/invite-code
notice, feature summaries and registration/login links. Signed-in visitors get an app
link rather than an automatic redirect. The chat entry is `/app`; existing `/my-team`,
`/shortlist`, `/usage`, `/settings` and `/admin` deep links remain unchanged.
`/login` and `/register` select the corresponding auth form and take authenticated
visitors to `/app`. Protected deep links retain their destination through sign-in;
Supabase confirmation callbacks on `/` still restore the session there.

Verification: focused routing/auth/navigation tests (17), landing render/link tests (3),
`SpaForwardingTest`, frontend lint/format and production build passed. Static review
found no blockers. Real-browser mobile rendering and live email callbacks were not
exercised; full-suite verification belongs to PR CI.


## Bench-order wording (KAR-68, 2026-09-20)

The system prompt now defines bench numbers as automatic-substitution priority: first outfield
substitute at 1, second at 2, last at 3. It separates that order from who should be removed from
the XI, rejects ambiguous “first out” wording and requires explanations to agree with the slots.
The reserve goalkeeper is listed separately; legal-formation exceptions and the one-minute-cameo
limit remain explicit. The existing markdown renderer and squad formatter already preserve list
order, so no UI list reversal, player-ranking or tool-data change is made.

Three deterministic prompt-contract tests cover priority, goalkeeper separation and auto-sub
constraints. They check the instructions, not live-model generation.

## Exception-only availability wording (KAR-67, 2026-09-20)

The chat prompt now tells routine player summaries and recommendations to omit normal
availability lines such as “Saatavilla: ei FPL-lippua”. Only concerns are reported, using plain
language for doubts, injuries, suspensions and other absence reasons. Availability checks before
transfer/captain advice still run; missing or outdated required evidence stays explicit, with its
capture-date qualification. Direct availability questions still get an answer, without promising
fitness, a starting place or minutes. Tool data, eligibility rules and UI rendering are unchanged.

Three deterministic prompt-contract tests cover these instructions; no live-provider generation
test is used.

## Test-use chat traces — 14-day retention

Chat turns now persist diagnostics in `fpl_chat_trace`: effective system/history prompts
for each model round, tool arguments/results and timings, reported model/token usage,
and the final answer or partial output with a sanitized error. Credential-shaped values
are redacted. Persistence is asynchronous and fail-open; a trace-store failure must not
fail a chat. A process crash can leave an unfinished trace. Client disconnects retain the
existing detached-generation behavior, not a cancelled model outcome.

Only explicitly exposed reasoning summaries are collected. Raw provider thinking,
reasoning content, signatures and redacted-thinking payloads are not collected; current
provider adapters may supply no summary at all. Visible Gemini output is not suppressed
merely because it carries a thought signature.

Admin-only, no-store APIs: `GET /api/admin/chat-traces?limit=50&before=<instant>`
(metadata list, maximum 100) and `GET /api/admin/chat-traces/{id}` (detail).
The table has deny-all RLS. Startup and hourly cleanup delete traces older than 14 days;
conversation history and quota accounting retain their separate lifecycle.

Verification: 64 focused chat/instrumentation/lifecycle/persistence tests passed before
review; the instrumentation tests passed again after review fixes for replayed thinking,
credential redaction and Gemini signatures. Full-suite verification belongs to PR CI.

## Trace redaction stack overflow (KAR-66, 2026-09-20)

Plan: `docs/superpowers/plans/2026-09-20-kar-66-trace-redaction.md`.

Chat returned HTTP 500 while the page and health endpoint remained healthy. The quote-pair regex
added in `3259810` recursed per character across long quoted spans; trace collection runs before
the provider call and before the controller returns its SSE stream, so `StackOverflowError`
escaped before the normal stream-error handling. Fly's retained log tail showed recursive
`Pattern` frames followed by the five-minute timeout; the original exception header was outside
the buffer. A standalone Temurin 25.0.4.1 reproduction confirmed the overflow with the deployed
regex on the actual 31,324-character system prompt and a synthetic 32,011-character quoted value.

Both quoted-string interiors now use possessive repetition. Their alternatives are disjoint and
exclude an unescaped closing quote, so no backtracking is needed and the matching/redaction
semantics stay intact. No prompt truncation, stack-size increase, trace disabling, fatal-error
catch or schema change. The standalone probe passed both inputs with this fix.

Six regressions cover the real prompt, long ordinary quoted keys/values, escaped secret values,
unmatched ordinary text and an instrumented stub-provider turn with the real prompt. Assertions
check exact retained/redacted text, not just absence of an exception. The complete-turn test uses
only `question` as its user message: the always-attached system prompt, not the length of the
user's own question, is enough to trigger the old defect.

PR #123 at `06453dd` passed CI `35498149340`: 1,255 backend tests with zero failures/errors/skips,
including all 12 trace instrumentation tests, plus frontend build/lint/format. Independent review
found no blockers and confirmed the pre-SSE failure path. The owner merged PR #123 as `e123272`;
workflow `35498621880` passed tests, deployment and the external health smoke on 2026-09-20.
A separate health read at 08:15 UTC returned 200 / UP. Authenticated production chat still needs
the owner's check. This follow-up only clarifies the code comment and records the evidence;
no manual deploy, restart or database change was made.


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
    FOOTBALL_DATA_API_KEY=... SPRING_AI_PROVIDER=openai CORS_ORIGINS=https://fpl-sidekick.com,https://fpl-assistant.fly.dev
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

## Verified against the live API

Smoke-run on 2026-08-25 against entry 1 with real FPL URLs: the squad card (GW1 locked squad,
bank, captain, chips for the GW2 deadline), the mini-league list, `nearby` and `top3`. Card plus
all three league calls cost twelve of the fifteen-call budget. The run also settled the chip
question above and confirmed the club short names come out right (`element.team` → the response's
own `teams` block). The throwaway test was deleted rather than kept: it is a smoke check against
a live third party, not an oracle, and the suite has exactly one `realdata` test on purpose.

## Cost and limits

Ten opponent squads and fifteen FPL calls per turn, both enforced in the services rather than
left to the tool descriptions. `focus="top50"` is accepted, clamped, and says it was clamped, so
the model cannot claim to have read the whole league. Opponents are reported as the difference
against the user's own squad — two lines each instead of fifteen — because the reply re-enters
the prompt on every later turn and the free tier meters messages, not tokens.

The mini-league list is capped at twelve, smallest first, with the rest counted off — the live
entry above is in sixteen, and a heavy player is in more.

Managers' real names (`player_name` in the standings) are never read: they belong to third
parties who never used this product, and everything here is sent to a model provider.

## Left undone on purpose

- Live gameweek points (`/api/event/{event}/live/`), a "my team" page, and head-to-head leagues.
- No persistence: squads and standings live in in-process TTL maps, keyed by FPL entry or league
  id rather than by user, so editing a stored team id cannot serve the previous team's squad.

---

# Strategy corpus — closing the gaps before test users (2026-08-25)

Corpus is 21 documents / 181 chunks (was 19 / 166). Verified by ingesting against the disposable
test container, not production.

**Three documents written**, the last two of which were tracked as issues #39, #40 and #41:

- `strategy-set-and-forget-and-low-maintenance.md` — which squad slots absorb inattention and
  which do not, and the three decisions (captain, availability, chips) that still cannot be
  skipped. The corpus referred to "set-and-forget" in four places without ever defining it.
- `strategy-blank-and-double-gameweeks.md` — deferred to a later season when it was first
  proposed, written now because a test user meeting their first blank without it is exactly the
  gap it describes. The framing that was missing everywhere else: blanks and doubles are the same
  fixtures seen at two different times, and with two chip sets a first-half chip cannot be saved
  for a spring double.
- `strategy-2026-27-early-season.md` (`kind: timely`, valid to 20 September) — replaces the
  pre-season consensus document, whose window closed on 21 August and which was therefore
  invisible to retrieval. Built from FPL's own data read on 25 August: GW1 results, who actually
  scored, ownership, transfer counts, prices, and GW2 fixtures, plus published community opinion
  from the same week, named and dated. Its most important section is the one that says one
  gameweek is not form.

  The price sections are the part worth knowing about. **No player's price had moved at all by
  25 August** (`cost_change_start` is zero for every player), even though changes were enabled at
  the GW1 deadline and now land at midnight UK — so the document says the early-value window is
  still open rather than guessing at rises that have not happened. "Closest to a rise" is computed
  the way FPL's own mechanism works, net transfers as a share of current owners, which is why it
  names De Cuyper and Sangaré rather than the premiums with bigger absolute transfer counts.

`strategy-2026-27-preseason-consensus.md` is deleted rather than left expired. Ingestion truncates
and rebuilds, so a file that can never be retrieved again is just rows; git keeps the history.

**One factual error fixed.** `strategy-chips.md` claimed "One chip per Gameweek (exception:
Wildcard can be combined with Bench Boost or Triple Captain)". FPL allows no such exception —
one chip per gameweek, full stop; the combination people mean is sequential (Wildcard one week,
Bench Boost the next). `strategy-wildcard-and-free-hit.md` carried the same implication and was
corrected too. The chips document also now states that there is no Assistant Manager chip in
2026/27, which FPL's own `bootstrap-static` confirms: four chip types, two sets each.

**Production is not updated by a deploy.** The documents ship in the image, but the embeddings
live in Supabase, so the corpus changes only take effect after `bootRun --args="--ingest"` is run
against production. That is a deliberate manual step and it is owner-run.

**Run against production on 2026-08-25, on the owner's instruction: 21 documents, 186 chunks in
`fpl_embeddings`.** Two things worth copying next time. The startup sync runners fire on
`ApplicationReadyEvent` regardless of `--ingest`, so the run disabled them
(`--fpl.historical.startup-sync=false --fpl.price.startup-sync=false
--fpl.daily-price.scheduled=false`) rather than letting a corpus reload also trigger a
football-data sync and a price capture from a laptop. And the first attempt failed at context
startup because the env file was not sourced (`jdbcUrl, ${DATABASE_URL}`) — a failure *before*
the truncate, which is the safe direction: ingestion truncates the table first, so a run that
dies halfway leaves the assistant with no strategy layer at all.

---

# Set-piece duty moves from a document to the daily capture (2026-08-25)

Closes the "automated freshness tracking for `penalty-and-set-piece-takers.md`" item, which had
been deferred as "keep the document manual". The first gameweek settled the argument: a written
list of penalty takers is written before anyone has taken a penalty, and the opening weekend is
exactly when the duties are revised.

**The fix is not freshness tracking on a document — it is not needing the document.**
`bootstrap-static` publishes FPL's own set-piece order on the same elements as the price:
`penalties_order`, `direct_freekicks_order` and `corners_and_indirect_freekicks_order`, carried by
66, 56 and 81 players respectively on the day this went in. The 02:00 UK capture already fetches
that payload, so three columns on `fpl_daily_price` give the whole picture the daily cadence for
no extra request and no new job.

- `DailyPriceSchema` adds `penalties_order`, `direct_fk_order`, `corners_order`, with
  `ADD COLUMN IF NOT EXISTS` because the table already exists in production.
- `DailyPriceSyncService` writes them in the same upsert, in the same transaction, under the same
  finished-season guard.
- `SetPieceTools.getSetPieceTakers(club)` reports today's order **and the date each duty last
  changed**, which is the part a manager needs: "Gyökeres was on penalties until the 24th" changes
  a transfer decision in a way "Saka takes them" does not. The change log is computed in Java by
  comparing consecutive captures rather than in SQL by comparing adjacent rows — the series has
  holes on any day the capture did not run, and a window function would report a hole as a change.
- The system prompt routes every penalty/free-kick/corner question to the tool and forbids
  answering from memory or from the corpus.
- `penalty-and-set-piece-takers.md` keeps its background — why duty matters, how to read an order,
  which clubs were contested — and hands the live list to the tool, saying plainly that it was
  written before a penalty had been taken.

**Sequencing note.** That document change is *not* ingested into production yet, deliberately: it
tells the model to call `getSetPieceTakers`, and production does not have the tool until this
merges and deploys. Ingest after the deploy, not before. The columns fill on the first 02:00
capture after that deploy; until then the tool answers "no order published", which is an absence of
data and says so.

**Shipped and verified 2026-08-25.** Merged, deployed (v36), corpus ingested from master
(21 documents, 186 chunks), and `--sync-prices` run by hand so the new columns held today's order
without waiting for 02:00 (612 rows). End-to-end against production chat: *"Who takes Arsenal
penalties right now?"* → Saka, Gyökeres, Ødegaard, Bruno G., with the tool's own caveat that the
daily record begins on 18 August so nothing before that can be confirmed.

That live answer also exposed one bug. Every capture before today carries three nulls — it
predates the columns rather than recording an absence of duty — so the change log announced that
the whole league's set-piece duty changed on 25 August. A capture with no set-piece data at all is
now skipped when comparing, because it cannot tell "takes nothing" from "nobody looked"; a player
who genuinely loses a duty still reports `#1 -> none`.

## Pick tools rank on expected points, not on points scored (2026-08-26)

`getDifferentialPicks` and `getCaptainCandidates` ranked on FPL's `form` column. That column is
average points per match over the last 30 days, so two gameweeks into a season it *is* the last
scoreline. In GW2 the tools led with a £4.5m defender who had scored in GW1 — the model relayed
the ordering as though it were a case for him, which is points chasing dressed as advice.

They now rank on a **Score**: expected FPL points per 90, blended with the points actually scored.

```
xPts/90 = 2 + xG/90 × goal points + xA/90 × 3 + exp(-xGC) × clean-sheet points
Score   = xPts/90 × (1 - w) + Pts/90 × w
```

FPL's own scoring is what makes one number fair across positions, and across the two kinds of
defender: a goal is worth 6 to a defender and 4 to a forward, a clean sheet 4 to a defender, 1 to
a midfielder and nothing to a forward. `exp(-xGC)` is the Poisson probability that a side with
that expected concession keeps one. **An attacking full-back earns through the first two terms and
a stopper through the last**, and neither is judged on the other's strength — the earlier attempt
ranked defenders on xGC alone, which buried the attacking ones. Each pick prints its components
(`xG/90`, `xA/90`, `CS % at xGC`) so the model can say which kind of defender it is recommending.

`w` rises from 0 to 0.5 by gameweek 19. Underlying numbers predict better, but a real return is
evidence too, and it stops being a small sample as the season runs — penalties, set pieces, bonus
and defensive contributions are all real points the expected model cannot see. At GW2 realised
points carry 5% of the weight; by the midpoint, half. `form` is gone from the output entirely: a
30-day points average sitting beside a ranking invites the model to explain the order with it.

`form > 0` is gone as a filter too. It excluded every player yet to score, which early in a season
is exactly the set the underlying numbers would promote. A minimum of 180 minutes inside the
window replaces it — below two full matches a per-90 figure is arithmetic on noise.

**Not modelled, because tapas does not carry the columns:** defensive-contribution points, save
points, penalty saves. Goalkeepers and ball-winning defenders are understated in `xPts/90`, which
the reply states; `Pts/90` is where their return shows up. Issue #46 tracks capturing defcon data,
issue #47 the fixture-aware clean-sheet projection.

**Which season the metrics come from** is decided by sample size, not by the resolver alone: the
season being played once it has 5 completed gameweeks, the previous one until then. This is the
system prompt's early-season rule applied where the ordering is actually decided, instead of being
left for the model to remember after the tool has already handed it a points-sorted list.

Crossing seasons costs something. `player.id` is FPL's element id and is reassigned every season,
and tapas keeps no stable player code (`fixture` and `team` have one; `player` does not), so
players are bridged by name. Measured on production: of 612 players in 2026-27, 440 match a unique
2025-26 name, 34 match an ambiguous one, 138 match none. Ambiguous and missing are both dropped
rather than guessed — a wrong metric attached to a recommendation is the failure being fixed — and
the count of dropped players is printed, because a silently missing promoted-club defender reads
as one that was judged and rejected.

**Verified against production data.** The GW1 scorer who triggered this moves from 1st on `form`
to 31st of 63 cheap defenders — attacking output that is real, behind a defence that leaked. The
top of the list reads De Cuyper (xPts/90 6.83: xA/90 0.64 *and* a 68% clean-sheet chance),
Sessegnon (5.38, xG/90 0.20), Diop (5.01), with stoppers like Dunk alongside them — both shapes
present, each legible from its own components. 46 cheap defenders have no comparable 2025-26
record and are reported as unjudged. Suite green at 404 tests.

The system prompt gained a matching rule: never recommend a player because of one big gameweek,
name which kind of defender is being recommended and on what, and if the only case for a player is
that they scored, say plainly there is no underlying case yet.

Issue #26 is only half closed by this — `ExpectedStatsTools` still ranks on the resolved season
alone.

### Positional mismatch (same day)

One more thing the score alone does not say. FPL pays by the position a player is **listed** as,
not the one they play, so a defender who plays winger or midfielder collects 6 a goal, 3 an assist
and 4 a clean sheet from an attacking role. That gap is durable value, and it also changes how
fixture difficulty should be read: a hard fixture removes most of a *stopper's* return, because the
clean sheet is most of it, but a defender playing further forward keeps his attacking role whoever
the opponent is.

`backend/src/main/resources/docs/positional-mismatch-players.md` holds the list. It has two entries
— De Cuyper (Brighton, plays winger) and Reece James (Chelsea, plays midfield) — and it is
maintained by the owner, who decides membership from watching the games, community writing and
reporting, and their own observation. **The assistant does not add names to it.**

That rule exists because the first draft broke it. The list was screened out of `player_fixture_stats`
by expected goal involvements per 90, eleven names deep, against a stated threshold of the midfield
median that only two of them actually cleared — a criterion and a list that did not match. Worse,
the screen answers a different question from the one the document asks: it put three centre-backs
(Guéhi, Thiaw, Senesi) in its top fourteen because they head in corners, and it missed Reece James
at 0.195 despite his genuinely playing midfield. Attacking output conflates wing-backs, corner-box
centre-backs and penalty takers. It cannot identify a role, and a role is the whole subject.

The measurable half stays where it belongs: the pick tools already print `xG/90` and `xA/90` per
candidate, and `getSetPieceTakers` gives live duty. Those support "he produces well for a defender";
only the list supports "he plays as a winger". The system prompt draws that line explicitly.

The document is `kind: timely` and names its review points — after three gameweeks, after a
managerial change at a club on the list, and in January. **It needs a production ingest after this
merges.**

### Review of #48, and what it changed (2026-08-26)

A review of the branch found fourteen things. Eleven were fixed on the branch; two became issues;
one was already fixed before the review reported.

The three that mattered:

- **The window read the wrong quarter of a season.** `WINDOW_MATCHES = 10` over a *finished*
  season means gameweeks 28-38 — verified against production, the window spans GW28-38 — which is
  the run-in: sides chasing Europe rotate, safe sides coast, relegated ones have stopped. The
  output called it "the previous season". A finished season is now read whole; the ten-match
  window applies only to the season being played, where "recent" is the point.
- **The reply stated a falsehood.** It said save points and penalty saves were "not in the data",
  and the system prompt told the model to repeat it as the reason goalkeepers are understated.
  `player_fixture_stats` carries `saves` and `penalties_saved`. Only defensive contribution is
  genuinely absent. Leaving the other two out is a choice, and the text now says the true thing.
- **Both `@Tool` descriptions still promised the abandoned design** — xGI/90 for attackers, xGC
  for defenders — after the ranking moved to blended expected points. The model reads the
  description, not the code, so it would have explained a list by a rule that no longer produced
  it.

Also fixed: the cross-season name bridge de-duplicated the metric season only, so two current
players sharing a name both inherited one previous-season record; `expected_goals_conceded` is
`NOT NULL DEFAULT 0`, so an unpopulated column read as `exp(-0)` — a certain clean sheet, printed
as "CS 100%" — and zero is now treated as unrecorded; `element_type` indexed the scoring arrays
unbounded, and FPL has added managers as a fifth type, so an out-of-range value would have thrown
past the `DataAccessException` catch and failed the chat turn; the double-gameweek window boundary
had no tiebreak, so identical calls could return different numbers; `completedGameweeks` counted a
gameweek in progress as completed; the previous-season fallback triggered on any non-zero sample
rather than a larger one; and every decimal except the FDR went through `String.formatted`, which
resolves the default locale — "Score 6,83" on a comma-decimal JVM, and the new tests would have
failed there while passing in CI.

The footer now also dates the data (`the most recent gameweek in this data is N`), the discipline
`AGENTS.md` sets for `getTeamForm`: the ranking is load-bearing on a sync that has been measured
stale before.

Deferred: #49 (no minutes or rotation weighting — 180 minutes across the window does not separate
a starter from a substitute, and `starts` is never read) and #50 (the pool still takes price and
injury flags from the weekly tapas `player` table, against the `AGENTS.md` rule; predates this
change, which rewrote those lines without moving the source).

## Defensive contribution captured into player_fixture_stats (2026-08-26)

FPL introduced defensive-contribution points for 2025/26: **2 points for a defender reaching 10
clearances, blocks, interceptions and tackles in a match, or a midfielder or forward reaching 12
including recoveries.** For a ball-winning defender or a holding midfielder that is a large and
repeatable share of their return, and nothing in this project could see it.

The reason was not that FPL withholds it. `element-summary/{id}/history` — the same endpoint tapas
reads to fill `player_fixture_stats` — carries `defensive_contribution`,
`clearances_blocks_interceptions`, `recoveries` and `tackles` **per match**. Tapas' schema predates
the field and its sync writes a named column list, so the values have been arriving in the payload
and being dropped.

**The owner granted an exception to the "do not alter tapas tables" rule** and the four columns now
live on `player_fixture_stats`, recorded in `AGENTS.md`. They belong at that grain; a parallel
`fpl_`-prefixed table would duplicate the key, need a join in every query, and drift whenever tapas
re-syncs a gameweek. The write split keeps the two projects apart: tapas owns the rows and INSERTs
them, this project owns four columns and only ever UPDATEs them, and because both write named
column lists neither can clear the other's values.

Nullable with no default, deliberately. Zero is a real reading — a striker who did no defensive
work — and a `DEFAULT 0` would make "nothing happened" indistinguishable from "never fetched". That
is the same mistake that made the first set-piece capture announce the whole league had changed
duty.

`DefensiveStatsSyncService` fetches one player at a time, bounded to players who already have a row
in the current season, spaced 150ms apart. Per-match rather than the season totals in
`bootstrap-static`, which would be one request instead of six hundred: the points are a **threshold
per match**, so a season mean cannot say how often a player crossed it — a defender steady on 9.8
scores less often than one averaging 9.5 with a wide spread. Per-match rows also backfill, which a
daily total never can. Scheduled 03:00 Europe/London, an hour after the price capture so the two
never share the scheduler thread; `--sync-defcon` runs it by hand.

**Run against production 2026-08-26: 610 of 610 rows filled, 0 failures.** In GW1, 19 defenders and
12 midfielders earned the two points; no forward and no goalkeeper did. Starters average 7.2 actions
(DEF and MID alike), forwards 3.7, goalkeepers 0 — FPL does not award it to keepers. 2025/26 rows
are still empty and can be backfilled by the same runner whenever it is wanted.

Not yet wired into `PickTools`' `xPts/90`, which still covers goals, assists and clean sheets only
and says so. That is issue #46, which stays open for the scoring half.

### Second review of #48, and the backfill (2026-08-26)

Last season's defensive-contribution data is in: **29,757 rows backfilled for 2025/26, 0 skipped.**
Not from FPL — `element-summary` serves per-match rows for the current season only, and a past
season appears under `history_past` as one row of season totals, which cannot say how often a
player crossed a per-match threshold. vaastav archives the per-match rows, and this project already
reads that repository. `--backfill-defcon=2025-26` does it in one CSV fetch.

Every backfilled write is guarded on minutes: the UPDATE matches on fixture, player, season *and*
the minutes already stored, so a row is touched only where both sources agree on what happened in
it. vaastav's `element` column is the FPL id of the season the file belongs to, and if that
assumption were ever wrong the guard turns a silent corruption into a skipped row and a number in
the log. All matched — nothing was skipped, which is the strongest evidence available that the two key
spaces are the same.

The first run reported 29,757 rows updated against a table holding 29,747, which is not possible
and was worth chasing rather than rounding off: vaastav's file lists ten player-fixture pairs
twice, with identical values both times. The count was of statements, not of rows. Updates are now
keyed by player and fixture before they are applied, so a duplicate collapses and the number means
what it says.

The review of the branch found fifteen things. The one that mattered most was in my own fix from
the first round:

**The run-in fix was keyed on the wrong flag.** `windowLimit` tested `isResolvedSeason`, which is
true whenever the resolver's season has five or more gameweeks — including the changeover window,
which `AGENTS.md` documents as normal, where the resolver returns a *completed* season. So the
10-match window went straight back to reading GW28-38 of a finished season and calling it recent
form. `MetricSeason` now carries two flags: `isResolvedSeason` decides the SQL join key,
`beingPlayed` decides the window.

**And the cross-season branch had never been executed by a test.** `TapasSeed` leaves the current
season empty, so every existing test resolved the completed season and took the same-season path.
`PickToolsCrossSeasonTest` seeds a started season with no fixture statistics — the state every
season spends its first weeks in — and the name bridge ran for the first time. It was broken: the
metric-side uniqueness guard was missing its `WHERE`, so the query did not parse at all. That would
have surfaced in production on the day 2026/27's first gameweek synced.

Also fixed: the reply's banner named the season being played while every number under it came from
the previous one, so the metric block now carries its own `previousSeasonProvenance` line as
`AGENTS.md` requires; name uniqueness was checked inside the filtered pool, so a position or
ownership filter could hide a collision by admitting only one of a pair, and on the metric side the
minutes floor could drop one of a pair and leave the survivor looking unique; `matches` counted
fixture rows rather than appearances, printing "450 mins (10 mt)" for a player who played five; the
absentee count read as hundreds of withheld picks and is now a coverage line; `expected_goals` and
`expected_assists` had no "zero means unpopulated" guard, so an unfilled season would have tied the
whole pool and silently degenerated the ranking to the fixture tiebreak.

On the defcon side: the CSV reader used `isMapped` where it needed `isSet`, so one ragged row would
have thrown past the `IOException` catch and aborted the backfill (`MergedGwParser` already gets
this right); the nightly sync now guards its writes on minutes too, because `element-summary`
serves whatever season FPL is on and element ids are reused, so a rollover before tapas flips
`is_current` could stamp one player's match onto another's row; a payload whose fields changed
shape would have mapped every value to null and overwritten every captured reading while logging
success, so a run that produces no values at all now refuses to write; the runner exits with a code
like `DailyPriceRunner` instead of leaving a web server up; and the `ALTER TABLE` is now non-fatal
and switchable, because failing to start the chatbot over four enrichment columns is the wrong
trade.

One finding is not a code change: the review flagged that this branch rewrites `AGENTS.md`'s
guardrail on tapas tables and that the exception's only record is the commit itself. The owner
granted it in conversation on 2026-08-26, having confirmed they own both projects. Recorded here so
the grant lives outside the diff that relies on it.

## Sign-up: email verification, and invite codes that are rows (2026-08-26)

> **Deployed since PR #52 merged, but it cannot deliver until the Supabase project has a mail
> sender.** The remaining steps are DNS, a mail provider and dashboard settings, none of which
> live in this repository: `docs/EMAIL-SETUP.md` is the runbook — rewritten 2026-08-28 around
> owning a real domain — and KAR-33 tracks it.

Two changes to the same door.

### The verification step that was missing

Sign-up created the account with `email_confirm: true` and the frontend signed the person straight
in. Supabase's own **Confirm email** toggle was on and had no effect, because that setting governs
GoTrue's `/signup` path and this project used `/admin/users`, which creates a user and sends
nothing. The toggle had nothing left to enforce.

Sign-up now creates the account unconfirmed and then asks GoTrue to send its own confirmation
mail — **two calls, because no single endpoint does both while public sign-up is off.** Each of the
four candidates was tried against the live project rather than reasoned about:

| Endpoint | Creates | Sends | With sign-up disabled |
|---|---|---|---|
| `/auth/v1/signup` | yes | yes | **refused, service role included** |
| `/auth/v1/admin/users` (`email_confirm: false`) | yes | no | works |
| `/auth/v1/admin/generate_link` | yes | no (returns a link) | works |
| `/auth/v1/resend` | no | yes | works |

The first row is the one that mattered. `/signup` does everything in one call and was the design
this was first built on; with public sign-up turned off it answers `signup_disabled` to the anon key
**and to the service role alike** — the service role is not exempt. Public sign-up cannot stay on,
because the anon key ships in the frontend bundle and would let anyone create an account with no
code, so that endpoint is unusable here.

So: `admin/users` with `email_confirm: false`, then `resend` with `type: signup`. The mail is
Supabase's own, through the project's SMTP and template, which keeps a second provider out of the
request path. A failed send is logged and swallowed: the account exists and the place is taken by
then, and throwing would present a sent-mail failure as a failed sign-up, after which the person
retries and is told the address is taken.

Public sign-up was turned off in the project on 2026-08-26.

The frontend no longer signs in after a 201. It shows "check your email", and an attempt to sign in
before confirming turns Supabase's "Email not confirmed" into an instruction rather than an error.
The response carries `verificationRequired` explicitly, so no client can read a bare 201 as being
signed in.

Also learned: Supabase rejects `@example.com` as an invalid address, so a throwaway probe needs a
real domain. And the built-in mailer's rate limit is real — a probe hit
`429 over_email_send_rate_limit` after a single earlier send, which is why custom SMTP has to be
configured before any real sign-up. The client logs that 429 as its own line rather than leaving it
to be guessed at.

### Codes are rows now, not one secret

`INVITE_CODE` could express one thing — whether a string matched — and every question actually
worth asking is about something else. `fpl_invite_code` gives each code its own ceiling
(`max_uses`), its own worth (`benefit_kind`, `benefit_value`), its own validity window and an
`active` flag, so one code can be worth ten places, another a hundred, and another half off the
subscription. A code runs out or is withdrawn on its own without touching any other; rotating the
shared secret revoked everyone at once.

`uses` is a stored counter incremented in the same transaction as the redemption row, under the
same advisory lock that guards the account cap. Counting rows would be correct too, but it puts the
ceiling a join away from the lock protecting it. `--reconcile-invite-codes` brings the counters back
in line, which is the second half of the documented procedure for releasing a place burnt by a
failed Supabase call.

Codes are stored in plain text and matched case-insensitively. An invite code is a promotion code —
printed on things, read aloud, pasted into chats — and the owner has to know which is which. What
protects the tier is the ceiling on each code and the account cap behind it.

The account cap (`INVITE_MAX_ACCOUNTS`, 30) survives as a cost ceiling across every code. **A code
worth 100 places will still stop at 30 until that number is raised**, which is deliberate: the free
tier's cost is drawn against it.

Discounts are recorded, not yet applied — Stripe is Phase 5b (#43). The redemption row points at the
code, so the billing phase reads the benefit from the join.

`--create-invite-code=launch:10`, `--create-invite-code=podcast:100:discount_percent:50`,
`--list-invite-codes`. Creating a code that exists leaves it untouched rather than resetting a live
ceiling.


## Squad builder — what it is and where it stands (2026-08-28, PR #54)

`buildSquad` picks a whole FPL squad with a solver instead of prose. It exists because the model,
asked for a wildcard squad with three players from each of three clubs, produced two answers that
were not squads: three of fifteen slots were descriptions rather than players, a club constraint
was claimed but not met, the stated total disagreed with the answer's own table by over £1m, and
one player carried two different prices in the same conversation. Squad selection is constrained
optimisation — fifteen players inside a budget, at fixed position quotas, at most three per club,
around named pins — and prose cannot do it.

**How it works.** The candidate pool comes from `fpl_daily_price`, never the weekly-synced tapas
tables, so it carries no stale price or injury flag and survives the season changeover. Each
player is scored on expected FPL points per fixture — the per-90 model scaled by the minutes he
actually plays — summed over the next N gameweeks, so a double gameweek counts twice and a blank
counts as nothing. `Feasibility` refuses an impossible request before the solve with a sentence
naming the constraint and, where money is involved, the amount. An ojAlgo integer program then
picks the fifteen and the eleven under FPL's own rules, the user's constraints, and three
structural defaults that give way in a fixed order when they cannot all hold. The formatter prints
every constraint beside what was actually achieved, with ✓ or ✗.

**Two documented exceptions to project conventions**, both recorded in `AGENTS.md`: `SquadTools`
carries two provenance lines rather than one, because prices and metrics come from sources of
different freshness and one banner would attribute one of them to the wrong season; and the pool
is built from the daily capture rather than the tapas `player` table, which is what keeps issue
#50 from recurring here.

**Deliberately not built**, so nobody mistakes the gaps for oversights:

- Defensive-contribution points are absent from the scoring (#46), so goalkeepers and
  ball-winning defenders are understated. The output says so.
- Fixture difficulty is one multiplier on the whole score rather than a per-fixture clean-sheet
  projection (#47).
- Players with no minutes history — new signings, returns from long injury — can only enter as
  bench bodies or by being named. A curated source for them is its own ticket.
- Selling prices. FPL returns the purchase price plus half the profit and does not publish it, so
  a wildcard squad is priced as fresh purchases and the figure errs toward the manager looking
  poorer than they are. The output says so. Issue #53 covers the authenticated route.

**Follow-ups filed from this work:** #55 (`PickTools.avgFdrByTeam` has the double-gameweek defect
this branch fixed in `HorizonScorer`, and undervalues those clubs in production today), #56 (the
suite never runs against an empty database, so schema-ordering bugs only reach CI), #57 (the
minors the whole-branch review triaged as carry).

## Squad builder (`squad-builder` branch) — known gap after the whole-branch fix wave (2026-08-28)

A final reviewer read the branch as a whole, compiled and executed three probes, and found two
merge-blocking defects and five more worth fixing before merge — a stale-pool bug during the
season changeover, a constraint receipt that could certify an exclusion as met when it had
silently done nothing, club quotas matching only `team_name` and not `team_short`, a missing
pre-flight check for a pin colliding with a club quota, cross-season metric bridging with no
comparison against the season being played, expected points missing from the output, duplicated
window constants, and a silently unscored horizon. All eight were fixed in one pass, each with a
test that fails without its fix.

**One piece of the spec is deliberately still unimplemented.** `2026-08-27-squad-builder-design.md`'s
Infeasibility section says that when the pre-flight passes but the solver still reports
infeasible, "constraint groups are relaxed one at a time and re-solved; the group whose relaxation
restores feasibility is named." That diagnosis is not built. `SquadTools.buildSquad`'s only
fallback in that case is the generic sentence ("Those constraints cannot all be met at once...").
The pre-flight (`Feasibility.check`) now covers every case the spec enumerates, including a pin
colliding with a club quota, so the generic sentence should be rare — but it is still the whole
answer whenever the solver, not the pre-flight, is what discovers the infeasibility. Left for a
follow-up rather than added under this fix wave's scope.

**A second gap, found by the re-review of that fix wave.** Decoupling the pool's season from the
resolver's (the changeover fix) reopened a narrow cross-season hazard on the *metric* side.
`CandidatePool.load` picks its bridge join key from `window.isResolvedSeason()` alone: when true it
joins on the raw FPL element id, with no uniqueness guard — that guard exists only on the
name-bridging branch. Before the fix, pool and metrics were always drawn from the same season, so an
id match was always semantically an id match. Now the pool follows the capture's season while the
metric window follows the resolved one, and FPL renumbers every element every season, so a mismatch
between the two can pair a real current-season candidate with an unrelated same-numbered player's
statistics.

It does not fire in the changeover case the fix was written for: there the resolved season is
completed, so `HorizonScorer.fixtureDifficulties` returns nothing, every score is zero, and the
"could not score anyone" warning fires. The untested trigger is the inverse — a *stale* daily-price
capture lagging behind an already-advanced resolved season, which needs the capture job to stall for
longer than tapas takes to sync past five gameweeks. That inverts this project's usual assumption
that the capture is fresh and tapas lags, which is why it is recorded rather than fixed here.

The fix is to apply the same uniqueness discipline on the id branch that the name branch already
has, or to refuse to bridge at all when the capture's season and the metric window's season differ.

## Defensive contribution reaches xPts/90 (2026-08-28)

The capture half shipped on 2026-08-26; this is the scoring half, and it closes KAR-29 (the
migrated GitHub #46). `ExpectedPoints.perNinety` now carries a fourth term, so both the pick tools
and the squad builder stop understating ball-winning defenders and holding midfielders.

Plan: `docs/superpowers/plans/2026-08-28-defcon-in-expected-points.md`.

**A modelled probability, not a count of past crossings.** Defensive contribution pays 2 points at
a threshold *per match* — 10 clearances, blocks, interceptions and tackles for a defender, 12 of
those plus recoveries for a midfielder or forward. The term is `P(actions >= threshold) x 2`,
estimated from the player's mean actions per match.

The first version counted crossings instead: `hits / qualifying matches`. That was wrong, and the
owner caught it. A ranking wants how often a player *will* cross the threshold, and a count of
past crossings estimates that poorly — it reads only "over or under" and throws the action counts
away, so a defender who made 9 in every match scored identically to one who made 2, and a
two-match record could only ever say 0%, 50% or 100%.

The defence of counting was that a mean cannot express a threshold. True only of a mean used
*linearly*; taken through a distribution it expresses it exactly. A player averaging 9.8 actions
has a 49% chance of reaching 10, not none.

**Negative binomial, not Poisson.** Over 2025-26, on matches of 60+ minutes, within-player
variance is **1.34x the mean** at the median (1.38 for defenders and midfielders, 1.19 for
forwards). Poisson assumes exactly 1.0, so it is the wrong distribution; the negative binomial is
the Poisson generalised to admit that spread.

`DEFCON_DISPERSION` is 1.6, deliberately above the measured 1.34. A first measurement said 1.60,
but it was taken from data without minutes or zeros, and cameo appearances had inflated it; the
review that re-measured on the correct population also found that prediction *improves* as the
constant rises towards 2.0. The reason is that the mean the model reads is itself uncertain when
it comes from three matches, and the constant is the only place that uncertainty can go. So 1.6 is
a middle choice between the measured spread and the predictively best one, not tuned on the data it
was tested on. The principled form — a posterior predictive whose spread widens by itself when the
sample is short — belongs with KAR-38.

**Measured, not asserted.** Predicting the rest of each player's 2025-26 season from his first
few 60+ minute matches, the distribution's error is **28% lower than the hit rate's after 2
matches, 24% after 3, 20% after 5 and 7% after 10** — it wins by most exactly where the sample is
thin, which is where a ranking is most easily embarrassed. It also beats Poisson at every sample
size.

**Calibration, per player.** Over a held-out half-season the mean of the per-player predictions is
0.216 against an actual 0.205, and within ±0.015 in every band of predicted probability below
0.5. Above 0.5 it over-predicts by about 0.08 (27 players): regression to the mean, and those are
the players a ranking puts at the top, so the best DC candidates are overrated by roughly 0.17
points of `xPts/90`. Modest, and KAR-38's blending is the fix. Per position across the whole
season: defenders 0.255 predicted against 0.251 actual, midfielders 0.167 against 0.157, forwards
0.016 against 0.009. (An earlier check that pushed the *league* mean through the model gave
midfielders 10.6% against 14.0% — that gap was Jensen's inequality, not miscalibration.)

The mean is taken over matches of 60 minutes or more, the same discipline the clean-sheet term
uses — the actions arrive with the minutes, and a cameo would otherwise read as a match the player
failed to contribute in. Below `MIN_DEFCON_MATCHES` (two, the same sample the 180-minute floor
already admits a player on) the term is **dropped, not estimated**, and the reply prints `DC n/a`.
No component is held to a stricter standard than the row itself.

That floor was a long argument while the model was a hit rate, because a 2-for-2 record read as a
certainty worth a flat 2 points. The distribution mostly dissolves it: a mean from two matches is
noisy but not absurd. **KAR-38** removes the remaining edge by blending last season in on a ramp,
at which point two matches are never read alone.

Goalkeepers get no term: FPL awards them none, and all 20 keepers with an hour behind them read 0
in the live 2026-27 data. Save points and penalty saves are still not modelled, and both replies
keep saying so — that sentence changed from disowning defcon to disowning only those two.

**2026/27 rules, verified 2026-08-28.** Thresholds and points are unchanged from 2025/26
(`~/dev/tapas-fpl-app/backend/FPL_RULES.md`, updated for the season transition, lists them under
"Unchanged from 2025/26"). What did change is *when* the numbers are final: gameweek scores now
settle at 09:00 UK on the day after the gameweek's last match rather than an hour after the
whistle, so post-match Opta review can feed into BPS **and defensive contribution**. A hit rate
feels that more than an average would — a review lifting a defender from 9 to 10 flips a whole
match from miss to hit. Nothing was built for it: `DefensiveStatsSyncService` rewrites every match
row on every run, so the next nightly sync replaces a provisional figure with the reviewed one and
the exposure is bounded to roughly the 27 hours in between.

The 2026/27 BPS changes (CBI now 1 point per 3 rather than per 2, no -1 for being tackled) move
*bonus*, which arrives inside `total_points` and therefore inside `Pts/90` — the realised half of
the Score, which needed no change. There is no double counting: realised points already contain
the defcon points a player earned, exactly as they already contain his goals, and the Score blends
the two halves at a weight rather than adding them.

**Base rates to read a reply against**, from the live API after GW1 of 2026-27 (60+ minutes):
defenders average 7.19 actions and clear 10 in **22.4%** of matches; midfielders average 7.15 and
clear 12 in **14.0%**; forwards average 3.68 and cleared 12 in none; goalkeepers read 0. These
reproduce the 2026-08-26 production run exactly — 22.4% of 85 defenders is the 19 that scored,
14.0% of 86 midfielders is the 12 — so a defender showing `DC 40%` is genuinely well above the
league, not a scaling error.

**Both tools read it through the one shared formula**, which is why `CandidatePool` changed in the
same commit series as `PickTools`: `ExpectedPoints` was extracted precisely so the squad builder
and a picks list cannot value the same player differently in one conversation, and supplying the
new keys to only one of them would have reintroduced that.

**Follow-up filed.** The same single-season defect runs through every ranking metric, not just
this one, and the owner specified the fix: a weighted blend of both seasons on a ramp driven by
the player's own match count (1 match -> 10% this season, 2 -> 30%, 3 -> 60%, 4 -> 80%, 5-7 ->
90%, 8+ -> 100%). That is **KAR-38**, with KAR-11 (pick tools), KAR-39 (squad builder) and KAR-40
(`ExpectedStatsTools`, which has no cross-season fallback at all) under it.

**Verified:** 573 tests green against a freshly reset container. The suite was run after each task
with no existing expectation edited, except one that had to change — the pick footer's disclosure
sentence, which the work rewrites on purpose.

## Cross-season blending: one ramp replaces the GW5 switch (2026-08-28)

Every ranking metric read **one** season. `SeasonResolver.betterMetricSeason` handed a caller the
previous season until the season being played had five completed gameweeks, then that season and
nothing else. Binary and league-wide: in GW4 a player's own four matches counted for nothing, in
GW5 last season counted for nothing, and a player who had missed the opening rounds was judged
from GW5 on however little he had — the switch counted the league's gameweeks, not his.

Closes KAR-38 with KAR-11, KAR-39 and KAR-40.
Spec: `docs/superpowers/specs/2026-08-28-cross-season-blending-design.md`.
Plan: `docs/superpowers/plans/2026-08-28-cross-season-blending.md`.

**The ramp**, owner's decision, keyed on the player's own qualifying matches (60+ minutes) this
season: 1 → 10%, 2 → 30%, 3 → 60%, 4 → 80%, 5-7 → 90%, 8+ → 100%. Per player, not per gameweek,
so someone who sat out three of the first four rounds does not get 80% on a single appearance.

**Rates blend, evidence counts add.** `xg90` at two matches is 30% this season and 70% last;
`mins`, `matches` and `defcon_matches` are both seasons together, because the floors that read
them (`MIN_WINDOW_MINUTES`, `MIN_DEFCON_MATCHES`) ask how much evidence exists and evidence from
either season counts. It lives in one class, `SeasonBlend`, so the ramp is written once —
`ExpectedPoints`, `HorizonScorer` and `SquadSolver` did not change at all, which is the point:
the blend produces exactly the row shape they already read.

**A player with no previous record** is ranked on this season alone once the minutes floor is
cleared, and every reply marks him. There is nothing to ramp from, and hiding him would be worse
than showing him with his match count attached.

### What the measurement said, and it is not comfortable

Measured on vaastav's 2024-25 → 2025-26 (479 players bridged by name; nothing from 2024-25 was
written to the database or the repository — the project holds 2025-26 and 2026-27 only). Today is
2026-08-28 and 2026-27 has one completed gameweek, so the real pair cannot be measured yet; these
are the two most recent completed seasons standing in for it.

Predicting a player's later `xG/90` from his first *m* qualifying matches, RMSE, target = his next
five:

| m | ramp | this-only | last-only | ramp vs better baseline |
|---|---|---|---|---|
| 2 | 0.1083 | 0.1191 | 0.1109 | **+2.4%** |
| 4 | 0.1330 | 0.1463 | 0.1239 | −7.3% |
| 6 | 0.1411 | 0.1470 | 0.1236 | −14.1% |
| 8 | 0.1325 | 0.1325 | 0.1056 | −25.5% |

**The ramp beats both baselines at two matches and loses to last-season-only from three onward.**
The same shape holds for `xA/90` and `xGC`, and on three different targets. The cause is sample
size: a full previous season is 30+ matches and eight matches of this season is not, so for a
*rate* the larger sample wins even though it is a season stale.

Split by whether the player changed club (target = next five, `xG/90`) shows what the average
hides: for a mover the ramp is neutral to slightly better (+1.1% at m=2, +2.0% at m=6, −0.4% at
m=8), and the whole loss sits with players who stayed put (−32.1% at m=8). That is the case the
ramp exists for — a new club, a new role, a promoted side — and RMSE over everyone averages it
away.

**Shipped as specified.** The table is the owner's decision and the plan's rule was that Task 0
reports rather than tunes. Two things for the owner: reaching 100% at eight matches is early for a
stable player, and on this evidence a ramp that topped out nearer 90% and much later would measure
better for the majority. Re-measure on the real pair, 2025-26 → 2026-27, after GW10 — filed in
Linear.

### Provenance, which had to change shape

The pick tools keep **one** banner. The previous season is named on every pick
(`blend 30% 2026-27, 70% 2025-26 (2 mt this season)`) and once in the footer. A second
`Data source:` line would claim every number below it is last season's, and under blending none
purely is. The squad builder keeps its two lines and gains a blend sentence under the metrics
line, with the weight on each starter where it is below 100%.

`ExpectedStatsTools` is the opposite case and does get a second banner. It shows fixtures as
rows, and a row cannot be weighted — so a short current-season window is *filled* from last
season instead, each row labelled with its own season, the heading counting how many came from
each. The season prefix appears only when the reply actually mixes two: a single-season window
needs no prefix, because the banner above already names it.

The system prompt's "Before GW5 / From GW5" rule is gone. The model reads the printed weight
instead, which is per player and already correct; a gameweek number was always a worse proxy.

**Verified:** 587 tests green against a freshly reset container.

## Email verification: the runbook moves to a real domain (2026-08-28)

Owner decision: buy a domain rather than work around not having one. It is **`fpl-sidekick.com`**,
registered at Namecheap the same day, and `docs/EMAIL-SETUP.md` is rewritten around it.

**Most of the old runbook's difficulty was the missing domain.** SPF and DKIM live in DNS on the
domain mail is sent *from*, the app answered only on `fpl-assistant.fly.dev`, and a `.fly.dev`
subdomain is not yours to add TXT records to — so no provider could send as
`noreply@fpl-assistant.fly.dev`. That forced a choice between Gmail SMTP as a stopgap and Resend's
`onboarding@resend.dev`, which delivers only to the Resend account owner and is therefore useless
for testers. With a domain both compromises disappear and the runbook becomes a sequence.

**The domain carries the app as well as the mail.** Sending from `noreply@<domain>` while the
confirmation link lands on `fpl-assistant.fly.dev` is a sender/destination mismatch — the shape
phishing filters are built to notice, and a cautious tester notices it too. The DNS work is the
same either way. Mail-only remains a valid intermediate and the runbook says which tasks to skip
for it.

**The move is small in this repository, and that was worth verifying rather than assuming.**
`grep -rn "fly\.dev"` over `backend/src`, `frontend/src`, `fly.toml` and `.github` finds exactly
one match: the deploy workflow's smoke-test URL. Nothing else knows the hostname. The confirmation
link's destination is Supabase's **Site URL** alone, because `SupabaseAdminClient` deliberately
sends no `redirect_to` — one place decides, and it is a dashboard field rather than code. Besides
the smoke test, the only change is the `CORS_ORIGINS` secret — and that one is not optional, which
was learned the slow way. The runbook first claimed the secret needed no change because the frontend
is same-origin; the first sign-up on the domain then refused a valid code. A browser sends `Origin`
on every `POST`, and behind Fly's proxy the application sees itself as `http://…:8080`, so its own
`https://` origin reads as foreign and Spring answers `403 Invalid CORS request` — which the frontend
displays as *"That invite code is not valid."* Found by adding `-H 'Origin: …'` to a `curl` that had
been passing without it. The runbook's 4.1 now says so.

**The first confirmation mail then failed with `500 Error sending confirmation email`, and the
cause was Resend's DNS layout changing under the runbook.** The domain had been verified with an
SPF TXT and an MX on `send`; the dashboard then asked instead for two CNAMEs (`send` →
`send.forge.rmta.net`, `rsend` → `rsend-euw1.forge.rmta.net`) and marked them *Pending*, and Resend
does not send while they are. Adding the CNAMEs beside the old records was not enough — Namecheap
allows a CNAME next to a TXT and MX at the same name, which DNS forbids, and the nameserver kept
answering the stale SPF. Deleting the TXT and MX on `send` verified the domain within minutes;
GoTrue's `/auth/v1/resend` then answered `200` and the mail landed in Gmail's inbox from
`noreply@fpl-sidekick.com` (2026-08-29 00:00 UTC). The runbook's 2.3 and 2.4 describe the CNAME
layout and the deletion. One place on the `testi` code was burned by the failed first attempt —
`uses` reads 2 for one account.

**Its DNS was read rather than assumed**, and it carries two things that shape the work. The apex
already has an SPF record — Namecheap's email forwarding — and a domain may have exactly one: a
second makes it fail SPF outright, which is worse than having none. Sending from
`send.fpl-sidekick.com` sidesteps that entirely, which turns the subdomain from a tidiness
preference into the recommendation. And the A record points at Namecheap's parking page, so
Task 1 is a *change* to that record, not an addition; leaving both would send roughly half of all
requests to a parking page.

### Stale documentation corrected the same day

Three claims were checked against production and were wrong:

- `EMAIL-SETUP.md` and this file both said **not deployed**. PR #52 merged and master deploys on
  push; `/actuator/health` answers `UP` and the deployed jar contains the `email_confirm: false`
  plus `/auth/v1/resend` path.
- `docs/TODO.md` said `SUPABASE_SERVICE_ROLE_KEY` and `INVITE_CODE` were **not set**. Both are set
  and `Deployed` (`fly secrets list`, 2026-08-28). `INVITE_CODE` is vestigial anyway — codes have
  been rows in `fpl_invite_code` since PR #52.
- `docs/TODO.md` described email verification as **open**, with the note that `email_confirm: true`
  creates confirmed accounts. That is the defect PR #52 fixed.

`docs/TODO.md` also still called itself the working backlog. Issue tracking moved to Linear on
2026-08-28; the file keeps its history and says so at the top.

## Resend chosen as the mail provider (2026-08-28)

`docs/EMAIL-SETUP.md` is rewritten a third time, now concrete: Resend, sending from
`send.fpl-sidekick.com`, with the exact DNS records, SMTP fields and verification commands.

**The choice was re-made on the right basis.** The first recommendation was "Resend, because
thirty testers fit the free tier" — the owner rejected the framing: the service has to be built
for perhaps five thousand subscribers, not for the test round. That inverted the reasoning without
changing the answer.

At the scale being planned for, mail is a rounding error. Five thousand users at the measured
EUR 0.0146 per message is thousands of euros a month in language-model cost; $20/month of email is
a fraction of a percent of it. When cost does not decide, what breaks decides. Resend works today
with no application and no waiting, and it keeps this project's sending reputation separate from
`kurssialusta`'s.

**Amazon SES was considered seriously and rejected for now**, having examined the neighbouring
`kurssialusta` project, which is building on it. Two independent blockers: its production-access
request is mid-conversation with AWS as of 2026-08-28, so the account is still in the sandbox and
can only send to individually pre-verified recipient addresses — the exact opposite of a sign-up
confirmation flow; and SES bounce and complaint rates are account-wide per region, so a marketing
list of 25,000 messages a month sharing the account could suspend sending for sign-up mail that
has nothing to do with it. SES is about 400× cheaper per message ($0.10 per 1,000 against
Resend's effective rate), and that is the right trade to revisit **only if broadcasts to all users
become a real feature** — at which point `kurssialusta` will already have the SES-plus-Listmonk
stack and the expertise to run it.

**Plan: start on Free, upgrade to Pro before the invite code is handed out.** The binding
constraint on the free tier is not its 3,000 messages a month but its **100 a day**. Five thousand
sign-ups over six months averages 28 a day, but launches do not average, and the day sign-ups
spike is the day the cap silently drops the 101st confirmation.

### Three traps, one of them measured

The runbook now names three, and the first was verified rather than predicted:

1. **The parking A record is still live**, alongside Fly's. Namecheap implements a URL Redirect
   Record by pointing the apex at its own server, so the redirect *is* the second A record. Ten
   straight requests to the apex returned 200 — and the parking address answers nothing at all, so
   curl was silently retrying the other one. Certificate issuance did not clear it either: both
   certificates are Issued and the fault remains.
2. **One SPF record, and one DMARC record.** The apex already carries Namecheap's forwarding SPF.
   The DMARC half of this rule came from reading `kurssialusta`'s runbook, which has the fault
   live on its own domain: two `_dmarc` records make DMARC inert, and nothing warns you.
3. **Namecheap appends the domain to the Host field.** Pasting `send.fpl-sidekick.com` verbatim
   produces `send.fpl-sidekick.com.fpl-sidekick.com`, which fails silently — no error, just a
   domain that never verifies.

Traps 2 and 3 were both taken from `~/dev/kurssialusta/docs/vaihe-0-ses.md`, which is a more
mature runbook for the same class of work. So was the correction that a DMARC `rua` address must
exist and must be at the same domain — an address at `gmail.com` needs an authorisation record on
Google's side that you cannot add. The earlier version of this file named
`dmarc@fpl-sidekick.com` without saying it had to be created first.

## Announcing changes to every user: an export, not a sync (2026-08-29)

The question was whether everyone Resend has sent mail to could land on a contact list at the
same time, for the day a change to the service has to be announced. Resend does not do that:
SMTP sends add nobody to an Audience; contacts arrive only through the API, a CSV, or by hand.

Two ways to build the list, and the second was chosen. The first is an API call from
`SignupController` as each account is created. It costs a second Resend key with contact-write
permission as a Fly secret, a failure path that must not fail sign-up, and — the deciding
point — a second copy of the list that starts drifting the moment an account is deleted in
Supabase: the contact stays, and receives the announcement. The second is to have no list
between announcements at all. `auth.users` already is the list, and
`scripts/sql/export-mail-contacts.sql` reads it at the moment an announcement goes out — into a
CSV, into an Audience, into a broadcast. It cannot drift because it does not persist. The cost
is one manual step per announcement, which is the right trade for as long as announcements are
rare. `docs/EMAIL-SETUP.md` Task 6 is the procedure.

Three things had to be verified rather than assumed, and one of them changed the query:

- **`confirmation_sent_at` means "mail was handed over", not "account exists".** Read from
  GoTrue's `internal/api/mail.go`: `sendConfirmation` calls `sendEmail` first and stamps the
  column only on success. That matches what the failed sign-up of 2026-08-28 left behind — an
  account with the column `NULL`. `sendInvite` stamps the same column, so a dashboard invite is
  covered; password recovery stamps `recovery_sent_at` instead, and the query tests both so it
  stays right when that flow arrives.
- **The Supabase SQL editor caps results at 100 rows by default**, the CSV download contains
  only the rows the grid holds, and the only sign is a small *(Limited to only 100 rows)* beside
  the row count. Read from Studio's source (`sql-editor-session-state.ts`, `limit: 100`; the
  menu offers No limit / 100 / 500 / 1,000). The runbook now says to set *No limit* first.
- **The dashboard CSV import always updates existing contacts** — the *skip* option exists only
  in the API. That is what makes a one-column CSV a rule and not a preference: an `unsubscribed`
  column set to false would resurrect everyone who had opted out.

Also verified: Resend's Suppressions list adds hard bounces and spam complaints by itself and
skips them on every later send, so a bouncing address needs nothing done to the account. The
runbook's first draft said to delete the account; that would have destroyed a possibly active
user's data to fix a statistic Resend had already fixed, and it is gone.

The one honest limit of a broadcast: contacts who unsubscribed from an earlier announcement are
skipped on the next, whatever it says. Accepted for rare, informational notices. A notice that
must reach every account — a shutdown, a change to the terms — would need to go out
transactionally through the Emails API, and that is code to write when such a notice exists.

Pricing was re-read the same day: Audiences and Broadcasts are on the same account and the same
verified domain, free to 1,000 contacts, $40/month at 5,000. The runbook had called Broadcasts a
separate product at $40; at this project's size it is neither separate nor paid.

## Production runs without reasoning, and what a model with it would cost (2026-08-29)

Found while pricing Sol against Terra: `reasoning-effort: none` in `application.yml` is not a
tuning choice but OpenAI's constraint — the GPT-5.6 family refuses function tools with any
reasoning effort on Chat Completions, and Spring AI 2.0.1 has no Responses API (spring-ai #2962,
open). Every production answer has been written without reasoning since the chat endpoint was
built, which is a structural candidate for the uneven quality the owner has noticed, and Sol has
the same restriction, so it would not buy a fix.

`docs/LLM-PRICING.md` holds the full workings: list prices with cache terms for OpenAI, Anthropic,
xAI, Google, DeepSeek and Mistral; per-message cost on the measured token profile (with the
thinking-token assumption stated); the Artificial Analysis index beside each; and the paths to
reasoning. Short version: Grok 4.6 is the first to trial (index above Sol, $6 output, reasoning
with tools on Chat Completions through the existing OpenAI module by changing `chat.base-url`,
EU endpoint), Sonnet 5 second. Two corrections to earlier notes on the way: OpenAI now charges
GPT-5.6 cache writes at 1.25× and reports them only on the Responses API, so `total_tokens`
undercounts them; and Anthropic prompt caching was never configured in this code, so a bare
provider switch would pay full input on every turn.

Re-checked 2026-09-03, on the owner's question whether reasoning is reachable by leaving Spring
AI. It is — LangChain4j's `OpenAiOfficialResponsesChatModel` speaks the Responses API with tools
and `reasoningEffort` — but the migration would buy the dearest reasoning in the band, so the
answer stays "switch model, not framework". The trial order changed: Gemini 3.8 Flash (GA
2026-09-02, index 59, $0.75/$3.75 promotional to year-end) is now first, Grok 4.6 second,
Sonnet 5 third — Sonnet's scheduled September increase was cancelled and $2/$10 stands.
`docs/LLM-PRICING.md` carries the updated tables and the Gemini caveats (promo end, EU data
location, thinking + tools in `spring-ai-google-genai` unverified).

## iOS keyboard, and reading while the answer streams (KAR-22, KAR-46, 2026-08-29)

Two scrolling faults in the chat, fixed together in PR #64 because they share the thread's
scroll behaviour. Both wait on verification by hand before the PR leaves draft.

**The composer under the iOS keyboard (KAR-22).** `index.html` asks for
`interactive-widget=resizes-content`, which Chrome and Firefox honour by shrinking the window
when the keyboard opens. WebKit does not — bug 259770 was still `NEW` on 2026-06-07, with a
comment asking whether anyone is working on it — so on an iPhone `100dvh` stays full-height and
the keyboard paints over the composer. The decision the ticket asked for is taken: a JavaScript
fallback rather than accepting it, because FPL's audience is phone-first and this is the first
thing an iPhone tester meets.

The fallback is one tested pure function and one thin hook. `keyboardAwareHeight` reads the
visual viewport's height against the window's and answers a shell height only for a
keyboard-sized inset — at least 100 px, so the collapsing URL bar does not trigger it, and only
at scale 1, so a pinch-zoom does not either. `useKeyboardAwareViewport` writes that as
`--app-height` on `<html>` and removes it otherwise; `.shell`, `.root` and the sign-in page read
`var(--app-height, 100dvh)`, so wherever the property is absent nothing has changed. It is
mounted once in `App`, above both the chat and the sign-in form, whose password field sits under
the same keyboard. The hook also undoes WebKit's scroll-to-reveal of the focused field
(`window.scrollTo(0, 0)`), which with a shorter shell would only push the header off the top —
if a device shows that fighting Safari as jitter, the documented alternative is to follow the
scroll with `translateY(visualViewport.offsetTop)` instead of undoing it — and re-pins the
thread to its bottom if it was there before the shell shrank.

**Auto-scroll overriding the reader (KAR-46).** Owner report: while an answer streams you cannot
scroll up to re-read, because `MessageThread` called `scrollIntoView` on every token
unconditionally. Now a `scroll` listener records whether the reader is within 40 px of the
bottom (`followsBottom`, tested), and the token-time scroll runs only then. Sending a message
re-arms following so your own message always comes into view. While streaming the scroll is
instant rather than smooth: a smooth scroll chasing a target that moves every token never
settles, and mid-animation the "at the bottom" reading would flap and stop following on its own.
Finished messages keep the smooth scroll, still gated on `prefers-reduced-motion`.

**The frontend now has a test runner.** Vitest, Node environment, `npm test`, on a
`vitest.config.ts` kept apart from `vite.config.ts` because that one refuses to load without the
Supabase key. Ten tests over the two pure decisions; the hooks and components are verified by
hand, which for a software keyboard is the only honest test. Details in `docs/DEVELOPMENT.md`.

**Not yet verified on a device.** The plan's Task 4 checklist — composer above the keyboard,
thread pinned or left alone as appropriate, no jitter while typing, sign-in field reachable,
rotation, dismissal, Android unchanged — is the owner's, on an iPhone, and the KAR-46 check by
hand on desktop (scroll up mid-stream, view stays; scroll down, it follows; send, it jumps).
Results go here when they exist; until then PR #64 is a draft.

One thing learned the cheap way: a JSX comment placed before the root element inside
`return (...)` is a syntax error, and `Array.prototype.at` is ES2022 while `tsconfig` targets
ES2020 — both caught by `tsc -b` before the commit, which is what it is for.

## Pick tools read the daily capture (KAR-32, 2026-09-02)

`getDifferentialPicks` and `getCaptainCandidates` now build their candidate pool — identity,
club, price, ownership, availability, news — from `fpl_daily_price` at its latest capture,
never from the weekly tapas `player` table (measured 85 days stale, the ticket's complaint).
Metrics still come from `player_fixture_stats`. The reply carries two provenance lines, the
documented `SquadTools` shape: the capture's (`DailyPriceSource.provenance()`, season + capture
date), then the metrics' (`SeasonContext.provenance()`). Two sources, not two seasons — the
one-season-banner rule for these tools stands.

Three decisions taken in the plan, none contested:

- **No fallback to the tapas pool when nothing has been captured.** The tools refuse with the
  same sentence `SquadTools` uses. A pool built from stale prices *is* the bug, and labelling it
  does not make a wrong price right; `DailyPriceFreshnessCheck` alerts when the capture misses,
  so "no capture" is a first-day-in-production state.
- **The captain tie-break moved from `total_points` to blended minutes.** The capture does not
  carry `total_points`, and total points ≈ pts/90 × minutes, so what the old tie-break rewarded
  between equal Scores was playing time — `mins` (already blended, already printed) keeps that
  intent without adding a column to a time series.
- **Ownership comes from the capture** — `selected_by_percent` is captured daily, so the
  ticket's staleness question resolves to "no staleness note needed".

The metric join depends on the seasons matching: by element id while the capture and the
resolved season are the same season, by unique `web_name` when they are not. During the
changeover the capture already carries the new season under new element ids while the resolver
still names the completed one, and FPL renumbers every player in between — an id join across
that boundary attaches the completed season's numbers to whichever new player inherited the id.

Follow-ups filed while planning, not in this change: `squad/CandidatePool.load` has exactly that
changeover gap (KAR-45; the fix is the same `sameSeason ? id : name` clause, and its private
`latestCapture()` should then delegate to `DailyPriceSource.latestCapture()`), and
`PickTools.avgFdrByTeam` still reads the tapas `fixture` table until KAR-10 gives it an
FPL-sourced alternative.

## Gameweeks and fixtures captured from FPL (KAR-10, 2026-09-02)

`getGameweekInfo` and `getUpcomingFixtures` now answer from two new snapshot tables,
`fpl_gameweek` and `fpl_fixture`, captured daily at 02:10 UK from FPL's own
`bootstrap-static.events` and `/api/fixtures/` by `ScheduleSyncService` — the schedule
counterpart of the price capture: season code from the UK date, team names from the payload,
owing tapas nothing. Four decisions from the plan:

- **Snapshot, not a dated series.** One row per (season, id), overwritten daily; rows FPL no
  longer publishes are deleted in the same transaction. Prices need history, a fixture list
  needs to be current.
- **Capture first, tapas as fallback**, chosen by whether the capture holds rows. The tapas
  path is unchanged underneath, so the pre-first-capture state and the existing tests keep
  working, each under its own provenance line.
- **Gameweek status is derived from `deadline_time` against the clock at read time**, never
  from the captured `is_current` flag — FPL flips it at the deadline, so a 02:10 capture would
  carry yesterday's flag for most of Saturday. The stored flags are reference only.
- **Scope is the two tools the ticket names.** Still on tapas' `fixture` table:
  `FixtureDifficultyTools`, `PickTools.avgFdrByTeam`, `HorizonScorer`, `SquadEnrichment` —
  a follow-up once the capture has run for a while.

The write deletes by id-array (`NOT (id = ANY(?))`), not by comparing `captured_at` to `now()`:
the deletion must be correct even outside the transaction (`sync()` calls `write()` on `this`,
so `@Transactional` would be inert under self-invocation — a `TransactionTemplate` wraps it
instead), and a clock-keyed deletion in autocommit would have deleted every row just written.
An empty gameweeks *or* fixtures payload writes nothing at all: the write ends by deleting what
the capture did not carry, so an empty array served during FPL maintenance would otherwise wipe
the season instead of leaving yesterday's rows standing.

One behavioural difference between the paths, deliberate: the capture path starts the fixture
list at the next unfinished round (judged by deadline and `finished`), while the tapas path
starts at `is_current` and can open a Tuesday answer with Saturday's finished scores.

Remaining before KAR-10 closes: merge, the first production capture
(`--sync-schedule` from a machine with `.env.supabase`, expect 38 gameweeks), and the two
production checks (next deadline and a team's fixtures both open with the capture banner).

Review fixes (2026-09-02), same branch: a July-window guard — the season code flips on July 1
but the 40-day finished-season grace trips only around July 3, so for those days FPL's
still-old payload would have been written under the new code and stood as the latest snapshot;
the sync now refuses any payload whose deadlines all predate the coded period, and refuses a
fixtures list whose kickoffs fall nowhere near the events' deadlines (the two endpoints can
straddle the summer reset). The finished-season fixture fallback became MAX(id) — the old
MIN(id) opened a June answer with opening day's results labelled "Upcoming"; the header now
says "Fixtures" when nothing listed is still to play. `ScheduleFreshnessCheck` mirrors the
price capture's alert (02:10 capture, checked 06/12/18 UK; noisy across the summer reset by
design). The start-gameweek derivation moved to `ScheduleSource.startGameweek` so the two
tools share one deadline arithmetic, both tools' reply assembly is deduplicated, the capture
timestamp read is defensive against pgjdbc type drift, and the test profile pins the FPL URLs
to the fail-fast port like every other capture's.

## Staging app, and both apps scale to zero (2026-09-07)

`fpl-assistant-staging` exists for device testing against a real deploy (created during the
PR #69 iPhone checklist; it caught the iOS focus auto-zoom bug fixed in `4f062ec`). It shares
the production Supabase with the capture schedulers disabled via secrets
(`FPL_DAILYPRICE_SCHEDULED` / `FPL_SCHEDULE_SCHEDULED` / `FPL_DEFCON_SCHEDULED` = false), and
deploys with `fly deploy -c fly.staging.toml --ha=false --remote-only --build-arg
VITE_SUPABASE_ANON_KEY=...` (same build-arg as `deploy.yml`).

Cold start is enabled for the time being, on both apps (`auto_stop_machines = 'stop'`,
`min_machines_running = 0`). When we launch the actual product, we will remove the cold start;
right now the app is in development use only, so the cold start is acceptable — the machines
stop when idle instead of running 24/7, and the first request pays a JVM cold start (tens of
seconds on 512MB). This reverses the 2026-08-18 always-on decision for production, which was
made for the frontend-vs-prod dev loop; the revert values are in the `fly.toml` comment. The
production setting takes effect on the next master deploy.

## getMyTeam separates the Free Hit squad from the continuing one (KAR-49, 2026-09-08)

A Free Hit chip gives unlimited transfers for one gameweek. At that gameweek's deadline, FPL
locks the temporary fifteen as its scoring picks and restores the previous squad for transfers —
but `/entry/{id}/event/{gw}/picks/` keeps serving the Free Hit fifteen until the *next* deadline
passes. `getMyTeam` was reading that endpoint at face value, so for a whole week every
forward-looking answer — transfers, next-gameweek captaincy, chip planning — was built on players
the manager does not keep. The signal was already in the reply (`FplEntryClient` parses
`active_chip`, and the card printed `Active chip: free hit`); nothing acted on it.

`MyTeamService.myTeam()` now checks the locked squad's active chip, and when it is `freehit`
also fetches gameweek `N-1`'s picks through the existing cached `lockedSquad(entryId, event - 1,
budget)` path — the same call the tool already makes for any other gameweek, so it costs one
more unit of the per-turn FPL budget and lands in the same `squadCache` keyed `entryId@event`.
Those picks are exactly the squad restored at the Gameweek `N` deadline: transfers made inside
the Free Hit window belong to the Free Hit team alone and never touch the saved squad underneath.
The reply now carries two labelled blocks — `FREE HIT squad for Gameweek N`, whose picks score in
that gameweek but do not carry forward, and `Continuing squad (restored for transfers at the
Gameweek N deadline from the Gameweek N-1 picks)` — both enriched through one
`enrichment.forElements()` call over the union of their element ids, sharing the `squadPlayers`
and `appendPicks` helpers that used to serve only the single block.

Three cases can't reconstruct a continuing squad: a Free Hit in gameweek 1 (no earlier squad
exists, and the tool does not attempt the fetch — guarded on `event <= 1` so no test can hit a
nonsensical `/event/0/picks/` route), FPL publishing no picks for the previous gameweek, and FPL
being unreachable. The first cut of this fix left those to fall back silently to the old
single-block card — the original bug, just narrowed to three cases instead of every Free Hit
week. A `Continuing` sealed interface now has two variants, `Squad` and `Unknown(String
reason)`; `Unknown` says the squad used for transfers and future gameweeks could not be shown and
includes the reason instead of rendering a second block, so the gap is visible rather than
silently reintroduced. Wildcard, bench boost and triple captain need none of this: a wildcard
squad persists past its gameweek, and the other two chips don't change the fifteen at all.

The system prompt (`## The user's own team`) now tells the model that the Free Hit picks are
scoring evidence about the named gameweek only, that FPL has already restored the continuing
squad for transfers at that gameweek's deadline, and that transfers/captaincy/chip planning must
be grounded on the continuing block. If that block could not be reconstructed, advice stays
conditional rather than defaulting to the Free Hit fifteen. `MyTeamTools`' `@Tool` description
was updated to mention the two-squad reply.

PR review caught an important tense error in the first wording: it said the displayed Free Hit
squad would revert "at the next deadline". That is true only from the point when the chip is
activated, before the Gameweek `N` deadline; this card cannot be fetched until after that same
deadline, when restoration has already happened. The card and prompt now distinguish the picks
still scoring in Gameweek `N` from the squad already restored for transfers.

Verified with five new `MyTeamServiceTest` cases (both blocks present and ordered correctly, 404,
gameweek 1, a spent request budget, and wildcard as the negative case) plus the full suite,
629/629.

Originally left open in PR #71 (resolved by the follow-up below): `MiniLeagueService` had the
same blind spot on both sides of the diff it rendered, not just an opponent's. `opponents()` built
the user's own owned-element set via `ownedElements(entryId, event, budget)`, reading the locked
squad for `event` straight off `MyTeamService.lockedSquad` — the same `squadCache` entry
`getMyTeam` populates with the Free Hit fifteen. Both sides of the "what each rival owns that you
do not" comparison could therefore describe a squad the manager does not keep.

## Mini-league comparisons separate Free Hit and continuing ownership (2026-09-08)

The follow-up to PR #71 applies the Free Hit distinction to both managers in
`getMiniLeagueOpponents`, across `nearby`, `topN` and `entry:`. When either side played a Free Hit,
the reply preserves a labelled comparison of the named gameweek's locked picks and adds a
separate comparison of their continuing squads. Each Free Hit manager's continuing squad comes
from Gameweek N-1 picks, explicitly labelled as restored for transfers at the Gameweek N
deadline. Non-Free Hit squads need no extra fetch. Only differences are rendered, not another
fifteen-player card for each rival; points and ranks remain tied to the locked gameweek.

Both gameweeks use `MyTeamService.lockedSquad` and its existing cache and per-turn budget. Missing,
empty or unreachable previous picks, a Gameweek 1 Free Hit, or a spent budget leave continuing
ownership unknown — never an empty team and never a fallback to the Free Hit picks. The reply
names which side is unknown and withholds the continuing comparison. The user's warning also
survives when the budget prevents fetching standings at all. Additional Free Hit requests may
reduce the number of rivals shown; the fifteen-call budget and omitted-team warning are unchanged.

The tool description and system prompt now distinguish current-gameweek scoring evidence from
future transfer/captaincy/mini-league advice, require conditional advice when either continuing
squad is unknown, and note that post-deadline transfers remain invisible for both managers.

Verified: 25 added `MiniLeagueServiceTest` cases cover either/both Free Hits in every focus mode,
404/503/empty previous picks, Gameweek 1, spent budgets, shared-cache reuse with `getMyTeam`, a
failed bootstrap refresh without an immediate retry, and wildcard/bench boost/triple captain as
negative cases. Full `./gradlew cleanTest test`: 656 tests passed against the disposable local
database.

## Candidate metrics are safe across the season changeover (KAR-45, 2026-09-08)

`CandidatePool` used to attach the resolved completed season's metrics to the daily capture by
`element_id` even when that capture had already moved to the new season. FPL reassigns those ids
every summer, so a new player could inherit an unrelated player's minutes and underlying numbers.
The pool now uses ids only when the capture and metric season agree; across seasons it joins on a
case-insensitive `web_name` that is unique on both sides. The club-fixture denominator remains
keyed to the metric row that actually matched, never to the capture's reassigned player or team id.

Both ambiguity checks see their complete populations. Capture-side name counts run before
availability filtering, so an injured namesake cannot make a fit player look unique; metric-side
uniqueness checks every `player` row in that season, including a namesake with no fixture stats.
A missing or ambiguous match never removes a fit purchasable player: it remains a `BODY` with no
metrics, while a doubtful player with no rankable record stays excluded. Same-season joins still
use ids, and a `LEFT JOIN` to the weekly player table preserves fixture metrics that arrive before
the corresponding weekly player row.

`CandidatePool.latestCapture()` now delegates to the shared `DailyPriceSource.latestCapture()`
reader while retaining its public `Capture` API. Verification against the disposable database:
the focused candidate/pick/squad/blend run passed 91 tests, and full `./gradlew cleanTest test`
passed 664/664. The new regression first failed with swapped Okafor/Bramley minutes and an
incorrectly admitted doubtful newcomer before the join fix.

## My team is a read-only page (KAR-14, 2026-09-08)

Signed-in users can now open `/my-team` from the app menu and see their published squad, bank,
recorded squad value, captain and bench order, player market prices and availability, and chip
availability without sending a chat message. Authenticated `GET /api/my-team` maps the existing
`MyTeamService` result to a page-only DTO: the JWT alone selects the account, and manager legal
name, leagues and unused statistical enrichment never cross the endpoint. It spends no message
quota or LLM call, carries `Cache-Control: no-store`, and shares the existing 20/minute per-IP
bucket because an uncached load does call FPL.

Free Hit presentation follows the continuing-first decision. The squad restored at the Free Hit
deadline and its own funds are the primary view; the temporary scoring fifteen and their funds
stay in a native disclosure, closed by default. If the continuing squad cannot be reconstructed,
the primary area says so and remains empty rather than substituting the Free Hit team. Captain and
bench labels name the source gameweek, so previous-gameweek choices do not read as a new lineup
recommendation.

The page also keeps the limits visible: picks are the named locked-gameweek snapshot and omit
post-deadline transfers; recorded bank/value are not recomputed from current market prices;
selling prices and free-transfer counts are unavailable. `loadedAt` is labelled “Loaded”, not
“FPL updated”: `MyTeamService` assembles it even from cache, bootstrap details may be six hours old,
and a failed refresh may preserve an older player snapshot.

Verification used only the disposable database and deterministic browser fixtures. The focused
my-team/mini-league/rate-limit/SPA run passed, the full backend suite passed 682/682, frontend
Vitest passed 27/27, and the production frontend build and contrast gate passed. Headless Chromium
covered the deep link, menu and Back/Forward, save/change/remove in Settings, unchanged chat state,
normal/Free Hit/unknown/error responses, retry and route/sign-out aborts, disclosure pointer and
keyboard operation without another request, dark mode, and 390×844 plus 844×390 layouts with long
content. No physical iOS or Android device was claimed.

### Plan-conformance review — fixes applied (2026-09-08)

A cross-tab account replacement exposed a missing reset: Supabase can replace signed-in user A
with B without a signed-out render, so the unkeyed shell kept A's squad and disclosure state and
could still publish A's pending response. `App` now keys the signed-in shell by `session.user.id`,
clearing account-owned state and aborting its requests on a user change, but not on token refresh.
The new key regression failed before the fix; browser fixtures verify both already-loaded and
pending-request replacements, and that a same-user refresh neither resets nor refetches the page.

The unstyled player lists now retain explicit list semantics for Safari. Two partial-state wording
fixes keep the page honest when a Free Hit's continuing picks cannot be loaded: unknown chips no
longer claim the squad is known, and the post-deadline transfer limitation is always visible.
Controller tests also verify null player/deadline fields and absent continuing funds through the
actual HTTP serializer, with a non-default request budget.

After review: backend 683/683, frontend 33/33, production build and contrast gate passed. The
headless Chromium fixture checks were rerun with cross-tab replacement and both sources unknown.
Actual Safari/VoiceOver and physical-device checks remain unverified.

## Minutes reliability: research and supporting strategy (KAR-31, 2026-09-09)

The owner requires defender recommendations to favour reliable 60+ involvement, not merely high
per-90 returns. Research is in
`docs/superpowers/specs/2026-09-09-kar-31-minutes-reliability-research.md`: appearance and clean-sheet
points have a real boundary at 60, so neither an average-minutes filter nor scaling the whole
90-minute score is enough. DefCon has no such eligibility rule. Numerical scoring and primary
selection are still unimplemented; the calibration and final planning decision are recorded below.
KAR-31 remains open.

The supporting RAG guide, `strategy-minutes-and-rotation.md`, covers reliability-first defender
selection, captaincy, risky alternatives, missing evidence and changed roles. It distinguishes
historical frequency from a forecast and forbids inventing probabilities absent from the tools.
The defender, differential and captain guides point to it. Review added the red-card exception:
a dismissal does not protect a clean sheet from later team concessions as a substitution does.

Verification: 23 ingestion/retrieval tests passed against the disposable database. The checked-in
corpus ingested as 23 documents / 201 chunks, including nine new-guide chunks; real-embedding
queries for defenders and captains both retrieved the relevant new sections. This is not a live-chat
quality check. Production embeddings were not changed: the normal `--ingest` reload is still needed,
and a deploy alone does not reload them.

## Minutes calibration: history helps, but no safety gate passed (KAR-31, 2026-09-09)

The requested measurement and implementation plan are now recorded:
`docs/analysis/2026-09-09-kar-31-minutes-calibration.md` and
`docs/superpowers/plans/2026-09-09-kar-31-minutes-reliability.md`. The reproducible Python experiment
uses pinned public vaastav CSVs only: 2022/23 prior/history, 2023/24 cutoff grid, 2024/25 selection,
then a frozen 2025/26 holdout. No production data or scoring code was changed.

The 60-minute mean filter admitted 2,386 holdout opportunities and 25.16% of known outcomes stayed
below 60. Mean >=90 was much more selective: 482 admissions, 8.51% misses, 8.66% comparable-pool
coverage, but a descriptive upper risk endpoint of 11.34%. No candidate met the working 5%/10%
selection criteria. The 15% comparison selected on development also failed its holdout criterion:
12.93% observed risk, 15.59% upper. No threshold was retuned to turn those near misses into passes.

This is defender-only, history-only, and gameweek-frozen against the final schedule and proxy
cutoffs, not a reconstruction of historical availability or a verified deadline replay. A missing
row stays unknown. The audit caught default-zero starts in 136 early 2022/23 fixtures and ten exact
2025/26 duplicates; neither can silently distort the rates. Four holdout target rows were unknown.

Appearance and clean-sheet eligibility need their own terms; realised points must also become
per-fixture; DefCon must not acquire a false 60-minute eligibility rule. The earlier interpretation
that primary selection must wait for a passing 10% gate is superseded by the planning decision
below: that experimental risk budget was not the owner's requirement. The negative measurement
is unchanged. Even the modest scoring-diagnostic improvement cannot be attributed to components
alone because its smoothing differs from the linear comparator.

The frozen source/result hashes and full aggregate tables are under `docs/analysis/kar-31/`; raw
CSV files stay outside the repository and database. All 24 synthetic analysis tests passed,
covering chronology, DGW freezing, missing rows, transfers, name collisions, the 59/60 boundary
and artifact mismatch rejection. The 2025/26 holdout is now consumed, not a fresh test set for
another tuned threshold.

## Pick-ranking implementation plans ready (KAR-31 and KAR-35, 2026-09-09)

The owner asked to continue until both urgent tickets had implementation-ready plans. Execute:

1. `docs/superpowers/plans/2026-09-09-kar-31-minutes-reliability.md` — seven tasks: coherent
   schedule/freshness, shared batch metrics and club history, exposure/eligibility, per-fixture
   components, pick ranking, a non-relaxable squad XI rule, and seeded verification/docs.
2. `docs/superpowers/plans/2026-09-09-kar-35-gameweek-horizon.md` — five tasks: DGW regressions,
   shared calendar-GW horizon, real pick-score totals, the same fixture population/eligibility in
   the squad solver, and source attribution/acceptance.

**Primary selection is now specified, not left for another product decision.** A fresh daily
`a` flag plus complete, same-season current-club evidence is required: up to ten latest completed
fixtures, at least 180 minutes, every fixture at least 60. Previous-season minutes do not meet
that current-role floor. This applies to every primary pick/XI position; pins and solver defaults
cannot bypass it. One sub-60 result blocks selection until it leaves the window. Short windows
show their actual support; missing/stale data, early season and injuries can leave no primary
list or legal XI. Bench eligibility remains distinct.

This is a conservative **observed-history policy**, not a calibrated probability or a guarantee.
Its future miss rate and live coverage are unmeasured; no experiment percentage is transferred
to it and no holdout was retuned. The assistant-defined 10% experiment criterion is not a new
product obligation. The plan deliberately accepts abstention instead of silently weakening
"reliably 60+" to an average-minute filter.

The source contract also fixes a concrete upstream trap: `player_fixture_stats.player_team_id`
is stamped from the current bootstrap club across historical rows. Resolve actual historical
club membership from season-scoped canonical fixture sides plus `was_home`/opponent consistency,
not `MIN(player_team_id)`. Enumerate expected fixtures independently; missing results remain
unknown. Review also caught a leading-gap shortcut: the first surviving PFS/daily row cannot
prove registration. Only a positive different-club observation can bound the new spell; without
one, enumerate from season start before taking the latest ten. A newcomer with unknown arrival
can therefore wait ten complete fixtures. A coherent full schedule is necessary to distinguish
a blank or a short history from a partial capture. No new table or chat-time external call is planned.

KAR-35 begins at the **first future deadline**, not remaining fixtures in an already-locked GW.
It counts all fixtures in the same calendar window for every club and multiplies the pick's
per-fixture composite by that count; fixing average FDR alone would not reward a double. Squad
scoring shares that fixture list and expected components but retains its expected-only FDR-adjusted
objective. Unknown schedules never become zero forecasts or primary XI players. These replies
will name daily-capture, metric and schedule sources separately. The unrelated fixture-listing
API and KAR-30's matchup model stay out of scope.

The plans have source-level read-only reviews and explicit seeded acceptance cases, including
the corrected leading-gap case; final review found no remaining actionable gaps. All 24 offline
synthetic analysis tests pass; document links and whitespace checks pass. The backend suite was
not rerun for these plan-only changes. Both plans
remain at implementation Task 0; no production scoring, database or embedding change, issue
closure, push or deployment occurred.

## Minutes-aware ranking implementation outcome (KAR-31, 2026-09-09)

> **Historical and superseded 2026-09-20:** The rule described below was the original KAR-31
> implementation. The current expected-points-only policy is recorded at the top of this file.

Implementation Tasks 1–7 are complete in the current worktree, superseding the planning-state
notes above. Ranking reads now share one repeatable-read daily/metric/schedule snapshot; primary
picks and every generated-squad XI place share the same **consistent observed 60+ history** gate.
It requires fresh, coherent current-season/current-club evidence, at least 180 minutes and 60+ in
every expected fixture of the latest up-to-ten window. A sub-60 fixture blocks selection until ten
later completed club fixtures in that spell move it out; short qualifying windows remain labelled
small samples, stale or incomplete evidence causes abstention, and neither prior-season evidence
nor solver relaxation can
certify the role. Per-fixture expected scoring now models appearance, 60+-conditional clean sheets,
minutes-scaled attack and short/long DefCon exposure; passing remains historical evidence, not a
future-start guarantee or calibrated personal probability.

Verification used the local disposable PostgreSQL database. The plan's focused Gradle selection
passed 267 tests with 0 failures in 42 seconds. The full `./gradlew cleanTest test` passed 702 tests
with 0 failures, 0 errors, 0 skipped in 1 minute 9 seconds, using `backend/.env.test` →
`localhost:55432`. All 24 offline Python analysis tests passed. The focused run included
`IngestionService` and `StrategyToolsIntegration`, proving test-corpus chunking and retrieval
regressions only; it did not reload production embeddings or measure a live selection error rate.

An independent reviewer compared the whole diff with `origin/master` and reported
`No actionable findings.` The review was read-only and the reviewer ran no tests. No production
ingestion or deployment was performed.

## Gameweek-horizon ranking implementation outcome (KAR-35, 2026-09-09)

Implementation Tasks 1–5 are complete in the current worktree on `plan/kar-35-gameweek-horizon`.
The bug had two halves and both are fixed. `ROW_NUMBER <= N` took a club's next N *fixtures*, so a
club that doubled inside the window silently lost the last gameweek the caller had asked about;
and even with every fixture kept, averaging their difficulty gave the extra match no advantage in
the primary Score at all. The window is now N **calendar gameweeks** per club, derived once per
call by `schedule/HorizonFixtures`, and the pick Score is the per-fixture composite **totalled**
over the fixtures the club actually plays in it. A double is an extra opportunity rather than an
unconditional win — 3 twice beats 5 once and still loses to 8 once — and a blank simply
contributes nothing. `SquadTools`, `HorizonScorer`, `SquadSolver` and `Feasibility` score and gate
on the same fixture population and the playable-club set it produces; the two tools can no longer
disagree about which fixtures a club plays. Objectives stay distinct: picks blend expected and
realised per fixture, the squad objective remains expected-only with its existing coarse FDR
multiplier.

The window opens at the **first deadline strictly after** the ranking snapshot's `asOf`, on the
daily capture's own season. That is a product choice, not an unfinished question: these tools
recommend transfers, captains and a starting XI, so a round that is already locked cannot benefit
the new selection, and at an exact deadline that round is excluded. A known window holding no
fixtures is a real zero; a window that could not be read is not a zero at all, and the two are
counted, scored and printed apart everywhere — pick exclusion counters, bench rows, refusals. Past
the final deadline the answer is "no editable gameweek", never a replayed GW38 or an invented
GW39.

Both ranking replies now attribute **three sources**, not two: the daily price capture
(`DailyPriceSource.provenance()`), the metric season (`SeasonContext.provenance()`) and the
schedule capture (`ScheduleSource.provenance()`). The third is a *source* line, not a second
season banner — historical metrics stay labelled historical even when today's price and next
month's fixtures are the season being played, and during the changeover one clarification sentence
says the price line and the schedule line both describe the season being played. A separate
`Schedule evidence:` sentence carries the exact capture instant, the `asOf` instant it was judged
at, the editable gameweek range and the players or clubs the schedule alone removed; end-of-season
truncation is disclosed in the pick footer's evidence sentence and, in the squad reply, on its own
`Horizon:` line. `system-prompt.st` and `AGENTS.md` were updated to match.

Acceptance, each row of the plan's matrix mapped to the test that pins it:
double keeps six fixtures and the fifth gameweek → `HorizonFixturesTest`
`aDoubleGameweekAddsAFixtureWithoutLosingTheLastGameweek`; blank without extension →
`aBlankGameweekShortensTheListWithoutExtendingTheWindow` and `HorizonScorerIntegrationTest`
`aBlankGameweekRemovesAFixtureRatherThanPullingALaterOneIn`; 3×2 beats 5×1 despite worse FDR →
`PickToolsRankingTest.twoFixturesBeatOneSlightlyBetterOneEvenWithHarderOpponents` and
`PickToolsIntegrationTest.aDoubleGameweekOutranksACloseSingleButNotAFarBetterOne`; 3×2 loses to
8×1 → `PickToolsRankingTest.twoFixturesDoNotBeatOneFarBetterOne`; the arithmetic rows (pick 6,
squad 8) → `PickToolsRankingTest.theHorizonScoreIsThePerFixtureBlendTimesTheFixtureCount` and
`HorizonScorerTest.fourPointsAcrossAnFdrTwoAndAnFdrFourFixtureIsEightPoints`; unrounded ordering
and the element-id tie → `equalRoundedHorizonScoresAreOrderedOnTheirRawValues` and
`anExactTieIsBrokenByTheLowerElementId`; missing FDR on one leg →
`PickToolsRankingTest.aDoubleWithOneUnratedLegKeepsItsCountAndSortsAfterAnyKnownAverage`,
`HorizonScorerTest.aDoubleGameweekLegWithNoPublishedFdrKeepsTheCountAndGoesNeutralAlone`,
`HorizonFixturesTest.anUnratedFixtureStillCountsButLeavesNoAverageDifficulty`; the exact deadline →
`HorizonFixturesTest.theWindowOpensAtTheFirstDeadlineStrictlyAfterAsOf`,
`aRemainingMatchOfTheJustLockedRoundIsNotCounted` and
`HorizonFixturesIntegrationTest.staleCapturedCurrentAndNextFlagsDoNotMoveTheWindow`; nextN=5 from
GW37 → `HorizonFixturesTest.theSeasonEndTruncatesTheWindowAndThenLeavesNoEditableGameweek`,
`PickToolsIntegrationTest.theFooterNamesTheEditableRangeAndDisclosesTruncation`,
`SquadFormatterTest.disclosesThatTheHorizonRanOutOfSeasonBeforeTheGameweeksAsked`; after the GW38
deadline → `PickToolsIntegrationTest.afterTheFinalDeadlineThereIsNoHorizonRatherThanAForecastOfZero`,
`SquadToolsIntegrationTest.afterTheFinalDeadlineTheReplyRefusesAndNamesTheMissingGameweek`,
`FeasibilityTest.refusesAfterTheFinalDeadlineByNamingTheMissingGameweekRatherThanTheSquad`; one
club unresolved → `HorizonFixturesTest.anOutstandingFixtureWithNoGameweekMakesBothItsClubsUnknown`,
`PickToolsIntegrationTest.horizonExclusionsSeparateAKnownBlankFromAnUnresolvedSchedule`,
`SquadToolsIntegrationTest.aClubWithAnUnresolvedFixtureIsBoughtForTheBenchAtNotApplicable`;
unknown-or-zero horizon keeps players out of the XI while preserving n/a versus zero →
`SquadFormatterTest.tellsABlankedClubApartFromAnUnreadableOneOnEveryBenchRow`,
`SquadSolverRulesTest.theDirectSolverWillNotStartAPlayerWhoseClubHasNoFixture`,
`SquadSolverConstraintsTest.theDefaultedSolverWillNotStartAPlayerWhoseClubHasNoFixture` and
`noRelaxationStepEverAdmitsAnUnplayableClubsPlayerToTheXi`; the capture/metric changeover →
`HorizonFixturesTest.aCaptureOfAnotherSeasonIsNotThisSeasonsHorizon`,
`CandidatePoolChangeoverTest.reusedIdsDoNotGiveUnmatchedPlayersSomeoneElsesMetrics`,
`PickToolsCrossSeasonTest` and `SquadToolsCrossSeasonTest`; no qualifying candidates →
`PickToolsIntegrationTest.absurdOwnershipAndUnknownPositionReturnFactualMessages`,
`FeasibilityTest.refusesWhenEligiblePlayersCannotFormAnEleven` and
`SquadToolsCrossSeasonTest.historicalOnlyEvidenceRefusesInsteadOfInventingAnEligibleEleven`. Every
row was already pinned; Task 5 added only the three-source attribution assertions
(`PickToolsIntegrationTest.bothRepliesAttributeThePriceMetricAndScheduleSources` and
`aRefusalWithNoQualifyingPlayerStillNamesAllThreeSources`,
`SquadToolsIntegrationTest.theReplyAttributesThePriceMetricAndScheduleSources` and
`aRefusalCarriesTheSameThreeSourceHeader`,
`SquadFormatterTest.carriesAllThreeProvenanceLinesInSourceOrder`).

Verification used the local disposable PostgreSQL container and `backend/.env.test` only. The
plan's focused selection (`*PickTools*`, `*Horizon*`, `*Schedule*`, `*CandidatePool*`, `*Squad*`,
`*ExpectedPoints*`, `*SeasonBlend*`) passed 282 tests with 0 failures, 0 errors and 0 skipped in
52 seconds. The full `./gradlew cleanTest test` passed 765 tests with 0 failures, 0 errors and 0
skipped in 1 minute 13 seconds; it includes `IngestionServiceTest`, `IngestionIntegrationTest` and
`StrategyToolsIntegrationTest`, so test-corpus chunking and retrieval are unaffected.

Honest limits. Fixture difficulty is still FPL's coarse 1–5 FDR: a tie-break in the pick lists and
a single whole-score multiplier in the squad objective, where a hard fixture should move the
clean-sheet term far more than the attacking ones. KAR-30's opponent-specific clean-sheet model is
not implemented and nothing here is a calibration claim — the horizon Score is arithmetic over an
observed record and a published schedule, not a forecast with a measured error. A known window is
also only as good as the capture: rescheduling after 02:10 UK is invisible until the next one. The
strategy corpus was reviewed and deliberately left unedited, because no sentence in it became
inaccurate; nothing was re-ingested. Nothing was committed to `master`, pushed or deployed, and no
production or Supabase query was run.

## Chat SSE header race: security headers written eagerly (KAR-42, 2026-09-10)

Plan and full evidence: `docs/superpowers/plans/2026-09-09-kar-42-sse-concurrency.md`.

**Cause.** Spring Security's `HeaderWriterFilter` wrote the security headers lazily, and on
`POST /api/chat` it wrote them twice with nothing ordering the two writes: once from the MVC
executor thread when the first SSE flush committed the response, and once from the request
thread's `finally` while it unwound after `startAsync`. On MockMvc the two writes collide in
`MockHttpServletResponse`'s header `HashMap`, which is the CI `ConcurrentModificationException`.
On Tomcat the same overlap happens with no error. **Repair:** `SecurityConfig` sets
`HeaderWriterFilter.setShouldWriteHeadersEagerly(true)`, so the headers are written before the
chain runs and the on-commit path is gone.

**Upstream.** [spring-security gh-9175](https://github.com/spring-projects/spring-security/issues/9175)
is fixed by [PR #19103](https://github.com/spring-projects/spring-security/pull/19103). The 7.1.x
forward port, [#19647](https://github.com/spring-projects/spring-security/issues/19647), ships in
7.1.2, which is not yet released; 7.1.1 is the latest. That fix is a compare-and-set: it stops the
second write but still lets the async commit overlap the request thread's write. **Upgrade rule:**
after moving to 7.1.2 or later, keep eager writing unless the four `FIRST_FLUSH_DURING_UNWIND`
cases in `ChatSseLifecycleTest` pass without it.

**Header deltas (Ruling G).** `/api/my-team` keeps its single `Cache-Control: no-store` and now also
carries `Pragma: no-cache` and `Expires: 0`. Static-resource 304s now carry the security cache
headers, which lazy mode skipped. Everything else `SecurityHeadersTest` reads is unchanged.

**Contract coverage.** These already existed and needed no new code:
- Refund vs retained usage, including a failure after only the model name is known:
  `ChatControllerTest` `aFailureThatOnlyLearnedTheModelNameIsStillRefunded` (:209),
  `aFailureAfterTokensWereReportedKeepsTheCharge` (:291) and
  `aMidStreamFailureEmitsAnErrorEventAndStoresNothing` (:489), plus the lifecycle
  `MID_STREAM_FAILURE` cases over both transports.
- No partial persistence: `ChatControllerTest` :489 and the lifecycle `MID_STREAM_FAILURE` cases.
- Ownership 404 (:452), validation 400 (:472), and quota 429 with `Retry-After` (:129).
- Truncation: `turnsLostToTheReadCeilingAreAnnouncedToo` (:189), `aTrimmedHistoryIsAnnouncedToTheClient`
  (:251) and the lifecycle `READ_CEILING_TRUNCATED` cases.
- Event order: the lifecycle `containsExactly(...)` over both transports (`ChatSseLifecycleTest`
  :395, :407), which also guarantees exactly one `done`.
- Bounded cleanup: the lifecycle teardown, and the `@AfterEach` in `ChatControllerTest` (:114).

New in this change:
- `ChatSseLifecycleTest.anAnswerOutlivesABrowserThatClosedTheResponse` (:218) closes a real Tomcat
  response mid-answer, then completes the model. The answer is stored exactly once and the
  measured usage is recorded.
- `ChatControllerTest.persistenceAndAccountingRunOffTheProviderThread` (:316) emits from a
  `provider-loop` thread and asserts `append` and `recordTokens` run on `boundedElastic`. Without
  `publishOn` it fails.
- The `READ_CEILING_TRUNCATED` token is `" an\nswer"`, asserted as the raw wire line
  `data:" an\nswer"` and as the stored content.
- `SecurityHeadersTest.anUnhandledFailureKeepsTheSecurityHeadersOnTomcat` (:199): an uncaught
  exception gives a 500 that still carries `nosniff` and `DENY`.

The lifecycle teardown's wait for recorded tokens had keyed on `total_tokens IS NOT NULL`. That
column is a generated sum of COALESCEd columns, so it is never NULL and the wait passed at once.
It now keys on `input_tokens`.

**Limitations.** On MockMvc, `TERMINAL_DURING_DISPATCH` is observed rather than forced. On Tomcat
the overlap is silent, so only the probe sees it. The probe reads private Spring Framework 7.0.8
fields (`taskExecutor`, `reactiveHandler`, `asyncRequestTimeout`), so a Framework upgrade may need
probe updates.

**CI evidence.** Artifact upload was verified on the PR: run 34486382124 uploaded
`backend-test-results` (504040 bytes). The artifact is now kept 14 days.

**Follow-up.** [KAR-51](https://linear.app/quantti/issue/KAR-51): a small test-profile Hikari pool,
because the full suite runs close to the test database's `max_connections = 100`.

## Chat errors name their cause (KAR-8, 2026-09-10)

Plan: `docs/superpowers/plans/2026-09-09-kar-8-chat-errors.md`. A chat failure now shows one of
five messages naming its cause instead of one generic sentence: `UNREACHABLE` (fetch rejects,
not aborted), `UNAVAILABLE(status)` (HTTP 5xx), `REJECTED(status)` (other non-2xx besides
401/404/429), `GENERATION_FAILED` (a complete backend `event: error`, whatever its data — the
backend text is now `ChatController.GENERATION_FAILED`), and `INTERRUPTED` (a 2xx response with
no body, EOF or a rejected read before a complete `done`, or `done` arriving only as an
undelimited trailing fragment). 401/404/429 keep their existing behaviour. The HTTP/SSE reader
moved out of `useChat` into `frontend/src/hooks/chatTransport.ts` (`sendChatTurn`, 26 vitest
cases); the hook's public API is unchanged. Verified via curl through the Vite dev proxy against
a dead backend, which answers 502 and maps to `UNAVAILABLE(502)`; interactive browser cases
(dev server stopped mid-page → `UNREACHABLE`, backend killed mid-answer → `INTERRUPTED`) are left
to the owner. The composer is now capped at `maxLength=4000`, matching the backend limit. Out of
scope: a fetch with no timeout, a stale turn's `finally` clearing a newer turn's `isStreaming`,
and malformed token frames.
## Stored-data age disclosed per reader (KAR-9, 2026-09-10)

Plan: `docs/superpowers/plans/2026-09-10-kar-9-data-age.md`, 7/7 tasks, from `7721754`. `DataAge`
owns the wording. Player cards and fallback gameweek rows print each displayed row's recorded
write (`Stored-data age:`); fixture lists and FDR outlooks date their rows apart from the
gameweek row that picked the start. Expected stats add per-season `Stored match coverage:` and
the fixed `Metric refresh:` notice, which ranking and squad replies also carry beneath their three
source lines. My Team tags stored xG/xA and FDR with their own ages, separate from the FPL fetch.
The prompt and `AGENTS.md` keep season, record write, match date and capture distinct. There is
no freshness gate, and selection, scoring and schemas are unchanged.

Verified: disposable DB 841 tests, 0 failures/skips; frontend 33 tests and build green. Six
nested clock contexts had pushed the suite past `max_connections` (75 failures), so the tests
now build their tool with `DataAgeClock.provider()` and the pool count matches master (15).

Limits: this surfaces recorded ages and the unknown core-sync time. A trustworthy PFS
source-refresh timestamp needs an upstream writer/coverage contract outside this ticket.
Untested: the solver-infeasible squad refusal. PR review: My Team now dates the starting
gameweek only when some club has an FDR run, so a card without FDR carries no gameweek age. No
live LLM chat was run.

## Squad-builder minors triaged and closed (KAR-37, 2026-09-11)

Plan: `docs/superpowers/plans/2026-09-11-kar-37-squad-minors.md`. Of the nine items the
ticket carried from PR #54, five no longer exist: the season blend (`006dce9`) removed the
duplicate `previousSeason` call, KAR-31 (`03a4ee9`) removed `metric_team_id`, `club_fixtures`
and the `idx_season_current` comment, and KAR-35 (`b1793e1`) removed `fixtureDifficulties`. Fixed the three that remained:
`readsAllThreeComparators` now feeds unsorted input, `Feasibility.POSITION_QUOTAS` and
`POSITION_NAMES` are `List.of`, and the squad card pads position labels so names share a
column. The shared element-id ranges stay as they are — `build.gradle` still runs tests
serially, as the ticket asked.

Verified: disposable DB, full `./gradlew cleanTest test` green (844 tests). No behaviour change
to selection, scoring or provenance; no live chat run.

## The confirmation mail is the welcome mail (KAR-44, 2026-09-12)

Plan: `docs/superpowers/plans/2026-09-12-kar-44-confirm-signup-email.md`. The Confirm-signup
template is now a repository file, `docs/email-templates/confirm-signup.html`, with the subject
in its header comment; the dashboard holds a copy. It says what FPL Sidekick is and what the free
plan gives, shows the link as a button and as a visible URL whose text is its own target, says
the link passes through supabase.co and lands on fpl-sidekick.com signed in, names a real
address for problems because replies to `noreply@` go nowhere until KAR-43, and ends with why
the mail was sent and that ignoring it does nothing. Inline CSS, one 600 px column, no images:
Gmail's constraints, not taste. Rendered at 600 and 390 px and read tag-stripped; screenshots at
600 and 390 px from the snap chromium-browser show one column, the button, the wrapped URL and
the footer under a rule; the tag-stripped text reads as a letter with the URL directly under its
lead-in sentence.

Not done here, by design: the dashboard paste and the Gmail inbox/SPF/DKIM/DMARC check are the
owner's, per runbook 3.4 and 5.2–5.3; the status table row records the date. Supabase's
Management API could apply the template from the repository, but it needs a personal access
token the project does not hold, so the repository stays the source and the paste stays manual.

PR review: removed the welcome copy's unconditional daily-refresh/current-season promise.
The app may fall back to historical statistics and cannot guarantee every sync succeeds;
the mail now promises source and season attribution instead. Verified the HTML's two link
targets, visible fallback URL, email placeholder and absence of external assets.
## Aborted chat turns cannot clear their replacement (KAR-52, 2026-09-12)

Plan: `docs/superpowers/plans/2026-09-12-kar-52-chat-turn-ownership.md`. `useChat` now gives each
send exclusive ownership through its `AbortController`. Conversation replacement detaches A before
aborting its browser read; if B starts and A settles later, A's tokens, conversation id,
truncation notice, outcome and `finally` cannot alter B's transcript, streaming text or busy state,
and cannot fire B's list-refresh callback. Replacement remains authoritative when replacement and
send share a React batch.

The occupied controller is also the synchronous single-flight gate: two calls through the same
captured `sendMessage` before React rerenders produce one request and one optimistic message. The
still-owning turn alone releases the gate and calls `onTurnFinished` once when it settles,
including completed, failed, gone, unauthorized and aborted outcomes. A turn detached by
replacement or unmount never calls it. Server-side generation may continue after the browser read
is aborted; the hook makes no cancellation claim.

Verification on Node 24.19.0: `npm ci` installed 223 packages with 0 vulnerabilities; the complete
frontend suite passed (6 files, 73 tests) with no React act warnings. `npm run build` passed the
contrast checks, TypeScript project build and Vite production build using the existing local
publishable key. Vite retained its advisory that the main minified chunk exceeds 500 kB. No manual
browser check, backend suite, live chat or paid model call was run for this task.
## Fresh database for every content-bearing pre-push (KAR-36, 2026-09-12)

Plan: `docs/superpowers/plans/2026-09-12-kar-36-fresh-pre-push-db.md`. The hook now validates the
explicit local `.env.test` destination and local Docker context before mutation, runs its shell
contracts, then uses verified `reset` rather than reusable `up`. Reset fails on inspection,
removal or schema-load uncertainty instead of accepting an old container. CI runs the same 28
stub-only regressions before the backend suite while retaining its ephemeral Postgres lifecycle
and KAR-42 failure artifacts.

Verified exclusively against the local fixture: all three scripts passed `bash -n`; the standalone
shell suite passed 28/28; a simulated feature-ref hook invocation recreated `fpl-test-db` and ran
`cleanTest test`, passing 844 tests with 0 failures, errors or skips. No fresh-start or connection-
pressure failure occurred. No push, Supabase access or Nutribot container operation was made.

PR #81 review fixes preserve that behavior while making Docker's effective endpoint precedence
exact: explicit `DOCKER_CONTEXT`, then `DOCKER_HOST`, then the active context. Regressions cover
both variables set in both directions and selected-context lookup failure. The integration runner
now propagates reset and clean-state assertion failures explicitly despite running without `-e`;
a safe stub proves a failed first reset exits nonzero without a success message and still runs
owned-container cleanup. Post-review verification: all three scripts passed `bash -n`, the stub
suite passed 32/32, and isolated real-Docker integration passed twice on port 51733 and left
`fpl-kar36-reset-integration` absent.

PR #81 re-review found that both container-ID inspections and dirty-sentinel creation could still
false-green. Those setup failures now return explicitly without changing `RETURN` cleanup; a safe
Docker-stub regression exercises all three paths. Syntax passed, the stub suite passed 33/33, and
isolated integration passed on port 57527 and left its fixture absent. No backend suite or push ran.
## Tool-call rounds are summed into one turn charge (KAR-48, 2026-09-12)

Plan: `docs/superpowers/plans/2026-09-12-kar-48-round-trip-token-accounting.md`, 7/7 tasks.
Spring AI 2.0.0 starts a separate model invocation for every tool-call round; its provider models
do not produce one cumulative usage total for the outer turn. `TurnUsageAdvisor`, registered just
inside `ToolCallingAdvisor`, now gives each invocation subscription its own slot, keeps that slot's
richest coherent cumulative snapshot, and publishes the sum of all slots as usage arrives. Thus a
later failure retains earlier reported expenditure while one quota claim and one
`fpl_usage_event` row still represent the user turn. OpenAI and Gemini prompt counts are normalized
to uncached input before summing; Gemini thoughts enter output once. Historical rows are unchanged:
tool rounds discarded by the former maximum-across-turn logic cannot be reconstructed.

Deterministic provider-shaped fixtures use the real Spring AI model and client/tool loop with only
the SDK stream mocked: Anthropic via anthropic-java-core 2.40.1 sums two rounds to
`24/7/2/7` (input/cache-read/cache-write/output), OpenAI via openai-java-core 4.39.1 sums three
to `140/70/unknown/18`, and Google GenAI 1.58.0 sums the two measured rounds of a three-request
turn to `140/40/unknown/14`. Repeated cumulative chunks count once, request ids do not define round
identity, explicit cache misses remain zero, absent values remain NULL, and usage reported before a
partial-stream failure remains charged. Controller/persistence tests pin one claim/row, generated
turn totals, refund on model-only failure, no partial answer persistence and no accounting data in
SSE or tool schemas.

Limits: these are Spring AI-reported invocation totals, not guaranteed provider billing for every
HTTP attempt. SDK retries happen below the advisor and are unknown unless they emit mapped usage;
an Anthropic failure before a usage-bearing `message_delta` likewise reports nothing. The Spring AI
2.0.0 Anthropic adapter drops cache read/write values found only in `message_start`, so those fields
remain unknown; fixtures establish adapter behavior, not where live Anthropic streams place cache
fields. No inferred counts or fabricated zeros fill either gap.

Verification used only the dedicated `fpl-kar48-test-db` at localhost:55448, loaded through the
verified local `backend/.env.test`. The plan-focused selection, including `AiConfigTest`, passed 79
tests with 0 failures, 0 errors and 0 skipped in 18 seconds. Full `./gradlew cleanTest test` passed
862 tests with 0 failures, 0 errors and 0 skipped in 1 minute 18 seconds; its existing embedding
calls ran normally. No Supabase access, live chat-model request, browser run, schema change,
historical rewrite, provider switch or backfill was performed. The dedicated container remains
running for subsequent push work.
## Gameweek points on the squad card and the My team page (KAR-15, 2026-09-12)

Plan: `docs/superpowers/plans/2026-09-12-kar-15-live-gameweek-points.md`. `FplLivePoints` reads
FPL's `/event/{event}/live/` once per gameweek, cached app-wide for the squad TTL, and is spent
from the turn's budget last so it can only ever lose the player lines. The card and the page show
each pick's points and minutes in the locked gameweek, FPL's own entry total
(`entry_history.points`, parsed since the my-team tools shipped and unrendered until now), and
one status sentence from the bootstrap's `finished`/`data_checked` flags: provisional, finished
but unconfirmed, final, or unknown. The continuing squad carries none. No total is recomputed.
The `MiniLeagueServiceTest` budget arithmetic moved from 14 to 15 because `getMyTeam` now spends
one more call.

Verified: 858 tests, 0 failures/skips; 62 frontend vitest, build green; `entry_history.points`
checked against entry 1556 GW 3: points 78 with event_transfers_cost 4, and the history total
rose by 74, so `points` is gross, before transfer costs — the wording stands. The page was
verified by its tests and the production build only; no signed-in browser session was available.

PR review fix: bootstrap status, locked entry points and live points now retain their independent
read times. A newly final bootstrap cannot relabel older cached scores as final: the card and page
instead call those score snapshots provisional and name what final bonus or substitutions may be
missing. The regression primes provisional score caches before refreshing a final bootstrap.

## Keep production running for daily captures (2026-09-12)

Owner requested always-on operation after Wildcard ranking refused a stale schedule. Production
held 38 gameweeks and 380 fixtures, all captured on 2026-09-11 at 01:10 UTC; the September 12
capture was missing. The September 7 idle-stop setting prevents in-process timers from running
while the machine is stopped. Disabled autostop on the existing production machine through
`flyctl machine update --autostop=off`, started it and verified its live configuration and external
health (`UP`). No image deployment or extra machine. `fly.toml` now preserves always-on operation
with autostop off and one minimum running machine; configuration validation passed. This restores
future scheduled captures, not missed runs: existing stale data is not repaired by starting the VM.

## Personal player shortlist — pre-delivery verification (2026-09-13)

Approved scope is complete in the working tree: account-owned persistent shortlist membership,
validated per-message player catalogues, accessible add/remove controls in assistant Markdown, shared
chat/page/header state, a basic season-labelled shortlist page, dedicated My team/Shortlist navigation
and `/shortlist` routing. The deliberate historical limitation remains: messages saved before catalogue
binding have no identity evidence and stay plain rather than being reinterpreted against a later roster.

Verification used only the ignored `backend/.env.shortlist-test` and dedicated
`fpl-shortlist-test-db` on localhost:55438. A fresh reset followed by full `cleanTest test` passed 892
tests with 0 failures, errors or skips; the pre-push shell contract passed 33/33. Frontend `npm test`
passed 14 files and 103 tests. The production build passed contrast gates, TypeScript and Vite using a
non-secret test-only publishable-key value; only the existing chunk-size advisory remained.

A real headless Chromium run with mocked local auth and `/api` responses verified 320px and 390px
light layouts without horizontal overflow, desktop light/dark layouts, keyboard send/add/remove/theme
and navigation actions, shared mention/count/page membership, and chat preservation across navigation.
No browser console errors occurred. Screenshots are temporary under
`/tmp/fpl-shortlist-{mobile-light,mobile-shortlist,desktop-light,desktop-dark}.png` and are not
repository artifacts. This did not verify live Supabase auth/API persistence, a real model response,
external services or a headed device/browser;
the headless host also lacks the football emoji glyph. Delivery remains Task 5/6 until commit, feature-
branch push, PR and review are complete.

## Per-fixture clean sheets from club xG/xGC (KAR-30, 2026-09-13)

Plan: `docs/superpowers/plans/2026-09-13-kar-30-club-clean-sheet-projection.md`, 7/7 tasks.
The clean-sheet term in both ranking paths was the player's own season-average xGC — one number
for the whole horizon, blind to who the club plays and where, with an average FDR printed beside
it as a separate tie-break. A goalkeeper facing the leaders away and one facing a promoted side at
home scored the same clean-sheet component, and the only opponent signal anywhere near it was a
coarse multiplier that also scaled the attacking term.

The component is now projected per fixture. For club D defending against opponent O:

```text
lambda = defence(D) × attack(O) / meanXg × venue(O)
P(clean sheet | 60+) = exp(-lambda)
clean-sheet points for the fixture = CLEAN_SHEET_POINTS[position] × P(60+) × exp(-lambda)
```

`attack(C)` is club C's mean team xG per fixture-side, `defence(C)` its mean team xGC per
fixture-side, `meanXg` the league mean team xG per fixture-side, and `venue(O)` the league home
or away factor. With both clubs average this reduces to the league's average home or away
concession. `ClubRates` holds the model; `RankingMetrics` reads the sides inside the existing
snapshot (team xG as `SUM` over the club's players per side, team xGC as `MAX` because FPL stamps
the team figure on every player, sides with `xg_against = 0` excluded as unpopulated, clubs
bridged across seasons by unique case-insensitive `short_name` the way players bridge by
`web_name`); `ExpectedPoints.perFixture` takes the projection as an `OptionalDouble` and leaves
the clean-sheet component `null` — printed partial — when there is none, never a season average
standing in for it.

Three decisions. Club rates use the club's whole completed current season blended with its whole
previous season on the shared `SeasonBlend` ramp, keyed on the club's own completed current-season
fixtures: one ramp in one place, and Task 0 measured the alternative rather than tuning it.
League constants (mean xG, home and away factors) pool both seasons without a ramp — the league
mean is not a club's property — behind `MIN_LEAGUE_SIDES = 40` pooled sides, with
`MIN_CLUB_FIXTURES = 2` for a club rate; below either floor the projection is unavailable, not
zero. In the squad objective `fdrAdjust` now multiplies the attacking term only: the clean sheet
already carries the opponent and the venue, so an FDR multiplier on it would count the fixture
twice. Appearance and DefCon are not fixture-scaled, and the neutral-3 label is reworded "on the
attacking term". Pick lists are unchanged here — FDR stays a tie-break.

Task 0 measured the model offline on public vaastav CSVs, 2024-25 as the previous season and
2025-26 held out, rates computed only from rows dated before the predicted gameweek, 720 scored
fixture-sides:

```
variant            Brier    log-loss   n
baseline           0.1839   0.5522   720
opponent           0.1833   0.5484   720
opponent-no-prev   0.1823   0.5468   720
opponent-last10    0.1860   0.5563   720

Brier by gameweek band
GW3-10: baseline 0.1806, opponent 0.1820, opponent-no-prev 0.1792, opponent-last10 0.1820
GW11-20: baseline 0.1895, opponent 0.1918, opponent-no-prev 0.1907, opponent-last10 0.1941
GW21-38: baseline 0.1822, opponent 0.1791, opponent-no-prev 0.1790, opponent-last10 0.1832
```

The gate passes — `opponent` beats `baseline` overall on Brier (0.1833 vs 0.1839) and log-loss
(0.5484 vs 0.5522) — but by 0.0006 Brier over 720 sides, which is thin. The shape is not the one
the plan anticipated: `opponent` is worse than `baseline` in GW3-10 (0.1820 vs 0.1806) and GW11-20
(0.1918 vs 0.1895) and only pulls ahead in GW21-38 (0.1791 vs 0.1822). The last-10-fixture club
window was uniformly worse (0.1860), so the whole-season window shipped. **Open question for the
owner:** `opponent-no-prev` — this season's club rates alone, no previous-season blend — scored
better than the blend overall (0.1823 vs 0.1833) and better in every band. The
ruling was to ship as specified, because the plan says Task 0 reports and does not tune and one
ramp in one place is an AGENTS.md convention; dropping the previous-season club sides is a
one-line change if the owner wants the measured variant instead.

Printing: each pick's `CS` component is now the horizon total with its per-fixture list,
`CS 1.93 over 2 fixtures (vs K07 h 61% at xGA 0.49; vs K12 a 38% at xGA 0.97)`; a fixture with no
club projection prints `n/a` in that list and the row carries the existing partial label. The
footer names the model, `expected/fixture` is the horizon average so `horizonScore = score × count`
is unchanged, and the system prompt now tells the model to quote those per-fixture chances instead
of a season average and not to discount a stopper again for a hard opponent.

Verified: full `./gradlew cleanTest test` passed 899 tests with 0 failures, 0 errors and 0 skipped
against the local disposable container only. No Supabase access and no live chat-model request.

Honest limits. The attack term still has no opponent model at all in the pick lists and only the
coarse FDR multiplier in the squad objective — this ticket modelled the defensive side. `xGA` here
is FPL's own expected data as stored in `player_fixture_stats`, not a third-party xG model, and the
projection is a plain Poisson `exp(-lambda)` with no low-scoring or draw-inflation correction; no
calibration claim is made beyond the backtest above, whose margin is thin. Two known asymmetries
from review: the side read excludes on `xg_against > 0` but not `xg_for > 0`, so an unpopulated
attack beside a populated defence would be read as a real zero — unreachable with real data, since
both columns arrive in the same payload — and clubs choose the direct-id path over the bridge on
`metric.played().currentSeason()` where players use capture/metric season equality. The offline
measurement pooled every previous-season club into the league constants, including the relegated
clubs that bridge to nothing, and after the final review the shipped read does the same — it pools
the raw, unbridged sides of each season — so the measured and shipped populations agree.

The KAR-35 outcome section's sentence that "KAR-30's opponent-specific clean-sheet model is not
implemented" is superseded by this section.

## Wildcard comparison coverage — investigation and proposed fix (2026-09-13)

Plan: `docs/superpowers/plans/2026-09-13-wildcard-data-coverage.md` — planning complete,
implementation not started; no Linear identifier assigned yet.

The morning's wildcard recommendation omitted Arsenal. Read-only investigation found seven
elapsed GW4 fixtures still marked unfinished in the selected 02:10 UK schedule capture, affecting
fourteen clubs; Arsenal's GW4 core PFS rows were also absent. `RESULT_PENDING` makes those clubs'
players XI-ineligible before their points can compete. The solver can still produce a normal-looking
squad from the remaining clubs, and the response failed to disclose that restricted comparison.
This establishes a coverage/advice problem, not that the clean-sheet formula undervalues Arsenal.
The original tool invocation was not replayed, and the source-side reason for the pending flags
has not been established. Tapas' finalized-gameweek gate is a separate possible metric bottleneck.

The plan preserves the hard minutes rule and proposes refusing general squad recommendations
when whole clubs lack relevant match evidence, while reporting individual exclusions separately.
That stricter availability tradeoff needs owner approval. It separates the immediate correctness
guard from a measured sync remedy, which may require a separate Tapas PR; more schedule polls
alone cannot supply absent core PFS rows. Independent plan review found no actionable issues.
No production writes, model calls or implementation changes were made for this investigation.

### Owner clarification — this service owns FPL ingestion

The owner subsequently confirmed that updates belong to **this service, not Tapas**. The earlier
proposal to retain the Tapas writer is superseded by
`docs/superpowers/plans/2026-09-13-owned-matchday-sync.md` (implementation 0/10).
The plan takes over existing core-table writes without changing their schema, combines core and
DefCon history ingestion, polls repeatedly on every matchday, and requires both source
`finished=true` and verified per-fixture player evidence before declaring post-match readiness.
Two stable post-finish observations and subsequent correction checks are proposed; source
finality is not guaranteed by a clock. Durable retries, budget/lease fencing, owner alerts and an
external heartbeat make failures visible. A reviewed cutover disables all Tapas core writers,
including embedded/manual paths, while preserving its unrelated jobs.

Plan review caught two contract gaps, now resolved in the design: NOT NULL/default-zero PFS
columns require durable per-player/component evidence and reader masking for unavailable metrics;
pre-cutover intraday captures require staged publication so actual source timestamps are preserved
without replacing a still-usable prior-day capture too early. The companion comparison-coverage
plan remains required and now points to this ownership plan for ingestion. No updater has yet
been implemented or switched on.

### Owned-sync implementation — Tasks 1–2 foundation (2026-09-13)

Subagent implementation was split into source contracts, durable staging/leases, persisted request
budget and HTTP transport, with orchestrator review between units and a final independent pass.
The ownership plan is now 2/10; this is **not yet a running updater**. Task 3's dispatch/mappers,
core publication, fixture verification, scheduling, reader evidence, alerts and cutover remain.
No beans, scheduled jobs or startup hooks activate this infrastructure; schema initialization is
explicit and the core shared tables are untouched. The old writers have not been disabled.

`sync/OwnedSyncStore` provides season-scoped publication fences, coalescing, expiring leases,
transactional callback publication, bounded staged bytes and retention that preserves active work.
`OwnedSyncRequestBudget` persists one-second spacing, two permits, 5,000 rolling-day attempts
and Retry-After/backoff. `OwnedSyncHttpClient` uses per-client Apache retry/redirect suppression,
bounded bodies/total waits and bounded cancellation acknowledgement; uncertain cancellation holds
its permit until expiry rather than releasing capacity early. These APIs are explicitly invoked,
not a replacement scheduler hidden behind existing jobs.

Eight public source captures and 13 test-only contract tests establish fixture-scoped participant
and minutes evidence. The meaning of nonzero `points_modification` remains unproven; no corrected
point total is inferred from it. Full inventory and limitations are in
`docs/superpowers/specs/2026-09-13-owned-sync-source-contracts.md`. There are another 36
store/budget/local-HTTP tests. Independent review fixes are recorded in the plan outcome.
Production activation still requires source validation/publication, reader integration and an
owner-approved single-writer handover. Linear linking remains outstanding because this harness
has no configured Linear access.

### Owned-sync implementation — Task 3 catalogue and schedule (2026-09-14)

The owned-sync plan is now 3/10. Subagents implemented strict catalogue/schedule parsing,
atomic named-column writers and explicit fetch/stage/resume dispatch in three reviewed units.
Both publication groups use the same validated source batch but preserve their separate 02:00 UK
price and 03:00 UK schedule cutovers. Core row timestamps record writes; capture and endpoint
receipts record actual source reads. A shared season lock prevents cross-group team regressions.
New/coalesced demand requires new enough source observations, so yesterday's or an earlier
same-day batch cannot masquerade as a new update; retries retain their staged work.

Full disposable-database suite: 1004 tests, zero failures/errors/skips. An order-dependent RLS
failure in ad-hoc test-table setup was fixed without weakening the security assertion. Reviews
also fixed historical IDs outside today's catalogue, independent nullable fixture fields,
nullable season end dates, numeric capacity, cross-group locking and source/write-time separation.
The plan's Task 3 outcome records details and remaining boundaries.

The pipeline is still **inert**: no bean, scheduler, CLI or startup activation, no production core
writes, no PFS writes and no Tapas job disabled. Task 4 adds full history/DefCon and component
evidence; verification, scheduling, read-path integration, alerts and reviewed cutover still follow.
AGENTS records the owner's core write-ownership exception with those deployment restrictions.

## Header facelift — FPL Sidekick wordmark and page links (2026-09-13)

Built on the player-shortlist branch (PR #88), which introduced `HeaderNavigation`. The bar now
spans the whole viewport above both the sidebar and the reading column instead of sitting inside
the 660px column: the wordmark **FPL Sidekick** in small caps with the bar's one `--pitch` rule
under it, a vertical rule, then `My team` and `Shortlist` as small-caps labels, and the overflow
menu at the far end. The shortlist count is no longer in the link — the page shows it — so
`HeaderNavigation` takes only the route and `ChatView` no longer reads the shortlist context.
The sidebar carries a small-caps `Conversations` heading above the list. The product is named
FPL Sidekick on the sign-in page and in the document title too; the ⚽ emoji is gone.

Layout: `.shell` is a column (bar, then `.body` = sidebar + column). `.root` lost its explicit
`100dvh` height; it and `.body` are stretched flex items with `min-height: 0`, which is what keeps
the thread scrolling internally. Below 640px the page links always take a full second row of the
bar and the divider is hidden, so the overflow menu is never orphaned on a wrap.

Verified: 103 frontend tests, `npm run build` (contrast check, `tsc -b`, Vite) and headless
Chromium screenshots at 1280px and 375px in both schemes, with the sidebar overlay open, against
the dev server with a stubbed session and no backend. Measured `.root` height unchanged after
inserting 3000px of content, i.e. still height-bounded. Head to Head from the mockup is not built.

## Owned sync — cache deployed, attended restart still exceeds budget (2026-09-20)

The owner requested restoration without upgrading Supabase. PR #116 was already deployed; an
attended ACTIVE epoch 62 ran from 08:16:28 to 08:23:39 UTC on `e123272`. The stable-pair cache works,
but remaining amplification failed the free-tier acceptance: 15 canonical pair downloads in a
384-second sample (about 30 MB raw body content alone), 33 completed catalogue/schedule jobs over
the activation and no completed histories out of 667 queued. Fixture attempts repeatedly request
new priority-0 parents, and checkpoint reads plus recurring planner/watchdog metadata remain outside
the cache. Whole-machine network RX includes FPL downloads and is not a Supabase billing counter.

Normal rollback is verified **OFF epoch 63**, with no PENDING/LEASED epoch-62 work and application
UP. Tapas core writes remain disabled; Kuma remains paused. There is temporarily no core writer.
No new runtime fix is implemented and the requested sustained restoration is **not complete**.
The startup retention delete also reported an I/O error and needs follow-up before a long run.

[`OWNED-SYNC-EGRESS-REPORT.md`](OWNED-SYNC-EGRESS-REPORT.md) records the measured evidence,
remaining causes and bounded correction/acceptance units. Restore only after reviewed fixes and a
new attended test; do not treat PR #116 alone as sufficient or promise the existing overage is undone.

## Owned sync — Supabase egress containment (2026-09-19)

Supabase reported organisation egress at **88.78 GB / 5.5 GB** and shortened the Fair Use grace
to 2026-09-21. This is not just a past STAGING spike. `pg_stat_statements` showed 59,812 calls to
the query returning a complete canonical bootstrap/fixtures body pair; a live 60.6-second sample
while ACTIVE recorded **56 more calls**. At about 1.88 MiB per pair that workload alone was running
near 105 MiB/minute (about 6.2 GiB/hour) during the backlog. Even an otherwise idle five-second
planner would repeatedly read the same pair up to twelve times a minute. One Fly machine was
running, so duplicate application instances do not explain it.

Payload storage and transfer are different facts. The retained bodies totalled about 940 MiB when
uncompressed, led by about 743 MiB of bootstrap copies, but TOAST compression kept
`fpl_sync_payload` at 169 MiB physical and the whole database at 249 MiB. The hourly 24-hour
retention is running: `pg_stat_statements` records 45 deletes / 8,313 rows, and only 37.5 MiB logical
was currently old enough and terminal enough to delete. Retention controls storage; PostgreSQL
expands the selected `bytea` bodies on the wire, so it does not control this egress defect.

Immediate containment was a normal mode transition to **OFF epoch 61**, application UP, with no
active queue left; Kuma 18 was paused only after OFF was verified. Tapas core writes remain disabled,
so there is deliberately no core writer during this short hotfix window. Do not leave this state
in place after a reviewed fix, and do not re-enable Tapas as an unreviewed shortcut.

The hotfix keeps the metadata candidate query and exact season/generation/payload-id/endpoint/
VALID/runtime-epoch recheck on every lookup, but retains the raw immutable pair in a two-entry,
process-local LRU. Concurrent misses are single-flight. Strict parsing and newest-valid fallback
stay in each caller; a validation update evicts matching entries and payload pruning clears the
small cache. Thus a stable pair crosses the Supabase connection once per process instead of once
per planner/history/catalogue call. The bound is two pairs (8 MiB at the schema's absolute payload
caps; normally about 3.8 MiB).

Verified locally against a disposable database: 11 planner/store integration tests plus the
full-size 657-player Spring web-context pressure test pass. Regressions cover repeated malformed-
newest fallback without another body read, invalidation between candidate selection and cache
recheck, three concurrent readers producing one body query, and defensive byte arrays. The hard
scope peaked at 1,494 MiB with at least 2,651 MiB host memory available. Independent review found
no actionable issue. Production egress reduction is not verified until the reviewed release is
ACTIVE and `pg_stat_statements` is sampled again.

## Owned sync — a wait for histories is not a failed attempt (2026-09-18)

Two of the forty backlog fixtures (4 and 40) never verified. The request ceiling held them past
their twelve-hour follow-up window, they became `DAILY_ONLY`, and from then on each 03:00 UK
attempt asked for fresh player histories and was rescheduled to the *next* sweep, where it had to
ask again. `HistoryFixtureDispatcher.retryForDependency` went through `recordFailedAttempt`, so a
dependency wait counted as a failed attempt: it advanced the 10/20/40/60-minute ladder for every
fixture (which is why first observations took about 85 minutes) and, for a daily-only fixture,
consumed the day's single admission. One attempt a day can never finish work that needs a second
attempt the same day.

`FixtureSyncRepository.recordDependencyWait` now records only when the follow-up is due and why;
the attempt count, the ladder and the daily cadence are untouched, and `admitAttempt` admits the
follow-up at that due time. Real failures still use `recordFailedAttempt`. The two fixtures were
re-anchored by hand on 2026-09-18 08:27 UTC so GW1–4 could complete before GW5.

The same morning showed why owned health could never return to UP. Eight `DUE_WORK` incidents
stayed open after all forty fixtures had verified, and every one pointed at a run that had been
fenced `FAILED` when its activation ended (STAGING epoch 3, ACTIVE epoch 46). The watchdog accepted
only a `COMPLETED` run as recovery evidence, and a fenced run can never complete: its epoch is over
and the work was planned again under the next one. `OwnedSyncWatchdog.recoveryEvidence` now also
treats a `FAILED` run as settled when its `(runtime_mode, runtime_epoch)` is no longer the live
activation. A failure inside the live activation still keeps its incident open. Kuma monitor 18
stays paused until this is deployed, because external health would otherwise read DOWN for ever.

A third defect showed itself twenty seconds after fixtures 4 and 40 verified at 08:31 UTC: both
lost their verification again. A follow-up run planned before they verified executed right after,
`FixtureVerification` answered `PENDING` with the single reason `STABILITY_WINDOW` — too early to
compare, less than ten minutes after the previous observation — and `saveDecision` overwrote
`state` and nulled `current_verified_generation`, as it does for every pending decision. Too early
is not a contradiction. `FixtureSyncPublisher` now returns that decision without saving it when the
row is currently verified, so only real evidence (a changed fingerprint, a finish regression, a
source correction) can revoke. The two fixtures re-verify on their own at the next follow-up.

## Owned sync — cutover to ACTIVE, and the outage on the way there (2026-09-16/17)

The owner cut production over on the evening of 2026-09-16: Tapas cron stopped,
`FPL_CORE_WRITES_ENABLED=false`, a least-privilege `tapas_runtime` login provisioned and probed
(core writes and DDL refused, its own manager/league/chip tables writable), Tapas' `DATABASE_URL`
switched to it, the assistant set to `OWNED_SYNC_MODE=ACTIVE` with the playing season and cutover
confirmation, and the Tapas cron restarted with core writes disabled. `tapas_runtime` uses the
direct database host; the assistant keeps its `postgres` connection through the transaction pooler.

**Outage, 21:10–22:08 UTC, caused by this project's own operations.** Read-only inspection
queries through the Supabase *transaction* pooler (port 6543) had been issuing
`SET default_transaction_read_only = on` as a safety measure. In transaction pooling a session
setting outlives the client that set it: it stays on the pooler's shared server session and every
later client inherits it, including the assistant. The owned runtime failed with "cannot execute
SELECT FOR UPDATE in a read-only transaction", and once the app restarted it could not start at
all, because `PgVectorStore` runs `CREATE EXTENSION IF NOT EXISTS vector` during context
initialisation. A production observer script re-applied the setting every two minutes, so the
first resets did not hold. Recovery: stop the observer, `RESET default_transaction_read_only` on
every pooled backend, restart. **Rule: never run session `SET` statements through the transaction
pooler.** Read-only intent is expressed by writing only SELECTs.

Two further facts from the same night:

- `fpl_sync_payload` had grown to 394 MB of a 455 MB database (3,064 staged source bodies across
  every STAGING/ACTIVE epoch since 2026-09-14). `deleteTerminalPayloadsOlderThan` existed but
  nothing called it. The table was truncated during the incident (staged bodies only; receipts,
  observations and evidence are separate tables) and this change wires an hourly prune with a
  24-hour retention on the watchdog executor. One backlog pass stages about 170 MB, so retention
  keeps the free-tier database well under its 500 MB limit.
- The 2026-09-16 first-sighting fix (PR #105) only stamps rows on insert. Production's 40 fixture
  rows already existed from earlier STAGING epochs with capture-time stamps, so ACTIVE parked all
  of them as `DAILY_ONLY` until 2026-09-18 02:00 UTC. The owner re-anchored those 40 rows to the
  activation instant in a one-off maintenance update on 2026-09-17 07:28 UTC; the first two
  fixtures verified at 08:09 UTC and the rest followed. No code change was needed for this: the
  rule is right for every row created from now on.

## Owned sync — one-minute planner delay (2026-09-20)

A third independent egress correction changes only the planner's fixed delay from five seconds
to one minute, still with an immediate first tick. At idle this permits at most 1/12 as many
planner ticks; it does not promise a 12-fold reduction in total database traffic. The tradeoff is
up to a minute plus tick duration before newly due planning work is materialized. Two workers
still drain the durable queue at their one-second cadence; source-read slots, HTTP admission,
verification spacing and five-minute watchdog cadence are unchanged.

Verification: 22 focused runtime, planner and coordinator tests passed against disposable Postgres.
The runtime regression fails with the old five-second cadence and checks immediate startup,
continued worker dispatch and the second one-minute tick. Full-suite verification belongs to PR CI.
Production remains OFF; this change alone is not permission for sustained activation.

## Owned sync — backlog fixtures were parked until the daily sweep (2026-09-16)

An ACTIVE rehearsal on a disposable copy of production showed why no fixture had ever reached
`VERIFIED`. `PlannerInputRepository.observeTransitions` stamped `first_finished_observed_at` with
the *capture's* observation time. For a fixture that FPL finished weeks ago, that instant is
already outside the twelve-hour follow-up window `FixtureSyncRepository.admitAttempt` and
`scheduleNextAttempt` measure, so every backlog fixture was marked `DAILY_ONLY` and allowed one
attempt per day at 03:00 UK. Two coherent observations are required, so a backlog fixture needed
two calendar days, and a cutover would leave the whole season unverified meanwhile.

The column now records when **we** first saw the fixture finished. Live matchdays are unaffected —
we observe a finish within minutes of the capture — while a cutover gives the backlog the normal
10/20/40/60-minute cadence. An integration test seeds a fixture whose capture says it finished ten
days ago and asserts both the stamp and that `admitAttempt` still admits an hour later.

Rehearsal evidence with the fix, against real FPL data (local disposable Postgres, ACTIVE):
both core receipts published within 90 seconds, 663 publications, zero failed runs, stable memory
(peak 538 MiB), and **all 40 finished fixtures recorded their first observation**. Before the fix
that was impossible on the same data.

Two findings remain open, both about cost rather than correctness:

- The first backlog pass does not fit the 5,000-request rolling day: the run reached the ceiling
  at 4,717 requests before the second observation wave, so verification would continue the
  following day. A cutover therefore leaves current-season stats unverified — and the
  receipt-enforced readers refusing — for a day or more.
- Each verification attempt re-fetched its fixture's player histories because the freshness floor
  advanced with every canonical pair: 3,957 history fetches for 40 fixtures. `usableOwn` took the
  run's *newest* validated read, which after a fan-out is the post-history recheck, so the next
  attempt's floor invalidated everything already fetched. It now takes the earliest validated read
  at or after the run's own demand floor (`OwnedSyncStore.earliestValidStagedPayload`), which keeps
  the floor still between attempts. Every ordering guarantee is unchanged: histories are still read
  after the pair, and the recheck after the histories.

## Owned sync — attended STAGING drained initial work, verification not reached (2026-09-15)

PR #103 deployed (machine v86, `-Xmx320m`). A first attempt, epoch 40 (12:33–12:36 UTC), was
stopped after three minutes by the observer on a single transport `URLError`; OFF epoch 41, no
manual fence, nothing published. The observer now retries one transport failure once.

Owner-attended **epoch 42** ran 15:36–16:20 UTC. The owner extended the bound in session, and the
observer was handed over at 15:50 UTC without re-requesting STAGING. All initial queued work
completed with zero failed runs:

- canonical: 78 runs, 183 attempts;
- fixture verification: 40 runs, 241 attempts;
- histories: 659 runs, 1236 attempts. The first completed history was seen at 15:47, about eleven
  minutes after start.

Memory stayed flat: 44 samples on one Java process, sampled RSS at most 545.5 MiB, guest
available memory at least 260.0 MiB, heartbeat age at most 51.7 s. The run stopped at 16:19:55 on
five minutes without progress, because the queue was empty, not because anything stalled. The
graceful stop recorded **OFF epoch 43**, application UP, with no manual fence.

The watchdog fix held in production. Its first check at 15:41:49 opened 699 `DUE_WORK` incidents
plus three others without a transaction timeout, and all 699 recovered by 16:16:54.

- **Not demonstrated:** `fpl_fixture_sync` holds 40 `VERIFYING` rows, 340 `SCHEDULED` and zero
  `VERIFIED`. The second independent observation batch was not reached.
- **Nothing published:** zero player-evidence rows and zero receipts.
- **Still open:** `FIXTURE_VERIFICATION_12H` (1) and `MISSED_DAILY_CAPTURE` (2), so owned health
  stayed DOWN.
- **Kuma 18:** the owner received its DOWN mail, the first verified delivery. Recovery mail is
  unverified. The monitor was paused again after OFF.
- **Tapas** remains the writer.

The eleven-minute delay before histories is most likely queue order, not memory. This is an
inference; what the code shows is:

- The dispatcher orders by priority, then due time, with no dependency filter
  (`OwnedSyncStore:148`), and every lease counts as an attempt (`:167`).
- Every fixture attempt that fetched a public pair queues a priority-0 parent refresh
  (`HistoryFixtureDispatcher:178`).
- A retry restores the kind's own priority unless the run is waiting on a dependency
  (`OwnedSyncStore:558`).

Two candidate fixes are not implemented yet:

- refresh the parent only when the fetched pair differs from the latest canonical pair;
- keep the priority on admission-only deferrals.

The next attended run must last past the second verification batch to show `VERIFIED` fixtures.
Details are in the [memory report](OWNED-SYNC-MEMORY-REPORT.md).

## Owned sync — supervised stop and runtime-pressure follow-up (2026-09-15)

PR #100 deployed; epoch 36 stopped at the conservative guest-memory threshold (153.5 MiB
available versus the 192 MiB guard), not an observed OOM. Its 28 canonical completions and 659
unattempted histories did not establish readiness. Graceful shutdown verified **OFF epoch 37**, UP,
without a manual database fence; zero core publications. Tapas remains writer. A preceding short
epoch-34 run was aborted by the local monitor's old-heartbeat/startup mistake, not a service fault.
The fixed-PID RSS sampler must also be replaced by process-identity discovery.

Local full-size staging reproduction drained 657 synthetic histories, 40 fixture jobs and 84
canonical jobs: 981 fake HTTP requests, first history at virtual second 204. This demonstrates
finite initial work, not production timing or absence of starvation under continuing arrivals.
The new regression boots the web context, drains two planner ticks serially and additionally holds
three selected snapshots concurrently; it is not a full runtime/provider soak.

The follow-up changes planner polling from one to five seconds after each completed tick, leaving
workers, source floors and priorities alone. Heap becomes 320 MiB (other caps 160/64/32 unchanged,
VM still 1 GiB). A bounded diagnostic under those JVM limits passed: 555.1 MiB sampled RSS peak,
252.2 MiB sampled heap-use peak, 309.4 MiB committed heap and 80.4 MiB post-release/post-GC heap use.
The final 1536 MiB scope peaked at 941 MiB with host headroom at least 2348 MiB. Failed/interrupted
diagnostic setup runs are not passing evidence or application OOM reproductions.

PR #101 deployed this correction. Supervised epoch 38 remained on one Java process and levelled at
about 537 MiB sampled RSS / 271 MiB guest available through its last complete sample. It completed
68 canonical runs by then; 40 verification jobs were still making first-pass attempts and 659
histories remained unattempted. The external monitor alerted at the first five-minute watchdog
check. Logs show the watchdog's isolated five-second transaction timing out in `nextOccurrence`
after building the high-cardinality due-work signal set; this was not an OOM or process restart.
The operator interruption stopped the local observer, so the runtime was immediately returned to
**OFF epoch 39**, UP. Epoch-38 shutdown fenced 40 verification and 659 history runs as FAILED;
74 canonical runs had completed. Zero core receipts were published. Kuma 18 is paused while OFF.

The watchdog correction retains one incident per signal but replaces per-incident occurrence reads
and inserts with one grouped occurrence read and a batch insert. Recovery evidence for runs,
fixtures and captures is also loaded in three bounded metadata reads rather than one query per open
incident. A disposable-Postgres regression opens and recovers 700 due-work incidents inside the
real five-second monitor transaction; 11 focused tests pass under a 768 MiB/150-second diagnostic
scope. Full evidence, reproduction and revised cold-start monitoring gates remain in the
[memory report](OWNED-SYNC-MEMORY-REPORT.md). A reviewed deploy and another attended STAGING are
required; Tapas remains writer.

## Owned sync — overnight OOM and bounded investigation (2026-09-15)

The third STAGING run did not converge. The activation advanced from epoch 5 to 32 across
restarts; the latest Fly exit explicitly reports `oom_killed=true`, exit 137, and the kernel logged
Java at 877728 KiB anonymous RSS at 07:29 UTC. Application health timed out. In epoch 32, twelve
catalogue/schedule runs completed but all 659 history jobs still had attempt count zero. Across
STAGING epochs there were no completed history jobs; total HTTP attempts were 1673. No owned
receipt was published and no player evidence existed. This is not a successful staging rehearsal.

Containment: configured OFF, verified the replacement OFF process and HTTP 200/UP, then fenced
only the drained epoch-32 pending/leased runs (701 rows) in a guarded metadata transaction and
advanced the durable mode to OFF epoch 33. Source evidence was retained. Tapas credentials and
core-write ownership were not changed. The previous temporary Kuma pause helper was absent, so
no pause was performed in this containment; do not claim that the monitor is paused.

Read-only production size inspection found bootstrap bodies averaging 1.67 MiB, fixtures 0.20 MiB,
and live bodies 0.44 MiB. Epoch 32 held six valid canonical pairs (11.25 MiB raw). A disposable
Postgres probe using the checked-in full-size source fixtures reproduced the lookup with six
pairs, without contacting FPL: `latestValidPublicPayloadPairs` plus parsing the first pair allocated
70.32 MiB per call over twelve iterations. Selecting only one pair for measurement allocated
32.33 MiB per call. This is a diagnostic comparison, not a deployed change: simply removing the
fallback would lose required validation behavior. JFR samples locate the largest allocations in
PGStream tuple reception, PGbytea hex decoding and StagedPayload's defensive clone. After explicit
GC the probe retained about 27 MiB; this short run neither proves a leak nor excludes one in the
full runtime.

Separately, a local flags probe with `MaxRAMPercentage=75` and 1 GiB permits a 768 MiB heap;
read-only production HotSpot counters refine the actual maximum to about 722 MiB on the guest's
smaller usable memory. An OFF sample had about 328 MiB RSS and 76 MiB committed metaspace. Metaspace
was not explicitly capped and code-cache reservation was 240 MiB (reservation is not committed
RSS). No full application OOM reproduction or exclusive root-cause attribution has been made.

All diagnostic Java processes were serialized under a 768 MiB cgroup hard limit, no swap, one CPU,
a 150-second deadline and host-memory stop threshold; the workload used a 256 MiB heap. Its local
DB was isolated at port 55436 and stopped afterward; the other worktree's port-55432 DB was not
modified. Private JFR/output files are under `/tmp/fpl-memory-*` and are not committed. Next bounded
work: preserve newest-valid fallback while fetching bodies only on demand, establish an explicit
whole-process JVM budget, and measure the full-size workload before considering another STAGING
run. Production stays OFF; do not substitute another memory increase for that verification.

**Bounded correction:** all three canonical consumers now select metadata first and fetch only the
pair they inspect; catalogue reuse rejects stale request/day floors before loading bodies. Strict
newest-valid fallback, pair coherence and validation/epoch filters remain. Measured allocation fell
from 70.32 to 33.00 MiB per selection; stale-only candidates used 0.01 MiB. A twenty-pair,
300-selection repeat used 32.13 MiB per selection, finished in 17.96 seconds and retained 17.43 MiB
heap after GC. The whole corrected probe peaked at 443 MiB RSS. The Docker launch now budgets
448 MiB heap, 160 MiB metaspace, 64 MiB code cache and 32 MiB direct buffers; VM size remains 1 GiB.
This is a local correction, not a claim of successful full-runtime staging. One focused test run
was stopped safely when host headroom reached 691 MiB (own scope peak 983 MiB); no other process
was killed. Full verification/publication evidence belongs to the correction PR.
See `docs/OWNED-SYNC-MEMORY-REPORT.md` for measured facts, uncertainties, reproducible diagnostics
and the attended, bounded next-rollout proposal. Core-write handover remains blocked.

## Owned sync — second STAGING exposed dependency starvation (2026-09-14)

PR #97 deployed successfully with 1 GiB. The planner advanced and about 411 MiB remained available;
115 public HTTP attempts produced 114 VALID payloads plus one unchecked checkpoint. However, 39
high-priority fixture jobs repeatedly waited for missing histories: 659 history jobs and the two
canonical jobs never received a worker turn. A fixture's source pair cannot replace the canonical
catalogue pair required by history parsing. No receipts or player evidence were published.

The bounded follow-up prioritizes canonical prerequisites and durably puts dependency-waiting
fixtures behind histories. Ordinary retries restore normal priority, retaining ready-verification
preference. Real-store coverage uses 39 fixtures/600 histories and checks source floors, stale
leases and priority restoration; dispatcher coverage exercises the missing-history branch.
The first full gate caught an end-to-end test's old queue-order assumption: it now injects the
history failure before dispatch and pauses at that actual failure before checking the partial
sweep, rather than draining unrelated work first. Publication/readiness assertions are unchanged.

Production is OFF again. Fly forced the previous VM out before its durable activation marker was
cleared; after verifying replacement by an OFF instance, a guarded metadata-only transaction
fenced its 700 pending/leased runs and changed STAGING epoch 3 to OFF epoch 4. Evidence remains
intact. The follow-up gives Fly a 60-second shutdown budget; the exact shutdown obstruction is
unproven, so drain/epoch verification remains mandatory. Kuma recorded DOWN and is paused again;
mailbox receipt/recovery, credential handover, ACTIVE and reconciliation are still unverified.
No Tapas credential/flag or core-write ownership changed. See `docs/OWNED-SYNC-OPERATIONS.md`.

## Owned sync — STAGING blocked, safely returned to OFF (2026-09-14)

PR #96 merged and deployed successfully. Its monitor/operations settings and tokens were installed;
the first STAGING attempt then exposed database starvation before any source work was queued.
The planner's 380 per-fixture updates exceeded its five-second transaction, and two workers shared
the same two-connection pool with monitoring. At 22:17 UTC the runtime was returned to OFF.
Post-rollback checks confirmed HTTP application health UP, durable OFF epoch 2, and zero owned
runs, source payloads/attempts, fixture observations/evidence or published receipts. Tapas still
owns core writes; Kuma 18 remains paused. There was no production role/credential handover.

The narrow fix isolates work and monitoring pools, batches fixture observations and makes large
daily/matchday player materialization resumable in at-most-32-player transactions. Thirteen focused
local tests pass, including full-league batching, exhausted work-pool isolation and a real queue
rollback/restart between chunks. The first local rerun correctly refused the ACL rehearsal database
because it contained Tapas' `manager` table; resetting only our disposable container restored the
normal schema, without weakening that safety guard. Runs stayed within the enforced 1536 MiB scope.
The Fly allocation changes from 512 MiB to 1 GiB: OFF already left only about 104 MiB available.
Neither the corrected runtime nor the memory increase is deployed until the owner merges the fix.

The restricted Tapas role's DML/sequence matrix passed locally against representative real DDL
(20/20 core mutations refused, eleven derived tables writable, schema CREATE refused). Probe grants
were rolled back; the current production connection was backed up privately, not replaced. Before
cutover still verify production effective privileges/functions, STAGING progress, notification
DOWN/recovery, old-writer drain and denied core writes. Then reconcile and observe a real matchday.
Details and the distinction between desired rollout-file STAGING and actual production OFF are in
`docs/OWNED-SYNC-OPERATIONS.md`.

## Owned sync — bound watchdog evidence transfers (2026-09-20)

The second independent egress correction scopes occurrence-number lookups to new current signals
and recovery evidence to open incidents no longer represented by a current signal. Completed/fenced
run ids, verified fixtures and capture receipts are fetched only for those incident keys, using
batched typed arrays; empty key sets issue no evidence query. It no longer downloads every settled
historical run and every historical incident key on each five-minute check. Advisory locking,
occurrence numbering, exact recovery matching and live-epoch failure semantics are unchanged.

Verification: 12 focused watchdog integration tests passed on disposable Postgres. A test-only JDBC
observer measures one occurrence row and one settled-run row despite 1,000 irrelevant historical
runs and recovered incidents. Tests also cover empty queries, recurrence numbering, malformed run
keys, current failures versus superseded epochs and 700 simultaneous incident recoveries. This
bounds transferred evidence by relevant incidents, not all retained history; it does not establish
an overall egress budget or change planner cadence. Full-suite verification belongs to the PR.
Production was not changed and remains OFF pending the remaining egress work and attended acceptance.

## Owned sync — Uptime Kuma rollout preparation (2026-09-14)

The owner selected the existing home-server Uptime Kuma and authorized the operational handover.
Kuma 1.23.17 is on `plex`; authenticated API access works. Monitor 18, `FPL Assistant - owned sync`,
was created **paused**, using the existing `Home network alert` SMTP channel. Its transport test
was accepted; mailbox receipt is not independently verified. Separate high-entropy operator and
monitor tokens are stored only in an ignored mode-0600 local rollout file, not installed in Fly.

A bounded follow-up supports `OWNED_SYNC_ALERTS_VIA_MONITOR=true`: authenticated health is DOWN
for stale/missing planner/watchdog heartbeats or any incident without its exact occurrence's
recovery. The outbox records EXTERNAL rather than inventing delivery receipts. Monitor/webhook
modes are exclusive, missing monitor authentication fails startup, and webhook redrive is refused
while Kuma owns delivery. Normal webhook behavior remains the default. Focused verification:
22 tests passed under the constrained runner; the full suite runs again in the normal pre-push gate.

Both merged deployments were green at inspection. Production read-only checks identified playing
season id 2 / `2026-27`, no owned schema yet, and RLS-enabled Tapas tables without policies. The
role split therefore needs a restricted-table BYPASSRLS login for Tapas, not blanket privilege
revocation or ownership changes. See `docs/OWNED-SYNC-OPERATIONS.md` for the preflight and next gates.
No production flags, database roles/grants or core rows have changed; Tapas remains the writer.

## Owned matchday updates — code complete, not activated (2026-09-14)

Tasks 4–10 code/local verification follow the foundation in PRs #91/#93. Full histories and DefCon,
fixture-level stable-source verification, atomic publication/evidence, adaptive durable scheduling,
reader masks and whole-club comparison refusals, watchdog/outbox and authenticated operations are
implemented. OFF is still the default; no production writes, activation, Tapas disablement, secrets,
role changes or real notification calls were performed. The separate Tapas compatibility/guard
change is [PR #1](https://github.com/quantti/tapas-fpl-app/pull/1), defaulting core writes to true.

Subagent implementations and independent review/fix rounds covered source contracts, publication
races, retry/epoch fencing, pending/nullable metrics, derived jobs and notification recovery.
Final serial review corrected cutover ordering (never require live owner publication before the
old writer drains), preservation/revocation of receipts on rollback, and required season-row lock
privileges in the review-only grant outline.

Verification: 1132 backend tests, zero failures/errors/skips; 700 Tapas tests; 33 pre-push shell
regressions. Fake-clock/FPL end-to-end coverage includes delayed finish, incomplete histories, 429,
restart, independent fixture recovery, two stable batches, correction revocation and alert recovery.
The 600-player/ten-fixture stress case demonstrates that desired cadence can exceed 5000 daily
attempts: the ceiling defers work honestly, not an asserted freshness guarantee. Measure real
throughput and backlog before activation. Tapas Ruff has no diagnostics on changed lines; 34
existing diagnostics remain versus 38 in its base, and were not swept into this change.

**Resource incident:** the owner reported three host crashes from memory exhaustion during the
large implementation. No OOM-log root-cause proof was collected; final inspection did identify an
idle project Gradle daemon retaining about 1.1 GiB, which was stopped. Finalization used no parallel
agents/builds. Build/test heaps, worker count, Spring context cache and DB pools are now bounded;
test Postgres buffers/connections are reduced. This WSL Docker cannot enforce container memory
limits. A user-systemd cgroup-v2 scope did enforce a 1280 MiB limit: the full suite peaked at 1152
MiB with at least 1889 MiB host memory available, and left no persistent Gradle daemon.

**Still operationally gated:** owner-selected alert destination and delivery test, external monitor
secrets, distinct reviewed DB roles, maintenance/drain evidence, current-season reconciliation and
one observed real matchday. See `docs/OWNED-SYNC-OPERATIONS.md` and both updated plans. Linear access
is unavailable in this harness, so no ticket identifier was invented or automatically closed.

## Invite-code admin API (KAR-54, 2026-09-13)

Plan: `docs/superpowers/plans/2026-09-13-kar-54-invite-code-admin-api.md`. The page that uses it is KAR-55.

Per-code ceilings, the `active` flag and per-code attribution have existed since PR #52; what did
not exist was any way to operate them except SQL in the Supabase editor, because the CLI must not
run on the production machine (`docs/EMAIL-SETUP.md` 5.1). `/api/admin/invite-codes` now lists
every code with its counts and a server-computed state, creates codes, disables and enables them,
moves or removes a ceiling, lists a code's redemptions and releases burnt places (older than ten
minutes, then reconciles — KAR-18's procedure as one call).

**Admin identity is `ADMIN_USER_IDS`**, a comma-separated set of JWT `sub` ids, enforced by
`AdminAccess` on the `/api/admin/**` matcher in `SecurityConfig` rather than in the controller.
Empty means nobody, the same fail-closed posture as the missing service-role key. Chosen over a
Supabase `app_metadata` claim (cleaner if admins multiply, but a token round-trip and a dashboard
setting for a population of one) and over a profile column (a table write that grants admin).
`GET /api/profile` reports `admin` for the frontend's link; the API refuses regardless.

`InviteCode.state()` is the one rule for whether a code is open, and `refusalReason()` is now
written on it, so the list can never show OPEN for a code sign-up would refuse. Codes are never
deleted. `InviteLedger.reserve` and `SignupController` are untouched.

**Not set in production yet:** `fly secrets set ADMIN_USER_IDS=<owner sub>` — until then every
admin call answers 403.

Verified: `./gradlew test`, 942 tests, 0 real failures, including the new `AdminAccessTest`,
`InviteCodeTest`, `InviteLedgerTest` and `InviteCodeAdminControllerTest`. The pre-push hook's
fresh-database run then refused the push: 11 failures, all in `UserProfileControllerTest`
(`Failed to obtain JDBC Connection` / "sorry, too many clients already"). The cause was
`InviteCodeAdminControllerTest` itself — its `@Import(LocalSigningKey.class)` plus a mocked
`SupabaseAdminClient` is a context no other class shares, and its cached 10-connection Hikari pool
was the one more that crossed the KAR-51 ceiling. The class now carries
`@DirtiesContext(AFTER_CLASS)`, the same KAR-42 workaround `SecurityHeadersTest` already uses and
that KAR-51 records; the real pool-size fix is still KAR-51's. Re-run after that change:
`../scripts/test-db.sh reset && ./gradlew cleanTest test`, 942 tests, 0 failures, 0 errors.

Review follow-ups (PR #92): bounded the code to 64 characters with no spaces, refused a
`benefitValue` sent with `benefitKind` `access` instead of nulling it silently, added the
`Location` header on 201, closed the duplicate-create race with a `DuplicateKeyException` catch
around the insert, made `summary()` a single read-only transaction, moved `release-burnt`'s grace
cutoff onto the database clock instead of the JVM's, and made `isAdmin` null-safe.

## Admin page for invite codes (KAR-55, 2026-09-14)

Plan: `docs/superpowers/plans/2026-09-14-kar-55-admin-invite-codes-page.md`.

`/admin` in the frontend, reached from an **Admin** item in the app menu that appears when
`GET /api/profile` answers `admin: true` (`useProfile`, read once per signed-in shell). The item
is a convenience: anyone can type the URL, the API answers 403 and the page says *This page is
not available for this account.* — nothing in the browser decides who is an admin. The page shows
the places line with **Release burnt places** when something is burnt, one row per code with the
server-computed state in the spec's words (Open, Disabled, Used up, Expired, Not yet valid),
Disable/Enable, and on expanding a row its ceiling (save, or **No ceiling**) and its redemptions
newest first; a form below issues a new code. Every mutation re-renders from the `CodeView` the
server returns, never from the click. Under 640px the table is one stacked block per code.

Rulings: form dates are the admin's local days (`validUntil` is the end of that day); the places
line always carries the burnt count; only Disabled and Used-up rows are muted; `SettingsPage` is
untouched.

Verified: `npm test` (124 tests green), `npm run build` clean (contrast check, `tsc -b`, Vite).
Manual check with `npm run dev` against the local backend with `ADMIN_USER_IDS` set was **not**
run in this session — left for the owner to do before relying on the page.

Review follow-ups: the final whole-branch review's fix commit added digits-only validation on the
new-code form's ceiling and discount percentage, made releasing burnt places collapse the expanded
row instead of leaving it showing the just-deleted redemptions, dropped the details panel's inert
`stopPropagation` click handler, wired `aria-controls` from the code button to its details row,
restored details-row padding under 640px, and removed a duplicate local focus-visible rule already
covered globally; `npm test` is now 125 tests green.

PR review follow-ups (PR #94): a failed re-read after a successful burnt-place release no longer
misreports the release or replaces the page; the mobile action cell now beats `.table td` on
specificity; redemptions sort by parsed instant with an id tie-break instead of by ISO string;
ceiling and discount inputs are bounded to the server's `int` range (`isCount`); a 403 on
redemptions now reads the same not-available sentence as everywhere else; `problemDetail` and the
status-line markup are each shared in one place (`auth/problemDetail.ts`, `AdminPage/StatusLine.tsx`)
instead of duplicated across `SignIn`/`CodeDetails`/`NewCodeForm`; and the plan doc's step headings
dropped their checkbox syntax. `npm test` is now 126 tests green.

## Player name search (KAR-57, 2026-09-18)

Plan: `docs/superpowers/plans/2026-09-18-player-name-search.md`. Ticket: KAR-57.

Production evidence, from one conversation over three turns: `Tarik Muharemovic` came back not found because
`lower('Tarik Muharemović') LIKE '%tarik muharemovic%'` is false — `ć` ≠ `c`; `Hall Newcastle` came
back not found because the whole string was matched against `web_name`/`full_name` and the club is
not in that haystack; asked to name the club, `Leeds` still failed because no lookup accepted a
club at all; and the pasted, correctly-accented `Muharemović` was found but answered only "he is
listed as Muharemović" — nothing told the model to go back and answer the comparison it was asked.

**The fix is one component, `PlayerNameSearch`** (`backend/.../tools/PlayerNameSearch.java`):
folds both the name and a club expression through Postgres `unaccent(lower(...))`, tokenises the
query on whitespace/commas, and requires every token to hit the name or the club while at least one
token must hit the name — a club alone (`Newcastle`) matches nobody. An exact folded `web_name`
match is ranked first, so a cheaper exact hit is never outranked by a pricier substring hit. It
replaces the hand-written `LOWER(col) LIKE ?` lookups in `DailyPriceSource.matchPlayers` (serving
`getPlayerStats`'s current-squad block, `getPriceHistory`, `getPlayerAvailability`),
`PlayerTools.seasonRows` and `ExpectedStatsTools.getPlayerExpectedStats`. The four affected `@Tool`
descriptions gained a sentence that the name may be a surname, a full name, or a name followed by a
club, and that accents are optional; `getPlayerStats`'s description dropped "spelled exactly as the
user wrote it", which had told the model the opposite of what is now true. The system prompt
section "Player names are the user's, not yours" now tells the model to pass a given club along
with the name, that an accent/case/first-name/club difference is still a match, to retry once on
the surname alone when nothing matches, and — the fourth evidence row — to go back and answer the
original question once the player is identified rather than stopping at "here is how they're
listed".

**Clubs are FPL's abbreviated names, and the retry drops the club** (final review, Important 1–2):
the club haystack is `team_name || team_short`, so "Manchester United" narrows nothing while
"Man Utd" and "MUN" do — the prompt and the four `@Tool` descriptions now say which form to pass,
and the surname-only retry drops the club rather than keeping it. And because each season's rows
carry that season's club, `getPlayerStats` looks the per-season statistics up by the matched
`web_name` — the project's cross-season bridge — once the daily capture has identified the player:
on the raw query a club token would drop the season a summer signing spent at his old club, which
is the season an early-season answer needs most.

**`unaccent` is probed, not assumed** (owner-approved option A, 2026-09-18): the extension is
`CREATE EXTENSION IF NOT EXISTS`-ed at construction and then exercised (`unaccent('ć') = 'c'`). A
refused `CREATE` is logged at WARN and the probe runs anyway — read-only mode or a role without
`CREATE` refuses the statement while the function is installed and callable. Any `RuntimeException`
from the probe, or a fold that does not return `c`, degrades the search to plain `lower()` — the
pre-KAR-57 behaviour — and logs at ERROR; nothing about startup can throw. No session `SET` is used anywhere in the probe, per the pooler rule. Folding only
removes accents and case; it never maps one name onto a similar-looking one — `konsa` still does not
match Konaté.

Out of scope, deliberately untouched: `RankingMetrics`' and `ExpectedStatsTools`'s cross-season
`web_name` bridges (both sides are FPL's own spelling, nothing to fold), `SquadTools.fold` (pins are
already folded in Java), every team-name lookup (`FixtureTools`, `FixtureDifficultyTools`,
`AvailabilityTools`/`SetPieceTools` team queries, `HistoricalTeamResolver` — no club name carries an
accent), and the frontend player linker — which linked `Hall` inside a reply that said Hall could
not be found; whether that is still true is a post-deploy check, and if it recurs it is a separate
frontend ticket, not a reopening of this one.

**Before merging:** the owner should enable `unaccent` in the Supabase dashboard, so it lands in the
`extensions` schema and the app's `CREATE` is a no-op; created by the app it would land in `public`,
which Supabase's advisor flags. Do not add `WITH SCHEMA extensions` to the code — that schema exists
neither locally nor in CI.

**Not yet verified:** production confirmation is deferred until this PR is merged and deployed —
`fly logs` should read "Player name search: accent folding ON", and re-asking the original chat
question (`Tarik Muharemovic vs Hall Newcastlesta wild cardilla, kumpi parempi`) should resolve and
compare both players on the first turn. Neither has been checked yet.

## DefCon on the named-player reply (KAR-58, 2026-09-18)

Production evidence: asked to compare defenders (Hall, Muharemović), the assistant left defensive
contribution out, and when challenged argued it from the players' roles with no numbers. It had
none. DefCon was modelled only inside the ranking paths (`PickTools`' `DC` term, `HorizonScorer`);
`getPlayerStats` and `getPlayerExpectedStats` — what the model calls for players the user names —
never read `player_fixture_stats.defensive_contribution`, and the prompt's only comparison rule
for defenders was the clean-sheet chance.

`getPlayerExpectedStats` now prints each match's raw count as `DefCon actions` — deliberately not
`DC`, which in the pick lists is expected points per fixture — and one `Defensive contribution`
line **per season** in the window: the threshold of the position he held that season, how often
it was reached, the same over 60+ appearances (a cameo cannot reach it and would read as a miss),
and actions per 90. Seasons are never pooled, so a filled early-season window cannot pass last
season's rate off as current, and a CBIT season is never judged at the CBIRT threshold. It is an
observed hit rate over the returned window, not the ranking model's probability, and the prompt
says the two are different units. Only appearances count; a NULL reading is "without a reading",
never a miss. Read through `PfsReadSource`, so owned-sync masking holds. Goalkeepers get one line
saying none is awarded. The prompt now requires DefCon in every DEF/MID comparison and forbids
arguing it from role alone (`DefconComparisonPromptContractTest`).

Not done: `getPlayerStats` still has no DefCon — its card is built from the tapas `player`
season totals, which carry none, and one PFS-grain home for the figure is enough.

Shipped in two pull requests: #110 (the DefCon line and the prompt rule) merged before its review
finished, so the review's fixes — per-season lines, the `DefCon actions` name, the 60+ split —
followed as #111. Both are on `master` and deployed 2026-09-18 with the run for #112 (the run for
#111 itself was cancelled as superseded seconds later; same code). The rule agents must keep is
in `AGENTS.md` under Conventions.

Observed while verifying, not fixed: a second full suite against the same test container fails
`RowLevelSecurityTest` (left-over `fpl_plan_*_test` / `fpl_pressure_*_test` tables without RLS) and
`CatalogueScheduleDispatcherIntegrationTest` (380 residual fixture rows). CI always starts from
a fresh database and never sees it; a hand-run second suite does. See `docs/DEVELOPMENT.md`,
"The test database".

## The pre-push hook no longer runs the suite (2026-09-19)

Owner decision: the hook's `test-db.sh reset` + `cleanTest test` on every push (about six minutes,
1.2 GiB peak) was too heavy for the cloud host this is also developed on. `scripts/hooks/pre-push`
is now the master guard alone — typed confirmation at a terminal, outright refusal without one —
and needs only bash and git, so it installs on the VPS as well, which is what KAR-59 asked for.
Tests: the ones you wrote or touched while developing; the whole suite on GitHub for every pull
request and on the merge, where `needs: test` still blocks a red deploy. What is given up: a red
suite is now discovered on the PR rather than before the push. `scripts/tests/pre-push-test.sh`
lost the hook's database-preflight contracts with the code they tested and keeps the master
guard, the deletion no-op and the `test-db.sh reset` contracts (run in CI).

## Frontend lint and format (KAR-60, 2026-09-19)

What replaced the suite in the pre-push hook. ESLint and Biome arrive with `kurssit-pro`'s
configuration (type-checked `typescript-eslint` + `unicorn` + `sonarjs`; Biome as formatter only),
pinned to the same versions. One mechanical commit: Biome reformatted 63 frontend files and
`consistent-type-imports` was autofixed; `tsc`, the 126 Vitest tests and the production build pass
on the result. The other 363 violations are baselined in `frontend/eslint-suppressions.json`
rather than fixed — a blanket `eslint --fix` was tried first and broke `tsc` (ES2021+ APIs against
an ES2020 `lib`) and four tests, so the burn-down is by hand, file by file, as files are touched.
That exposed the stale compiler setting: `tsconfig.json` went from ES2020 to ES2022 `target`/`lib`,
which Vite 8's default browser target (Chrome/Edge 111, Firefox 114, Safari 16.4) supports in full.
The hook lints only when a push touches `frontend/` and Node is present; `deploy.yml` runs
`lint` and `format:check` on every pull request and is the enforcement. Java is out of scope by
owner decision.

## The quota window is a gameweek (KAR-61, 2026-09-19)

Free-tier messages were counted over a rolling seven days, so nothing ever reset: each message
freed up individually, seven days after it was sent, and "12 of 20 messages used this week" named
a period with no beginning and no end. The allowance now belongs to a round of the game — the
window runs from one FPL deadline to the next, 20 messages, resetting at every deadline.

`QuotaWindows` cuts it from every captured season's deadlines read as one timeline, not from the
newest season alone. That is what makes the changeover fall out of the same rule: the last deadline
before an August request is the previous season's GW38, so the gap between two seasons is one
off-season window of its own, 100 messages for the whole break. The newest season alone would leave
a preseason request with no lower bound and count the entire previous season against a user.

A window always knows when it began. Where every captured deadline is still ahead — the only
captured season being one that has not started — nothing records the start, so that case is the
fallback rather than an unbounded window; counting from the beginning of time would charge an
account for every message it had ever sent. Review of PR #115 caught this, along with a suspended
account being shown the window's reset (it is now told nothing, the same silence the 429 keeps) and
`QuotaWindows` catching only `DataAccessException` while `ScheduleSource.instantOf` throws
`IllegalStateException` by design — which would have turned one malformed timestamp into a 500 on
every chat turn, the exact fail-closed outage the fallback exists to prevent.

Two rules are deliberate and would look like bugs to a later reader. **Freshness is not checked**,
unlike `HorizonFixtures`: the schedule sync legitimately refuses to write all summer, so requiring
a fresh capture would mean the off-season window could never occur — every off-season request would
fall back to the rolling week instead. Only the newest season's events must be a clean 1..38 from
one capture, which `ScheduleSource.assessEvents` now answers without the fixture half of `assess`.
And **the fallback is the old code path untouched**, `OFFSET (used - limit)` and all: a fixed window
has one reset instant, a rolling one has none, so a blocked user in the fallback would otherwise get
no `Retry-After` at all. `Quota` therefore carries two timestamps — `resetsAt`, when the count
returns to zero, and `nextFreeAt`, when the caller may send again.

Nothing user-facing says "this gameweek". During the window `[GW12 deadline, GW13 deadline)` the
matches being played are GW12's, which `GameweekTools` calls the current round, while the window is
the run-up to GW13; the refusal and the usage page name the deadline instead. `UsagePage` gained
its first test along with the three wordings, and the one `unicorn/prefer-ternary` suppression its
rewrite made stale was pruned from the ESLint baseline.

`fpl.quota.free-messages-per-week` is now `free-messages-per-gameweek`
(`QUOTA_FREE_MESSAGES_PER_GAMEWEEK`), with `free-messages-off-season`
(`QUOTA_FREE_MESSAGES_OFF_SEASON`, 100) beside it. `QUOTA_FREE_MESSAGES_PER_WEEK` was **not** set as a Fly
secret — verified against `fly secrets list` on 2026-09-19, so the limit has always come from the
property default and the rename carries no deployment step. `QUOTA_FREE_MESSAGES_OFF_SEASON` is
unset too, so the off-season allowance is the default 100. `fpl_user_profile.weekly_message_limit` keeps its
name: it is a per-user override that applies to whichever window is in force, and renaming it buys
a migration and nothing else. No data migration; on deploy the count restarts from the last
deadline, so most users' usage drops and nobody is charged for anything they were not charged for
before.

Spec: `docs/superpowers/specs/2026-09-19-quota-gameweek-window-design.md`.
Plan: `docs/superpowers/plans/2026-09-19-quota-gameweek-window.md`.

## Conversation selection loading (KAR-53, 2026-09-20)

Plan: `docs/superpowers/plans/2026-09-20-kar-53-conversation-loading.md`.

Selecting a saved conversation now immediately replaces the old transcript with a loading
status and disables the composer. The clicked sidebar row says `Loading…`; the current marker
stays on the last successfully loaded conversation until the new one lands. Other rows and
New conversation remain available. HTTP, network and malformed-history failures release loading
and show the existing could-not-be-opened error, retaining the previous conversation id as before.

The existing ChatView selection counter protected displayed messages but not `currentId`, which
`useConversations.select` used to change unconditionally. The hook now guards identity and pending
cleanup by request generation too. A newer click or New conversation invalidates old responses.
Selection also immediately detaches any outgoing browser chat stream with `replaceMessages([])`;
late conversation metadata cannot steal the pending selection or its successor. This does not
promise that server-side generation stops. A 404 still refreshes the sidebar, without holding
selection loading open for that second request.

Owner execution rule recorded in `AGENTS.md`: always one subagent at a time because resources are
limited; no parallel/nested delegation or overlapping builds/test suites. Planning, hook work and
UI work were separate sequential units.

Verified locally: tests-first failures, then 27 hook-selection tests and 8 mounted ChatView tests
with real hooks/sidebar/thread/composer. Full frontend suite: 189 tests in 22 files, one worker;
lint, format check, TypeScript and contrast checks passed. PR #121's first CI run passed the
backend suite and production frontend build (no local JDK or frontend public-key configuration).
PR review found no functional blocker; its accessibility follow-up keeps an empty live status
region mounted and updates its text rather than mounting it already populated. The same 189
tests and static checks passed after that fix, followed by a clean independent re-review and
successful CI run `35496208578` at `7ca33f1`. Linear is In Review; the owner merges PR #121.
Real-browser layout, screen-reader announcements and production behavior have not been checked.
