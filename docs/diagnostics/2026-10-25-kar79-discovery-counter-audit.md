# KAR-79: discovery-batch instrumentation audit and correction

**PROGRESS:** Bounded audit and counter correction complete locally; regression failed as expected before the fix, then all six focused cases passed; final integrated verification/review remains separate.

## Identity, scope and decision

Date: 2026-10-25. Baseline: `bb8c895` on `docs/kar79-collector-smoke-audit`, worktree
`/home/vanka/dev/fpl-assistant-test-audit-next`. The owner authorized the small-audit → fix →
next-unit workflow, ending in a PR and strict independent review without merge.
This unit is the remaining named KAR-79 discovery instrumentation item, not a whole-suite audit.

Read every `discoveryCandidateBatchCount` caller, the test-local `SqlRecordingDataSource`,
`OwnedSyncStore.recordIncrementalPopulation`, its discovery/known candidate insertion helpers,
and the existing batch execution/failure counters. The discovery helper has six call sites:
four retry/no-op/correction observations in the known-zero-minute incident regression and two
in the 501-candidate boundary case. The shared recorder has fourteen direct test-method users
including the new self-check; broader consumer verification is tracked in the final handoff.

The old helper counted **prepared SQL strings containing a diagnostic comment**, not executions.
A prepared but never executed marked statement could count as work; one prepared statement
executed repeatedly counted once; removing/renaming a SQL comment could make real writes count
zero. Durable row counts independently protect population/participant semantics but cannot prove
bounded JDBC round trips or no repeated write attempts when `ON CONFLICT DO NOTHING` keeps the
same stored rows.

## Six candidate decisions

| Candidate | Contract / plausible rejected defect | Oracle / replacement | Cost and decision |
|---|---|---|---|
| D2.1 Delete the 501-candidate test | Crossing the 500-row chunk boundary must use two discovery writes and must skip existing candidates on retry without participant promotion. Per-row writes can preserve all row counts while regressing execution cost. | Real disposable DB plus execution counter and discovery/participant row queries; no equivalent replacement established. | Fixture already shrunk in PR #180; do not count that again. **Keep unchanged.** |
| D2.2 Drop the incident regression's zero-discovery checks | A retry/no-op/correction can needlessly rewrite durable discovery rows while leaving row snapshots equal. | Other assertions test results/known-zero-minute withdrawal, not write attempts. **Keep** all six original helper-call observations. | Old timing is not current billing evidence. No fixture shrink or incident oracle removed. |
| D2.3 Use total receipt writes for every caller | Other population, appearance and page receipt writes legitimately occur while discovery alone must stay zero. | Existing total counter is too broad and would conflate those behaviors. **Reject blanket substitution.** | No new production instrumentation warranted. |
| D2.4 Count actual discovery write executions from bound keys | Reused statement, markerless SQL or a prepared-but-unexecuted batch must not fool the counter. | Reuse the current test JDBC proxy, identify receipt inserts and publication key parameter 2 by the stable discovery prefix; count execution attempts, not preparations/rows. **Implement.** | Test-helper work only; no runtime SQL changes or expected performance gain claimed. |
| D2.5 Delete production comments or restructure SQL to make tests pass | Renaming a diagnostic comment is not a behavior change, and SQL shape is not the desired oracle. | Correct the test observer instead. **Leave production untouched.** | No shared/runtime churn. |
| D2.6 Remove recorder/failure-injection support | Existing timeout/bounded-round-trip and transaction rollback cases use total counters and injection. | New discovery-specific classification must coexist with total count, delay and failure injection. **Keep existing support**, verify its callers. | No global serialization, new framework or large-workload recreation. |

## Minimal implementation and self-check

The helper returns a discovery-specific execution counter. The existing statement proxy remembers
the bound publication key and whether a queued batch contains discovery keys. It increments only
on receipt INSERT execution (`executeUpdate`, `executeLargeUpdate`, `executeBatch`, or
`executeLargeBatch`), resets queued discovery state on batch execution/clear, and clears key state
on `clearParameters`. The recording reset also resets the new aggregate counter. The shared
total receipt execution counter, timing delay, injected SQL failure and query/result recording
stay in place. The proxy remains specific to these owned receipt statements, where publication
key is bound at parameter 2; this is not a general SQL parser or whole-application JDBC monitor.
The counter measures attempted executions, not successful commits or physical row writes.

One real-DB self-check, `discoveryCounterCountsExecutionsWithoutDependingOnSqlComments`, uses
markerless SQL. It checks zero after preparation/addBatch, two after two executions of one
prepared discovery statement (including an unchanged conflict), no increment for participant
writes or a cleared/empty batch, one more for a `setObject`-bound direct large update, and zero
after recorder reset. It does not depend on production batching to validate its own counter.
Existing physical rows and rollback assertions remain the independent storage oracle.

Only `CoalescedPlayerHistoryCollectorIntegrationTest.java` changes in this unit. No production
SQL/comment, store/collector behavior, batch size, source fixture, shared configuration or
runtime dependency changed. No existing case was deleted or weakened.

## Executed checks

All runs: cwd `backend/`; explicit disposable `127.0.0.1:55432` PostgreSQL and dummy AI keys;
native Ubuntu Docker via process-local `DOCKER_HOST=unix:///run/kurssit-docker.sock`.
The same runner rejects concurrent Gradle/test JVMs or <2 GiB available host memory; applies
1536 MiB/no-swap/200% CPU scope and 300-second timeout; and stops only that scope below 768 MiB
host available memory. Checked-in Gradle/test limits were unchanged. No guard stopped a run.

Common command prefix:

```bash
systemd-run --user --scope --unit="$unit" \
  -p MemoryMax=1536M -p MemorySwapMax=0 -p CPUQuota=200% \
  ./gradlew --offline --console=plain cleanTest test
```

Append `--tests '*CoalescedPlayerHistoryCollectorIntegrationTest.<method>'` for each selector:

- Baseline (`bb8c895`): `discoveryCandidateReceiptsUseBoundedBatchesAndSkipExistingIds`,
  `unchangedDiscoveryRetryKeepsKnownZeroMinutePlayerOutstanding` — **2/2 passed**, exit 0.
- Red (`bb8c895` plus initial markerless self-check, original helper unchanged):
  `discoveryCounterCountsExecutionsWithoutDependingOnSqlComments` — **1 expected assertion
  failure, 0 errors/skips**, exit 1: expected 2 after two actual executions, old helper returned 0.
  This was not a compile/fixture/environment failure.
- Green: the self-check, both baseline methods, plus
  `coldLeaguePopulationFitsBoundedTransactionWithDatabaseRoundTripLatency`,
  `liveEventParticipantsAndAppearanceReceiptsUseBoundedBatches`, and
  `populationBatchFailureRollsBackEarlierBatches` — **6/6 passed, 0 failures/errors/skips**, exit 0.
  Green test-file SHA-256:
  `be21cdabb93b9da33fcde17706963b4c1ee332addcc0c1205aa5e9f5bfe445e8`.

Raw local evidence: `/tmp/fpl-kar79-closeout.L9kkjV/run.sh`,
`discovery-{before,red,green}.log`, and corresponding XML directories. These temporary local
paths are not remote evidence links; this report preserves counts, exact scope, command and
failure reason for publication. The new self-check was extended before the green run to exercise
clearBatch/empty execution, non-discovery keys, setObject, large update and reset as described.

Parent source/diff acceptance: the correction removes comment/preparation counting, every old
caller remains, the incident and 501-boundary assertions are unchanged, and existing total
counter/delay/failure-injection machinery still passes its focused guards. `git diff --check`
passes. Fourteen recorder consumers will be included in the final integrated targeted run.
No full suite, production service, live provider, coverage/mutation program, independent panel
or remote write is claimed by this per-slice receipt. Further audit families remain separate
work in KAR-79; the next step here is integrated verification, PR and strict review.
