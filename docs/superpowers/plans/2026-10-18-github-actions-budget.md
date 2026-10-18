# GitHub Actions budget: measured use and reduction plan

**PROGRESS:** Task 3/5 done — CI routing, four workload deletions and small-test pruning verified; retained-test speedups and final review pending.

## Goal and scope

Reduce this project's use of the account's shared Actions allowance without silently removing
release verification. This is a plan, not a workflow change or authorization to run production
commands. Research snapshot: **18 October 2026, 15:45 UTC**; calendar-month runs created
1–18 October. Source checkout: `4c1fa71`.

**Owner decision, 18 October:** no full remote backend suite on PRs. Backend changes receive
local targeted verification before push; master runs the full retained default suite before
deployment. This supersedes the initial proposal to keep full CI on ready PRs. The detailed
[test-pruning audit](2026-10-18-backend-test-pruning.md) separates genuinely redundant tests,
explicitly deleted large workloads and correctness checks that stay.

First acceptance target: measure at least a 25% reduction in runner minutes per comparable set
of changes. That alone may not keep the account under quota. If the current activity continued
and the allowance is 2,000, a working monthly budget is **1,200 FPL + 700 other projects + 100
contingency minutes**. This implies roughly halving FPL's extrapolated use; stages 1–3 are not
promised to achieve that on their own. These are budget targets, not performance claims.

## What consumes the allowance

According to [GitHub's billing documentation](https://docs.github.com/en/billing/concepts/product-billing/github-actions)
and [job execution accounting](https://docs.github.com/en/actions/how-tos/monitor-workflows/view-job-execution-time):

- Standard GitHub-hosted runners in **private repositories** consume the repository owner's
  allowance, shared across that owner's repositories. Free currently includes 2,000 minutes/month;
  Pro includes 3,000. The actual account plan was not returned by the available API token.
- Count **runner time per job, rounded up to a minute**, not workflows, steps or action calls.
  Container startup, compilation, tests, cache transfer, artifact upload and waiting inside a
  running deploy job all use runner time. Parallel jobs add minutes; shorter elapsed time alone
  is not a saving. Queued jobs that never receive a runner do not use execution minutes.
- Failed, cancelled-after-start and repeated jobs still consume the time they used. Cancellation
  prevents future consumption; it does not refund completed work. Skipped jobs do not allocate
  runners. Standard public-repository jobs and self-hosted execution do not consume this quota.
- Larger hosted runners have separate paid pricing. All allocated FPL and kurssit-pro jobs in this
  sample used `ubuntu-latest`; there is no macOS/Windows/larger-runner mix to optimize here.
- Artifact/Packages storage is a separate shared allowance, billed by accumulated GB-hours.
  Workflow logs and job summaries are not artifact-storage usage. Cache storage is separate,
  currently 10 GB/repository included. Deleting artifacts does not recover execution minutes or
  erase storage already accrued this month.
- GitHub also documents Actions usage by Copilot code review on private repositories. No separate
  consumption of that kind was identified here; the billing report is needed to rule it out.
- Fly compute/build charges, Supabase traffic and OpenAI embedding charges are separate bills.
  A Fly remote build still consumes GitHub minutes while its Actions job waits for it.

### Measurement limits

The account billing endpoint `GET /users/quantti/settings/billing/usage?year=2026&month=10`
returned 404 with a missing `user`-scope diagnostic. No authentication scopes were changed.
The run-timing endpoint returned zero `billable.total_ms` even for a successful test/deploy run;
those zeros are **not evidence that execution was free**.

The figures below are reconstructed execution-minute estimates: collect every job attempt with
`GET /repos/{owner}/{repo}/actions/runs/{id}/jobs?filter=all&per_page=100`, include completed jobs
that actually received a runner, and sum `ceil((completed_at-started_at)/60)` per job. One cancelled
job had 902 seconds between timestamps but no runner and no steps; it was excluded as queued,
not billed execution. Pagination and job counts were checked. None of the sampled jobs was still
running at the snapshot. These estimates are not GitHub's authoritative invoice, account-plan
confirmation or a complete history of deleted runs.

## Where this account's time goes

All 30 accessible owned repositories were inventoried; nine were private. Only two private
repositories had Actions runs created in this calendar-month window. Public-repository standard
runner executions were not added to the private-repository allowance estimate.

| Private repository | Workflow runs | Estimated Linux job-minutes | Share |
| --- | ---: | ---: | ---: |
| `quantti/fpl-assistant` | 181 | **1,417** | **77.4%** |
| `quantti/kurssit-pro` | 46 | **413** | **22.6%** |
| Other seven accessible private owned repositories | 0 | 0 | 0% |
| **Total** | **227** | **1,830** | **100%** |

Thus this is mostly FPL, but the other project's 413 minutes matter. If the account is still on
Free and calendar-month accounting applies, the estimate is already **91.5% of 2,000**, with
about 170 minutes left, not 400: roughly four more 40-minute PR/master pairs, before other usage.
The reported 80% warning may predate subsequent runs; its exact
meter, plan, billing period and remaining allowance must be checked in Billing & licensing → Usage.
A simple 18-day linear extrapolation is about 3,150 account minutes/month (FPL 2,440; kurssit-pro
710), not a reliable forecast of development activity. At that activity level the whole account
would need about a 37% reduction to fit 2,000; a 25% FPL-only improvement would not suffice. Do not spend the estimated remaining allowance on this plan's full-suite
experiments without first checking the authoritative remaining budget.

`kurssit-pro` has 41 push-triggered runs and five PR runs. Its job-minutes split is 371 push / 42 PR;
47 allocated jobs include a repeated attempt. Its aggregate step time is dominated by the older
`Backend tests (live MinIO cases excluded)` step: about 234 minutes. Web tests account for about
37, lint 52, and typecheck 15. This is evidence for a separate repository-specific follow-up, not
permission to edit that project's CI or assume its present workflow still has those older costs.

### FPL triggers and jobs

The current workflows are `.github/workflows/deploy.yml` and
`.github/workflows/owned-sync-heartbeat.yml`.

| Trigger | Runs | Estimated minutes |
| --- | ---: | ---: |
| PR open/update/reopen | 66 | **943** |
| Push to master: tests, then deployment when tests pass | 25 | **474** |
| Scheduled external heartbeat, every job skipped | 90 | **0** |
| **Total** | **181** | **1,417** |

The 91 test-and-deploy runs allocated 91 test jobs across attempts (**1,349 minutes**) and 22
deploy jobs (**68 minutes**). One never-started cancelled test job and all skipped deploy/heartbeat
jobs were excluded. Of the 474 master-triggered minutes, **406** went to tests and **68** to deploy.
Median PR-run estimate: 12.5 minutes; median master-run estimate: 19 minutes. Scope and suite size
changed during the month, so these are not a controlled benchmark.

| Run's final conclusion | Runs | Estimated minutes, including its attempts |
| --- | ---: | ---: |
| Success | 69 | 1,184 |
| Failure | 11 | 160 |
| Cancelled | 11 | 73 |
| Skipped | 90 | 0 |

Failed/cancelled runs together used **233 minutes (16.4%)**. This is a cost to reduce with better
preflight and fewer superseded pushes, not a reason to suppress failing checks. A failed earlier
attempt inside an ultimately successful run is included under success, not lost from the total.

### FPL steps: backend tests dominate

These are **unrounded step wall-minutes**, not extra amounts to add to job-minute billing:

| Step | Aggregate minutes |
| --- | ---: |
| Backend suite (`./gradlew test`, including compilation) | **1,196.7** |
| Fly deploy command | 55.1 |
| Postgres/container initialization | 28.3 |
| Frontend lint and format | 24.7 |
| Frontend dependency install and build | 19.9 |
| Gradle setup/cache step | 9.4 |
| Test-result artifact upload | 2.6 |

Backend execution is about **88% of allocated runner wall time**. Deleting artifacts, changing
upload compression or optimizing frontend setup is not the main minute-saving opportunity.

### Concrete avoidable work

1. **Documentation-only runs:** checking the changed files of master commits and historical PR
   heads found 14 docs-only runs using **152 minutes**. Their files were confined to `docs/**`
   or root Markdown. This includes PR #169: 3 + 20 PR minutes and 19 master minutes = **42 minutes**
   for rollout documentation. Four docs-only master runs account for 49 minutes, including
   unnecessary deployments. This is a counterfactual opportunity, not a refund.
2. **Our prompt fix:** [PR #164](https://github.com/quantti/fpl-assistant/pull/164) consumed
   [17 PR minutes](https://github.com/quantti/fpl-assistant/actions/runs/39023107516) and
   [23 master minutes](https://github.com/quantti/fpl-assistant/actions/runs/39024163381), **40 total**,
   although its local prompt-contract checks finished in seconds. A prompt change can still affect
   application behavior; do not automatically classify it as documentation.
3. **The measured workflow runs the whole suite on every PR update, including drafts.**
   Concurrency already cancels older runs of the same PR (`test-${{ github.ref }}`). It does not recover their consumed time and deliberately
   does not cancel production deploys. Separate PR/master refs also run independently.
4. **At the measured baseline, both PR and master run the full suite.** The owner has now
   explicitly chosen to remove the PR backend suite, retain local targeted verification before
   push and keep the full master release gate. The measured PR backend step used 833.4 minutes.

### Which tests are expensive

Downloaded JUnit artifacts from PR #164, its master run, and master #169. All passed (1,664 /
1,664 / 1,685 tests). The last run recorded about **838 seconds** in test-class execution:

| Class | Latest sample seconds | Earlier two samples |
| --- | ---: | ---: |
| `OwnedMatchdayAdmissionProgressIntegrationTest` | **324.5** | 327.2 / 402.6 |
| `CoalescedPlayerHistoryCollectorIntegrationTest` | **104.8** | 103.3 / 131.3 |
| `OwnedMatchdayRuntimeTest` | **62.7** | 62.7 / 62.8 |
| `OwnedMatchdayWarmStartPressureIntegrationTest` | 51.2 | 45.4 / 62.1 |
| `OwnedMatchdayEndToEndIntegrationTest` | 35.1 | 37.8 / 47.2 |

The first three classes consume about **59%** of that sample's test time. The largest individual
method is `combinedActiveRecoveryPublishesAllTenNeverPublishedFixturesAfterRestart`: **248.7s**
in #169, **322.0s** in master #164. This is a real correctness/pressure scenario, not disposable
coverage. Its existing virtual clock does not make its database/driver workload free.

There is also a direct real-time wait in `OwnedMatchdayRuntimeTest`:
`plannerStartsImmediatelyThenWaitsOneMinuteWhileWorkersContinue` starts the runtime, waits
5.5 seconds to ensure no extra tick, then waits for the real one-minute planner tick. The class
costs about a minute on every sample. This is a concrete candidate for controllable scheduling,
while preserving a check that the production cadence remains one minute.

### Storage is not the bottleneck found here

FPL currently reports 80 unexpired test-result artifacts, **76,618,426 bytes (~73.1 MiB)**, retained
for 14 days. Other accessible private owned repositories report no retained Actions artifacts.
FPL has 22 caches, **565,922,018 bytes (~539.7 MiB)**, well below the included 10 GB/repository.
These are current API sizes, not accrued GB-hours or an inventory of GitHub Packages. No artifact,
cache or account setting was deleted/changed. Shrinking retention is optional storage hygiene,
not a solution to this execution-minute warning.

## Ordered implementation plan

### 1. First CI change: lightweight PR checks; full master release gate

- Give PRs a lightweight job without PostgreSQL, Java/Gradle setup or `./gradlew test`. Retain
  cheap repository/shell checks and relevant frontend build/lint/format checks. Neither draft nor
  ready PRs run the full backend suite by default. Changed backend code is tested locally with
  the touched/related test classes before push; record the actual command and tested revision
  in the handoff/PR rather than introducing an attestation database or automatic full-suite hook.
  This is a contributor/process requirement with self-reported evidence: the existing pre-push
  hook does not execute or verify backend tests, and a green PR does not prove they ran locally.
- Keep master backend verification complete for the retained default suite, including mixed,
  build/config and unknown code changes. Deploy still requires its success. Manual redeployment
  also retains full verification, bypassing the docs-only shortcut. Reject manual deployment
  from non-master refs; only `refs/heads/master` may deploy. PRs cannot trigger deployment.
- Keep cancellation of superseded PR checks and deployment serialization. Do not cancel master
  verification when a newer docs-only push arrives. Do not share a concurrency group between
  master test runs: even `cancel-in-progress: false` replaces older pending jobs in the same
  group. Use no master test group (or a unique run-id group) so a green docs no-op cannot
  displace running or pending code-bearing verification; deployment stays serialized separately.
  Group related local
  review fixes before pushing. Do not cancel a production deploy merely to recover minutes.
- Update AGENTS, DEVELOPMENT and workflow comments in the same change: a green PR now means
  lightweight checks passed, **not** that the backend suite passed remotely. A backend defect
  missed locally can turn master red; production must stay on the last good deployment until
  the full gate passes. This is the explicit trade-off the owner selected.
- Confirm the plan, meter, period, actual remaining minutes and paid-overage budget in the billing
  UI before admission of more CI experiments. Do not change payment/auth/spending settings.

**Verification:** bounded routing tests cover PR, draft/ready transition, push to master,
workflow dispatch, failure and superseding push. Assert PRs cannot deploy, any failed/skipped
required master backend result blocks deploy. Check master/non-master dispatch explicitly,
including dispatch after a docs-only commit, and a docs push during code verification. A manual
master run must execute full verification and remain able to redeploy the tested master.
Do not spend another whole backend PR run merely to test the old behavior.

**Saving scenario:** the 66 observed PR runs cost 943 rounded minutes. At 2–3 minutes per
lightweight PR job, this becomes about 132–198: **745–811 minutes saved** in the sample. The
backend step itself accounted for 833.4 unrounded minutes. At the same activity rate the account
would project around 1,760–1,870 minutes/month after this change alone, rather than 3,150.
These are scenario estimates requiring measurement, not guaranteed future usage.

### 2. Cheap docs-only master path, with no deploy

Use a narrow classifier **inside the master test job**, after checkout. For docs-only changes,
skip toolchain setup, application builds and application tests; report a deliberate successful
no-op `test` check. Export whether runtime content changed and suppress deploy for docs-only
master changes. Keeping the current Postgres service for the first version is acceptable: it costs
tens of seconds, but avoids a new always-running classifier job and a premature CI redesign.
A classifier job taking one rounded minute on every run would itself cost about 91 minutes in
this sample; do not ignore that overhead.

Only allow repository documentation (`docs/**` and specifically agreed root Markdown) to take
this path. **Not** `backend/src/main/resources/docs/**`: those files are runtime RAG input. Keep
`.github/**`, scripts, Gradle files, Docker/Fly config, application YAML and unknown paths on the
full master path. Handle deleted/renamed files, mixed changes, shallow checkout and the complete
change range; unknown base, truncated file enumeration or classification failure must select
full master verification, not no-op. Do not rely on a broad `*.md` workflow filter or silently omit required status checks.

**Verification:** classifier regression fixtures for docs-only, code+docs, runtime strategy Markdown,
workflow changes, scripts, rename/delete, missing base and oversized diff; docs PR green no-op;
docs-only master does not deploy; application changes still run the full master gate. Update
AGENTS/DEVELOPMENT to record the explicit docs-only exception to the full-suite policy.

**Saving:** in isolation the historical 152-minute docs workload could fall to roughly 14–28
minutes. This overlaps with the PR saving above. After lightweight PR checks are adopted, the
remaining four docs-only master runs cost 49 minutes: at 1–2 minutes per no-op job, the **additional
saving is about 41–45 minutes**. Keep both estimates separate and measure the service overhead.

### 3. Prune low-value tests and delete the four large workloads

Follow the bounded [backend audit](2026-10-18-backend-test-pruning.md): remove seven concrete
low-value/duplicate cases only after transferring their named guards, reduce the 20 randomized
solver repetitions to 3–5 fixed cases, and delete the four named large workloads entirely,
as subsequently instructed by the owner. Do not retain them behind tags or add manual/nightly
tasks. Those four methods cost 352.2 seconds in the latest sample, about 42% of JUnit class time.
Their former full-scale guarantees are deliberately dropped for now; preserve the named compact
correctness/rollback/publication/read-path regressions and remove only newly orphaned support.

Keep current Gradle heap, worker, fork and context-cache limits. Do not parallelize shared-DB
tests, remove the default safety cases merely because a runtime is inactive, or use the retired
VPS. Admit one bounded local unit at a time and run the full retained gate on master.

**Verification:** record cases deleted and retained; there is no manual acceptance group.
Confirm each lost assertion is either mapped to a compact regression or an explicitly dropped
scale guarantee. Counts going down do not prove retained safety. Check compilation/selection without
repeated full backend CI runs. Do not multiply savings by PR jobs that no longer run the suite.

### 4. Speed retained tests and keep component routing simple

Remove the real minute-long wait from the runtime scheduler test with controllable scheduling,
while separately asserting the real production cadence. Shrink the collector's 657-player
correction/resume fixture without dropping its multi-fixture finality and no-op assertions.
Try bounded pooled test connections only if still worthwhile after measuring those changes.
Retain one independent full-roster collector smoke; do not build a new test framework.

PR checks may route frontend build/lint/format by changed files. Backend local verification stays
explicit and targeted. The full retained default master suite remains the gate for all
code-bearing changes; do not add backend package-dependency selection or a remote focused-test
framework for this policy. Prompt contract tests still do not prove live-model behavior.

**Verification:** repeated focused local regressions and comparable master JUnit timings. Report
CI runner time separately from testcase time, compilation, setup and shared-context attribution.

### 5. Keep master verification; do not build a tested-tree reuse mechanism

The owner chose to remove the PR backend suite, not the master release gate. The 406 historical
master test minutes are therefore **not** an additional saving under this policy. Keep full
verification on the exact integrated master content before deployment, including manual redeploy.
No tested-tree receipt service, cross-workflow artifact attestation or SHA-reuse optimization is
needed. Retain deployment serialization and the external health smoke test. Do not use `[skip ci]`
as an informal substitute for the release gate.

## Explicit non-recommendations

- **No nightly full suite by default:** at 20 minutes, 30 extra nightly runs add about 600 minutes.
  Moving checks from every PR to nightly also changes the release-safety contract.
- **Do not enable the Actions heartbeat:** `*/10` means 144 runner starts/day and about 4,320
  rounded minutes/30 days even for sub-minute checks. All current jobs are skipped; there is no
  measured saving from deleting it. Existing independent Kuma monitoring, not a new Actions
  schedule or the retired VPS, is the documented production-monitoring path.
- **No always-on WSL self-hosted runner as the default fix:** it moves CPU, secrets, availability
  and cleanup onto the constrained development machine. Public exposure of the private repo is
  not an acceptable billing workaround. More shards or larger runners are not proven savings.
- **No blanket test removal, relaxed timeouts or unapproved paid-plan change.** Paid Linux overage
  or a plan upgrade is an owner trade-off after seeing the real budget, not an agent action.
- **No extra remote CI run just to publish this plan.** Planning output is local until approved.

## Acceptance and follow-up evidence

Before/after reports must separate rounded job-minutes, unrounded step/class time, storage and
paid service usage. Compare similar code-change workloads, include every run attempt, and record
what was skipped by policy. Retain a full-master-gate override for unknown classifications.
First implement lightweight PR / full master routing, then the docs exception and the separately
reviewed pruning packets. Measure their effect before further test-framework or runner changes.
The 413-minute kurssit-pro workload needs its own owner-approved plan; this plan edits neither
its workflows nor production behavior.

## Implementation outcomes

Task 1: plans passed the Sol/Astra/Luna panel after correcting stale manual-acceptance wording
and clarifying master dispatch, pending-job cancellation, payload assertions and self-reported
local evidence. Implementation baseline advanced to `757bd13` (unrelated merged features);
the measured cost snapshot above remains `4c1fa71`. The new offline routing regression first
failed on the old PR workflow/full-scope scaffold, then passed: two regression methods with
change-range subcases, plus all 17 existing pre-push shell cases. No GitHub run was started.
PRs now have no Gradle/Postgres job; code-bearing master and manual master verification remain
full, unknown ranges fail closed, docs-only master does not deploy, and non-master refs cannot
deploy. Master tests have no shared concurrency group. Actual hosted-run timing is unverified.

Task 2: deleted the four named workloads, the warm-start-only class/diagnostic launcher and
newly unreferenced private support members (about 2,840 Java lines removed). All twelve other
admission/progress cases remain. Scoped local compilation and 35 retained admission, fixture
publication and end-to-end cases pass (0 failed/skipped), under a 1536 MiB cgroup with no swap
and a 768 MiB host-available stop guard. The shared disposable loopback DB was reused without
reset. Default Docker Desktop was unavailable; the existing `fpl-test-db` was independently
identified on the local daemon at 127.0.0.1:55432. The old `.env.test` port 15432 was refused;
tests used explicit documented disposable credentials and loopback-pinned dummy AI keys.
No workload was moved to a manual/tagged task; former combined scale bounds are dropped.

Task 3: removed the seven named low-value cases and their newly unused script/oracle support;
the four standalone smoke/schema files are gone. Schedule `_test` guards now precede writes
and cleanup (JUnit teardown can run after a failed setup guard). The retained end-to-end case
directly asserts notification event/key/category/season/root and detail fixture identity/status,
then exact-string delivery and persisted DELIVERED state. Solver invariants now use three fixed
seeds instead of twenty random repetitions. All 67 affected/retained schedule, solver, chat,
season-guard and end-to-end cases pass locally (0 failed/skipped), with unchanged scoped resource
limits and disposable loopback DB. This is test pruning, not a fabricated test-first bug fix.

Reproduction sources: GitHub REST run/job/commit/compare/PR-file/artifact/cache metadata, the
three linked/specified JUnit artifacts, and the checked-in workflows/tests. Raw research JSON,
XML and analysis scripts are local under `/tmp/fpl-actions-usage-20261018/` (temporary, not a
shareable or durable artifact). No tests, new Actions runs, production queries, workflow edits,
remote writes, billing changes or credential changes were performed for this research.
