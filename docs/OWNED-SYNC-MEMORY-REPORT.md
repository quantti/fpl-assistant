# Owned-sync memory incident — 2026-09-15

## Epoch 42 after PR #103: initial work drained, verification not reached

The owner attended the epoch-42 run from 15:36 to 16:20 UTC; epoch 40 had stopped after three
minutes on one transport error. The observer was the one described below, with three changes:

- one transport-error retry;
- an attach mode, used when the owner-authorised extension handed the run to a new observer at
  15:50 without re-requesting STAGING;
- the five-minute no-progress stop now applies after histories start as well.

The rest of the procedure was unchanged: memory, heartbeat and restart stops, identity-resolved
PID and a durable OFF check.

| Measure | Result |
|---|---|
| Samples / process | 44, one Java process identity throughout |
| Sampled RSS peak | 545.5 MiB (stop threshold 720) |
| Guest available minimum | 260.0 MiB (stop threshold 192) |
| Heartbeat age maximum | 51.7 s (stop threshold 180) |
| Canonical runs | 78 completed, 183 attempts |
| Fixture-verification runs | 40 completed, 241 attempts |
| History runs | 659 completed, 1236 attempts; first completion seen 15:47 |
| Failed runs / published receipts / evidence rows | 0 / 0 / 0 |
| End state | stall stop at 16:19:55 (queue empty) → OFF epoch 43, no manual fence |

The watchdog's first check at 15:41:49 opened 699 `DUE_WORK` incidents, one
`FIXTURE_VERIFICATION_12H` and two `MISSED_DAILY_CAPTURE` without a timeout. All `DUE_WORK`
incidents recovered by 16:16:54. The other three stayed open, so owned health remained DOWN.

`fpl_fixture_sync` ended with 40 `VERIFYING` and 340 `SCHEDULED` rows. **No fixture reached
`VERIFIED`**: completed verification jobs are not the two-observation readiness rule.

The eleven-minute wait before the first history matches the finite-initial-work interpretation
below. The attempt counts reflect admission deferrals, because every lease increments the count.
A read-only review points at two queue costs:

- fixture-driven parent refreshes at priority 0;
- deferrals restoring priority 1.

These are candidate fixes, not measured changes. The next attended run must extend past the second
verification batch. Durable success means `VERIFIED` fixtures with staged evidence and the open
incidents recovering; memory alone does not establish it.

## Follow-up after PRs #100–101: bounded stops, not another OOM

PR #100 deployed as `546a207` (workflow 34950247318). The supervised 09:20–09:26 UTC
STAGING attempt, epoch 36, stopped when guest available memory reached **153.5 MiB**, below the
192 MiB guard. No OOM/restart was observed. Twenty-eight canonical jobs completed; 659 histories
had zero attempts. OFF **epoch 37** and application UP were verified after graceful shutdown;
no manual database fence was required and no core receipt was published. Tapas remains writer.
Kuma 18 was resumed; mailbox delivery/recovery is still unverified.

An earlier, brief epoch-34 attempt was stopped by a **monitor error**, not an application failure:
it compared a previous instance's heartbeat before the new instance completed its first tick.
It shut down to OFF 35. Monitoring must allow at most three minutes for the first successful
heartbeat of the new activation. Also resolve the Java PID by process identity, not a hard-coded
635: that PID produced implausible 20 MiB OFF samples. The epoch-36 guest-memory stop does not
depend on that flawed PID assumption; its recorded PID RSS rose 317→644 MiB, but process identity
was not independently recorded with each sample.

### Findings and next correction

- Cold fixture verification fetches its own bootstrap/fixtures pair and queues two canonical
  parent jobs. Canonical jobs are priority 0, initial verification priority 1, histories priority
  3; verification waiting for histories yields at priority 4. Completed parent jobs can be
  recreated by the next fixture. This is expensive **finite initial work**, not proof of starvation.
  Do not remove parent refreshes or globally promote all histories over urgent verification.
- A planner tick loads/parses the selected full-size pair, batches fixture transition writes and
  materializes durable slots. Its old fixed delay was one second **after tick completion**, not a
  guaranteed 60 ticks/minute. Change only that delay to **five seconds**, retaining immediate
  startup and the two one-second workers. The ideal polling ceiling falls 60→12/minute; the actual
  reduction depends on tick duration. Slot identities, source floors and verification stay intact.
- Reduce the Docker heap from 448 to **320 MiB**, keeping metaspace 160, code cache 64, direct 32
  MiB and ExitOnOutOfMemoryError. The VM stays 1 GiB. This reserves another 128 MiB outside the
  heap; it is not a whole-process hard cap or a demonstrated production high-water mark.

### Reproduction and measured limits

`OwnedMatchdayWarmStartPressureIntegrationTest` boots the real Spring web context with external
APIs and automatic sync disabled. Its manual, strictly parsed STAGING pipeline uses a disposable
DB, 40 synthetic completed fixtures and the checked-in 657-player catalogue (production had 659).
One second of **virtual**, not wall-clock, time passes per fake HTTP request. It drains a two-tick
initial queue serially, then retains three concurrently selected/parsed snapshots for ten rounds.
It does not simulate continuing planner ticks, two-worker dispatch/admission, a real API, provider
chat traffic, ACTIVE publication or two separated verification observations.

The initial queue drained: **657 histories, 40 fixture jobs, 84 canonical jobs**, 821 dispatches,
981 fake HTTP attempts (122 public pairs, 80 live reads, 657 histories), zero active jobs. The first
history started at virtual second **204**; the drain ended at 981. These are demand/progress
measurements, not a production completion-time promise. A fixed five-minute “no history” rule
confuses necessary initial work with starvation: inspect initial verification/dependency-wait
counts as well as completed catalogue jobs.

Under the proposed 320/160/64/32 MiB limits, SerialGC and one reported processor, the successful
bounded diagnostic took 77 seconds of workload time (93 seconds including Gradle). Sampled peak
RSS was **555.1 MiB**, heap use **252.2 MiB**, committed heap **309.4 MiB**, and metaspace used/
committed **82.0/82.8 MiB**. After releasing selections and a diagnostic-only explicit GC, heap use
was **80.4 MiB**, while committed heap/RSS remained **309.4/554.9 MiB**. Reserved/committed space,
live objects and RSS are different measurements; high RSS alone does not prove retained-heap
leakage. There is no forced GC in production.

An earlier 384 MiB-heap web-context serial run passed at 609.1 MiB sampled RSS, 285.3 MiB peak
sampled heap use and 361.3 MiB committed heap. This is supporting evidence, not a controlled
heap-only comparison: the later run also adds concurrent retention and runs without the separate
Mockito runtime tests. The final hard 1536 MiB scope peaked at 941 MiB; host headroom stayed above
2348 MiB. Earlier diagnostics were stopped by host pressure or an erroneously smaller 1024 MiB
whole-Gradle scope; those interruptions are **not** application OOM reproductions or passing tests.
Only disposable port-55438 databases were used and removed afterward.

Reproduce the single-class diagnostic under the enforced scope in `DEVELOPMENT.md`, with a private
disposable database and test credentials (never Supabase):

```bash
cd backend
./gradlew -I ../docs/diagnostics/owned-runtime-budget.gradle cleanTest test
```

The init script restricts selection to this one test and applies the proposed JVM caps. The normal
suite keeps its checked-in budget.

### Epoch 38 and watchdog correction

PR #101 deployed the five-second planner and 320 MiB heap. Epoch 38 kept one identified Java
process from 11:22 UTC through the observer's last complete sample. RSS levelled at about 537 MiB
and guest available memory at about 271 MiB; no OOM/restart occurred. Initial work continued:
68 canonical runs had completed, 40 verification jobs were accumulating first-pass attempts and
659 histories remained unattempted. This remains an incomplete rehearsal, not evidence of eventual
convergence.

At 11:27 the first scheduled watchdog check exceeded its isolated five-second transaction. The
stack ended in `nextOccurrence`: after collecting hundreds of due-work signals, the old code read
an occurrence and inserted an alert once per signal. The check rolled back and external health
alerted. The user's message interrupted the local observer, not the Fly process; OFF was requested
immediately. Graceful shutdown recorded **OFF epoch 39**, failed the epoch's 40 active verification
and 659 history runs, retained 74 completed canonical runs, and published zero core receipts. Kuma
18 is paused while OFF; Tapas remains writer.

The pending correction preserves one durable incident per signal and advisory-lock atomicity. It
loads all next occurrences in one grouped query and writes incidents/recoveries in one JDBC batch.
It also replaces per-open-incident recovery queries with one completed-run read, one verified-
fixture read and one grouped capture-receipt read. A disposable test creates and then recovers 700
due-work incidents using the real five-second transaction limit. Eleven focused watchdog/database
tests pass under a hard 768 MiB, no-swap, 150-second diagnostic scope. This is local verification,
not Supabase latency evidence. A reviewed release and another attended STAGING are still required;
keep memory/health/restart stops, assess cold-start queue progress, and verify durable OFF on exit.

## Overnight incident (before PR #100)

The overnight STAGING run failed; it did not establish readiness for writer takeover.
Fly confirmed a Java OOM kill at 07:29 UTC (exit 137, anonymous RSS 877728 KiB).
Activations advanced from epoch 5 to 32; that count is not proof that every restart was an OOM.
The latest epoch completed twelve catalogue/schedule jobs, but all 659 history jobs still had
attempt count zero. There were 1673 HTTP attempts overall, no published owned receipts and no
player evidence.

The replacement process was verified OFF and application health returned HTTP 200/UP. A guarded
metadata-only transaction then fenced the drained epoch-32 queue and recorded OFF epoch 33.
Payloads/evidence were preserved. Tapas remains the core writer; its credentials and flags were
not changed. The subsequent deployment and supervised attempt are described above.

## What is established, and what remains uncertain

1. **A large allocation hotspot is reproduced.** Canonical lookup used to load the bodies of up
   to twenty pairs into a list before a caller selected or rejected them. Production bootstrap
   bodies averaged 1.67 MiB; fixture bodies 0.20 MiB. Six valid pairs in the last epoch totalled
   11.25 MiB raw. A matching local six-pair workload allocated about 70 MiB for one lookup and
   parsing the first pair. JFR's largest allocation stacks were PGStream tuple reception,
   PGbytea hex decoding and StagedPayload's defensive clone.
2. **Catalogue reuse adds avoidable work.** A new source-demand floor can reject every older
   pair. Previously that freshness check happened after loading and copying their bodies.
3. **The old JVM policy leaves limited non-heap headroom.** A local 1 GiB ergonomics probe permits
   a 768 MiB heap under `MaxRAMPercentage=75`. Read-only production HotSpot counters refine this:
   the guest's smaller usable memory produces an actual maximum of approximately 722 MiB.
   An OFF sample had about 328 MiB process RSS and 76 MiB committed metaspace. Metaspace had no
   explicit application cap; code-cache reservation was 240 MiB. Reservation is not resident
   memory, and these samples are not a complete native-memory profile.

These are measured contributors/risk factors, **not proof of a single exclusive OOM cause**.
The short local probe did not exhibit continuously retained heap growth. It neither establishes
nor excludes a leak elsewhere in the full application. No production heap dump was collected.
Repeated restarts also discard the usable activation's work and amplify cold-start work; the
failed queue records alone must not be misreported as individual upstream failures.

## First correction (PR #100)

- Select at most twenty candidate IDs and read timestamps first. Fetch one exact same-run body
  pair at a time and stop after the first acceptable result. All three consumers use this path:
  planner input, history canonical input and catalogue reuse.
- Reject candidates older than the catalogue request/UK-day floor using metadata, before any
  body retrieval. Keep the original bounded newest-first fallback rather than replacing it with
  `LIMIT 1`. The body query rechecks season, runtime epoch/mode, endpoint kind and VALID state.
- Preserve strict parsing, source floors, defensive byte-array copies, lease/publication fences,
  fixture verification and ranking/eligibility rules. No unknown value is turned into zero.
- Keep the Fly VM at 1 GiB, but replace the percentage heap with explicit application limits:

| Area | Limit |
|---|---:|
| Java heap | 448 MiB |
| Metaspace | 160 MiB |
| Code cache | 64 MiB |
| Direct buffers | 32 MiB |

These limits total 704 MiB; remaining guest memory must cover other native allocations, threads,
the OS and Fly's processes. They are not a whole-process hard cap. `ExitOnOutOfMemoryError`
prevents a Java OOM from leaving a partially functioning JVM, but does not solve restart loops
or replace monitoring. The OFF metaspace sample informed the limit; full provider/chat load
under these caps still needs observation.

## Local measurement

Disposable PostgreSQL at port 55436, checked-in full-size source fixtures, one visible CPU;
no application runtime, chat provider or external FPL calls. Diagnostics were serialized under
cgroup memory protection and a host-memory stop threshold.

| Workload | Allocation per selection | Other observation |
|---|---:|---|
| Before: six pairs, parse first, 12 selections | 70.32 MiB | 11.20 MiB raw fixtures |
| Corrected: same workload, 12 selections | 33.00 MiB | about 53% less allocation |
| Corrected: all six pairs older than required floor | 0.01 MiB | no body query |
| Corrected: twenty pairs, 300 selections | 32.13 MiB | 17.96 seconds; 17.43 MiB heap after explicit GC |

The complete corrected probe, including seeding and all cases, peaked at **443 MiB process RSS**
and took 26.78 seconds under the proposed JVM limits. This is a bounded repeated-load probe,
**not a long-duration full-application soak or proof that production history ingestion completes**.

The checked-in diagnostic is `docs/diagnostics/OwnedMemoryProbe.java`; its classpath init script
is alongside it. To reproduce, start/reset only a dedicated `fpl-memory-probe` container on 55436,
source SDKMAN and the local test environment, and override the JDBC port in that process only.
The probe refuses other JDBC destinations. Use a fresh disposable schema for each run.
Run `./gradlew -I ../docs/diagnostics/memory-probe-classpath.gradle memoryProbeClasspath` from
`backend`, compile the diagnostic with the resulting `/tmp/fpl-probe-classpath.txt`, then run its
`fi.karivanttinen.fplassistant.sync.OwnedMemoryProbe` main with the Dockerfile's JVM limits and
`-XX:ActiveProcessorCount=1`. Keep build and diagnostic execution sequential and bounded as in
`docs/DEVELOPMENT.md`; stop the dedicated container afterward. JFR files may contain environment
metadata: keep them private and never commit them.

## Regression verification and resource gate

The real-DB regression covers full-size fallback, same-run/season/epoch filtering, a source
invalidated between metadata selection and body fetch, and stale-floor rejection without body
reads. Existing dispatcher/end-to-end tests remain applicable.

The latest focused attempt was stopped by the host-memory guard: available host memory fell to
691 MiB while the build/test scope peaked at 983 MiB, below its own 1536 MiB limit. The guard killed
only that scope; no other project's processes were stopped. Nine history-dispatcher tests had
completed successfully, but the interrupted run is not a passing full verification. Final suite
and publication status must be recorded before this correction is called complete.

## Proposed next rollout — not authorized by test success alone

After reviewed deployment, first verify OFF and its memory limits. Do not leave another unattended
overnight STAGING run. Reserve an attended, at-most-30-minute experiment with a confirmed stop path;
a chat response does not provide background monitoring after the agent stops executing tools.

Sample health, current activation, job progress and process/guest memory at least once a minute.
Stop immediately on an OOM/restart, unresponsive application health, or planner heartbeat older
than three minutes. Stop conservatively at 720 MiB JVM RSS or less than 192 MiB guest available
memory; these are rehearsal stop thresholds, not player/data eligibility rules. If canonical work
is ready but histories have not started, also inspect the initial verification jobs moving into
dependency wait. Stop after five minutes without either upstream first-pass progress or history
progress, rather than assuming zero history attempts alone proves a stall. Do not extend the
attended time bound silently. Allow a new activation three minutes to produce its first heartbeat;
never use an old activation's heartbeat for that startup check. Resolve the Java PID by identity.
Always verify process drain and the durable OFF epoch afterward.

Success requires completed canonical **and history** work with advancing verification staging and
stable memory—not merely VALID raw payloads or a fresh planner heartbeat. Full reconciliation,
notification recovery, least-privilege credential handover and old-writer drain remain separate
gates before ACTIVE. Separating sync and web into different processes is a possible later isolation
measure, not implemented here; simply increasing VM memory is not the proposed remedy.
