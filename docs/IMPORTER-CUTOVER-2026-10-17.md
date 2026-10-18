# Importer cutover — attempts and verified activation, 17–18 October 2026

**PROGRESS:** Verified ACTIVE111 on reviewed image1d105c0. Run342125 completed667/667 page checks; GW1–6 FINALIZED,120/120 reader sides complete. Importer health and PlexKuma20 genuinely recovered UP at11:46 UTC on18 October. Future recurring/no-op/night-capture runs and email inbox receipt remain unverified.

All times UTC (Finland UTC+3; late events below fall on 18 October in Finland).

## 18 October — verified ACTIVE111 and monitor recovery

After explicit owner approval to activate and monitor, fresh SELECT-only checks at11:19 UTC
confirmed OFF110, no active jobs/unresolved permits, Java schema preflight READY, correct
playing season2026-27/id2, retained proof visibility and Tapas SELECT-only core permissions.
The rolling ledger was2,021/6,000; its fall was natural expiry, not a reset. Today's667 prices
and coherent38/380 schedule capture remained present. The exact deployed image was1d105c0
and the application still used one1024MiB Fly machine.

The token-authenticated OFF110 handoff returned ACTIVE111 at11:20:35. The next config
release enabled the importer and closed the handoff flag atomically; independent daily
price/schedule jobs stayed enabled, legacy DefCon/startup-price stayed disabled. No direct
SQL transition, old writer, budget increase or fabricated recovery was used.

Run342125 started11:21:12 and completed11:43:16 without an error. All667 player pages were
newly checked, costing677 admissions. Previously incomplete appearance snapshots were now
complete, including the nine zero/negative-BPS/omitted-stat cases. GW1–4/GW6 received valid
FINALIZED certificates; GW5's existing valid certificate remained intact. GW7 correctly
remains OPEN because that round is unfinished. The actual assistant reader accepted all
120 fixture sides in GW1–6 as complete. The merged Tapas projection was also executed
against the retained live proof, not inferred from physical row counts.

The next watchdog tick at11:46:18 recovered the five remaining incidents through real
finality proof. `/ops/sync/health` returned200/UP; PlexKuma20 recorded DOWN11:45:27 →
UP11:46:28, with another UP11:47:29. Public monitor19 stayed UP. Both monitors remain
active and linked to existing SMTP notification1. No alert row was manually changed.
At11:45 the rolling ledger was1,770/6,000, zero unresolved permits. One guest sample had
454,380KiB MemAvailable; this is not a continuous peak-memory measurement.

This proves the attended corrected cycle and real monitor recovery, not future hourly/no-op
behavior, the next02:00/02:10 UK captures, a production SLA or SMTP inbox delivery. Those
remain explicit follow-up observations; independent Kuma monitoring continues without an
agent process or a new server/subscription.

## 18 October — reviewed correction and guarded retry

- PR #167 (`43e88b6`) batches population and live participant/appearance receipts in the
  existing 500-row bound. The old code failed a 65-fixture latency regression with the
  same transaction timeout; focused integration checks passed after the fix. Original
  generation/source-time/fingerprint predicates and atomic rollback remain unchanged.
- Two `openai-codex/gpt-6.1-sol` reviewers returned CLEAN, zero findings. PR CI
  `39077632691` passed; authorised squash merge `47ec9e3` and master CI/deploy
  `39107199619` passed. The one 1024 MiB machine runs that exact image; public health UP.
- Fresh SELECT-only preflight confirmed OFF108, no PENDING/LEASED work or unresolved
  permits, installed Java schema preflight READY, and Tapas SELECT proof grants without
  core INSERT/UPDATE/DELETE/TRUNCATE. The actual assistant reader still exposed GW1–6's
  old VERIFIED rows. The rolling-day request ledger had 3,597 admissions at 07:22 UTC;
  it was not reset. All DB probes avoided session SET/read-only connection settings.
- Today's captures were missing because their overnight jobs had been OFF. Existing
  one-shot commands captured 667 prices at 07:46:51 and one coherent 38-event/380-fixture
  schedule at 08:04:09. They used the same VM, OFF/no-importer, loopback ephemeral web
  port and a 128 MiB heap. Non-web startup lacked a required security bean; the initial
  schedule deadline expired during Spring initialization, not during a demonstrated DB
  lock or OOM. A thread dump established startup progress; one bounded 300-second
  invocation completed. No extra Fly machine or new monitoring service was created.
- The authenticated handoff at 08:06:00 returned runtimeEpoch109 for expected OFF108.
  One config release enabled ACTIVE/importer plus independent daily price/schedule jobs,
  closing the handoff flag in that same release. Legacy DefCon/startup-price remain OFF;
  the importer cap remains 6,000, and old evidence/incidents remain intact.
- Run342123 acquired at 08:06:48. Its first real player-page read began 08:07:19, unlike
  failed run342122's zero pages. At 08:13:51, 120 page receipts and 120 VALID current
  pointers in each GW1–7 were present; the real assistant CTE exposed PROVISIONAL rows.
  The merged Tapas reader SQL (`aa4ce30`, with the live-checked table-existence facts)
  also accepted proved GW7 rows while withholding unproved physical values. This SQL
  check is not a Tapas UI test or impersonation of its DB role.
- Importer heartbeat and the first 08:11 watchdog tick are fresh. GW1–6 remain
  FINAL_PENDING while the sweep runs; GW7 remains OPEN. Kuma20 remains active/DOWN,
  correctly not claiming that this initial partial publication is a completed import.

## 18 October — first-cycle completion and remaining finality blocker

At 08:43:18 UTC run342123 completed without a run error: 667 page receipts, 4,550 VALID
PFS pointers, GW1–7 rows accepted by the real reader. The initial activation used 678
HTTP admissions; at 08:44 the rolling ledger was 4,263/6,000, no unresolved permit.
GW5 is FINALIZED; GW1–4 and GW6 each have all 667 required pages completed and zero
outstanding, but remain FINAL_PENDING. GW7 is correctly OPEN. Runtime is ACTIVE109,
with a new PENDING342124 due 09:43:18; no blind retry or ledger reset was issued.

A separate source-contract defect now explains the finality refusal. The live appearance
snapshots have incomplete fixtures 6/9 (GW1), 16/18 (GW2), 27 (GW3), 32 (GW4), 58/60
(GW6), plus 64 in still-open GW7. `FixtureVerification.fixtureEvidenceByFixture` treats
strictly positive fixture-stat values as the complete positive-minute player set, and maps
sides only from those fixture-stat entries. FPL does not guarantee that: the stored season
2026-27 pages show Schär with one minute/BPS -1 in fixture9, and Petrović with 90 minutes/
BPS0 missing entirely from fixture-stats in fixtures27/32. Other mismatches include negative
BPS players who did play and zero-BPS Meunier/Johnson omitted from fixture-stats. Thus
successful page ingestion cannot make that fixture-only roster assumption true.

These findings do not authorise removing completeness/side/minute checks or forging
FINALIZED/recovery. A bounded follow-up must preserve raw live membership and use separately
proved history/side evidence where necessary, with regressions and normal review/CI before
publication. Whether to leave the safely provisional importer running or stop it during
that correction is an explicit operational decision: recurring historical sweeps otherwise
consume the retained request allowance. No legacy writer is restored over these new rows.

## 18 October — pause and second correction deployed

The owner chose to pause during the appearance-proof correction. OFF/no-importer was
deployed before the authenticated retire of ACTIVE109 at 08:52:47, returning OFF110;
the handoff flag was closed again. SELECT/status verified no pending/leased work or
unresolved permits and retained publication proof. Independent daily price/schedule jobs
remain enabled; legacy DefCon/startup-price stay disabled. No old writer was restored.

PR168 at `733a6f3` corrects positive-minute/nonpositive-stat selection and adds one fenced,
physical/page-proof-backed history-side lookup, matched by season/fixture/GW/kickoff/clubs.
Its 89 focused tests and both independent gpt-6.1-sol reviews passed. PR CI39116604311
passed. On explicit merge/deploy approval it was squash-merged as `1d105c0` at 10:09:36;
master CI/deploy39118150432 passed. At 10:33:51 the single 1024 MiB machine's image named
that exact GH_SHA, public health was UP and persisted/configured mode remained OFF110.
The latest request did not perform another activation. Owned health correctly remains
503/DOWN; the correction still needs an attended guarded reactivation and actual finality/
monitor-recovery evidence before the full cutover can be called complete.

## Release and handoff

- Budget PR #165 merged becef01; master CI/deploy 39057123698 passed.
- Handoff first hit SecurityConfig denyAll: 401 with operator token, 403 even with real user
  JWT. No transition occurred. Browser login did not resolve this; the operator-route list
  was missing the new handoff/retire paths.
- Owner explicitly authorised follow-up PR, two gpt-6.1-sol reviewers, merge only after clean
  review/CI and continuation without another command. Narrow PR #166 merged f518b9e after
  two clean reviewers and green PR CI 39072601551. Master CI/deploy 39074164148 passed.
- Read-only preflight: persisted OFF106, no pending/leased owned runs, no unresolved HTTP
  permits. One Fly machine, 6832545c205238; public health UP.
- Reviewed operator POST handoff (expected OFF106) succeeded with runtimeEpoch 107 at
  22:39:26. No direct SQL transition. Config enabled ACTIVE + importer, request cap 6000,
  independent daily price/schedule captures; legacy DefCon/startup-price remained disabled.
  Handoff flag was disabled after use.

## Actual first-cycle result

- Importer-specific heartbeat appeared; producer INCREMENTAL_CORE run generation 342122.
- Initial source span began 22:40:01; compatibility catalogue receipt published 22:40:23.
  Later event/participant discovery receipts were also committed. This was real metadata/
  compatibility publication, **not successful player-fixture publication**.
- DB log showed socket read timeout on heartbeat's runtime-epoch FOR UPDATE at 22:41:09.
- Run failed at 22:42:19 with `Transaction timed out: deadline was Sat Oct 17 22:42:19 UTC 2026`.
  Runtime summary: `FAILED (0 pages requested, 0 committed, 0 failed)`.
- `fpl_player_fixture_current` remained empty; GW7 PFS count remained zero. New gameweek
  state records existed (GW1–6 FINAL_PENDING, later OPEN), not finalized certificates.
- Rolling HTTP count rose 4263 → 4272 (nine admissions), zero unresolved attempts. This
  failure was **not** daily budget exhaustion. No cap reset/increase beyond 6000 occurred.
- One resource sample showed 478060 kB available out of 985220 kB guest RAM. That is not
  continuous peak-memory/egress evidence; no OOM was observed in the retained failure trace.
- Likely next investigation: bounded discovery/participant transactions and runtime-row
  lock contention under cold full-league data. Exact blocking SQL/transaction cause has not
  been proven; do not simply increase timeouts or rerun an unbounded production cycle.

## Rollback verified

As the first cycle failed its acceptance gate, the approved rollback was executed:
stop importer via OFF/disabled config, restore the inert operator surface, POST retire for
ACTIVE107, which returned offEpoch108 at 22:44:32, then close the handoff surface again.
At 22:45:17 SELECT confirmed OFF108, no PENDING/LEASED jobs and no unresolved permits.
Public health returned 200/UP. Importer and legacy planner remain disabled; daily-price,
schedule, DefCon and startup-price fallback flags are false. No legacy writer was restarted.

Published metadata, receipts, physical PFS and ownership evidence were preserved. No truncate,
manual recovery, receipt deletion or role-grant change. Reader visibility after the failed
incremental discovery is not asserted merely from retained physical row counts; a subsequent
repair must inspect it through the real proof boundary.

## Kuma replacement requested by owner

The old paused owned-sync monitor ID18 had briefly been renamed while preparing the new
configuration. The owner explicitly asked to delete the old Kuma monitor, not source code.
An independent monitor was created first, then ID18 was deleted through Kuma's UI confirmation.

- ID18: absent (SELECT count=0).
- ID20: `FPL Assistant - incremental importer`, active, HTTPS `/ops/sync/health`, JSON query
  `status` expected `UP`, dedicated monitor header retained without disclosure.
- ID19: existing `FPL Assistant - public health`, active and unchanged.
- Both IDs19/20 linked to SMTP notification ID1. Importer interval60s, retry20s, retries1,
  timeout10s, no redirects/TLS bypass. ID20 correctly returned 503/DOWN; it remains active
  to disclose the unhealthy/off sync rather than being silenced or labelled UP.
- Kuma itself is on the existing Plex server; no VPS/new server/subscription was added.
  End-to-end email inbox reception remains owner-unconfirmed; earlier SMTP test was accepted.

No new importer repair/production retry is performed as part of deleting the old monitor.
The activation task is blocked on the first-cycle transaction timeout, not completed.
