# Owned sync operations

Production cut over to the assistant-owned writer on 2026-09-16. Tapas runs through its restricted
`tapas_runtime` login with `FPL_CORE_WRITES_ENABLED=false`; do not restore a second writer without
the documented rollback fence. Deploy through the reviewed GitHub workflow, not manual Fly deploy.

Latest containment (2026-10-03): the [owner-authorized bounded retest](OWNED-SYNC-EGRESS-REPORT.md)
ran STAGING epoch 74 under an independent guard. The 20 MB observed DB-RX stop fired after
about 248 seconds (20.62 MB DB / 25.04 MB whole-machine RX); 48 histories completed, zero
fixtures STAGED. **OFF epoch 75**, zero active runs and unresolved request attempts, public
health UP, 706 published receipts, 6,523 evidence rows, 40 VERIFIED fixtures and unchanged
core/GW5 counts were verified after rollback. The guard and read-only socket observer were
dry-run before activation; owner-provided billing and Fly-console fallback were checked.
There was no ACTIVE mode, core publication, Tapas writer change or application deployment.
The locally recovered 28 September epoch-72 attempt had earlier stopped at 20.56 MB machine RX
with 43 histories and zero STAGED fixtures; older OFF epochs below are historical.

Earlier containment (2026-09-27): the owner-authorized STAGING epoch 66 retested deployed
`80dba18`, including KAR-71's metadata-first payload hydration. An attended ten-minute guard
stopped it at 15.21 MB observed DB TCP RX, about 66 seconds after the mode request, with 17.19 MB
whole-machine RX. These are not a Supabase billing total. Two catalogue/schedule jobs completed;
zero player histories completed and no fixture job staged. Normal rollback is verified **OFF
epoch 67**, zero active queue, application UP and publication fingerprint/evidence unchanged.
GW5 still has zero physical PFS rows and ten `VERIFYING` fixture receipts. Tapas settings and
Kuma were not changed. The earlier 26 September run on `bc5ced7` stopped at 20.81 MB machine RX,
with 15.16 MB observed DB RX, and returned to OFF epoch 65; that is historical evidence.

Do not restore sustained STAGING or ACTIVE yet: KAR-72/73/75's fixes made history progress,
but no fixture reached STAGED before the traffic cap. Neither bounded observation establishes
a monthly rate or GW5 readiness; do not extend the limit without a new cause and owner approval.
Tapas core writes remain disabled under the existing cutover. See
`docs/OWNED-SYNC-EGRESS-REPORT.md` for evidence and measurement limits. The 20 September ACTIVE
test and its OFF epoch 63 are historical, not the current mode. Reducing future traffic does not
undo the previous period's overage.

Before containment the current-season backlog held 40 `VERIFIED` fixtures. Resolve the Java PID by
identity and give each new activation its own bounded startup-heartbeat grace period.

## Safe defaults and required configuration

`OWNED_SYNC_MODE=OFF` creates no owned runtime, watchdog schedule, alert delivery executor, schema,
or outbound request. Operations and external health are also absent by default.

Before a reviewed cutover, select and test **one** notification route. The selected deployment
uses Uptime Kuma on `plex`, with its existing `Home network alert` SMTP notification.

### Uptime Kuma (selected)

Set `OWNED_SYNC_ALERTS_VIA_MONITOR=true`, `OWNED_SYNC_ALERT_WEBHOOK_ENABLED=false`,
`OWNED_SYNC_EXTERNAL_HEALTH_ENABLED=true` and a strong `OWNED_SYNC_MONITOR_TOKEN`. Enable operator
status separately with `OWNED_SYNC_OPS_ENABLED=true` and a different `OWNED_SYNC_OPERATOR_TOKEN`.
The monitor polls `https://fpl-assistant.fly.dev/ops/sync/health` using header
`X-FPL-Monitor-Token`, requires HTTP 200 and body `"status":"UP"`, checks TLS, and follows no
redirects. Poll every 60 seconds with two retries; enable DOWN and recovery mail through the
existing notification channel. Do not enable the GitHub heartbeat workflow as a second route.

In this explicitly delegated mode, health is DOWN while any incident lacks its own matching
occurrence's recovery, or a planner/watchdog heartbeat is missing/stale, or the database is
unavailable. OFF is not healthy. The outbox records `EXTERNAL`, never `DELIVERED`: the application
cannot attest Kuma's mail delivery. Notification retries belong to Kuma in this mode; the
application refuses webhook retries. Switching back to webhook additively redrives still-open external incidents.
An enabled authenticated health endpoint is required; selecting both notification routes fails
startup. A STAGING instance can intentionally be DOWN because it has not published/verified data.

Kuma monitor **18**, `FPL Assistant - owned sync`, was created paused on 2026-09-14 through its
authenticated API. Its SMTP transport test was accepted; inbox receipt is not independently proven.
Unpause it for the monitored rollout, not while the application is OFF. Kuma is independent of Fly,
not of its own home-server/network outage; no off-site availability SLA is claimed.

### Webhook and optional GitHub heartbeat (alternative)

For this alternative, leave `OWNED_SYNC_ALERTS_VIA_MONITOR=false` and configure:

- an owner-controlled HTTPS notification destination;
- `OWNED_SYNC_ALERT_WEBHOOK_ENABLED=true` and secret `OWNED_SYNC_ALERT_WEBHOOK_URL`;
- `OWNED_SYNC_OPS_ENABLED=true` and a high-entropy `OWNED_SYNC_OPERATOR_TOKEN`;
- `OWNED_SYNC_EXTERNAL_HEALTH_ENABLED=true` and a different high-entropy
  `OWNED_SYNC_MONITOR_TOKEN`;
- repository variable `FPL_SYNC_MONITOR_ENABLED=true`, plus GitHub secrets
  `FPL_SYNC_MONITOR_URL` (the `/ops/sync/health` URL), `FPL_SYNC_MONITOR_TOKEN`, and
  `FPL_SYNC_MONITOR_FAILURE_WEBHOOK`.

Keep all values in environment/repository secrets. Do not put them in source, command arguments,
URLs printed to logs, tickets, or screenshots. The application webhook accepts HTTPS only; HTTP
loopback exists only as a package-private test seam. No real webhook is called by the test suite.

The GitHub schedule runs every ten minutes only when the repository variable is exactly `true`.
GitHub scheduled workflows are best-effort and can be delayed or skipped; they are an independent
way to detect a stopped machine/dead in-process scheduler, not an availability SLA.

## Overnight OOM containment (2026-09-15)

The next STAGING run repeatedly restarted and did not complete player histories. Fly confirmed
an OOM kill; application health timed out. The replacement OFF process was verified healthy,
and the drained epoch-32 queue was fenced in a guarded metadata-only transaction to OFF epoch 33.
No owned receipts or player evidence had been published; Tapas remains the core writer.

The bounded memory correction and its evidence/limits are documented in
[OWNED-SYNC-MEMORY-REPORT.md](OWNED-SYNC-MEMORY-REPORT.md). Do not infer operational readiness from
the local allocation probe. The next STAGING run must be attended and time-bounded, with actual
history completion and stable memory checked, and an explicit stop/drain/epoch check afterward.
Do not leave it running overnight on the strength of an initial heartbeat or catalogue completion.

## Second STAGING attempt (2026-09-14 23:10–23:18 UTC)

PR #97 deployed with 1 GiB. The planner heartbeat advanced without the earlier pool/transaction
failure; sampled available memory was about 411 MiB. Source parsing worked, but queue dependencies
starved: 39 fixture verifications repeatedly waited for histories while 659 histories and both
catalogue/schedule jobs still had zero attempts. A history needs a canonical catalogue/schedule
pair; valid pairs belonging to fixture-verification runs deliberately do not substitute for it.

The bounded correction gives canonical prerequisites first admission. A fixture waiting for
histories persists a priority behind histories; an ordinary retry restores its kind's normal
priority. Thus ready verification still outranks ordinary history work, but a blocked dependent
cannot consume every worker turn. Lease/epoch validation, requested-source floors, retry dates,
adaptive backoff and the rolling request ceiling remain unchanged. A real-queue regression covers
39 waiting fixtures, 600 histories and both canonical jobs; the dispatcher regression exercises
its actual dependency retry path.

The instance was returned to OFF. Fly sent SIGINT at 23:18:47, SIGTERM at 23:18:50 and reported an
abrupt VM exit at 23:18:56. The replacement OFF instance started successfully, but the old durable
activation row remained STAGING epoch 3. The precise shutdown obstruction is unproven. After
confirming the old instance had been replaced and OFF was effective, a guarded maintenance
transaction fenced only epoch 3's 700 PENDING/LEASED rows and advanced the mode row to OFF epoch 4.
Payloads, receipts and evidence were retained. The next release allows a 60-second Fly shutdown
budget for the durable fence and bounded worker drain; verify both process drain and durable mode
again rather than treating the environment flag alone as proof.

Final evidence: 115 HTTP attempts; 114 VALID payloads and one UNCHECKED checkpoint; zero published
receipts and zero player-evidence rows. VALID parsing is not completed fixture verification. Tapas
credentials/core-write flags and all five core tables were untouched by the owned runtime.
Kuma 18 was resumed, recorded real HTTP-503 DOWN beats, and was paused again after rollback.
Mailbox delivery and an actual recovery remain unverified. Read-only privilege inspection also
found no public-table write grants to PUBLIC and no PUBLIC-executable SECURITY DEFINER routines;
no production role has been created or switched.

## STAGING attempt and rollback (2026-09-14 22:15–22:17 UTC)

The approved release consumed staged configuration and started STAGING. It failed before source
work could be queued: `PlannerInputRepository.observeTransitions` performed 380 separate round
trips inside the monitoring pool's five-second transaction. The two fetch workers also shared that
same two-connection pool with planner/watchdog/operations, causing acquisition failures.

The runtime was returned to OFF using an environment-only machine update. Confirmed afterward:
application health UP, owned health DOWN as expected, durable mode OFF at epoch 2; zero owned runs,
source payloads, request attempts, fixture observations, player evidence or published receipts.
Only owned schema/runtime metadata was created. Tapas remains the core writer; no role or credential
switch occurred. Kuma monitor 18 remains paused. The local rollout file describes desired STAGING
configuration, not the current production mode: do not import it again before the fix is deployed.

The bounded fix separates work (four connections, 10s query/socket and 30s transaction bounds) from
monitoring (two connections, original 5s bounds), batches fixture observations and persists large
daily/matchday player work in stable ranges of at most 32 players per transaction. Committed chunks
survive interruption; the failing chunk rolls back and resumes. Small slot ids and total request
estimates are preserved. No readiness, source-floor, lease or ranking bypass was added.

The Fly instance had only about 104 MiB available while OFF on its 512 MiB allocation. The reviewed
fix raises that allocation to 1 GiB before retrying STAGING. After deployment, require advancing
planner heartbeats and staged source work without pool/transaction failures before proceeding.
STAGING is still not permission to write core rows, and publication cutovers/readiness can keep the
owned health signal DOWN until the subsequent reconciliation completes.

The Tapas privilege split was rehearsed on disposable Postgres with the real core schema plus a
read-only, DDL-only export of its derived tables: 20/20 INSERT/UPDATE/DELETE/TRUNCATE probes on core
tables were refused, core SELECT counts matched the owner, all eleven derived tables had their
required DML and sequence privileges, and schema CREATE was refused. The probe role/grants were
rolled back. This is privilege evidence, not a claim that a production cron cycle has run under the
new credential. Its current credential is backed up in an ignored mode-0600 file for controlled
rollback, preserving the existing session-pooler port 5432. No new production login exists yet.

## Read-only preflight (2026-09-14)

Both merged deployments succeeded. The configured playing season is database id `2`, code
`2026-27`. No owned-sync schema existed at inspection, and no owned mode/monitor secrets were
installed. The current database login is `postgres` (BYPASSRLS and CREATEROLE, not superuser).
All inventoried Tapas core and derived tables have RLS enabled with no policies.

The least disruptive role split is to keep the assistant's existing connection and give Tapas a
new dedicated login: SELECT only on the five transferred core tables and season/evidence, named
writes only on its manager/league/chip/derived tables, no CREATE/role-management or inherited
postgres membership. This login needs BYPASSRLS alongside those restricted table grants; GRANT
alone would silently hide the rows under the existing RLS setup. PostgreSQL 17 permits a role
already holding BYPASSRLS to grant that attribute. Rehearse the complete privilege matrix in the
disposable database and verify denied core writes before switching the live Tapas credential.
Do not weaken RLS or change table ownership, and do not execute the partial role template blindly.
No production role, grants, credential, mode or core data has been changed by this preflight.

## Activation and rollback gates

The implementation PR does not satisfy these operational gates. Deploy OFF first, validate sources
in STAGING without core publication, and test the chosen notification path. Follow the separate
[Tapas cutover runbook](https://github.com/quantti/tapas-fpl-app/pull/1): block admission and drain
all old automated/manual/embedded writers before ACTIVE. Set the explicit playing season and
`OWNED_SYNC_CUTOVER_CONFIRMED=true` only after recording that evidence and reviewing distinct-role
permissions. The confirmation flag cannot inspect another application's processes.

On rollback, stop/drain the owned runtime before permitting Tapas writes. Revoke current owned
verification in a separately reviewed maintenance operation while retaining observations, last
verified generations and receipts; otherwise overwritten physical rows would inherit old source
proof. Keep readers fail-closed until reviewed migration/re-verification. Never delete evidence to
force a legacy-looking successful answer.

The 5000-attempt rolling-day ceiling can defer desired polling on a busy full-league day. Local
600-player/ten-fixture demand intentionally exercises that case; it is not a guarantee of all
cadences fitting the budget. Measure STAGING throughput/backlog and post-match verification priority
before activation, then record real finish detection, verification latency, requests and delivered
alerts/recovery on the first approved matchday. No production SLA is established by fake-clock tests.

## What the watchdog means

The watchdog runs every five minutes on an executor separate from the planner, fetch workers, and
notification delivery. It reads durable state and opens one outbox incident for:

- no successful planner heartbeat within ten minutes;
- pending work overdue by five minutes or an expired lease;
- catalogue/schedule source receipts missing for the UK day after 06:00;
- a fixture still unverified after kickoff +3h (critical escalation after +12h), including expected,
  received and missing participant counts, last source read, attempts, and next due time;
- due work blocked by persisted cooldown or the 5,000-request rolling-day budget.

An in-progress match is pending, not failed. The +3h wording is **verification overdue**. Failed
fetches and failed planner ticks never advance successful heartbeat/publication timestamps.
Fixture recovery is emitted only after `fpl_fixture_sync.state=VERIFIED` with a current verified
generation; a missing state row does not count as recovery. Other incidents recover only after a
fresh durable check proves their condition has cleared.

`fpl_sync_alert` is both incident ledger and bounded outbox. Disabled and unconfigured delivery are
stored explicitly. Webhook attempts use bounded payloads/timeouts, at most three attempts, and a
leased claim. A delivery error remains queryable as `FAILED` with its attempt count and bounded
error; it cannot block the watchdog executor. Successful watchdog and delivery-loop heartbeats are
persisted separately from the planner heartbeat; external health requires all three and also fails
on an exhausted notification that has no later delivered redrive.

## Inspection and bounded retry

The planner starts immediately, then waits one minute after each completed tick to reduce idle
metadata transfers. Newly due planning can therefore wait a minute plus tick duration; workers
still drain queued work every second. Source-read cadences, HTTP admission and independent
verification spacing are unchanged. Do not interpret this as an overall egress budget guarantee.

The full status endpoint requires `X-FPL-Operator-Token`. Its diagnostic lists are bounded to the
latest 50 failed runs, source/publication receipts, and failed alerts, and include the identifiers
used by the retry operations:

```sh
curl --fail --silent --show-error \
  -H "X-FPL-Operator-Token: $OWNED_SYNC_OPERATOR_TOKEN" \
  https://HOST/ops/sync/status
```

Compare these different facts rather than calling any one of them "freshness":

- `fpl_sync_receipt.source_read_finished_at`: source observation age;
- `fpl_sync_receipt.published_at`: successful publication age;
- `fpl_sync_run`: due/attempt/lease state and bounded failure;
- `fpl_fixture_sync`: waiting finish versus waiting/missing stats, participant counts, verification;
- `fpl_sync_request_attempt` / `fpl_sync_request_state`: rolling budget and cooldown;
- `fpl_sync_alert`: incident/recovery and notification delivery state.

A retry is additive: it creates a new pending generation and never resets/deletes the failed run.
It requires the failed generation and the activation epoch shown by status. OFF mode, a stale epoch,
a non-failed generation, or a conflicting active job is refused.

```sh
curl --fail --silent --show-error -X POST \
  -H "X-FPL-Operator-Token: $OWNED_SYNC_OPERATOR_TOKEN" \
  -H 'Content-Type: application/json' \
  --data '{"failedGeneration":123,"expectedRuntimeEpoch":7}' \
  https://HOST/ops/sync/retry
```

An exhausted notification can be redriven after the destination is repaired. This creates a new
`REDRIVE` outbox row; it does not reset or erase the failed row:

```sh
curl --fail --silent --show-error -X POST \
  -H "X-FPL-Operator-Token: $OWNED_SYNC_OPERATOR_TOKEN" \
  -H 'Content-Type: application/json' \
  --data '{"failedAlertId":456}' \
  https://HOST/ops/sync/alerts/retry
```

The external endpoint uses the distinct `X-FPL-Monitor-Token` and returns only `{"status":"UP"}`
or `{"status":"DOWN"}` (HTTP 503). It exposes no queue, season, fixture, error, or owner details.

There is intentionally no reset operation. Final release still requires owner destination selection,
secret installation, delivery verification, single-writer cutover evidence, and a reviewed runbook
update with the chosen monitoring service and escalation ownership.
