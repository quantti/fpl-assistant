-- Close the PostgREST door on the shared Supabase project.
--
-- Supabase publishes the `public` schema as a REST API, and `anon` / `authenticated` hold
-- SELECT/INSERT/UPDATE/DELETE/TRUNCATE on every table there by default. The only thing that
-- constrains those grants is row level security, and it was off on 34 of 35 tables (measured
-- 2026-08-19: the anon key read `player`, `fpl_daily_price` and `chip_usage` straight out of
-- /rest/v1). The key is public by design — Phase 5a Task 6 ships it in the frontend bundle —
-- so this has to be closed before that lands.
--
-- Neither application loses anything: fpl-assistant and upstream-stats-app both connect as `postgres`,
-- which has rolbypassrls = true. RLS with no policies is deny-all for the PostgREST roles and a
-- no-op for us.
--
-- Run it:
--   set -a; source backend/.env.supabase; set +a
--   docker run --rm -i -e PGPASSWORD="$DB_PASSWORD" postgres:17 \
--     psql -h aws-1-eu-west-1.pooler.supabase.com -p 5432 -U "$DB_USER" -d postgres \
--     -v ON_ERROR_STOP=1 < scripts/sql/enable-rls.sql
--
-- Reverting is ALTER TABLE ... DISABLE ROW LEVEL SECURITY, table by table.

-- 1. RLS on every public table that lacks it. No policies means anon and authenticated match
--    no rows at all — reads come back empty, writes are refused.
DO $$
DECLARE t regclass;
BEGIN
  FOR t IN SELECT c.oid::regclass FROM pg_class c
           JOIN pg_namespace n ON n.oid = c.relnamespace
           WHERE n.nspname = 'public' AND c.relkind = 'r' AND NOT c.relrowsecurity
  LOOP
    EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', t);
    RAISE NOTICE 'RLS enabled: %', t;
  END LOOP;
END $$;

-- 2. Tables created from now on must not inherit the grants either. Only covers tables created
--    by `postgres` — which is what both applications connect as, so it covers ours. Existing
--    grants are left in place: with RLS on they grant access to zero rows, and revoking them
--    outright is a separate decision.
ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON TABLES FROM anon, authenticated;

-- 3. chip_usage was the one table that already had RLS, carrying a policy that granted PUBLIC
--    — so, the anon key — an unrestricted read of all 75 rows. Nothing reads it that way: shared'
--    frontend talks to its own backend, and that backend connects to Postgres directly. Removed
--    on the owner's decision 2026-08-19: an unexplained hole in an otherwise closed wall reads
--    as an oversight a year from now. Reinstating it is one CREATE POLICY.
DROP POLICY IF EXISTS "Allow public read access" ON public.chip_usage;

-- Verification, printed by the run:
SELECT count(*) FILTER (WHERE relrowsecurity) || ' of ' || count(*) || ' public tables have RLS'
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public' AND c.relkind = 'r';
