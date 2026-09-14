# Owned sync operations (pre-cutover)

This is an implementation reference, not authorization to activate the owned writer. No production
mode, webhook destination, monitor, repository variable, URL, or secret is configured by this change.
Do not use a manual Fly deploy.

## Safe defaults and required configuration

`OWNED_SYNC_MODE=OFF` creates no owned runtime, watchdog schedule, alert delivery executor, schema,
or outbound request. Operations and external health are also absent by default.

Before a reviewed cutover, the owner must separately select and test:

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
