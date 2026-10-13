# KAR-19 — daily capture email reminder

**PROGRESS:** Task 1/2 done — sender/check implementation and focused tests green.

## Contract

Send operational reminders to `kvanttinen@gmail.com` when the existing daily-price
freshness check finds a missed capture. Reuse Resend, already used by the project's
Supabase confirmation mail, through its HTTPS Emails API. No Supabase Auth changes.
Owner requested planning, review and implementation; production activation is separate.

Worktree: `/home/vanka/dev/fpl-assistant-kar19`, branch `feat/kar-19-freshness-email`,
baseline `610b3d9`. The original worktree contains unrelated uncommitted owned-sync work
and `.auth.env`; none of those files may enter this change.

## Design and review decisions

- Keep the existing 06:00/12:00/18:00 Europe/London cron and 02:00 cutover. One send
  attempt per unhealthy check, no immediate retries or new scheduler. The normal single
  Fly instance therefore attempts at most three reminders daily; this is not a durable
  cross-instance deduplication guarantee. Recovery is silence, not a recovery email.
- Old captures and an empty table both warrant email when explicitly enabled. Keep the
  existing empty-table warning. Future dates retain their warning, without claiming
  staleness. DB failures retain ERROR logging, not an ungrounded stale-data email.
- Email contains latest (or missing) and expected UK capture dates, plus diagnostic advice.
  It must not suggest bypassing owned-sync write guards or running a manual production sync.
- Add opt-in `DAILY_PRICE_ALERT_EMAIL_ENABLED` (false), `DAILY_PRICE_ALERT_EMAIL_TO`
  (owner address), `DAILY_PRICE_ALERT_EMAIL_FROM` (existing noreply domain), and a separate
  `DAILY_PRICE_ALERT_RESEND_API_KEY` (empty). No credentials or outbound sends in tests.
- Disabled or incomplete configuration never sends. Incomplete enabled configuration is
  an ERROR, not a startup failure. Keep application health independent of email delivery.
- Use the fixed `https://api.resend.com/emails` endpoint, Bearer auth and plain-text JSON.
  No redirects. Bounded connect/read timeouts; no retry loop. Provider/transport failures
  are logged without provider bodies, exception messages or secrets and do not escape
  the check. API acceptance is not inbox delivery.
- No new database tables, outbox, alert framework, dependencies, schedule alerts, owned-sync
  watchdog changes, production writes or provisioning. An in-process check cannot detect
  its own stopped app/scheduler or deliver when network/mail is down: external monitoring
  remains a separate concern.

Direct design review: checked current freshness predicate, empty/future/error paths,
scheduler gating, existing Resend/domain runbook and isolated HTTP conventions. Retain
`isFresh` unchanged because ranking uses its stricter predicate. Inject a test clock
without adding global clock configuration. Rejected a new durable outbox as unnecessary
for three reminders/day; document its delivery limitations instead.

Provider contract: https://resend.com/docs/api-reference/emails/send-email
(read 2026-10-13). Required fields: from, to, subject; send `text`, not HTML.

## Implementation slices and checks

1. Add the opt-in sender and connect it to the check. Deterministic mocked-JDBC tests
   prove stale/empty notifications, fresh/future silence, UK cutover and DB failure
   isolation. Loopback HTTP tests prove payload/auth, disabled/misconfigured silence,
   provider rejection/timeout handling and no redirects/retries. Record real assertion
   failures against no-op scaffolding before implementation; run only focused classes.
2. Document environment configuration and separate activation/delivery checks. Commit the
   green change, directly review its exact diff, fix findings in a new commit if needed,
   push the feature branch and open a PR against master. CI owns the full suite.

## Rollout gate

Owner supplies a separate domain-scoped sending key, configures Fly secrets and explicitly
enables mail after merge. Verify receipt in the destination inbox, not merely a Resend
HTTP success; then disable the test condition. No artificial stale rows in production.
Record production verification separately. This implementation does not attest delivery.

## Evidence

- RED: `./gradlew --console=plain test --tests '*DailyPriceFreshnessCheckTest'
  --tests '*DailyPriceEmailAlertTest'` on no-op sender and unwired check scaffolding:
  12 executed, 10 expected assertion failures (no sends/requests/logs), 2 passed.
- GREEN: same command plus `--tests '*DailyPriceFreshnessTest'`: 18 passed, 0 failed,
  0 skipped. Both runs used the checked-in Gradle limits and a 1536 MiB / two-CPU
  user-systemd scope. No database/container or external provider was contacted.
- Verification includes loopback request body/auth, missing configuration, HTTP
  401/429/500, redirect refusal, bounded timeout and successful later attempt;
  mocked-JDBC stale/empty/fresh/future/DB-error decisions and repeat/recovery behavior.
- Full suite belongs to PR CI; production mail configuration and inbox delivery are unverified.
