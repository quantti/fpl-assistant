# Wildcard recommendations must not optimise around missing clubs

**PROGRESS:** Tasks 1–4 implemented and locally verified; Task 5 transferred to the owned-sync plan; Task 6 code/review complete, production release and real-matchday observation pending.

Scope: ranking-data coverage and honest squad advice, not PR #89's header facelift.
PR #89 was reviewed separately with no actionable findings. No Linear identifier has been
assigned to this plan; link an existing relevant item or create one in Kari before implementation.

## Incident and confidence

Read-only production investigation on 2026-09-13 found:

- A 07:47 UTC wildcard request received a squad without Arsenal defenders. The response described
  the replacement defence as more reliable rather than disclosing a data-based Arsenal exclusion.
- The selected schedule capture was `2026-09-13T01:10:00.143324Z`. Sunderland–Arsenal (GW4,
  fixture 36, kickoff September 12 at 19:00 UTC) had `started=true`, `finished=false`.
  No current-season Arsenal PFS rows existed for that fixture.
- Seven elapsed GW4 fixtures were unfinished in that capture, affecting fourteen clubs.
  This is not an Arsenal-specific scoring preference.
- Gabriel's stored current-season GW1–3 minutes were 90/90/90; Calafiori's were 80/90/66.
  Those windows clear the existing minutes floor in isolation. The subsequent pending fixture
  blocks admission despite that history.
- `RankingMetrics.buildCurrentMetrics` assigns `RESULT_PENDING` for an elapsed unfinished
  current-club fixture in the relevant observed window. `MinutesModel` makes incomplete evidence
  ineligible; `SquadSolver.rulesOfTheGame` forbids those players in the XI in every entry path.
- `SquadFormatter` explains the selected players but does not give a complete account of the
  omitted candidate population. Enough other eligible players can produce an apparently normal
  optimum over a severely restricted pool. The chat's follow-up proposed its own replacement
  defence rather than returning a newly verified squad.

These observations explain the code path; they are not a replay of the original tool invocation.
Tool arguments/results were not recovered. Do not claim that the optimiser compared Arsenal's
clean-sheet projection and rejected it, or that the precise API-side cause has been established.
Do not copy account identifiers or conversation bodies into fixtures, logs or this plan.

## What the sync code establishes (not the incident's root cause)

`ScheduleSyncService` copies FPL's `finished` boolean literally and atomically replaces the season
capture; `ScheduleScheduler` runs at 02:10 UK. `ScheduleFreshnessCheck` checks capture date, not
whether elapsed matches or their metric rows are final. A fresh capture can contain pending games.

Tapas owns the core PFS writer. In `~/dev/tapas-fpl-app/backend/scripts/scheduled_update.py`, a
new `events[].data_checked` gameweek gates the collection and fixture refresh. The PFS writes are
in `scripts/collect_points_against.py`, using per-player element-summary history. The checked-in
`backend/crontab` runs at 10:00 UTC; this does not prove the deployed cron is running. The
assistant's 03:00 UK DefCon sync only UPDATEs four columns on existing matching PFS rows. It
cannot fill the missing core rows, and moving that job cannot repair this incident.

## Proposed behaviour and boundaries

Keep the hard current-season minutes rule. Missing is not zero, a pending game is not finished,
previous-season exposure cannot certify a current role, and a pin cannot bypass eligibility.
Do not add an Arsenal quota, inflate clean sheets, silently discard the latest game, or use
Tapas fixture flags to patch the captured schedule.

**Decision proposed for approval:** a general full-squad recommendation must refuse when an
entire club is unavailable for comparison because its relevant match evidence is pending or
absent. Having eleven eligible players elsewhere is not enough. This intentionally reduces
availability during live gameweeks and while Tapas awaits a finalized gameweek. A warning under
an apparently optimal replacement squad is not sufficient for this failure mode. A less strict
product would require a separately approved minutes/evidence policy, not a hidden bypass here.

Make this a precise systemic gate, not "any ineligible player blocks everyone":

1. A relevant `RESULT_PENDING` match blocks the general recommendation, including a genuinely
   live match. Do not infer finalisation from elapsed hours.
2. A completed match in a club's role-evidence window with no usable current-season PFS side
   evidence blocks too. Validate side evidence with the existing canonical mapping and metric
   validity rules; zero-minute placeholders do not prove a side was populated.
3. An individual cameo, injury, newcomer, missing player row or ambiguous identity remains a
   per-player exclusion with a reason. Do not turn that into a league-wide outage. Partial
   side populations must still expose missing-player counts; one valid row does not certify
   every player's metrics. No claimed whole-pool completeness from a side-level sentinel.
4. Keep existing missing/stale/season-mismatch schedule refusals and no-editable-GW behaviour.
   Apply systemic checks before user filters, pins and relaxation so requests cannot conceal an
   affected club. Distinguish those checks from a legitimate blank or an intentional exclusion.
5. The two pick tools must disclose the same affected clubs and restricted comparison population.
   They may retain explicitly labelled eligible-only lists under their existing contract, but
   the model must not use those lists to assemble a fallback squad or claim a league-wide best.

No new chat-time public API calls, no new per-player ranking SQL, no parallel unversioned metric
store. Reuse `RankingMetrics`' read-only REPEATABLE_READ snapshot and its single `asOf`, capture,
schedule and canonical identities. **Owner clarification, 2026-09-13:** this service takes over
core FPL updates from Tapas. The previous no-ownership-change boundary is superseded by
`2026-09-13-owned-matchday-sync.md`; that plan defines the single-writer cutover and evidence
receipts. The read-side correctness work here does not itself enable a new production writer.

## Task 1 — reproduce the bias and diagnose the data supply

Files: `RankingMetricsIntegrationTest`, `SquadToolsIntegrationTest`, existing schedule integration
fixtures, and a short outcome section in this plan. Use `TapasSeed` in the disposable DB only.

- Seed a coherent league, exact-day captures and enough eligible alternatives to solve a legal
  squad. Give one club strong valid historical metrics and current 90/90/90 support, then an
  elapsed unfinished fixture. Demonstrate that the current implementation can return a squad
  excluding that club; preserve the fixture as the refusal regression for Task 3.
- Add the second state: schedule finalised but the club's new PFS side absent. Add the recovery
  state with valid core rows and minutes that actually satisfy eligibility. All state transitions
  are fixture-owned; never expect a particular real-world player to win a production-data test.
- With owner-authorised read-only operational access, inspect capture/scheduler logs and Tapas
  cron/collection status around the incident. Record source read times, match flags, relevant
  `data_checked` state and PFS row presence separately. A new API read shows today's state, not
  what yesterday's payload said. If no original payload/log exists, leave the cause unknown.
- Compare the deployed sync revision and scheduling configuration with the checked-in files.
  Determine whether the bottleneck is source finalisation, schedule polling, the Tapas finalized-GW
  gate, a failed job, or several of these. Do not run a production sync merely to diagnose it.

Verify: deterministic reproduction on local Postgres; explicitly recorded proven facts and
unknowns. The tests need neither an LLM request nor external APIs. Unknown operational cause
must not block the correctness guard in Tasks 2–4.

## Task 2 — carry comparison-coverage evidence in the ranking snapshot

Files: `tools/RankingMetrics.java`, `squad/CandidatePool.java`; one small shared coverage value
object if needed, with unit and snapshot integration tests. No second independent SQL reader.

- Retain canonical fixture IDs, gameweeks, clubs and specific window reasons, not only the coarse
  `UNKNOWN_CURRENT_EVIDENCE` eligibility enum. Derive club-side metric coverage inside the same
  transaction, reusing/extending the bulk reads rather than querying each player.
- Implement the systemic conditions above for the same role-evidence windows the minutes rule
  uses; do not let an irrelevant old match or a verified previous-club spell create an outage.
- Carry per-club candidate counts and per-player exclusion reasons independently of systemic
  failures. Deduplicate match and player counts. Keep user exclusions, availability/minutes
  risk, fixture-horizon exclusions and evidence failures separate.
- Preserve the distinction between source capture age, PFS coverage, match status and unknown
  core-stat refresh time. Never use PFS `updated_at` as a core-metric refresh certificate.

Verify: pending vs finished-without-side vs recovered; one missing player vs absent whole side;
zero-only placeholders; missing/mismapped sides; normal injured/sub-60/newcomer cases; club-spell
boundaries; source season mismatch; future captures and kickoff boundaries. Snapshot tests prove
that recovery cannot mix an old schedule with a newer metric/coverage read within one call.

## Task 3 — stop misleading full-squad output and expose excluded comparisons

Files: `squad/SquadTools.java`, `SquadFormatter.java`, `tools/PickTools.java`, their tests.

- Before optimisation, refuse a systemic coverage failure with an explicit status such as
  `COMPARISON_DATA_INCOMPLETE`, affected clubs, fixture/gameweek, exact reason and source/asOf.
  Explain that the tool cannot fairly compare the league, not that the omitted club is weak or
  its players have poor minutes. Do not promise a repair time that no upstream contract guarantees.
- On success, include a compact coverage receipt with counts and grouped specific evidence
  exclusions. Selected bench notes are not a substitute for omitted-player disclosure.
- Use the same coverage evidence in both pick tools. Label any returned restricted list as such
  before its rows; retain per-player floors and all existing provenance lines.
- Preserve existing solver invariants, unknown projections, season blending and scoring formulas.
  Budget, formation, club quotas, named pins and default relaxation cannot bypass the refusal.

Verify: Task 1's misleading success becomes an explicit refusal even with enough alternative
players; schedule-only recovery still refuses; real metric recovery allows a legal solve when
all other constraints pass. Test each entry path, legitimate blanks, and individual exclusions
that must not cause a global refusal. Assert counts and reasons, not just generic warning text.

## Task 4 — make chat obey the refusal, including follow-up challenges

Files: `prompts/system-prompt.st`, existing prompt/tool contract tests and chat test fixtures.

- Require the assistant to distinguish data exclusion from football judgement, name affected
  clubs when explaining omissions, and not describe missing evidence as rotation/minutes risk.
- After a squad refusal, neither pick lists nor manually substituted names may become a squad.
  A follow-up such as "why no defenders from this club?" must explain the coverage reason; a
  proposed replacement squad requires a successful new `buildSquad` result.
- Do not prescribe two or three defenders from any named club. Do not imply clean sheets are
  guaranteed or that this incident validates/calibrates the clean-sheet model.

Verify: deterministic contract tests plus a scripted stubbed conversation covering the original
question and the challenge. An optional owner-authorised model smoke must actually execute the
model; a string assertion on prompt text alone is not evidence of model compliance. Record the
remaining stochastic limitation if no live-model smoke is run.

## Task 5 — assistant-owned sync (superseded by the dedicated plan)

Owner clarification: **this service, not Tapas, performs the updates.** Implement the bounded
units in `2026-09-13-owned-matchday-sync.md`: repeated matchday fetching, full core history and
DefCon ingestion, post-match verification, durable retries/alerts and a single-writer handover.
The earlier proposal to leave ingestion in Tapas and only adjust its collection gate is withdrawn.
A small Tapas PR is still needed to disable its embedded/manual core writers and preserve its
remaining readers/jobs; it is not where the replacement updater is implemented.

Task 1's operational investigation remains useful but the decision to own ingestion no longer
waits for proving the original delay's cause. Coordinate the two plans' read-path changes; do not
implement two separate coverage models. Until handover/reconciliation completes, the correctness
guard can ship first and must honestly expose limited availability. Mark this task complete only
when the owned-sync plan's acceptance gates pass, not when this documentation is approved.

## Implementation outcome (2026-09-14)

`ComparisonCoverage` travels in the coherent ranking snapshot. A whole-club missing/pending
comparison causes a squad refusal even when other clubs can supply fifteen players; individual
player exclusions stay distinct. Pick replies label restricted comparisons. The prompt forbids
constructing a fallback squad after that refusal. Tests cover pending → schedule-only → verified
recovery, with no Arsenal quota, extra eligibility bypass or chat-time source request.

The owned-sync implementation supplies fixture/component receipts and correction revocation.
The original upstream reason for the stale GW4 source remains unproven; a reproduced coverage
failure is not evidence of its upstream cause. Local full-suite verification passed 1132 backend
tests; see the owned-sync plan for reviews, resource limits and the unactivated cutover gates.

## Task 6 — review, release and document

- Run the targeted tests, then the full backend suite against the disposable DB. No Supabase
  tests. Review coverage semantics independently, especially the "no side"/"one missing player"
  distinction and whether filters can hide an outage.
- Update `AGENTS.md`'s ranking/provenance contracts and `docs/STATUS.md` with the final policy,
  measured sync cause (or remaining unknown), verification and availability tradeoff.
- Commit on dedicated implementation branches, push with the normal pre-push hook, open PRs to
  master, review and fix/re-review. Owner merges; never manually deploy this work.
- After deployment, verify a success and an honest refusal by authorised read-only observation.
  Acceptance is not "Arsenal always selected": it is "Arsenal and every other club are either
  fairly comparable or the missing comparison is explicit and cannot masquerade as an optimum".

## Verification matrix

| Input state | Full squad | Pick lists / explanation |
|---|---|---|
| Whole league usable, eligible players exist | Normal constrained solve | Normal provenance and coverage receipt |
| One relevant live/elapsed unfinished match | Refuse, name both affected clubs | Restricted population explicitly labelled |
| Match final, one club's required metric side absent | Refuse, name missing side | Missing metric evidence, not poor football form |
| One missing player, side otherwise evidenced | Player stays ineligible; solve may succeed | Specific individual exclusion; no whole-pool completeness claim |
| Valid zero-minute cameo / injury flag | Existing player gate unchanged | Football eligibility, not systemic data outage |
| Schedule recovered, PFS still absent | Still refuse | Do not announce recovery from capture freshness alone |
| Core rows arrive, some players sub-60 | Solve only from remaining eligible players | No recovery-time minutes bypass |
| Blank horizon / season over | Existing blank/no-editable-GW behaviour | Never treat blank as unknown or replay GW38 |
| User demands Arsenal or challenges omissions | Same checks, no bypass | Explain evidence; no hand-built replacement squad |

## Deferred

Predictive minutes probabilities, relaxed eligibility, additional xG calibration, attack-opponent
modelling, bookmaker odds, club stacking preferences and budget/bench objective tuning are separate
model changes. This incident does not establish a defect in their numerical weights. The scope
here is the population being compared and the truthfulness/availability of the resulting advice.
