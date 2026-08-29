-- Everyone this project has ever sent mail to, in the shape Resend's contact import wants.
--
-- Run it in the Supabase SQL editor (SQL Editor -> new query), then use the result grid's
-- CSV download and upload that file to a Resend Audience. docs/EMAIL-SETUP.md Task 6 is the
-- full procedure; this file is only the query.
--
-- SET THE EDITOR'S ROW LIMIT TO "NO LIMIT" FIRST. The results pane has a Row limit menu that
-- defaults to 100 rows, and the download contains only the rows the grid holds. The only sign is
-- a small "(Limited to only 100 rows)" beside the row count -- no error, and an announcement that
-- reaches the first hundred addresses in alphabetical order. Confirmed from Studio's source on
-- 2026-08-29 (sql-editor-session-state.ts, `limit: 100`; options No limit / 100 / 500 / 1,000).
--
-- Not a psql script and not a shell script on purpose, and not for the tooling reason that the
-- application could not read auth.users -- it connects as postgres and could. It is the same
-- line the runbook takes in 5.1: the SQL editor is the one place this project touches production
-- by hand, so a one-off read belongs there rather than in a script that needs the production
-- password on a host with no psql. There is also nothing to schedule: the export is run at the
-- moment an announcement is sent, which is what keeps it from drifting out of date.

-- Sent-at columns are the predicate that answers the actual question. GoTrue sets them only after
-- the message has been handed to the SMTP provider -- sendConfirmation in internal/api/mail.go
-- calls sendEmail first and stamps confirmation_sent_at on success, and every other sender is
-- built the same way. So an account whose send failed keeps the column NULL, which is what was
-- observed on 2026-08-28 while Resend's sending records were still pending. "Has an account" and
-- "has been sent mail" are therefore different sets, and this is the second one.
--
-- Two columns cover every mail this project can send. confirmation_sent_at is stamped by sign-up
-- and by a Supabase-dashboard invite alike (sendInvite sets it too). recovery_sent_at is stamped
-- by password recovery and magic links; no such flow exists in the frontend yet, and it is here
-- so the export stays correct on the day one does. Email-change mail goes to an address that is
-- not yet in the email column, so it is not a case this query needs to cover.
--
-- Confirmed and unconfirmed addresses both belong here: an unconfirmed account received the
-- confirmation message, which is what makes the address one we have written to.
--
-- ONE COLUMN, DELIBERATELY. Resend also accepts first_name, last_name and unsubscribed, and
-- unsubscribed is the one that must never appear: a dashboard import always updates contacts it
-- already holds (there is no skip option in the dashboard, only in the API), so a column
-- asserting unsubscribed = false would resurrect everyone who had opted out. Leaving the column
-- out gives the import nothing to overwrite that flag with. We have no names to export either --
-- fpl_user_profile deliberately keeps no email or name, and auth.users holds no name for a
-- password sign-up.
SELECT u.email AS email
FROM auth.users u
WHERE u.email IS NOT NULL
  AND (u.confirmation_sent_at IS NOT NULL OR u.recovery_sent_at IS NOT NULL)
  AND u.deleted_at IS NULL
  AND (u.banned_until IS NULL OR u.banned_until < NOW())
ORDER BY u.email;

-- Sanity check before importing: run this too and read the numbers together. Its WHERE clause is
-- the export's, minus the sent-at test, so `sent` is exactly the row count the export above
-- produced and `accounts - sent` is the number of people who signed up and never got the
-- message. A non-zero gap that is not explained by a known outage is a mail fault, not an export
-- fault -- read docs/EMAIL-SETUP.md "When it fails".
--
-- SELECT count(*) AS accounts,
--        count(*) FILTER (WHERE confirmation_sent_at IS NOT NULL
--                            OR recovery_sent_at IS NOT NULL) AS sent,
--        count(*) FILTER (WHERE email_confirmed_at IS NOT NULL) AS confirmed
-- FROM auth.users
-- WHERE email IS NOT NULL
--   AND deleted_at IS NULL
--   AND (banned_until IS NULL OR banned_until < NOW());
