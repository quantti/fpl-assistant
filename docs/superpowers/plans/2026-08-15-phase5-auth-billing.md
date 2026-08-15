# FPL Assistant — Phase 5: Auth and Billing Implementation Plan

**Spec:** `docs/superpowers/specs/2026-08-15-phase5-auth-billing-design.md` — read it fully first.
Its three corrections to the June outline, the authorization contract, the data model, the quota
design and the measured cost basis are binding.

**Depends on:** `docs/superpowers/plans/2026-08-15-frontend-design-system.md` must be complete
first. Tasks 6 and 7 add screens to the design system that plan builds; running them against the
Mantine defaults means styling every new component twice.

**PROGRESS:** Task 0/8 done

**Goal:** Turn the anonymous chatbot into a product: users sign in, pay €9.99/month after a
seven-day trial, are held to 50 messages per rolling seven days, and get their conversations back
when they return.

**Architecture:** Supabase issues JWTs; Spring validates them locally against Supabase's JWKS with
no shared secret. Authorization is enforced in application SQL, not by RLS, because the
application's database role bypasses RLS entirely. Stripe owns subscription and trial state and we
mirror it. Usage is counted from an append-only table the user cannot influence.

**Tech stack:** Spring Boot 4.1, Java 25, Spring Security (OAuth2 resource server), Stripe Java
SDK, Supabase Postgres via `JdbcTemplate`. Frontend: the design system from the preceding plan,
plus `@supabase/supabase-js` for the auth client only.

Design altitude, as Phases 2–4: each task states what it must achieve and how you know it is done.
Class shapes, SQL and test layout are yours within the spec's constraints.

## Global constraints

- **`user_id` comes from the JWT `sub` claim and from nowhere else.** Never from a request body,
  never from a path or query parameter, never from a header the client controls. This is the
  authorization mechanism — the spec establishes that RLS cannot be, because the application
  connects as `postgres` with `rolbypassrls = true`.
- **New tables only.** No tapas table is altered. Every new table's name is a configuration
  property pinned to a `_test` variant under the `test` profile, exactly as `fpl_embeddings`,
  `pl_match_result` and `fpl_player_price` already are.
- **Quota values are configuration, not constants.** Trial and paid budgets are separately
  settable and changing either must not require a deploy.
- **The static prompt prefix must stay byte-identical and first**, or OpenAI's automatic caching
  stops applying and the cost model in the spec doubles. Nothing in this phase may inject
  per-user text into the system prompt or vary tool registration order.
- Secrets reach production through `fly secrets set`, never a committed file. `.env.test` is
  gitignored; only `.env.test.example` is tracked.
- Commit per task; update `docs/STATUS.md` after every task.

---

### Task 1 — Prerequisites gate and JWT authentication

A gate task, as Phase 2's Task 1 was: prove the external dependencies work before building on
them, and record what was found.

Confirm and write down: the Supabase project's JWKS URI
(`${SUPABASE_URL}/auth/v1/.well-known/jwks.json`), its issuer (`${SUPABASE_URL}/auth/v1`), the
audience claim value Supabase issues (`authenticated`), and that email sign-up is enabled. Obtain
a real signed JWT for a test user and keep it for the tests below.

Then add `spring-boot-starter-oauth2-resource-server`, configure the JWKS URI, and require
authentication on `/api/**` with these exceptions: `/api/webhooks/stripe` (Stripe cannot present a
user token; it is authenticated by signature in Task 5) and the actuator health endpoint. Static
frontend resources stay public.

Validate signature, issuer, audience and expiry. A token failing any of these is `401`.

Keep the existing per-IP `RateLimitFilter`. It guards different ground from the per-user quota and
neither supersedes the other.

**Done when:** a request with a valid token reaches the chat endpoint; requests with no token, a
malformed token, an expired token, a token from a different issuer, and a token signed with the
wrong key each return `401` and are covered by tests; the JWKS is fetched once and cached rather
than per request; and the findings above are written into `docs/STATUS.md` the way Phase 2's
prerequisites gate recorded its probe results.

### Task 2 — Schema

Create the five tables from the spec's data model, idempotently at startup, following the
`HistoricalSchema` / `PriceSchema` pattern — `@PostConstruct`, every statement `IF NOT EXISTS`.
There is no migration tool in this project and this task must not introduce one.

```
conversation      (id, user_id, title, created_at, updated_at, archived_at)
message           (id, conversation_id -> ON DELETE CASCADE, role, content, created_at)
user_subscription (user_id PK, stripe_customer_id, stripe_subscription_id,
                   status, current_period_end, updated_at)
usage_event       (id, user_id, created_at, input_tokens, cached_tokens, output_tokens)
stripe_event      (id PK, received_at)
```

Index `usage_event (user_id, created_at)` — the quota check is a range count on exactly that pair
and runs on every chat request. Index `conversation (user_id, updated_at DESC)` for the list.

Write the RLS policies the spec describes, and **comment in the DDL that they are
defence-in-depth and not the enforcement mechanism**, naming the `rolbypassrls` finding. A future
reader must not conclude that authorization is handled.

`usage_event` must have no foreign key to `conversation` and no cascade path from it. The spec's
reasoning: usage counted from anything a user can delete lets them refill their quota by deleting
a conversation.

**Done when:** all five tables exist in Supabase; a `bootRun` against production creates them and
a second run changes nothing; tests run against `_test` variants and a test asserting the
configured name ends in `_test` fails before any write; and deleting a conversation removes its
messages while leaving `usage_event` rows untouched.

### Task 3 — Server-side conversation history

Move history to the server. This is a **breaking change to the Phase 1 SSE contract** and both
sides change together.

`POST /api/chat` accepts a conversation id and a single new message instead of the full history.
An absent or null conversation id creates a new conversation owned by the caller. A conversation
id belonging to another user is `404`, not `403` — do not confirm the existence of other users'
data.

The server loads that conversation's history, applies `ChatService`'s existing window (the last
~20 messages, trimmed to open on a `user` turn — Anthropic rejects an assistant-first history,
which was a Phase 1 review finding), streams the answer, and persists both the user message and
the completed assistant message.

Persisting the assistant message must survive a client disconnect mid-stream: a user who closes
the tab has still incurred the cost and must still see the answer on return.

Add the read endpoints the frontend needs: list the caller's conversations newest-first, and fetch
one conversation's messages. Both filter by `sub`.

**Done when:** a conversation continues correctly across two separate requests; another user's
conversation id returns `404`; a disconnect mid-stream still persists the assistant message; and
the existing chat tests pass against the new request shape.

### Task 4 — Usage recording and quota enforcement

Record one `usage_event` per user turn, **when the LLM call starts** — cost is incurred there, and
recording on completion makes an aborted stream free to repeat. Populate the token columns from
the provider response so the quota can later be tuned from data rather than guesswork.

Enforce the quota **before the stream opens**, because once an SSE response has begun its status
is already sent and cannot be changed. A quota failure is `429` with a `Retry-After` giving the
seconds until the oldest counted message leaves the seven-day window — not an SSE `error` event.

```sql
SELECT count(*) FROM usage_event
WHERE user_id = ? AND created_at > now() - interval '7 days';
```

Configuration: `fpl.quota.messages-per-week` (default 50) and
`fpl.quota.trial-messages-per-week` (default 50). Separate properties even though they start
equal, per the spec.

Add a usage endpoint returning used, limit, and when the window next frees a message, for Task 7's
indicator.

**Done when:** the 51st message in a window returns `429` with a correct `Retry-After`; a message
that falls out of the window frees exactly one slot; token counts are recorded and non-zero; the
limits change by configuration alone with no code edit; and both branches are covered by tests
that do not depend on wall-clock timing to pass.

### Task 5 — Stripe: checkout, webhook, and entitlement

Add the Stripe Java SDK. Configuration: secret key, webhook signing secret, and the price id, all
from the environment.

**Checkout:** an endpoint that creates a Stripe Checkout Session in subscription mode with a
seven-day trial and a payment method collected up front, keyed to the caller's `sub`, and returns
the session URL. The trial is Stripe's, not ours — this codebase has no `trial_ends_at` and no
expiry job.

**Webhook** at `POST /api/webhooks/stripe`, excluded from JWT authentication in Task 1 and
authenticated instead by Stripe's signature:

- **Verify the signature before parsing the body.** An unverified webhook is an unauthenticated
  write to the table that decides who has paid.
- **Be idempotent.** Stripe retries. Record the event id in `stripe_event` and drop replays; a
  redelivered event must not change state twice.
- Handle `customer.subscription.created`, `.updated`, `.deleted` and the payment-failure events,
  mirroring Stripe's status into `user_subscription`.

**Entitlement:** one function answering *is this status entitling?* — `trialing` and `active` both
are. Nothing in the codebase asks whether a trial is still running. A non-entitling status returns
`402` from the chat endpoint, before the stream opens.

**Done when:** a checkout session is created and returns a usable URL; a webhook with a bad
signature is rejected without touching the database; the same event delivered twice changes state
once; `trialing` and `active` both permit chat while `past_due` and `canceled` return `402`; and
the whole flow is exercised against Stripe's test mode, not a mock alone.

### Task 6 — Frontend: sign-in, session, and SPA routing

Add `@supabase/supabase-js` for the auth client only — it issues and refreshes tokens; it must not
be used to query the database directly, because that would bypass the authorization contract this
phase establishes in the backend.

- A sign-in and sign-up screen built in the design system from the preceding plan.
- The access token is attached to every `/api/**` request, refreshed before expiry, and a `401`
  returns the user to sign-in rather than showing a broken chat.
- A forwarding controller in Spring so client-side routes resolve to `index.html`. `STATUS.md`
  recorded this in Phase 1 as a known issue awaiting this phase: the static handler serves files,
  so any client-side route 404s without it. It must not swallow `/api/**` or actuator paths.

**Done when:** a user can sign up, sign in, reload without being logged out, and sign out; an
expired token refreshes silently mid-session; a deep link to a client route loads the application
rather than 404ing; and `/api/**` still returns JSON errors rather than `index.html`.

### Task 7 — Frontend: conversations, usage, and billing states

- **Conversation list.** A column beside the thread above the 640px breakpoint and a drawer below
  it. The spec's frontend amendment is explicit that the reading measure must survive it and that
  the drawer must not reintroduce the chrome the bubble removal eliminated.
- **Usage indicator** from Task 4's endpoint, and a clear `429` state saying when the next message
  frees rather than only that the limit was reached.
- **Billing states.** A subscribe hand-off to Stripe Checkout for a user without an entitling
  subscription, and a payment-problem state for `402` after a failed payment. Per the spec, a
  signed-in user is normally a paying user, so this is a recovery path rather than a paywall.

**Done when:** conversations can be created, listed, switched and reopened; the drawer works at
375px without a horizontal scrollbar and without shrinking the reading measure below the spec's
value above the breakpoint; the usage indicator matches the backend; and `402` and `429` each
produce a distinct, actionable screen rather than a generic error.

### Task 8 — End-to-end verification, deploy, and documentation

Through the real application, verify:

- Sign up with a card in Stripe test mode, land in chat, ask a question, receive an answer.
- The conversation survives a sign-out and sign-in.
- A second account cannot see the first account's conversations by guessing an id.
- Exhausting the quota produces `429` with a correct `Retry-After`, and the indicator agrees.
- Cancelling the subscription in Stripe produces `402` on the next message.
- **Confirm prompt caching is actually applied** by reading `cached_tokens` from the recorded
  usage. The spec's pricing assumes it; the measurement showed it probable but unconfirmed. If it
  is not applied, say so in `STATUS.md` and flag that the quota needs revisiting before the user
  group grows.

Set the production secrets with `fly secrets set`, deploy with `fly deploy --ha=false
--remote-only`, and update `docs/STATUS.md` with what was built, the deviations, and the open
items.

**Done when:** every check above passes against the deployed application and the documentation
reflects what exists.

> **Review checkpoint.** Request a full-phase review before considering Phase 5 complete.

## Pre-implementation verification

Each of these would change a task if it turns out otherwise:

- Supabase's JWKS endpoint is reachable and its issuer and audience claims match what Task 1
  configures. Fetch a real token and decode it rather than assuming the claim values.
- The Stripe Java SDK's current major version supports Checkout subscription mode with
  `trial_period_days` in the form Task 5 uses.
- `auth.users` is still empty, so no user migration is needed. It held 0 rows on 2026-08-15.
- Spring AI exposes `cached_tokens` from the OpenAI response. Task 4 records it and Task 8 checks
  it; if the field is not surfaced, Task 8's caching confirmation needs a different route and the
  column stays null rather than silently recording zero.
