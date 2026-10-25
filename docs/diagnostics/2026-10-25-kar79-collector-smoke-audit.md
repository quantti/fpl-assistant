# KAR-79: full-roster collector smoke audit

**PROGRESS:** Bounded audit and approved case-local pooling complete locally; unchanged test passes before/after (15.415s → 8.074s); publication remains separate.

## Identity and authority

Audit date: 2026-10-25. Fetched `origin/master`: `f0a04c470b852b342bc311341b690cab188f921c`.
Worktree: `/home/vanka/dev/fpl-assistant-test-audit-next`;
branch: `docs/kar79-collector-smoke-audit`.
Read KAR-79 through authenticated Linear GraphQL: Backlog, last updated 2026-10-19.
The owner's instruction to continue authorized this next audit, not inherited approval of
further test deletion, scale loss, remote publication or merge. The subsequent “vaihda” approved
F7's method-local pool; the owner explicitly selected Ubuntu Docker rather than Docker Desktop.

Unit: `CoalescedPlayerHistoryCollectorIntegrationTest.coalescesRecordedLeaguePagesAndDoesNotRefetchCompletedPostFinishWork`
and its immediate helpers/production path. Test file SHA-256 at the baseline:
`66fb8e5c4b6bdc2ff6d858f698c06317d860b427cb22acb705c38e6e91964e6e`.
No other test family is claimed audited. Prior work, research and approval boundaries remain in
[the first-pass report](2026-10-18-test-audit-first-pass.md) and
[the plan](../superpowers/plans/2026-10-18-behavior-first-test-audit.md).

## Contract and real boundary

The test drives two collector cycles against recorded source bodies and a real disposable
Postgres store/publisher. The first must merge catalogue players, fixture participants and an
existing PFS key into 660 unique selections and successful publications, coalesce four live
requests, insert two physical PFS rows, and reject a nested collector invocation as BUSY.
The immediately repeated cycle must fetch only bootstrap/fixtures, with no player/live requests.

`RecordedFetch` replaces HTTP, advances a mutable clock and returns empty history for every
player except the two explicitly configured pages. Thus this is full-catalogue discovery/page-
receipt smoke evidence, **not** 660 populated match histories, real network/budget integration,
parallel-worker contention or full production compatibility-catalogue publication. The collector
factory uses the constructor without `CompatibilityCatalogueWriter`.

Assertions inspect independently observable request lists, cycle outputs and PostgreSQL row
triggers. They do not reconstruct the production selection algorithm. Some are partial oracles:
660 committed IDs is not exact committed-set equality, and the printed fixture count is not an
assertion. Do not describe a printed metric as a guaranteed performance bound.

## Eight candidate decisions

| Candidate | Contract / plausible rejected defect | Boundary and oracle / retained replacement | Cost evidence and decision |
|---|---|---|---|
| F1: delete or shrink the 657-player catalogue | A full-league union must not truncate or fail when processing hundreds of page receipts. A compact test can miss a roster-dependent cap or dropped tail. | Real store/publication, fake HTTP. Exact selected/fetched cardinality and uniqueness are checked. No smaller test was established equivalent for this scale. | 660 successful page publications in this fixture, not a fresh timing. **Keep full roster.** Prior E1 scale-loss approval applies only to that correction case. |
| F2: remove nested BUSY check | A second invocation must not acquire the in-flight durable run. Removing/excessively relaxing acquisition ownership could permit it. | Callback enters the same collector during its first request, after outer acquisition. `compatibilityRetryReusesPublishedPairAndCorrectionUsesANewGeneration` also checks BUSY, but on a different constructor/publication path. Neither is proof of simultaneous-thread safety. | Two nested store operations add work, but no measured cost. **Keep**; no equivalent replacement established for this non-compatibility path. |
| F3: collapse selection/fetch uniqueness assertions | Selection and actual requests must each be unique. A request loop can duplicate HTTP work even when selection is unique; selection itself can also duplicate IDs. | Separate result and fake-fetch observations. Equal lengths alone do not exclude duplication. The compact prioritization case does not replace full-roster actual-request uniqueness. | Small Java set work, no measured material cost. **Keep** both boundaries. |
| F4: remove the second idle cycle | Persisted post-finish receipts must prevent refetching across the whole selected roster. A tail receipt missing from the first cycle can surface only on the second. | Real durable state plus request-count oracle. `idleUncheckedCycleAvoidsPfsPopulationHistoryAndStagedBodyReads` checks a compact single-fixture SQL no-read path, not the full-roster receipt population. | Additional canonical reads and store work; no fresh duration. **Keep**; compact idle test is complementary, not proven equivalent. |
| F5: remove trigger-based two-insert assertion | Real PFS insertion must still occur, rather than reporting successful empty publications or writing only receipts. | Real AFTER-row triggers count physical inserts independently of `committedPlayerIds`. No equivalent same-scenario physical-write oracle established. This asserts insertion count, not all metric values. | Triggers observe ten tables; actual overhead unmeasured. **Keep** assertion and instrumentation pending a separate justified replacement. |
| F6: remove SQL recorder/diagnostic print | Statement/row/byte/wall-time totals are diagnostic, not asserted budgets. Their removal would not remove a currently asserted SQL ceiling in this method. | `SqlRecordingDataSource` wraps real JDBC. Other callers use its assertions/failure injection; shared helper deletion is not justified. | Per-JDBC-call proxy/row bookkeeping is present, cost unknown. **Optional trim only**, not the preferred first change: it loses diagnostic evidence, requires owner acceptance, and promises no measured gain. |
| F7: case-local connection pool | Preserve all 660 page transactions, independent commits, run/season fences and nested BUSY behavior, but reuse JDBC connections between them. | `initialize` uses `DriverManagerDataSource`; `useRecordingJdbc` delegates to it. `publishPlayerHistoryPage` starts a transaction for each successful page. Earlier C2/D1 use an installed Hikari pool locally, not a global test-profile change. | Source-derived lower bound: **660 transaction connection acquisitions** for first-cycle page publication alone; not an executed counter or timing. **Recommend bounded implementation**, retaining every assertion, source body, trigger and recorder. |
| F8: delete the synthetic-ID ordering assertion as duplicate priority coverage | Current assertion says 99002 precedes 99004, but this test explicitly has no FINAL_PENDING events. Numeric tie ordering can satisfy it; it does not prove final-pending priority despite the constant name. | Production sorts by priority then ID. `coalescesOneInProgressEventAndPrioritizesFinalPendingPages` actually asserts FINAL_PENDING plus active participants. These are different setups; exact smoke ordering may still be intentional. | Negligible assertion cost. **Retain / uncertain intent** rather than deleting or claiming priority coverage from the names. |

## Original implementation proposal (subsequently approved below)

Apply only F7 to this method. Use the installed Hikari facility and the existing case-local
pattern from `OwnedSyncStoreIntegrationTest` / `OwnedMatchdayEndToEndIntegrationTest`.
Create and automatically close a method-local pool, rebuild the recording store/publisher against
it before constructing the collector, and retain class setup/cleanup safety gates unchanged.
Do not pool the whole class, alter shared helpers, reduce the roster, remove triggers/logging,
merge transactions or serialize any real concurrent-worker fixture. Preserve the nested BUSY
callback and restore any temporarily replaced fixture fields in `finally` before closing the pool.
Do not assume a one-connection pool is safe merely because this method is serial: check the
callback and transaction propagation and run it under the proposed configuration.

Verification contract if approved:

1. Run this exact method unchanged on the disposable DB, with the documented memory/Gradle gates.
2. Apply only case-local reuse; run the same method, preserving every existing assertion.
3. Record exact revisions, command, tests/failures/errors/skips and pool cleanup. Compare JUnit
   method times only as a same-host pair; the existing printed cycle time excludes some setup.
4. Inspect the diff against baseline: no runtime/schema/shared-helper/config changes and no lost
   workload/assertions. If it fails or no useful improvement appears, retain the existing test
   and report the result rather than broadening scope.

This is a performance-only test-harness change: before/after success, not a fabricated TDD red.
No test deletion is recommended in this pass. No global pool ceiling claim or KAR-51 closure.

## Initial audit evidence (before implementation approval)

Executed: authenticated KAR-79 read; `git fetch origin master`; current-source inspection of the
named test, immediate helpers, collector and page-publication transaction; nearby named tests
only for replacement comparison. A read-only Python assertion check of the recorded JSON verifies
20 clubs, 657 catalogue players, 380 fixture rows, absence of ID 658 from the catalogue, and that
99001/99002/99004 are distinct additions outside it. The first four recorded events plus the
unchecked-source helper confirm this scenario is not the final-pending priority fixture.
These static checks do not execute collector behavior or prove the 660 runtime result.

Not executed: Gradle/build/backend tests, before/after timing, coverage/mutation, production/API
probes, independent review, commits, push/PR/merge or Linear writes. No runtime or test code changed.
Historical timing in prior reports is not current performance evidence. The source-derived
connection lower bound is a reason to measure F7, not a promised speedup or hosted billing saving.

## Approved F7 implementation and verification

Only the named method now wraps its existing recorded JDBC boundary in a method-local Hikari
pool (`maximumPoolSize=1`, `minimumIdle=0`, try-with-resources). Store, transaction manager,
publisher and collector are constructed locally against the same recording datasource. No
static or instance fixture fields are replaced, so there is no field-restoration obligation.
The class-wide datasource, shared helpers, setup/cleanup guards and other methods are unchanged.
There is no transaction around the whole test: individual store/publication transactions remain
separate. The first-request callback runs outside those transactions; its BUSY assertion passes
with the single-connection pool. Triggers, SQL recording and the idle cycle remain intact.

The original method's **21 assertion statements are byte-equivalent after whitespace
normalization**, checked against baseline `f0a04c4`. No source bodies or expected counts changed.
The changed test-file SHA-256 is
`e2b02db78147af2437c4cd4db1a1eed1b6dba50e439497e41028c51a94797f12`.
Both measurements use the same baseline runtime/build inputs; only this test file differs.
Documentation edits are not backend inputs. This performance-only harness change uses a passing
baseline and passing changed test, not a claimed TDD-red regression.

Environment: existing Ubuntu native Docker daemon via process-local
`DOCKER_HOST=unix:///run/kurssit-docker.sock`; Docker Desktop was not started or reconfigured.
`scripts/test-db.sh up` created the absent disposable `fpl-test-db` on loopback port 55432 and
loaded the checked-in schema. No other containers were modified. Explicit test environment:
`DATABASE_URL=jdbc:postgresql://127.0.0.1:55432/postgres`, `DB_USER=postgres`,
`DB_PASSWORD=postgres`; Anthropic/OpenAI/Google provider keys were dummy values, not loaded
from credential files. Java: Temurin 25.0.4+7-LTS.

Exact test command, cwd `backend/`, executed once per state:

```bash
systemd-run --user --scope --unit="$unit" \
  -p MemoryMax=1536M -p MemorySwapMax=0 -p CPUQuota=200% \
  ./gradlew --offline --console=plain cleanTest test \
  --tests '*CoalescedPlayerHistoryCollectorIntegrationTest.coalescesRecordedLeaguePagesAndDoesNotRefetchCompletedPostFinishWork'
```

The runner rejects overlapping Gradle/test JVMs or available host memory below 2 GiB, applies
a 300-second timeout, and stops only its own scope if host available memory drops below
768 MiB. Both commands exited 0; neither guard stopped the run. Checked-in worker/heap limits
were unchanged. Raw local receipts: `/tmp/fpl-kar79-pool.RKA55j/{before,after}.{log,xml}`;
the temporary runner is `run.sh` there. These local paths are not remote review artifacts.

| Measurement | Before | After |
|---|---:|---:|
| JUnit method duration | 15.415s | 8.074s |
| Printed first-cycle duration (partial method timing) | 14.259s | 6.962s |
| Tests / failures / errors / skipped | 1 / 0 / 0 / 0 | 1 / 0 / 0 / 0 |
| Selected / unique / history requests | 660 / 660 / 660 | 660 / 660 / 660 |
| Recorded statements / queries / updates | 10,009 / 6,821 / 3,167 | 10,009 / 6,821 / 3,167 |
| Recorded returned rows / approximate logical bytes | 3,508 / 149,589 | 3,508 / 149,589 |
| Returned fake-HTTP body bytes | 3,851,486 | 3,851,486 |
| Physical PFS inserts | 2 | 2 |

Both runs also report identical physical-write counts for all observed table/operation keys;
bootstrap/fixtures/live request counts are 1/1/4. The after log confirms Hikari startup and
shutdown. Hikari logs an unsupported datasource login-timeout notice; this does not fail the
run. There are no surviving Gradle/test JVMs or test scopes, and the post-run disposable DB
reports zero JDBC-driver sessions. The test container is retained for further focused work.

Parent acceptance: source/diff inspection confirms method-local pool ownership, unchanged
assertions/workload, independent transaction boundaries and no production/shared-helper change.
XML confirms both real DB runs passed, including BUSY and the second-cycle no-refetch assertions.
`git diff --check` passes. This is one same-host comparison pair, not a stable benchmark,
whole-suite gain or hosted CI/billing claim. No full suite, additional test family, independent
panel, production query, remote publication, Linear write or deployment was performed. Local
commit contains the verified test change and its audit/plan/status evidence; remote actions
require separate authorization.
