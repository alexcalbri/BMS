-- Everything about a company's activity log that differs from tenant V011 (ACTIVITY_LOG.md §9), as rows
-- (kind, detail): 'structure' = the objects are not exactly V011's (tampering or damage), 'hardening' = not
-- (or no longer) given to bms_audit, 'setting' = a per-database setting that could steer a privileged session.
-- Shared, byte for byte, by ActivityIntegrity (the server, as the runtime role) and scripts/ubuntu/activity-harden.sh
-- (the superuser, before deciding whether there is anything to do; it also requires head.hardened_at). Reads
-- catalogs only, takes no lock that
-- blocks writers. The caller pins search_path = pg_catalog, pg_temp (operators and functions resolve only in
-- pg_catalog; pg_temp is never searched for them) and sets:
--   bms.runtime_user     the runtime role
--   bms.audit_functions  name=sha256[|sha256...];... of prosrc for activity_log_append_only, activity_log_before_insert,
--                        activity_log_seal and guard (bms_audit.activity_log_guard): the server accepts the source of
--                        every migration version it knows, activity-harden.sh only the newest (it normalizes to it)
WITH
wanted_tables (name) AS (VALUES ('activity_log'), ('activity_log_chain'), ('activity_log_chain_head')),
rels AS (
    SELECT w.name, c.oid, c.relkind, c.relpersistence, c.relrowsecurity, c.relforcerowsecurity, c.relowner, c.relacl, c.relam
    FROM wanted_tables w
    LEFT JOIN pg_catalog.pg_class c ON c.relname = w.name AND c.relnamespace = 'public'::pg_catalog.regnamespace
),
present AS (SELECT * FROM rels WHERE oid IS NOT NULL AND relkind = 'r'),
seq AS (
    SELECT c.oid, c.relkind, c.relpersistence, c.relowner, c.relacl FROM pg_catalog.pg_class c
    WHERE c.relname = 'activity_log_id_seq' AND c.relnamespace = 'public'::pg_catalog.regnamespace
),
wanted_columns (rel, name, type, not_null, def) AS (VALUES
    ('activity_log', 'id', 'bigint', true, 'nextval(''public.activity_log_id_seq''::regclass)'),
    ('activity_log', 'occurred_at', 'timestamp with time zone', true, 'CURRENT_TIMESTAMP'),
    ('activity_log', 'actor_kind', 'text', true, NULL),
    ('activity_log', 'actor_user_id', 'uuid', false, NULL),
    ('activity_log', 'actor_name', 'text', true, NULL),
    ('activity_log', 'actor_display', 'text', true, NULL),
    ('activity_log', 'agent_name', 'text', false, NULL),
    ('activity_log', 'real_actor_kind', 'text', false, NULL),
    ('activity_log', 'real_actor_user_id', 'uuid', false, NULL),
    ('activity_log', 'real_actor_name', 'text', false, NULL),
    ('activity_log', 'module_id', 'text', true, NULL),
    ('activity_log', 'entity_type', 'text', true, NULL),
    ('activity_log', 'entity_id', 'text', false, NULL),
    ('activity_log', 'entity_label', 'text', true, '''''::text'),
    ('activity_log', 'action', 'text', true, NULL),
    ('activity_log', 'summary', 'text', true, NULL),
    ('activity_log', 'changes', 'jsonb', true, '''[]''::jsonb'),
    ('activity_log', 'origin', 'text', false, NULL),
    ('activity_log_chain', 'chain_seq', 'bigint', true, NULL),
    ('activity_log_chain', 'id', 'bigint', true, NULL),
    ('activity_log_chain', 'prev_hash', 'text', true, NULL),
    ('activity_log_chain', 'row_hash', 'text', true, NULL),
    ('activity_log_chain_head', 'id', 'smallint', true, NULL),
    ('activity_log_chain_head', 'last_seq', 'bigint', true, NULL),
    ('activity_log_chain_head', 'last_hash', 'text', true, NULL),
    ('activity_log_chain_head', 'hardened_at', 'timestamp with time zone', false, NULL),
    ('activity_log_chain_head', 'hardened_seq', 'bigint', false, NULL)
),
columns_ AS (
    SELECT p.name AS rel, a.attname::TEXT AS name, pg_catalog.format_type(a.atttypid, a.atttypmod) AS type, a.attnotnull AS not_null,
           pg_catalog.pg_get_expr(d.adbin, d.adrelid) AS def, a.attgenerated, a.attidentity, a.attacl
    FROM present p JOIN pg_catalog.pg_attribute a ON a.attrelid = p.oid AND a.attnum > 0 AND NOT a.attisdropped
    LEFT JOIN pg_catalog.pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
),
wanted_constraints (rel, name, def) AS (VALUES
    ('activity_log', 'activity_log_pkey', 'PRIMARY KEY (id)'),
    ('activity_log', 'activity_log_action_check', 'CHECK ((action ~ ''^[a-z][a-z0-9_]{0,63}$''::text))'),
    ('activity_log', 'activity_log_actor_kind_check', 'CHECK ((actor_kind ~ ''^[a-z][a-z_]{0,31}$''::text))'),
    ('activity_log', 'activity_log_changes_check', 'CHECK ((jsonb_typeof(changes) = ''array''::text))'),
    ('activity_log', 'activity_log_module_id_check', 'CHECK ((module_id ~ ''^[a-z][a-z0-9_]{0,62}$''::text))'),
    ('activity_log', 'activity_log_seal', 'TRIGGER DEFERRABLE INITIALLY DEFERRED'),
    ('activity_log_chain', 'activity_log_chain_pkey', 'PRIMARY KEY (chain_seq)'),
    ('activity_log_chain', 'activity_log_chain_id_key', 'UNIQUE (id)'),
    ('activity_log_chain_head', 'activity_log_chain_head_pkey', 'PRIMARY KEY (id)'),
    ('activity_log_chain_head', 'activity_log_chain_head_id_check', 'CHECK ((id = 1))')
),
constraints_ AS (
    SELECT p.name AS rel, k.conname::TEXT AS name, pg_catalog.pg_get_constraintdef(k.oid) AS def, k.convalidated
    FROM present p JOIN pg_catalog.pg_constraint k ON k.conrelid = p.oid
),
wanted_indexes (rel, name, def) AS (VALUES
    ('activity_log', 'activity_log_pkey', 'CREATE UNIQUE INDEX activity_log_pkey ON public.activity_log USING btree (id)'),
    ('activity_log', 'activity_log_occurred_idx', 'CREATE INDEX activity_log_occurred_idx ON public.activity_log USING btree (occurred_at DESC)'),
    ('activity_log', 'activity_log_actor_idx', 'CREATE INDEX activity_log_actor_idx ON public.activity_log USING btree (actor_user_id, occurred_at DESC)'),
    ('activity_log', 'activity_log_module_idx', 'CREATE INDEX activity_log_module_idx ON public.activity_log USING btree (module_id, occurred_at DESC)'),
    ('activity_log', 'activity_log_entity_idx', 'CREATE INDEX activity_log_entity_idx ON public.activity_log USING btree (entity_type, entity_id)'),
    ('activity_log_chain', 'activity_log_chain_pkey', 'CREATE UNIQUE INDEX activity_log_chain_pkey ON public.activity_log_chain USING btree (chain_seq)'),
    ('activity_log_chain', 'activity_log_chain_id_key', 'CREATE UNIQUE INDEX activity_log_chain_id_key ON public.activity_log_chain USING btree (id)'),
    ('activity_log_chain_head', 'activity_log_chain_head_pkey', 'CREATE UNIQUE INDEX activity_log_chain_head_pkey ON public.activity_log_chain_head USING btree (id)')
),
indexes_ AS (
    SELECT p.name AS rel, i.relname::TEXT AS name, pg_catalog.pg_get_indexdef(x.indexrelid) AS def, x.indisvalid
    FROM present p JOIN pg_catalog.pg_index x ON x.indrelid = p.oid JOIN pg_catalog.pg_class i ON i.oid = x.indexrelid
),
wanted_triggers (rel, name, def) AS (VALUES
    ('activity_log', 'activity_log_before_insert', 'CREATE TRIGGER activity_log_before_insert BEFORE INSERT ON public.activity_log FOR EACH ROW EXECUTE FUNCTION public.activity_log_before_insert()'),
    ('activity_log', 'activity_log_no_change', 'CREATE TRIGGER activity_log_no_change BEFORE DELETE OR UPDATE ON public.activity_log FOR EACH ROW EXECUTE FUNCTION public.activity_log_append_only()'),
    ('activity_log', 'activity_log_no_truncate', 'CREATE TRIGGER activity_log_no_truncate BEFORE TRUNCATE ON public.activity_log FOR EACH STATEMENT EXECUTE FUNCTION public.activity_log_append_only()'),
    ('activity_log', 'activity_log_seal', 'CREATE CONSTRAINT TRIGGER activity_log_seal AFTER INSERT ON public.activity_log DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (((new.id)::text = current_setting(''bms.activity_seal_first''::text, true))) EXECUTE FUNCTION public.activity_log_seal()'),
    ('activity_log_chain', 'activity_log_chain_no_change', 'CREATE TRIGGER activity_log_chain_no_change BEFORE DELETE OR UPDATE ON public.activity_log_chain FOR EACH ROW EXECUTE FUNCTION public.activity_log_append_only()'),
    ('activity_log_chain', 'activity_log_chain_no_truncate', 'CREATE TRIGGER activity_log_chain_no_truncate BEFORE TRUNCATE ON public.activity_log_chain FOR EACH STATEMENT EXECUTE FUNCTION public.activity_log_append_only()')
),
triggers_ AS (
    SELECT p.name AS rel, t.tgname::TEXT AS name, pg_catalog.pg_get_triggerdef(t.oid) AS def, t.tgenabled
    FROM present p JOIN pg_catalog.pg_trigger t ON t.tgrelid = p.oid AND NOT t.tgisinternal
),
wanted_functions (name, secdef) AS (VALUES ('activity_log_append_only', false), ('activity_log_before_insert', false), ('activity_log_seal', true)),
expected_source AS (
    SELECT pg_catalog.split_part(item, '=', 1) AS name, pg_catalog.split_part(item, '=', 2) AS sha
    FROM pg_catalog.regexp_split_to_table(pg_catalog.current_setting('bms.audit_functions', true), ';') AS item
),
functions_ AS (
    SELECT f.oid, f.proname::TEXT AS name, f.pronargs, f.prokind, f.prosecdef, f.proleakproof, f.proisstrict, f.provolatile,
           f.prorettype, f.prolang, f.proowner, f.proacl, pg_catalog.array_to_string(f.proconfig, ';') AS config,
           pg_catalog.encode(pg_catalog.sha256(pg_catalog.convert_to(f.prosrc, 'UTF8')), 'hex') AS sha
    FROM pg_catalog.pg_proc f
    WHERE f.pronamespace = 'public'::pg_catalog.regnamespace AND f.proname IN (SELECT name FROM wanted_functions)
),
guard AS (
    SELECT f.oid, f.proowner, f.prosecdef, f.proacl, pg_catalog.array_to_string(f.proconfig, ';') AS config,
           pg_catalog.encode(pg_catalog.sha256(pg_catalog.convert_to(f.prosrc, 'UTF8')), 'hex') AS sha
    FROM pg_catalog.pg_proc f JOIN pg_catalog.pg_namespace n ON n.oid = f.pronamespace
    WHERE n.nspname = 'bms_audit' AND f.proname = 'activity_log_guard' AND f.pronargs = 0
),
audit AS (SELECT r.oid, r.rolcanlogin, r.rolinherit, r.rolsuper, r.rolcreatedb, r.rolcreaterole, r.rolreplication, r.rolbypassrls
          FROM pg_catalog.pg_roles r WHERE r.rolname = 'bms_audit'),
runtime AS (SELECT r.oid FROM pg_catalog.pg_roles r WHERE r.rolname = pg_catalog.current_setting('bms.runtime_user', true)),
-- What every grantee other than the owner holds on each object, against what hardening leaves.
wanted_grants (rel, privileges) AS (VALUES
    ('activity_log', 'INSERT,SELECT'), ('activity_log_chain', 'SELECT'), ('activity_log_chain_head', 'SELECT'), ('activity_log_id_seq', 'SELECT,USAGE')
),
grants AS (
    SELECT o.name, acl.grantee, pg_catalog.string_agg(acl.privilege_type, ',' ORDER BY acl.privilege_type) AS privileges
    FROM (SELECT name, relowner, relacl FROM present UNION ALL SELECT 'activity_log_id_seq', relowner, relacl FROM seq) o,
         pg_catalog.aclexplode(o.relacl) acl
    WHERE acl.grantee <> o.relowner
    GROUP BY o.name, acl.grantee
),
findings (kind, detail) AS (
    -- Tables: present, plain, logged, heap, no row security, rules or inheritance.
    SELECT 'structure', 'Falta la tabla ' || name || ' o no es una tabla normal.' FROM rels WHERE oid IS NULL OR relkind <> 'r'
    UNION ALL SELECT 'structure', name || ' es UNLOGGED (se pierde al reiniciar PostgreSQL).' FROM present WHERE relpersistence <> 'p'
    UNION ALL SELECT 'structure', name || ' no usa el metodo de acceso heap.' FROM present WHERE relam <> (SELECT oid FROM pg_catalog.pg_am WHERE amname = 'heap')
    UNION ALL SELECT 'structure', name || ' tiene la seguridad por filas activada.' FROM present WHERE relrowsecurity OR relforcerowsecurity
    UNION ALL SELECT 'structure', 'La politica ' || pol.polname || ' esta sobre ' || p.name || '.' FROM present p JOIN pg_catalog.pg_policy pol ON pol.polrelid = p.oid
    UNION ALL SELECT 'structure', 'La regla ' || r.rulename || ' esta sobre ' || p.name || '.' FROM present p JOIN pg_catalog.pg_rewrite r ON r.ev_class = p.oid
    UNION ALL SELECT 'structure', p.name || ' hereda de ' || i.inhparent::pg_catalog.regclass::TEXT || '.' FROM present p JOIN pg_catalog.pg_inherits i ON i.inhrelid = p.oid
    UNION ALL SELECT 'structure', i.inhrelid::pg_catalog.regclass::TEXT || ' hereda de ' || p.name || ' (sus filas se leen como del registro).' FROM present p JOIN pg_catalog.pg_inherits i ON i.inhparent = p.oid
    -- Columns exactly as V011: no extra ones, same types, nullability and defaults, nothing generated.
    UNION ALL SELECT 'structure', 'Falta la columna ' || w.rel || '.' || w.name || '.' FROM wanted_columns w
        WHERE w.rel IN (SELECT name FROM present) AND NOT EXISTS (SELECT 1 FROM columns_ c WHERE c.rel = w.rel AND c.name = w.name)
    UNION ALL SELECT 'structure', 'La columna ' || c.rel || '.' || c.name || ' no es de V011.' FROM columns_ c
        WHERE NOT EXISTS (SELECT 1 FROM wanted_columns w WHERE w.rel = c.rel AND w.name = c.name)
    UNION ALL SELECT 'structure', 'La columna ' || c.rel || '.' || c.name || ' fue cambiada (tipo, NOT NULL, valor por defecto o generada).'
        FROM columns_ c JOIN wanted_columns w ON w.rel = c.rel AND w.name = c.name
        WHERE c.type <> w.type OR c.not_null <> w.not_null OR c.def IS DISTINCT FROM w.def OR c.attgenerated <> '' OR c.attidentity <> ''
    -- Constraints, indexes and triggers exactly as V011.
    UNION ALL SELECT 'structure', 'La restriccion ' || c.name || ' de ' || c.rel || ' no es de V011 o fue cambiada.' FROM constraints_ c
        WHERE NOT c.convalidated OR NOT EXISTS (SELECT 1 FROM wanted_constraints w WHERE w.rel = c.rel AND w.name = c.name AND w.def = c.def)
    UNION ALL SELECT 'structure', 'Falta la restriccion ' || w.name || ' de ' || w.rel || '.' FROM wanted_constraints w
        WHERE w.rel IN (SELECT name FROM present) AND NOT EXISTS (SELECT 1 FROM constraints_ c WHERE c.rel = w.rel AND c.name = w.name)
    UNION ALL SELECT 'structure', 'La clave foranea ' || k.conname || ' de ' || k.conrelid::pg_catalog.regclass::TEXT || ' apunta a ' || p.name || '.'
        FROM present p JOIN pg_catalog.pg_constraint k ON k.confrelid = p.oid AND k.conrelid <> p.oid
    UNION ALL SELECT 'structure', 'El indice ' || i.name || ' de ' || i.rel || ' no es de V011 o fue cambiado.' FROM indexes_ i
        WHERE NOT i.indisvalid OR NOT EXISTS (SELECT 1 FROM wanted_indexes w WHERE w.rel = i.rel AND w.name = i.name AND w.def = i.def)
    UNION ALL SELECT 'structure', 'Falta el indice ' || w.name || ' de ' || w.rel || '.' FROM wanted_indexes w
        WHERE w.rel IN (SELECT name FROM present) AND NOT EXISTS (SELECT 1 FROM indexes_ i WHERE i.rel = w.rel AND i.name = w.name)
    UNION ALL SELECT 'structure', 'El disparador ' || t.name || ' de ' || t.rel || ' no es de V011 o fue cambiado.' FROM triggers_ t
        WHERE NOT EXISTS (SELECT 1 FROM wanted_triggers w WHERE w.rel = t.rel AND w.name = t.name AND w.def = t.def)
    UNION ALL SELECT 'structure', 'El disparador ' || t.name || ' de ' || t.rel || ' esta desactivado (tgenabled = ' || t.tgenabled::TEXT || ').' FROM triggers_ t
        WHERE t.tgenabled <> 'O' AND EXISTS (SELECT 1 FROM wanted_triggers w WHERE w.rel = t.rel AND w.name = t.name)
    UNION ALL SELECT 'structure', 'Falta el disparador ' || w.name || ' de ' || w.rel || '.' FROM wanted_triggers w
        WHERE w.rel IN (SELECT name FROM present) AND NOT EXISTS (SELECT 1 FROM triggers_ t WHERE t.rel = w.rel AND t.name = w.name)
    -- The id sequence.
    UNION ALL SELECT 'structure', 'Falta la secuencia activity_log_id_seq.' WHERE NOT EXISTS (SELECT 1 FROM seq WHERE relkind = 'S')
    UNION ALL SELECT 'structure', 'La secuencia activity_log_id_seq fue cambiada (tipo, incremento, limites, ciclo o UNLOGGED).'
        FROM seq s JOIN pg_catalog.pg_sequence q ON q.seqrelid = s.oid
        WHERE q.seqtypid <> 'pg_catalog.int8'::pg_catalog.regtype OR q.seqincrement <> 1 OR q.seqmin <> 1
           OR q.seqmax <> 9223372036854775807 OR q.seqcycle OR s.relpersistence <> 'p'
    -- The three functions: one each, the body of the release, the attributes V011 gives them.
    UNION ALL SELECT 'structure', 'Falta la funcion ' || w.name || '.' FROM wanted_functions w
        WHERE NOT EXISTS (SELECT 1 FROM functions_ f WHERE f.name = w.name AND f.pronargs = 0)
    UNION ALL SELECT 'structure', 'La funcion ' || f.name || ' tiene una sobrecarga que V011 no crea.' FROM functions_ f WHERE f.pronargs <> 0
    UNION ALL SELECT 'structure', 'La funcion ' || f.name || ' fue cambiada: no es la de la migracion.' FROM functions_ f JOIN wanted_functions w ON w.name = f.name
        WHERE f.pronargs = 0 AND (NOT EXISTS (SELECT 1 FROM expected_source e WHERE e.name = f.name AND f.sha = ANY (pg_catalog.string_to_array(e.sha, '|')))
           OR f.prosecdef <> w.secdef OR f.config IS DISTINCT FROM 'search_path=pg_catalog, pg_temp' OR f.prokind <> 'f'
           OR f.proleakproof OR f.proisstrict OR f.provolatile <> 'v' OR f.prorettype <> 'pg_catalog.trigger'::pg_catalog.regtype
           OR f.prolang <> (SELECT oid FROM pg_catalog.pg_language WHERE lanname = 'plpgsql'))
    -- Per-database settings: anything that could steer a superuser or the runtime role in this database.
    UNION ALL SELECT 'setting', 'La base tiene el ajuste ' || s.setting || ' (ALTER DATABASE ... SET).'
        FROM pg_catalog.pg_db_role_setting d, pg_catalog.unnest(d.setconfig) AS s (setting)
        WHERE d.setdatabase = (SELECT oid FROM pg_catalog.pg_database WHERE datname = pg_catalog.current_database())
          AND pg_catalog.lower(pg_catalog.split_part(s.setting, '=', 1)) NOT IN
              ('timezone', 'datestyle', 'intervalstyle', 'lc_messages', 'lc_monetary', 'lc_numeric', 'lc_time',
               'default_text_search_config', 'extra_float_digits', 'application_name', 'client_encoding')
    -- Hardened: everything belongs to bms_audit, nobody but the runtime role holds anything, and only what it needs.
    UNION ALL SELECT 'hardening', 'No existe el rol bms_audit.' WHERE NOT EXISTS (SELECT 1 FROM audit)
    UNION ALL SELECT 'hardening', 'El rol bms_audit tiene atributos de mas (LOGIN, INHERIT, SUPERUSER, CREATEDB, CREATEROLE, REPLICATION o BYPASSRLS).'
        FROM audit WHERE rolcanlogin OR rolinherit OR rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls
    UNION ALL SELECT 'hardening', 'El rol de la aplicacion es miembro de bms_audit.' FROM audit, runtime
        WHERE pg_catalog.pg_has_role(runtime.oid, audit.oid, 'MEMBER')
    UNION ALL SELECT 'hardening', name || ' pertenece a ' || pg_catalog.pg_get_userbyid(relowner) || ', no a bms_audit.'
        FROM (SELECT name, relowner FROM present UNION ALL SELECT 'activity_log_id_seq', relowner FROM seq) o
        WHERE o.relowner IS DISTINCT FROM (SELECT oid FROM audit)
    UNION ALL SELECT 'hardening', 'La funcion ' || name || ' pertenece a ' || pg_catalog.pg_get_userbyid(proowner) || ', no a bms_audit.'
        FROM functions_ WHERE proowner IS DISTINCT FROM (SELECT oid FROM audit)
    UNION ALL SELECT 'hardening', 'Cualquiera puede ejecutar la funcion ' || name || ' (falta el REVOKE).' FROM functions_
        WHERE proacl IS NULL OR EXISTS (SELECT 1 FROM pg_catalog.aclexplode(proacl) a WHERE a.grantee <> proowner)
    UNION ALL SELECT 'hardening', CASE WHEN g.grantee = 0 THEN 'PUBLIC' ELSE pg_catalog.pg_get_userbyid(g.grantee)::TEXT END || ' tiene ' || g.privileges || ' sobre ' || g.name || '.'
        FROM grants g
        WHERE g.grantee IS DISTINCT FROM (SELECT oid FROM runtime)
           OR g.privileges <> (SELECT w.privileges FROM wanted_grants w WHERE w.rel = g.name)
    UNION ALL SELECT 'hardening', 'El rol de la aplicacion no tiene ' || w.privileges || ' sobre ' || w.rel || '.' FROM wanted_grants w
        WHERE NOT EXISTS (SELECT 1 FROM grants g WHERE g.name = w.rel AND g.grantee = (SELECT oid FROM runtime))
    UNION ALL SELECT 'hardening', 'Hay permisos por columna sobre ' || c.rel || '.' || c.name || '.' FROM columns_ c WHERE c.attacl IS NOT NULL
    UNION ALL SELECT 'hardening', 'bms_audit no puede usar el esquema public (el sellado fallaria).' FROM audit
        WHERE NOT pg_catalog.has_schema_privilege(audit.oid, 'public'::pg_catalog.regnamespace, 'USAGE')
    UNION ALL SELECT 'hardening', 'El esquema bms_audit no existe o no pertenece a bms_audit.'
        WHERE NOT EXISTS (SELECT 1 FROM pg_catalog.pg_namespace n WHERE n.nspname = 'bms_audit' AND n.nspowner = (SELECT oid FROM audit))
    UNION ALL SELECT 'hardening', 'La funcion bms_audit.activity_log_guard falta o fue cambiada.'
        WHERE NOT EXISTS (SELECT 1 FROM guard g WHERE g.proowner = (SELECT oid FROM audit) AND NOT g.prosecdef
            AND g.config = 'search_path=pg_catalog, pg_temp'
            AND EXISTS (SELECT 1 FROM expected_source e WHERE e.name = 'guard' AND g.sha = ANY (pg_catalog.string_to_array(e.sha, '|'))))
    UNION ALL SELECT 'hardening', 'Falta o esta desactivado el disparador de eventos ' || w.name || '.'
        FROM (VALUES ('bms_activity_log_no_drop', 'sql_drop', NULL::TEXT), ('bms_activity_log_no_move', 'ddl_command_end', '{"ALTER SCHEMA"}')) AS w (name, event, tags)
        WHERE NOT EXISTS (SELECT 1 FROM pg_catalog.pg_event_trigger e WHERE e.evtname = w.name AND e.evtevent = w.event AND e.evtenabled = 'O'
            AND e.evtfoid = (SELECT oid FROM guard) AND e.evttags::TEXT IS NOT DISTINCT FROM w.tags)
)
SELECT kind, detail FROM findings ORDER BY kind, detail
