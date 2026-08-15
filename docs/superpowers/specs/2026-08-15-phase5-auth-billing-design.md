# FPL Assistant — Phase 5: Auth and Billing Design Spec

**Supersedes:** Where this spec differs from the Phase 5 outline in
`docs/superpowers/specs/2026-06-27-fpl-assistant-design.md`, this document wins. That outline was
four bullets and two DDL sketches; three of its assumptions are wrong against the live system and
are corrected below.

**Status:** Approved — design agreed 2026-08-15, not yet implemented.

**Related:** `docs/superpowers/specs/2026-08-15-frontend-design-system.md` defines the visual
system these screens are built in. That spec assumes a single column and no sidebar; this phase
adds a conversation list, so it needs the amendment noted under *Frontend surface*.

---

## Idea

Phases 1–4 built a chatbot anyone can use anonymously. Phase 5 makes it a product: users sign in,
pay, and get their conversations back when they return. It is the last planned phase.

Three things ship together, because none is useful alone: authentication (Supabase JWT),
subscription billing (Stripe, card at signup, seven-day trial), and server-side conversation
history.

## What the June outline got wrong

Verified against the live database and Supabase's current documentation on 2026-08-15.

### 1. The promised RLS enforcement would do nothing

The outline says *"RLS enforced: users can only read/write their own conversations and messages."*
The application connects as:

```
current_user | rolsuper | rolbypassrls
postgres     | f        | t
```

`rolbypassrls = true`. Row-level security does not apply to this connection at all. Every policy
written for these tables would be inert for the only code path that touches them. If
`ChatService` forgets a `WHERE user_id = ?`, nothing catches it.

**The contract is therefore application-level:** `user_id` is read from the JWT's `sub` claim and
from nowhere else — never from a request body, never from a query parameter — and every query in
this phase filters on it.

RLS policies are still written, because they cost little and would protect a future path that
connects with a restricted role or goes through PostgREST. They are documented as
**defence-in-depth, not the enforcement mechanism**, so nobody later mistakes them for the thing
keeping users apart. Moving the application off the `postgres` role is real hardening, but it
would require granting every existing Phase 1–4 tool its own permissions; that is a separate
piece of work and is out of scope here.

### 2. The JWT validation method is out of date

The outline says *"Spring Boot validates JWT signature against Supabase JWT secret"* — the legacy
HS256 shared-secret scheme, where any party able to verify a token also holds enough to forge one.

Supabase's current scheme is asymmetric signing keys with a published JWKS endpoint: the private
key signs on Supabase's side, and the backend fetches the public key and verifies locally.
`spring-boot-starter-oauth2-resource-server` consumes a JWKS URI natively, so this is *less* code
than the legacy path and no shared secret is stored anywhere.

### 3. Supabase Auth is provisioned but empty

The `auth` schema exists (alongside `storage` and `realtime`), and `auth.users` holds **0 rows**.
There is no migration and no legacy user data. Note that the shared database also carries
`tapas-fpl-app`'s own `app_user` and `manager` tables; Phase 5 users are unrelated to those and
must not be joined to them.

## Authentication

`spring-boot-starter-oauth2-resource-server`, configured with Supabase's JWKS URI. Validate
signature, `iss`, `aud`, and `exp`. The `sub` claim is the user's UUID and equals
`auth.users.id`.

No user table of our own. Everything this phase stores is keyed on that UUID.

## Subscription and trial

**A payment method is collected at signup.** The seven-day trial is a Stripe trial on a real
subscription, which converts automatically when it ends — the ordinary subscription lifecycle,
not a separate free tier.

This means **the trial is not a concept this codebase models.** Stripe owns it. A subscription is
`trialing`, then `active`; both entitle the user. Our code asks one question — *is this status
entitling?* — and never *is the trial still running?* There is no `trial_ends_at` of our own and
no expiry job.

It also removes an abuse vector that a card-free trial would have required a whole table to
defend: without a card, deleting an account and signing up again with a new address yields an
unlimited supply of trials. With a card at signup, Stripe is the identity anchor.

Consequence for the frontend: **a signed-in user is a paying user.** The "signed in but not
subscribed" wall largely disappears; `402` is reached only when payment actually fails
(`past_due`, `canceled`). What the UI needs is a payment-problem state, not a paywall.

### Stripe integration

- **Checkout Session** for signup, with a seven-day trial period on the subscription.
- **Webhook** (`POST /api/webhooks/stripe`) for `customer.subscription.created/updated/deleted`
  and the payment-failure events, mirroring Stripe's status into `user_subscription`.
- The webhook **verifies the Stripe signature** and is **idempotent**: Stripe retries, and a
  redelivered event must not change state twice. Event ids are recorded in `stripe_event` and
  replays are dropped.
- Stripe is the source of truth. `user_subscription` is a cache of it, never authored locally.

## Usage limits

**One limit: a rolling seven-day message cap.** No short burst window.

The reason is specific to this product: usage clusters into the hours before a gameweek deadline,
when a manager is actually making decisions. A short rolling window — five hours, say — would
throttle users at precisely the moment the product is worth paying for. A seven-day window
absorbs a deadline session and refills gradually.

```sql
SELECT count(*) FROM usage_event
WHERE user_id = ? AND created_at > now() - interval '7 days';
```

One count, one index on `(user_id, created_at)`. No counter table, no reset job, no cron.

A usage event is recorded **when the LLM call starts**, because that is where cost is incurred —
recording on completion would make an aborted stream free to repeat.

### Why usage is not counted from `message`

`message` cascades from `conversation`. If usage were counted there, deleting a conversation
would erase the user's usage history and refill their quota. Billing-critical counting cannot
read a table the user controls, so `usage_event` is append-only and untouched by any user action.
It also gives a place to record tokens and cost later without changing `message`.

### Cost basis

Measured 2026-08-15: the system prompt is ~1,548 tokens and the thirteen tool definitions ~1,777,
so **every LLM call carries ~3,300 tokens of fixed overhead** before any conversation history. A
user turn is typically two calls — one to choose tools, one to answer with their results — so
roughly 10,000–20,000 input tokens and ~1,000 output per message.

Starting budgets, **to be confirmed by measuring real cost against the configured provider before
a price is set**: 60 messages per rolling seven days on trial, 500 on a paid subscription.

## Data model

Additive only; no tapas table is touched.

```sql
conversation      (id, user_id, title, created_at, updated_at, archived_at)
message           (id, conversation_id -> ON DELETE CASCADE, role, content, created_at)
user_subscription (user_id PK, stripe_customer_id, stripe_subscription_id,
                   status, current_period_end, updated_at)
usage_event       (id, user_id, created_at)      -- append-only, never user-deletable
stripe_event      (id PK, received_at)           -- webhook idempotency
```

`archived_at` on `conversation` rather than deletion: it keeps the "start fresh" action from
being a data-loss action, and keeps history intact for support.

## Chat endpoint changes

**This is a breaking change to the Phase 1 SSE contract.** The client currently sends the full
message history on every request. It will instead send a conversation id and the new message,
and the server loads history from the database.

`ChatService`'s existing history handling still applies to the loaded history: the last ~20
messages, trimmed to open on a `user` turn (Anthropic rejects an assistant-first history — a
Phase 1 review finding).

**Gating happens before the stream opens**, and answers with an ordinary HTTP status. Once an SSE
response has begun, the status is already sent and cannot be changed, so a quota failure must
never be delivered as an SSE `error` event:

| Status | Meaning |
|---|---|
| `401` | missing, malformed, or expired token |
| `402` | subscription not entitling (`past_due`, `canceled`) |
| `429` | seven-day quota exhausted; `Retry-After` gives the seconds until the oldest counted message leaves the window |

The existing per-IP `RateLimitFilter` stays as coarse protection for unauthenticated routes. It
does not supersede the per-user quota and the per-user quota does not supersede it; they guard
different things. Note its existing constraint: it is in-memory and assumes a single machine,
which is why deploys use `--ha=false`.

## Frontend surface

New: a sign-in screen, the Stripe Checkout hand-off, a conversation list (a drawer on mobile), a
usage indicator, a payment-problem state, and token refresh handling.

Two known consequences:

- **The frontend design spec needs an amendment.** It specifies a single centred column and
  explicitly no sidebar; a conversation list is new component work in that visual system.
- **SPA deep links will 404.** `STATUS.md` recorded this in Phase 1 as a known issue awaiting
  Phase 5: the Spring Boot static handler serves files, so any client-side route needs a
  forwarding controller.

## Out of scope

- Squad import (`getUserSquad`) — the system prompt and Phase 2 RAG wording already accommodate
  a future squad-aware tool, but nothing is built here.
- Moving the application off the `postgres` database role (see *RLS* above).
- Teams, sharing, or multi-user accounts.
- Any change to tools, prompts, RAG, or sync behaviour.

## Open

Only the budget numbers, and they are blocked on measuring real per-message cost against the
configured provider rather than on any design question.
