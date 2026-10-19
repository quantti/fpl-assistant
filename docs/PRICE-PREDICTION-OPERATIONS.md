# Price prediction capture operations (KAR-76)

This implementation defaults disabled. The owner approved activation and incremental-importer
coexistence on 18 October 2026; that approval is not proof of completed rollout. Gated forecast tools are implemented; this does not alter daily price captures,
invalidate bootstrap caches, modify ranking sources or change owned-sync configuration.

## Configuration and durable control

`PRICE_PREDICTION_ENABLED` maps to `fpl.price-prediction.enabled` and defaults to false.
An explicit true creates the configuration, schema, store, HTTP client and worker only
in one of two admitted configurations: `fpl.owned-sync.mode=OFF`, or `ACTIVE` with
`fpl.owned-sync.importer.enabled=true`, `fpl.owned-sync.cutover-confirmed=true` and identical
explicit forecast/importer playing seasons. The latter is the new incremental importer,
not the retired legacy runtime, despite reusing its configuration namespace. STAGING,
legacy ACTIVE, incomplete importer configuration, mismatched seasons and invalid modes
deny the gate. Never change importer mode, epoch or budget merely to enable this feature.
The required `PRICE_PREDICTION_PLAYING_SEASON` /
`fpl.price-prediction.playing-season` is a consecutive `YYYY-YY` season. Every bootstrap
must independently match its official season marker and event calendar. SeasonResolver
is not involved.

New storage starts durably disabled. After an approved rollout, an operator must explicitly
arm `PricePredictionStore.enablePublishing()` (or perform its equivalent single control-row
update through the approved operational database workflow). Neither startup nor any tick
arms storage. This second gate is deliberate: a restarted process cannot undo a durable
revoke. There is no new admin HTTP endpoint. Before arming, inspect actual mode, source
configuration, the four table names and the deployment's active instances.

Normal shutdown (`PricePredictionWorker.close()`) stops local permission first, shuts down
scheduling and drains the in-flight tick. Requests and publication callbacks reject that
worker's late results, and token-scoped lease release cannot revoke a successor's lease.
Shutdown does not change the durable enabled flag: a new process resumes on its next eligible
slot without manual rearming. It never enables storage that was explicitly disabled.

For a deliberate fleet-wide disable, call `PricePredictionStore.revokeAndDisable()` (or its
approved equivalent control-row update): it disables publication, increments the fence and
clears the lease. Then close local workers and set the feature flag false on every instance.
A database failure during revocation is a failed disable operation; keep local workers stopped,
retry durable revocation and verify the control row before declaring the fleet disabled.
A deployment that starts disabled does not touch storage or revoke another enabled instance.
Re-enabling after an explicit disable still requires deliberate durable arming.

When first deploying this shutdown correction, old binaries still revoke on exit. Drain all
old workers and rearm once after verifying the new fleet; later normal deploys need no rearm.
Rolling back to an old binary restores its shutdown revocation behavior.

A runtime permission change is detected on the next one-second tick and before each request
and publication. Unlike normal shutdown, an observed configuration denial latches stopped
and durably revokes publication; shutdown retries any previously failed revocation. Flipping
the flag back does not resume it: a fresh lifecycle and deliberate rearm are required.
Durable lease checks before/after HTTP and token checks at publication prevent revoked,
expired or superseded holders from publishing.

## Cadence, limits and evidence

A dedicated single-thread executor wakes once per second, with fixed delay and no overlapping
ticks. It admits only the first five seconds of a UTC ten-minute grid slot, once per process.
Normal polling uses :00/:30. From 15:00 UTC it uses every ten-minute slot, across midnight,
until stored previous/current target evidence confirms forecast-cycle rollover or 02:00
Europe/London is reached. London daylight-saving changes apply to the fallback; the grid
and evening transition remain UTC. The current startup slot and all missed slots are skipped.
A no-change night needs no player price change. Rollover confirms only a forecast cycle,
never overnight price-run completion. Expired first targets are preserved, never relabelled.
The 02:00 fallback remains subject to approval before rollout.

Each tick acquires one two-minute DB-time lease, makes at most one bootstrap GET and then
one history GET, sequentially, without a database transaction spanning HTTP. History needs
that tick's validated bootstrap identity. A bootstrap failure skips history; history failure
leaves a successful forecast publication intact. Forecast and history commits are independent.
Requests have a 20-second total cancellation deadline, response timeout and an 8 MiB decoded
body ceiling. Redirects, authentication, cookies and automatic retries are disabled. Source
URLs are fixed official public endpoints outside tests. No sleeping retry loops or raw-body
logging. Errors defer until a later eligible slot, with at least ten minutes cooldown;
Retry-After seconds and HTTP dates are accepted, bounded at six hours. There is no backlog.

Payload fingerprints are SHA-256 over captured response bytes. Identical validated payloads
advance checked time alone. Original fetched time, source predictor time and target remain
unchanged. Current-price freshness uses checked time; forecast freshness additionally checks
the original source timestamp and target. A stale cache response cannot renew predictor age.

An exception while publishing is uncertain, never a success. The next eligible attempt first
reads durable metadata and matches fingerprint plus checked time. It claims the earlier success
only when that read confirms it; otherwise it accepts the durable outcome and resumes capture.
If reconciliation cannot read storage, no HTTP proceeds and uncertainty remains visible.
Restart reads the durable view before requesting anything; it does not carry an in-memory
success claim across restart.

`PricePredictionWorker.status()` exposes bounded per-endpoint last attempt/success/error and
consecutive failures, phase (frequent/rollover confirmed/fallback), validated checked time,
original read and source time, usable count, cooldown and pending reconciliation. Times allow
capture age calculation without a second clock. Errors are fixed categories, never source
bodies. A fallback is not an outage or evidence of an actual price change.

## APIs for the tools unit and tests

`PriceForecastTools` applies `@PricePredictionEnabled`. It uses the shared
`PricePredictionPermission.permitted()` for runtime feature/mode/lifecycle permission;
the annotation is the startup gate only. Also require `PricePredictionStore.publishingEnabled()`
before serving stored evidence so that another instance's durable revoke is honoured.
Database failure must refuse serving, not bypass this check.
`PricePredictionConfiguration.Settings` supplies playingSeason, Tables and endpoint URIs.
Table names are fixed allowlisted defaults, not arbitrary external identifiers. The `test`
profile unconditionally selects all four `_test` tables and loopback HTTP URLs. Its optional
`fpl.price-prediction.test-base-url` must be localhost/127.0.0.1 HTTP with a trailing slash;
without it the closed default is `http://localhost:9/`. Production URL overrides are ignored.

Read `ForecastView.publication().checkedAt()` separately from `snapshot().fetchedAt()`.
Use `PricePredictionEvidence.assess(snapshot, checkedAt, elementId, asOf, frequentPhase)`;
the original overload remains for freshly parsed snapshots. The immutable view retains the
cycle marker needed by `PricePredictionCadence.phase`. Recent-history reads remain separate.

`readForecastView(Function)` and `readRecentView(Function)` run their callback inside the
same read-only REPEATABLE_READ transaction as metadata and immutable rows. Tools run the
season/generation-scoped `PlayerNameSearch` lookup there; recent history uses its own stored
identity, never current forecast players. The no-argument readers share the same reader body.
The serving permission and durable enabled flag are checked again after the read transaction
ends. `getPlayerPriceForecast`, `getPriceChangeCandidates` and `getRecentPriceChanges` make no
HTTP requests. Recent history can return dated old events with a stale-feed disclosure; absence
never establishes no change. Daily history, ranking and my-team price sources are unchanged.

Verification uses synthetic fixtures, pure clocks, fake worker HTTP, a loopback HTTP server,
ApplicationContextRunner and disposable-loopback PostgreSQL. It never requires real FPL or
production database access. Run focused PriceForecast and PricePrediction tests under the memory and timeout
limits in DEVELOPMENT.md; the full suite is CI's responsibility.
