# Email verification on fpl-sidekick.com — the runbook

The code for email verification and invite codes is finished, merged and deployed. What is left is
configuration that does not live in this repository: DNS records, a mail provider, and Supabase
dashboard settings. This file is the runbook for that, and it is written to be worked through in
order — each task depends on the one above it.

Rewritten 2026-08-28 for **Resend** on **fpl-sidekick.com**, after two earlier versions: one
written around not having a domain at all, and one written before a provider was chosen.

## Where this stands

Every row below was checked against production or live DNS on 2026-08-28 or 2026-08-29. Nothing
is copied forward from an earlier version of this file.

| | State |
|---|---|
| Backend, frontend, tests | done — merged in PR #52 |
| Deployed | **yes** — master deploys on push; `/actuator/health` answers `UP` |
| `SUPABASE_SERVICE_ROLE_KEY` | **set** as a Fly secret, status `Deployed`. `INVITE_CODE` is also set but ignored since PR #52 — codes are table rows |
| Public sign-up in Supabase | turned off 2026-08-26 |
| Domain | **bought** — `fpl-sidekick.com`, registered at Namecheap |
| App on the domain | **serving** — both certificates Issued, `https://fpl-sidekick.com/actuator/health` returns 200 |
| Parking A record | **gone** — verified 2026-08-28, the apex now returns exactly one A record |
| Resend DNS records | **all live, CNAME layout** — Resend shows every row Verified as of 2026-08-29 |
| DMARC | **live**, one record, `p=none`, no `rua` |
| Inbound mail to the domain | **dead** — the Custom MX switch removed the apex MX *and* SPF; see 2.8 |
| Mail provider | Resend — domain **Verified** 2026-08-29; first confirmation mail delivered to Gmail's inbox |
| Site URL / redirect URLs | **unverified** — the first confirmation mail was delivered 2026-08-29; following its link is the test (5.2) |
| Email rate limit | **unknown** — a probe hit `429 over_email_send_rate_limit` on 2026-08-26, before custom SMTP; check 3.2 before a tester round |
| Invite codes issued | `testi` — 10 places, `uses` 2 as of 2026-08-29: one account, one place burnt by the failed first attempt |
| `CORS_ORIGINS` | **includes the domain** since 2026-08-28 — without it sign-up refuses every code (4.1) |
| Announcement list | **never exported** — Task 6 is the procedure, run when there is something to announce |

Sign-up is open on the code `testi`. One account exists, created 2026-08-28 and unconfirmed until
its link is followed.

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

**Broadcasts are priced separately, and at this size they are free.** Emailing every user at
once — a service change, an outage, a price announcement — is Resend's marketing side, billed per
*contact* rather than per message: **1,000 contacts cost nothing**, and the paid tiers start above
that, reaching $40/month at 5,000 contacts whether you send once a month or ten times. It is the
same Resend account and the same verified domain; nothing extra to set up and no second provider.

Task 6 is the procedure. It needs no code, because the list of people to write to is `auth.users`
and it is exported at the moment an announcement goes out. The thing to watch is the contact
count, not the message count: crossing 1,000 users turns a free line item into a paid one, and at
that point the SES-plus-Listmonk stack being built in the `kurssialusta` project next door costs
roughly a tenth as much and is worth the comparison.

For scale: at 5,000 users the language-model cost exceeds the mail cost by about three orders of
magnitude. Choose mail on what breaks, not on what it costs.

---

## The domain, and the four traps in it

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

**Resend's dashboard already shows Host values with the domain stripped** — it displays
`resend._domainkey.send`, not `resend._domainkey.send.fpl-sidekick.com`. So for Resend the rule is
simply: **paste the Host verbatim, and add nothing.** The Value field likewise, in full.

The trap is live for records you compose yourself, or copy from a provider that shows fully
qualified names. If a Host you are about to paste ends in `.fpl-sidekick.com`, strip that suffix;
if it does not, leave it alone.

| Where it comes from | Resend shows | Type into Namecheap |
|---|---|---|
| Resend dashboard | `resend._domainkey.send` | `resend._domainkey.send` — verbatim |
| Written out by hand | `_dmarc.fpl-sidekick.com` | `_dmarc` |

One more Namecheap mechanic, relevant to 2.8: an **MX record's priority goes in its own field**,
not at the front of the value. `10 eforward1...` pasted whole into Value is a broken record.

### Trap 4 — Namecheap will not let you add an MX record at all

There is no MX option in the Host Records dropdown, and that is not a bug. Above the record list
is a separate **MAIL SETTINGS** dropdown, currently set to **Email Forwarding**. While it is,
Namecheap owns the MX records — it wrote the `eforward` ones itself — and refuses to let you add
your own, including on a subdomain.

Resend's current record set (2.3) is two CNAMEs and needs no MX record of your own, so this trap
no longer blocks sending. It is kept because the switch to Custom MX was made on 2026-08-28 under
the older TXT + MX layout, and because 2.8 — restoring inbound mail — needs Custom MX anyway. One
thing matters before switching: **write down the existing MX records**, because the switch removes
them.

```
10  eforward1.registrar-servers.com
10  eforward2.registrar-servers.com
10  eforward3.registrar-servers.com
15  eforward4.registrar-servers.com
20  eforward5.registrar-servers.com
```

Those are the values as of 2026-08-28 — confirm with `dig +short MX fpl-sidekick.com` before you
touch anything, in case they have changed.

### What must not be touched

```
TXT  @   v=spf1 include:spf.efwd...           ← the apex SPF; leave it alone
```

The apex SPF belongs to the forwarding service. Sending through Resend neither needs nor disturbs
it.

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

**2.2 Add the domain — and choose the region deliberately.** *(Done 2026-08-28.)*

Dashboard → **Domains** → **Add Domain**.

```
Domain    fpl-sidekick.com
Region    EU (Ireland)
```

**Add the apex. Do not add `send.fpl-sidekick.com`.** Resend prepends the sending subdomain
itself: give it `fpl-sidekick.com` and it sends through subdomains of it — `send.` and `rsend.`,
both CNAMEs to Resend, see 2.3. The delivered mail's Return-Path read
`…@rsend.fpl-sidekick.com` (2026-08-29), which is why `rsend` points at an Amazon SES SPF record. That gets the benefit an earlier draft of this runbook was
reaching for — SPF on `send.`, unable to collide with Namecheap's apex SPF (Trap 2) — without
constructing it by hand.

**This was got wrong on 2026-08-28 and cost a verification cycle.** An earlier version of this
file said to add `send.fpl-sidekick.com`. Resend then prepends its own subdomain and looks for
everything one level deeper — `resend._domainkey.send.send.fpl-sidekick.com` — while the DNS
records, written for the apex layout, sat at `resend._domainkey.fpl-sidekick.com`. The dashboard
reports this as *Pending / Looking for DNS records*, which reads exactly like slow propagation and
is not. **If a domain stays Pending for more than about half an hour, compare the Host Resend
shows against the name that actually resolves** — one extra or missing label is the usual cause:

```bash
dig +short @dns1.registrar-servers.com TXT resend._domainkey.fpl-sidekick.com
```

The fix is to correct Resend, not the DNS: delete the domain, add the apex, and update the one
DKIM TXT value Resend regenerates. The CNAMEs on `send` and `rsend` need no change.

**You then send from an address at the verified domain**: `noreply@fpl-sidekick.com`. The `send.`
subdomain is the bounce and return path, not the From address.

**The region** matters and is awkward to undo: your users and your app are in Europe, and mail
metadata is personal data. Changing it later means deleting the domain and verifying again.

**2.3 Add the four records.**

Resend shows them as soon as the domain is added. Read Trap 3 before pasting anything. As of
2026-08-29 the set is one TXT for DKIM, two CNAMEs under *Enable Sending*, and the DMARC record
from 2.5:

| Type | Host (domain stripped) | Value | TTL |
|---|---|---|---|
| TXT | `resend._domainkey` | the long `p=...` key Resend generates | Automatic |
| CNAME | `send` | `send.forge.rmta.net` | Automatic |
| CNAME | `rsend` | `rsend-euw1.forge.rmta.net` | Automatic |

Take the exact values from your own dashboard: the DKIM key is generated per domain, and the
`rsend` target names the region you chose (`euw1` is `eu-west-1`). Namecheap adds a trailing dot to
CNAME values on save; that is correct.

**This layout replaced an older one, and the change is what broke the first sign-up on
2026-08-28.** Resend used to ask for an SPF TXT (`v=spf1 include:amazonses.com ~all`) and an MX
(`feedback-smtp.eu-west-1.amazonses.com`) directly on `send`. Those were added and verified, and
then the dashboard silently switched to asking for the CNAMEs above, with both marked *Pending*.
Resend does not send from a domain whose sending records are pending: Supabase's SMTP call failed
and GoTrue answered `500 Error sending confirmation email` for every attempt, while the app's log
said only *"the confirmation email could not be sent (500)"*.

**If the old records are present, delete them first.** A name can hold a CNAME or other records,
never both. Namecheap accepted `CNAME send` beside `TXT send` and `MX send` without complaint, and
the authoritative nameserver then answered all three — `dig TXT send.fpl-sidekick.com` returned the
stale SES SPF instead of following the CNAME, and Resend stayed *Pending*. Remove the TXT from Host
Records and the MX from **MAIL SETTINGS → Custom MX** (that is where Namecheap lists it), then
press **Verify** in Resend.

**2.4 Wait for verification.** Resend usually verifies within about 15 minutes of the records
propagating. Check from the command line before blaming the dashboard:

```bash
dig +short CNAME send.fpl-sidekick.com     # send.forge.rmta.net.  — and nothing else
dig +short CNAME rsend.fpl-sidekick.com    # rsend-euw1.forge.rmta.net.
dig +short TXT resend._domainkey.fpl-sidekick.com
dig +short TXT send.fpl-sidekick.com       # the CNAME, then Resend's own v=spf1 — not amazonses
```

Each must return what Resend showed. An empty answer means the record has not propagated yet, or
the Host field carried a doubled domain (Trap 3). A `TXT send` answer that names `amazonses.com` is
the stale record from the old layout sitting beside the CNAME — delete it (2.3). Once every row is
Verified, expect the rows to flick to *Pending* for a minute while Resend re-checks the whole
domain; it settles by itself.

**2.5 Add DMARC — without reports, for now.** One record, on the apex, covering subdomains too.

| Type | Host | Value |
|---|---|---|
| TXT | `_dmarc` | `v=DMARC1; p=none` |

That is a complete, valid DMARC record. `p=none` states the policy and rejects nothing, which is
what you want while nothing is proven; tighten to `p=quarantine` only after weeks of clean
evidence.

**Reports are deliberately left out until an inbox exists.** A `rua=` address has to exist *and*
be at this domain — an address elsewhere, a Gmail one for instance, needs an authorisation record
on *that* domain's side, which you cannot add to `gmail.com`. Since Trap 4 has just removed the
forwarding that would have received them, adding `rua` now would point reports at a mailbox that
does not exist. Step 2.8 restores inbound mail; add `rua` after that, and only once you have
confirmed a test message actually arrives.

```bash
dig +short TXT _dmarc.fpl-sidekick.com    # exactly one line
```

**2.6 Create the API key.** Resend → **API Keys** → **Create API Key**.

```
Name         supabase-smtp
Permission   Sending access
Domain       fpl-sidekick.com
```

**The key is shown once.** Put it in your password vault before closing the dialog — it is the
SMTP password in Task 3, and a lost key means creating a new one and updating Supabase. Scope it
to sending only and to this domain: a key that can also read or manage domains is a key that needs
more care than an SMTP password ever gets.

**2.7 Upgrade to Pro** before handing the invite code to anyone. The cost section above explains
why the daily cap, and not the monthly total, is the reason.

**2.8 Restore inbound mail, if you want it.** Optional, and nothing in the sign-up flow depends on
it — but a product with users eventually needs an address people can write to, and DMARC reports
need one too.

**Confirmed 2026-08-28: the switch removed both.** The apex now has no MX records *and* no TXT
SPF — Namecheap manages the forwarding SPF as part of the same bundle, so it went with the MX
records. Public resolvers served the old values from cache for a while; the authoritative
nameserver is the one to ask.

Nothing about *sending* is affected, and that is worth being precise about rather than assumed:

- **DKIM** signs as `fpl-sidekick.com` and the From header is at `fpl-sidekick.com`. Aligned.
- **SPF** checks the envelope sender, which is `rsend.fpl-sidekick.com` on the delivered mail, and
  that name resolves through its CNAME to an SPF record naming the sending IPs. Under DMARC's default relaxed alignment a subdomain
  aligns with its parent. Aligned.

Both mechanisms pass, so DMARC passes, with or without an apex SPF record.

Under **Custom MX**, re-add the five apex records you recorded in 2.3:

| Type | Host | Value | Priority |
|---|---|---|---|
| MX | `@` | `eforward1.registrar-servers.com` | `10` |
| MX | `@` | `eforward2.registrar-servers.com` | `10` |
| MX | `@` | `eforward3.registrar-servers.com` | `10` |
| MX | `@` | `eforward4.registrar-servers.com` | `15` |
| MX | `@` | `eforward5.registrar-servers.com` | `20` |

Then Namecheap → Domain List → Manage → **Redirect Email**, and forward `dmarc@` to an address you
read.

**Whether forwarding still works in Custom MX mode is not documented by Namecheap, and this
runbook does not claim it does.** Test it: send a message to `dmarc@fpl-sidekick.com` from an
outside address and see whether it arrives. If it does, add `rua=mailto:dmarc@fpl-sidekick.com;
fo=1` to the DMARC record from 2.5. If it does not, leave DMARC without reports — it loses you
telemetry, nothing else — or move the domain's mail to a real mailbox provider later.

**Done when:** the domain reads **Verified** in Resend, the three `dig` checks return what the
dashboard shows, `_dmarc` returns exactly one line, and the API key is in your vault.

---

## Task 3 — Supabase

Four dashboard settings in the Supabase project.

**3.1 Authentication → Emails → SMTP Settings**

```
Enable Custom SMTP    yes
Sender email          noreply@fpl-sidekick.com
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

**4.1 Add the domain to `CORS_ORIGINS`.** An earlier version of this file said the secret needed
no change because the frontend calls the API on relative paths from the same origin. That reasoning
was wrong in practice and cost a debugging round on 2026-08-28: sign-up refused a valid invite code
with *"That invite code is not valid."*

What happens: a browser sends an `Origin` header on every `POST`, same-origin or not. Behind Fly's
proxy the application sees itself as `http://…:8080`, so `Origin: https://fpl-sidekick.com` does not
match its own address and Spring treats the request as cross-origin, checks it against
`CORS_ORIGINS`, and answers `403 Invalid CORS request`. The frontend shows every `403` from the
sign-up endpoint as an invalid code, so the real message is never seen. `curl` never reproduces it
because `curl` sends no `Origin`; add `-H 'Origin: https://fpl-sidekick.com'` and it does.

```bash
flyctl secrets set -a fpl-assistant \
  'CORS_ORIGINS=https://fpl-sidekick.com,https://fpl-assistant.fly.dev'
```

The machine restarts on a secret change; allow half a minute. Proof that it took: the same `curl`
with the `Origin` header answers `400` (missing password) instead of `403`.

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

**5.1 Check whether a code exists, and create one — from Supabase, not over SSH.**

The table may be empty, and nobody can sign up until it is not.

**Do not run the CLI flags against the running production machine.** An earlier version of this
file said to, and that was a hazard rather than a shortcut: the app runs on a single 512 MB Fly
machine with `-XX:MaxRAMPercentage=75`, so the live JVM already claims about 384 MB.
`flyctl ssh console -C "java -jar /app/app.jar --list-invite-codes"` starts a *second* full Spring
context in the same container, and the likely outcome is the kernel killing one of the two — most
easily the one serving traffic. Verified against `fly status` and `fly.toml` on 2026-08-28.

Use the Supabase SQL editor instead. Reading is free:

```sql
SELECT code, label, max_uses, uses, active, valid_until
FROM fpl_invite_code
ORDER BY code;
```

Creating a code is a plain insert, and that is not a shortcut around the CLI — it is what the CLI
does. `InviteLedger.create` inserts the row with `code.trim()` and nothing else; the defaults on
the table supply the rest (`uses` 0, `benefit_kind` `'access'`, `active` true):

```sql
INSERT INTO fpl_invite_code (code, label, max_uses)
VALUES ('testi', 'testi', 10);
```

Name only the columns you mean, so the defaults apply — passing an explicit `NULL` for
`benefit_kind` would violate its `NOT NULL`.

The CLI remains the right tool anywhere it is not competing with the live app for memory: locally
against `.env.supabase`, or on a machine sized for it. `--create-invite-code` leaves an existing
code untouched rather than resetting a live ceiling, so running it twice is safe.

**5.2 Sign up as a real tester** — an address you can read, on a device not already signed in.

| Step | What should happen |
|---|---|
| Submit the form | Says **“Check your email”** — not a redirect into the app |
| Inbox | Message from `noreply@fpl-sidekick.com`, within a minute |
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

## Task 6 — Telling every user about a change

Sign-up confirmation reaches one person at a time. This is the other direction: one message to
everyone who has ever had mail from this project, for the day something about the service changes.
Nothing here runs until there is something to announce — read it then, and work through it in one
sitting.

**No code, and no automatic sync — that is the decision, not an omission.** The obvious
alternative is to add a Resend contact from `SignupController` as each account is created. It
would need a second API key with contact-write permission, a failure path that must not fail
sign-up, and it would create a second copy of the list that immediately starts drifting: an
account deleted in Supabase stays a contact in Resend and receives the announcement anyway. The
export below cannot drift, because the export *is* the list, read at the moment it is used. The
cost is one manual step per announcement, which is the right trade while announcements are rare.
Revisit when the manual step is the thing that hurts.

**6.1 Export the addresses.** Paste `scripts/sql/export-mail-contacts.sql` into the Supabase SQL
editor. **Before running it, open the *Row limit* menu at the bottom of the results pane and set
it to *No limit*.** The editor defaults to 100 rows, the CSV download contains only the rows the
grid holds, and the one sign of truncation is a small *(Limited to only 100 rows)* beside the row
count — confirmed from Studio's source on 2026-08-29. Then run the query and download the CSV.

The predicate doing the work is *a sent-at column is set*: `confirmation_sent_at`, which sign-up
and a dashboard invite both stamp, or `recovery_sent_at`, which password recovery would stamp if
the frontend had that flow. GoTrue sets these only after the message has actually been handed to
the SMTP provider — read from its source, `sendConfirmation` sends first and stamps on success —
so an account whose send failed keeps them `NULL`, which is exactly what was observed on
2026-08-28 while Resend's sending records sat *Pending* (2.3). The column therefore means *we have
written to this address*, which is the question being asked, and it is a different set from *has
an account*. Unconfirmed accounts belong in the export: they were sent the confirmation message.

Read the row count before going further. If it is far below the number of accounts, the gap is a
mail fault rather than an export fault — the commented sanity-check query at the bottom of the
`.sql` file gives all three numbers side by side, and *When it fails* below diagnoses them.

**6.2 Import into an Audience.** Resend → **Audiences**. Use the default audience or create one;
either way use **the same one every time**, since re-importing is how the list stays current.
Upload the CSV, confirm Resend has mapped the single column to `email`, and import. A dashboard
import always updates contacts it already holds — the *skip existing* choice exists only in the
API — so running this before every announcement adds the new people and leaves the rest alone.

**The CSV has exactly one column, and adding a second can undo an unsubscribe.** Resend also
accepts `first_name`, `last_name` and `unsubscribed`. The last one is the hazard: because the
dashboard import updates existing contacts with whatever the file says, a column asserting
`unsubscribed = false` would flip everyone who had opted out back into the list. Leaving the
column out gives the import nothing to overwrite that flag with. Do not add it, and do not "fix"
the export by including it.

After the first import of an existing audience, check that the unsubscribed count has not fallen.
That is the only symptom this mistake produces, and it produces no error.

**6.3 Send the broadcast.** Resend → **Broadcasts** → new broadcast, against that audience.

The From address must be at the verified domain. `noreply@fpl-sidekick.com` is the safe default and
matches the confirmation mail. **If you use an address people can reply to, restore inbound mail
first (2.8)** — the Custom MX switch removed the apex MX records, so a reply to any address at this
domain currently goes nowhere, silently. An announcement inviting questions that then vanish is
worse than one sent from `noreply@`.

Send a test to yourself before sending to the audience, and re-read 5.3: a broadcast is judged on
SPF, DKIM and DMARC exactly like a confirmation, and it goes to every user at once, so a
misconfiguration is not a single lost message.

**Put the Unsubscribe Footer in, and accept what it means.** The broadcast editor offers an
*Unsubscribe Footer* block; it is not added for you. Include it — Resend replaces it with a
per-contact link, records the click, and from then on **skips that contact on every broadcast**.
That is the consequence to be clear-eyed about: a broadcast is not guaranteed to reach everyone.
Someone who opted out of an earlier announcement will not receive the next one, even if it says
the service is closing. For announcements that are rare and mostly informational, that is
acceptable, and it is the trade this runbook makes. If a notice ever *must* reach every account —
a shutdown, a change to the terms — a broadcast is the wrong tool, and reaching opted-out users
means sending it transactionally through Resend's Emails API, which is code this project does not
have. Decide that when the notice exists, not before.

The footer touches only broadcasts: confirmation mail goes out over SMTP through GoTrue and reaches
an unsubscribed address normally, which is correct — a person who declines announcements has not
declined the message that lets them log in.

**One boundary worth keeping straight.** Telling users about changes to a service they use is
service communication, and needs no separate consent under the GDPR; the unsubscribe link is
good practice there rather than a legal requirement. Sending the same audience marketing — offers,
referrals, anything selling — does need consent, and there the link is mandatory. The audience
built here was assembled from people who signed up for the product, so it carries permission for
the first and not the second. If marketing ever happens, it needs its own opt-in and its own
audience.

**Done when:** the row count in 6.1 matches `sent` from the sanity-check query, the audience holds
that many contacts, a test send passes 5.3, and the broadcast is out.

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

This project needs no bounce webhooks for sign-up confirmation: it is one message to one address a
person just typed a moment earlier, and there is no list to prune.

**Broadcasts (Task 6) are where those two numbers can actually be damaged**, because a broadcast
goes to addresses collected months earlier, some of which have since died. Check the Resend
dashboard after each broadcast rather than only after sign-up spikes.

Dead addresses need nothing from you. Resend keeps a **Suppressions** list — an address a mailbox
provider hard-bounces, or whose owner marks the mail as spam, is added to it automatically and
skipped on every later send, transactional and broadcast alike, across all domains on the
account. The export in 6.1 re-derives the list from `auth.users` each time and so will happily
re-import a dead address; the suppression list is what stops it being sent to again, and the
import does not clear it. **A bouncing address is not a reason to touch the account.** The person
may be using the product every day with an inbox they abandoned; deleting the account over a
bounce destroys their data to fix a mail statistic that Resend has already fixed.

## When it fails, read in this order

Each of these fails in a way that looks like a different one.

| Symptom | First thing to check |
|---|---|
| No MX option in Namecheap's dropdown | Trap 4 — Mail Settings is on Email Forwarding. Switch it to Custom MX (2.3) |
| Domain never verifies in Resend | Trap 3 — a doubled domain in the Host field — or a stale `TXT`/`MX` on `send` beside the CNAME (2.3). `dig` the record and read what came back |
| Mail to the domain stopped arriving | Expected after 2.3 removed the forwarding MX records; 2.8 restores them |
| No mail at all, app log says `could not be sent (500)` | Resend shows a sending row *Pending* (2.3–2.4) — this happened 2026-08-28. Otherwise Supabase → Logs → Auth: a `429` is the rate limit (3.2); an SMTP auth error is the username or password (3.1 — the username is the literal `resend`) |
| Mail arrives, link opens a blank page | Site URL (3.3) — almost always this |
| Mail lands in spam | `dig` the SPF and DKIM records, then read `Show original` (5.3) to see which one failed |
| DMARC reads as absent | Two `_dmarc` records, or one at the wrong Host (Traps 2 and 3) |
| Link opens the app but not signed in | Redirect URLs (3.3) must include the exact origin |
| Sign-up answers `503` | `SUPABASE_SERVICE_ROLE_KEY` — set as of 2026-08-28, so this means it was cleared |
| Sign-up refuses every code, including one that exists in `fpl_invite_code` | The domain is missing from `CORS_ORIGINS` (4.1). The frontend shows every `403` as an invalid code; `curl -H 'Origin: https://fpl-sidekick.com'` reveals `Invalid CORS request` |
| Sign-up refuses every code and `curl` with the `Origin` header answers `400` | No code exists (5.1), the code is out of uses, or the 30-account cap is reached |
| Site works, then intermittently does not | Trap 1 — the parking A record was left in place |
| The Task 6 export returns exactly 100 rows, or a round number | The SQL editor's *Row limit* is still on its default (6.1). Set it to *No limit* and run again |
| The Task 6 export returns far fewer rows than there are accounts | Not an export fault. Those accounts were never sent mail — `confirmation_sent_at` is `NULL` because the send failed. Read this table from the top |
| Someone says they never got the announcement | Either they unsubscribed from an earlier one, or Resend's Suppressions list holds their address after a bounce. Both are visible in the Resend dashboard, and neither is fixed by re-importing |
| An announcement reached someone who had unsubscribed | The imported CSV carried an `unsubscribed` column and the import overwrote the flag (6.2). Export one column only |
| Replies to an announcement never arrive | Expected — the apex has no MX records until 2.8 is done. Send announcements from `noreply@` until it is |

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
