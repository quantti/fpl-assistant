# KAR-9 — show the age and limits of stored statistics

**PROGRESS:** Implementation Task 2/7 done.

Issue: [KAR-9](https://linear.app/quantti/issue/KAR-9/surface-data-age-in-tool-output-tapas-tables-go-stale-silently).
Checked in Linear on 2026-09-10: Todo, High (2), no blockers. There are no Urgent Todo tickets;
KAR-8 is also High, but already has a local implementation-plan draft. KAR-9 is the next
unplanned item. Neither ticket's state was changed.

Baseline: `origin/master` at `7721754`, including the merged KAR-31, KAR-35 and KAR-42 work.
Planning worktree: `~/dev/fpl-assistant-kar9-plan`, branch `plan/kar-9-data-age`.
The original worktree's uncommitted documents are not part of this change. This is planning
only: no implementation, production query, test run, commit, push or deployment.

## Goal and scope

A season label must not make old statistics look current. When a tool uses stored Tapas data,
show the age of the relevant records where that is knowable; where it is not, disclose that
limit and distinguish the dates of the football sample from its unknown refresh time.

This is a presentation/evidence change, not a new freshness gate. Preserve season resolution,
source selection, row populations, ranking arithmetic, eligibility, solver constraints and API
schemas. Do not add a TTL cache, warning threshold, sync job, table, column, trigger, dependency
or chat-time external call. Do not modify the Tapas project. In particular, this ticket does
not migrate the remaining Tapas-backed FDR readers to `fpl_fixture`.

The dated 85-day-stale observation in `docs/STATUS.md` (Daily price capture, 2026-08-18) explains
the ticket. It is not a measurement of today's production data.

## Source findings that constrain the solution

Paths below are relative to `backend/src/main/java/fi/karivanttinen/fplassistant/` unless stated
otherwise. The checked-in schema is `backend/src/test/resources/db/tapas-schema.sql`.

| Source | What its timestamp can actually establish |
| --- | --- |
| `player.updated_at` | Last recorded player-row write. Tapas `scripts/scheduled_update.py::sync_players_from_bootstrap` sets it on conflict updates. It is not an upstream fetch timestamp or proof that an entire sync completed. |
| `fixture.updated_at` | Last recorded fixture-row write; the fixture upsert explicitly stamps it. A fixture refresh does not prove that the PFS metrics refreshed too. |
| `gameweek.updated_at` | Recorded row-write time, not necessarily an FPL fetch: Tapas `collect_manager_snapshots.py::ensure_gameweek_exists` can insert a local placeholder. |
| `team.updated_at` | Not a reliable last-write marker. The regular `collect_points_against.py::sync_teams` conflict update leaves it unchanged and the table has no update trigger. Do not use it to date joined club labels. |
| `player_fixture_stats.updated_at` | Any row modification, including a DefCon-only update. It cannot establish the refresh time of minutes, points, xG or xA. `created_at` cannot substitute: it dates first insertion. |
| `player_fixture_stats.kickoff_time` | Date of a stored observation's match, not its ingestion/refresh time and not proof that all newer matches are present. |

Critical evidence: `tapas-schema.sql:17-24,448-451` contains `trg_pfs_updated_at`, a BEFORE UPDATE
trigger which sets `NEW.updated_at = NOW()`. `defcon/DefensiveStatsSyncService::applyAll` and
`DefensiveStatsBackfillService::applyAll` update the four enrichment columns only, firing that
trigger even when the original football metrics have not changed. A fresh PFS row-write label
would recreate the false-freshness bug this ticket is meant to fix.

All five `updated_at` columns are nullable defaults, not `NOT NULL`. A maximum ignores nulls
and a single recently written row can hide older rows. There is no reliable per-source completed
sync marker in this project's six-table schema contract. Do not introduce a dependency on
Tapas `collection_status`: a whole-job timestamp would not prove this player's/window's
coverage, and that table is outside the current schema/test contract.

## Output contract

Keep every existing `Data source:` banner and its source/season attribution. Add evidence near
the data it describes, not to `SeasonContext` or `SeasonResolver`: neither knows which tables or
rows a caller actually used. Do not add a fourth `Data source:` line to ranking replies.

### Recorded writes: player, fixture and gameweek

Use the label **`Stored-data age:`**, explicitly naming the source and scope. For example:

> Stored-data age: player record for Okafor, season T-2026-27, last recorded write
> 2026-09-03 07:00 UK (7 days ago). This is a database record time, not a live FPL check.

For multiple contributing rows, print the oldest and newest recorded writes and their ages;
collapse equal endpoints to one timestamp. Describe the range as **record writes**, never as
one completed sync or a football observation window. Report missing timestamp counts, e.g.
`1 of 3 fixture records has no write timestamp`; do not make the dated subset sound complete.
A gameweek label additionally says its row can be locally created and does not certify an FPL
refresh. No reliable timestamp is asserted for club-name joins from `team`.

- Scope is the rows actually displayed or used in a displayed calculation, after name preference,
  season selection and window limits. Never use an unfiltered/table-wide `MAX(updated_at)`.
- Use `UkTime` for absolute UK timestamps and one explicit `asOf` instant for relative ages.
  Age is elapsed duration: `<1 hour ago`, whole hours below 24 hours, then whole 24-hour days,
  rounded down. Do not use calendar subtraction across DST or emit negative ages.
- Null timestamp: `write time unavailable`. No contributing rows: `no stored rows used; age
  unavailable`. Neither means fresh, zero days old, or a successful current-season sync.
- A future write timestamp is printed as a future-dated anomaly with `age unavailable`, never
  clamped to today. A range containing one must disclose it rather than claim an overall age.
- No automatic `STALE after 7 days` rule: the upstream job can legitimately wait for a finalized
  gameweek, and a completed-season archive need not refresh. Show the age; retain historical
  season labels without calling an old archive a failed current-season sync.

### PFS metrics: unknown refresh, dated football sample

Do **not** display PFS `updated_at` as metric freshness. Use one shared, fixed notice:

> Metric refresh: the core-stat sync time for player_fixture_stats is unavailable; row writes
> can update defensive contribution alone. Fixture dates describe the sample, not its refresh.

`ExpectedStatsTools` also shows **`Stored match coverage:`** per season represented in the
returned window: the oldest/latest known match dates, age of the latest, and missing date
counts. It describes stored rows only, not complete coverage through that date. Current and
previous seasons never share one range. Null/future match dates remain explicitly uncertain;
never borrow dates from a different season, player, club or the upcoming schedule.

The ranking tools already print current-club window dates and sample counts. Retain these and
add the unknown-refresh notice; do not relabel a `MinutesWindow` boundary as the latest received
statistic. Its dates describe expected completed fixtures, including missing observations when
the window is incomplete. No new ranking query or coverage aggregate is needed in this ticket.

An unavailable core-sync timestamp alone is not a new reason to change a tool's existing
eligibility result. Known/unknown metrics, actual missing fixtures and the established capture
freshness rules remain exactly as before.

### Coverage matrix

| Reader | Addition | Deliberately unchanged |
| --- | --- | --- |
| `PlayerTools.getPlayerStats` | Each displayed Tapas player row's write time in its own seasonal block | Daily identity/price/availability block, matching and dual-season selection |
| `GameweekTools.getGameweekInfo` | Each returned fallback gameweek row's recorded-write age | FPL schedule-capture path and clock-derived status |
| `FixtureTools.getUpcomingFixtures` | Selected starting-gameweek row age; write span for the final scheduled + postponed fixture window | Capture path, distinct-GW window, ordering, scores and postponements |
| `FixtureDifficultyTools.getFixtureDifficulty` | Starting-gameweek age; write span of displayed scheduled + postponed fixtures | Tapas source, next-N-fixture semantics and rated-only average |
| Both `ExpectedStatsTools` methods | PFS refresh notice and returned-window match coverage per season | Existing filters, fill, aggregates, limits and ambiguity handling |
| Both `PickTools` methods and `SquadTools.buildSquad` | PFS refresh notice beside existing source/evidence headers, including data-dependent refusals | Three source lines, snapshot, date windows, ranking/eligibility and all numeric results |
| `getMyTeam` chat enrichment | Ages attached specifically to Tapas xG/xA and FDR; starting-gameweek metadata | FPL squad-fetch provenance, auth context, call budget, season gate and curated REST response |

No new age banner on daily-price/availability/set-piece tools, FPL schedule-capture replies,
RAG, historical-match tools, or mini-league ownership comparisons. Those have different existing
attribution schemes and do not consume the undated Tapas metrics addressed here. A no-match or
DB-error result must not invent a timestamp: preserve its failure meaning and state the missing
evidence where appropriate, not an age from some other row.

## Task 1 — implement the small, pure age formatter

Proposed files: `tools/DataAge.java` and `tools/DataAgeTest.java` in the corresponding main/test
packages. Keep this a formatting/aggregation helper, not a freshness service or query layer.
It accepts typed timestamps/ranges and `asOf`, distinguishes empty/undated/future-dated input,
and owns the shared PFS notice. Reuse `UkTime`; support the JDBC instant-bearing forms already
used here (`Timestamp`, `OffsetDateTime`, `Instant`), without inventing a timezone for a local
or otherwise unsupported value.

Write tests first for zero/exactly-one-hour/exactly-one-day/multi-day age, GMT/BST including a
DST transition, mixed old/new/null timestamps, all-null versus no rows, future timestamps,
single versus multi-row ranges and a non-UK JVM default timezone. Keep the wording and units
pinned. Do not add a severity enum, configurable thresholds or a cache.

Verify: focused `*DataAgeTest` and existing `*UkTimeTest`.

## Task 2 — date player cards and fallback gameweek rows

Files: `tools/PlayerTools.java`, `GameweekTools.java`; `PlayerToolsTest.java`,
`GameweekToolsTest.java`, `GameweekToolsCaptureTest.java`.

Select `p.updated_at` in the existing two-season player query and `updated_at` in the fallback
gameweek query. Render from the same row that supplied the values, not a subsequent timestamp
lookup. Player name preference is applied before describing the selected data; different
namesakes and different seasons cannot share the first match's timestamp.

Use the project's optional `ObjectProvider<Clock>` pattern, defaulting to a system clock, to
capture one `asOf` at entry for these new age calculations. Do not rewrite the independent
capture path's clock behavior. Keep the helper independent of Spring.

Seed explicit timestamps after `TapasSeed.ensure`; restore modified shared rows or use isolated
rows with rollback. Prove that a fresh current-season player does not refresh its previous-season
block, a fresh daily capture does not refresh old player statistics, null/future dates remain
unknown, and a name filtered out before display cannot determine the displayed age. Pin distinct
ages for current/next gameweek rows and no added Tapas-age text on schedule-capture replies.

Verify: focused player/gameweek tests, including unchanged values, source banners and no-match
behavior. There must be no additional SQL round trip.

## Task 3 — date the actual fixture outlook and its selection input

Files: `tools/FixtureTools.java`, `FixtureDifficultyTools.java`; their fallback, format,
integration and capture tests.

Extend the existing start-gameweek selection to return both the chosen id and its `updated_at`
in the same statement. Preserve its exact precedence: current, then next, then minimum id,
with the existing season filter and empty result behavior. This is not a new shared horizon
implementation or a clock-based selection change.

Select `f.updated_at` with both scheduled and postponed rows. For `FixtureTools`, compute the
span only after `window(...)`; for FDR, include exactly the rows returned by its existing limits.
Label fixture age separately from the gameweek row which selected the starting boundary, so a
fresh fixture does not hide an old selection flag. If no fixture rows remain, do not give them
the gameweek's age; if no gameweek exists, do not manufacture a timestamp from the season.

Regressions: old selected rows beside a fresh unrelated club/season/out-of-window row; an older
postponed row; a mixture of known/null dates; current/next/minimum selection; no gameweek;
empty fixture population; exact existing DGW membership, FDR average and unknown-FDR handling.
A future kickoff is not a future write timestamp. Capture-path output remains unchanged.

Verify: focused `*FixtureTools*` and `*FixtureDifficultyTools*`. No new query per row or club.

## Task 4 — expose expected-stat sample age without faking its sync date

Files: `tools/ExpectedStatsTools.java`, `ExpectedStatsToolsIntegrationTest.java`,
`ExpectedStatsToolsCrossSeasonTest.java`; add focused formatter cases if necessary.

Carry `pfs.kickoff_time` through both current/previous player queries. For team aggregates, carry
min/max kickoff and total/dated source-row counts in each existing fixture group. Preserve the
original grouping, ordering, limit, sums and MAX-conceded calculation. Summarize those metadata
fields only over the returned groups, separately by season; SQL MIN/MAX must not conceal
null-dated source rows. Distinguish source-row counts from the existing fixture counts.

Render the common unknown-refresh notice and coverage before the numeric rows, after the existing
season banners. No new `Data source:` banner, per-player lookup, source-table join or live call.
No rows still means no fixture-level record, not a zero-day-old record. Null/future coverage does
not remove observations or silently change totals: label the date uncertainty instead.

Add a seeded regression reproducing the real PFS trigger: insert an isolated old-dated PFS row,
then update only an enrichment column. Confirm `updated_at` changes while minutes/xG and the
printed sample dates do not, and the reply still says core sync time is unavailable. Do not
disable the trigger or attempt to force an old timestamp with UPDATE (the trigger overwrites it).
Pin current/previous coverage independently, the limited window rather than all history, and
partial/all-null dates in a team aggregate.

Verify: focused `*ExpectedStatsTools*`, with existing numeric and cross-season tests unchanged.

## Task 5 — disclose ranking refresh limits without touching selection

Files: `tools/PickTools.java`, `squad/SquadTools.java`, `squad/SquadFormatter.java` and their
provenance/integration tests. `RankingMetrics`, `CandidatePool`, the solver and scoring formulas
are not implementation targets for this task.

Use the same PFS notice after the existing source header/clarification, before numeric evidence;
keep the three source lines in price/metric/schedule order. Include the notice on existing
source-bearing no-candidate, unknown-horizon and infeasible-XI refusals. Do not add a header to a
malformed-constraint/name-resolution error that currently has no data block.

Pin both ranking tools, a solved squad and those data-dependent refusal paths. Prove that changing
only row-write timestamps changes neither pick order, horizon Scores, XI eligibility nor squad
projections. Keep all current/previous attribution, sample dates and schedule evidence assertions.
There must be no added SQL, no second clock read inside snapshot-backed formatting and no use of
`player.updated_at` or `fixture.updated_at` as a proxy for PFS refresh.

Verify: focused `*PickTools*`, `*SquadFormatter*`, `*SquadTools*`, `*RankingMetrics*`, `*Horizon*`.

## Task 6 — keep My Team's fetched squad and old enrichment distinct

Files: `myteam/SquadEnrichment.java`, `MyTeamService.java`; `SquadEnrichmentTest.java`,
`MyTeamServiceTest.java`, `MyTeamToolsTest.java`, `MyTeamResponseTest.java`, `MyTeamControllerTest.java`.

Carry the player's write timestamp alongside each `Depth`, plus the write span of the fixture
rows actually admitted to that club's FDR calculation. Extend the existing starting-gameweek
query with its selected-row metadata as in Task 3, kept separately on `Enrichment`. Do not take
an age over every fixture fetched: `addDifficulty` currently keeps at most five rated fixtures
per club, skips unknown ratings, and a sixth fixture must affect neither the average nor its age.

Render a concise age annotation with each displayed Tapas xG/xA and FDR value; render the shared
gameweek-selection age once with the enrichment provenance. Use `Result.Squad.fetchedAt()` as
this reply's one `asOf`. If a value is not displayed, do not attribute other fields to its age.
A null timestamp cannot remove otherwise available depth. Preserve per-element/season mapping
and the same behavior for the continuing Free Hit squad. Adjust the continuing block's current
claim that all statistics are "today's" to distinguish its FPL player snapshot from the dated
Tapas xG/xA/FDR; do not otherwise refactor bootstrap-cache provenance.

Keep the account in `ToolContext`, never in a tool parameter. The enrichment metadata stays on
the internal service result: `MyTeamResponse` is a deliberate allowlist and must still omit
statistics/enrichment. No frontend/REST contract change or extra FPL call.

Pin different players/clubs' ages, the sixth/unknown-rated fixture exclusions, mixed null dates,
fresh FPL squad with old enrichment, Free Hit's two squads, missing bootstrap elements, and the
changeover gate (no attempted historical-id enrichment). Retain `$.enrichment` absence and auth,
no-store and call-budget regressions.

Verify: focused `*SquadEnrichment*`, `*MyTeam*` and existing Free Hit tests.

## Task 7 — prompt, full verification and implementation handoff

Update only the relevant attribution guidance in `prompts/system-prompt.st` and `AGENTS.md`:
season, stored-row write time, match date and source capture are distinct. In answers based on
Tapas data, relay the supplied age/unknown-refresh qualification with the affected numbers;
never infer freshness from `currentSeason`, an upcoming kickoff, a recent DefCon write or a
fresh daily capture. Unknown core-sync time is a disclosure, not a new eligibility rule. Do not
call a completed-season archive a stalled sync merely because its dates are old. Keep all RAG,
historical, daily/schedule and multi-source exceptions intact.

Run the focused tests and then the full disposable-database suite. Run frontend tests/build as
regression checks even though frontend source is unchanged. Inspect seeded tool transcripts for
these acceptance cases: fresh daily price + old player card, two differently dated seasons,
fresh fixture + old gameweek selector, old PFS sample + new DefCon write, unknown dates, a solved
ranking with the three source lines, and fresh My Team fetch + old enrichment.

The evidence for acceptance is deterministic tool output and unchanged calculation tests, not a
claim that an LLM will always paraphrase correctly. Review the prompt separately; no paid live
chat or production fault injection is required. Record any live conversational verification only
if explicitly authorized and actually performed.

Update `docs/STATUS.md` with outcomes and the honest limit: this ticket surfaces recorded ages
and unknown core-sync time; a trustworthy PFS source-refresh timestamp still requires an upstream
writer/coverage contract outside this task. Update this plan's single progress line once per
completed task. Execute one numbered task per bounded worker unit and return control between
tasks sharing the worktree.

Once implementation is authorized and verified: commit on its dedicated feature branch, push,
open a PR to `master` with `Closes KAR-9`, then review the PR. Apply review fixes as new commits,
push and re-review. Only the owner merges. No direct master push or manual deployment.

## Planning review

An independent read-only review of this plan against `7721754` found no actionable issues.
It checked the trigger/enrichment distinction, selected-row scopes, ranking coverage and the
My Team chat/REST boundary. Source/schema inspection supports the plan; no live schema query,
runtime test or model-response check was performed. True PFS source-sync tracking remains an
explicit limitation, not a guessed timestamp or an unresolved implementation choice.

## Verification commands — proposed, not executed

Prerequisites in the implementation worktree: Java 25/SDKMAN, Node 24, Docker, ignored env files
configured as `docs/DEVELOPMENT.md` describes. A fresh worktree does not inherit ignored env files.
`backend/.env.test` must target the disposable local DB (`localhost:55432`), never Supabase.
The full backend suite uses the existing OpenAI embedding key; frontend build uses the existing
publishable Supabase key. No new credentials are needed.

```bash
# From the implementation worktree root; SDKMAN init is incompatible with set -u.
(
  set -e
  scripts/test-db.sh up
  cd backend
  source "$HOME/.sdkman/bin/sdkman-init.sh"
  set -a; source .env.test; set +a
  ./gradlew cleanTest test --tests '*DataAgeTest' --tests '*UkTimeTest' \
    --tests '*PlayerTools*' --tests '*GameweekTools*' --tests '*FixtureTools*' \
    --tests '*FixtureDifficultyTools*' --tests '*ExpectedStatsTools*' \
    --tests '*PickTools*' --tests '*RankingMetrics*' --tests '*Horizon*' \
    --tests '*Squad*' --tests '*MyTeam*'
  ./gradlew cleanTest test
)
(
  set -e
  cd frontend
  npm test
  npm run build
)
git diff --check
```
