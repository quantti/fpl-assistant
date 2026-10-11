# Incremental FPL rows and a checked-gameweek final sweep

**PROGRESS:** Unit 3 reader/output implementation and its four finality review corrections are locally implemented; the correction-specific guarded rerun passed 7/7. Unit 7's reader-watermark fix and fail-closed importer guard/manual entrypoint are locally implemented. Automatic hourly runtime wiring is deliberately deferred until a changed-only compatibility writer covers `team`, `player`, `gameweek` and `fixture` without taking ownership of capture tables. Unit 4 review/acceptance, Task 6 evidence, the actual Tapas-reader audit and reviewed single-writer cutover remain pending. `OWNED_SYNC_IMPORTER_ENABLED` defaults false; no production activation, live API, production DB/Fly/monitor action, full suite, commit or push occurred.

## Decision being proposed

Write independently valid player-fixture rows as they arrive, including provisional rows. Replace
changed values when FPL changes them. For this new checked-gameweek path, when FPL reports the
gameweek finished and checked, run a fresh final sweep, apply the last differences, and record its
successful completion. Do not make this path wait for two identical observations or for every other
player to arrive. The legacy fixture-level verifier keeps its existing second-observation and
stability-window contract until an approved cutover.

The owner explicitly accepted provisional values and later corrections on 2026-10-10 for the new
writer path only. This changes that path's **publication assurance contract**, not legacy
`FixtureVerification` / `FixtureSyncPublisher` behavior, and is not permission to fabricate absent
values, mislabel incomplete coverage, or remove source validation. Approval of this plan does not
activate new code. The existing ACTIVE92 runtime and observation-only monitor are left untouched; the owner
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

## Task 1 outcome (2026-10-10)

`HistoryParser` now distinguishes mandatory fields from twelve optional components, including a
present zero from missing/null; discovery retains zero-minute history rows and never synthesizes
absent player-fixture rows. Later discovery can seed from current catalogue elements, fixture stats,
event-live explain participants and retained known keys; a player absent from every source remains
undiscoverable. Final sweep eligibility requires a complete fixture manifest, checked finished event
and every assigned fixture finished (a complete blank GW qualifies; provisional alone
does not). The source signature binds schedule revision. Checkpoints and terminal receipts also bind a
monotonic current invalidation revision/floor, so flag regression and history-only corrections cannot
resurrect old work; post-invalidation reads can complete the new revision. The finalized source floor
is stored independently of the mutable attempt floor. Receipts bind sorted valid/withdrawn row
fingerprints and validated optional-component coverage; FINALIZED requires no outstanding pages and
an exact completed/required manifest match.

The two additive metadata tables point to existing evidence/run generations and store GW finality,
checkpoints and compact receipts; they duplicate no numeric stats and start with no backfilled legacy
proof. Tapas readers could not be audited because that checkout is absent; compatibility remains a
Task 3 follow-up. Focused disposable-DB tests passed **28/28, zero skipped**: `GameweekFinalityTest`
(11), `HistoryParserTest` (9), `HistoryEvidenceSchemaIntegrationTest` (4),
`SharedFixtureRecoveryPolicySchemaIntegrationTest` (3) and `ScheduleOwnershipInvalidationIntegrationTest`
(1), including both affected namespace initializers. No full suite ran. No writer, reader migration,
production mode, monitor, live DB or Tapas schema changed; ACTIVE92 is untouched.

## Task 2 outcome (2026-10-10)

The unit-1 receipt coverage CHECK requires the `STRICT` validator result `IS TRUE`, and schema initialization replaces the earlier NULL-permitting constraint on reused tables. The migration regression reinstalls the old CHECK, proves a FINALIZED row with SQL NULL coverage passes before reinitialization, then proves the tightened constraint rejects it. `HistoryEvidenceSchemaIntegrationTest` passed **5/5, zero skipped**.

`IncrementalPlayerFixturePublisher` parses the whole page before `IncrementalPfsRepository` opens its writer transaction. Page publication now requires an explicit ACTIVE runtime fence and, inside the transaction, locks and matches the configured season to the unique database `is_current` row and the parsed season ID/code. OFF and STAGING modes, or an ACTIVE configured season that is not the database current season, reject before the callback. The transaction persists every successful page interval—including an empty page—in the existing receipt table under the distinct `incremental-page:player:<id>` key (`latest_generation` and read interval, linked to that run's durable source floor); the legacy `player-history:<id>` receipt is neither locked nor overwritten. Stale generations/read ends cannot resurrect absent rows. No metadata table or sentinel row was added.

A successful page atomically writes named PFS columns, immutable component evidence, valid/withdrawn row pointers and the page watermark. It revokes any obsolete fixture `current_verified_generation` by moving that fixture to `WAITING_STATS`, while preserving `last_verified_generation` and historical evidence. The current row pointer is not gated by fixture verification. The legacy `PfsReadSource` OFF path treats current-row and successful player-page receipt markers as ownership: it masks old verified fixtures, incremental-only fixtures and retained physical values after presence-only changes or withdrawals. Reassigned fixtures return both previous and new gameweek keys. Conditional normalized-value upserts still avoid PFS UPDATEs for unchanged and presence-only pages.

The focused guarded disposable-DB run on `localhost:55432` passed **26/26, zero skipped**: `IncrementalPlayerFixturePublisherIntegrationTest` **12/12** (including ACTIVE/OFF/STAGING/season fences, actual OFF-reader masking, empty-page concurrent serialization, reassignment keys, multirow rollback and lease expiry during callback), `HistoryEvidenceSchemaIntegrationTest` **5/5** (including the legacy-constraint migration), and `PfsReadSourceIntegrationTest` **9/9**. Test seasons restore the prior global `is_current` rows; owned runtime tables use a private namespace. No scheduler, Spring writer bean, automatic activation, production runtime, monitor, full suite, production DB or Tapas schema change occurred; ACTIVE92 remains untouched.

## Task 3 bounded reader slice (2026-10-10; local, partial)

The Unit 2 ownership regression is fixed first: the page publisher now writes its empty/non-empty watermark to `incremental-page:player:<id>`, leaving legacy `player-history:<id>` receipts independent. A focused writer regression seeds a newer/future legacy watermark and proves the incremental lease can publish without reading or overwriting it. The legacy verified fixture remains visible in both ACTIVE and OFF; the old generic receipt is not mistaken for an incremental page.

`PfsReadSource` reads incremental rows only through a valid current pointer whose season, source/evidence generations and exact immutable source-read interval agree with matching evidence. The dedicated page receipt is validated as an ordered watermark: it may advance beyond an unchanged row pointer, but cannot predate that row or fence a newer withdrawal. A separate built-in PostgreSQL SHA-256 fingerprint binds the pointer to normalized physical named PFS values; absent optional values are masked from that fingerprint before aggregation. Invalid, tampered or withdrawn pointers, fixture ownership markers and successful player-page markers prevent raw physical fallback, including in OFF; provisional rows do not require the fixture-wide verified state or a FINALIZED gameweek. Legacy fixture visibility remains on its previous verified/active contract.

`RankingMetrics` replaces receipt-only incremental coverage with daily-capture player population plus usable completed-side PFS rows. Missing pages and missing usable rows create explicit whole-club gaps; a whole-club gap still blocks pick/squad coverage. A fixture row with an elapsed kickoff but `finished=false` does not add to the completed window or season blend count. `ComparisonCoverageFormatter` is the one migrated representative formatter: it distinguishes incomplete coverage from provisional page evidence, avoids implying FPL/final-import confirmation, and calls the counted players a comparison population. It has a checked-final wording branch, but no current reader path emits that status yet; old fixture `VERIFIED` receipts are not promoted to gameweek finality.

The guarded focused run on `localhost:55432` passed **91/91, zero skipped**: `PfsReadSourceIntegrationTest` 12, `IncrementalPlayerFixturePublisherIntegrationTest` 13, `RankingMetricsIntegrationTest` 23, `ComparisonCoverageFormatterTest` 2, `PickToolsIntegrationTest` 18 and `SquadToolsIntegrationTest` 23. This is not a full-suite result. Remaining Unit 3 work is: wire validated gameweek-final receipts to checked-final labels; migrate the other relevant output formats in `ExpectedStatsTools`, pick/squad component details and `DataAge`/source-age wording; audit `SeasonResolver` against provisional unfinished fixtures; and review actual Tapas readers (checkout unavailable here). No writer activation, API endpoint, monitor, production data/schema, full suite, commit or push was changed/run.

## Unit 2/3 review-critical fixes (2026-10-10; local)

Four bounded review findings are fixed. First, unchanged later player pages advance only the page watermark: they keep the actual row source interval, evidence generation and pointer revision, perform zero PFS writes, and remain visible in both ACTIVE and OFF; receipt validation accepts a same-or-newer ordered watermark while an older page still cannot reverse a withdrawal. Second, each current pointer carries a separate normalized physical-PFS SHA-256 fingerprint using PostgreSQL built-ins (no extension); reader-side validation masks absent optional values, and mandatory or present-optional physical tampering makes the owned row unreadable rather than falling back. Existing pointers are not backfilled with physical proof. Third, a player's first complete empty page discovers previously owned legacy PFS keys from owned fixture metadata, writes withdrawal pointers, returns their real gameweek keys, revokes only current fixture verification, and retains prior evidence and receipts; unowned seasons/rows are not swept. Fourth, the legacy `player-history:<id>` receipt regression now has a complete source interval and proves exact original values remain visible in both modes without being confused for the `incremental-page:player:<id>` watermark.

The latest guarded focused disposable-DB run on `localhost:55432` passed **57/57, zero skipped**: `IncrementalPlayerFixturePublisherIntegrationTest` 15, `HistoryEvidenceSchemaIntegrationTest` 5, `PfsReadSourceIntegrationTest` 14, and `RankingMetricsIntegrationTest` 23. No full suite, collector/unit 4, final sweep, production/API/monitor work, production DB, commit or push was run. Writers remain inert and ACTIVE92 is untouched.

## Task 4 ordinary-cycle foundation outcome (2026-10-10; local, partial)

`CoalescedPlayerHistoryCollector.runCycle()` is an explicit callable with no Spring bean or scheduler; the constructor without an enable flag is disabled. An enabled instance is pinned to one configured season, and its store entrypoint checks the unique database `is_current` row before creating or claiming its producer-isolated `PLAYER_HISTORY` run. Legacy workers claim only `LEGACY` runs. The cycle fetches one bootstrap/fixtures pair, records compact source fingerprints and first-finished markers, discovers candidates from current catalogue, fixture stats, fixture-scoped event-live identities and existing PFS keys, deduplicates player pages, and orders `FINAL_PENDING` work before active and unchecked post-finish work. It publishes each page independently through `IncrementalPlayerFixturePublisher`, using the existing HTTP/budget transport in the production constructor and no staged response bodies.

Thirteen guarded integration tests use the recorded 20-club/657-player sources, stubbed responses, and a loopback-only HTTP server. They cover overlapping fixture/event selection and source-only/known player keys, durable fixture-scoped live identities across repeated page failures and later live omission, per-fixture header corrections, resumed read intervals, elapsed unfinished fixtures, producer-aware page ordering, a non-empty page under shared request pacing with the legacy producer still pending, one event-live read per selected event, final-pending priority, a reentrant overlapping call losing the lease, an idle post-finish cycle without PFS/history hydration, independent page failure, cooldown deferral, current-season mismatch refusal and disabled-by-default behavior. `CoalescedPlayerHistoryCollectorIntegrationTest` passed 13/13; `FixtureVerificationTest` (18), `OwnedSyncStoreIntegrationTest` (66) and `HistoryFixtureDispatcherIntegrationTest` (31) passed in a separate guarded focused run, and `IncrementalPlayerFixturePublisherIntegrationTest` passed 15/15 in its own guarded run. No full suite or live API was run. This is the ordinary-cycle foundation only: final sweep/checkpoint, Unit 5 orchestration, scheduling, writer activation, production DB/Fly work, commit and push remain out of scope; Unit 4 review/acceptance is pending.

## Unit 5a finality review fixes (2026-10-10; local, focused)

1. **Population survives omissions without crossing fixture identity.** The stored manifest keeps category-separated catalogue, participant and known IDs per fixture, alongside a fixture identity hash. A compatible prior manifest and durable participant receipts retain a failed/omitted page ID across population refresh and process/lease restart. Existing valid PFS row keys supplement known IDs. A `WITHDRAWN` pointer removes a retained ID only when its successful page read starts strictly after both that participant's durable observation and the current invalidation/checkpoint floor; the older-pointer regression keeps B required through population change and lease restart. Prior population and participant receipts are not carried across a changed fixture identity.
2. **Fingerprint serialization is unambiguous.** The canonical digest includes population category, fixture count/IDs and each fixture's player count/IDs with separators. A grouped-versus-split fixture population that collided under the previous concatenation now produces a different fingerprint and cannot resume the other checkpoint.
3. **Legacy finality is retired before stricter checks.** Initialization first archives unsupported rows as `legacy_finality_evidence`, then clears unsupported attempt/receipt fields and moves eligible checked rows to `FINAL_PENDING` (otherwise `OPEN`), before reinstalling constraints. It leaves `updated_at`, observed invalidation bounds and archived historical values untouched; no attempt/final timestamp is fabricated. A repeated initialization is stable, and only a fresh read can begin a new attempt.
4. **Read intervals survive restart truthfully.** The immutable initial source interval, aggregate initial-to-latest source span, successful page interval and terminal validation interval have separate persisted fields. The terminal receipt and owned-run interval cover the actual initial source start through the fresh end read; no page is backdated to finalization time.

The guarded focused disposable-DB invocation on `localhost:55432` passed **22/22, zero skipped**: `GameweekFinalityTest` 13 (including the fingerprint collision/resume-negative cases), `HistoryEvidenceSchemaIntegrationTest` 6 (including genuine old-shape migration and repeated-init coverage), and three `CoalescedPlayerHistoryCollectorIntegrationTest` methods (population omission across lease restart, source/page/end interval restart, and complete blank-GW finality). This is not the full suite. No live API, production database, activation, scheduler, full suite, commit or push occurred; the collector still does not invoke final-sweep APIs.

### Unit 5b checked-gameweek orchestration outcome (2026-10-10; local, focused)

The collector calls the inert `GameweekFinalityRepository`: `beginOrResume` after the checked source/population read, `recordCompletedPage` immediately after each successful page publication, then a fresh terminal bootstrap/fixtures pair followed by `finalizeSweep`. `RequiredPopulation` is fixture/category scoped; one player shared by DGW legs is fetched once. For this new path only, the positive event-live appearance set and fixture-stat side evidence are reconciled with current PFS history in both directions: every positive live appearance needs a current history row with identical minutes and the correct side, and every positive current history row must match a positive live appearance on that exact fixture. An unmatched positive row, missing source evidence or inconsistent minutes/side leaves finality pending. Zero-minute live explanations are excluded from appearance evidence; zero-minute current PFS rows remain distinct from appearances and are tolerated without a live explanation or catalogue registration. The legacy `FixtureVerification.verify` and `FixtureSyncPublisher` behavior remains at `HEAD 784c66f`, including its first-observation `NEEDS_SECOND_OBSERVATION`, ordered/hash-identical later read, ten-minute `STABILITY_WINDOW`, legacy coverage/participant selection, and preservation of a currently verified row during a stability-only recheck.

A recorded complete source capture for fixture 29 has a fixture-stats participant union matching its 31 positive live appearances. This proves only that fixture's captured population; it does not establish FPL API or league-wide completeness. Finalization abstains on nonconsistent evidence and makes no universal completeness claim. A `WITHDRAWN` page remains bounded evidence and cannot erase a later participant observation; it is consumed only after a successful read newer than participant evidence and the invalidation/checkpoint floor. Reusing a finalized gameweek invokes targeted physical-row validation shared with `PfsReadSource` (row fingerprint, immutable evidence, page receipt/watermark and checkpoint), not a full PFS scan. Initial and terminal source intervals remain distinct and truthful. Terminal reassignment/source changes reopen the attempt rather than finalizing stale evidence. The collector remains explicitly invoked and disabled by default; no scheduler or writer activation was added.

Sequential guarded focused runs on disposable `localhost:55432` passed `FixtureVerificationTest`, `FixtureSyncPublisherIntegrationTest`, `OwnedMatchdayEndToEndIntegrationTest`, `ScheduleOwnershipInvalidationIntegrationTest`, the zero-minute departed-player `HistoryFixtureDispatcherIntegrationTest` case, two selected legacy `OwnedMatchdayAdmissionProgressIntegrationTest` methods (including `scheduleCorrectionCanRepublishOnlyAfterTwoFreshCoordinatorBatches`), and three targeted new collector finality tests (positive history missing from live, positive history with a zero-minute live explanation, and legitimate zero-minute unregistered history). The large active-recovery acceptance was left unchanged and not run; the full suite remains the CI gate. No live API, production DB/Fly/monitoring, commit or push was run.

### Unit 3 output/finality handoff (2026-10-10; local, focused)

`GameweekFinalityReader` is a read-only output API separate from the writer-fenced repository. In one bounded call per requested gameweek set it reconstructs the current event/assigned-fixture source, then validates a `FINALIZED` receipt against that source signature, the current catalogue fingerprint, checkpoint/population and invalidation revisions, covered row revision and optional-component counts, persisted completed producer/run status, original per-player page evidence, the separately advancing latest page watermark and source/read intervals. It uses the archived run evidence rather than the planner's current runtime mode/epoch, so OFF or reactivation does not erase valid finality. Invalid or stale final receipts cannot produce a finalized result. `FINAL_PENDING` is reserved for a currently checked/finished event whose assigned fixtures are all finished; checked flags with an unfinished event/fixture remain `OPEN`. It takes no writer lease, makes no external request, and does no per-player ranking query.

`GameweekFinalityFormatter` labels provisional incremental rows, FPL-checked `FINAL_PENDING`, and validated `FINALIZED` separately, scoped to the exact shown fixture/gameweek set. Final receipts print their actual source-read interval and final completion time. Optional-component output is presence counts only; unsupported components say `unsupported`, and no missing metric is rewritten as zero. `RankingMetrics` gathers scoped row counts in its existing repeatable-read snapshot; Pick/Squad provenance appends that summary without replacing legacy `DataAge` freshness. Expected Stats uses a read-only repeatable-read snapshot and applies the same grouped status only to its returned player/team fixture rows. Comparison footers distinguish fully checked-final incremental comparison evidence from provisional evidence and point to the per-gameweek summary; legacy/non-incremental rows never inherit a final receipt. No formulas, minutes/ramp behavior, `SeasonResolver` code, or legacy freshness wording changed.

The guarded disposable-DB focused run passed **103/103, zero skipped**: `GameweekFinalityFormatterTest` 2, `PfsReadSourceIntegrationTest` 15, the reader/finality crash-resume integration case 1, `ExpectedStatsToolsIntegrationTest` 18, `RankingMetricsIntegrationTest` 23, `ComparisonCoverageFormatterTest` 3, `PickToolsIntegrationTest` 18, and `SquadToolsIntegrationTest` 23. It covers mixed output states, unsupported optional coverage, current source and row tampering, and existing ranking/expected-stats/pick/squad formatting. This was the prior output-format baseline, not a full-suite result or a rerun after the four review corrections. The actual Tapas reader audit remains blocked because `~/dev/tapas-fpl-app` is unavailable on this host; do not infer compatibility from this project's CTE. History-only correction while flags stay true remains a separate end-to-end case. No schema change, live API, production DB/Fly/monitoring, scheduler, activation, full suite, commit or push occurred at that checkpoint.

### Unit 3 read-only finality review follow-up (2026-10-11; local, focused)

The reader validates the terminal finalized run's persisted producer, mode, epoch, completed status, source interval and requested-source floor; it does not require the planner's current runtime mode/epoch. Finalization separately stores each required player's original page generation and read interval in `finalized_page_evidence`, so an ordered later page can advance the mutable latest receipt watermark without replacing the original proof or invalidating unchanged rows. Original and latest page runs are validated independently. Checkpoint required/completed IDs must exactly equal the persisted population's required IDs, with no outstanding IDs; the population must cover the current catalogue and is checked against durable fixture participants, positive source evidence and current PFS rows. Invalid or tampered proof stays `FINAL_PENDING`. The new column is project-owned and invalidation clears it; no runtime activation occurred.

The guarded disposable-DB correction-specific rerun passed **7/7, zero skipped**: `CoalescedPlayerHistoryCollectorIntegrationTest.finalityCheckpointResumesFreshPageAfterCrashAndFinalizesOnlyAfterEndRead` 1/1 and `HistoryEvidenceSchemaIntegrationTest` 6/6 on `localhost:55432`. It ran in a verified 1200 MiB cgroup with swap disabled and a 768 MiB host-availability stop threshold. The prior 103/103 run was not repeated; this is not a full-suite result. No actual Tapas-reader audit, live API, production DB/Fly/monitoring, scheduler, activation, commit or push occurred; writers remain inert.

### Unit 7 reader-watermark fix and inert runtime foundation (2026-10-12; local)

`GameweekFinalityReader` still requires the original terminal finality run and original page-proof runs to be completed. It no longer treats a newer committed player-page watermark as invalid merely because that page's whole coalesced run is still `LEASED`, has been requeued as `PENDING` after another page's fetch failure, or has later ended `FAILED`. That watermark must still point to the correct ACTIVE incremental producer/epoch and satisfy its persisted request floor, source interval and ordered receipt proof. The regression keeps an unchanged older gameweek finalized across a newer committed page while its containing run is `LEASED`, requeued `PENDING`, and later `FAILED`; changing the original terminal proof run away from `COMPLETED` remains invalid.

`OWNED_SYNC_IMPORTER_ENABLED` defaults false. A small explicit manual entrypoint interface is implemented, but the collector remains unregistered and unscheduled. If the importer flag is set, the legacy core runtime is excluded and startup fails closed after validating ACTIVE mode, explicit playing season and cutover confirmation; it does not initialize the owned schema, activate a runtime epoch, fetch or publish. STAGING/OFF cannot use this flag. Default and existing ACTIVE behavior with the flag absent remain unchanged.

Automatic runtime wiring is deferred because the collector validates an in-memory bootstrap/fixtures snapshot and publishes PFS pages, but does not publish the compatibility `team`, `player`, `gameweek` and `fixture` rows that the legacy `CatalogueScheduleWriter` maintains. Reusing that writer would also write canonical daily-capture tables and their own publication timestamps, conflicting with the independent `DailyPriceSyncService` / `ScheduleSyncService` jobs. The next unit must supply a separate validated, named-column, changed-only compatibility writer that preserves capture ownership and timestamp semantics; only then can the one-hour trigger and runtime lease be safely registered. The actual Tapas metric-reader audit remains unavailable and is an independent cutover blocker. No hidden auto-activation, old-code removal, live API, production DB/Fly/monitor action or production cutover occurred.

Focused guarded disposable-DB tests passed **59/59, zero skipped** on the latest run: `CoalescedPlayerHistoryCollectorIntegrationTest` 32, `HistoryEvidenceSchemaIntegrationTest` 6, `OwnedSyncRuntimeConfigurationTest` 7, `OwnedSyncWatchdogIntegrationTest` 12 and `ScheduleWriteIntegrationTest` 2. No full suite or live API was run.

## Rollback

Rollback is a writer stop with compatible readers retained first, not silently re-enabling Tapas
or treating the new rows as legacy. An older binary that understands only fixture verification may
hide newly written rows; it must not accept them under an obsolete receipt. Re-enabling the old
writer would require an explicit fenced re-verification plan. Existing archives are never rewritten
just to change the active-season protocol.

## Approval and completion criteria

The owner approved this **provisional-row contract**, the hourly collection default, and the
explicit limit on unseen post-final history-only corrections on 2026-10-10. Task 1 pins endpoint
assumptions and optional-metric completion wording from the current parser/source evidence; later
units remain gated by their own tests and cutover review.

Success means: useful valid rows become available without a global verification barrier; missing
clubs still cannot produce misleading recommendations; checked GWs receive fresh final upserts and
an honest completion receipt; unchanged input does not rewrite statistics or restart full sweeps;
idle operation does not continuously read staged payloads; and only one core writer remains active.
This document neither changes running ACTIVE92 nor declares its full source-body audit complete.
