#!/usr/bin/env bash
# shellcheck disable=SC2016 # literal text searched in update.sh
# Syntax and shellcheck of every Ubuntu script, and the
# lines of update.sh other tests read (ActivityIntegrityPostgresTest replays its tenant V011 grants).
set -uo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"
for script in "$SCRIPTS"/*.sh "$SCRIPTS"/tests/*.sh; do
    check "bash -n ${script#"$SCRIPTS"/}" bash -n "$script"
done
if command -v shellcheck >/dev/null 2>&1; then
    for script in "$SCRIPTS"/*.sh "$SCRIPTS"/tests/*.sh; do
        check "shellcheck ${script#"$SCRIPTS"/}" shellcheck -x -P SCRIPTDIR "$script"
    done
else
    printf '  (omitido shellcheck: no esta instalado)\n'
fi
check 'update.sh aplica a cada empresa las migraciones tenant desde V006 (V011 incluida)' grep -qF 'apply_migration "$tenant_db" tenant "$migration"' "$SCRIPTS/update.sh"
# install.sh lists the control migrations one by one: every file of the tree must be required and applied.
for file in "$REPO"/database/control/migrations/V*.sql; do
    name=database/control/migrations/${file##*/}
    check "install.sh requiere y aplica $name" bash -c 'grep -qx "    $1" "$2" && grep -qxF "apply_migration \"\$DB_NAME\" control $1" "$2"' _ "$name" "$SCRIPTS/install.sh"
done
# update.sh requires every tenant migration its loop applies.
for file in "$REPO"/database/tenant/migrations/V*.sql; do
    [[ ${file##*/} > V006 ]] || continue
    check "update.sh requiere ${file##*/}" grep -qF "database/tenant/migrations/${file##*/} \\" "$SCRIPTS/update.sh"
done
equals 'update.sh: dos GRANT del registro de actividad' 2 "$(sed -n '/^-- Activity log (tenant V011/,/^SQL$/p' "$SCRIPTS/update.sh" | grep -c '^GRANT')"
check 'update.sh no migra empresas como superusuario' bash -c '! grep -nE "runuser -u postgres -- psql[^|]*tenant_db" "$1"' _ "$SCRIPTS/update.sh"

# psql output (db-roles.sh, "THE ONE RULE ABOUT psql OUTPUT"): the flags that keep command tags out of captured output live
# in bms_pg_query and bms_pg_exec only.
check 'ningun script usa ya bms_pg_super (cada transporte es bms_pg_transport)' bash -c '! grep -nE "^[^#]*\bbms_pg_super\b" "$1"/*.sh "$1"/tests/*.sh | grep -v "lint_test.sh"' _ "$SCRIPTS"
check 'bms_pg_transport solo lo llaman bms_pg_query y bms_pg_exec' bash -c '[[ $(grep -hE "^[^#]*bms_pg_transport \"" "$1"/db-roles.sh "$1"/rehearse.sh | wc -l) == 2 ]]' _ "$SCRIPTS"
check 'nunca se captura la salida de bms_pg_exec' bash -c '! grep -nE "\$\([^)]*bms_pg_exec" "$1"/*.sh' _ "$SCRIPTS"
check 'todo psql cuya salida se captura lleva -q (o es bms_pg_query)' python3 - "$SCRIPTS" <<'PY'
import os, re, sys
bad = []
for name in sorted(os.listdir(sys.argv[1])):
    if not name.endswith('.sh'):
        continue
    for number, line in enumerate(open(os.path.join(sys.argv[1], name), encoding='utf-8'), 1):
        code = line.strip()
        if code.startswith('#') or '$(' not in code and '`' not in code:
            continue
        if re.search(r'\$\([^)]*\bpsql\b', code) and not re.search(r'\s-[A-Za-z]*q|--quiet', code):
            bad.append(f'{name}:{number}: {code}')
for line in bad:
    print('    ' + line, file=sys.stderr)
sys.exit(1 if bad else 0)
PY

# Locale (tests/locale_test.sh): no check may depend on the locale the server runs the scripts with (the update that
# rejected a valid 64-hex password ran under LANG=en_US.UTF-8, where [!-~] is not printable ASCII). The agreed protection:
#   1. every entry point fixes its locale at the top (LC_ALL=C.UTF-8, or C when missing);
#   2. every [[ =~ ]] goes through bms_ascii_match (LC_ALL=C for that one test); the only =~ is its definition, and a
#      file that calls it defines it (a sourced library never relies on its caller);
#   3. no glob or case pattern uses a range ([a-z]...): spell the characters out;
#   4. in the sourced libraries, sort, and grep/sed/awk/tr/find with a range or a character class, run with LC_ALL=C.
check 'cada regla de locale se cumple' python3 "$SCRIPTS/tests/locale_lint.py" "$SCRIPTS"
finish
