#!/usr/bin/env bash
# shellcheck disable=SC2016 # eval bodies expand later on purpose
# dist.sh: root only extracts archives with plain files and directories inside the target (the CI web bundle, the
# release migrations, build output), and the release migrations come from the git tree, not a writable checkout.
set -uo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"
ROOT_STATE_DIR=$(mktemp -d)
trap 'rm -rf -- "$ROOT_STATE_DIR"' EXIT
# shellcheck source=../dist.sh
source "$SCRIPTS/dist.sh"
W=$ROOT_STATE_DIR
mkdir -p "$W/src/sub" "$W/ok" "$W/link" "$W/dots"
printf 'hola\n' > "$W/src/sub/index.html"
tar -C "$W/src" -czf "$W/good.tar.gz" .
check 'un paquete normal se extrae' bms_extract_checked "$W/good.tar.gz" "$W/ok" -z
check 'con su contenido' test -f "$W/ok/sub/index.html"
ln -s /etc/passwd "$W/src/passwd"
tar -C "$W/src" -czf "$W/link.tar.gz" .
check 'un enlace simbolico se rechaza' eval '! bms_extract_checked "$W/link.tar.gz" "$W/link" -z'
check 'y no se extrae nada' test -z "$(ls -A "$W/link")"
rm -f "$W/src/passwd"
python3 - "$W/dots.tar" <<'PY'
import io, sys, tarfile
with tarfile.open(sys.argv[1], "w") as tar:
    data = b"x"
    info = tarfile.TarInfo("../fuera.txt"); info.size = len(data)
    tar.addfile(info, io.BytesIO(data))
PY
check 'una ruta que sale del directorio se rechaza' eval '! bms_extract_checked "$W/dots.tar" "$W/dots"'
check 'y no se escribe fuera' test ! -e "$W/fuera.txt"
mkdir -p "$W/migrations"
check 'migraciones de la version desde git' bms_release_migrations "$REPO" HEAD "$W/migrations"
check 'incluye las del Core y las de los modulos' bash -c "test -f '$W/migrations/database/tenant/migrations/V011__create_activity_log.sql' && ls '$W'/migrations/modules/*/migrations/*.sql >/dev/null"
# Every migration the server reads from MIGRATIONS_ROOT is in the copy: the Core's own (database/..., read by
# CompanyProvisioningService) and the migrationPaths of the modules compiled into the distribution (their jar does not
# carry them; packaged modules and extensions read theirs from their own jar).
missing=$(grep -rhoE '"(database/(core|control|tenant)/migrations|modules/[a-z0-9_]+/migrations)/V[0-9]{3}__[A-Za-z0-9_]+\.sql"' \
        "$REPO/server/src/main/kotlin" "$REPO"/modules/*/server/src/main/kotlin | tr -d '"' | sort -u |
    while IFS= read -r path; do [[ -f $W/migrations/$path ]] || printf '%s ' "$path"; done)
equals 'cada migracion que lee el servidor esta en la copia' '' "$missing"
equals 'y nada mas que SQL y README' 0 "$(find "$W/migrations" -type f ! -name '*.sql' ! -name 'README.md' | wc -l)"
finish
