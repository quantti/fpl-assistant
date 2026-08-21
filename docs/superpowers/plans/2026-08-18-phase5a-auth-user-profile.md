# FPL Assistant — Phase 5a: Auth, User Profile and Server-Side History

**Status:** complete (2026-08-21). Deployed; see `docs/STATUS.md` for what was verified.

**PROGRESS:** Tasks 1–8 done — phase complete, verified against the running application. Findings in `docs/STATUS.md`.

**Spec:** `docs/superpowers/specs/2026-08-15-phase5-auth-billing-design.md` — read it fully first.
Its authorization contract (`user_id` from the JWT `sub` claim and nowhere else), its RLS finding
(`rolbypassrls = true`, so RLS is defence-in-depth only), its quota design and its measured cost
basis are binding here. The parts of that spec covering Stripe, trials and entitlement belong to
Phase 5b and are deliberately **out of scope** in this plan.

**Depends on:** `docs/superpowers/plans/2026-08-15-frontend-design-system.md` (6/6 done ✅) — the
new screens are built in that system, not against Mantine defaults.

**Recommended predecessor:** `docs/superpowers/plans/2026-08-18-cicd-ephemeral-test-db.md`. This
phase adds the first tables that hold user data and the first code where a missing `WHERE user_id
= ?` is a data leak rather than a wrong answer. A test gate that runs on every PR is worth having
before that code lands, and this phase's tests are exactly the self-seeding kind that plan's
migration is establishing. Not a hard dependency — Phase 5a can be built first and its tests
retro-fitted — but the order is cheaper the other way round.

---

## Scope: what this plan splits off from Phase 5

The original `2026-08-15-phase5-auth-billing.md` shipped authentication, billing and server-side
history as one eight-task phase, on the spec's reasoning that "none is useful alone". The owner's
decision on 2026-08-18 splits it: **billing waits.**

| In scope (5a) | Deferred (5b) |
|---|---|
| Supabase JWT authentication | Stripe Checkout session |
| `fpl_user_profile` incl. the user's FPL team id | Stripe webhook + signature verification + idempotency |
| `conversation` / `message` — server-side history | `user_subscription`, `stripe_event` tables |
| `usage_event` + rolling seven-day quota (`429`) | Entitlement check → `402` |
| Sign-in / sign-up / session / SPA routing | Subscribe hand-off and payment-problem screens |
| Conversation list, usage indicator, team-id field | |

**Consequence that must be decided before Task 8 deploys anything publicly:** without billing,
every signed-up account gets free LLM usage bounded only by the quota. The spec's card-at-signup
design was also the abuse defence — Stripe was the identity anchor that stopped
delete-and-re-register from yielding unlimited free usage. Phase 5a has no such anchor.

**Recommendation:** keep public sign-up closed for the duration of 5a — Supabase email allowlist,
or sign-up disabled with accounts created by hand — and treat the quota as a cost ceiling per
known user, not as the defence. Decide this explicitly; do not discover it from an OpenAI bill.

**No entitlement seam is built in this phase.** Phase 5b's check goes in the same place the quota
check goes (chat endpoint, before the stream opens). Writing an always-true `Entitlement`
abstraction now would be speculative structure for a single future caller.

---

## Architecture

Supabase issues JWTs; Spring validates them locally against Supabase's JWKS — no shared secret.
Authorization is enforced in application SQL, because the application's database role bypasses
RLS entirely. Usage is counted from an append-only table the user cannot influence.

**Tech:** Spring Boot 4.1, Java 25, Spring Security (OAuth2 resource server), Supabase Postgres
via `JdbcTemplate`. Frontend: the existing design system plus `@supabase/supabase-js` for the
auth client only.

Design altitude as in Phases 2–4: each task states what it must achieve and how you know it is
done. Class shapes, SQL and test layout are yours within the spec's constraints.

## Global constraints

- **`user_id` comes from the JWT `sub` claim and from nowhere else.** Never from a request body,
  a path or query parameter, or a client-controlled header. This *is* the authorization
  mechanism.
- **New tables only**, `fpl_`-prefixed, each name a configuration property pinned to a `_test`
  variant under the `test` profile — exactly as `fpl_embeddings`, `pl_match_result`,
  `fpl_player_price` and `fpl_daily_price` already are.
- **Quota values are configuration, not constants.** Changing a limit must not require a deploy.
- **The static prompt prefix stays byte-identical and first**, or OpenAI's automatic caching stops
  applying and the spec's cost model doubles. Nothing here may inject per-user text into the
  system prompt or vary tool registration order. **This includes the FPL team id** — Task 3
  stores it, and nothing in this phase feeds it to the model.
- **Tests seed their own data** and never assert on production content (the convention the CI/CD
  plan establishes). Every table this phase adds is created by this project's own startup DDL, so
  its tests are self-seeding by construction.
- Secrets reach production through `fly secrets set`, never a committed file.
- Commit per task; update `docs/STATUS.md` after every task.

---

## Tasks

### Task 1 — Prerequisites gate and JWT authentication

A gate task, as Phase 2's Task 1 was: prove the external dependencies work before building on
them, and record what was found in `docs/STATUS.md`.

Confirm and write down: the Supabase project's JWKS URI
(`${SUPABASE_URL}/auth/v1/.well-known/jwks.json`), its issuer (`${SUPABASE_URL}/auth/v1`), the
audience claim Supabase actually issues (`authenticated`), and that email sign-up is enabled.
**Fetch a real signed JWT for a test user and decode it** rather than assuming the claim values;
keep that token for the tests below.

Then add `spring-boot-starter-oauth2-resource-server`, configure the JWKS URI, and require
authentication on `/api/**`, with the actuator health endpoint and the static frontend resources
staying public. (Phase 5b will add one further exception, `/api/webhooks/stripe`, which cannot
present a user token and is authenticated by signature instead. Nothing needs building for it
now.)

Validate signature, issuer, audience and expiry. A token failing any of these is `401`.

Keep the existing per-IP `RateLimitFilter`. It guards different ground from the per-user quota and
neither supersedes the other.

**Done when:** a request with a valid token reaches the chat endpoint; requests with no token, a
malformed token, an expired token, a token from a different issuer and a token signed with the
wrong key each return `401`, each covered by a test; the JWKS is fetched once and cached rather
than per request; and the findings are in `docs/STATUS.md` the way Phase 2's prerequisites gate
recorded its probe results.

### Task 2 — Schema

Create four tables idempotently at startup, following the `HistoricalSchema` / `PriceSchema`
pattern (`@PostConstruct`, every statement `IF NOT EXISTS`). There is no migration tool in this
project and this task must not introduce one.

```
fpl_user_profile (user_id PK, fpl_team_id, weekly_message_limit, created_at, updated_at)
fpl_conversation (id, user_id, title, created_at, updated_at, archived_at)
fpl_message      (id, conversation_id -> ON DELETE CASCADE, role, content, created_at)
fpl_usage_event  (id, user_id, created_at, event_type, provider,
                  input_tokens, cached_tokens, output_tokens)
```

**Naming — do not call this table `app_user`.** The shared database already carries
`tapas-fpl-app`'s own `app_user` (its application users) and `manager` (FPL entry ids for the
teams it tracks). A `CREATE TABLE IF NOT EXISTS app_user` in this project would silently bind to
tapas' table and write our rows into it — the one failure mode "IF NOT EXISTS everywhere" cannot
protect against. `fpl_user_profile` also matches this project's existing `fpl_`-prefix convention.
The spec is explicit that Phase 5 users are unrelated to tapas' users and **must not be joined to
them**.

- `user_id` is the JWT `sub` UUID and the primary key: one profile row per user.
- Index `usage_event (user_id, created_at)` — the quota check is a range count on exactly that
  pair and runs on every chat request. Index `conversation (user_id, updated_at DESC)` for the
  list.
- **`usage_event` must have no foreign key to `conversation` and no cascade path from it.** Usage
  counted from anything a user can delete lets them refill their quota by deleting a
  conversation.
- Write the RLS policies the spec describes, and **comment in the DDL that they are
  defence-in-depth and not the enforcement mechanism**, naming the `rolbypassrls` finding, so a
  future reader cannot conclude authorization is handled.

**Done when:** all four tables exist in Supabase; a `bootRun` against production creates them and
a second run changes nothing; tests run against `_test` variants and a test asserting the
configured name ends in `_test` fails before any write; deleting a conversation removes its
messages and leaves `usage_event` untouched; and a test proves the profile table name is not
`app_user`.

**Outcome (2026-08-19):** `UserSchema` creates `fpl_user_profile`, `fpl_conversation`, `fpl_message` and
`fpl_usage_event`, each name a property pinned to a `_test` copy. `conversation.id` is a UUID rather
than a sequence: the id travels to the client, and not handing out the map is cheaper than
defending it. `fpl_usage_event` has no foreign key to `fpl_conversation`, asserted both behaviourally
(deleting a conversation takes its messages and leaves usage) and structurally (`pg_constraint`),
because a key added later would reintroduce the refill path silently.

**Deviation from the spec, deliberate: RLS is enabled with no policies.** The spec asks for
`auth.uid()`-scoped policies as defence-in-depth. Deny-all is strictly stronger, needs no
dependency on Supabase's auth schema — which the ephemeral test database does not have — and
nothing here reads these tables through PostgREST. `RowLevelSecurityTest` now covers all seven
tables this project creates.

There is no foreign key to `auth.users` either: it would require privileges on the auth schema
that the test database cannot grant, and nothing depends on the cascade until account deletion
exists.

### Task 3 — The user's FPL team id

Store the user's FPL entry id on their profile row, so later phases can answer questions about
*their* squad. This phase stores and validates it and nothing more.

- **Endpoints:** read the caller's profile, and set/update `fpl_team_id`. Both key on `sub`. The
  field is **nullable** — chat must work fully for a user who never supplies it, and sign-up must
  not be blocked by it.
- **Validation at write time** against FPL's own entry endpoint
  (`https://fantasy.premierleague.com/api/entry/{id}/`): a 404 means the id does not exist and the
  write is rejected with a clear message. This is a **documented exception** to the project rule
  that external APIs are hit only at sync time — the rule exists to keep *chat* off the network,
  and this is a user-initiated write on a different path. Bound it: a short timeout, no retries,
  and a failure of FPL's API must not be indistinguishable from an invalid id (say which
  happened).
- **The entry id is not season-scoped.** Unlike `element_id` in `fpl_daily_price`, which FPL
  renumbers every season, an entry id identifies the same manager across seasons — so no `season`
  column and no `SeasonResolver` involvement. (tapas' `manager` table is keyed `(id, season_id)`
  because it snapshots per-season *stats*, not because the id changes.)
- **Do not log the id** alongside the user id in ordinary request logging: together they link an
  account to a real person's public FPL profile. It is not a secret, but it does not belong in
  every log line either.
- **Out of scope, deliberately:** no tool reads it, no chat request is enriched with it, no squad
  data is fetched. tapas' `manager` tables only cover teams tapas tracks, so they are not a
  source for an arbitrary user's squad — that is a Phase 6 design question, not a detail to
  settle here.

**Done when:** a valid id round-trips through set → read; an invalid id is rejected without
writing; an FPL API outage produces a distinct error from "no such team"; a user with no id can
still chat; and another user's profile is unreachable by any request shape.

**Outcome (2026-08-19):** `GET /api/profile` and `PUT /api/profile/fpl-team-id`, both keyed on
the token's `sub` and on nothing else — no path or body field names a user, and
`UserProfileService` has no method that can be asked for someone else's row. 249 tests.

`FplEntryLookup` validates at write time and reports three outcomes, not two: `EXISTS`,
`NOT_FOUND` (**400** — "FPL does not know a team with id X") and `UNAVAILABLE` (**503** — "could
not reach FPL, nothing was saved"). Keeping those apart is the point: telling a user their id is
wrong while FPL is merely down sends them hunting a mistake they did not make. Verified against
the live API — `entry/1/` answers 200 and `entry/999999999/` answers 404 — and against a local
stub for the 500 and connection-refused paths.

Timeouts are 3 s connect / 5 s read with no retries, tighter than the sync clients' because a
person is waiting. An id that is not a positive number is refused without asking FPL at all.

### Task 4 — Server-side conversation history

Move history to the server. This is a **breaking change to the Phase 1 SSE contract** and both
sides change together.

`POST /api/chat` accepts a conversation id and a single new message instead of the full history.
An absent or null id creates a new conversation owned by the caller. **A conversation id belonging
to another user is `404`, not `403`** — do not confirm the existence of other users' data.

The server loads that conversation's history, applies `ChatService`'s existing window (the last
~20 messages, trimmed to open on a `user` turn — Anthropic rejects an assistant-first history, a
Phase 1 review finding), streams the answer, and persists both the user message and the completed
assistant message.

**Persisting the assistant message must survive a client disconnect mid-stream:** a user who
closes the tab has still incurred the cost and must still see the answer on return.

Add the read endpoints the frontend needs: list the caller's conversations newest-first, and fetch
one conversation's messages. Both filter by `sub`.

**Done when:** a conversation continues correctly across two separate requests; another user's
conversation id returns `404`; a disconnect mid-stream still persists the assistant message; and
the existing chat tests pass against the new request shape.

### Task 5 — Usage recording and quota enforcement

Record one `usage_event` per user turn **when the LLM call starts** — cost is incurred there, and
recording on completion makes an aborted stream free to repeat. Populate the token columns from
the provider response so the quota can later be tuned from data rather than guesswork.

Enforce the quota **before the stream opens**: once an SSE response has begun, its status is
already sent and cannot be changed. A quota failure is `429` with a `Retry-After` giving the
seconds until the oldest counted message leaves the seven-day window — not an SSE `error` event.

```sql
SELECT count(*) FROM usage_event
WHERE user_id = ? AND created_at > now() - interval '7 days';
```

Configuration: `fpl.quota.messages-per-week` (default 50) is the fallback, not the answer. The
limit differs per person, so it is resolved per user from `fpl_user_profile.weekly_message_limit`
(added in Task 2):

| Value | Meaning |
|---|---|
| `NULL` | no override — the configured default applies. What almost every row holds |
| `-1` | unlimited: skip the count entirely |
| `0` | suspended: no messages at all |
| *n* | *n* messages per rolling seven days |

A user with no profile row is the `NULL` case. Changing a limit is a statement; there is no admin
surface in this phase and does not need to be one.

The spec's second property (`trial-messages-per-week`) exists to distinguish trial from paid
users, which this phase has no concept of — **add it in 5b with the entitlement check that gives
it meaning**, not now.

Add a usage endpoint returning used, limit, and when the window next frees a message, for Task 7's
indicator.

**Done when:** the 51st message in a window returns `429` with a correct `Retry-After`; a message
falling out of the window frees exactly one slot; token counts are recorded and non-zero; the
limit changes by configuration alone; and both branches are covered by tests that do not depend on
wall-clock timing to pass.

### Task 6 — Frontend: sign-in, session, and SPA routing

Add `@supabase/supabase-js` **for the auth client only** — it issues and refreshes tokens; it must
not query the database directly, which would bypass the authorization contract the backend
establishes.

- Sign-in and sign-up screens built in the existing design system.
- The access token is attached to every `/api/**` request, refreshed before expiry, and a `401`
  returns the user to sign-in rather than showing a broken chat.
- **A forwarding controller in Spring** so client-side routes resolve to `index.html`. `STATUS.md`
  recorded this in Phase 1 as a known issue awaiting this phase: the static handler serves files,
  so any client-side route 404s without it. It must not swallow `/api/**` or actuator paths.

**Done when:** a user can sign up, sign in, reload without being logged out, and sign out; an
expired token refreshes silently mid-session; a deep link to a client route loads the application
rather than 404ing; and `/api/**` still returns JSON errors rather than `index.html`.

### Task 7 — Frontend: conversations, usage indicator, and the team-id field

- **Conversation list.** A column beside the thread above the 640px breakpoint, a drawer below it.
  The design spec's amendment is explicit that the reading measure must survive it and that the
  drawer must not reintroduce the chrome the bubble removal eliminated.
- **Usage indicator** from Task 5's endpoint, and a clear `429` state saying *when* the next
  message frees — not merely that the limit was reached.
- **Team-id field** in a profile/settings surface: optional, validated server-side (Task 3), with
  the two failure modes distinguishable in the UI ("no such team" vs "couldn't reach FPL, try
  again"). Explain what it is for, given nothing consumes it yet — an unexplained field that
  changes nothing reads as broken.

**Done when:** conversations can be created, listed, switched and reopened; the drawer works at
375px without a horizontal scrollbar and without shrinking the reading measure below the design
spec's value above the breakpoint; the usage indicator matches the backend; and `429` produces a
distinct, actionable screen rather than a generic error.

### Task 8 — End-to-end verification, deploy, and documentation

Through the real application, verify:

- Sign up, land in chat, ask a question, receive an answer.
- The conversation survives a sign-out and sign-in.
- **A second account cannot see the first account's conversations by guessing an id.**
- Saving an FPL team id persists across sessions; an invalid one is refused.
- Exhausting the quota produces `429` with a correct `Retry-After`, and the indicator agrees.
- **Confirm prompt caching is actually applied** by reading `cached_tokens` from the recorded
  usage. The spec's pricing assumes it; the measurement showed it probable but unconfirmed. If it
  is not applied, say so in `STATUS.md` and flag that the quota needs revisiting before the user
  group grows.

Settle the sign-up gate from the scope section above **before** this deploy, not after.

Set production secrets with `fly secrets set`, deploy with `fly deploy --ha=false --remote-only`
(or through the CI/CD pipeline if that plan landed first), and update `docs/STATUS.md` with what
was built, the deviations, and the open items — including the ones this phase knowingly leaves:
no account deletion, no data export, and no billing.

**Done when:** every check above passes against the deployed application and the documentation
reflects what exists.

> **Review checkpoint.** Request a full-phase review before considering Phase 5a complete.

---

## Pre-implementation verification

Each of these would change a task if it turns out otherwise:

- Supabase's JWKS endpoint is reachable and its issuer and audience claims match what Task 1
  configures. **Fetch a real token and decode it** rather than assuming.
- `auth.users` is still empty, so no user migration is needed. It held 0 rows on 2026-08-15.
- FPL's `entry/{id}/` endpoint still answers unauthenticated, and its 404 shape for an unknown id
  is distinguishable from an outage. If it now requires auth or rate-limits aggressively, Task 3's
  validation needs a different design (store unvalidated, validate lazily) — decide before
  building, not after.
- Spring AI exposes `cached_tokens` from the OpenAI response. Task 5 records it and Task 8 checks
  it; if the field is not surfaced, the column stays null rather than silently recording zero, and
  Task 8's caching confirmation needs another route.

## Risks / open questions

| Risk | Resolution path |
|---|---|
| Free LLM usage for anyone who signs up (no billing, no card anchor) | Closed sign-up / allowlist for the duration of 5a; quota is a per-user ceiling, not the defence. Decide before Task 8 |
| A missing `WHERE user_id = ?` leaks another user's conversations, and RLS will not catch it | Every query in this phase filters on `sub`; Task 8's cross-account check is the acceptance test. Land the CI gate first so a regression is caught on the PR |
| `app_user` name collision with tapas' table | Table is `fpl_user_profile`; Task 2 asserts the configured name is not `app_user` |
| FPL entry endpoint changes or rate-limits | Validation is bounded and its failure is distinguishable from an invalid id; worst case, store unvalidated and validate lazily |
| Breaking the Phase 1 SSE contract mid-phase | Task 4 changes both sides in one commit; the frontend has no other consumer |
| Personal data with no deletion path (email, team id, chat contents) | Out of scope here by decision, recorded in `docs/TODO.md` at Task 8 rather than left implicit |
| Phase 5b later needs a `trial` distinction the quota does not model | `trial-messages-per-week` is added in 5b together with the entitlement check that gives it meaning |

## Order of execution

Task 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8.

Tasks 1–2 are gates: an unverified claim shape or a table-name collision invalidates everything
after them. Task 3 is independent of 4–5 and can move if the FPL endpoint check in the
pre-implementation list turns up trouble.
