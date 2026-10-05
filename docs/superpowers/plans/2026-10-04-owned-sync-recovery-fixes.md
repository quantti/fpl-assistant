# Owned-sync recovery fixes

**PROGRESS:** Task 1/4 implemented; focused tests and independent static review passed. PR/full CI gate pending; Tasks 2–4 untouched.

## Goal and evidence

Fix all three diagnosed paths: old pending fixtures blocked until the next daily boundary, published correction work replayed on activation, and repeated anchor-body hydration while histories are missing. Last verified production state is OFF83. ACTIVE80/82 refreshed nine distinct already-VERIFIED fixtures but left GW5 empty. Diagnosis and saved evidence: [egress report](../../OWNED-SYNC-EGRESS-REPORT.md), `/tmp/fpl-active-20261004/`, `/tmp/fpl-active-20261004-second/` and `/tmp/fpl-offline-recovery-diagnosis-20261004/`.

Existing three focused tests characterize components only. They do not reproduce a complete ACTIVE restart or allocate the full observed 500 MB to particular bodies. Implementation must add regressions before changing behavior.

## Decisions proposed for approval

1. **Late recovery is an episode, not one isolated daily attempt.** A source-finished, unverified fixture can start one bounded recovery episode per UK recovery day (03:00 UK to the following 03:00), immediately when eligible and overdue. It may finish the independent-batch verification within that episode instead of being pushed to tomorrow before its first source read. Reuse the existing twelve-hour follow-up limit and retry ladder; do not introduce an arbitrary new duration. This deliberately changes the old one-attempt-per-day behavior for overdue recovery and needs owner approval with implementation. Twelve hours is an internal retry window, **not authorization to run production for twelve hours**.
2. **Published source evidence is the completion record.** Derive completed correction slots from an existing valid ACTIVE publication and its combined source interval, not an in-memory or enqueue ledger. No new correction-completion table is needed.
3. **Metadata before hydration.** A reusable, fenced dependency manifest must be checked before loading its three large anchor bodies on a retry. Do not solve this with a large RAM cache: observed headroom was only about 6.6 MiB above the stop.
4. **No fixture-specific priority.** Preserve canonical preemption, ordinary history FIFO and ready/blocked/untouched turns across the full queue. Do not pin GW5 or change queue weights speculatively; the ACTIVE acceptance test below decides whether the three fixes actually allow unverified backlog progress.

## Invariants

- Never replace `first_finished_observed_at` with activation time or erase actual ownership/verification on OFF.
- Keep exact playing-season, runtime-epoch, key, anchor, effective source-floor and complete participant/history requirements. No source reuse across epochs to hide restart cost.
- Keep two coherent independent batches; the second starts at least ten minutes after the first finishes. A recovery episode changes admission only, not verification.
- Keep HTTP quotas, min spacing, cooldowns/429 handling, unresolved-exchange fencing and transport backoff. No fail-open daily reset or forced HTTP call.
- STAGING, a queued slot, a failed callback, or an incomplete source batch never establishes correction completion. Genuine source changes must still revoke verification and schedule fresh work.
- Only project-owned schema may change. Proposed recovery fields belong to `fpl_fixture_sync`; no Tapas schema alterations, season mutation or legacy-writer reactivation.
- Production remains OFF during implementation. The already-spent observation approvals do not cover another run or continuous operation.

## Task 1 — durable correction completion, then ACTIVE restart regression

### Change

- In `PlannerInputRepository.progress`, replace the unconditional empty `completedCorrections` set with a projection of **current, still-valid fixture publication**.
- Require VERIFIED state, non-null current verified generation, exact season/fixture receipt identity, receipt pointing to that generation, and a completed ACTIVE fixture run. Do not take `MAX(published_at)` across historical generations. A still-valid publication from an earlier runtime epoch remains a completion fact after activation; do not filter it out by the new epoch. This does not permit an old raw source batch to satisfy new work.
- For `2h`, `24h`, `48h`, calculate due instants from the existing `first_verified_at`. A slot is covered only when the publication's combined **source read start is at or after its due instant**. The production publisher's combined interval takes the earliest bootstrap/fixtures/live/history start, not just the write timestamp. Require a known ordered interval with end at or before publication, not future-dated evidence. A later qualifying verification covers all earlier correction slots in that same verification series.
- Apply the same source-time check to the current `data_checked_transition_at`; a later transition is not covered by an older publication.
- Keep activation's epoch fence and planner-slot reset. The ledger records scheduling, not published completion, and preserving it blindly could treat STAGING work as done.
- Use existing persisted receipt/run/source fields. Legacy or incomplete evidence with unknown interval means not complete; no fabricated timestamp or blanket backfill.

### Tests and acceptance

Use the real planner input repository/coordinator/runtime with tiny seeded ACTIVE fixtures and a fixed clock:
- Successful correction publication, ACTIVE→OFF→ACTIVE, then planner tick: no replay solely because of restart, even with an identical fingerprint.
- Merely queued or STAGED correction, failed publication/rollback and partial evidence: still eligible after restart.
- Publication after a due time but a source read before it: not complete. Exact due instant is eligible; newer qualifying source covers older 2h/24h/48h slots.
- New data-checked transition, changed/removed history fingerprint, changed header/stat fingerprint and invalidation: genuine fresh work still queues; old receipt does not suppress it.
- First verification retains the original correction schedule; invalidation/new verification series does not inherit old completion.

**Pass:** restart neither republishes already-covered correction slots nor loses genuinely unfinished/changed work. No body reads added to planner input; bounded metadata SQL, not per-player queries.

**Task 1 outcome (focused verification and independent re-review passed; PR/full CI still required):** correction completion comes from the current same-season fixture receipt and completed ACTIVE run, using its coherent combined source interval. The P1 history-only invalidation gap is also fixed: changed/removed history fingerprints revoke current verification and persist immediate fixture work in the same ACTIVE history-publication transaction; rollback restores fingerprints, fixture state and queue together. Physical PFS/evidence stays retained but is reader-masked until fresh proof. History invalidation retains the paired observation/generation ownership marker with `coherent=false`, preventing pending-first-observation rows from falling back to legacy reads after OFF. The old baseline cannot count as a coherent independent batch. The real ACTIVE→OFF→ACTIVE regression, including an identical post-restart history read and fresh completion, plus rollback and focused planner/history tests pass. No schema or source/budget fence changed; no production runtime action occurred.

**Bounded CI-isolation follow-up (2026-10-05):** CI run 37276350222 had 18 reset failures (12 planner-runtime, six sync-operations) because this integration class seeded the shared `fpl_fixture_sync_test` with a generation reference, then a later class deleted its `fpl_sync_run_test` parent. The test now uses a class-specific `fpl_plan_*` `OwnedSyncTables` prefix and clears its own fixture/run/receipt/planner rows, capture rows and season before reseeding. The focused planner-input class passed 3/3 on guarded localhost:55432; planner-runtime plus sync-operations then passed together, 18/18. This is focused verification only: the complete CI gate has not been rerun and is not claimed green.

## Task 2 — bounded overdue recovery admission, shared with planning

### Change

- Add nullable project-owned metadata on `fpl_fixture_sync`: recovery-day UK date, episode start and episode expiry. Preserve existing attempt, first-finished and source evidence fields. Do not bulk-reset old rows.
- For a source-finished, unverified fixture past its original twelve-hour follow-up window, allow one recovery episode for a recovery-day date when no episode is active and that date has not been consumed. A recovery day is the interval from 03:00 UK on its named date to 03:00 UK the next day, not midnight. Atomically reserve it under the existing fixture row/runtime lock at actual admission, not every planner tick or enqueue.
- Set expiry once to admission time plus the existing twelve-hour limit. OFF→ACTIVE, coalescing, dependency waits, transient failures and midnight do not extend or reopen it. An active episode crossing midnight or the 03:00 boundary remains the same episode; no overlapping daily grants. Retain the consumed date and original start/expiry after publication, closure and invalidation; closure cannot grant another episode for that same recovery day.
- Within that fixed window, retain normal verification follow-ups, including the ten-minute independent-batch wait. Close/stop recovery on publication; expiry without publication returns to daily eligibility, not an endless hourly loop.
- Source invalidity, request-budget deferral and transport failures retain their existing rules. Dependency waits remain non-attempts; actual failures retain the retry ladder. Never overwrite a genuine cooldown/backoff with an earlier catch-up date.
- Distinguish daily policy deferral from dependency/transport retry times explicitly in the policy inputs. Do not infer a transport refusal solely from a mutable `last_error` string or treat every future `next_due_at` as yesterday's bug. Add an owned typed due-reason field for new writes (policy, dependency, transport), and keep the transport deadline separately where a policy decision would otherwise overwrite it. A new field does not reconstruct an old row's cause; unknown legacy reasons use the fallback below.
- **Legacy fallback is deterministic and conservative:** old rows without a typed reason must honor any future existing `next_due_at`, including the observed GW5 next-morning dates. DAILY_ONLY, an empty observation, zero attempts or a mutable error string alone cannot prove policy-only deferral. Do not clear/reinterpret that date or reconstruct a grant from missing history. Once the existing due is reached, atomically admit one new-policy episode rather than shifting it to tomorrow again, provided no later transport deadline, cooldown, budget or unresolved-exchange refusal remains. New typed policy-only rows can use the intended immediate catch-up eligibility; genuine transport dates cannot. This one-time legacy wait is an explicit safety tradeoff, not a claim of immediate pre-boundary GW5 recovery.
- Make `admitAttempt`, `scheduleNextAttempt`, dependency-wait handling and planner recovery/follow-up selection consume the same decision. The planner must not enqueue daily recovery already forbidden until tomorrow, or replace a preserved wait with `clock.now()` on every tick.
- Early normal matchday verification remains unchanged. Finished eligibility comes from validated source/planner evidence, not kickoff age or physical defaults; unfinished/unknown fixtures do not receive catch-up merely because they are old.

### Bounded execution subunits

2a: pure shared policy plus owned metadata/legacy transition and boundary tests. 2b: locked admission/dispatcher and retry integration. 2c: planner/coalescing integration and restart/concurrency verification. Run sequentially with control returned between subunits; do not delegate the whole admission task as one broad implementation.

### Tests and acceptance

- Build legacy policy-only and transport-failure rows through the **old actual write paths**, not by seeding a discriminator absent from production. Both ambiguous future dates are respected. At the old due instant the policy-only case starts an episode, not another next-day deferral; still-active transport/cooldown refusal wins in the other case. Separately, a new typed policy-only overdue row with an unused current recovery-day grant can fetch anchors now. Publication always needs two valid independent observations.
- Existing active episode resumes across OFF→ACTIVE without moving expiry or granting a second daily episode. Publication followed by same-day invalidation retains the consumed recovery-day marker; genuine correction work must remain queued and must not be suppressed by old completion evidence, but publication/invalidation alone cannot create a second recovery grant. Test its applicable admission/backoff behavior explicitly; do not add an undocumented same-day bypass. Expired/consumed episode cannot restart on repeated activation the same UK date.
- Two concurrent workers cannot both grant an episode. UK DST changes, exact start/expiry, midnight crossing and day-boundary cases are deterministic.
- Future real transport backoff, 429 cooldown, quota refusal and unresolved exchange still refuse; no fabricated body/publication or failed-attempt increment for a mere dependency wait.
- Expiry and the next eligible daily boundary agree between planner and dispatcher; no zero-work claim/deferral loop. Preserve normal early verification and source-correction behavior.
- Test both never-verified and previously-invalidated finished fixtures, plus unfinished/unknown-source refusal.

**Pass:** overdue recovery reaches actual source work within the grant, without activation-reset retry storms or weakened publication evidence. Owner-approved recovery policy is encoded explicitly rather than deleting the twelve-hour cutoff.

## Task 3 — fenced metadata retry gate before anchor hydration

### Change

- On fixture retries, read the current manifest and anchor metadata first under the lease/runtime fence. Reuse the existing coherent-anchor/missing-history predicates and effective-floor calculation, rather than hand-writing a weaker second eligibility rule.
- Gate applies only to a manifest for this exact lease generation/season/epoch, target fixture/event, valid bootstrap/fixtures/live identities, and current demand. Include missing/deleted anchors, validation changes and advanced demand in the check.
- If that valid manifest lacks qualifying histories, ensure only its missing history demands at the effective floor, preserve unchanged demand/checkpoints, and persist a dependency wait **without loading bootstrap, fixtures, live or partial history bodies**. Repeated notifications must not advance floors or count failed attempts.
- If no reusable manifest exists or its anchors/demand are obsolete, run the existing fresh-anchor construction/validation path; do not silently wait forever on an impossible manifest.
- Once all history metadata qualifies, hydrate through existing strict rechecking APIs, parse bodies and run the unchanged post-fan-out public recheck and publisher validation. A deletion, invalidation or floor change between readiness and hydration must stop/refetch/requeue, never publish by trusting the earlier metadata snapshot.
- No new whole-fixture payload cache, no lifetime extension for raw retained bodies, no callback outside the runtime fence.

### Tests and acceptance

- First visit builds the manifest; repeated unchanged missing-history retries then perform **zero body queries/zero returned body bytes** and zero redundant HTTP reads.
- Partial history coverage never hydrates a partial history set. Correct histories eventually move the fixture through full hydration and publication, not a permanent wait.
- Pre-floor/wrong-epoch/wrong-season/wrong-key/invalid/deleted history, broken anchor, deleted body and future/advanced floor cases do not qualify. Test races immediately before hydration/publication.
- Compare baseline and fix on the **same dependency-wait work** with realistic synthetic body sizes. Instrument returned body-byte totals separately for own anchors, cross-run histories and canonical pairs; label them logical JDBC body bytes, not billed or TCP bytes.
- Exercise all fixture turns and both history-preference/FIFO lanes. Preserve canonical preemption, complete participant coverage, and admissible independent batches.

**Pass:** the existing blocked-retry case goes from three anchor body reads to zero without changing the eventual verified data or its freshness/coverage floors. No unbounded cache growth.

## Task 4 — combined ACTIVE acceptance and publication gate

- Extend the representative progress driver to **ACTIVE**, not just STAGING: seed 20 clubs/full roster, 50 finished fixtures, 40 previously verified and 10 never published, old daily-only state, and a realistic mix of served/unserved genuine corrections. All data is disposable and seeded.
- Exercise actual ACTIVE→OFF→ACTIVE with retained publication evidence and fixed clocks. Include the observed first- and second-run shapes; keep a bounded virtual-time/claim limit and independent coverage/source-floor oracle.
- Require **10/10 never-published fixtures to obtain receipt-backed ACTIVE verification** within a finite virtual-time/claim bound fixed in the test before running it. Advance the seeded clock to eligible legacy due where applicable; test immediate new-policy admission separately. Report first verification separately, but fewer than 10 complete is a failure, not partial success. Assert source-complete history demands are served, served corrections do not replay, and ordinary FIFO/canonical/ready-blocked-untouched lanes still progress. No pinned fixture or test oracle based on production contents.
- Include two-batch timing, same-fingerprint republication where genuinely due, changed-source correction, rollback/late callback, optional-component masking and read-source ownership behavior. A separately identified pre-existing schedule-invalidation path clears the ownership marker for first-pending observations; fix/test that bounded path before accepting Task 4, just as history invalidation must retain ownership while invalidating its comparison baseline.
- Measure body-read counts/bytes at equal work; report first-new-verification and backlog completion separately. Do not compare unequal publication outcomes as a billing saving or multiply a synthetic rate into a monthly forecast.
- Focused tests locally, one agent/build unit at a time with the checked-in limits. Full suite is GitHub CI on each PR and merge. Independent review must cover ACTIVE admission, completion proof, metadata races and OFF rollback.

**Pass:** all 10/10 initially unpublished fixtures achieve receipt-backed ACTIVE verification within the declared finite bound, with unchanged integrity rules, no correction replay solely on restart, and proven blocked-body savings. If the full-queue test still leaves unverified work behind routine corrections, report the trace and propose a general fairness-preserving work-class adjustment separately; do not sneak in fixture pinning or a production experiment.

## Delivery and rollout

Implement in dependency order **1 → 2 → 3 → 4**, returning control between bounded units. Each unit gets its own tests, verified commit, dedicated feature-branch PR against master and post-push review; no master push or automatic merge. Update this plan's single PROGRESS line once per completed unit.

No production data migration/probe/activation is part of implementing this plan. Review null/legacy metadata transitions in a seeded container. Deploy only after owner-reviewed merge and green workflow, still OFF. A subsequent production observation needs a fresh approved envelope, provider usage/headroom, writer/season/health/monitor preflight and reviewed rollback harness. Retain the192 MiB/400 MB stops and acknowledged Fly-API risk; durable budgeting/API-independent containment and verified alert delivery remain separate prerequisites for continuous operation. Do not choose a larger traffic allowance from synthetic results or reuse a spent harness.
