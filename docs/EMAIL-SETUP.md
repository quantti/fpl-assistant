# Email verification — what is left to configure

The code for email verification and invite codes is finished and on the branch
`invite-codes-and-email-verification`. **It cannot work in production until the Supabase project
is configured, and none of that configuration is in this repository** — it is dashboard settings
and a mail provider. This file is the runbook for that, written 2026-08-26.

Nothing here is urgent to the running app: sign-up is closed either way, because public sign-up is
off and the invite-code table is empty until a code is issued.

## Where this stands

| | State |
|---|---|
| Backend, frontend, tests | done — 435 tests green |
| Public sign-up in Supabase | **turned off** 2026-08-26 (was open; the anon key could create accounts with no code) |
| Custom SMTP | **not configured** — this is the blocker |
| Site URL / redirect URLs | **unverified** — likely still Supabase's `http://localhost:3000` default |
| Email rate limit | **not raised** — a probe already hit `429 over_email_send_rate_limit` |
| Invite codes issued | none — the table is empty, so sign-up refuses everyone |
| Deployed | no |

## The blocker: there is no sending domain

SPF and DKIM records live in DNS on the domain mail is sent *from*. The app answers on
`fpl-assistant.fly.dev` and nothing else (`flyctl certs list -a fpl-assistant` is empty), and a
`.fly.dev` subdomain is not yours to add TXT records to. **So no provider can send as
`noreply@fpl-assistant.fly.dev`.** Every option below is a way around that.

| Option | Works today | Who receives mail | Verdict |
|---|---|---|---|
| **A.** Own domain + Resend/Postmark/SES | needs a domain first | everyone | the production answer |
| **B.** Gmail SMTP + app password | yes | everyone, ~500/day | enough for the test round |
| **C.** Resend's `onboarding@resend.dev` | yes | **only the Resend account owner** | useless for testers |

For a first round of roughly thirty testers, **B** unblocks today and **A** is what to move to
before anyone pays. C is listed only so it is not mistaken for a shortcut: Resend refuses to
deliver from that sender to anyone but you.

## Option B, step by step

**1. Google account.** Security → 2-Step Verification **on** (App Passwords do not exist without
it). Then App passwords → create one, name it `supabase`. Sixteen characters, shown once.

**2. Supabase → Authentication → Emails → SMTP Settings**

```
Enable Custom SMTP    yes
Sender email          <you>@gmail.com     ← must match the SMTP username; Gmail rewrites anything else
Sender name           FPL Assistant
Host                  smtp.gmail.com
Port                  465
Username              <you>@gmail.com
Password              <the 16-character app password, not the Google account password>
```

**3. Supabase → Authentication → Rate Limits.** Raise *"Rate limit for sending emails"*. The
default is a few an hour and it is what refused a probe on 2026-08-26 with
`429 over_email_send_rate_limit`. Thirty an hour covers a test round.

## Do this whatever option is chosen

**Supabase → Authentication → URL Configuration**

```
Site URL          https://fpl-assistant.fly.dev
Redirect URLs     https://fpl-assistant.fly.dev/**
                  http://localhost:5173/**
```

If Site URL is still `http://localhost:3000` — Supabase's default — **every confirmation link sends
the tester to a blank page.** This is the single most likely reason for the flow to fail, and it has
nothing to do with SMTP.

**Supabase → Authentication → Email Templates → Confirm signup.** The body must contain
`{{ .ConfirmationURL }}`.

The frontend side is already handled: `detectSessionInUrl` is set explicitly in
`frontend/src/auth/supabaseClient.ts`, which is what turns the tokens in the returned URL fragment
into a session.

## Then: deploy, issue a code, test

```bash
# after the branch merges, the workflow deploys; otherwise
fly deploy -a fpl-assistant

# issue a code — the table is empty, so nobody can sign up until this runs
fly ssh console -a fpl-assistant -C "java -jar /app/app.jar --create-invite-code=testi:10"
fly ssh console -a fpl-assistant -C "java -jar /app/app.jar --list-invite-codes"
```

Expected flow: the form says *"Check your email"* → the message arrives → the link lands on
`fpl-assistant.fly.dev` → you are signed in. Signing in before following the link gives
*"Confirm your email address first"*.

## Why the code looks the way it does

Four GoTrue endpoints were tried against the live project rather than reasoned about, because an
earlier assumption about this had already proved wrong:

| Endpoint | Creates | Sends | With public sign-up off |
|---|---|---|---|
| `/auth/v1/signup` | yes | yes | **refused — the service role is not exempt** |
| `/auth/v1/admin/users` with `email_confirm: false` | yes | no | works |
| `/auth/v1/admin/generate_link` | yes | no, returns a link | works |
| `/auth/v1/resend` | no | yes | works |

The first row is why sign-up is two calls: create unconfirmed, then ask GoTrue to send. Public
sign-up cannot be turned back on to use the one-call path, because the anon key ships in the
frontend bundle and would let anyone create an account with no invite code at all.

Also learned along the way: Supabase rejects `@example.com` as an invalid address, so a throwaway
probe needs a real-looking domain.

## Invite codes, in one paragraph

Codes are rows in `fpl_invite_code`, not one shared secret. Each carries its own ceiling
(`max_uses`), its own worth (`benefit_kind` / `benefit_value`), a validity window and an `active`
flag, so one code can be worth ten places, another a hundred, and another half off a subscription —
and one can run out or be withdrawn without touching any other. Discounts are recorded but do
nothing until Stripe exists (#43). The account cap `INVITE_MAX_ACCOUNTS` (30) still applies across
every code as a cost ceiling: **a code worth 100 places stops at 30 until that number is raised.**

```bash
--create-invite-code=launch:10
--create-invite-code=podcast:100:discount_percent:50
--create-invite-code=friends:unlimited
--list-invite-codes
--reconcile-invite-codes   # after releasing a place by hand
```

Codes are stored in plain text and matched case-insensitively: an invite code is a promotion code,
read aloud and typed by hand, and the owner has to know which is which. What protects the tier is
the ceiling on each code and the account cap behind it.
