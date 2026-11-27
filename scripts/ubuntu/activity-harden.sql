-- Hardening of one company's activity log (ACTIVITY_LOG.md §9). Run by activity-harden.sh as the
-- PostgreSQL superuser, connected to the company database, after
--     SELECT set_config('bms.runtime_user', '<runtime role>', false);
-- Idempotent. It gives activity_log, its chain head, its sequence and its trigger functions to the
-- NOLOGIN role bms_audit (which the runtime role is not a member of), leaves the runtime role only
-- SELECT/INSERT (and the sequence), and installs event triggers that refuse dropping or moving those
-- objects. A database without activity_log yet (before tenant V011) is left untouched.
BEGIN;
-- Same lock as the migrations, so a migration of this database waits for (or is waited by) this.
SELECT pg_advisory_xact_lock(735192401);
DO $harden$
DECLARE
    runtime TEXT := current_setting('bms.runtime_user');
    target REGCLASS;
    grantee OID;
BEGIN
    IF to_regclass('public.activity_log') IS NULL THEN
        RAISE NOTICE 'activity_log does not exist yet: nothing to harden';
        RETURN;
    END IF;
    IF current_setting('server_version_num')::INT < 160000 THEN
        -- Before PostgreSQL 16 a CREATEROLE role (the server owner's) could grant itself bms_audit.
        RAISE EXCEPTION 'PostgreSQL 16 or later is required to harden the activity log';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'bms_audit') THEN
        RAISE EXCEPTION 'role bms_audit does not exist';
    END IF;
    IF runtime = '' OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = runtime) THEN
        RAISE EXCEPTION 'runtime role % does not exist', runtime;
    END IF;
    IF pg_has_role(runtime, 'bms_audit', 'MEMBER') THEN
        RAISE EXCEPTION 'runtime role % is a member of bms_audit: refusing to harden', runtime;
    END IF;

    EXECUTE 'CREATE SCHEMA IF NOT EXISTS bms_audit AUTHORIZATION bms_audit';
    EXECUTE 'REVOKE ALL ON SCHEMA bms_audit FROM PUBLIC';
    -- The sealing function (SECURITY DEFINER, now bms_audit's) reaches the table through public.
    EXECUTE 'GRANT USAGE ON SCHEMA public TO bms_audit';

    EXECUTE 'ALTER TABLE public.activity_log OWNER TO bms_audit';
    EXECUTE 'ALTER TABLE public.activity_log_chain_head OWNER TO bms_audit';
    EXECUTE 'ALTER SEQUENCE public.activity_log_id_seq OWNER TO bms_audit';
    EXECUTE 'ALTER FUNCTION public.activity_log_append_only() OWNER TO bms_audit';
    EXECUTE 'ALTER FUNCTION public.activity_log_before_insert() OWNER TO bms_audit';
    EXECUTE 'ALTER FUNCTION public.activity_log_seal() OWNER TO bms_audit';
    EXECUTE 'REVOKE ALL ON FUNCTION public.activity_log_seal() FROM PUBLIC';

    -- Nobody keeps anything on these objects but their owner, then the runtime role gets its two rights.
    FOREACH target IN ARRAY ARRAY['public.activity_log', 'public.activity_log_chain_head', 'public.activity_log_id_seq']::REGCLASS[] LOOP
        FOR grantee IN
            SELECT DISTINCT acl.grantee FROM pg_class c, aclexplode(c.relacl) acl
            WHERE c.oid = target AND acl.grantee <> c.relowner
        LOOP
            EXECUTE format('REVOKE ALL ON %s %s FROM %s',
                CASE WHEN target = 'public.activity_log_id_seq'::REGCLASS THEN 'SEQUENCE' ELSE 'TABLE' END,
                target, CASE WHEN grantee = 0 THEN 'PUBLIC' ELSE quote_ident(pg_get_userbyid(grantee)) END);
        END LOOP;
    END LOOP;
    EXECUTE format('GRANT SELECT, INSERT ON public.activity_log TO %I', runtime);
    EXECUTE format('GRANT USAGE, SELECT ON SEQUENCE public.activity_log_id_seq TO %I', runtime);
    -- Read only: the server's verification compares the head with the chain.
    EXECUTE format('GRANT SELECT ON public.activity_log_chain_head TO %I', runtime);

    -- The schema public belongs to the database owner (the server owner's role, in the runtime's
    -- membership), and a schema owner may drop what is in it: these event triggers refuse that.
    EXECUTE $guard$
        CREATE OR REPLACE FUNCTION bms_audit.activity_log_guard() RETURNS event_trigger
        LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $body$
        DECLARE
            dropped RECORD;
        BEGIN
            IF (SELECT rolsuper FROM pg_catalog.pg_roles WHERE rolname = current_user) THEN
                RETURN;
            END IF;
            IF TG_EVENT = 'sql_drop' THEN
                FOR dropped IN SELECT * FROM pg_catalog.pg_event_trigger_dropped_objects() LOOP
                    IF pg_catalog.strpos(COALESCE(dropped.object_identity, ''), 'public.activity_log') > 0 THEN
                        RAISE EXCEPTION 'the activity log cannot be dropped (%)', dropped.object_identity
                            USING ERRCODE = 'insufficient_privilege';
                    END IF;
                END LOOP;
            ELSIF pg_catalog.to_regclass('public.activity_log') IS NULL THEN
                RAISE EXCEPTION 'the activity log must stay in the schema public' USING ERRCODE = 'insufficient_privilege';
            END IF;
        END;
        $body$
    $guard$;
    EXECUTE 'ALTER FUNCTION bms_audit.activity_log_guard() OWNER TO bms_audit';
    IF NOT EXISTS (SELECT 1 FROM pg_event_trigger WHERE evtname = 'bms_activity_log_no_drop') THEN
        EXECUTE 'CREATE EVENT TRIGGER bms_activity_log_no_drop ON sql_drop EXECUTE FUNCTION bms_audit.activity_log_guard()';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_event_trigger WHERE evtname = 'bms_activity_log_no_move') THEN
        EXECUTE $et$CREATE EVENT TRIGGER bms_activity_log_no_move ON ddl_command_end WHEN TAG IN ('ALTER SCHEMA')
            EXECUTE FUNCTION bms_audit.activity_log_guard()$et$;
    END IF;
    EXECUTE 'ALTER EVENT TRIGGER bms_activity_log_no_drop ENABLE';
    EXECUTE 'ALTER EVENT TRIGGER bms_activity_log_no_move ENABLE';
END;
$harden$;
COMMIT;
