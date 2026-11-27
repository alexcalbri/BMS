#!/usr/bin/env bash
# Tests of the Ubuntu scripts. Run: bash scripts/ubuntu/tests/run.sh
#   BMS_SCRIPTS_PG=host:port   a DISPOSABLE PostgreSQL 16+ reachable as superuser "postgres" without password
#                              (default 127.0.0.1:54329); the tests create and drop bms_seca_t* databases and seca_t_*
#                              roles only. Without it reachable, the PostgreSQL tests are skipped.
# Needs: bash, shellcheck (lint), psql, python3, nginx (config test; skipped when missing).
set -uo pipefail
TESTS_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
export TESTS_DIR
status=0
for test in "$TESTS_DIR"/*_test.sh; do
    printf '== %s\n' "${test##*/}"
    if bash "$test"; then
        printf 'OK  %s\n' "${test##*/}"
    else
        printf 'FAIL %s\n' "${test##*/}"
        status=1
    fi
done
exit "$status"
