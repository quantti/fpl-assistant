# Restore current-season Team xCS

**PROGRESS:** Superseded for team metrics by [Understat migration](2026-10-18-understat-team-xg.md); do not implement this reader expansion as part of that migration.

## Goal and known cause

Restore usable current-season xCS without rejecting a valid publication merely because it uses
the older protocol. Explain legitimate n/a values; retain the 48px rows and existing forecast math.

Baseline: local `8f556c3`, branch `feat/shortlist-comparison`, PR #162. The unit legend is already
local in `9897aab`. At planning time staging ran code `9edb0c3`, published PR head `879ad6c`.
Preserve unrelated staging-automation files and the original worktree's sync/outage work.

The October 17 diagnosis reproduced MCI 1.5402616728 from 2025/26 alone: current-season rows
were readable as VERIFIED, but the population guard treated an appearance snapshot as incremental
ownership and required PROVISIONAL rows/page receipts. All current club sides disappeared.
Hull, Ipswich and Coventry lacked previous-season PL models, so their opponents' totals were n/a.
These are dated observations, not a guarantee about the database at implementation time.

Pi-review found two plan defects: applying the incremental correction floor to valid fixture-wide
certificates can recreate the rejection; requiring fresh live captures to accept the code couples
two independent problems. This revision addresses both; it has not yet been independently re-reviewed.

## Acceptance rule: automatic, according to the actual publication protocol

Repair the shared `PfsReadSource` boundary, not a raw-query workaround in the shortlist.

- **Fixture-wide VERIFIED:** accept the complete, unrevoked certificate under its own protocol:
  current verified generation, matching player evidence, source identity/header/stats, participant
  completeness and the protocol's source-time/two-observation checks. Reuse the existing verifier,
  publisher and receipt semantics; a status flag or row count alone is insufficient.
- **Appearance snapshot only:** this is not incremental write ownership. A first later snapshot
  with the same contents, or an unchanged reread, must not invalidate a valid fixture certificate
  merely because its timestamp is newer. Actual source, roster or minutes contradictions still
  invalidate incompatible evidence; do not impose a different protocol's floor on unchanged data.
- **Actual incremental ownership:** retain the existing ownership fences, current-pointer/page/
  generation/source/roster/correction-floor proof and shortlist FINALIZED requirement. Do not fall
  back to old VERIFIED or physical rows, including after OFF rollback or missing current rows.
- **Insufficient or revoked proof:** exclude that model fixture and explain the absence. This is
  a normal automatic result, not a reason to stop the entire repair or request human approval for
  each match. Preserve the existing guarded archive contract and optional-component masking.

No schema, new mode, importer activation or publication change is planned. If implementation
needs one, stop that scope expansion for an owner decision; do not manufacture missing receipts.

## Task 1 — Reproduce the compatibility bug with real receipt tests

In `PfsReadSourceIntegrationTest`, seed a complete fixture-wide certificate using the actual
publisher/receipt contract, not just a VERIFIED flag. Verify automatic acceptance with no snapshot,
with the first later identical snapshot, and after an unchanged reread. Reproduce the current
failure before changing the reader.

Keep the refusal in `currentLiveRosterCannotPromoteLegacyAggregateWithoutCurrentPageBackedRows`:
its incomplete certificate must remain insufficient. Its assertion that roster presence itself
means INCREMENTAL is not a policy to preserve. Add negative cases for actual source/roster/minute
changes, missing or extra positive participants, wrong season/side/opponent, revoked or mixed
verification and actual incremental ownership accompanied by an old fixture certificate.
Keep zero-minute nonparticipant and optional-NULL versus known-zero coverage.

Verify: valid fixture certificates pass under their own protocol; insufficient fixture proof and
invalid incremental proof remain refused. Seed only disposable test data; no production oracle.

## Task 2 — Fix the shared reader and prove the calculation

Separate publication ownership from appearance-snapshot presence in `fixtureSidePopulation`.
Audit `SidePopulation.incremental()` as well: changing the receipt label alone must not accidentally
skip coverage checks. Reuse existing proof fields/helpers and audit both callers, `RankingMetrics`
and `ExpectedStatsTools`; no per-player SQL or separate relaxed ranking/squad path.

Exercise the real reader in `RankingMetricsIntegrationTest` and
`ShortlistComparisonIntegrationTest`: current and previous seasons, a promoted club without an
archive, complete fixture-wide proof and a later identical appearance snapshot. Assert current
sample inclusion and latest included kickoff, unchanged blending/league floors and per-fixture
xCS math. A bad side excludes its entire model fixture; unfinished/unverified matches do not
suppress unrelated valid completed matches. A missing upcoming projection still withholds the
whole horizon total rather than returning a partial sum. Do not pin the result to Haaland's 1.54.

Verify: positive and refusal regressions pass; one coherent schedule, season-qualified identity,
outer read-only REPEATABLE_READ and bounded-query behaviour remain intact. Existing incremental
and real FINALIZED-proof checks remain unchanged, not replaced with mocks.

## Task 3 — Explain remaining n/a and verify the code

Use existing `Forecast.reason`, row reasons and sample metadata. Distinguish stale captures,
unknown horizon, insufficient verified match/league evidence and missing own/opponent model.
Name the club/gameweek when known, e.g. “GW8 vs IPS: opponent model unavailable; full-window total
withheld.” Show a short accessible n/a-cell explanation and the full reason in the existing
expanded card. Clearly label previous-season-only modelling even when its result is numeric.
Keep the unit legend, two-sentence source summary and name/numeric-only sorting.

Run affected focused backend tests sequentially: PfsReadSource, RankingMetrics, ExpectedStatsTools
and shortlist integration/unit/API tests as affected. Use disposable localhost:55432, checked-in
Gradle/WSL resource limits and no local full suite. Run focused frontend tests, lint, format and
build; use the real App shell to check keyboard/touch explanations and fixed 48px rows with long
names and double/triple gameweeks, including after expansion.

**Code acceptance:** proven protocol-specific acceptance and refusal, correct maths/provenance,
clear explanations, fixed row height, review and green full CI on the exact proposed PR head.
A missing live capture does not prevent completing or accepting this code repair.

## Separate live and publication acceptance

October 18 morning's captures still belonged to October 17. Check current dates read-only before
claiming live success. **Live benefit verified** requires fresh coherent price/schedule captures
and actual comparisons using current-season evidence, including promoted-club opponents.
If captures remain stale, record live verification as pending without reopening code acceptance.
Do not claim that all 3,883 formerly observed rows necessarily have complete certificates.

Eligibility is always decided by the reader, without match-by-match human approval. Existing
approved sync jobs may publish fresh valid evidence normally. An owner decision is needed only
for operational actions not already authorised: manual production repair, writer/cutover changes,
push/deployment or merge. Staging shares Supabase and keeps its own capture jobs disabled.
Do not erase ownership receipts, fabricate zeros, run pooler session SETs or restart old writers.

Commit verified implementation locally. Publish to the existing PR only when authorised; an empty
CI rollup is not green. Stage and merge only on explicit decisions, not because this plan exists.
Rollback must preserve actual publication ownership and compatible readers; mode changes follow
`docs/OWNED-SYNC-OPERATIONS.md`. This revision authorises no implementation or live operation.
