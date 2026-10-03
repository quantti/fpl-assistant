# Owned sync egress — bounded production observations

**PROGRESS:** Two attended 2026-10-03 STAGING observations stopped at their DB-RX triggers. The latest completed 84 histories but no fixture STAGED; OFF epoch 77 and unchanged publication verified. Sustained egress and GW5 restoration remain unverified.

## 2026-10-03 — second, longer full-queue observation: no fixture STAGED

The owner approved **one further run** with the existing whole-queue scheduling: stop at 20 minutes from the request, 50,000,000 observed DB-RX bytes or 65,000,000 machine-RX bytes, retaining the 192 MiB guest-available-memory and 400,000,000-byte physical DB-size stops. The owner-provided post-first-run Supabase dashboard showed 0.115 GB egress and 0.136 GB database size; billing may lag. The owner explicitly accepted the residual risk that both the temporary supervisor and Fly-console rollback need Fly's API: these are observed stop triggers, not API-independent hard limits. An offline review and mocked guard/OFF/failure-path tests passed; the socket meter passed its 524,288-byte test on the unchanged deployed image. No app deployment, Tapas writer change, ACTIVE or core publication was requested.

SELECT-only preflight read OFF epoch 75, zero active runs/unresolved attempts, no cooldown, 120,966,291 physical DB bytes and unchanged publication/core/GW5 baselines. The independent guard was armed before the 11:54:53 UTC STAGING request. The new boot appeared at 11:55:03 and the first history completed at 11:56:05. At **12:00:56 UTC**, roughly 363 seconds from the request, the DB-RX stop fired: **53,187,034 observed DB TCP payload bytes** (3,187,034 beyond the stop trigger) and **59,936,871 whole-machine RX bytes**. The lowest sampled guest `MemAvailable` was **232,612 KiB**, above 192 MiB. The last STAGING shutdown sample showed 53,187,034 DB and 59,949,294 machine bytes; the tail and short-lived sockets may be missed. None of these numbers is Supabase billed egress. OFF epoch 77 fenced the queue at 12:01:01; the guard and postflight verified OFF, public health UP and zero active runs/unresolved attempts by 12:01:52. A second SELECT-only check at 12:05 confirmed OFF epoch 77.

Epoch 76 had **137 durable admissions**, two completed catalogue/schedule runs, **84 completed VALID histories** and **zero fixtures STAGED**. The last in-run progress query saw 79 histories; five more completed before the OFF fence. Fifteen fixture dependency manifests were persisted. In the best source-floor overlap, fixture 11 had only **37 of 71** required histories; fixture 10 had 33 of 68 at its floor (48 of 68 without that floor). Thus more completed histories did not establish a ready fixture. OFF fenced 50 fixture and 605 history runs as FAILED; these are shutdown markers, not independent source errors. All 137 new payloads were VALID (19 bootstrap, 18 fixtures, 16 live, 84 histories). No persisted 429, uncertain run, partial pair or unresolved permit remained.

Physical DB size ended at 124,767,379 bytes, below the stop; selected statement counters with an unchanged reset instant added 2 canonical body-pair calls/returned pairs, no own-checkpoint body calls and 26 single-payload-body calls/returned rows. The **706 published receipts and their fingerprint, 6,523 evidence rows, 40 VERIFIED fixtures, core counts and GW5's zero physical PFS rows/ten VERIFYING receipts were unchanged**. This was a failed fixture-progress gate, not evidence of sustainable egress, a monthly billing rate or GW5 readiness. No second run follows from this approval. Temporary evidence: `/tmp/fpl-staging-20261003-second/`.

## 2026-10-03 — bounded KAR-72/73/75 retest: histories, no fixture

The owner approved one attended run under the [12-minute / 20 MB DB / 26 MB machine
plan](superpowers/plans/2026-10-03-owned-sync-bounded-staging-retest.md), accepted the
20 MB database and 26 MB machine stop triggers including sampling/shutdown overshoot,
confirmed Fly-console fallback and that Tapas core writes remained disabled. The owner-provided
Supabase screenshot showed the 25 September–25 October Free billing period at 0.093 GB egress
and 0.132 GB database size before the run; the owner confirmed that this was the entire relevant
DB usage. The dashboard can lag and was not rechecked after the run. The already deployed single
Fly machine was 1 GiB, reporting image `deployment-01M3NVRHYDC320RHHQK3QTA767`; master
[`5af29cf` CI/deploy and health smoke](https://github.com/quantti/fpl-assistant/actions/runs/36527740100)
passed on 29 September. No application image, Tapas settings, monitoring route or database
privileges changed for this observation.

The temporary independent rollback supervisor was armed before the STAGING request. Its
mocked OFF/failure and deadline dry-run passed without a mode change; the read-only socket meter
measured exactly 524,288 bytes on a held loopback connection with stable cookie and port
filter **on the deployed image**. A SELECT-only preflight showed OFF epoch 73, zero active
runs/unresolved HTTP attempts, 116,518,035 physical database bytes, 706 published receipts,
6,523 evidence rows, 40 VERIFIED fixtures and zero GW5 physical PFS rows. The guard watched
192 MiB available-memory and 400,000,000-byte physical DB-size triggers as well as time,
traffic, health, telemetry, unsettled permits and progress.

| UTC on 3 October | Event |
|---|---|
| 09:03:27 | Guard armed; STAGING requested |
| 09:03:38 | New STAGING boot observed (epoch 74) |
| 09:04:41 | First completed VALID player history |
| 09:07:35 | DB-RX trigger requested OFF, about 248 seconds after mode request |
| 09:07:40 | Durable OFF epoch 75 fenced the remaining queue |
| 09:08:22–09:08:26 | OFF machine environment/public health and durable OFF/drain/publication verified |

At the stop sample the meter read **20,619,257 bytes observed PostgreSQL TCP payload RX** and
**25,041,673 bytes whole-machine RX**. The 20,000,000-byte DB trigger overshot by 619,257 bytes;
the 26,000,000-byte machine and 12-minute triggers did not fire. The last shutdown sample of the
STAGING boot showed 25,052,035 machine RX bytes; the DB meter still showed 20,619,257 and no
observed socket closures. The lowest sampled available memory was 222,280 KiB (about 217 MiB),
above the 192 MiB stop; physical DB size went from 116,518,035 to 120,966,291 bytes, below the
400 MB stop. Socket polling can miss connections that open and close between samples, the tail
after the last sample is not captured, and neither counter is exact Supabase billed egress.

Epoch 74 had **85 durable admissions**, two completed catalogue/schedule runs and **48 completed
player-history runs** (45 at the last in-run progress poll; three completed before OFF fenced
work). No fixture run completed STAGING's `completeStaged` path: **zero fixtures STAGED**. OFF
fenced 50 fixture and 640 history runs as FAILED; these are shutdown markers, not independent
source errors. All 85 staged responses were VALID: 13 bootstrap, 13 fixtures, 11 live and 48
player-history payloads. Zero unfinished attempts, persisted 429s or uncertain runs remained.
The normal retention sweep deleted 119 earlier payload rows, leaving 85; the physical DB-size
delta cannot be equated with staged response bytes. The first postflight payload/fixture summary
accidentally queried epoch 72, so those two empty outputs were discarded and the endpoint counts
above were obtained from a corrected **SELECT-only** epoch-74 postflight query. The completion
count and stop decision used the correct epoch throughout.

`pg_stat_statements` had an unchanged reset instant. Between the before/after snapshots,
canonical body-pair reads added **2 calls/2 returned pairs**, own-checkpoint body reads **0/0**,
and single-payload body reads **6 calls/6 returned rows**. Payload inserts added 85 rows and
run-metadata reads added 2,365 calls/1,512 returned rows; observer queries may contribute.
These counters do not identify which body ids crossed the wire or allocate the measured RX to
SQL families. The preceding locally recovered 28 September epoch-72 run stopped at 20.56 MB
machine RX with 43 completed histories and zero fixtures STAGED. This test shows earlier history
progress under its limits, **not fixture progress, sustained egress, an idle/matchday rate, a
monthly billing estimate or a GW5 backfill**.

Final SELECTs and the independent guard confirmed OFF epoch 75, zero PENDING/LEASED runs and
unresolved attempts, public health UP, identical publication-receipt fingerprint (706), 6,523
player-evidence rows, 40 VERIFIED fixtures, unchanged core row counts and zero GW5 physical PFS
rows with ten VERIFYING fixture receipts. STAGING published no core rows. Do not enlarge the
limits or enter ACTIVE to manufacture a fixture: diagnose the remaining fixture/history replay
and retained full-body transfers locally first, then seek separate approval for any further
production observation.

## 2026-09-27 — KAR-71 STAGING retest stopped at the DB threshold

The owner authorized an attended retest with a ten-minute deadline from the mode request, a
15,000,000-byte observed PostgreSQL TCP RX trigger and a 20,000,000-byte whole-machine RX trigger.
The already deployed single Fly machine ran `80dba18` (successful master CI/deploy), which
contains KAR-71's metadata-first history/fixture payload selection. The preceding observation ran
on `bc5ced7`, before KAR-71. No application image, Tapas configuration or database role changed.
The owner-provided preflight dashboard showed 0.024 / 5 GB organisation egress and 0.166 / 0.5 GB
database size; its billing data can lag. The database preflight read OFF epoch 65, zero active
runs, 706 published receipts, 6,523 evidence rows and 40 VERIFIED fixtures.

The independent rollback supervisor was armed **before** requesting STAGING. The temporary
read-only Linux socket observer passed its 524,288-byte loopback/cookie/port-filter self-test on
the deployed Fly image. It sampled DB socket counters once a second; the controller checked the
latest complete sample and machine traffic approximately every two seconds. The owner remained
available for Fly-console fallback. The guest-memory floor was 192 MiB, with a 90-second startup
health grace and fail-closed checks for missing/stale telemetry, changed boot and runtime errors.
Both traffic thresholds were stop *triggers*, not provider-enforced hard quotas.

| Event (UTC, 27 September) | Result |
|---|---|
| Preflight OFF epoch 65 | 03:43:05; zero active runs |
| Guard armed; STAGING requested | 03:43:06 |
| New STAGING boot observed | 03:43:18 |
| DB traffic stop requested | 03:44:13, about 66 seconds after request |
| Durable OFF epoch 67 and queue fenced | 03:44:17 |
| OFF environment and public health verified | 03:44:56 |
| Durable OFF, drained queue and publication baseline verified | 03:45:00 |

At the stop request the observer reported **15,211,025 bytes DB TCP RX** and **17,194,620 bytes
whole-machine RX**. The last sampled STAGING socket state before replacement was 15,222,242
DB bytes and 17,205,471 machine bytes, with zero observed DB socket closures and a lowest sampled
guest available memory of 441,936 KiB (about 432 MiB). These counters include startup but not every
byte during shutdown; DB TCP payload RX includes TLS/PostgreSQL framing, not TCP/IP headers, and can
miss connections born and closed
between samples. Neither number is exact Supabase billing or an organisation-wide rate. The machine
counter includes FPL traffic and diagnostics. The provider dashboard has not yet reported the
post-test billing amount.

In this epoch **two catalogue/schedule runs completed**, 50 fixture runs were fenced FAILED by
normal OFF (13 total fixture attempts), and 68 history runs were fenced FAILED with **zero history
attempts**. No fixture job reached STAGED and no history completed. These shutdown failures are
not independent source errors. Sixteen payload rows and sixteen request attempts were added.
`pg_stat_statements`' reset instant did not change. Its selected deltas from before the request
to after rollback were: ten legacy-shaped own-checkpoint body calls returning three rows, one
canonical-pair body call returning one pair, zero new single-payload-body returns, and 16 payload
inserts. The categories describe SQL shapes; they do not identify which body ids crossed the
wire, and source response size is not billed DB egress. No fan-out history body return was
observed, but no history job was attempted either, so this run cannot establish whether KAR-71
reduced that path's transfer under load. It did not produce history progress before the DB stop.
It cannot be declared a sustainable egress fix from this test.

SELECT-only follow-up measured epoch 66's payload lengths on the server without returning bodies: ten
`UNCHECKED` fixture-run bootstrap responses of 1,778,630 raw bytes each. One other fixture
bootstrap, a fixture-list response and an event-live response were validated; the two completed
catalogue/schedule runs staged two bootstraps and one fixture-list response. These 16 rows totalled
24,021,780 bytes of raw source bodies, which are database *ingress* on staging, not the observed DB RX.
The store's acquisition order is priority then due time: one fixture reached dependency-waiting
priority 4 and queued 68 priority-3 histories, while 49 other fixtures remained priority 1.
Forty-nine fixture runs had twelve attempts between them (37 never attempted), and none of the
68 histories was attempted before rollback. `HistoryFixtureDispatcher.obtainOwnPair` fetches and
stages each fixture run's own bootstrap before fetching its fixture list; HTTP minimum-spacing
can defer that second request, so the next priority-1 fixture starts another bootstrap. This is
a concrete admission/scheduling path for both repeated staging and delayed history progress in
the short window, not proof of indefinite starvation or a full-month transfer rate.

The remaining old-shaped body SELECT is reachable from `CatalogueScheduleDispatcher.usableOwn`,
which loads a same-run payload body before testing its UK date and source-read floor; KAR-71
changed the analogous history/fixture selection, not this canonical writer. That and the one
fully hydrated canonical pair are consistent with the measured DB RX, but statement counters
cannot identify the returned body ids or assign exact bytes to each query. A focused follow-up
should preserve strict source and runtime fences while avoiding ineligible body reads and
ensuring queued histories can make bounded progress ahead of new incomplete fixture pairs.

Postflight SELECTs confirmed OFF epoch 67, zero PENDING/LEASED runs, public health UP, the same
published-receipt fingerprint and 706 rows, 6,523 evidence rows and 40 VERIFIED fixtures. GW5
has zero physical PFS rows and ten `VERIFYING` fixture receipts. Tapas core writes stayed disabled,
STAGING published no core rows, and no ACTIVE mode was requested. The guest diagnostic directory
did not survive the OFF replacement; private raw logs are held in a mode-0700 local temporary
directory and are not a persistent monitoring service. Do not use this brief cold-start interval
as a monthly forecast, repeat it without a reviewed cause/stop budget, or move to ACTIVE to
manufacture progress. Next: test a narrowly scoped canonical checkpoint-read correction and a
bounded admission/fairness fix against a seeded 50-fixture/history backlog before another attended
observation.

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
