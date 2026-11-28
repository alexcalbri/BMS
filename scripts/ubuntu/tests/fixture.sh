#!/usr/bin/env bash
# shellcheck disable=SC2016 # SQL and eval bodies expand later on purpose
# Fixtures of the PostgreSQL role tests (sourced after lib.sh and db-roles.sh): a server as older versions left it.
#
#   fx_names <prefix>      OWNER, OWNER2 (two server owners), RUNTIME, PROVISIONER, CONTROL, TA, TB, FOREIGN names
#   fx_cleanup             drops everything the prefix created
#   fx_minimal             small tables, no migrations:
#                          runtime a member of both owner roles (the old layout); company TA owned by OWNER with every
#                          table made by postgres (an older update.sh); company TB owned by OWNER2 whose tables the
#                          server created (owner's tables, the runtime role with no explicit grants)
#   fx_provisioner         creates the provisioning login (bms_role_set_password, SCRAM) and bms_pg_as_provisioner
#   fx_release             like fx_minimal's roles, but the databases come from the real migrations as the PREVIOUS
#                          release left them (control up to V014; companies with only their first migrations), so
#                          update.sh has real migrations to apply
#   fx_apply <db> <module> <file> [role]   one migration as the superuser (or after SET ROLE <role>)
#   fx_state <db>          every owner and ACL of public, one line each (to compare two databases)
# shellcheck source=lib.sh
FX_PASSWORD='clave-de-aprovisionamiento-123'

fx_names() {
    FX=$1
    OWNER=${FX}_own
    OWNER2=${FX}_own2
    RUNTIME=${FX}_rt
    PROVISIONER=${FX}_prov
    CONTROL=bms_${FX}_ctl
    TA=bms_${FX}_a
    TB=bms_${FX}_b
    FOREIGN=bms_${FX}_foreign
}

fx_cleanup() {
    local database role
    for database in $CONTROL $TA $TB $FOREIGN; do
        psu postgres -c "DROP DATABASE IF EXISTS $database WITH (FORCE)" >/dev/null 2>&1
    done
    for role in $PROVISIONER $RUNTIME $OWNER $OWNER2; do
        if [[ $(sql postgres "SELECT count(*) FROM pg_roles WHERE rolname = '$role'") == 1 ]]; then
            psu postgres -c "DROP OWNED BY $role CASCADE" >/dev/null 2>&1
            psu postgres -c "DROP ROLE $role" >/dev/null 2>&1
        fi
    done
    return 0
}

fx_roles() {
    psu postgres <<SQL || die 'fixture: roles'
CREATE ROLE $OWNER LOGIN NOSUPERUSER CREATEDB CREATEROLE PASSWORD 'owner-pass';
CREATE ROLE $OWNER2 LOGIN NOSUPERUSER CREATEDB CREATEROLE PASSWORD 'owner-pass';
CREATE ROLE $RUNTIME LOGIN PASSWORD 'runtime-pass';
GRANT $OWNER TO $RUNTIME;
GRANT $OWNER2 TO $RUNTIME;
SQL
}

fx_minimal() {
    fx_roles
    psu postgres <<SQL || die 'fixture: bases'
CREATE DATABASE $CONTROL;
REVOKE ALL ON DATABASE $CONTROL FROM PUBLIC;
GRANT CONNECT ON DATABASE $CONTROL TO $RUNTIME, $OWNER, $OWNER2;
CREATE DATABASE $TA OWNER $OWNER;
REVOKE ALL ON DATABASE $TA FROM PUBLIC;
GRANT CONNECT ON DATABASE $TA TO $RUNTIME;
CREATE DATABASE $TB OWNER $OWNER2;
REVOKE ALL ON DATABASE $TB FROM PUBLIC;
GRANT CONNECT ON DATABASE $TB TO $RUNTIME;
CREATE DATABASE $FOREIGN;
SQL
    psu "$CONTROL" <<SQL || die 'fixture: central'
CREATE TABLE application_users (id uuid PRIMARY KEY, postgres_role text);
CREATE TABLE server_owners (user_id uuid PRIMARY KEY REFERENCES application_users(id));
CREATE TABLE companies (code text PRIMARY KEY, database_name text NOT NULL);
CREATE TABLE provisioning_audit_log (id int PRIMARY KEY);
CREATE TABLE tenant_data_keys (id int PRIMARY KEY, wrapped_key text);
INSERT INTO application_users VALUES ('11111111-1111-1111-1111-111111111111', '$OWNER'), ('22222222-2222-2222-2222-222222222222', '$OWNER2');
INSERT INTO server_owners VALUES ('11111111-1111-1111-1111-111111111111'), ('22222222-2222-2222-2222-222222222222');
INSERT INTO companies VALUES ('ta', '$TA'), ('tb', '$TB');
GRANT USAGE ON SCHEMA public TO $RUNTIME;
GRANT SELECT, INSERT, UPDATE, DELETE ON companies, application_users, tenant_data_keys, provisioning_audit_log TO $RUNTIME;
SQL
    fx_minimal_tenant "$TA" postgres
    fx_minimal_tenant "$TB" "$OWNER2"
    # TA (legacy): explicit grants for some tables, and the rest only through membership; TB: membership only.
    psu "$TA" <<SQL || die 'fixture: permisos de TA'
GRANT USAGE ON SCHEMA public TO $RUNTIME;
GRANT SELECT, INSERT, UPDATE ON application_users TO $RUNTIME;
GRANT SELECT, INSERT, UPDATE, DELETE ON business_owner TO $RUNTIME;
GRANT SELECT, INSERT ON activity_log, activity_log_chain TO $RUNTIME;
GRANT USAGE ON SEQUENCE activity_log_id_seq TO $RUNTIME;
SQL
}

# Tables of a company database owned by [role] (postgres or an owner role).
fx_minimal_tenant() {
    local database=$1 role=$2
    { printf 'SET ROLE %s;\n' "$role"; cat <<'SQL'; } | psu "$database" || die "fixture: tablas de $database"
CREATE TABLE application_users (id uuid PRIMARY KEY, postgres_role text);
CREATE TABLE business_owner (id serial PRIMARY KEY, name text);
CREATE TABLE tenant_data_keys (id int PRIMARY KEY, wrapped_key text);
CREATE TABLE activity_log (id bigserial PRIMARY KEY, summary text);
CREATE TABLE activity_log_chain (id bigint PRIMARY KEY, seal text);
CREATE TABLE notas (id serial PRIMARY KEY, texto text);
CREATE VIEW notas_vista AS SELECT id, texto FROM notas;
CREATE TYPE estado AS ENUM ('a', 'b');
CREATE FUNCTION vieja_funcion() RETURNS int LANGUAGE sql AS 'SELECT 1';
INSERT INTO business_owner (name) VALUES ('x');
SQL
}

# The provisioning login, with a real password, and the connection of update.sh's passfile as a function.
fx_provisioner() {
    printf '%s' "$FX_PASSWORD" | bms_role_set_password "$PROVISIONER" "$BMS_PROVISIONER_ATTRIBUTES" || die 'fixture: rol de aprovisionamiento'
}
bms_pg_as_provisioner() {
    local database=$1
    shift
    PGPASSWORD=$FX_PASSWORD PGOPTIONS='-c client_min_messages=warning' psql -h "$PG_HOST" -p "$PG_PORT" -U "$PROVISIONER" -d "$database" "$@"
}

fx_apply() {
    local database=$1 module=$2 file=$3 role=${4:-} script
    script=$(bms_migration_script "$module" "$file") || return 1
    { printf 'BEGIN;\n'; [[ -z $role ]] || printf 'SET ROLE "%s";\n' "$role"; printf '%s\nCOMMIT;\n' "$script"; } | psu "$database" > /dev/null
}

# The databases as the previous release left them, from the real migrations.
fx_release() {
    local file
    fx_roles
    psu postgres <<SQL || die 'fixture: bases'
CREATE DATABASE $CONTROL;
REVOKE ALL ON DATABASE $CONTROL FROM PUBLIC;
GRANT CONNECT ON DATABASE $CONTROL TO $RUNTIME, $OWNER, $OWNER2;
CREATE DATABASE $TA OWNER $OWNER;
REVOKE ALL ON DATABASE $TA FROM PUBLIC;
GRANT CONNECT ON DATABASE $TA TO $RUNTIME;
CREATE DATABASE $TB OWNER $OWNER2;
REVOKE ALL ON DATABASE $TB FROM PUBLIC;
GRANT CONNECT ON DATABASE $TB TO $RUNTIME;
SQL
    cd "$REPO" || die 'repo'
    for file in V001__create_application_users V002__create_application_sessions V003__add_application_user_profile V004__add_application_user_code V006__add_application_user_language; do
        fx_apply "$CONTROL" core "database/core/migrations/$file.sql" || die "fixture: core $file"
    done
    for file in database/control/migrations/V0{01..14}__*.sql; do
        fx_apply "$CONTROL" control "$file" || die "fixture: control $file"
    done
    psu "$CONTROL" <<SQL || die 'fixture: empresas'
INSERT INTO application_users (id, postgres_role, is_active, display_name, email) VALUES
  ('11111111-1111-1111-1111-111111111111', '$OWNER', TRUE, 'Duena', 'duena@example.com'),
  ('22222222-2222-2222-2222-222222222222', '$OWNER2', TRUE, 'Duena Dos', 'duena2@example.com');
INSERT INTO server_owners (user_id) VALUES ('11111111-1111-1111-1111-111111111111'), ('22222222-2222-2222-2222-222222222222');
INSERT INTO companies (id, code, name, database_name, owner_group) VALUES
  (gen_random_uuid(), '${FX}_a', 'Vieja', '$TA', gen_random_uuid()),
  (gen_random_uuid(), '${FX}_b', 'Nueva', '$TB', gen_random_uuid());
GRANT USAGE ON SCHEMA public TO $RUNTIME;
GRANT SELECT, INSERT, UPDATE, DELETE ON companies, application_users TO $RUNTIME;
GRANT SELECT, INSERT ON provisioning_audit_log TO $RUNTIME;
SQL
    # TA: an older update.sh created and migrated it as postgres. TB: the server created it (owner's tables).
    fx_release_tenant "$TA" ''
    fx_release_tenant "$TB" "$OWNER2"
    psu "$TA" <<SQL || die 'fixture: permisos de TA'
GRANT USAGE ON SCHEMA public TO $RUNTIME;
GRANT SELECT, INSERT, UPDATE ON application_users TO $RUNTIME;
GRANT SELECT ON business_owner, tenant_modules TO $RUNTIME;
SQL
    psu "$TB" <<SQL || die 'fixture: permisos de TB'
GRANT USAGE ON SCHEMA public TO $RUNTIME;
SQL
}

fx_release_tenant() {
    local database=$1 role=$2
    fx_apply "$database" core database/core/migrations/V001__create_application_users.sql "$role" || die "fixture: $database core V001"
    fx_apply "$database" core database/core/migrations/V002__create_application_sessions.sql "$role" || die "fixture: $database core V002"
    fx_apply "$database" tenant database/tenant/migrations/V001__create_business_owner.sql "$role" || die "fixture: $database tenant V001"
    fx_apply "$database" tenant database/tenant/migrations/V003__create_tenant_modules.sql "$role" || die "fixture: $database tenant V003"
    fx_apply "$database" clientes modules/clientes/migrations/V001__create_customers.sql "$role" || die "fixture: $database clientes V001"
}

# Owner and ACL of every object of schema public, one line each.
fx_state() {
    sql "$1" "SELECT string_agg(x, E'\n' ORDER BY x) FROM (
        SELECT 'rel ' || c.relname || ' ' || pg_get_userbyid(c.relowner) || ' ' || COALESCE(c.relacl::text, '') AS x FROM pg_class c WHERE c.relnamespace = 'public'::regnamespace
        UNION ALL SELECT 'fn ' || p.oid::regprocedure::text || ' ' || pg_get_userbyid(p.proowner) FROM pg_proc p WHERE p.pronamespace = 'public'::regnamespace
        UNION ALL SELECT 'type ' || t.typname || ' ' || pg_get_userbyid(t.typowner) FROM pg_type t WHERE t.typnamespace = 'public'::regnamespace AND t.typtype = 'e') q"
}
