# KAR-45: Safe candidate metrics across the season changeover

**PROGRESS:** Task 4/4 done — regression suite green and handoff documented.

**Ticket:** [KAR-45](https://linear.app/quantti/issue/KAR-45/candidatepool-attaches-the-completed-seasons-metrics-by-element-id)
— Todo, **Urgent (1)**, checked in Linear on 2026-09-08. The only Urgent item among Kari's
Todo tickets; no blocking relations or comments. Related KAR-32 already supplies the matching
pattern in `PickTools` and the shared `DailyPriceSource.latestCapture()` reader.

**Goal:** A new-season candidate must never inherit another player's completed-season metrics
because FPL reused an element id. Keep today's identity, club, price and availability from the
daily capture, and attach historical statistics only through an unambiguous name match.

## What the code does now

- `squad/CandidatePool.load()` joins `windowed c` on `metric.played().seasonId()` and
  `pool.element_id` regardless of the capture's season. The season predicate scopes the metric
  row but does not make ids comparable across seasons.
- During the changeover `SeasonResolver` returns a completed season, and
  `MetricSeasons.previous()` is empty. The existing `prev` name bridge cannot repair this case.
- `CandidatePoolCrossSeasonTest` exercises early-season blending **after** the resolver has
  switched to the new season. `CandidatePoolIntegrationTest` checks that a newer capture is
  loaded, but not whose statistics its players receive.
- `PickTools.candidates()` already distinguishes the id and name paths. Importantly, its name
  counts cover the **whole capture before availability filtering**. `CandidatePool` currently
  counts only available/doubtful players, so an injured namesake can hide an ambiguity.
- `CandidatePool.latestCapture()` duplicates the SQL in `DailyPriceSource`. Its `Capture` record
  is used by `SquadTools` and existing tests; retain that API and delegate the lookup.

## Scope and constraints

Production changes belong in
`backend/src/main/java/fi/karivanttinen/fplassistant/squad/CandidatePool.java` only.
No schema, sync, frontend, prompt, solver, scoring-formula or season-ramp changes. Preserve
`SquadTools`' separate daily-capture and metric-season provenance lines.

Use the existing case-insensitive `web_name` bridge, not fuzzy matching or a new identity
service. Missing or ambiguous historical identity means no metric attachment: a fit player
stays a `BODY` with empty metrics; an unrankable doubtful player remains excluded. Never fall
back to an id match across different seasons.

Tests use `TapasSeed` and the disposable local database through `backend/.env.test`, never
Supabase. Assert the daily table ends in `_test` before writes. Clean only test-owned rows,
scoped by season, date and ids, before and after each case; do not delete or rename shared seed
players to construct an edge case.

When implementation is authorized, use a new `kar-45-candidate-pool-changeover` branch from
up-to-date `origin/master`, carrying this plan with it. Do not add the fix to the current
mini-league branch. Never push to `master`, bypass hooks, deploy manually or merge the PR.

## Task 1 — Reproduce the wrong-player attachment

**Create:**
`backend/src/test/java/fi/karivanttinen/fplassistant/squad/CandidatePoolChangeoverTest.java`.

Use `TapasSeed.ensure()` without starting the new season. Assert that the resolver chooses
`POPULATED_SEASON_CODE`, `currentSeason()` is false and `metric.previous()` is empty. Seed the
daily capture under `CURRENT_SEASON_CODE` at a fixed date. Pass that exact `Capture` to `load()`
so metric-join tests do not depend on the latest date left by another test class.

Cover these cases with seeded, exact numeric expectations:

1. Swap Okafor and Bramley's ids in the capture: new Okafor is `9202`, new Bramley is `9201`.
   Okafor must receive his own `xg90 = 1.25 * 90 / 270`, 270 minutes and 3 club fixtures;
   Bramley receives `xg90 = 0.75 * 90 / 180`, 180 minutes and 2 club fixtures. Assert each
   candidate appears once and retains its captured id, club, price and availability. Include
   mixed-case spelling to prove the name match is case-insensitive.
2. A new, unmatched name reusing an old player's id stays `BODY` with empty metrics, not
   `RANKABLE`. Repeat with doubtful status: that candidate is excluded.
3. Two fit captured players sharing the historical name both stay `BODY`. An injured namesake
   is excluded from the candidates but must still prevent the fit namesake's metric attachment.
4. Two `player` rows in the metric season share the name, even though only one has fixture
   statistics: the bridge must refuse both possible identities. Insert a test-owned twin with
   a spare id rather than modifying an existing seed row.
5. Compatibility: when capture and metric season **agree**, a changed captured display name
   still attaches metrics by id. Also seed two appearances for a test-owned id with no weekly
   `player` row: its same-season metrics must survive, guarding against an accidental inner join.

**Verify:** Run `./gradlew cleanTest test --tests '*CandidatePool*'` using the setup below.
The wrong-id and unsafe-ambiguity cases must fail on their metric/tier assertions before the
fix; the existing same-season and blending cases must remain green. Do not weaken old assertions.

## Task 2 — Select the join by the two seasons

**Modify:** `squad/CandidatePool.java`.

1. Split the daily CTE into `whole_capture` (season/date predicate and lowercased-name count)
   and `pool` (the existing availability filter). Count names before filtering out any status.
2. Add `cur` over `windowed`, restricted to `metric.played().seasonId()`. Use a **LEFT JOIN**
   to `player` on both player id and season id to add `LOWER(mp.web_name) AS k` and the existing
   `nameIsUnique("mp") AS unique_name`. Missing weekly rows must not break same-season id joins.
3. Choose the final `LEFT JOIN cur c` predicate using
   `capture.season().equals(metric.played().code())`:
   - same season: `c.player_id = pool.element_id`;
   - different seasons: `c.k = LOWER(pool.web_name) AND c.unique_name AND pool.name_count = 1`.
4. Keep the fixture-denominator join keyed to the **matched metric row**,
   `cc.season_id = c.season_id AND cc.player_id = c.player_id`, not the captured element/team id.
   Keep the `prev` bridge, metric windows, `cur_`/`prev_` columns and `SeasonBlend` unchanged.
5. Check SQL formatting placeholders and JDBC parameter order against the revised CTE order.
   Keep ambiguity guards in the join, not a final `WHERE` that would discard fit bodies.

**Verify:** All Task 1 and existing candidate-pool tests pass, including the established 30%
blend, 450 combined minutes, defensive-contribution metrics and club-fixture denominator tests.

## Task 3 — Delegate capture selection

**Modify:** `squad/CandidatePool.java`.
**Create:**
`backend/src/test/java/fi/karivanttinen/fplassistant/squad/CandidatePoolCaptureTest.java`.

Inject `DailyPriceSource`. Replace the duplicate lookup SQL in `latestCapture()` with
`daily.latestCapture().map(c -> new Capture(c.season(), c.date()))`. Keep `CandidatePool.Capture`
and `load(Capture, MetricSeasons)` unchanged, so `SquadTools` and its callers need no edits.
No shared SQL abstraction or unrelated unused-field cleanup.

Add plain JUnit/Mockito tests before implementation: a supplied capture is mapped with exactly
the same season/date; an empty source stays empty. Verify delegation occurs once and this method
issues no queries through CandidatePool's own `JdbcTemplate` or `SeasonResolver`.

**Verify:** The new tests pass, Spring still wires `CandidatePool`, and the existing
`DailyPriceSourceCaptureTest` and candidate-pool capture-date/changeover tests pass. No production
changes to `DailyPriceSource` are necessary.

## Task 4 — Regression verification and handoff

Run from the repository root for setup, then from `backend/`:

```bash
scripts/test-db.sh up
cd backend
source "$HOME/.sdkman/bin/sdkman-init.sh"
set -a; source .env.test; set +a

./gradlew cleanTest test \
  --tests '*CandidatePool*' \
  --tests '*DailyPriceSourceCaptureTest' \
  --tests '*PickTools*' \
  --tests '*SquadTools*' \
  --tests '*SeasonBlendTest'
./gradlew cleanTest test
```

Inspect JUnit results; `UP-TO-DATE` is not evidence that tests ran. No live-season data or real
chat call is needed to prove a deterministic SQL identity bug. Existing squad-tool tests guard
the unchanged response/provenance contract; the new pool tests prove metric ownership.

After verification, append a dated KAR-45 entry to `docs/STATUS.md`: why ids were unsafe, why
both name guards see unfiltered populations, why unresolved players remain bodies, and the
actual test results. Update this plan's single progress line once per completed task.

Review the diff for scope, then push the implementation branch through the normal hook and
open a PR with `Closes KAR-45`, the plan link and verification in at most eight body lines.
The owner merges. Do not mark the ticket completed merely because planning is finished.

**Planning verification:** Read the ticket, relations and comments plus the current SQL,
consumers, seed data and regression tests. An independent read-only review confirmed the
approach and added the missing-weekly-row and explicit-capture test requirements. No application
code was changed and no tests, database queries, ticket updates, commits or deployments were run
while preparing this plan.
