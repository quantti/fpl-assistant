# Development setup

Everything needed to build, test and run this project from a clean shell. Written for WSL2 /
Ubuntu with zsh, which is the environment it was set up in.

## Toolchain

| Tool | Version | Installed via |
|---|---|---|
| JDK | Temurin 25.0.4 | SDKMAN (`sdk install java 25.0.4-tem`) |
| Gradle | 9.7.0 | SDKMAN (`sdk install gradle 9.7.0`) |
| Node | 24.19.0 | pre-existing |
| Docker | 28.1.1 | pre-existing |
| Supabase CLI | 2.114.0 | pre-existing, authenticated |

The plan asks for Gradle 9.1; SDKMAN no longer offers it, and the plan's actual requirement is
9.x. `flyctl` is **not** installed — it is first needed in Task 14.

SDKMAN needs `zip` and `unzip` present before it will install, so on a bare box:

```bash
sudo apt-get install -y zip unzip
curl -s "https://get.sdkman.io?rcupdate=true" | bash
```

### SDKMAN in non-interactive shells

SDKMAN puts `java` and `gradle` on the PATH from `~/.zshrc`, which non-interactive shells do not
read. Any scripted build needs the init script sourced first, or `java: command not found`:

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
```

## Secrets

`backend/.env.test` holds the database password and the LLM API key. It is gitignored;
`backend/.env.test.example` is the committed template and documents each value.

Load it with `set -a; source`, never `export $(cat .env.test | xargs)` — the JDBC URL contains
characters that the latter mangles:

```bash
set -a; source .env.test; set +a
```

## Commands

Run from `backend/`:

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
set -a; source .env.test; set +a

./gradlew test                                    # full suite
./gradlew test --tests "*GameweekToolsTest"       # one test class
./gradlew compileJava
./gradlew bootRun                                 # serves on :8080
./gradlew bootRun --args="--ingest"               # reload the RAG corpus, then exit
```

Tests hit the real Supabase database — there is no Testcontainers setup and no local Postgres, so
the env file must be loaded or every `@SpringBootTest` fails on datasource creation. Tests are
pinned to `fpl_embeddings_test` (`src/test/resources/application.properties` activates the `test`
profile for the whole source set), so they never touch the production corpus.

## Reloading the strategy corpus

The RAG documents live in `backend/src/main/resources/docs/`. After adding or editing one:

```bash
./gradlew bootRun --args="--ingest"
```

This **deletes every row in `fpl_embeddings` and reloads from the documents on the classpath**,
then exits. It is the only supported way to update the corpus — there is no incremental path.
Every document needs YAML front matter (`title`, `kind`, `valid_from`, plus `valid_until` when
`kind: timely`); a document missing it aborts the run *before* anything is deleted, so a bad file
cannot leave the corpus empty. See the Phase 2 spec for the metadata contract.

## Historical match data (Phase 3)

`pl_match_result` (2019-20 onward) is backfilled once from vaastav and kept current by
`StartupSyncRunner`, which syncs football-data.org automatically on application startup when
the last sync is more than `fpl.historical.sync-stale-after-hours` old (default 20h) — there is
no manual command for the ongoing sync, unlike the RAG corpus.

**Backfill (one-time, or to widen the season range):**

```bash
./gradlew bootRun --args="--backfill"
```

Imports 2019-20 through 2025-26 from vaastav's public CSVs (no key needed), upserts on
`(season, home_team_code, away_team_code)` so re-running is safe, and exits when done.

**Football-data.org sync requires `FOOTBALL_DATA_API_KEY`** — free registration at
https://www.football-data.org/client/register. This project has never registered one; without
it, `StartupSyncRunner` logs and does nothing (does not fail startup). `FootballDataTeamAliases`
(football-data's team names → FPL codes) is built from the API's public documentation and has
**not been verified against a real response** — see `docs/STATUS.md` for the open item.

Gradle writes JUnit XML to `build/test-results/test/`. When a test fails, the exception message
lives there rather than in console output, which only prints the exception type:

```bash
grep -oE "Caused by: [^<]{0,200}" build/test-results/test/TEST-<fqcn>.xml
```

## Database

Supabase project **tapas-and-tackles** (`itmykooxrbrdbgqwsesb`, eu-west-1), shared with
`~/dev/tapas-fpl-app`. This project only reads the FPL tables; it must not alter them.

Connection details that cost time to rediscover:

- **Use the transaction pooler, not the direct host.** `db.itmykooxrbrdbgqwsesb.supabase.co`
  publishes only an AAAA record, and WSL2 generally cannot reach it. The pooler is
  `aws-1-eu-west-1.pooler.supabase.com:6543`.
- **The pooler username is `postgres.<project-ref>`**, not `postgres`. With plain `postgres` it
  fails as `FATAL: (ENOTFOUND) tenant/user ... not found`, which reads like a wrong password but
  is not. The same error appears when the project is on a different pooler — this project answers
  on `aws-1`, not `aws-0`.
- **`prepareThreshold=0` is required and set in `application.yml`** (Hikari
  `data-source-properties`), not in the JDBC URL: pgjdbc promotes a query to a named server-side
  prepared statement after 5 executions, and the transaction-mode pooler breaks those under
  concurrent load (`prepared statement "S_1" does not exist`). Single-threaded tests never hit
  it; concurrent chat requests in production will. It stays out of the URL because an unquoted
  `&` breaks `set -a; source .env.test`.

Neither the Supabase CLI nor `fly secrets` will reveal the database password; it comes from the
Supabase dashboard (Project Settings → Database). Resetting it there rotates the password and
breaks the deployed `tapas-fpl-backend` app until its Fly secret is updated too.

### Season data state

`season` currently holds two rows, and the one flagged current is empty:

| id | code | is_current | players | teams | fixtures | gameweeks |
|---|---|---|---|---|---|---|
| 1 | 2025-26 | false | 841 | 20 | 380 | 38 |
| 2 | 2026-27 | **true** | 0 | 0 | 0 | 0 |

Season 2026/27 starts 2026-08-21; tapas creates the season row well before syncing its data. See
`docs/STATUS.md` for how the tools handle this.

## LLM provider

`SPRING_AI_PROVIDER` selects the provider (`anthropic` or `openai`) and is currently set to
`openai` in `.env.test`. `application.yml` maps it to `spring.ai.model.chat`, which activates only
that provider's autoconfiguration — without it, the unused provider's blank API key fails context
startup.

**`OPENAI_API_KEY` is mandatory regardless of `SPRING_AI_PROVIDER`, from Phase 2 onward.** RAG
embeddings always go through OpenAI (`text-embedding-3-small`) because Anthropic has no embeddings
API. The OpenAI embedding autoconfiguration is gated by `spring.ai.model.embedding`, a separate
switch from `spring.ai.model.chat` — an Anthropic-only chat deployment still needs a populated
OpenAI key or the application fails to start.
