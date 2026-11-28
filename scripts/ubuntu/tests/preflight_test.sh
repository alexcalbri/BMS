#!/usr/bin/env bash
# shellcheck disable=SC2016 # eval bodies expand later on purpose
# preflight.sh against a server as older versions left it: the database checks report what update.sh needs (a
# company database owned by another role FAILs), secrets are never printed, and nothing changes.
set -uo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"
pg_available || { printf '  (omitido: no hay PostgreSQL en %s)\n' "$PG"; exit 0; }

CONTROL=bms_seca_pctl
GOOD=bms_seca_pgood
FOREIGN=bms_seca_pforeign
OWNER=seca_p_owner
RUNTIME=seca_p_rt
WORK=$(mktemp -d)
cleanup() {
    local database role
    for database in $CONTROL $GOOD $FOREIGN; do
        psu postgres -c "DROP DATABASE IF EXISTS $database WITH (FORCE)" >/dev/null 2>&1
    done
    for role in $RUNTIME $OWNER; do
        if [[ $(sql postgres "SELECT count(*) FROM pg_roles WHERE rolname = '$role'") == 1 ]]; then
            psu postgres -c "DROP OWNED BY $role CASCADE" >/dev/null 2>&1
            psu postgres -c "DROP ROLE $role" >/dev/null 2>&1
        fi
    done
    rm -rf -- "$WORK"
}
cleanup
WORK=$(mktemp -d)
trap cleanup EXIT

psu postgres <<SQL >/dev/null || die 'preparacion'
CREATE ROLE $OWNER LOGIN NOSUPERUSER CREATEDB CREATEROLE PASSWORD 'owner-pass';
CREATE ROLE $RUNTIME LOGIN PASSWORD 'runtime-pass';
GRANT $OWNER TO $RUNTIME;
CREATE DATABASE $CONTROL;
CREATE DATABASE $GOOD OWNER $OWNER;
CREATE DATABASE $FOREIGN;
GRANT CONNECT ON DATABASE $GOOD TO $RUNTIME;
SQL
{
    for file in V001__create_application_users V002__create_application_sessions V003__add_application_user_profile V004__add_application_user_code V006__add_application_user_language; do
        strip_tx "$REPO/database/core/migrations/$file.sql"
    done
    for file in "$REPO"/database/control/migrations/V*.sql; do strip_tx "$file"; done
    cat <<SQL
INSERT INTO application_users (id, postgres_role, is_active, display_name, email) VALUES ('22222222-2222-2222-2222-222222222222', '$OWNER', TRUE, 'Duena', 'duena@example.com');
INSERT INTO server_owners (user_id) VALUES ('22222222-2222-2222-2222-222222222222');
INSERT INTO companies (id, code, name, database_name, owner_group) VALUES
    (gen_random_uuid(), 'seca_pgood', 'Buena', '$GOOD', gen_random_uuid()),
    (gen_random_uuid(), 'seca_pforeign', 'Ajena', '$FOREIGN', gen_random_uuid());
INSERT INTO core_update_requests (target_version, from_version, requested_by) VALUES ('9.9.9', '0.1.0', 'duena');
SQL
} | psu "$CONTROL" >/dev/null || die 'base central'
sql "$GOOD" "CREATE TABLE public.items (id int)" >/dev/null

cat > "$WORK/server.env" <<ENV
DB_URL=jdbc:postgresql://127.0.0.1:5432/$CONTROL
DB_USER=$RUNTIME
DB_PASSWORD="secreto-que-no-se-imprime"
BMS_MASTER_KEY=llave-que-no-se-imprime
TENANT_JDBC_URL_PREFIX=jdbc:postgresql://127.0.0.1:5432/
ENV

snapshot() {
    sql postgres "SELECT string_agg(r.rolname || ':' || pg_get_userbyid(m.roleid), ',' ORDER BY 1) FROM pg_auth_members m JOIN pg_roles r ON r.oid = m.member WHERE r.rolname LIKE 'seca\\_p\\_%'"
    sql "$GOOD" "SELECT string_agg(relname || ':' || pg_get_userbyid(relowner) || ':' || COALESCE(relacl::text, ''), ',' ORDER BY 1) FROM pg_class WHERE relnamespace = 'public'::regnamespace"
    sql "$CONTROL" "SELECT count(*) || ':' || string_agg(status, ',') FROM core_update_requests"
}
# A stand-in for update.sh --rehearse (the real one needs root, the mirror and the real server): it records its arguments.
cat > "$WORK/update-stub.sh" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$*" > "$STUB_ARGS"
case ${STUB_MODE:-ok} in
    ok) printf 'PASS ensayo control bms_x — ejecutado y revertido (ROLLBACK)\nINFO   + rol x creado\nPASS ensayo sin cambios — iguales\n\n0 FAIL, 0 WARN\n' ;;
    warn) printf 'WARN ensayo tenant bms_a — no ensayado por bloqueo: 3 intentos\nPASS ensayo control bms_x — ok\n\n0 FAIL, 1 WARN\n'; exit 0 ;;
    fail) printf 'FAIL ensayo tenant bms_a — fallo en migraciones: relation "t" does not exist\n        DETAIL: x\nPASS ensayo control bms_x — ok\n\n1 FAIL, 0 WARN\n'; exit 1 ;;
    crash) printf 'ERROR: no se pudo sincronizar el espejo\n' >&2; exit 3 ;;
esac
STUB
export STUB_ARGS=$WORK/stub-args
before=$(snapshot)
output=$(BMS_PREFLIGHT_UPDATE_SH=$WORK/update-stub.sh BMS_PREFLIGHT_PG=$PG BMS_PREFLIGHT_CONFIG=$WORK/server.env BMS_PREFLIGHT_SKIP_HOST=true bash "$SCRIPTS/preflight.sh" 2>&1)
status=$?
equals 'sale con error cuando algo falla' 1 "$status"
check 'la empresa correcta pasa' grep -q "^PASS empresa $GOOD " <<< "$output"
check 'la base de otro dueno falla' grep -q "^FAIL empresa $FOREIGN .*pertenece a postgres" <<< "$output"
check 'la pertenencia actual del runtime se informa' grep -q "^INFO rol $RUNTIME .*miembro de $OWNER" <<< "$output"
check 'el server owner es sano' grep -q "^PASS server owner $OWNER" <<< "$output"
check 'la solicitud pendiente falla' grep -q '^FAIL actualizaciones pedidas desde la app' <<< "$output"
check 'pg_hba con trust falla' grep -q '^FAIL pg_hba 127.0.0.1 .*trust' <<< "$output"
check 'los duenos de public se informan' grep -q "^INFO empresa $GOOD duenos en public .*postgres:1" <<< "$output"
check 'CREATE del dueno en public' grep -q "^PASS empresa $GOOD CREATE en public" <<< "$output"
check 'ningun secreto aparece' eval '! grep -q "no-se-imprime" <<< "$output"'
equals 'no cambio nada' "$before" "$(snapshot)"
# The rehearsal block: update.sh --rehearse --ref <ref>, its lines relayed, its FAIL and WARN counted.
equals 'el ensayo se llama con --rehearse --ref y la referencia por defecto' '--rehearse --ref master' "$(cat "$STUB_ARGS")"
check 'las lineas del ensayo se muestran' grep -q '^PASS ensayo control bms_x — ejecutado y revertido' <<< "$output"
check 'y el detalle' grep -q '^INFO   + rol x creado' <<< "$output"
check 'y el resultado del ensayo' grep -q '^PASS ensayo de update.sh' <<< "$output"
run_preflight() { env BMS_PREFLIGHT_UPDATE_SH="$WORK/update-stub.sh" BMS_PREFLIGHT_PG="$PG" BMS_PREFLIGHT_CONFIG="$WORK/server.env" BMS_PREFLIGHT_SKIP_HOST=true "$@" bash "$SCRIPTS/preflight.sh" 2>&1; }
base_fails=$(grep -c '^FAIL' <<< "$output")
out=$(run_preflight STUB_MODE=fail)
equals 'un FAIL del ensayo cuenta como FAIL de preflight' "$((base_fails + 1))" "$(grep -c '^FAIL' <<< "$out")"
check 'y se cuenta en el resumen' grep -q "^$((base_fails + 1)) FAIL" <<< "$out"
check 'con su detalle' grep -q '^        DETAIL: x' <<< "$out"
out=$(run_preflight STUB_MODE=warn)
check 'un WARN del ensayo se muestra' grep -q '^WARN ensayo tenant bms_a — no ensayado por bloqueo' <<< "$out"
check 'y el ensayo termina bien' grep -q '^PASS ensayo de update.sh' <<< "$out"
out=$(run_preflight STUB_MODE=crash)
check 'si update.sh --rehearse no llega a ensayar, FAIL' grep -q '^FAIL ensayo de update.sh — termino con codigo 3 sin ejecutar el ensayo: ERROR: no se pudo sincronizar' <<< "$out"
out=$(run_preflight BMS_PREFLIGHT_REF=v1.2.3)
equals 'BMS_PREFLIGHT_REF cambia la referencia' '--rehearse --ref v1.2.3' "$(cat "$STUB_ARGS")"
out=$(run_preflight BMS_PREFLIGHT_NO_REHEARSE=true)
check 'sin ensayo (BMS_PREFLIGHT_NO_REHEARSE) hay un WARN que lo dice' grep -q '^WARN ensayo de update.sh — omitido' <<< "$out"
out=$(run_preflight BMS_PREFLIGHT_UPDATE_SH="$WORK/no-existe.sh")
check 'sin update.sh, FAIL' grep -q '^FAIL ensayo de update.sh — falta' <<< "$out"
equals 'tras todo, nada cambio' "$before" "$(snapshot)"
printf '%s\n' "$output" | sed 's/^/    | /'
finish
