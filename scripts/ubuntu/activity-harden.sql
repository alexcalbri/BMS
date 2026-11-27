-- Hardening of one company's activity log (ACTIVITY_LOG.md §9). Fed by activity-harden.sh to the PostgreSQL
-- superuser through pg_super (pg-safe.sh: role none, search_path = pg_catalog, pg_temp, every per-database
-- setting checked first), only when the deviation query found something to do, with -v db_user=<runtime role>.
-- The line "-- @canonical@" is replaced by the body of the tenant migrations that define the log
-- (V011__create_activity_log.sql, later ones in order), which are fully schema-qualified.
--
-- The server owner's role owned these objects until now and could have changed anything about them, so
-- nothing existing is trusted: everything that is not exactly the migration's is removed (triggers, rules,
-- policies, row security, inheritance, foreign keys, extra columns, constraints and indexes, function
-- overloads), the functions, triggers and indexes are created again from the migration, then everything is
-- given to bms_audit with only the runtime role's SELECT/INSERT left. Nothing of that role's runs here: the
-- tables are locked and checked to be plain tables before any row is read, and every name is qualified
-- (operators resolve only in pg_catalog: the search path is pinned and pg_temp is never searched for them).
-- A lock not obtained within lock_timeout fails this run; the timer tries again an hour later.
SET lock_timeout = '3s';
BEGIN;
-- Same lock as the migrations, so a migration of this database waits for (or is waited by) this.
SELECT pg_catalog.pg_advisory_xact_lock(735192401);
SELECT pg_catalog.set_config('bms.runtime_user', :'db_user', true);

DO $prepare$
DECLARE
    runtime TEXT := pg_catalog.current_setting('bms.runtime_user');
    audit_oid OID;
    item RECORD;
    rel TEXT;
    first_time BOOLEAN;
BEGIN
    IF pg_catalog.current_setting('search_path') <> 'pg_catalog, pg_temp' OR current_user <> session_user
       OR NOT (SELECT r.rolsuper FROM pg_catalog.pg_roles r WHERE r.rolname = current_user) THEN
        RAISE EXCEPTION 'activity-harden.sql must run as the superuser itself with search_path = pg_catalog, pg_temp';
    END IF;
    IF pg_catalog.current_setting('server_version_num')::INT < 160000 THEN
        -- Before PostgreSQL 16 a CREATEROLE role (the server owner's) could grant itself bms_audit.
        RAISE EXCEPTION 'PostgreSQL 16 or later is required to harden the activity log';
    END IF;
    SELECT r.oid INTO audit_oid FROM pg_catalog.pg_roles r WHERE r.rolname = 'bms_audit';
    IF audit_oid IS NULL THEN
        RAISE EXCEPTION 'role bms_audit does not exist';
    END IF;
    IF runtime = '' OR NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles r WHERE r.rolname = runtime) THEN
        RAISE EXCEPTION 'runtime role % does not exist', runtime;
    END IF;
    IF pg_catalog.pg_has_role(runtime, 'bms_audit', 'MEMBER') THEN
        RAISE EXCEPTION 'runtime role % is a member of bms_audit: refusing to harden', runtime;
    END IF;
    IF pg_catalog.to_regclass('public.activity_log') IS NULL THEN
        RAISE EXCEPTION 'public.activity_log does not exist';
    END IF;

    -- First: nobody changes these objects while they are checked and rebuilt.
    FOR rel IN SELECT c.oid::pg_catalog.regclass::TEXT FROM pg_catalog.pg_class c
               WHERE c.relnamespace = 'public'::pg_catalog.regnamespace
                 AND c.relname IN ('activity_log', 'activity_log_chain', 'activity_log_chain_head') LOOP
        EXECUTE pg_catalog.format('LOCK TABLE %s IN ACCESS EXCLUSIVE MODE', rel);
    END LOOP;
    FOR item IN SELECT c.relname, c.relkind FROM pg_catalog.pg_class c
                WHERE c.relnamespace = 'public'::pg_catalog.regnamespace
                  AND c.relname IN ('activity_log', 'activity_log_chain', 'activity_log_chain_head') AND c.relkind <> 'r' LOOP
        RAISE EXCEPTION 'public.% is not a plain table (relkind %): repair it by hand', item.relname, item.relkind;
    END LOOP;

    -- Whether the server owner's role had control until now: then this is the (re)start of the guarantee.
    first_time := EXISTS (
        SELECT 1 FROM pg_catalog.pg_class c
        WHERE c.relnamespace = 'public'::pg_catalog.regnamespace
          AND c.relname IN ('activity_log', 'activity_log_chain', 'activity_log_chain_head', 'activity_log_id_seq')
          AND c.relowner <> audit_oid
        UNION ALL
        SELECT 1 FROM pg_catalog.pg_proc p
        WHERE p.pronamespace = 'public'::pg_catalog.regnamespace
          AND p.proname IN ('activity_log_append_only', 'activity_log_before_insert', 'activity_log_seal') AND p.proowner <> audit_oid
    ) OR pg_catalog.to_regclass('public.activity_log_chain') IS NULL OR pg_catalog.to_regclass('public.activity_log_chain_head') IS NULL;
    PERFORM pg_catalog.set_config('bms.harden_first', CASE WHEN first_time THEN 'on' ELSE 'off' END, true);

    -- Inheritance and partitions, both ways.
    FOR item IN SELECT i.inhrelid::pg_catalog.regclass::TEXT AS child, i.inhparent::pg_catalog.regclass::TEXT AS parent,
                       p.relkind AS parent_kind
                FROM pg_catalog.pg_inherits i JOIN pg_catalog.pg_class p ON p.oid = i.inhparent
                WHERE i.inhrelid IN (SELECT c.oid FROM pg_catalog.pg_class c WHERE c.relnamespace = 'public'::pg_catalog.regnamespace
                                     AND c.relname IN ('activity_log', 'activity_log_chain', 'activity_log_chain_head'))
                   OR i.inhparent IN (SELECT c.oid FROM pg_catalog.pg_class c WHERE c.relnamespace = 'public'::pg_catalog.regnamespace
                                      AND c.relname IN ('activity_log', 'activity_log_chain', 'activity_log_chain_head')) LOOP
        IF item.parent_kind = 'p' THEN
            EXECUTE pg_catalog.format('ALTER TABLE %s DETACH PARTITION %s', item.parent, item.child);
        ELSE
            EXECUTE pg_catalog.format('ALTER TABLE %s NO INHERIT %s', item.child, item.parent);
        END IF;
        RAISE NOTICE 'bms-harden: removed inheritance % -> %', item.child, item.parent;
    END LOOP;

    FOR rel IN SELECT c.relname FROM pg_catalog.pg_class c WHERE c.relnamespace = 'public'::pg_catalog.regnamespace
               AND c.relname IN ('activity_log', 'activity_log_chain', 'activity_log_chain_head') LOOP
        -- Rules and row security.
        FOR item IN SELECT r.rulename FROM pg_catalog.pg_rewrite r WHERE r.ev_class = pg_catalog.format('public.%I', rel)::pg_catalog.regclass LOOP
            EXECUTE pg_catalog.format('DROP RULE %I ON public.%I', item.rulename, rel);
            RAISE NOTICE 'bms-harden: removed rule % on %', item.rulename, rel;
        END LOOP;
        FOR item IN SELECT p.polname FROM pg_catalog.pg_policy p WHERE p.polrelid = pg_catalog.format('public.%I', rel)::pg_catalog.regclass LOOP
            EXECUTE pg_catalog.format('DROP POLICY %I ON public.%I', item.polname, rel);
            RAISE NOTICE 'bms-harden: removed policy % on %', item.polname, rel;
        END LOOP;
        EXECUTE pg_catalog.format('ALTER TABLE public.%I NO FORCE ROW LEVEL SECURITY, DISABLE ROW LEVEL SECURITY', rel);
        EXECUTE pg_catalog.format('ALTER TABLE public.%I SET LOGGED', rel);
        -- Every trigger: the migration creates its own again below.
        FOR item IN SELECT t.tgname FROM pg_catalog.pg_trigger t
                    WHERE t.tgrelid = pg_catalog.format('public.%I', rel)::pg_catalog.regclass AND NOT t.tgisinternal LOOP
            EXECUTE pg_catalog.format('DROP TRIGGER %I ON public.%I', item.tgname, rel);
        END LOOP;
        -- Foreign keys of other tables pointing here (their triggers fire on these tables).
        FOR item IN SELECT k.conname, k.conrelid::pg_catalog.regclass::TEXT AS other FROM pg_catalog.pg_constraint k
                    WHERE k.confrelid = pg_catalog.format('public.%I', rel)::pg_catalog.regclass
                      AND k.conrelid <> pg_catalog.format('public.%I', rel)::pg_catalog.regclass LOOP
            EXECUTE pg_catalog.format('ALTER TABLE %s DROP CONSTRAINT %I', item.other, item.conname);
            RAISE NOTICE 'bms-harden: removed foreign key % of % pointing to %', item.conname, item.other, rel;
        END LOOP;
    END LOOP;

    -- Columns: extra ones go, the migration's keep their type and get back their nullability and default.
    FOR item IN
        WITH wanted (rel, name, type, not_null, def) AS (VALUES
            ('activity_log', 'id', 'bigint', true, 'pg_catalog.nextval(''public.activity_log_id_seq''::pg_catalog.regclass)'),
            ('activity_log', 'occurred_at', 'timestamp with time zone', true, 'CURRENT_TIMESTAMP'),
            ('activity_log', 'actor_kind', 'text', true, NULL), ('activity_log', 'actor_user_id', 'uuid', false, NULL),
            ('activity_log', 'actor_name', 'text', true, NULL), ('activity_log', 'actor_display', 'text', true, NULL),
            ('activity_log', 'agent_name', 'text', false, NULL), ('activity_log', 'real_actor_kind', 'text', false, NULL),
            ('activity_log', 'real_actor_user_id', 'uuid', false, NULL), ('activity_log', 'real_actor_name', 'text', false, NULL),
            ('activity_log', 'module_id', 'text', true, NULL), ('activity_log', 'entity_type', 'text', true, NULL),
            ('activity_log', 'entity_id', 'text', false, NULL), ('activity_log', 'entity_label', 'text', true, '''''::pg_catalog.text'),
            ('activity_log', 'action', 'text', true, NULL), ('activity_log', 'summary', 'text', true, NULL),
            ('activity_log', 'changes', 'jsonb', true, '''[]''::pg_catalog.jsonb'), ('activity_log', 'origin', 'text', false, NULL),
            ('activity_log_chain', 'chain_seq', 'bigint', true, NULL), ('activity_log_chain', 'id', 'bigint', true, NULL),
            ('activity_log_chain', 'prev_hash', 'text', true, NULL), ('activity_log_chain', 'row_hash', 'text', true, NULL),
            ('activity_log_chain_head', 'id', 'smallint', true, NULL), ('activity_log_chain_head', 'last_seq', 'bigint', true, NULL),
            ('activity_log_chain_head', 'last_hash', 'text', true, NULL),
            ('activity_log_chain_head', 'hardened_at', 'timestamp with time zone', false, NULL),
            ('activity_log_chain_head', 'hardened_seq', 'bigint', false, NULL)
        )
        SELECT c.relname AS rel, a.attname AS name, pg_catalog.format_type(a.atttypid, a.atttypmod) AS type, a.attgenerated,
               a.attidentity, w.name AS wanted_name, w.type AS wanted_type, w.not_null, w.def
        FROM pg_catalog.pg_class c JOIN pg_catalog.pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
        LEFT JOIN wanted w ON w.rel = c.relname AND w.name = a.attname
        WHERE c.relnamespace = 'public'::pg_catalog.regnamespace AND c.relname IN ('activity_log', 'activity_log_chain', 'activity_log_chain_head')
    LOOP
        IF item.wanted_name IS NULL THEN
            EXECUTE pg_catalog.format('ALTER TABLE public.%I DROP COLUMN %I CASCADE', item.rel, item.name);
            RAISE NOTICE 'bms-harden: removed column %.%', item.rel, item.name;
            CONTINUE;
        END IF;
        IF item.type <> item.wanted_type OR item.attgenerated <> '' THEN
            RAISE EXCEPTION 'column public.%.% is % (generated: %), not %: repair it by hand', item.rel, item.name, item.type,
                item.attgenerated <> '', item.wanted_type;
        END IF;
        IF item.attidentity <> '' THEN
            EXECUTE pg_catalog.format('ALTER TABLE public.%I ALTER COLUMN %I DROP IDENTITY', item.rel, item.name);
        END IF;
        EXECUTE pg_catalog.format('ALTER TABLE public.%I ALTER COLUMN %I %s NOT NULL', item.rel, item.name,
            CASE WHEN item.not_null THEN 'SET' ELSE 'DROP' END);
        IF item.def IS NULL THEN
            EXECUTE pg_catalog.format('ALTER TABLE public.%I ALTER COLUMN %I DROP DEFAULT', item.rel, item.name);
        ELSE
            EXECUTE pg_catalog.format('ALTER TABLE public.%I ALTER COLUMN %I SET DEFAULT %s', item.rel, item.name, item.def);
        END IF;
    END LOOP;

    -- Constraints and indexes: what is not exactly the migration's goes (the migration's missing ones are added below).
    FOR item IN
        WITH wanted (rel, name, def) AS (VALUES
            ('activity_log', 'activity_log_pkey', 'PRIMARY KEY (id)'),
            ('activity_log', 'activity_log_action_check', 'CHECK ((action ~ ''^[a-z][a-z0-9_]{0,63}$''::text))'),
            ('activity_log', 'activity_log_actor_kind_check', 'CHECK ((actor_kind ~ ''^[a-z][a-z_]{0,31}$''::text))'),
            ('activity_log', 'activity_log_changes_check', 'CHECK ((jsonb_typeof(changes) = ''array''::text))'),
            ('activity_log', 'activity_log_module_id_check', 'CHECK ((module_id ~ ''^[a-z][a-z0-9_]{0,62}$''::text))'),
            ('activity_log_chain', 'activity_log_chain_pkey', 'PRIMARY KEY (chain_seq)'),
            ('activity_log_chain', 'activity_log_chain_id_key', 'UNIQUE (id)'),
            ('activity_log_chain_head', 'activity_log_chain_head_pkey', 'PRIMARY KEY (id)'),
            ('activity_log_chain_head', 'activity_log_chain_head_id_check', 'CHECK ((id = 1))')
        )
        SELECT c.relname AS rel, k.conname AS name, k.contype, pg_catalog.pg_get_constraintdef(k.oid) AS def, k.convalidated, w.def AS wanted
        FROM pg_catalog.pg_class c JOIN pg_catalog.pg_constraint k ON k.conrelid = c.oid
        LEFT JOIN wanted w ON w.rel = c.relname AND w.name = k.conname
        WHERE c.relnamespace = 'public'::pg_catalog.regnamespace AND c.relname IN ('activity_log', 'activity_log_chain', 'activity_log_chain_head')
          AND k.contype <> 't'
    LOOP
        IF item.wanted IS NULL OR item.wanted <> item.def OR NOT item.convalidated THEN
            EXECUTE pg_catalog.format('ALTER TABLE public.%I DROP CONSTRAINT %I CASCADE', item.rel, item.name);
            RAISE NOTICE 'bms-harden: removed constraint % on % (%)', item.name, item.rel, item.def;
        END IF;
    END LOOP;
    FOR item IN
        WITH wanted (rel, name, def) AS (VALUES
            ('activity_log', 'activity_log_pkey', 'PRIMARY KEY (id)'),
            ('activity_log', 'activity_log_action_check', 'CHECK ((action ~ ''^[a-z][a-z0-9_]{0,63}$''::text))'),
            ('activity_log', 'activity_log_actor_kind_check', 'CHECK ((actor_kind ~ ''^[a-z][a-z_]{0,31}$''::text))'),
            ('activity_log', 'activity_log_changes_check', 'CHECK ((jsonb_typeof(changes) = ''array''::text))'),
            ('activity_log', 'activity_log_module_id_check', 'CHECK ((module_id ~ ''^[a-z][a-z0-9_]{0,62}$''::text))'),
            ('activity_log_chain', 'activity_log_chain_pkey', 'PRIMARY KEY (chain_seq)'),
            ('activity_log_chain', 'activity_log_chain_id_key', 'UNIQUE (id)'),
            ('activity_log_chain_head', 'activity_log_chain_head_pkey', 'PRIMARY KEY (id)'),
            ('activity_log_chain_head', 'activity_log_chain_head_id_check', 'CHECK ((id = 1))')
        )
        SELECT w.rel, w.name, w.def FROM wanted w
        WHERE pg_catalog.to_regclass(pg_catalog.format('public.%I', w.rel)) IS NOT NULL
          AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_constraint k
                          WHERE k.conrelid = pg_catalog.format('public.%I', w.rel)::pg_catalog.regclass AND k.conname = w.name)
    LOOP
        -- Drops an index already using the name, then adds the constraint (it scans the table: checked, plain, locked).
        IF pg_catalog.to_regclass(pg_catalog.format('public.%I', item.name)) IS NOT NULL THEN
            EXECUTE pg_catalog.format('DROP INDEX public.%I', item.name);
        END IF;
        EXECUTE pg_catalog.format('ALTER TABLE public.%I ADD CONSTRAINT %I %s', item.rel, item.name, item.def);
        RAISE NOTICE 'bms-harden: restored constraint % on %', item.name, item.rel;
    END LOOP;
    FOR item IN
        WITH wanted (name, def) AS (VALUES
            ('activity_log_pkey', 'CREATE UNIQUE INDEX activity_log_pkey ON public.activity_log USING btree (id)'),
            ('activity_log_occurred_idx', 'CREATE INDEX activity_log_occurred_idx ON public.activity_log USING btree (occurred_at DESC)'),
            ('activity_log_actor_idx', 'CREATE INDEX activity_log_actor_idx ON public.activity_log USING btree (actor_user_id, occurred_at DESC)'),
            ('activity_log_module_idx', 'CREATE INDEX activity_log_module_idx ON public.activity_log USING btree (module_id, occurred_at DESC)'),
            ('activity_log_entity_idx', 'CREATE INDEX activity_log_entity_idx ON public.activity_log USING btree (entity_type, entity_id)'),
            ('activity_log_chain_pkey', 'CREATE UNIQUE INDEX activity_log_chain_pkey ON public.activity_log_chain USING btree (chain_seq)'),
            ('activity_log_chain_id_key', 'CREATE UNIQUE INDEX activity_log_chain_id_key ON public.activity_log_chain USING btree (id)'),
            ('activity_log_chain_head_pkey', 'CREATE UNIQUE INDEX activity_log_chain_head_pkey ON public.activity_log_chain_head USING btree (id)')
        )
        SELECT i.relname AS name, pg_catalog.pg_get_indexdef(x.indexrelid) AS def, x.indisvalid, w.def AS wanted,
               EXISTS (SELECT 1 FROM pg_catalog.pg_constraint k WHERE k.conindid = x.indexrelid) AS backs_constraint
        FROM pg_catalog.pg_index x JOIN pg_catalog.pg_class i ON i.oid = x.indexrelid
        JOIN pg_catalog.pg_class c ON c.oid = x.indrelid
        LEFT JOIN wanted w ON w.name = i.relname
        WHERE c.relnamespace = 'public'::pg_catalog.regnamespace AND c.relname IN ('activity_log', 'activity_log_chain', 'activity_log_chain_head')
    LOOP
        IF (item.wanted IS NULL OR item.wanted <> item.def OR NOT item.indisvalid) AND NOT item.backs_constraint THEN
            EXECUTE pg_catalog.format('DROP INDEX public.%I', item.name);
            IF item.wanted IS NULL THEN
                RAISE NOTICE 'bms-harden: removed index % (%)', item.name, item.def;
            END IF;
        ELSIF (item.wanted IS NULL OR item.wanted <> item.def OR NOT item.indisvalid) THEN
            RAISE EXCEPTION 'index public.% backs a constraint and is not the migration''s: repair it by hand', item.name;
        END IF;
    END LOOP;

    -- The id sequence: the migration's parameters (its current value stays), logged, owned by the column.
    IF pg_catalog.to_regclass('public.activity_log_id_seq') IS NOT NULL THEN
        IF (SELECT c.relkind FROM pg_catalog.pg_class c WHERE c.oid = 'public.activity_log_id_seq'::pg_catalog.regclass) <> 'S' THEN
            RAISE EXCEPTION 'public.activity_log_id_seq is not a sequence: repair it by hand';
        END IF;
        ALTER SEQUENCE public.activity_log_id_seq AS BIGINT INCREMENT BY 1 MINVALUE 1 NO MAXVALUE NO CYCLE OWNED BY public.activity_log.id;
        ALTER SEQUENCE public.activity_log_id_seq SET LOGGED;
    END IF;

    -- The functions: no overloads, nothing else under their names; the migration replaces the bodies.
    FOR item IN SELECT p.oid::pg_catalog.regprocedure::TEXT AS signature, p.prokind, p.pronargs, p.prorettype
                FROM pg_catalog.pg_proc p WHERE p.pronamespace = 'public'::pg_catalog.regnamespace
                  AND p.proname IN ('activity_log_append_only', 'activity_log_before_insert', 'activity_log_seal') LOOP
        IF item.pronargs <> 0 OR item.prokind <> 'f' OR item.prorettype <> 'pg_catalog.trigger'::pg_catalog.regtype THEN
            EXECUTE pg_catalog.format('DROP ROUTINE %s CASCADE', item.signature);
            RAISE NOTICE 'bms-harden: removed routine %', item.signature;
        END IF;
    END LOOP;

    -- The schema bms_audit holds only the guard function, and belongs to bms_audit.
    IF EXISTS (SELECT 1 FROM pg_catalog.pg_namespace n WHERE n.nspname = 'bms_audit') THEN
        ALTER SCHEMA bms_audit OWNER TO bms_audit;
        FOR item IN SELECT p.oid::pg_catalog.regprocedure::TEXT AS signature FROM pg_catalog.pg_proc p
                    WHERE p.pronamespace = 'bms_audit'::pg_catalog.regnamespace
                      AND p.oid::pg_catalog.regprocedure::TEXT <> 'bms_audit.activity_log_guard()' LOOP
            EXECUTE pg_catalog.format('DROP ROUTINE %s CASCADE', item.signature);
            RAISE NOTICE 'bms-harden: removed routine %', item.signature;
        END LOOP;
        FOR item IN SELECT c.oid::pg_catalog.regclass::TEXT AS name FROM pg_catalog.pg_class c
                    WHERE c.relnamespace = 'bms_audit'::pg_catalog.regnamespace AND c.relkind IN ('r', 'p', 'v', 'm', 'f', 'S') LOOP
            EXECUTE pg_catalog.format('DROP %s %s CASCADE',
                (SELECT CASE c.relkind WHEN 'v' THEN 'VIEW' WHEN 'm' THEN 'MATERIALIZED VIEW' WHEN 'f' THEN 'FOREIGN TABLE'
                        WHEN 'S' THEN 'SEQUENCE' ELSE 'TABLE' END FROM pg_catalog.pg_class c WHERE c.oid = item.name::pg_catalog.regclass), item.name);
            RAISE NOTICE 'bms-harden: removed %', item.name;
        END LOOP;
    END IF;
END;
$prepare$;

-- @canonical@

DO $finish$
DECLARE
    runtime TEXT := pg_catalog.current_setting('bms.runtime_user');
    target TEXT;
    item RECORD;
    head_seq BIGINT;
    head_hash TEXT;
    pending RECORD;
    sealed BIGINT := 0;
BEGIN
    -- Ownership, then nobody but the owner keeps anything, then the runtime role gets what it needs.
    EXECUTE 'CREATE SCHEMA IF NOT EXISTS bms_audit AUTHORIZATION bms_audit';
    EXECUTE 'REVOKE ALL ON SCHEMA bms_audit FROM PUBLIC';
    -- The sealing function (SECURITY DEFINER, bms_audit's) reaches the tables through public.
    EXECUTE 'GRANT USAGE ON SCHEMA public TO bms_audit';
    FOREACH target IN ARRAY ARRAY['public.activity_log', 'public.activity_log_chain', 'public.activity_log_chain_head'] LOOP
        EXECUTE pg_catalog.format('ALTER TABLE %s OWNER TO bms_audit', target);
    END LOOP;
    EXECUTE 'ALTER SEQUENCE public.activity_log_id_seq OWNER TO bms_audit';
    FOREACH target IN ARRAY ARRAY['public.activity_log_append_only()', 'public.activity_log_before_insert()', 'public.activity_log_seal()'] LOOP
        EXECUTE pg_catalog.format('ALTER FUNCTION %s OWNER TO bms_audit', target);
        FOR item IN SELECT DISTINCT acl.grantee FROM pg_catalog.pg_proc p, pg_catalog.aclexplode(p.proacl) acl
                    WHERE p.oid = target::pg_catalog.regprocedure AND acl.grantee <> p.proowner LOOP
            EXECUTE pg_catalog.format('REVOKE ALL ON FUNCTION %s FROM %s', target,
                CASE WHEN item.grantee = 0 THEN 'PUBLIC' ELSE pg_catalog.quote_ident(pg_catalog.pg_get_userbyid(item.grantee)) END);
        END LOOP;
        -- Also the implicit EXECUTE of PUBLIC (a trigger needs none to fire).
        EXECUTE pg_catalog.format('REVOKE ALL ON FUNCTION %s FROM PUBLIC', target);
    END LOOP;
    FOREACH target IN ARRAY ARRAY['public.activity_log', 'public.activity_log_chain', 'public.activity_log_chain_head', 'public.activity_log_id_seq'] LOOP
        FOR item IN SELECT DISTINCT acl.grantee FROM pg_catalog.pg_class c, pg_catalog.aclexplode(c.relacl) acl
                    WHERE c.oid = target::pg_catalog.regclass AND acl.grantee <> c.relowner LOOP
            EXECUTE pg_catalog.format('REVOKE ALL ON %s %s FROM %s',
                CASE WHEN target = 'public.activity_log_id_seq' THEN 'SEQUENCE' ELSE 'TABLE' END, target,
                CASE WHEN item.grantee = 0 THEN 'PUBLIC' ELSE pg_catalog.quote_ident(pg_catalog.pg_get_userbyid(item.grantee)) END);
        END LOOP;
        FOR item IN SELECT DISTINCT a.attname, acl.grantee FROM pg_catalog.pg_attribute a, pg_catalog.aclexplode(a.attacl) acl
                    WHERE a.attrelid = target::pg_catalog.regclass AND a.attacl IS NOT NULL LOOP
            EXECUTE pg_catalog.format('REVOKE ALL (%I) ON TABLE %s FROM %s', item.attname, target,
                CASE WHEN item.grantee = 0 THEN 'PUBLIC' ELSE pg_catalog.quote_ident(pg_catalog.pg_get_userbyid(item.grantee)) END);
        END LOOP;
    END LOOP;
    EXECUTE pg_catalog.format('GRANT SELECT, INSERT ON public.activity_log TO %I', runtime);
    EXECUTE pg_catalog.format('GRANT USAGE, SELECT ON SEQUENCE public.activity_log_id_seq TO %I', runtime);
    -- Read only: the server's verification compares the chain and the head with the log.
    EXECUTE pg_catalog.format('GRANT SELECT ON public.activity_log_chain, public.activity_log_chain_head TO %I', runtime);

    -- The schema public belongs to the database owner (the server owner's role, in the runtime's membership), and a
    -- schema owner may drop what is in it: these event triggers refuse that for exactly these objects.
    EXECUTE $guard$
        CREATE OR REPLACE FUNCTION bms_audit.activity_log_guard() RETURNS event_trigger
        LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $guard_body$
        DECLARE
            dropped RECORD;
            protected TEXT[] := ARRAY['public', 'bms_audit', 'bms_audit.activity_log_guard()',
                'public.activity_log', 'public.activity_log_chain', 'public.activity_log_chain_head', 'public.activity_log_id_seq',
                'public.activity_log_append_only()', 'public.activity_log_before_insert()', 'public.activity_log_seal()',
                'public.activity_log_pkey', 'public.activity_log_occurred_idx', 'public.activity_log_actor_idx',
                'public.activity_log_module_idx', 'public.activity_log_entity_idx', 'public.activity_log_chain_pkey',
                'public.activity_log_chain_id_key', 'public.activity_log_chain_head_pkey'];
            tables TEXT[] := ARRAY['public.activity_log', 'public.activity_log_chain', 'public.activity_log_chain_head'];
        BEGIN
            IF (SELECT r.rolsuper FROM pg_catalog.pg_roles r WHERE r.rolname = current_user) THEN
                RETURN;
            END IF;
            IF TG_EVENT = 'sql_drop' THEN
                FOR dropped IN SELECT * FROM pg_catalog.pg_event_trigger_dropped_objects() LOOP
                    -- Exact identities: the objects themselves, or a trigger/constraint/rule/policy "<name> on <table>".
                    IF dropped.object_identity = ANY (protected)
                       OR (dropped.object_type IN ('trigger', 'table constraint', 'rule', 'policy', 'table column')
                           AND (pg_catalog.regexp_replace(dropped.object_identity, '^.* on ', '') = ANY (tables)
                                OR pg_catalog.regexp_replace(dropped.object_identity, '\.[^.]+$', '') = ANY (tables))) THEN
                        RAISE EXCEPTION 'the activity log cannot be dropped (%)', dropped.object_identity
                            USING ERRCODE = 'insufficient_privilege';
                    END IF;
                END LOOP;
            ELSIF pg_catalog.to_regclass('public.activity_log') IS NULL OR pg_catalog.to_regnamespace('bms_audit') IS NULL THEN
                RAISE EXCEPTION 'the activity log must stay in the schema public' USING ERRCODE = 'insufficient_privilege';
            END IF;
        END;
        $guard_body$
    $guard$;
    EXECUTE 'ALTER FUNCTION bms_audit.activity_log_guard() OWNER TO bms_audit';
    EXECUTE 'REVOKE ALL ON FUNCTION bms_audit.activity_log_guard() FROM PUBLIC';
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_event_trigger e WHERE e.evtname = 'bms_activity_log_no_drop'
                   AND e.evtevent = 'sql_drop' AND e.evttags IS NULL AND e.evtfoid = 'bms_audit.activity_log_guard()'::pg_catalog.regprocedure) THEN
        EXECUTE 'DROP EVENT TRIGGER IF EXISTS bms_activity_log_no_drop';
        EXECUTE 'CREATE EVENT TRIGGER bms_activity_log_no_drop ON sql_drop EXECUTE FUNCTION bms_audit.activity_log_guard()';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_event_trigger e WHERE e.evtname = 'bms_activity_log_no_move'
                   AND e.evtevent = 'ddl_command_end' AND e.evttags::TEXT = '{"ALTER SCHEMA"}'
                   AND e.evtfoid = 'bms_audit.activity_log_guard()'::pg_catalog.regprocedure) THEN
        EXECUTE 'DROP EVENT TRIGGER IF EXISTS bms_activity_log_no_move';
        EXECUTE $et$CREATE EVENT TRIGGER bms_activity_log_no_move ON ddl_command_end WHEN TAG IN ('ALTER SCHEMA')
            EXECUTE FUNCTION bms_audit.activity_log_guard()$et$;
    END IF;
    EXECUTE 'ALTER EVENT TRIGGER bms_activity_log_no_drop ENABLE';
    EXECUTE 'ALTER EVENT TRIGGER bms_activity_log_no_move ENABLE';

    -- The guarantee starts now: rows the owner's role could still have written unsealed are sealed (they are
    -- not covered), and the head records when and how far. Later runs keep it, so it cannot be moved forward.
    IF pg_catalog.current_setting('bms.harden_first') = 'on'
       OR NOT EXISTS (SELECT 1 FROM public.activity_log_chain_head h WHERE h.id = 1 AND h.hardened_at IS NOT NULL AND h.hardened_seq IS NOT NULL) THEN
        INSERT INTO public.activity_log_chain_head (id, last_seq, last_hash) VALUES (1, 0, pg_catalog.repeat('0', 64)) ON CONFLICT (id) DO NOTHING;
        SELECT h.last_seq, h.last_hash INTO head_seq, head_hash FROM public.activity_log_chain_head h WHERE h.id = 1;
        FOR pending IN
            SELECT row_.id, pg_catalog.jsonb_build_array(
                row_.id, pg_catalog.to_char(row_.occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
                row_.actor_kind, row_.actor_user_id, row_.actor_name, row_.actor_display, row_.agent_name, row_.real_actor_kind,
                row_.real_actor_user_id, row_.real_actor_name, row_.module_id, row_.entity_type, row_.entity_id, row_.entity_label,
                row_.action, row_.summary, row_.changes, row_.origin) AS fields
            FROM public.activity_log AS row_
            WHERE NOT EXISTS (SELECT 1 FROM public.activity_log_chain AS link WHERE link.id = row_.id)
            ORDER BY row_.id
        LOOP
            head_seq := head_seq + 1;
            INSERT INTO public.activity_log_chain (chain_seq, id, prev_hash, row_hash)
            VALUES (head_seq, pending.id, head_hash, pg_catalog.encode(pg_catalog.sha256(pg_catalog.convert_to(
                head_hash || '|' || (pg_catalog.jsonb_build_array(head_seq) || pending.fields)::TEXT, 'UTF8')), 'hex'))
            RETURNING row_hash INTO head_hash;
            sealed := sealed + 1;
        END LOOP;
        UPDATE public.activity_log_chain_head
        SET last_seq = head_seq, last_hash = head_hash, hardened_at = pg_catalog.statement_timestamp(), hardened_seq = head_seq
        WHERE id = 1;
        IF sealed > 0 THEN
            RAISE NOTICE 'bms-harden: sealed % rows written without their seal before hardening', sealed;
        END IF;
        RAISE NOTICE 'bms-harden: guarantee starts at %, chain position %', pg_catalog.statement_timestamp(), head_seq;
    END IF;
END;
$finish$;
COMMIT;
