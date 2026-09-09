# KAR-35 — count every fixture in an editable gameweek horizon

**PROGRESS:** Implementation Task 5/5 done.

Issue: [KAR-35](https://linear.app/quantti/issue/KAR-35).
Implement after [KAR-31](2026-09-09-kar-31-minutes-reliability.md), which supplies per-fixture scores,
shared capture context and hard observed-minutes eligibility. This is a separate implementation
and acceptance boundary; neither ticket implements KAR-30's opponent-specific clean-sheet model.
Java/test paths use the same package roots as the KAR-31 plan.

## Final decisions

The existing bug has two parts. `ROW_NUMBER <= N` discards a later fixture when a club doubles;
keeping all fixtures but merely averaging their FDR still gives the extra match no primary-score
advantage. Fix both: **one calendar-GW window for every club, and a total score over its fixtures**.

The window begins at the **first deadline strictly after the call's `asOf` instant**. These tools
recommend transfers/captains/a starting XI, so fixtures in an already-locked GW cannot benefit the
new selection. This is an explicit product choice, not an unfinished question. Do not change
`FixtureTools.getUpcomingFixtures`'s separate residual-current-GW listing behaviour.

- PickTools signatures stay unchanged. `nextN` now means calendar gameweeks and is clamped to
  1–5 as before. Rename the private `MAX_FIXTURES`/fixture-limit terminology accordingly.
- `buildSquad` keeps its existing input policy: `nextN <= 0` means five; positive values are not
  silently capped at five. Truncate to the season end using long arithmetic before conversion,
  so `Integer.MAX_VALUE` cannot overflow `from + horizon`.
- Only the fresh, coherent FPL schedule `View` from KAR-31 is used. Its season must match the
  daily candidate capture, not a completed fallback metric season. No weekly/older-snapshot
  fallback and no cross-season club-id bridge. No additional database/network call per player.
- Bounds are `from <= gameweek < min(from + requestedN, 39)`. Do not extend a club's calendar
  window for blanks, already-started fixtures, postponed games or doubles.
- If no future deadline remains, report **no editable gameweek**. Do not replay GW38/GW1 or
  invent GW39. At an exact deadline the newly locked round is excluded.
- The same fixture population feeds picks and squad scoring, but their objectives remain distinct:
  picks blend expected/realised points; the squad objective stays expected-only with its existing
  coarse FDR multiplier. No new FDR coefficient or duplicated minutes scaling.

## Shared horizon result

Add `schedule/HorizonFixtures.java` as a pure derivation over `ScheduleSource.View`, `asOf`, capture
season and normalized requested horizon. It returns source metadata, start/end/requested/actual
GW counts and one `ClubWindow` per captured club. Reuse the view/clock produced by the ranking
load; do not fetch another snapshot between eligibility and scoring.

`ClubWindow` distinguishes **known fixtures (including an empty list)** from **unknown with a
reason**. Keep the counted fixture list, not just an average, so count, FDR and squad summation
cannot refer to different populations. Missing/stale/incoherent/mismatched views are globally
unknown; a valid view with unresolved scheduling can be unknown for one club only. Global
`NO_EDITABLE_GAMEWEEK` is a separate outcome, not a forecast of zero for a new season.

### Fixture classification, in order

1. Started or finished fixtures never contribute future points, regardless of timestamp. They
   are not used to extend the range or infer an additional future replay.
2. An outstanding fixture with `gameweek=NULL` makes both involved clubs' horizons unknown:
   its placement has not been established. Do not count it as a blank or assume it falls outside N.
3. An assigned fixture outside the selected GW range does not affect that window, including an
   undated fixture assigned to a later GW.
4. Within the range, require `started=false`, `finished=false`, and a known kickoff **after**
   `asOf`. An unresolved kickoff or an elapsed/equal kickoff with both flags still false is
   unknown scheduling/completion, not a future fixture and not a silently inferred zero. No
   forecast may use a fixture merely because captured `finished` has not flipped yet.
5. Count every remaining in-range fixture once by its season-scoped id. A DGW contributes two;
   a known blank contributes none. Other clubs stay usable when an uncertainty is club-specific.

For an otherwise countable in-range fixture, kickoff must also be **strictly after its assigned
GW's deadline**; an equal/earlier kickoff makes that club unknown. Do not infer GW membership from
a date bucket or add an upper bound at the following deadline: FPL's assigned GW is authoritative.
Use captured deadlines, ignoring `is_current` and `is_next` flags for this calculation.

Missing or invalid FDR (outside 1–5) **does not remove a fixture**. PickTools' average FDR is n/a
if any counted fixture lacks a valid value—never average just the known subset. Squad scoring
retains neutral FDR 3 for that row and discloses the approximation.

## Task 1 — pin the two DGW regressions and clock semantics

Tests: `tools/PickToolsRankingTest.java`, new `schedule/HorizonFixturesTest.java`,
`tools/PickToolsIntegrationTest.java` and horizon integration tests. Reuse the coherent full-season
ranking seed from KAR-31; move fixture GWs to create doubles/blanks rather than dropping rows from
the complete captured season.

First demonstrate that the post-KAR-31 baseline (a) discards the last intended fixture in a double
window and (b) cannot let two 3-point fixtures beat one 5-point fixture through primary Score.
Assertions must inspect the fixture ids/order and actual ranking, not only an output label.

Pin next-editable-deadline boundaries: immediately before, exactly at and immediately after a
GW deadline. Remaining matches of the just-locked GW are not included, even if kickoff is still
90 minutes away. Stale captured current/next flags must not change the result.

## Task 2 — implement the shared gameweek horizon

Files: new `schedule/HorizonFixtures.java`; `schedule/ScheduleSource.java` only where its view
needs an already-stored field. Do not rewrite the listing tool's start-GW SQL or add a sync job.

Implement the result/classification contract above. A known club with no remaining fixtures has
count 0, numerical horizon total 0 and FDR n/a; an unknown club has no numerical projection.
Include requested and actual spans so GW37 plus `nextN=5` says only GW37–38 remain. Deadline and
fixture decisions use the single supplied clock instant, not the database clock or captured flags.

Verify: six fixtures across five GWs with a double; a blank without extending the window;
GW at the exclusive end excluded; unknown GW/kickoff; elapsed kickoff with false flags;
started/finished with inconsistent future timestamps; missing FDR; missing/partial/stale snapshots;
season-id reuse; nextN normalization/overflow; and no future deadline after GW38.

## Task 3 — rank picks by a real horizon total

Files: `tools/PickTools.java` and pick tests.

After KAR-31 eligibility, require a **known horizon with at least one fixture** for automatic
primary picks. Known-zero and unknown-schedule players are counted separately in the exclusion
summary, not appended to fill ten places.

```text
horizonScore = perFixtureScore * remainingFixtureCount
order = horizonScore DESC, averageFdr ASC (unknown last),
        existing ownership ASC / blended minutes DESC, elementId ASC
```

Compare unrounded values. The last id tie-break makes otherwise identical picks deterministic;
it does not change football weighting. Average FDR stays a tie-break, not a new multiplier. A double
is an extra opportunity, not an unconditional win: 3*2 beats 5*1, but loses to 8*1.

Remove the old private `avgFdrByTeam` and start-gameweek queries once unused. Format the shared
per-fixture composite, fixture count, **horizon** Score and average FDR as separate columns. Rename
the footer/tool description to calendar gameweeks and the actual editable range. No method may
print a per-fixture score under a heading claiming it is the N-GW total.

## Task 4 — use the same fixture population in squad scoring and XI eligibility

Files: `squad/HorizonScorer.java`, `squad/SquadTools.java`, `squad/SquadSolver.java`,
`squad/Feasibility.java`, `squad/SquadFormatter.java` and their tests.

Replace `HorizonScorer.fixtureDifficulties(seasonId, horizon)`'s private weekly-fixture query with
the shared `HorizonFixtures` result from the pool's capture context. Preserve this objective:

```text
squadScore = sum(expectedPerFixture * existing_fdrAdjust(fdr))
```

No realised-points blend, extra fixture-count multiplier or outer minutes multiplier. This shares
expected components and fixture membership with picks, not an identical final objective.

Compute the set of clubs with a known positive horizon and require it as an explicit solver input.
In **all** solver entry paths, `x_i=0` unless the candidate passes KAR-31 **and** its club belongs
to this set. No omitted-input overload may assume all clubs are playable. Pass the same set to
eligible-capacity checks, respecting forced formations. Pins still mean membership, not a bypass.

Known-zero players may be bench options with a genuine zero within the shown horizon. Unknown
horizon players may remain bench options under the purchase rules, with zero internal objective
weight and `n/a/unscored` output. They must not enter the XI or captain recommendations. Preserve
unknown versus zero through `SquadFormatter`, including individual bench rows—not only a
league-wide footer. Missing FDR uses the existing neutral 3, explicitly labelled.

Verify that a double never rescues minutes-ineligible history, the basic and default solver paths
both enforce the combined eligibility, pins/quotas/relaxations cannot bypass it, and no-eligible-XI
refusals explain schedule/minutes limits rather than promising a budget change will solve them.

## Task 5 — provenance, acceptance and closure

Files: both tool descriptions/formatters, relevant `prompts/system-prompt.st` passages,
`AGENTS.md`, the fixture-planning/minutes strategy guidance as needed, `docs/STATUS.md`.

These ranking replies now use three sources. Preserve the daily-price and metric attribution;
add `ScheduleSource.provenance()` for the schedule plus exact capture time, `asOf`, next editable
GW range and any truncation/exclusions. Update the documented ranking/squad provenance convention
accordingly. This is an additional **source** line, not a second metrics-season banner. Historical
metrics remain historical even when prices and future fixtures are from the season being played.

Acceptance matrix:

| Case | Required result |
| --- | --- |
| Five GWs, one double | Six fixture ids; fifth GW retained, following GW excluded |
| Blank in the window | Fewer fixtures, no extension to a later GW |
| Per-fixture scores 3 versus 5, two versus one match | Double ranks first at 6 versus 5, even with worse FDR |
| Per-fixture 3 twice versus 8 once | Single ranks first; no unconditional DGW promotion |
| Expected=4, realised=2, realised weight=0.5, two fixtures FDR2/4 | Pick horizon Score=6; squad score=8 |
| Equal rounded scores, unequal raw values | Correct unrounded ordering; final exact tie by element id |
| Missing FDR on one DGW leg | Count stays two; pick average n/a, squad uses neutral only for that leg |
| Exact deadline | Start at the next editable GW, not the newly locked one |
| NextN=5 from GW37 | GW37–38 only, disclosed truncation; no rollover |
| After GW38 deadline | No editable GW; no historical fixture replay |
| Unresolved match for one club | That club unknown, not a blank; unaffected clubs remain usable |
| Unknown or zero horizon | No primary pick/XI; bench labels preserve n/a versus zero |
| Capture/metric season changeover | Match schedule to daily pool by season; no reused-id cross-talk |
| No qualifying candidates | Honest empty list/infeasibility, never weakened eligibility |

Run focused `*PickTools*`, `*Horizon*`, `*Schedule*`, `*CandidatePool*`, `*Squad*`, `*ExpectedPoints*`
and `*SeasonBlend*` tests, then `./gradlew cleanTest test` against the disposable database only.
Verify relevant corpus chunking/retrieval if wording changes; it is not a forecast-quality study.
No new `realdata` oracle, production query, chat-time API call, sync writer or deployment is needed.

Update this progress line and STATUS once each task is complete. Submit a feature-branch PR that
closes KAR-35 only after both fixture membership and primary-score DGW advantage are verified.
Never push master; leave deployment and any explicit production corpus reload to the normal
reviewed workflow. No implementation decision remains open in this plan.

### Outcome

All five tasks are implemented on `plan/kar-35-gameweek-horizon`. `schedule/HorizonFixtures`
derives one calendar-gameweek window per club from the first deadline strictly after the ranking
snapshot's `asOf`, on the daily capture's own season. `PickTools` ranks on
`Score/fixture × counted fixtures` with average FDR as a tie-break, and counts known-zero and
unknown-horizon players apart instead of ranking them at an invented zero. `SquadTools`,
`HorizonScorer`, `SquadSolver` and `Feasibility` share that same fixture population and the
playable-club set it produces, as an explicit solver and capacity input; bench rows keep a real
zero and an `n/a` apart. Task 5 added the third source line, `ScheduleSource.provenance()`, to
both ranking replies including their refusal paths, extended the `Schedule evidence:` sentence
with the `asOf` instant, the editable range and the schedule-caused exclusions, and updated the
tool descriptions, class javadoc, `system-prompt.st` and `AGENTS.md`.

Verification ran from `backend/` with `backend/.env.test` only, against the disposable
`fpl-test-db` container. The plan's focused selection (`*PickTools*`, `*Horizon*`, `*Schedule*`,
`*CandidatePool*`, `*Squad*`, `*ExpectedPoints*`, `*SeasonBlend*`) passed 282 tests with 0
failures, 0 errors and 0 skipped in 52 seconds. The full `./gradlew cleanTest test` passed 765
tests with 0 failures, 0 errors and 0 skipped in 1 minute 10 seconds; that run includes
`IngestionServiceTest`, `IngestionIntegrationTest` and `StrategyToolsIntegrationTest`, so
test-corpus chunking and retrieval are unchanged.

Not done, deliberately: no `realdata` oracle was added, no production or Supabase query was run,
no chat-time external API call was introduced, no sync writer was touched, nothing was deployed
and no strategy corpus was reloaded or re-ingested. The strategy corpus was reviewed and left
unedited — no sentence in it became inaccurate, so changing it would only have created drift
against the embeddings already in production. The FDR treatment remains the existing coarse
whole-score multiplier; KAR-30's opponent-specific clean-sheet model is still out of scope, and
nothing here is a calibration claim.
