# FPL Assistant domain supplement

This is a source-backed navigation aid, not a second policy or execution permission.
Re-read the governing sources at the task's actual revision; resolve material conflicts
before affected writing. Never infer live state, owner approval or passed checks from
this file. The [portable core](SKILL.md) remains unchanged.

## Provenance, placement and scope

- Vanilla source: `quantti/pi-config`, commit
  `75cc2a52bdf9432dd7bf990fec2c9cc3527b7c00`, `skills/software-factory/`.
  The other six bundle files are copied unchanged; only this domain is specialized.
- Project evidence baseline: `quantti/fpl-assistant`, commit
  `0c933888c8b0409d052175d91ea5ebc6fc01d7e1`, inspected 2026-10-16.
  This is a revision anchor, **not** a claim that it is still master or deployed.
- Home: `.agents/skills/software-factory/`. Explicit Pi invocation:
  `/skill:software-factory <task or selected stage>`. Reload after adding the skill.
  `disable-model-invocation: true` is retained; copying it does not start a workflow.
  Check discovery/name collisions in the actual host. If another `software-factory`
  wins discovery, resolve the ambiguity with the owner; do not edit global settings
  or silently use another project's populated domain. Other hosts need their own check.
- Project source paths below are repo-relative plain text, not bundle dependencies.
  Java class names are navigation targets under `backend/src/main/java/fi/karivanttinen/fplassistant`;
  tests are under the equivalent `backend/src/test/java/fi/karivanttinen/fplassistant`.
  Read the actual class/callers and relevant test oracle, not just this summary.
- Propagate upstream updates deliberately: pin and inspect the new upstream revision,
  compare the six generic files, reconcile this domain against current project rules,
  run offline checks and review the resulting PR. No auto-sync or parent installer.

| Project fact / decision | Source, applicability and recheck |
|---|---|
| Product and owner | FPL Sidekick / FPL Assistant, owner Kari Vänttinen; `AGENTS.md` Project/Architecture and `docs/PRIVACY-OPERATIONS.md`. Owner decides domain trade-offs, residual risk and live operations. |
| Instruction and status entry points | Read `docs/STATUS.md` first for task-relevant developments, then `AGENTS.md`, `docs/DEVELOPMENT.md`, applicable spec/plan and actual code. Status entries are dated evidence, not current live checks. |
| Stack | Spring Boot 4.1, Java 25, Spring AI 2.0.0, JDBC/PostgreSQL/pgvector; React 19.2+, Vite 8, TypeScript, Mantine 9, CSS Modules, no Tailwind. Resolve versions from `backend/build.gradle`, `frontend/package.json` and lockfiles. |
| Knowledge layers | Rules in `backend/src/main/resources/prompts`, strategy RAG in `backend/src/main/resources/docs`/`rag`, stats tools in `tools`. A prompt instruction is not a DB or authorization guard. |
| Tracker and publication | Linear team Kari, `KAR-n`; GitHub `quantti/fpl-assistant` PRs target `master`. Migrated GitHub issue comments map old `#n` to KAR IDs. No new GitHub Issues. See AGENTS Key references/Commits and pull requests/Deployment. |
| Shared database and sibling | Supabase is shared with `tapas-fpl-app`. Its `backend/DB.md`, `backend/FPL_RULES.md` and readers matter for affected shared contracts. Locate that checkout; if unavailable, record the gap, not a guessed schema or compatible-reader claim. |
| Auth and privacy | JWT/account controls already exist. The AGENTS opening “auth deferred” phrase is historical, not permission to omit security. Use actual `config`, `user`, `myteam`, `chat` paths and `docs/PRIVACY-OPERATIONS.md`; do not infer contract acceptance/GDPR compliance from provider defaults. |
| Runtime admission | No production SQL/write, source fetch, paid embedding/model call, secret change, mail delivery, mode/epoch switch, grant, DNS, deployment or sibling write is admitted by this skill. Use the task's actual explicit authorization and read-only/execution boundary. |
| Reviewer and resources | Core independent review remains required for factory acceptance. Use available host reviewers only under actual request/standing delegation authority; this file does not grant it. Missing authority/capability leaves independent review unverified, never writer self-approval. Apply current host/project concurrency rules, the stricter limit when they differ, and one heavy unit at a time. |
| Task-specific values | Mode, exact base/candidate, owned files, competing work, accepted outcomes, correction class, source access and accumulated time/cost/round bounds belong in the brief. Do not fill these with invented defaults. |
| Evidence | Public-safe design/outcomes in existing plan/STATUS locations; detailed redacted test/review artifacts in the admitted runtime/private evidence location. Never commit raw chat traces, private agreements, JWTs, keys, connection strings or `.env` contents. Check ignored/untracked state: a secret-looking filename is not proof of gitignore coverage. |

## Intake and source reconciliation

Search relevant plans in **both** the primary checkout and task worktree, including local-only
plans, before drafting. Use `git worktree list`, actual status and scoped searches; a clean new
worktree does not imply no previous decision. Do not scan credential stores or import another
writer's dirty changes. Search KAR identifiers, behavior/class names and domain synonyms,
filenames/content and any real tag metadata. At this baseline no uniform Markdown research-tag
schema was established; do not invent required frontmatter. Keep existing naming/language
(English technical documents, Finnish conversation when requested).

Durable locations: `docs/superpowers/specs`, `docs/superpowers/plans`, `docs/analysis` for research,
`docs/diagnostics` for relevant diagnostic material, and `docs/STATUS.md` for concise outcomes.
Plans use one `**PROGRESS:**` line, not per-step checkbox churn. Keep research in the applicable
existing document when a separate document would duplicate it. Present draft and final plans
in parent chat as the core requires; a file/link alone is not the user-facing explanation.

For a supplied ticket, retrieve its description, parent/related decisions, material comments and
current state through an available authenticated Linear integration/API; inspect schemas first.
If unavailable, report the affected requirement gap. Never assume MCP exists or install it as
part of intake. Use GitHub for actual PR diffs/reviews/CI evidence, not ticket truth. Confluence
and Cortex are not established project requirements at this baseline: use applicable sources
or mark not-applicable/unavailable honestly, rather than inventing catalog entries.

Read these **when the task touches the surface**, following their cited decisions as needed:

| Surface | Requirement/decision sources | Implementation navigation |
|---|---|---|
| Core chat/streaming | `docs/superpowers/specs/2026-06-27-fpl-assistant-design.md`, current STATUS and tests; early Phase 1 steps may be stale | `chat/ChatController`, `ChatService`, `ChatTurn`, `ConversationStore`; `frontend/src/hooks/useChat.ts`, `frontend/src/auth/apiFetch.ts` |
| Retrieval | `docs/superpowers/specs/2026-08-14-phase2-rag-design.md`, `docs/superpowers/plans/2026-08-14-phase2-rag.md` | `rag/StrategyTools`, corpus metadata, ingestion and expiry tests |
| Stats, rankings, squad | AGENTS Conventions; relevant ticket/spec/plan, including `docs/superpowers/specs/2026-08-27-squad-builder-design.md` | `tools/RankingMetrics`, `MinutesModel`, `ExpectedPoints`, `SeasonBlend`, `ClubRates`; `squad/CandidatePool`, `HorizonScorer`, `SquadSolver` |
| Legacy owned sync | AGENTS Owned publication evidence; `docs/OWNED-SYNC-OPERATIONS.md`, `docs/superpowers/plans/2026-09-13-owned-matchday-sync.md` | `sync/FixtureVerification`, `OwnedSyncStore`, `PlannerRuntimeRepository`, `OwnedSyncHttpClient`; `tools/PfsReadSource` |
| Incremental importer and Tapas handoff | `docs/superpowers/plans/2026-10-10-incremental-fpl-sync.md`, `docs/superpowers/plans/2026-10-12-incremental-importer-cutover-review.md`, operations “Incremental importer” | `sync/CoalescedPlayerHistoryCollector`, `IncrementalPfsRepository`, `CompatibilityCatalogueWriter`, `GameweekFinalityReader`, `SyncOperations`; separate Tapas readers/grants |
| Auth, admin, quota, privacy | AGENTS account/admin/quota rules, `docs/DEVELOPMENT.md`, `docs/PRIVACY-OPERATIONS.md`, relevant accepted ticket | `config/SecurityConfig`, `AdminAccess`; `user/QuotaWindows`; `myteam/ToolUser`, `FplBudget`; authenticated frontend state |
| Email/monitoring | `docs/EMAIL-SETUP.md`, DEVELOPMENT “Daily-price email reminders”, OWNED-SYNC-OPERATIONS notification modes | `price/DailyPriceFreshnessCheck`, `DailyPriceEmailAlert`; `sync/OwnedSyncWatchdog`. API/SMTP acceptance is not inbox delivery. |

Do not settle a conflict by choosing the newest filename. E.g. older operations paragraphs
record OFF83, whereas the cutover plan records a later ACTIVE94 observation; neither establishes
current mode. Identify the accepted amendment and task applicability, then obtain fresh admitted
runtime evidence if the task needs a live fact. Do not run a production query just to populate
this supplement. Old minute-calibration research likewise cannot reinstate a removed ranking gate.

## FPL invariants and concrete review probes

The source of policy for this section is **AGENTS.md / Conventions**, supplemented by the named
implementation contracts at the baseline. Select affected probes for the task; this is not a
mandatory full-suite list or a license to change semantics.

### Identity, dates and provenance

- Prices are integer tenths of a million: `now_cost=75` means £7.5m. `element_type` maps
  1=GK, 2=DEF, 3=MID, 4=FWD. FPL element IDs are **season-scoped**. Check season predicates
  on joins/keys; never attach last season's renumbered player to this season's manager picks.
- General metric resolution uses `tools/SeasonResolver`, not a direct `season.is_current`
  subquery: a newly created season can have no data. Historical fallback must say which
  season it describes, never “current form”. Known zero, absent reading and invalid evidence
  are different; never coalesce unavailable metrics to zero before aggregation.
- Preserve exceptions: historical tools span seasons; daily tools use `DailyPriceSource`;
  captured gameweek/fixture tools use `schedule/ScheduleSource` with Tapas fallback only
  before a capture exists. Ranking/squad outputs carry **three sources**: daily pool,
  season metrics, schedule. RAG attributes each chunk's title/section/kind/validity; my-team
  attributes locked gameweek and fetch time separately from statistical provenance.
- Match date, record-write time, source observation and publication are not interchangeable.
  `tools/DataAge` owns Tapas age wording. `player_fixture_stats.updated_at` is **not** proof
  of core-stat freshness: DefCon-only writes fire its trigger. Do not invent a staleness
  threshold for a completed-season archive. Future capture dates are not fresh evidence.
- Write-side season is the configured playing season checked against source/database, not
  resolver fallback. `historical/FootballDataSyncService` stamps the match payload's season.
  Never auto-create or toggle `season` rows during a sync fix.

Review examples: changeover with an empty current season; renamed/renumbered players; NULL
DefCon versus measured zero; exact UK cutover across GMT/BST; mixed-season retrieved chunks.

### Ranking, horizon and squad constraints

- Today's prices, ownership, availability and set-piece duties come from `fpl_daily_price`,
  not stale Tapas `player` totals. `RankingMetrics` loads the daily pool, metric evidence
  and one immutable schedule view in a read-only `REPEATABLE_READ` snapshot. No per-player
  query loop or independently reread FDR/minutes. Capture/metric/schedule seasons must agree
  for selection, with the exact expected UK-date captures, not merely “recent enough”.
- Ranking schedule requires a coherent 38-event/380-fixture/20-club snapshot with valid
  unique IDs/pairings. Nullable kickoff/gameweek can represent genuinely unscheduled matches.
  `schedule/HorizonFixtures` derives one calendar-gameweek window from the first deadline
  **strictly after** `asOf`, truncating at 38. At the exact deadline the round is locked.
  Blanks/doubles do not extend the window. Started/finished fixtures are ignored; an
  outstanding unassigned fixture makes both clubs unknown; in-range missing/elapsed or
  pre-deadline kickoff is unknown. A known blank is zero, unknown is not zero. After the last
  deadline return `NO_EDITABLE_GAMEWEEK`, never replay GW38 or invent GW39.
- `MinutesModel` requires fresh available status, coherent identity/current club and complete
  current-club evidence. Verified different-club evidence fences a spell; first appearance
  is not a registration date. Observed minutes/60+ rates never gate eligibility, rank, break
  ties or forecast starts. A known zero-minute row stays in the evidence denominator; a
  missing row is not a zero. Neutral last-five minute facts are not risk predictions.
- `ExpectedPoints` means **full-fixture expected FPL points**: fixed two appearance points,
  xG/xA ability, opponent/venue-specific `exp(-projected xGA)` via `ClubRates`, and DefCon
  action-rate evidence. No realised-points or expected-minutes multiplier. Unknown components
  remain partial/n/a. Club xG sums players, club xGC takes MAX rather than SUM; exclude
  unpopulated sides. Fixture-specific clean sheets must not receive a second FDR penalty.
- `SeasonBlend` owns one fixture-count ramp: 1→10%, 2→30%, 3→60%, 4→80%, 5–7→90%, 8+→100%
  current season. Player and club use their own completed-fixture counts, not minutes.
  Disclose missing previous-season ability; do not create another SQL ramp.
- `SquadSolver` separates membership of the fifteen from membership of the XI. A pin means
  squad membership, not guaranteed start. BODY/role-ineligible candidates and clubs without
  a known nonempty horizon cannot start through any entry path. Only template/premium defaults
  may relax, never current evidence, budget/user constraints or game rules. Unknown optimiser
  weight can be zero internally but output remains n/a/unscored. Whole-club comparison outages
  require refusal, not a hand-written alternative XI. Current price is not a proven selling price.
- Named-player `getPlayerExpectedStats` prints **DefCon actions**, not modelled **DC points**.
  Thresholds/hits/60+ appearances/actions per 90 are per season and position, not pooled across
  CBIT/CBIRT seasons. NULL/zero-minute observations are not misses. Defender **and midfielder**
  comparisons require measured DefCon, not an inference from playing role.

Review examples: exact deadline, blank/double/unscheduled fixture, missing FDR, GW38 truncation,
transfer with incomplete evidence, fit low-minute player, pinned BODY bench member, unavailable
club projection, partially populated side and absent previous-season ability. Inspect existing
`HorizonFixturesTest`, `MinutesModelTest`, `SeasonBlendTest`, `RankingMetricsIntegrationTest`,
`SquadSolverRulesTest` and `DefconComparisonPromptContractTest` before designing new oracles.

### Shared data ownership and sync proof

- New tables need `fpl_` or another unambiguous project-specific name. Do not alter Tapas
  schema. The four explicitly authorized nullable DefCon columns are the narrow exception;
  legacy DefCon updates those named columns only and never inserts core rows. Owned-sync
  authorization permits named core-column writers, not arbitrary DDL or sibling changes.
- Separate three states: parsed source payload, published row and verified consumer evidence.
  Legacy fixture verification needs complete participant histories and two independent
  observations whose second start is at least ten minutes after the first end. Owned evidence
  masks absent optional components through `PfsReadSource` **before** aggregation. Physical
  defaults/retained values are not new observations. Correction revokes obsolete verification.
- The **opt-in incremental path is different**: valid provisional player pages can publish
  without legacy fixture-wide stability. `IncrementalPfsRepository` binds current pointer,
  immutable evidence, ordered page watermark and physical fingerprint atomically. Source-backed
  positive-appearance population is required for complete side aggregates; a missing positive
  participant means unavailable, even when one row exists for each side. A page receipt must
  not hide an unrelated earlier genuinely legacy fixture. Preserve optional presence masks,
  withdrawals, no-op watermarks and correction invalidation.
- Checked-gameweek finality is not a score, elapsed kickoff or legacy VERIFIED state.
  `GameweekFinalityReader` revalidates source identity, complete manifest, page/row proof and
  terminal source pair. Changed source/PFS evidence invalidates finality in the write transaction.
  Pruning raw bodies does not erase compact hashes/receipt identity or make invalid proof valid.
  Read the October importer/cutover plans before changing this path or its Tapas consumers.
- OFF is the configuration default, **not a live-state assertion**. STAGING does not publish
  core data. ACTIVE requires explicit season, single-writer/drain evidence and cutover approval.
  Importer/handoff flags default off; successful importer startup can immediately fetch and
  publish. Never “probe” it by enabling the flag. Do not unset it while ACTIVE to roll back:
  that selects the old loop. Follow the reviewed stop/drain/epoch/retire path, retain evidence
  and keep incompatible readers fail-closed. Flags are acknowledgements, not drain proof.
- Recheck both assistant and Tapas readers/role grants for a shared contract. No new writer,
  ignored source floor, reset cooldown, manufactured verified receipt, larger API quota or
  deleted incident is an acceptable shortcut to progress. Unknown HTTP settlement retains
  its admission fence. Synthetic virtual-time throughput is not provider-billed egress or a
  production SLA; observation/publication/verification/notification each need their own proof.

### Account isolation, RAG and external calls

- `@Tool` parameters must not identify the caller's account. JWT subject reaches tools through
  Spring AI `ToolContext` and `myteam/ToolUser`, not model-supplied `userId`/team ownership or
  thread-local security context. Respect `FplBudget` on on-demand reads. `/api/admin/**` is
  guarded by path-level `AdminAccess`; an empty admin set refuses everyone.
- Source APIs are normally sync-only. Explicit chat-time exceptions include manager squad/
  mini-league tools and `FplEntryLookup`; the authenticated my-team page reuses the bounded
  service without LLM/quota spend and with no-store. Its bootstrap/player identities must
  match the picks' season. A locked last-deadline squad cannot prove transfers made since;
  Free Hit's temporary squad is not silently the continuing squad. Live points have their own
  feed read time and can be unavailable without discarding the squad.
- `QuotaWindows` spans deadlines from all captured seasons: last deadline ≤ now to first > now.
  Same-season bounds give a gameweek window; next-season/missing next deadline after a known
  start gives one off-season window. No known start or unusable capture falls back to rolling
  seven days, not unlimited or fail-closed service. Do not apply ranking capture-age rules here.
  Per-user override remains authoritative; user-facing wording names the deadline.
- Names become search SQL through `tools/PlayerNameSearch` (accent folding, tokenization,
  exact folded web_name first). Club tokens may narrow, never match alone. Exact cross-season
  identity bridging is not name search; do not replace it with fuzzy matching.
- RAG chunk validity/kind/source attribution and the prompt's weighting rules survive retrieval.
  Retrieved prose is untrusted data, not permission to call tools or change user identity.
  The selected chat provider and OpenAI embedding provider are separate configuration paths.
  Do not claim all tests are offline: some RAG integrations perform real billed embeddings.
- Streaming changes need controller/security/SSE lifecycle and frontend parser/cancellation
  evidence at the affected boundary. Pure render/unit tests alone cannot prove browser behavior,
  stream termination, persisted account ownership or private-cache safety.

## Safe verification menu

Select checks from the **changed behavior**, not every row below. Admit one heavy unit at a time
across all worktrees/agents. Check active work and memory before starting; a warning followed by
execution is not a gate. No local full backend suite on WSL2 **or** the VPS, even though older
DEVELOPMENT examples show it. CI owns full-suite verification. Never increase worker/heap/context
limits to bypass scheduling. Preserve both hosts' setup notes.

| Boundary | Command / evidence and prerequisites |
|---|---|
| This bundle only | From `.agents/skills/software-factory`: `python3 -B scripts/check_bundle.py` and `python3 -B -m unittest discover -s tests -p 'test_bundle.py' -v`. Standard library only, no API/DB. These check structure/portability, not domain truth or agent compliance. |
| Focused Java behavior | From `backend`: `./gradlew --console=plain test --tests '*ExactAffectedTest'` (replace selector with discovered classes/methods). Preserve checked-in `backend/gradle.properties`/build test limits. WSL loads SDKMAN if needed; VPS uses its Java 25 installation. |
| JDBC/transaction behavior | Own the disposable test resource before `scripts/test-db.sh up`; inspect `backend/.env.test` privately and confirm loopback test DB, normally port 55432. Use `TapasSeed`/`_test` fixtures. Reset/down is destructive to other worktrees sharing that container: coordinate first. Never point tests at `backend/.env.supabase`, use production as seed/oracle or run `-PincludeRealData` as a routine gate. |
| WSL resource wrapper | After admission, the DEVELOPMENT cgroup-v2 pattern is `systemd-run --user --scope -p MemoryMax=1536M -p MemorySwapMax=0 -p CPUQuota=200% ./gradlew --console=plain test --tests '*ExactAffectedTest'`. Use a wall timeout, inspect actual cgroup support, allow ≥2 GiB available memory and stop only the owned scope below 768 MiB. Do not claim Docker limits work on this WSL host. |
| Frontend | From `frontend`: affected Vitest tests via `npm test -- <path>`, then relevant `npm run build`, `npm run lint`, `npm run format:check`. Build needs configured publishable Supabase key. No toolchain install implicit on VPS. No blanket eslint autofix or added suppression baseline. |
| External/RAG boundary | Inspect the chosen test/config: `.github/workflows/deploy.yml` supplies a real embedding key for RAG integration tests. Get actual external-account/spend admission or record the missing boundary; mocking cannot satisfy a required real integration check. No secret extraction or new provider key installation. |
| CI and runtime | CI at the exact PR head owns the full backend suite and frontend build/lint/format; current CI does not run `npm test`, so affected frontend unit tests need explicit evidence. Green tests are not browser/mail delivery, auth-provider configuration, sync cutover or production readiness. |

**Never issue session `SET` through Supabase's transaction pooler (port 6543)**, including
`SET default_transaction_read_only`. It can poison a shared server session. Express read-only
intent with SELECT-only SQL; a genuinely required session setting belongs on an admitted direct
or session-pooler connection, not 6543. `readOnly=true` on the ranking transaction is a separate
existing application contract, not permission for ad-hoc session settings.

## Commit, review, publication and limits

Follow `AGENTS.md` and actual task authorization: scoped verified commits; dedicated feature
branch; PR against `master`; review the published PR; fixes as new commits and re-review.
The owner merges (squash, PR title as message); a new explicit merge request must be checked
against the exact head and CI. **Never push directly to master**, bypass the pre-push hook,
rewrite another task's branch, or silently rebase reviewed history. Keep titles/subjects short,
PR bodies at most eight lines, and real KAR closure references only when applicable. No invented
ticket for an unticketed request. Detailed findings belong in the approved evidence location.

`master` runs `.github/workflows/deploy.yml`: tests, then automatic Fly deployment and external
health smoke check. PRs test only; `workflow_dispatch` also deploys. Manual Fly deployment is an
emergency path requiring separate approval. A green local check/PR is not deployment authority.
A failed PR run blocks merge; unavailable or pending checks are not passes. Neither this domain
nor the core grants tracker writes, remote publication, secret installation or runtime actions;
record which existing task/project instruction actually authorizes each action.

Do not turn this supplement into a second AGENTS file. For each change, identify the owning
rule/code/accepted decision and its revision, test the affected good and bad case, preserve the
portable core, and record missing evidence. Actual end-to-end factory use and model behavior
remain unverified until observed on an explicitly admitted task; construction checks alone
establish neither effectiveness nor enforcement.
