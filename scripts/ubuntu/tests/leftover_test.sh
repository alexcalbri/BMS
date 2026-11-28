#!/usr/bin/env bash
# shellcheck disable=SC2016 # SQL and eval bodies expand later on purpose
# What a failed update leaves behind, with update.sh's own step 0 (resolve_provisioner_role, precheck_role_layout and
# report_layout_state, read from the file) and the real psql:
#   - the provisioning role exists but server.env (restored by the rollback) does not name it: it is ADOPTED when it is
#     what an attempt leaves, and the update STOPS, changing nothing, when it is anything else;
#   - company databases half moved (some objects already owned by the database owner, some grants missing, one
#     database fully moved, the provisioning role holding CONNECT on some): the move finishes the work and ends in
#     exactly the state a fresh move gives, and a second pass changes nothing.
set -uo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"
pg_available || { printf '  (omitido: no hay PostgreSQL en %s)\n' "$PG"; exit 0; }
# shellcheck source=../db-roles.sh
source "$SCRIPTS/db-roles.sh"
# shellcheck source=../migrations.sh
source "$SCRIPTS/migrations.sh"
# shellcheck source=../rehearse.sh
source "$SCRIPTS/rehearse.sh"
# shellcheck source=fixture.sh
source "$(dirname -- "${BASH_SOURCE[0]}")/fixture.sh"
fx_names seca_l
TC=bms_seca_l_c
TD=bms_seca_l_d
WORK=$(mktemp -d)
cleanup() {
    psu postgres -c "DROP DATABASE IF EXISTS $TC WITH (FORCE)" >/dev/null 2>&1
    psu postgres -c "DROP DATABASE IF EXISTS $TD WITH (FORCE)" >/dev/null 2>&1
    fx_cleanup
    psu postgres -c "DROP ROLE IF EXISTS seca_l_otro" >/dev/null 2>&1
    psu postgres -c "DROP ROLE IF EXISTS seca_l_miembro" >/dev/null 2>&1
    rm -rf -- "$WORK"
}
cleanup
WORK=$(mktemp -d)
trap cleanup EXIT

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
warn() { printf 'AVISO: %s\n' "$*" >&2; }
sed -n '/^# BEGIN validators/,/^# END validators/p' "$SCRIPTS/update.sh" > "$WORK/validators.sh"
sed -n '/^# BEGIN role-layout/,/^# END role-layout/p' "$SCRIPTS/update.sh" > "$WORK/layout.sh"
sed -n '/^config_has_key() {/,/^}/p;/^config_value() {/,/^}/p' "$SCRIPTS/update.sh" > "$WORK/config.sh"
check 'se leyeron los bloques de update.sh' test -s "$WORK/layout.sh" -a -s "$WORK/config.sh" -a -s "$WORK/validators.sh"
# shellcheck source=/dev/null
source "$WORK/validators.sh"
# shellcheck source=/dev/null
source "$WORK/layout.sh"
# shellcheck source=/dev/null
source "$WORK/config.sh"
CONFIG_FILE=$WORK/server.env

# shellcheck disable=SC2034 # DB_USER, CONTROL_DB and DEFAULT_PROVISIONER are read by update.sh's functions
setup() {
    fx_cleanup
    fx_minimal
    DB_USER=$RUNTIME
    CONTROL_DB=$CONTROL
    DEFAULT_PROVISIONER=$PROVISIONER
    printf 'DB_USER=%s\nDB_URL=jdbc:postgresql://127.0.0.1:5432/%s\n' "$RUNTIME" "$CONTROL" > "$CONFIG_FILE"
    LAYOUT_OWNER_ROLES=$(bms_server_owner_roles "$CONTROL")
}
snapshot() { bms_rehearse_snapshot "$1" "$WORK" 2>/dev/null; }
# step 0 for a server.env without the provisioning keys; prints what it says; the exit status is the update's.
step0() { ( resolve_provisioner_role; printf 'PLANNED=%s ADOPTED=%s\n' "$PLANNED_PROVISIONER" "$PROVISIONER_ADOPTED" ) 2>&1; }

# --- 1. The role does not exist: created as before.
setup
equals 'sin rol: se crea el de siempre' "PLANNED=$PROVISIONER ADOPTED=false" "$(step0)"

# --- 2. What the live server has: LOGIN NOINHERIT, no memberships, owns nothing.
psu postgres -c "CREATE ROLE $PROVISIONER LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE"
snapshot "$WORK/before"
out=$(step0)
equals 'rol sobrante limpio: se adopta' "PLANNED=$PROVISIONER ADOPTED=true" "$(tail -n 1 <<< "$out")"
check 'y lo dice' grep -q 'Se adopta con una contrasena nueva' <<< "$out"
snapshot "$WORK/after"
check 'el paso 0 no cambia nada' cmp -s "$WORK/before" "$WORK/after"
# The adoption itself (ensure_provisioner_role's call), then the move; the new password replaces the old one.
before_hash=$(sql postgres "SELECT md5(rolpassword) FROM pg_authid WHERE rolname = '$PROVISIONER'")
printf '%s' "$FX_PASSWORD" | bms_role_set_password "$PROVISIONER" "$BMS_PROVISIONER_ATTRIBUTES" || fail 'adopcion'
after_hash=$(sql postgres "SELECT md5(rolpassword) FROM pg_authid WHERE rolname = '$PROVISIONER'")
equals 'adoptado: la contrasena es nueva' si "$([[ -n $after_hash && $before_hash != "$after_hash" ]] && echo si || echo no)"
bms_migrate_role_layout "$CONTROL" "$RUNTIME" "$PROVISIONER" "$LAYOUT_OWNER_ROLES" 2>/dev/null
equals 'adoptado: el movimiento termina' 0 "$?"
equals 'adoptado: atributos y membresias del aprovisionamiento' 'f f f t t' "$(sql postgres "SELECT rolinherit, rolcreatedb, rolcreaterole, pg_has_role('$PROVISIONER', '$OWNER', 'SET'), NOT pg_has_role('$PROVISIONER', '$OWNER', 'USAGE') FROM pg_roles WHERE rolname = '$PROVISIONER'" | tr '|' ' ')"

# --- 3. What a failed attempt leaves: memberships and CONNECT grants of the provisioner, rollback done.
setup
printf '%s' "$FX_PASSWORD" | bms_role_set_password "$PROVISIONER" "$BMS_PROVISIONER_ATTRIBUTES"
BMS_TEST_FAIL_AT=after_tenant_layout bms_migrate_role_layout "$CONTROL" "$RUNTIME" "$PROVISIONER" "$LAYOUT_OWNER_ROLES" 2>/dev/null
equals 'el intento falla tras mover la primera empresa' 1 "$?"
bms_restore_runtime_membership "$RUNTIME" "$OWNER" "$OWNER2" 2>/dev/null
out=$(step0)
equals 'rol sobrante con pertenencias y CONNECT de un intento: se adopta' "PLANNED=$PROVISIONER ADOPTED=true" "$(tail -n 1 <<< "$out")"
equals 'el estado se informa como intento anterior' 'adoptable touched' "$(bms_provisioner_assess "$RUNTIME" "$PROVISIONER" "$LAYOUT_OWNER_ROLES")"
PLANNED_PROVISIONER=$PROVISIONER
report=$(report_layout_state "$(bms_tenant_databases "$CONTROL" 2>/dev/null)" 2>&1)
check 'report_layout_state avisa de las huellas del intento anterior' grep -q 'huellas de un intento anterior' <<< "$report"
bms_migrate_role_layout "$CONTROL" "$RUNTIME" "$PROVISIONER" "$LAYOUT_OWNER_ROLES" 2>/dev/null
equals 'un nuevo intento termina' 0 "$?"

# --- 4. Anything else: the update stops with the reason and changes nothing.
unexpected() {
    local description=$1 expected=$2 prepare=$3 out
    setup
    psu postgres -c "DROP ROLE IF EXISTS seca_l_otro"
    psu postgres -c "DROP ROLE IF EXISTS seca_l_miembro"
    psu postgres -c "CREATE ROLE $PROVISIONER LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE"
    psu postgres -c "CREATE ROLE seca_l_otro NOLOGIN"
    psu postgres -c "CREATE ROLE seca_l_miembro NOLOGIN"
    eval "$prepare"
    snapshot "$WORK/before"
    out=$(step0)
    equals "$description: el paso 0 se detiene" 1 "$(grep -q 'ADOPTED=' <<< "$out" && echo 0 || echo 1)"
    check "$description: dice el motivo ($expected)" grep -q -- "$expected" <<< "$out"
    check "$description: dice que no se cambio nada" grep -q 'No se cambio nada' <<< "$out"
    snapshot "$WORK/after"
    check "$description: el cluster no cambio" cmp -s "$WORK/before" "$WORK/after"
}
unexpected 'CREATEDB' 'SUPERUSER, CREATEDB, CREATEROLE' 'psu postgres -c "ALTER ROLE $PROVISIONER CREATEDB"'
unexpected 'CREATEROLE' 'SUPERUSER, CREATEDB, CREATEROLE' 'psu postgres -c "ALTER ROLE $PROVISIONER CREATEROLE"'
unexpected 'SUPERUSER' 'SUPERUSER, CREATEDB, CREATEROLE' 'psu postgres -c "ALTER ROLE $PROVISIONER SUPERUSER"'
unexpected 'REPLICATION' 'REPLICATION' 'psu postgres -c "ALTER ROLE $PROVISIONER REPLICATION"'
unexpected 'miembro de pg_read_all_data' 'es miembro de pg_read_all_data' 'psu postgres -c "GRANT pg_read_all_data TO $PROVISIONER"'
unexpected 'miembro de otro rol' 'es miembro de seca_l_otro' 'psu postgres -c "GRANT seca_l_otro TO $PROVISIONER"'
unexpected 'otro rol es miembro de el' 'seca_l_miembro es miembro de el' 'psu postgres -c "GRANT $PROVISIONER TO seca_l_miembro"'
unexpected 'dueno de una tabla' 'objeto' 'psu "$TB" -c "CREATE TABLE zz_suyo (id int); ALTER TABLE zz_suyo OWNER TO $PROVISIONER"'
unexpected 'privilegio sobre una tabla' 'objeto' 'psu "$TB" -c "GRANT SELECT ON notas TO $PROVISIONER"'
unexpected 'configuracion propia' 'configuracion propia' 'psu postgres -c "ALTER ROLE $PROVISIONER SET search_path = public"'
unexpected 'caducidad' 'limite de conexiones o fecha de caducidad' 'psu postgres -c "ALTER ROLE $PROVISIONER VALID UNTIL '"'"'2030-01-01'"'"'"'
unexpected 'limite de conexiones' 'limite de conexiones o fecha de caducidad' 'psu postgres -c "ALTER ROLE $PROVISIONER CONNECTION LIMIT 1"'

# --- 5. Half moved company databases.
setup
fx_provisioner
for database in $TC $TD; do
    psu postgres <<SQL || die "base $database"
CREATE DATABASE $database OWNER $OWNER;
REVOKE ALL ON DATABASE $database FROM PUBLIC;
GRANT CONNECT ON DATABASE $database TO $RUNTIME;
SQL
    fx_minimal_tenant "$database" postgres
    psu "$database" <<SQL || die "permisos de $database"
GRANT USAGE ON SCHEMA public TO $RUNTIME;
GRANT SELECT, INSERT, UPDATE ON application_users TO $RUNTIME;
GRANT SELECT, INSERT ON activity_log, activity_log_chain TO $RUNTIME;
GRANT USAGE ON SEQUENCE activity_log_id_seq TO $RUNTIME;
SQL
    psu "$CONTROL" -c "INSERT INTO companies VALUES ('${database#bms_seca_l_}', '$database')"
done
# TA: fully moved by an earlier attempt (a transaction of its own, then the failure); TC: half moved (a previous
# version, or by hand): some objects the owner's, some grants missing; TD: untouched twin of TC.
bms_run_script postgres "$(bms_sql_connect_grant)" db="$CONTROL" member="$PROVISIONER"
bms_run_script postgres "$(bms_sql_connect_grant)" db="$TA" member="$PROVISIONER"
bms_run_script postgres "$(bms_sql_grant_set_only)" role="$OWNER" member="$PROVISIONER"
bms_migrate_tenant_layout "$TA" "$OWNER" "$RUNTIME"
psu "$TC" -c "ALTER TABLE application_users OWNER TO $OWNER; ALTER TABLE notas OWNER TO $OWNER; ALTER VIEW notas_vista OWNER TO $OWNER; ALTER FUNCTION vieja_funcion() OWNER TO $OWNER; GRANT SELECT, INSERT, UPDATE, DELETE ON business_owner TO $RUNTIME;"
bms_run_script postgres "$(bms_sql_connect_grant)" db="$TC" member="$PROVISIONER"
LAYOUT_OWNER_ROLES=$(bms_server_owner_roles "$CONTROL")
tenants=$(bms_tenant_databases "$CONTROL" 2>/dev/null)
layout=$(bms_layout_status "$RUNTIME" "$PROVISIONER" "$tenants")
equals 'estado: TA ya movida (nada pendiente, intento visto)' "$TA $OWNER 0 7 1" "$(grep "^$TA " <<< "$layout")"
equals 'estado: TC a medio mover' "$TC $OWNER 5 3 1" "$(grep "^$TC " <<< "$layout")"
equals 'estado: TD sin tocar' "$TD $OWNER 9 0 0" "$(grep "^$TD " <<< "$layout")"
PLANNED_PROVISIONER=$PROVISIONER
report=$(report_layout_state "$tenants" 2>&1)
check 'el paso 0 nombra la empresa a medio mover' grep -q "Empresa $TC: a medio mover" <<< "$report"
check 'y avisa del intento anterior' grep -q 'huellas de un intento anterior' <<< "$report"
bms_migrate_role_layout "$CONTROL" "$RUNTIME" "$PROVISIONER" "$LAYOUT_OWNER_ROLES" 2>/dev/null
equals 'el movimiento termina desde ese estado' 0 "$?"
# The same owner for every object as a fresh move gives; the grants of what was already the owner's are kept (the runtime
# role could do everything on those through its membership, and the move writes that down before it leaves the role).
owners_of() { fx_state "$1" | awk '{ print $1, $2, $3 }'; }
equals 'TC acaba con los mismos duenos que una empresa sin tocar (TD)' "$(owners_of "$TD")" "$(owners_of "$TC")"
equals 'y con el mismo registro de actividad y llave de datos (TD y TC)' "$(fx_state "$TD" | grep -E ' (activity_log|activity_log_chain|activity_log_id_seq|tenant_data_keys) ')" "$(fx_state "$TC" | grep -E ' (activity_log|activity_log_chain|activity_log_id_seq|tenant_data_keys) ')"
equals 'TC: lo que el runtime podia hacer en lo ya movido se conserva (DELETE en application_users, business_owner)' 't t' "$(sql "$TC" "SELECT has_table_privilege('$RUNTIME', 'application_users', 'DELETE'), has_table_privilege('$RUNTIME', 'business_owner', 'DELETE')" | tr '|' ' ')"
equals 'TC: ni gana lo que no tenia (DELETE en application_users de TD)' f "$(sql "$TD" "SELECT has_table_privilege('$RUNTIME', 'application_users', 'DELETE')")"
equals 'nada queda de postgres ni del runtime' 0 "$(sql "$TC" "SELECT count(*) FROM pg_class WHERE relnamespace = 'public'::regnamespace AND relowner <> '$OWNER'::regrole")"
state_c=$(fx_state "$TC")
state_a=$(fx_state "$TA")
bms_migrate_role_layout "$CONTROL" "$RUNTIME" "$PROVISIONER" "$LAYOUT_OWNER_ROLES" 2>/dev/null
equals 'una segunda pasada no cambia nada (TC y TA)' "$state_c|$state_a" "$(fx_state "$TC")|$(fx_state "$TA")"
equals 'estado final: nada pendiente en ninguna' '0 0 0 0' "$(bms_layout_status "$RUNTIME" "$PROVISIONER" "$tenants" | awk '{ print $3 }' | paste -sd ' ' -)"
check 'comprobacion final' bms_check_role_layout "$CONTROL" "$RUNTIME" "$PROVISIONER"
finish
