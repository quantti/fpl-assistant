# Backend test audit and bounded pruning plan

**PROGRESS:** Task 1/5 done — lightweight PR/full-master routing implemented; the four large workloads will be deleted entirely, not made manual.

## Owner decision and scope

On 18 October 2026 the owner chose **local targeted backend tests before push, lightweight PR
checks, and the full retained backend correctness suite on master before deployment**. A full
remote backend suite on every PR is no longer the desired policy. Apply that decision in the
Actions budget plan and, during implementation, update AGENTS/DEVELOPMENT/workflow instructions
together. A green lightweight PR check must never be described as a full backend pass.

The owner subsequently instructed that the four named large workloads be removed entirely for
now, not retained behind tags or a manual task. Their former scale guarantees are intentionally
not part of the retained suite; Git history is the recovery path if needed later.

This audit is against `4c1fa71`. It indexes the entire test tree and the complete latest JUnit
report, then examines the costly scenarios, cheap smoke tests, their overlapping assertions and
selected production paths in detail. It is not a claim to have read every line of all fixture,
fake-server and driver helpers or to have proved every test redundant. No local full suite, live
model call, production query or new CI run was used.

## Inventory and measured concentration

- 189 Java files including helpers, **68,988 source lines**.
- Latest successful master sample: **177 executed test classes / 1,685 test cases**, about
  **838.3 seconds** of JUnit class time. Annotation-method counts differ from executed cases
  because repetitions/parameterized cases expand and one real-data test is excluded by policy.
- 66 source files use `@SpringBootTest`; 93 reference JDBC. These counts are descriptive, not
  reasons to delete tests or promise equivalent startup savings.

| Package/family | Java files, including helpers | Source lines | Latest JUnit class seconds |
| --- | ---: | ---: | ---: |
| `sync` | 42 | 40,394 | **756.9** |
| `sync.source` | 2 | 570 | <0.1 |
| `squad` | 15 | 3,849 | 14.9 |
| `rag` | 5 | 750 | 14.6 |
| `chat` | 19 | 4,795 | 12.5 |
| `tools` | 31 | 7,454 | 9.4 |
| `user` | 12 | 2,257 | 5.5 |
| `myteam` | 10 | 2,550 | 3.5 |
| `config` | 6 | 849 | 3.2 |
| Other packages/helpers | 47 | 5,520 | ~17.8 |

**About 90% of test time is in sync.** Deleting hundreds of small domain/auth/formatting cases
would mostly cut confidence and maintenance surface, not this runner bill. Count reduction is
not the acceptance metric. There are nevertheless real low-value tests and oversized workloads.

## A. Remove low-value/duplicate tests first

These are concrete candidates, not a blanket instruction to remove all unit or integration tests.
Together the first six rows remove seven executed cases, not hundreds.

| Candidate | Why it adds little | Existing evidence/guard to retain |
| --- | --- | --- |
| `DbConnectionTest.canQueryCurrentSeason` | Queries the row its own `TapasSeed.ensure` created and checks the seed's constant. Tests the fixture/bootstrap, not season fallback behavior. | Keep `SeasonResolverGuardTest`, real player/ranking integration reads and the production-target guard in `TapasSeed` / `TapasSeedSafetyTest`. |
| `AiConfigTest.chatClientBeanCreated` | Only asserts an autowired bean is non-null. Full Spring contexts already instantiate the non-lazy bean and fail when configuration cannot construct it. | Keep actual chat service/tool registration and provider protocol/usage tests. Do not replace this with a new generic context-load smoke test. |
| Two tests in `OjAlgoSmokeTest` | Exercise ojAlgo's own binary optimizer API on a toy model, without calling the project's solver. Useful during library exploration, redundant once the real solver has feasible/infeasible cases. | `SquadSolverRulesTest.everyResultObeysEveryRuleOfTheGame`, its infeasible-budget case, and domain constraint tests exercise the real library through `SquadSolver`. |
| `ScheduleSchemaTest.bothTablesExistWithRowLevelSecurityOn` | Table existence is required by schedule writer integration tests; the global RLS test already checks every owned existing table. | **Before deleting:** keep its `_test` table-name guards in the remaining write integration test before any write. Keep `RowLevelSecurityTest` and `ScheduleWriteIntegrationTest`; do not drop RLS or destructive-write safeguards. |
| `WildcardCoveragePromptContractTest.scriptedChallengeRequiresANewSuccessfulSquadToolResultBeforeRevisedFifteen` | The `ScriptedModel` returns a predefined sequence without interpreting the prompt. The test hard-codes two tool calls and a revised-squad answer, then verifies that they occurred. It does not establish that an LLM obeys the refusal/recheck rule. | Keep the class's direct prompt contract. Tool-call registration/history and actual tool-loop instrumentation are already checked in `ChatServiceTest` / `ChatTraceInstrumentationTest`; keep `SquadTools` coverage-refusal tests. Remove this case and its now-unused script/helper code, not the underlying behavior checks. |
| `OwnedMatchdayEndToEndIntegrationTest.fixtureOverdueMessageMatcherRejectsNearMisses` plus its test-only matching machinery | Tests a custom JSON predicate used only to recognize this test's notification. The production sender already emits the persisted `payload::text` unchanged. | First directly assert the parsed persisted payload's event, incident key, category, season and root/detail fixture identity, then find its exact string in `delivered`, retaining bounded batch draining and the alert's `DELIVERED` state check. Then remove the predicate, fake notification builder, near-miss case and orphan imports/constants. Keep production watchdog tests. |

The schedule candidate is conditional on moving its safety assertions. The notification candidate
is conditional on simplifying its oracle, not simply deleting the helper's test and trusting a
complicated untested predicate. These are maintenance simplifications; no large minute saving is
claimed. Removing `DbConnectionTest` may move shared context startup time onto another class.

### Trim a small randomized loop, not the domain checks

`SquadSolverRulesTest.everyResultObeysEveryRuleOfTheGame` uses `@RepeatedTest(20)` with
`System.nanoTime()` as the seed. It checks the same invariants twenty times with changing pools;
the whole class was about four seconds in the latest sample. Replace the twenty random CI
repetitions with **3–5 deliberately chosen fixed pools/seeds**, while retaining the explicit
budget, formation, club-limit, ineligible-starter, pin and blank-fixture tests. Put broad fuzzing
behind an explicit manual invocation only if it is actually useful. This removes 15–17 default
cases and makes failures easier to reproduce, but is not a major Actions saving.

## B. Delete the four named large workloads entirely

**Owner-approved scope:** delete the four cases below, the warm-start-only class, and helpers/
fixtures made unused solely by those deletions. Do not add acceptance tags, an opt-in task,
nightly execution or replacement full-size tests. Keep unrelated cases and shared fixtures.

The former scale/combined-workload guarantees below are intentionally dropped for now. Do not
claim that smaller tests prove them. Inspect the surviving compact correctness regressions before
removal; flag any essential production-safety gap instead of silently recreating the large tests.

| Scenario | Latest testcase seconds | What to keep in default correctness CI | Guarantee intentionally dropped for now |
| --- | ---: | --- | --- |
| `OwnedMatchdayAdmissionProgressIntegrationTest.combinedActiveRecoveryPublishesAllTenNeverPublishedFixturesAfterRestart` | **248.7** | Keep the compact same-class restart/receipt case, `scheduleCorrectionCanRepublishOnlyAfterTwoFreshCoordinatorBatches`, actual end-to-end publication/reader masking, per-fixture recovery-admission guards, current-parent selection and store fairness tests. Transfer any unique fail-closed assertion to one of those small cases before excluding the composite. | The combined 40-published + 10-unpublished fixture workload, 48-hour virtual recovery envelope, aggregate request/dispatch ceilings and all-ten combined outcomes. Existing small tests do **not** already establish those scale guarantees. |
| `OwnedMatchdayWarmStartPressureIntegrationTest.fullSizeWarmStartCompletesCanonicalAndHistoryStagingWithoutStarvation` | **48.8** | Keep bounded ready/blocked/untouched queue progress and source-complete publication checks. | 657-player/40-fixture warm-start throughput and three retained snapshots over ten rounds, with JVM/RSS/explicit-GC diagnostics. It starts a full random-port Spring context mostly for the memory baseline, while constructing the tested coordinator/DB collaborators itself. This is a benchmark/acceptance probe, not a necessary default check on every app change. |
| `OwnedMatchdayAdmissionProgressIntegrationTest.fullRosterTwoWorkersStageSourceCompleteFixtureWithinBudget` | **32.7** | Keep `twoWorkerFixtureFanoutDoesNotStarveDueHistories` (both arrival orders), cold-parent continuation, ready/matured-ready continuation, valid participant manifests and body-hydration/floor/epoch guard tests. | Full 657-player population, 50–86 participants per fixture, admission/dispatch throughput and 70 MB body-read envelope under that workload. Those former resource bounds are deliberately not checked after removal. |
| `OwnedMatchdayAdmissionProgressIntegrationTest.finiteDrainStagesAllPinnedFixturesAndHistoryDependencies` | **22.1** | Keep completion of a compact actual pipeline, exact manifest tests and queue fairness. | Draining all 50 pinned fixtures and every dependent history under the configured 2-hour virtual / 300-second wall / admission budget. Small success is not proof of the full finite drain. |

Their testcase time totals **352.2 seconds**, about **42%** of the latest 838.3-second class total;
including warm-start class setup/cleanup the opportunity is about 355 seconds. The same four
methods took 347.4 seconds in PR #164 and 438.0 seconds in its master run. This is consistent
concentration, not an extrapolation that all 91 earlier CI jobs had today's test suite.

### Required retained coverage before deleting the composite

For every removed default assertion, mark one of these outcomes explicitly:

1. Retained correctness regression: exact smaller test named and its assertion read.
2. New compact correctness assertion: added to an existing case, not another giant end-to-end copy.
3. Deliberately dropped scale/combined-workload guarantee, accepted by the owner's explicit
   removal instruction; no replacement manual execution is implied.

In particular, keep rollback on publication/evidence failure, stale generation/lease/epoch
fencing, exact source intervals, real two-observation stability, optional-component unknown-vs-zero
masking, retained ownership on revocation, and one actual restart → publish → reader path in default
CI. Do not delete the four tests until that mapping is checked. `FixtureSyncPublisherIntegrationTest`
already has targeted durable-first-observation, two-read restart, rollback and optional-mask cases;
`OwnedMatchdayEndToEndIntegrationTest.fakeMultiMatchdaySurvivesPartialHistoryRestartAndCorrectionWithoutFalseCoverage`
exercises the joined real pipeline. These are retained, not replaced by mocks.

Update current operations/plan references that cite a removed case as an ongoing release gate;
historical evidence remains historical, not a current test claim. Future queue, hydration or
publication work that needs the dropped full-size guarantees requires a new explicit validation
decision. Do not resurrect the retired VPS or create automatic replacement workloads.

## C. Shrink or speed meaningful tests rather than discarding their behavior

### 1. Runtime scheduling: remove the real one-minute wait

`OwnedMatchdayRuntimeTest.plannerStartsImmediatelyThenWaitsOneMinuteWhileWorkersContinue`
waits 5.5 seconds to rule out an early tick, then waits for the actual one-minute planner interval.
The class is consistently about 62.7 seconds. Use a controllable scheduling seam and separately
assert the real production interval is one minute. Preserve immediate start, no early tick,
continued workers and shutdown/fail-closed behavior. Do not change the production interval or
remove a timeout assertion just to make the test green. Target the timer test at <3 seconds;
that is a future measurement, not a demonstrated saving yet.

### 2. The collector's oversized cold roster

`CoalescedPlayerHistoryCollectorIntegrationTest.checkedTenFixtureGameweekResumesNoopsAndCorrectsOnlyTheChangedRow`
takes **38.4 seconds**, about 37% of that class's 104.8 seconds. The case proves valuable behavior:
partial page failure → resumed finality → no-op → one-row correction → no-op with exact write
counters and source intervals. Keep that state sequence. It currently selects 657 catalogue
players for 40 actual participants, and repeats full pipeline stages.

Make its valid catalogue contain the participants actually needed plus a deliberate unrelated
player; retain the schedule/club/event shape required by the parser and the multiple-fixture
unchanged-row checks. Do not weaken finality population requirements or silently claim the smaller
input is full-league testing. The independent recorded-league collector case already exercises
657 catalogue players/660 selected identities and no-refetch behavior in about 9.7 seconds.
Retain that one full-roster smoke unless there is an explicit reason to move its guarantee too.
The smaller current-safety regressions for missing pages, conflicting appearances, reassigned
fixtures, source revisions and atomic failure remain in default CI.

### 3. Eliminate avoidable test-harness cost before cutting more regressions

The collector integration and older end-to-end class use `DriverManagerDataSource`; many queries
outside transactions open fresh physical connections. Other test harnesses already use bounded
Hikari pools. A small pooled-data-source trial may reduce runtime without removing tests, but
has not been benchmarked. Preserve `TapasSeed.openDisposableTestJdbc` preflight-before-construction,
close the pool, and retain explicit independent connections in actual locking/race tests. Do not
turn this into a new generic test framework or increase Gradle parallelism/context cache limits.

## D. Keep: similar-looking tests that cover different failure boundaries

- Store fairness vs dispatcher/admission vs published-reader tests are different boundaries.
  For example `readyFixtureSelectionUsesDueTimeBeforePriority` calls normal acquisition whereas
  `fairReadyFixtureClaimsUseDueTimeBeforePriority` calls the fair acquisition path. Same rule,
  different entry points; not duplicate coverage merely because their names match.
- HTTP unknown outcome, delayed commit acknowledgement, blocked rollback/JDBC close and actual
  Hikari exhaustion are not interchangeable. They prevent an uncertain attempt from opening
  another paid/external request or writing after losing its fence. Retain the targeted cases;
  the HTTP test class is about 31.7 seconds, not the four-minute composite workload.
- JWT/admin/user-id isolation, invite redemption races, quota accounting, rate-limit header
  spoofing and RLS are not low-value CRUD boilerplate. Config/user/my-team tests together are only
  about twelve seconds in this sample; do not trade their protection for an insignificant saving.
- NULL vs zero, historical vs current season, capture cutovers, missing fixtures and live identity
  conflicts are distinct domain states with known failure history. Parameterizing cases can
  simplify source, but it does not reduce executed cases/time by itself. Keep their assertions.
- Provider usage-round-trip tests with a scripted server/model can legitimately test token
  aggregation and framework tool-loop plumbing. They are not all tautologies like the singled-out
  scripted *decision* case. Do not delete the whole provider family.
- `HistoricalToolsOutcomeTest` is tiny but explicitly checks loss as well as win/draw; the inspected
  integration result only explicitly asserts win/draw. It is not proven redundant by that test.
- Real-data tests are already excluded. Do not count excluding them again as a saving.

## E. Optional later cleanup, not this budget's main lever

RAG ingestion integration repeatedly calls real OpenAI embeddings for metadata/replacement/
pre-delete validation cases. Most of those assertions concern chunking and stored metadata, not
embedding-provider behavior. A deterministic embedding double can keep DB/vector integration
coverage and make those cases offline, with at most one opt-in real-provider smoke if desired.
The whole RAG family is about 14.6 seconds: useful reliability/paid-provider cleanup, not the
main Actions saving. Preserve validation-before-truncation and `_test` isolation. Do not add a
new semantic fake embedding framework or claim a stub proves the external provider contract.

## Delivery order and checks

1. **PR gate first:** implement the owner's lightweight PR / full-master decision. No production
   behavior changes. Workflow/job routing and its assertions must prove that PRs cannot deploy,
   all code-bearing master changes run the full retained default suite, manual redeploy also runs
   the master gate, and deploy still requires its successful result. Keep docs-only no-op handling
   and no deploy for docs-only changes, as an explicit exception rather than a fake full pass.
2. **Small deletions:** remove the seven listed low-value cases with the two specified safety/oracle
   transfers; trim the random solver repetitions. Run only affected local tests and review the diff.
3. **Delete the four workloads:** preserve the named compact safety regressions, delete the four
   cases and only their newly orphaned support code/data. Check compilation and affected retained
   cases locally; do not spend the remaining monthly budget on repeated full CI experiments.
4. **Speed retained tests:** remove the real timer wait and compact the collector roster, one change
   at a time. Pooling is optional only after measuring the smaller changes.
5. **Master confirmation:** one full retained default backend run on the final integrated master
   is the release gate. Record cases removed and assertions retained/transferred separately;
   there are no cases moved to manual acceptance. A lower count alone is not proof of equal safety.

PR backend-stage wall time in the month sample was **833.4 minutes**. If the 66 PR jobs instead
finished in 2–3 billed minutes each, the 943-minute PR workload would become about 132–198 minutes:
**745–811 minutes saved** before any test pruning. That is a scenario estimate and includes the
cost of retained lightweight PR work; it is not a promise of exactly two minutes per job.
The master gate stays. No tested-tree receipt/artifact mechanism for skipping master verification
is needed under this owner's policy.

Deleting the four workloads offers roughly six minutes per present-day full suite; removing the
real timer wait adds about one more minute. These numbers concern the existing sample's test
execution, not a guaranteed reduction of the same size in billed runner minutes. Compile/setup,
shared-context attribution and the smaller roster need measured before/after results. Savings
from PR removal and per-suite cuts overlap: do not multiply default-test savings by PR jobs that
will no longer run them.

## Audit limitations and evidence

Detailed review covered the named heavy test bodies and competing compact cases, selected shared
fixture/oracle paths, bean/DB/schema/library smoke cases, solver rule/constraint entries, schedule
writer/RLS checks, prompt script/tool-loop behavior, and the relevant watchdog payload-read/send
path. Most remaining cases were inventoried by method/family and existing JUnit outcome/time,
not individually re-proved against their full production call graph. No coverage or mutation
measurement was run; this report does not authorize deleting unnamed tests.

Evidence: JUnit artifacts for runs `39023107516`, `39024163381`, `39136986856`; source files named
above; indexed inventory and scripts in `/tmp/fpl-actions-usage-20261018/` (temporary local
research, not durable publication). The earlier Actions budget plan contains billing caveats and
source links. No tests were deleted, skipped, run, committed, pushed or merged by this audit.
