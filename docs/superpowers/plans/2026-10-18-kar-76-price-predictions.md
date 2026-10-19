# KAR-76 — price strategy and official price forecasts

**PROGRESS:** Original delivery/RAG complete; importer correction merged as PR #172 and activated on 18 October with first forecast/history captures verified. Owner-approved restart-continuity correction passes 85 focused cases locally; publication, review and initial migration rearm remain. Overnight/live-chat verification is still separate.

## Scope and decisions

The owner wants useful price-change information and strategy, starting with the strategy documents. The official public `bootstrap-static` response inspected on 18 October 2026 included `price_change_percent`, `price_change_projections`, `price_change_calibrating`, `price_change_locked_until` and `price_change_hourly_rate`. Availability of these fields is observed, not a stable API contract. [Source evidence](../../PRICE-PREDICTION-SOURCE-EVIDENCE.md) records verified next-target/frontend mappings, source refresh timestamps, explicit season evidence and the public recent-change endpoint. Only offset zero is supported initially; hourly-rate units and later-offset semantics are not established.

Owner-approved polling: every 30 minutes normally; from 15:00 UTC every 10 minutes until the overnight price update, then every 30 minutes again. The overnight update is expected at 00:00 Europe/London. The owner explicitly considers perfect overnight-completion detection noncritical. Investigating a source signal is a bounded task, not a reason to delay the strategy work or design an elaborate overnight coordinator.

The following thresholds, fallback and component names are design proposals, not additional owner decisions. This document consolidates the KAR-76 comments and review; the 30/10-minute decision supersedes the earlier uniform 15-minute proposal.

## Existing boundaries to preserve

- `application.yml` schedules the legacy daily capture at 02:00 Europe/London. Its 06:00/12:00/18:00 checks only report missing data. These are repository settings, not a verification of live production execution.
- Keep `fpl_daily_price`, daily history, ranking freshness and existing daily writers unchanged. Frequent forecasts must not rewrite that day's canonical price/availability row.
- `FplBootstrap` is a separate my-team cache with a six-hour default and stale fallback. Do not use that cache or a user's `FplBudget` for forecast ingestion, or change my-team behavior for this feature.
- No new Tapas schema/core writes, season-row changes, owned-sync activation or changes to its cutover rules. Chat reads captured database evidence, never this external API.
- Names resolve through `PlayerNameSearch`. Every element ID and identity join is season-qualified. A configured/source season mismatch leaves the previous capture unmodified and unavailable as current-season evidence.
- Personal purchase and selling prices remain outside this feature; KAR-34 is separate.

## 1. Strategy content

Extend `backend/src/main/resources/docs/price-change-mechanics-and-team-value.md`, not a competing second reference. Cover official predictor interpretation; current progress versus projected progress; observed moves versus forecasts; purchase/current/selling prices and bank; monitoring an intended transfer; and points-first early-transfer decisions.

Correct daily-history wording and statements conflating market team value with realisable selling proceeds. A rise need not immediately increase selling proceeds, and a fall need not immediately reduce them: examples must carry the purchase price and rounding. Explain that avoiding a target's rise preserves purchasing power even when there is no immediately realisable profit on owned players.

Examples: an unaffordable target after a rise; an outgoing fall plus incoming rise; a fall that does not change selling proceeds; waiting for team news with sufficient headroom; and selling/rebuying on wildcard. Verify official rules and chip effects before writing. Do not invent the predictor's empirical accuracy or reproduce the feedback's undated events as verified history.

Make focused aligned edits to transfer, deadline/team-news, wildcard/free-hit and team-building/preseason references. Check the preseason price-change claim against the official rules. Live player signals never belong in static RAG. A strategy-only rollout explains how to use the official page without implying an unimplemented assistant tool already exists.

Sections must be independently useful when retrieved: keep relevant rounding, evidence conditions and short uncertainty wording beside each recommendation. Retain front matter attribution, with dates describing the revised content honestly. Re-ingestion is an explicitly authorised later operation; editing Markdown or restarting the application does not replace embeddings.

## 2. Polling and freshness

Use a ten-minute UTC scheduling grid. In the normal phase, only the :00 and :30 slots are due; from 15:00 UTC, all slots are due. Do not replay missed slots after restart. A timed-out/failed attempt is not a successful refresh. Retry 429/transient failures only within a bounded request policy, respecting Retry-After and avoiding a concurrent second attempt or a backlog burst. New scheduling and HTTP timeouts must fit the existing host resource limits.

Proposed overnight fallback: a later, future first source deadline in a source update at/after the previous target confirms forecast-cycle rollover and returns polling to 30 minutes. This does not prove price processing completed. Without this signal, leave frequent polling at 02:00 Europe/London and log that rollover was not confirmed. This is a scheduling fallback, not proof that prices changed. It preserves the normal daytime cadence, does not alter the canonical 02:00 writer and is independent of whether any player actually changed price. A delayed source can still be noticed by the subsequent 30-minute checks. The fallback time needs owner agreement.

Serving rules are independent of polling phase:

1. Last successfully validated fetch and source `price_change_last_updated` both at most 45 minutes old: usable forecast evidence, provided its season, player state and first source target are valid. Always print both times. Missing/invalid/future source update means forecast unavailable; current market price retains its separate fetch-age policy.
2. During the frequent phase, either age over 20 minutes additionally says the refresh is delayed. The source-attributed forecast remains visible up to the same 45-minute limit; do not silently call it fresh. Suppress this early warning until 15:10 UTC to allow the first 15:00 fetch to complete.
3. Age over 45 minutes: no actionable rise/drop recommendation or current-price claim from that capture. State that current forecast data is unavailable. Historical daily observations remain available with their own dates.
4. A passed forecast target is expired regardless of age. Never move yesterday's forecast to tonight just because it was retrieved again. Unknown target semantics mean forecast unavailable, not a guessed date. Current market price from an otherwise valid capture is distinct from an unavailable forecast.

Record fetch completion, source update time and payload fingerprint/change time separately. A successful validated identical response may update last-checked time without rewriting player rows; it does not renew source age or establish a new forecast cycle. Failed or malformed responses advance neither successful-fetch time nor publication. HTTP cache validators may save bandwidth only if verified; a 304 alone cannot renew the forecast target. The official page says the source updates every 15 minutes; the agreed client cadence remains 30/10 minutes.

## 3. Payload and player states

Reject a whole capture for structural corruption: empty/malformed player collection, duplicate season-scoped element IDs, unresolved season, broken required identity/team references or invalid required current prices. No hard-coded current player count: new players can legitimately appear. Parse and validate the entire response before any replacement. Store capture counts and make coverage visible; do not claim a syntactically valid payload proves upstream completeness.

Preserve every structurally valid player, with a separate forecast state. Proposed internal states: READY, CALIBRATING, LOCKED, UNAVAILABLE and UNSUPPORTED. These describe our evidence, not availability to play. A valid future lock takes precedence over calibration; an elapsed retained lock is ambiguous/unavailable. Only first projection with offset zero is supported. Codes 5/4 mean very likely/likely to rise, -5/-4 very likely/likely to drop, and observed integers -3 through 3 map to unlikely to change. A null/empty projection or unknown likelihood code must not become zero, a hold recommendation or an invented directional label. Treat malformed optional forecast data locally rather than suppressing all other players. Require the recognised official static-content URL season marker, consistent event dates and configured playing season to agree.

Keep current market price usable when only that player's forecast is unavailable. Remove players absent from a newer accepted capture rather than retaining them silently. List output reports usable/total forecast coverage plus returned count. Filter to usable verified directional forecasts; sort by documented official classification, then progress toward the requested threshold, with stable identity tie-breaking. Clamp requested list size to 1–20. Verify the numeric mappings before defining their sort order.

## 4. Bounded storage and publication

Proposed new tables: `fpl_price_forecast_capture` for the singleton published metadata/control record and `fpl_price_forecast_player` for its latest player set. Use isolated `_test` names in tests. Keep only the latest forecast player set, not repeated raw bootstrap bodies or permanent full-player polling history. The player rows carry season, element ID, matching names/club/position/current price, required forecast fields and a small validated projection array. Captured projections are external data, never instructions to the model.

The capture record carries published generation, season, successful-read time, source predictor update time, source target, payload fingerprint/change time, counts, and the minimal previous-cycle marker needed by the rollover signal. Keep no historical baseline unless that signal actually needs it. Add a separate latest recent-change snapshot/control and event table (`fpl_price_change_capture`, `fpl_price_change_event`): only the latest bounded feed, no indefinitely accumulated archive. Its capture time, season linkage, counts and earliest/latest event dates are independent of forecast freshness. Validate source event timestamps and prices, season-qualified identity and unique event keys; no inferred zero changes from a missing record. Fetch bootstrap then history sequentially under one worker's fencing token with independently atomic publications. History failure preserves the prior history and does not veto a valid forecast refresh. No source-level atomicity or guaranteed feed retention is claimed.

One bounded database-backed lease on the control record prevents overlapping forecast fetches across application instances. Reserve a monotonic fencing token in a short transaction, fetch/parse outside any database transaction, then publish only while that token/lease remains valid. An expired lease or older token cannot publish after a successor. No session-level SET, sleeping transaction or shared-connection lock through the Supabase transaction pooler.

A successful publication replaces the player set and metadata atomically in one short transaction. A reader loads one immutable view in a read-only repeatable-read transaction, never metadata from one generation and players from another. An identical validated fingerprint updates only the permitted metadata, not the full player set. Validation, write or connection failure leaves the prior published view intact; uncertain commits must be reconciled before claiming a successful refresh.

Original first-delivery mode matrix (ACTIVE coexistence is superseded by the activation amendment):

- Feature flag off, any owned-sync mode: no forecast HTTP requests/publication/tool registration.
- Explicit feature flag on, owned-sync OFF: forecast-only capture and tools permitted; existing daily paths unchanged.
- STAGING: no new forecast serving, fetching or production publication, regardless of the flag. Tests use disposable storage. Existing published evidence is not relabelled as a STAGING result.
- ACTIVE: first delivery leaves this separate scheduler/tools disabled with a clear configuration warning, rather than adding an unreviewed fetcher alongside the owned runtime. ACTIVE support is a separately reviewed integration using that runtime's request/ownership fences. Never switch modes to enable this feature.

Before enabling, verify the deployment's actual mode. This plan does not assert production is OFF. Mode/config changes during in-flight work require cancellation/fencing; capture publication rechecks permission. Disabling a process-local flag alone is not a cross-instance revocation mechanism: any operational live-disable path must revoke the durable lease/token and drain this worker. No new admin endpoint is required by the initial scope.

## 5. Tools and answer contracts

Proposed `PriceForecastTools` implements `FplTool` under the explicit feature/mode gate; automatic registration through the existing tool list remains the integration point.

- `getPlayerPriceForecast(playerName)`: captured current market price and, separately, the verified upcoming official price signal, progress/classification, target and evidence state. Name ambiguity uses the existing search discipline. Forecast unavailable does not imply unchanged price.
- `getPriceChangeCandidates(direction, limit)`: rise/drop candidates with source/read/target attribution, list coverage and truncation. No account parameter. Own-squad/watchlist filtering is deferred.
- Add a recent official-change lookup against the captured `stats/price-changes/` feed, with exact source `changed_at`, before/after prices, season and read time. Feed absence is not evidence of no change. History failure/age/unknown identity is explicit, never silently replaced with inferred nightly events.
- `getPriceHistory` remains the daily observed-history tool. It never proves which night a change occurred across a missing-day gap. Route recent exact-event questions to the new captured recent-change lookup, and longer observation history to this tool. Do not modify ranking sources to use forecast storage.

Update tool descriptions and the enrichment section in `system-prompt.st` together. Route current-price/forecast questions to the forecast capture when the tool exists; history questions distinguish official recent events from daily observations. If the new tool is absent/unavailable, quote a dated daily price only as the latest captured observation. Never silently combine old daily prices with a new forecast to calculate affordability. Treat personal selling proceeds as unknown without purchase/selling-price evidence; offer a conditional example or ask for the missing value.

Required responses include before-02:00 disagreement between the two captures; expired target despite a recent fetch; unavailable official forecast; known price with calibration; and a missing personal selling price. Keep official forecast wording useful and proportionate, not a generic refusal to predict and not a guaranteed event. Strategy explanations alone do not supply a live player's forecast.

## 6. Tests and operations

Use Clock-driven unit tests, a local stub HTTP server and seeded disposable database integration tests. No real API or production DB as a test oracle. Inspect existing daily-price, bootstrap, ingestion and prompt-contract tests; extend only relevant suites.

Required cases: 14:50/15:00/15:10 UTC transitions; normal 30-minute slots; UK-midnight/DST and 02:00 fallback; no-change/delayed source nights; 20/45-minute boundaries; same-payload and failure timestamps; rollover without relabelling; per-player unavailable versus structurally rejected payloads; unknown likelihood codes; disappearing players; season mismatch/reused IDs; lease expiry and stale publication; out-of-order completions; atomic readers; restart without catch-up; mode/disable during fetch; unchanged canonical daily/ranking data; and prompt/source routing including absent tools.

Source-inspection additions: source age versus fetch age under stale cache responses, missing/future source timestamps, exact first-target expiry, offset-zero-only policy, lock/calibration precedence, independent recent-change failure/publication, malformed/duplicate history events and absent history coverage/retention guarantees. Synthetic fixtures only.

For documents, validate front matter and section chunks with the existing ingestion test utilities. Check each financial example independently and retrieve the key sections after the explicitly authorised corpus update. Verify superseded contradictory chunks are gone. Use the normal ingestion workflow; do not expand this task into redesigning the existing corpus replacement mechanism.

Expose bounded operational status: last attempt/success, capture age, usable-player count, current polling phase, consecutive failures and whether overnight completion was confirmed or a fallback was used. Log failures and missing data without dumping whole responses. An overnight fallback alone is not an outage alert. Review retry, storage and request budgets before activation.

## 7. Bounded delivery units

1. Write/review the agreed strategy changes, including aligned references and self-contained examples. No ingestion or promise of live tools yet.
2. Verify source field mappings, season evidence and a possible completion signal with a bounded inspection. Return the findings; fallback keeps overnight perfection out of the critical path.
3. Implement and verify the isolated parser/evidence states/freshness policy against fixtures. No production scheduler.
4. Implement and verify forecast-only schema, lease/fencing, immutable reads and atomic bounded publication on the disposable database.
5. Implement the approved 30/10-minute scheduler, retry/fallback policy and explicit feature/mode gate, initially disabled.
6. Implement tools and prompt routing with seeded integration/contract tests; inspect compatibility with existing daily and ranking tests.
7. Independent review, focused local validation and full PR CI. Owner-approved deployment, corpus ingestion/retrieval verification and forecast enablement are separate operations. Observe the first evening/morning and disable only this feature if needed; existing daily history remains intact.

Run one bounded agent/build/test unit at a time. No local full suite, commit, PR, deployment or activation is implied by this design document.

## Outcomes

### Task 1 — strategy documents (18 October 2026)

Updated the existing price/value reference and five aligned strategy documents. Official rules/FAQs and the 2026/27 predictor introduction were inspected; the client-rendered help content was checked in the official frontend bundle after browser accordion clicks timed out. Content distinguishes threshold progress from probability, dated forecasts from observed daily prices, market value from personal selling proceeds and bank, price/news trade-offs, and permanent Wildcard versus previous-deadline Free Hit restoration. The preseason demand-driven price-change claim is corrected. Revised references are dated 18 October 2026 and do not advertise a working forecast tool.

Added `PriceStrategyDocumentTest`: six reference/chunk checks and seven content checks, including integer-tenths verification of all six selling-price table examples. All 13 cases failed against the old content, then passed. Final focused run of `PriceStrategyDocumentTest` and the existing `IngestionServiceTest`: 30 tests passed in total. Ran sequentially in a bounded WSL scope with the documented memory stop threshold; no database, embedding provider or live FPL calls are used by these tests. One independent read-only review found no blockers; final wording refinements were included in the passing rerun.

Production corpus replacement and real retrieval behavior were not exercised. No application runtime, sync configuration or forecast tool changed. Next unit is the bounded source-mapping/season-evidence inspection in Task 2, including a best-effort overnight signal and the newly noticed Recent Price Changes view on the official page; no history endpoint or completion semantics are assumed from that view alone.

### Task 2 — public source verification (18 October 2026)

Inspected the official frontend bundle and public bootstrap/recent-change responses. The [source evidence](../../PRICE-PREDICTION-SOURCE-EVIDENCE.md) records hashes, shapes, code-to-label mappings, first projection/deadline pairing and limitations. Bootstrap supplies explicit upcoming deadlines, a predictor refresh time and a season marker. Its cache policy can serve old data, so source age now constrains forecast usability independently of read age. First delivery supports the verified next deadline only; no hourly-rate interpretation or multi-night forecast.

The official recent-change endpoint supplies dated before/after price events, allowing recent actual-change answers without guessing from daily gaps. Its captured coverage is finite and not guaranteed; the plan adds an independent bounded snapshot and tool route without changing canonical daily rows. Forecast-cycle rollover is a modest polling signal, not a completed-price-run claim. No overnight experiment, runtime implementation, production write or activation was performed. Next bounded unit implements parser/evidence policies using synthetic tests.

### Task 3 — isolated parser and evidence policy (18 October 2026)

Added `PricePredictionParser`, immutable `PricePredictionSnapshot` records and pure
`PricePredictionEvidence` assessments in the price package. They have no Spring beans,
HTTP clients, database access, scheduler registration or effect on existing price paths.
Required player/club/price/season corruption rejects a whole bootstrap, while optional
forecast failures remain local to the player. The source URL season marker and ordered
38-event calendar must agree with the configured season. Snapshots never retain removed
players from a previous generation.

Only the verified first offset-zero forecast is supported. The parser preserves signed
progress beyond ±100, maps all observed likelihood codes, and handles lock/calibration
precedence without converting unknown values into unchanged-price advice. Price usability,
source/read age and exact target expiry are separate. The 20/45-minute proposed thresholds
are encoded in the isolated evidence policy, not activated in production; first-evening
warning grace does not accidentally suppress the warning after midnight. A pure rollover
check uses a later future target and a fresh post-target source update, without claiming
actual prices changed.

Recent-event parsing uses a validated bootstrap read no more than ten minutes earlier,
requires known season-scoped identities and bounded valid event rows, and preserves exact
timestamps and before/after prices. A historical club may differ from the current one.
Empty feeds create no synthetic unchanged events. History parsing failure cannot mutate
the immutable forecast snapshot. Future storage/publication must preserve that independence.

Added 89 parser cases and 19 evidence cases using synthetic JSON only. All 108 new cases
passed initially. An independent read-only review found no blockers. The final bounded
run also included `PriceStrategyDocumentTest` and `IngestionServiceTest`: all 138 cases
passed, with no skipped/failing cases, database/API calls or Spring application context.
The full suite remains CI's responsibility. Next bounded unit is isolated storage and
fenced atomic publication on the disposable test database; no production schema change
or activation is implied by this checkpoint.

### Task 4 — isolated storage (18 October 2026)

Added plain schema/store classes for four new allowlisted `fpl_` tables and isolated test
names, with no Spring startup wiring. A database-time lease, monotonic fencing token,
durable disable/revoke and final permission/lease checks protect independent forecast and
recent-event publications. Replacement, metadata and row sets are atomic; reads return
immutable repeatable-read views. Only latest sets are retained, including denormalized
recent-event identity. Identical fingerprints change checked time, never source time or
target. Checked time remains separate from original payload-read time for later tool
freshness decisions.

Nineteen disposable-loopback integration cases and three storage unit cases passed, plus
the 108 parser/evidence cases (130 total in that run). Independent read-only review found
no blockers. Parent inspection then corrected the integration-test guard to also allow
the existing GitHub Actions disposable service on port 5432, only under GITHUB_ACTIONS;
the local path remains loopback port 55432. The focused final integration run will cover
that guard's local path. Uncertain commit handling and mode gating belong to the next
worker unit. No production DB operation or automatic schema creation was performed.

### Task 5 — gated scheduled capture (18 October 2026)

Added the 30/10-minute UTC-slot cadence, source-cycle rollover and 02:00 UK fallback,
bounded sequential bootstrap/recent-event requests, Retry-After cooldowns, fenced
publication, uncertain-result reconciliation and operational status. The feature is
disabled by default and only registers under its explicit flag plus owned-sync OFF.
STAGING/ACTIVE never create these beans/tables or issue requests. Test profile wiring
uses only isolated table names and loopback sources. Startup never rearms durable storage;
the operations runbook requires separately approved arming and rollout.

Independent review found one lifecycle defect: an observed in-flight mode denial could
be forgotten if OFF was restored before the next tick. The permission check now latches
stopped, and the worker durably revokes after the publication transaction has returned,
always releasing its lease even if revocation fails. Three strengthened/new regression
cases failed before the fix. All 179 focused price-prediction cases then passed. The
first follow-up review could not start because provider authentication expired; a fresh
read-only review after recovery confirmed the finding resolved and no remaining blockers.
No production access, activation or change to daily-price/owned-sync behavior occurred.

### Task 6 — captured tools and routing (18 October 2026)

Added gated `PriceForecastTools` for a named player's price/forecast, directional candidate
lists and recent actual change events. All reads use stored evidence, with runtime and
durable enable checks before and after serving. Store callback readers keep the immutable
view and season/generation-qualified PlayerNameSearch SQL in one repeatable-read transaction.
History retains its own denormalized identity instead of joining the latest forecast season.
Outputs distinguish read/check/source/target times, market-price and forecast usability,
coverage/truncation and dated history versus absence of an event. Prompt routing and the
daily-history tool description were updated together, preserving dated fallback behavior,
no forecasts from RAG/daily trends and no invented personal selling proceeds.

Twenty-seven new tool/gate/prompt cases passed as part of a 90-case focused run that also
covered storage, evidence, configuration and existing prompt contracts. Independent read-only
review found no blockers. Final verification will combine all changed units and the affected
daily-history/strategy/ingestion contracts before publication to a feature-branch PR.

### Task 7 — final verification and publication (in progress)

The combined local focused run passed all 265 cases across 21 classes, with zero failures
or skips, in a bounded 1536 MiB scope with the host-memory stop threshold and 240-second
timeout. It includes every new parser/storage/runtime/tool unit, existing daily price
format/history/schedule checks, strategy/ingestion and all prompt contracts. No full suite
was run locally. Minor descriptive-variable naming cleanup was included in this run.
Next: commit to the existing feature branch, open the PR, independently review its complete
change and require green full PR CI before handing it back. Rollout/ingestion remain separate.

Published PR #170 at `1a3fef6`; independent full-change review found no blockers. GitHub
then reported a conflict with newer master, so merged master `4c1fa71` into this feature
branch, retaining both STATUS entries and all upstream prompt/config/runtime changes.
The same 265 focused cases passed on the merged tree. PR CI must run on the updated head;
the earlier conflicting head had no checks. No production configuration or rollout changed.

The merged-tree review found one P2 integration issue: the upstream disputed-player-facts
paragraph still unconditionally selected daily history for disputed prices. Aligned it with
the new forecast-first/daily-dated-fallback rule, retaining the other current-fact checks.
A new correction-block regression failed before the fix; the full 266-case focused rerun
passed afterwards, including the adjusted upstream player-name prompt contract. The merged
review otherwise confirmed upstream STATUS content and config/runtime code were preserved.

The routing correction at `a30c66a` passed independent re-review with no blockers; its PR
CI also passed. Master then advanced to `a949a20` (shortlist/direct Understat team xG),
causing another STATUS-only conflict. Integrated that master while retaining both branches'
entries, the forecast-first correction and both disabled-by-default collector configurations.
All 269 focused tests across 22 classes passed on this integrated tree with zero failures
or skips, including the new upstream team-xG prompt contract. Integration review and full
CI on the new PR head remain required; the old head's green CI is not reused as that proof.

### Activation amendment — incremental importer coexistence (18 October 2026)

PR #170 merged at `757bd13`; master test/deploy run `39153387820` passed. Owner-approved
production RAG replacement and actual StrategyTools retrieval were independently reviewed:
23 documents / 205 chunks, source hashes verified, health UP, no forecast activation.

The owner explicitly approved activation and the required compatibility correction, clarifying
that the old owned-sync runtime is retired and a different importer is in use. Production
still uses `fpl.owned-sync.mode=ACTIVE` plus `importer.enabled=true` to configure that new
importer; the namespace does not imply the retired runtime is running. This supersedes the
original OFF-only forecast restriction below, not importer ownership or publication fences.

Permit the existing OFF path or ACTIVE with importer enabled, cutover confirmed and identical
explicit forecast/importer playing seasons. Keep STAGING, legacy ACTIVE, invalid mode,
incomplete importer settings and mismatched seasons closed. Observed permission loss must
still latch stopped, durably revoke and require a fresh lifecycle and deliberate rearm.
Never change importer mode, epoch, budget or data to activate forecasts. The new collector
retains its isolated tables, bounded HTTP, cadence, evidence policy and durable arm gate.

Delivery: reproduce the production compatibility failure with configuration/tool/worker
regressions; implement the bounded gate correction and operations wording; run focused tests;
independently review; publish a correction PR and require full CI before merge/deploy.
Then verify deployment/runtime configuration, install only forecast storage via reviewed
application wiring, set the explicit season/flag and deliberately arm publication after
old workers drain. Verify the first stored forecast/history capture and actual tool outputs;
if evidence is unavailable, report it honestly. Activation authority does not permit weakening
source validation or freshness. The owner-approved overnight fallback remains 02:00 UK for
this rollout; cycle rollover is not proof of completed overnight price processing.

The configuration, tool-registration and in-flight permission-loss regressions failed before
the correction (3 failures / 42 cases). After the gate change, all 292 focused cases across
24 classes passed, including the existing source/evidence/storage/HTTP/cadence/strategy/
prompt/daily-price checks and importer runtime/request-limit tests. No local full suite.
The collector retains its existing bounded two-request-per-slot transport and separate
forecast lease/publication fences; it does not acquire core write ownership or alter the
incremental importer's admission ledger. Inspect aggregate traffic/headroom before arming;
the original legacy-runtime integration proposal is not silently applied to the new importer.

### Restart-continuity amendment — 19 October 2026

The owner rejected manual rearming on every routine deploy (up to ten a day). Normal shutdown
must stop/drain only the departing process while preserving the operator's durable enabled
choice. A fresh worker resumes at the next eligible slot, never replaying a missed slot or
turning an explicitly disabled capture back on. Explicit durable disable and observed invalid
configuration still revoke/fence publication and require deliberate rearming. This supersedes
the original shutdown-revokes/rearm-every-deploy rule above, not the initial disabled default.

The permission object now distinguishes a local lifecycle stop from latched configuration
denial. Shutdown stops permission before draining the tick and revokes only for the latter;
late HTTP results/publication callbacks are refused and lease release remains token-scoped.
Two regressions failed before the change. Five focused classes pass 85/85 with no failures,
errors or skips, including real-store restart after both enabled and explicit-disabled states.
No full local suite or production change. Deploying from an old binary requires one final
rearm after old workers drain; normal subsequent restarts preserve that choice.
