# Email verification on a real domain — the runbook

The code for email verification and invite codes is finished, merged and **deployed**. What is
left is configuration that does not live in this repository: DNS, a mail provider, and Supabase
dashboard settings. This file is the runbook for that.

Rewritten 2026-08-28 on the owner's decision to buy a domain. The previous version was written
around *not* having one, and most of its difficulty came from that; with a domain the awkward
options disappear and this becomes a sequence rather than a compromise.

## The domain: `fpl-sidekick.com`

Bought 2026-08-28, registered at Namecheap. Shell blocks below set it once:

```bash
DOMAIN=fpl-sidekick.com
```

**Its DNS as it stands today**, read on 2026-08-28 — this is the starting point, not a blank slate:

```
A       @            192.64.119.148                                   ← Namecheap parking page
AAAA    @            (none)
NS      @            dns1/dns2.registrar-servers.com                  ← Namecheap DNS
MX      @            eforward1-5.registrar-servers.com                ← Namecheap email forwarding
TXT     @            "v=spf1 include:spf.efwd.registrar-servers.com ~all"
TXT     _dmarc       (none)
```

`https://fpl-sidekick.com` does not answer. Nothing is wrong with that — it is a parked domain.

**Two things follow, and one of them is a trap.**

**There is already an SPF record on the apex.** A domain may have exactly one: two SPF TXT records
make the domain fail SPF outright, which is worse than having none. So the mail provider's
`include:` must be *merged into* the existing record rather than added beside it — unless you send
from a subdomain, which has its own SPF and cannot collide. That is the stronger of the two
reasons to prefer `send.fpl-sidekick.com` in Task 2.2, and it is why that recommendation is not
merely tidiness.

**The MX records are Namecheap's email forwarding**, which is how mail *to* the domain reaches
you. Sending through a provider does not need MX and does not disturb it. Keep it: DMARC reports
go to a mailbox at this domain, and a support address is worth having.

## Where this stands

Verified against production on 2026-08-28, not copied forward from the previous version.

| | State |
|---|---|
| Backend, frontend, tests | done — merged in PR #52 |
| **Deployed** | **yes** — master deploys on push; last deploy 2026-08-28, `/actuator/health` is `UP` |
| `SUPABASE_SERVICE_ROLE_KEY`, `INVITE_CODE` | **set** as Fly secrets, status `Deployed` |
| Public sign-up in Supabase | turned off 2026-08-26 |
| Domain | **bought** — `fpl-sidekick.com`, parked at Namecheap, not yet pointed at the app |
| Custom SMTP | not configured |
| Site URL / redirect URLs | unverified; likely still Supabase's `http://localhost:3000` default |
| Email rate limit | not raised — a probe hit `429 over_email_send_rate_limit` on 2026-08-26 |
| Invite codes issued | unknown — check with `--list-invite-codes` before assuming |

So sign-up is closed at two independent points: no mail can be sent, and there may well be no
invite code to give anyone. Neither is a hazard while both hold — but do not treat the second as
a guarantee without running `--list-invite-codes`.

## The decision this runbook assumes

A domain can serve mail only, or mail *and* the app. **This runbook moves both**, and that is a
choice worth stating.

Serving mail from `noreply@fpl-sidekick.com` while the confirmation link lands on
`fpl-assistant.fly.dev` is a mismatch between the sender and the destination — the shape every
phishing filter is built to notice, and the shape a cautious tester notices too. It costs
deliverability and trust for no saving worth having, because the DNS work is done either way.

If you want mail working before the app moves, do Tasks 2, 3 and 5 and skip Task 1; Site URL stays
`https://fpl-assistant.fly.dev` and the rest is unchanged. That is a valid intermediate, not a
different plan.

---

## Task 1 — Point the domain at the app

**1.1 Done** — the domain is registered and its DNS is hosted at Namecheap, so records are edited
in the Namecheap dashboard under *Domain List → Manage → Advanced DNS*.

**1.2 Ask Fly for a certificate.** This tells Fly to expect the name and starts the ACME challenge.

```bash
export PATH="$HOME/.fly/bin:$PATH"
DOMAIN=fpl-sidekick.com
flyctl certs create "$DOMAIN"      -a fpl-assistant
flyctl certs create "www.$DOMAIN"  -a fpl-assistant
```

**1.3 Point the records at Fly.** The app's addresses today
(`flyctl ips list -a fpl-assistant`):

```
A     @      66.241.125.168              ← replaces the parking IP 192.64.119.148
AAAA  @      2a09:8280:1::16c:e5d7:0     ← new; there is no AAAA today
CNAME www    fpl-assistant.fly.dev
```

The A record is a **change**, not an addition: remove Namecheap's parking record, or the domain
will resolve to two hosts and roughly half of requests will reach the wrong one. Namecheap's
Advanced DNS page may also carry a `CNAME www → parkingpage.namecheap.com` and a URL-redirect
record; both must go.

The IPv4 is Fly's **shared** address, which is fine for a normal HTTPS app. Re-read
`flyctl ips list` before trusting the values above — a dedicated v4 (`flyctl ips allocate-v4`)
changes them, and this file will go stale the day that happens.

**1.4 Wait for the certificate, then verify.**

```bash
flyctl certs show "$DOMAIN" -a fpl-assistant     # until Status = Ready
curl -sS "https://$DOMAIN/actuator/health"        # expect {"status":"UP",...}
```

Expected: `{"groups":["liveness","readiness"],"status":"UP"}`. A TLS error means the certificate
is not issued yet; a connection refused means DNS has not propagated. Neither is a mail problem —
do not move on to Task 3 while either is true.

---

## Task 2 — Make the domain able to send mail

**2.1 Choose a provider.** Resend, Postmark or SES. Any of them works; Resend has the least setup
and a free tier that covers a test round. The previous version of this file warned that Resend's
`onboarding@resend.dev` delivers only to the Resend account owner — **that warning no longer
applies once your own domain is verified**, and is left here only so the old note is not
misremembered as a reason to avoid Resend.

**2.2 Decide which name sends.** Two options, and the second is the better habit:

- `noreply@fpl-sidekick.com` — verify the apex domain.
- `noreply@send.fpl-sidekick.com` — verify a subdomain instead.

The subdomain keeps transactional mail's sending reputation separate from the apex, so a future
mistake with a marketing tool cannot damage the domain that carries your sign-in mail. It costs
one extra DNS record. **Recommended: the subdomain.**

**2.3 Verify the domain with the provider.** The provider gives you three or four records; add
them exactly as given. They will look like:

```
TXT    send                    "v=spf1 include:<provider>.com ~all"
CNAME  <sel>._domainkey.send   <provider-supplied target>          # DKIM
TXT    _dmarc                  "v=DMARC1; p=none; rua=mailto:dmarc@fpl-sidekick.com"
```

**The SPF record goes on `send`, not on `@`.** The apex already has one
(`v=spf1 include:spf.efwd.registrar-servers.com ~all`, Namecheap's forwarding), and a second SPF
record on the same name makes the domain fail SPF instead of passing it. Sending from the
subdomain keeps the two apart. If you ever do send from the apex, the provider's `include:` must
be edited *into* the existing string — one record, two includes — never added as a second row.

DMARC belongs on `_dmarc` at the apex and covers subdomains too. `p=none` reports without
rejecting, which is what you want while nothing is proven; tighten to `p=quarantine` after a few
weeks of clean reports, not before. `rua=` needs a mailbox that exists — Namecheap's forwarding
already gives you one.

**2.4 Wait for the provider to report the domain verified.** Do not proceed on "records added" —
proceed on the provider's own green tick. DNS propagation is minutes to hours.

**2.5 Get SMTP credentials** from the provider. Host, port, username, password. Note them for
Task 3; the password is usually shown once.

---

## Task 3 — Configure Supabase

All four of these are dashboard settings in the Supabase project.

**3.1 Authentication → Emails → SMTP Settings**

```
Enable Custom SMTP    yes
Sender email          noreply@send.fpl-sidekick.com
Sender name           FPL Assistant
Host                  <provider SMTP host>
Port                  587            (or 465 for implicit TLS — the provider will say)
Username              <provider SMTP username>
Password              <provider SMTP password>
```

The sender address must be one the provider has verified. A sender the provider does not
recognise fails at send time, not at save time, so this looks fine in the dashboard and fails in
the flow.

**3.2 Authentication → Rate Limits.** Raise *"Rate limit for sending emails"*. The default is a
few an hour and it is what refused a probe on 2026-08-26 with `429 over_email_send_rate_limit`.
Thirty an hour covers a test round.

**3.3 Authentication → URL Configuration**

```
Site URL          https://fpl-sidekick.com
Redirect URLs     https://fpl-sidekick.com/**
                  https://www.fpl-sidekick.com/**
                  https://fpl-assistant.fly.dev/**        ← keep during the move
                  http://localhost:5173/**                ← local development
```

**This is the single most likely thing to get wrong.** `SupabaseAdminClient` does not send a
`redirect_to` — deliberately, so there is one place that decides where a confirmation link goes —
which means Site URL *is* that decision. If it is still `http://localhost:3000`, every
confirmation link sends the tester to a blank page, and that failure looks nothing like a mail
problem.

Keep the `fly.dev` entry until you are sure nothing points at it any more; an extra redirect URL
costs nothing and removes a way for the move to strand someone mid-confirmation.

**3.4 Authentication → Email Templates → Confirm signup.** The body must contain
`{{ .ConfirmationURL }}`. Check the sender name and subject read like a product and not like a
test — this is the first thing a tester sees from you.

---

## Task 4 — What changes in this repository

Small, because almost nothing here knows the hostname. Verified by
`grep -rn "fly\.dev" backend/src frontend/src fly.toml .github`, which finds exactly one match.

**4.1 `CORS_ORIGINS`.** The backend allows origins from this secret
(`application.yml:197`). The new origin must be in it or the browser blocks every API call from
the new domain.

```bash
flyctl secrets set -a fpl-assistant \
  CORS_ORIGINS="https://fpl-sidekick.com,https://www.fpl-sidekick.com,https://fpl-assistant.fly.dev"
```

Setting a secret restarts the app. Do it in the same window as the deploy in Task 5 rather than
twice.

**4.2 The deploy workflow's smoke test.** `.github/workflows/deploy.yml:135` curls
`https://fpl-assistant.fly.dev/actuator/health`. Point it at the new domain once Task 1.4 passes —
the check exists to prove the route the public uses actually serves, and after the move that route
is the domain.

**4.3 Nothing else.** `frontend/src/auth/supabaseClient.ts` sets `detectSessionInUrl: true` and no
redirect URL, so it turns the tokens in the returned fragment into a session wherever it is
served from. `fly.toml` names no hostname. The backend builds no absolute URLs.

---

## Task 5 — Issue a code and test it end to end

**5.1 Deploy** if Task 4 changed anything: push to `master` through a pull request and let the
workflow run. `fly deploy` by hand is the emergency path, not this.

**5.2 Check whether any invite code exists.** The table may be empty; nobody can sign up until it
is not.

```bash
flyctl ssh console -a fpl-assistant -C "java -jar /app/app.jar --list-invite-codes"
flyctl ssh console -a fpl-assistant -C "java -jar /app/app.jar --create-invite-code=testi:10"
```

`--create-invite-code` leaves an existing code untouched rather than resetting a live ceiling, so
running it twice is safe.

**5.3 Sign up as a real tester**, with an address you can actually read, on a device that is not
already signed in.

Expected, in order:

1. The form says **"Check your email"** — not a redirect into the app.
2. The message arrives from `noreply@send.fpl-sidekick.com`, within a minute.
3. The link lands on `https://fpl-sidekick.com`, signed in.
4. Signing in *before* following the link says **"Confirm your email address first"**.

**5.4 If it fails, read this order.** Each of these fails in a way that looks like a different one.

| Symptom | First thing to check |
|---|---|
| No mail at all | Supabase → Logs → Auth. A `429` is the rate limit (3.2); an SMTP error is the credentials (3.1) |
| Mail arrives, link opens a blank page | Site URL (3.3) — almost always this |
| Mail lands in spam | Provider's domain verification (2.4), then the DMARC record (2.3) |
| Link opens the app but not signed in | Redirect URLs (3.3) must include the exact origin |
| Sign-up answers `503` | `SUPABASE_SERVICE_ROLE_KEY` — set as of 2026-08-28, so this means it was cleared |
| Sign-up refuses every code | No code exists (5.2), or the code is out of uses, or `INVITE_MAX_ACCOUNTS` (30) is reached |
| Browser console shows a CORS error | `CORS_ORIGINS` (4.1) |

**5.5 Then tighten.** Remove the `fpl-assistant.fly.dev` redirect URL once nothing uses it, and
move DMARC to `p=quarantine` after a few weeks of clean reports.

**5.6 One thing this runbook cannot check for you.** Deliverability is judged by the receiving
side, not by your provider's dashboard. Send to a Gmail address and a non-Gmail one, and in Gmail
open *Show original* to confirm `SPF: PASS`, `DKIM: PASS`, `DMARC: PASS`. A message that arrives
in your own inbox proves less than those three lines do.

---

## Why the code looks the way it does

Unchanged by the domain, and still the thing most likely to be re-derived wrongly. Four GoTrue
endpoints were tried against the live project rather than reasoned about:

| Endpoint | Creates | Sends | With public sign-up off |
|---|---|---|---|
| `/auth/v1/signup` | yes | yes | **refused — the service role is not exempt** |
| `/auth/v1/admin/users` with `email_confirm: false` | yes | no | works |
| `/auth/v1/admin/generate_link` | yes | no, returns a link | works |
| `/auth/v1/resend` | no | yes | works |

The first row is why sign-up is two calls: create unconfirmed, then ask GoTrue to send. Public
sign-up cannot be turned back on to use the one-call path, because the anon key ships in the
frontend bundle and would let anyone create an account with no invite code at all.

A failed send is logged and swallowed. The account exists and the invite place is taken by then,
so throwing would present a mail failure as a failed sign-up, after which the person retries and
is told the address is taken.

Also learned: Supabase rejects `@example.com` as an invalid address, so a throwaway probe needs a
real-looking domain — which, once Task 1 is done, you have.

## Invite codes, in one paragraph

Codes are rows in `fpl_invite_code`, not one shared secret. Each carries its own ceiling
(`max_uses`), its own worth (`benefit_kind` / `benefit_value`), a validity window and an `active`
flag, so one code can be worth ten places, another a hundred, and another half off a subscription —
and one can run out or be withdrawn without touching any other. Discounts are recorded but do
nothing until Stripe exists (KAR-28). The account cap `INVITE_MAX_ACCOUNTS` (30) still applies
across every code as a cost ceiling: **a code worth 100 places stops at 30 until that number is
raised.**

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

## Tickets

- **KAR-33** — this runbook's checklist.
- **KAR-18** — burnt invite slots have no admin path; released by hand with
  `DELETE FROM fpl_invite_redemption WHERE user_id IS NULL` then `--reconcile-invite-codes`.
- **KAR-28** — Stripe, where the recorded discounts start doing something.
