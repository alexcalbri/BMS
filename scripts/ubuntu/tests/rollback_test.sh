#!/usr/bin/env bash
# shellcheck disable=SC2016 # SQL and eval bodies expand later on purpose
# The role move and update.sh's automatic rollback with the REAL helpers and the real psql (no overrides): a failure
# injected (BMS_TEST_FAIL_AT, db-roles.sh) at every stage of the move, then update.sh's own rollback function
# (rollback_config_and_roles, read from update.sh). After each one: server.env is the saved copy, the runtime role is a member
# of every server owner role again (inherit and set, as install.sh made it) and the service-facing state is the old
# one: it connects and reads everywhere, writes the activity log, and SETs ROLE to each owner as the previous release does
# to create companies. The rollback uses only what step 0 recorded: the control database's server_owners is broken
# before it runs on purpose.
set -uo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"
pg_available || { printf '  (omitido: no hay PostgreSQL en %s)\n' "$PG"; exit 0; }
[[ $EUID -eq 0 ]] || { printf '  (omitido: la restauracion de server.env instala archivos de root; ejecuta como root)\n'; exit 0; }
# shellcheck source=../db-roles.sh
source "$SCRIPTS/db-roles.sh"
# shellcheck source=../migrations.sh
source "$SCRIPTS/migrations.sh"
# shellcheck source=fixture.sh
source "$(dirname -- "${BASH_SOURCE[0]}")/fixture.sh"
WORK=$(mktemp -d)
fx_names seca_r
cleanup() { fx_cleanup; rm -rf -- "$WORK"; }
cleanup
WORK=$(mktemp -d)
trap cleanup EXIT

# update.sh's own functions: the validators and the role-layout block (read from the file, not copied).
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
warn() { printf 'AVISO: %s\n' "$*" >&2; }
sed -n '/^# BEGIN validators/,/^# END validators/p' "$SCRIPTS/update.sh" > "$WORK/validators.sh"
sed -n '/^# BEGIN role-layout/,/^# END role-layout/p' "$SCRIPTS/update.sh" > "$WORK/layout.sh"
check 'update.sh tiene su bloque de organizacion de roles' test -s "$WORK/layout.sh"
# shellcheck source=/dev/null
source "$WORK/validators.sh"
# shellcheck source=/dev/null
source "$WORK/layout.sh"
CONFIG_FILE=$WORK/server.env
BACKUP_DIR=$WORK/backup
# shellcheck disable=SC2034 # read by the functions of update.sh
DEFAULT_PROVISIONER=bms_provisioner

# The service as the previous release uses it: the runtime role's own connections, no superuser.
as_runtime() { PGPASSWORD=runtime-pass psql -X -q -At -v ON_ERROR_STOP=1 -h "$PG_HOST" -p "$PG_PORT" -U "$RUNTIME" -d "$1" -c "$2" 2>&1; }
service_is_old() {
    local owner database problems=''
    for owner in "$OWNER" "$OWNER2"; do
        [[ $(sql postgres "SELECT pg_has_role('$RUNTIME', '$owner', 'MEMBER') AND pg_has_role('$RUNTIME', '$owner', 'USAGE') AND pg_has_role('$RUNTIME', '$owner', 'SET')") == t ]] || problems+=" sin-membresia-$owner"
        [[ $(sql postgres "SELECT bool_and(inherit_option AND set_option) FROM pg_auth_members WHERE member = '$RUNTIME'::regrole AND roleid = '$owner'::regrole") == t ]] || problems+=" opciones-$owner"
    done
    [[ $(sql postgres "SELECT rolcreatedb OR rolcreaterole OR rolsuper FROM pg_roles WHERE rolname = '$RUNTIME'") == f ]] || problems+=' atributos'
    for database in "$CONTROL" "$TA" "$TB"; do
        [[ $(as_runtime "$database" 'SELECT count(*) >= 0 FROM application_users') == t ]] || problems+=" lectura-$database"
    done
    [[ $(as_runtime "$TA" "INSERT INTO activity_log (summary) VALUES ('x') RETURNING true") == t ]] || problems+=' registro-TA'
    [[ $(as_runtime "$TB" "INSERT INTO activity_log (summary) VALUES ('x') RETURNING true") == t ]] || problems+=' registro-TB'
    # The previous release creates a company's objects by SET ROLE to the owner from the runtime role.
    [[ $(as_runtime "$TA" "SET ROLE $OWNER; CREATE TABLE zz_prueba (id int); DROP TABLE zz_prueba; SELECT true" | tail -n 1) == t ]] || problems+=' set-role-TA'
    [[ $(as_runtime "$TB" "SET ROLE $OWNER2; CREATE TABLE zz_prueba (id int); DROP TABLE zz_prueba; SELECT true" | tail -n 1) == t ]] || problems+=' set-role-TB'
    printf '%s' "${problems# }"
}

STAGES=(after_provisioner_alter after_set_memberships after_connect_control after_tenant_layout after_control_layout
    after_revoke_membership after_runtime_alter after_check after_verify)
printf 'DB_URL=jdbc:postgresql://127.0.0.1:5432/%s\nDB_USER=%s\nDB_PASSWORD="x"\n' "bms_x" "seca_r_rt" > "$WORK/server.env.original"
for stage in "${STAGES[@]}" none; do
    fx_cleanup
    fx_minimal
    fx_provisioner
    DB_USER=$RUNTIME
    CONTROL_DB=$CONTROL
    cp -p "$WORK/server.env.original" "$CONFIG_FILE"
    chmod 600 "$CONFIG_FILE"
    rm -rf "$BACKUP_DIR"
    install -d -m 700 "$BACKUP_DIR"
    install -m 600 "$CONFIG_FILE" "$BACKUP_DIR/server.env"
    equals "[$stage] antes: el servicio funciona como la version anterior" '' "$(service_is_old)"
    # Step 0: the owner roles, validated, recorded.
    LAYOUT_OWNER_ROLES=$(bms_server_owner_roles "$CONTROL") || fail "[$stage] roles de server owner"
    equals "[$stage] los roles registrados" "$OWNER $OWNER2" "$(paste -sd ' ' - <<< "$LAYOUT_OWNER_ROLES")"
    # configure_server_env's writes, then the move, failing at [stage].
    printf 'DB_PROVISIONING_USER=%s\nDB_PROVISIONING_PASSWORD=%s\n' "$PROVISIONER" "$FX_PASSWORD" >> "$CONFIG_FILE"
    LAYOUT_CHANGED=true
    if [[ $stage == none ]]; then
        check "[$stage] el movimiento termina bien" bms_migrate_role_layout "$CONTROL" "$RUNTIME" "$PROVISIONER" "$LAYOUT_OWNER_ROLES"
    else
        BMS_TEST_FAIL_AT=$stage bms_migrate_role_layout "$CONTROL" "$RUNTIME" "$PROVISIONER" "$LAYOUT_OWNER_ROLES" 2> "$WORK/move.err"
        status=$?
        equals "[$stage] el movimiento falla" 1 "$status"
        check "[$stage] y dice en que etapa" grep -q "fallo inyectado en $stage" "$WORK/move.err"
    fi
    if [[ $stage == after_verify || $stage == none ]]; then
        equals "[$stage] el movimiento si cambio el servicio (la prueba tiene dientes)" f "$(sql postgres "SELECT pg_has_role('$RUNTIME', '$OWNER', 'MEMBER')")"
    fi
    # Whatever step 0 read can no longer be read: the rollback must not need it.
    psu "$CONTROL" -c 'ALTER TABLE server_owners RENAME TO server_owners_roto' >/dev/null
    check "[$stage] (sin la lista de server owners en la base central)" eval '! bms_server_owner_roles "$CONTROL" >/dev/null 2>&1'
    rollback_config_and_roles 2> "$WORK/rollback.err"
    equals "[$stage] la restauracion termina bien" 0 "$?"
    check "[$stage] server.env es la copia guardada" cmp -s "$WORK/server.env.original" "$CONFIG_FILE"
    equals "[$stage] server.env: root, 600" 'root:root 600' "$(stat -c '%U:%G %a' "$CONFIG_FILE")"
    equals "[$stage] despues: el servicio esta como la version anterior" '' "$(service_is_old)"
    psu "$CONTROL" -c 'ALTER TABLE server_owners_roto RENAME TO server_owners' >/dev/null
    # And the move can be tried again from there.
    check "[$stage] un nuevo intento termina" bms_migrate_role_layout "$CONTROL" "$RUNTIME" "$PROVISIONER" "$LAYOUT_OWNER_ROLES"
    equals "[$stage] y el runtime ya no es miembro" 'f f' "$(sql postgres "SELECT pg_has_role('$RUNTIME', '$OWNER', 'MEMBER'), pg_has_role('$RUNTIME', '$OWNER2', 'MEMBER')" | tr '|' ' ')"
done

# --- The restoration itself: it goes on after a failure and says exactly what to run.
fx_cleanup
fx_minimal
fx_provisioner
LAYOUT_OWNER_ROLES=$(bms_server_owner_roles "$CONTROL")
bms_migrate_role_layout "$CONTROL" "$RUNTIME" "$PROVISIONER" "$LAYOUT_OWNER_ROLES" 2>/dev/null || fail 'el movimiento'
LAYOUT_CHANGED=true
BMS_TEST_FAIL_AT=rollback_grant rollback_config_and_roles 2> "$WORK/rollback.err"
equals 'si no se puede devolver la pertenencia, termina con error' 1 "$?"
check 'y el mensaje trae la orden exacta de cada rol' bash -c 'grep -q "GRANT \"$1\" TO \"$3\"" "$4" && grep -q "GRANT \"$2\" TO \"$3\"" "$4"' _ "$OWNER" "$OWNER2" "$RUNTIME" "$WORK/rollback.err"
LAYOUT_OWNER_ROLES=$(printf '%s\nSET\n%s\n' "$OWNER" "$OWNER2")
rollback_config_and_roles 2> "$WORK/rollback.err"
equals 'un rol no valido en la lista no impide devolver los demas (y se informa)' 1 "$?"
equals 'los demas si se devolvieron' 't t' "$(sql postgres "SELECT pg_has_role('$RUNTIME', '$OWNER', 'MEMBER'), pg_has_role('$RUNTIME', '$OWNER2', 'MEMBER')" | tr '|' ' ')"
check 'y el mensaje nombra el valor' grep -q 'SET' "$WORK/rollback.err"
LAYOUT_OWNER_ROLES=''
rollback_config_and_roles 2> "$WORK/rollback.err"
equals 'sin lista registrada se dice y termina con error' 1 "$?"
LAYOUT_CHANGED=false
rollback_config_and_roles 2>/dev/null
equals 'si el movimiento no empezo, no se toca ninguna pertenencia' 0 "$?"

# --- migrate_role_layout (update.sh): a server owner created while the build ran stops the move before changing anything.
fx_cleanup
fx_minimal
fx_provisioner
# shellcheck disable=SC2034 # read by migrate_role_layout
CONTROL_DB=$CONTROL DB_USER=$RUNTIME LAYOUT_CHANGED=false
LAYOUT_OWNER_ROLES=$OWNER
( migrate_role_layout ) 2> "$WORK/migrate.err"
equals 'lista de server owners cambiada: el movimiento se detiene' 1 "$?"
check 'con el motivo' grep -q 'cambio desde la comprobacion previa' "$WORK/migrate.err"
equals 'y no cambio ningun rol' 't t' "$(sql postgres "SELECT pg_has_role('$RUNTIME', '$OWNER', 'MEMBER'), pg_has_role('$RUNTIME', '$OWNER2', 'MEMBER')" | tr '|' ' ')"
finish
