# KAR-44 — A real welcome/confirmation email

**PROGRESS:** Task 0/3 — plan written, nothing implemented.

Issue: [KAR-44](https://linear.app/quantti/issue/KAR-44/write-a-real-welcomeconfirmation-email-instead-of-supabases-default).
Checked in Linear on 2026-09-12: Todo, High (2), related to KAR-33 (the runbook) and KAR-43
(inbound mail, still dead). The first confirmation mail (2026-08-29) was Supabase's stock
*Confirm Your Signup* template, and it is the first thing every tester receives.

Baseline: `origin/master` at `13be2ae`. Branch `kar-44-confirm-signup-email`, own worktree.
This ticket is one HTML file and one runbook edit; the deliverable that closes it — the mail
landing in Gmail's inbox — is a dashboard paste and a real sign-up the owner does after the PR.

## Goal and scope

Commit the Confirm-signup template as `docs/email-templates/confirm-signup.html` with its subject
in a comment at the top, make `docs/EMAIL-SETUP.md` say to paste it and record when it was, and
hand the owner an exact acceptance procedure. No code changes: `SupabaseAdminClient` already
creates the account unconfirmed and asks GoTrue to send its own mail through the project's SMTP
and template, so this template *is* the welcome mail. One mail, not two.

## Facts that shape the template

- **Where it lives:** Supabase → Authentication → Email Templates → *Confirm signup* has two
  fields, *Subject heading* and *Message body*, HTML only. GoTrue offers no plain-text
  alternative, so the HTML must read sensibly with its tags stripped.
- **Variables:** `{{ .ConfirmationURL }}` is required. `{{ .Email }}` (the address signed up)
  is available on this template and is used once, in the footer. `{{ .SiteURL }}` is not used:
  the text names `fpl-sidekick.com` outright so a wrong Site URL cannot rewrite it.
- **The link is a `supabase.co` URL**, not one on the sender's domain
  (`https://<ref>.supabase.co/auth/v1/verify?token=…&type=signup&redirect_to=https://fpl-sidekick.com`).
  That is unavoidable without a custom auth domain (a paid Supabase add-on, not taken), so the
  mail says so in a sentence rather than letting the reader discover it on hover.
- **Replies go nowhere.** The sender is `noreply@fpl-sidekick.com`; the apex has no MX and no
  forwarding SPF since the Custom MX switch (runbook 2.8, KAR-43), so a reply neither bounces
  usefully nor arrives. The body must name a real address and say replies are not read.
- **Runbook numbering:** the ticket says "3.3", but in the current `docs/EMAIL-SETUP.md` 3.3 is
  *URL Configuration* and the template step is **3.4**. Edit 3.4.
- **Gmail:** clips a message above 102 KB, ignores `<style>` in several clients, blocks nothing
  in a mail with no images. Inline CSS, single 600 px column, system font stack, no images, no
  external assets, no `<script>`.

## Decisions

1. **Reply address: `kvanttinen@gmail.com`**, the owner's address already recorded in
   `docs/STATUS.md` (the Fly login). It goes in the body as a `mailto:` link whose text is the
   address itself. **Confirm with the owner before Task 1** — it is their personal address in
   every tester's inbox. The alternative is to wait for KAR-43 and use an address on the domain;
   that blocks this High-priority ticket on a Medium one, so the plan does not wait.
2. **Subject:** `Welcome to FPL Sidekick — confirm your email`, the ticket's own suggestion. It
   names the service, states the action, and reads nothing like "Confirm your user".
3. **The visible link text is the URL itself.** The button says *Confirm my email*; under it the
   same `{{ .ConfirmationURL }}` appears as an anchor whose text is its own href, so no link
   text differs from its target, and a text-only render still carries a usable link.
4. **Manual paste, not the Management API.** `PATCH /v1/projects/{ref}/config/auth` can set
   `mailer_subjects_confirmation` and `mailer_templates_confirmation_content`, which would make
   the repository the source of truth. It needs a personal access token this project does not
   hold and a script for a template that changes once a year. Not now; noted in STATUS.
5. **No new script.** The local render check is a `sed` one-liner in this plan, run from the
   scratchpad. The lint is four `grep`s.
6. **Copy is English, matches the app:** "The free plan is 20 messages a week" is the sign-in
   page's own sentence (`SignIn.tsx:200`); "Check your email … follow it, then sign in" is the
   post-sign-up screen, and the mail's "brings you back to fpl-sidekick.com signed in" agrees
   with runbook 5.2's "Lands on https://fpl-sidekick.com, signed in".

## Task 1 — The template

**Files:** create `docs/email-templates/confirm-signup.html`.

**Step 1 — write the file**, exactly this:

```html
<!--
  Supabase -> Authentication -> Email Templates -> Confirm signup      (KAR-44)

  Subject heading:  Welcome to FPL Sidekick — confirm your email
  Message body:     everything from the first <div> below to the end of the file.

  Keep {{ .ConfirmationURL }} (GoTrue fills it; it appears twice, button and plain link) and
  {{ .Email }} (the address that signed up). Inline CSS only: Gmail drops <style> blocks.
  When this file changes, paste it again and record the date in docs/EMAIL-SETUP.md.
-->
<div style="margin:0;padding:24px 16px;background-color:#f4f5f7;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;font-size:16px;line-height:1.5;color:#1f2933;">
  <table role="presentation" cellpadding="0" cellspacing="0" border="0" width="100%" style="max-width:600px;margin:0 auto;background-color:#ffffff;border-radius:8px;">
    <tr>
      <td style="padding:32px 32px 8px 32px;">
        <p style="margin:0 0 16px 0;font-size:22px;font-weight:700;color:#1f2933;">Welcome to FPL Sidekick</p>
        <p style="margin:0 0 16px 0;">FPL Sidekick is a chat assistant for Fantasy Premier League. Ask it about players, prices, fixtures and your own squad, and it answers from live FPL data and current stats. The free plan is 20 messages a week.</p>
        <p style="margin:0 0 24px 0;">One step first: confirm that this is your email address.</p>
        <p style="margin:0 0 24px 0;">
          <a href="{{ .ConfirmationURL }}" style="display:inline-block;padding:12px 24px;background-color:#2f6fed;color:#ffffff;text-decoration:none;font-weight:600;border-radius:6px;">Confirm my email</a>
        </p>
        <p style="margin:0 0 8px 0;font-size:14px;color:#52606d;">If the button does not work, open this link:</p>
        <p style="margin:0 0 24px 0;font-size:14px;word-break:break-all;"><a href="{{ .ConfirmationURL }}" style="color:#2f6fed;">{{ .ConfirmationURL }}</a></p>
        <p style="margin:0 0 16px 0;">The link goes through our sign-in provider (supabase.co) and brings you back to fpl-sidekick.com signed in. It works once.</p>
        <p style="margin:0 0 16px 0;">Problems or questions? Write to <a href="mailto:kvanttinen@gmail.com" style="color:#2f6fed;">kvanttinen@gmail.com</a>. Replies to this message are not read.</p>
      </td>
    </tr>
    <tr>
      <td style="padding:16px 32px 32px 32px;border-top:1px solid #e4e7eb;font-size:13px;line-height:1.5;color:#7b8794;">
        <p style="margin:0 0 8px 0;">You received this because someone signed up for FPL Sidekick with {{ .Email }} using an invite code. If that was not you, ignore this message: nothing happens without the link, and the account cannot be used.</p>
        <p style="margin:0;">FPL Sidekick &middot; fpl-sidekick.com</p>
      </td>
    </tr>
  </table>
</div>
```

**Step 2 — lint.** From the worktree root; every line names its expected output:

```bash
f=docs/email-templates/confirm-signup.html
grep -n '^  Subject heading:' "$f"                     # one line, the subject
sed '1,/^-->$/d' "$f" > /tmp/confirm-signup.body.html  # the part that gets pasted, comment dropped
b=/tmp/confirm-signup.body.html
grep -c '{{ .ConfirmationURL }}' "$b"                  # 2
grep -c '{{ .Email }}' "$b"                            # 1
grep -ciE '<style|<link|<img|<script|url\(' "$b"       # 0
grep -c 'http://' "$b"                                 # 0  (only https and mailto)
wc -c "$b"                                             # a few KB; Gmail clips at 102400
```

The header comment mentions `<style>` and both variables, which is why the counts run on the
body alone.

**Step 3 — render locally** with a sample URL and address, and look at it at 600 px and 390 px:

```bash
SCRATCHPAD="${SCRATCHPAD:-/tmp/kar-44}"; mkdir -p "$SCRATCHPAD"   # use the session scratchpad when one is given
out="$SCRATCHPAD/confirm-signup.preview.html"
sed -e 's|{{ .ConfirmationURL }}|https://abcdefgh.supabase.co/auth/v1/verify?token=pkce_0123456789abcdef0123456789abcdef01234567\&type=signup\&redirect_to=https://fpl-sidekick.com|g' \
    -e 's|{{ .Email }}|tester@example.com|g' "$b" > "$out"
for w in 600 390; do
  "$(command -v chromium || command -v chromium-browser || command -v google-chrome)" \
    --headless --disable-gpu --screenshot="$SCRATCHPAD/confirm-signup-$w.png" \
    --window-size=$w,1000 "file://$out"
done
```

Read both screenshots: one column, button visible, the long URL wrapped (not overflowing), the
footer under a rule. If no Chromium binary exists on the machine, open `$out` in the desktop
browser instead and say so in the STATUS entry.

**Step 4 — text-only read.** The HTML must read as a letter when tags are gone:

```bash
sed -e 's/<[^>]*>//g' "$out" | sed '/^\s*$/d'
```

Expected: heading, three sentences, the URL on its own line, the supabase.co sentence, the
contact line, the footer. Nothing repeated, nothing orphaned like "If the button does not work"
with no URL after it.

**Step 5 — commit.**

```bash
git add docs/email-templates/confirm-signup.html
git commit -m "Add the Confirm-signup email template as a repository file"
```

## Task 2 — The runbook says to paste it, and where to record that it was

**Files:** `docs/EMAIL-SETUP.md`.

**Step 1 — status table** (the `| | State |` table under "Where this stands"). Add a row after
`Mail provider`:

```markdown
| Confirm signup template | **committed, not applied** — `docs/email-templates/confirm-signup.html` (KAR-44). Supabase still sends its stock *Confirm Your Signup* until 3.4 is done; write the date here when it is |
```

**Step 2 — 3.4.** Replace the paragraph that begins `**3.4 Authentication → Email Templates →
Confirm signup.**` with:

```markdown
**3.4 Authentication → Email Templates → Confirm signup.** The template is in this repository,
`docs/email-templates/confirm-signup.html`, and the dashboard is a copy of it, never the other
way round. Paste the subject from the comment at the top of the file into *Subject heading*,
and the file from its first `<div>` to the end into *Message body* (the comment above it need
not go). The body must keep `{{ .ConfirmationURL }}`; the file carries it twice, as a button and
as a visible link, because some clients strip buttons. Save, then write today's date in the
status table above and run 5.2 and 5.3 again — a template change is a deliverability change, and
Gmail judges the new one on its own.
```

**Step 3 — 5.2 table.** Change the *Inbox* row to:

```markdown
| Inbox | Message from `noreply@fpl-sidekick.com` with the subject *Welcome to FPL Sidekick — confirm your email*, within a minute, in Primary — not Promotions, not spam |
```

and add a row after *Follow the link*:

```markdown
| Open the plain link under the button instead | Same result — both confirm the account |
```

**Step 4 — "When it fails" table.** Add a row after `Mail arrives, link opens a blank page`:

```markdown
| Mail arrives with the subject *Confirm Your Signup* | 3.4 was never applied, or the dashboard was reset: it still holds Supabase's stock template |
```

**Step 5 — Tickets list** at the end: add `- **KAR-44** — the confirmation template, committed as
`docs/email-templates/confirm-signup.html`.` and `- **KAR-43** — inbound mail; until it is done the
template names a personal address for replies.`

**Step 6 — check, commit.**

```bash
grep -n "confirm-signup.html\|KAR-44" docs/EMAIL-SETUP.md     # status row, 3.4, tickets
git add docs/EMAIL-SETUP.md
git commit -m "Runbook: paste the committed Confirm-signup template, record when"
```

## Task 3 — STATUS, PR, and the owner's acceptance

**Files:** `docs/STATUS.md`, this plan.

**Step 1 — STATUS entry** at the end of `docs/STATUS.md`:

```markdown
## The confirmation mail is the welcome mail (KAR-44, <date>)

Plan: `docs/superpowers/plans/2026-09-12-kar-44-confirm-signup-email.md`. The Confirm-signup
template is now a repository file, `docs/email-templates/confirm-signup.html`, with the subject
in its header comment; the dashboard holds a copy. It says what FPL Sidekick is and what the free
plan gives, shows the link as a button and as a visible URL whose text is its own target, says
the link passes through supabase.co and lands on fpl-sidekick.com signed in, names a real
address for problems because replies to `noreply@` go nowhere until KAR-43, and ends with why
the mail was sent and that ignoring it does nothing. Inline CSS, one 600 px column, no images:
Gmail's constraints, not taste. Rendered at 600 and 390 px and read tag-stripped; <how>.

Not done here, by design: the dashboard paste and the Gmail inbox/SPF/DKIM/DMARC check are the
owner's, per runbook 3.4 and 5.2–5.3; the status table row records the date. Supabase's
Management API could apply the template from the repository, but it needs a personal access
token the project does not hold, so the repository stays the source and the paste stays manual.
```

**Step 2 — plan progress, commit, push, PR.**

Set `**PROGRESS:** Task 3/3 done — awaiting the owner's paste and Gmail check.` at the top of
this file.

```bash
git add docs/STATUS.md docs/superpowers/plans/2026-09-12-kar-44-confirm-signup-email.md
git commit -m "Close out KAR-44 in STATUS and the plan"
git push -u origin kar-44-confirm-signup-email
```

PR title: `Commit the Confirm-signup email template and runbook step`. Body, at most eight
lines: what changed, why, how verified (lint, render at two widths, text read), plan path,
`Closes KAR-44`, attribution lines. Never push `master`. The suite runs on the PR; nothing here
touches code.

**Step 3 — owner's acceptance, after merge** (the ticket's "Done when"; not the agent's):

1. Paste subject and body per 3.4; save.
2. Sign up with a fresh Gmail address on a device not signed in (5.2). Confirm: Primary inbox,
   subject as decided, renders in Gmail web and on one phone client, the button confirms, and
   — on a second fresh address — the plain link confirms.
3. *Show original*: SPF, DKIM, DMARC all PASS (5.3).
4. Write the date in the status table row and, if anything in the copy was changed in the
   dashboard, change the file first and paste again — the file is the source.

## Self-review

- Ticket coverage: subject (Decision 2), what/what-you-get (body ¶2), button and plain URL
  (Decision 3), what happens after (supabase.co sentence), reply address (Decision 1), footer
  (last block), inline CSS ≤ 600 px system fonts (template), text readability (Task 1 Step 4),
  no misleading link text and no phishing-shaped subject (Decisions 2–3), English, committed
  under `docs/email-templates/` with the subject in a comment, runbook 3.4 and status table
  (Task 2), "Done when" as the owner's procedure (Task 3 Step 3).
- One owner decision before starting: the reply address.
