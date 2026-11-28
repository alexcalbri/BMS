#!/usr/bin/env bash
# backup.sh: the database dump update.sh and bms-backup use (restorable, root-only files), the redacted copy of
# server.env and the free-space guard.
# shellcheck disable=SC2016 # eval bodies expand later on purpose
set -uo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"
# shellcheck source=../backup.sh
source "$SCRIPTS/backup.sh"
WORK=$(mktemp -d)
DB=bms_seca_tbk
cleanup() {
    rm -rf -- "$WORK"
    pg_available && psu postgres -c "DROP DATABASE IF EXISTS $DB WITH (FORCE)" >/dev/null 2>&1
    pg_available && psu postgres -c "DROP DATABASE IF EXISTS ${DB}_restore WITH (FORCE)" >/dev/null 2>&1
}
trap cleanup EXIT

# The guard: room for the bytes plus 2 GiB.
check 'espacio suficiente para 1 byte' bms_backup_space_ok "$WORK" 1
check 'sin espacio para un exabyte' eval '! bms_backup_space_ok "$WORK" 1000000000000000000'

# server.env without its secrets.
printf 'DB_URL=jdbc:postgresql://127.0.0.1:5432/bms_control\nDB_PASSWORD="s3cr3t"\nBMS_MASTER_KEY=abc\nDB_PROVISIONING_PASSWORD=ff\nSMTP_HOST=mail\nMODULE_GITHUB_TOKEN=t\n' > "$WORK/server.env"
redacted=$(bms_redacted_env "$WORK/server.env")
check 'la copia redactada no lleva secretos' eval '! grep -qE "s3cr3t|=abc|=ff|=t$" <<< "$redacted"'
check 'y conserva lo demas' grep -q 'SMTP_HOST=mail' <<< "$redacted"

if pg_available; then
    psu postgres -c "CREATE DATABASE $DB" >/dev/null || die 'base de prueba'
    sql "$DB" "CREATE TABLE t (id int); INSERT INTO t SELECT generate_series(1, 50)" >/dev/null
    # Only this test's database (the real list is every bms_* database of the server), and the superuser over TCP.
    bms_backup_databases() { printf '%s\n' "$DB"; }
    runuser() {
        [[ $1 == -u && $3 == -- ]] || return 1
        shift 3
        PGHOST=$PG_HOST PGPORT=$PG_PORT PGUSER=postgres "$@"
    }
    check 'volcado de las bases' bms_dump_databases "$WORK/db" "$DB"
    equals 'archivos solo de root' '700 600 600' "$(stat -c %a "$WORK/db") $(stat -c %a "$WORK/db/$DB.dump") $(stat -c %a "$WORK/db/roles.sql")"
    check 'sumas de comprobacion' bash -c "cd '$WORK/db' && sha256sum -c --quiet SHA256SUMS"
    psu postgres -c "CREATE DATABASE ${DB}_restore" >/dev/null
    check 'el volcado se restaura' pg_restore -h "$PG_HOST" -p "$PG_PORT" -U postgres -d "${DB}_restore" "$WORK/db/$DB.dump"
    equals 'filas restauradas' 50 "$(sql "${DB}_restore" 'SELECT count(*) FROM t')"
else
    printf '  (omitido el volcado: no hay PostgreSQL en %s)\n' "$PG"
fi
finish
