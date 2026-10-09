-- Role for the APPLICATION, separate from the role that runs the migrations (Flyway).   *** NOT APPLIED AUTOMATICALLY ***
--
-- Run it by hand, as the migrations owner (in Supabase: `postgres`), AFTER Flyway has applied the migrations, and again after every
-- migration that adds a table (it is idempotent). Never from the application.
--
-- What `app_runtime` is:  a login role that does NOT own the tables, so it can neither ALTER nor DROP them; it has no TRUNCATE; and on
-- the append-only tables (every table with a forbid_row_change trigger: consent_record, consent_revocation, attendance_audit,
-- account_audit, ...) it cannot UPDATE / DELETE what the trigger forbids (password_reset: only DELETE; it must keep setting used_at /
-- revoked_at). The triggers stay as a second line of defence. It has BYPASSRLS because a
-- non-owner role is otherwise subject to RLS and, with no policies, would see zero rows. RLS keeps doing its job against Supabase's
-- `anon` / `authenticated` roles. Tenant isolation is still `coach_id` in the application.
--
-- The password is NOT in this file. After running it, set it separately and keep it out of the repo, the chat and the logs:
--     ALTER ROLE app_runtime PASSWORD '<random password>';
-- In Supabase the application connects through the pooler as `app_runtime.<project-ref>` (session mode, port 5432); Flyway keeps its
-- own credentials (SPRING_FLYWAY_URL / USER / PASSWORD).

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_runtime') THEN
        CREATE ROLE app_runtime LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION BYPASSRLS;
    END IF;
END
$$;

-- re-assert the attributes even if the role already existed with others
ALTER ROLE app_runtime LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION BYPASSRLS;

GRANT USAGE ON SCHEMA public TO app_runtime;
REVOKE CREATE ON SCHEMA public FROM app_runtime;

-- everything that exists today: read and write rows, nothing else (no TRUNCATE, no REFERENCES, no TRIGGER)
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM app_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO app_runtime;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO app_runtime;

-- Flyway's own bookkeeping is not the application's business
REVOKE ALL ON TABLE public.flyway_schema_history FROM app_runtime;

-- append-only tables: found by their forbid_row_change trigger, so a new one is covered the next time this script runs. Only what the
-- trigger forbids is revoked (tgtype bits: 8 = DELETE, 16 = UPDATE): password_reset blocks DELETE / TRUNCATE but must stay UPDATE-able
-- (used_at / revoked_at go from NULL to a value).
DO $$
DECLARE r record;
BEGIN
    FOR r IN
        SELECT tg.tgrelid::regclass AS tbl, bool_or((tg.tgtype & 16) <> 0) AS blocks_update, bool_or((tg.tgtype & 8) <> 0) AS blocks_delete
        FROM pg_trigger tg
        JOIN pg_proc p ON p.oid = tg.tgfoid
        WHERE p.proname = 'forbid_row_change' AND NOT tg.tgisinternal
        GROUP BY tg.tgrelid
    LOOP
        IF r.blocks_update THEN
            EXECUTE format('REVOKE UPDATE ON TABLE %s FROM app_runtime', r.tbl);
        END IF;
        IF r.blocks_delete THEN
            EXECUTE format('REVOKE DELETE ON TABLE %s FROM app_runtime', r.tbl);
        END IF;
    END LOOP;
END
$$;

-- tables created later by the migrations owner (the role running this script) get the same base grants; the append-only revokes
-- above must be re-applied by re-running this script after such a migration
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO app_runtime;
