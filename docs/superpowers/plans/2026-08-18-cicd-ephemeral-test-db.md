# FPL Assistant — CI/CD: Automatic Production Deploy + Ephemeral Test Database

**Status:** planned, not started (2026-08-18)

**PROGRESS:** Task 0/7 done

This plan came out of the 2026-08-18 evening session. Deployments are currently manual
(`fly deploy --ha=false --remote-only`). The goal: `git push origin master` (via PR) → tests
against a disposable database → automatic deploy → smoke test. A failing test blocks both the
merge and the release.

---

## Principle: the test database is ephemeral

**Owner decision, binding: the test database must be completely empty and destroyed on every
run. Nothing persists between runs.** This rules out:

- Testing against the production Supabase project (current state — every test hits live
  Supabase; tests write to `_test` tables there).
- A dedicated hosted test database cleaned up by a script — a crashed run leaves state behind,
  and "clean" would mean trusting that the previous run cleaned up. A container destroyed by
  the runner is the only guarantee that is mechanical rather than hopeful.

Consequence: CI uses a `services:` container that GitHub Actions creates empty at job start and
destroys at job end. Local development uses the same image via `docker run` with a documented
reset command. The production Supabase project is never touched by tests again.

The earlier plan drafts considered (and rejected) a `ci_runner` Postgres role in the production
project (Task 1 of draft v1) and a hosted Supabase test project with periodic truncation
(draft v2). The rejection reason for both is the same: they leave the door open to state
carrying over between runs.

---

## Current state (verified 2026-08-18)

- One Fly.io app `fpl-assistant` (region `cdg`, 512 MB, always-on). The multi-stage Dockerfile
  at the repo root builds both frontend and backend; `--remote-only` means CI needs no Docker.
- 216 backend tests, all currently hitting live Supabase (write access: `_test` tables, seeded
  with fictitious data guarded by `FictitiousTestData`). Spring context startup requires
  non-empty `OPENAI_API_KEY`/`ANTHROPIC_API_KEY` (autoconfiguration), but no test calls an LLM —
  dummy values are expected to suffice, to be verified in Task 2.
- Deployment docs say "no CI yet — deploy manually"; `docs/TODO.md` tracks "CI: every test hits
  live Supabase — needs Testcontainers fixtures or a read-only secret" as open.
- Historical note: `org.testcontainers:postgresql` was removed in Phase 3 because Boot 4.1's BOM
  does not manage its version (compileTestJava failed). This plan does **not** reintroduce
  Testcontainers — a `services:` container achieves the same isolation with zero dependency
  changes.

---

## Tasks

### Task 1 — Dependency inventory

**This task decides the seed SQL; do it before anything else.**

Go through what the test suite reads beyond data it writes itself:
- `season` table rows (which tests need them, and which seasons/values),
- any backfilled real data a test asserts against,
- schema prerequisites: does any test create a `vector` column (pgvector extension), or do the
  RAG tests mock embeddings?

**Verification:** run the full suite against a fresh empty Postgres (local container). Every
failure names a missing seed or extension. Output: an exact seed SQL file, and the answer to
the pgvector question (decides the image name in Task 2).

### Task 2 — Container-based test database in CI

GitHub Actions `services:` container:
- Image: `pgvector/pgvector:pg17` if Task 1 found vector-column tests, else `postgres:17`.
  (Supabase runs Postgres 17 + pgvector; matching both keeps the dialect honest.)
- Health options on the postgres port; the job waits for readiness.
- Step: `CREATE EXTENSION IF NOT EXISTS vector` (if needed) + seed SQL from Task 1.
- `DATABASE_URL`/`DB_USER`/`DB_PASSWORD` point at the container — plain values in the workflow
  file, not secrets (the container is unreachable from outside the job).
- Also verify in this task: the suite passes with dummy LLM keys. If any test genuinely calls
  OpenAI embeddings, real keys go to GitHub secrets instead (minimal usage, but then documented).

**Verification:** full suite green against a container that did not exist 30 seconds earlier.

### Task 3 — Local development switch

Same image locally: document a `docker run` (or a tiny script) + seed SQL in
`docs/DEVELOPMENT.md`. Update `backend/.env.test.example` to point at `localhost`. After this,
no local test run touches the production project.

**Verification:** fresh local container from scratch → suite green; no production credentials
in play.

### Task 4 — GitHub secrets

Only what the container does not provide:
- `FLY_API_TOKEN` — app-scoped, created with `fly tokens create deploy -a fpl-assistant`
  (revocable, smaller blast radius than a personal token).
- LLM keys (dummy values, unless Task 2 proved real ones needed).

**Verification:** no database credentials in GitHub at all.

### Task 5 — Workflow `.github/workflows/deploy.yml`

- Triggers: `pull_request` (container + tests, no deploy), `push: branches: [master]`
  (container + tests → deploy), `workflow_dispatch` (manual re-deploy).
- Test job: `setup-java` (temurin 25) + `gradle/actions/setup-gradle` (dependency cache),
  `setup-node` 24, `npm ci && npm run build` for the frontend (tsc + check-contrast + vite as a
  fast gate), then `./gradlew cleanTest test` with the container env.
- Deploy job (`needs: test`): `superfly/flyctl-actions/setup-flyctl`, then
  `flyctl deploy --ha=false --remote-only`, then a smoke test:
  `curl --retry 10 --retry-delay 15 --retry-all-errors https://<app>.fly.dev/actuator/health`
  (JVM cold start takes tens of seconds; the external check proves the route serves).
- `concurrency: production-deploy` with `cancel-in-progress: true` — stacked pushes deploy only
  the latest.

**Verification:** a PR with a failing test shows a red check and cannot merge (once Task 6 is
in place); a green merge deploys and the smoke test passes.

### Task 6 — Branch protection on master

GitHub Settings → Branches: require a pull request before merging, require the test status
check to pass, do not allow bypassing. Order matters: the workflow must already be on master
(Tasks 5) before the requirement is enabled, or the first protected merge has no check to
require. GitHub Free suffices for status-check requirements on a private repo.

Note: this ends direct pushes to master — the PR-trigger in Task 5 runs the same tests on the
PR, so nothing is tested twice that does not need to be.

**Verification:** direct push to master is rejected.

### Task 7 — Documentation

- `docs/DEVELOPMENT.md`: new deploy procedure (PR → merge = deploy; `workflow_dispatch` for
  emergency re-deploys; manual `fly deploy` only for exceptions), plus the local test-database
  setup from Task 3.
- `AGENTS.md` Deployment section: replace "No CI yet — deploy manually".
- `docs/TODO.md` / `docs/STATUS.md`: close the "CI: every test hits live Supabase" row, record
  the session.

**Verification:** every documented command matches the workflow as merged.

---

## Risks / open questions

| Risk | Resolution path |
|---|---|
| Suite depends on real backfilled data (Task 1 finds more than `season` rows) | Seed SQL grows; worst case a small fixture dump is checked into the repo |
| SQL that only works through Supabase's pooler (`prepareThreshold=0` was needed there) | Direct connection to the container behaves like a standard Postgres; anything failing is found in Task 1's dry run and fixed in the test, not papered over |
| Duration: first run ~10 min (gradle download), cached ~7 min; deploy + remote build ~5-6 min after merge | Acceptable; a CI-side Docker build pushing to Fly's registry would be faster but adds a second deploy path — rejected for now |
| `FLY_API_TOKEN` leak | App-scoped and revocable; cannot read database secrets. Rotate via `fly tokens revoke` |

## Order of execution

Task 1 → 2 → 3 → 4 → 5 → 6 → 7. Task 1 is the only one that can change the shape of what
follows (seed size, image name, dummy-vs-real LLM keys).
