# First bounded test-audit pass

**PROGRESS:** Four bounded audits and approved cleanups complete locally; both scoped-pool scenarios pass their recorded before/after checks.

Source revision: `96caea7c123ed64c66a6845b45e2e31234f8345f` (PR #173).
[Audit plan](../superpowers/plans/2026-10-18-behavior-first-test-audit.md).
Owner approved correcting the plan and executing the read-only audit, not deleting tests.
No tests, builds, coverage/mutation runs, CI runs or production accesses are part of this pass.

## Baseline, not execution measurements

Static file enumeration at this revision found **206 Java files in the test source tree**, including
helpers. This is not an executed test count. The historical 1,685 cases / 838.3 JUnit class-seconds
belong to the earlier pre-pruning revision and are not reused as current performance evidence.

| Family | Java source files, including helpers |
| --- | ---: |
| sync, including sync.source | 43 |
| tools | 35 |
| chat + trace | 22 |
| price | 19 |
| squad + shortlist | 18 |
| schedule | 8 |
| user + myteam | 22 |
| historical | 11 |
| defcon + teamxg | 8 |
| rag | 6 |
| config + testsupport + root | 14 |
| Total | 206 |

These counts orient reading; they do not establish waste or prioritize by runtime.

## Pilot: seven prompt-contract classes

Scope: the seven `chat/*PromptContractTest.java` files, **305 source lines / 15 ordinary test
methods**. Read in full, together with the actual prompt and `AiConfig`; read `ChatService` and
relevant orchestration/trace test excerpts only to distinguish boundaries. No outside-family
case is being proposed for deletion.

Production uses the same resource: `config/AiConfig.java:20–46` injects
`classpath:prompts/system-prompt.st` and sets it as the chat client's default system prompt.
These tests assert against that real resource, not a copy inside a fake model.

All test paths below are relative to
`backend/src/test/java/fi/karivanttinen/fplassistant/`.

### Retention decisions

| Class / methods | Contract and plausible regression | Oracle/boundary | Decision |
| --- | --- | --- | --- |
| `AvailabilityPromptContractTest` (3) | Keep normal availability quiet, qualify missing/old evidence, and answer direct questions without guaranteeing fitness. Deleting the missing-check qualification should fail even if the quiet-output instruction remains. | Literal instructions in production resource; independent obligations, not three identical cases. | Keep all three. |
| `BenchOrderPromptContractTest` (3) | First substitute must come on first; reserve GK is not the fourth outfielder; formation and cameo rules constrain substitutions. Reversing slots does not test the separate GK/cameo rules. | Ordered subsequence plus distinct instruction strings. | Keep all three. |
| `DefconComparisonPromptContractTest` (1) | Defender/midfielder comparisons require observed DefCon evidence. Removing midfielder coverage or replacing evidence with role stereotypes is an independent regression. | Production wording and tool-name requirement; not a DefCon calculation test. | Keep. |
| `PlayerNamePromptContractTest` (3) | Disputed facts need fresh lookups, valid same-turn club evidence avoids redundant lookups, and name retries must not substitute a similar player. Removing the fresh-evidence exception or surname retry is not caught by price instructions. | Production resource. Some price/availability assertions overlap the next class; see A1. | Keep all three methods; optional assertion trim only. |
| `PriceForecastPromptContractTest` (3) | Separate source routing/absent tools, correction-section routing, and pre-daily-capture/personal-sale limitations. Wrong current-price instructions in the correction section can survive a global-text check elsewhere. | Real prompt plus actual `PriceTools.getPriceHistory` annotation; correction case restricts assertions to the correction section. | Keep all three methods and the tool-description assertions; see A2. |
| `TeamXgPromptContractTest` (1) | Distinguish direct Understat team data from FPL player data; prohibit reconstructing missing team totals. Restoring the old player-xGC route is not a DefCon or availability regression. | Whitespace-normalized real prompt, positive and negative assertions. | Keep. |
| `WildcardCoveragePromptContractTest` (1) | Missing comparison data must not become a rotation judgement or a hand-built fallback squad. Removing the refusal/recheck instruction is distinct from ordinary player lookup. | Real prompt. The previously deleted scripted-model case is absent. | Keep. |

The wording oracle is intentionally narrow. It does not prove that a real LLM obeys these rules,
that tool data is correct, or even that a contradictory instruction could not coexist elsewhere.
That limitation does not make these tests tautologies: removing an asserted instruction changes
the production input and fails its guard. Do not advertise them as behavioral LLM evaluations.

`ChatServiceTest.everyInjectedToolIsRegistered` checks callback registration, while
`ChatTraceInstrumentationTest.instrumentedTurnWithRealSystemPromptCompletesAndPreservesTrace`
checks the joined service/trace path with a controlled model. They do not replace these content
contracts. Conversely, a wording test does not establish orchestration or model obedience.

### Actual candidates: two trims, zero whole-test deletions

**A1 — optional, high static confidence:** remove only three duplicated `contains` arguments from
`chat/PlayerNamePromptContractTest.java:29–31`, within
`currentClubAndDisputedPlayerFactsRequireANewLookup`:

- `For disputed current prices, use getPlayerPriceForecast if that tool is available`
- `only the latest dated daily observation`
- `for disputed availability use getPlayerAvailability`

Exact retained replacement:
`PriceForecastPromptContractTest.disputedCurrentPriceUsesTheSameForecastFirstRoutingAsTheOriginalQuestion`,
lines 35–42, requires these same strings **inside the correction section**. That stronger predicate
implies their presence in the whole prompt. Keep the rest of the player-facts method, including
fresh lookup, historical club, memory-conflict and stale/ambiguous-source checks.

**A2 — optional, high static confidence:** remove only two duplicated global-prompt `contains`
arguments from `PriceForecastPromptContractTest.routesDistinctSourcesWithAbsentToolFallbackAndNoHardcodedState`,
lines 19–20: `getPlayerPriceForecast if that tool is available` and
`only the latest dated daily observation`. The same retained correction-section method above
requires the first as a substring of a longer exact phrase, and the second verbatim. Its passing
therefore implies both global checks. Preserve every other source/state/negative assertion and
the separate `PriceTools` annotation checks.

For either trim, deleting the corresponding production phrase still fails the retained
correction-section method. This is static predicate containment, not an executed verification
claim. It relies on retaining that method and using the same immutable classpath resource.
If a future edit changes the tests' resource or scope, recheck the relationship.

**Benefit/decision:** at most five repeated string expectations disappear; **zero test methods,
classes or fixtures** disappear. This is negligible, unmeasured runtime benefit. Do not create a
standalone PR or run a suite for this micro-cleanup; consider it when that prompt-test family
next needs an authorized change. No common loader abstraction, test-class merger or new live-model
suite is justified by this pilot.

## Single sync path: importer startup / legacy-runtime exclusion

Read `CoalescedPlayerHistoryImportConfiguration`, the directly selected runtime/monitoring/ops
configuration and schema-preflight dependencies. In `OwnedSyncImporterContextIntegrationTest`,
audit the four default-OFF / enabled-startup / missing-schema cases, not its two operator-handoff
cases. In `OwnedSyncRuntimeConfigurationTest`, audit the five configuration/activation cases,
not its timer/maintenance or CLI cases. Read only the direct `existingActiveFence` comparison
case and setup in `CoalescedPlayerHistoryCollectorIntegrationTest`, not its collector scenarios.
These are all one startup path, not further sync families. Findings below describe the pre-cleanup
`96caea7` source; the later implementation and verification are recorded at the end.

### Keep the distinct contracts

| Test/group | Plausible defect and actual boundary | Decision |
| --- | --- | --- |
| `OwnedSyncRuntimeConfigurationTest.activeRequiresBothExplicitSeasonAndCutoverConfirmation` and `newImporterRequiresActiveModeExplicitSeasonAndCutoverWithoutTouchingTheDatabase` | Configured mode, explicit season and cutover are independently rejected by real validation functions; importer ACTIVE-only admission is stricter than the legacy gate. | Keep; do not collapse all invalid states because they throw similar exceptions. |
| `importerGuardSelectsTheNewCoreLoopButLeavesDailyCaptureEnabled` | Pure selectors could accidentally admit both core writers, or disable daily captures when the importer is selected. | Keep. Spring startup assertions test actual wiring, not a replacement for all selector/capture combinations. |
| Context `defaultOffCreatesNeitherImporterNorMonitoringRuntimeAndTouchesNoOwnedRows` | An OFF configuration could start a monitoring/work pool or change a persisted epoch. Checks fuller configuration wiring and actual disposable-DB rows. | Keep, including the fixture's production-target guards. Compare the lean context below before any merge. |
| Context `importerAndExternalHealthStartWithSeparatePoolsWithoutActivatingOrInitializing` | New importer could coexist with legacy dispatchers, share monitoring/work pools, alter its epoch or start issuing requests during startup. Real beans/DB plus controlled lifecycle entrypoint exercise this boundary. | Keep. The fake limits the test to startup/health, not successful real ingestion. |
| Context `missingTypedSupersedeReasonColumnFailsOptInPreflightBeforeCollectorStarts` and `missingPointerTableOrFinalityCheckpointColumnFailsWithoutSchemaRepair` | Removing required schema items must refuse startup without implicit schema repair or collector work. The typed run-state requirement, absent pointer table and finality checkpoint are different migration obligations. | Keep both. Generic missing-column logic does not make the required-table/column list itself redundant. |
| Direct `importerAttachesOnlyToAnAlreadyActiveSeasonRuntime` comparison | A stored STAGING fence is not the configured STAGING-mode check; accepting it despite configured ACTIVE would be a separate error. | Keep its real SQL/fence assertions. No whole-collector-suite conclusion follows. |

### S1 — misleading DB-isolation oracle; retain the startup refusal

`OwnedSyncRuntimeConfigurationTest.invalidOptInFailsBeforeDatabaseAccess` (lines 81–95)
registers a mocked `JdbcTemplate` and checks `verify(jdbc, never()).execute(anyString())`.
However, the configuration under test does not inject that bean:

- `CoalescedPlayerHistoryImportConfiguration.ownedSyncWorkDatabase` builds its own database holder.
- `OwnedSyncMonitoringDatabase` constructs its own Hikari datasource and `JdbcTemplate`.
- Importer beans obtain JDBC from that holder, not the registered mock.

Thus the mock check cannot detect access through the actual work pool. Even if that mock were
connected, checking only `execute(String)` would not detect `query`/`update` calls. The useful
oracle is the actual Spring startup failure with the specific ACTIVE-mode validation root cause.
The `ImporterActivation` parameter on the work-pool factory currently enforces validation-before-
pool construction in production; observing an unrelated mock is not a test of that dependency.

**Decision:** keep the invalid-opt-in test. In a separately approved change, either observe the
actual factory/pool boundary, or remove the disconnected mock/verification and narrow the method's
claim to activation refusal. There is no verified replacement oracle for the stronger no-DB-access
claim in this inspected slice. Do not call the current test proof of that guarantee, and do not
replace it with another test that only checks an unconnected mock.

Confidence: high for the disconnected oracle, not an executed demonstration of a runtime defect.
This is a test-claim/quality issue, not evidence that production currently accesses the DB too early.

### S2 — one possible default-OFF merge, conditional rather than ready to delete

`OwnedSyncRuntimeConfigurationTest.defaultOffCreatesNoOwnedRuntimeAndTouchesNoDatabase`
and context `defaultOffCreatesNeitherImporterNorMonitoringRuntimeAndTouchesNoOwnedRows`
overlap substantially. But the latter currently lacks three explicit absence assertions:
`CoalescedPlayerHistoryImportEntrypoint`, `OwnedMatchdayCoordinator` and `OwnedSyncHttpClient`.
It also includes monitoring/controller configuration and real datasource properties, unlike the
lean test. Therefore deletion today is not exact deduplication.

**Decision:** uncertain/retain. A possible future merge must first transfer those three assertions
to the existing context test and decide whether the lean/no-datasource-properties startup is a
separate supported contract. If it is, retain the lean test. Preserve the full-context monitoring,
epoch and no-work checks regardless. The most this could remove is one context test method;
there is no current timing evidence or large Actions-saving claim.

### Do not infer dead code from operational retirement

`OwnedSyncRuntimeConfiguration.legacyRuntimeEnabled` returns true for ACTIVE with importer=false;
its configuration can select the factory that constructs `OwnedMatchdayRuntime`. The selector
unit test explicitly expects that result. This disproves an assumption that the legacy factory is
unconditionally excluded in the pinned source; it does not establish that a production handback
is safe, supported or allowed by the current persisted fence. No production configuration or DB
was inspected. Legacy scenario tests cannot be classified obsolete from the word “retired” alone.

## First-batch decision and stop

- **No whole-test deletion is justified unconditionally by this pass.**
- A1/A2 identify five repeated prompt expectations, not five useless tests. They are optional
  maintenance trims, not a standalone CI-cost project.
- S1 merits correcting a misleading oracle/claim when implementation is authorized; retain the
  real fail-closed startup behavior. S2 is a conditional consolidation candidate, not a deletion.
- Current runtime/billing savings remain unknown. No new benchmark, mutation setup, live-model
  suite or replacement large scenario was introduced.
- Stop here as the corrected plan requires. Another sync path, implementation, verification runs
  or remote publication needs a separate owner decision. This pass was not independently
  re-reviewed; the earlier panel reviewed the plan before its boundedness correction.

## Subsequent owner-approved cleanup

The owner chose **remove only the disconnected mock**, not the whole invalid-opt-in test, and
approved local verification. Commit `83e241351db77038394696b6b38658005184e471` removes A1/A2's five
repeated string expectations and S1's unused mock/bean/verification plus orphaned imports.
`invalidOptInFailsDuringActivationValidation` retains both `hasFailed()` and the exact activation
root-cause assertion. No test methods or production behavior were removed; S2 remains untouched.

From `backend/`, the following targeted command passed with **15 tests, 0 failures/errors/skips**:

```bash
./gradlew --offline --console=plain test \
  --tests fi.karivanttinen.fplassistant.chat.PlayerNamePromptContractTest \
  --tests fi.karivanttinen.fplassistant.chat.PriceForecastPromptContractTest \
  --tests fi.karivanttinen.fplassistant.sync.OwnedSyncRuntimeConfigurationTest
```

Executed in one user-systemd scope (`MemoryMax=1536M`, `MemorySwapMax=0`, `CPUQuota=200%`), a
360-second timeout and host-memory stop guard. The environment was cleared and whitelisted,
with dummy provider keys and only a deliberately unusable localhost DB target; these classes
need no PostgreSQL service. Build elapsed 1m14s, including fresh compilation; JUnit class times
were 0.121s, 0.055s and 1.446s respectively, not a before/after benchmark.

Local receipt: `/tmp/fpl-test-audit-check.6NDgiL/test.log`; XML under
`backend/build/test-results/test/`. The first admission probe refused to launch because it matched
its own shell text, not a JVM; a corrected JVM-only check admitted the sole actual test run.
No failed test run is omitted. The committed code was compared against the tested files and
parent-reviewed for the approved scope. Test-first red was inapplicable to removing redundant
checks without changing production behavior. No full suite, hosted CI, live API/DB access, new
independent panel, push or PR was performed.

## Follow-on bounded audit: receipt batching

Source identity: `e1ff25dd688d7aa00b15e1b0092ec453ad29b978`; collector/store code is unchanged from
`96caea7`. Scope is exactly the five adjacent cases at
`CoalescedPlayerHistoryCollectorIntegrationTest.java:2133–2294`, their datasource recorder and
lease/setup helpers, and the store's population/live receipt batching methods and callers.
Other collector/finality scenarios were not audited. No further tests or builds were run here.

The [cold-start implementation record](../STATUS.md#importer-cold-start-receipt-batching-2026-10-17-implementation-record)
identifies a real production population-transaction timeout. Its 65-fixture shape and 4 ms/write
latency made the old implementation fail; the recorded fixed run took about 0.54 s. This is
historical evidence, not a new timing or evidence that the current full collector class is fast.
It explains why a scale-shaped case here is not automatically a disposable benchmark.

| Method | Unique defect/contract | Decision |
| --- | --- | --- |
| `coldLeaguePopulationFitsBoundedTransactionWithDatabaseRoundTripLatency` | Per-row population writes held the runtime fence until timeout. Covers 65 fixture markers, 2,015 participants and 4,030 discovery receipts under a bounded transaction. | Keep the real-incident shape and latency; do not shrink it under a claim of equivalent coverage. Improve its measurement below. |
| `liveEventParticipantsAndAppearanceReceiptsUseBoundedBatches` | Live-event writes use a different path with participant and appearance evidence. Fixing population batching alone does not fix it. | Keep: real execution counter, 800 participants across ten fixtures and typed appearance receipt counts. |
| `populationBatchesPreserveReceiptOrderingAndUnchangedFingerprint` | A batching rewrite can overwrite a newer source/generation or unnecessarily rewrite an unchanged population marker. | Keep: those SQL conflict predicates are not checked by row totals or query counts. |
| `populationBatchFailureRollsBackEarlierBatches` | A failure on write execution two must roll back the successful first chunk, not leave partial receipts. | Keep: real wrapped datasource/transaction and before/after receipt snapshot. 501 crosses the current 500-row boundary minimally. |
| `discoveryCandidateReceiptsUseBoundedBatchesAndSkipExistingIds` | Candidate discovery must not promote participants or rewrite existing IDs on retry. | Keep the method; its 657-row fixture has a smaller equivalent boundary candidate below. |

**B1 — optional fixture reduction, high static confidence:** the last method can use **501 rather
than 657 IDs**, updating its two count expectations. `OwnedSyncStore.java:63,1355–1359` fixes the
batch size at 500: both inputs exercise exactly two discovery batches and the retry/skip path,
while participants remain empty. No domain-specific 657-player requirement was found in that
method or its referenced membership plan. This removes 156 receipt rows, not a test or a database
round trip. No material CI saving is claimed, and it has not been edited or verified.

**B2 — correct the cold-test metric, high static confidence:** its `receiptWrites` variable counts
`recording.statements()`, which logs SQL at `prepareStatement`/`prepareCall`, but labels that count
“database write round trips”. Reusing a prepared statement for multiple executions can invalidate
that equivalence. The recorder already counts actual receipt executions in
`receiptWriteExecutions`, which the adjacent live-event test uses. Prefer that existing counter
for the same <=20 bound; preserve latency, transaction timeout and all row assertions. This is an
oracle correction, not a reason to delete the incident regression, and is not implemented yet.

The recorder is genuinely in this store's datasource/transaction path, unlike S1's disconnected
JDBC mock. Its `discoveryCandidateBatchCount` helper nevertheless identifies SQL by a comment
marker: changing that comment alone can fail tests without changing behavior. That is a bounded
maintainability follow-up, not proof of no value or authority for a class-wide helper rewrite.

**Stop:** five cases read, zero whole-test deletions justified; B1/B2 are proposals only.
The injected delays are 4 ms per intercepted receipt execution, not a hidden multi-minute wait.
Other finality/collector families, production handback policy and the rest of the 206-file test
tree remain unaudited. Further deletions or scope expansion require the next owner decision.

## Subsequent B1/B2 approval and verification

The owner approved both proposals. The working tree now uses 501 discovery IDs (still exactly
500+1) and the existing `receiptWriteExecutions` counter for the cold-test <=20 bound. All other
batching, row-count, nonparticipant, retry, latency and transaction assertions remain.

The first launch was refused before Gradle started because another worktree's test worker was
active. After the owner's next request, admission confirmed no active Gradle/test JVM and more
than 2 GiB available; the sole actual run passed **2 tests, 0 failures/errors/skips**:

```bash
./gradlew --offline --console=plain test \
  --tests fi.karivanttinen.fplassistant.sync.CoalescedPlayerHistoryCollectorIntegrationTest.coldLeaguePopulationFitsBoundedTransactionWithDatabaseRoundTripLatency \
  --tests fi.karivanttinen.fplassistant.sync.CoalescedPlayerHistoryCollectorIntegrationTest.discoveryCandidateReceiptsUseBoundedBatchesAndSkipExistingIds
```

The same 1536 MiB/no-swap scope, host-memory guard, 360-second timeout and cleared/whitelisted
environment were used, with dummy provider keys and the guarded disposable DB at
`127.0.0.1:55432/postgres`. No Docker daemon/container configuration changed. Build time was
17s; JUnit class time 3.33s, method times 0.663s and 0.271s. These are not before/after savings.
Receipt: `/tmp/fpl-batching-check.sfMnY8/test.log` plus the collector JUnit XML. Tested source
SHA-256: `6bcc0dcf60d4b863d03faffaae79d674b83f03b065a1c4b44488881aec9d623c`.
`git diff --check` and parent scope review passed. The earlier 15/15 receipt remains separate;
no full collector class, full suite, hosted CI, production service, independent panel or remote
publication was run for B1/B2.

## Third bounded audit: ten-matchday demand and closed admission

Source: `f8a22bc834802d714928b743abf9f671fada6a91`. One candidate:
`OwnedMatchdayEndToEndIntegrationTest.fullTenMatchdayDemandIsMaterializedAndBudgetExhaustionCannotCompleteWork`
(lines 579–638), plus the immediate production path and named smaller-boundary comparisons below.
The local historical `master169` report recorded 16.542s for this method. Its body is unchanged
from the previous audit's `4c1fa71` source, but this is **not a current timing** or billing estimate.
The old 38.386s collector correction case is already shrunk; it is not counted again as an
untouched optimization candidate. No new test, benchmark or hosted run was started this pass.

**Do not delete as an exact duplicate.** The pure
`AdaptiveMatchdayPlannerTest.tenMatchRollingCadenceStaysUnderCoordinatorDailyBudget` uses 36
players per fixture and checks <=5,000 requests plus 92 distinct source slots. This integration
case uses 60 players per fixture and checks >5,000 requests plus **92 persisted SQL slots**.
`OwnedMatchdayCoordinatorIntegrationTest.stableDailySlotsEnqueueCorrectClassesOnceWithoutDemandBumps`
and `matchdayPlayerRefreshIsBoundedLikeTheDailySweep` check smaller wiring/idempotence/chunking
boundaries, not the complete day's persisted ledger. They are not exact retained replacements.

**C1 — narrow the completion claim, not an automatic deletion.** `tick()` only plans/enqueues;
it never calls `dispatchOne`. After five admissions the test calls `store.enqueue` directly,
then checks the new row is PENDING with no completion timestamp. This is a valid narrow initial-
state check after closed admission, not proof that a worker cannot falsely complete denied work.
Keep that distinction and do not erase its initial-state assertions under a tautology label.
`OwnedSyncRequestBudgetIntegrationTest.rollingAttemptLimitExpiresAtExactWindowWithoutRestartReset`
checks the cap/restart/expiry separately. The real HTTP/dispatcher deferral cases inspect actual
requests and retry state but exercise minimum spacing, not this exact ATTEMPT_LIMIT combination;
the dispatcher uses one common `DeferredResult` branch. No stronger joined proof is claimed.

**C2 — recommended next implementation: reuse connections only inside this serial scenario.**
The fixture creates `DriverManagerDataSource`, which opens a connection for each acquisition.
Every `enqueueOnce` starts a transaction, even when `ON CONFLICT` finds the slot already present.
The 600-player daily sweep is expanded to 19 chunks on each of 144 ticks, giving a static lower
bound of **2,736 transaction/connection acquisitions for that sweep alone**, before source/history
slots and heartbeats. This is a source-derived count, not a measured connection trace or timing.

A case-local, automatically closed pool using the installed JDBC/Hikari facilities can serve the
case's JdbcTemplate and transaction manager without changing production code, any other test's
datasource, transaction boundaries, ten fixtures/600 players, 144 ticks, 92 persisted source slots,
>5,000 demand assertion or budget/new-row checks. Do not globally replace datasources in tests
that exercise concurrent actors, lock waits, restarts or pool acquisition. Preserve target guards.
This is fixture overhead reduction, not a dropped scale guarantee or fewer test cases.

Before accepting an implementation, run only this unchanged method once as the same-host baseline,
then the modified method, with the existing disposable-DB/resource guards. Compare the unchanged
oracles and record actual elapsed times; do not promise a percentage from the old report. No new
benchmark harness, library, permanent job or wider suite is needed. C2 was not implemented during
this read-only audit; its subsequent approval and checks follow. Further audit paths remain separate.

## Approved C2 implementation and same-host comparison

The owner approved implementation. Only this serial demand scenario creates a try-with-resources
`HikariDataSource`, backed by the existing datasource, with maximum pool size one and minimum idle
zero. Its local JdbcTemplate and transaction manager share that pool. It closes before the method
returns, including on assertion failure. Class-wide fixtures, production code, dependencies and
Gradle limits are unchanged. Connections are reused; transactions and durable writes are not merged.

All previous assertions and inputs remain: ten fixtures, 600 players, 144 ticks, 92 persisted source
slots, demand >5,000, five real budget admissions followed by refusal, and the new run's pending/null-
completion state. Diff review ignoring indentation shows only the import and scoped pool setup.
There is still no worker-dispatch proof hidden in the initial-state check.

Both runs used this exact single-method selector, offline and sequentially:

```bash
./gradlew --offline --console=plain test \
  --tests fi.karivanttinen.fplassistant.sync.OwnedMatchdayEndToEndIntegrationTest.fullTenMatchdayDemandIsMaterializedAndBudgetExhaustionCannotCompleteWork
```

| Observed check | Before, source f8a22bc | After, scoped pool |
| --- | ---: | ---: |
| JUnit method time | 29.212s | 4.388s |
| JUnit class time, including fixture lifecycle | 31.628s | 6.630s |
| Gradle build time | 40s | 18s |
| Tests / failures / errors / skips | 1 / 0 / 0 / 0 | 1 / 0 / 0 / 0 |

This is **one local before/after pair**, not a repeat-sampled benchmark or hosted billing result.
The after build also compiled the changed test; the before build reused compiled classes. The
method-level reduction is observed for this pair, not promised across CI or the whole suite.

Both used cleared/whitelisted environments with dummy provider keys and the guarded disposable
`127.0.0.1:55432/postgres`, 1536 MiB/no-swap user scopes, CPU quota 200%, a 360s timeout and host-
memory guard. No other Gradle/test JVM was active at admission. Preserved receipts:
`/tmp/fpl-ten-matchday-before.haRO6H/{test.log,result.xml}` and
`/tmp/fpl-ten-matchday-after.mUgB6T/{test.log,result.xml}`. The after source file SHA-256 is
`5ed052a0f13c2f717f37a3178520b09f36f6d2e32e552611e397bb4617dd6d4b`.

Parent scope review and `git diff --check` pass. No test-first red was manufactured for a fixture-
overhead optimization whose original behavioral assertions already pass. No full class/suite,
independent panel, hosted CI, production API/DB, push or PR was performed for C2.

## Fourth bounded audit: waiting-fixture fanout and dependency priority

Source `817ccb43581596a5968b46d1a945976dc77df82c`; one candidate:
`OwnedSyncStoreIntegrationTest.prerequisitesAndDependenciesCannotBeStarvedByWaitingFixtureFanout`
(lines 1237–1284). Its test class and `OwnedSyncStore` are byte-identical to the old `4c1fa71`
audit source; the local `master169` report recorded 10.880s for the case. That is historical,
not a fresh local measurement. No new test/build/benchmark was run during this audit.

Retain its actual oracles: two canonical jobs precede 600 histories; dependency-waiting fixtures
follow them; dependency retry preserves its source floor; a released lease cannot retry again;
and an ordinary retry restores fixture priority ahead of the newly added history job.
`acquire` orders the real durable queue by priority/due/generation; `finishAttempt` demotes only
dependency retries and restores the original kind's priority for ordinary retries.

The [September 14 incident record](../STATUS.md#owned-sync--second-staging-exposed-dependency-starvation)
and original fix `bda8010` explain the 39-fixture/600-history shape: production had 39 waiting
fixtures repeatedly selected while 659 histories and two canonical jobs never got a turn.
Shrinking to two rows might check priority ordering, but would no longer replay that fanout.
Do not call the current case pointless or silently discard the scale evidence.

**D1 — next recommended implementation:** apply the same case-local connection-pool pattern as
C2, preserving all counts, ordering, source-floor, stale-lease and priority-restoration checks.
The 600-history section alone performs 600 enqueues, 600 acquisitions and 600 failures, each
through a transaction on `DriverManagerDataSource`: 1,800 acquisitions before fixture setup and
other checks. Reuse connections, not transaction boundaries. This case is serial; the adjacent
`twoWorkersClaimDifferentRunsInDeterministicPriorityOrder` is not. Do not replace the class-wide
datasource or serialize its genuine multi-worker tests with a one-connection shared pool.

Only one unchanged-method baseline and the same method after the scoped edit are needed if D1
is approved, with the existing disposable DB and resource guards. No gain was measured during
this read-only pass. The later approved implementation and observations follow; no whole-test
deletion or further test-family expansion follows from this pass.

## Approved D1 implementation and comparison

The owner approved the same case-local pooling pattern. Only this serial starvation scenario
creates a try-with-resources Hikari pool (maximum one connection, minimum idle zero), backed by
the existing guarded datasource. Local JdbcTemplate, transaction manager and store use it;
class-wide JDBC/store fields and the adjacent multi-worker tests remain unchanged. Every original
loop, transaction boundary and assertion remains, including 39 waiting fixtures, 600 histories,
two canonical jobs, source-floor preservation, stale-lease refusal and ordinary-retry promotion
ahead of history 601. There is no production or dependency change.

One unchanged baseline and one modified run both selected only:

```bash
./gradlew --offline --console=plain test \
  --tests fi.karivanttinen.fplassistant.sync.OwnedSyncStoreIntegrationTest.prerequisitesAndDependenciesCannotBeStarvedByWaitingFixtureFanout
```

| Observed check | Before, source 817ccb4 | After, scoped pool |
| --- | ---: | ---: |
| JUnit method time | 19.313s | 4.297s |
| JUnit class time | 21.335s | 6.311s |
| Gradle build time | 30s | 18s |
| Tests / failures / errors / skips | 1 / 0 / 0 / 0 | 1 / 0 / 0 / 0 |

This is one local comparison pair, not a repeat-sampled result or hosted savings claim. The after
build compiled the changed test; before compilation was up-to-date. Both used the same cleared/
whitelisted dummy-key environment, guarded disposable `127.0.0.1:55432/postgres`, 1536 MiB/no-swap
scope, CPU quota 200%, 360s timeout and host-memory guard. No other Gradle/test JVM was active at
admission. Receipts: `/tmp/fpl-starvation-before.AvX1SA/{test.log,result.xml}` and
`/tmp/fpl-starvation-after.KmfBEL/{test.log,result.xml}`. Tested after-source SHA-256:
`f0449244b7e7012411e8f715b7d3adf5ce6c40848b5c4012b033762f00c9c82e`.

Parent scope review and `git diff --check` pass; the indentation-insensitive diff contains only
the import and scoped pool/JDBC/transaction/store setup. No fake red was generated for this
fixture-only optimization. No full class/suite, multi-worker rerun, independent panel, CI,
production access or remote publication was performed for D1.
