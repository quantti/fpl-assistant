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

Three env files, all gitignored, all with a committed `.example` template:

| File | Points at | Used by |
|---|---|---|
| `backend/.env.test` | the local test container (`localhost:55432`) | the test suite |
| `backend/.env.supabase` | the production Supabase project | `scripts/dump-test-schema.sh`, `bootRun --ingest` / `--backfill` / `--sync-prices` |
| `frontend/.env.local` | the Supabase publishable key | `npm run dev`, `npm run build` |

`frontend/.env.local` holds `VITE_SUPABASE_ANON_KEY` and nothing else has to be in it. The key is
safe to ship — it is in every built bundle — but it is not committed, so a frontend build without
it fails with a message rather than producing a page that cannot sign anyone in. CI reads it from
the `VITE_SUPABASE_ANON_KEY` repository secret and passes it to `flyctl deploy --build-arg`.

The split exists because tests now seed the data they assert on, which means writing to
tapas-owned tables. That is forbidden on the shared database, and `TapasSeed` refuses to run
there — so the suite must not be pointed at Supabase, and the credentials that reach it are kept
in a separate file you have to load on purpose.

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
./gradlew bootRun --args="--sync-prices"          # capture today's FPL prices, then exit
./gradlew bootRun --args="--sync-schedule"        # capture FPL gameweeks and fixtures, then exit
```

## The free tier and sign-up

Every account is on the free tier: **20 messages per rolling seven days**
(`fpl.quota.free-messages-per-week`, env `QUOTA_FREE_MESSAGES_PER_WEEK`). There is no `plan`
column — the tier is derived on every request, and Phase 5b adds the branch that asks whether a
subscription entitles an account to more.

Sign-up is `POST /api/auth/signup`, the one route under `/api/**` that needs no token. It takes
`{email, password, inviteCode}`, looks the code up in `fpl_invite_code` (case-insensitive),
reserves a place in `fpl_invite_redemption` against both the code's own `max_uses` and the
account cap `fpl.invite.max-accounts`, creates the account unconfirmed through Supabase's Admin
API, and asks GoTrue to send the confirmation mail. Supabase's own public sign-up stays **off**: a
code checked in the browser would be decoration.

Codes are rows, not a secret (since PR #52). Create one with the CLI against `.env.supabase` —
`--create-invite-code=launch:10`, `--list-invite-codes`, `--reconcile-invite-codes` — or with a
plain `INSERT`, which is all the CLI does; `docs/EMAIL-SETUP.md` 5.1 has the SQL and explains why
the CLI must not run on the production machine. The `INVITE_CODE` Fly secret still exists and is
ignored.

Production needs one secret for any of it to work, and it is not in the repository:

```bash
fly secrets set SUPABASE_SERVICE_ROLE_KEY=...   # Project Settings -> API -> service_role
```

Unset, it closes sign-up rather than opening it: the endpoint answers `503`. The account cap
defaults to 30 and moves with `fly secrets set INVITE_MAX_ACCOUNTS=n`. The domain must also be in
`CORS_ORIGINS`, or every sign-up is refused as an invalid code — `docs/EMAIL-SETUP.md` 4.1.

A failed Supabase call leaves a reserved row with a null `user_id` — one burnt place, chosen over
an account nobody counted (see `InviteLedger`). Release burnt places by hand, then bring each
code's counter back in line with its rows (what `--reconcile-invite-codes` does):

```sql
DELETE FROM fpl_invite_redemption WHERE user_id IS NULL;
UPDATE fpl_invite_code c
   SET uses = (SELECT count(*) FROM fpl_invite_redemption r WHERE r.code_id = c.id)
 WHERE c.uses <> (SELECT count(*) FROM fpl_invite_redemption r WHERE r.code_id = c.id);
```

## Per-user message limits

The weekly quota is per person, held in `fpl_user_profile.weekly_message_limit`: `NULL` means the
configured default (`fpl.quota.free-messages-per-week`), `-1` means unlimited, `0` suspends the
account, and any other number is that many messages per rolling seven days. There is no admin
screen; a limit is one statement, run with the `backend/.env.supabase` credentials:

```sql
INSERT INTO fpl_user_profile (user_id, weekly_message_limit)
VALUES ('<uuid from the JWT sub claim>', -1)
ON CONFLICT (user_id) DO UPDATE SET weekly_message_limit = EXCLUDED.weekly_message_limit,
                                    updated_at = NOW();
```

The uuid is in Supabase → Authentication → Users, or `auth.users.id`.

## Token tracking

Every chat turn writes one `fpl_usage_event` row: who, when, which provider and model, and the
token counts the provider reported. **Tokens only — no money.** A price depends on the model, the
date and whatever promotion is running, so a stored figure is wrong as soon as any of those move.
Nothing surfaces this in the UI; it is read with SQL.

`total_tokens` is a generated column — `input + cached + cache_write + output`, added up by the
database so no code path can forget it and no two callers can disagree about the sum.

```sql
-- How many tokens has one user spent in total?
SELECT sum(total_tokens) FROM fpl_usage_event WHERE user_id = '<uuid>';

-- Per user, this week, with the breakdown
SELECT user_id, count(*) AS turns, sum(total_tokens) AS total,
       sum(input_tokens) AS input, sum(cached_tokens) AS cache_reads,
       sum(cache_write_tokens) AS cache_writes, sum(output_tokens) AS output
FROM fpl_usage_event
WHERE event_type = 'chat' AND created_at > now() - interval '7 days'
GROUP BY user_id ORDER BY total DESC NULLS LAST;

-- One message at a time, newest first
SELECT created_at, model, conversation_id, total_tokens,
       input_tokens, cached_tokens, cache_write_tokens, output_tokens
FROM fpl_usage_event
WHERE user_id = '<uuid>' AND event_type = 'chat'
ORDER BY created_at DESC;

-- One conversation, message by message
SELECT u.created_at, u.total_tokens, m.role, left(m.content, 60) AS text
FROM fpl_usage_event u
JOIN fpl_message m ON m.conversation_id = u.conversation_id
WHERE u.conversation_id = '<uuid>'
ORDER BY u.created_at;
```

What the numbers mean:

- **One row per user turn, not per model call.** Spring AI reports usage cumulatively across a
  turn's tool-call round trips, so the row is the whole turn.
- **`input_tokens` is the uncached remainder** on Anthropic: cache reads and cache writes are
  counted separately, which is why the total adds all four columns. **If the provider is ever
  switched to OpenAI, check this first** — a provider that counts cached tokens inside its prompt
  total would be double-counted by `total_tokens`.
- **`NULL` means the provider reported nothing**, never zero. A cache miss really does report zero
  reads, and that is the measurement Task 8 checks. A NULL column contributes nothing to the total.
- **`conversation_id` carries no foreign key and no cascade.** Usage counted from something the
  user can delete is a quota the user can refill by deleting a conversation, so the reference is
  allowed to dangle.

## The pre-push hook

There is no branch protection on this repository. GitHub blocks it for private repos on the Free
plan — verified 2026-08-19, both the protection and the rulesets API answer
`403 Upgrade to GitHub Pro`. Going Pro or making the repo public are the only ways to get the
real thing; for now the substitute is a hook, installed once per clone:

```bash
git config core.hooksPath scripts/hooks
```

`scripts/hooks/pre-push` then does two things before any push leaves the machine:

1. **A push to `master` needs typed confirmation** (`push to master`, exactly). With no terminal
   to confirm at — an agent, a script, CI — it is refused outright. master deploys, so a push
   there is a release.
2. **The backend suite runs** against the local container, which it starts if needed
   (`scripts/test-db.sh up`), with `cleanTest` so Gradle cannot report a green it did not earn.
   A failure refuses the push.

`git push --no-verify` skips the hook entirely. That is git's escape hatch for a human who knows
what they are skipping; treat it as bypassing branch protection, because that is what it is.

## The test database

Tests run against a disposable Postgres container — the same image CI uses, created empty and
thrown away. Nothing persists between runs, so "is the database clean?" is never a question you
have to answer.

```bash
scripts/test-db.sh up       # start it and load the tapas schema
scripts/test-db.sh reset    # destroy and recreate — the guaranteed-clean path
scripts/test-db.sh down     # stop and remove
```

Three layers make up a working test database, and only the first is manual:

1. **The tapas schema.** `season`, `team`, `player`, `gameweek`, `fixture` and
   `player_fixture_stats` belong to `tapas-fpl-app` and have no DDL in this repository, so an
   empty container fails with `relation "player" does not exist`. `scripts/test-db.sh` loads
   `backend/src/test/resources/db/tapas-schema.sql` for you. That file is generated — regenerate
   it with `scripts/dump-test-schema.sh` after any tapas migration, and check it with
   `scripts/dump-test-schema.sh --check` (both need `backend/.env.supabase`).
2. **This project's own tables** (`fpl_embeddings_test`, `pl_match_result_test`,
   `fpl_player_price_test`, `fpl_daily_price_test`) are created by the application's startup DDL
   the first time a Spring context boots.
3. **The data** is seeded by the tests themselves: `TapasSeed` (four clubs, six players, four
   gameweeks, nine fixtures) for anything reading tapas tables, and per-test rows for everything
   else. No test asserts on production content any more.

`OPENAI_API_KEY` must be real: `IngestionIntegrationTest` and `StrategyToolsIntegrationTest`
embed for real and fail with `401: Incorrect API key provided` otherwise. Nothing calls a chat
model, so `ANTHROPIC_API_KEY` can be any non-empty string.

Tests tagged `realdata` are excluded from `./gradlew test`. There is one: the vaastav importer's
cross-check against tapas' fully-synced 2025-26 fixtures, which no reasonable fixture reproduces.
Run it against Supabase by hand after changing the importer:

```bash
set -a; source .env.supabase; set +a
./gradlew test -PincludeRealData --tests "*VaastavBackfill*"
```

Two ways this bites, both hit during the 2026-08-18 review:

- **The failure message hides the cause.** Without the env file, the run fails with
  `Driver org.postgresql.Driver claims to not accept jdbcUrl, ${DATABASE_URL}` — the literal
  placeholder is still in the URL, so it reads like a misconfigured datasource rather than an
  unset variable. `set -a; source .env.test` fixes it.
- **`test` can report BUILD SUCCESSFUL without running anything.** Gradle skips the task when
  its inputs are unchanged, so a run printing `5 actionable tasks: 5 up-to-date` executed zero
  tests. Force a real run with `./gradlew cleanTest test` (or `--rerun-tasks`). The full suite
  is 218 tests as of 2026-08-19.

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
https://www.football-data.org/client/register; the key lives in `.env.test` (or `fly secrets
set` in production). Without it, `StartupSyncRunner` logs and does nothing (does not fail
startup). `FootballDataTeamAliases` (football-data's team names → FPL codes) and the response
parser were verified against a real captured response on 2026-08-15 — the check-in fixture
lives in `backend/src/test/resources/football-data-fixtures/` and is proven by
`FootballDataCapturedResponseTest`.

## Price history and enrichment tools (Phase 4)

`fpl_player_price` (current season only) is populated by `StartupPriceSyncRunner`, which
on application startup snapshots `player.now_cost` (`gameweek=0`, `source=snapshot`) and pulls
vaastav's `gws/merged_gw.csv` for played GWs (`source=vaastav`), when the last snapshot is
older than `fpl.price.sync-stale-after-hours` (default 6h). Both are gated by
`SeasonContext.currentSeason()` — during the pre-season changeover the sync is a no-op, so
no fallback-season data is written. The current-season `gws/merged_gw.csv` does not exist
before GW1; `VaastavSource.fetchIfPresent` handles that as a no-op, not a failure.

No manual command is needed for the ongoing price sync. The table is keyed on the
season-scoped `element_id` (== vaastav `element` == tapas `player.id`) — the tapas `player`
table has no `code` column, and single-season storage needs no cross-season identity.

**The vaastav write is batched and incremental.** A full season's `merged_gw.csv` is about
30,000 rows (one per player per gameweek), so the sync writes in batches of 1,000 and skips
every gameweek below the highest already stored — an earlier gameweek's price is frozen and
cannot change. The highest stored gameweek is rewritten because it may have been captured
while that gameweek was still in progress. Steady-state cost is therefore roughly one
gameweek's worth of rows, not the whole season, which matters because this database is shared
with `tapas-fpl-app`.

Phase 4 also adds six enrichment tools (xG/xA/xGI plus team xGA, differential and captain
picks, fixture difficulty, price history) and two new strategy docs. Re-ingest the RAG corpus
after adding or changing strategy docs:

```bash
./gradlew bootRun --args="--ingest"
```

Gradle writes JUnit XML to `build/test-results/test/`. When a test fails, the exception message
lives there rather than in console output, which only prints the exception type:

```bash
grep -oE "Caused by: [^<]{0,200}" build/test-results/test/TEST-<fqcn>.xml
```

## My team advice

`getMyTeam`, `getMyMiniLeagues` and `getMiniLeagueOpponents` read the user's own FPL entry at
chat time — a documented exception to "external APIs only at sync time", because a squad is per
user and per gameweek. Nothing is stored: the caches are in-process TTL maps.

Two things about the numbers surprise people:

- **`bank` and `value` are in 0.1m units** (`1003` = £100.3m), and `value` excludes the bank.
- **There are no selling prices.** FPL publishes those only to the logged-in manager, so a
  player whose price has risen sells for less than the card shows. The tool says so; do not
  "fix" it by treating `now_cost` as a selling price.

The squad is the one **locked at the last deadline** — FPL serves picks only for a gameweek whose
deadline has passed, so a transfer made since is invisible. That is normal for most of the week,
not a bug.

Configuration lives under `fpl.myteam` in `application.yml`: the four FPL URLs, the cache TTLs,
`max-requests-per-turn` (15) and `max-opponents` (10). The per-turn budget is created in
`ChatService` and passed to the tools in the `ToolContext`, beside the user id.

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
