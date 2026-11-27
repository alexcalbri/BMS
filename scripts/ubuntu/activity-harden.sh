#!/usr/bin/env bash
# Hardens the activity log of every company (ACTIVITY_LOG.md §9): normalizes activity_log, its chain, its head,
# its sequence and its trigger functions to exactly what tenant V011 creates (removing whatever the server
# owner's role added: triggers, rules, policies, inheritance, columns, constraints, indexes...), gives them to
# the NOLOGIN role bms_audit, which the runtime role is not a member of, leaves the runtime role SELECT/INSERT,
# and installs event triggers that refuse dropping them. A company already exactly hardened is only read.
# Runs as root and talks to PostgreSQL as the postgres superuser through pg-safe.sh (pinned role and
# search_path, per-database settings checked and reset first). Installed by install.sh/update.sh with
# --install as /usr/local/sbin/bms-activity-harden and run hourly by bms-activity-harden.timer, so a company
# created from the app (whose log the server owner's role owns until then) is hardened within the hour.
# What it removes or resets is logged and written to the server audit as activity.integrity_failed.
#   --install            install this script, its SQL and the systemd units
#   --database <name>    harden only that company database
set -Eeuo pipefail
umask 022

readonly CONFIG_FILE=${BMS_SERVER_ENV:-/etc/bms/server.env}
readonly INSTALLED_PATH=/usr/local/sbin/bms-activity-harden
readonly SHARE_DIR=/usr/local/share/bms
readonly LIB_DIR=/usr/local/lib/bms
readonly LOG_FILE=${BMS_ACTIVITY_HARDEN_LOG:-/var/log/bms-activity-harden.log}
readonly NAME_PATTERN='^[a-z][a-z0-9_]{0,62}$'
# Tenant migrations that define the log, in order (a later one that alters it is added here, §9 "Migrations").
readonly CANONICAL_MIGRATIONS=(V011__create_activity_log.sql)

log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >> "$LOG_FILE"; }
die() { log "ERROR: $*"; printf 'ERROR: %s\n' "$*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die 'Debe ejecutarse como root.'
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_dir=$(cd -- "$script_dir/../.." && pwd)

if [[ $script_dir == /usr/local/sbin ]]; then
    sql_file=$SHARE_DIR/activity-harden.sql
    deviations_file=$SHARE_DIR/activity-deviations.sql
    canonical_dir=$SHARE_DIR/activity-canonical
    safe_lib=$LIB_DIR/pg-safe.sh
else
    sql_file=$script_dir/activity-harden.sql
    deviations_file=$repo_dir/server/src/main/resources/activity/activity-deviations.sql
    canonical_dir=$repo_dir/database/tenant/migrations
    safe_lib=$script_dir/pg-safe.sh
fi

if [[ ${1:-} == --install ]]; then
    for file in "$sql_file" "$deviations_file" "$safe_lib"; do
        [[ -f $file ]] || die "No se encontro $file."
    done
    install -m 755 "${BASH_SOURCE[0]}" "$INSTALLED_PATH"
    install -d -m 755 "$SHARE_DIR" "$SHARE_DIR/activity-canonical" "$LIB_DIR"
    install -m 644 "$sql_file" "$SHARE_DIR/activity-harden.sql"
    install -m 644 "$deviations_file" "$SHARE_DIR/activity-deviations.sql"
    install -m 644 "$safe_lib" "$LIB_DIR/pg-safe.sh"
    for migration in "${CANONICAL_MIGRATIONS[@]}"; do
        [[ -f $canonical_dir/$migration ]] || die "No se encontro $canonical_dir/$migration."
        install -m 644 "$canonical_dir/$migration" "$SHARE_DIR/activity-canonical/$migration"
    done
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

for file in "$sql_file" "$deviations_file" "$safe_lib"; do
    [[ -f $file ]] || die "No se encontro $file."
done
for migration in "${CANONICAL_MIGRATIONS[@]}"; do
    [[ -f $canonical_dir/$migration ]] || die "No se encontro $canonical_dir/$migration."
done
[[ -r $CONFIG_FILE ]] || die "No se encontro $CONFIG_FILE."
# shellcheck source=pg-safe.sh
source "$safe_lib"

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

# The bodies of the canonical migrations (without their BEGIN/COMMIT) and the sha256 of each function's
# source as PostgreSQL stores it (prosrc), for the deviation query.
canonical_sql=$(python3 - "$canonical_dir" "${CANONICAL_MIGRATIONS[@]}" <<'PY'
import pathlib, sys
for name in sys.argv[2:]:
    sql = (pathlib.Path(sys.argv[1]) / name).read_text(encoding="utf-8").strip()
    if not sql.startswith("BEGIN;") or not sql.endswith("COMMIT;"):
        sys.exit(f"{name}: falta BEGIN/COMMIT")
    print(sql[len("BEGIN;"):-len("COMMIT;")])
PY
) || die 'No se pudieron leer las migraciones del registro.'
audit_functions=$(python3 - "$sql_file" "$canonical_dir" "${CANONICAL_MIGRATIONS[@]}" <<'PY'
import hashlib, pathlib, re, sys
found = {}
for name in sys.argv[3:]:
    text = (pathlib.Path(sys.argv[2]) / name).read_text(encoding="utf-8")
    for match in re.finditer(r"CREATE OR REPLACE FUNCTION public\.(\w+)\(\).*?AS \$\$(.*?)\$\$;", text, re.S):
        found[match.group(1)] = hashlib.sha256(match.group(2).encode()).hexdigest()
guard = re.search(r"\$guard_body\$(.*?)\$guard_body\$", pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"), re.S)
found["guard"] = hashlib.sha256(guard.group(1).encode()).hexdigest()
wanted = {"activity_log_append_only", "activity_log_before_insert", "activity_log_seal", "guard"}
if set(found) != wanted:
    sys.exit("funciones del registro incompletas: " + ", ".join(sorted(found)))
print(";".join(f"{key}={value}" for key, value in sorted(found.items())))
PY
) || die 'No se pudieron calcular las funciones del registro.'

[[ $(pg_admin_db -Atc 'SHOW server_version_num') -ge 160000 ]] ||
    die 'Se necesita PostgreSQL 16 o posterior: antes, el rol del dueno (CREATEROLE) podria darse bms_audit.'
# The role exists once, outside the runtime's membership: NOLOGIN, NOINHERIT, no attributes.
pg_admin_db -v db_user="$db_user" <<'SQL' || die 'No se pudo preparar el rol bms_audit.'
SELECT NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'bms_audit') AS missing \gset
\if :missing
CREATE ROLE bms_audit NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
\endif
ALTER ROLE bms_audit NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
SELECT pg_catalog.pg_has_role(:'db_user', 'bms_audit', 'MEMBER') AS member \gset
\if :member
DO $$ BEGIN RAISE EXCEPTION 'El rol de la aplicacion es miembro de bms_audit: se aborta.'; END $$;
\endif
SQL

if [[ -n $only ]]; then
    companies=$(pg_control "$db_name" -At -F '|' -v db="$only" <<'SQL'
SELECT code, database_name FROM public.companies WHERE database_name = :'db'
UNION ALL SELECT '', :'db' WHERE NOT EXISTS (SELECT 1 FROM public.companies WHERE database_name = :'db')
SQL
) || die 'No se pudo leer la lista de empresas.'
else
    companies=$(pg_control "$db_name" -At -F '|' -c 'SELECT code, database_name FROM public.companies ORDER BY code') ||
        die 'No se pudo leer la lista de empresas.'
fi

# Writes what was found and removed in a company database to the server audit (activity.integrity_failed).
evidence() {
    local code=$1 database=$2 kind=$3
    shift 3
    local details
    details=$(python3 -c 'import json, sys; print(json.dumps({"source": "activity-harden", "database": sys.argv[1], "kind": sys.argv[2], "problems": sys.argv[3:][:50]}))' \
        "$database" "$kind" "$@")
    pg_audit_evidence "$db_name" activity.integrity_failed "$code" "$details"
}

# Deviations of one database (kind|detail lines), plus "head|<hardened_at>" and "table|absent" when relevant.
inspect() {
    local database=$1
    { printf "SELECT pg_catalog.set_config('bms.runtime_user', :'db_user', false) IS NULL AS ignored \\\\gset\n"
      printf "SELECT pg_catalog.set_config('bms.audit_functions', :'audit_functions', false) IS NULL AS ignored \\\\gset\n"
      printf "SET lock_timeout = '3s';\n"
      printf 'BEGIN READ ONLY;\n'
      printf "SELECT pg_catalog.to_regclass('public.activity_log') IS NOT NULL AS present \\\\gset\n"
      printf '\\if :present\n'
      cat "$deviations_file"
      printf ';\n'
      # The head is read only once it is known (under a lock that blocks nobody) to be a plain table.
      printf "SELECT pg_catalog.to_regclass('public.activity_log_chain_head') IS NOT NULL AS head_present \\\\gset\n"
      printf '\\if :head_present\n'
      printf 'LOCK TABLE public.activity_log_chain_head IN ACCESS SHARE MODE;\n'
      printf "SELECT c.relkind = 'r' AS head_plain FROM pg_catalog.pg_class c WHERE c.oid = 'public.activity_log_chain_head'::pg_catalog.regclass \\\\gset\n"
      printf '\\if :head_plain\n'
      printf "SELECT 'head', COALESCE((SELECT pg_catalog.to_char(h.hardened_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') FROM public.activity_log_chain_head h WHERE h.id = 1 AND h.hardened_seq IS NOT NULL), '');\n"
      printf '\\endif\n\\endif\n'
      printf '\\else\n'
      printf "SELECT 'table', 'absent';\n"
      printf '\\endif\n'
      printf 'COMMIT;\n'
    } | pg_super "$database" -At -F '|' -v db_user="$db_user" -v audit_functions="$audit_functions" 2>>"$LOG_FILE"
}

failed=0
while IFS='|' read -r company tenant_db; do
    [[ -n $tenant_db ]] || continue
    if [[ ! $tenant_db =~ $NAME_PATTERN ]]; then
        log "AVISO: se omite la base \"$tenant_db\" de la empresa ${company:-?}: nombre no valido."
        continue
    fi
    if [[ $(pg_admin_db -At -v db="$tenant_db" <<< "SELECT pg_catalog.count(*) FROM pg_catalog.pg_database WHERE datname = :'db'") != 1 ]]; then
        log "AVISO: la base $tenant_db de la empresa ${company:-?} no existe: no se protege (la verificacion del servidor lo reporta)."
        continue
    fi
    PG_GUARD_FINDINGS=()
    if ! pg_db_guard "$tenant_db"; then
        log "ERROR $tenant_db: conserva ajustes peligrosos; no se ejecuta nada en ella."
        evidence "$company" "$tenant_db" setting "${PG_GUARD_FINDINGS[@]}" 'Los ajustes no se pudieron quitar.'
        failed=1
        continue
    fi
    guard_findings=("${PG_GUARD_FINDINGS[@]}")
    if ! found=$(inspect "$tenant_db") || [[ -z $found ]]; then
        log "ERROR al revisar $tenant_db"
        failed=1
        continue
    fi
    if [[ $found == *'table|absent'* ]]; then
        log "OK $tenant_db: aun sin el registro de actividad (V011)."
        (( ${#guard_findings[@]} == 0 )) || evidence "$company" "$tenant_db" setting "${guard_findings[@]}"
        continue
    fi
    deviations=$(grep -v '^head|' <<< "$found" || true)
    hardened_at=$(sed -n 's/^head|//p' <<< "$found")
    if [[ -z $deviations && -n $hardened_at ]]; then
        (( ${#guard_findings[@]} == 0 )) || evidence "$company" "$tenant_db" setting "${guard_findings[@]}"
        log "OK $tenant_db (ya protegida desde $hardened_at)"
        continue
    fi
    tampering=()
    while IFS='|' read -r kind detail; do
        [[ -n $kind ]] || continue
        log "  $tenant_db [$kind] $detail"
        [[ $kind == hardening ]] || tampering+=("$detail")
    done <<< "$deviations"

    output=$( { sed -n '1,/^-- @canonical@$/p' "$sql_file"; printf '%s\n' "$canonical_sql"; sed -n '/^-- @canonical@$/,$p' "$sql_file"; } |
        pg_super "$tenant_db" -v db_user="$db_user" 2>&1 >/dev/null ) && status=0 || status=$?
    removed=()
    while IFS= read -r line; do
        [[ $line == *'bms-harden: '* ]] || continue
        log "  $tenant_db ${line#*bms-harden: }"
        [[ $line == *'guarantee starts at'* ]] || removed+=("${line#*bms-harden: }")
    done <<< "$output"
    if (( status != 0 )); then
        if [[ $output == *'lock timeout'* || $output == *'canceling statement due to lock timeout'* ]]; then
            log "AVISO $tenant_db: ocupada (no se obtuvo el bloqueo en 3 s); se reintenta en la siguiente ejecucion."
        else
            log "ERROR al proteger $tenant_db: $(grep -m1 'ERROR' <<< "$output" || true)"
            printf 'AVISO: no se pudo proteger el registro de actividad de %s (ver %s).\n' "$tenant_db" "$LOG_FILE" >&2
        fi
        (( ${#tampering[@]} + ${#guard_findings[@]} == 0 )) || evidence "$company" "$tenant_db" tampering "${guard_findings[@]}" "${tampering[@]}"
        failed=1
        continue
    fi
    after=$(inspect "$tenant_db")
    if grep -qv '^head|' <<< "$after" || ! grep -q '^head|.' <<< "$after"; then
        log "ERROR $tenant_db: sigue sin estar exactamente protegida: $(grep -v '^head|' <<< "$after" | tr '\n' ' ')"
        failed=1
    else
        log "OK $tenant_db (protegida; garantia desde $(sed -n 's/^head|//p' <<< "$after"))"
    fi
    if (( ${#tampering[@]} + ${#guard_findings[@]} + ${#removed[@]} > 0 )); then
        evidence "$company" "$tenant_db" tampering "${guard_findings[@]}" "${tampering[@]}" "${removed[@]}"
    fi
done <<< "$companies"
exit $failed
