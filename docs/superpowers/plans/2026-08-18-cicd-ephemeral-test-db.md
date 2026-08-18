# FPL Assistant — CI/CD: Automatic Production Deploy + Ephemeral Test Database

**Status:** planned, not started (2026-08-18; revised 2026-08-18 after a code-level review of the
test suite)

**PROGRESS:** Task 9/11 done — 1–7, 9 and 11 complete; Tasks 8 (GitHub secrets) and 10 (branch protection) need repository access

This plan came out of the 2026-08-18 evening session. Deployments are currently manual
(`fly deploy --ha=false --remote-only`). The goal: `git push origin master` (via PR) → tests
against a disposable database → automatic deploy → smoke test. A failing test blocks both the
merge and the release.

**Revision note (2026-08-18):** the first draft treated the test-suite migration as a one-task
inventory ("Task 1 — Dependency inventory", verified by "run the suite against empty Postgres,
every failure names a missing seed"). A read of the actual tests showed that is an order of
magnitude off: the suite depends on a schema this repository does not own and on real production
*content*, so most of those failures say "rewrite this test", not "add a seed row". The
migration is now Tasks 1–5 and is the bulk of the work; the workflow itself (Tasks 6–9) is the
small part. Nothing about the ephemeral principle changed.

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

**Consequence the first draft missed:** an empty database is not merely unseeded, it is
*schemaless*. Six of the tables the suite reads belong to `tapas-fpl-app` and have no DDL in
this repository at all. See Task 1.

---

## Current state (verified against the code 2026-08-18)

- One Fly.io app `fpl-assistant` (region `cdg`, 512 MB, always-on, `min_machines_running = 1`).
  The multi-stage Dockerfile at the repo root builds both frontend and backend; `--remote-only`
  means CI needs no Docker.
- 216 tests (`build/test-results/test`, 41 result files; 219 `@Test` annotations in source).
  21 test classes are `@SpringBootTest`. Java 25 toolchain, Gradle, Spring Boot 4.1.
- `/actuator/health` is exposed (`management.endpoints.web.exposure.include: health`), so the
  post-deploy smoke test has a route.
- `frontend/package-lock.json` is committed, so `npm ci` works. `npm run build` =
  `check-contrast` + `tsc -b` + `vite build`. There are no frontend unit tests; the build is
  the only frontend gate and should be described as such.
- Deployment docs say "no CI yet — deploy manually"; `docs/TODO.md:43` tracks "CI: every test
  hits live Supabase — needs Testcontainers fixtures or a read-only secret" as open.
- Historical note: `org.testcontainers:postgresql` was removed in Phase 3 because Boot 4.1's BOM
  does not manage its version (compileTestJava failed). This plan does **not** reintroduce
  Testcontainers — a `services:` container achieves the same isolation with zero dependency
  changes.

### Findings that shape the plan

1. **The tapas schema is not in this repository.** Tests read `player`, `team`, `gameweek`,
   `fixture`, `season` and `player_fixture_stats`. This repo's schema classes
   (`DailyPriceSchema`, `HistoricalSchema`, `PriceSchema`) create only their own tables. On an
   empty container the failure is `relation "player" does not exist` — a missing table, not a
   missing row.
2. **~29 tests assert on real production content**, which no reasonable seed reproduces. See the
   table in Task 2.
3. **Dummy LLM keys do not suffice.** `IngestionIntegrationTest` and
   `StrategyToolsIntegrationTest` run real `ingest()` / `similaritySearch()` calls, and no stub
   `EmbeddingModel` bean exists anywhere in `src/test/java` (the only mock is a `VectorStore` in
   `StrategyToolsErrorTest`). Those tests make real OpenAI embedding calls. The first draft
   listed this as an open question for the CI task; it is answered here, and Task 4 decides what
   to do about it.
4. **`StartupPriceSyncRunner` fires in every Spring test context.** It is an
   `ApplicationListener<ApplicationReadyEvent>` with no profile gating, and
   `application-test.yml` gates nothing. Against an empty database `isStale()` is true, so it
   calls `PriceSyncService.sync()` — network fetch of the vaastav CSV plus a read of the tapas
   `player` table — `@Async`, racing the tests. `StartupSyncRunner` has the same shape (it
   no-ops without `FOOTBALL_DATA_API_KEY`, but implicitly).
5. **The production-isolation scaffolding becomes obsolete, and part of it breaks.**
   `IngestionIntegrationTest.java:51,81` reads the production table `fpl_embeddings`, which does
   not exist in the container. `_test` table pinning, `FictitiousTestData` and
   `refuseToRunAgainstProduction` all exist to protect a shared production database that tests
   will no longer touch.
6. **The image question is already answered.** `spring-ai-starter-vector-store-pgvector` with
   `initialize-schema: true` and `dimensions: 1536` means pgvector is required:
   `pgvector/pgvector:pg17`. No investigation needed.

---

## Tasks

### Task 1 — Schema provenance: how the tapas DDL reaches an empty database

Decide and implement how the six tapas-owned tables come into existence in a container. Two
options; **recommended: a checked-in schema dump with a regeneration script**, because
hand-written DDL drifts silently and a drift means CI is green while production SQL breaks.

- `scripts/dump-test-schema.sh`: `pg_dump --schema-only` restricted to `season`, `team`,
  `player`, `gameweek`, `fixture`, `player_fixture_stats`, writing
  `backend/src/test/resources/db/tapas-schema.sql`. Document that the dump is regenerated, never
  hand-edited.
- A drift check: the same script run with `--check` (or a documented manual step) diffs the
  committed dump against live Supabase and fails loudly on a difference. At minimum, document
  when to re-run it (after any tapas migration).
- The dump is schema-only. Data belongs to the tests (Task 3), not to the schema file.

**Verification:** a fresh `pgvector/pgvector:pg17` container plus `CREATE EXTENSION vector`, the
dump, and this project's own startup DDL yields a database where every table the suite names
exists. `\dt` lists all six tapas tables plus `fpl_embeddings_test`, `pl_match_result_test`,
`fpl_player_price_test`, `fpl_daily_price_test`.

**Outcome (2026-08-19):** `scripts/dump-test-schema.sh` +
`backend/src/test/resources/db/tapas-schema.sql` (522 lines). Verified: a fresh
`pgvector/pgvector:pg17` container loads the dump clean, and a Spring context boot against it
yields the six tapas tables plus `fpl_embeddings_test`, `pl_match_result_test`,
`fpl_player_price_test`, `fpl_daily_price_test`. Four findings, all handled in the script:

- **pg_dump needs the session pooler (5432), not the transaction pooler (6543)** the application
  uses. The direct host is IPv6-only and unreachable from WSL2. Server is Postgres 17.6, so the
  plan's `pg17` image choice matches.
- **`pg_dump` 17.11 emits a random `\restrict` token on every run** (a psql meta-command, not
  DDL). Left in, `--check` would report drift every time and stop meaning anything. Stripped,
  along with the two `-- Dumped by/from version` comments that change with the client image.
- **`-t` does not dump trigger functions.** `player_fixture_stats` has a trigger calling
  `public.update_pfs_updated_at()`, and the dump failed to load without it. The script emits
  trigger function definitions ahead of the dump body.
- **Spring AI's `PgVectorStore` creates `vector`, `hstore` and `uuid-ossp` itself** at context
  start. Task 6's explicit `CREATE EXTENSION` step is therefore belt-and-braces rather than
  strictly required — keep it, but note the CI database user must be permitted to create
  extensions either way.

All foreign keys of the six tables stay inside the set, and every column type is built-in, so
the dump has no dependency this plan has not already accounted for.

### Forward compatibility — the tables Phase 5a adds

`docs/superpowers/plans/2026-08-18-phase5a-auth-user-profile.md` adds `fpl_user_profile` (the
user's profile, including their FPL team id), `conversation`, `message` and `usage_event`. Three
consequences for this plan, none of which require work now:

- **They need no dump entry.** All four are created by this project's own `@PostConstruct` startup
  DDL, so they appear in an empty container automatically. Task 1's dump script must therefore
  stay restricted to the six tapas-owned tables — a `pg_dump` that starts sweeping up our own
  tables would freeze a schema this repository already owns in code.
- **Their tests are group (b) by construction** (Task 2's classification): they seed their own
  rows in `_test` variants. The convention Task 11 writes into `AGENTS.md` — tests seed their own
  data and never assert on production content — is what keeps that true for the tests written
  after this migration.
- **`app_user` is a name collision, not an available name.** The shared database already carries
  `tapas-fpl-app`'s `app_user` and `manager` tables. Anything named that way here would bind to
  tapas' table instead of creating one. Phase 5a uses `fpl_user_profile`; this is noted here
  because a schema dump reviewer will see `app_user` in the tapas schema and needs to know it is
  not ours.

### Task 2 — Classify all 216 tests

Three groups. The classification decides how much of Task 3 there is.

- **(a) No data dependency** — pure unit tests, plus context-only Spring tests that boot but read
  no rows. Pass unchanged.
- **(b) Self-seeding integration tests** — write and read their own rows in the `_test` tables
  this project creates at startup.
- **(c) Tests depending on data the empty database does not have** — the input to Task 3.

**Verification:** every test carries a group, and the (c) size is stated before any rewriting
starts.

**Outcome (2026-08-19):** measured, not predicted — the full suite was run against the Task 1
container (`cleanTest test --continue`, dummy LLM keys, 20 s):

```
216 tests completed, 41 failed
```

| Group | Tests | Status |
|---|---:|---|
| (a) no data dependency | 130 | pass unchanged |
| (b) self-seeding integration | 45 | **pass on an empty database already** |
| (c) needs work | 41 | Task 3 (32) + Task 4/5 (9) |

**(b) confirmed green on the empty container:** `AvailabilityToolsIntegrationTest` (13),
`PriceToolsIntegrationTest` (8), `HistoricalToolsIntegrationTest` (7),
`StartupSyncRunnerIntegrationTest` (5), `StartupPriceSyncRunnerIntegrationTest` (4),
`HistoricalSchemaTest` (3), `FootballDataSyncServiceIntegrationTest` (3),
`VaastavBackfillIntegrationTest` (3 of 4).

**(c1) — needs seeded tapas data (32 tests), Task 3:**

| Test class | Failing | What it needs |
|---|---:|---|
| `ExpectedStatsToolsIntegrationTest` | 6/6 | `player_fixture_stats` xG rows for a named player |
| `PickToolsIntegrationTest` | 6/6 | players with ownership/form/status; a non-empty result |
| `FixtureToolsTest` | 5/5 | a club with upcoming fixtures (`GW\d+:`) |
| `PlayerToolsTest` | 5/5 | a player findable by web name and by full name |
| `FixtureDifficultyToolsIntegrationTest` | 3/3 | fixtures + the season guard |
| `PriceSyncServiceIntegrationTest` | 3/8 | the tapas `player` table as a price snapshot source |
| `GameweekToolsTest` | 2/3 | gameweek rows |
| `DbConnectionTest` | 1/1 | one `season` row with `is_current = true` |
| `VaastavBackfillIntegrationTest` | 1/4 | cross-checks its import against 380 real `fixture` + `team` rows |

Every one of these fails as `EmptyResultDataAccessException` out of `SeasonResolver` (no `season`
row exists at all) or as the tool's own "data is temporarily unavailable" fallback string. A
single seeded season unblocks the resolver but satisfies almost none of the assertions — the
rewrite is per test, as planned.

`VaastavBackfillIntegrationTest` is the most surgical case: its first assertion (380 rows written
from the checked-in CSV fixture) **passes** on an empty database, and only the second — a JOIN
cross-checking the import against tapas' own `fixture`/`team` rows — fails. Seeding 380 fixtures
to keep it is out of proportion; splitting that assertion into a `@Tag("realdata")` test is the
better trade, decided in Task 3.

**(c2) — needs a real embedding path (9 tests), Tasks 4 and 5:**

| Test class | Failing | Cause |
|---|---:|---|
| `StrategyToolsIntegrationTest` | 5/5 | `401: Incorrect API key provided: dummy-key` |
| `IngestionIntegrationTest` | 4/4 | same 401, plus `SELECT count(*) FROM fpl_embeddings` — the production table, absent from the container |

This is finding 3 of this plan confirmed by execution: dummy LLM keys do not carry the suite.
Task 4 decides real key vs stub `EmbeddingModel`; the production-table read is Task 5's cleanup.

**Nothing hung on the network.** The startup runners fired (finding 4) but their tests passed and
the whole run took 20 s, so Task 5's gating is a correctness and hygiene fix, not a CI-duration
fix.

### Task 3 — Rewrite group (c) to seed its own fixtures

This is the bulk of the migration, not a footnote. Each (c) test seeds the rows its assertions
need (fictitious players, teams, fixtures, a season row) and asserts against those, instead of
against whatever production happens to contain.

**Escape hatch, if the whole group does not fit this batch:** tag the remainder
`@Tag("realdata")`, exclude it from the CI gate, and run it manually against Supabase. Visible
debt beats silent deletion — but every tagged test must appear in `docs/TODO.md` with the reason.

**Coverage note, worth stating in the commit:** this is an upgrade, not plumbing.
`FixtureDifficultyToolsIntegrationTest` currently documents that no season in the shared database
has a `finished = false` fixture, so it can only ever reach the empty branch. With seeded data
its real branch finally runs. The same applies to `PickToolsIntegrationTest`'s FDR map.

**Verification:** the rewritten tests pass against a container seeded only by themselves, and
fail if the seed is removed (proving they no longer lean on ambient data).

### Task 4 — Decide the embedding path for CI

`IngestionIntegrationTest` (4) and `StrategyToolsIntegrationTest` (6) make real OpenAI embedding
calls. Two options:

- **Real `OPENAI_API_KEY` as a GitHub secret** (recommended for now): ~9 fixture chunks per run,
  negligible cost, and the tests keep proving that ingestion works end to end against pgvector.
  Cost: every PR run makes a network call to OpenAI, and a provider outage turns the gate red.
- **A stub `EmbeddingModel` bean** in the test profile: offline, deterministic, free — but the
  tests then prove chunking and metadata handling rather than real embedding round-trips.

Whichever is chosen, record why in `application-test.yml` or the test's javadoc.

**Verification:** the RAG tests pass in a network-isolated shell (stub option) or with the secret
present and the key rotated into GitHub (real-key option).

### Task 5 — Gate the startup runners, retire the production-isolation scaffolding

Two cleanups the ephemeral database forces:

- **Gating:** add an explicit flag (e.g. `fpl.price.startup-sync`, `fpl.historical.startup-sync`)
  defaulting to true in `application.yml` and false in `application-test.yml`, so
  `StartupPriceSyncRunner` and `StartupSyncRunner` do not fire during tests. Today the price
  runner would fetch the vaastav CSV over the network and read the tapas `player` table
  `@Async` on every context start against an empty database.
- **Scaffolding:** `IngestionIntegrationTest.java:51,81` must stop reading the production
  `fpl_embeddings` table (it does not exist in the container). Then decide, per item, what to do
  with `refuseToRunAgainstProduction`, the `_test` table pinning in `application-test.yml`, and
  `FictitiousTestData`: they guard against damaging a shared production database that tests no
  longer reach. Keeping the `_test` names is harmless; keeping checks that *assert* against
  production is not. Delete deliberately and note it in the commit — this is safety machinery,
  and removing it silently would look like an accident later.

**Verification:** a full run against a container makes no outbound HTTP call other than the
embedding calls Task 4 sanctioned (check with the network blocked, or by log inspection).

**Outcome, Tasks 3–5 (2026-08-19):** the suite is green on an empty database.

```
218 tests, 0 failed          (216 before: +1 fixture-difficulty branch,
                              +1 pick-tools FDR, +1 vaastav split, −1 excluded by tag)
```

- **Task 3.** `TapasSeed` (four clubs, six players, four gameweeks, nine fixtures) is what the
  32 data-dependent tests now assert on. It reproduces the production changeover — a current
  season flagged but empty beside the completed one holding the data — so the fallback
  provenance stays under test. It refuses to run unless the database is ephemeral, checked by
  the presence of tapas' own `manager` table rather than by URL. Two tests gained coverage they
  could not have had: the fixture-difficulty tool now reaches its Average FDR branch, and the
  double-gameweek rule is finally exercised by a club that plays twice in the window.
  `VaastavBackfillIntegrationTest`'s oracle assertion is tagged `realdata` and excluded from the
  default test task (`-PincludeRealData` to run it against Supabase).
- **Task 4.** Real `OPENAI_API_KEY`, decided and verified: with it the RAG classes pass, with a
  dummy key all nine fail on `401`. The stub-`EmbeddingModel` alternative is recorded in the
  classes' javadoc as the road not taken.
- **Task 5.** Both startup runners are gated by a `startup-sync` property (off under test), as
  is the daily-price scheduler — a suite running at 02:00 UK would otherwise call FPL's API for
  real. `IngestionIntegrationTest`'s production row-count comparison is gone; the `_test`
  pinning assertion that prevents the damage stays.

### Task 6 — Container-based test database in CI

GitHub Actions `services:` container:
- Image: `pgvector/pgvector:pg17` (decided — see finding 6; Supabase runs Postgres 17 +
  pgvector, so matching both keeps the dialect honest).
- Health options on the postgres port; the job waits for readiness.
- Steps: `CREATE EXTENSION IF NOT EXISTS vector`, then the schema dump from Task 1.
- `DATABASE_URL`/`DB_USER`/`DB_PASSWORD` point at the container — plain values in the workflow
  file, not secrets (the container is unreachable from outside the job).
- `prepareThreshold=0` stays as configured; it is harmless against a direct connection.

**Verification:** full suite green against a container that did not exist 30 seconds earlier.

### Task 7 — Local development switch

Same image locally: document a `docker run` (or a tiny script) + `CREATE EXTENSION` + the schema
dump in `docs/DEVELOPMENT.md`, with a one-command reset. Update `backend/.env.test.example` to
point at `localhost` — and keep the existing Supabase pooler notes as a separate, clearly
labelled block, since production troubleshooting still needs them.

**Verification:** fresh local container from scratch → suite green; no production credentials in
play.

**Outcome, Tasks 6, 7 and 9 (2026-08-19):** `.github/workflows/deploy.yml` written (test job
with the `services:` container on pull requests and master, deploy + external smoke test on
master only, `cancel-in-progress: false` on the deploy), and `scripts/test-db.sh` gives local
development the same container with `up` / `reset` / `down`.

The env files had to split: tests seed tapas-owned tables, so the suite must not be pointed at
Supabase. `backend/.env.test` now points at the local container and `backend/.env.supabase`
carries the production credentials the schema dump and the `bootRun` data commands still need.
Verified along the documented path — `scripts/test-db.sh reset`, `source .env.test`,
`./gradlew cleanTest test` — 218 green, no production credentials in play.

### Task 8 — GitHub secrets

Only what the container does not provide:
- `FLY_API_TOKEN` — app-scoped, `fly tokens create deploy -a fpl-assistant` (revocable, smaller
  blast radius than a personal token).
- `OPENAI_API_KEY` — real, unless Task 4 chose the stub.
- `ANTHROPIC_API_KEY` — dummy value; context startup needs it non-empty, no test calls chat.
- `FOOTBALL_DATA_API_KEY` — set explicitly (empty is fine: the sync logs and no-ops), so the
  absence is a decision rather than an accident.

**Verification:** no database credentials in GitHub at all.

### Task 9 — Workflow `.github/workflows/deploy.yml`

- Triggers: `pull_request` (container + tests, no deploy), `push: branches: [master]`
  (container + tests → deploy), `workflow_dispatch` (manual re-deploy).
- Test job: `setup-java` (temurin 25) + `gradle/actions/setup-gradle` (dependency cache),
  `setup-node` 24 with `cache-dependency-path: frontend/package-lock.json`, then
  `npm ci && npm run build` in `working-directory: frontend` (check-contrast + tsc + vite —
  there are no frontend unit tests, so this build *is* the frontend gate), then
  `./gradlew test` with the container env.
- Deploy job (`needs: test`): `superfly/flyctl-actions/setup-flyctl`, then
  `flyctl deploy --ha=false --remote-only`, then a smoke test:
  `curl --retry 10 --retry-delay 15 --retry-all-errors https://<app>.fly.dev/actuator/health`
  (JVM cold start takes tens of seconds; the external check proves the route serves).
- Concurrency: `concurrency: production-deploy` with **`cancel-in-progress: false` on the deploy
  job** — a `flyctl deploy` killed mid-run can leave a half-finished release. Stacked pushes may
  cancel each other's *test* runs; they must not cancel a release in flight.
- Note in the workflow file: a deploy near 02:00 UK restarts the machine and can make the daily
  price capture miss its window; `DailyPriceFreshnessCheck` will log an ERROR the next morning.

**Verification:** a PR with a failing test shows a red check and cannot merge (once Task 10 is
in place); a green merge deploys and the smoke test passes.

### Task 10 — Branch protection on master

**Verify plan availability first.** Classic branch protection on *private* repositories has
historically required GitHub Pro/Team, and ruleset availability on Free has changed over time.
Check the repo's Settings → Rules/Branches before designing around it.

If available: require a pull request before merging, require the test status check to pass, do
not allow bypassing. Order matters — the workflow must already be on master (Task 9) before the
requirement is enabled, or the first protected merge has no check to require.

If not available on the current plan: `needs: test` in Task 9 already blocks the *release* on a
red suite, which is the property that matters most. Record the gap in `docs/TODO.md` rather than
paying for a plan upgrade by reflex.

**Verification:** direct push to master is rejected — or, in the fallback, a red test run is
shown to block the deploy job while the merge itself stays possible, and that limitation is
written down.

### Task 11 — Documentation

- `docs/DEVELOPMENT.md`: new deploy procedure (PR → merge = deploy; `workflow_dispatch` for
  emergency re-deploys; manual `fly deploy` only for exceptions), plus the local test-database
  setup from Task 7 and the schema-dump regeneration from Task 1.
- `AGENTS.md` Deployment section: replace "No CI yet — deploy manually". Add a line to the
  Conventions section stating that tests seed their own data and never depend on production
  content — this is the convention Task 3 establishes, and it is the one most likely to be
  broken by the next test written.
- `docs/TODO.md` / `docs/STATUS.md`: close the "CI: every test hits live Supabase" row
  (`docs/TODO.md:43`), open rows for anything tagged `@Tag("realdata")` in Task 3 and for a
  branch-protection gap from Task 10, record the session.

**Verification:** every documented command matches the workflow as merged.

---

## Risks / open questions

| Risk | Resolution path |
|---|---|
| Schema drift: the committed tapas dump diverges from Supabase, CI stays green while production SQL breaks | Task 1's regeneration script + a documented re-run after any tapas migration; the dump is never hand-edited |
| Task 3 is larger than one batch | `@Tag("realdata")` escape hatch: the ephemeral gate ships with groups (a)+(b)+the rewritten part of (c); the rest migrates class by class, tracked in `docs/TODO.md` |
| Rewritten tests assert on their own seed and therefore prove less about real data shapes | Accepted: they gain the branches production data can never reach (unfinished fixtures, non-empty FDR). Real-data shape questions belong to the sync tests, which seed from real captured fixtures |
| CI depends on OpenAI availability (Task 4, real-key option) | Stub `EmbeddingModel` is the documented fallback; the decision and its reason are recorded in the test profile |
| SQL that only works through Supabase's pooler (`prepareThreshold=0` was needed there) | Direct connection to the container behaves like a standard Postgres; anything failing shows up in Task 6's first full run and is fixed in the test, not papered over |
| Duration: first run ~10 min (gradle download), cached ~7 min; deploy + remote build ~5-6 min after merge | Acceptable; a CI-side Docker build pushing to Fly's registry would be faster but adds a second deploy path — rejected for now |
| `FLY_API_TOKEN` leak | App-scoped and revocable; cannot read database secrets. Rotate via `fly tokens revoke` |
| Branch protection unavailable on the current GitHub plan | `needs: test` still blocks the release; record the gap instead of upgrading by reflex (Task 10) |
| A deploy at 02:00 UK makes the daily price capture miss its window | Single always-on machine, `--ha=false`; `DailyPriceFreshnessCheck` surfaces the miss as an ERROR. Avoid deploying in that window, or accept one noisy alert |

## Order of execution

Task 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 → 9 → 10 → 11.

Tasks 1–5 are the migration and hold all the uncertainty; 6–11 are mechanical once the suite runs
green against an empty container. Task 2's classification is the one output that can still change
the size of what follows.
