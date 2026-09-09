# KAR-31 — minutes-aware scoring and observed-history selection

**PROGRESS:** Implementation Task 7/7 done.

Issue: [KAR-31](https://linear.app/quantti/issue/KAR-31).
Implement this first, then [KAR-35](2026-09-09-kar-35-gameweek-horizon.md). KAR-30's fixture-specific
clean-sheet model is not a dependency and is out of scope.

Research: `../specs/2026-09-09-kar-31-minutes-reliability-research.md`.
Frozen measurement: `../../analysis/2026-09-09-kar-31-minutes-calibration.md`.
All Java paths below are under `backend/src/main/java/fi/karivanttinen/fplassistant/`; test paths
are the corresponding packages under `backend/src/test/java/fi/karivanttinen/fplassistant/`.

## Final planning decision

The previous plan incorrectly made the assistant's experimental 10% risk budget a product blocker.
The owner requested strong minutes weighting and defenders who consistently reach 60, not a
certified next-match probability. The negative experiment remains unchanged: no 5%/10% policy was
selected, and the 15% comparison failed its holdout criterion. It is not permission to invent a
successful probability cutoff, but it does not prevent an explicit **observed-history rule**.

An automatic primary pick or starting-XI player must satisfy **all** of:

1. A fresh daily capture says `status = 'a'`, with a valid position and club.
2. Capture, current metric season and fresh, coherent schedule capture are the same season.
3. The current-club window contains every expected completed fixture, up to the latest ten,
   without a missing or invalid minutes observation or a more recent unresolved result.
4. The window contains **at least 180 current-club, current-season minutes**.
5. **Every fixture in that window records at least 60 minutes.**

The 180-minute condition is additional to the existing combined-season ability floor. Previous
minutes cannot satisfy this current-role requirement. With valid minutes bounded by 90, it also
requires at least two observations; no new arbitrary match-count or probability threshold is added.

Apply the **same predicate** to both pick tools (including `position="any"`) and **every squad XI
position**. This is a conservative product policy for GK/DEF/MID/FWD, not an extrapolated claim that
all positions share the defender experiment's error rate. Captains already come from MID/FWD.

Call it **"consistent observed 60+ history"**, never "safe", "guaranteed" or "90% likely". Its future
miss rate and production coverage have not been measured. Do not assign it the mean-90 diagnostic's
8.51% miss rate: that was a different, season-blended policy. No new holdout tuning is needed to
implement this definition, and the consumed 2025/26 holdout must not be relabelled as fresh evidence.

### Fixed consequences, not unresolved choices

- `90,90` and `60,60,60` can pass; `60,60`, `90,59,90`, or a known zero cannot.
- A single sub-60 result blocks primary selection until it leaves the ten-fixture window. Do
  not reset the window for an injury return, a new manager or convenient recent form. A verified
  different-club observation bounds a new spell, which must earn its own 180-minute record.
  First appearing in PFS or the daily capture is not a registration date: a new arrival without
  a verified boundary may need ten complete fixtures before pre-arrival gaps leave the window.
- Before sufficient current-season evidence, during stale syncs or the season changeover, the
  primary list may be empty and an XI may be infeasible. No fallback to last season as role proof.
- Short complete windows are allowed once they clear 180 minutes; print `N/N` and the window so
  two full appearances cannot read as a season of certainty.
- Fit players failing the primary rule remain possible bench purchases. Doubtful players retain
  the existing rule: bench candidates only with an underlying record, explicitly flagged. Other
  availability states remain excluded from the purchasable pool.
- Pins mean membership of the fifteen, not permission to start. No pin, formation, budget, club
  quota, solver relaxation, tool parameter or environment switch bypasses the XI rule.
- Primary pick replies list qualifying players only, up to ten; exclusion reasons are counted
  separately. Do not backfill the list with a second disguised recommendation list. Named risky
  players remain discussable through the existing stats/availability tools and strategy guidance.

## Task 1 — a coherent schedule view and explicit freshness

Files: `schedule/ScheduleSource.java`, `schedule/ScheduleFreshnessCheck.java`,
`price/DailyPriceFreshnessCheck.java`; new schedule-view and ranking fixture tests.

Extend `ScheduleSource` with a read-only `View` containing its `Snapshot`, immutable event and fixture
rows, and a completeness status/reason. Fixture rows carry id, assigned GW, home/away ids, kickoff,
started/finished, FDRs and capture timestamp; event rows carry id, deadline and capture timestamp.
`readView()` reads both tables consistently. The outer ranking read in Task 2 owns a read-only
REPEATABLE READ transaction and supplies one `asOf` instant; do not rely on inert self-invocation
annotations or a clock read per player. The existing fixture-listing methods keep their behaviour.

For ranking, a complete schedule means events 1–38 and the 380 distinct ordered home/away pairings
of 20 clubs, with no self-fixture, invalid assigned GW or conflicting id. Both tables must describe
the same capture timestamp. Null kickoff/GW is allowed as unresolved scheduling, not silently
removed. This guards against a partial snapshot manufacturing blanks or a shorter minutes window;
it is a read-side check, not a new schema or writer. `ScheduleSyncService` already writes both
blocks in one transaction, so its `NOW()` timestamps agree.

Freshness reuses the existing predicates, exposing their pure helpers rather than copying their
clock constants: prices require today's UK date from 02:00, yesterday before; schedule requires
it from 03:00, yesterday before. A future UK capture date is invalid. No same-season/fresh schedule
means no complete **current** minutes window for primary selection. Do not fall back to weekly
fixtures for that claim. Completed-season reference calculations can still use tapas fixtures.

Verify today/yesterday cutovers on both GMT/BST dates, a future date, no capture, mismatched seasons,
partial fixture/event sets, mixed timestamps, missing clubs/pairs and a single consistent read
while a new snapshot is written. Add a test-only full-schedule seed using fictitious clubs; pin
one capture timestamp instead of trusting independent default `NOW()` values in test inserts.

## Task 2 — one batch ranking-metrics reader with verified club identity

Files: new `tools/RankingMetrics.java`; `tools/PickTools.java`, `squad/CandidatePool.java`;
`testsupport/RankingSeed.java`, `tools/RankingMetricsIntegrationTest.java` and existing pool tests.

Replace the duplicated ranking `recent/windowed` aggregations with this one reader. Do not change
`ExpectedStatsTools`, player cards, historical tools or unrelated enrichment queries.

Contract: `RankingMetrics.load(Capture, MetricSeasons)` returns the chosen `asOf`, schedule `View`,
and one candidate record per daily element id: capture identity/availability, per-season observed
metrics, per-season `MinutesWindow`, blended metrics and eligibility from Task 3. Fetch the whole
capture before consumer filters; name uniqueness must see injured, high-owned and other-position
namesakes too. `PickTools` applies its own position/ownership filter afterwards; `CandidatePool`
retains its `a`/`d` purchase/body policy and returns a `Pool` with candidates plus this snapshot
context for `SquadTools`. This also hands KAR-35 the same view without a second schedule read.
Use aggregate queries per season, not a query per player or unbounded full-history row materialisation.

### Query/data contract

- Preserve same-season element-id matching, including players whose PFS rows precede the weekly
  player row. Across seasons use the existing case-insensitive `web_name` bridge, unique in both
  whole populations. Resolve each season's PFS player id explicitly; never join different seasons
  by element, team or fixture id.
- Current canonical fixture rows come from the matching schedule view; previous/completed-season
  rows come from `fixture WHERE season_id = ?`. `fpl_fixture.id`, `fixture.id` and PFS `fixture_id`
  are FPL fixture ids only **within the same season**. Do not join on `fixture.code` or `team.code`.
- Derive the actual historical club as `was_home ? fixture.team_h : fixture.team_a`, validating
  `opponent_team_id` against the other side. **Do not require or use PFS `player_team_id` as the
  club history.** The upstream `collect_points_against.py` stamps the bootstrap club onto a whole
  history and its upsert does not repair that field. Canonical fixture sides repair the read
  without modifying tapas rows. Fixture dates are the ordering authority, not stale PFS dates/GWs.
- Inspect verified played-season history in `(kickoff_time, fixture_id)` order. Its latest club
  must equal the same-season daily `team_id`. Unmappable history or contradictory opponent/side
  data makes current evidence unknown; do not silently discard it to find a convenient spell.
- Establish a lower fence only from the **latest positively observed different club**, using
  either a completed canonical PFS fixture's kickoff or an earlier daily row's `captured_at`
  for this same `(season, element_id)` with a valid different `team_id`. Batch the dated-membership
  lookup as well. Do not use `team_name` (historical names may be backfilled), `price_date` as a
  transfer instant, or observations after `asOf`. The fence means last known not at this club,
  not a guessed registration date; same-club observations cannot advance it.
- With no different-club fence, enumerate from **season start**, never from the first PFS/daily
  row. With a fence, exclude only club fixtures at/before it; every later fixture stays expected,
  including gaps before the first new-club PFS row. This deliberately abstains when the actual
  arrival time is unknowable, rather than declaring two surviving rows a complete recent history.
- Enumerate the club's expected completed fixtures independently, through `asOf`, ordered by
  `(kickoff_time, fixture_id)` newest first, taking at most ten. Freeze that set, then LEFT JOIN
  PFS on `(season_id, fixture_id, player_id)`. Any absent row or minutes outside 0–90 makes the
  window incomplete; do not replace it with an older appearance. An explicit zero consumes a slot.
- A more recent club fixture with a passed kickoff but no captured completion makes current
  evidence `RESULT_PENDING`, including live games or a stale postponement. Do not assume it was
  a zero, completed, or irrelevant. A finished row without usable chronology is also unknown.
- Previous/completed-season exposure requires every season fixture of a single verified club,
  not just fixtures since the first surviving player row. Multiple clubs or missing coverage
  lose that **exposure** side; valid historical per-90 ability evidence may remain explicitly
  labelled. This conservative v1 does not infer historical registration dates.
- `MinutesWindow` carries completeness/reason, club, date range, expected/observed counts, total
  minutes, appearances, 60+ count and latest two expected outcomes. Keep the whole-season 60+
  count for `SeasonBlend` separate. `starts` is not load-bearing: default zero cannot establish
  a known benching. Do not add a starts-based penalty or new ingest field in v1.
- Record coverage is not certified upstream finality. In particular, PFS `updated_at` is also
  advanced by this project's DefCon updates and cannot be called a minutes-sync timestamp.

Verify leading/interior/trailing missing rows, zeros, a missed DGW leg, blanks, out-of-order GWs,
future/live fixtures, deliberately mis-stamped `player_team_id`, transfers and reused season ids.
In particular: ten completed club fixtures with only the latest two `90` rows must give expected=10,
observed=2 and unknown eligibility. First same-club daily appearance cannot shorten it. Test both
PFS and dated-daily different-club fences, a missing post-fence fixture, and a newcomer becoming
judgeable once the latest ten are complete. A previous-season reused element id cannot set the
current fence; a leading gap also disables previous-season exposure. Retain the KAR-32/KAR-45
whole-population ambiguity tests. Missing current rows remain unjudged despite a strong previous
record; do not turn data absence into poor football ability.

## Task 3 — shared selection and exposure rates

Files: new `tools/MinutesModel.java`, `tools/SeasonBlend.java`, `tools/MinutesModelTest.java`,
`tools/SeasonBlendTest.java` and reader tests.

`MinutesModel.eligibility(...)` implements the five conditions above once. Its result is an enum
with reason: `ELIGIBLE`, `AVAILABILITY_RISK`, `MINUTES_RISK`, `INSUFFICIENT_CURRENT_EVIDENCE`,
`UNKNOWN_CURRENT_EVIDENCE`. Check availability/freshness/identity/coverage first, then known sub-60
outcomes, then the 180-minute support floor. A known `0,0` record is minutes risk, not hidden by
"insufficient evidence" or a positive old-season blend. Use the same result in both consumers.

For each complete season window of `N` fixtures calculate these historical rate inputs:

```text
appearance_rate = count(minutes > 0) / N
sixty_rate = count(minutes >= 60) / N
short_rate = appearance_rate - sixty_rate
expected_minutes = sum(minutes) / N
points_per_fixture = sum(total_points) / N
```

Add rates to `SeasonBlend`; its ramp remains unchanged and counts still add for evidence only.
Do not derive exposure by dividing added cross-season counts. Preserve per-season raw counts for
output; no blended percentage is an observed frequency. Treat each side's exposure rates and
points-per-fixture as an availability bundle, not independently filled missing fields. Missing
previous exposure uses current rates. When the resolved season is being played but its exposure
is unknown/empty, the per-fixture projection is **unavailable**, even if `SeasonBlend` could supply
old rates. Previous statistics remain historical explanation only. A completed-season fallback
may carry a historical per-fixture reference, never current eligibility. Keep the combined
180-minute ability guard distinct from eligibility.

No experimental two-pseudo-fixture prior, learned coefficient or probability certification enters
this slice. Tests pin `0 <= sixty_rate <= appearance_rate <= 1` and
`60*sixty_rate <= expected_minutes <= 90*appearance_rate`. Cover 0/1/59/60/90, unequal season lengths,
short history and the unchanged ramp at zero current qualifying matches.

## Task 4 — per-fixture point components, including DefCon

Files: `tools/ExpectedPoints.java`, `tools/ExpectedPointsTest.java`, reader/`SeasonBlend` aggregates.

Add `perFixture` without changing the meaning of the per-90 reference method:

```text
appearance = appearance_rate + sixty_rate
attack = (goal_points*xG90 + assist_points*xA90) * expected_minutes/90
clean_sheet = position_clean_sheet_points * sixty_rate * exp(-xGC)
```

Retain the existing unavailable-xGC guard and label its season-average conditional approximation;
this does not add dismissal timing or KAR-30's matchup model. The 59/60 boundary uses official
recorded minutes, excluding stoppage time. Substitution after 60 preserves eligibility; dismissal
does not protect a clean sheet from later team concessions.

DefCon has **no** 60-minute eligibility rule. Aggregate mean known actions and sample count
separately for 1–59 and 60+ appearances over the same windows. Blend the class means through
`SeasonBlend`, with separate evidence counts. Use the existing negative-binomial tail, thresholds,
dispersion and minimum two known observations **per class**:

```text
DC = 2 * (short_rate*tail(short_mean) + sixty_rate*tail(long_mean))
```

Known zero actions yields a zero tail, not `n/a`. A class with positive exposure and insufficient
known action data contributes no estimated term and labels the score partial. GK DefCon is zero.
Discard previous-season DefCon means/counts when its position's action definition differs from
this season's (DEF's CBIT versus MID/FWD's CBIRT, or GK); other metrics still blend. An unknown
previous position also makes those DC inputs unusable. Do not import the short-class assumption
into the full-match reference silently: it retains its long-appearance interpretation.

A zero exposure forecast receives zero appearance/attack/CS/DC components; unknown exposure has
no per-fixture projection, not a numeric prediction of zero. Preserve per-90 ability evidence for
explanation. Verify the equal-mean 60/60 versus 90/30 example: appearance plus CS is **3.60 versus
2.30** at conditional CS probability 0.40. Add short-appearance DefCon, zero/null, absent older
DefCon and position-family-change tests. No claim of empirically improved DefCon accuracy is made.

## Task 5 — integrate primary pick ranking and output

Files: `tools/PickTools.java`, `tools/PickToolsRankingTest.java`, pick integration/cross-season tests.

Filter primary eligibility **before** ranking/truncating. Replace realised `Pts/90` in the score:

```text
perFixtureScore = (1 - existing_realised_weight)*expectedPerFixture
                + existing_realised_weight*pointsPerFixture
```

Do not add appearance points again to realised points. Retain the realised-weight schedule and
FDR/ownership/minutes tie-break intent. KAR-35 subsequently makes the primary score horizon-based;
this ticket must not claim its per-fixture score already rewards doubles.

Print each pick's observed current-club `60+ N/N`, minutes, dates and any short-window note beside
its per-fixture score and the existing blend explanation. Distinguish raw current observations
from blended historical exposure inputs. If prior exposure is unusable but prior ability still
blends, say so rather than claiming every component uses the same mix; missing DC terms remain
labelled partial. Preserve daily capture and metric provenance; add the schedule check's exact
capture time in the evidence explanation, not a second season banner. No calibrated personal
probability or "guaranteed starter" language.

No qualifying players means a factual refusal/coverage message. Give counts by exclusion reason,
not ineligible names appended to fill ten slots. Database failures remain ordinary tool error
strings. Tests must show a high-per-90 substitute, a doubtful star and a last-season regular now
playing zero cannot enter either primary list, including at maximum realised weight.

## Task 6 — enforce the same hard XI rule in the squad builder

Files: `squad/CandidatePool.java`, `squad/SquadCandidate.java`, `squad/HorizonScorer.java`,
`squad/SquadSolver.java`, `squad/Feasibility.java`, `squad/SquadTools.java`, `squad/SquadFormatter.java`.

Carry minutes evidence/eligibility separately from `Tier`: an ability-ranked player may be
ineligible for the XI. Retain the existing fit-body/doubtful-with-record purchase policy. Unknown
projections have zero **optimizer weight** if needed but display `n/a`/unscored, not "0 expected
points". Known historical-only projections cannot certify current eligibility.

Use the shared expected per-fixture calculation in `HorizonScorer`, removing the outer minutes
multiplier. The squad objective stays expected-only with its existing coarse FDR adjustment;
only PickTools blends realised points. Replace `expectedMinutes`' added-count ratio with the
blended rate. Keep displayed minutes available to the formatter.

In `rulesOfTheGame`, force `x_i = 0` for every ineligible candidate in **all** solver entry paths,
beside the existing BODY prohibition. Remove `RELAXED_STARTERS_PLAY` and its ladder entry; only
premium/template defaults remain relaxable. Remove the now-unused expected-minutes constraint
arguments from solver overloads/call sites, not the evidence displayed to users. Do not retain an
unsafe compatibility overload. `mustInclude` may place an ineligible player on the bench only.

Add necessary eligible-position capacity checks to `Feasibility` (respect forced formations),
without pretending they prove all club/budget combinations feasible. The final refusal must name
the minutes restriction and constraints, not imply that raising budget necessarily fixes it.
A pin or relaxation cannot make an ineligible XI acceptable. Do not introduce a bypass parameter.

Verify both solver entry paths, every remaining relaxation step, pinned ineligible players,
formation/club-quota conflicts, valid low-cost benches, too few eligible starters and unchanged
legal-squad constraints. A short or infeasible result is the deliberate outcome, not a solver bug.

## Task 7 — contract alignment, verification and closure

Update tool descriptions, relevant system-prompt passages, the minutes RAG guide and defender/
differential/captain cross-references to the actual observed-history rule. Explain the conservative
ten-fixture recovery wait, small samples, stale-data abstention and lack of a future guarantee.
Update `AGENTS.md`'s ranking/window/solver conventions without altering the existing account-context,
season-provenance or DB-ownership exceptions. Record outcomes in STATUS and this progress line.

1. Demonstrate red seeded tests for the original cameo/mean-minute/relaxation failures, then green.
2. Run focused ranking, expected-points, blend, candidate, horizon, solver, freshness and source-view
   tests, followed by `./gradlew cleanTest test` using **only `backend/.env.test`** and the disposable
   container. All schedule/player data is test-owned; no new `realdata` oracle.
3. Run the 24 offline analysis tests without changing the frozen experiment or aggregate files.
4. Verify the actual minutes guide's chunking and real-corpus defender/captain retrieval after
   wording changes. Retrieval is not a measured future-selection error rate.
5. Branch/PR only; never push master or manually deploy. The completed implementation may close
   KAR-31 when the deterministic policy and scoring contract pass—not when a probability guarantee
   is invented. This planning change itself does not close the issue.
6. Production corpus reload remains an explicit, separate `--ingest` operation after review;
   a deploy alone does not refresh embeddings. No ingestion or production write is part of planning.

Verification commands from the repository root (Docker, Java 25 and the existing test embedding
key are prerequisites; `.env.test` must point to the disposable local database):

```bash
(
  set -e
  scripts/test-db.sh up
  cd backend
  source "$HOME/.sdkman/bin/sdkman-init.sh"
  set -a; source .env.test; set +a
  ./gradlew cleanTest test \
    --tests '*RankingMetrics*' --tests '*MinutesModel*' \
    --tests '*PickTools*' --tests '*ExpectedPoints*' --tests '*SeasonBlend*' \
    --tests '*CandidatePool*' --tests '*Horizon*' --tests '*Squad*' \
    --tests '*Schedule*' --tests '*DailyPriceFreshness*' \
    --tests '*IngestionService*' --tests '*StrategyToolsIntegration*'
  ./gradlew cleanTest test
  cd ..
  PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
    -s scripts/analysis -p 'test_calibrate_minutes.py'
)
```

No product choice remains deferred in this plan. Prediction calibration and live eligible-player
coverage remain honest limitations, not hidden prerequisites or permission to weaken the rule.

### Task 7 outcome

The tool descriptions, system prompt, minutes guide, related defender/differential/captain guidance
and `AGENTS.md` now state the same consistent observed 60+ history contract, the recovery wait
until ten later completed club fixtures, small-sample labels, stale-data abstention and
no-future-guarantee boundary.

Verification used the local disposable PostgreSQL database: the focused Gradle selection passed 267
tests with 0 failures in 42 seconds, and the full `./gradlew cleanTest test` passed 702 tests with 0
failures, 0 errors, 0 skipped in 1 minute 9 seconds (`backend/.env.test` → `localhost:55432`). All 24
offline Python analysis tests passed. The focused run included `IngestionService` and
`StrategyToolsIntegration`; that verifies test-corpus chunking and retrieval regressions, not a
production embedding reload or a measured future-selection error rate. An independent read-only
review of the whole diff against `origin/master` reported `No actionable findings.` The reviewer
did not run tests. No production ingestion or deployment was performed.
