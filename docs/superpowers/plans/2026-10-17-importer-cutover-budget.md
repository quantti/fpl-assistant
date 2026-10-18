# Importer cutover: bounded request headroom

**PROGRESS:** Budget PR #165 merged as becef01; master CI/deploy 39057123698 passed. Live handoff blocked by missing SecurityConfig operator routes (401/403); runtime still OFF106. Narrow route fix passes 11 focused tests; its review/CI/deploy and importer activation remain pending.

## Operator-route follow-up

The live handoff route was not listed beside existing dedicated-token ops routes in
SecurityConfig, so denyAll rejected it before SyncOperationsController. A user JWT did
not help (403); no new runtime epoch was minted. On 17 October the owner explicitly
approved a separate narrow correction PR, two concurrent gpt-6.1-sol read-only reviewers,
merge only after a clean review and green CI, normal deploy, then continuation of the
already-authorised cutover without another command. The handoff flag is false while
waiting. No direct SQL, wildcard ops access or weakening of token/epoch guards is allowed.

## Owner request and live baseline

On 17 October the owner authorised stopping the legacy sync and switching to the incremental
importer, including assessing/changing the internal request budget for immediate collection.
No permit reset, unlimited API use, direct master push or unreviewed production code is authorised.

At 13:53 UTC the live runtime was OFF epoch 106, no PENDING/LEASED owned runs and no unresolved
permits. Public health UP. Legacy consumed 5,000 attempts in its rolling 24-hour ledger and
stopped admitting at 11:57 UTC. Its last GW7 fixture capture was 11:50 UTC. GW6 has 10 VERIFIED
fixtures and 667 physical PFS rows; no incremental runs/current pointers/gameweek finality exist.
The source-playing season is 2026-27/id 2. One Fly machine remains, 6832545c205238.

Read-only preflight found all 211 importer-required columns, the six named validated CHECKs,
and enabled payload hash/prune guard. This catalogue check did not replace the importer's own
full definition checks at startup. Tapas runtime has SELECT on the three new proof tables and
no INSERT/UPDATE/DELETE on the five core tables. Assistant PR #159 and Tapas PR #3 tests passed;
both merged. No manual SQL write or source request was used for these inspections.

## Budget assessment and chosen change

The 5,000 ceiling is our own code limit, not an advertised FPL provider quota. The candidate
union of today's catalogue and current-season PFS is 667 players; seven gameweeks have started.
A simple cold pass of 667 deduplicated pages, seven event-live reads and initial/terminal source
pairs is roughly 680 requests. This is an estimate, not a strict cap: changed sources, retained
participant discovery, final-sweep retries and failures can add work.

Choose an importer-only configurable **6,000 attempts per rolling 24h**, providing at most 1,000
extra attempts above the already-consumed ledger without erasing it. Keep defaults at 5,000 for
both modes and a hard validation ceiling of 6,000. Only set the new production importer option
to 6,000 during the approved switch. Do not extrapolate this one-time headroom into monthly
network/DB egress acceptance; observe first-cycle throughput and resource use. Budget exhaustion
still defers work rather than bypassing admission. Do not further raise the cap on a failed cycle.

Unchanged: one-second spacing, live permit cap, 24h window, expiry, unknown-outcome blocking,
429/Retry-After handling, cooldown/backoff, leases, publication and reader proof. The importer
watchdog receives the exact same Limits as admission, so 5,000 old attempts alone no longer
produce a false exhausted incident when the importer is configured for 6,000.

New option: `OWNED_SYNC_IMPORTER_MAX_ATTEMPTS_PER_DAY` →
`fpl.owned-sync.importer.max-attempts-per-day`, default 5000; validated 1..6000.
It is wired only into the importer request budget/watchdog; legacy wiring stays at default.

## Validation

Regression first failed with expected 6000 / actual 5000. Seventeen focused unit/config checks passed (five new budget cases plus nine runtime and
three ops-configuration cases). They cover
unchanged defaults, exact bounded override, all other guard fields, refusal of invalid or
out-of-range options and watchdog behaviour below/at the configured cap. A mocked real-admission case proves
5,000 recorded attempts still block the default budget, allow the 6,000 importer budget,
and block again at 6,000; only expired completed records are pruned. This is not a
PostgreSQL concurrency test. The existing DB-backed
boundary test now checks 6001 refusal while retaining 5000 default assertions. Its full class
requires disposable Postgres and is left to CI because Docker's socket was unavailable here;
no production test DB is substituted. No local full suite. PR CI is mandatory before publication.

## Production sequence after reviewed green release

1. Re-read live machine/version, OFF epoch, outstanding jobs/permits, source-playing season and
   request count. Stop on unexpected writers, unresolved attempts or changed ownership.
2. Current OFF config also disables independent daily/schedule/DefCon/startup-price fallbacks.
   Keep it inert during code deployment. Enable the reviewed operator handoff surface while OFF
   with explicit season/cutover acknowledgement, without printing credentials.
3. Call the authenticated operator handoff with the observed OFF epoch and playing season;
   never UPDATE runtime rows manually. Read back its fresh ACTIVE importer epoch. The old
   runtime remains absent. No uncertain POST is retried without checking persisted state.
4. Start MODE=ACTIVE + IMPORTER_ENABLED=true + IMPORTER_MAX_ATTEMPTS_PER_DAY=6000.
   Re-enable independent daily-price and schedule capture flags required by this path; keep
   legacy DefCon and startup-price writers off. Disable handoff after use. No new machine.
5. Verify importer-specific heartbeat, first source receipts, actual incremental page publication,
   component/population masking, gameweek finality as appropriate, ledger/cooldown, memory and
   request pace. The old two-observation verifier must not run beside it. Stop on safety failure
   via reviewed importer shutdown/retire, preserving evidence and never restoring legacy writes
   over provisional rows implicitly. Do not claim a green first cycle from heartbeat alone.
6. Replace the paused Kuma legacy monitor with importer health using the existing monitor token
   header and email channel. Preserve historic incident evidence. The same health URL selects
   importer heartbeat only when importer mode is actually configured. Leave general site monitor.

Any failing CI, startup schema proof, handoff or reader safety check blocks activation. One
cycle's first useful row/finality time and actual request/egress use must be recorded, not inferred
from the static estimate. Current research/incident documents remain in the separate Fly-recovery
worktree; this feature branch is based on fresh master a144675.
