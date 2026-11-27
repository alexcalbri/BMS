#!/usr/bin/env bash
# Hardens the activity log of every company (ACTIVITY_LOG.md §9): gives activity_log, its chain head,
# sequence and trigger functions to the NOLOGIN role bms_audit, which the runtime role is not a member
# of, leaves the runtime role SELECT/INSERT, and installs event triggers that refuse dropping them.
# Runs as root and talks to PostgreSQL as the postgres superuser (peer authentication), like
# update.sh. Installed by install.sh/update.sh with --install as /usr/local/sbin/bms-activity-harden
# and run hourly by bms-activity-harden.timer, so a company created from the app (whose log the
# server owner's role owns until then) is hardened within the hour. Idempotent.
#   --install            install this script, its SQL and the systemd units
#   --database <name>    harden only that company database
set -Eeuo pipefail
umask 022

readonly CONFIG_FILE=${BMS_SERVER_ENV:-/etc/bms/server.env}
readonly INSTALLED_PATH=/usr/local/sbin/bms-activity-harden
readonly INSTALLED_SQL=/usr/local/share/bms/activity-harden.sql
readonly LOG_FILE=${BMS_ACTIVITY_HARDEN_LOG:-/var/log/bms-activity-harden.log}
readonly NAME_PATTERN='^[a-z][a-z0-9_]{0,62}$'

log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >> "$LOG_FILE"; }
die() { log "ERROR: $*"; printf 'ERROR: %s\n' "$*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die 'Debe ejecutarse como root.'
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)

if [[ ${1:-} == --install ]]; then
    [[ -f $script_dir/activity-harden.sql ]] || die "No se encontro $script_dir/activity-harden.sql."
    install -m 755 "${BASH_SOURCE[0]}" "$INSTALLED_PATH"
    install -d -m 755 "$(dirname "$INSTALLED_SQL")"
    install -m 644 "$script_dir/activity-harden.sql" "$INSTALLED_SQL"
    cat > /etc/systemd/system/bms-activity-harden.service <<'UNIT'
[Unit]
Description=Harden the BMS company activity logs (owner bms_audit)
After=postgresql.service
[Service]
Type=oneshot
ExecStart=/usr/local/sbin/bms-activity-harden
UNIT
    cat > /etc/systemd/system/bms-activity-harden.timer <<'UNIT'
[Unit]
Description=Harden new BMS company activity logs every hour
[Timer]
OnBootSec=5min
OnUnitActiveSec=1h
[Install]
WantedBy=timers.target
UNIT
    chmod 644 /etc/systemd/system/bms-activity-harden.{service,timer}
    systemctl daemon-reload
    systemctl enable --now bms-activity-harden.timer
    printf 'Proteccion del registro de actividad instalada (bms-activity-harden.timer).\n'
    exit 0
fi

only=
if [[ ${1:-} == --database ]]; then
    only=${2:-}
    [[ $only =~ $NAME_PATTERN ]] || die 'Nombre de base no valido.'
fi

sql_file=$INSTALLED_SQL
[[ -f $script_dir/activity-harden.sql && $script_dir != /usr/local/sbin ]] && sql_file=$script_dir/activity-harden.sql
[[ -f $sql_file ]] || die "No se encontro $sql_file."
[[ -r $CONFIG_FILE ]] || die "No se encontro $CONFIG_FILE."

exec 9>"${BMS_ACTIVITY_HARDEN_LOCK:-/run/bms-activity-harden.lock}"
flock -n 9 || exit 0

config_value() {
    awk -F= -v key="$1" '$1 == key { sub(/^[^=]*=/, ""); print; exit }' "$CONFIG_FILE"
}
db_url=$(config_value DB_URL)
db_user=$(config_value DB_USER)
db_name=${db_url##*/}
db_name=${db_name%%\?*}
[[ $db_name =~ $NAME_PATTERN ]] || die 'DB_URL no tiene un nombre de base valido.'
[[ $db_user =~ $NAME_PATTERN ]] || die 'DB_USER no es un rol valido.'

pg() { runuser -u postgres -- psql -X -q -v ON_ERROR_STOP=1 "$@"; }

[[ $(pg -d postgres -Atc 'SHOW server_version_num') -ge 160000 ]] ||
    die 'Se necesita PostgreSQL 16 o posterior: antes, el rol del dueno (CREATEROLE) podria darse bms_audit.'
# The role exists once, outside the runtime's membership: NOLOGIN, NOINHERIT, no attributes.
pg -d postgres -v db_user="$db_user" <<'SQL'
SELECT NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'bms_audit') AS missing \gset
\if :missing
CREATE ROLE bms_audit NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
\endif
ALTER ROLE bms_audit NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
SELECT pg_has_role(:'db_user', 'bms_audit', 'MEMBER') AS member \gset
\if :member
DO $$ BEGIN RAISE EXCEPTION 'El rol de la aplicacion es miembro de bms_audit: se aborta.'; END $$;
\endif
SQL

if [[ -n $only ]]; then
    databases=$only
else
    databases=$(pg -d "$db_name" -At -c 'SELECT database_name FROM companies ORDER BY code') ||
        die 'No se pudo leer la lista de empresas.'
fi

failed=0
while IFS= read -r tenant_db; do
    [[ -n $tenant_db ]] || continue
    if [[ ! $tenant_db =~ $NAME_PATTERN ]]; then
        log "AVISO: se omite la base \"$tenant_db\": nombre no valido."
        continue
    fi
    [[ $(pg -d postgres -Atc "SELECT count(*) FROM pg_database WHERE datname = '$tenant_db'") == 1 ]] || continue
    if { printf "SELECT set_config('bms.runtime_user', :'db_user', false);\n"; cat "$sql_file"; } |
        pg -d "$tenant_db" -v db_user="$db_user" >/dev/null; then
        log "OK $tenant_db"
    else
        log "ERROR al proteger $tenant_db"
        printf 'AVISO: no se pudo proteger el registro de actividad de %s (ver %s).\n' "$tenant_db" "$LOG_FILE" >&2
        failed=1
    fi
done <<< "$databases"
exit $failed
