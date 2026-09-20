# Owned sync egress — attended production test, 2026-09-20

**PROGRESS:** Investigation and first bounded production test complete; free-tier acceptance failed. Production is OFF epoch 63. No new runtime fix is implemented in this report.

## Release and safe final state

PR #116's canonical body cache was already merged and deployed. The tested image carried
`e123272` (GitHub deploy run `35498621880`, successful). The owner requested an attended restart
and egress investigation, not an upgrade to Supabase Pro.

Only `OWNED_SYNC_MODE` was changed, using normal Fly secret updates. ACTIVE epoch 62 began at
08:16:28 UTC; normal OFF epoch 63 was recorded at 08:23:39 UTC. The application was UP afterward.
No code was manually deployed, no session SET was issued, no receipts were erased, and Tapas core
writes were not re-enabled. Kuma 18 remains paused. There is currently **no core writer**; this is
containment, not completion of the owner's request to restore sync.

## Measurements

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

## Remaining causes

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

## Required next bounded units

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
