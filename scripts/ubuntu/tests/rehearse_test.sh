#!/usr/bin/env bash
# shellcheck disable=SC2016 # SQL and eval bodies expand later on purpose
# update.sh --rehearse (rehearse.sh) against a server as the previous release left it, from the real migrations: the
# role move, the grant blocks and the pending control and company migrations run inside transactions that are rolled
# back (update.sh's own functions, read from the file, with the real psql), and the cluster is the same afterwards:
# a before/after snapshot of roles, memberships, databases, owners and ACLs. It also has to FAIL where the real run
# would (the pre-fix function; a migration that does not run on this PostgreSQL), WARN instead of waiting for a lock,
# and FAIL loudly when something does change.
set -uo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"
pg_available || { printf '  (omitido: no hay PostgreSQL en %s)\n' "$PG"; exit 0; }
[[ $EUID -eq 0 ]] || { printf '  (omitido: update.sh pide root)\n'; exit 0; }
# shellcheck source=../db-roles.sh
source "$SCRIPTS/db-roles.sh"
# shellcheck source=../migrations.sh
source "$SCRIPTS/migrations.sh"
# shellcheck source=../rehearse.sh
source "$SCRIPTS/rehearse.sh"
# shellcheck source=fixture.sh
source "$(dirname -- "${BASH_SOURCE[0]}")/fixture.sh"
fx_names seca_h
WORK=$(mktemp -d)
cleanup() { fx_cleanup; rm -rf -- "$WORK"; }
cleanup
WORK=$(mktemp -d)
trap cleanup EXIT

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
warn() { printf 'AVISO: %s\n' "$*" >&2; }
sed -n '/^# BEGIN validators/,/^# END validators/p' "$SCRIPTS/update.sh" > "$WORK/validators.sh"
sed -n '/^# BEGIN role-layout/,/^# END role-layout/p' "$SCRIPTS/update.sh" > "$WORK/layout.sh"
sed -n '/^config_has_key() {/,/^}/p;/^config_value() {/,/^}/p' "$SCRIPTS/update.sh" > "$WORK/config.sh"
# shellcheck source=/dev/null
source "$WORK/validators.sh"
# shellcheck source=/dev/null
source "$WORK/layout.sh"
# shellcheck source=/dev/null
source "$WORK/config.sh"
CONFIG_FILE=$WORK/server.env
# The superuser path of migrations.sh is runuser -u postgres psql: here, TCP to the test server.
# shellcheck disable=SC2329 # called by migration_psql
runuser() { [[ $1 == -u && $3 == -- ]] || return 1; shift 3; PGHOST=$PG_HOST PGPORT=$PG_PORT PGUSER=postgres PGOPTIONS='-c client_min_messages=warning' "$@"; }
# shellcheck disable=SC2329 # called by apply_control_migrations
require_release_files() { :; }
# shellcheck disable=SC2034 # read by update.sh's functions
TRUSTED_DIR=$REPO

setup() {
    fx_cleanup
    fx_release
    # shellcheck disable=SC2034 # read by update.sh's functions
    DB_USER=$RUNTIME CONTROL_DB=$CONTROL DEFAULT_PROVISIONER=$PROVISIONER
    printf 'DB_USER=%s\nDB_URL=jdbc:postgresql://127.0.0.1:5432/%s\n' "$RUNTIME" "$CONTROL" > "$CONFIG_FILE"
}
rehearse() { bms_rehearse_run "$CONTROL" "$RUNTIME" "$PROVISIONER" 2>&1; }
# The same with environment settings for this one run (a plain VAR=x before an assignment would stay set).
# shellcheck disable=SC2163 # the argument is the NAME=value to export
rehearse_with() { ( for assignment in "$@"; do export "$assignment"; done; rehearse ); }
section() { awk -v start="$2" '$0 ~ /^(PASS|WARN|FAIL|INFO) +[a-z]/ { inside = index($0, start) > 0 } inside' <<< "$1"; }

# --- 1. The live server's state: the provisioning role left by the failed attempts (LOGIN NOINHERIT, nothing else).
setup
psu postgres -c "CREATE ROLE $PROVISIONER LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE"
equals 'antes: los objetos de TA son de postgres' 'postgres' "$(sql "$TA" "SELECT pg_get_userbyid(relowner) FROM pg_class WHERE oid = 'public.business_owner'::regclass")"
LAYOUT_OWNER_ROLES=$(bms_server_owner_roles "$CONTROL")
output=$(rehearse_with BMS_REHEARSE_DETAIL=500 BMS_REHEARSE_ALL_NEW=1)
status=$?
printf '%s\n' "$output" | sed 's/^/    | /'
equals 'el ensayo termina bien' 0 "$status"
check 'ninguna linea FAIL' eval '! grep -q "^FAIL" <<< "$output"'
check 'ensayo de la base central' grep -q "^PASS ensayo control $CONTROL — ejecutado y revertido" <<< "$output"
check 'ensayo de TA' grep -q "^PASS ensayo tenant $TA — ejecutado y revertido" <<< "$output"
check 'ensayo de TB' grep -q "^PASS ensayo tenant $TB — ejecutado y revertido" <<< "$output"
check 'cero cambios, comprobado con las instantaneas' grep -q '^PASS ensayo sin cambios' <<< "$output"
check 'el rol sobrante se adopta' grep -q "^INFO rol $PROVISIONER — existe, sin pertenencias ni privilegios" <<< "$output"
check 'dice que objetos cambian de dueno en TA' grep -q "~ objeto public.business_owner: r postgres " <<< "$output"
check 'dice que pertenencias se quitan' grep -q "^INFO   - pertenencia $RUNTIME>$OWNER:" <<< "$output"
check 'dice que pertenencias se dan' grep -q "^INFO   + pertenencia $PROVISIONER>$OWNER:" <<< "$output"
check 'dice las tablas nuevas que crean las migraciones' grep -q '^INFO   + objeto public.activity_log:' <<< "$output"
check 'y cuenta lo que crean las migraciones' grep -q 'objetos, columnas o funciones creados por las migraciones' <<< "$output"
check 'los roles y pertenencias se dicen una vez, con la primera base' bash -c '[[ $(grep -c "^INFO   + pertenencia .*>$2:" <<< "$1") == 1 ]]' _ "$output" "$OWNER"
check 'dice los permisos que se dan al runtime' grep -q "$RUNTIME=" <<< "$output"
# The in-app assistant (tenant V018-V020, GLOBAL_AGENT.md): its migrations are part of what is replayed and its grants
# come out of the same grant block as the real update, inside the rolled back transaction.
for db in "$TA" "$TB"; do
    steps=$(bms_rehearse_steps_sql tenant "$db")
    check "$db: el ensayo repite V018 (tablas del asistente)" grep -q 'CREATE TABLE.*assistant_messages' <<< "$steps"
    check "$db: el ensayo repite V019 (lease_token)" grep -q 'lease_token' <<< "$steps"
    check "$db: el ensayo repite V020 (preview_hash)" grep -q 'preview_hash' <<< "$steps"
done
check 'asistente: assistant_messages termina con solo SELECT e INSERT (ar) para el runtime, en las dos empresas' bash -c '[[ $(grep -cE "objeto public\.assistant_messages: r .*\{[^}]*,$2=ar/[^,}]*\}" <<< "$1") == 2 ]]' _ "$output" "$RUNTIME"
check 'asistente: su secuencia solo se usa y se lee' grep -qE "objeto public\.assistant_messages_id_seq: S .*,$RUNTIME=rU/" <<< "$output"
check 'asistente: el resto de sus tablas son normales (arwd)' bash -c '[[ $(grep -cE "objeto public\.assistant_(conversations|pending_calls|settings|usage): r .*,$2=arwd/" <<< "$1") == 8 ]]' _ "$output" "$RUNTIME"
equals 'despues: nada cambio (TA sigue de postgres)' 'postgres' "$(sql "$TA" "SELECT pg_get_userbyid(relowner) FROM pg_class WHERE oid = 'public.business_owner'::regclass")"
equals 'despues: el runtime sigue siendo miembro' 't t' "$(sql postgres "SELECT pg_has_role('$RUNTIME', '$OWNER', 'MEMBER'), pg_has_role('$RUNTIME', '$OWNER2', 'MEMBER')" | tr '|' ' ')"
equals 'despues: el rol de aprovisionamiento sigue sin pertenencias' 0 "$(sql postgres "SELECT count(*) FROM pg_auth_members WHERE member = '$PROVISIONER'::regrole")"
equals 'despues: las migraciones no quedaron aplicadas (sin tabla del registro de actividad en TA)' '' "$(sql "$TA" "SELECT to_regclass('public.activity_log')")"
equals 'despues: la base central sin V015 (server_admins)' '' "$(sql "$CONTROL" "SELECT to_regclass('public.server_admins')")"
ta_predicted=$(section "$output" "ensayo tenant $TA" | grep -c '^INFO   ~ objeto .*: [a-zA-Z] postgres ' || true)
ta_before=$(sql "$TA" "SELECT count(*) FROM pg_class WHERE relnamespace = 'public'::regnamespace AND relowner = 'postgres'::regrole")
equals 'TA: los objetos que el ensayo dice que cambian de dueno son los que de verdad pasaran' "$ta_before" "$ta_predicted"

# --- 2. The real run (update.sh's functions, the real psql), and what the rehearsal said.
# shellcheck disable=SC2034 # read by migration_psql
MIGRATION_LOGIN=$PROVISIONER MIGRATION_PASSFILE=$WORK/pgpass MIGRATION_HOST=$PG_HOST MIGRATION_PORT=$PG_PORT
printf '%s:%s:*:%s:%s\n' "$PG_HOST" "$PG_PORT" "$PROVISIONER" "$FX_PASSWORD" > "$WORK/pgpass"
chmod 600 "$WORK/pgpass"
printf '%s' "$FX_PASSWORD" | bms_role_set_password "$PROVISIONER" "$BMS_PROVISIONER_ATTRIBUTES" || fail 'adopcion'
bms_migrate_role_layout "$CONTROL" "$RUNTIME" "$PROVISIONER" "$LAYOUT_OWNER_ROLES" 2> "$WORK/real.err"
status=$?
[[ $status == 0 ]] || sed 's/^/    | /' "$WORK/real.err" >&2
equals 'la organizacion de roles real termina bien' 0 "$status"
( apply_control_migrations ) > "$WORK/control.out" 2>&1
equals 'las migraciones y permisos de la base central (los mismos que se ensayaron) terminan bien' 0 "$?"
( apply_tenant_migrations ) > "$WORK/tenant.out" 2>&1
equals 'las migraciones y permisos de las empresas (los mismos que se ensayaron) terminan bien' 0 "$?"
equals 'TA: ahora si hay registro de actividad' 'activity_log' "$(sql "$TA" "SELECT to_regclass('public.activity_log')")"
equals 'TA: V019 y V020 aplicadas de verdad (lease_token y preview_hash)' '1 1' "$(sql "$TA" "SELECT (SELECT count(*) FROM information_schema.columns WHERE table_name = 'assistant_conversations' AND column_name = 'lease_token'), (SELECT count(*) FROM information_schema.columns WHERE table_name = 'assistant_pending_calls' AND column_name = 'preview_hash')" | tr '|' ' ')"
equals 'TA: el runtime solo lee y agrega mensajes del asistente (lo mismo que dijo el ensayo)' 't f f' "$(sql "$TA" "SELECT has_table_privilege('$RUNTIME', 'assistant_messages', 'SELECT') AND has_table_privilege('$RUNTIME', 'assistant_messages', 'INSERT'), has_table_privilege('$RUNTIME', 'assistant_messages', 'UPDATE'), has_table_privilege('$RUNTIME', 'assistant_messages', 'DELETE')" | tr '|' ' ')"
equals 'TA: todo del dueno' 0 "$(sql "$TA" "SELECT count(*) FROM pg_class WHERE relnamespace = 'public'::regnamespace AND relowner <> '$OWNER'::regrole")"
output=$(rehearse)
status=$?
equals 'un ensayo despues del cambio real: termina bien' 0 "$status"
check 'y no queda nada por cambiar en ninguna base' bash -c '[[ $(grep -c "0 nuevos (0 objetos, columnas o funciones creados por las migraciones), 0 quitados, 0 cambiados" <<< "$1") == 3 ]]' _ "$output"
check 'sin cambios otra vez' grep -q '^PASS ensayo sin cambios' <<< "$output"

# --- 3. The very bug: the pre-fix bms_server_owner_roles (SET search_path, then SELECT, no -q).
setup
psu postgres -c "CREATE ROLE $PROVISIONER LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE"
real_function=$(declare -f bms_server_owner_roles)
bms_server_owner_roles() {
    local control_db=$1
    bms_pg_transport "$control_db" -X -At -v ON_ERROR_STOP=1 <<'SQL'
SET search_path = pg_catalog, pg_temp;
SELECT DISTINCT r.rolname FROM public.server_owners o
JOIN public.application_users u ON u.id = o.user_id
JOIN pg_catalog.pg_roles r ON r.rolname = u.postgres_role
WHERE NOT r.rolsuper AND r.rolname !~ '^pg_' ORDER BY 1;
SQL
}
output=$(rehearse)
status=$?
equals 'con la funcion de antes el ensayo FALLA' 1 "$status"
check 'y nombra el valor que no es un rol' grep -q '^FAIL roles de server owner' <<< "$output"
check 'con "SET" en el mensaje' grep -q 'SET' <<< "$output"
printf '%s\n' "$output" | sed 's/^/    | /'
eval "$real_function"
# The old move itself, against the same state, fails as the server did.
old_grant_all() { local role=$1 member=$2; printf 'GRANT :"role" TO :"member";\n' | bms_pg_transport postgres -X -v ON_ERROR_STOP=1 -v role="$role" -v member="$member" 2>&1; }
check 'GRANT con ese valor da el error del servidor' grep -q 'role "SET" does not exist' <<< "$(old_grant_all SET "$RUNTIME")"

# --- 4. A migration that does not run: SQL errors surface in the rehearsal, as the real update would hit them.
setup
tree=$WORK/tree
mkdir -p "$tree"
cp -a "$REPO/database" "$REPO/modules" "$tree/" 2>/dev/null
printf 'BEGIN;\nALTER TABLE tabla_que_no_existe ADD COLUMN x int;\nCOMMIT;\n' > "$tree/database/tenant/migrations/V099__rota.sql"
# shellcheck disable=SC2034 # read by update.sh's functions
TRUSTED_DIR=$tree
LAYOUT_OWNER_ROLES=$(bms_server_owner_roles "$CONTROL")
output=$( set -Eeuo pipefail; rehearse )
status=$?
equals 'migracion rota (bajo set -Eeuo pipefail, como en update.sh): el ensayo FALLA' 1 "$status"
check 'en TA y en TB' bash -c '[[ $(grep -c "^FAIL ensayo tenant .* fallo en .migraciones y permisos que se aplicarian.: .*tabla_que_no_existe" <<< "$1") == 2 ]]' _ "$output"
check 'la base central pasa' grep -q "^PASS ensayo control $CONTROL" <<< "$output"
check 'y aun asi no queda ningun cambio' grep -q '^PASS ensayo sin cambios' <<< "$output"
printf '%s\n' "$output" | grep '^FAIL' | sed 's/^/    | /'
# shellcheck disable=SC2034
TRUSTED_DIR=$REPO

# A database that cannot be entered: FAIL with the reason, not a silent exit (set -e and pipefail as in update.sh).
setup
psu postgres -c "ALTER DATABASE $TB WITH ALLOW_CONNECTIONS false"
LAYOUT_OWNER_ROLES=$(bms_server_owner_roles "$CONTROL")
output=$( set -Eeuo pipefail; rehearse )
status=$?
psu postgres -c "ALTER DATABASE $TB WITH ALLOW_CONNECTIONS true"
equals 'sin poder entrar a una base: el ensayo FALLA' 1 "$status"
check 'con el motivo' grep -q "^FAIL ensayo tenant $TB" <<< "$output"

# --- 5. Locks: the service is using a table the move has to change owner; the rehearsal does not wait.
setup
LAYOUT_OWNER_ROLES=$(bms_server_owner_roles "$CONTROL")
( PGOPTIONS='-c client_min_messages=warning' psql -X -q -h "$PG_HOST" -p "$PG_PORT" -U postgres -d "$TA" -c 'BEGIN; SELECT count(*) FROM business_owner; SELECT pg_sleep(25);' >/dev/null 2>&1 ) &
holder=$!
sleep 1
started=$SECONDS
output=$(rehearse_with BMS_REHEARSE_LOCK_TIMEOUT=300ms BMS_REHEARSE_ATTEMPTS=2 BMS_REHEARSE_RETRY_SLEEP=0.2)
status=$?
elapsed=$((SECONDS - started))
kill "$holder" 2>/dev/null
wait "$holder" 2>/dev/null
check 'un bloqueo no se espera: aviso "no ensayado por bloqueo"' grep -q "^WARN ensayo tenant $TA — no ensayado por bloqueo" <<< "$output"
check 'las demas bases se ensayan' grep -q "^PASS ensayo tenant $TB " <<< "$output"
equals 'un ensayo con una base sin ensayar es INCOMPLETO: sale con 2, no con 0' 2 "$status"
check 'y lo dice (WARN ensayo incompleto, repetir en un momento tranquilo)' grep -q '^WARN ensayo incompleto .*momento tranquilo' <<< "$output"
check 'sin ninguna linea FAIL' eval '! grep -q "^FAIL" <<< "$output"'
check 'y tarda segundos, no el bloqueo entero' test "$elapsed" -lt 15
check 'y no deja cambios' grep -q '^PASS ensayo sin cambios' <<< "$output"
printf '%s\n' "$output" | grep -E '^WARN' | sed 's/^/    | /'

# --- 6. Something that does leave a change is caught, loudly.
setup
LAYOUT_OWNER_ROLES=$(bms_server_owner_roles "$CONTROL")
real_hook=$(declare -f bms_rehearse_steps_sql)
# A change made outside the transaction while the rehearsal runs (here by the hook itself): the snapshots must differ.
# shellcheck disable=SC2329 # called by rehearse.sh
bms_rehearse_steps_sql() {
    [[ $1 == control ]] || return 0
    psu postgres -c "CREATE ROLE ${FX}_escapado"
}
output=$(rehearse)
status=$?
equals 'un cambio fuera de la transaccion: el ensayo FALLA' 1 "$status"
check 'con el mensaje fuerte' grep -q '^FAIL ensayo sin cambios — EL ENSAYO DEJO CAMBIOS' <<< "$output"
check 'y la diferencia' grep -q "${FX}_escapado" <<< "$output"
psu postgres -c "DROP ROLE IF EXISTS ${FX}_escapado"
# Every way of ending the transaction early: refused before anything runs (the first five), or stopped by the tripwire (END;).
HOOK_SQL=''
# shellcheck disable=SC2329 # called by rehearse.sh
bms_rehearse_steps_sql() { printf '%s\n' "$HOOK_SQL"; }
bypass() {
    local description=$1 expected=$2
    HOOK_SQL=$3
    output=$(rehearse)
    check "$description: se rechaza o falla" grep -q '^FAIL ensayo' <<< "$output"
    check "$description: dice por que ($expected)" grep -q -- "$expected" <<< "$output"
    check "$description: y no queda ningun cambio" grep -q '^PASS ensayo sin cambios' <<< "$output"
    equals "$description: el runtime sigue siendo miembro (nada se confirmo)" t "$(sql postgres "SELECT pg_has_role('$RUNTIME', '$OWNER', 'MEMBER')")"
}
bypass 'COMMIT;' 'control de transaccion' 'COMMIT;'
bypass 'COMMIT; con sangria' 'control de transaccion' '    COMMIT;'
bypass 'COMMIT; BEGIN; en una linea' 'control de transaccion' 'COMMIT; BEGIN;'
bypass 'SELECT 1; COMMIT;' 'control de transaccion' 'SELECT 1; COMMIT;'
bypass '\c' 'orden de psql no permitida' '\c postgres'
bypass '\!' 'orden de psql no permitida' '\! true'
bypass '\copy' 'orden de psql no permitida' '\copy (select 1) to /dev/null'
bypass '\set AUTOCOMMIT on' 'orden de psql no permitida' '\set AUTOCOMMIT on'
bypass 'END; (no lo ve la guarda: lo para la trampa)' 'violates foreign key constraint' 'END;'
bypass 'ROLLBACK; (tampoco deja seguir)' 'control de transaccion' 'rollback;'

# Limits: a statement that takes longer than the limit is a FAIL (never retried, never a lock WARN), with the database.
HOOK_SQL='SELECT pg_sleep(3);'
started=$SECONDS
output=$(rehearse_with BMS_REHEARSE_STATEMENT_TIMEOUT=1s)
status=$?
check 'tiempo agotado (57014): FAIL con la base' grep -q "^FAIL ensayo control $CONTROL — tiempo agotado o cancelado (57014)" <<< "$output"
check 'no se reintenta ni sale como bloqueo' eval '! grep -q "no ensayado por bloqueo" <<< "$output"'
equals 'y el ensayo falla (1)' 1 "$status"
check 'sin esperar los intentos' test $((SECONDS - started)) -lt 14
HOOK_SQL=''
output=$(rehearse_with BMS_REHEARSE_STATEMENT_TIMEOUT=banana)
check 'un limite mal escrito se rechaza' grep -q 'tiempo limite del ensayo no valido' <<< "$output"
output=$(rehearse_with BMS_REHEARSE_LOCK_TIMEOUT=4s)
check 'los limites se pueden cambiar' grep -q '^PASS ensayo control' <<< "$output"

# Deferred constraints run at the end, as the real COMMIT would (the migration below violates one).
setup
tree=$WORK/tree-diferida
mkdir -p "$tree"
cp -a "$REPO/database" "$REPO/modules" "$tree/" 2>/dev/null
printf 'BEGIN;\nCREATE TABLE zz_p (id int PRIMARY KEY);\nCREATE TABLE zz_c (id int REFERENCES zz_p DEFERRABLE INITIALLY DEFERRED);\nINSERT INTO zz_c VALUES (1);\nCOMMIT;\n' > "$tree/database/tenant/migrations/V099__diferida.sql"
eval "$real_hook"
# shellcheck disable=SC2034
TRUSTED_DIR=$tree
LAYOUT_OWNER_ROLES=$(bms_server_owner_roles "$CONTROL")
output=$(rehearse)
status=$?
equals 'una restriccion diferida violada por una migracion: el ensayo FALLA' 1 "$status"
check 'en las empresas, en el paso de restricciones diferidas' bash -c '[[ $(grep -c "^FAIL ensayo tenant .* fallo en .restricciones diferidas.: .*violates foreign key constraint" <<< "$1") == 2 ]]' _ "$output"
check 'la base central pasa' grep -q "^PASS ensayo control $CONTROL" <<< "$output"
# shellcheck disable=SC2034
TRUSTED_DIR=$REPO

# Duration per database, and what was NOT rehearsed.
setup
LAYOUT_OWNER_ROLES=$(bms_server_owner_roles "$CONTROL")
output=$(rehearse)
check 'cada base dice cuanto duro (y que los bloqueos duraron eso como maximo)' bash -c '[[ $(grep -cE "^PASS ensayo (control|tenant) .* — ejecutado y revertido \(ROLLBACK\) en [0-9]+ ms" <<< "$1") == 3 ]]' _ "$output"
check 'y una linea final con lo que NO se ensayo' grep -q '^INFO NO ensayado — .*login real' <<< "$output"

# --- The provisioner's pg_hba rule and real TCP login (before the role move, which is committed for good).
hba_rows=$'host|all|all|127.0.0.1|255.255.255.255|scram-sha-256\nlocal|all|all|||peer'
equals 'pg_hba: scram para 127.0.0.1' scram-sha-256 "$(bms_rehearse_hba_match 127.0.0.1 p c localhost <<< "$hba_rows")"
equals 'pg_hba: ninguna regla para ::1' none "$(bms_rehearse_hba_match ::1 p c localhost <<< "$hba_rows")"
equals 'pg_hba: all/all con ::1/128' md5 "$(bms_rehearse_hba_match ::1 p c localhost <<< $'host|all|all|::1|ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff|md5')"
equals 'pg_hba: la primera regla gana (reject antes de scram)' reject "$(bms_rehearse_hba_match 127.0.0.1 p c localhost <<< $'host|all|p|127.0.0.1|255.255.255.255|reject\nhost|all|all|127.0.0.1|255.255.255.255|scram-sha-256')"
equals 'pg_hba: regla de otra base o de otro rol no cuenta' none "$(bms_rehearse_hba_match 127.0.0.1 p c localhost <<< $'host|otra|all|127.0.0.1|255.255.255.255|scram-sha-256\nhost|all|q|127.0.0.1|255.255.255.255|scram-sha-256')"
equals 'pg_hba: una red (10.0.0.0/8) no es 127.0.0.1' none "$(bms_rehearse_hba_match 127.0.0.1 p c localhost <<< $'host|all|all|10.0.0.0|255.0.0.0|scram-sha-256')"
equals 'pg_hba: all como direccion' scram-sha-256 "$(bms_rehearse_hba_match ::1 p c localhost <<< $'host|all|all|all||scram-sha-256')"
equals 'se resuelve una direccion literal a si misma' 127.0.0.1 "$(bms_rehearse_resolve 127.0.0.1)"
equals 'localhost resuelve a 127.0.0.1 o ::1 (en el orden de libpq)' 1 "$(bms_rehearse_resolve localhost | grep -cE '^(127\.0\.0\.1|::1)$' | awk '{ print ($1 >= 1) }')"
setup
LAYOUT_OWNER_ROLES=$(bms_server_owner_roles "$CONTROL")
output=$(bms_rehearse_run "$CONTROL" "$RUNTIME" "$PROVISIONER" 127.0.0.1 "$PG_PORT" 2>&1)
status=$?
check 'pg_hba: el cluster de pruebas usa trust, se avisa' grep -q "^WARN pg_hba de $PROVISIONER — 127.0.0.1 (127.0.0.1): regla trust" <<< "$output"
check 'login TCP real con un rol de prueba: PASS' grep -q '^PASS login TCP como el aprovisionador' <<< "$output"
equals 'y el rol de prueba ya no existe' 0 "$(sql postgres "SELECT count(*) FROM pg_roles WHERE rolname LIKE 'bms\_rh\_%'")"
check 'la instantanea sigue identica' grep -q '^PASS ensayo sin cambios' <<< "$output"
# A failed login: FAIL, the role is dropped, the transactions are not run.
output=$(export BMS_TEST_FAIL_AT=rehearse_throwaway_fail; bms_rehearse_run "$CONTROL" "$RUNTIME" "$PROVISIONER" 127.0.0.1 "$PG_PORT" 2>&1)
status=$?
equals 'login que falla: el ensayo FALLA' 1 "$status"
check 'con el motivo' grep -q '^FAIL login TCP como el aprovisionador' <<< "$output"
check 'y no ejecuta las transacciones' grep -q 'omitido: corrige lo anterior' <<< "$output"
equals 'el rol de prueba no queda' 0 "$(sql postgres "SELECT count(*) FROM pg_roles WHERE rolname LIKE 'bms\_rh\_%'")"
# Signals while the throwaway role exists: nothing is left behind.
runner=$WORK/runner.sh
cat > "$runner" <<RUNNER
source "$SCRIPTS/tests/lib.sh"
source "$SCRIPTS/db-roles.sh"
source "$SCRIPTS/migrations.sh"
source "$SCRIPTS/rehearse.sh"
BMS_TEST_FAIL_AT=rehearse_throwaway_wait bms_rehearse_run "$CONTROL" "$RUNTIME" "$PROVISIONER" 127.0.0.1 "$PG_PORT"
RUNNER
for signal in INT TERM; do
    setsid env --default-signal=INT,TERM bash "$runner" > "$WORK/signal.out" 2>&1 &
    runner_pid=$!
    for _ in $(seq 1 100); do
        [[ $(sql postgres "SELECT count(*) FROM pg_roles WHERE rolname LIKE 'bms\_rh\_%'") == 1 ]] && break
        sleep 0.2
    done
    equals "$signal: el rol de prueba existia mientras corria" 1 "$(sql postgres "SELECT count(*) FROM pg_roles WHERE rolname LIKE 'bms\_rh\_%'")"
    kill "-$signal" -- "-$runner_pid" 2>/dev/null
    wait "$runner_pid" 2>/dev/null
    for _ in $(seq 1 50); do
        [[ $(sql postgres "SELECT count(*) FROM pg_roles WHERE rolname LIKE 'bms\_rh\_%'") == 0 ]] && break
        sleep 0.2
    done
    equals "$signal: el rol de prueba ya no existe" 0 "$(sql postgres "SELECT count(*) FROM pg_roles WHERE rolname LIKE 'bms\_rh\_%'")"
done
equals 'tras las senales el cluster no cambio (sin roles de prueba)' 0 "$(sql postgres "SELECT count(*) FROM pg_roles WHERE rolname LIKE 'bms\_rh\_%'")"
eval "$real_hook"
finish
