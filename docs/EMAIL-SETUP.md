# Email verification on fpl-sidekick.com — the runbook

The code for email verification and invite codes is finished, merged and deployed. What is left is
configuration that does not live in this repository: DNS records, a mail provider, and Supabase
dashboard settings. This file is the runbook for that, and it is written to be worked through in
order — each task depends on the one above it.

Rewritten 2026-08-28 for **Resend** on **fpl-sidekick.com**, after two earlier versions: one
written around not having a domain at all, and one written before a provider was chosen.

## Where this stands

Every row below was checked against production or live DNS on 2026-08-28. Nothing is copied
forward from an earlier version of this file.

| | State |
|---|---|
| Backend, frontend, tests | done — merged in PR #52 |
| Deployed | **yes** — master deploys on push; `/actuator/health` answers `UP` |
| `SUPABASE_SERVICE_ROLE_KEY`, `INVITE_CODE` | **set** as Fly secrets, status `Deployed` |
| Public sign-up in Supabase | turned off 2026-08-26 |
| Domain | **bought** — `fpl-sidekick.com`, registered at Namecheap |
| App on the domain | **serving** — both certificates Issued, `https://fpl-sidekick.com/actuator/health` returns 200 |
| Parking A record | **still present** — see Trap 1; the one thing left in Task 1 |
| Mail provider | **not set up** — Task 2, the remaining blocker |
| Site URL / redirect URLs | unverified; likely still Supabase's `http://localhost:3000` default |
| Email rate limit | not raised — a probe hit `429 over_email_send_rate_limit` on 2026-08-26 |
| Invite codes issued | unknown — run `--list-invite-codes` rather than assuming |

Sign-up is closed at two independent points: no mail can be sent, and there may well be no invite
code to give anyone. Neither is a hazard while both hold.

## What this costs, and which plan to start on

**Resend Pro, $20/month.** Not the free tier, and the reason is not the monthly total.

| Plan | Price | Emails / month | Daily cap |
|---|---|---|---|
| Free | $0 | 3,000 | **100 / day** |
| Pro | $20 | 50,000 | none |

The free tier's monthly allowance is generous for this project. Its **daily cap is not**. Five
thousand users signing up across six months averages roughly 28 a day, which fits — but a launch,
a podcast mention or a link that travels does not average. On exactly the day sign-ups spike, the
101st confirmation of the day is not sent, and the person who just gave you their address sees
nothing. That is the worst possible day to discover a cap.

Start on Free while you test the flow with a handful of addresses; **upgrade before you hand the
invite code to anyone.**

**Broadcasts are a separate product.** If you ever want to email all your users at once, that is
Resend's marketing product, priced per *contact* rather than per email — $40/month at 5,000
contacts, whether you send once a month or ten times. Nothing here sets that up, and sign-up
confirmation does not need it. Revisit if announcements become a real feature: at that point SES
plus Listmonk costs roughly a tenth as much, and that exact stack is already being built in the
`kurssialusta` project next door.

For scale: at 5,000 users the language-model cost exceeds the mail cost by about three orders of
magnitude. Choose mail on what breaks, not on what it costs.

---

## The domain, and the three traps in it

`fpl-sidekick.com`, registered at Namecheap, DNS hosted there too. Records are edited under
**Domain List → Manage → Advanced DNS**.

### Trap 1 — The parking A record is still live

```
A  @  66.241.125.168     ← Fly, correct
A  @  192.64.119.148     ← Namecheap parking, DELETE THIS
```

Namecheap implements a **URL Redirect Record** by pointing the apex at its own server, so that
redirect record *is* the second A record. Deleting the redirect removes it.

**This looks fine and is not.** Measured on 2026-08-28: ten straight requests to
`https://fpl-sidekick.com/actuator/health` returned 200. But the parking address answers nothing
at all — `curl --resolve fpl-sidekick.com:443:192.64.119.148` fails outright — and curl silently
retries the other address. Clients that do not retry get a hard failure, and which address a
client picks is not something you control. Certificate issuance does not clear this either: both
certificates are Issued and the fault is still there.

```bash
dig +short A fpl-sidekick.com     # must return exactly one line: 66.241.125.168
```

### Trap 2 — One SPF record, and one DMARC record

The apex already carries `v=spf1 include:spf.efwd.registrar-servers.com ~all` — Namecheap's email
forwarding. **A domain may have exactly one SPF record**; a second makes it fail SPF outright,
which is worse than having none.

The same rule applies to DMARC, and it is easier to trip over because nothing warns you: two
`_dmarc` TXT records make DMARC inert. The neighbouring `kurssialusta` project has exactly this
fault on its own domain right now, which is how it came to be written down here.

This is why the runbook sends from the subdomain `send.fpl-sidekick.com`. A subdomain carries its
own SPF record and cannot collide with the apex. If you ever do send from the apex, the provider's
`include:` must be edited *into* the existing string — one record, two includes — never added as
a second row.

### Trap 3 — Namecheap appends the domain for you

In Namecheap's Host field you enter **only the subdomain part**; Namecheap adds
`.fpl-sidekick.com` itself. Paste what Resend shows verbatim and you get
`send.fpl-sidekick.com.fpl-sidekick.com`, which resolves to nothing and fails silently — no error
message, just a domain that never verifies.

| Resend shows | Type into Namecheap's Host field |
|---|---|
| `send.fpl-sidekick.com` | `send` |
| `resend._domainkey.fpl-sidekick.com` | `resend._domainkey` |
| `_dmarc.fpl-sidekick.com` | `_dmarc` |

Rule: **strip `.fpl-sidekick.com` from the end of every Host value before pasting.** The Value
field is the opposite — paste it exactly as given, in full.

One more Namecheap mechanic: an **MX record's priority goes in its own field**, not at the front
of the value. `10 feedback-smtp...` pasted whole into Value is a broken record.

### What must not be touched

```
MX   @   eforward1-5.registrar-servers.com    ← inbound mail; DMARC reports land here
TXT  @   v=spf1 include:spf.efwd...           ← the apex SPF; leave it alone
```

Namecheap's forwarding is how mail *to* the domain reaches you. Sending through Resend neither
needs nor disturbs it, and you need a working inbox at this domain for DMARC reports.

---

## Task 1 — The app on the domain

Mostly done. Both certificates are Issued and the app serves on both names.

**1.1 Delete the URL Redirect Record.** Namecheap → Advanced DNS. Remove the
`URL Redirect Record` on `@`, and any `CNAME www → parkingpage.namecheap.com` beside it.

**1.2 Confirm the records that remain:**

```
A     @      66.241.125.168
AAAA  @      2a09:8280:1::16c:e5d7:0
CNAME www    fpl-assistant.fly.dev.
```

Re-read `flyctl ips list -a fpl-assistant` before trusting those addresses — a dedicated IPv4
would change them, and this file goes stale the day that happens.

**Done when:**

```bash
dig +short A fpl-sidekick.com                          # exactly one line
curl -sS https://fpl-sidekick.com/actuator/health      # {"...","status":"UP"}
```

---

## Task 2 — Resend

**2.1 Create the account.** <https://resend.com>. Start on Free; you upgrade in 2.7.

**2.2 Add the domain — and choose the region deliberately.**

Dashboard → **Domains** → **Add Domain**.

```
Domain    send.fpl-sidekick.com
Region    EU (Ireland)
```

Two decisions on one screen, both awkward to undo:

- **The subdomain**, `send.`, not the apex. Trap 2 is the technical reason; the reputational one
  is that a mistake later with some marketing tool on the apex then cannot damage the domain
  carrying your sign-in mail.
- **The region.** Your users and your app are in Europe, and mail metadata is personal data. Pick
  the EU region. Changing it later means deleting the domain and verifying again.

**2.3 Add the three DNS records in Namecheap.** Resend shows them as soon as the domain is added.
Read Trap 3 before pasting anything.

| Type | Host (domain stripped) | Value | Priority |
|---|---|---|---|
| MX | `send` | `feedback-smtp.eu-west-1.amazonses.com` | `10` |
| TXT | `send` | `v=spf1 include:amazonses.com ~all` | — |
| TXT | `resend._domainkey` | the long `p=...` key Resend generates | — |

Take the exact values from your own dashboard: the DKIM key is generated per domain, and the MX
target names the region you chose. TTL `Automatic` throughout.

**2.4 Wait for verification.** Resend usually verifies within about 15 minutes of the records
propagating. Check from the command line before blaming the dashboard:

```bash
dig +short MX  send.fpl-sidekick.com
dig +short TXT send.fpl-sidekick.com
dig +short TXT resend._domainkey.fpl-sidekick.com
```

Each must return what Resend showed. An empty answer means the record has not propagated yet, or
the Host field carried a doubled domain (Trap 3).

**2.5 Add DMARC.** One record, on the apex, covering subdomains too.

| Type | Host | Value |
|---|---|---|
| TXT | `_dmarc` | `v=DMARC1; p=none; rua=mailto:dmarc@fpl-sidekick.com; fo=1` |

`p=none` reports without rejecting, which is what you want while nothing is proven. Tighten to
`p=quarantine` only after a few weeks of clean reports.

**The `rua` address has to exist, and it has to be at this domain.** An address in another domain
— a Gmail address, say — needs an authorisation record on *that* domain's side, which you cannot
add to `gmail.com`. Create `dmarc@fpl-sidekick.com` as a forwarder first: Namecheap → Domain List
→ Manage → **Redirect Email**. Expect a daily XML attachment from every large mail provider; that
is what the reports are.

```bash
dig +short TXT _dmarc.fpl-sidekick.com    # exactly one line
```

**2.6 Create the API key.** Resend → **API Keys** → **Create API Key**.

```
Name         supabase-smtp
Permission   Sending access
Domain       send.fpl-sidekick.com
```

**The key is shown once.** Put it in your password vault before closing the dialog — it is the
SMTP password in Task 3, and a lost key means creating a new one and updating Supabase. Scope it
to sending only and to this domain: a key that can also read or manage domains is a key that needs
more care than an SMTP password ever gets.

**2.7 Upgrade to Pro** before handing the invite code to anyone. The cost section above explains
why the daily cap, and not the monthly total, is the reason.

**Done when:** the domain reads **Verified** in Resend, the three `dig` checks return what the
dashboard shows, `_dmarc` returns exactly one line, and the API key is in your vault.

---

## Task 3 — Supabase

Four dashboard settings in the Supabase project.

**3.1 Authentication → Emails → SMTP Settings**

```
Enable Custom SMTP    yes
Sender email          noreply@send.fpl-sidekick.com
Sender name           FPL Sidekick
Host                  smtp.resend.com
Port                  587
Username              resend
Password              <the API key from 2.6>
```

Two of these are counter-intuitive, and both produce the same silent failure. **The username is
the literal string `resend`** — not your email address, not the key. **The password is the API
key**, the whole `re_...` string. And the sender must be at the domain Resend verified: an address
it does not recognise fails at send time rather than at save time, so it looks correct in the
dashboard and fails in the flow.

**3.2 Authentication → Rate Limits.** Raise *Rate limit for sending emails*. The default is a few
an hour, and it is what refused a probe on 2026-08-26 with `429 over_email_send_rate_limit`.
Thirty an hour covers a test round; raise it again before opening sign-ups more widely.

**3.3 Authentication → URL Configuration**

```
Site URL          https://fpl-sidekick.com

Redirect URLs     https://fpl-sidekick.com/**
                  https://www.fpl-sidekick.com/**
                  https://fpl-assistant.fly.dev/**     ← keep during the move
                  http://localhost:5173/**             ← local development
```

**This is the single most likely thing to get wrong.** `SupabaseAdminClient` sends no
`redirect_to`, deliberately, so exactly one place decides where a confirmation link goes — and
that place is Site URL. Left at Supabase's `http://localhost:3000` default, every confirmation
link sends a tester to a blank page, and the failure looks nothing like a mail problem.

**3.4 Authentication → Email Templates → Confirm signup.** The body must contain
`{{ .ConfirmationURL }}`. Read the subject and sender name as a stranger would — this is the first
thing anyone ever receives from you.

**Done when:** all four are saved and Supabase's own **Send test email** reaches your inbox.

---

## Task 4 — What changes in this repository

Very little, because almost nothing here knows the hostname.
`grep -rn "fly\.dev" backend/src frontend/src fly.toml .github` finds exactly one match.

**4.1 `CORS_ORIGINS`.** The backend reads allowed origins from this secret
(`application.yml:197`). Without the new origin the browser blocks every API call from the domain.

```bash
flyctl secrets set -a fpl-assistant \
  CORS_ORIGINS="https://fpl-sidekick.com,https://www.fpl-sidekick.com,https://fpl-assistant.fly.dev"
```

Setting a secret restarts the app — do it in the same window as the Task 5 deploy, not twice.

**4.2 The deploy workflow's smoke test.** `.github/workflows/deploy.yml:135` curls the fly.dev
health endpoint. Point it at the domain: the check exists to prove the route the public uses
actually serves, and after the move that route is the domain.

**4.3 Nothing else.** The frontend's Supabase client sets `detectSessionInUrl: true` and no
redirect URL, so it turns the returned fragment into a session wherever it is served from.
`fly.toml` names no hostname. The backend builds no absolute URLs.

**Done when:** the secret is set, the workflow change is merged through a pull request, and the
deploy is green.

---

## Task 5 — Issue a code and test end to end

**5.1 Check whether a code exists.** The table may be empty, and nobody can sign up until it is
not.

```bash
flyctl ssh console -a fpl-assistant -C "java -jar /app/app.jar --list-invite-codes"
flyctl ssh console -a fpl-assistant -C "java -jar /app/app.jar --create-invite-code=testi:10"
```

`--create-invite-code` leaves an existing code untouched rather than resetting a live ceiling, so
running it twice is safe.

**5.2 Sign up as a real tester** — an address you can read, on a device not already signed in.

| Step | What should happen |
|---|---|
| Submit the form | Says **“Check your email”** — not a redirect into the app |
| Inbox | Message from `noreply@send.fpl-sidekick.com`, within a minute |
| Follow the link | Lands on `https://fpl-sidekick.com`, signed in |
| Sign in *before* confirming | Says **“Confirm your email address first”** |

**5.3 Confirm the authentication actually passed.** Deliverability is judged by the receiving
side, not by Resend's dashboard. Send to a Gmail address and to a non-Gmail one. In Gmail, open
the message → three dots → **Show original**:

```
SPF:    PASS
DKIM:   PASS
DMARC:  PASS
```

All three must pass, and the message must land in the inbox rather than in spam. A message
arriving in your own inbox proves less than these three lines do.

**Done when:** every row of 5.2 behaves as described and all three lines of 5.3 read PASS.

---

## Keeping it working

Two numbers decide whether a sender keeps its reputation, and every provider watches them, Resend
included:

- **Bounce rate** — mail to addresses that do not exist. Above roughly **5%** a sender is warned
  and eventually suspended.
- **Complaint rate** — recipients marking mail as spam. The threshold is around **0.1%**, which is
  one complaint in a thousand.

For confirmation mail sent to addresses people typed themselves, both should sit near zero. They
climb only when something is wrong: a typo'd address retried forever, or mail people did not
expect. Check the Resend dashboard after the first real batch of sign-ups, and again whenever you
raise the invite ceiling.

This project needs no bounce webhooks. Sign-up confirmation is one message to one address a person
just typed; there is no list to prune. That changes the day broadcasts appear — and so does the
provider question.

## When it fails, read in this order

Each of these fails in a way that looks like a different one.

| Symptom | First thing to check |
|---|---|
| Domain never verifies in Resend | Trap 3 — a doubled domain in the Host field. `dig` the record and read what came back |
| No mail at all | Supabase → Logs → Auth. A `429` is the rate limit (3.2); an SMTP auth error is the username or password (3.1 — the username is the literal `resend`) |
| Mail arrives, link opens a blank page | Site URL (3.3) — almost always this |
| Mail lands in spam | `dig` the SPF and DKIM records, then read `Show original` (5.3) to see which one failed |
| DMARC reads as absent | Two `_dmarc` records, or one at the wrong Host (Traps 2 and 3) |
| Link opens the app but not signed in | Redirect URLs (3.3) must include the exact origin |
| Sign-up answers `503` | `SUPABASE_SERVICE_ROLE_KEY` — set as of 2026-08-28, so this means it was cleared |
| Sign-up refuses every code | No code exists (5.1), the code is out of uses, or the 30-account cap is reached |
| Browser console shows a CORS error | `CORS_ORIGINS` (4.1) |
| Site works, then intermittently does not | Trap 1 — the parking A record was left in place |

## Why sign-up is two calls

The part most likely to be re-derived wrongly. Four GoTrue endpoints were tried against the live
project rather than reasoned about:

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
so throwing would present a mail failure as a failed sign-up — after which the person retries and
is told the address is already taken.

Also learned: Supabase rejects `@example.com` as an invalid address, so a throwaway probe needs a
real-looking domain.

## Invite codes

Codes are rows in `fpl_invite_code`, not one shared secret. Each carries its own ceiling
(`max_uses`), its own worth (`benefit_kind` / `benefit_value`), a validity window and an `active`
flag — so one code can be worth ten places, another a hundred, another half off a subscription,
and any one can run out or be withdrawn without touching the others. Discounts are recorded but
inert until Stripe exists (KAR-28).

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

The account cap `INVITE_MAX_ACCOUNTS` (30) applies across every code as a cost ceiling: **a code
worth 100 places stops at 30** until that number is raised. Raise it deliberately — it is what
bounds the language-model spend, which at any real scale is the cost that matters.

## Tickets

- **KAR-33** — this runbook.
- **KAR-18** — burnt invite slots have no admin path;
  `DELETE FROM fpl_invite_redemption WHERE user_id IS NULL` then `--reconcile-invite-codes`.
- **KAR-28** — Stripe, where the recorded discounts start doing something.
