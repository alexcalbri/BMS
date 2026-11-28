#!/usr/bin/env bash
# shellcheck disable=SC2016 # SQL and eval bodies expand later on purpose
# The real entry points, end to end, on the test PostgreSQL: `sudo bash update.sh --rehearse --ref master` and
# `sudo bash preflight.sh` (which runs it), exactly as the operator runs them on the server in phase 1: root-owned
# scripts in /root/bms-update, /etc/bms/server.env, the root mirror /var/lib/bms/mirror (here a clone of a copy of this
# working tree: a git shim answers `fetch origin` from it), runuser -u postgres psql (the test server through PGHOST and
# PGPORT). All of it inside a private mount namespace (unshare -m) with tmpfs over /etc/bms, /var/lib/bms and
# /root/bms-update, so the machine keeps nothing. The server it looks at is the one the live failures left: the
# provisioning role there without memberships, server.env without its keys, the runtime role a member of the owner.
set -uo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"
pg_available || { printf '  (omitido: no hay PostgreSQL en %s)\n' "$PG"; exit 0; }
[[ $EUID -eq 0 ]] || { printf '  (omitido: hace falta root)\n'; exit 0; }
for tool in unshare runuser flock git mount; do
    command -v "$tool" >/dev/null 2>&1 || { printf '  (omitido: falta %s)\n' "$tool"; exit 0; }
done
id postgres >/dev/null 2>&1 || { printf '  (omitido: no hay usuario postgres)\n'; exit 0; }

if [[ ${1:-} != --inner ]]; then
    # Directories this test may have to create on the real file system (empty, removed again).
    created=()
    for dir in /etc/bms /var/lib/bms /root/bms-update; do
        [[ -d $dir ]] || { mkdir -p "$dir" && created+=("$dir"); }
    done
    lock_existed=false
    [[ -e /run/bms-maintenance.lock ]] && lock_existed=true
    unshare -m bash "${BASH_SOURCE[0]}" --inner
    status=$?
    for dir in "${created[@]}"; do rmdir "$dir" 2>/dev/null; done
    [[ $lock_existed == true ]] || rm -f /run/bms-maintenance.lock
    exit "$status"
fi

# --- Inside the private mount namespace.
# shellcheck source=../db-roles.sh
source "$SCRIPTS/db-roles.sh"
# shellcheck source=../migrations.sh
source "$SCRIPTS/migrations.sh"
# shellcheck source=fixture.sh
source "$(dirname -- "${BASH_SOURCE[0]}")/fixture.sh"
fx_names seca_u
WORK=$(mktemp -d)
cleanup() { fx_cleanup; cd / && rm -rf -- "$WORK"; }
trap cleanup EXIT
fx_cleanup
for dir in /etc/bms /var/lib/bms /root/bms-update; do mount -t tmpfs -o mode=700 tmpfs "$dir"; done

# The role the live server has: bms_provisioner (the default name), LOGIN NOINHERIT, nothing else, server.env without it.
[[ $(sql postgres "SELECT count(*) FROM pg_roles WHERE rolname = 'bms_provisioner'") == 0 ]] ||
    { printf '  (omitido: el cluster de pruebas ya tiene un rol bms_provisioner; no se toca)\n'; exit 0; }
trap 'psu postgres -c "DROP ROLE IF EXISTS bms_provisioner" >/dev/null 2>&1; cleanup' EXIT
fx_release
psu postgres -c "CREATE ROLE bms_provisioner LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE"
cat > /etc/bms/server.env <<ENV
DB_URL=jdbc:postgresql://127.0.0.1:$PG_PORT/$CONTROL
DB_USER=$RUNTIME
DB_PASSWORD="runtime-pass"
BMS_MASTER_KEY=llave-que-no-se-imprime
TENANT_JDBC_URL_PREFIX=jdbc:postgresql://127.0.0.1:$PG_PORT/
CORE_UPDATE_REF=master
ENV
chmod 600 /etc/bms/server.env

# The release: a copy of this working tree as a git repository (what GitHub would serve), and the mirror cloned from it.
release=$WORK/release
mkdir -p "$release/server" "$release/modules/clientes"
cp -a "$REPO/scripts" "$REPO/database" "$release/"
cp -a "$REPO/modules/clientes/migrations" "$release/modules/clientes/"
cp "$REPO/server/build.gradle.kts" "$release/server/"
git -C "$release" init -q -b master
git -C "$release" add -A
git -C "$release" -c user.name=t -c user.email=t@example.com commit -q -m release
git clone -q "$release" /var/lib/bms/mirror
git -C /var/lib/bms/mirror remote set-url origin https://github.com/alexcalbri/BMS.git
chmod 700 /var/lib/bms/mirror
mkdir -p "$WORK/shim"
cat > "$WORK/shim/git" <<'SHIM'
#!/usr/bin/env bash
# `fetch ... origin <ref>` is answered from the local release instead of GitHub.
args=("$@")
for a in "$@"; do
    if [[ $a == fetch ]]; then
        for i in "${!args[@]}"; do [[ ${args[$i]} == origin ]] && args[i]=$RELEASE_REPO; done
        break
    fi
done
exec /usr/bin/git "${args[@]}"
SHIM
chmod 755 "$WORK/shim/git"
export RELEASE_REPO=$release

# The scripts as the operator downloads them (section 15): update.sh mirror.sh preflight.sh db-roles.sh nginx-site.sh.
for f in update.sh mirror.sh preflight.sh db-roles.sh nginx-site.sh; do install -m 644 -o root -g root "$SCRIPTS/$f" "/root/bms-update/$f"; done

# runuser -u postgres psql talks to the test server through the environment.
export PGHOST=$PG_HOST PGPORT=$PG_PORT PGUSER=postgres
export PATH="$WORK/shim:$PATH"
snap() {
    sql postgres "SELECT string_agg(rolname || rolcanlogin::text || rolinherit::text, ',' ORDER BY rolname) FROM pg_roles WHERE rolname LIKE '${FX}%' OR rolname = 'bms_provisioner'"
    sql postgres "SELECT string_agg(m.member::regrole || '>' || m.roleid::regrole || to_jsonb(m)::text, ',' ORDER BY 1) FROM pg_auth_members m WHERE m.member::regrole::text LIKE '${FX}%'"
    sql "$TA" "SELECT string_agg(relname || pg_get_userbyid(relowner) || COALESCE(relacl::text, ''), ',' ORDER BY relname) FROM pg_class WHERE relnamespace = 'public'::regnamespace"
    sql "$TA" "SELECT count(*) FROM pg_class WHERE relname = 'activity_log'"
    sql "$CONTROL" "SELECT count(*) FROM pg_class WHERE relname = 'server_admins'"
    sql postgres "SELECT datacl::text FROM pg_database WHERE datname = '$TA'"
}
before=$(snap)

# --- update.sh --rehearse
output=$(bash /root/bms-update/update.sh --rehearse --ref master 2>"$WORK/rehearse.err")
status=$?
printf '%s\n' "$output" | sed 's/^/    | /'
[[ -s $WORK/rehearse.err ]] && sed 's/^/    ! /' "$WORK/rehearse.err"
equals 'update.sh --rehearse termina bien' 0 "$status"
check 'sincroniza el espejo y dice contra que ensaya' grep -q '^Ensayo contra master' <<< "$output"
check 'el rol sobrante bms_provisioner se adopta' grep -q '^INFO rol bms_provisioner — existe, sin pertenencias ni privilegios' <<< "$output"
check 'ensaya la base central' grep -q "^PASS ensayo control $CONTROL — ejecutado y revertido" <<< "$output"
check 'ensaya cada empresa' bash -c '[[ $(grep -c "^PASS ensayo tenant .* — ejecutado y revertido" <<< "$1") == 2 ]]' _ "$output"
check 'cero cambios comprobado' grep -q '^PASS ensayo sin cambios' <<< "$output"
check 'resumen sin FAIL (el unico WARN: el pg_hba de pruebas es trust)' grep -q '^0 FAIL, 1 WARN' <<< "$output"
check 'pg_hba y login TCP del aprovisionador revisados' grep -q '^PASS login TCP como el aprovisionador' <<< "$output"
check 'cada base dice su duracion' bash -c '[[ $(grep -cE " en [0-9]+ ms " <<< "$1") == 3 ]]' _ "$output"
check 'y lo que no se ensayo' grep -q '^INFO NO ensayado' <<< "$output"
equals 'no queda ningun rol de prueba' 0 "$(sql postgres "SELECT count(*) FROM pg_roles WHERE rolname LIKE 'bms\_rh\_%'")"
check 'ningun secreto aparece' eval '! grep -q "no-se-imprime" <<< "$output"'
equals 'el servidor quedo exactamente igual' "$before" "$(snap)"
equals 'el espejo es lo unico que escribio (el commit del ensayo)' "$(git -C "$release" rev-parse HEAD)" "$(git -C /var/lib/bms/mirror rev-parse HEAD)"

# The in-app assistant (tenant V018-V020): with the detail of every new object, the rolled back transaction of each company
# ends with assistant_messages readable and appendable only (SELECT, INSERT = "ar") for the runtime role.
detail=$(BMS_REHEARSE_ALL_NEW=1 BMS_REHEARSE_DETAIL=2000 bash /root/bms-update/update.sh --rehearse --ref master 2>/dev/null)
check 'asistente: el ensayo de cada empresa crea assistant_messages' bash -c '[[ $(grep -c "^INFO   + objeto public.assistant_messages: r " <<< "$1") == 2 ]]' _ "$detail"
check 'asistente: y la deja con solo SELECT e INSERT para el runtime' bash -c '[[ $(grep -cE "objeto public\.assistant_messages: r .*\{[^}]*,$2=ar/[^,}]*\}" <<< "$1") == 2 ]]' _ "$detail" "$RUNTIME"
check 'asistente: sin UPDATE ni DELETE en ninguna linea de assistant_messages' eval '! grep -E "objeto public\.assistant_messages: " <<< "$detail" | grep -qE "$RUNTIME=[a-zA-Z]*[wdD]"'
equals 'el ensayo con detalle tampoco cambio el servidor' "$before" "$(snap)"

# --- argument handling
out=$(bash /root/bms-update/update.sh --rehearse --commit 0123456789abcdef0123456789abcdef01234567 2>&1)
check '--rehearse con --commit se rechaza' grep -q -- '--rehearse no se combina' <<< "$out"
out=$(bash /root/bms-update/update.sh --ref master 2>&1)
check '--ref sin --rehearse se rechaza' grep -q -- '--ref solo se usa con --rehearse' <<< "$out"
out=$(bash /root/bms-update/update.sh --rehearse --ref 'a b' 2>&1)
check 'una referencia invalida se rechaza' grep -q 'Referencia Git invalida' <<< "$out"
out=$(BMS_TEST_FAIL_AT=after_check bash /root/bms-update/update.sh --self-test 2>&1)
check 'BMS_TEST_FAIL_AT no se acepta en un update.sh real' grep -q 'solo para las pruebas' <<< "$out"
# Another install or update running: the rehearsal waits for nobody.
out=$( ( exec 9>>/run/bms-maintenance.lock; flock -n 9 && bash /root/bms-update/update.sh --rehearse --ref master 2>&1 ) )
check 'con otra actualizacion en curso se niega' grep -q 'Ya hay una instalacion o actualizacion de BMS en curso' <<< "$out"

# --- preflight.sh, as the operator runs it (host checks skipped: this is not a real install).
output=$(BMS_PREFLIGHT_SKIP_HOST=true bash /root/bms-update/preflight.sh 2>&1)
status=$?
printf '%s\n' "$output" | sed 's/^/    | /'
check 'preflight muestra el ensayo' grep -q "^PASS ensayo control $CONTROL — ejecutado y revertido" <<< "$output"
check 'y su resultado' grep -q '^PASS ensayo de update.sh — update.sh --rehearse --ref master termino bien' <<< "$output"
check 'preflight no cambio nada' bash -c '[[ $1 == "$2" ]]' _ "$before" "$(snap)"
check 'ningun secreto aparece en preflight' eval '! grep -q "no-se-imprime" <<< "$output"'
equals 'preflight: el unico FAIL es pg_hba con trust del servidor de pruebas (y los que ese servidor tiene)' '' "$(grep '^FAIL' <<< "$output" | grep -v 'pg_hba' | grep -v 'actualizaciones pedidas' || true)"

# --- A release whose migration does not run on this PostgreSQL: phase 1 finds it, the server stays as it was.
printf 'BEGIN;\nALTER TABLE tabla_que_no_existe ADD COLUMN x int;\nCOMMIT;\n' > "$release/database/tenant/migrations/V099__rota.sql"
git -C "$release" add -A
git -C "$release" -c user.name=t -c user.email=t@example.com commit -q -m rota
output=$(bash /root/bms-update/update.sh --rehearse --ref master 2>"$WORK/rehearse.err")
status=$?
printf '%s\n' "$output" | grep -E '^(FAIL|[0-9]+ FAIL)' | sed 's/^/    | /'
equals 'con una migracion rota update.sh --rehearse sale con 1 (y llega al final)' 1 "$status"
check 'una linea FAIL por empresa, con el error de PostgreSQL' bash -c '[[ $(grep -c "^FAIL ensayo tenant .*tabla_que_no_existe" <<< "$1") == 2 ]]' _ "$output"
check 'el resumen cuenta los FAIL' grep -q '^2 FAIL, 1 WARN' <<< "$output"
check 'y comprueba que no cambio nada' grep -q '^PASS ensayo sin cambios' <<< "$output"
check 'y no hay errores de bash sin explicar' eval '! grep -q "interrumpida" "$WORK/rehearse.err"'
equals 'el servidor sigue igual' "$before" "$(snap)"
output=$(BMS_PREFLIGHT_SKIP_HOST=true bash /root/bms-update/preflight.sh 2>&1)
check 'preflight cuenta esos FAIL' grep -q '^FAIL ensayo tenant .*tabla_que_no_existe' <<< "$output"
check 'y termina con error' bash -c '! BMS_PREFLIGHT_SKIP_HOST=true bash /root/bms-update/preflight.sh >/dev/null 2>&1'
finish
