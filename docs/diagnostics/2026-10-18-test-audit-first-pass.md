# First bounded test-audit pass

**PROGRESS:** First bounded audit and owner-approved A1/A2/S1 cleanup complete; 15/15 targeted cases passed.

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
