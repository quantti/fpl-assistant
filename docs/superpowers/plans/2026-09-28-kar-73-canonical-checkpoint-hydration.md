# KAR-73 — metadata-first canonical checkpoint hydration

**PROGRESS:** Task 4/5 done; deterministic hydration/fencing races pass focused tests.

Ticket: [KAR-73](https://linear.app/quantti/issue/KAR-73).
This expands [Unit 2 of the reviewed progress/checkpoint plan](2026-09-27-owned-sync-progress-and-checkpoints.md#unit-2--metadata-first-canonical-checkpoint-selection-kar-73), not its scope.
KAR-72 is Done in Linear; [PR #140](https://github.com/quantti/fpl-assistant/pull/140) merged on
2026-09-28 as `0cc38c9`, the code baseline for this plan. Older pending-merge notes describe the
27 September checkpoint, not the current prerequisite state.

## Goal and boundaries

Stop `CatalogueScheduleDispatcher` from downloading own checkpoint bodies that metadata already
rejects, or whose missing partner cannot yet be fetched. Keep successful checkpoint reuse,
strict parsing, retry outcomes and publication fencing. Measure SQL calls, returned bodies and
raw body lengths separately; none is a measurement of TCP traffic or Supabase billing.

The [27 September retest](../../OWNED-SYNC-EGRESS-REPORT.md#2026-09-27--kar-71-staging-retest-stopped-at-the-db-threshold)
returned three old-shaped checkpoint bodies and one canonical pair. Their identities are unknown;
this ticket cannot attribute or promise to remove all observed traffic. Latest documented
production containment is OFF epoch 67, not a fresh live observation during planning.

No scheduler/FIFO/budget changes, manifest changes, schema migration, new cache, persistent meter,
provider API, historical-reader changes or fixture-owned/canonical evidence bridging. No production
SQL, external FPL requests, activation, Tapas changes or deployment. A future attended experiment
requires separate owner authorization under the parent plan's operational handoff.

## Current code and the important differences

Paths below are relative to `backend/src/`:

| File | Relevant behavior at `0cc38c9` |
|---|---|
| `main/java/fi/karivanttinen/fplassistant/sync/CatalogueScheduleDispatcher.java` | `obtainPair` tries reusable canonical pairs first, then `usableOwn` downloads the latest same-run body before checking validation/day/floor. Missing endpoints are fetched only afterward. |
| `main/java/fi/karivanttinen/fplassistant/sync/OwnedSyncStore.java` | `latestStagedPayloadMetadata(lease, endpoint)` already selects the latest exact endpoint without a body. `hydrateStagedPayload` rechecks the lease, exact id/run/endpoint/state, season/epoch and maximum of caller/durable source floors. |
| `main/java/fi/karivanttinen/fplassistant/sync/HistoryFixtureDispatcher.java` | Has availability-first hydration, but prefers earliest VALID checkpoints and refreshes malformed resumed pairs. Neither policy belongs in the canonical writer. Read as a reference; do not modify. |
| `main/java/fi/karivanttinen/fplassistant/sync/CatalogueScheduleParser.java` | A pair's span is `ChronoUnit.MINUTES.between(earliestStart, latestFinish) <= 10`, not an elapsed 600-second cutoff. |
| `main/java/fi/karivanttinen/fplassistant/sync/CatalogueScheduleWriter.java` | Strict parsing plus UK publication day/cutovers, transactional demand checks and locked endpoint-specific receipts remain final authorities. |
| `test/java/fi/karivanttinen/fplassistant/sync/OwnedSyncStoreIntegrationTest.java` | Existing `TrackingJdbcTemplate` counts body SELECTs, but its returned-body counter recognizes only `p.body`; it misses the old unaliased `body` query and canonical `b_body`/`f_body`. |
| `test/java/fi/karivanttinen/fplassistant/sync/OwnedMatchdayAdmissionProgressIntegrationTest.java` | Reports HTTP-returned lengths and stored payload lengths, not DB-returned body lengths. Do not relabel those counters as DB egress. |

Keep `findInLatestValidPublicPayloadPairs`, its 20-candidate limit and bounded cache unchanged.
It can legitimately return a body pair before own-checkpoint selection, including from the same
run. Tests for the own path must prevent that shortcut (for example, leave one endpoint UNCHECKED),
not disable or change reusable-pair behavior in production.

## Proposed flow

Keep the existing `process` source-attempt bound and publication handling. Change only the own
checkpoint branch of `obtainPair` and small private predicates/helpers needed by it:

1. Read the durable source floor with the current lease. A future floor still durably defers
   before HTTP or body reads. Try complete reusable canonical pairs in the existing order.
2. Select the latest metadata for **both** own public endpoints with the no-state-filter overload.
   Apply INVALID, current UK day (both read endpoints) and source-start-floor rejection to that
   selected row. A newer INVALID row must not uncover an older VALID row on this own path.
3. Fetch/stage missing endpoints before hydrating a selected partner. Keep fresh response bodies
   local only for this invocation; they are already durably staged, so do not SELECT them back.
   Carry the renewed lease forward. Admission deferral returns its authoritative hint, without
   reading the incomplete pair's stored body or incrementing source-failure counters.
4. Once both endpoints are available as metadata or fresh responses, check their read intervals
   **before** any checkpoint hydration. Preserve conditional precedence: for **resumed** pairs,
   check the span first, then UK date/source floor and endpoint-specific receipts. For entirely
   **fresh** pairs, check day/floor/receipts first and leave the span check to the strict parser.
   Recheck the day after network waits. Use SourceRead/ReadInterval values; do not construct fake
   empty-body source documents. Overlapping failures must follow the outcome table below.
5. Hydrate only selected existing checkpoints, using `hydrateStagedPayload`. Stop at the first
   empty result: durably retry rather than parse a partial pair or fetch repeatedly. Reread
   `requestedSourceAfter(lease)` after the miss, then persist the later of that floor and
   `clock.instant() + 1 second`. The Optional hydration result does not expose its observed floor.
   Perform no further hydration/GET in that invocation. Do not invalidate a vanished id or count
   a source-fetch failure; preserve other stages for next invocation. `LeaseLostException` still
   escapes, including on the floor reread. A later demand race remains fenced on the next attempt;
   this retry-time calculation is not an atomic promise against future demand changes.
6. Retain document-level chronology/day/floor/receipt checks after hydration, then strict parsing,
   validation and the current publication path. Metadata is a rejection optimization, not a new
   publication authority. Keep transactional demand checks against both endpoint intervals and
   the writer's existing group-specific locked receipt checks; their limits are explicit below.

For a partial checkpoint that has aged eleven minutes, the minimal design still fetches the
missing partner, then rejects the now-known pair from metadata and refreshes both. It avoids the
old body read without introducing predictions about future HTTP intervals. No extra pre-fetch
age heuristic is needed. An old **complete**, internally coherent same-day pair is not stale just
because publication waited for a cutover.

### Preserve these outcomes

| Condition | Required outcome |
|---|---|
| Latest own row is INVALID, previous-day or below floor | Treat that endpoint as missing; no body SELECT for that row and no fallback to an older same-run row. Do not newly invalidate unrelated stages. |
| Missing endpoint's HTTP admission is deferred | Preserve existing stages and exact future hint; no own checkpoint body SELECT. Real HTTP failures retain their existing separate behavior. |
| Resumed pair spans 11 whole minutes or more | Invalidate the selected pair with the current source-window reason; use the existing one fresh-pair fallback, without reading rejected checkpoints. The fresh fallback cannot recurse again for this reason. |
| Entirely fresh pair exceeds the span, but passes day/floor/receipt checks | Preserve terminal parser failure, not a refresh loop. No checkpoint body read is needed. |
| Pair reaches the day/floor/receipt checks and fails one | Invalidate the pair and schedule the existing one-second obsolete-pair retry; no selected checkpoint body read. A fresh pair that also exceeds the span still takes this retry, whereas a resumed pair takes its span fallback first. |
| Hydration observes pruning, validation change or advanced durable demand | No rejected body returned; no parse/publication of an incomplete pair. Use the bounded durable retry above. The already-hydrated eligible partner, if any, need not have zero returned bytes. |
| Lease/token/epoch is lost | Fail closed through the existing lease exception; no stale staging/publication or retry write. |
| Own pair has malformed content | Mark pair INVALID and run FAILED, even when resuming. Do not import the fixture dispatcher's malformed-checkpoint refetch fallback. |
| Reusable canonical candidate has malformed content | Skip it and try the next candidate within the existing bound; VALID metadata never bypasses parsing. |
| Publication sees newer demand or receipt | Keep `process`'s existing bounded fresh attempt and subsequent retry handling. |
| Before 02:00 UK price / 03:00 UK schedule cutover | Keep the staged pair and retry at the cutover; waiting does not change source-read instants. |

A body may become obsolete after its guarded SELECT. Hydration itself does not check receipt
chronology or UK midnight. Keep the existing post-hydration checks and test their actual boundaries;
pair-window/receipt checks must not compare a bootstrap receipt to the fixtures' finish or to a
combined maximum.

**Existing publication limits, not silently expanded by KAR-73:** the writer's UK-day check runs
before its publication transaction/lock waits, not at commit. This plan does not claim to prevent
a lock wait crossing midnight after that check. Catalogue publication locks/checks the bootstrap
receipt only; schedule publication locks/checks both bootstrap and fixture receipts. Dispatcher
preflight checks both, but a later fixture-receipt race is not transactionally rejected by the
catalogue writer. Tests cover rollover before dispatcher post-hydration validation and receipt
races on each publication group's guarded streams. Stronger commit-day or catalogue fixture-stream
guarantees require a separate scope decision, not a body-read optimization disguised as a fix.

## Sequential implementation tasks

Work on a dedicated implementation branch after plan review. One bounded agent/build/test unit
at a time, returning to the orchestrator between tasks. No nested or parallel delegation.

### Task 1 — trustworthy read probe and baseline

Files: the two dispatcher/store integration tests above; a small package-private test helper
`test/java/fi/karivanttinen/fplassistant/sync/PayloadReadProbe.java` if needed by those tests and the
admission workload. No production instrumentation or new dependency.

- Instrument the actual JdbcTemplate/row-mapper boundary, not just mocked store invocations.
  Count attempted body SELECTs even when they return zero rows; distinguish old own-checkpoint,
  guarded single-checkpoint and reusable canonical-pair families. Count result rows, body values
  and sum of their raw `byte[].length` separately (a pair is one row with two bodies).
- Count only raw projected body columns; `octet_length(body)`/EXISTS/metadata queries transfer no
  bodies. Count bodies from results already fetched; do not issue measurement-only body SELECTs.
  Assert the probe with one known own-body read, one canonical pair, a guarded zero-row read and
  a metadata-only read. Reset it after seeding and before the operation under test.
- Add focused red regressions for INVALID/day/floor rejection and a usable partial checkpoint
  whose missing endpoint defers. Exercise bootstrap-missing and fixtures-missing symmetrically.
  Pin current outcomes/validation/fetch-failure counts alongside the failing body-read assertions.
- Capture the parent plan's same two-worker mixed-backlog baseline before production edits, with
  the probe on the store JDBC path. Keep the two arrival orders, clock schedule and bounds intact.
  Record DB body families separately from the existing HTTP/staged-length counters. An unchanged
  workload can show no KAR-73 saving if it never resumes a canonical checkpoint; report that honestly.

Verify: targeted new cases fail for the expected old body reads, not setup or transport errors;
probe self-checks and the unchanged admission workload pass. Save baseline values in this plan's
outcome record, including commit, scenario and measurement scope. Do not commit an unexplained red
suite as a completed implementation.

### Task 2 — latest metadata selection and availability-first hydration

Files: `CatalogueScheduleDispatcher.java`, its integration test, and focused store tests.
Reuse the current metadata/hydration APIs; no store production change is expected.

Implement flow steps 2–3 and guarded hydration. Add newer-INVALID-over-older-VALID and exact
endpoint/run/season tests. A newly fetched partner must not be reread from the database; a healthy
resumed single checkpoint is hydrated exactly once after the partner succeeds. Cover both
endpoint orders and a new dispatcher/store instance with a cold cache after retry. A simple
scripted Fetch suffices for deterministic response/deferral ordering; also retain at least one
partial-pair case through the real budget and loopback HTTP/fenced constructor.

Verify: Task 1's own-read regressions pass; eligible partial pairs keep their good checkpoint,
resume successfully, preserve true due hints and do not increment consecutive fetch failures for
admission waits. A hydration miss persists the exact due time derived from its fresh floor reread,
performs no subsequent hydration/GET in that invocation, and preserves fetch-failure counters.
Fresh pairs use zero own-checkpoint body SELECTs; reusable pair reads are counted separately.
No framework or generic checkpoint abstraction is necessary.

### Task 3 — pair chronology before hydration without policy drift

Files: `CatalogueScheduleDispatcher.java` and its integration test. Parser/writer production
code stays unchanged.

Implement the metadata interval/receipt preflight and retain post-hydration checks. Add these
explicit boundary cases, in addition to the existing source-window and receipt-race tests:

- Whole-minute spans of exactly 10 minutes, 10 minutes 59 seconds and exactly 11 minutes;
  accept the first two and reject the third. A fresh invalid window with otherwise usable
  metadata is terminal; a resumed invalid window refreshes once. Add overlapping-condition
  cases: a fresh over-window pair crossing UK midnight or regressing a receipt gets the obsolete
  retry before parsing, while a resumed over-window pair takes the window fallback first.
- Known bootstrap-receipt and fixture-receipt regressions separately, including equality as
  non-regression and a regression discovered only after fetching the missing partner.
- An eleven-minute-old partial restart (currently three fetches) and a complete out-of-window
  UNCHECKED pair (currently two fetches): same validation/retry outcomes, zero rejected own bodies.
- A coherent pre-cutover pair much older than ten minutes at publication, restart at both UK
  cutovers, previous-day own stages, and a missing-partner wait crossing UK midnight. Cover a BST
  instant where UTC and UK calendar dates differ; use Europe/London rather than a fixed offset.
- Malformed resumed own content is terminal; a malformed reusable VALID pair permits the next
  usable canonical candidate. Arrange own-path tests so reusable lookup cannot accidentally win.

Verify: **metadata-pre-rejected** candidates issue zero own body SELECTs, not merely zero returned
rows. Malformed-content rejection necessarily reads the bodies first; assert those needed reads
and the terminal/fallback distinction instead. Eligible complete UNCHECKED checkpoints hydrate
exactly two bodies; pre-cutover VALID pair reuse may use the existing canonical cache/read family.
Preserve fetch counts unless an explicitly justified earlier rejection makes a fetch unnecessary;
explain any such change in the outcome.

### Task 4 — deterministic selection/hydration and publication races

Files: dispatcher/store integration tests; only a narrowly demonstrated production fix if a
regression exposes a missing existing guard. Stop for plan review if it needs a new persistence
protocol, a new cache or expanded store semantics.

Use test JDBC hooks or bounded latches at the selection/hydration boundary, not timing sleeps or
production fault-injection plumbing. Exercise:

- Demand advances after metadata selection, including a floor in the future.
- Selected validation changes (including UNCHECKED to VALID), or the row disappears. A same-run
  deletion is defensive injected loss, not a claim that normal retention prunes active runs:
  `deleteTerminalPayloadsOlderThan` deliberately protects active work.
- Exact endpoint/run/season mismatch and fenced lease expiry/reassignment/epoch replacement.
- Loss of the second checkpoint after the first was read; do not demand impossible zero-total
  bytes. Require zero bytes from the rejected candidate and a durable nonterminal retry.
- Receipt advancement after preflight on a transactionally guarded stream (retain
  `sourceReceiptRaceRefreshesOnceAndUsesTheSamePublicationErrorHandling`; bootstrap for both groups,
  fixture receipt for schedule). Assert the old attempt cannot publish and the bounded fresh
  attempt can. Rollover after selection but before dispatcher post-hydration validation must
  take the existing rejection path; do not claim a commit-time day check that does not exist.
- SQL publication rollback, retained validated stages and successful restart; retain STAGING's
  no-core-write behavior separately from isolated ACTIVE publication tests.

Verify actual guarded-query counts and returned bodies per rejected id; a raced guarded query may
be executed and return zero rows. Assert no publication writes from the rejected attempt, distinct
from receipt changes injected by the racer or a later successful fresh attempt. Require no false
source-failure count, finite completion and complete latch/worker cleanup. Exercise the
fenced runtime store, not only the existing unfenced test seam.

### Task 5 — focused verification, combined evidence and delivery

Run the changed dispatcher and store test classes sequentially, then rerun the unchanged seeded
admission scenarios with the measurement-only probe from Task 1. Compare first-history time,
distinct history completions, STAGED fixture progress, admissions/deferrals, peak new partial pairs
and DB body-read families. Keep workload bounds and fixtures fixed. If necessary body reads grow
with progress, show both numbers; do not infer a production cost rate.

Record focused results, baseline/final counts and remaining limits here and in `docs/STATUS.md`.
Review for accidental edits to the canonical cache, fixture/history policy or activation paths.
Commit the verified change on its feature branch, push normally, open a PR to `master` with
`Closes KAR-73`, then perform independent PR review and push/re-review fixes. Full PR CI is the
suite gate; the owner merges. Do not close KAR-73 for this planning document alone.

## Verification commands and stop conditions

Planning runs only document/link/diff checks. During implementation, follow
[`docs/DEVELOPMENT.md`](../../DEVELOPMENT.md) for the current host; verify the disposable container
is loopback-only and `.env.test` names that container. Never load `.env.supabase` or run session
SET through the transaction pooler. Keep checked-in Gradle heap/worker limits and a wall timeout.
Do not run these commands concurrently or run the full local suite:

```bash
# From the repository root; implementation only, after local DB/toolchain preflight.
scripts/test-db.sh up
cd backend
set -a; source .env.test; set +a
./gradlew --console=plain cleanTest test --tests '*CatalogueScheduleDispatcherIntegrationTest'
./gradlew --console=plain cleanTest test --tests '*OwnedSyncStoreIntegrationTest'
./gradlew --console=plain cleanTest test --tests '*OwnedMatchdayAdmissionProgressIntegrationTest'
```

During Tasks 1–4 narrow `--tests` further to the cases just added/touched. If shared store behavior
actually changes, include the affected history/fixture and canonical-cache regressions before
claiming it safe; do not silently widen the production scope. Stop rather than loosen source
floors, parser boundaries, lease/epoch guards, workload bounds or malformed-own-pair terminality.
No local test result authorizes a production experiment or proves GW5 restoration/monthly cost.

## Review and outcome record

Planning review completed on 2026-09-28. The first independent review found a rejection-precedence
change for overlapping fresh-pair failures, overbroad publication-race claims and an unqualified
zero-SELECT assertion. The revision preserves fresh/resumed precedence, documents existing writer
limits, separates metadata rejection from malformed content and specifies a fresh floor reread on
hydration miss. Independent re-review found no remaining blockers or warnings; the plan is ready
for owner-approved bounded implementation, not activation.

Linear KAR-73 and KAR-72 were read, the prerequisite merge verified against GitHub, and relevant
code inspected. Documentation diff/link checks passed. No test/build, production query, mode
change, implementation commit or deployment was performed during planning.

Implementation Task 1 baseline, `0cc38c9`, disposable loopback DB: the test-side JdbcTemplate
probe self-check passed for old own, canonical-pair, guarded one-row/zero-row and metadata-only
queries. Five targeted regressions fail solely because the old own path SELECTs an ineligible or
incomplete body (two own attempts, one returned 1,750,769-byte body per case); state, retry,
fetch and failure-count assertions pass. The unchanged 60-virtual-second two-worker mixed backlog
passed in both arrival orders: first history GET/completion 1.1/1.2s, 30 histories, zero staged
fixtures, 55 admitted/returned GETs (2,497,034 HTTP bytes), 39–40 claims, zero deferrals, 41
dispatches, peak one new partial pair and 5,045,185 staged raw bytes. DB body-family counts
(attempts/rows/values/raw bytes), identical in both orders: old own `0/0/0/0`, guarded single
`5/5/5/1101550`, canonical pair `1/1/2/345051`, other `0/0/0/0`. This workload does not resume
an old own checkpoint; its baseline cannot demonstrate a KAR-73 traffic reduction. These are
synthetic raw lengths, not TCP or Supabase billing.

Task 2: own checkpoints now select the latest metadata for each endpoint; a missing partner is
staged before guarded hydration, and a miss retries at the later of the refreshed floor and
`now + 1 second`. The reusable-pair path and store production code are unchanged. Seventeen
focused dispatcher cases and six store cases passed against the disposable loopback DB, including
both partial orders, newer-INVALID masking, real fenced budget deferral, cold restart, floor/
validation miss and lease loss. Pair-level metadata chronology remains Task 3.

Task 3: pair interval/day/floor/receipt preflight now precedes guarded hydration. Resumed pairs
check the whole-minute span before obsolete conditions, while fresh pairs retain the original
obsolete-before-parser order. The 47 dispatcher integration tests passed on the disposable DB,
including 10m/10m59s/11m boundaries, endpoint receipts, UK midnight/BST, pre-cutover reuse and
strict malformed-own versus reusable-candidate handling. Strict parse and post-hydration checks
remain in place; race coverage is Task 4.

Task 4: deterministic test-side hooks cover demand/validation/deletion races, second-checkpoint
loss after first hydration, fenced lease expiry/reassignment/epoch change, UK-day rollover after
selection, guarded receipt publication races, SQL rollback/restart and STAGING versus isolated
ACTIVE publication. Eleven focused dispatcher and seven store cases passed on the disposable DB;
no new store protocol or production change was needed. The second-checkpoint case asserts the
first accepted body's exact byte count and zero returned bytes for the rejected checkpoint id.

Task 5 local verification: the complete affected dispatcher and store integration-test classes
passed independently, then all five admission/progress tests passed, sequentially on the
loopback-only disposable DB with checked-in Gradle limits and wall timeouts. The two fixed
60-virtual-second arrival orders each completed 30 histories (first GET 1.1s, completion 1.2s),
zero fixtures, 55 HTTP admissions/GETs, zero deferrals, peak one new partial pair and 41
dispatches; claims were 39 and 40. Each returned 2,497,034 HTTP bytes and staged 5,045,185 raw
bytes. DB body reads in each order matched the baseline exactly: old own `0/0/0/0`, guarded
single `5/5/5/1101550`, canonical pair `1/1/2/345051`, other `0/0/0/0` (attempts/rows/values/
raw bytes). This workload never resumes an own checkpoint; a reduction is demonstrated by the
focused own-path tests, not by an unchanged throughput case. The separate finite drain completed
50 fixtures and 68 required unique histories in 613.4 virtual seconds, 506 admitted GETs,
peak one partial pair, zero failed runs. None of these synthetic lengths is TCP traffic or
Supabase billing; there was no production action. `git diff --check` passed; canonical reusable
cache, history dispatcher and activation paths were not edited. Full suite belongs to PR CI.
