# Shortlist comparison: observed stats and five editable gameweeks

**PROGRESS:** Task 2/3 done — comparison API and UI verified; integration/handoff next.

## Brief, authority and sources

The owner approved in conversation: a comparison shortlist with current price/availability,
season points and points per £m, **xG, xA and xGI**, five **gameweeks** (not five matches) of
FPL FDR, and sensible expected team clean sheets. Expand a player for season details, latest
five team fixtures, DefCon, ownership and set-piece roles. No new overall score, custom FDR,
player-point projection or minute forecast. They then requested careful planning with the new
factory, **one agent plan review**, correction, then implementation without approval pauses.
That exact review-count amendment replaces the factory's default repeated independent reviews:
implementation/final PR review will be parent-performed and labelled accordingly, not passed
by an imaginary independent reviewer. No merge/activation/production access authorized.

Factory input: PR #161 at `f30277e`, read from the separate clean factory worktree; it is still
unmerged, so do not stack this feature on it or copy the skill into this branch. Product base:
`0c933888c8b0409d052175d91ea5ebc6fc01d7e1`, branch `feat/shortlist-comparison`, worktree
`/home/vanka/dev/fpl-assistant-comparison`. Original checkout has unrelated uncommitted sync
changes and a credential file; preserve them. No other writer owns this task worktree.

| Requirement | Accepted source / current evidence | Acceptance boundary |
|---|---|---|
| A1 ownership and identity | Original `docs/superpowers/plans/2026-09-13-player-shortlist.md`, AGENTS account rules; `ShortlistStore`, `ShortlistController`, `ShortlistContext` | JWT-only comparison read, no-store; owner isolation; season/id join; membership API, removal, count and chat highlighting unchanged. |
| A2 observed comparison stats | Owner discussion above; `PlayerTools`, player schema, `DailyPriceSource` | Explicit season totals, current capture price/status, points/(now_cost/10), all three xG/xA/xGI. Null and zero distinct, no cross-season fallback labelled current. |
| A3 future schedule | Owner chose 5 GW; AGENTS horizon convention; `HorizonFixtures`, `ScheduleSource` | Same immutable view/instant, exact deadline exclusion, blank/double, unknown vs zero, invalid FDR shown unknown, truncation and no GW39. |
| A4 expected team clean sheets | Owner accepted existing model; `ClubRates`, `RankingMetrics`, `ExpectedPoints` | Sum exp(-projectedGoalsAgainst) over counted fixtures. Any missing projection => total unavailable, never partial sum as full. Zero for known empty horizon. Not player points/start probability. |
| A5 expanded observations | Owner accepted details; `ExpectedStatsTools`, `PfsReadSource`, `DataAge`, source schema | Season goals/assists/minutes/CS/saves/bonus; observed appearances and DefCon reading denominator; last 5 current-club completed fixtures including missing vs known zero rows; ownership and set-piece ranks. |
| A6 usable resilient UI | Existing ShortlistPage and shared provider, App account-keyed shell; owner asks comparison | Responsive sortable view, position filter, semantic expansion, keyboard/touch controls, non-color-only FDR/status, localized-source explanations; metric failure never removes membership or blocks removal. |
| A7 bounded safe delivery | AGENTS, DEVELOPMENT, factory amendment and current request | One read-only reviewer, parent writer, focused disposable tests, no full local backend suite, scoped commits, feature PR and exact-head CI status. |

Research covered primary/factory/original-shortlist worktree plan locations with shortlist/watchlist
and related source terms; the earlier minimal-page plan remains binding except its explicit
no-stats/no-fixtures scope, superseded by this conversation. Its account/immutable catalogue and
old-season persistence decisions remain unchanged. Current STATUS and AGENTS were inspected in
the baseline/factory intake; October importer proof applies to PFS reads, not by treating raw
physical defaults as observations. No ticket was supplied and no shortlist follow-up existed in
the previously retrieved open Kari list; do not invent a KAR identifier. Confluence/Cortex are
not established source systems for this repository. No external service/data query is needed.
Relevant code and existing test navigation are listed below; this document consolidates research
and planning rather than duplicating a separate research file. Search terms: shortlist, player
comparison, points per million, expected clean sheets, FDR, five gameweeks.

## User-visible contract

The comparison row shows player/club/position/season, current price, availability, season points,
p/£m, xG, xA, xGI, team expected clean sheets (prominent for GK/DEF) and five GW cells with
opponent, home/away and FPL FDR (1 easiest–5 hardest). xGI is xG+xA only when both exist. Points
per £m uses **season observed points / current price in millions** (60 / £6 = 10), not predicted
points or purchase/selling value. No composite rating. Sort by name, position, price, points,
p/£m, xG/xA/xGI or expected CS; unknown values always sort last. Position filter defaults all.

Expand a row via a native details/summary control. Show season goals, assists, minutes, clean
sheets, saves for GKs, bonus; observed appearances with a source label; DefCon hits/appearances
with a reading, position threshold and actions/90 (exclude missing readings and zero-minute
rows); captured ownership and rank for penalties/direct FKs/corners. Last five **current-club
completed fixtures** show GW/opponent/H-A, minutes, points, goals/assists, xG/xA/xGI and DefCon
actions. Enumerate fixtures before joining player readings: absent PFS is n/a, a real zero stays
0. Do not assert a missing row means not selected/registered. Do not mix prior-season history
into this five-match list. History status distinguishes absent/malformed schedule from zero
completed fixtures; owned provisional evidence is labelled as such. No new penalties for minutes.

Keep the established editorial/pitch visual language: Archivo body/Newsreader headings, existing
light/dark tokens, restrained green accents. Desktop uses aligned comparison columns; small
screens use stacked player cards with a compact numerical grid and five-GW strip, not page-wide
horizontal overflow. Expanded match history may have a labelled local scroll region. FDR has
visible numerals/text, not color alone; all buttons/summary have focus and adequate touch areas.
No new fonts, libraries, hero, animation system or global redesign.

## Data and degradation contract

New read-only `GET /api/shortlist/comparison` takes no user/player identifiers from a caller.
Resolve owner with `TokenSubject.userId(jwt)` and list that owner's existing shortlist. Keep
GET/PUT/DELETE membership responses unchanged so chat/catalogue behavior does not depend on
heavy metrics. Comparison returns no-store and does not call a model, spend message quota or
fetch external APIs. Failure yields generic 503, not SQL/connection details; the page retains
membership/removal and offers comparison retry. Empty shortlist returns without league reads.

Read membership, chosen daily capture, resolved metric season, ranking snapshot and supplementary
stats in one read-only REPEATABLE_READ service transaction. Reuse `RankingMetrics.load` once for
its coherent schedule/club rates; its transaction joins the outer transaction. Use its `asOf` for
all freshness/horizon decisions (fallback clock only if no snapshot exists). No per-player SQL or
new club-rate formula. Supplement with batched selected-player queries: daily fields/set pieces,
player season totals and guarded PFS rows. The complete league read for existing club-rate floors
is reused, not duplicated. Tests/inspection must ensure SQL request count does not grow per row.

- Identity remains `(season, elementId)`. Display current same-season club/position/name from a
  valid capture; never mutate the persisted catalogue. Old-season entries remain visible and
  removable with a historical-entry explanation, no new-season price/programme or substituted
  player's data. A missing capture identity leaves that player's observations unavailable.
- Price/availability/set-piece data use the selected daily capture. Only a fresh expected-UK-day,
  timestamp-coherent capture supplies **current** market values or a p/£m denominator. Missing,
  stale/future/invalid capture => unavailable with reason, not stale data called current.
- Use `SeasonResolver`/`metricSeasons`; season totals come from the existing `player` card
  source only when entry/capture/resolved playing season and player club/position agree. No
  previous-season fallback labelled current. Expose `player.updated_at` as a **record-write time**,
  not proven FPL sync time. It is valid to show stored totals while forecasts are unavailable.
- Read all supplementary match/DefCon observations via `PfsReadSource.cte()` with season-qualified
  selected IDs. Do not expose unverified owned rows or present absent optional fields as zero.
  For recent matches use canonical current-club sides and verify `was_home`/opponent against them;
  never trust PFS `player_team_id` to infer historical club membership. Unknown chronology yields
  unavailable recent history rather than a silently shortened last-five population.
- `HorizonFixtures.derive(view, asOf, captureSeason, 5)` is called once. A usable view is required
  for the five cells; unknown club windows cannot be displayed as five blanks. Started/finished
  fixtures stay excluded. An editable window near GW38 is visibly truncated; no editable round
  is not zero forecast. Include both matches of a double and an explicit blank in a known week.
- Team clean-sheet expectation reuses `ClubRates.projectedGoalsAgainst` on snapshot rates and
  the exact fixture opponent/venue, then exp(-xGA). Missing rate/league/coverage gives null,
  not zero. A known blank gives 0. Daily identity, metric season and schedule must be compatible.
  This is a team model without playing-time weighting, player eligibility/availability filtering
  or FDR multiplier. Explain lack of calibration, horizon total and current/previous model
  seasons separately from observed season totals. Optional per-fixture probability is shown in
  the fixture's accessible description. No forecast when playing-season metrics are unavailable.
- Give separate provenance: selected daily date/time; season-total record time; schedule capture
  and assessment instant/range; model season basis. PFS row source/evidence and sample-scoped
  `DataAge` wording must not borrow ranking receipt dates for a different history sample.

DTOs are explicit records/types (nullable numbers, not formatted numeric strings): response
metadata/horizon plus rows keyed by season/id; row market, season totals, p/£m, observations,
DefCon and fixture/expected-CS data carry their own missing reasons. Keep the response bounded
by stored shortlist size and five recent fixtures per player; no arbitrary new shortlist cap,
pagination protocol, configurable forecast length or generalized analytics framework.

Frontend comparison loading is page-scoped and separate from `ShortlistProvider`. Depend on
stable season/id membership keys, not unrelated pending state. Abort on unmount/account change
and ignore late responses after retry/membership changes. Merge only exact season/id matches
into the currently visible provider membership; late results cannot resurrect a removed row.
No refresh polling/background requests when the page is absent. Initial comparison failure,
loading or retry never replaces the shared membership error/loading behavior. Keep old stored
identity as fallback for display/removal, not as current-price proof.

## Decision tree / stress-test closure

Settled: five editable **gameweeks**, observed season p/£m, xG+xA+xGI, existing team CS model,
expanded basic/history/DefCon/set-piece stats (owner conversation). Preserve existing account,
catalogue and no-external-fetch boundaries (original plan/AGENTS). Missing and zero differ.
Probed: season-ID reuse, transferred player, blank/double/last GW/exact deadline, stale/missing
source, partial projection, missing optional metric, unavailable stats but removable shortlist,
account swap and late fetch, small screen and keyboard. These are technical correctness cases
settled by existing contracts, not new product choices. No question or renewed approval needed.
Deferred/out of scope: custom FDR, point/start forecast, overall rating, notes, new lists, sorting
by hidden model recommendations, cross-season auto-migration, polling and runtime/model validation
against production. A missing environment blocks only affected verification, not permission to
invent successful results.

## Dependency-ordered implementation and checks

1. **Comparison read API and safe projections.** Add a focused service/DTO in `shortlist` and
   a controller GET handler. Read no membership differently and change no writers/schema.
   Tests: arithmetic (price units/xGI), additive probabilities, missing versus blank, date
   bounds, unknowns, stale daily capture, archived same ID, changed club, missing player totals,
   actual guarded PFS optional values and zero-minute history. Real disposable-JDBC tests for
   batch joins/season boundaries, mocked collaborators only where explicitly stated; authenticate
   endpoint via existing local JWT signing support and verify owner/no-store/unauthorized paths.
   Reuse `RankingSeed` for 38/380 schedule and actual `RankingMetrics` integration where feasible.
   Tests first with honest expected failures; compile/setup errors are not RED. Commit green slice.
2. **Comparison UI and lifecycle.** Add typed comparison fetch hook and page presentation within
   current CSS Modules/tokens. Keep existing provider/membership operations. Tests: all columns,
   known zero versus n/a, totals/DefCon/fixture details, sorting/filter null handling, five-GW blank/
   double/truncation, retry, removal during pending load, account/unmount stale settlement, removal
   after metrics failure. Update existing ShortlistPage test mocks/call expectations. Run focused
   Vitest, build, lint and format; inspect mobile/dark/focus structure, browser check if available
   with isolated local stub data (never infer runtime UI from DOM-only tests). Commit green slice.
3. **Integration and handoff.** Parent reviews full diff and accepted plan-review findings, runs
   affected old shortlist/ranking/horizon security regressions sequentially, records exact evidence
   and gaps, updates STATUS/plan, pushes dedicated branch and opens PR against master. No local
   full backend suite; GitHub full CI is a separate merge gate. Do not merge/deploy.

Owned paths: new `shortlist` comparison service/tests; `ShortlistController`/`ShortlistApiTest`;
new frontend comparison types/hook/tests; `ShortlistPage` component/CSS/tests; plan and STATUS.
Any shared helper change must be minimal, behavior-preserving, have its own caller/test evidence,
and be recorded here before use. No planned dependency/config/schema change.

## Environment and review handoff

WSL2; preserve Gradle worker/heap limits and serialize every agent/build/test unit. At intake
the default Docker socket is unavailable; the existing daemon's `/run/kurssit-docker.sock`
works. No container is running and port 55432 is free. Use that existing socket without changing
the daemon and create dedicated loopback-only `fpl-shortlist-comparison-test-db` on **55432**
(the endpoint accepted by `TapasSeed`) with the checked-in schema. Never reset the shared existing container. Use dummy
provider keys for these non-RAG tests; no production .env or real embedding requests. Use the
DEVELOPMENT cgroup-v2 wrapper, time limit and 768 MiB host-memory stop boundary for Gradle.
Frontend dependencies/toolchain availability must be checked before installing anything.

One reviewer now reads this complete draft, original shortlist plan, AGENTS and actual connected
code at the named baseline. Source-only; no edits/build/tests/installs/live calls or nested agents.
Report stable findings with source/impact and smallest correction, including missing acceptance
or data semantics, not speculative extras. Parent records disposition and corrects the draft
before implementation; no further agent pass per owner's explicit limit.

## Findings, evidence and outcome

Reviewer run `5732ada7-191c-4e78-8177-79c42f7e56d0` completed source-only review of draft
SHA-256 `5e2d21f51982178b69f98344902ffba2a6965b6a6c2684f5e7f13be1bc33b773` at baseline
`0c933888c8b0409d052175d91ea5ebc6fc01d7e1`. Full report is retained in session artifacts as
`outputs/d7b29b11-9004-41e8-ab57-13d5fde53490/plan-review.md`.

- **SC-01 accepted/corrected:** port 55439 conflicts with TapasSeed's safety guard. Use free
  loopback 55432 with a task-owned container; no guard change or CI impersonation.
- **SC-U1 inconclusive:** league-wide load cost is unknown, not a proven defect. Add bounded
  full-population timing/statement-count/row-volume evidence; no speculative cache or new reader.
- Add explicit tests for sufficient historical rates plus incomplete recent coverage (no CS
  projection), flagged player with sufficient team evidence (still gets team CS), and the outer
  service's repeatable-read boundary. Existing helper tests alone do not prove these seams.

The reviewer did not approve implementation or execute tests; corrections are checked by the
parent under the one-review limit.

Task 1 outcome: comparison API uses one outer read-only repeatable-read transaction and existing
ranking/horizon/guarded-PFS readers. Auth regression first failed on the missing endpoint (404
instead of 401); then the endpoint and all 7 ShortlistApiTest tests passed. DTO arithmetic/model
and real-JDBC cases were added alongside implementation, not claimed as prior RED. An initial
integration fixture omitted required catalogue names; corrected the fixture, not the oracle.
Focused ShortlistComparison unit/integration, ShortlistApiTest and HorizonFixturesTest pass.
The outer-transaction test commits a concurrent change and proves supplementary totals retain
the original snapshot. A flagged player gets a team forecast; a missing recent club side blocks
it despite older usable rates. A zero-minute row stays zero and an absent reading stays null.

SC-U1 bounded observation (not a production benchmark): disposable population 600 players / 3000
PFS rows, one vs twenty shortlist entries; 18 queryForList calls for both, 5549/5682 returned rows,
304/491 ms. This counts queryForList only, not every JDBC statement or wire row. Source inspection
finds no per-entry SQL. Production latency/concurrent load remains unverified. Tests used the
owned local container and the 1536 MiB cgroup, dummy provider keys, no external API calls.

Task 2 outcome: page-scoped comparison loading, all accepted columns, position filter, numerical
sorts with unknowns last, five-GW strip and expandable observations. Membership/removal still use
the unchanged shared provider. Test-first page assertion failed because xG was absent; now 13
focused page/provider tests pass, covering retry, removal during loading, account remount,
season/id matching and null-last sorting. TypeScript/Vite build, ESLint and Biome pass. Removed
three obsolete test-rule suppressions; added none to the baseline. ES2022-specific local lint
exceptions retain supported sorting/Promise construction rather than requiring newer browsers.

Headless Chromium with a disposable profile and local stub responses rendered the real component
and provider at 390px light/dark and 1440px light. No page overflow or runtime exceptions; Space
opens native details and expanded history stays within its local scroll region. Visual inspection
caught cramped mobile controls; corrected their wrapping and reran the browser probe. Screenshots
and output are local `/tmp/shortlist-{390,1440}-{light,dark}.png` and `/tmp/shortlist-browser.log`
(actual variants: 390 light/dark and 1440 light). Initial probe setup errors (binary basename,
pre-load DOM) were harness errors and corrected, not product failures. This is isolated UI evidence,
not a signed-in live API smoke test. No browser profile, production auth or backend was used.
