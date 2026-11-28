#!/usr/bin/env bash
# Helpers of the script tests (sourced).
# The tests compare and sort as in C (locale_test.sh runs the scripts' code under other locales on purpose).
export LC_ALL=C.UTF-8
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

# The transport of the code under test (db-roles.sh asks every caller for exactly one): psql as the superuser of the test
# server over TCP, written like the production transports of update.sh, install.sh and preflight.sh: NO -q, NO -X,
# NO ON_ERROR_STOP, NO PGOPTIONS. It must stay that way: the live failure ("role SET does not exist") hid for as long
# as the test transport silenced the command tags that the production one printed. bms_pg_query and bms_pg_exec add the
# flags, in db-roles.sh only. (client_min_messages only quiets server NOTICEs, which go to stderr; it never changes
# what psql prints on stdout.)
bms_pg_transport() {
    local database=$1
    shift
    PGOPTIONS='-c client_min_messages=warning' psql -h "$PG_HOST" -p "$PG_PORT" -U postgres -d "$database" "$@"
}
# The tests' own superuser psql, for preparing and checking things (not the code under test): quiet, stops on errors.
psu() {
    local database=$1
    shift
    PGOPTIONS='-c client_min_messages=warning' psql -X -q -v ON_ERROR_STOP=1 -h "$PG_HOST" -p "$PG_PORT" -U postgres -d "$database" "$@"
}
sql() { psu "$1" -At -c "$2"; }
pg_available() { psql -X -h "$PG_HOST" -p "$PG_PORT" -U postgres -d postgres -Atc 'SELECT 1' >/dev/null 2>&1; }
strip_tx() { python3 -c 'import sys; s=open(sys.argv[1]).read().strip(); print(s[len("BEGIN;"):-len("COMMIT;")])' "$1"; }
