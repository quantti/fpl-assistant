# Owned sync source contracts and writer inventory

Task 1 evidence for `2026-09-13-owned-matchday-sync.md`. This document authorises no writer,
schema, scheduler or production change. The owner has decided that this service will eventually own
the core writes; cutover remains Task 9.

## Captured public evidence

Eight unauthenticated GETs were made sequentially, at least 1.1 seconds apart, with a 20-second
timeout and no account/user endpoint. Bodies are unmodified under
`backend/src/test/resources/owned-sync-source-fixtures/`; `capture-metadata.json` records each URL,
HTTP status, byte count and UTC read interval.

| Specimen | Source read (UTC) | What it proves |
|---|---|---|
| `fixtures.json` | 2026-09-13 17:45:06.445–17:45:06.593 | Full 380-fixture schedule and raw state. GW3 fixture 29 is finished; GW4 fixtures 31–39 are started, `finished_provisional=true`, `finished=false`; fixture 40 is not started. No real postponed/unassigned fixture existed in this capture. |
| `bootstrap-static.json` | 17:45:07.693–17:45:07.896 | 38 events, 20 teams and 657 current elements. Events 1–3 are finished/data-checked and event 4 is current but unfinished. |
| `event-3-live.json` | 17:45:25.840–17:45:25.985 | Finished-event live evidence: 654 unique element IDs and one fixture-scoped explanation per element. Fixture 29 has 62 explanation rows, each with minutes; 31 have minutes > 0. Those 31 IDs exactly equal the union of the separately captured fixture-stat participants (16 home, 15 away). |
| `event-4-live.json` | 17:45:27.086–17:45:27.207 | In-progress event contract. Fixture 40 has 60 zero-minute explanations and no appearance; source presence is not appearance. Element 658 appears here but not in the later bootstrap, demonstrating that bootstrap alone is not an exhaustive historical identity list. |
| `element-summary-1.json` | 17:46:18.763–17:46:18.868 | Appeared history. For fixture 29, element 1 has 90 minutes and 3 points, equal to the fixture-scoped live explanation. |
| `element-summary-2.json` | 17:46:19.968–17:46:20.060 | Source-returned zero. Fixture 29 is a real history row with zero minutes, points, xG and DefCon. |
| `element-summary-557.json` | 17:46:21.160–17:46:21.222 | A second full appeared history with non-zero optional DefCon evidence. |
| `element-summary-658.json` | 17:46:22.323–17:46:22.405 | Historical identity outside the captured bootstrap: one zero-minute fixture-38 row remains fetchable. It is not evidence of an appeared transfer. |

There was no real double gameweek, postponed/unassigned fixture, corrected result, or appeared
player transferred between clubs in the available four-gameweek season. No captured body is
presented as one of those cases. Bounded synthetic tests cover fixture-scoped DGW legs whose stat
fields differ, side reconstruction from fixture plus history, a contradictory opponent, and a
historical pre-transfer side that differs from the current bootstrap club. A separate raw-shape test
pins pending and nullable postponed fields without claiming to implement the later fixture state
machine. Missing-row, duplicate-field/identity, malformed numeric and absent-versus-zero cases are
also explicit. A correction requires dated before/after source reads and cannot be manufactured from
one observation.

## Source contracts

All IDs are season-scoped. Numeric parsing is exact: an absent, null, textual non-number,
out-of-range integer or fractional integer is invalid, never zero; fields constrained positive and
minutes constrained non-negative reject negative values. Duplicate JSON object fields, trailing JSON
tokens, empty/null roots, duplicate identities and duplicate `(element, fixture)` rows invalidate
the batch. Unknown fields may be ignored; known fields must keep the semantics below. The parser in
`src/test` is deliberately partial source-proof support with no runtime caller; a production parser
waits for the writer contracts in later tasks.

### `/bootstrap-static/`

- `teams[]` mandatory for catalogue publication: unique positive `id`, integer `code`, nonblank
  `name` and `short_name`. Strength fields are nullable enrichments.
- `elements[]` mandatory: unique positive `id`, positive `team` referencing this same teams block,
  nonblank `web_name`, valid `element_type`, integer `now_cost`, status and every core season-total
  field the compatibility `player` row publishes. Names/news/set-piece orders and optional
  projections may be null, but their absence is not a default observation.
- `events[]` mandatory: unique event `id`, name, valid `deadline_time`, and exact booleans
  `finished`, `data_checked`, `is_current`, `is_next`. Aggregate/ranking fields may be null.
- Bootstrap identifies the current catalogue, not a player's club in an earlier fixture and not
  all historical element IDs. It cannot by itself certify a match participant set.

### `/fixtures/`

Each fixture requires unique positive `id`, integer `code`, two distinct team IDs present in the
same validated catalogue, exact `started`, `finished`, `finished_provisional`, and coherent scores.
`event` and `kickoff_time` are legitimately nullable for unassigned/postponed matches. Difficulty
and scores may be null where the source has not assigned/published them. `finished_provisional`
does not substitute for `finished`; both are stored faithfully and readiness is separate.
Fixture/event identity, teams and kickoff must agree with the history batch being considered.

### `/element-summary/{element}/`

Every `history[]` row must identify the requested `element` and have a unique positive `fixture`,
positive `round` and `opponent_team`, boolean `was_home`, integer `minutes` and `total_points`.
Traditional scoring fields used by PFS (`bonus`, `bps`, goals, assists, clean sheets, goals
conceded, own goals, penalties, saves, cards and starts) plus value/selection/transfer counters are
mandatory for core publication and must be well typed. `kickoff_time` may be null only when the
corresponding fixture is undated.

Expected-stat, ICT and DefCon fields are optional components. Presence and validity are recorded
per generation; absence must mask any PFS default or retained old value. A returned numeric zero is
present evidence. History side identity is reconstructed against the fixture: `was_home=true`
requires `opponent_team=team_a` and assigns `player_team_id=team_h`; false does the converse.
Current bootstrap `team` must never rewrite that historical assignment.

### `/event/{event}/live/` participant manifest

`elements[]` must contain unique positive `id`. Each `explain[]` entry has a unique positive
`fixture`; its `stats[]` identifiers are unique and carry exact integer `value`, `points` and
`points_modification`. Exactly one `minutes` stat is required per explanation. An appeared
participant is an explanation for that fixture with `minutes > 0`; zero remains a source observation,
not an appearance. Preserve each stat's `points` and `points_modification` separately. The captures
contain no non-zero modification and prove no arithmetic relating those fields to a corrected fixture
total; no sum or corrected total may be inferred. The top-level `stats` are event aggregates and must
never be compared with one DGW leg.

The endpoint does not put a team ID on an explanation. Coverage is therefore established by the
combination of sources, not live alone: enumerate every positive-minute live explanation; require
one matching history row with identical element, fixture and minutes; use that row's
`was_home`/`opponent_team` against the fixture to assign exactly one of the two sides; and require
both sides to resolve. Missing, duplicate or contradictory evidence leaves readiness unknown.
Zero-minute explanations do not prove every registered non-participant is represented, so hard-role
window completeness remains a separate check.

**Gate result: partial.** The participant-manifest gate is proven for implementation, with an
upstream-contract caveat. The captured finished fixture demonstrates that `explain` provides a
fixture-scoped enumeration of appeared IDs: all 31 exactly match the separately captured fixture-stat
participant set, with both fixture-stat sides represented, and minutes match element-summary for the
checked player. Each explanation names its fixture; synthetic tests pin separate DGW legs, differing
leg fields, and side reconstruction/contradiction handling. FPL publishes no formal completeness
guarantee, so verification must retain the required cross-check and alert/abstain on any missing
participant or side. This evidence supports that gate; it does not justify weakening it to HTTP
success or row counts.

**Correction arithmetic remains unresolved and blocks any claim that corrected fixture points can
be derived from live explanations.** The captured `points_modification` values are all zero. Later
stability/correction verification may compare only independently proven raw fields (for example,
fixture-scoped minutes and each unchanged raw points/modification field) or must first resolve the
upstream contract using dated before/after evidence. It must never manufacture `points +
points_modification` as a corrected fixture total.

## Core table and writer inventory

Schema facts come from `backend/src/test/resources/db/tapas-schema.sql`, regenerated from the
shared schema. Composite keys are season-qualified: `team`, `player`, `gameweek` and `fixture` use
`(id, season_id)`; PFS uses `(fixture_id, player_id, season_id)`. Player/fixture FKs require parent
season/team rows first. PFS core metric columns are NOT NULL with zero defaults; its update trigger
changes `updated_at` on DefCon-only updates, so that timestamp cannot date core metrics.

| Core table/columns | Current writer entry points | Current behaviour and handover requirement |
|---|---|---|
| `team` | Tapas `scheduled_update` (when empty), `--sync-fixtures` (when empty), and `collect_points_against`; test utilities `seed_test_data.py` | Named upserts. `collect_points_against` rewrites current bootstrap identity/strength and derives season as the largest ID. Disable every production/manual route; retain test seeding only with an enforced non-production DB guard. |
| `player` | Tapas scheduled full update; Tapas `scheduled_update --sync-bootstrap`; `seed_test_data.py` | Named upsert, currently gated by whole-GW `data_checked`; parser uses `.get(..., 0)`/safe coercion for many fields. Replace with strict owned catalogue publication; disable both scheduled and manual Tapas paths. Despite its help text, `--sync-bootstrap` writes players only and assumes teams exist. |
| `gameweek` | Tapas `collect_manager_snapshots` default/single-manager; scheduled quick/full manager paths import its `sync_gameweeks_from_bootstrap`; `ensure_gameweek_exists` inserts placeholder `NOW()` deadlines; `seed_test_data.py` | Embedded core writes remain even if top-level core sync is disabled. Remove/uproot these writes or make manager jobs depend read-only on owned gameweeks; never keep placeholder upserts after handover. |
| `fixture` | Tapas scheduled full update; Tapas `--sync-fixtures` | Named full-schedule upsert. The scheduled path runs only after `data_checked`; manual path is independently callable. Disable both at cutover. Owned compatibility fixture and `fpl_fixture` must publish from one validated source batch. |
| PFS core columns | Tapas `collect_points_against`, called by scheduled full update and directly; its `--reset` deletes all season PFS first; `test_small_collection.py` writes PFS | Per-player upserts occur before the collector's later aggregate transaction and tolerate up to 10% fetch failures. `player_team_id` comes from current bootstrap, so transfers can misattribute old rows. Parser `_safe_int/_safe_float` converts missing/malformed values to zero. Disable direct, scheduled and reset paths; restrict test script to disposable DB. |
| PFS DefCon columns | This service `DefensiveStatsScheduler`, `--sync-defcon`, and historical `--backfill-defcon`; `DefensiveStatsSchema` adds the four nullable columns | UPDATE-only and minutes-guarded, but missing fields currently write null and no generation sidecar exists. Fold current-season fetch/publication into owned history and retire independent scheduler/manual sync at cutover. Historical backfill remains separately scoped. |
| All public tables | Tapas `scripts.migrate --reset` | Drops every public table, including assistant-owned tables, then reruns Tapas migrations. This destructive manual path must be removed or made incapable of targeting the shared production database before handover. |

Assistant `DailyPriceSyncService`/`PriceSyncService` write only assistant-owned price tables;
`ScheduleSyncService` writes only `fpl_gameweek`/`fpl_fixture`; football-data and vaastav history
write `pl_match_result`. They are not hidden core writers. `TapasSeed` is the permitted disposable
integration-test writer and refuses the shared database.

## Compatibility checklist for later writer tasks

- Resolve exactly one configured playing `season`; validate source dates/identity and never choose
  max season ID or `SeasonResolver` fallback for writes.
- Publish parents before children with the existing keys, types, FKs and named column lists; do not
  delete archives or prune players needed by PFS history.
- Preserve nullable event/kickoff/scores and raw fixture status; retain all 38 events and 380
  fixtures, including unassigned rows.
- Preserve real zero separately from absent/invalid data. Never copy Tapas `_safe_int/_safe_float`.
  Mask optional PFS components through generation evidence before any reader aggregation.
- Derive historical `player_team_id` from fixture plus history, not current bootstrap. Fetch IDs
  evidenced by live/history even when absent from the current catalogue.
- Stage network bodies before short atomic catalogue/schedule or per-fixture publication. One
  validated schedule batch feeds both compatibility and captured tables.
- Before activation, disable/drain every Tapas route listed above, audit DB privileges, backfill
  receipts/evidence, and verify Tapas consumers honour unknown components. Task 1 changes none of
  these paths.
