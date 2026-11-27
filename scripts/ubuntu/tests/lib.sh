#!/usr/bin/env bash
# Helpers of the script tests (sourced).
SCRIPTS=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
# shellcheck disable=SC2034 # used by the tests that source this file
REPO=$(cd -- "$SCRIPTS/../.." && pwd)
PG=${BMS_SCRIPTS_PG:-127.0.0.1:54329}
PG_HOST=${PG%:*}
PG_PORT=${PG##*:}
FAILURES=0

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
fail() { printf '  FALLO: %s\n' "$*" >&2; FAILURES=$((FAILURES + 1)); }
pass() { printf '  ok: %s\n' "$*"; }
check() {
    local description=$1
    shift
    if "$@"; then pass "$description"; else fail "$description"; fi
}
equals() {
    local description=$1 expected=$2 actual=$3
    if [[ $expected == "$actual" ]]; then pass "$description"; else fail "$description (esperado [$expected], obtenido [$actual])"; fi
}
finish() {
    (( FAILURES == 0 )) || { printf '%s fallos\n' "$FAILURES" >&2; exit 1; }
}

# Superuser psql of the test server (what update.sh runs as runuser -u postgres).
bms_pg_super() {
    local database=$1
    shift
    PGOPTIONS='-c client_min_messages=warning' psql -X -q -v ON_ERROR_STOP=1 -h "$PG_HOST" -p "$PG_PORT" -U postgres -d "$database" "$@"
}
sql() { bms_pg_super "$1" -At -c "$2"; }
pg_available() { psql -X -h "$PG_HOST" -p "$PG_PORT" -U postgres -d postgres -Atc 'SELECT 1' >/dev/null 2>&1; }
strip_tx() { python3 -c 'import sys; s=open(sys.argv[1]).read().strip(); print(s[len("BEGIN;"):-len("COMMIT;")])' "$1"; }
