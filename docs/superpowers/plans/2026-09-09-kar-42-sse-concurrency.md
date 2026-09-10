# KAR-42 — diagnose and fix the intermittent chat SSE concurrency failure

**PROGRESS:** Task 2/4 done; root cause: `HeaderWriterFilter` writes security headers from the request thread's `finally` while the MVC executor's first SSE commit writes them too.

Existing issue: [KAR-42](https://linear.app/quantti/issue/KAR-42/chatcontrollertest-hit-a-concurrentmodificationexception-inside-the).
Checked 2026-09-09: Backlog, Low (4). No duplicate was created and no priority/status was changed.
The recurrence and current diagnostic findings were added as a comment on the existing issue.

## Goal and boundaries

Identify the collection/lifecycle operation behind the intermittent `ConcurrentModificationException`
and remove its cause without weakening the SSE, authentication, persistence or usage contracts.
Tasks 1–2 can proceed now; the production/test repair is **not implementation-ready until the
root-cause gate passes**. A green rerun is not a fix and does not prove this is test-only.

Execute one numbered task per worker invocation and return evidence before the next. Use a separate
feature worktree based on the merged master code and a separate disposable database; do not edit,
reset or rebase the concurrent KAR-35 work. This ticket is independent of ranking changes.
Coordinate overlapping `ChatController`/test edits and framing assertions with
[KAR-8's error-message plan](2026-09-09-kar-8-chat-errors.md), but do not implement its UI changes here.

## Evidence already available

- [Failed master run 34366423923](https://github.com/quantti/fpl-assistant/actions/runs/34366423923),
  2026-09-09, commit `f6624b35b987f1ea1bfb66a0eabb6ca6598e1a24`: 702 tests ran, two failed:
  `ChatControllerTest.turnsLostToTheReadCeilingAreAnnouncedToo` and
  `ChatControllerTest.aMidStreamFailureEmitsAnErrorEventAndStoresNothing`.
- The summary shows `WebClientRequestException` at `ChatControllerTest.java:322` (`post().exchange()`),
  with `ServletException`/`IllegalStateException` at `ResponseBodyEmitter.java:259` and root
  `ConcurrentModificationException` at `HashMap.java:1230`. The second case also shows a direct
  concurrent-modification cause. These summaries do not identify the collection owner.
- Frontend build passed; deploy was skipped. The merge's tree is exactly the green PR head
  `03a4ee9` tree (`9e049891b1f52ff882095a828d2b179a2bd5100f`). KAR-31 changed neither chat
  code/tests nor the dependency pins. The successful PR run was
  [34363926917](https://github.com/quantti/fpl-assistant/actions/runs/34363926917).
- The existing KAR-42 report records the same exception on documentation-only PR #59, in
  `asecondMessageContinuesTheSameConversation`, and twelve green local class runs afterward.
  The new diagnostic pass likewise ran all fourteen current class tests five times locally,
  with the anthropic configuration and disposable database: all passed. Neither series proves a fix.
- CI uploads no test report artifacts; the failed run's artifact list was empty. Its complete
  originating stack is therefore not available from retained test reports.
- `ChatController.answerOutlivingTheClient` deliberately owns a detached subscription and replays
  its output. `.publishOn(Schedulers.boundedElastic())` keeps blocking persistence/accounting off
  provider event-loop threads. Successful generation must survive a browser disconnect.
- Local Spring Framework 7.0.8 bytecode maps `ResponseBodyEmitter:259` to the wrapper around
  `Handler.send(Set)`. Both emitter initialization and sends hold `writeLock`; the MVC reactive
  subscriber has its own task executor and send scheduling. `DefaultSseEmitterHandler.send(Set)`
  iterates items, invokes message converters and flushes the response. Thus the old ticket's
  `earlySendAttempts` explanation is an **unproven hypothesis**, not a demonstrated missing lock.
  A downstream response/header collection or reentrant lifecycle operation is also possible.
- `MockMvcHttpConnector.connect` waits for the async result, performs async dispatch, then adapts
  the mock response. An exception reported at `.exchange()` does not establish which of those
  phases failed. Confirm selected dependency versions against the failing CI environment before
  relying on local line mappings.

## Task 1 — retain the complete failure evidence

Files: `.github/workflows/deploy.yml`, `backend/build.gradle`.

Configure Gradle failed-test logging for full exceptions, nested causes and stack frames, including
suppressed exceptions where emitted. A Gradle task-level `--stacktrace` alone is not a substitute
for the underlying test exception.

Add an artifact-upload step after the backend test step, guarded by `always()`, for:

- `backend/build/test-results/test/**`
- `backend/build/reports/tests/test/**`
- proposed test-only diagnostics under `backend/build/diagnostics/kar-42/**`

Use a supported official `actions/upload-artifact` release, checked at implementation time. Retain
normal failing job status; do not add retry plugins, `continue-on-error`, `|| true`, or any path
that deploys after failing tests. If an earlier step prevents tests from starting, missing reports
must not conceal the original failure. Upload only test reports and targeted diagnostics, never env
files, access tokens, production payloads or dumps of the process environment.

Capture the exact test JVM patch, resolved Spring Framework/Security versions and active MVC task
executor configuration with the diagnostic results. Recheck source/bytecode for those versions.

Verification: a temporary local nested-cause failure remains red and shows the complete chain in
console/XML; remove that temporary probe afterward. Check the workflow's failure/upload conditions
and verify retained artifacts on the eventual feature PR. Do not trigger the master workflow just
to collect diagnostics: a successful run would deploy.

## Task 2 — controlled lifecycle reproduction and root-cause gate

Files: existing `backend/src/test/java/fi/karivanttinen/fplassistant/chat/ChatControllerTest.java`;
proposed sibling `ChatSseLifecycleTest.java` and only the test support it needs.

Use the existing controllable fake-model approach (`@MockitoBean ChatService`, compliant `Flux`
signals), `LocalSigningKey`/`TestJwks` and test-owned `_test` data. Keep production security and
filters in the chain. Compare:

1. The current `MockMvcWebTestClient` connector.
2. Real embedded servlet HTTP with `@SpringBootTest(webEnvironment = RANDOM_PORT)` and the JDK
   HTTP client, so the servlet path is exercised without introducing another HTTP-client dependency.

Start with the two currently failing scenarios, each across three controlled orderings on both
transports: at most twelve diagnostic cases, not an open-ended stress run.

- Events queued before emitter initialization.
- First send/flush overlapping servlet/filter unwind and response-header work.
- Terminal events/completion overlapping async dispatch and response adaptation.

Instrument the relevant phases using **test-only delegating probes** and bounded latches/barriers
at boundaries verified from the exact framework version. Assert that each intended ordering actually
occurred. A pause around `tryEmitNext` or `exchange()` alone does not prove emitter ordering. Probes
must preserve framework locking/executor semantics and publisher serialization; do not manufacture
a race by removing locks or concurrently emitting from a non-compliant publisher.

Every wait and HTTP request has a deadline. Teardown must release barriers, terminate fake sources,
await detached persistence/accounting, close clients/executors and then remove seeded rows, including
on assertion failure. Do not leave threads blocked or let cleanup race an in-flight database write.

Respect the shared per-IP 20/minute limit: use distinct test-only `Fly-Client-IP` scenario values as
in `MyTeamControllerTest`, not higher production limits or test sleeps. Keep the dedicated rate-limit
assertions unchanged. Do not use real LLM failures, production accounts or Supabase as an oracle.

### Evidence required before Task 3

Record in a short outcome block:

- complete stack from the originating mutation/iteration;
- exact collection owner and failing operation;
- participating threads **or** verified reentrant mutation sequence;
- the controlled phase ordering and MockMvc-versus-real-servlet comparison;
- a regression that fails against the unchanged application/framework behavior for that cause.

Inspect downstream converter/response/header state as well as emitter buffers. If the bounded
investigation cannot establish the cause, stop and return the findings and next precise evidence
needed. Leave KAR-42 open; do not select a scheduler fix merely because ordinary reruns are green.

### Task 2 outcome

Root-cause gate passed. Versions: JDK 25.0.4 (same patch as CI), Spring Framework 7.0.8, Spring
Security 7.1.0. Line mappings were checked against those exact sources.

- **Cause.** Spring Security's `HeaderWriterFilter` defers header writing by default, so it writes
  the security headers twice on the same response. Nothing orders the two writes. (1) On the MVC
  executor thread, the first SSE flush commits the response, and `HeaderWriterResponse.onResponseCommitted`
  writes them (`DefaultSseEmitterHandler.send` → `ServletServerHttpResponse.flush` →
  `OnCommittedResponseWrapper.flushBuffer`). (2) On the request thread, `doHeadersAfter`'s `finally`
  (`HeaderWriterFilter.java:93`) writes them while the thread unwinds after `startAsync`. Its
  `disableOnCommitted` guard is a plain field. The window opens only when `ResponseBodyEmitter.initialize`
  finds no queued events, so the executor thread does the first commit instead of the request thread.
- **Collection and failing operation.** The collection is `MockHttpServletResponse.headers`
  (`LinkedCaseInsensitiveMap` over a `LinkedHashMap`). The call chain is
  `doAddHeaderValue:788` → `LinkedCaseInsensitiveMap.computeIfAbsent:235` →
  `HashMap.computeIfAbsent:1230`, and the exception is that method's `mc != modCount` check.
  Emitter buffers are ruled out: `earlySendAttempts` is only touched under `writeLock`, and the
  converters write to a per-emitter header copy (`StreamingServletServerHttpResponse`).
- **Stacks and threads.** Both CI signatures were reproduced with complete chains. The threads are
  the executor `task-N` (Boot `ThreadPoolTaskExecutor`, core 8, recorded in
  `build/diagnostics/kar-42/mvc-async-executor.txt`) and the request thread.
  - On `task-N`, CME → `IllegalStateException` at `ResponseBodyEmitter:259` → `ServletException`
    on the async dispatch. This is CI case 1.
  - On the request thread, the CME comes from `doHeadersAfter:93` → `XXssProtectionHeaderWriter`
    and leaves `perform` directly under `WebClientRequestException`. This is CI case 2.
  - Evidence: a temporary barrier-released run hit the genuine CME in 5 of 40 attempts. That run
    was not deterministic, so it is not committed. Its logs are kept under
    `build/diagnostics/kar-42/race-run-1/`.
- **Ordering comparison.** `ChatSseLifecycleTest` runs 12 cases: 2 scenarios × 3 orderings × 2
  transports (MockMvc, Tomcat).
  - `EARLY_QUEUED` and `TERMINAL_DURING_DISPATCH` pass on both transports. In these orderings
    `initialize` commits on the request thread, so the `finally` write is skipped. The terminal
    flush comes before `postProcess`, and the async dispatch runs under the `WebAsyncManager`
    monitor after it.
  - `FIRST_FLUSH_DURING_UNWIND` records the overlap on both transports: the request thread writes
    the same six headers while the emitter thread is inside its first commit.
  - The race is therefore not MockMvc-only. Tomcat's `MimeHeaders` has no fail-fast check, so there
    the same interleaving goes silent instead of throwing.
- **Regression.** The four `FIRST_FLUSH_DURING_UNWIND` cases are committed enabled and red (reports
  in `build/diagnostics/kar-42/red-run-2/`). The probe pauses the emitter before it delegates, so
  those cases fail on the recorded overlap, never on a manufactured CME. The other eight are green.
- **For Task 3.** This is the framework row, reached through this app's `SecurityConfig` defaults.
  First look for an upstream Spring Security issue or fix. Then compare that with a change at the
  `SecurityConfig` boundary, and verify whichever is chosen with these cases.

## Task 3 — repair only the demonstrated boundary

The exact file change is intentionally conditional on Task 2, not guessed during planning:

| Demonstrated cause | Minimal repair direction |
| --- | --- |
| MockMvc adapter/test-lifecycle defect only | Correct or replace that test transport/lifecycle handling; retain real-servlet SSE coverage. Leave production scheduling and security unchanged. |
| Framework defect on the real servlet path | Prefer an exact supported, Boot-compatible framework correction with upstream issue/commit/release evidence. Verify the fixed version with the same reproduction; do not upgrade dependencies speculatively or mix incompatible Spring modules. |
| Application-owned lifecycle/handoff defect | Apply a narrow ownership/synchronization correction at the proven `ChatController` or configuration boundary, with no cross-request bottleneck. |

Do not catch-and-ignore the exception, disable assertions, insert timing sleeps, serialize all chat
requests on a global scheduler, or move blocking JDBC back onto provider threads. Preserve the
single detached model subscription and its bounded lifetime.

Verification: keep the controlled ordering and assertions identical. Repair absent → identified
failure; repair present → correct behavior. Preserve both reports before `cleanTest` deletes them.
Several consecutive green runs are supplementary evidence, not the red/green proof.

## Task 4 — regression acceptance and verified handoff

Files: the affected chat tests, proposed lifecycle test and a scoped `docs/STATUS.md` outcome entry.
Keep only small deterministic regression cases in the normal suite, not a permanent stress loop.

Verify the unchanged contracts:

- `conversation` first, optional `truncated`, ordered JSON-string tokens preserving leading spaces
  and newlines, optional `error`, then exactly one `done`. Inspect actual wire framing as well as
  decoded event objects.
- Generation failure never persists a partial assistant answer. No measured usage means a refund;
  measured usage is retained. A failure after learning only the model name is still unmeasured.
- Close a real HTTP response while generation is pending, then complete the fake model. The answer
  is persisted exactly once and usage is handled correctly despite the departed browser.
- Ownership/404, validation, quota/429 and history truncation remain intact. Blocking persistence
  and accounting remain off the fake provider's emission thread.
- Cleanups are bounded, leave no test-owned rows/threads behind and do not depend on test order.

Run focused tests and the full suite against the separate disposable database. Preserve the failing
and passing evidence, update this plan's single progress line once per completed task and record
limitations honestly. Commit the verified fix on its dedicated feature branch, push it and open a PR
against `master` with `Closes KAR-42`. Review once for the final code; reuse a just-completed review
if no code changed. The owner merges. No master rerun, `workflow_dispatch`, direct master push,
manual deployment or production sync is part of this task.

## Commands for implementation — not executed in this planning pass

Prerequisites: separate KAR-42 worktree, Java 25, Docker, and a gitignored `backend/.env.test` in that
worktree using `jdbc:postgresql://localhost:55433/postgres`. Check the proposed port/container are
free first; do not reset the KAR-35 database. Use the same test credentials/provider configuration
as documented in `docs/DEVELOPMENT.md`. KAR-42 tests use fake models; the **full** suite needs the
existing real OpenAI embedding key only for its pre-existing RAG tests.

```bash
# From the separate KAR-42 worktree root; no set -u during SDKMAN initialization.
(
  set -e
  export TEST_DB_CONTAINER=fpl-kar42-test-db TEST_DB_PORT=55433
  scripts/test-db.sh up
  cd backend
  source "$HOME/.sdkman/bin/sdkman-init.sh"
  set -a; source .env.test; set +a
  test "$DATABASE_URL" = 'jdbc:postgresql://localhost:55433/postgres'

  # Save diagnostic red reports separately before a subsequent cleanTest.
  ./gradlew cleanTest test \
    --tests '*ChatControllerTest' --tests '*ChatSseLifecycleTest'
  ./gradlew cleanTest test \
    --tests '*.chat.*' --tests '*JwtAuthenticationTest' \
    --tests '*RateLimitFilterTest' --tests '*UsageServiceTest'
  ./gradlew cleanTest test
)
```

This plan does not claim a confirmed collection race, a production-safe scheduler workaround or a
fixed pipeline. Current evidence and five local class reruns come from the preceding diagnostic
pass; this planning pass inspected source/bytecode and updated documentation/Linear only.
