-- Everyone this project has ever sent mail to, in the shape Resend's contact import wants.
--
-- Run it in the Supabase SQL editor (SQL Editor -> new query), then use the result grid's
-- "Download CSV" button and upload that file to a Resend Audience. docs/EMAIL-SETUP.md Task 6
-- is the full procedure; this file is only the query.
--
-- Not a psql script and not a shell script on purpose. auth.users is owned by GoTrue and the
-- SQL editor runs as postgres, so it reads the table without granting anything to the pooler
-- role the application uses. There is also nothing here to schedule: the export is run at the
-- moment an announcement is sent, which is what keeps it from drifting out of date.

-- confirmation_sent_at is the predicate that answers the actual question. GoTrue stamps it only
-- once the message has been handed to the SMTP provider, so an account whose send failed keeps
-- it NULL -- observed on 2026-08-28, when every send failed while Resend's sending records were
-- still pending. "Has an account" and "has been sent mail" are therefore different sets, and
-- this is the second one.
--
-- Confirmed and unconfirmed addresses both belong here: an unconfirmed account received the
-- confirmation message, which is what makes the address one we have written to.
--
-- ONE COLUMN, DELIBERATELY. Resend also accepts first_name, last_name and unsubscribed, and
-- unsubscribed is the one that must never appear: a dashboard import updates contacts it already
-- has, so a column asserting unsubscribed = false would resurrect everyone who had opted out.
-- Leaving the column out gives the import nothing to overwrite that flag with. We have no names
-- to export either -- fpl_user_profile deliberately keeps no email or name, and auth.users holds
-- no name for a password sign-up.
SELECT u.email AS email
FROM auth.users u
WHERE u.email IS NOT NULL
  AND u.confirmation_sent_at IS NOT NULL
  AND u.deleted_at IS NULL
  AND (u.banned_until IS NULL OR u.banned_until < NOW())
ORDER BY u.email;

-- If either of the last two predicates fails with "column does not exist", this project's GoTrue
-- version predates it. Drop that line rather than working around it; both are narrowing filters
-- and a version without the column has nothing for them to exclude.

-- Sanity check before importing: run this too and read the three numbers together. sent is the
-- row count the export above should have produced, and accounts - sent is the number of people
-- who signed up and never got the message. A non-zero gap that is not explained by a known
-- outage is a mail fault, not an export fault -- read docs/EMAIL-SETUP.md "When it fails".
--
-- SELECT count(*)                                            AS accounts,
--        count(*) FILTER (WHERE confirmation_sent_at IS NOT NULL) AS sent,
--        count(*) FILTER (WHERE email_confirmed_at IS NOT NULL)   AS confirmed
-- FROM auth.users
-- WHERE deleted_at IS NULL;
