# Importer appearance proof for zero/negative fixture stats

**PROGRESS:** Implementation and 89 focused tests verified; independent review/CI/rollout pending. Importer safely OFF110 by owner decision.

## Observed failure and boundary

PR #167 fixed receipt transaction latency. Its reviewed deployment `47ec9e3` completed
run342123 on 18 October: all 667 player pages, 4,550 VALID PFS pointers, GW5 FINALIZED.
GW1–4/GW6 still refused finality despite zero outstanding pages. Stored source receipts and
player histories show legitimate positive-minute players with BPS<=0, including a 90-minute
keeper absent entirely from fixture-stats. Fixture-stats is not a complete appearance roster.
The owner requested the follow-up fix and a safe importer pause; authenticated retirement
ACTIVE109→OFF110 preserved all publication proof. Independent daily captures remain enabled.

Scope: `FixtureVerification`'s incremental appearance selection, one fenced bulk store read,
the collector caller, focused tests and status. No schema, receipt-key format, legacy writer,
request cap, timeout, external API, cadence or automatic activation change.

## Smallest proof-preserving correction

1. Keep positive-minute LIVE membership authoritative for appearances. Retain explicit LIVE
   coverage of fixture-stat identities and require every positive fixture-stat participant to
   appear in LIVE, but do not require every played player to have a positive fixture-stat value.
   Keep nonempty fixture stats and HOME/AWAY source/appearance coverage requirements.
2. For a LIVE player absent from fixture-stat side mapping, allow only an existing incremental
   history-side proof. Read these once per cycle under the core lease, using
   `IncrementalPfsEvidenceSql.currentRowProof`; match season, fixture, gameweek, kickoff,
   home/away clubs, opponent and `was_home`. Do not use raw legacy rows, current `player.team`,
   bootstrap club guesses, withdrawn/tampered rows or a different fixture/season.
3. Existing fresh-page and exact minute/side checks remain the final authority before a
   certificate. Previously proved history only supplies a candidate stable fixture side;
   contradictory fresh history/source evidence still prevents finality. Direct fixture-stat
   side evidence is never overwritten by a history fallback.
4. Keep missing sides unknown. A genuinely cold omitted-stat player can require an additional
   cycle after its first valid page publishes; this bounded extra cold pass avoids inventing
   evidence or expanding the receipt protocol. Current production already has those pages.

## Verification and release

- Reproduce positive-minute/BPS0 and negative-BPS refusals before the production fix.
- Reproduce a positive LIVE player omitted entirely from fixture stats; prove its side only
  after valid incremental page/current-row evidence, and prove eventual finality.
- Reject legacy-only, withdrawn/tampered, wrong season/club/header and contradictory-side
  fallbacks. Keep missing LIVE/one-sided/zero-minute and correction/invalidation tests passing.
- Assert one bulk history-side query, not per-player SQL. Run only focused tests locally against
  the disposable database, under the checked-in Gradle limits; full suites run in GitHub.
- Commit, push a dedicated branch and open a master PR. Two requested independent gpt-6.1-sol
  source reviewers, green PR CI and normal merge/deploy precede a fresh guarded handoff.
  Do not erase receipts/incidents, mark FINALIZED/UP manually, or restore legacy writes.
