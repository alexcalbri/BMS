-- Everything about a company's activity log that differs from tenant V011 (ACTIVITY_LOG.md §9), one row per finding
-- (detail, in Spanish). Detection only: run by ActivityIntegrity as the runtime role, in a read-only transaction.
-- It reads system catalogs only (pg_catalog.*, never a row of the company's own tables) and calls only built-in
-- catalog functions that deparse or look up (pg_get_*def, format_type, aclexplode...), so nothing the company
-- database defines is executed. The caller pins search_path = pg_catalog, pg_temp (operators and functions resolve
-- only in pg_catalog; pg_temp is never searched for them) and sets:
--   bms.activity_functions  name=sha256[|sha256...];... of prosrc for activity_log_append_only,
--                           activity_log_before_insert and activity_log_seal (every migration version it knows)
WITH
wanted_tables (name) AS (VALUES ('activity_log'), ('activity_log_chain')),
rels AS (
    SELECT w.name, c.oid, c.relkind, c.relpersistence, c.relrowsecurity, c.relforcerowsecurity, c.relowner, c.relam,
           c.reloftype, c.reloptions
    FROM wanted_tables w
    LEFT JOIN pg_catalog.pg_class c ON c.relname = w.name AND c.relnamespace = 'public'::pg_catalog.regnamespace
),
present AS (SELECT * FROM rels WHERE oid IS NOT NULL AND relkind = 'r'),
log_owner AS (SELECT relowner FROM present WHERE name = 'activity_log'),
seq AS (
    SELECT c.oid, c.relkind, c.relpersistence, c.relowner FROM pg_catalog.pg_class c
    WHERE c.relname = 'activity_log_id_seq' AND c.relnamespace = 'public'::pg_catalog.regnamespace
),
-- Types by their built-in oid (a type of the same name created in the company database has another oid).
wanted_columns (rel, name, type, not_null, def) AS (VALUES
    ('activity_log', 'id', 'pg_catalog.int8'::pg_catalog.regtype, true, 'nextval(''public.activity_log_id_seq''::regclass)'),
    ('activity_log', 'occurred_at', 'pg_catalog.timestamptz'::pg_catalog.regtype, true, 'CURRENT_TIMESTAMP'),
    ('activity_log', 'actor_kind', 'pg_catalog.text'::pg_catalog.regtype, true, NULL),
    ('activity_log', 'actor_user_id', 'pg_catalog.uuid'::pg_catalog.regtype, false, NULL),
    ('activity_log', 'actor_name', 'pg_catalog.text'::pg_catalog.regtype, true, NULL),
    ('activity_log', 'actor_display', 'pg_catalog.text'::pg_catalog.regtype, true, NULL),
    ('activity_log', 'agent_name', 'pg_catalog.text'::pg_catalog.regtype, false, NULL),
    ('activity_log', 'real_actor_kind', 'pg_catalog.text'::pg_catalog.regtype, false, NULL),
    ('activity_log', 'real_actor_user_id', 'pg_catalog.uuid'::pg_catalog.regtype, false, NULL),
    ('activity_log', 'real_actor_name', 'pg_catalog.text'::pg_catalog.regtype, false, NULL),
    ('activity_log', 'module_id', 'pg_catalog.text'::pg_catalog.regtype, true, NULL),
    ('activity_log', 'entity_type', 'pg_catalog.text'::pg_catalog.regtype, true, NULL),
    ('activity_log', 'entity_id', 'pg_catalog.text'::pg_catalog.regtype, false, NULL),
    ('activity_log', 'entity_label', 'pg_catalog.text'::pg_catalog.regtype, true, '''''::text'),
    ('activity_log', 'action', 'pg_catalog.text'::pg_catalog.regtype, true, NULL),
    ('activity_log', 'summary', 'pg_catalog.text'::pg_catalog.regtype, true, NULL),
    ('activity_log', 'changes', 'pg_catalog.jsonb'::pg_catalog.regtype, true, '''[]''::jsonb'),
    ('activity_log', 'origin', 'pg_catalog.text'::pg_catalog.regtype, false, NULL),
    ('activity_log_chain', 'chain_seq', 'pg_catalog.int8'::pg_catalog.regtype, true, NULL),
    ('activity_log_chain', 'id', 'pg_catalog.int8'::pg_catalog.regtype, true, NULL),
    ('activity_log_chain', 'prev_hash', 'pg_catalog.text'::pg_catalog.regtype, true, NULL),
    ('activity_log_chain', 'row_hash', 'pg_catalog.text'::pg_catalog.regtype, true, NULL)
),
columns_ AS (
    SELECT p.name AS rel, a.attname::TEXT AS name, a.atttypid, pg_catalog.format_type(a.atttypid, a.atttypmod) AS type_name,
           a.attnotnull AS not_null, a.attcollation, pg_catalog.pg_get_expr(d.adbin, d.adrelid) AS def, a.attgenerated, a.attidentity
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
    ('activity_log_chain', 'activity_log_chain_id_key', 'UNIQUE (id)')
),
constraints_ AS (
    -- PostgreSQL 18 keeps every NOT NULL as a constraint of its own (contype 'n'); older versions have none. Whether a
    -- column is NOT NULL is compared in columns_, so here they only have to be valid.
    SELECT p.name AS rel, k.conname::TEXT AS name, pg_catalog.pg_get_constraintdef(k.oid) AS def, k.convalidated,
           k.contype = 'n' AS not_null
    FROM present p JOIN pg_catalog.pg_constraint k ON k.conrelid = p.oid
),
wanted_indexes (rel, name, def) AS (VALUES
    ('activity_log', 'activity_log_pkey', 'CREATE UNIQUE INDEX activity_log_pkey ON public.activity_log USING btree (id)'),
    ('activity_log', 'activity_log_occurred_idx', 'CREATE INDEX activity_log_occurred_idx ON public.activity_log USING btree (occurred_at DESC)'),
    ('activity_log', 'activity_log_actor_idx', 'CREATE INDEX activity_log_actor_idx ON public.activity_log USING btree (actor_user_id, occurred_at DESC)'),
    ('activity_log', 'activity_log_module_idx', 'CREATE INDEX activity_log_module_idx ON public.activity_log USING btree (module_id, occurred_at DESC)'),
    ('activity_log', 'activity_log_entity_idx', 'CREATE INDEX activity_log_entity_idx ON public.activity_log USING btree (entity_type, entity_id)'),
    ('activity_log_chain', 'activity_log_chain_pkey', 'CREATE UNIQUE INDEX activity_log_chain_pkey ON public.activity_log_chain USING btree (chain_seq)'),
    ('activity_log_chain', 'activity_log_chain_id_key', 'CREATE UNIQUE INDEX activity_log_chain_id_key ON public.activity_log_chain USING btree (id)')
),
indexes_ AS (
    SELECT p.name AS rel, i.relname::TEXT AS name, pg_catalog.pg_get_indexdef(x.indexrelid) AS def, x.indisvalid
    FROM present p JOIN pg_catalog.pg_index x ON x.indrelid = p.oid
    JOIN pg_catalog.pg_class i ON i.oid = x.indexrelid
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
wanted_functions (name, config) AS (VALUES
    ('activity_log_append_only', 'search_path=pg_catalog, pg_temp'),
    ('activity_log_before_insert', 'search_path=pg_catalog, pg_temp'),
    ('activity_log_seal', 'search_path=pg_catalog, pg_temp;lock_timeout=10s')
),
expected_source AS (
    SELECT pg_catalog.split_part(item, '=', 1) AS name, pg_catalog.split_part(item, '=', 2) AS sha
    FROM pg_catalog.regexp_split_to_table(pg_catalog.current_setting('bms.activity_functions', true), ';') AS item
),
functions_ AS (
    SELECT f.oid, f.proname::TEXT AS name, f.pronargs, f.prokind, f.prosecdef, f.proleakproof, f.proisstrict, f.provolatile,
           f.prorettype, f.prolang, f.proowner, pg_catalog.array_to_string(f.proconfig, ';') AS config,
           pg_catalog.encode(pg_catalog.sha256(pg_catalog.convert_to(f.prosrc, 'UTF8')), 'hex') AS sha
    FROM pg_catalog.pg_proc f
    WHERE f.pronamespace = 'public'::pg_catalog.regnamespace AND f.proname IN (SELECT name FROM wanted_functions)
),
-- Reloptions that only tune storage and vacuum; anything else (user_catalog_table...) is not V011's.
allowed_options (name) AS (VALUES ('fillfactor'), ('toast_tuple_target'), ('parallel_workers'), ('vacuum_index_cleanup'), ('vacuum_truncate')),
findings (detail) AS (
    -- Tables: present, plain, logged, heap, not typed, no row security, rules or inheritance, usual options.
    SELECT 'Falta la tabla ' || name || ' o no es una tabla normal.' FROM rels WHERE oid IS NULL OR relkind <> 'r'
    UNION ALL SELECT name || ' es UNLOGGED (se pierde al reiniciar PostgreSQL).' FROM present WHERE relpersistence <> 'p'
    UNION ALL SELECT name || ' no usa el metodo de acceso heap.' FROM present
        WHERE relam <> (SELECT oid FROM pg_catalog.pg_am WHERE amname = 'heap')
    UNION ALL SELECT name || ' es una tabla tipada (OF ' || reloftype::pg_catalog.regtype::TEXT || ').' FROM present WHERE reloftype <> 0
    UNION ALL SELECT name || ' tiene la opcion de almacenamiento ' || o.option || '.'
        FROM present p, pg_catalog.unnest(p.reloptions) AS o (option)
        WHERE pg_catalog.split_part(o.option, '=', 1) NOT IN (SELECT name FROM allowed_options)
          AND pg_catalog.split_part(o.option, '=', 1) NOT LIKE 'autovacuum\_%'
    UNION ALL SELECT name || ' tiene la seguridad por filas activada.' FROM present WHERE relrowsecurity OR relforcerowsecurity
    UNION ALL SELECT 'La politica ' || pol.polname || ' esta sobre ' || p.name || '.'
        FROM present p JOIN pg_catalog.pg_policy pol ON pol.polrelid = p.oid
    UNION ALL SELECT 'La regla ' || r.rulename || ' esta sobre ' || p.name || '.'
        FROM present p JOIN pg_catalog.pg_rewrite r ON r.ev_class = p.oid
    UNION ALL SELECT p.name || ' hereda de ' || i.inhparent::pg_catalog.regclass::TEXT || '.'
        FROM present p JOIN pg_catalog.pg_inherits i ON i.inhrelid = p.oid
    UNION ALL SELECT i.inhrelid::pg_catalog.regclass::TEXT || ' hereda de ' || p.name || ' (sus filas se leen como del registro).'
        FROM present p JOIN pg_catalog.pg_inherits i ON i.inhparent = p.oid
    UNION ALL SELECT 'El objeto de estadisticas ' || s.stxname || ' esta sobre ' || p.name || '.'
        FROM present p JOIN pg_catalog.pg_statistic_ext s ON s.stxrelid = p.oid
    -- Owners: the tables, the sequence and the functions belong to one role (whoever applied V011).
    UNION ALL SELECT name || ' pertenece a ' || pg_catalog.pg_get_userbyid(relowner) || ' y activity_log a otro rol.'
        FROM (SELECT name, relowner FROM present UNION ALL SELECT 'activity_log_id_seq', relowner FROM seq) o
        WHERE o.relowner <> (SELECT relowner FROM log_owner)
    UNION ALL SELECT 'La funcion ' || name || ' pertenece a ' || pg_catalog.pg_get_userbyid(proowner) || ' y activity_log a otro rol.'
        FROM functions_ WHERE proowner <> (SELECT relowner FROM log_owner)
    -- Columns exactly as V011: no extra ones, the built-in types, nullability, collation and defaults, nothing generated.
    UNION ALL SELECT 'Falta la columna ' || w.rel || '.' || w.name || '.' FROM wanted_columns w
        WHERE w.rel IN (SELECT name FROM present) AND NOT EXISTS (SELECT 1 FROM columns_ c WHERE c.rel = w.rel AND c.name = w.name)
    UNION ALL SELECT 'La columna ' || c.rel || '.' || c.name || ' no es de V011.' FROM columns_ c
        WHERE NOT EXISTS (SELECT 1 FROM wanted_columns w WHERE w.rel = c.rel AND w.name = c.name)
    UNION ALL SELECT 'La columna ' || c.rel || '.' || c.name || ' es de un tipo que no es el de PostgreSQL (' || c.type_name || ').' FROM columns_ c
        WHERE c.atttypid >= 16384
    UNION ALL SELECT 'La columna ' || c.rel || '.' || c.name || ' fue cambiada (tipo, NOT NULL, intercalacion, valor por defecto o generada).'
        FROM columns_ c JOIN wanted_columns w ON w.rel = c.rel AND w.name = c.name
        WHERE c.atttypid <> w.type OR c.not_null <> w.not_null
           OR c.def IS DISTINCT FROM w.def OR c.attgenerated <> '' OR c.attidentity <> ''
           OR c.attcollation NOT IN (0, 100)
    -- Casts that are not PostgreSQL's own, from or to the types of these columns.
    UNION ALL SELECT 'Hay una conversion (CREATE CAST) que no es de PostgreSQL de ' || k.castsource::pg_catalog.regtype::TEXT || ' a '
            || k.casttarget::pg_catalog.regtype::TEXT || '.'
        FROM pg_catalog.pg_cast k
        WHERE k.oid >= 16384
          AND (k.castsource IN (SELECT atttypid FROM columns_) OR k.casttarget IN (SELECT atttypid FROM columns_))
    -- Constraints, indexes and triggers exactly as V011.
    UNION ALL SELECT 'La restriccion ' || c.name || ' de ' || c.rel || ' no es de V011 o fue cambiada.' FROM constraints_ c
        WHERE NOT c.convalidated OR (NOT c.not_null AND NOT EXISTS (SELECT 1 FROM wanted_constraints w
            WHERE w.rel = c.rel AND w.name = c.name AND w.def = c.def))
    UNION ALL SELECT 'Falta la restriccion ' || w.name || ' de ' || w.rel || '.' FROM wanted_constraints w
        WHERE w.rel IN (SELECT name FROM present) AND NOT EXISTS (SELECT 1 FROM constraints_ c WHERE c.rel = w.rel AND c.name = w.name)
    UNION ALL SELECT 'La clave foranea ' || k.conname || ' de ' || k.conrelid::pg_catalog.regclass::TEXT || ' apunta a ' || p.name || '.'
        FROM present p JOIN pg_catalog.pg_constraint k ON k.confrelid = p.oid AND k.conrelid <> p.oid
    UNION ALL SELECT 'El indice ' || i.name || ' de ' || i.rel || ' no es de V011 o fue cambiado.' FROM indexes_ i
        WHERE NOT i.indisvalid OR NOT EXISTS (SELECT 1 FROM wanted_indexes w
            WHERE w.rel = i.rel AND w.name = i.name AND w.def = i.def)
    UNION ALL SELECT 'Falta el indice ' || w.name || ' de ' || w.rel || '.' FROM wanted_indexes w
        WHERE w.rel IN (SELECT name FROM present) AND NOT EXISTS (SELECT 1 FROM indexes_ i WHERE i.rel = w.rel AND i.name = w.name)
    UNION ALL SELECT 'El disparador ' || t.name || ' de ' || t.rel || ' no es de V011 o fue cambiado.' FROM triggers_ t
        WHERE NOT EXISTS (SELECT 1 FROM wanted_triggers w
            WHERE w.rel = t.rel AND w.name = t.name AND w.def = t.def)
    UNION ALL SELECT 'El disparador ' || t.name || ' de ' || t.rel || ' esta desactivado (tgenabled = ' || t.tgenabled::TEXT || ').' FROM triggers_ t
        WHERE t.tgenabled <> 'O' AND EXISTS (SELECT 1 FROM wanted_triggers w WHERE w.rel = t.rel AND w.name = t.name)
    UNION ALL SELECT 'Falta el disparador ' || w.name || ' de ' || w.rel || '.' FROM wanted_triggers w
        WHERE w.rel IN (SELECT name FROM present) AND NOT EXISTS (SELECT 1 FROM triggers_ t WHERE t.rel = w.rel AND t.name = w.name)
    -- The id sequence.
    UNION ALL SELECT 'Falta la secuencia activity_log_id_seq.' WHERE NOT EXISTS (SELECT 1 FROM seq WHERE relkind = 'S')
    UNION ALL SELECT 'La secuencia activity_log_id_seq fue cambiada (tipo, incremento, limites, ciclo o UNLOGGED).'
        FROM seq s JOIN pg_catalog.pg_sequence q ON q.seqrelid = s.oid
        WHERE q.seqtypid <> 'pg_catalog.int8'::pg_catalog.regtype OR q.seqincrement <> 1
           OR q.seqmin <> 1 OR q.seqmax <> 9223372036854775807 OR q.seqcycle
           OR s.relpersistence <> 'p'
    -- The three functions: one each, the body of a known migration, the attributes V011 gives them.
    UNION ALL SELECT 'Falta la funcion ' || w.name || '.' FROM wanted_functions w
        WHERE NOT EXISTS (SELECT 1 FROM functions_ f WHERE f.name = w.name AND f.pronargs = 0)
    UNION ALL SELECT 'La funcion ' || f.name || ' tiene una sobrecarga que V011 no crea.' FROM functions_ f WHERE f.pronargs <> 0
    UNION ALL SELECT 'La funcion ' || f.name || ' fue cambiada: no es la de la migracion.'
        FROM functions_ f JOIN wanted_functions w ON w.name = f.name
        WHERE f.pronargs = 0 AND (
               NOT EXISTS (SELECT 1 FROM expected_source e
                   WHERE e.name = f.name AND f.sha = ANY (pg_catalog.string_to_array(e.sha, '|')))
            OR f.prosecdef OR f.config IS DISTINCT FROM w.config OR f.prokind <> 'f'
            OR f.proleakproof OR f.proisstrict OR f.provolatile <> 'v'
            OR f.prorettype <> 'pg_catalog.trigger'::pg_catalog.regtype
            OR f.prolang <> (SELECT oid FROM pg_catalog.pg_language WHERE lanname = 'plpgsql'))
)
SELECT detail FROM findings ORDER BY detail
