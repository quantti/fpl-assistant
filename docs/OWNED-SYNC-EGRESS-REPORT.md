# Owned sync egress — bounded production observations

**PROGRESS:** The 2026-09-26 STAGING observation reached its conservative 20 MB traffic stop threshold before completing the backlog. Production is verified OFF epoch 65. Sustainable egress remains unverified; this report implements no runtime fix.

## 2026-09-26 — STAGING stopped at the traffic threshold

The owner authorized STAGING for at most ten minutes with a 20 MB stop threshold, followed by OFF.
The single Fly machine ran the already deployed `bc5ced7` image, including the canonical cache and
all three 20 September follow-ups: redundant parent refresh removal, scoped watchdog evidence and
one-minute planner cadence. Only `OWNED_SYNC_MODE` changed in the application configuration; no
application image was deployed, no ACTIVE mode was requested and no Tapas configuration or database
role was changed.

A separate local rollback supervisor was armed **before** requesting STAGING. It requested OFF on
an explicit stop, controller exit or the ten-minute deadline. Guest available memory below 192 MiB,
stale/missing measurements, a runtime error or loss of health after startup also stopped the test.
As an extra conservative guard, **all received machine traffic**, including compressed FPL HTTP
responses and diagnostic traffic, could trigger the same 20,000,000-byte threshold. This is an
observed stop threshold, not a hard provider-side quota: polling and shutdown can overshoot it.
The supervisor depended on the observing host and Fly API remaining available; it is not a
persistent production egress circuit breaker.

### Timeline and safe final state

All instants below are UTC on 26 September:

| Event | Time |
|---|---|
| SELECT-only preflight: OFF epoch 63, zero active runs | 15:07:45.571 |
| Rollback guard armed; STAGING requested | 15:07:47.481 |
| Durable STAGING epoch 64 began | 15:08:28.220 |
| Traffic stop requested at 20,805,452 received bytes | 15:09:32.114 |
| Durable OFF epoch 65; pending work fenced | 15:09:37.274 |
| OFF environment and public health UP verified | 15:10:22.131 |
| Durable OFF, zero active runs and publication baseline verified | 15:10:25.269 |

Thus the threshold was reached about **105 seconds after the request**, including startup, and
64 seconds after the durable STAGING activation. This was not a completed ten-minute test.
The final publication-receipt fingerprint, 706 published receipts, 6,523 player-evidence rows and
40 VERIFIED fixtures matched the preflight. The receipt fingerprint covers source intervals and
publication instants; the evidence check is a row count, not a claim to have hashed every core row.
STAGING did not publish core data. Temporary remote diagnostic files were removed after rollback.

### Database-specific measurements

A temporary, read-only Linux `SOCK_DIAG` observer sampled `TCP_INFO.bytes_received` once per second
for connections to PostgreSQL ports 5432/6543. It did not capture packet contents or alter the
application's connections. Its local loopback check measured exactly 524,288 bytes sent over a held
connection and verified socket identity and port filtering. A separate check verified selection of the latest
complete sample; rollback-policy checks covered explicit stop, controller exit and deadline.
No application test suite was run against production.

| Last observed value on the STAGING boot | Value |
|---|---:|
| PostgreSQL TCP payload received, including TLS/protocol overhead | **15,159,032 bytes (15.16 MB)** |
| Whole-machine received traffic | **20,806,858 bytes (20.81 MB)** |
| Observed DB socket closures during sampling | 0 |
| Lowest sampled guest available memory | 439,132 KiB |

These counters include startup. The final socket sample was at 15:09:32.062, before the durable
OFF fence, so 15.16 MB is an observed amount, **not the exact total through shutdown or a Supabase
billing measurement**. A connection born and closed entirely between samples could be missed.
The difference between whole-machine and DB counters is not a precise FPL-byte total: their scope,
protocol overhead and sample instants differ. The owner-provided dashboard screenshot showed the
new 25 September–25 October period with a 5 GB allowance and 0.00 GB rounded usage before this
experiment; its post-test hourly billing update has not been inspected.

SELECT-only `pg_stat_statements` snapshots bracketed the observation and shutdown without resetting
statistics. The reset timestamp was unchanged. Selected deltas:

| Query/result | Calls | Returned/affected rows |
|---|---:|---:|
| Own checkpoint body reads | **222** | **4** |
| Canonical bootstrap/fixtures body-pair reads | 0 | 0 |
| Cross-run valid history body reads | 0 | 0 |
| Run metadata reads | 591 | 587 |
| Payload inserts | 33 | 33 |
| Normal payload-retention delete | 1 | 2,767 |

Metadata totals can include the diagnostic SELECTs; body rows were never fetched by the observer.
The checkpoint query family is still outside the canonical-pair cache. Every staged bootstrap in
this run was 1,778,826 raw bytes; pgjdbc's text `bytea` representation can approximately double
body transfer size. The four returned body rows therefore identify a substantial remaining path,
although query counters do not identify which payload ids were returned or prove that the same id
was read repeatedly. Do not attribute every measured byte to those four rows alone.

### Work and retention outcome

Epoch 64 completed **two catalogue/schedule jobs**, with zero completed player histories or fixture
verifications. Its 50 fixture jobs were fenced FAILED by normal OFF; these are shutdown markers,
not 50 independent source failures. Across all jobs, 33 source responses were staged: 30 bootstrap
and three fixtures responses, totalling 54,060,060 raw bytes. Fixture jobs accounted for 28 of those
bootstrap responses and only one fixtures response. These staged bytes are storage/ingress, not
measured database egress. No event-live response was staged in the bounded window.

The normal startup retention job successfully removed the 2,767 pre-existing, older-than-24-hour
payloads. Postflight found 33 payloads and none older than 24 hours; database size was 150,031,507
bytes versus 275,213,459 at preflight. This verifies retention for this execution, not a general
storage-budget guarantee. No manual payload deletion or receipt/evidence erasure was performed.

### Conclusion and next boundary

**Do not restore sustained sync yet.** The later fixes were present, but this cold-start observation
hit its conservative traffic threshold before verifying a fixture or completing a history. It
shows remaining checkpoint-body traffic, not a steady-state monthly cost estimate. It neither
proves that the Free allowance will suffice nor that steady state necessarily exceeds it. The
five-minute watchdog tick was not reached, so this run does not verify its traffic reduction.

The next bounded implementation remains availability-first checkpoint/dependency checks and
avoiding unnecessary body hydration, preserving every season, epoch, source-floor and validation
fence. Then repeat a budgeted observation covering actual history/verification progress, idle,
daily sweep and matchday traffic. Do not weaken independent verification to make a short test pass.
The historical list below predates the deployed parent/watchdog/cadence fixes; it is not a claim
that those three changes are still unimplemented. Retention succeeded in this later execution.

Private raw snapshots and the temporary diagnostic sources for this observation are on the VPS at
`/tmp/fpl-egress-20260926.oG4i9Z`; that temporary directory is not a durable acceptance-test harness.
This report records the selected aggregate evidence, not credentials or shared-database query text.

## 2026-09-20 — ACTIVE observation

### Release and safe final state

PR #116's canonical body cache was already merged and deployed. The tested image carried
`e123272` (GitHub deploy run `35498621880`, successful). The owner requested an attended restart
and egress investigation, not an upgrade to Supabase Pro.

Only `OWNED_SYNC_MODE` was changed, using normal Fly secret updates. ACTIVE epoch 62 began at
08:16:28 UTC; normal OFF epoch 63 was recorded at 08:23:39 UTC. The application was UP afterward.
No code was manually deployed, no session SET was issued, no receipts were erased, and Tapas core
writes were not re-enabled. Kuma 18 remains paused. There is currently **no core writer**; this is
containment, not completion of the owner's request to restore sync.

### Measurements

SELECT-only `pg_stat_statements` snapshots were taken without resetting shared statistics.
The comparable ACTIVE interval was 08:16:58.664–08:23:22.800 UTC (384.1 seconds).
Queries were classified locally; raw shared-database query text is not committed.

| Query/result | Delta during comparable interval |
|---|---:|
| Canonical bootstrap/fixtures body-pair query | 15 calls / 15 pairs |
| Earliest valid own checkpoint body query | 165 calls / 28 returned bodies |
| Latest own checkpoint body query | 104 calls / 17 returned bodies |
| Another own-checkpoint body query | 165 calls / 0 returned bodies |
| Cross-run latest valid history body query | 893 calls / 0 returned bodies |
| Full-season fixture progress | 14 calls / 5,320 rows |

Canonical pair traffic fell from the earlier 56 calls per 60.6 seconds to about 2.34 calls/minute.
The cache fixes repeated reads of a stable pair, but new pairs still cross the connection.
The sampled new pair was 1,777,295 bootstrap bytes plus 223,028 fixture bytes: the 15 pair reads
alone imply about **30 MB raw body content**, before checkpoint bodies, metadata and protocol
representation. This is not an exact Supabase billing measurement.

The machine's total received-byte counter reached 254,792,745 at 08:23:25, with 278,648 KiB
available memory. That counter includes FPL HTTP responses and other network traffic: **do not
attribute it all to Supabase egress**. No OOM or application outage was observed.

At shutdown, epoch 62 had completed **33 catalogue/schedule jobs, zero player histories and zero
fixture-verification jobs**. Its remaining one catalogue job, 667 histories and 46 fixture jobs
were normally fenced FAILED by OFF; those shutdown failures are not 714 independent source errors.
The previously verified 40 fixtures remained verified during the test.

The activation staged 79 bootstrap responses (140,407,005 raw bytes), 42 fixtures responses
(9,367,176 bytes) and 15 live responses (6,771,142 bytes). These are stored source bytes, **not
measured database egress**. Uploading source payloads is database ingress; later reading them is
what consumes database egress.

The initial payload-retention delete reported an I/O error. Planner heartbeats continued advancing,
but retention success was not established by this short test. Recheck it before a long activation.

### Remaining causes

1. `HistoryFixtureDispatcher.processFixture` calls `queueParentRefresh` whenever its initial
   public pair was fetched. That queues both priority-0 parent jobs with a new source floor.
   The parent fetch cannot simply reuse the fixture run's pair: canonical lookup deliberately
   accepts catalogue/schedule runs only. Repeated fixture work therefore generates new pairs,
   defeating deduplication by immutable payload id. Normal planner cadence alone does not explain
   33 parent jobs in seven minutes.
2. Fixture work takes precedence over histories until it reaches a dependency wait. HTTP admission
   deferrals can occur earlier. This activation made no history progress; parent amplification and
   scheduling fairness must be tested together, not inferred fixed from a warm cache test.
3. Fixture retries hydrate available checkpoint/history bodies before discovering another missing
   dependency. Those reads do not use the canonical cache. Availability-first metadata checks can
   avoid partial fan-out downloads, but hydration must repeat every existing evidence guard.
4. The planner still returns all 380 progress rows and candidate metadata on every tick. The fixed
   delay is five seconds after a tick finishes; the actual busy-test rate was slower (14 ticks in
   384 seconds). An idle five-second loop would return about 197 million progress rows per 30 days.
   Neither that idle projection nor byte estimates are substitutes for measured wire traffic.
5. The watchdog returned 39,165 settled run ids and 7,677 incident-key rows in one sampled pass.
   This is another recurring transfer to review; the body cache does not cover it.

### Required next bounded units

Keep the units sequential and require focused regression tests plus PR CI and review before release.

1. Remove redundant fetched-only parent refreshes when the canonical parent has equivalent required
   content, while preserving missing-parent, contradiction, target-change and database-mismatch
   refreshes. Reproduce the 46-fixture/667-history restart and prove histories make progress despite
   HTTP admission deferrals. Do not weaken source floors or independent verification batches.
2. Avoid downloading partial dependency sets and stale/rejected checkpoints. Recheck exact season,
   endpoint, validation, source floor and runtime epoch when hydrating. Bound any extra cache by
   bytes and test invalidation, pruning and concurrent readers.
3. Budget recurring planner/watchdog metadata. A longer planner delay trades scheduling latency for
   cost and needs an explicit decision; alternatively avoid transferring unchanged full-season
   state. Filter watchdog recovery evidence to relevant outstanding incidents rather than returning
   all settled historical runs. Preserve heartbeat/watchdog semantics.
4. Add a repeatable bounded egress acceptance test and a persistent rate/budget alarm. The existing
   sync health endpoint is not an egress alarm. Measure database-specific received bytes, not just
   whole-machine RX; correlate with body-query and metadata-query deltas. Define an attended-test
   stop threshold before reactivation, verify normal OFF/drain if crossed, then cover idle, backlog,
   daily sweep and a real matchday before declaring the budget safe.

The reported 5.5 GB monthly allowance averages about 183 MB/day **for all included consumers**.
Tapas and chat traffic share the database and need headroom. A successful short cache test cannot
promise this allowance will hold, and reducing future traffic cannot undo the 88.78 GB already
reported or cancel the provider's 2026-09-21 Fair Use deadline. Check the provider dashboard again
for the current billing window, usage and grace status.
