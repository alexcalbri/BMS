#!/usr/bin/env bash
# shellcheck disable=SC2016 # literal text searched in update.sh and SQL run by psql
# update.sh's move to the new PostgreSQL role layout (db-roles.sh) on a server as older versions left it: the runtime
# role a member of the server owner's role, one company created by the server (owner's tables), one whose tables an
# older update.sh created as postgres, and rows in companies that must be ignored. Then the company migrations as the
# owner's role through the provisioning login (migrations.sh, A2) and the rollback helper.
set -uo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"
pg_available || { printf '  (omitido: no hay PostgreSQL en %s)\n' "$PG"; exit 0; }

CONTROL=bms_seca_tctl
NEW=bms_seca_tnew
OLD=bms_seca_told
FOREIGN=bms_seca_tforeign
FULL=bms_seca_tfull
OWNER=seca_t_owner
RUNTIME=seca_t_rt
PROVISIONER=seca_t_prov

cleanup() {
    local database role
    for database in $CONTROL $NEW $OLD $FOREIGN $FULL; do
        bms_pg_super postgres -c "DROP DATABASE IF EXISTS $database WITH (FORCE)" >/dev/null 2>&1
    done
    for role in $PROVISIONER $RUNTIME $OWNER; do
        if [[ $(sql postgres "SELECT count(*) FROM pg_roles WHERE rolname = '$role'") == 1 ]]; then
            bms_pg_super postgres -c "DROP OWNED BY $role CASCADE" >/dev/null 2>&1
            bms_pg_super postgres -c "DROP ROLE $role" >/dev/null 2>&1
        fi
    done
}
cleanup
trap cleanup EXIT

# shellcheck source=../db-roles.sh
source "$SCRIPTS/db-roles.sh"
# shellcheck source=../migrations.sh
source "$SCRIPTS/migrations.sh"

# --- The server as older versions left it (install.sh: runtime role member of the owner's role).
bms_pg_super postgres <<SQL || die 'preparacion'
CREATE ROLE $OWNER LOGIN NOSUPERUSER CREATEDB CREATEROLE PASSWORD 'owner-pass';
CREATE ROLE $RUNTIME LOGIN PASSWORD 'runtime-pass';
GRANT $OWNER TO $RUNTIME;
CREATE DATABASE $CONTROL;
REVOKE ALL ON DATABASE $CONTROL FROM PUBLIC;
GRANT CONNECT ON DATABASE $CONTROL TO $RUNTIME, $OWNER;
CREATE DATABASE $FOREIGN;
SQL
{
    for file in V001__create_application_users V002__create_application_sessions V003__add_application_user_profile V004__add_application_user_code V006__add_application_user_language; do
        strip_tx "$REPO/database/core/migrations/$file.sql"
    done
    for file in "$REPO"/database/control/migrations/V*.sql; do strip_tx "$file"; done
    cat <<SQL
INSERT INTO application_users (id, postgres_role, is_active, display_name, email) VALUES ('11111111-1111-1111-1111-111111111111', '$OWNER', TRUE, 'Duena', 'duena@example.com');
INSERT INTO server_owners (user_id) VALUES ('11111111-1111-1111-1111-111111111111');
GRANT USAGE ON SCHEMA public TO $RUNTIME;
GRANT SELECT, INSERT, UPDATE, DELETE ON companies, application_users TO $RUNTIME;
GRANT SELECT, INSERT ON provisioning_audit_log TO $RUNTIME;
GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_data_keys TO $RUNTIME;
INSERT INTO companies (id, code, name, database_name, owner_group) VALUES
    (gen_random_uuid(), 'seca_tnew', 'Nueva', '$NEW', gen_random_uuid()),
    (gen_random_uuid(), 'seca_told', 'Vieja', '$OLD', gen_random_uuid()),
    (gen_random_uuid(), 'seca_tctl', 'Central', '$CONTROL', gen_random_uuid()),
    (gen_random_uuid(), 'seca_tpg', 'Postgres', 'postgres', gen_random_uuid()),
    (gen_random_uuid(), 'seca_tforeign', 'Ajena', '$FOREIGN', gen_random_uuid()),
    (gen_random_uuid(), 'seca_tgone', 'No existe', 'bms_seca_tgone', gen_random_uuid());
SQL
} | bms_pg_super "$CONTROL" || die 'base central'

tenant_tables() {
    for file in database/core/migrations/V001__create_application_users database/core/migrations/V002__create_application_sessions \
        database/tenant/migrations/V001__create_business_owner database/tenant/migrations/V003__create_tenant_modules \
        database/tenant/migrations/V006__create_extensions database/tenant/migrations/V009__outbound; do
        strip_tx "$REPO/$file.sql"
    done
    cat <<'SQL'
CREATE TABLE schema_migrations (module TEXT NOT NULL, version TEXT NOT NULL, checksum TEXT NOT NULL, applied_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (module, version));
CREATE FUNCTION public.vieja_funcion() RETURNS int LANGUAGE sql AS 'SELECT 1';
SQL
    strip_tx "$REPO/database/tenant/migrations/V011__create_activity_log.sql"
    printf "INSERT INTO schema_migrations VALUES ('tenant', 'V011__create_activity_log', 'x');\n"
}
# A company the server created: database and tables of the owner's role.
psql -X -q -v ON_ERROR_STOP=1 -h "$PG_HOST" -p "$PG_PORT" -U "$RUNTIME" -d postgres <<SQL || die 'empresa nueva'
SET ROLE $OWNER;
CREATE DATABASE $NEW;
REVOKE CONNECT ON DATABASE $NEW FROM PUBLIC;
GRANT CONNECT ON DATABASE $NEW TO $RUNTIME;
CREATE DATABASE $OLD;
REVOKE CONNECT ON DATABASE $OLD FROM PUBLIC;
GRANT CONNECT ON DATABASE $OLD TO $RUNTIME;
SQL
{ printf 'SET ROLE %s;\n' "$OWNER"; tenant_tables; printf 'GRANT SELECT, INSERT ON activity_log, activity_log_chain TO %s; GRANT USAGE ON SEQUENCE activity_log_id_seq TO %s;\n' "$RUNTIME" "$RUNTIME"; } |
    psql -X -q -v ON_ERROR_STOP=1 -h "$PG_HOST" -p "$PG_PORT" -U "$RUNTIME" -d "$NEW" || die 'tablas de la empresa nueva'
# A company whose tables an older update.sh created as postgres, with its explicit runtime grants.
{ tenant_tables; cat <<SQL; } | bms_pg_super "$OLD" || die 'tablas de la empresa vieja'
GRANT USAGE ON SCHEMA public TO $RUNTIME;
GRANT SELECT, INSERT, UPDATE ON application_users TO $RUNTIME;
GRANT SELECT ON business_owner, tenant_modules TO $RUNTIME;
GRANT SELECT, INSERT, UPDATE, DELETE ON application_sessions, outbound_events TO $RUNTIME;
GRANT USAGE, SELECT ON SEQUENCE outbound_events_id_seq TO $RUNTIME;
GRANT SELECT, INSERT ON activity_log, activity_log_chain TO $RUNTIME;
GRANT USAGE ON SEQUENCE activity_log_id_seq TO $RUNTIME;
SQL

as_runtime() { psql -X -q -v ON_ERROR_STOP=1 -h "$PG_HOST" -p "$PG_PORT" -U "$RUNTIME" -d "$1" -At -c "$2"; }
write_rows() {
    as_runtime "$1" "INSERT INTO public.activity_log (actor_kind, actor_name, actor_display, module_id, entity_type, action, summary) SELECT 'system', 'Sistema', 'Sistema', 'core', 'x', 'updated', 'fila ' || g FROM generate_series(1, $2) g" >/dev/null
}
write_rows "$NEW" 3
write_rows "$OLD" 4
equals 'la empresa vieja: activity_log de postgres antes' postgres "$(sql "$OLD" "SELECT pg_get_userbyid(relowner) FROM pg_class WHERE oid = 'public.activity_log'::regclass")"

# Integrity deviations as ActivityIntegrity computes them (only rows of the activity log's structure).
deviations() {
    local database=$1 functions
    functions=$(sql "$database" "SELECT string_agg(proname || '=' || encode(sha256(convert_to(prosrc, 'UTF8')), 'hex'), ';') FROM pg_proc WHERE pronamespace = 'public'::regnamespace AND proname LIKE 'activity\\_log\\_%'")
    { printf "SET search_path = pg_catalog, pg_temp;\nSET bms.activity_functions = '%s';\n" "$functions"; cat "$REPO/server/src/main/resources/activity/activity-deviations.sql"; printf ';\n'; } |
        bms_pg_super "$database" -At
}

# --- The provisioning login and the move (twice: idempotent).
ERRORS=$(mktemp)
printf '%s' 'clave-de-aprovisionamiento-123' | bms_role_set_password "$PROVISIONER" "$BMS_PROVISIONER_ATTRIBUTES" || die 'rol de aprovisionamiento'
# A real connection of the provisioning login (update.sh defines the same with its passfile).
bms_pg_as_provisioner() {
    local database=$1
    shift
    PGPASSWORD='clave-de-aprovisionamiento-123' psql -X -q -v ON_ERROR_STOP=1 -h "$PG_HOST" -p "$PG_PORT" -U "$PROVISIONER" -d "$database" "$@"
}
# Registered databases that exist but break the rules (the control database, postgres, one owned by another role):
# nothing changes, and the message names them.
check 'con bases registradas que no cumplen las reglas no se cambia nada' eval '! bms_migrate_role_layout "$CONTROL" "$RUNTIME" "$PROVISIONER" 2> "$ERRORS"'
equals 'se nombran las tres bases rechazadas' 3 "$(grep -c '^ - ' "$ERRORS")"
check 'el mensaje dice que no se cambio ningun rol' grep -q 'no se cambio ningun rol' "$ERRORS"
equals 'el runtime sigue siendo miembro y el aprovisionamiento no gano nada' 't f' "$(sql postgres "SELECT pg_has_role('$RUNTIME', '$OWNER', 'MEMBER'), pg_has_role('$PROVISIONER', '$OWNER', 'SET')" | tr '|' ' ')"
equals 'las tablas de la empresa vieja siguen siendo de postgres' postgres "$(sql "$OLD" "SELECT pg_get_userbyid(relowner) FROM pg_class WHERE oid = 'public.activity_log'::regclass")"
sql "$CONTROL" "DELETE FROM companies WHERE code IN ('seca_tctl', 'seca_tpg', 'seca_tforeign')" >/dev/null
bms_migrate_role_layout "$CONTROL" "$RUNTIME" "$PROVISIONER" 2> "$ERRORS" || { cat "$ERRORS" >&2; die 'primera pasada'; }
check 'solo se avisa de la base inexistente' bash -c "grep -c 'se omite' '$ERRORS' | grep -qx 1 && grep -q 'bms_seca_tgone' '$ERRORS'"
rm -f "$ERRORS"
before=$(sql "$OLD" "SELECT pg_catalog.array_to_string(array_agg(relacl::text || relowner::text ORDER BY relname), ';') FROM pg_class WHERE relnamespace = 'public'::regnamespace")
bms_migrate_role_layout "$CONTROL" "$RUNTIME" "$PROVISIONER" 2>/dev/null || die 'segunda pasada'
equals 'segunda pasada: nada cambia' "$before" "$(sql "$OLD" "SELECT pg_catalog.array_to_string(array_agg(relacl::text || relowner::text ORDER BY relname), ';') FROM pg_class WHERE relnamespace = 'public'::regnamespace")"

# The access proof after the move fails when the runtime role lost access to a company.
sql postgres "REVOKE CONNECT ON DATABASE $NEW FROM $RUNTIME" >/dev/null
check 'la comprobacion de acceso detecta una empresa sin acceso' eval '! bms_verify_role_access "$CONTROL" "$RUNTIME" "$PROVISIONER" "$NEW $OWNER" 2>/dev/null'
sql postgres "GRANT CONNECT ON DATABASE $NEW TO $RUNTIME" >/dev/null
check 'y pasa con el acceso devuelto (conexion real del aprovisionamiento)' bms_verify_role_access "$CONTROL" "$RUNTIME" "$PROVISIONER" "$NEW $OWNER
$OLD $OWNER"
equals 'runtime ya no es miembro del dueno' f "$(sql postgres "SELECT pg_has_role('$RUNTIME', '$OWNER', 'MEMBER')")"
equals 'aprovisionamiento: SET sin heredar' 't f t' "$(sql postgres "SELECT pg_has_role('$PROVISIONER', '$OWNER', 'SET'), pg_has_role('$PROVISIONER', '$OWNER', 'USAGE'), pg_has_role('$PROVISIONER', '$RUNTIME', 'SET')" | tr '|' ' ')"
equals 'atributos' 'f f f t' "$(sql postgres "SELECT rolcreatedb, rolcreaterole, rolinherit, (SELECT rolinherit FROM pg_roles WHERE rolname = '$RUNTIME') FROM pg_roles WHERE rolname = '$PROVISIONER'" | tr '|' ' ')"
for database in $NEW $OLD; do
    equals "$database: todo el esquema public es del dueno" "0 + 0" "$(sql "$database" "SELECT count(*) FROM pg_class c WHERE relnamespace = 'public'::regnamespace AND relowner <> '$OWNER'::regrole") + $(sql "$database" "SELECT count(*) FROM pg_proc WHERE pronamespace = 'public'::regnamespace AND proowner <> '$OWNER'::regrole")"
    equals "$database: sin desviaciones del registro de actividad" '' "$(deviations "$database")"
    equals "$database: el runtime solo lee y agrega en el registro" 'SELECT,INSERT|USAGE' "$(sql "$database" "SELECT string_agg(privilege_type, ',' ORDER BY privilege_type DESC) FROM information_schema.role_table_grants WHERE grantee = '$RUNTIME' AND table_name = 'activity_log'")|$(sql "$database" "SELECT string_agg(privilege_type, ',') FROM information_schema.role_usage_grants WHERE grantee = '$RUNTIME' AND object_name = 'activity_log_id_seq'")"
    check "$database: el runtime no puede desactivar ni borrar los disparadores" \
        bash -c "! psql -X -q -h '$PG_HOST' -p '$PG_PORT' -U '$RUNTIME' -d '$database' -c 'ALTER TABLE activity_log DISABLE TRIGGER ALL' 2>/dev/null && ! psql -X -q -h '$PG_HOST' -p '$PG_PORT' -U '$RUNTIME' -d '$database' -c 'DROP TABLE activity_log_chain' 2>/dev/null"
    check "$database: el runtime sigue escribiendo y sellando filas" write_rows "$database" 2
    equals "$database: CONNECT del aprovisionamiento" t "$(sql postgres "SELECT has_database_privilege('$PROVISIONER', '$database', 'CONNECT')")"
done
equals 'empresa nueva: el runtime conserva lo que podia hacer (business_owner)' 'DELETE,INSERT,SELECT,UPDATE' "$(sql "$NEW" "SELECT string_agg(privilege_type, ',' ORDER BY privilege_type) FROM information_schema.role_table_grants WHERE grantee = '$RUNTIME' AND table_name = 'business_owner'")"
equals 'empresa vieja: el runtime conserva sus permisos explicitos y no gana otros' 'SELECT' "$(sql "$OLD" "SELECT string_agg(privilege_type, ',' ORDER BY privilege_type) FROM information_schema.role_table_grants WHERE grantee = '$RUNTIME' AND table_name = 'business_owner'")"
equals 'empresa vieja: secuencia de outbound' t "$(sql "$OLD" "SELECT has_sequence_privilege('$RUNTIME', 'outbound_events_id_seq', 'USAGE')")"
equals 'filas conservadas' '5 6' "$(sql "$NEW" 'SELECT count(*) FROM activity_log') $(sql "$OLD" 'SELECT count(*) FROM activity_log')"
equals 'filas selladas' '5 6' "$(sql "$NEW" 'SELECT count(*) FROM activity_log_chain') $(sql "$OLD" 'SELECT count(*) FROM activity_log_chain')"
equals 'la base ajena no se toca' 'postgres f' "$(sql postgres "SELECT pg_get_userbyid(datdba), COALESCE(datacl::text, '') LIKE '%$PROVISIONER%' FROM pg_database WHERE datname = '$FOREIGN'" | tr '|' ' ')"
equals 'empresa nueva: la llave de datos solo se lee y se crea' 'INSERT,SELECT' "$(sql "$NEW" "SELECT string_agg(privilege_type, ',' ORDER BY privilege_type) FROM information_schema.role_table_grants WHERE grantee = '$RUNTIME' AND table_name = 'tenant_data_keys'")"
equals 'base central: la llave de datos (V017) solo se lee y se crea' 'INSERT,SELECT' "$(sql "$CONTROL" "SELECT string_agg(privilege_type, ',' ORDER BY privilege_type) FROM information_schema.role_table_grants WHERE grantee = '$RUNTIME' AND table_name = 'tenant_data_keys'")"
equals 'auditoria del servidor: solo SELECT, INSERT' 'INSERT,SELECT' "$(sql "$CONTROL" "SELECT string_agg(privilege_type, ',' ORDER BY privilege_type) FROM information_schema.role_table_grants WHERE grantee = '$RUNTIME' AND table_name = 'provisioning_audit_log'")"
equals 'empresas que update.sh migra' "$NEW $OWNER|$OLD $OWNER" "$(bms_tenant_databases "$CONTROL" 2>/dev/null | paste -sd '|')"

# --- A2: a company migration as the owner's role through the provisioning login, with the fixed search_path.
MIGRATION_DIR=$(mktemp -d)
cat > "$MIGRATION_DIR/V900__seca_prueba.sql" <<'SQL'
BEGIN;
CREATE TABLE seca_prueba (id INT PRIMARY KEY, path TEXT NOT NULL DEFAULT current_setting('search_path'), who TEXT NOT NULL DEFAULT current_user, session TEXT NOT NULL DEFAULT session_user);
INSERT INTO seca_prueba (id) VALUES (1);
ALTER TABLE business_owner ADD COLUMN IF NOT EXISTS seca_columna TEXT;
COMMIT;
SQL
printf '%s:%s:*:%s:%s\n' "$PG_HOST" "$PG_PORT" "$PROVISIONER" 'clave-de-aprovisionamiento-123' > "$MIGRATION_DIR/pgpass"
chmod 600 "$MIGRATION_DIR/pgpass"
# shellcheck disable=SC2034 # read by migration_psql
MIGRATION_ROLE=$OWNER MIGRATION_LOGIN=$PROVISIONER MIGRATION_PASSFILE=$MIGRATION_DIR/pgpass MIGRATION_HOST=$PG_HOST MIGRATION_PORT=$PG_PORT
for database in $OLD $OLD; do
    check "migracion como el dueno en $database (dos veces)" apply_migration "$database" seca "$MIGRATION_DIR/V900__seca_prueba.sql"
done
equals 'la tabla nueva es del dueno, creada en public con el search_path fijo' "$OWNER|public|$OWNER|$PROVISIONER" "$(sql "$OLD" "SELECT pg_get_userbyid(c.relowner) || '|' || n.nspname || '|' || p.who || '|' || p.session FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace, seca_prueba p WHERE c.relname = 'seca_prueba'")"
equals 'search_path de la migracion' public "$(sql "$OLD" 'SELECT path FROM seca_prueba')"
equals 'una tabla que antes era de postgres se puede alterar' 1 "$(sql "$OLD" "SELECT count(*) FROM information_schema.columns WHERE table_name = 'business_owner' AND column_name = 'seca_columna'")"
equals 'historia de migraciones' 1 "$(sql "$OLD" "SELECT count(*) FROM schema_migrations WHERE module = 'seca'")"

# --- update.sh's own grant blocks (its control and company SQL, read from update.sh) with the runtime role owning
# nothing: every migration of a company as the owner through the provisioning login, then the grants, then what the
# runtime role can do on the two-step verification tables (control V017/V018, tenant V012/V013).
update_sql() { sed -n "/$1/,/^SQL\$/p" "$SCRIPTS/update.sh" | sed '1d;$d'; }
bms_pg_super postgres -c "CREATE DATABASE $FULL OWNER $OWNER" >/dev/null || fail 'base completa'
bms_pg_super postgres -c "GRANT CONNECT ON DATABASE $FULL TO $PROVISIONER, $RUNTIME" >/dev/null
for file in "$REPO"/database/core/migrations/V*.sql; do apply_migration "$FULL" core "$file" >/dev/null || fail "core $file"; done
for file in "$REPO"/database/tenant/migrations/V*.sql; do apply_migration "$FULL" tenant "$file" >/dev/null || fail "tenant $file"; done
for file in "$REPO"/modules/clientes/migrations/V*.sql; do apply_migration "$FULL" clientes "$file" >/dev/null || fail "clientes $file"; done
tenant_grants() {
    { printf 'GRANT USAGE ON SCHEMA public TO "%s";\n' "$RUNTIME"; update_sql 'migration_psql "\$tenant_db"'; } |
        migration_psql "$FULL" -q -v ON_ERROR_STOP=1 -v db_user="$RUNTIME" >/dev/null
}
check 'update.sh: permisos de la empresa como el dueno, por el login de aprovisionamiento' tenant_grants
update_sql 'migration_psql "\$CONTROL_DB"' | bms_pg_super "$CONTROL" -v db_user="$RUNTIME" -v provisioner="$PROVISIONER" >/dev/null || fail 'update.sh: permisos de la base central'
grants() { sql "$1" "SELECT string_agg(privilege_type, ',' ORDER BY privilege_type) FROM information_schema.role_table_grants WHERE grantee = '$RUNTIME' AND table_name = '$2'"; }
for database in $CONTROL $FULL; do
    equals "$database: llave de datos" 'INSERT,SELECT' "$(grants "$database" tenant_data_keys)"
    equals "$database: verificacion en dos pasos" 'DELETE,INSERT,SELECT,UPDATE|DELETE,INSERT,SELECT,UPDATE' "$(grants "$database" application_user_two_factor)|$(grants "$database" application_user_recovery_codes)"
    equals "$database: recuperacion por correo" 'INSERT,SELECT,UPDATE' "$(grants "$database" application_user_2fa_email_recovery)"
    check "$database: el runtime usa las tablas y no borra la recuperacion por correo" bash -c '
        run() { psql -X -q -v ON_ERROR_STOP=1 -h "$1" -p "$2" -U "$3" -d "$4" -c "$5" >/dev/null 2>&1; }
        run "$@" "INSERT INTO tenant_data_keys (id, wrapped_key) VALUES (1, '"'"'x'"'"') ON CONFLICT DO NOTHING" &&
        run "$@" "UPDATE application_user_2fa_email_recovery SET status = status WHERE FALSE" &&
        run "$@" "DELETE FROM application_user_recovery_codes WHERE FALSE" &&
        ! run "$@" "DELETE FROM application_user_2fa_email_recovery" &&
        ! run "$@" "UPDATE tenant_data_keys SET key_version = 2"' _ "$PG_HOST" "$PG_PORT" "$RUNTIME" "$database"
done
equals 'empresa completa: todo es del dueno' 0 "$(sql "$FULL" "SELECT count(*) FROM pg_class WHERE relnamespace = 'public'::regnamespace AND relowner <> '$OWNER'::regrole")"
MIGRATION_ROLE=''
rm -rf -- "$MIGRATION_DIR"

# --- Rollback helper: the previous release needs the membership back; a new pass removes it again.
bms_restore_runtime_membership "$CONTROL" "$RUNTIME" || fail 'devolver la pertenencia'
equals 'rollback: el runtime vuelve a ser miembro' t "$(sql postgres "SELECT pg_has_role('$RUNTIME', '$OWNER', 'MEMBER')")"
bms_migrate_role_layout "$CONTROL" "$RUNTIME" "$PROVISIONER" 2>/dev/null || fail 'pasada tras el rollback'
equals 'y otra pasada la quita' f "$(sql postgres "SELECT pg_has_role('$RUNTIME', '$OWNER', 'MEMBER')")"
check 'la comprobacion final pasa' bms_check_role_layout "$CONTROL" "$RUNTIME" "$PROVISIONER"
finish
