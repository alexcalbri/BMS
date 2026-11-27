#!/usr/bin/env bash
# shellcheck disable=SC2016 # literal text searched in update.sh
# Syntax and shellcheck of every Ubuntu script (Package C's core-update.sh and mirror.sh: syntax only), and the
# lines of update.sh other tests read (ActivityIntegrityPostgresTest replays its tenant V011 grants).
set -uo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"
for script in "$SCRIPTS"/*.sh "$SCRIPTS"/tests/*.sh; do
    check "bash -n ${script#"$SCRIPTS"/}" bash -n "$script"
done
if command -v shellcheck >/dev/null 2>&1; then
    for script in "$SCRIPTS"/*.sh "$SCRIPTS"/tests/*.sh; do
        case ${script##*/} in core-update.sh|mirror.sh) continue ;; esac
        check "shellcheck ${script#"$SCRIPTS"/}" shellcheck -x -P SCRIPTDIR "$script"
    done
else
    printf '  (omitido shellcheck: no esta instalado)\n'
fi
check 'update.sh aplica V011 a cada empresa' grep -qF 'apply_migration "$tenant_db" tenant database/tenant/migrations/V011__create_activity_log.sql' "$SCRIPTS/update.sh"
equals 'update.sh: dos GRANT del registro de actividad' 2 "$(sed -n '/^-- Activity log (tenant V011/,/^SQL$/p' "$SCRIPTS/update.sh" | grep -c '^GRANT')"
check 'update.sh no migra empresas como superusuario' bash -c '! grep -nE "runuser -u postgres -- psql[^|]*tenant_db" "$1"' _ "$SCRIPTS/update.sh"
finish
