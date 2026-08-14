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
```

Tests hit the real Supabase database — there is no Testcontainers setup and no local Postgres, so
the env file must be loaded or every `@SpringBootTest` fails on datasource creation.

Gradle writes JUnit XML to `build/test-results/test/`. When a test fails, the exception message
lives there rather than in console output, which only prints the exception type:

```bash
grep -oE "Caused by: [^<]{0,200}" build/test-results/test/TEST-<fqcn>.xml
```

## Database

Supabase project **tapas-and-tackles** (`itmykooxrbrdbgqwsesb`, eu-west-1), shared with
`~/dev/tapas-fpl-app`. This project only reads the FPL tables; it must not alter them.

Two connection details cost time to rediscover:

- **Use the transaction pooler, not the direct host.** `db.itmykooxrbrdbgqwsesb.supabase.co`
  publishes only an AAAA record, and WSL2 generally cannot reach it. The pooler is
  `aws-1-eu-west-1.pooler.supabase.com:6543`.
- **The pooler username is `postgres.<project-ref>`**, not `postgres`. With plain `postgres` it
  fails as `FATAL: (ENOTFOUND) tenant/user ... not found`, which reads like a wrong password but
  is not. The same error appears when the project is on a different pooler — this project answers
  on `aws-1`, not `aws-0`.

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
