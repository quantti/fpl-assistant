# Incremental importer: bounded collection and non-paging budget waits

**PROGRESS:** Tasks 1–3/6 done locally: provisional collection and priority admission verified; monitoring and final closeout pending. No production or Kuma change made.

## Goal and owner request

Keep normal collection within the existing **6,000 outbound FPL attempts / rolling 24 hours**.
Stop hourly refetches of provisionally ended matches. Expected capacity waits must not generate
DOWN or recovery email. Preserve honest data coverage, finality, correction handling and real
service/process failure monitoring.

Interpretation of the email request: **budget/capacity waiting is non-paging**, not an instruction
to disable all importer monitoring or its shared SMTP notification. Public-health monitor 19 and
other monitors must remain untouched. If the owner wants every importer email disabled instead,
that is a separate, monitor-20-only notification change; do not edit the shared notification.

This is a planning artifact, not approval to deploy or change production configuration.

## Verified incident, 2 November 2026

Read-only inspection of Plex Kuma, production PostgreSQL and Fly logs established:

- Kuma 20 checks every 60 seconds, one retry after 20 seconds; repeat interval is **1,440 failed
  checks**, not hourly. Repeated DOWN/UP transitions bypass that repeat interval.
- Public-health monitor 19 stayed UP throughout the inspected 24 hours. Importer failures were
  application-generated HTTP 503 responses, not connection failures.
- The sole open incident at inspection was `REQUEST_BUDGET_BLOCKED`, with exactly 6,000 admitted
  attempts. Importer and watchdog heartbeats were fresh. All 6,000 attempts were settled.
- Repeated successful runs fetched **611 player pages**, with zero failed pages, approximately
  614 HTTP requests per run. The last complete run finished at 04:37 UTC; the next run committed
  297 pages and then deferred. Later runs alternated retry/supersession with no player progress.
- All nine already-played GW9 fixtures had `finished_provisional=true`, `finished=false` in the
  captured source. The tenth match had not started. GW1–8 were FINALIZED; GW9 remained OPEN.
- Kuma transitions followed the budget events: UP/DOWN at 09:30/09:50, 10:30/10:51 and
  11:31/11:47 UTC. Older attempts expired, the watchdog recorded recovery, and the next source
  pair consumed the freed slots and reopened the incident. Some budget incidents had no due work.

The current importer treats `!finished && started` as active, assigns the current cycle's source
floor, and requires a page from the current generation. Thus provisional full time never ends
hourly history fan-out. `OwnedSyncWatchdog.inspectBudget` independently treats reaching the cap
as CRITICAL; `SyncOperations.healthy` treats any open incident as DOWN.

This is our conservative application ceiling, not a published FPL quota or a money limit.

## Non-negotiable boundaries

- Keep the 6,000 ceiling, rolling window, durable ledger, request pacing, Retry-After, settlement,
  single-writer epoch/lease checks and default-OFF configuration. No budget reset or second writer.
- `finished_provisional` changes **collection frequency only**. Never manufacture `finished=true`,
  `data_checked=true`, VERIFIED or FINALIZED. Keep FPL's flags and current evidence truthful.
- Reuse valid per-player receipts across cycles where their actual source intervals satisfy the
  required floor. A skipped/deferred/failed request is not a successful observation.
- Preserve identity, population, withdrawal, correction, component-mask and source chronology
  guards. No copying an old page into a new generation or advancing its timestamp.
- No new daily all-player audit, per-player durable job queue, JSON cache, background collector,
  external API access at chat time, generic scheduling framework or user-facing configuration.
- The retired owned-runtime path keeps its existing contract. Scope new behavior to the importer.

## 1. Distinguish live refresh from post-match evidence

Use the existing `GameweekFinality.fixtureProgress` classification; do not duplicate its parser.
Collection decisions are fixture-specific, not based on whether the whole gameweek is checked.

| Source state | History work |
| --- | --- |
| Not started, future kickoff | No live history fan-out. Source discovery continues. |
| Started/elapsed kickoff, neither provisional nor finished | Hourly live refresh, subject to the low-priority budget admission below. |
| `finished_provisional=true`, `finished=false` | One post-provisional sweep with receipts newer than the observed provisional revision. Thereafter fetch only missing/stale pages or a detected relevant correction. |
| `finished=true`, event not yet checked | Existing post-finish sweep, then outstanding pages only. Do not repeat completed pages while waiting for `data_checked`. |
| Checked event / reopened finality | Existing final sweep and terminal source validation; finality requirements unchanged. |
| FINALIZED and unchanged | No history fan-out. Keep source discovery and existing manual reconciliation limits. |

A provisional sweep is not the final sweep. A later real finish or checked transition can require
new evidence. Coalesce obligations when a single newer page satisfies several fixtures/phases;
never fetch that player once per fixture or once per reason in the same cycle.

### Durable provisional observation

Reuse `fpl_sync_receipt` with a distinct, season-qualified provisional-fixture key, not the
existing finished marker. Store the first observation interval and the relevant fixture revision
using the existing fingerprint helpers (`verifiedHeader` plus fixture stats as appropriate).
Unchanged later captures do not advance that floor. No new metrics table or Tapas schema change.

- Include fixture identity, club/venue, kickoff/event, progress flags, score and relevant source
  stats in invalidation. Cosmetic JSON order, fetch time and unrelated bootstrap price/news
  changes must not restart every provisional sweep.
- A changed candidate/participant population requires new discovery and any genuinely missing
  pages. Reuse existing population, appearance and page receipts rather than a second completion list.
- A return from provisional to open clears its collection checkpoint and resumes live policy.
  A later provisional transition requires fresh evidence after the new transition.
- A correction to one fixture must not refresh every other unchanged fixture's population.
- Upgrade starts conservatively: without a trustworthy provisional checkpoint, perform one sweep
  after the first newly observed marker. Do not infer a completed sweep from physical PFS rows.

Review **all** active/finished branches in the collector, not just target selection: source floors,
`activeFixture`, live-event fetch decisions, population discovery, page freshness, priority,
retry and completion paths must use the same collection classification. Keep the original raw
flags for compatibility capture and finality.

## 2. Make optional live refresh yield before mandatory work runs out

Fixing provisional handling removes the observed waste. Add a small admission policy as protection
against long-lived open flags, DGWs, larger rosters and retries; do not assume ordinary fixture
flags always arrive on time.

Keep one shared attempt ledger. Pass an internal request purpose through the existing collector
fetch/HTTP boundary into the existing **atomic** admission transaction. Legacy calls retain their
old admission behavior. Do not implement a separate check-then-fetch counter or an in-memory quota.

Four purposes, in priority order:

1. **Discovery**: bootstrap/fixtures used to learn current state or validate an existing pass.
2. **Final**: required checked-event histories/live evidence and final validation.
3. **Post-match**: initial provisional/finished observation and outstanding/corrected pages.
4. **Live refresh**: optional repeated intramatch history refresh.

A player with several reasons gets the highest priority and the strictest source floor. Preserve
final > post-match > live ordering before sorting player IDs. Event-live requests inherit the
reason for which their evidence is needed; do not accidentally leave them unlimited.

### Concrete initial reserves

Let `B` be the configured cap (6,000 in production). Let `P` conservatively bound the distinct
players in one league-equivalent pass: the maximum of current catalogue size and the union of
required candidate/checkpoint IDs for the selected work, including departed/known players.
Do not hard-code today's 611. Recompute from the validated snapshot; admission uses actual total
attempts in the rolling window, including unsuccessful requests and other users of this ledger.

Reserve **D = 128 attempts** for source discovery/validation. Reserve **F = P + 128** for one final
pass and intervening source overhead, and **E = 2P + 128** for provisional and true-finish post-match
passes and intervening source overhead. Implementation's 48-hour attempt-trace regression proved
that the initially proposed `F=P+4, E=2P` could starve a mandatory pass after live work reached its
ceiling: later source/live requests consume the same total ledger. The extra margins correct that
under-reservation without increasing the hard limit.
The following are admission ceilings, not additional quotas:

| Request purpose | Maximum total rolling-window admissions before admitting this request |
| --- | --- |
| Discovery | `B` |
| Final | `max(0, B - D)` |
| Post-match | `max(0, B - D - F)` |
| Live refresh | `max(0, B - D - F - E)` |

Use `< ceiling` for admission; reaching the ceiling returns a typed normal capacity deferral.
One request still consumes one shared attempt. These reserves do not weaken the hard ceiling and
cannot promise unlimited finalisations/corrections. Retain minimum-spacing/cooldown/unsettled
checks regardless of purpose. In particular, an unresolved transport settlement remains a real
fault, not a harmless budget wait.

For `P=670`, the live ceiling is 3,606, post-match 5,074 and final 5,872. A representative league
round with three live refreshes plus provisional, real-finish and final passes costs approximately
`6P = 4,020` player requests, plus coalesced source/live/validation overhead. Ordinary hourly
source discovery costs 48 requests/day. Verify the exact overhead with the real planner tests,
not by presenting this estimate as a measured bound.

For the observed incident, the initial fix changes each repeating 611-page overnight run to a
source-only check once the provisional pass has completed. Normal overnight discovery therefore
costs about two requests per hourly cycle instead of 614.

### Capacity and scheduling semantics

- If an admission ceiling is reached, defer lower-priority work without marking pages failed.
  Keep successful receipts and outstanding IDs; do not replay an entire successful prefix.
- Calculate the capacity retry hint from the actual ordered admission timestamps: when usage is
  above a purpose's ceiling, expiry of just the oldest attempt may not free enough capacity.
  Preserve all later Retry-After/settlement constraints. The existing hourly trigger can resume
  at or after that hint; do not add a fast polling loop.
- Keep source discovery available when a history pass is deferred. For normal capacity deferrals,
  schedule the coordinator's durable run for the next ordinary hourly opportunity, rather than
  moving its `due_at` all the way to a distant history-admission hint. The hint describes HTTP
  permission, not permission to inspect source state. Every attempted fetch still goes through
  the purpose-aware admission/cooldown guard; an hourly tick during a global Retry-After makes
  zero HTTP requests. No early history request or second source-only scheduler is introduced.
- A deferred run's source-pair supersession must not discard reusable global page/population
  evidence. Conversely, do not weaken the one-canonical-pair-per-generation fence merely to
  resume faster. Test this with a bootstrap that changes every hour, as it did in the incident.
- Log/report **selected**, actually fetched, committed, deferred and failed pages distinctly.
  The current selected-ID list is not an actual HTTP request count.
- If two rounds, a cold catch-up, repeated corrections or upstream failures require more than
  6,000 attempts, reduce/skip optional live refresh first. Keep required work pending and dated.
  Do not promise fixed hourly live freshness plus unlimited mandatory work under a finite cap.
- The reserve is deliberately conservative, not a new tuning UI. Change these constants only
  with the request-count tests and an explicit budget calculation.

## 3. Budget waiting is diagnostic, not DOWN or email

For the importer, classify capacity exhaustion/reservation as **non-paging advisory state**.
Keep usage, cap, request purpose, remaining/outstanding work, reason and next eligible retry in
authenticated operations diagnostics. Prefer extending the existing status response, not adding
another dashboard, unauthenticated endpoint or scheduler.

Implement one explicit non-paging classification shared by watchdog/health, rather than filtering
all WARNING events or relying on free-text `contains("budget")`. The existing budget category can
remain in the event history, but it is excluded from importer DOWN eligibility. Make an already
open `REQUEST_BUDGET_BLOCKED` occurrence non-paging too; deployment must not depend on deleting it
or inventing a recovery. Budget recovery is diagnostic only and must not independently send mail.

Prevent a capacity wait from reappearing as `DUE_WORK` / `REPEATED_FAILURE`:

- Preserve the exact typed admission reason on the durable retry path. Reuse the existing
  standardized deferral representation if sufficient; never guess from arbitrary exception text.
- Admission refusals do not increment consecutive upstream-fetch failure counters.
- An importer run waiting on a known capacity admission is advisory, even if the next hourly
  invocation is later than its earliest capacity hint. Do not apply the legacy five-minute
  due-work grace blindly to an hourly importer.
- Fresh runtime/watchdog heartbeats still matter. A dead scheduler must not be hidden just because
  the last run happened to wait for capacity. Genuine lease expiry, schema/auth/identity failures,
  unsettled attempts, persistent upstream failures and other actionable incidents stay visible
  under their existing checks; they are not blanket-muted by a budget advisory.

`/ops/sync/health` remains a minimal authenticated HTTP 200 + `{"status":"UP"}` while capacity
waiting is the only issue. Do not expose detailed diagnostics through the monitor token. Database
unavailability, OFF, stale/missing heartbeat or a separate paging-eligible incident still returns
503. Public `/actuator/health` behavior is unchanged.

**Kuma:** keep monitor 20's interval, retries, 24-hour resend and notification assignment. No SMTP
edit is needed once non-actionable capacity waiting stops producing DOWN transitions. An already
DOWN monitor can send one genuine UP transition on rollout; state that explicitly in the rollout
notice. Do not silence the shared `Home network alert` notification or monitor 19. Inbox delivery
cannot be inferred from application health alone.

## Implementation units and verification

Each unit is bounded and verified before the next. No automatic activation/cutover, master push
or production SQL write is part of implementation.

### Task 1 — Reproduce the incident and pin collection semantics

Add clock-driven regressions to `CoalescedPlayerHistoryCollectorIntegrationTest` using synthetic
source data: nine provisional fixtures, a future tenth, multiple hourly cycles and new generations.
The old collector must refetch pages on cycle two; the expected corrected behavior must fail first.
Also pin a genuine live fixture refreshing again, and the distinction between provisional and final.
Use existing fixtures/builders and small representative player sets, not hundreds of DB page writes.
Verify: the new regressions fail for the intended reason against the old code.

### Task 2 — Add durable provisional checkpoints and reuse valid receipts

Touch `CoalescedPlayerHistoryCollector`, the incremental discovery/marker helpers in `OwnedSyncStore`
and their focused tests. Reuse `GameweekFinality.fixtureProgress`; finality eligibility is unchanged.
Cover restart, partial page failure, source-pair supersession, flags regressing, one-fixture correction,
population growth and unrelated price/news changes. Verify no missing page becomes an invented zero
and no source interval or generation is fabricated. Verify a later true finish/check still triggers
its required work, and unchanged finalized events remain no-ops.

### Task 3 — Add purpose-aware atomic admission and budgeted ordering

Touch `OwnedSyncRequestBudget`, `OwnedSyncHttpClient`, the collector fetch/retry seam, importer
runtime/wiring and incremental enqueue/acquire tests only where needed. Preserve supervised
preallocated permit IDs and settlement/deadline handling. Pin the distinction between the next
hourly coordinator opportunity and the next permitted HTTP attempt so a distant history hint
cannot stop hourly source discovery.
Verify cap boundaries, purpose ceilings, exact rolling-window expiry, restart/two-client contention,
low-cap tests, changed P, deduplication and preservation of partial success. Check all fetch callers;
legacy/manual calls must not accidentally evade the total cap or change behavior.

Add lightweight deterministic request-count simulations using production selection/admission logic,
not an independent formula as the oracle. Run only the affected tests locally, one Gradle unit at a
time, with the existing resource guards and disposable test database. Do not resurrect the removed
large combined-scale suites or depend on production data.

### Task 4 — Separate non-paging waits from external failure

Touch `OwnedSyncWatchdog`, `SyncOperations` and the minimal existing operations result types.
Focused tests must cover budget-only waiting with fresh heartbeats, a pre-existing open budget
occurrence, recovery/re-exhaustion cycles, deferred due-work, actual failures alongside waiting,
stale heartbeat, DB failure, OFF, legacy behavior and monitor-token privacy. Existing controller
response/auth tests remain valid. Verify the notification decision, not merely event severity.

### Task 5 — Run the connected targeted verification and document the contract

Required synthetic timelines and assertions:

| Scenario | Required evidence |
| --- | --- |
| Nine provisional matches overnight | One complete provisional sweep, then zero repeated history fan-out; hourly discovery remains. |
| Full ten-match day, staggered kickoffs | Only actually live clubs refresh; post-match/final work wins; exact total requests recorded. |
| Provisional flags delayed for 24–48 h | Live-purpose ceiling stops optional repeats; source discovery and required work retain headroom. |
| DGW / adjacent rounds within 24 h | Player dedup across fixtures/events; optional refresh yields; outstanding work remains explicit. |
| Checked transition and correction | Required post-floor evidence plus terminal validation, never provisional-to-FINALIZED shortcut. |
| Roster growth / departed players | Reserves use the real candidate/checkpoint union; no fixed 611/670 assumption. |
| Partial failure then restart/new source pair | Good pages survive; missing pages resume; real invalidation still forces new evidence. |
| Retry-After, timeout, unresolved settlement | Existing pacing and fail-closed settlement survive purpose-aware admission. |
| Repeated capacity transitions over 24 h | No importer DOWN/recovery notification decision from capacity alone. |
| Capacity wait plus process/database failure | Genuine failure is not masked; monitor still goes DOWN. |

For every timeline assert **actual admissions <= 6,000 in every sliding 24-hour window**, not only
per UTC/UK calendar day. Include windows crossing midnight and source-state transitions; also
report completion latency and skipped optional refreshes so a low request count cannot hide no work.
Use planner-level IDs/clock advances for full-league simulations and a small real-DB seam for atomic
admission/publication. Run connected existing collector, finality, HTTP/budget, runtime, watchdog,
operations/controller and importer-context tests as appropriate. No local full-suite claim.

Update `docs/OWNED-SYNC-OPERATIONS.md` and `docs/STATUS.md` with the new collection/monitoring contract,
current budget versus historical legacy limits, exact tested commands/revision and known latency
trade-off. Review the verified commits/PR before any owner-authorized merge; master CI gates deploy.

### Task 6 — Observe rollout without manufacturing recovery

After an explicitly authorized reviewed release, read back the actual production revision, importer
mode/season, current budget and Kuma configuration. Leave the attempt ledger and old incidents intact.
No emergency manual deploy, budget increase, manual flag/finality update or writer toggle.

Observe the first complete provisional pass and a subsequent unchanged cycle; then at least one
rolling-day interval, a real checked transition and continued source discovery. Read-only evidence:
request totals by time window, admitted/committed/deferred counts, page/source receipt ages,
finality/pending progress, fresh heartbeats and Kuma transition history. Kuma should remain UP during
capacity waits, while authenticated operations still disclose delayed data. Do not deliberately
break production to test a real-failure email; rely on automated failure-path tests and record that
inbox delivery was not retested.

Acceptance requires both fewer redundant requests and preserved mandatory progress. If a cold backlog
already exhausted the old budget, natural expiry may delay rollout recovery; do not clear the ledger.
Rollback uses the normal reviewed release path, retains all real evidence and budget usage, and may
restore the old noisy policy. Do not use OFF/ownership changes as a shortcut for a scheduling rollback.

## Local implementation evidence

Tasks 1–2: `provisionalFinishStopsHourlyRefetchButCorrectionAndRealFinishRefresh` failed first
with one repeated history request instead of zero. The collector now separates provisional
post-match work using the existing progress classifier and durable, revision-scoped receipt keys.
The regression also covers new collector instances/generations, hourly idle checks, regression
back to live, re-entry to provisional, a score correction and a real finish; GW state remains OPEN.
The full affected collector class passed **65/65**, no failures/errors/skips, with
`bash /tmp/fpl-focused-tests.sh --tests '*CoalescedPlayerHistoryCollectorIntegrationTest'`.
The wrapper supplies only disposable `127.0.0.1:55432` credentials and dummy provider keys, with
1536 MiB/no-swap cgroup, single Gradle unit and host-memory stop guard. Docker's working local
socket is `/run/kurssit-docker.sock`; only the project `fpl-test-db` container was started.
Direct diff review found no finality/reader changes; no full suite or production writes occurred.

Task 3: admission regression first returned Allowed where the live reserve required Deferred.
A 48-hour production-ceiling trace then exposed insufficient source overhead; corrected reserves
pass for 670 and 900 players, preserve three mandatory passes per day and cap every sliding window.
This trace checks admission arithmetic, not a full-league end-to-end collector load benchmark.
The real-DB seam checks exact expiry across restart, low caps and priority boundaries; loopback
HTTP proves a refused history request submits no HTTP while discovery still works. Collector
regression proves four long capacity waits retain hourly discovery and zero consecutive-fetch
failure count. Selected pages are now labelled selected, not falsely reported as HTTP requests.
Connected command: `bash /tmp/fpl-focused-tests.sh --tests '*ImporterBudgetTimelineTest'
--tests '*OwnedSyncRequestBudgetIntegrationTest' --tests '*OwnedSyncHttpClientIntegrationTest'
--tests '*CoalescedPlayerHistoryCollectorIntegrationTest'` passed with the same isolated guards.

## Explicit limits

No finite cap guarantees every live refresh and arbitrary source corrections complete immediately.
This plan prioritizes post-match/final evidence and truthful delayed-data diagnostics over repeated
live refreshes. It does not guarantee Supabase egress, memory usage, FPL availability or discovery of
history-only corrections to players no longer fetched; existing manual reconciliation limits remain.
No code, test execution, email delivery test or production modification is claimed by this document.
