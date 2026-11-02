# KAR-79 S2: default-OFF startup audit

**PROGRESS:** S2 audit and approved oracle cleanup complete locally; both contexts retained and pass before/after (2/2 each).

## Identity and scope

Date: 2026-10-25. Baseline: `54913522a319b4716de05843fd72cc41fd467553`,
branch `docs/kar79-collector-smoke-audit`, worktree
`/home/vanka/dev/fpl-assistant-test-audit-next`. Parent baseline is fetched master `f0a04c4`;
the preceding commit changes only the collector smoke harness and its documentation.
The owner's next “jatka” resumes one audit unit, not remote publication, unconditional test
removal or implementation of every candidate. The collector-pool change is already locally
committed and is not re-counted as S2 work.

Exactly two methods and their immediate configuration/fixture paths:

- `OwnedSyncRuntimeConfigurationTest.defaultOffCreatesNoOwnedRuntimeAndTouchesNoDatabase`
  (lean module context, no datasource properties).
- `OwnedSyncImporterContextIntegrationTest.defaultOffCreatesNeitherImporterNorMonitoringRuntimeAndTouchesNoOwnedRows`
  (wider module context with disposable datasource properties and SQL observations).

Test-file SHA-256 values at this baseline:

- `OwnedSyncRuntimeConfigurationTest.java`:
  `124c5da25ac99b621ba81bd825160834534a370e58c5394f05b50189ee9ea52c`
- `OwnedSyncImporterContextIntegrationTest.java`:
  `3eaa14a751bb864b2ebcb03d86642c53ec9fdbe476eeb3f840f0b01bcf4e2f51`

Read the four direct configuration classes and controller condition, not the unrelated active,
operator-handoff, schema-repair, scheduling or endpoint test families. Prior conditional S2
analysis is in [the first-pass report](2026-10-18-test-audit-first-pass.md#s2--one-possible-default-off-merge-conditional-rather-than-ready-to-delete).

## Actual contracts and evidence boundaries

Both select real Spring configuration under missing opt-in properties. Importer wiring is
conditional on `fpl.owned-sync.importer.enabled=true`. Legacy wiring defaults mode to OFF;
monitoring and operations have separate opt-in expressions. The database-holder constructors
require datasource properties and construct their own Hikari/JDBC objects: registering a
standalone `JdbcTemplate` mock does not observe those factories' SQL.

The lean runner selects runtime/importer/operations configuration and registers a JDBC mock.
It has no datasource properties and does not select the monitoring configuration or controller
class. It asserts ten absent beans, including three not explicitly checked by the wider test:
`CoalescedPlayerHistoryImportEntrypoint`, `OwnedMatchdayCoordinator`, `OwnedSyncHttpClient`.
Its no-datasource input checks these modules' conditional wiring, **not** the ability of the
whole application to boot without a database. No Mockito verification observes its mock.

The wider runner additionally selects `OwnedSyncMonitoringDatabase.DatabaseConfiguration`
and `SyncOperationsController`, supplies datasource properties, explicitly asserts successful
startup and checks absence of the monitoring holder. Before the method, its fixture initializes
real disposable tables, seeds an ACTIVE epoch 7, and zeros the run table and `SAFE_CYCLES`.
Thus “touches no database” cannot describe its complete fixture lifecycle; intended scope is
startup side effects after seeding, not a claim that no SQL runs in the test.

Two gaps limit the wider method's claimed no-work/no-write oracle:

1. `defaultOffRunner()` does not register `SafeLifecycleConfiguration`. The only increment of
   `SAFE_CYCLES` is inside that configuration's fake entrypoint, selected by the separate enabled
   `runner()`. Checking zero in this OFF method does not observe a started real collector cycle.
   The actual bean-absence checks remain useful and independent of this disconnected counter.
2. `defaultOffRunner()` does not register the fixture's `OwnedSyncTables` bean. Its SQL assertions
   inspect custom `fpl_ctx*` tables. If importer or ops factories were admitted, they would select
   default tables (`ownedImporterTables` / `tableProvider.getIfAvailable(defaults)`), not these
   custom tables. The legacy factory also hardcodes defaults. Therefore unchanged epoch/run rows
   in the custom tables are not independently routed evidence that those real factory paths
   cannot write. Correct class/bean exclusions are still checked; this is not evidence of actual
   erroneous OFF writes in production.

## Seven candidate decisions

| Candidate | Rejected defect / distinct boundary | Replacement and oracle independence | Cost and decision |
|---|---|---|---|
| S2.1 Delete lean test | A conditional configuration unexpectedly requires datasource properties or admits one of its three extra bean types. | Wider context supplies properties and lacks those three assertions. No exact replacement. | No measured duration. **Retain.** No-datasource module wiring is a distinct input; do not silently drop it. |
| S2.2 Delete wider test | Independent monitoring/controller conditions accidentally admit beans under default OFF. | Lean context never selects these classes and cannot catch that regression. | Real DB fixture initialization/seed/restore incurs work, but unmeasured. **Retain.** |
| S2.3 Transfer three assertions, then delete lean | Extra bean-absence coverage moves, but missing-datasource input disappears. | Transferring assertions alone cannot replace that input or reproduce the different selected configuration. | Maximum one context method removed, unknown saving. **Do not consolidate as-is.** A supported-input decision remains owner-owned. |
| S2.4 Remove lean JDBC mock and narrow test name | Current mock observes nothing; a real work holder constructs its own JDBC regardless of it. | Real configuration plus bean-absence assertions remain. No actual SQL-access detector is being replaced because no mock interaction is asserted. | Small setup/maintenance trim, no measured speedup. **Recommend separately approved cleanup**, preserving no-datasource input and every absence assertion. Remove the now-orphaned `mock` import only if this is implemented. |
| S2.5 Remove wider method's `SAFE_CYCLES == 0` assertion | A real collector could run without incrementing the unregistered fake entrypoint counter. | Existing absence of collector/runtime is the connected oracle. Do not register the fake entrypoint just to give the counter a producer: that changes OFF wiring and conflicts with transferring an entrypoint-absence assertion. | One ineffective assertion, no timing claim. **Recommend separately approved removal/narrowing**, not a new claim of proven zero real cycles. Other enabled-runner uses of the counter must remain. |
| S2.6 Treat custom-table epoch/run checks as proof of no writes | Accidental work could target default tables while custom epoch/run tables remain unchanged. | No exact routed SQL oracle in this method. A fixture `OwnedSyncTables` bean routes importer/ops, not the legacy factory's hardcoded defaults. | Current SQL cost unknown. **Retain pending repair/claim decision**; do not use these checks to justify deleting a connected test elsewhere. No global shared-runner edit under a two-method audit. |
| S2.7 Replace both with a new DB-free wider test | Wider selected configs could require datasource properties even under OFF; a merged test might cover this, but loses configured-datasource input and current DB observations. | Requires a deliberate replacement contract, not merely shared assertions. Safe configured-datasource no-write observation still needs a connected boundary. | New context/framework not warranted for uncertain saving. **Defer**, no replacement test proposed in this pass. |

## Recommendation and handback

Keep both methods. Their distinct selected configurations and input properties are not exact
duplicates; no test deletion is justified by the current source. If the owner wants the next
small implementation, remove only the unused lean JDBC mock and the disconnected OFF
`SAFE_CYCLES` assertion, and narrow the affected method names to the connected startup/bean
claims. Do not remove counters/helpers used by enabled cases or claim this saves meaningful CI
time. The custom-table SQL-routing issue remains a separate bounded decision, not a reason to
silently drop all fixture safety/ownership checks.

If that cleanup is approved, run the two existing methods before/after against the disposable
DB under the established resource gate, retain every current bean-absence assertion and actual
startup success contract, inspect all changed-symbol callers, and record hashes/commands/counts.
This would be test-claim cleanup with a passing baseline, not a production fix or fabricated
red/green regression. No local full suite, live database probe or new framework is needed.

## Executed / unverified

Executed: source inspection, baseline/clean-tree verification before this pass, and a read-only
Python assertion check extracting absence sets from the two methods. It confirms exactly the
three lean-only absence assertions and the wider-only monitoring-holder assertion; confirms
`defaultOffRunner` selects neither the fake lifecycle configuration nor custom table binding;
and confirms the lean method has no mock verification. Recorded hashes identify both files.
These are source observations, not executed startup outcomes.

The initial audit changed no runtime/test/config code and executed no startup tests. Subsequent
owner approval and executed S2 checks are recorded below; prior collector results are not used
as evidence for these contexts.

## Approved cleanup and local verification

The owner approved correcting this pass and continuing through bounded audits/fixes before PR
publication and strict review. Removed only the lean method's unused mock/bean registration and
two orphaned imports, and the wider OFF method's disconnected `SAFE_CYCLES` assertion.
The fake entrypoint/counter remain unchanged for the enabled-runner tests that actually use them.
Renamed the lean method to `defaultOffCreatesNoOwnedBeansWithoutDatasourceProperties` and the
wider method to `defaultOffCreatesNoImporterOrMonitoringBeans`, removing unsupported no-SQL/
no-write claims. The lean method now explicitly asserts successful startup; all ten lean and
eight wider bean-absence checks remain. The two custom-table SQL snapshots remain, with an
explicit comment limiting their claim rather than silently pretending they observe default-table
writers. No shared runner or production configuration was changed; both methods remain.

Ran the same two cases before and after (renamed selectors after the edit), **2/2 each, zero
failures/errors/skips**, against disposable `127.0.0.1:55432` with dummy provider keys. Cwd:
`backend/`; native Ubuntu Docker socket, no Desktop. Resource gate: no overlapping Gradle JVM,
at least 2 GiB available before launch, 1536 MiB scope, no swap, 200% CPU, 300-second timeout,
and stop only the scope below 768 MiB host available memory. Exact Gradle portion:

```bash
./gradlew --offline --console=plain cleanTest test \
  --tests '*OwnedSyncRuntimeConfigurationTest.defaultOffCreatesNoOwnedRuntimeAndTouchesNoDatabase' \
  --tests '*OwnedSyncImporterContextIntegrationTest.defaultOffCreatesNeitherImporterNorMonitoringRuntimeAndTouchesNoOwnedRows'
```

The after command substitutes the two new names above. Local runner and receipts:
`/tmp/fpl-kar79-closeout.L9kkjV/run.sh`, `s2-before.log`, `s2-after.log` and corresponding
XML directories. Both commands exit 0. The method durations were 0.872s/0.378s before and
0.007s/0.343s after (lean/wider); this is one startup-sensitive comparison, not a stable speedup,
whole-suite saving or evidence that the wider DB fixture is cheap.

Changed test-file SHA-256 values:

- Runtime configuration test: `2b23019e8f8b0efea1e87be751aa86a90a947838b7e72938d3557929841c062d`
- Importer context test: `371fc576827bf7a761441c661e09f1e44fb496e33e0a7aaeca62280905dbffd2`

Parent diff/source check accepts unchanged configuration selections, preserved absence checks,
no loss of the no-datasource input, unchanged real fixture SQL guards, and retention of counters
used elsewhere. `git diff --check` passes. This is test-claim cleanup with a passing baseline,
not a production fix or fabricated TDD red. No claim is made to have repaired/rerouted the SQL
oracle; the unsupported test-name claim is removed and the evidence limit is explicit. Full
suite, production checks and independent panel are not part of this per-slice receipt; the
final PR review is separate.
