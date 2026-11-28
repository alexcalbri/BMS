#!/usr/bin/env bash
# shellcheck disable=SC2016 # SQL and eval bodies expand later on purpose
# The output of every captured query of db-roles.sh and backup.sh against the REAL psql, with the production-shaped
# transport (lib.sh: no -q, no -X, no ON_ERROR_STOP): exact lines, never a command tag, and every value that becomes a
# role or database name is validated where it is used.
#
# Why the old tests missed the live failure ("role "SET" does not exist"): their transport (the old bms_pg_super of
# lib.sh) always passed -q, while update.sh's did not. psql prints a command tag ("SET") for every statement that is
# not a SELECT unless -q is on, and bms_server_owner_roles sent SET search_path before its SELECT, so under update.sh's
# transport the function returned two lines, "SET" and the role. The tests ran the function through a transport that
# could not show it. Now the transport is the production one and db-roles.sh adds the flags itself.
set -uo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"
pg_available || { printf '  (omitido: no hay PostgreSQL en %s)\n' "$PG"; exit 0; }
# shellcheck source=../db-roles.sh
source "$SCRIPTS/db-roles.sh"
# shellcheck source=../migrations.sh
source "$SCRIPTS/migrations.sh"
# shellcheck source=fixture.sh
source "$(dirname -- "${BASH_SOURCE[0]}")/fixture.sh"
fx_names seca_o
cleanup() { fx_cleanup; }
cleanup
trap cleanup EXIT
fx_minimal
fx_provisioner

# --- The old failure, reproduced: the pre-fix function under the production-shaped transport.
old_bms_server_owner_roles() {
    local control_db=$1
    bms_pg_transport "$control_db" -X -At -v ON_ERROR_STOP=1 <<'SQL'
SET search_path = pg_catalog, pg_temp;
SELECT DISTINCT r.rolname FROM public.server_owners o
JOIN public.application_users u ON u.id = o.user_id
JOIN pg_catalog.pg_roles r ON r.rolname = u.postgres_role
WHERE NOT r.rolsuper AND r.rolname !~ '^pg_' ORDER BY 1;
SQL
}
equals 'reproducido: la funcion de antes devuelve SET ademas de los roles' "SET|$OWNER|$OWNER2" "$(old_bms_server_owner_roles "$CONTROL" | paste -sd '|' -)"
equals 'con el transporte de las pruebas de antes (-q) no se veia' "$OWNER|$OWNER2" "$(PGOPTIONS='-c client_min_messages=warning' psql -X -q -At -v ON_ERROR_STOP=1 -h "$PG_HOST" -p "$PG_PORT" -U postgres -d "$CONTROL" -c 'SET search_path = pg_catalog, pg_temp' -c "SELECT rolname FROM pg_roles WHERE rolname IN ('$OWNER', '$OWNER2') ORDER BY 1" | paste -sd '|' -)"
# And the failure it caused: GRANT with the stray token as a role.
equals 'y el GRANT con ese valor falla con el mensaje del servidor' 'role "SET" does not exist' "$(printf 'GRANT :"role" TO :"member";\n' | psql -X -q -h "$PG_HOST" -p "$PG_PORT" -U postgres -d postgres -v role=SET -v member="$RUNTIME" 2>&1 | sed 's/^ERROR:  //')"

# --- bms_pg_query / bms_pg_exec: rows only, whatever precedes the SELECT.
equals 'bms_pg_query: SET antes del SELECT no deja rastro' 1 "$(bms_pg_query postgres <<'SQL'
SET search_path = pg_catalog, pg_temp;
SET lock_timeout = '1s';
SELECT 1;
SQL
)"
equals 'bms_pg_query: BEGIN, SELECT ... \gset, DO y COMMIT no dejan rastro' 'a|b' "$(bms_pg_query postgres <<'SQL'
BEGIN;
SELECT 'a' AS x \gset
DO $do$ BEGIN PERFORM 1; END $do$;
SELECT :'x' || '|b';
COMMIT;
SQL
)"
equals 'bms_pg_query: \gexec no imprime las etiquetas de lo que ejecuta' '' "$(bms_pg_query postgres <<'SQL'
SELECT 'SET search_path = pg_catalog' \gexec
SQL
)"
equals 'bms_pg_exec: no imprime nada' '' "$(bms_pg_exec postgres <<'SQL'
SET search_path = pg_catalog;
CREATE TEMP TABLE t (id int);
INSERT INTO t VALUES (1);
SQL
)"
check 'bms_pg_query: un error de SQL detiene (ON_ERROR_STOP) y falla' eval '! bms_pg_query postgres 2>/dev/null <<< "SELECT no_existe; SELECT 2" > /dev/null'
psqlrc=$(mktemp)
printf '\\echo RC-LEIDO\n\\pset format aligned\n\\pset tuples_only off\n' > "$psqlrc"
equals 'bms_pg_query: un .psqlrc del entorno no se lee' 1 "$(PSQLRC=$psqlrc bms_pg_query postgres -c 'SELECT 1')"
rm -f "$psqlrc"
equals 'bms_pg_count: un numero' 2 "$(bms_pg_count postgres <<'SQL'
SET search_path = pg_catalog;
SELECT 2;
SQL
)"
check 'bms_pg_count: rechaza lo que no es un numero' eval '! bms_pg_count postgres -c "SELECT '"'"'SET'"'"'" 2>/dev/null'

# --- Every captured query, exact lines.
equals 'bms_server_owner_roles' "$OWNER|$OWNER2" "$(bms_server_owner_roles "$CONTROL" | paste -sd '|' -)"
equals 'bms_classify_tenant_databases' "ok $TA $OWNER|ok $TB $OWNER2" "$(bms_classify_tenant_databases "$CONTROL" | paste -sd '|' -)"
equals 'bms_tenant_databases' "$TA $OWNER|$TB $OWNER2" "$(bms_tenant_databases "$CONTROL" | paste -sd '|' -)"
psu "$CONTROL" -c "INSERT INTO companies VALUES ('tg', 'bms_${FX}_gone'), ('tf', '$FOREIGN'), ('tx', 'postgres'), ('tc', '$CONTROL')"
equals 'bms_classify_tenant_databases: inexistente, ajena, postgres y la central' "missing bms_${FX}_gone|ok $TA $OWNER|ok $TB $OWNER2|reject $CONTROL no es un nombre de empresa (bms_<codigo>) o es la base central|reject $FOREIGN pertenece a postgres, que no es el rol de un server owner|reject postgres no es un nombre de empresa (bms_<codigo>) o es la base central" "$(bms_classify_tenant_databases "$CONTROL" | LC_ALL=C sort | paste -sd '|' - | sed 's/|ok /|ok /')" 
psu "$CONTROL" -c "DELETE FROM companies WHERE code IN ('tg', 'tf', 'tx', 'tc')"
equals 'bms_provisioner_assess: un rol recien creado, sin nada' 'adoptable clean' "$(bms_provisioner_assess "$RUNTIME" "$PROVISIONER" "$(bms_server_owner_roles "$CONTROL")")"
equals 'bms_provisioner_assess: rol inexistente' absent "$(bms_provisioner_assess "$RUNTIME" "${FX}_nadie" "$(bms_server_owner_roles "$CONTROL")")"
equals 'bms_layout_status' "$TA $OWNER 9 0 0|$TB $OWNER2 0 7 0" "$(bms_layout_status "$RUNTIME" "$PROVISIONER" "$(bms_tenant_databases "$CONTROL")" | paste -sd '|' -)"

# bms_role_set_password: the existence check and the SQL it sends.
printf '%s' "$FX_PASSWORD" | bms_role_set_password "${FX}_nuevo" 'NOSUPERUSER' || fail 'bms_role_set_password (crear)'
printf '%s' "$FX_PASSWORD" | bms_role_set_password "${FX}_nuevo" 'NOSUPERUSER' || fail 'bms_role_set_password (cambiar)'
equals 'bms_role_set_password: un rol y LOGIN' '1 t' "$(sql postgres "SELECT count(*), bool_and(rolcanlogin) FROM pg_roles WHERE rolname = '${FX}_nuevo'" | tr '|' ' ')"
psu postgres -c "DROP ROLE ${FX}_nuevo"

# bms_check_role_layout and bms_verify_role_access: silent when fine, the reason when not.
bms_migrate_role_layout "$CONTROL" "$RUNTIME" "$PROVISIONER" 2>/dev/null || fail 'la organizacion de roles'
equals 'bms_check_role_layout: en silencio cuando esta bien' '' "$(bms_check_role_layout "$CONTROL" "$RUNTIME" "$PROVISIONER" 2>&1)"
equals 'bms_verify_role_access: en silencio cuando esta bien' '' "$(bms_verify_role_access "$CONTROL" "$RUNTIME" "$PROVISIONER" "$(bms_tenant_databases "$CONTROL")" 2>&1)"
psu postgres -c "GRANT $OWNER TO $RUNTIME"
equals 'bms_check_role_layout: dice el problema exacto' "ERROR: organizacion de roles incompleta: el rol $RUNTIME es miembro de $OWNER, que crea bases o roles o es dueno de una base" "$(bms_check_role_layout "$CONTROL" "$RUNTIME" "$PROVISIONER" 2>&1)"
psu postgres -c "REVOKE $OWNER FROM $RUNTIME"
# PostgreSQL 16+: inheritance belongs to each grant, not to the role's rolinherit.
psu postgres -c "GRANT $OWNER TO $PROVISIONER WITH INHERIT TRUE, SET TRUE"
equals 'bms_check_role_layout: un GRANT con INHERIT al aprovisionamiento se detecta (PostgreSQL 16+)' "ERROR: organizacion de roles incompleta: el rol $PROVISIONER hereda privilegios (INHERIT)" "$(bms_check_role_layout "$CONTROL" "$RUNTIME" "$PROVISIONER" 2>&1)"
psu postgres -c "GRANT $OWNER TO $PROVISIONER WITH INHERIT FALSE, SET TRUE, ADMIN TRUE"
equals 'y uno con ADMIN OPTION' "ERROR: organizacion de roles incompleta: el rol $PROVISIONER hereda privilegios (INHERIT)" "$(bms_check_role_layout "$CONTROL" "$RUNTIME" "$PROVISIONER" 2>&1)"
psu postgres -c "GRANT $OWNER TO $PROVISIONER WITH INHERIT FALSE, SET TRUE, ADMIN FALSE"
equals 'con SET sin INHERIT ni ADMIN vuelve a estar bien' '' "$(bms_check_role_layout "$CONTROL" "$RUNTIME" "$PROVISIONER" 2>&1)"
# N1: a NOTICE or WARNING on stderr (any transport, the provisioner's connection) is not part of the answer.
real_transport=$(declare -f bms_pg_transport)
real_provisioner=$(declare -f bms_pg_as_provisioner)
bms_pg_transport() { printf 'WARNING:  ruido de prueba\n' >&2; local database=$1; shift; PGOPTIONS='-c client_min_messages=warning' psql -h "$PG_HOST" -p "$PG_PORT" -U postgres -d "$database" "$@"; }
bms_pg_as_provisioner() { printf 'NOTICE:  ruido de prueba\n' >&2; local database=$1; shift; PGPASSWORD=$FX_PASSWORD psql -h "$PG_HOST" -p "$PG_PORT" -U "$PROVISIONER" -d "$database" "$@"; }
check 'bms_verify_role_access: el ruido por stderr no lo hace fallar' bms_verify_role_access "$CONTROL" "$RUNTIME" "$PROVISIONER" "$(bms_tenant_databases "$CONTROL" 2>/dev/null)"
equals 'y no imprime nada' '' "$(bms_verify_role_access "$CONTROL" "$RUNTIME" "$PROVISIONER" "$(bms_tenant_databases "$CONTROL" 2>/dev/null)" 2>&1 | grep -v 'ruido de prueba')"
psu postgres -c "REVOKE CONNECT ON DATABASE $TB FROM $RUNTIME"
eval "$real_transport"
eval "$real_provisioner"
psu postgres -c "GRANT CONNECT ON DATABASE $TB TO $RUNTIME"
psu postgres -c "REVOKE CONNECT ON DATABASE $TB FROM $RUNTIME"
equals 'bms_verify_role_access: nombra la base sin acceso' "ERROR: el rol $RUNTIME no puede leer $TB: ERROR: se detiene" "$(bms_verify_role_access "$CONTROL" "$RUNTIME" "$PROVISIONER" "$(bms_tenant_databases "$CONTROL")" 2>&1 | sed -E 's/(no puede leer [a-z0-9_]+: ).*/\1ERROR: se detiene/' | head -n 1)"
psu postgres -c "GRANT CONNECT ON DATABASE $TB TO $RUNTIME"

# backup.sh: databases and size.
# shellcheck source=../backup.sh
source "$SCRIPTS/backup.sh"
# shellcheck disable=SC2329 # called by the backup functions
runuser() { [[ $1 == -u && $3 == -- ]] || return 1; shift 3; PGHOST=$PG_HOST PGPORT=$PG_PORT PGUSER=postgres "$@"; }
backup_list=$(bms_backup_databases "$CONTROL")
check 'bms_backup_databases: la central primero y cada base bms_ una vez, sin etiquetas' bash -c '[[ $(head -n 1 <<< "$1") == "$2" ]] && ! grep -qvE "^[a-z0-9_]+$" <<< "$1" && grep -qx "$3" <<< "$1" && grep -qx "$4" <<< "$1"' _ "$backup_list" "$CONTROL" "$TA" "$TB"
check 'bms_backup_size: un numero' bash -c '[[ $1 =~ ^[0-9]+$ ]]' _ "$(bms_backup_size "$CONTROL")"
unset -f runuser

# --- Defense in depth: even if something prints a stray line before the data (a transport that echoes a tag), no value
# becomes a role or a database name unchecked, and the message names it.
stray_transport() { echo SET; psql -h "$PG_HOST" -p "$PG_PORT" -U postgres -d "$1" "${@:2}"; }
real_transport=$(declare -f bms_pg_transport)
bms_pg_transport() { stray_transport "$@"; }
err=$(bms_server_owner_roles "$CONTROL" 2>&1 >/dev/null)
check 'un SET suelto: bms_server_owner_roles falla' eval '! bms_server_owner_roles "$CONTROL" >/dev/null 2>&1'
check 'y el mensaje nombra el valor' grep -q 'SET' <<< "$err"
check 'bms_classify_tenant_databases: falla' eval '! bms_classify_tenant_databases "$CONTROL" >/dev/null 2>&1'
check 'bms_migrate_role_layout: falla sin tocar nada' eval '! bms_migrate_role_layout "$CONTROL" "$RUNTIME" "$PROVISIONER" >/dev/null 2>&1'
check 'bms_pg_count: falla' eval '! bms_pg_count postgres -c "SELECT 1" >/dev/null 2>&1'
check 'bms_layout_status: falla' eval '! bms_layout_status "$RUNTIME" "$PROVISIONER" "$TA $OWNER" >/dev/null 2>&1'
check 'bms_provisioner_assess: falla' eval '! bms_provisioner_assess "$RUNTIME" "$PROVISIONER" "$OWNER" >/dev/null 2>&1'
eval "$real_transport"
# A role or database name that is not valid never reaches SQL.
rejects_role() { ! bms_restore_runtime_membership "$RUNTIME" "$1" 2>/dev/null; }
for bad in 'SET' 'Bms_Mayuscula' 'x;DROP' "a'b" 'pg_monitor' 'postgres' 'ñandu' ''; do
    check "bms_restore_runtime_membership rechaza el rol $(printf '%q' "$bad")" rejects_role "$bad"
done
check 'bms_psql_set rechaza una comilla' eval '! bms_psql_set x "a'"'"'b" 2>/dev/null'
check 'bms_psql_set rechaza un salto de linea' eval '! bms_psql_set x "$(printf "a\nb")" 2>/dev/null'
finish
