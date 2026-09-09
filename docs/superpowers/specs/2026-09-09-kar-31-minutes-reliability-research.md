# KAR-31 — minutes reliability and the 60-minute boundary

**PROGRESS:** Research, RAG guide and historical calibration complete; KAR-31/KAR-35 implementation plans ready. Production scoring not started; no calibrated safety claim is made.

Issue: [KAR-31](https://linear.app/quantti/issue/KAR-31).
This document records the evidence. The final implementation contracts are in
[the KAR-31 plan](../plans/2026-09-09-kar-31-minutes-reliability.md) and the subsequent
[KAR-35 plan](../plans/2026-09-09-kar-35-gameweek-horizon.md).

## Owner requirement

Defender recommendations must strongly favour players who reliably reach **at least 60 minutes**,
not players whose per-90 numbers look good despite short appearances. The boundary earns the
second appearance point and makes the defender eligible for four clean-sheet points. Future
minutes cannot be guaranteed: the implementation must distinguish evidence of reliability from
certainty, and cannot advertise a historical 100% rate as a guaranteed next start.

## What the sources establish

Sources checked 2026-09-09. The external strategy examples below are evidence about modelling
and selection principles, not current player recommendations.

| Source | Relevant finding | What it does not establish |
| --- | --- | --- |
| [Official FPL rules](https://fantasy.premierleague.com/en/help/rules), 2026/27 | 1–59 minutes earns one appearance point; 60+ earns two, excluding stoppage time. GK/DEF clean sheets pay four and MID clean sheets one, only after 60+ minutes without conceding while on the pitch. A later goal after substitution does not remove the clean sheet, but a goal after dismissal does. | No numerical threshold for calling a player a reliable starter. |
| [FPL Review: Editing xMins](https://docs.fplreview.com/the-model/planner-interface/editing-xmins) | Expected minutes is an average over possible match outcomes. Its practical ranges are 80–95 for a usual full-match starter, 60–80 for a regular starter with substitution risk, 40–60 for heavy rotation and 0–40 for a backup. Team news and fixture congestion can change the estimate. | These are guidance ranges, not calibrated probabilities of reaching 60. An 80-minute estimate does not mean an 80% chance. Its 95-minute input range is not a reason to change this project's 90-minute statistical convention. |
| [The Scout: festive-fixture tips](https://www.premierleague.com/en/news/4509983/the-scouts-top-tips-for-success-during-festive-fixtures-in-fantasy), 2025/26 | Prioritise regular starters whose minutes are not managed. Starting regularly and surviving substitutions are separate considerations; returnees and recently benched players need caution. | The named players and festive schedule are historical, not evidence of their current roles. |
| [Marcus Leadboot: Modelling xPts, version 2](https://medium.com/@marcusleadboot/modelling-xpts-in-fpl-version-2-0-e7d8cd738e75), 2025-10-17 | Models appearance and 60+ probabilities separately, applies the latter to clean sheets, and handles attacking exposure separately. Uses smoothing rather than taking a tiny record literally. | Not a validated formula to copy: the author explicitly reports that substitutes still rank too highly and that injured players' DefCon estimates need work. Its six-week window and bespoke weights are not this project's defaults. |

The local corpus agrees about selection discipline:

- `backend/src/main/resources/docs/strategy-differentials.md`: 70+ minutes most games,
  preferably 80+; playing every other match is not reliable differential selection.
- `backend/src/main/resources/docs/captain-selection-and-triple-captain.md`: a near-certain
  starter for captaincy; uncertain starts, rotation and knocks are reasons not to use Triple Captain.
- `backend/src/main/resources/docs/strategy-set-and-forget-and-low-maintenance.md`: nailed
  defenders with another route to points; reliability over occasional upside.
- `backend/src/main/resources/docs/strategy-blank-and-double-gameweeks.md`: minutes certainty
  first; two fixtures do not imply 180 minutes.
- `backend/src/main/resources/docs/strategy-defence-and-clean-sheets.md`: clean sheets and
  defensive contributions are separate routes to points. The supporting-guide change now also
  explains the 60-minute eligibility boundary there.
- `backend/src/main/resources/docs/strategy-deadline-and-team-news.md`: late news, returning
  injuries and rotation can supersede a historical minutes pattern.

None of these supplies an empirically established 80% or 90% reliability cutoff. The corpus's
70/80-minute guidance supports preferring secure minutes, not inventing a probability threshold.

## The weighting comes first from the scoring rules

Let `M` be minutes in one fixture, `pPlay = P(M > 0)` and `p60 = P(M >= 60)`.

- **Appearance expectation, exactly:** `pPlay + p60`.
- **Clean-sheet expectation, exactly:** `CS_points * P(M >= 60 AND no concession that counts
  against the player's clean sheet)`. Concessions while on the pitch count; after a dismissal,
  later team concessions also count, unlike after a substitution. With a conditional clean-sheet
  estimate `q`, this is `CS_points * p60 * q`. Treating the existing season-average `exp(-xGC)`
  as `q` is an approximation, not a proof of independence. Fixture-specific projection is KAR-30,
  not an extra feature of this task.
- **Attacking expectation, as a rate model:** `(goal_points * xG90 + 3 * xA90) * E[M] / 90`.
  This is where linear exposure scaling belongs; it assumes the historical per-minute attacking
  rate carries forward and does not by itself cure all cameo-rate bias.

At a hypothetical 40% conditional clean-sheet chance, crossing 59 → 60 minutes adds **2.6
expected points for a defender** from these two components alone: one appearance point plus
`4 * 0.40`. The corresponding jump is 1.4 for a midfielder and 1.0 for a forward. No arbitrary
"defender minutes bonus" or exponential penalty is needed to create this positional difference.

Averages alone lose the information needed at the boundary. This illustrative comparison assumes
the same 40% conditional clean-sheet chance and counts appearance plus clean-sheet points only:

| Playing pattern | Mean minutes | pPlay | p60 | DEF expectation |
| --- | ---: | ---: | ---: | ---: |
| 60 minutes every fixture | 60 | 1 | 1 | 3.60 |
| 90 and 30 minutes, equally often | 60 | 1 | 0.5 | 2.30 |

A filter on mean minutes >=60 admits both. Multiplying the whole full-match score by 60/90 also
values both identically. Neither implements the owner's reliability requirement.

**DefCon is not a 60-minute rule.** Its two points depend on reaching the action threshold
(10 CBIT for DEF, 12 CBIRT for MID/FWD), even in a shorter appearance. Minutes influence the
chance of doing enough actions, but neither clean-sheet eligibility nor its `p60` multiplier
may be imposed on DefCon as a game rule. The existing estimator reads only 60+ appearances;
its exposure treatment must be made explicit before calling a new per-fixture total complete.

## Consequences for the existing code

- `tools/ExpectedPoints.perNinety` always includes two appearance points and the full
  clean-sheet term. `tools/PickTools.score` ranks that figure without minutes weighting.
- `squad/HorizonScorer.score` multiplies the entire shared figure by mean minutes / 90. It
  penalises rotation but smooths away the appearance/clean-sheet discontinuity. Reusing it
  unchanged, as initially suggested, would not be enough.
- `PickTools` also blends realised `Pts/90` into its score. One appearance point in a 20-minute
  cameo becomes 4.5 Pts/90. Correcting only the expected component leaves a route for cameos to
  regain priority later in the season. Both sides of the blend must be per fixture before they
  are combined; a candidate is a per-season `SUM(total_points) / comparable club fixtures`
  rate. Preserve the existing realised-weight schedule; do not add appearance points twice.
- A shared per-fixture calculation must replace the squad path's outer minutes scaling, not
  sit inside it and get scaled twice. Preserve per-90 figures as explanatory rate statistics,
  not as the final recommendation score. Keep the work limited to minutes; KAR-35's future
  fixture-horizon defect and KAR-30's matchup model remain separate.

## Data and estimation constraints

1. Calculate mean minutes, appearance rate and 60+ rate over the **same identifiable fixture
   population**, including observed zero-minute matches. Denominate in fixtures, not appearances
   or distinct gameweeks: missed matches must count and a DGW contains two opportunities.
2. Missing player rows are unknown, never observed zeroes. Establish club membership and enumerate
   the expected finished fixtures independently, then require a minutes row for each. Do not count
   future fixtures, unsynced results or pre-transfer fixtures as benchings, or replace a missing
   result with an older appearance. First appearing in PFS or a daily capture is not registration
   proof: a leading sync gap must not shorten the window. Only positively observed different-club
   membership can supply a lower fence; otherwise enumerate from season start. Incomplete coverage
   or membership remains unjudged.
3. `PickTools.matches60` is the whole season's qualifying-match count for the blend ramp, not
   the current ten-row window's 60+ numerator. Keep these quantities separate.
4. Compute probability/rate estimates per season and blend through `SeasonBlend`. Do not divide
   its added evidence counts to manufacture a rate: that lets the longer previous season dominate
   outside the agreed ramp. Do not introduce a second ramp. Surface recent reduced minutes
   separately: a player with no new 60+ matches cannot have his lost role hidden by a strong
   previous-season record and a ramp that consequently stays at zero.
5. `starts` can add context but cannot replace the actual 60+ outcomes. The test schema has
   `starts NOT NULL DEFAULT 0`, and upstream parsing defaults missing values to zero; absence of
   starts data can masquerade as benching. Validate that signal before making it load-bearing.
6. Keep the 180-minute floor as a minimum for estimating per-90 ability, not as evidence of
   reliable starts. Two full matches are not proof of a near-certain future 60+ appearance.
7. Daily availability remains authoritative. A historical reliability score must not promote a
   doubtful player as safe, or equate FPL's fitness flag with the probability of playing 60+.

## Final planning decision — observed-history eligibility, not certification

The experimental 10% sub-60 risk budget was assistant-defined, **not an owner-approved product
requirement**. The failed calibration does not establish a safe probability cutoff, but neither
does it block implementing a precisely described historical selection policy.

The ready KAR-31 plan requires a fresh `a` availability flag and a complete, comparable window
of up to ten latest completed **current-season, current-club fixtures**, with **at least 180 minutes
and every fixture reaching 60**. Current capture/metric/schedule seasons must agree; missing or
stale evidence cannot be certified by old-season data. Apply this to both primary pick tools and
all squad XI positions. `90,90` can pass; `60,60` or `90,30,90` cannot. This current-role floor is
separate from the combined-season ability floor.

A sub-60 outcome blocks primary selection until it leaves the window. There is no injury-return
or new-manager reset. A positively observed different club bounds a new spell requiring its own
record; first appearance alone does not. A newcomer with an unknown arrival boundary may need
ten complete fixtures before pre-arrival gaps roll out. An empty list or infeasible XI is acceptable.
Keep permitted bench bodies, but never relax the primary rule to fill a list, honour a pin or
satisfy a solver default.

Print the observed current-club `60+ N/N`, minutes and dates beside the score and existing season
blend. Name the designation "consistent observed 60+ history", not a personal forecast probability
or a guaranteed next start. The new rule's future miss rate and production coverage are unmeasured;
none of the frozen season-blended experiment's percentages belongs to it.

KAR-35 follows separately: count every fixture in a shared calendar-GW range beginning at the
next editable deadline, then rank picks by a per-fixture composite times the fixture count.
A double is an opportunity, not permission to bypass the minutes rule. The two plans settle the
source, completeness, scoring, solver and test contracts without retuning the consumed holdout.

## Regression requirements for implementation

- Exact 0, 1, 59, 60 and 90-minute appearance boundaries, with 60 included in eligibility.
- The equal-mean/different-60+ example above; defenders with repeatedly short appearances
  cannot qualify merely by clearing 180 combined minutes.
- A nine-times-20-minute attacker does not inherit full appearance/clean-sheet points, and
  the realised-points blend does not bypass the minutes correction.
- Early substitution after 60 preserves eligibility, but a sending-off does not protect a clean
  sheet from subsequent team concessions; a DefCon threshold reached before 60 is not rejected
  as ineligible. Do not award a goalkeeper DefCon.
- Zero-minute rows, missing rows, incomplete sync, blank/double gameweeks and a club transfer
  do not inflate reliability by shrinking its denominator.
- Previous-season-only, sparse current-season, full-ramp, lost-role and ambiguous-name cases
  preserve provenance and never attach another player's reliability.
- Pick and squad paths agree on shared point components and apply minutes exposure once;
  existing solver constraints and the candidate-pool identity guards remain covered.

Implementation tests must seed their own rows in the disposable database, explicitly supplying
`starts` where it matters. The initial research was static: no production data was queried and
no scoring code changed.

## Supporting strategy guide — added after the owner's follow-up

`backend/src/main/resources/docs/strategy-minutes-and-rotation.md` supports the selection process:
60-minute scoring, repeated involvement versus mean minutes, a reliability-first defender shortlist,
captain/differential/bench distinctions, season and role changes, and missing-evidence handling.
The guide uses sourced qualitative principles, not an invented numerical reliability cutoff, and
explicitly forbids manufacturing probabilities or corrected scores that the tools did not supply.
Defender, differential and captain documents point to it. This complements the numerical work above;
it does not implement that work or replace it with an instruction to the model.

Verification: 23 ingestion/retrieval tests passed against the disposable database, including the
real checked-in corpus (23 documents, 201 chunks; nine from the new guide) with real embeddings.
Defender and captain queries both retrieved the relevant new sections among the existing corpus.
The production vector store was not changed; the guide needs the normal corpus reload to become
available there. Live-chat recommendation quality remains unverified.

## Historical calibration and implementation plan — completed 2026-09-09

Measurement: `../../analysis/2026-09-09-kar-31-minutes-calibration.md`; reproducible scripts and
aggregate artifacts are linked there. The frozen 2025/26 holdout followed 2023/24 cutoff-grid
construction and 2024/25 selection, using 2022/23 for priors and previous-season history.

No 5%/10% risk-budget gate qualified in development. The selected 15% comparison also failed its
holdout criterion (12.93% observed sub-60 risk, 15.59% descriptive upper endpoint). Mean >=90 was
conservative but not an accepted policy: 8.51% holdout misses and 8.66% coverage, with an 11.34%
upper endpoint. These results concern history-only defender–fixture opportunities, not known-fit
players or other positions, and cannot be relabelled as guaranteed personal probabilities.

The component scoring diagnostic improved modestly over linear scaling but used different
smoothing, so it does not isolate the formula's predictive contribution. The scoring rules still
require the distinct appearance and clean-sheet boundary; DefCon remains a separate action event.

The staged plan is `../plans/2026-09-09-kar-31-minutes-reliability.md`, followed by
`../plans/2026-09-09-kar-35-gameweek-horizon.md`. They now fix the engineering policy described
above rather than deferring primary selection behind an invented product risk threshold. This
supersedes the earlier blocked-shortlist interpretation, not the experiment's negative result.
No calibrated safety gate or completed production implementation is claimed. The holdout remains
consumed and must not be reused as fresh evidence for thresholds tuned from these results.
