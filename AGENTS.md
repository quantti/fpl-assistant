# AGENTS.md

Project instructions for coding agents. Claude Code loads this via the `@AGENTS.md` import in `CLAUDE.md`.

## Project

General-purpose Fantasy Premier League (FPL) chatbot. Web chat UI backed by an LLM with three knowledge layers: static FPL rules in the system prompt, strategy docs via RAG (pgvector), and live/historical stats via tool calls against Supabase.

Auth (Supabase JWT) + Stripe billing are deferred to the last phase. Build the chatbot first.

## Architecture

- **Backend:** Spring Boot 4.1, Java 25, Spring AI 2.0.0, Gradle. Serves both REST API and the built frontend as static resources from a single Fly.io app.
- **Frontend:** Vite 8.x + React 19 + TypeScript + Mantine v9 + CSS Modules. No Tailwind. (Mantine v9 requires React ≥19.2 — React 18 will not install.)
- **DB:** Supabase PostgreSQL — shared with the `tapas-fpl-app` project (`~/dev/tapas-fpl-app`). FPL stats (players, fixtures, gw stats, ownership, xG/xA) already live there. New tables added by this project, all `fpl_`-prefixed or otherwise unambiguous because the
schema is shared: `fpl_embeddings`, `pl_match_result`, `fpl_player_price`, `fpl_daily_price`,
`fpl_user_profile`, `fpl_conversation`, `fpl_message`, `fpl_usage_event`,
`fpl_invite_redemption`, `fpl_gameweek`, `fpl_fixture`. Never take a generic
name — `CREATE TABLE IF NOT EXISTS` binds silently to tapas' table if they own it first.
- **LLM provider:** swappable via `SPRING_AI_PROVIDER` env var (`anthropic` application default, `openai` or `google-genai`); production currently uses the billed, API-key Gemini Developer API (`google-genai`), while OpenAI is used only for RAG embeddings. `OPENAI_API_KEY` is therefore required regardless of the chat setting from Phase 2 onward, and its embedding autoconfiguration activates independently of the chat provider. Current privacy/account evidence lives in `docs/PRIVACY-OPERATIONS.md`; do not infer it from provider defaults.
- **Chat transport:** `POST /api/chat` returns `text/event-stream` (SSE). Frontend reads via `fetch` + `ReadableStream`.

## Key references

- **Current status, deviations from the plan, known issues: `docs/STATUS.md`** — read this first when picking the work back up
- **Issue tracking is in Linear, team `Kari`, identifiers `KAR-n`** — that is where the current
  state of every open item lives. GitHub Issues are closed and locked: everything was migrated on
  2026-08-28, each issue carries a comment naming its `KAR-n`, and nothing new is filed there.
  Older `#n` references in code and docs point at the migrated originals; follow the comment.
- **Setup, commands, DB connection details: `docs/DEVELOPMENT.md`**
- Design spec: `docs/superpowers/specs/2026-06-27-fpl-assistant-design.md`
- Phase 1 plan: `docs/superpowers/plans/2026-06-27-phase1-core-chatbot.md` — several steps are stale; check `docs/STATUS.md` before following one
- Phase 2 (RAG) spec: `docs/superpowers/specs/2026-08-14-phase2-rag-design.md`, plan: `docs/superpowers/plans/2026-08-14-phase2-rag.md`
- Phase 3 (historical data) spec: `docs/superpowers/specs/2026-08-15-phase3-historical-data-design.md`, plan: `docs/superpowers/plans/2026-08-15-phase3-historical-data.md`
- Phase 4 (enrichment) spec: `docs/superpowers/specs/2026-08-15-phase4-enrichment-design.md`, plan: `docs/superpowers/plans/2026-08-15-phase4-enrichment.md`
- Source DB schema (tapas): `~/dev/tapas-fpl-app/backend/DB.md`
- FPL game rules: `~/dev/tapas-fpl-app/backend/FPL_RULES.md`

## Conventions

- All DB queries filter by a season resolved through `SeasonResolver`, not by `is_current` directly. A season row is created weeks before its data is synced, so `WHERE season_id = (SELECT id FROM season WHERE is_current = true)` returns nothing across the changeover. The resolver prefers the current season once it holds data and otherwise falls back to the most recent season that does. **The one deliberate exception among the tools is `HistoricalTools` (`getTeamForm`, `getHeadToHead`), which must NOT filter through `SeasonResolver`** — spanning seasons is the point, and `pl_match_result` carries `season` on every row. Do not bolt season filtering onto those tools. **On the write side, `FootballDataSyncService` is the same kind of exception:** it stamps each row with the season derived from the payload (`season.startDate` on the match itself), compared against the season the database flags `is_current`, never with the season `SeasonResolver` resolves. The resolver falls back to a completed season for as long as tapas has not synced the new one, so keying the write guard on it left the current season's results unwritten for weeks after kickoff. Do not "fix" that back onto `SeasonResolver`.
- Season data is never interchangeable, and every reply must attribute its numbers. One discipline, three schemes, one per knowledge layer:
  - **Stats tools** (`getGameweekInfo`, `getPlayerStats`, `getUpcomingFixtures`, `getPlayerExpectedStats`, `getTeamExpectedGoals`, `getDifferentialPicks`, `getCaptainCandidates`, `getFixtureDifficulty`): prefix every reply with `SeasonContext.provenance()`, which names the season and states whether it is the one being played. Fallback data must always be labelled historical — the system prompt instructs the model to attribute it by season and never call it current form. Later phases weight earlier seasons differently, and the current season by how far into it we are; that weighting depends on this provenance being explicit. One reply can carry two such lines: once the resolved season is the one being played, `getPlayerStats` shows the previous season's statistics beside it under `SeasonContext.previousSeasonProvenance(boolean)` — the prompt's early-season weighting rule needs both seasons on the card. In that dual-season reply the banner is not repeated: each block carries its own line and the surrounding text carries none.
    **The two ranking tools (`getDifferentialPicks`, `getCaptainCandidates`) blend both seasons
    per player and carry one *season* banner only:** the previous season is named on every pick
    and in the footer, not on a second season `Data source:` line, because a second season banner
    would claim every number below it is last season's and under blending none purely is. That
    banner sits between two other **source** lines, three in all since KAR-35: above it the daily
    capture's `DailyPriceSource.provenance()` for the pool — identity, club, price, ownership,
    availability, `fpl_daily_price` since KAR-32 — and below it the schedule capture's
    `ScheduleSource.provenance()` for the fixture population every horizon Score is totalled
    over. The metrics are `player_fixture_stats`, so the three lines describe three sources, not
    three seasons: historical metrics stay labelled historical even when today's price and next
    month's fixtures are the season being played. When the capture season and the metric season
    differ, one clarification sentence says the price line *and* the schedule line describe the
    season being played and the season banner covers the metrics alone. Beneath the sources a
    separate `Schedule evidence:` sentence carries the exact capture instant, the `asOf` instant
    it was judged at, the editable gameweek range with any end-of-season truncation, and the
    players the schedule alone removed. Do not fold the schedule line into the banner, and do not
    turn it into a second season banner. `ExpectedStatsTools` is the
    opposite case and does get a second line — it shows fixtures as rows, fills a short
    current-season window from last season, labels each row with its own season, and the second
    banner therefore describes a labelled block rather than the whole reply.
  - **RAG strategy tool** (`StrategyTools.searchStrategyDocs`): does NOT prefix with `SeasonContext.provenance()` — the corpus is not resolved through `SeasonResolver`, and a single banner line cannot describe a mix of season-long reference and dated timely documents. Instead every retrieved chunk carries its own attribution: document `title`, `section`, `kind` (`reference`/`timely`) and the `valid_from`/`valid_until` window, and the system prompt instructs the model to weigh `timely` content by age. This is the RAG-side counterpart of the provenance discipline, for the same reason. Do not "fix" this tool back into the banner convention.
  - **Historical tools** (`HistoricalTools.getTeamForm`, `getHeadToHead`): every match names its own season and the reply states the range covered; `getTeamForm` also reports the date of its most recent known match so a stalled sync shows up as visibly old data. Old results are evidence about tendencies, not statements about current form — a 2019/20 result must not read as recent, and the system prompt instructs the model to attribute by season and never call historical results current form.
  - **Stored-data age (KAR-9) sits beside those schemes and replaces none of them.** Season,
    stored-row write time, match date and source capture are four different facts. A reply built
    on Tapas rows adds, next to the data it describes, a `Stored-data age:` line (the `updated_at`
    of the player/fixture/gameweek rows actually shown — a record write, not a completed sync), a
    `Stored match coverage:` line (PFS kickoff range per season, stored rows only) or the fixed
    `Metric refresh:` notice. `DataAge` is the one place for that wording. **Never show
    `player_fixture_stats.updated_at` as metric freshness** — `trg_pfs_updated_at` fires on the
    DefCon-only writes described below, so the core-stat refresh time is unknown and is said to be.
    No staleness threshold: an unknown refresh is a disclosure, not an eligibility rule, and an old
    completed-season archive is not a stalled sync. `team.updated_at` dates nothing. No age line on
    daily-capture, schedule-capture, RAG or historical replies — their own schemes above stand.
- **The message quota is counted in one gameweek window, `QuotaWindows`.** The window runs from
  the last deadline at or before now to the first deadline strictly after it — strict, like
  `HorizonFixtures.derive`, because at the deadline instant the round is already locked. It is cut
  from **every captured season's** deadlines read as one timeline: the newest season alone cannot
  see the changeover, and a preseason request would then count the whole previous season against a
  user. Both ends present and naming the same season is a gameweek window at the gameweek
  allowance; a known start whose next deadline is absent or belongs to another season is one
  off-season window at its own larger allowance, a single window for the whole break that does not
  reset inside itself. **A window always knows when it began** — when every captured deadline is
  still ahead, nothing records the start, and counting from the beginning of time would charge an
  account for every message it has ever sent, so that case is the fallback rather than an
  unbounded window. **The capture's freshness is deliberately not checked**, unlike
  `HorizonFixtures`: the schedule sync refuses to write all summer, so requiring it would abolish
  the off-season window, and a deadline that moved late shifts a quota boundary by hours where a
  moved fixture makes a horizon meaningless. Only the newest season's events must be a clean 1..38
  from one capture (`ScheduleSource.assessEvents`). Without a usable capture the count falls back
  to the rolling seven days on the untouched old path — not fail-open, which a broken capture would
  turn into uncapped spend, and not fail-closed, which one missing capture would turn into an
  outage. A per-user `fpl_user_profile.weekly_message_limit` override replaces the window's own
  number whatever the window is. Nothing user-facing says "this gameweek": the round being played
  during a window is the one before it, so the refusal and the page name the deadline.
- Do NOT alter existing tapas tables. Only add new tables. **One deliberate exception, granted by
  the owner on 2026-08-26: `player_fixture_stats` carries four columns this project adds and
  fills — `defensive_contribution`, `clearances_blocks_interceptions`, `recoveries`, `tackles`.**
  FPL introduced defensive contribution for 2025/26 and tapas' schema predates it, so the values
  arrive in the `element-summary` payload tapas already reads and are dropped by its named column
  list. They belong at this table's grain (per player, per fixture) and a parallel `fpl_` table
  would duplicate the key and drift on every re-sync. `DefensiveStatsSchema` adds them
  (`ADD COLUMN IF NOT EXISTS`, nullable, no default — zero is a real reading and must stay
  distinct from "not fetched"), and `DefensiveStatsSyncService` only ever UPDATEs those four
  columns, never INSERTs a row: tapas owns the rows, this owns the columns. Neither writer can
  clear the other's values, because both write named column lists. Do not extend this exception to
  other tapas tables or other columns without the owner saying so.
- **Owned-sync write takeover authorised by the owner on 2026-09-13:** this service, not Tapas,
  will own core FPL updates. The implementation may add named-column writers for existing
  `team`, `player`, `gameweek`, `fixture` and `player_fixture_stats` rows, preserving their shared
  schema and season-qualified keys. This is a write-ownership exception, not permission to alter
  their schema or unrelated tables. Until the reviewed single-writer cutover, these new APIs stay
  inert: no automatic activation, production core writes or disabling Tapas jobs. Cutover must
  drain/disable every old core writer first and preserve Tapas' unrelated jobs/readers. The current
  DefCon-only writer rule above remains in force for the legacy path until that cutover. New
  writes validate the configured playing season against the source, never the resolver's fallback;
  do not create/change `season` rows automatically. Follow
  `docs/superpowers/plans/2026-09-13-owned-matchday-sync.md` for the staged handover and evidence.
- **Never run a session `SET` through the Supabase transaction pooler (port 6543).** The
  assistant and every ad-hoc inspection share that pooler, and in transaction pooling a session
  setting outlives the client that issued it: it stays on the shared server session and every
  later client inherits it. `SET default_transaction_read_only = on` from a "read-only" probe put
  production into read-only mode on 2026-09-16 and took the site down, because startup runs
  `CREATE EXTENSION`. Express read-only intent by writing only SELECTs; if a session-level
  setting is genuinely needed, use the direct host or the session pooler, never 6543.
- Player prices stored as integers ×10 (`now_cost=75` = £7.5m). `element_type`: 1=GK, 2=DEF, 3=MID, 4=FWD.
- External APIs (vaastav CSVs, football-data.org, FPL `bootstrap-static`) are hit only at sync time, never at chat time — everything queries Supabase.
- **Today's price and today's injury flags come from `fpl_daily_price`, not from the tapas `player` table.** That table is synced weekly by `tapas-fpl-app` and was measured 85 days stale; `fpl_daily_price` is captured daily at 02:00 UK from FPL's own API (prices change at 00:00 UK) and owes tapas nothing, so it also survives the season changeover. It carries `status`/`news` — the availability picture — and `penalties_order`/`direct_fk_order`/`corners_order` — the set-piece picture — alongside `now_cost`. Set-piece duty lives here rather than in the strategy corpus because a written list of takers is written before anyone has taken one, and the first weeks of a season revise it. Dated rows, so it answers *when* a flag appeared; it cannot be backfilled, since FPL publishes only today's state. **The daily-capture tools (`getPriceHistory`, `getPlayerAvailability`, `getFlaggedPlayers`, `getSetPieceTakers`) are a documented exception like `HistoricalTools`: they use `DailyPriceSource.provenance()` (season + capture date) instead of `SeasonContext.provenance()`, because the resolver would attribute changeover-window data to the wrong season. Do not "fix" them back onto `SeasonContext.provenance()`.** **`GameweekTools` and `FixtureTools` are the same kind of exception when a schedule capture exists: they read `fpl_gameweek`/`fpl_fixture` (FPL's own `events` and `/api/fixtures/`, captured daily at 02:10 UK) under `ScheduleSource.provenance()`, and fall back to the tapas tables under `SeasonContext.provenance()` only before the first capture. Gameweek status on the capture path is derived from `deadline_time` against the clock, never from the captured `is_current` flag.** `PickTools` (`getDifferentialPicks`, `getCaptainCandidates`) reads its candidate pool from this table too, beside `SquadTools.buildSquad`: metrics attach by element id while the capture and the resolved season are the same season, and by unique `web_name` when they are not. Note that `element_id` in this table is season-scoped (FPL renumbers every season) — every query keyed on it must also filter on `season`.
- **Ranking reads are one coherent snapshot, `RankingMetrics`.** `PickTools` and `CandidatePool`
  load the whole selected daily capture, its current/previous metric evidence and one immutable
  `ScheduleSource.View` inside a read-only `REPEATABLE_READ` transaction; do not reintroduce
  per-player SQL or read FDR/minutes from a second schedule. Selection evidence accepts only the
  exact UK capture expected at that instant (after the price/schedule cutovers); yesterday's or a
  future-dated capture is unusable. The daily capture, current metric season and schedule must name
  the same season. A ranking schedule must be one timestamp-coherent full league: 38 events, 380
  directed fixtures and 20 clubs, with unique ids/pairings and valid assigned gameweeks. Nullable
  gameweek/kickoff remains valid for genuinely unscheduled fixtures. The gameweek horizon is
  derived **once per call from that one snapshot**, on the daily capture's own season and the
  snapshot's own `asOf`; both ranking tools call the same `HorizonFixtures.derive`, so they cannot
  disagree about which fixtures a club plays.
- **The horizon is calendar gameweeks from the next editable deadline, `HorizonFixtures`.** The
  window opens at the first deadline **strictly after** `asOf` — at an exact deadline that round
  is already locked and a transfer cannot benefit from it — and covers
  `from <= gameweek < min(from + N, 39)`. Classify each fixture in order: started/finished is
  ignored whatever its timestamps; an outstanding fixture with no gameweek makes both its clubs
  unknown; an assigned fixture outside the range is ignored; in range, a missing or elapsed
  kickoff, or a kickoff at or before its own round's deadline, makes that club unknown; anything
  left counts once by id for both clubs. **A known window holding no fixtures is a real zero and
  an unknown window is not a zero at all** — never collapse the two, in a count, a score or a
  printed row. Never extend a club's window for a blank, a double, a postponement or a started
  fixture, and never replay GW38 or invent GW39: past the last deadline the answer is
  `NO_EDITABLE_GAMEWEEK`, not a forecast. Missing or out-of-range FDR does not remove a fixture;
  the pick average is n/a unless every counted fixture is rated, and the squad scores that leg at
  a labelled neutral 3. `PickTools` clamps its request to 1–5 gameweeks, `buildSquad` takes its
  own and both truncate at the season's end and disclose it. There is no bypass, for a pin, a
  budget or a relaxation.
- **Primary role eligibility is one hard current-season observed-history rule, `MinutesModel`.**
  Call it **consistent observed 60+ history**. The selected daily capture must be fresh and say
  `status = 'a'`, with a valid position and club; identity/current club must be coherent across the
  same-season sources. The latest up-to-ten completed fixtures for that current-club spell must be
  complete: at least 180 current-season minutes and 60+ in every expected fixture. Known zero-minute
  rows stay in the denominator. Previous-season minutes and blended exposure never certify the
  current role; missing/stale/pending/invalid data means abstention. A sub-60 fixture blocks primary
  selection until ten later completed club fixtures in that spell move it out; an injury return,
  new manager or convenient recent-form boundary does not reset the window. Only a verified
  different-club observation fences a new spell. Without one, enumerate from season start: a first
  PFS/daily appearance is not a registration date, and a newcomer may need ten complete fixtures.
  A short complete window may qualify after clearing the same floor, but its `N/N` support is
  labelled and remains a small sample. This is conservative history, not a
  calibrated probability or a future-start guarantee; never add a bypass or fill a list with
  ineligible names.
- **Expected ranking points are per fixture, not a whole score multiplied by mean minutes.**
  `SeasonBlend.Exposure` is an indivisible bundle of appearance, 60+, short-appearance,
  expected-minutes and realised-points rates. `ExpectedPoints.perFixture` uses `P(play)+P(60+)`,
  scales attack by expected minutes, takes the
  clean sheet **per fixture** as `exp(-xGA)` on the concession `ClubRates.projectedGoalsAgainst`
  projects for that club against that opponent at that venue (KAR-30) — conditional on 60+, `null`
  and labelled partial when no club projection exists, never the player's season-average xGC — and
  gives DefCon separate short/long exposure. Club rates are read inside the same `RankingMetrics`
  snapshot: team xG is `SUM` over the club's players per side, team xGC is `MAX` (FPL stamps the
  team figure on every player), an unpopulated side (`xGC = 0`) is excluded, clubs bridge across
  seasons by unique `short_name` the way players bridge by `web_name`, and the league mean and
  home/away factors pool both seasons without a ramp. Known zero DefCon is evidence; unavailable components print
  partial/n/a rather than zero. Pick lists blend expected and realised per fixture and then
  **total that per-fixture composite over the club's fixtures in the horizon** — that horizon
  Score is what they rank on, and average FDR stays a tie-break, never a multiplier.
  `HorizonScorer` scores the squad over the same fixture population with the same expected
  components, expected only — no realised blend, no outer minutes multiplier — and FDR scales the
  attacking term only: the clean sheet already carries the opponent, so an FDR multiplier on it
  would count the fixture twice. Appearance and DefCon are not fixture-scaled. Shared population, distinct objectives; do not merge them.
- **A named player's DefCon lives on `getPlayerExpectedStats`, as an observed record (KAR-58).**
  Each match row prints the raw count as `DefCon actions N` and the reply ends with one
  `Defensive contribution, season …` line **per season** in the window: that season's position
  threshold (`ExpectedPoints.defconThreshold`), hits over appearances with a reading, the same
  over 60+ appearances, and actions per 90. Never pool a filled window's two seasons into one
  rate, never judge a CBIT season at the CBIRT threshold, never count a NULL reading or a
  zero-minute row as a miss. The cell is deliberately **not** called `DC`: in the pick lists `DC`
  is modelled expected points per fixture, a different unit, and the prompt forbids setting one
  against the other. The prompt requires DefCon in every defender *and midfielder* comparison
  and forbids arguing it from role alone (`DefconComparisonPromptContractTest`). `getPlayerStats`
  carries none on purpose — its card is tapas `player` season totals; do not add a second home.
- Tools query Supabase via `JdbcTemplate`, annotated with Spring AI `@Tool`.
- **A player name typed by a user becomes SQL in one place, `PlayerNameSearch` (KAR-57).**
  Accent-folded through `unaccent`, tokenised, club fields may narrow but never match alone,
  exact folded `web_name` first. A club token is matched against FPL's own short name or
  three-letter code ("Man Utd", "MUN"), never a club's full name. Do not hand-write `LOWER(web_name) LIKE ?` in a new tool. The
  extension is probed at startup and the search degrades to plain `lower()` if it is missing —
  a startup statement must never be able to take the app down. The cross-season `web_name`
  bridges are not name *searches* and stay exact.
- **No `@Tool` method takes a parameter that identifies an account.** The model fills tool
  parameters from the conversation, so a `userId` (or a team id) in a tool's schema is a working
  request for someone else's data the moment a user asks for it. The user id travels from the JWT
  to the tools in Spring AI's `ToolContext` (`ChatController` → `ChatService.stream` →
  `.toolContext(...)`), which Spring AI keeps out of the generated schema; `MyTeamTools.getMyTeam`
  reads it through `ToolUser.required`. `SecurityContextHolder` does not work there — tool calls
  run on the provider's reactive threads. The same context carries the turn's `FplBudget`.
- **`/api/admin/**` is admin-only, decided on the path.** `AdminAccess` is the
  `AuthorizationManager` `SecurityConfig` attaches to that matcher; it holds the JWT `sub` ids in
  `fpl.admin.user-ids` (`ADMIN_USER_IDS`) and **an empty set refuses everyone**. A controller
  under that path carries no check of its own and takes no user id. Invite codes are operated
  through `InviteCodeAdminController` (KAR-54): a code's `state` is computed by
  `InviteCode.state`, which `refusalReason` is written on, so the admin view and the sign-up
  refusal cannot disagree. Codes are disabled, never deleted — redemption rows point at them.
- **The my-team tools (`getMyTeam`, `getMyMiniLeagues`, `getMiniLeagueOpponents`) are a third
  documented chat-time exception to "external APIs only at sync time"**, alongside
  `FplEntryLookup`: a manager's squad is per user and per gameweek, so there is nothing to
  pre-sync. The authenticated `GET /api/my-team` page endpoint reuses `MyTeamService` under the
  same on-demand exception; its account comes only from the JWT, it uses a fresh bounded
  `FplBudget`, and its response is `no-store`. It never calls the LLM or spends message quota.
  These reads also fetch `bootstrap-static` (cached app-wide for six hours) because it is the
  only source certain to be on the same season as the `element` ids in a manager's picks — FPL
  renumbers every player every season, so filling a squad from an unsynced database names
  *different players*. `getMyTeam` also reads FPL's `/event/{event}/live/` for the locked
  gameweek (`FplLivePoints`, cached app-wide per gameweek for the squad TTL, spent from the
  budget last so a refusal loses the player points and never the squad); the card dates those
  numbers by the feed's own read time, never by the squad's assembly time. Supabase adds fixture
  difficulty and xG/xA only while `SeasonResolver` names the season being played. The tools'
  provenance is two lines, not one: the squad block states
  the gameweek it was locked for and when it was fetched, and the statistics block carries
  `SeasonContext.provenance()`. Do not fold them into the single-banner convention.
- **`SquadTools.buildSquad` is a fourth documented multi-line-provenance exception**, alongside
  the my-team tools, and carries the same three source lines as the pick tools since KAR-35. Its
  candidate pool — identity, club, price, ownership, availability —
  comes from `fpl_daily_price` and carries `DailyPriceSource.provenance()`; its metrics come
  from `player_fixture_stats` and carry `SeasonContext.provenance()`; the fixtures every
  projection is summed over come from the schedule capture and carry
  `ScheduleSource.provenance()`. The three sources have
  different freshness and, during the changeover, different seasons, so a single banner would
  attribute one of them wrongly. Building the pool from the daily capture rather than the
  tapas `player` table is deliberate and is what keeps issue #50 (KAR-32) from recurring here: it also
  means the tool works while tapas has not synced the season being played. Do not fold this
  into the single-banner convention. Its metrics line is followed by the schedule source line,
  then the blend sentence, which names both seasons and the ramp, and by a `Schedule evidence:`
  sentence and a `Horizon:` line naming the editable range. The pool may buy a fit role-ineligible/BODY player for the bench,
  and a doubtful player only when an underlying record exists. A named pin requires membership of
  the fifteen, not a start: `SquadSolver.rulesOfTheGame` forces every role-ineligible or BODY
  player's `x_i` to zero in every entry path, and so does the playable-club set — a club with no
  known fixture in the horizon supplies bench cover only. This XI rule is never on the relaxation ladder: only
  template and premium defaults may give way. Unknown projections have zero optimiser weight but
  must display `n/a`/unscored, and infeasibility must name the minutes rule plus the interacting
  constraints, never imply that budget alone fixes it.
- **Cross-season weighting is one ramp in one place, `SeasonBlend`.** Ability and usable exposure
  rates blend the season being played with the previous one on a weight driven by the player's own
  qualifying matches this season (60+ minutes): 1 → 10%, 2 → 30%, 3 → 60%, 4 → 80%, 5-7 → 90%,
  8+ → 100%. Exposure rates travel as one bundle; never blend each term independently or divide
  added cross-season minutes by added fixture counts. Evidence counts used by ability/DefCon floors
  may add, but raw current-role eligibility above never does. Unknown current exposure cannot be
  borrowed from a previous season. A player with no previous-season record is ranked on this
  season alone once its floor is cleared and the reply says so. Do not reintroduce a single-season
  switch, and do not write the ramp a second time in SQL.
  Club attack/defence rates (`ClubRates`) ride the same table, keyed on the club's own completed
  current-season fixtures; league constants are pooled, not ramped.
- **Tests seed the data they assert on and never depend on production content.** The suite runs
  against a disposable container (`scripts/test-db.sh`), which holds the tapas schema
  (`scripts/dump-test-schema.sh` regenerates the dump) and nothing else. Anything reading
  tapas-owned tables seeds through `TapasSeed`, which refuses to run against the shared
  database; everything else seeds its own rows in the `_test` tables. A test that needs a whole
  synced season as its oracle is tagged `realdata` and excluded from `./gradlew test` — there is
  exactly one, and adding another should feel like a decision, not a convenience.

## Task execution

- **Split large work into bounded parts.** Do not hand one agent an entire multi-hour plan or a
  broad ticket with many independent tasks. Divide it into smaller dependency-ordered units, give
  each agent one clear unit with its own verification, and return control to the orchestrating
  agent between units. Keep sequential work sequential when agents share a working tree.

### Resource safety

Two hosts: a **WSL2 machine** with the full toolchain, and a **Hetzner VPS** that has no JDK,
Gradle, Node or Docker and therefore cannot build or test locally at all — from there, verification
is the GitHub Actions run on the pull request. Neither host's setup notes may be deleted in favour
of the other's; see `docs/DEVELOPMENT.md`.

**Always run only one subagent at a time: resources are limited.** No parallel agent calls,
concurrent agent tasks or nested delegation. Wait for the current subagent to finish before
starting another. Run one bounded agent/build/test unit at a time; never overlap builds or test
suites with subagent work.
Keep the checked-in Gradle heap/worker/context-cache limits. On memory-constrained WSL, use the
cgroup-v2 scope and host-memory stop threshold in `docs/DEVELOPMENT.md`; do not assume this Docker
supports hard memory limits. Stop only this project's idle daemons or test scopes, never another
project's processes. Verify memory headroom before a full suite. **Nothing local runs the full
suite for you** (owner decision 2026-09-19, too heavy for the cloud host): run the tests you
wrote or touched while developing, and let GitHub run the whole suite on the pull request and
again on the merge. A red PR run is a blocker, exactly as a refused push used to be.

### Owned publication evidence (not activated)

The runtime is OFF by default. STAGING must not publish core data or poison legacy reads. ACTIVE
requires explicit playing season and cutover confirmation only after every old writer drains.
`FixtureVerification` requires complete source-consistent participant histories and two independent
batches whose second start is at least ten minutes after the first end. `fpl_fixture_sync` and
`fpl_player_fixture_evidence` fence publication and mask absent optional components before SQL
aggregation through `PfsReadSource`. Physical defaults/retained values are not new observations.
Corrections revoke verification; OFF rollback must not erase actual ownership or silently accept
unverified data. Before restoring legacy writes, revoke current owner verification while preserving
evidence, or new physical writes would inherit old receipts. Whole-club comparison outages require
a squad refusal, not an alternative hand-built XI. Receipt source intervals are scoped to shown
metrics; legacy PFS core-refresh time remains unknown. See `docs/OWNED-SYNC-OPERATIONS.md`.

## Commits and pull requests

A ticket implementation is not complete while it exists only in the working tree. Finish every
implemented ticket in this order: commit the verified change, push its dedicated feature branch to
GitHub, open a pull request targeting `master`, and then review the pull request. Apply review fixes
as new commits on the same feature branch, push them, and re-review as needed. Never push the ticket
directly to `master`; the owner merges the reviewed PR.

Short. The narrative belongs in `docs/STATUS.md` and the plan's outcome blocks, not in git
metadata — nobody reads a git log for a design discussion, and a long message hides the one line
that mattered.

- **Commit subject:** one line, imperative, ~60 chars. A body only when the *why* is not obvious
  from the diff, and then 1–3 sentences. Never restate what the diff shows, never paste test
  output, never list every file touched.
- **PR title:** one line. **PR body:** 8 lines at most — what changed, why, how it was verified,
  a link to the plan if there is one, and the Linear identifier it closes (`Closes KAR-n`). No
  headings, no checklists, no findings dumps.
- **Describe the change, not the project.** Environment limits, plan status, background the
  reader already lives with ("branch protection is unavailable on this plan") belong in
  `docs/TODO.md` or `docs/STATUS.md`. A PR body that explains the repository is padding.
- **Merge:** squash; the merge message is the PR title.
- If a decision needs more room than that, it is a `docs/STATUS.md` entry — write it there and
  keep the commit pointing at it.

## Progress tracking

Plans use a single `**PROGRESS:**` line (e.g. `Task 3/14 done`), updated once per task. Do NOT use per-step `- [ ]` checkboxes — they waste tokens on every flip.

## Deployment

Single Fly.io app (region `cdg` — `mad` is deprecated and can no longer provision machines)
serving API + frontend. Frontend built in a Docker multi-stage build, copied into Spring Boot
`static/`. Secrets via `fly secrets set`. Remote: `origin` → `github.com/quantti/fpl-assistant`
(private).

> ### NEVER PUSH TO `master`
>
> `master` deploys. A push there runs `.github/workflows/deploy.yml` and a green run ships
> straight to production — no review, no second pair of eyes. **Agents push a branch and open a
> pull request. Always.** The same tests run on the PR; the owner merges.
>
> There is no branch protection to catch a mistake: GitHub refuses it for private repositories on
> the Free plan (verified 2026-08-19 — the protection and rulesets APIs both answer 403 "Upgrade
> to GitHub Pro"). The substitute is the `scripts/hooks/pre-push` hook, which refuses a master
> push that has no terminal to confirm at — which is exactly the case for an agent. It no longer
> runs the suite (2026-09-19); the pull request's GitHub run is the test gate. **Do not work
> around it: never `git push --no-verify`, never re-point `core.hooksPath`,
> never push to master "because the tests passed anyway".** If a push is refused, that is the
> intended outcome, not an obstacle to route around.

**Deploying is `.github/workflows/deploy.yml`, not a manual command.** A push to `master` runs
the suite against an ephemeral Postgres container and, if it passes, runs
`flyctl deploy --ha=false --remote-only` and smoke-tests `/actuator/health` from outside. Pull
requests run the tests only. `workflow_dispatch` re-deploys without a push. A manual
`fly deploy` is for emergencies, and is the one path that can ship a red suite.

Install the pre-push hook once per clone, on every host: `git config core.hooksPath scripts/hooks`.
It needs only bash and git, so the VPS gets the master guard too. When a push touches `frontend/`
and Node is installed it also runs `npm run lint` (ESLint) and `npm run format:check` (Biome);
CI runs both on every pull request regardless. Existing violations are baselined in
`frontend/eslint-suppressions.json` — fix new ones, never add to the baseline, and never run a
blanket `eslint --fix` (it broke the build once; see `docs/DEVELOPMENT.md`).

A manual `fly deploy` always prompts first: `.claude/settings.json` routes any Bash command
that runs one through `scripts/hooks/ask-before-deploy.sh`, whatever path or wrapper it is
invoked by. It ships the working tree untested, so it is the emergency path, never the habit.

Env files are gitignored (only `*.example` templates are tracked) and there are two:
`backend/.env.test` points at the local test container, `backend/.env.supabase` at production.
Tests must never run against Supabase — see the testing convention below.
