# Use Understat for all team xG/xGA

**PROGRESS:** Task 3/3 done locally — consumers `4f2633a`, focused verification and collection audit complete; publication/live capture/activation remain separately authorised.

## Contract

Owner selected all team xG/xGA: the shared ClubRates model (shortlist, picks and squad) and
getTeamExpectedGoals use Understat. Player xG/xA/xGI and DefCon remain FPL data. The team tool
no longer reports player-summed xA/xGI. No mixed-provider fallback. This supersedes the proposed
fixture-wide/incremental compatibility repair for the club model; its player-proof rules remain.

Baseline: feature worktree `/home/vanka/dev/fpl-assistant-comparison`, local `8f556c3` plus the
uncommitted compatibility plan/research/STATUS. Preserve the unrelated staging automation test
and original worktree sync/outage work. No new delegation, push, deployment or production sync.

## Task 1 — Capture direct match totals

Fetch Understat getLeagueData/EPL/{startYear}, one request for the playing season and one for
the preceding season. Use the database's playing-season identity, not the FPL metric fallback.
Strictly parse complete results: provider match id, explicit club aliases, datetime, scores and
both finite nonnegative xG values. Ignore unplayed fixtures, retain real zeros, reject duplicate
ids/pairings and wrong-season or malformed results before replacing any data. Decode gzip and
bound HTTP time/body size. Understat datetimes are interpreted as UTC; verify against the FPL
fixture samples and public client convention, never silently apply the host timezone.

Store only owned `fpl_understat_capture` and `fpl_understat_match` tables, configurable `_test`
names, RLS, atomic per-season replacement and capture timestamps. Update valid seasons even if
another season's fetch/parse fails; retain the failed season's preceding capture and report an
operational failure. A valid empty capture removes old results instead of resurrecting them.
Store exact decimals. One match supplies both teams' xG/xGA, goals and venue/date, so no player
roster, player histories or incremental finality is required for a team observation.

Provide an explicit sync runner and a disabled-by-default daily scheduler. Fetch only at sync
time; reads never call an external API. Do not auto-activate it or populate shared Supabase.
Test parser, gzip/error handling, capture replacement/rollback and literal zero using local HTTP
and disposable PostgreSQL. Commit the coherent green storage slice locally.

## Task 2 — Replace team reads, not player evidence

Load Understat current/previous sides inside the existing ranking snapshot. Bridge clubs by
explicit canonical identity to the daily pool; pool all archived clubs for league constants.
Retain ClubRates mathematics, fixture-count ramp and sample floors. Source ages/seasons refer
to Understat captures and included match dates, not PFS writes or player seasons. A valid zero
xGA is a real observation, not the old PFS unpopulated-side sentinel.

Delete the now-unused PFS team SUM/MAX/whole-fixture/finality bridge from RankingMetrics.
Do not relax player evidence checks or use PFS population coverage to gate Understat xCS.
The FPL schedule still defines the editable horizon, blank/double handling and unknown windows.

Replace the team tool's PFS aggregations with stored Understat matches, xG/xGA, actual scores,
source season/capture and last-match attribution. Keep the public tool name and bounded lastN;
remove team-only xA/xGI and their dead helpers. Update callers/tests/prompts, and retain player
formatters/proofs. Missing source/model/horizon stays n/a with a useful reason, never a partial
full-window total. Publish Understat provenance in shortlist/picks/squad and the team tool; player
metrics retain their separate FPL source. Keep the two-sentence source summary, unit legend,
accessible explanations, sorting limits and 48px rows.

Test source independence (incomplete FPL player roster cannot erase valid team rates), absent
Understat data (no FPL fallback), corrections, promoted clubs, cross-season identity/blending,
source provenance, zeros and all affected ranking/squad/tool contracts. Commit locally.

## Task 3 — Verify and report collection simplifications

Run connected focused tests one build/test unit at a time with existing WSL/Gradle limits and
disposable localhost:55432; no local full suite. Frontend tests/lint/format/build and real App-shell
layout checks as affected. Parent reviews the committed slices and integrated diff. Full CI and
independent review/publication need their normal separate authority; no clean claim from old CI.

Remove only team-aggregation orphans made unused by this change. Audit rather than silently
remove existing collectors: player histories still serve player ability/DefCon/minutes, live
rosters still serve player publication proof, daily captures supply prices/availability and the
schedule supplies deadlines/fixtures. Historical football-data/vaastav results remain separate;
Understat also carrying scores is a possible future consolidation, not permission to disable
those jobs or rewrite their archives. No player-level data is replaced by team-level data.

Document fewer team dependencies/queries, two bounded league fetches per sync, retained data
needs and any remaining simple consolidation opportunity. API availability is not a licence or
SLA: permitted automated/commercial use, sustained reliability and operational cadence remain
explicit rollout checks. Record that model outputs may change because Understat is another
xG model; do not call its data FPL or mix providers invisibly.

## Acceptance

Code acceptance requires truthful one-provider team metrics, actual parser/store/reader and
consumer tests, no lost player validation, no new dependencies or schema changes to Tapas tables,
and committed verified slices. Live acceptance separately needs approved initial capture and
staging inspection. Missing capture is explicit, not a reason to silently retain the old model.

## Local outcome

Consumer `4f2633a` removes the team-only player aggregation/helpers and xA/xGI output, preserves
player proof and the existing ClubRates mathematics, and propagates Understat sample/capture
metadata and n/a reasons. Parent reviewed both committed slices and the integrated source/diff;
no blocking finding remains in that bounded review, not an independent approval.

Focused green receipts: 101 source/player/shortlist tests together; 73 pick/squad/model tests;
22 tests covering PFS proof and two touched collector methods; prompt/DataAge checks; 61 frontend
tests, lint, format and TypeScript/Vite. Eight synthetic real-App Chromium cases cover
3840/1920/1440/390 × light/dark: 48px rows, full width, 8 sortable headers, inline TGW,
accessible ellipsis/n/a, two-sentence source summary and 44px touch controls. Details alone grow.
No live account/backend browser claim, local full suite or fresh CI claim.

| Collection | Decision |
| --- | --- |
| FPL core/player histories | Keep player ability, minutes, DefCon and publication evidence; remove only the team SUM/MAX reader dependency. |
| Daily price/availability/playing chance | Keep current identity, cost and flags. |
| Schedule/deadlines | Keep editable horizons, blanks/doubles and fixture identity. |
| Understat league results | Two new requests; atomic per season, failed season retained without blocking the other. Scheduler OFF. |
| football-data/vaastav history | Keep existing historical consumers; score consolidation needs a separate change. |

The 600-player/3000-PFS probe uses 17 queryForList calls for both 1 and 20 saved players
(279/240ms in this run). Full-pool player evidence is still read: narrowing shortlist reads is a
possible follow-up, not a claimed collection saving. No net FPL HTTP reduction or collector
shutdown is claimed. Provider-use permission/reliability, approved initial cache plus fresh
FPL price/schedule captures, exact-head CI/review and publication remain outside local acceptance.
See [STATUS](../../STATUS.md) for the receipt context; browser artifacts `/tmp/understat-app-shell/`.
