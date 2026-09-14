# Assistant-owned FPL sync and post-match verification

**PROGRESS:** Tasks 1–10 implemented and locally verified; operational rollout authorized, but first STAGING failed on shared-pool/transaction bounds. Runtime safely returned to OFF; the bounded fix awaits deployment. Tapas remains the core writer; notification, cutover, reconciliation and real-matchday gates remain open.

Owner decision: this service, **not Tapas**, will perform the FPL data updates. This supersedes
Task 5's ownership assumption in `2026-09-13-wildcard-data-coverage.md`. That plan's comparison
coverage, refusal and explanation work remains required; fetching more often is not a substitute.
Attach both plans to a Kari Linear item before implementation. No production change is authorised
by a plan commit alone: implement, test, review, then perform the documented owner-approved cutover.

## Outcome

On every Premier League matchday, player identity/availability, schedule/opponents and player
match statistics refresh several times. After a match, the system keeps checking until the FPL
source says `finished=true` **and** the corresponding per-player data has passed completeness and
consistency checks. A later source correction is collected too. Missing/failed work survives
restarts, is retried within a budget, and raises an actionable alert rather than a false success.

"Correct after the match" means **verified against the source responses at recorded times**, not
an assurance FPL will never correct its numbers. No code forces `finished=true` after two hours,
treats `finished_provisional` as final, or waits for the whole gameweek to become `data_checked`
before collecting a finished match. `data_checked` triggers an additional reconciliation.

## Ownership and scope

Take over writes to the existing season-qualified core tables: `team`, `player`, `gameweek`,
`fixture`, `player_fixture_stats` (PFS). Keep their schema and composite keys, existing archives and
Tapas read compatibility. This is an explicit owner-approved change to the old **write ownership**
convention, not permission to alter arbitrary shared columns/tables. Amend AGENTS in the writer PR.
Season registration (`season`) remains an explicit operator step in this plan; the sync validates
one unambiguous configured playing season against the source and refuses a mismatch. It never
selects the largest season ID or uses `SeasonResolver`'s historical fallback to label new writes.

This service already owns `fpl_daily_price`, `fpl_gameweek` and `fpl_fixture`. Preserve those reader
contracts. Publish captured and compatibility schedules from the same validated source batch;
there must not be independent jobs disagreeing about fixture ID, opponent or kickoff. Preserve
all 38 events and the full league schedule, including genuinely unassigned fixtures.

Fold DefCon enrichment into the same fetched history and publication as the core PFS fields;
retire the old independent UPDATE-only DefCon scheduler after cutover. Do not clear a known value
because a field is absent from a response: missing/unavailable and a source-reported zero remain
different. Optional missing metrics remain unavailable/partial and cannot certify full coverage.

Tapas may retain manager/mini-league/chip/ownership and derived analytics jobs. Those are not
this chatbot's core FPL ingestion and are not transferred here. However, any such job that also
writes core tables must become read-only for those tables. Disabling only Tapas' top-level cron
is insufficient: it would stop unrelated work and leave standalone/manual writers available.
Historical football-data/vaastav imports and per-user my-team reads retain their existing scopes.

## Target data flow

`persistent due work → bounded FPL fetches → validation/staging → atomic publication + receipt`

One coordinator owns scheduling. Reuse existing HTTP/mapping code where sound, but separate fetch,
validate and publish so a retry does not require an open database transaction over network I/O.
All instances and CLI entries use the same database-backed lease and rate budget. A stale worker
cannot commit after losing its lease; publication checks a fencing version. Acquisition, expiry,
renewal, takeover and generation ordering must be tested. Only the last successfully published
batch is reader-visible; raw partial responses are staging evidence, not data readiness.

New assistant-owned tables (final names checked against shared schema before migration):

- `fpl_sync_run`: due/attempt times, job kind and season, bounded error, lease/fence, publication
  state and source read interval. A durable queue/receipt, not merely a timestamp in application logs.
- `fpl_sync_payload`: bounded-retention staged public FPL responses per run/endpoint/player with
  actual read time and validation result. Allows atomic match publication, replay diagnosis and
  restart without trusting half a player sweep. No tokens or user/account API payloads.
- `fpl_fixture_sync`: `(season, fixture_id)` status, last successful generation/read interval,
  expected/received participant counts, validation failures, next due time and alert state.
- `fpl_player_fixture_evidence`: season/fixture/player key, published generation, per-component
  presence/validity and actual source read time. Permanent metadata for current-writer rows,
  committed with PFS; not a second copy of their numeric metrics. Payload retention cannot erase it.

Existing PFS xG columns are NOT NULL with zero defaults. Without altering that shared schema,
missing optional values require this evidence sidecar: a physical storage default or retained old
value is **not an observed value** in the new generation. Every consumer of writer-owned rows
must check component presence/generation and return unknown/partial rather than reading that
number as evidence. This includes Tapas-derived metric readers after handover. Do not enable
publication until that reader audit is complete. Historical rows without sidecar metadata retain
their separately labelled archive contract, not an invented new verification claim.

If run metadata can share the payload manifest without another table, keep the design small.
Do not add a new historical metric warehouse or duplicate PFS as a second permanent authority.
Stage first, then publish all validated rows for one fixture and its receipt in one short transaction.
Publish a bootstrap/catalogue/schedule batch atomically as its own unit. Per-fixture publication
need not wait for other simultaneous matches. All compatibility writers use named column lists.
An older run cannot overwrite a later successfully published batch even if it finishes last.

## Initial cadence and request limits

All matchday decisions use stored kickoff instants and UK calendar dates, not fixed weekdays or
`is_current` flags. Re-derive due work after fixture changes; postponed games, doubles, midweek
rounds and games crossing midnight need no manual calendar edits. A persisted pending fixture
continues to receive follow-up even when it is no longer on today's schedule.

| When | Work |
|---|---|
| Every day, 02:00 UK | Existing daily price capture plus full player/team/event metadata |
| Every day, 02:10 UK | Full schedule; discover changed kickoff times and catch-up work |
| Every day, 03:00 UK | Full active-season player-history reconciliation, replacing the DefCon-only sweep |
| Matchdays, 08:00 UK onward | Bootstrap + schedule every 30 min, including before the first kickoff |
| First kickoff −60 min through last kickoff +4 h | Bootstrap + schedule every 10 min; affected clubs' history sweeps every 30 min |
| First observed `finished=true` | Queue immediate post-match verification; coalesce with any history sweep already in progress |
| Verification incomplete | Retry after 10, 20, 40, then every 60 min up to 12 h after first observed finish; global rate limit still applies |
| Finished source never arrives | After kickoff +3 h flag verification overdue, continue bounded hourly checks; never manufacture a result |
| After successful verification | Reconcile at +2 h, +24 h and +48 h, and on gameweek `data_checked` transition |
| Still unresolved after 12 h | Escalate; hourly checks stop, daily reconciliation/manual retry remains; status stays incomplete |

Initial global budget: one outbound FPL request per second, at most two in flight, 5,000 requests
per rolling 24 hours for this coordinator, including retries. These are conservative application
limits, **not a claimed FPL rate-limit contract**. Respect `Retry-After`; otherwise use bounded
exponential backoff with jitter on 429/5xx/timeouts. Do not retry invalid/season-mismatched payloads
in a tight loop. Persist rate/cooldown state so a restart or a second instance cannot reset the budget.

Prioritise post-match verification, then source-status discovery, then intramatch sweeps, then
routine full sweeps. Fetch each required element history once per coalesced sweep, not once per
fixture; reuse one bootstrap response for its consumers. Measure a full ten-match day before
release. If the budget cannot meet these cadences, expose delayed work and adjust the reviewed
cadence/budget—never silently skip players or label a partial sweep complete.

## Post-match verification contract

Use `/fixtures/`, `/bootstrap-static/`, `/element-summary/{element}/` and, for a separately
checked participant manifest, `/event/{event}/live/`, all at **sync time** only. Confirm their exact
current payload contracts with captured public fixtures before implementing readiness logic.

1. Source season, event, fixture identity, both clubs and venue must agree. Publish FPL's raw
   started/finished state faithfully, but keep readiness as a separate field/state. A finished
   fixture with missing metrics is `WAITING_STATS`, not ready.
2. Histories are mapped by `(season, fixture_id, player_id)`, using the fixture's home/away identity
   and `was_home`/opponent evidence to establish the player's club **for that match**. Current
   bootstrap club alone cannot reassign a pre-transfer performance. Fetch players of both clubs
   plus IDs evidenced as participants even if subsequently transferred; never a chat shortlist.
3. A fixture-specific participant manifest must establish whose performance is expected. Validate
   each appeared player's history row and minutes against independent per-fixture live evidence.
   Live event totals are gameweek aggregates: **never** compare them directly to one leg of a
   double gameweek. Use fixture-scoped explanation data only where its captured contract supports
   the required values. If the manifest is absent, ambiguous or inconsistent, readiness stays
   unknown and alerts; do not substitute "every HTTP request returned 200" or "one row exists".
4. Preserve source-returned zero-minute rows; never invent them for missing histories. Separately
   record missing non-participant evidence needed by the hard role window; verified match
   participants do not prove every squad player's window complete. Such players stay ineligible.
   Do not require a player signed after the match to have a row before joining the club.
5. Mandatory core fields must be present, well-typed and internally consistent. Missing is not a
   parse-to-zero observation. Validate coverage of both sides, identity/duplicates, gameweek,
   minutes and points. Optional DefCon/xG availability is recorded per player/component and
   generation in `fpl_player_fixture_evidence`; metric consumers must honour it before aggregating
   ability/club projections. A verified appearance does not certify every optional metric.
6. Require two successful, internally consistent post-finish observations at least 10 minutes
   apart with unchanged relevant per-fixture values/manifest before `VERIFIED`. This is a defined
   stability heuristic, not proof of upstream finality. Recheck fixture state around each batch;
   live/history disagreements remain pending until a later coherent observation.
7. Later changed data reopens validation and publishes a new verified generation atomically;
   retain the old successful data with its dated receipt, never as "current verified" after a
   detected invalidation. Source `finished` regression, score changes, abandonment and correction
   to the participant set must invalidate readiness. A failed fetch alone does not delete an old
   generation or erase its last successful read time.

Fixture state: `SCHEDULED → IN_PLAY/WAITING_FINISH → WAITING_STATS → VERIFYING → VERIFIED`.
Track retry/overdue/failed status independently so a temporary HTTP error does not lose the fixture's
last evidence state. `VERIFIED` is revocable; correction checks are not optional cleanup.

## Reader safety and failsafe

`RankingMetrics` reads the fixture receipt inside its existing REPEATABLE_READ transaction,
alongside PFS and its single immutable schedule/asOf. Post-cutover current-season completed rows
without a valid receipt cannot be treated as certified by the new writer. Pre-cutover current
fixtures are backfilled/verified before enabling the new writer-backed read mode; historical
archives remain explicitly historical and are not retroactively labelled freshly synced.

Integrate the companion plan's systematic coverage guard. Pending whole-club evidence stops a
general squad recommendation; pick lists disclose restricted coverage. Individual missing rows
remain individual exclusions. Hard minutes admission is unchanged. A schedule refresh or run
heartbeat alone cannot clear a missing-metrics refusal.

Update `DataAge` narrowly: the receipt supplies actual **source read / verified-publication** times
for rows it covers; it is not PFS `updated_at` and must not date uncovered/historical rows. Preserve
unknown-refresh disclosures elsewhere. Existing daily-price and schedule provenance stay distinct.

Intraday `fpl_daily_price` refresh uses the existing season/date key and atomically refreshes the
whole selected capture from bootstrap. Its one-row-per-day history remains a daily observation,
not a claim to retain every intraday change; explain this and expose the latest actual capture
instant beside intraday availability. Do not backfill or rewrite earlier dates.

Resolve the existing cutover explicitly with deferred publication, not backdating: source reads
continue overnight, but the prior usable price/schedule capture stays published until the reader's
expected date switches: prices at 02:00 UK (`DailyPriceFreshnessCheck.expectedDate`), schedule at
03:00 UK (`ScheduleFreshnessCheck.CAPTURE_SETTLED`). Publish the 02:00 price batch when ready;
stage pre-02:00 price refreshes and the 02:10 schedule batch until their respective cutovers.
Then publish the newest validated current-date batch, preserving its **actual read time**, not
publication time, as capture provenance. Publish same-day recaptures normally after cutover.
Metric fetch/verification can proceed meanwhile; ranking still treats an unfinished older schedule
as pending until a compatible schedule is published. This short conservative window is deliberate.
If today's batch fails, do not promote yesterday to today: freshness refusal remains correct.
Test 00:00, 01:00, run times, exact settled boundaries, DST and overnight fixture correction;
the prior coherent capture must not be replaced early by one the reader will reject. No implicit
freshness relaxation to accommodate more jobs.

Watchdog: independent of the fetching executor, check durable due work, last successful run,
lease expiry and overdue fixture verification every five minutes. Alert once per incident with
season, match, missing IDs/counts, last source read, retry count and next action; send recovery
only on verified recovery. Fetch failures must not refresh success timestamps. Real live games
are pending, not failed syncs; the +3 h alert says **verification overdue**, not that the source
must be wrong. Alerts go through a configured owner notification channel, not only Fly logs.
Add an external heartbeat check so a stopped Fly machine/dead scheduler can be detected; an
in-process watchdog cannot detect its own process being down. Test alert delivery before cutover.

## Bounded implementation tasks

### 1. Inventory and source contracts

Capture representative public responses: finished fixture, ongoing match, source-returned zero,
missing row, transfer, double, postponed and corrected result. Document mandatory/optional fields
and the participant-manifest contract; synthesize negative variants in tests. Inspect existing
Tapas named writes and constraints; don't copy its parser's missing-field-to-zero behaviour.
Map every automatic/manual core-table writer, including `collect_points_against`, `--reset`,
`--sync-bootstrap`, `--sync-fixtures` and `collect_manager_snapshots`' gameweek upserts.

Verify: source-contract tests independent of live network; table/column ownership matrix and
compatibility checklist approved. If per-fixture participant evidence cannot be established,
revise the readiness design before implementation—do not weaken its gate in code.

### 2. Durable coordinator and bounded HTTP client

Add the assistant-owned run/payload/fixture-state schema, injected Clock, fenced lease, due-work
selection, coalescing, cooldown/budget and retention cleanup. CLI/manual retries go through the
same coordinator. Keep network I/O outside transactions; bound payload size and retention.

Verify: two workers, lease loss during fetch, crash/restart, duplicate due work, stale-generation
publication, 429 Retry-After, timeout, budget exhaustion, malformed data and retention safety.
No fixture can starve indefinitely without an overdue state being visible.

### 3. Bootstrap and schedule writer takeover

Add strict mappers and named writes for team/player/gameweek/fixture compatibility fields. Reuse
the same validated payloads for daily capture and captured schedule. Parent rows precede FK
children. Never prune a player needed by existing PFS history; missing players are not blanket
DELETE instructions. Invalid/partial league payload preserves the prior complete capture.
Keep startup/scheduled/CLI paths under the coordinator; remove duplicate legacy job dispatch.

Core `updated_at` remains row publication/write time (the transaction clock), never source-read
provenance; captured tables retain the actual source capture time. Fence bootstrap and fixture
chronology through fixed per-season source receipts in `fpl_sync_receipt`, with bootstrap shared by
catalogue and schedule and both endpoint intervals retained separately. The first owned publication
over legacy rows requires a newly fetched, fully validated batch after legacy writers are drained:
legacy `updated_at` cannot initialise or certify source chronology.

Verify: season changeover/reused IDs, archived rows untouched, null kickoff/gameweek, same-day
availability changes, read-at-02:00/published-at-03:00 timestamp separation, same bootstrap reused
across both publication groups, independently regressed bootstrap/fixture rejection, complete
timestamp-coherent schedule publication and transaction rollback including source receipts. Reader
cutover/date tests must pass, not merely mapper tests.

### 4. Full player-history ingestion and DefCon integration

Fetch, stage and validate complete current-season histories; publish by fixture with composite
keys and historical-club mapping. Update only fields evidenced by a valid response, with explicit
unknown handling; ensure corrections can replace earlier values without replaying older runs.
One fetched history supplies core metrics and DefCon. Do not publish provisional staging as
completed-match statistics. Retire the old DefCon job in the cutover configuration.

Verify: all scoring/ranking fields, genuine zero vs absence, transferred and departed players,
duplicate IDs, double-gameweek legs, partial sweep failure, idempotent correction and immutable
archives. Include missing optional metrics on insert and on correction of a previously known
value: sidecar masks/generation must prevent storage defaults or retained numbers entering a new
projection. Seed shared-schema tests through TapasSeed only in disposable Postgres.

### 5. Match verification state machine

Implement the contract above and fixture-level atomic publication/receipt. Ensure finish observed
before core rows does not certify anything; stats arriving before finish stays staged. Recovery
requires both sides and the manifest, not total row counts alone. Corrections revoke/reverify.

Verify: both arrival orders; incomplete side; failed player fetch; live/history disagreement;
two stable observations; later BPS/points correction; reopened/abandoned match; missing optional
metrics; participant transfer; unrelated fixture can publish despite another's failure.

### 6. Adaptive matchday scheduling

Implement the cadence table as due-time calculations, not multiple overlapping full-sweep crons.
Use actual fixtures, UK dates, pending backlog and persisted correction jobs. Recompute affected
work on kickoff/event changes. Daily metadata and all-player sweep remain the off-day safety net.

Verify: Saturday/Sunday/midweek, midnight, DST transitions, simultaneous matches, doubles,
postponement, restart after missed windows, next-season schedule and no matches. Simulated full
league matchday proves coalescing/request budgets and prioritises post-match verification.

### 7. Read-path integration and truthful recommendations

Wire fixture receipts and per-player/component evidence into RankingMetrics and the companion
coverage/refusal/prompt work. Audit every PFS reader (player cards, expected/team stats, squad
and pick ranking, my-team enrichment, and any raw SQL analytics); mask unavailable components
before aggregation, not just in the formatter. Update freshness/provenance for intraday captures
without deleting season attribution or relabelling archives. Keep solver/minutes/scoring
invariants intact. Add integration tests for pending →
schedule-only recovery → complete verified recovery → source correction invalidation.

Verify: sufficient alternative players cannot hide an absent club; a pin never bypasses coverage;
only the new published coherent generation is visible, with its actual source times. Test all
affected tool families, not just buildSquad. No external API request occurs during ranking/chat.

### 8. Watchdog, alerts and operating runbook

Implement independent-executor watchdog plus external heartbeat/owner notification integration.
Provide safe status and bounded retry commands, no destructive reset shortcut. Document how to
inspect source vs publication age, waiting finish vs missing rows, budget exhaustion and recovery.
Configure/verify the notification destination without committing secrets.

Verify: stopped scheduler, hung request, expired lease, DB failure, missed daily capture, overdue
finish, missing player histories, repeated failures, deduplication and recovery notifications.
A failing notification path must be visible independently; do not call log-only alerts delivered.

### 9. Single-writer handover from Tapas

Separate small Tapas PR: disable **all core-table writes** in automated and manual entry points,
while preserving manager/league and derived readers/jobs. Remove destructive core reset access.
Adapt Tapas metric consumers to the new per-component evidence for current-writer rows so raw
PFS defaults/retained values cannot become false known metrics downstream; test their unknown
handling before enabling the writer. Check collectors with embedded team/gameweek/PFS writes,
not just `scheduled_update`'s top level.
Prefer read-only DB permissions for Tapas on the transferred tables, after checking its other
operations; distinct application credentials are required to enforce this, not shared superuser
credentials. Do not revoke privileges blindly on the shared database.

Rollout sequence: deploy the new writer disabled and stage/compare current-season public data;
verify readiness/compatibility and alert delivery; owner merges/disables Tapas core writers and
drains running jobs; verify no active old writer; enable this writer; reconcile the current season;
then enable receipt-enforced readers. This is a bounded maintenance window, not overlapping writers.
Keep the comparison guard active during the window. If reconciling fails, remain in safe refusal.

Rollback: disable/drain the new writer first; preserve its data/receipts for diagnosis; only then
restore Tapas writing if the owner elects to do so. Do not restore a reader mode that silently
accepts incomplete comparisons. Rehearse with seeded data before any production switch.

### 10. End-to-end verification and reviewed release

Run a fake-clock/mock-FPL multi-match day with delayed finish, missing stats, 429, restart and
corrections. Assert eventual verified publication when the source recovers, bounded failure and
alert otherwise, and honest tool output throughout. Run the full disposable backend suite;
never integration tests against Supabase. Have a separate agent review concurrency, source
completeness, reader safety and cutover before committing verified bounded units.

Update AGENTS/STATUS/DEVELOPMENT and the ownership/runbook documents in both repositories.
Dedicated feature branches and PRs, normal pre-push checks, fixes as new commits and re-review;
owner merges, deployment through the existing workflow only. Observe at least one real matchday
post-deploy read-only: record source finish detection, verified coverage latency, request count,
correction check and delivered recovery/alert. Do not invent an upstream-latency SLA from one run.

## Acceptance gates

- Core updates continue with Tapas core writers disabled; this service is the sole active writer.
- All matchdays get repeated player-data refreshes without a whole-GW `data_checked` dependency.
- `finished=true` plus missing or inconsistent rows cannot produce a verified fixture or a
  misleading full-squad recommendation.
- Completed fixture data, receipt and times publish atomically; generations cannot go backwards.
- A crash, rate limit or partial sweep cannot mark work successful, lose pending follow-up, or
  leave the owner unaware indefinitely. Failure is bounded and visible, not a fabricated success.
- Legitimate zero minutes, historical club spells and season-scoped IDs retain their meaning.
- Post-match corrections are collected and attributed; missing optional metrics stay partial.
- No new chat-time FPL reads, no Arsenal-specific selection rule, no minutes-rule bypass.

## Tasks 1–2 outcome — foundation only (2026-09-13)

Task 1: eight public FPL captures with source URLs/read intervals, a writer inventory and 13
source-contract tests in `sync/source`. The deliberately partial parser is test-only. See
`docs/superpowers/specs/2026-09-13-owned-sync-source-contracts.md`. Fixture-scoped participants
and minutes are evidenced; raw `points_modification` arithmetic remains unresolved. Later work
must not derive corrected totals from it without evidence. Synthetic DGW/transfer/postponement
and correction variants are labelled, not presented as captured real cases.

Task 2 was split into three sequential, independently reviewed units: fenced durable runs and
bounded payload staging; shared persisted request admission/cooldown; and bounded HTTP transport.
`OwnedSyncSchema.initialize` is explicit, with no Spring registration or job activation. The
schema currently creates only run/payload/publication-receipt and request-state/attempt tables;
fixture verification and component evidence schema is deliberately deferred to Tasks 4–5, where
its actual publication contract is implemented rather than invented speculatively here. This is
infrastructure, not a functioning updater: Task 3 must connect dispatch/validation/staging to it.

The store coalesces compatible work, fences leases and publication by season/key, bounds bodies
at 2 MB and cumulative retained payloads per run at 20 MB, and protects active evidence from
retention cleanup. Exceeding a cap fails visibly; later dispatch must terminate/escalate oversized
work, not retry it forever. Request admission persists the one-second/two-permit/rolling-24-hour
budget and cooldown across instances. Valid Retry-After is respected without truncating long
but representable deadlines. Unpersistable deadlines use bounded malformed-header fallback.

Transport uses Boot-managed Apache HttpClient5's per-client retry/redirect controls, not cached
JDK-global properties. Body reads and total waits are bounded; cancellation acknowledgement has
at most one additional second. An unconfirmed exchange teardown records outcome/cooldown but
retains its permit until expiry. That bounds local admission; it does not prove when a remote
peer has observed socket closure. HTTP 200 remains raw, unverified evidence.

There are 49 new tests (13 source, 16 store, 8 budget, 12 local-HTTP). Review fixes covered JSON
strictness, unproven correction arithmetic, incompatible coalescing, season-scoped fences,
aggregate staging caps, Retry-After storage range and bounded cancellation. Final independent
foundation review found no further actionable issues. No public API is called by these tests;
all DB integration uses the disposable container. No Tapas files or production data were changed.
Linear access is unavailable in this harness; the required Kari link remains outstanding rather
than a fabricated issue/closure. The implementation PR is the interim tracking reference.

## Task 3 outcome — explicit catalogue/schedule pipeline (2026-09-14)

Implemented in three bounded subagent units with independent reviews between them:
`CatalogueScheduleParser`, `CatalogueScheduleWriter`/`CatalogueScheduleTables`, and
`CatalogueScheduleDispatcher`. The pipeline can be invoked explicitly but has no Spring bean,
startup runner, CLI or scheduled registration. Legacy entry points remain unchanged until the
Task 9 cutover; routing/activation belongs with Tasks 6/9, not an implicit switch in this task.

The parser covers named compatibility/capture fields, validates schema numeric capacity and the
configured season period, and reuses `ScheduleSource.assess` for the complete league invariant.
Historical player references need not remain in today's bootstrap. Assigned-undated and
unassigned-dated fixtures are both valid. A database season may have its actual August start and
no end date; the source-validation period derives from its season code, not a fabricated DB row.

Publication is split into canonical `catalogue-price` and `schedule` units for the 02:00/03:00 UK
cutovers. Each atomically writes compatibility rows, its entire capture and source receipts;
missing catalogue players are retained in core history but removed from the selected daily
capture. Both units serialize on the same season row before checking shared-team chronology.
Core `updated_at` is publication time; captures and separate bootstrap/fixture receipts retain
source time. Older endpoints cannot slip through merely because a combined batch has a newer
maximum timestamp. No PFS writes or existing-table schema changes are included.

Dispatch enqueues both units, shares one coherent fetched/staged pair, survives first-endpoint
interruption, and defers publication without backdating. A persisted source-demand floor advances
on new/coalesced demand but not on retry: the next cycle cannot reuse an old same-day pair forever,
and a demand arriving during a fetch is checked again under the publication lock. Obsolete pairs
are refreshed rather than terminally rejected; malformed source, payload-cap failure, transient
HTTP/budget deferral, lease loss and publication rollback remain distinct. Retry hints come from
the persistent request budget instead of a second Retry-After interpretation in dispatch.

Verified against disposable Postgres: 1004 tests, 0 failures/errors/skips, including 38 new parser,
writer and dispatcher tests plus a new store demand-fence regression. Full-suite verification
found that four ad-hoc failure/probe test tables lacked RLS; their initializers now enable it on
both fresh and reused tables. The security assertion was not weakened. A final independent Task 3
review found no further actionable issues. AGENTS now records the explicit write-ownership
exception while forbidding activation/production core writes before the reviewed handover.

Next is Task 4's full history/DefCon mapping and publication with component evidence. Fixture
readiness, adaptive scheduling, reader masking, alerts and cutover remain incomplete. Do not
interpret the new API or a parsed catalogue as a verified post-match result.

## Tasks 4–10 implementation outcome (2026-09-14)

Full current-season histories and DefCon now share strict parsing, historical-club mapping and
component-presence evidence. Fixture-scoped publication is atomic and fenced; absent components
mask retained/default storage values. Two independently fetched coherent batches are required,
with the second starting at least ten minutes after the first finishes. Corrections revoke and
reverify; partial sweeps, missing parents and 429 responses remain resumable pending work.

The adaptive UK planner persists slots, retries, recovery and correction checks. OFF/STAGING/ACTIVE
runtime epochs fence stale workers; ACTIVE requires explicit cutover confirmation and gates legacy
current-season writers. Evidence-aware PFS reads, whole-club comparison refusal, restricted pick
wording and the prompt preserve hard eligibility rather than filling missing clubs with alternatives.
Watchdog, incident/recovery outbox, authenticated status/retry and opt-in external health are wired,
with no real notification destination configured.

The separate Tapas change is [PR #1](https://github.com/quantti/tapas-fpl-app/pull/1): all core writers
are guarded, evidence-aware readers preserve pending appearances and unknown aggregates, and derived
Points Against work survives core-write disablement. Defaults remain unchanged. Final runbook review
corrected pre-publication/drain ordering, rollback receipt revocation and season-row lock grants;
role SQL remains review-only. No grants, flags, jobs or production data were changed.

Independent implementation reviews covered source completeness, concurrency, readers and cutover;
findings were corrected before final serial verification. The final local full suite passed 1132
tests with no failures/errors/skips, Tapas passed 700, and pre-push shell regressions passed 33.
The integrated fake-clock/FPL test covers delayed finish, partial histories, 429/restart, stable
observations, independent fixture recovery, corrections, component masks and incident/recovery.
Its ten-fixture, 600-player stress case deliberately exceeds the 5000-attempt estimated demand:
that budget is a hard ceiling, not a promise that every cadence can be fulfilled on a busy day.
Budget denial must leave work pending and visible. Actual source/throughput measurements are still
required during STAGING and the first approved real matchday; no upstream latency SLA is claimed.

After host memory exhaustion was reported, finalization ran without parallel agents/builds.
Checked-in Gradle/test/pool limits and smaller disposable Postgres buffers prevent the previous
unbounded context retention/idle-daemon pattern. A kernel-enforced 1280 MiB scope ran the suite
successfully (peak 1152 MiB; host available memory never below 1889 MiB). Docker on this WSL host
cannot enforce its own memory flags; that limitation is documented, not silently bypassed.

## Remaining decisions before execution

Select/confirm the owner alert destination and external heartbeat service in Task 8. Approve the
maintenance/cutover window and separate DB credentials in Task 9. The initial cadence/budget and
two-observation readiness rule are explicit proposed defaults to test, not claims about FPL's
service guarantees. The core ownership decision itself is settled: **this service, not Tapas**.
