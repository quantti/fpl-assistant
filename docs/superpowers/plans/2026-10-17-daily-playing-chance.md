# Capture FPL's next-round playing chance

**PROGRESS:** Task 2/3 done: storage and same-snapshot comparison DTO verified.

## Contract and scope

Persist `elements[].chance_of_playing_next_round` from the bootstrap response already fetched
by `DailyPriceSyncService`, then expose that same daily observation to shortlist comparison.
The owner requested this plan after the UI could not distinguish yellow and amber FPL flags.
This is FPL's published availability estimate, not a probability of starting, minutes played,
or our own forecast. Do not derive it from news text, position, observed minutes or status.

Planning baseline: `0ae445e` on `feat/shortlist-comparison` (PR #162). A separate, unfinished
`scripts/tests/staging-deploy-test.py` exists in the worktree; it belongs to staging automation
and must not be included in this implementation. Owner approved implementation after planning;
live schema operations, captures, scheduler activation and deployment remain separately authorised.

## Root cause and existing seams

- `price/DailyPriceSyncService.toRows` currently retains status/news and set-piece fields but
  drops this bootstrap field; `upsertAll` has an explicit 18-column/parameter write contract.
- `price/DailyPriceSchema` owns `fpl_daily_price` and creates/upgrades it at startup. Its primary
  key `(season, price_date, element_id)` and RLS must remain unchanged.
- `myteam/FplBootstrap` already reads the API field for on-demand squad data. That is a different
  capture and must not be borrowed to fill shortlist data or changed as part of this task.
- `shortlist/ShortlistComparison` already batches daily set-piece data for the saved element IDs,
  selected season and price date inside its outer read-only REPEATABLE_READ transaction. Extend
  that query and `Market` record rather than expanding `RankingMetrics.CapturePlayer` and all
  its unrelated ranking/squad consumers. The existing `fresh` guard controls market exposure.
- `frontend/src/shortlist/comparison.ts` describes `Market`; `ShortlistPage.tsx` renders and sorts
  the availability column. `AvailabilityTools` and ranking eligibility need no changes: they
  already use FPL status, and this change must not introduce a new selection rule.

## Data contract

Database column: `chance_of_playing_next_round SMALLINT NULL`, without a default. Include it
in new-table DDL and an idempotent `ADD COLUMN IF NOT EXISTS` upgrade for existing tables.
Use a named CHECK accepting NULL or an integer in 0..100; add it idempotently to existing tables
as well. Validate source values before writing, so malformed optional data cannot abort an
otherwise valid daily capture. Only integral numeric values in range are observations; absent,
JSON null, strings, fractional, negative, out-of-range or non-finite values become NULL. Do not
reuse `asInt` blindly: its `intValue()` truncates fractional numbers.

Append the new parameter after existing fields in `toRows` and the INSERT column list to preserve
existing array indexes; update placeholder count and ON CONFLICT assignment together. A new
capture reporting NULL clears the current date's former value: no COALESCE retaining an old
percentage. The whole capture remains one existing atomic transaction, and `captured_at` follows
the existing semantics. No additional HTTP request and no new cadence or independent timestamp.

Existing historical rows stay NULL. Do not backfill them from today's response, another date,
another season or free text. Same-day re-capture is a new current observation, not a historical
backfill. NULL is unknown; zero is a genuine published 0% value.

API: add `market.chanceOfPlayingNextRound: number | null`. Populate it from the same season,
date and player row as the existing daily duties, only when market data passes the current
freshness/identity guards. Old-season entries and stale captures must not gain a current chance.
The market's `capturedAt` dates this value as well; the turn's `asOf` is not its observation time.
Treat an absent JSON property like NULL in the frontend for rolling-version compatibility.

## Availability presentation and sorting

Retain the separate status column and visible status labels. Add a visible percentage only when
known, e.g. `Doubtful · 50%`; explain in the detail card that this is FPL's captured next-round
estimate, not a starting guarantee. Do not turn `Available` with NULL into a made-up 100%.

Colour precedence:

| Evidence | Presentation |
| --- | --- |
| Explicit injured/suspended/unavailable/not-in-squad status | Red status, even if percentage conflicts |
| Published 0% without conflicting available status | Red flag |
| Doubtful and 25% or 50% | Amber/orange flag |
| Doubtful and 75% | Yellow flag |
| Available without conflicting percentage | Existing available styling; never imply FPL assigns a green warning flag |
| Doubtful without a percentage | Neutral unknown-severity badge, not assumed yellow |
| Unknown status, contradictory status/chance, or other valid percentages | Neutral badge retaining the reported label/percentage; do not invent a flag mapping |

Status labels and percentages must carry the meaning without colour. Keep `news` as an explanation,
not a parser input. Verify light/dark contrast and use a labelled colour token/style rather than
hard-coded branching spread through JSX. The availability header remains bidirectionally sortable;
keep the existing alphabetical status ordering unless the owner separately requests a severity
or numeric-probability sort. No change to other column ordering or eligibility.

The yellow/amber convention is corroborated by
https://www.fantasyfootballscout.co.uk/2026/07/20/how-do-fpl-players-become-unavailable
(75% yellow; 25/50% amber; ruled out red). Exact accessible hues need not copy inaccessible pixels.

## Bounded implementation sequence and verification

### 1. Persist the optional observation

Change `DailyPriceSchema.java`, `DailyPriceSyncService.java` and
`DailyPriceSyncServiceTest.java`; add a focused disposable-database integration test for this
writer/schema if no existing test seam suffices. Use an injected/stub HTTP bootstrap response,
never FPL or production data. Add tests before implementation where the behavior exists to fail.

Verify 0/25/50/75/100, absent/JSON null, malformed types, fractional and range-invalid data;
assert the new parameter without disturbing existing set-piece indexes. Against the disposable
container verify fresh DDL, upgrading a pre-column table containing an old row, running the
upgrade twice, valid INSERT, same-key 75→50→NULL UPDATE, and historical dates/seasons staying
untouched. Exercise actual SQL binding, not merely the row array. Validate CHECK rejection for
invalid direct SQL in an isolated transaction. Preserve RLS and the season-qualified key.

### 2. Carry it through the existing shortlist snapshot

Extend `ShortlistComparison`'s daily batch SELECT and Market mapping, plus
`ShortlistComparisonIntegrationTest` (and any direct Market constructors found by caller search).
Do not create per-player SQL or call `FplBootstrap`/external APIs from comparison.

Verify known zero vs unknown, same element ID in different seasons, no value from a different
capture date, stale-market refusal, and same-day capture replacement. Extend the existing
concurrent-commit snapshot test to change the percentage as well: one response must keep a
single coherent old snapshot, never mix a new percentage with an old status/news/capture time.
Confirm statement count does not grow with saved-player count.

### 3. Render actual FPL flag distinctions

Extend the frontend market type and one tested availability-presentation helper used by the
shortlist status cell/detail card. Cover the full table above, missing-property compatibility,
percentage text, news not parsed, contradictory evidence and unchanged null-last/header sorting.
Use deterministic fixtures rather than signed-in account writes. Run page/provider tests,
ESLint, Biome, TypeScript/Vite and isolated Chromium at mobile/desktop light/dark sizes.

One build/test unit at a time. Focused backend tests only locally, using `scripts/test-db.sh`
and `docs/DEVELOPMENT.md` safety/resource rules; never run tests against Supabase. GitHub retains
the full-suite gate. Record actual test commands/results, source-review findings and remaining
limits in STATUS. Commit verified bounded slices, push the existing feature PR branch and review
the final diff; no merge or deployment without separate owner authority.

## Rollout and acceptance

The schema is shared between staging and production: deploying even staging runs its startup
DDL against that shared database. An additive nullable column is compatible with the old writer
(which omits it), but staging must still have all capture schedulers and owned-sync writers off.
Do not trigger a production capture just to make a screenshot work.

When separately authorised: apply the additive schema through the established application
startup mechanism, deploy compatible writer/reader code, and let the next normal daily capture
populate today's values. Before that capture, old NULL rows must render as unknown severity.
If earlier population is necessary, request a separately authorised, existing bounded capture
operation with explicit target season/date; never backfill history or enable staging schedulers.

Acceptance requires a disposable-DB round trip, coherent comparison API evidence and a visible
25/50-vs-75 distinction based on stored values. Rollback code leaves the harmless nullable column
and captured observations intact; no DROP, destructive migration or invented historical values.
