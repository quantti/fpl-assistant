# Behavior-first test audit: research, method and delivery

**PROGRESS:** Five bounded audits and approved changes complete; integrated 20/20 passes; publication/review handoff and remaining scope are tracked in KAR-79.

## Approved outcomes

The owner approved executing the read-only audit after correcting its sync scope limit.
[First-pass decisions](../../diagnostics/2026-10-18-test-audit-first-pass.md) cover the seven
prompt-contract classes and the single importer-startup path below. No unconditional whole-test
removal was justified. The owner subsequently approved trimming the five prompt expectations
and removing only the disconnected mock, keeping the real startup refusal. Commit `83e2413`
implements that cleanup; all 15 cases in the three changed classes pass locally. The default-OFF
consolidation remains conditional. The authorized follow-on audited only five receipt-batching
cases: retain the real-incident regression, with proposals to reduce one discovery fixture from
657 to 501 and replace a prepared-statement count with the existing execution counter. Those
proposals were subsequently approved and implemented. After the other worktree's Gradle run
ended, both changed methods passed locally (2 tests, no failures/errors/skips). Additional
changes and remote publication remain separate decisions. The third audit retains the full-day
92-slot SQL guarantee and proposes case-local connection reuse instead of deleting its scenario;
the source-derived minimum is 2,736 connection acquisitions for repeated daily chunks alone.
The owner approved and implemented a test-local pool, keeping every workload and assertion.
The same method passed before and after: 29.212s → 4.388s in one local comparison pair, not a
hosted CI or whole-suite savings claim. The fourth audit preserves the 39-fixture/600-history
starvation regression and applies the same approved case-local pool: 19.313s → 4.297s for the
same method in one local pair. Concurrency tests and class-wide fixtures remain unchanged.
The fifth audit's compact correction source was also approved, including its explicit full-roster
scale loss: one-line 657→68 selection, unchanged protocol assertions, before/after passes at
16.945s → 5.796s for the same method. No hosted or whole-suite savings are inferred.

## Scope and constraints

Research cutoff: 18 October 2026. Source baseline: merged PR #173, master
`96caea7c123ed64c66a6845b45e2e31234f8345f`.
This began as a proposal, not blanket authorization to delete tests or change CI.

The initial research/planning pass was source-only: no test/build/coverage/mutation runs, CI,
production queries, code changes, commits or publication. Subsequent owner decisions authorized
each delivered slice and targeted local verification, including E1's explicit scale loss.
The later request authorizes publishing this feature branch as a PR, a Linear continuation issue,
full documentation and post-PR review. A subsequent owner instruction explicitly authorizes
immediate squash merge through that PR, with the normal master release workflow. Direct master
pushes, manual deploy, a local full suite, extra test deletion or another audit family are not
part of that authorization. A separate worktree preserves
unrelated changes in other checkouts.

The owner-selected policy remains targeted local backend verification before a code push,
lightweight PR checks, and the retained full backend suite on master before deployment.
Do not add another periodic job, parallel suite, manual heavy-test bucket or reporting framework.
Approvals were staged, not inherited by every new candidate: A1/A2/S1, then B1/B2, then the
individual C2/D1 pools and E1 compact fixture. Publication approval came separately. Research,
implementation receipts and the integrated 20-case verification at `53e5743` are consolidated in
the [delivery report](../../diagnostics/2026-10-18-test-audit-first-pass.md). The published PR and
continuation issue carry the final remote/review state; they do not turn local subset evidence
into a full-suite claim.

**Question:** Which retained tests add distinct, credible defect detection, and which merely
repeat another test, the implementation, or their own mocks?
The goal is less execution/maintenance cost for useful confidence, not a target test count.
Remaining work is tracked in [KAR-79](https://linear.app/quantti/issue/KAR-79/continue-the-backend-test-audit-in-bounded-batches),
which stays open after this publication. It names one next unit and links the actual PR/review
handoff; neither this source inventory nor the PR completes the rest of the audit.

## Recent discussion and evidence

### 1. A very recent concrete deletion: Prisma, 16 October 2026

[Prisma/orm PR #30731](https://github.com/prisma/orm/pull/30731),
“Remove 945 redundant and declaration-restating unit tests”, was merged on 16 October
([public API metadata](https://api.github.com/repos/prisma/orm/pulls/30731)).

The author reports removing 945 `it`/`test` declarations: exact duplicates, declaration-restating
type checks, constant/getter restatements and low-information existence checks. The PR excludes
integration tests and uncertain unreachable-input cases. Importantly, the author rechecked an
older audit against current code and skipped about 90 entries wholly or partly: a proposed
replacement sometimes checked less, or the proposed deletion was the only behavior check.

**Use here:** high-confidence, current-source evidence; exact retained replacement; bounded,
independently revertible batches; remove only helpers made orphaned by the change.
**Limit:** this is the PR author's account, not an independent experiment proving every deletion
safe. Its verification report includes failures/timeouts described as environmental or unchanged.
945 declarations are neither 945 distinct requirements nor a transferable deletion quota.
Do not copy its categories mechanically: this repository's test-helper production-DB guards
are safety checks, not dispensable just because they test a test helper.

### 2. Compose tests instead of repeating every combination: Kent Beck, 10 November 2025

[Composable Tests — dated author summary](https://kentbeck.com/summaries/composable-tests/),
[full essay](https://tidyfirst.substack.com/p/composable-tests).

Beck distinguishes isolation from composition. His example replaces four calculation variants
crossed with five reporting variants (20 tests) with four calculation tests, five reporting tests
and one wiring test (10 tests). This depends on the dimensions being demonstrably orthogonal.
He also prefers removing repeated assertions from a longer test over automatically deleting the
smaller focused test: the latter can preserve useful failure localization.

**Use here:** look for repeated setup/assertion chains and unnecessary Cartesian products;
retain focused diagnostics and actual boundary/wiring checks.
**Limit:** independent dimensions are a premise to establish, not an assumption. FPL season,
position, availability, source freshness and fixture state often interact. Equal expected output
or identical assertion text does not make their cases interchangeable.

### 3. Deletion is legitimate maintenance: André Arko, 30 June 2025

[You should delete tests](https://andre.arko.net/2025/06/30/you-should-delete-tests/),
[2025 Hacker News discussion](https://news.ycombinator.com/item?id=45038074).

Arko argues that confidence, rather than preserving tests, is the point. Concrete warning signs
include one small behavior change forcing edits to many tests, changed requirements leaving
obsolete assertions, and persistent flakiness teaching developers to ignore real failures.
The discussion contains disagreement over unit and integration testing, not a settled rule
that either layer should replace the other.

**Use here:** record maintenance fan-out and whether failures are actionable; reassess old
requirements instead of updating obsolete tests by habit.
**Limit:** this is practitioner opinion. Do not treat anecdotes or commenters' bug-detection
percentages as measurements. A flaky race/timeout case may reveal a real production defect;
flakiness alone does not authorize deleting its unique protection.

### 4. Deletion can discard unique detection: MSR 2025, Java study

[Understanding Test Deletion in Java Applications — conference record](https://2025.msrconf.org/details/msr-2025-technical-papers/24/Understanding-Test-Deletion-in-Java-Applications),
[paper](https://hifromajay.github.io/papers/msr25.pdf), Bhatta, Kendemah and Jha.

The dataset contains 24,431 manually confirmed deleted tests across seven Java projects.
In the six non-CTS projects, 91.4% of 7,326 deletions were obsolete tests associated with removed
production code. This is not evidence that 91.4% of an existing suite is dispensable.

The study initially classifies deleted tests that still pass as “redundant”, then checks that
classification. Of 518 such tests, 103 (20%) reduced line/branch coverage when removed;
most of those were in PMD. Mutation analysis succeeded for 369/518, and 66 reduced mutation
scores; those also reduced coverage. Thus “passing and deleted” did not establish redundancy.

**Use here:** require positive replacement evidence; a deletion label or green surviving suite
is not enough. Neither unchanged line coverage nor shared executed lines proves equal oracles.
**Limit:** these are proxy measurements on a small historical project sample, heavily concentrated
in one project for the losses, not measured production-bug or CI-billing savings for our service.
Mutation setup also failed for 149 candidate tests.

### 5. Agent-generated mocks deserve scrutiny: MSR 2026

[Are Coding Agents Generating Over-Mocked Tests? An Empirical Study](https://arxiv.org/abs/2602.00409),
submitted 30 January 2026, accepted at MSR 2026.

The observational study examines over 1.2 million 2025 commits in 2,168 TypeScript, JavaScript
and Python repositories, including 48,563 agent commits. It finds agents more likely to change
tests and introduce mocks, and calls for explicit mocking guidance.

**Use here:** ask whether a test exercises the real behavior or merely verifies the stubs it
configured. Check whether a mocked dependency hides the actual integration failure of interest.
**Limit:** this is not a Java study, does not prove causation, and does not establish that any
particular mock is harmful. Boundary mocks and interaction assertions can be the correct oracle
for policies such as “quota refusal must not invoke a paid provider”.

### 6. LLM test smells: updated August 2026, TOSEM acceptance July 2026

[On the Diffusion of Test Smells in LLM-Generated Unit Tests](https://arxiv.org/abs/2410.10628v3).
First submitted in 2024; v3 is dated 1 August 2026 and reports TOSEM acceptance on 31 July.
It compares LLM-generated Java suites with human and EvoSuite baselines and finds smells such
as Assertion Roulette and Magic Number Test, with results varying by prompt, context and model.

**Use here:** inspect generated repetition, unclear assertions, coupled expectations and noisy
fixtures instead of treating compilation/coverage as test quality.
**Limit:** the evaluated models include GPT-3.5/GPT-4 and older Mistral variants; this is not a
benchmark of today's agents. A smell is a review signal, not proof of uselessness. Multiple
assertions and explicit numeric business expectations can be valuable.

### 7. Practical oracle checks, without a new execution program

[VS Code: Test existing code with AI](https://code.visualstudio.com/docs/agents/guides/test-code-with-ai)
is living practitioner guidance: identify meaningful behavior, follow existing test conventions,
and check that assertions actually detect incorrect results.

[PIT basic concepts](https://pitest.org/quickstart/basic_concepts/) explains targeted injected
faults, surviving/equivalent mutants and error outcomes.
[PIT FAQ](https://pitest.org/faq/) explicitly describes mutation testing as computationally
expensive and recommends limiting analysis to relevant classes/packages.

**Use here:** write a concrete counterexample before accepting a test's claimed value.
For example, would changing `deadline > asOf` to `deadline >= asOf` escape the retained tests?
**Limit:** this does not propose installing PIT, adding a CI job or running mutations now.
If later approved and static evidence is insufficient, use one bounded local fault experiment
or narrowly scoped mutation check, with a fixed budget. Equivalent mutants, setup failures,
timeouts and memory errors require interpretation; they are not a blanket quality score.

## Repository implications

The previous [pruning plan](2026-10-18-backend-test-pruning.md) already removed the four named
large workloads and seven low-value cases. Do not rediscover them as remaining candidates.
The deliberately dropped combined-scale guarantees remain dropped; smaller tests did not prove
them equivalent. Do not recreate the workloads through mutation, manual or nightly execution.

That plan's baseline was `4c1fa71`: 1,685 executed cases and 838.3 seconds of summed JUnit class
time, with sync contributing 756.9 seconds. These are historical pre-pruning measurements, not
current test counts, wall time, runner-minutes or predicted billing savings. Shared context
startup can also move between classes when a test is deleted.

Separate two audit goals:

- **Execution cost:** old evidence points first to sync fixtures, contexts, loops and driver work.
- **Maintenance cost:** repeated unit/prompt/type assertions may matter even when execution is
  cheap. Removing many of them must not be advertised as a large Actions saving without evidence.

Unit, integration, contract and scale tests are different boundaries, not competing count quotas.
Use the cheapest boundary that detects the relevant defect, retaining a real boundary check when
mocked lower-level tests cannot establish wiring, SQL, transaction or serialization behavior.

A small read-only spot check found `AvailabilityPromptContractTest` explicitly limits itself to
wording and does not call an LLM. Its three cases protect different prompt obligations. It is
not automatically a tautology because it asserts literal text, and it does not prove model
obedience. This is an example of correct claim boundaries, not a deletion recommendation.

## Audit decision rule

Audit behavior groups first; expand only plausible candidates into method-level records. Do not
create a spreadsheet row for every assertion or introduce a scoring framework.

For each candidate, answer:

1. **Contract:** what supported requirement or real boundary does it protect? Follow callers,
   production code, fixture/seed setup, configuration and assertions—not just the test name.
2. **Counterexample:** what plausible wrong implementation would this test reject? Would it
   still pass if the important production behavior were removed? A stubbed scripted model does
   not demonstrate prompt obedience; a provider protocol fake can still test genuine orchestration.
3. **Oracle:** is the expected result grounded in the contract rather than recomputed by the
   same production helper or predetermined by a mock? Hard-coded expected business values can
   be more independent than reusing a production constant/formula.
4. **Distinctness:** which exact retained test detects the same counterexample through the
   necessary boundary? Compare inputs, state, failure mode and assertions. Same lines, output,
   method call or test names do not prove duplication. Preserve independent tests' isolation.
5. **Cost and change burden:** what do existing reports/history actually establish about fixture
   size, repeated cases, Spring context startup, flakiness and edit fan-out? Mark unknown costs
   unknown; do not launch a baseline suite just to fill the table.
6. **Decision:** keep, delete, trim/merge, shrink fixture, or uncertain/retain. State any guarantee
   intentionally lost separately from redundancy. A useful but weak oracle is not an automatic
   deletion; strengthening it would be separately approved implementation scope.

Use a compact record only for actual candidates:

| Test/group | Contract and counterexample | Real/mocked boundary and oracle | Exact retained replacement, or none | Existing cost evidence | Decision, confidence and lost guarantee |
| --- | --- | --- | --- | --- | --- |

An independently valuable boundary or requirement blocks a “redundant” deletion. Uncertain
candidates stay. There is no minimum number of findings or deletions.

### Default-retain protections

Do not remove these merely to lower counts or because a mock/assertion looks stylistically noisy:

- Account isolation, JWT-to-tool context, admin refusal, RLS and production-target write guards,
  including `TapasSeedSafetyTest` and table-name guards in test setup/cleanup.
- Quota/deadline strictness, off-season/changeover windows, no paid call after refusal.
- Current-vs-historical attribution, stale/missing capture refusal, unknown-vs-zero behavior,
  cross-season identity and the actual season/position/fixture-state interactions.
- Publication transactionality, ownership/evidence/epoch fencing, restart/correction behavior,
  and no publication or reader leakage through unverified data.
- Necessary SQL/HTTP/serialization/wiring contracts which a mocked unit test cannot establish.

These are review priorities, not permanent immunity from demonstrated exact duplication.

## Proposed bounded audit sequence

1. **Read-only baseline refresh.** Index current test families and supported production entry
   paths from the pinned revision. Reuse existing timings only where the source still matches;
   label old data. Distinguish methods, parameterized executions, fixtures and helpers. Deliver
   a short family map, not a new inventory tool, coverage report or full-suite run.
2. **One small pilot.** Inspect one coherent family and at most ten plausible candidates,
   including callers and fixtures. Start with smoke/identity/existence/duplicated-assertion
   candidates if present; do not force a finding. In chat, distinguish prompt wording contracts,
   actual service/tool orchestration, and scripted replies that only restate the fake's choices.
   Deliver the decision records above, with exact retained alternatives and unresolved risks.
3. **One sync path only, then report.** This pass is limited to importer startup and retired-runtime
   exclusion: `CoalescedPlayerHistoryImportConfiguration` and its startup assertions in
   `OwnedSyncImporterContextIntegrationTest`, reading immediate configuration dependencies only
   as needed. Do not expand into collector, publication, recovery or rollback scenario audits.
   Keep activation fail-closed guards; OFF by default does not mean obsolete. Return the pilot
   and this single path's decisions before any further sync path, even if no deletion is justified.
   Another path or audit batch requires a separate owner decision; previously removed costly
   cases remain out of scope.
4. **Propose a first deletion/simplification batch.** High-confidence candidates only, one
   concern, no production refactor, no new dependencies and no expansion of CI. State expected
   maintenance benefit separately from measured execution savings. List any genuinely dropped
   guarantee for explicit owner acceptance; do not conceal it as deduplication.
5. **Stop for implementation/verification approval.** Reading is not a verified code change.
   Any later edits need their own bounded local checks and review under the existing resource
   policy; do not run the full suite locally or generate new hosted runs for audit experiments.
   A green retained suite alone would not prove deletion equivalence. Commit/push/PR publication
   is a separate step, not part of this research document.

## Acceptance for this planning pass

- Recent original sources are linked, dated where known, and separated from opinions and proxy
  measurements. Unretrieved articles and search-summary-only claims are not used as evidence.
- The audit can accept zero deletions; every future proposed redundancy has a named retained
  oracle, while intentional loss of a guarantee needs a separate decision.
- Previously removed tests are not counted again, and historical timings are not called current.
- No tests, builds, coverage/mutation runs, hosted workflows, production accesses or remote
  publication were performed for this research/planning pass.
