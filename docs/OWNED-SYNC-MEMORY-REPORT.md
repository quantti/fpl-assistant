# Owned-sync memory incident — 2026-09-15

## Outcome and current safety state

The overnight STAGING run failed; it did not establish readiness for writer takeover.
Fly confirmed a Java OOM kill at 07:29 UTC (exit 137, anonymous RSS 877728 KiB).
Activations advanced from epoch 5 to 32; that count is not proof that every restart was an OOM.
The latest epoch completed twelve catalogue/schedule jobs, but all 659 history jobs still had
attempt count zero. There were 1673 HTTP attempts overall, no published owned receipts and no
player evidence.

The replacement process was verified OFF and application health returned HTTP 200/UP. A guarded
metadata-only transaction then fenced the drained epoch-32 queue and recorded OFF epoch 33.
Payloads/evidence were preserved. Tapas remains the core writer; its credentials and flags were
not changed. The memory correction described below is not deployed or activated by this report.

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

## Correction

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
is ready but history attempts remain zero for five minutes, stop and investigate instead of
waiting indefinitely. Always verify process drain and the durable OFF epoch afterward.

Success requires completed canonical **and history** work with advancing verification staging and
stable memory—not merely VALID raw payloads or a fresh planner heartbeat. Full reconciliation,
notification recovery, least-privilege credential handover and old-writer drain remain separate
gates before ACTIVE. Separating sync and web into different processes is a possible later isolation
measure, not implemented here; simply increasing VM memory is not the proposed remedy.
