# KAR-31 — historical minutes-reliability calibration

**PROGRESS:** Measurement and holdout review complete; no safety gate met the frozen acceptance criteria.

## Frozen protocol

Source: public vaastav `merged_gw.csv` plus `fixtures.csv`, pinned to commit
`9779cdbc0c07f6c900c2d0c181ddf6bb9c800f88`. Raw downloads stay outside the repository and database.

- **2022/23:** prior estimation and previous-season history only.
- **2023/24:** development of the fixed candidate cutoff grid, before threshold selection.
- **2024/25:** choose a gate once.
- **2025/26:** chronological holdout, opened only after saving the selection artifact.
- Target: one defender–fixture outcome, official FPL `minutes >= 60`, including exactly 60.
  Zero-minute and 1–59-minute failures are reported separately, including injuries and dismissals.
- No historical availability reconstruction from final news, price, selection or injury fields.
  This is a **history-only** benchmark, not reliability conditional on verified fitness.

### Time and population

This is a retrospective, **gameweek-frozen fixture benchmark**, not a verified FPL-deadline replay.
The final schedule is known; archived announcement times and deadlines are not. Use the earliest
final kickoff in each gameweek minus 90 minutes as a proxy cutoff, with historical observations
available only 27 hours after kickoff (three hours for completion plus a conservative 24-hour
publication allowance). Both DGW legs use the same frozen history. This cannot establish the
actual ingestion latency or the fixture list that was known at a historical deadline.

Build candidates from player rows already available before the cutoff, not the target lineup or
end-of-season squad. Carry forward the last observed club and position. Missing target rows are
unknown outcomes, not zeroes; report them and do not silently remove their candidate count. Missing
rows inside a historical club-fixture window cause abstention. Do not move the window backwards
until ten convenient appearances have been found. First observed registration and club changes
bound which fixtures can be treated as comparable.

Use at most the last ten comparable club fixtures for the current-season rate; previous-season
rates use the complete observable season only when its club-fixture windows are complete.
At least two current-club observations and 180 combined historical minutes are required to enter
the comparable pool. Report the excluded opportunities separately. A new signing or first
appearance cannot acquire a forecast from future rows. Previous-season names bridge only when
unique in the known current population and the previous season; the CSV has full names rather
than the production `web_name`, a transferability limitation to report.

### Comparisons, fixed before selection

1. Mean minutes per fixture (gate signal, not a probability).
2. Start share (gate signal, not a probability of 60+).
3. Observed 60+ share, also assessed as a naive next-fixture probability forecast.
4. Smoothed 60+ share: two prior pseudo-fixtures, using the defender base rate from 2022/23.
5. The same smoothed signal with a fixed recent-involvement veto: both last two current-club,
   current-season fixtures must have reached 60. A veto is abstention, not a zero probability.

Rates are formed per season and blended by the existing `SeasonBlend` ramp, read from its Java
source rather than copied into another implementation. Its input is the qualifying count as of
the cutoff, not the final season count. Missing previous rates fall back to current evidence.
`starts` is unknown for a fixture unless both sides have 11 recorded starts. Initial source audit
found all-zero starts for 136 fixtures in 2022/23; these must not become observed non-starts.
An incomplete previous season, including an intra-season club move without a registration timeline,
is not used as a complete prior record. Current-club history can still qualify on its own.

No window-length search, learned nonlinear model, alternative recent-veto rule or new season ramp.
Candidate cutoffs are frozen from 2023/24: 50th, 75th, 90th, 95th and 99th percentiles of each
signal among comparable observations, plus each signal's maximum. The veto variant shares the
smoothed variant's grid. Identical cutoff values are deduplicated.

### Selection and uncertainty

The **working**, not owner-approved, risk budget is 10% sub-60 outcomes. Also report 5% and 15%
risk-budget alternatives without making them new opportunities to tune the holdout.

Choose maximum development coverage among candidates whose descriptive 95% upper risk bound
is at most the budget, with at least 200 admitted observations, 20 different defenders,
20 gameweeks and 5% comparable-pool coverage. Ties prefer fewer conditions, then the fixed
variant order above, then the lower cutoff. If no gate qualifies, record abstention: do not
relax the constraints and call it success.

For uncertainty, resample four-gameweek time blocks with all players together, 2,000 draws and a
fixed random seed. Use the larger of the bootstrap upper endpoint and the Wilson upper endpoint
as a guard against degenerate zero-failure bootstrap intervals. These are descriptive intervals,
not simultaneous multiple-comparison guarantees or a proof that long-lived player dependence is
fully captured. Unknown admitted outcomes enter this upper bound pessimistically as possible
failures, while their actual labels remain unknown and are excluded from probability scores.
Selection is performed on development only; the independent holdout decides whether the chosen
gate generalises. No retuning after a holdout failure.

Report risk, zero/short failure counts, precision, opportunity coverage, successful-outcome recall,
player-gameweek coverage, distinct players, admitted-per-gameweek counts, and calibration/Brier
scores for the two probability interpretations. DGWs and short/full history get separate holdout
diagnostics. Count a player-GW once when reporting recommendations and report how often both
observed DGW legs reach 60. Unknown outcomes and incomplete data remain explicit limitations.

## Development selection — frozen before opening holdout

No candidate met the 5% or 10% risk budget under the specified support and upper-bound criteria.
The 15% comparison selected the smoothed signal at 0.7206613670187666 plus both latest fixtures
reaching 60: 1,483 admissions, 12.61% observed sub-60 risk, 14.40% upper bound and 26.04% coverage.
The most conservative supported candidate, mean minutes >=90, had 7.89% observed risk but a
10.61% upper bound, so it was **not** promoted to a passing 10% policy.

A reporting amendment made after development and before opening holdout retains results for the
entire frozen cutoff grid as diagnostics. This adds no candidate and changes no selection rule;
it allows the plan to distinguish "no supported primary gate" from "history has no predictive
value". Those diagnostics must not be used to reselect a better-looking holdout rule. The script
hash is frozen again with this output-only addition before validation.

A fixed scoring diagnostic also compares unscaled per-90, whole-score linear minutes scaling,
and separate appearance/attack/clean-sheet components. It is not used for gate selection and is
not a full FPL projection: it excludes realised-points blending, FDR, saves, bonus, deductions and
DefCon. The component model uses two-pseudo-fixture smoothing for both probabilities and mean
minutes, while the linear comparator uses the unsmoothed mean. This compares two estimation
pipelines, **not** a matched-estimator ablation of the component formula.

## Holdout results — 2025/26, consumed once without retuning

The selected 15% policy also failed its validation criterion: observed risk was **12.93%**, but
its descriptive upper endpoint was **15.59%**. This does not prove true risk exceeds 15%; it means
the predeclared evidence requirement was not met. There is no selected 5% or 10% policy to deploy.
Do not choose a different cutoff from the holdout diagnostics and call that independent validation.

All rates below are for defender–fixture opportunities. Coverage is against the comparable pool,
not against all defenders or a fit-only squad. All five gate signals blend per-season rates using
`SeasonBlend`; "mean 90" is **not necessarily ten current-season 90-minute appearances**. At zero
current qualifying matches the ramp can retain the previous season entirely. Recent contrary
evidence therefore needs to remain explicit, not hidden behind the blended number.

| Rule | Development risk / upper | Holdout risk / upper | Holdout admissions | Holdout coverage |
| --- | ---: | ---: | ---: | ---: |
| Existing 180-minute comparability floor, no minutes gate | 53.70% / 55.25% | 50.31% / 51.65% | 5,566 (4 unknown) | 100.00% |
| Mean minutes >=60 | 23.15% / 24.92% | 25.16% / 26.97% | 2,386 (1 unknown) | 42.87% |
| Mean minutes >=90 — diagnostic, not an accepted 10% policy | 7.89% / 10.61% | 8.51% / 11.34% | 482 | 8.66% |
| Start share =1 | 12.57% / 14.90% | 13.03% / 15.42% | 875 | 15.72% |
| Observed 60+ share =1 | 10.14% / 12.48% | 10.24% / 12.57% | 781 | 14.03% |
| Smoothed 60+ >=0.887328 | 9.72% / 12.26% | 13.67% / 16.74% | 600 | 10.78% |
| Selected 15% comparison: smoothed >=0.720661 + last two 60+ | 12.61% / 14.40% | 12.93% / 15.59% | 1,423 | 25.57% |

The full, unrounded frozen grid and all diagnostics are in `kar-31/kar-31-selection.json` and
`kar-31/kar-31-holdout.json`. The percentile cutoffs are experimental values, not new FPL rules.

### What the denominator contains

The holdout generated **9,463** opportunities for defenders whose identities/clubs were already
observable. Of these, 274 lacked two current-club observations and 3,623 were below the combined
180-minute floor, leaving **5,566** comparable opportunities and **5,562** known outcomes across
174 defenders and 36 gameweeks. Four target rows were missing; none were labelled as zero-minute
appearances. The archive did not exhibit incomplete current windows in this replay; the synthetic
tests still cover that failure mode. This does not establish equivalent completeness in Supabase.

The mean-90 diagnostic retained 53 defenders, a median of 12 distinct defenders per gameweek,
and 15.96% of actual 60+ outcomes. Its 41 failures were **25 zero-minute** and **16 short**
appearances. Even this unusually restrictive historical profile did not eliminate missed matches.

The selected 15% comparison retained 100 defenders, a median of 40 per gameweek and 44.83% of
actual 60+ outcomes. Its 184 failures split into **94 zero-minute** and **90 short** appearances.
Nothing here identifies whether those failures were foreseeable injuries, suspensions, tactical
substitutions or unexpected incidents; no historical fitness flag was reconstructed.

### Prespecified holdout diagnostics for the selected 15% comparison

| Slice | Admissions | Sub-60 risk | Coverage within slice |
| --- | ---: | ---: | ---: |
| GW1–10 | 313 | 8.63% | 27.48% |
| GW11–25 | 616 | 11.53% | 26.11% |
| GW26–38 | 494 | 17.41% | 23.89% |
| Fewer than ten current-club observations | 331 | 8.16% | 27.77% |
| Full ten-fixture window | 1,092 | 14.38% | 24.97% |
| Single-gameweek fixtures | 1,377 | 12.93% | 25.49% |
| Double-gameweek fixtures | 46 | 13.04% | 28.05% |

Among the 23 admitted player–DGWs with two known outcomes, **17/23 reached 60 in both matches**;
all 23 did so in at least one. Thus a good per-fixture record does not establish two secure starts.
The sample is small and matches are dependent; do not infer a guarantee or multiply independent
probabilities. The late-season deterioration is a diagnostic, not permission to tune a seasonal
exception after viewing holdout.

### Probability calibration

| Period | Observed-share Brier | Smoothed-share Brier |
| --- | ---: | ---: |
| 2023/24 cutoff-grid period | 0.19834 | 0.19300 |
| 2024/25 selection period | 0.18028 | 0.17646 |
| 2025/26 holdout | 0.19903 | 0.19468 |

Smoothing reduced holdout Brier error by about **2.2%** relative to the naive share. That is not
proof of high-confidence calibration: the naive 0.9–1.0 band predicted 96.25% on average but only
84.95% actually reached 60 (1,369 observations). The smoothed 0.8–0.9 band predicted 85.04% and
observed 81.44% (1,293 observations); above 0.9 there were only three observations, insufficient
for any high-confidence claim. Historical fractions must not be advertised as calibrated personal
probabilities. The JSON's constant-prior Brier baseline is the raw 2022/23 defender population,
not a competitive baseline fitted to the minutes-qualified pool.

### Restricted scoring diagnostic

| Pipeline | Appearance RMSE | Appearance + goals + assists + clean-sheet RMSE |
| --- | ---: | ---: |
| Unscaled 90-minute reference | 1.27368 | 2.94765 |
| Entire score scaled by mean minutes /90 | 0.81706 | 2.21529 |
| Separate components with smoothed exposure | 0.81124 | 2.20596 |

The component pipeline was only **0.7%** better on appearance RMSE and **0.4%** on the restricted
points RMSE than linear scaling. These estimates use 5,562 known outcomes, not just an admitted
shortlist. The smoothing differs between pipelines and statistical significance was not tested:
**do not attribute the small difference solely to handling the 60-minute boundary**. Nor does this
measure the complete shipped score, ranking quality, captaincy, fixture adjustment or DefCon.
The reason to separate appearance/clean-sheet components remains the scoring rule itself; a
smooth multiplier cannot reproduce its 59/60 discontinuity even with perfectly known minutes.

## Source audit and reproducibility

| Season | Unique player-fixture rows | Explicit zero-minute rows | Exact duplicate rows removed | Unknown-start fixtures |
| --- | ---: | ---: | ---: | ---: |
| 2022/23 | 26,505 | 15,160 | 0 | 136 |
| 2023/24 | 29,725 | 18,341 | 0 | 0 |
| 2024/25 | 27,283 | 15,717 | 0 | 0 |
| 2025/26 | 29,747 | 18,255 | 10 | 0 |

Each archive had 380 finished fixtures, matching player-row dates, gameweeks and opponent sides.
The 322 assistant-manager rows in 2024/25 were removed before validating starts. Identical
2025/26 duplicates were deduplicated; conflicting duplicates would abort the run. All-zero starts
in 136 early 2022/23 fixtures became unknown, not 22 observed non-starts. That season's minute
observations remain usable, but its incomplete start rate is not supplied as a complete prior.

Run from the repository root with Python 3.10+ (standard library only):

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/analysis -p 'test_calibrate_minutes.py'
python3 scripts/analysis/calibrate_minutes.py select --cache /tmp/kar31-minutes
python3 scripts/analysis/calibrate_minutes.py validate --cache /tmp/kar31-minutes
```

All 24 synthetic analysis tests passed, including target-leakage, transfer/name boundaries,
DGW freezing, the actual component diagnostic and frozen-artifact rejection. The first command
needs no network or database. `select` downloads only 2022/23–2024/25;
`validate` reads the frozen selection and only then loads 2025/26. Outputs are aggregate JSON in
`docs/analysis/kar-31/`. Each source has a SHA-256 digest; raw data lives under a revision-keyed
cache, never in Supabase or the repository. The script reads the ramp directly from `SeasonBlend`
and rejects validation if the protocol, experiment script, ramp or development-source digest has
changed after selection. The lock hashes the entire `SeasonBlend.java`, not only the ramp array:
a later addition of exposure-rate fields will also reject validation. Reproduce from the research-era
source tree; do not refreeze artifacts or overwrite new implementation code to bypass this guard.
The final selection artifact digest is
`08e5bb4c2f477ead2bfb443cce733266c8d5370e767dac383301b0a5f3659f17`.

Both phases were rerun into a separate temporary output directory; both aggregate artifacts
matched the outputs stored under `docs/analysis/kar-31/` byte-for-byte. The holdout is now consumed. Running this
frozen experiment again is reproducibility, not a fresh validation set. Any future tuning against its results needs another untouched period before a
validation claim. Review added synthetic tests, not a changed fitted policy, and qualified the
unequal-smoothing comparison rather than rewriting a favourable result.

## Decision for the implementation plan

Proceed with **component-based minutes scoring and explicit historical evidence**, not an automatic
"safe 60+" probability label. Keep the 180-minute floor as an ability-sample guard, never as a
reliability guarantee. Preserve the existing season ramp and make recent contrary involvement
visible beside it. Do not deploy the selected 15% comparison or substitute the mean-90 diagnostic
as though either passed the predeclared safety gate.

### Planning resolution after the experiment

The 10% risk budget was an assistant-defined experimental objective, not the owner's product
requirement. The ready implementation plan therefore adopts a **historical selection rule**, not
a passing confidence gate: fresh fit status, a complete current-club/current-season window of
up to ten latest completed fixtures, at least 180 minutes in that window, and every fixture
reaching 60. Missing/stale evidence is unjudged; earlier seasons cannot satisfy the role gate.
The rule is hard for primary picks and every squad XI position, never relaxed to fill a list.
The production plan is stricter about leading gaps than this archive replay: first PFS/daily
appearance is not registration proof. Only a positive different-club observation can shorten the
season-start boundary; otherwise missing early fixtures remain unknown until outside the latest
ten. Previous-season exposure requires the complete club season rather than a first-row boundary.
The replay's original assumptions remain documented above, not retrospectively rewritten.

This is an explicit engineering decision made while finishing the requested plan, **not a new
calibration result or a reselected holdout winner**. Its future miss rate and production coverage
are unmeasured. In particular, neither the mean-90 diagnostic's 8.51% misses nor the observed-share
comparison's 10.24% belongs to this different, unblended current-role rule. Two full appearances
can satisfy its historical definition without establishing a near-certain future start.

Actual deadline snapshots, availability and role evidence may improve a later forecast, but that
benefit was not measured here. This defender-only experiment establishes no goalkeeper/midfielder/
forward confidence guarantee. The frozen protocol, negative result and aggregate artifacts remain
unchanged; the earlier interpretation that product implementation must wait for a 10% certificate
is superseded.

Implementation plans: `../superpowers/plans/2026-09-09-kar-31-minutes-reliability.md`, then
`../superpowers/plans/2026-09-09-kar-35-gameweek-horizon.md` for calendar-GW fixture totals.
Production code, the production database and the production vector store are unchanged.
