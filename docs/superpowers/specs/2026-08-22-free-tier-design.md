# FPL Assistant — Free Tier Design Spec

**Status:** Approved — design agreed 2026-08-22, not yet implemented.

**Relationship to Phase 5:** This is a phase inserted between 5a (auth, done) and 5b (Stripe
billing, not started). It does not supersede
`docs/superpowers/specs/2026-08-15-phase5-auth-billing-design.md`; it changes two of that spec's
assumptions, and both changes are written down under *What this hands to Phase 5b* so 5b starts
from the corrected picture rather than rediscovering it.

---

## Idea

The product will be paid, with a seven-day Stripe trial. That is unchanged. But before billing
exists we want testers using it, and today the only way in is an account created
by hand in the Supabase dashboard.

This phase opens a **permanent free tier — 20 messages per rolling seven days** — reached through
a self-serve sign-up form gated by an invite code with a hard account cap. It is not a testing
phase that later retires: free stays a real tier after Stripe ships, and paid is the upgrade from
it.

## What already exists, and must not be rebuilt

Phase 5a shipped the whole metering apparatus. The free tier is mostly a configuration of it.

- `fpl_usage_event` — append-only, one row per chat turn, recorded when the LLM call starts.
- `UsageService.quotaOf` — a rolling seven-day `count(*)`, no reset job, no counter table.
- `UsageService.claimChatTurn` — the count and the insert in one transaction under a per-user
  advisory lock, so two tabs cannot both spend the last message.
- `fpl_user_profile.weekly_message_limit` — per-user override. `NULL` = the configured default,
  `-1` = unlimited, `0` = suspended.
- `ChatController` answers `429` with `Retry-After` before the SSE stream opens.
- `GET /api/usage` and the usage page with its percentage bar.

Nothing in that list changes shape here. What changes is the number the default resolves to, who
can get an account, and what the user is told about their limit.

## The plan is derived, never stored

There is no `plan` column and no subscription row in this phase. Limit resolution is:

```
weekly_message_limit override  ->  use it
otherwise                      ->  the free limit
```

which is what `UsageService.resolveLimit` already does. Phase 5b adds exactly one branch to the
`otherwise` arm — *entitling subscription -> the paid limit* — and nothing else moves.

A single-valued `Plan` enum (`FREE` today, `PAID` added in 5b) is introduced and carried on
`UsageService.Quota`, resolved in one method. It exists for one reason: free and paid tell the
user different things (below), so the display rule needs a plan to branch on, and both
`UsageController` and `QuotaExceededException` must branch on the *same* one. One seam, named now,
so 5b changes one method instead of hunting for every place that assumed everyone was free.

Two alternatives were rejected:

- **A `plan` column on `fpl_user_profile`, written by the Stripe webhook.** It would be a second
  cache of Stripe's state sitting next to `fpl_user_subscription`, which is itself a cache. Two
  caches of one truth drift, and this drift would be a billing bug.
- **Creating `fpl_user_subscription` now with a `free` status.** It builds 5b's data model with
  none of 5b's behaviour, and invents a status Stripe never emits — which 5b would then have to
  reconcile against the statuses Stripe does emit.

## Sign-up: invite code, enforced on the server

Supabase's public sign-up stays **off**. A code checked in the browser is decoration —
`supabase.auth.signUp` is a client call, and anyone can make it without our form. The gate has to
be somewhere the client cannot reach, so the backend creates the account itself.

`POST /api/auth/signup` takes `{email, password, inviteCode}`, is `permitAll` in `SecurityConfig`
(every other route keeps `denyAll`), and is added to `RateLimitFilter.RATE_LIMITED_PATHS` — it
makes an outbound call and it creates accounts, which is exactly what that list is for. Note what
that buys: the filter's 20-per-minute is a shared constant, not a number chosen for account
creation. It blunts a script; the code and the cap are the actual gate.

Two properties: `fpl.invite.code` (`INVITE_CODE`, no default — the endpoint refuses everything
when it is unset, so a missing secret closes sign-up rather than opening it) and
`fpl.invite.max-accounts` (`INVITE_MAX_ACCOUNTS`, **default 30**). Thirty is the number the cost
ceiling below is drawn against; it is a property so the owner can move it without a deploy.

1. Constant-time compare of `inviteCode` against `fpl.invite.code`.
2. **Reserve a slot, and commit it.** Under a constant-key `pg_advisory_xact_lock`, count the
   rows in `fpl_invite_redemption` against `fpl.invite.max-accounts`, refuse at the cap, and
   otherwise insert a row with no `user_id` yet. A constant key serialises every sign-up against
   every other, which would be wrong for chat and is right here: the cap is a global count and
   total volume is a few dozen.
3. Supabase Admin `POST /auth/v1/admin/users` with `{email, password, email_confirm: true}`.
4. Stamp the returned id onto the reserved row.

**The reservation commits before the remote call, and that ordering is the whole point.** One
transaction cannot span Postgres and Supabase, so the only real choice is which way the pair
fails when it comes apart. Counting after the account exists fails toward *undercounting* — an
account nobody counted, and a cap that drifts upward with every failure, silently. Reserving
first fails toward *overcounting*: a Supabase error leaves a committed row with a null `user_id`,
burning one slot. That is a visible row, releasable by hand, and it errs toward refusing one
person too many rather than letting the tier grow past its ceiling with no trace.

Two alternatives were rejected:

- **Enable Supabase sign-up and refuse un-redeemed users at `/api/**`.** Less auth code, but it
  leaves `auth.users` unbounded and invents a "signed up but not let in" state the UI would have
  to explain.
- **A Supabase `before-user-created` Auth Hook.** Native, but its configuration lives outside this
  repository and it is awkward to test.

### What the invite code is, and is not

One shared code, held as a secret, handed out by the owner, with a hard ceiling on how many
accounts it can create. If it leaks, the cap is the defence and the code is rotated;
`code_label` on the ledger records which code an account came in on, so a rotation stays legible
afterwards.

It is deliberately not a per-invite table of single-use codes. That buys attribution we do not
need at this size, and costs a generator and an admin path to mint them.

### Email enumeration is accepted here

An address that already exists answers differently from a fresh one. The alternative — a fake
success — is worse for a real person mistyping their own address, and the attacker must already
hold a valid invite code to ask the question at all. Revisit if the code ever comes off.

## Quota configuration

`fpl.quota.messages-per-week` becomes `fpl.quota.free-messages-per-week`, defaulting to **20**
from `QUOTA_FREE_MESSAGES_PER_WEEK`. One call site.

Verified 2026-08-22: `QUOTA_MESSAGES_PER_WEEK` is **not** set as a Fly secret — production runs
the compiled default. So the rename has nothing to reconcile and no deploy step of its own.

The per-user override keeps its existing precedence over the plan limit, and is how an account is
given paid-grade access before Stripe exists.

## What the user is told about their limit

Issue #11 settled that `/api/usage` returns a share and never a count, and that the `429` names no
number. **That rule now holds for paid accounts only.**

A free tier's limit is public information — it is the offer. Hiding it makes the product look
evasive about the one number a prospective user needs, and there is nothing to protect: the free
limit is on the marketing page either way. The paid limit stays the owner's business, because it
is a cost-control number that will be tuned from usage data and is nobody's entitlement to quote
back.

So:

| | Free | Paid (5b) |
|---|---|---|
| `GET /api/usage` | `used` and `limit` alongside the share | share only, as today |
| `429` body | names the limit | "You have used this week's messages." |

`unlimited` short-circuits ahead of both: an account with no limit has no count worth displaying,
so the question does not arise.

## Data model

One new table. Additive, `fpl_`-prefixed, created in `UserSchema` beside the others, RLS enabled
like its siblings.

```sql
fpl_invite_redemption (id BIGSERIAL PRIMARY KEY,
                       user_id UUID UNIQUE,          -- NULL while the slot is only reserved
                       code_label TEXT,
                       redeemed_at TIMESTAMPTZ NOT NULL DEFAULT NOW())
```

`user_id` is nullable and is not the primary key, because a row exists before there is an account
to name — see the sign-up sequence above. `UNIQUE` still holds one row per account.

No email column. `auth.users` already holds it, and a second copy is PII kept for no purpose the
`user_id` does not already serve.

## API surface

| Route | Change |
|---|---|
| `POST /api/auth/signup` | New. `permitAll`, rate limited. `201` created; `403` bad code; `409` cap reached; `422` Supabase rejected the password or the address |
| `GET /api/usage` | Gains `plan`; gains `used` and `limit` on the free plan |
| `POST /api/chat` | Unchanged, except that the `429` body names the limit on the free plan |

## Frontend surface

`SignIn` gains a create-account mode — email, password, invite code — posting to
`/api/auth/signup` with a plain `fetch` (there is no token yet), then calling
`signInWithPassword` on success so the user lands in the app rather than back at a form they just
filled in. Bad code, cap reached and a rejected password each get their own message; the existing
"public sign-up is disabled" comment goes.

No new frontend environment variable: sign-up goes through our backend, and the anon key already
in the bundle is enough for the sign-in that follows.

The usage page reads **"Free plan"** above the existing bar and states the limit and what is used
of it. No upgrade button, no "coming soon" — there is nothing to click until Stripe can take
money, and a dead control for several weeks is worse than an honest absence.

## Security

`SUPABASE_SERVICE_ROLE_KEY` bypasses row-level security entirely. It belongs in the same handling
class as the database password: set with `fly secrets`, backend only, never logged, and never in
anything the frontend build can reach.

## Cost

At the spec's measured planning value of €0.0146 per message, 20 messages a week is roughly 87 a
month, so **€1.27 per month per free user who maxes the tier**. At the default 30-account cap that is a
**€38/month ceiling**, and the ceiling is not the expectation.

## Testing

- A wrong invite code answers `403` and makes no Supabase call — the assertion is that no call
  happens, not merely that the status is right.
- At the cap, sign-up is refused and no account is created.
- The happy path makes the admin call and writes exactly one ledger row.
- `POST /api/auth/signup` is reachable without a token while every other `/api/**` route still
  answers `401`.
- The free default resolves to 20 with no profile row, and the override still wins over it.
- Free usage carries the count; an unlimited account carries none.

The Supabase admin client is stubbed the way `FplEntryLookup` already is in its tests.

## Out of scope

- Stripe, `fpl_user_subscription`, Checkout, webhooks, any upgrade affordance.
- Per-invite single-use codes, and any admin UI for minting or revoking them.
- Email verification. `email_confirm: true` marks the address confirmed without sending anything,
  which is defensible only while the invite code and the cap are the gate. **It stops being
  defensible the day the code comes off** — see *Open*.
- Moving the application off the `postgres` database role.

## What this hands to Phase 5b

Two corrections to `2026-08-15-phase5-auth-billing-design.md`, which assumed a card at signup and
no free tier:

- **A cancelled or lapsed subscriber falls back to the free tier rather than meeting a wall.**
  The `402` in that spec becomes a downgrade, not a door. That changes what the webhook does on
  `customer.subscription.deleted` and it changes the payment-problem state the frontend needs.
- **Checkout starts from an account that already exists and has no card on file.** The trial is
  still Stripe's, seven days, on a real subscription — but the hand-off is an upgrade flow for a
  signed-in user, not the signup flow that spec describes. Its abuse note also weakens: with a
  free tier, deleting an account and signing up again already yields a fresh 20 a week, so the
  card is no longer the identity anchor for everything, only for the paid tier.

## Open

- **Email verification before the invite code comes off.** A permanent free tier reached without
  a code and without a verified address is refillable by anyone willing to register a new
  address. The cap hides this for now; it is the first thing to build when the tier opens up.
  Recorded in `docs/TODO.md`.
- **Whether 20 is the right number** is a product judgement, not a derived one. It is a
  configuration property precisely so it can be moved from evidence once testers have used it.
