-- Drops the three tables left behind when Phase 5a's user tables were renamed.
--
-- PR #4 shipped `conversation`, `message` and `usage_event`; the deploy on merge created them in
-- the shared Supabase project. The schema review that followed pointed out what the app_user
-- argument already implied — a generic name in a schema upstream-stats-app also develops against is a
-- name they can take first, and our ALTER TABLE ... ENABLE ROW LEVEL SECURITY would then quietly
-- close a table they read through PostgREST. So they became fpl_conversation, fpl_message and
-- fpl_usage_event, and the originals are now referenced by nothing.
--
-- Leaving them is not neutral: they sit in a shared schema with RLS enabled, and shared running
-- `CREATE TABLE IF NOT EXISTS conversation` would bind to ours and read zero rows forever.
--
-- Refuses to drop a table with rows in it. They were created after auth shipped but before any
-- user could sign in (public sign-up is disabled), so all three were empty when this was written
-- — the check is here so that fact is verified rather than assumed.
--
--   set -a; source backend/.env.supabase; set +a
--   docker run --rm -i -e PGPASSWORD="$DB_PASSWORD" postgres:17 \
--     psql -h aws-1-eu-west-1.pooler.supabase.com -p 5432 -U "$DB_USER" -d postgres \
--     -v ON_ERROR_STOP=1 < scripts/sql/drop-prerename-tables.sql
DO $$
DECLARE t text; n bigint;
BEGIN
  FOREACH t IN ARRAY ARRAY['message', 'usage_event', 'conversation'] LOOP
    IF to_regclass('public.' || t) IS NULL THEN
      RAISE NOTICE 'already gone: %', t;
      CONTINUE;
    END IF;
    EXECUTE format('SELECT count(*) FROM public.%I', t) INTO n;
    IF n = 0 THEN
      EXECUTE format('DROP TABLE public.%I CASCADE', t);
      RAISE NOTICE 'dropped: %', t;
    ELSE
      RAISE WARNING 'kept % — it holds % rows, so this is not the table the rename orphaned', t, n;
    END IF;
  END LOOP;
END $$;
