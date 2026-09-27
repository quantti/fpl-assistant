# KAR-70 — Persistent egress monitoring and sync restart gate

**PROGRESS:** Design complete — provider capability is blocked pending Supabase confirmation; no runtime
implementation, production monitoring, alerting or sync activation has been performed.

Ticket: [KAR-70](https://linear.app/quantti/issue/KAR-70/add-persistent-egress-monitoring-and-alerts-before-sync-restart)

## Context and outcome

The 2026-09-26 attended STAGING observation measured 15.16 MB of PostgreSQL TCP payload before
its independent, conservative 20.81 MB whole-machine-RX stop. It was a short cold-start sample,
not a Supabase bill, steady-state forecast or production monitor. The verified safe state remains
OFF epoch 65. See [`OWNED-SYNC-EGRESS-REPORT.md`](../../OWNED-SYNC-EGRESS-REPORT.md).

Build a separate, explicit egress-monitoring runtime which runs while `OWNED_SYNC_MODE=OFF` and
which can prevent the owned runtime from starting or continuing without usable required metering.
It must be able to show the owner current rate, daily and billing-window evidence, coverage gaps,
provider evidence and an immutable stop/recovery trail. It must not claim to measure Tapas, another
Fly machine, or the whole Supabase organisation from this application process.

This is a restart gate alongside the checkpoint/dependency-body corrections. It does not authorise
STAGING or ACTIVE, alter Tapas, publish core data, change FPL request cadence, or promise that any
measured rate fits a monthly allowance. There is no end-user UI in this ticket: the authenticated
operations response is the owner-visible surface.

**Assumptions:** Fly currently has one application machine, but all local readings stay
machine-scoped; Java 25 FFM is used only after its runtime self-test passes; and no threshold,
provider allowance or billing boundary is inferred from the 26 September experiment. Those latter
values remain owner decisions, not configuration defaults.

## Measurement contract

The monitor exposes these facts separately; no formatter may combine them into one “egress” number.

| Metric | Source and scope | It is **not** |
|---|---|---|
| `APPLICATION_DB_TCP_RX` | Linux `SOCK_DIAG` / `TCP_INFO.bytes_received` for configured PostgreSQL endpoints on this Fly machine. It includes all matching application DB connections, including the monitor's small writes. | Exact Supabase billing, whole organisation/project use, or sync-only traffic. |
| `APPLICATION_DB_TCP_TX` | `TCP_INFO.bytes_sent`, only if the same runtime self-test proves the kernel layout and counter. | Stored-payload size or a provider ingress bill. |
| `OWNED_SYNC_FPL_RESPONSE_BODY` | Bytes read by `OwnedSyncHttpClient` for the fixed FPL endpoints. Report the decoded response-body semantics and whether an incomplete/oversize response prevents an exact value. | All FPL wire bytes, DB traffic, or arbitrary application HTTP. |
| `OWNED_SYNC_STAGED_PAYLOAD_BODY` | `byte[].length` at successful owned-payload staging. | TCP ingress, physical table size, or a scan of `fpl_sync_payload`. |
| `MACHINE_RX` | Kernel interface received counter for the Fly VM. | Database traffic, FPL traffic, or a Supabase bill. |
| `PROVIDER_USAGE` | A supported Supabase management/billing response, with its declared scope, period and reported-at time. | A per-machine/sync attribution. |

The socket sampler must match the configured PostgreSQL host/address set and ports, not merely
assume that any connection on 5432/6543 is Supabase. If address resolution or a required kernel
counter is unavailable, the relevant metric is `UNKNOWN`; it is never emitted as zero.

The temporary probe already proved the required receive counter on the deployed Fly image: its
loopback self-test observed exactly 524,288 bytes and the attended run used the same read-only
`SOCK_DIAG` mechanism. The production implementation keeps that mechanism in-process using Java
25 FFM, with no helper process, packet capture, SQL inspection, `pg_stat_statements` polling,
payload-body read, SQL parameter collection, credential collection or session `SET`. The image
gets `--enable-native-access=ALL-UNNAMED`; the monitor makes no native call unless explicitly
enabled.

`bytes_sent` is optional evidence, not an assumed struct offset: the implementation must prove it
with a bidirectional loopback self-test before reporting it. Failure leaves DB ingress unknown and
must not disable the required RX measurement or invent a TX value.

## Decisions required before the hard gate

Do not choose a production threshold from the 20 MB experiment. Before Task 3, the owner must
record one policy version containing:

1. The local application/sync share of the shared Supabase allowance, warning and critical limits
   for sustained DB-RX rate, UTC-day use and the provider billing window; recovery hysteresis and
   alert cooldown; and whether a warning merely alerts or a critical condition trips the runtime.
2. The allowed reporting delay and the allowance represented by a fresh, organisation-wide,
   supported provider reading. That reading is mandatory for STAGING/ACTIVE: no dashboard
   screenshot, local meter or manual/operator record substitutes for it.
3. The exact billing-period boundaries. They must come from a verified provider response or an
   explicit owner-approved operational record; no code may infer a 25th-of-the-month boundary from
   the earlier dashboard screenshot.
4. The dedicated Uptime Kuma monitor, mail recipient, polling/retry interval and its notification
   cooldown, plus who may acknowledge a stop. The existing sync-health monitor remains separate
   and paused while containment is OFF.

As of this design research, the public Management API reference documents project API/request-count
usage endpoints (fine-grained permission `analytics_usage_read`) and organisation metadata, but no
documented egress-usage or billing-period endpoint. Supabase Studio's public source does reveal
Dashboard routes at `GET /platform/organizations/{slug}/usage`,
`/usage/daily` and `/billing/subscription`: the first has organisation aggregate `EGRESS`,
`usage`, `pricing_free_units` and `usage_original`; the daily route has egress component data; and
the subscription route has `current_period_start`/`current_period_end`. Studio calls them with its
Bearer account access token, and describes the usage data as refreshing up to hourly. These routes
are absent from the published Management API OpenAPI and scoped-PAT permission table, so they are
an unsupported Dashboard contract, not an integration source. The documented Prometheus Metrics
API is project-scoped database telemetry and supplies neither organisation billing usage nor quota.

Task 0 must obtain Supabase's written confirmation of a supported endpoint, its stable contract,
scoped read-only PAT permission, scope, cadence and billing-boundary semantics before making a
read-only probe. It must not reuse the database service-role key or scrape/use the Dashboard route.
Without that evidence, status reports `PROVIDER_BLOCKED`, local numbers remain clearly labelled
partial, and STAGING/ACTIVE admission is refused.

## Durable model and lifecycle

Create an isolated `sync.egress` package and `EgressSchema`; do not make `OwnedSyncSchema`
initialise the old owned-sync queue merely because monitoring is enabled. All tables are
`fpl_`-prefixed, get RLS enabled and have `_test` equivalents:

- `fpl_sync_egress_meter` — one leased sampler state per Fly-machine/boot identity: lifecycle,
  last successful in-memory and durable sample, bounded failure and coverage state.
- `fpl_sync_egress_socket` — only the current high-water RX/TX counters keyed by machine boot,
  socket cookie and counter epoch. It stores no packet, SQL, endpoint text or payload.
- `fpl_sync_egress_usage` — bounded mutable UTC-day and declared-billing-window rollups per metric,
  scope and coverage state. `known_bytes` is a lower bound when coverage is partial; a missing total
  stays null rather than becoming zero.
- `fpl_sync_egress_provider_snapshot` — normalized provider scope, period, usage/allowance,
  reported/read instants, delay/status and bounded error; never a raw management response.
- `fpl_sync_egress_incident` — one durable occurrence per alert key, its severity, hold/recovery
  evidence and no delivery claim (Kuma owns mail delivery).
- `fpl_sync_egress_stop` — append-only critical-stop and operator-acknowledgement evidence,
  including the policy version and fenced runtime epoch.

The sampler collects in memory every second and has one owner per machine/boot through a short
fenced database lease. It writes a compact checkpoint/rollup batch every 30 seconds, immediately
on a lifecycle transition, critical condition and clean shutdown. It stores no per-second history.
This keeps writes bounded while the one-second loop can still trip a runaway promptly. Current rate
is calculated only from a contiguous current in-memory window; it is `UNKNOWN` after a restart,
gap or stale sample until enough new samples exist. FPL/staged-body counters use the same bounded
flush discipline; an unflushed process loss records a coverage gap rather than reconstructing usage
by scanning retained payloads.

Counter accounting is conservative:

1. A known socket whose raw counter advances contributes only its non-negative delta once.
2. A newly observed socket establishes a baseline. Bytes whose timing predates its first sample are
   recorded as a coverage gap/lower-bound fact, not silently assigned to a day or window.
3. A socket absent from the next snapshot, a lower raw counter, a lost sampler lease, a VM boot-id
   change, a sampler crash or an overlong sample interval closes the affected coverage with an
   explicit gap. It never subtracts, reuses a cookie blindly or treats the unknown tail as zero.
4. On process replacement, the successor resumes from the durable high-water state if the socket is
   still present. If it is not, it records the closure gap; it does not replay the predecessor's
   cumulative counter.
5. A period is `COMPLETE` only when every allocated interval is known. A partial period exposes its
   lower bound and gaps; a wholly unavailable period exposes no numeric total. Boundary-crossing
   intervals are not guessed across UTC days or a provider window.

Apply the same boot/baseline/reset/gap rules to `MACHINE_RX`; it is a separate monotonic source,
not a fallback for a missing DB counter.

Keep only active socket checkpoints plus closed checkpoints for seven days, and compact daily,
billing-window, provider, incident and stop history for 400 days. A scheduled bounded cleanup uses
indexed time predicates. Status reads at most the current and most recent 31 daily rows, 13 billing
windows, 13 provider snapshots and 20 incidents. It must never query `fpl_sync_payload`, scan large
owned tables or return raw socket identities.

`EgressMonitoringConfiguration` is separately opt-in. With its property false, OFF still creates
no egress schema, native sampler, egress-specific pool/endpoint or outbound provider call. With it
true, its small monitoring pool and egress monitor run even when the owned runtime is OFF. The generic application
health remains independent: an egress failure must not make Fly restart-loop. A dedicated egress
health signal is instead DOWN.

## Owner surfaces, provider reconciliation and alerts

Add exact, default-closed routes:

- `GET /ops/sync/egress/health` accepts a new dedicated monitor token and returns only
  `{"status":"UP"}` or HTTP 503 with `{"status":"DOWN"}`. It is UP only while the local
  sampler is fresh, all required meters/provider evidence are usable, and no held egress incident
  is alertable. It never queries a large table or returns metric details.
- `GET /ops/sync/egress/status` and the acknowledgement operation below require the existing
  operator token. They return `Cache-Control: no-store`, timestamped metric scope/semantics,
  current rate or unknown, daily/window lower bounds and coverage, provider scope/delay/blocker,
  bounded incidents and any active stop. They expose neither socket identifiers nor secrets.

The dedicated Kuma monitor polls the new health URL with TLS, no redirects and its own token. It
is unpaused while `OWNED_SYNC_MODE=OFF`; it must not be tied to `/ops/sync/health`, whose OFF state
is intentionally DOWN. An alert opens a durable incident and holds the health signal DOWN for at
least one full Kuma poll/retry opportunity. It recovers only after the policy's evidence/hysteresis
rule succeeds. This gives Kuma one DOWN/recovery sequence per occurrence rather than an email per
second of a high rate. The application records `EXTERNAL`-style delegation only; it never claims
that Kuma sent or the mailbox received mail.

The provider poll is bounded and independent of sync activity. It stores normalized snapshots at
the provider-supported cadence (never more frequently than hourly until documentation proves a
shorter useful cadence), uses HTTPS/timeouts/backoff and has no retry loop tied to FPL work. The
status puts local and provider readings beside each other with their different scope and times; it
does not subtract one from the other or call their difference “Tapas traffic.” A delayed, denied,
unsupported or absent provider response is shown with the specific blocker and becomes an alertable
failure only when the approved policy declares it required.

## Critical budget stop

Introduce an `EgressAdmissionGate` shared by planner, worker and source-admission paths. A critical
local/provider threshold or unusable required meter must atomically close this gate before new FPL
or sync database work starts. It then:

1. persists the incident and `fpl_sync_egress_stop` when the database is reachable;
2. calls the normal fenced deactivation path, which marks the current owned season's active
   pending/leased work failed and advances durable mode to OFF;
3. cancels local scheduling/HTTP work without deleting receipts, evidence, verified publication or
   staged diagnostic data; and
4. keeps the gate closed in memory if the database is unavailable, retries only the durable fence,
   and reports DOWN. A restart finds either the durable stop or missing/freshness-gap evidence and
   also refuses work.

Move activation out of eager configuration-time side effects as needed so a configured STAGING or
ACTIVE process with an unacknowledged egress stop can serve the normal application and egress
monitor without activating the coordinator. It must visibly report `BLOCKED_BY_EGRESS_STOP`, not
silently reinterpret the requested mode as healthy OFF. No restart, recovered socket or recovered
Kuma endpoint auto-resumes work.

`POST /ops/sync/egress/recover` requires the operator token, a specific stop id and current durable
epoch. It may record an acknowledgement only when the durable runtime is OFF, no active work
remains, required telemetry is fresh, and the triggering critical condition has actually cleared
under the current approved policy. It appends evidence; it never deletes/edits the stop. It does
not start sync: the operator must make a separate deliberate STAGING/ACTIVE activation after the
acknowledgement. This stops only this application's owned sync; it neither diagnoses nor controls
Tapas or another shared consumer.

## Implementation sequence

Implement and review one unit at a time. Do not run units in parallel, run tests only against the
disposable database, and do not turn on STAGING/ACTIVE while developing.

### Task 0 — provider capability and policy record

Before code that relies on provider data, obtain Supabase's written confirmation of a supported
organisation-usage endpoint and a scoped read-only PAT permission, then use an owner-supplied token
to make read-only capability probes. Record the endpoint, required scope, response fields, exact
usage scope, period boundaries, reported-at delay and whether egress/allowance are actually
available. Do not commit a token, response body or dashboard screenshot, and do not rely on the
undocumented Dashboard routes identified above. If unsupported, record `PROVIDER_BLOCKED` in
`OWNED-SYNC-OPERATIONS.md`; it blocks STAGING/ACTIVE rather than opening a manual-evidence route.

Obtain the remaining owner policy decisions above in the same record. Tests later inject small
synthetic policy values; no production numerical default is added.

### Task 1 — socket measurement, durable accounting and bounded storage

**Create**

- `backend/src/main/java/fi/karivanttinen/fplassistant/sync/egress/LinuxSocketDiagnostics.java`
- `backend/src/main/java/fi/karivanttinen/fplassistant/sync/egress/EgressSampler.java`
- `backend/src/main/java/fi/karivanttinen/fplassistant/sync/egress/EgressStore.java`
- `backend/src/main/java/fi/karivanttinen/fplassistant/sync/egress/EgressSchema.java`
- `backend/src/main/java/fi/karivanttinen/fplassistant/sync/egress/EgressTables.java`
- focused pure/integration tests under the matching test package.

**Modify**

- `backend/src/main/resources/application.yml`
- `backend/src/test/resources/application-test.yml`
- `backend/src/main/java/fi/karivanttinen/fplassistant/sync/OwnedSyncMonitoringDatabase.java`
- `Dockerfile`

Port the temporary `SOCK_DIAG` reader as a narrow Java-FFM adapter behind a fakeable sampler
interface. Parse configured PostgreSQL endpoints, read boot/interface identity safely and run a
bidirectional loopback capability self-test before marking the meter ready. Keep Linux/native
details out of accounting. Implement the one-second loop, 30-second bounded persistence, lease,
rollup/gap lifecycle and retention described above. `EgressStore` is the only SQL boundary; it uses
small indexed rows and never touches payload bodies.

Tests must prove exact known deltas; connection closure; first-seen/unknown timing; counter reset;
boot/process replacement; stale sampler; lease loss; period-boundary gap; no negative/double count;
RLS; bounded status/cleanup reads; and disabled-default absence. The Linux loopback test proves the
adapter where the kernel permits it; the accounting suite uses a deterministic fake and must pass
without native access.

**Verification:** focused egress tests plus the existing monitoring-pool tests. Review this unit
before any provider, endpoint or runtime-stop work.

### Task 2 — source counters, owner status, provider state and Kuma signal

**Create** `EgressPolicy`, `EgressMonitor`, `EgressOperations` and a provider-source interface in
`sync.egress`, with controller tests and disposable-DB integration tests.

**Modify**

- `OwnedSyncHttpClient.java` and `OwnedSyncStore.java` to feed the two explicitly labelled
  owned-sync body counters without querying historical payloads;
- `SyncOperationsController.java`, `SyncOperationsConfiguration.java` and `SecurityConfig.java`
  for the exact egress routes/tokens;
- `application.yml` and the test profile for explicit enablement/table names;
- `OWNED-SYNC-OPERATIONS.md` with the separate monitor contract.

Implement normalized provider snapshots only after Task 0 proves a supported source. Otherwise the
provider implementation is an explicit blocked state, not an undocumented endpoint guess. Build
status JSON around nullable rate/total values and coverage states. Implement durable alert
occurrences, warning/critical/recovery hysteresis and the health hold needed for Kuma polling. The
new health endpoint must work while sync is OFF and make no database read per poll.

Tests cover known FPL/staged counters, absent/oversize response semantics, current-rate gaps,
daily and provider-window rollover, delayed provider values, provider denial/unsupported state,
warning/recovery transitions, duplicate suppression, token isolation, minimal health output and
no-store detailed status. No test calls Supabase or Kuma.

**Verification:** focused egress/status/security tests, then independent review. Do not unpause
or alter the existing sync-health monitor in this task.

### Task 3 — fenced stop, restart refusal and explicit recovery

**Modify**

- `OwnedSyncRuntimeConfiguration.java`
- `OwnedMatchdayRuntime.java`
- `OwnedMatchdayCoordinator.java`
- `PlannerRuntimeRepository.java`
- egress operations/configuration classes from Task 2.

Inject the admission gate before activation, every planner tick, worker lease/acquisition and source
request. Implement the in-memory-first/database-eventual fail-closed path and durable stop/ack
contract above. Reuse the existing normal deactivation fencing rather than inventing another queue
state. Ensure an in-flight stale worker cannot publish after a trip, and a restarted process cannot
activate solely because its environment still says STAGING/ACTIVE.

Tests cover critical rate/daily/window/provider and required-meter failures; DB loss during trip;
no post-trip FPL admission; normal OFF fencing; receipt/evidence preservation; concurrent
trip/worker race; restart refusal; stale recovery request; recovery while condition persists; and
acknowledgement followed by a separate explicit activation. Seed all owned and Tapas tables through
the disposable test conventions only.

**Verification:** focused runtime/coordinator/egress integration tests, then independent review.
A green implementation test is not authority to change production mode.

### Task 4 — OFF rollout and supervised acceptance

Deploy the reviewed monitor with `OWNED_SYNC_MODE=OFF`; only the explicitly enabled egress schema
and monitor may start. Confirm a fresh local sample, a supported fresh provider reading, protected
status response and the dedicated egress health endpoint before unpausing its new Kuma monitor. If
provider state is `PROVIDER_BLOCKED`, keep the new monitor paused and end the rollout in OFF.

While still OFF, prove actual mail delivery without changing sync behavior: temporarily configure a
wrong token on the **new egress Kuma monitor** for several poll/cooldown intervals, record one DOWN
inbox delivery, restore the correct token and record recovery delivery. Do not expose a public
test-fault route, log tokens or use the paused sync-health monitor for this proof.

Only after that evidence, a supported fresh provider reading and the owner-approved policy, run a
separately authorized, time- and budget-bounded STAGING observation with the independent rollback
supervisor still armed. Observe
backlog progress, current rate, all five local metric categories (with an explicit `UNKNOWN` for
an unsupported DB-TX counter), coverage status, provider delay, alert behavior and any automatic
stop. Whether it trips or reaches its bound, return to OFF and
verify durable epoch, zero active queue, publication receipts/evidence baseline and public health.

Repeat bounded observations for idle behaviour, the daily sweep and a real matchday before calling
sustained operation safe. Record facts and gaps in `OWNED-SYNC-EGRESS-REPORT.md`,
`OWNED-SYNC-OPERATIONS.md` and `docs/STATUS.md`. A spike or a single quiet run is never a monthly
cost guarantee.

## Completion gates

- A fresh, self-tested DB-RX meter runs and alerts while owned sync is OFF; missing telemetry is
  visibly unknown and makes the dedicated health signal DOWN.
- Owner status distinguishes application DB RX/TX, FPL response body, staged body, machine RX and
  provider readings, with timestamps, scope and coverage. It makes no whole-organisation claim.
- A supported, scoped, organisation-wide provider reading supplies egress, allowance, period and
  delay; its absence keeps STAGING/ACTIVE blocked.
- Actual egress Kuma DOWN, dedup/cooldown and recovery emails have reached the owner inbox.
- A critical policy breach or required-meter failure fences only this owned runtime, preserves
  publication evidence, survives restart and needs an explicit operator acknowledgement plus a
  new activation.
- Monitoring writes/storage and responses remain bounded; no payload/table scans, SQL text,
  credentials, packet contents or unauthenticated operational detail are exposed.
- The supervised OFF/STAGING/OFF evidence covers the stated cases. Sustained sync remains blocked
  until the independent checkpoint/dependency corrections and all operational gates also pass.
