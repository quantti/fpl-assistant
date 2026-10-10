# Incremental FPL rows and a checked-gameweek final sweep

**PROGRESS:** Investigation and proposed plan complete; implementation 0/7. No runtime, database, scheduler or production-mode change. Await owner approval of this replacement contract before implementation.

## Decision being proposed

Write independently valid player-fixture rows as they arrive, including provisional rows. Replace
changed values when FPL changes them. When FPL reports the gameweek finished and checked, run a
fresh final sweep, apply the last differences, and record its successful completion. Do not make
publication wait for two identical observations or for every other player to arrive.

The owner explicitly accepted provisional values and later corrections on 2026-10-10. This is a
change to the old **publication assurance contract**, not permission to fabricate absent values,
mislabel incomplete coverage, or remove source validation. Approval of this plan does not activate
new code. The existing ACTIVE92 runtime and observation-only monitor are left untouched; the owner
separately authorised continuous operation without automatic monitoring shutdowns on 2026-10-09.

Proposed defaults: an hourly collection cycle, changed-only PFS upserts, and one successful final
sweep per checked gameweek revision. No routine all-player daily history sweep or +2h/+24h/+48h
republication. Daily price/availability and schedule capture contracts remain in place.

## Why we built the current system

The original problem was real, but its solution bundled three different concerns together.

1. **Incomplete comparison, not weak Arsenal players.** The 2026-09-13 wildcard investigation
   found a normal-looking squad excluding Arsenal while fixture 36 was stored unfinished and
   Arsenal's GW4 PFS rows were absent. Fourteen clubs had elapsed unfinished fixtures. The tool
   could optimise over the surviving clubs without admitting the population was incomplete.
   The original request's exact tool invocation and the upstream cause were not recovered.
2. **Ingestion ownership and availability.** Tapas' collection waited for a new
   `events[].data_checked` gameweek. The assistant's DefCon-only UPDATE job could not insert the
   missing PFS rows. The owner chose this service as the core writer, with updates before a whole
   gameweek was checked. Writer isolation also addressed hidden/manual Tapas writers, unsafe
   missing-to-zero coercion and current-club attribution of pre-transfer matches.
3. **A conservative, chosen publication rule.** We then required a complete fixture manifest,
   two unchanged post-finish observations separated by ten minutes, and a current verified
   receipt before any owned fixture rows became readable. The original plan calls this a
   **stability heuristic, not upstream finality**. It was not a requirement imposed by FPL.
   Fixture-specific demand floors, repeated history work, blocked/ready queues, canonical-parent
   selection and overdue recovery were added to make that protocol progress safely.

Primary evidence:

- [Wildcard incident and boundaries](2026-09-13-wildcard-data-coverage.md), especially
  “Incident and confidence”, “What the sync code establishes”, and “Proposed behaviour”.
- [Original owned-sync plan](2026-09-13-owned-matchday-sync.md), “Outcome”, “Initial cadence”
  and “Post-match verification contract”. Git: `b3f71f6` (coverage proposal), `30092d6`
  (owned-sync plan), `5791ab5` (implementation).
- [Captured source contracts and old writer inventory](../specs/2026-09-13-owned-sync-source-contracts.md).
- [Recovery repairs](2026-10-04-owned-sync-recovery-fixes.md): correct repairs to the old
  protocol, not evidence that the protocol is needed by the revised product.

**Keep the original protections against missing-club bias and corrupt data. Remove the assumption
that provisional but valid data must be invisible.** Do not simply restore Tapas' whole-GW gate.

### Where the unnecessary work is

`OwnedMatchdayRuntime` ticks the planner every minute and two queue workers every second.
`PlannerInputRepository.load` parses full canonical snapshots and `observeTransitions` upserts
fixture metadata, including `updated_at`, even when nothing changed. Payload caches and
metadata-first blocked retries already exist; not every tick/retry downloads every body.

`AdaptiveMatchdayPlanner` adds matchday source reads every 10/30 minutes, affected-player histories
every 30 minutes, a daily full-player reconcile, verification retries, and correction slots.
`HistoryFixtureDispatcher` adds per-fixture source anchors, live evidence, history hydration and
source rechecks. `FixtureSyncPublisher` upserts every row on a successful verification, including
unchanged rows. `PfsReadSource` then hides rows without the fixture-wide verified generation.

The successful GW5 recovery on 2026-10-09 produced 10 VERIFIED fixtures and 667 PFS rows; its meter
reported 262,028,796 DB TCP RX bytes and 299,435,558 machine RX bytes. These are observations with
sampling gaps, **not provider-billed egress or an isolated attribution to ingestion**. The earlier
harness also wrongly treated source receipts and in-flight partial pairs as failures; those were
monitor defects, not reasons to strengthen publication gates. Another monitor wrongly compared
all history/evidence rows with the count of positive-minute participants. A fixture can have 31
appearances and 63 legitimate history rows including non-appearances. Never equate those counts.
Evidence is retained under `/tmp/fpl-active-20261009-three-hour/`; temporary operational files are
not a durable production monitoring service or proof of complete source-body acceptance.

## Three independent questions

| Question | Proposed answer |
|---|---|
| May we store/show this row? | Yes, if its source, key, types and fixture-side identity are valid; label its collection/finality state. |
| May a tool fairly compare this player/club/league? | Only if the required rows/components/window and comparison population are covered. Provisional is not the same as missing. |
| Is routine collection of this GW complete? | Only after a fresh successful sweep following FPL's checked/finished signal, with a current membership manifest and a terminal receipt. |

A failed player fetch must not prevent another valid player's row from committing. Nor may one
successful row certify the other club, the whole fixture, or a complete gameweek.

## What “gameweek final” will mean

### Source signal

Read a validated current-playing-season bootstrap and fixtures snapshot. A GW becomes eligible
for its final sweep when **that event's `finished=true` and `data_checked=true`**, and every fixture
currently assigned to it is `finished=true`. Do not use `is_current`, a fixed weekday, elapsed time,
09:00 UK, `release_time`, or `finished_provisional` as a substitute.

`finished_provisional` may coexist with `finished` and cannot certify finality. The dated source
fixtures have no non-null event `release_time`, and no captured real DGW, postponed/reassigned
fixture, or before/after correction. Tests must distinguish captured evidence from synthetic
edge cases. The older [my-team points plan](2026-09-12-kar-15-live-gameweek-points.md) uses the
finished/checked flags for display; that does not prove cross-endpoint histories are already
complete when the flag is first observed.

This is our operational finalisation signal, **not a documented promise that FPL will never amend
xG, DefCon, points or any other field**. User wording should be “GW checked by FPL; our final
import completed at …”, not “these values can never change”. Do not infer confirmed source data
from our own `VERIFIED` fixture state.

### Durable gameweek state

Use one small record keyed by `(season, gameweek)`, with three ingestion states:

- `OPEN`: provisional rows can arrive and change.
- `FINAL_PENDING`: FPL has supplied the signal, but our final sweep is not complete. This state
  also covers a previously finalised GW whose relevant source or published rows changed.
- `FINALIZED`: the terminal sweep and its coverage receipt have committed for this revision.

Keep source flags, the fixture-membership/header fingerprint, first observed check time,
final-attempt source floor, completed sweep id/time and outstanding IDs separately from retry
errors. Source regression moves the affected GW back to OPEN; a changed checked GW becomes
FINAL_PENDING. Failed HTTP requests alone neither erase valid rows nor reopen a completed GW.

**Finalisation is level-triggered, not only a false→true edge:** on restart, `checked=true` plus
no matching final receipt schedules work even if we never witnessed the transition. A persistent
true flag plus an unchanged completed revision does nothing. This avoids both lost completion
and the old restart replay problem.

### The final sweep

1. Freeze the validated event membership and source signature. Require all assigned fixtures
   finished. Use assigned IDs, not “ten matches”, scheduled kickoff age or the originally
   announced fixture list. A blank GW can have zero fixtures; a DGW can have more than ten.
   A moved fixture belongs to its newly assigned event; unresolved evidence linking it to the
   old event prevents falsely closing that old revision. Unrelated unassigned fixtures do not
   block every GW.
2. Establish required player IDs from the clubs' catalogue plus fixture-scoped live/fixture
   participant evidence and previously known rows/identities for these fixtures. Preserve
   transferred/departed IDs. Resolve missing FK parents from trustworthy season evidence;
   do not invent current players/clubs or silently drop their IDs.
3. Fetch each selected element summary **once for the combined cycle**, with source reads
   beginning after this final attempt's checked/change floor. A successful response collected
   earlier may already have supplied provisional values, but cannot certify this sweep.
   Fetch live evidence once per relevant event, not once per fixture. Event `stats` aggregates
   cannot replace per-fixture history in a DGW; `explain` is used only for its proven fields.
4. Upsert valid rows and component evidence as pages arrive, in short transactions. Record
   which requested pages completed. A bad/missing page stays outstanding; successful pages
   remain available and need not be replayed merely because another page failed.
5. Validate the final required appearance set and fixture-side/minute identities against the
   fixture-scoped source evidence. Preserve returned zero-minute rows, but never manufacture
   zero rows for absence. Fetching a current squad player's page does not prove they were
   registered for every historical match. Keep non-appearance/window gaps explicitly counted.
6. Re-read bootstrap/fixtures **once at the end of the final sweep**. If checked/finished state,
   event membership or relevant fixture headers changed, do not commit finality; rebuild only
   the affected work. This is not a second history sweep or a ten-minute stability timer.
7. In one short transaction, fenced against a superseding writer/revision, verify the completed
   page/coverage manifest and record FINALIZED plus the covered row revision fingerprint.
   No network I/O inside the transaction. It may certify unchanged PFS rows freshly checked by
   this sweep without rewriting them or fabricating new PFS `updated_at` values.

Resume an interrupted final attempt from its durable completed-ID manifest while its source
signature remains compatible. All resumed evidence must meet the same post-signal floor. Known
new changes invalidate affected checkpoints. Report the **actual first-to-last read interval**;
never make an old page appear fetched at the final commit time. A crash after the last row commit
but before the terminal marker must not lead to another mass rewrite.

`FINALIZED` certifies collected core rows and the availability of their components, not invented
optional numbers. If xG/xA/DefCon is absent, keep the component unavailable and expose that fact
beside finalisation; do not advertise complete metric coverage or treat it as zero. Task 1 must
pin which supported current-season components are required for “fully covered” tool output.
Missing mandatory rows/pages always prevents FINALIZED; optional availability and ranking
eligibility remain explicit separate facts.

## Collection without continuous queue polling

Proposed initial cadence: **one hourly cycle**, not “real time”. Prices at 02:00 UK and schedule
capture at 02:10 UK retain their existing expected-capture/cutover semantics (including the 03:00
schedule read boundary). Reuse a source response within the same cycle where compatible; never
backdate a capture or relabel a cached my-team bootstrap as a fresh sync source.

Each cycle reads bootstrap/fixtures once and plans from that in-memory snapshot and compact
progress metadata. It selects:

- Players for in-progress fixtures where rows may be available. A returned valid row can commit
  without a finished flag; an absent row is not a zero.
- An initial post-finish sweep and outstanding missing pages for ended fixtures. Once this
  sweep succeeded, no repeated hourly all-player sweep for that finished fixture while merely
  waiting for the GW's check flag. Values remain labelled provisional until final collection.
- Any FINAL_PENDING event, including one found checked on startup or reopened by a correction.

Deduplicate element IDs across all these reasons/events; one element-summary response carries
all of that player's returned fixture rows. New final work precedes provisional refresh work.
Keep one bounded core-import runner per configured season with a durable lease/fencing token and
the existing shared HTTP request budget/cooldown. If a cycle is still running, the next tick must
not launch a duplicate. A transport failure or budget refusal leaves outstanding work for a later
cycle and is reported; neither means “all done”. Retries do not consume successful checkpoints.

When nothing is playing and no post-match/final import is pending, perform source discovery only:
**no history fan-out, no per-second queue polling, no PFS scan, and no loading staged JSON bodies**.
The hourly trigger is a proposed product latency default, not an upstream guarantee. It also bounds
our detection delay for the finalisation signal. Budget tests must prove the proposed cadence fits
full-league and DGW inputs without assuming every player can be fetched arbitrarily often.

Daily prices/availability/set pieces and captured schedules remain distinct sources. Compatibility
`player` season totals still come from bootstrap, not sums of partially received PFS rows, and their
provenance remains distinct. My-team live points remain their existing cached on-demand feature;
this plan does not add chat-time stats API calls or remove that feature.

## Changed-only writes and honest row visibility

### Write boundary

Reuse strict parsers, source-season checks, historical side reconstruction, request pacing and
named-column writers. A page with duplicate/ambiguous identity or malformed core fields must not
publish fabricated rows; keep the initial implementation's strict **per-player page** rejection,
while allowing other valid pages to commit. Do not invent row-level salvage inside malformed JSON.

PFS stays the single numeric store keyed by `(season_id, fixture_id, player_id)`. Each page's valid
rows, their presence masks and the pointer to their current evidence commit atomically. This is
one transaction per small page/batch, **not one physical row or one transaction for a whole GW**.
A partial run's already committed rows remain readable; run completion is not their validity gate.

Use a conditional upsert (`IS DISTINCT FROM` with the real persisted values) or equivalent proven
normalised value/presence comparison. Do not compare fetch time, run id or cosmetic decimal
formatting as a metric change. Repeating identical input produces **zero PFS UPDATEs** and no
new “data changed” timestamp. A presence-only change updates evidence without rewriting unchanged
physical values. Absent optional metrics remain NULL to readers even where the shared PFS schema
forces a zero default or retains an old value. Missing/invalid mandatory input is never coerced.

Keep source interval/hash and last actual value-change provenance on row evidence. A later final
check has its own timestamp/receipt. Do not claim that every row was rewritten at that time.
An older source/batch may never overwrite a newer row or withdrawal. A failed fetch preserves the
last valid row and its age. A valid complete source response contradicting/removing a previously
published row withdraws its current availability and reopens affected coverage; preserve ownership
and historical evidence, rather than deleting history or inventing zeros.

### Minimum metadata change

Prefer reusing existing run/evidence storage for batch identity and immutable component evidence.
Add, if necessary, only:

- `fpl_player_fixture_current`: a season/fixture/player pointer to the current valid/withdrawn
  row revision, with a normalised fingerprint. **No duplicate numeric metrics.** This replaces
  fixture-wide verified-generation visibility for the new path.
- `fpl_gameweek_stat_sync`: the small event state, source signature and final-attempt manifest
  above. Completed/outstanding IDs are a bounded sweep checkpoint, not another per-player queue.

Names are proposals to verify against the shared schema in Task 1. Do not alter Tapas-owned table
schema. There is no new generic job framework, in-memory cross-run payload cache, permanent second
metric warehouse, or alternate recommendation formula. Keep raw response diagnostics bounded;
parse each fetched page once and release it. Do not persist and repeatedly hydrate the same bodies
for every fixture. Final manifests retain compact evidence, not another copy of the numeric data.

### Reader changes are required, not optional polish

`PfsReadSource` currently needs `fs.state='VERIFIED'` and the fixture's one current generation.
Writing provisional rows without changing that boundary would hide them. The new path reads the
current **row** evidence and masks components before SQL aggregation; legacy/old-owned behaviour
remains distinguishable, including when runtime mode is OFF.

`RankingMetrics` also has finished-only fixture windows and an `UNVERIFIED_OWNED_RECEIPT` coverage
reason. Replace that receipt-only exclusion for newly published rows with explicit row/population
coverage. Do not require GW FINALIZED to use complete, provisional **finished-match** evidence.
Keep genuine live/unfinished matches out of completed-fixture ability/window calculations: storing
or displaying a live row is not permission to divide a season ramp by half a played match.
Individual pages/windows can remain incomplete, and a wholly absent club must still block a
whole-league squad comparison. No hand-built fallback XI or silent substitution of other clubs.

Update `CandidatePool`, `ComparisonCoverage`, the pick/squad formatters, `ExpectedStatsTools`,
`SeasonResolver` and `DataAge` tests for the same contract. Expose “provisional / FPL checked and
final import complete / incomplete” next to the data concerned. Do not relabel a newly written row
with the old ten-minute VERIFIED promise. Bootstrap-based player cards and per-user live points
remain separately attributed. Inventory Tapas metric readers before cutover; that checkout is not
available on this host, and changing our CTE alone does not protect its readers from default zeros.

## Corrections after FINALIZED

Normal player-history responses contain older fixture rows too. Compare those returned rows as
well: a real change is upserted and reopens the affected event's checked revision. A changed fixture
header, event assignment or regressed flag found by routine source discovery also reopens it. The
next final attempt uses a source floor after that detected change, even when `data_checked` stayed
true. Invalid/missing HTTP responses do not manufacture a correction.

**Proposed explicit limit:** stop routinely fetching a finalised event's histories. A history-only
late correction to a departed player never fetched again is not automatically discoverable from
bootstrap/fixtures. Keep a targeted manual event/element reconciliation path, sharing the same
writer and budget. If the owner instead requires automatic detection of every such late change,
that entails further history audits; it cannot be promised together with “never read it again”.
Do not silently keep the old daily full-season sweep to mask this tradeoff.

## What is retired, and what survives

Retire from the new stats path: two-batch/ten-minute stability publication; per-fixture verification
fan-out and its blocked/ready/fairness machinery; per-fixture canonical parent/body reloads;
12-hour recovery episodes; periodic +2h/+24h/+48h correction republication; the daily full-player
history reconcile; and the minute planner/second worker polling loops. Delete the replaced callers
only after cutover verification, rather than leaving two automatic engines active.

Retain: correct source parsers, playing-season and single-writer fences, row/fixture identity,
optional-component masks, comparison coverage, compact source receipts, HTTP quota/Retry-After,
bounded payload handling, daily-capture conventions, and truthful age/error reporting. Reuse only
the necessary portions of existing store/lease code; do not route the new runner back through old
fixture verification just to retain a familiar class name. No automatic consumption/health-based
mode shutdown is introduced by this plan; the owner's monitoring policy is separate from API
backoff and publication validity.

## Bounded implementation and verification

Each unit gets its own focused tests and reviewed feature PR. Local verification uses the guarded
disposable DB at `localhost:55432`; no full local suite. GitHub runs the full suite for every PR
and merge. Attach implementation tickets to Linear/Kari before starting; no identifier is invented
by this planning document.

1. **Pin the new contract and schemas, without a writer.** Seed source statuses, row visibility
   and finality fixtures. Confirm event checked semantics, required/current optional components,
   candidate identity discovery, metadata keys and the chosen hourly/default correction policy.
   Verify live row vs finished match vs checked GW are three different states. Treat release-time
   and an absent checked flag as unknown, never as permission to finalise.
2. **Implement conditional row publication, inert by default.** Test changed/unchanged/presence-only
   rows, real zero vs missing, bad page isolation, source ordering, lease loss, transfers, duplicate
   IDs and atomic PFS/evidence pointers. A later valid row can correct an earlier provisional row.
3. **Make readers compatible before enabling writes.** Test provisional named-player output,
   completed-match ranking without GW finality, partial/whole-club omissions, component masks,
   live-window exclusion, source ages and OFF ownership. Audit actual Tapas consumers; an
   inaccessible checkout is an unresolved verification task, not an assumption of compatibility.
4. **Implement the single coalesced collector.** Test due work from real schedules, overlapping
   games/events, selected-player de-duplication, no-match idle behaviour, request budget and
   resumable pages. Use a representative seeded 20-club/657-player league; do not pin fixture IDs
   or change ordering to make the run pass. The replacement remains disabled in production.
5. **Implement final sweep and reopening.** Test already-checked startup without a transition,
   fresh post-signal reads, one bad player page, crash between last row and final marker,
   idempotent completed restart, mid-sweep membership/flag changes, postponed/reassigned fixtures,
   DGW legs, blank GW, optional unavailability and a history-only correction with flags still true.
   Only a complete manifest earns FINALIZED; old provisional rows alone never do.
6. **Compare on recorded/disposable inputs and rehearse migration.** Same final numerical rows as
   validated source, no extra zero defaults, no changes to unrelated seasons. Replay identical
   input: zero PFS rewrites. Measure body reads, DB statements/returned bytes, actual row updates,
   source requests, wall time and memory separately. Verify at most one history fetch per selected
   player per cycle, no second stability sweep, and zero idle staged-body/history work. Confirm a
   removed participant/row cannot remain readable behind an old receipt. Rehearse OFF and an
   old-reader rollback for both previously VERIFIED fixtures and new-collector-only fixtures,
   including withdrawals and presence-only changes; neither may fall through to uncertified
   physical values. Publish results, not a guessed monthly egress saving.
7. **Reviewed single-writer cutover and subsequent removal.** Ship compatible readers/inert writer
   first; then explicitly drain/fence the old core runner before enabling the new one. Preserve
   Tapas restrictions, daily capture jobs and per-user reads. Seed row pointers only from actual
   matching current evidence, with original timestamps; do not turn old ownership or a physical
   PFS row into a new proof. Revoke old fixture-wide current verification when changed rows make
   its proof obsolete; preserve immutable old receipts. Never label an old VERIFIED fixture as a
   newly FINALIZED GW without a checked final sweep. Verify one real GW end-to-end, including
   an observed checked signal, changed-only final upserts and a subsequent no-op cycle, then
   remove the retired scheduling paths in a separate reviewed change.

Rollback is a writer stop with compatible readers retained first, not silently re-enabling Tapas
or treating the new rows as legacy. An older binary that understands only fixture verification may
hide newly written rows; it must not accept them under an obsolete receipt. Re-enabling the old
writer would require an explicit fenced re-verification plan. Existing archives are never rewritten
just to change the active-season protocol.

## Approval and completion criteria

Approve this **provisional-row contract**, the proposed hourly collection default, and the explicit
limit on unseen post-final history-only corrections before implementation. The endpoint assumptions
and optional-metric completion wording are pinned in Task 1, not inferred from a passing HTTP call.

Success means: useful valid rows become available without a global verification barrier; missing
clubs still cannot produce misleading recommendations; checked GWs receive fresh final upserts and
an honest completion receipt; unchanged input does not rewrite statistics or restart full sweeps;
idle operation does not continuously read staged payloads; and only one core writer remains active.
This document neither changes running ACTIVE92 nor declares its full source-body audit complete.
