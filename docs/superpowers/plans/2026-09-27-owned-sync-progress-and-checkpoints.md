# Owned sync — bounded history progress and canonical checkpoint reads

**PROGRESS:** KAR-72 transport fault-evidence cases added and passed; ready-selection/progress remains pending. Implementation 0/2 units. No production activation.

Follow-up to KAR-71. Evidence: [STAGING retest PR #137](https://github.com/quantti/fpl-assistant/pull/137)
and [`OWNED-SYNC-EGRESS-REPORT.md`](../../OWNED-SYNC-EGRESS-REPORT.md). The latest verified safe
state is OFF epoch 67, zero active work, 40 verified fixtures and no GW5 PFS rows. The report in
PR #137 supersedes the 26 September observation on `bc5ced7`; the retest used `80dba18`, including
KAR-71. Delivery tickets: [KAR-72](https://linear.app/quantti/issue/KAR-72) (Unit 1) and
[KAR-73](https://linear.app/quantti/issue/KAR-73) (Unit 2).

## Outcome and limits

Make real player-history requests and completed history jobs progress under a fixture backlog,
without starving fixture continuation/verification. Separately, stop the canonical writer from
reading checkpoint bodies it can reject from metadata or cannot yet use. These are two reviewed
implementation units, delivered sequentially on separate branches/PRs, then measured together.
Neither is a claim that the shared Free-tier allowance will suffice.

KAR-70's unavailable provider-API integration is **not a dependency**. This plan adds no persistent
meter, alerts service, general-purpose scheduler, new cache or production budget defaults. It
neither authorizes another STAGING run nor restores ACTIVE or Tapas. A future attended experiment
requires its own explicit limits, rollback guard and owner authorization.

Assumptions: one current owned-runtime epoch with two workers sharing one HTTP client; durable
season/epoch/lease fencing remains authoritative if an old process survives. Application-local
fairness must not be described as fairness across independent machines/processes.

## What the evidence establishes

- The retest stopped about 66 seconds after the mode request (including startup), not after ten
  minutes of useful work. It measured about 15.22 MB DB TCP payload before shutdown, not exact
  provider billing. No steady-state or perpetual-starvation conclusion follows from that window.
- Fifty fixture runs and 68 history runs remained at shutdown. All 68 histories had zero attempts;
  49 fixtures retained priority 1, while one dependency-waiting fixture had priority 4. Histories
  are priority 3. `OwnedSyncStore.acquire` sorts by priority, due time and generation.
- Ten fixture runs held unchecked 1.78 MB bootstraps. A fixture can fetch its bootstrap, lose the
  next minimum-spacing admission, and retry at priority 1 before reaching dependency discovery.
  Another untouched fixture can then fetch another bootstrap. Uploads are DB ingress, not DB RX.
- The old-shaped checkpoint body SELECT returned three rows and the canonical pair SELECT one
  pair. `CatalogueScheduleDispatcher.usableOwn` still loads a body before its date/source-floor
  checks; KAR-71 changed the history/fixture path only. The counters do not reveal whether those
  three particular bodies were ineligible or which payload ids/bytes crossed the wire.
- `OwnedMatchdayWarmStartPressureIntegrationTest` drains serially and its fake always returns 200
  after advancing its clock one second. `prerequisitesAndDependenciesCannotBeStarvedByWaitingFixtureFanout`
  seeds fixtures already in dependency wait. Neither reproduces early HTTP deferral with two
  competing workers. Merely acquiring a history lease is not proof that its HTTP request is admitted.

## Invariants for both units

Keep the configured playing season, runtime epoch, lease/token, exact endpoint identity, validation
state, requested-source floor and genuine newer-demand checks. Keep same-run pairs, strict parsing,
post-history public rechecks, correction revocation and optional-component masking. Do not bridge
fixture-owned observations through the canonical cache to make request counts smaller.

Keep the one-second HTTP spacing, the two-live-permit safety ceiling, 5,000 attempts per rolling day,
Retry-After/cooldown handling, payload caps, planner/source schedules or two-worker count. Do not
count scheduling/admission/dependency waits as failed source fetches. Keep source-read intervals
separate from scheduling time. Keep the two independent verification batches whose second start
is at least ten minutes after the first end; STAGING completion is not VERIFIED publication.

No Tapas table/schema changes, production SQL writes or session SETs through the transaction
pooler. Tests use disposable Postgres, TapasSeed, owned `_test` tables and loopback FPL only.

## Unit 1 — bounded admission and history progress (KAR-72)

### 1a. Establish the failing case and review the scheduling decision

Create `OwnedMatchdayAdmissionProgressIntegrationTest` under
`backend/src/test/java/fi/karivanttinen/fplassistant/sync/`. Use real store/coordinator/dispatchers,
the real `OwnedSyncRequestBudget` and `OwnedSyncHttpClient` with its loopback-only seam. Add only
package-private clock/wait seams needed for deterministic transport tests; the source host stays
fixed in production. One clock authority drives budget, dispatch and source intervals. Advance
virtual time in the driver at explicit boundaries, not implicitly on every successful response.

Seed a valid canonical pair, 50 relevant fixture runs (one dependency-waiting and 49 higher-priority
runs, ten with usable partial checkpoints) and 68 due histories. Also run the smaller cold-parent
case so history preference cannot suppress the canonical prerequisite jobs. Pin full fixture and
participant data; the exact synthetic player count need not impersonate a production roster.

Drive two controlled workers with 100 ms simulated response durations and both arrival orders.
Preserve production's one-second fixed delay **after each dispatch completes**, not a new dispatch
every second regardless of duration. Barrier-control request start/response, budget contention
and dispatch completion; advance to the next scheduled event only when worker state is known.
Use the identical event schedule before and after the change. The initial case is bounded to
60 virtual seconds / 150 dispatches and 120 seconds wall time. A separate wall watchdog must
release barriers, interrupt/join workers and close the client in finally; assert cleanup, so a
fake-clock deadlock cannot hang the test JVM.

Track separately: claims, deferrals, admitted attempts, server-observed GETs by endpoint, valid
completed histories, STAGED fixtures, body rows/raw lengths returned and new partial bootstrap
checkpoints. No production logs, payloads or credentials are test fixtures. A spy returning
canned deferrals alone is insufficient: the actual limiter must be responsible for admission.

**Checkpoint:** capture the baseline failure and review the proposed mechanism below before
changing production scheduling. Compare it with bounded due-class rotation using the same fair
admission primitive; choose the smallest mechanism that meets all §1c targets. Record the selected
policy, a numerical end-to-end invocation/cleanup budget and the resulting timing tradeoff here.
The proposed two-second wait is not approved merely by writing it into this plan. If a durable
scheduling protocol or broader deadline/pool redesign is needed, stop and revise the plan before
implementation. Do not raise test bounds to hide failure.

### KAR-72 checkpoint — reproduction complete; production policy not approved (2026-09-27)

`OwnedMatchdayAdmissionProgressIntegrationTest` uses fenced store/coordinator/dispatchers,
real budget and loopback HTTP, a driver-controlled clock, two completion-relative workers,
100 ms responses and a separate wall watchdog. It seeds a valid canonical pair, 49 priority-1
fixtures, one dependency-waiting fixture, ten partial bootstrap checkpoints and 68 histories.
With unchanged runtime selection/admission, both arrival orders failed the first-history target
at 60 virtual seconds. In the latest focused run the first order made 112 dispatches,
56 admitted/server-observed GETs, 108 deferrals, zero history GETs/completions and reached
37 **new** partial pairs at peak. The reverse order made 112 dispatches, 56 GETs, 109
deferrals, zero history GETs/completions and peaked at 38 new partial pairs. The cold-parent case fetched bootstrap, then fixtures on the same parent's
bounded retry after spacing. These are synthetic local observations, not traffic or source
billing estimates. The baseline driver advances virtual time only at observed response
releases or worker completion-plus-one-second boundaries; it does **not yet** drive a future
FIFO spacing wait. Before comparing policies, add an explicit wait-state acknowledgement and
use the same event schedule before/after. Do not advance the clock on a quiet-period guess.
This deliberately failing test is **not** a green implementation or a production SLA.

**Design blocker:** neither worker preference nor due-class rotation is selected yet. Rotation
(history → ready continuation → history → untouched fixture, with due canonical work first) is
worth comparing with worker preference, but classifying a *ready* continuation over older
blocked dependency retries without body reads remains unproven. More importantly, no numerical
method-entry-to-**durable-settlement** deadline can be justified from the present transport and
work-pool settings. A 30-second transaction timeout is not a preemptive wall deadline for pool
acquisition, commit, rollback and connection cleanup. Even an illustrative envelope of 2 seconds
queueing + four DB operations of 2 seconds pool / 30 seconds transaction + 30 seconds HTTP +
1 second cancellation is 161 seconds before terminal I/O allowance; a spacing retry or further
fence checks enlarge it. This is **not** an approved bound: a timeout/interrupt can make synchronous
`budget.complete()` fail, leaving a permit live until its durable two-minute expiry. Permit
expiry does **not** reconstruct a lost Retry-After cooldown. Presently interruption is restored
before `complete()`, increasing that risk under pool contention. A bounded *attempt* to settle
is not the same as a guaranteed settled permit. `nextAdmissionHint()`
in the dispatcher is another out-of-deadline DB call.

Per §1a, stop before production scheduling changes. Design approval must decide whether an
unsettled permit with expiry/containment is acceptable on DB failure, or require a stronger
persistence/deadline design. Then select/test a numerical deadline inclusive of pool, lock,
socket, cancellation and settlement; define lease/epoch/source-floor checks at the dispatcher
handoff; compare the two scheduling policies against all §1c targets using the same driver.
Do not weaken the acceptance bounds or enable STAGING/ACTIVE to bypass this checkpoint.

**DB-failure scope review (owner approved investigation, 2026-09-27):** An expiry-only
unsettled-attempt guard is unsafe. An admitted request can receive `429 Retry-After: 7200`, fail
to commit its result, and crash; if the database recovers before the two-minute permit expiry,
a second caller can see no cooldown and issue an early GET. The database cannot distinguish
this row from an ordinarily in-flight request. Current retention also deletes expired incomplete
attempts after 24 hours, so even an expiry guard would forget a lost, arbitrarily long
Retry-After. Completion/cooldown must remain atomic; an ambiguous commit cannot be treated as a
refund. Waiting out the TTL does not reconstruct a lost header.

The smallest fail-closed design, without a schema change, refuses new admission while **any**
attempt lacks a confirmed completion, retains those rows beyond 24 hours and requires explicit
operator reconciliation for an unknown result. That keeps the two-live-permit *ceiling* but
serializes normal overlapping requests and can stop sync indefinitely on a crash or database
failure. Already-admitted but not-yet-sent requests also need a final gate. Unacknowledged
cancellation can still receive headers after the current completion snapshot; such an exchange
cannot be marked safely settled merely by holding a completed permit for two minutes. The
interruption path must attempt settlement before restoring the interrupt flag. A stronger
multi-permit protocol would need a separately reviewed, process/epoch-aware request lifecycle,
not just a queue-order change. Neither design can promise a successful database commit by a
finite deadline during an arbitrary outage: the defensible endpoint is **confirmed settlement
or an explicit uncertain outcome with durable fail-closed containment**. No numerical
method-entry bound is approved yet; the actual work-pool contention, cancellation and cleanup
behavior must be measured before choosing one.

**Owner decision 2026-09-27:** approved the smallest fail-closed design: retain the two-permit
*ceiling*, serialize unfinished requests, and pause indefinitely pending manual reconciliation
of an unknown outcome. A 30-second recheck is a wakeup, not permission to send a GET. Do not
claim a successful database commit can be guaranteed during an outage: the deadline contract
must distinguish confirmed settlement from an uncertain result whose original attempt remains
durably blocking. The original §1c safety and progress targets remain; if serialization makes
the healthy-source targets impossible, stop and revisit the policy rather than restoring unsafe
overlap. The budget prerequisite is implemented in a separate bounded step and its 11 focused tests
passed against the disposable database. They prove unresolved rows block before/after TTL and
after 24 hours, not that an actual database outage or lost commit acknowledgement is handled.
The HTTP-client settlement, FIFO and scheduling changes are not approved until the monotonic
deadline and caller-fence handoff are specified and reviewed. Changes to
crash-expiry and unacknowledged-cancellation tests must explain this stronger safety contract.
No schema change or production activation is authorized.

**Deadline follow-up:** independent review of the approved budget prerequisite found that the
current work pool's two-second connection-acquisition, three-second connection/lock, ten-second
socket/query and 30-second transaction settings do **not** bound the entire synchronous JDBC
operation, commit, rollback, or cleanup. Merely choosing 60 seconds or subtracting a queue wait
from the 30-second HTTP timeout would be a false guarantee. A bounded return requires a
separately supervised, fixed-capacity DB-operation path that can stop the caller waiting while
never sending a late GET; timed-out admission may later commit only an unresolved row and timed-out
settlement may later atomically commit completion plus cooldown. Until those outcomes are known,
the durable unresolved row must block fresh admission. The operation path must quarantine
nonterminating work so timeout retries cannot accumulate unbounded threads/connections, and must
be tested under pool/lock/socket and ambiguous-commit faults before selecting a numerical
method-entry bound. A hard successful commit during an arbitrary outage is impossible. The owner approved this fixed-capacity supervised DB path on 2026-09-27. Before transport
integration, verify two stuck operations exhaust two zero-queue workers without spawning
replacements and that late tasks cannot send HTTP. A **candidate** 180-second per-GET
method-entry-to-return target allocates 2 seconds to future FIFO waits, up to 70 seconds to
prechecks/admission, 35 seconds to the final caller fence, 30 seconds to HTTP, 35 seconds to
settlement, 2 seconds to termination acknowledgements and 6 seconds to local overhead.
Each DB operation receives at most 35 seconds of caller wait through one monotonic deadline;
its worker may finish later but cannot initiate HTTP. This is a caller-return bound under a
schedulable JVM, **not** a promise of a successful DB commit, process restart time or physical
remote cancellation; no numerical bound is approved until fault tests verify it. Supervise at
most two DB operations with no queue or caller-runs fallback. Timeout leaves an unknown result,
not a refundable permit; `complete()` remains atomic with cooldown. Also repair the
unacknowledged-cancellation path: a completed-but-TTL-held attempt does not block new admission
and cannot protect a late Retry-After; leave it unresolved instead. Caller fencing and eliminating
the extra out-of-deadline `nextAdmissionHint()` query belong in that transport unit. The owner
approved fixed-capacity DB supervision; a candidate 180-second bounded-return transport and both
dispatcher fences have focused local coverage; commit-ack ambiguity, two-worker saturation and
already-started late-429 cases now have focused evidence, while rollback/connection-close faults
remain untested. Neither a 180-second production guarantee nor a scheduling policy is accepted yet.

**Transport fault-evidence follow-up (2026-09-27):** `OwnedSyncHttpClientIntegrationTest` adds
four disposable-Postgres/loopback cases: a post-COMMIT admission acknowledgement loss that times
out, sends no late GET and remains blocking beyond permit TTL; a post-COMMIT settlement
acknowledgement loss after `429 Retry-After: 7200`, where cooldown persists but the caller receives
`SETTLEMENT_UNCERTAIN`; blocked connection acquisition exhausting both zero-queue DB workers while
callers return and no late GET follows release; and a started/cancelled exchange that later consumes
a loopback 429 while its permit remains unresolved. The final focused run passed all 37 class tests
(0 failures, 71.73 seconds wall; JUnit class time 26.953 seconds). The four added test-case times
were 0.398, 0.470, 0.364 and 4.452 seconds respectively. The injected DB caller waits were 150–200
ms; asserted caller-return ceilings were 2–2.5 seconds, with saturation rejected within 300 ms.
These are controlled fault observations, not a 180-second end-to-end guarantee. Real rollback / connection-close stalls, a genuinely exhausted production-style pool, JVM scheduling pressure and the full method-entry envelope remain untested; no production, scheduling or manifest code changed.

**Scheduling checkpoint — further owner decision needed:** current run/payload *metadata* cannot
say whether a matured two-minute fixture retry has all its particular histories. That required
player set is discovered from its own parsed bootstrap, fixture and live bodies, then the queue
stores only a generic waiting priority/error; STAGING does not publish verification evidence.
Consider eight older, still-blocked due retries followed by one due retry whose histories are
complete. All nine can have identical allowed selection metadata. Worker preference and due-class
rotation both pick the older blocked runs first, so the ready ninth cannot be selected in four
completed dispatch opportunities. Randomness does not give a bound. Do not claim a preference
policy meets §1c by merely selecting *some* fixture lease.

Two honest choices: (1) with approval, add a narrowly scoped durable per-run dependency manifest
(<=80 players, exact own checkpoint ids and source floor), recorded when parsing the fixture's
already-fetched bodies and fenced/invalidated on changed demand/checkpoints/epoch; selection reads
only its metadata against valid history evidence, not new payload bodies; or (2) retain current
schema, weaken the four-opportunity **ready** guarantee to bounded class probing with a bound
that grows with blocked backlog. The reviewed plan requires a stop if a durable scheduling
protocol is needed: no production claim-selection change until the owner chooses. This is
separate from, and not automatically authorized by, the approval for the DB supervisor.

**Owner decision (2026-09-27):** approved the narrow owned, `fpl_`-prefixed per-fixture
manifest table; PRs #138 and #139 have merged, and KAR-72 is rebased onto master. The manifest
write is now implemented with the exact same-run VALID bootstrap/fixtures/live payload IDs,
source floor and 1–80 sorted unique required players, inside the leased run's retry transaction.
It does not make claims ready by itself. Focused store and dispatcher integration tests (59 total)
pass against disposable Postgres, including stale demand, rollback and a lost history hydration.
Next prove metadata-only readiness matches the dispatcher's hydration rules, then compare
selection policies on the unchanged two-worker driver; fair HTTP admission and DB fault tests
remain independent blockers. Neither claim-selection nor a production rollout is approved by
these passing manifest tests alone.

Serialized unfinished requests also make a simple admission-only FIFO insufficient. A second
caller first sees `UNSETTLED` even for a healthy 100 ms predecessor; its 30-second recheck hint
would miss the 15-second history target. A fair turn must wait *boundedly* for a known local
predecessor's confirmed settlement, then retry durable admission; an orphan/unknown attempt
still refuses immediately. With completion-relative one-second spacing and two queued callers,
the second eligible GET can begin after 2.1 seconds, so the original proposed two-second
queue-plus-spacing limit must be retested/revised inside the 180-second method-entry budget,
not quietly raised to make a failing test pass.

### 1b. Proposed minimal mechanism

A global priority reversal merely trades history delay for fixture delay. Alternating claims alone
can still let one worker lose every HTTP slot. Evaluate these complementary changes first:

1. Give one of the existing workers history preference and the other fixture preference, while
   keeping due canonical prerequisites first. Borrow work when the preferred class has no
   claimable job (future-dated or exclusively leased work is not claimable). Give due fixture
   continuations, including matured dependency retries, bounded preference, not an unlimited
   override of untouched fixtures; §1c bounds both selections. Use bounded existing run metadata,
   not payload bodies, for selection; retain every season/epoch/expiry/SKIP LOCKED condition.
2. In the shared HTTP client, use an interruptible FIFO **admission-only** turn for short
   `MINIMUM_SPACING` waits. A denied caller retains its place, waits to the returned instant and
   asks the durable budget again. Proposed maximum queue-plus-spacing wait: two seconds per
   invocation. Hold no JDBC connection/transaction while sleeping or queuing. `budget.admit()`
   necessarily uses its short transaction during admission; release the turn before network I/O.
   Do not pre-consume a permit when claiming a job. Other budget denials remain immediate with
   their authoritative hints; no waiting out a cooldown, day ceiling or held permit.

This is a proposal to review against the reproduction, not permission for an unlimited sleep or
an extra HTTP retry. At most one GET follows one allowed permit. The current 30-second timeout
starts **after** JDBC admission and does not bound the method: cancellation acknowledgement and
synchronous `budget.complete()` can add time. Do not describe it as an existing end-to-end limit.
Before approving FIFO, define one monotonic deadline starting at method entry, including queueing,
admission/fence SQL, network wait, cancellation and durable permit settlement. The checkpoint must
choose/test its numerical budget with realistic bounded pool/lock/socket contention, not simply
subtract two seconds from the transport timeout. Do not mutate shared JdbcTemplate/transaction
limits per caller. If bounded settlement cannot fit safely, return for design approval; do not
silently add asynchronous persistence or forget an admitted permit.

Recheck lease/epoch after waiting and after permit acquisition before submission; specify the
caller-fence handoff in both dispatchers. No GET may start after cancellation, expiry or observed
fence loss. Preserve held-permit behavior when an already submitted exchange cannot acknowledge
cancellation. Waiting interruption/timeout/shutdown must produce a no-fetch outcome and no
fabricated source evidence; do not count it as a server failure or shorten a true Retry-After.
Test FIFO re-entry/barging, both lock contenders, expiry and unacknowledged cancellation. Failure
to prove these semantics is a design blocker, not an excuse to omit them.

Candidate production files (all under `backend/src/main/java/fi/karivanttinen/fplassistant/sync/`):
`OwnedSyncStore`, `OwnedMatchdayCoordinator`, `OwnedMatchdayRuntime`, `OwnedSyncHttpClient` and,
only for caller-fence integration, the two dispatchers. Any change to the durable budget's timeout
API must be explicitly selected at the checkpoint. The owner has approved only the additional owned fixture-dependency manifest table, not
modifications to Tapas tables. Keep the existing unfenced store seam's scope explicit; test the
actual fenced runtime path as well.

### 1c. Acceptance and review

Under the pinned fast-successful source, stable demand, usable canonical parents and available
budget, require the following **test targets, not production SLAs**:

| Scenario | Required evidence |
|---|---|
| Original mixed backlog | First valid history completes within 15 virtual seconds; at least ten distinct histories complete within 60 seconds. |
| Competing requests | Once both callers are waiting for admission, each gets a server-observed GET within the next two admissions; exercise either worker arriving first. |
| Ready fixture selection | A due oldest ready continuation is selected within four completed dispatch opportunities and ten virtual seconds of becoming claimable, even with histories continuously due; its two final recheck GETs occur within four subsequent admissions and it reaches STAGED within 20 virtual seconds of becoming claimable. |
| Other claimable work | In the ready-continuation pressure case, an untouched due fixture is selected within eight completed dispatch opportunities and 20 virtual seconds. A newly due canonical prerequisite is selected within the next two claim opportunities after already leased work yields. |
| Nonclaimable/blocked preference | Future-dated/source-floor work and exclusively leased preferred jobs cannot idle the other worker; matured-but-still-blocked dependency retries must yield to a ready continuation within the same fixture-selection bound. |
| Partial pairs | In the controlled healthy-spacing case, no more than two newly outstanding partial initial pairs; usable checkpoints resume without bootstrap refetch. Distinguish pre-seeded partial pairs. |
| True limits/failure | One-second spacing, two live permits and rolling ceiling hold; a two-hour Retry-After, permit hold or exhausted budget causes no early GET. Invalid histories stay invalid. |
| Lifecycle | Restart store/client/workers without clearing durable budget or checkpoints; cover lease expiry, epoch replacement, interruption and shutdown while waiting; stale work cannot stage/publish. |
| Evidence | Genuine newer demands still reject earlier evidence; isolated ACTIVE tests retain ten-minute batch separation, corrections and receipts. |

Do not certify fixture progress solely from a lease or `WAITING_FOR_HISTORIES`, or verification from
a STAGING result. The ready-continuation case seeds an already-due retry with complete source-valid
dependencies. Separately advance the clock past a real two-minute dependency retry and repeat the
selection/completion assertions; the 60-second case alone cannot cover this path. A claimed job
whose source floor is still future must defer without a GET and yield, never lower its floor.

Add a finite-drain case over the 50 seeded fixtures plus all histories their pinned manifests
require (68 initially due is not necessarily the entire dependency population). Stop at the first
of 2 virtual hours, 5,000 admissions, 6,000 dispatches or 300 seconds wall time; assert all fixture
jobs STAGED and all required history jobs completed, with no failed/abandoned job counted as a
successful drain. These are test ceilings, not a production SLA or permission to spend 5,000 live
requests. Count required unique players, repeat history fetches and fixture retries in the result.
If it cannot drain under the unchanged daily ceiling, return to design instead of resetting the
budget/clock or removing rows. Independently verify that continuous source changes, unavailable
parents or exhausted quota defer honestly; they are exceptions to the healthy-source target,
not opportunities to bypass evidence.

Run only the new/changed focused tests locally, including relevant store, coordinator, runtime,
HTTP-client, budget and history/fixture dispatcher cases. Preserve the existing pressure test as
a memory/serial-drain test with its limitation explicit; it is not the new admission oracle.
Commit/push a dedicated branch, open a PR to master, require full PR CI and an independent review.
Return to the orchestrator before Unit 2. Deployment remains OFF.

## Unit 2 — metadata-first canonical checkpoint selection (KAR-73)

Keep this separate from scheduling so its body-read reduction is independently measurable.
Change `CatalogueScheduleDispatcher.obtainPair` / `usableOwn` to reuse KAR-71's bounded metadata
selection and guarded `hydrateStagedPayload`, rather than `latestStagedPublicPayload` before filtering.

1. Use `latestStagedPayloadMetadata(lease, endpoint)` for both same-run checkpoints, then filter:
   the canonical path currently selects the **latest** row and rejects it when unusable. Do not
   copy the fixture path's earliest-valid selection or revive an older valid row beneath a newer
   invalid one. Reject known invalid, wrong-day, wrong-endpoint/run/epoch or below-floor candidates
   before issuing a body SELECT.
2. Fetch/stage any missing public endpoint **before** hydrating an existing partner. If admission
   defers, return with its true hint and no body read for the incomplete candidate pair. Preserve
   each successfully staged response for restart. Do not refetch a still-valid partner needlessly.
3. Before hydrating an available pair, including after fetching its missing partner, reject any
   known pair-window or endpoint-specific receipt regression from read-interval metadata. Preserve
   `ChronoUnit.MINUTES.between(start, end) <= 10` exactly, including its floor-to-whole-minutes
   boundary; do not silently replace it with a strict 600-second cutoff. An old but internally
   coherent same-UK-day pre-cutover pair remains reusable. Recheck dates if the wait crossed UK
   midnight. Keep metadata-only rejection's validation/retry outcome consistent with the old path.
4. Recheck exact ids, endpoint, validation, season, lease/epoch and the effective durable source
   floor during hydration. If selection became stale or a payload was pruned/invalidated, use
   the bounded refresh/retry route. Preserve own-pair parse failure as terminal; malformed reusable
   canonical pairs may try another candidate. Do not import the fixture dispatcher's refetch
   fallback into the own-pair parser. A rejected candidate must not become a publication.
5. Keep complete canonical-pair reuse and its bounded cache intact. Strict parse and
   publication-time chronology/source-floor/receipt checks remain final authorities, including
   races. This ticket makes own-checkpoint admission metadata-first; it does not rewrite the
   reusable-pair API/cache or introduce in-memory checkpoint ownership.

Focused regressions in `CatalogueScheduleDispatcherIntegrationTest` and relevant
`OwnedSyncStoreIntegrationTest` cases must prove zero checkpoint-body SELECTs/returned bodies for
metadata-ineligible candidates, already-known pair-window/receipt regressions and a partial pair
whose missing partner is admission-deferred. Pre-rejected candidates issue no body SELECT; a
hydration race may issue a guarded SELECT but must return no rejected body. Once its partner is
fetched, exactly the needed eligible checkpoint body is hydrated. Count canonical-pair reuse
separately rather than setting an impossible zero-total-read assertion.

Retain restart/pre-cutover publication, previous-day rejection, source-window rejection,
malformed-pair fallback, demand advancement between selection and hydration, receipt race,
validation invalidation, pruning, lease/epoch loss and SQL rollback cases. Add newer-invalid over
older-valid checkpoints, own-pair versus reusable-pair parse failure, UK-midnight boundaries and
an old-but-coherent pre-cutover pair explicitly. If earlier metadata
rejection intentionally avoids a now-unnecessary fetch, update only that test's fetch count and
explain why its safety outcome remains identical. Measure returned raw body lengths in synthetic
tests; do not call them TCP or provider-billed bytes.

Run the focused dispatcher/store tests, then independent review and full PR CI on its separate
branch. No code change to fixture readiness, historical readers or core writer activation.

## Combined acceptance and operational handoff

After both reviewed changes, rerun the same seeded admission workload and report old/new values
for first history completion, distinct histories completed, fixture progress, allowed/denied HTTP,
new partial pairs and each body-read family. Greater progress naturally reads more necessary
bodies: zero body reads is not the goal. Do not claim these two changes explain or eliminate all
15 MB from the retest. If a fixed healthy case still has unbounded dependency replay/body growth,
stop and investigate locally; do not enlarge the production test to paper over it.

Local commands use `backend/.env.test`, the disposable container and checked-in resource limits;
never load `.env.supabase` for tests. Run a single bounded class/group at a time, no full suite on
this VPS. GitHub owns the full-suite gate. Planning itself needs only document/link/diff review.

A later production experiment is separately authorized and attended. Before it, verify the actual
image contains both fixes, fresh OFF/drain/health, the current provider-dashboard allowance and
unchanged single-writer ownership. Rehearse the observer and independent stop supervisor on the
actual image. Define time/traffic/memory/health/telemetry stop conditions before activation; budget
polling, SQL and shutdown latency so the observed trigger is not falsely called a hard cap.
Keep diagnostic counters running through stop as far as possible and disclose missing tails.
Preserve postflight OFF epoch/drain/health and published receipt/evidence baselines. No observer
reads payload bodies, captures SQL parameters, resets shared statistics or issues pooler SETs.

A short STAGING sample can prove bounded traffic/progress only for what it actually exercises.
It does not publish GW5, invoke ACTIVE's fixture verification/publication path or establish
monthly cost. Any ACTIVE backfill needs separate permission after reviewed evidence, retains both
independent batches and correction checks, and must verify all ten GW5 fixtures and visible PFS
rows. Sustained operation additionally needs backlog, idle, daily-sweep and matchday measurements;
no unavailable provider API is substituted for those measurements or made a prerequisite here.

## Review/outcome record

Independent review identified five gaps: the existing timeout is not end-to-end; fixture
selection/drain lacked bounds; the reproduction lacked the runtime cadence and a wall-time stop;
pair-level metadata rejection was underspecified; and canonical latest-row/parse-fallback semantics
could be confused with the fixture dispatcher. All five are addressed above. A second independent
review found no remaining blocker to the bounded local reproduction.

The two-second FIFO wait and worker preferences remain candidate designs, not approved runtime
behavior. Unit 1's reproducer must establish the failure, then its policy/deadline checkpoint
selects and reviews a feasible mechanism before production scheduling code changes. No test was
run against Supabase, no runtime implementation was made, and no production setting changed during
planning/review. KAR-70 is not a dependency; operational acceptance remains a separate permission.
