# KAR-36 — run the pre-push suite against a fresh test database

**PROGRESS:** Task 4/4 done.

Issue: [KAR-36](https://linear.app/quantti/issue/KAR-36). Checked in Linear on 2026-09-12:
Backlog, High, no blocking relations. Baseline: `master 13be2ae`. Planning only.

## Goal and selection

Every content-bearing pre-push check must run the backend suite against a freshly recreated local
test database, rather than accepting a green result dependent on earlier application startup.
Configuration mismatches must fail before destroying a container or starting tests.

First of the three selected tickets: High-priority, reproducible infrastructure gap with an existing
reset mechanism. KAR-52 follows as a bounded frontend bug; KAR-48 requires wider provider coverage.
These are independent tickets, not prerequisites for one another. KAR-51's smaller test connection
pool remains separate: do not change pool sizing or remove SSE test contexts here.

## Current evidence and scope

- `scripts/test-db.sh reset` already exists. No `--fresh` alias or new migration framework is needed.
  It recreates the container, installs vector and loads `backend/src/test/resources/db/tapas-schema.sql`.
  Fresh means Tapas DDL exists, but application-created tables and prior test data do not.
- `scripts/hooks/pre-push` calls `up` before loading `backend/.env.test`. The hook can therefore reuse
  old tables, or start one database while Gradle connects to another.
- `reset` currently suppresses every removal error with `|| true`; if removal fails and the old
  container still runs, `start` can reuse it. Freshness must be a verified outcome, not a label.
- `.github/workflows/deploy.yml` already starts an ephemeral database and loads the same schema.
  Keep that database lifecycle; add only the new fast shell regressions to CI.
- The original ordering bug is already fixed: `InviteCodeSchema` depends on `userSchema`
  (`0c1aa74`, PR #52 incident). Existing schema tests cover creation/idempotence. Do not undo the
  dependency or add speculative ordering annotations to unrelated beans.
- KAR-42's SSE race was fixed in merged PR #77. KAR-37 / PR #80 removed the stale index comment
  mentioned in KAR-36. Neither is unfinished KAR-36 work. GitHub #56 contains migration comments,
  not further requirements; Linear remains the source of current status.
- `docs/DEVELOPMENT.md` overstates cleanliness: ordinary `up` retains state. Correct that description
  with the hook change. `docs/TODO.md` is historical, not a work queue to repopulate.

No production/Supabase access, schema-dump regeneration, application DDL refactor or deployment.
Reset destroys local test data; it must never run alongside another DB-backed test or local app
using that container. Retain `up` for iterative development.

## Task 1 — pin the hook and reset contracts without Docker

Proposed file: `scripts/tests/pre-push-test.sh`, a small Bash test runner using temporary fixture
repositories and PATH stubs for Git, Docker and Gradle. Copy the real scripts into the fixture;
do not make a second implementation of their logic. Fixture `.env.test` files contain dummy data.
Stub SDKMAN through a temporary HOME. No real credentials, Docker daemon or database in these tests.

Assert behavior and call order, not grep matches:

- A content push requests `reset` before `cleanTest test`, never reusable `up`.
- Missing configuration, a nonlocal host, wrong port/database/credentials, invalid port or a
  conflicting datasource override fails before Docker mutation or Gradle execution.
- Matching `TEST_DB_PORT` overrides work; mismatch cannot silently fall back to another port.
- A reset/removal/schema-load failure prevents Gradle and returns nonzero.
- Deletion-only pushes remain a no-op; noninteractive master pushes are still refused before
  any reset. Exercise the real master gate, not a rewritten test stub for it.
- Stub an existing running container whose removal fails: reset must not reuse it successfully.

Verify the relevant assertions fail on current scripts for the intended freshness/order reasons.
Keep unrelated already-correct gate assertions green. No push is necessary to exercise stdin.

**Task 1 outcome (2026-09-12):** Added the stub-only fixture runner with copies of the real hook
and database script. Baseline: 17 checks ran without Docker; 5 existing contracts stayed green and
12 intended freshness/preflight/reset contracts failed.

## Task 2 — validate the destination before resetting it

File: `scripts/hooks/pre-push`.

Load `.env.test` before lifecycle operations and use the same exported settings for preflight,
`test-db.sh` and Gradle. Require its database variables to be explicitly set instead of inheriting
production values. Validate against the script's actual contract: simple
`jdbc:postgresql://localhost:<port>/postgres` (also allow `127.0.0.1`), effective
`${TEST_DB_PORT:-55432}`, and its fixed `postgres` user/password. Reject unsupported URL forms,
parameters or competing Spring datasource overrides with a credential-free explanation rather
than implementing a general JDBC parser or rewriting the user's environment.

Check local Docker availability/context and schema readability before destructive operations;
a remote Docker endpoint must not be treated as a local disposable database. Preserve existing
container/port overrides when they identify the intended local test fixture. Never delete an
unrelated container to resolve a port conflict. Run the shell regressions before the real reset,
then invoke existing `reset` and retain `--console=plain --quiet cleanTest test`.

Keep master confirmation and deletion-only gates first and unchanged. Do not bypass the hook,
change `core.hooksPath`, print secrets or add a skip-test option.

Verify Task 1's preflight/ordering tests pass and the same variables reach reset and Gradle.

**Task 2 outcome (2026-09-12):** The hook now loads and exports `.env.test` before any lifecycle
operation, requires its three database settings, accepts only the fixed local fixture contract,
and rejects competing Spring datasource settings or a remote/unavailable Docker destination
before mutation. It checks the schema, runs the stub regressions without fixture recursion, then
calls `reset` with the same environment before Gradle. The expanded stub suite has 22 Task 2 and
preserved-gate checks green. Its two expected Task 3 checks remain red: `reset` still suppresses a
running container's removal failure, both directly and when called through the hook.

## Task 3 — make reset fresh or unsuccessful

File: `scripts/test-db.sh`; extend the proposed shell tests.

Distinguish an absent container from Docker inspection/removal failures. Existing-container
removal must succeed before creating its replacement. Check schema readability before removal;
retain SQL `ON_ERROR_STOP` so a partial schema load cannot produce a successful reset. Do not
expand this into a general container manager or change ordinary `up` semantics beyond shared
code necessarily affected by the fix. Remove the now-stale comment saying the hook uses `up`.

Add an explicitly opt-in `--integration` mode to the test runner for a bounded real-Docker check.
Use a dedicated test container name and unused local port, refuse to take over an existing
container, and clean up only that fixture. Create a sentinel application table, reset, then assert:

- the container was recreated and the sentinel is absent;
- Tapas `season` and vector exist;
- application-owned test tables are not precreated by the reset;
- a second successful reset leaves the same clean starting state.

The ordinary shell-test mode must never invoke this Docker mode implicitly.

**Task 3 outcome (2026-09-12):** `test-db.sh reset` now checks that the schema is readable
before inspecting or removing anything, treats only Docker's explicit “No such container” result
as absence, fails on inspection/removal errors, and verifies removal before creating a replacement.
The stale `up` comment was removed. The stub suite is **28 passed, 0 failed**, including the two
prior removal reds plus removal-outcome, absent-container, inspection-error and direct
schema-preflight coverage.
`bash scripts/tests/pre-push-test.sh --integration` used only
`fpl-kar36-reset-integration` on unused local port **46291**, recreated it twice, and verified after
each reset that the sentinel was absent, `season` and vector existed, and application test tables
did not. Its cleanup left that name absent. A separate collision probe returned **1**, printed
`refusing existing fixture container fpl-kar36-reset-integration`, preserved the same container ID,
and the probe fixture was then removed explicitly. No backend tests ran.

## Task 4 — integrate and verify the fresh startup

Files: `.github/workflows/deploy.yml`, `docs/DEVELOPMENT.md`, `docs/STATUS.md`, this plan.

Run the fast stub-based shell tests in CI, before the backend suite. Keep CI's existing ephemeral
Postgres setup and failure-report artifact upload from KAR-42. Document reusable `up` versus
fresh `reset`, the pre-push data-loss warning and endpoint refusal behavior.

Exercise the real hook against the exclusively held local test container, without an actual push.
The full suite must execute from reset state; a Gradle up-to-date result is not acceptance. Record
actual results and any fresh-start failures separately from known connection-pressure issues.
Do not mask a failure with retries, schema precreation, bigger pools or weaker tests.

**Task 4 outcome (2026-09-12):** CI now runs the 28 stub-only shell regressions before the backend
suite without changing its ephemeral Postgres service or KAR-42 failure artifacts. Development
docs distinguish reusable `up` from destructive fresh `reset` and describe the hook's local
endpoint and Docker-context refusals. All three scripts passed `bash -n`; the standalone stub suite
passed **28/28**. A simulated feature-ref invocation of the real hook recreated `fpl-test-db` on
`localhost:55432` and forced `cleanTest test`; the full backend suite passed **844 tests, 0
failures, 0 errors, 0 skipped**. There was no fresh-start or connection-pressure failure. No push,
Supabase access or Nutribot container operation occurred.

## PR #81 post-creation review fixes (2026-09-12)

Two review findings were applied without changing the completed task outcomes above. The hook now
follows Docker CLI precedence: an explicit `DOCKER_CONTEXT` is inspected first, `DOCKER_HOST` is
used only when no context is selected, and the active context is inspected only when neither is
set. Stub regressions prove both mixed-variable directions and fail closed when the selected
context cannot be inspected. The opt-in integration runner now explicitly returns every reset and
clean-state assertion failure while its `RETURN` cleanup remains active; a stubbed failed first
container creation proves the mode exits nonzero, does not print success, and still removes its
owned fixture.

Post-review verification: all three scripts passed `bash -n`; the stub suite passed **32/32**; the
isolated real-Docker integration recreated its dedicated container twice on unused port **51733**,
reported both clean states, and left `fpl-kar36-reset-integration` absent. No backend suite, push,
Supabase access or unrelated container operation was performed.

## Verification commands — proposed, not run during planning

From repository root, Java 25/SDKMAN and local Docker available. `backend/.env.test` must match
the disposable local database; the existing RAG integration tests require a real OpenAI embedding
key. Never source `.env.supabase` or enable `includeRealData`.

```bash
for script in scripts/test-db.sh scripts/hooks/pre-push scripts/tests/pre-push-test.sh; do
  bash -n "$script" || exit 1
done
bash scripts/tests/pre-push-test.sh
bash scripts/tests/pre-push-test.sh --integration
# This invokes the hook's test path, not git push; it resets the normal local test DB.
printf '%s\n' \
  'refs/heads/kar-36 1111111111111111111111111111111111111111 refs/heads/kar-36 0000000000000000000000000000000000000000' \
  | bash scripts/hooks/pre-push
```

## Implementation handoff

Start a dedicated `kar-36-fresh-pre-push-db` feature branch from current master. Execute one
numbered task at a time, verify it, and return control before the next. Update the single progress
line once per completed task and record outcomes in STATUS. Commit verified implementation, push
only the feature branch, open a PR targeting `master` with `Closes KAR-36`, and review the PR.
Owner merges. This planning session performs none of those implementation/push/deployment steps.
