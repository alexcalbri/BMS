#!/usr/bin/env bash
# Update an existing BMS Ubuntu installation created by install.sh.
#
# Order (SELF_HOSTING.md section 15): build and test first (as the build user, nothing on the server changes if it
# fails); then a pg_dump of the control and company databases (root-only, kept with the other backups); then the
# service stops, the configuration, PostgreSQL roles and migrations are brought up to date and the new release is
# started. If anything fails from the stop on, or the new release does not answer, the previous binaries, unit
# settings and role memberships come back automatically and the service starts again; restoring the databases is
# only offered (the commands are printed), never automatic.
if [ -z "${BASH_VERSION:-}" ]; then
    if command -v bash >/dev/null 2>&1 && [ -f "$0" ]; then
        exec bash -- "$0" "$@"
    fi
    printf '%s\n' 'Este actualizador requiere Bash. Ejecuta: sudo bash update.sh' >&2
    exit 1
fi

set -Eeuo pipefail
set +x
umask 077

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
warn() { printf 'AVISO: %s\n' "$*" >&2; }

ask() {
    local value
    read -r -p "$2 [$3]: " value </dev/tty
    printf -v "$1" '%s' "${value:-$3}"
}

git_source() {
    runuser -u bms -- git -C "$SOURCE_DIR" "$@"
}

[[ $EUID -eq 0 ]] || die 'Ejecuta con sudo bash update.sh.'

# Modo no interactivo (lo usa bms-core-update): sin preguntas, commit exacto y destino fijos.
NON_INTERACTIVE=false
ARG_COMMIT=''
ARG_TARGET=''
HARDEN_HOST=false
while (( $# > 0 )); do
    case "$1" in
        --non-interactive) NON_INTERACTIVE=true; shift ;;
        --commit) [[ $# -ge 2 ]] || die '--commit requiere un valor.'; ARG_COMMIT=$2; shift 2 ;;
        --target) [[ $# -ge 2 ]] || die '--target requiere un valor.'; ARG_TARGET=$2; shift 2 ;;
        --harden-host) HARDEN_HOST=true; shift ;;
        *) die "Argumento desconocido: $1" ;;
    esac
done
# Se reconstruyen para el re-exec bajo flock.
SCRIPT_ARGS=()
if [[ $NON_INTERACTIVE == true ]]; then
    [[ $ARG_COMMIT =~ ^[0-9a-f]{40}$ ]] || die '--commit debe ser un sha completo de 40 caracteres hexadecimales.'
    case "$ARG_TARGET" in
        server|webapp|ambos) ;;
        *) die '--target debe ser server, webapp o ambos.' ;;
    esac
    [[ $HARDEN_HOST == false ]] || die '--harden-host solo se usa en modo interactivo.'
    SCRIPT_ARGS=(--non-interactive --commit "$ARG_COMMIT" --target "$ARG_TARGET")
else
    [[ -z $ARG_COMMIT && -z $ARG_TARGET ]] || die '--commit y --target solo se usan con --non-interactive.'
    [[ -r /dev/tty ]] || die 'Se necesita una terminal interactiva; usa ssh -t.'
    [[ $HARDEN_HOST == false ]] || SCRIPT_ARGS=(--harden-host)
fi

# One install or update at a time (install.sh takes the same lock): the script runs again under flock,
# which holds the lock until it ends.
# -o keeps the lock out of child processes, so a Gradle daemon left running cannot keep it held.
readonly MAINTENANCE_LOCK_FILE=/run/bms-maintenance.lock
readonly MAINTENANCE_LOCK_BUSY=75
if [[ ${BMS_MAINTENANCE_LOCKED:-} != 1 ]]; then
    [[ -f $0 ]] || die 'Descarga update.sh y ejecutalo como archivo: sudo bash update.sh'
    command -v flock >/dev/null 2>&1 || die 'No se encontro flock (paquete util-linux).'
    lock_status=0
    flock -n -o -E "$MAINTENANCE_LOCK_BUSY" "$MAINTENANCE_LOCK_FILE" \
        env BMS_MAINTENANCE_LOCKED=1 bash -- "$0" ${SCRIPT_ARGS[@]+"${SCRIPT_ARGS[@]}"} || lock_status=$?
    (( lock_status != MAINTENANCE_LOCK_BUSY )) || die 'Ya hay una instalacion o actualizacion de BMS en curso. Espera a que termine.'
    exit "$lock_status"
fi

readonly APP_DIR=/opt/bms
readonly SOURCE_DIR=$APP_DIR/source
readonly APP_RUNTIME_DIR=$APP_DIR/app
readonly WEB_RUNTIME_DIR=$APP_DIR/web
# Verdadero si la ruta, ya resuelta (sin enlaces), y cada directorio hasta / son de root y no son
# escribibles por grupo ni otros (los directorios con sticky bit tipo /tmp tampoco sirven).
is_root_private() {
    local path info
    path=$(realpath -e -- "$1") || return 1
    while :; do
        info=$(stat -c '%u %a' -- "$path") || return 1
        [[ ${info%% *} == 0 ]] || return 1
        (( (8#${info##* } & 8#022) == 0 )) || return 1
        [[ $path != / ]] || return 0
        path=${path%/*}
        path=${path:-/}
    done
}

# Verdadero si es un archivo regular (tras resolver enlaces) y toda su ruta es de root.
is_root_private_file() {
    local resolved
    resolved=$(realpath -e -- "$1") || return 1
    [[ -f $resolved && ! -L $resolved ]] && is_root_private "$resolved"
}

# Todo lo que se ejecuta o se lee como root/postgres (migrations.sh, SQL, helpers) sale de TRUSTED_DIR.
# En modo manual es el espejo de root (/var/lib/bms/mirror), que se asigna tras elegir la referencia;
# en modo no interactivo es obligatorio y debe ser un directorio de root que nadie mas pueda escribir.
TRUSTED_DIR=${BMS_TRUSTED_DIR:-}
if [[ $NON_INTERACTIVE == true ]]; then
    [[ -n ${BMS_TRUSTED_DIR:-} ]] || die 'BMS_TRUSTED_DIR es obligatorio en modo no interactivo.'
    [[ $TRUSTED_DIR == /* && -d $TRUSTED_DIR ]] || die 'BMS_TRUSTED_DIR debe ser una ruta absoluta existente.'
    [[ $(realpath -- "$TRUSTED_DIR") != "$(realpath -- "$SOURCE_DIR")" ]] ||
        die 'BMS_TRUSTED_DIR no puede ser el directorio de codigo fuente.'
    is_root_private "$TRUSTED_DIR" ||
        die 'BMS_TRUSTED_DIR (y cada directorio que lo contiene) debe ser de root y no escribible por grupo u otros.'
    [[ -f $TRUSTED_DIR/scripts/ubuntu/migrations.sh && -f $TRUSTED_DIR/scripts/ubuntu/core-update.sh ]] ||
        die 'BMS_TRUSTED_DIR no contiene los scripts de BMS.'
    readonly TRUSTED_DIR
fi

# El script que corre como root debe estar en un lugar que solo root pueda modificar.
SCRIPT_PATH=$(realpath -- "$0")
SCRIPT_DIR=${SCRIPT_PATH%/*}
if ! is_root_private_file "$SCRIPT_PATH"; then
    die "update.sh esta en $SCRIPT_DIR, una ruta que no es solo de root (o es un enlace) y podria haber sido alterada. Descarga update.sh y mirror.sh desde https://raw.githubusercontent.com/alexcalbri/BMS/master/scripts/ubuntu/ a un directorio de root (por ejemplo /root/bms-update) y ejecuta sudo bash /root/bms-update/update.sh."
fi
readonly CONFIG_FILE=/etc/bms/server.env
readonly SERVICE_NAME=bms.service
readonly NGINX_SITE=/etc/nginx/sites-available/bms
readonly ACME_ROOT=/var/www/bms-acme
# Todo lo que escribe root vive fuera de lo que bms puede modificar: /opt/bms es de root.
readonly ROOT_STATE_DIR=/var/lib/bms
readonly BACKUP_ROOT=$ROOT_STATE_DIR/backups
readonly NGINX_BACKUP_DIR=$ROOT_STATE_DIR/nginx-backups
readonly BUILD_LOG_DIR=/var/log/bms-update
readonly UPDATE_SWAP_FILE=$ROOT_STATE_DIR/update.swap
readonly LEGACY_SWAP_FILE=$APP_DIR/update.swap
readonly APP_HOME_DIR=$APP_DIR/home
# Usuario que compila (A3 de SECURITY_FIXES.md): no es el del servicio, asi el codigo de la compilacion (plugins y
# dependencias de Gradle, Node) no puede leer el entorno ni los archivos del servidor en marcha.
readonly DEFAULT_BUILD_USER=bms-build
readonly BUILD_HOME_DIR=$APP_DIR/build-home
# Se compila en un arbol nuevo (lo escribe el usuario de compilacion, root no lo lee directamente).
readonly BUILD_TREE=$APP_DIR/build-tree
readonly KEEP_BACKUPS=5
readonly KEEP_DB_DUMPS=3
readonly KEEP_ENV_BACKUPS=3
readonly KEEP_LOGS=10
readonly DEFAULT_PROVISIONER=bms_provisioner
readonly WEB_DIST_PUBLIC_KEY=/usr/local/lib/bms/web_dist_public.pem
# Same limits as install.sh: the production webpack build needs extra memory for Node.
readonly UPDATE_SWAP_SIZE_MB=4096
readonly GRADLE_JVM_ARGS="-Xmx1536M -Dfile.encoding=UTF-8"
readonly NODE_OPTIONS_VALUE="--max-old-space-size=3072"
readonly APP_PORT=8080
UPDATE_SWAP_CREATED=false
PROVISIONER_PASSFILE=''

# Where a failed update is, for the automatic rollback (on_update_exit).
#   prepare  nothing on the server changed yet          stopped  the service is stopped, binaries untouched
#   swapped  the new binaries are in place              done     the new release answers
STAGE=prepare
LAYOUT_CHANGED=false
APP_SWAPPED=false
WEB_SWAPPED=false
DROPIN_BACKUP=''
PREVIOUS_SOURCE_COMMIT=''
BACKUP_DIR=''
BUILD_USER=$DEFAULT_BUILD_USER
BUILD_HOME=$BUILD_HOME_DIR
BUILD_DIR=$BUILD_TREE
CI_WEB_DIR=''
WEB_DIST_ROOT_OWNED=false
PROVISIONER=''
readonly DEPLOYED_FILE=$ROOT_STATE_DIR/deployed-commit
readonly ALLOWED_SIGNERS=/usr/local/lib/bms/allowed_signers

# shellcheck disable=SC2329 # called from the EXIT trap
cleanup_update_swap() {
    if [[ $UPDATE_SWAP_CREATED == true ]]; then
        swapoff "$UPDATE_SWAP_FILE" >/dev/null 2>&1 || true
        rm -f "$UPDATE_SWAP_FILE"
    fi
}

recover_stale_update_swap() {
    [[ -e $UPDATE_SWAP_FILE ]] || return 0
    printf 'Se encontro swap temporal de una actualizacion anterior interrumpida; se recupera.\n'
    if awk 'NR > 1 { print $1 }' /proc/swaps | grep -Fxq "$UPDATE_SWAP_FILE"; then
        swapoff "$UPDATE_SWAP_FILE"
    fi
    rm -f "$UPDATE_SWAP_FILE"
}

ensure_update_swap() {
    local available_kb total_swap_kb
    # Before measuring: a leftover active swap would count as available and then never be removed.
    recover_stale_update_swap
    available_kb=$(awk '/MemAvailable:/ { print $2 }' /proc/meminfo)
    total_swap_kb=$(awk '/SwapTotal:/ { print $2 }' /proc/meminfo)

    if (( available_kb >= 2097152 || total_swap_kb >= 1048576 )); then
        return
    fi

    printf 'Memoria limitada detectada; se creara swap temporal de %s MiB para compilar.\n' "$UPDATE_SWAP_SIZE_MB"
    if command -v fallocate >/dev/null 2>&1; then
        fallocate -l "${UPDATE_SWAP_SIZE_MB}M" "$UPDATE_SWAP_FILE" ||
            dd if=/dev/zero of="$UPDATE_SWAP_FILE" bs=1M count="$UPDATE_SWAP_SIZE_MB" status=none
    else
        dd if=/dev/zero of="$UPDATE_SWAP_FILE" bs=1M count="$UPDATE_SWAP_SIZE_MB" status=none
    fi
    chmod 600 "$UPDATE_SWAP_FILE"
    mkswap "$UPDATE_SWAP_FILE" >/dev/null
    swapon "$UPDATE_SWAP_FILE"
    UPDATE_SWAP_CREATED=true
}

# The build runs as the build user with a clean environment (nothing of root's), no user-level Gradle, Maven or npm
# settings and no local build cache: a build can only use what the tree and the dependency caches give it.
run_gradle() {
    clean_build_home_config
    # HOME explicito: /opt/bms es de root; el usuario de compilacion tiene su propio directorio.
    runuser -u "$BUILD_USER" -- env -i \
        PATH=/usr/local/bin:/usr/bin:/bin \
        LANG=C.UTF-8 \
        HOME="$BUILD_HOME" \
        GRADLE_USER_HOME="$BUILD_HOME/.gradle" \
        JAVA_HOME="$UPDATE_JAVA_HOME" \
        NODE_OPTIONS="$NODE_OPTIONS_VALUE" \
        bash ./gradlew \
        --no-daemon \
        --no-configuration-cache \
        --no-build-cache \
        --max-workers=1 \
        --console=plain \
        "-Dorg.gradle.jvmargs=$GRADLE_JVM_ARGS" \
        "-Dorg.gradle.caching=false" \
        "-Pkotlin.compiler.execution.strategy=in-process" \
        "$@"
}

# Settings a build could have left in the build user's home for the next one (init scripts, gradle.properties,
# repository mirrors, the local build cache) are removed before each build, as that user (no link is followed by root).
clean_build_home_config() {
    runuser -u "$BUILD_USER" -- rm -rf --one-file-system -- \
        "$BUILD_HOME/.gradle/gradle.properties" "$BUILD_HOME/.gradle/init.d" "$BUILD_HOME/.gradle/init.gradle" \
        "$BUILD_HOME/.gradle/init.gradle.kts" "$BUILD_HOME/.gradle/caches/build-cache-1" "$BUILD_HOME/.m2/settings.xml" \
        "$BUILD_HOME/.npmrc" "$BUILD_HOME/.yarnrc" "$BUILD_HOME/.yarnrc.yml" "$BUILD_HOME/.gitconfig" "$BUILD_HOME/.config" \
        "$BUILD_HOME/.bashrc" "$BUILD_HOME/.profile" 2>/dev/null || true
}

# No process of the build user may outlive a build step (a daemon or a forked helper could change the output after
# root checked it): every one is killed before root reads anything the build produced. With BMS_BUILD_USER=bms the
# build user is the service user, whose processes cannot be killed: then only the Gradle daemons are stopped.
stop_build_user_processes() {
    if [[ $BUILD_USER == bms ]]; then
        runuser -u bms -- env -i PATH=/usr/bin:/bin HOME="$BUILD_HOME" GRADLE_USER_HOME="$BUILD_HOME/.gradle" \
            JAVA_HOME="$UPDATE_JAVA_HOME" bash "$BUILD_DIR/gradlew" -p "$BUILD_DIR" --stop >/dev/null 2>&1 || true
        return 0
    fi
    pkill -KILL -u "$BUILD_USER" 2>/dev/null || true
    for _ in {1..100}; do
        pgrep -u "$BUILD_USER" >/dev/null 2>&1 || return 0
        sleep 0.1
    done
    die "Siguen corriendo procesos de $BUILD_USER despues de la compilacion; no se usa lo compilado."
}

config_has_key() {
    local key=$1
    grep -Eq "^${key}=" "$CONFIG_FILE"
}

config_value() {
    local key=$1
    awk -F= -v key="$key" '$1 == key { sub(/^[^=]*=/, ""); print; exit }' "$CONFIG_FILE"
}

# Sets [key] to [value], replacing the line when the key exists (the copy of server.env in the backup is restored on a
# rollback).
set_config_value() {
    local key=$1 value=$2
    if ! config_has_key "$key"; then
        append_config_value "$key" "$value"
        return
    fi
    [[ $(config_value "$key") == "$value" ]] && return
    awk -v key="$key" -v value="$value" -F= '$1 == key { print key "=" value; next } { print }' "$CONFIG_FILE" > "$CONFIG_FILE.tmp"
    chmod 600 "$CONFIG_FILE.tmp"
    mv -f -- "$CONFIG_FILE.tmp" "$CONFIG_FILE"
    printf 'Configuracion cambiada en server.env: %s\n' "$key"
}

append_config_value() {
    local key=$1
    local value=$2
    if config_has_key "$key"; then
        return
    fi
    printf '%s=%s\n' "$key" "$value" >> "$CONFIG_FILE"
    printf 'Configuracion agregada en server.env: %s\n' "$key"
}

# Conserva solo los N mas recientes (por fecha de modificacion) de un patron dentro de un directorio.
prune_old() {
    local dir=$1 pattern=$2 keep=$3 item
    while IFS= read -r item; do
        rm -rf --one-file-system -- "$item"
    done < <(find "$dir" -mindepth 1 -maxdepth 1 -name "$pattern" -printf '%T@ %p\n' | sort -rn | tail -n "+$((keep + 1))" | cut -d' ' -f2-)
}

configure_server_env() {
    local db_url tenant_prefix
    install -d -m 755 "$APP_DIR/company-backups"
    chown bms:bms "$APP_DIR/company-backups"
    chmod 700 "$APP_DIR/company-backups"
    install -d -m 700 "$APP_DIR/server-modules"
    chown bms:bms "$APP_DIR/server-modules"
    # El servidor guarda los paquetes de extension en la carpeta hermana de server-modules.
    install -d -m 700 "$APP_DIR/server-extensions"
    chown bms:bms "$APP_DIR/server-extensions"
    install -d -m 700 "$APP_DIR/company-files"
    chown bms:bms "$APP_DIR/company-files"

    db_url=$(config_value DB_URL)
    [[ -n $db_url ]] || die 'DB_URL no existe en /etc/bms/server.env.'
    tenant_prefix="${db_url%/*}/"
    [[ $tenant_prefix != "$db_url/" ]] || die 'No se pudo inferir TENANT_JDBC_URL_PREFIX desde DB_URL.'

    # Copia de server.env antes de tocarla: solo root, se conservan las ultimas tres.
    install -m 600 -o root -g root "$CONFIG_FILE" "$CONFIG_FILE.update-backup.$(date -u +%Y%m%dT%H%M%SZ)"
    append_config_value TENANT_DATABASES_JSON '[]'
    append_config_value TENANT_JDBC_URL_PREFIX "$tenant_prefix"
    # M5: the server reads the company migrations from a root-owned copy shipped with the release, never from
    # /opt/bms/source (which the service user can write, and whose SQL would later run as the owner's role).
    set_config_value MIGRATIONS_ROOT "$APP_RUNTIME_DIR/migrations"
    append_config_value COMPANY_BACKUPS_ROOT "$APP_DIR/company-backups"
    append_config_value COMPANY_FILES_ROOT "$APP_DIR/company-files"
    append_config_value MODULE_CATALOG_URL 'https://github.com/alexcalbri/BMS/tree/master/modules'
    append_config_value MODULE_PACKAGES_ROOT "$APP_DIR/server-modules"
    append_config_value MODULE_GITHUB_TOKEN ''
    # Instalar extensiones desde un ZIP o una URL queda apagado por seguridad; pon true y reinicia bms para activarlo.
    append_config_value EXTENSION_UPLOAD_ENABLED false
    append_config_value VERSION_CATALOG_URL ''
    # Llave maestra de los secretos cifrados: se genera una sola vez y nunca se regenera.
    if ! config_has_key BMS_MASTER_KEY; then
        append_config_value BMS_MASTER_KEY "$(openssl rand -base64 32)"
        printf 'Respalda BMS_MASTER_KEY (%s) fuera del servidor: sin ella no se pueden leer los secretos guardados (SELF_HOSTING.md, seccion 14).\n' "$CONFIG_FILE"
    fi
    append_config_value CORE_UPDATE_REF master
    if [[ -f $NGINX_SITE ]] && grep -Eq 'listen[[:space:]]+443' "$NGINX_SITE"; then
        append_config_value PUBLIC_SCHEME https
    else
        append_config_value PUBLIC_SCHEME http
    fi
    # Base publica fija de los enlaces en correos (cancelar la recuperacion por correo, TWO_FACTOR.md) y de los metadatos
    # OAuth de agentes; evita confiar en la cabecera Host del cliente. Se toma del sitio de Nginx de BMS (dominio validado).
    if [[ -f $NGINX_SITE ]] && ! config_has_key BMS_PUBLIC_BASE_URL; then
        local site_scheme site_domain
        if read -r site_scheme site_domain _ < <(bms_nginx_site_params "$NGINX_SITE"); then
            append_config_value BMS_PUBLIC_BASE_URL "${site_scheme}://${site_domain}"
        else
            warn "no se pudo leer el dominio de $NGINX_SITE; BMS_PUBLIC_BASE_URL queda sin definir (los enlaces usan el Host validado)."
        fi
    fi
    append_config_value ACME_EMAIL ''
    append_config_value SMTP_HOST ''
    append_config_value SMTP_PORT 587
    append_config_value SMTP_USERNAME ''
    append_config_value SMTP_PASSWORD ''
    append_config_value SMTP_FROM ''
    append_config_value SMTP_SECURITY starttls
    # Login propio del aprovisionamiento de empresas (SELF_HOSTING.md, Roles de PostgreSQL).
    if ! config_has_key DB_PROVISIONING_USER; then
        [[ $(pg_super_count "SELECT count(*) FROM pg_catalog.pg_roles WHERE rolname = '$DEFAULT_PROVISIONER'") == 0 ]] ||
            die "Ya existe un rol PostgreSQL $DEFAULT_PROVISIONER que no figura en server.env. Si es de BMS, agrega DB_PROVISIONING_USER=$DEFAULT_PROVISIONER y DB_PROVISIONING_PASSWORD (la de ese rol) a server.env; si no, renombralo. No se cambio nada."
        append_config_value DB_PROVISIONING_PASSWORD "$(openssl rand -hex 32)"
        append_config_value DB_PROVISIONING_USER "$DEFAULT_PROVISIONER"
    fi
    append_config_value DB_PROVISIONING_PASSWORD "$(openssl rand -hex 32)"
    chown root:root "$CONFIG_FILE"
    chmod 600 "$CONFIG_FILE"
    local backup
    for backup in "$CONFIG_FILE".update-backup.*; do
        [[ -f $backup && ! -L $backup ]] && chmod 600 -- "$backup"
    done
    prune_old /etc/bms 'server.env.update-backup.*' "$KEEP_ENV_BACKUPS"
}

# The unit uses ProtectSystem=strict; backups, company files and module packages need write access.
configure_service_writable_paths() {
    local dropin_dir=/etc/systemd/system/$SERVICE_NAME.d
    local dropin=$dropin_dir/writable-paths.conf
    local expected
    expected=$(printf '[Service]\nReadWritePaths=%s %s %s %s\n' "$APP_DIR/company-backups" "$APP_DIR/company-files" "$APP_DIR/server-modules" "$APP_DIR/server-extensions")
    if [[ -f $dropin && $(cat "$dropin") == "$expected" ]]; then
        return
    fi
    install -d -m 755 "$dropin_dir"
    printf '%s\n' "$expected" > "$dropin"
    chmod 644 "$dropin"
    printf 'Servicio actualizado con rutas escribibles para backups y modulos.\n'
}

# A6: sandbox of bms.service as a drop-in (scripts/ubuntu/units.sh). The previous drop-in directory is kept in the
# update's backup so a failed start can take it back.
configure_service_hardening() {
    local filter memory
    filter=$(config_value BMS_SYSCALL_FILTER)
    memory=$(bms_default_memory_max "$(config_value BMS_MEMORY_MAX)")
    if [[ $(bms_service_hardening "${filter:-false}" "$memory" | bms_write_dropin "$SERVICE_NAME") == changed ]]; then
        printf 'Servicio endurecido (%s.d/hardening.conf).\n' "$SERVICE_NAME"
    fi
    systemd-analyze verify "/etc/systemd/system/$SERVICE_NAME" >/dev/null 2>&1 ||
        warn "systemd-analyze verify da avisos para $SERVICE_NAME; revisa: systemd-analyze verify /etc/systemd/system/$SERVICE_NAME"
}

# psql as the PostgreSQL superuser; SQL on stdin (db-roles.sh calls it).
bms_pg_super() {
    local database=$1
    shift
    runuser -u postgres -- psql -X -v ON_ERROR_STOP=1 -d "$database" "$@"
}

# psql as the provisioning login over TCP with the root-only passfile (db-roles.sh verifies access with it).
bms_pg_as_provisioner() {
    local database=$1
    shift
    PGPASSFILE=$PROVISIONER_PASSFILE PGCONNECT_TIMEOUT=15 psql -X -w -v ON_ERROR_STOP=1 \
        -h "$MIGRATION_HOST" -p "$MIGRATION_PORT" -U "$PROVISIONER" -d "$database" "$@"
}

pg_super_count() {
    runuser -u postgres -- psql -X -At -d postgres -c "$1"
}

# jdbc:postgresql://host:port/database -> "host port"
db_host_port() {
    local url=$1 rest host port
    rest=${url#jdbc:postgresql://}
    rest=${rest%%/*}
    host=${rest%%:*}
    port=5432
    [[ $rest == *:* ]] && port=${rest##*:}
    [[ $host =~ ^[A-Za-z0-9.-]+$ && $port =~ ^[0-9]{1,5}$ ]] || return 1
    printf '%s %s\n' "$host" "$port"
}

# The provisioning login (role and password from server.env) and the passfile tenant migrations use (A2).
ensure_provisioner_role() {
    local password host_port
    PROVISIONER=$(config_value DB_PROVISIONING_USER)
    password=$(config_value DB_PROVISIONING_PASSWORD)
    password=${password#\"}
    password=${password%\"}
    bms_valid_role "$PROVISIONER" || die 'DB_PROVISIONING_USER invalido en server.env.'
    [[ $PROVISIONER != "$DB_USER" ]] || die 'DB_PROVISIONING_USER debe ser distinto de DB_USER.'
    [[ $password =~ ^[!-~]{16,}$ && $password != *:* && $password != *\\* ]] ||
        die 'DB_PROVISIONING_PASSWORD debe tener al menos 16 caracteres ASCII imprimibles, sin ":" ni "\".'
    printf '%s' "$password" | bms_role_set_password "$PROVISIONER" "$BMS_PROVISIONER_ATTRIBUTES" ||
        die "No se pudo crear o actualizar el rol $PROVISIONER."
    host_port=$(db_host_port "$(config_value DB_URL)") || die 'No se pudo leer servidor y puerto de DB_URL.'
    read -r MIGRATION_HOST MIGRATION_PORT <<< "$host_port"
    PROVISIONER_PASSFILE=$(mktemp -p "$ROOT_STATE_DIR" pgpass.XXXXXX)
    chmod 600 "$PROVISIONER_PASSFILE"
    printf '%s:%s:*:%s:%s\n' "$MIGRATION_HOST" "$MIGRATION_PORT" "$PROVISIONER" "$password" > "$PROVISIONER_PASSFILE"
    # Read by migration_psql (migrations.sh).
    # shellcheck disable=SC2034
    MIGRATION_LOGIN=$PROVISIONER
    # shellcheck disable=SC2034
    MIGRATION_PASSFILE=$PROVISIONER_PASSFILE
    unset password
}

# A1: the runtime role stops being a member of the server owner's role (db-roles.sh, idempotent).
migrate_role_layout() {
    LAYOUT_CHANGED=true
    printf 'Organizacion de roles de PostgreSQL (runtime %s, aprovisionamiento %s)...\n' "$DB_USER" "$PROVISIONER"
    bms_migrate_role_layout "$CONTROL_DB" "$DB_USER" "$PROVISIONER" || die 'No se pudo pasar a la nueva organizacion de roles de PostgreSQL.'
}

apply_control_migrations() {
    local migration
    # Rutas relativas de aqui en adelante: SQL y migrations.sh se leen del directorio confiable.
    cd "$TRUSTED_DIR"
    [[ -f scripts/ubuntu/migrations.sh ]] || die 'No se encontro scripts/ubuntu/migrations.sh.'
    for migration in \
        database/core/migrations/V003__add_application_user_profile.sql \
        database/core/migrations/V004__add_application_user_code.sql \
        database/core/migrations/V005__create_notifications.sql \
        database/core/migrations/V006__add_application_user_language.sql \
        database/control/migrations/V015__create_server_admins.sql \
        database/control/migrations/V017__create_two_factor.sql \
        database/control/migrations/V018__create_two_factor_email_recovery.sql \
        database/control/migrations/V019__session_second_factor_mark.sql \
        database/tenant/migrations/V004__create_application_permissions.sql \
        database/tenant/migrations/V005__create_application_user_credentials.sql \
        database/tenant/migrations/V006__create_extensions.sql \
        database/tenant/migrations/V007__create_company_mail_settings.sql \
        database/tenant/migrations/V008__account_reset_throttle.sql \
        database/tenant/migrations/V009__outbound.sql \
        database/tenant/migrations/V010__create_text_translations.sql \
        database/tenant/migrations/V011__create_activity_log.sql \
        database/tenant/migrations/V012__create_two_factor.sql \
        database/tenant/migrations/V013__create_two_factor_email_recovery.sql \
        database/tenant/migrations/V014__session_second_factor_mark.sql \
        modules/clientes/migrations/V002__require_customer_contact_fields.sql \
        modules/clientes/migrations/V003__add_customer_type.sql \
        modules/clientes/migrations/V004__add_customer_code.sql; do
        [[ -f $migration ]] || die "No se encontro $migration."
    done

    # shellcheck source=migrations.sh
    source scripts/ubuntu/migrations.sh
    # Control database (it belongs to the PostgreSQL superuser): every control migration of the tree, in order, so
    # the migrations other packages add (V016 and later) need no new line here.
    MIGRATION_ROLE=''
    while IFS= read -r migration; do
        apply_migration "$CONTROL_DB" control "$migration"
    done < <(find database/control/migrations -maxdepth 1 -type f -name 'V[0-9][0-9][0-9]__*.sql' | sort)
    apply_migration "$CONTROL_DB" core database/core/migrations/V003__add_application_user_profile.sql
    apply_migration "$CONTROL_DB" core database/core/migrations/V004__add_application_user_code.sql
    apply_migration "$CONTROL_DB" core database/core/migrations/V006__add_application_user_language.sql
    migration_psql "$CONTROL_DB" -v ON_ERROR_STOP=1 -v db_user="$DB_USER" -v provisioner="$PROVISIONER" <<'SQL'
GRANT UPDATE (display_name, email) ON application_users TO :"db_user";
GRANT SELECT, UPDATE (user_code) ON application_users TO :"db_user";
GRANT UPDATE (language) ON application_users TO :"db_user";
GRANT SELECT, INSERT, UPDATE, DELETE ON password_reset_codes TO :"db_user";
GRANT SELECT, INSERT, UPDATE, DELETE ON companies TO :"db_user";
GRANT SELECT, INSERT, UPDATE, DELETE ON server_modules TO :"db_user";
GRANT SELECT, INSERT, UPDATE, DELETE ON server_settings TO :"db_user";
GRANT SELECT, INSERT, UPDATE, DELETE ON public_domains TO :"db_user";
GRANT SELECT, INSERT, UPDATE, DELETE ON server_owner_company_sessions TO :"db_user";
GRANT SELECT, INSERT, UPDATE, DELETE ON company_links TO :"db_user";
-- Server administrators (control V015): created by the owner from the app; no PostgreSQL role.
GRANT INSERT (id, postgres_role, is_active, display_name, email) ON application_users TO :"db_user";
GRANT SELECT, INSERT, UPDATE, DELETE ON server_admins, server_admin_permissions, server_admin_companies, server_workspace_staff TO :"db_user";
REVOKE INSERT ON core_update_requests FROM :"db_user";
GRANT SELECT ON core_update_requests TO :"db_user";
GRANT INSERT (target_version, from_version, requested_by) ON core_update_requests TO :"db_user";
GRANT USAGE ON SEQUENCE core_update_requests_id_seq TO :"db_user";
GRANT SELECT, INSERT, UPDATE ON server_extensions, extension_operations, extension_operation_targets TO :"db_user";
GRANT SELECT, INSERT ON extension_unsigned_authorizations TO :"db_user";
GRANT USAGE ON SEQUENCE extension_unsigned_authorizations_id_seq, extension_operations_id_seq TO :"db_user";
-- The server audit is append-only for the runtime role (ACTIVITY_LOG.md section 9).
REVOKE UPDATE, DELETE, TRUNCATE ON provisioning_audit_log FROM :"db_user";
GRANT SELECT, INSERT ON provisioning_audit_log TO :"db_user";
-- Commit pin of core updates (control V016, SIGNING.md), when that migration is part of this release.
SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_attribute WHERE attrelid = 'public.core_update_requests'::regclass
               AND attname = 'target_commit' AND NOT attisdropped) AS has_commit_pin \gset
\if :has_commit_pin
GRANT INSERT (target_version, from_version, requested_by, target_commit) ON core_update_requests TO :"db_user";
\endif
-- Two-step verification of the server owner and administrators (control V017, TWO_FACTOR.md). The data key that seals
-- their secrets is only read and created by the runtime role, never replaced or deleted.
REVOKE UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON tenant_data_keys FROM :"db_user";
GRANT SELECT, INSERT ON tenant_data_keys TO :"db_user";
GRANT SELECT, INSERT, UPDATE, DELETE ON application_user_two_factor, application_user_recovery_codes TO :"db_user";
-- Delayed recovery by email (control V018): the record of every process, never deleted by the runtime role.
REVOKE DELETE, TRUNCATE, REFERENCES, TRIGGER ON application_user_2fa_email_recovery FROM :"db_user";
GRANT SELECT, INSERT, UPDATE ON application_user_2fa_email_recovery TO :"db_user";
SQL
    cd /
}

# A2: company migrations run as the database owner's role through the provisioning login (migrations.sh), never as
# the superuser, and only on databases bms_tenant_databases accepts (name bms_<code>, owned by a server owner role).
apply_tenant_migrations() {
    local tenants tenant_db owner migration
    cd "$TRUSTED_DIR"
    # shellcheck source=migrations.sh
    source scripts/ubuntu/migrations.sh
    tenants=$(bms_tenant_databases "$CONTROL_DB") || die 'No se pudo leer la lista de empresas (companies); no se migro ninguna base.'
    while read -r tenant_db owner; do
        [[ -n $tenant_db ]] || continue
        MIGRATION_ROLE=$owner
        apply_migration "$tenant_db" clientes modules/clientes/migrations/V002__require_customer_contact_fields.sql
        apply_migration "$tenant_db" clientes modules/clientes/migrations/V003__add_customer_type.sql
        apply_migration "$tenant_db" clientes modules/clientes/migrations/V004__add_customer_code.sql
        apply_migration "$tenant_db" tenant database/tenant/migrations/V004__create_application_permissions.sql
        apply_migration "$tenant_db" tenant database/tenant/migrations/V005__create_application_user_credentials.sql
        apply_migration "$tenant_db" core database/core/migrations/V003__add_application_user_profile.sql
        apply_migration "$tenant_db" core database/core/migrations/V005__create_notifications.sql
        apply_migration "$tenant_db" core database/core/migrations/V006__add_application_user_language.sql
        # Tenant V006 and later, every one of the tree in order (they are idempotent; the server's startup catch-up records
        # them under the same module and version, so neither side applies one twice). V001-V005 are not idempotent and
        # stay as above: an old company may lack their history rows.
        while IFS= read -r migration; do
            apply_migration "$tenant_db" tenant "$migration"
        done < <(find database/tenant/migrations -maxdepth 1 -type f -name 'V[0-9][0-9][0-9]__*.sql' | LC_ALL=C sort |
            awk -F/ '{ n = substr($NF, 2, 3) + 0; if (n >= 6) print }')
        migration_psql "$tenant_db" -v ON_ERROR_STOP=1 -v db_user="$DB_USER" <<'SQL'
GRANT SELECT, INSERT, UPDATE ON application_users TO :"db_user";
GRANT SELECT, INSERT, UPDATE, DELETE ON password_reset_codes TO :"db_user";
GRANT SELECT, INSERT, UPDATE, DELETE ON application_permissions, application_user_permissions TO :"db_user";
GRANT SELECT, INSERT, UPDATE, DELETE ON application_user_credentials TO :"db_user";
GRANT SELECT, INSERT, DELETE, UPDATE (read_at) ON notifications TO :"db_user";
-- The company's data key (tenant V006) is only read and created by the runtime role, never replaced or deleted.
REVOKE UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON tenant_data_keys FROM :"db_user";
GRANT SELECT, INSERT ON tenant_data_keys TO :"db_user";
GRANT SELECT, INSERT, UPDATE ON tenant_extensions TO :"db_user";
GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_extension_settings, tenant_extension_objects TO :"db_user";
GRANT SELECT, INSERT, UPDATE ON company_mail_settings TO :"db_user";
GRANT SELECT, INSERT, UPDATE, DELETE ON account_reset_throttle TO :"db_user";
GRANT SELECT, INSERT, UPDATE, DELETE ON outbound_events, outbound_links, integration_connections TO :"db_user";
GRANT USAGE, SELECT ON SEQUENCE outbound_events_id_seq TO :"db_user";
GRANT SELECT, INSERT, UPDATE, DELETE ON text_translations TO :"db_user";
-- Two-step verification of the company's users (tenant V012, TWO_FACTOR.md) and its delayed recovery by email
-- (tenant V013, never deleted by the runtime role).
GRANT SELECT, INSERT, UPDATE, DELETE ON application_user_two_factor, application_user_recovery_codes TO :"db_user";
REVOKE DELETE, TRUNCATE, REFERENCES, TRIGGER ON application_user_2fa_email_recovery FROM :"db_user";
GRANT SELECT, INSERT, UPDATE ON application_user_2fa_email_recovery TO :"db_user";
-- Activity log (tenant V011, ACTIVITY_LOG.md): append-only, the runtime user only reads and appends.
GRANT SELECT, INSERT ON activity_log, activity_log_chain TO :"db_user";
GRANT USAGE ON SEQUENCE activity_log_id_seq TO :"db_user";
SQL
    done <<< "$tenants"
    # shellcheck disable=SC2034
    MIGRATION_ROLE=''
    cd /
}

# Instalaciones anteriores dejaban /opt/bms como home de bms: ese usuario podia plantar enlaces
# simbolicos donde root escribe. Se deja el directorio en manos de root y bms pasa a /opt/bms/home.
# Debe ejecutarse antes de cualquier otra escritura de root en APP_DIR.
harden_app_dir() {
    local entry name legacy_owner
    # Primero se cierra el directorio: desde aqui bms ya no puede crear ni renombrar entradas en el.
    chown root:root "$APP_DIR"
    chmod 755 "$APP_DIR"
    install -d -m 700 -o root -g root "$ROOT_STATE_DIR"

    # Los directorios de bms deben ser directorios reales, no enlaces.
    for name in source company-backups company-files server-modules server-extensions; do
        [[ ! -L $APP_DIR/$name ]] || die "$APP_DIR/$name es un enlace simbolico; no es seguro continuar."
    done

    # Hogar nuevo de bms.
    if [[ -L $APP_HOME_DIR ]]; then
        rm -f -- "$APP_HOME_DIR"
    fi
    if [[ ! -d $APP_HOME_DIR ]]; then
        rm -rf --one-file-system -- "$APP_HOME_DIR"
        mkdir -m 700 -- "$APP_HOME_DIR"
    fi
    chown -h bms:bms "$APP_HOME_DIR"
    chmod 700 "$APP_HOME_DIR"

    # Mueve las entradas ocultas (.gradle, .cache, .config...) sin seguir enlaces: rename no los sigue.
    for entry in "$APP_DIR"/.[!.]* "$APP_DIR"/..?*; do
        [[ -e $entry || -L $entry ]] || continue
        name=${entry##*/}
        if [[ -e $APP_HOME_DIR/$name || -L $APP_HOME_DIR/$name ]]; then
            continue
        fi
        mv -T -- "$entry" "$APP_HOME_DIR/$name"
    done
    if [[ $(getent passwd bms | cut -d: -f6) != "$APP_HOME_DIR" ]]; then
        # usermod rechaza cambiar el directorio si bms tiene procesos (el servicio corre);
        # no es fatal: Gradle recibe HOME explicito y el cambio se reintenta en la siguiente actualizacion.
        if usermod -d "$APP_HOME_DIR" bms; then
            printf 'bms ahora usa %s como directorio personal.\n' "$APP_HOME_DIR"
        else
            printf 'AVISO: no se pudo cambiar el directorio personal de bms (servicio en uso); se reintentara.\n' >&2
        fi
    fi

    # Swap heredado: se desactiva sin seguir enlaces y se elimina.
    if [[ -e $LEGACY_SWAP_FILE || -L $LEGACY_SWAP_FILE ]]; then
        if [[ ! -L $LEGACY_SWAP_FILE ]] && awk 'NR > 1 { print $1 }' /proc/swaps | grep -Fxq "$LEGACY_SWAP_FILE"; then
            swapoff "$LEGACY_SWAP_FILE"
        fi
        rm -f -- "$LEGACY_SWAP_FILE"
    fi

    # Entradas que root usa directamente en APP_DIR: se eliminan si son enlaces o no son de root.
    # build-tree es del usuario de compilacion y se recrea en cada actualizacion.
    for name in update-logs backups app.new app.previous web.new web.previous build-tree; do
        entry=$APP_DIR/$name
        [[ -e $entry || -L $entry ]] || continue
        legacy_owner=$(stat -c %u -- "$entry")
        if [[ -L $entry || $legacy_owner != 0 || $name == build-tree ]]; then
            rm -rf --one-file-system -- "$entry"
        fi
    done
    # Respaldos antiguos de root: se mueven al nuevo lugar si es un directorio real.
    if [[ -d $APP_DIR/backups && ! -L $APP_DIR/backups && ! -e $BACKUP_ROOT ]]; then
        mv -T -- "$APP_DIR/backups" "$BACKUP_ROOT"
    fi
    install -d -m 700 -o root -g root "$BACKUP_ROOT" "$BUILD_LOG_DIR" "$NGINX_BACKUP_DIR"
}

# A3: the user that compiles. BMS_BUILD_USER=bms in server.env goes back to compiling as the service user (only as
# a way out if the separate user ever fails on a server; SELF_HOSTING.md section 15). Its home starts empty: nothing
# is taken from the service user's home (a Gradle or npm setting, init script, cache entry or JDK the service user
# could have planted there would run in every build), and the build-only caches left there are removed.
ensure_build_user() {
    local name
    BUILD_USER=$(config_value BMS_BUILD_USER)
    BUILD_USER=${BUILD_USER:-$DEFAULT_BUILD_USER}
    [[ $BUILD_USER =~ ^[a-z][a-z0-9-]{0,31}$ && $BUILD_USER != root ]] || die 'BMS_BUILD_USER invalido en server.env.'
    if [[ $BUILD_USER == bms ]]; then
        warn 'BMS_BUILD_USER=bms: se compila con el usuario del servicio (sin separacion).'
        BUILD_HOME=$APP_HOME_DIR
        return
    fi
    if ! id "$BUILD_USER" >/dev/null 2>&1; then
        useradd --system --user-group --no-create-home --home-dir "$BUILD_HOME_DIR" --shell /usr/sbin/nologin "$BUILD_USER"
        printf 'Usuario de compilacion creado: %s\n' "$BUILD_USER"
    fi
    [[ $(id -u "$BUILD_USER") != 0 ]] || die "$BUILD_USER no puede ser root."
    [[ " $(id -nG "$BUILD_USER") " != *' bms '* ]] || die "$BUILD_USER no debe pertenecer al grupo bms."
    BUILD_HOME=$BUILD_HOME_DIR
    # A home without the marker (new, or filled by an earlier version that imported the service user's caches) is
    # emptied once.
    if [[ -L $BUILD_HOME || ! -d $BUILD_HOME || ! -f $BUILD_HOME/.bms-build-home-v2 || -L $BUILD_HOME/.bms-build-home-v2 ]]; then
        rm -rf --one-file-system -- "$BUILD_HOME"
        mkdir -m 700 -- "$BUILD_HOME"
        : > "$BUILD_HOME/.bms-build-home-v2"
        chmod 644 "$BUILD_HOME/.bms-build-home-v2"
    fi
    chown -h "$BUILD_USER:$BUILD_USER" "$BUILD_HOME"
    chmod 700 "$BUILD_HOME"
    chown -h root:root "$BUILD_HOME/.bms-build-home-v2"
    for name in .gradle .konan .kotlin .m2 .npm .yarn; do
        if [[ -e $APP_HOME_DIR/$name || -L $APP_HOME_DIR/$name ]]; then
            rm -rf --one-file-system -- "${APP_HOME_DIR:?}/$name"
            printf 'Cache de compilacion de bms eliminada: %s\n' "$APP_HOME_DIR/$name"
        fi
    done
}

# Opcional (opt-in): en vez de compilar la webapp (webpack no cabe en 4 GB de RAM), descarga la
# distribucion que el CI (.github/workflows/build.yml) publico para este mismo commit en el release
# "web-dist" de GitHub. Se activa con WEB_DIST_FROM_CI=true en /etc/bms/server.env (o en el
# entorno). Si algo falla (sin paquete para ese sha, sin red, firma, checksum o contenido invalidos) devuelve 1
# y el llamador compila como siempre. Deja la ruta en WEB_DIST (un directorio de root).
# curl corre como el usuario de compilacion (no como root) pero escribe por su salida en archivos de un directorio
# propio de root: root nunca abre una ruta de ese usuario. Con la llave publica de root WEB_DIST_PUBLIC_KEY instalada,
# la firma Ed25519 del .sha256 es obligatoria. El hash se calcula sobre la copia de root, y root extrae esa copia
# solo si su listado no tiene enlaces, archivos especiales ni rutas fuera del directorio (dist.sh).
fetch_ci_web_dist() {
    local sha=$1 base name dir expected actual
    base=$(config_value WEB_DIST_BASE_URL)
    base=${base:-https://github.com/alexcalbri/BMS/releases/download/web-dist}
    [[ $base =~ ^https://[A-Za-z0-9./_-]+$ ]] || { warn 'WEB_DIST_BASE_URL invalida.'; return 1; }
    command -v curl >/dev/null 2>&1 || { warn 'no hay curl para descargar la webapp del CI.'; return 1; }
    name=web-$sha.tar.gz
    dir=$(mktemp -d -p "$ROOT_STATE_DIR" webdist.XXXXXX) || return 1
    CI_WEB_DIR=$dir
    printf 'Descargando la webapp compilada por el CI (%s)...\n' "$name"
    ci_download() {
        runuser -u "$BUILD_USER" -- env -i PATH=/usr/bin:/bin HOME="$BUILD_HOME" \
            curl --fail --silent --show-error --location --proto '=https' --tlsv1.2 \
            --retry 3 --retry-delay 5 --connect-timeout 15 --max-time "$2" --max-filesize "$3" -o - "$base/$1"
    }
    # --fail: un 404 (el CI aun no publica ese commit) cuenta como error y se compila.
    ci_download "$name" 600 209715200 > "$dir/package.tar.gz" || return 1
    ci_download "$name.sha256" 60 4096 > "$dir/sha256" || return 1
    if [[ -e $WEB_DIST_PUBLIC_KEY || -L $WEB_DIST_PUBLIC_KEY ]]; then
        if ! is_root_private_file "$WEB_DIST_PUBLIC_KEY"; then
            warn "$WEB_DIST_PUBLIC_KEY existe pero no es un archivo solo de root; no se usa la webapp del CI."
            return 1
        fi
        if ! ci_download "$name.sha256.sig" 60 4096 > "$dir/sig"; then
            warn 'la webapp del CI no tiene firma (.sha256.sig) y la firma es obligatoria con la llave instalada.'
            return 1
        fi
        if ! openssl pkeyutl -verify -rawin -pubin -inkey "$WEB_DIST_PUBLIC_KEY" -in "$dir/sha256" -sigfile "$dir/sig" >/dev/null 2>&1; then
            warn 'la firma de la webapp del CI no es valida.'
            return 1
        fi
        printf 'Firma de la webapp del CI verificada.\n'
    else
        warn "no hay llave de firma de la webapp ($WEB_DIST_PUBLIC_KEY): se usa sin firma, solo con su checksum."
    fi
    stop_build_user_processes
    # El .sha256 (verificado) debe nombrar exactamente este paquete; se compara el hash de la copia de root.
    expected=$(awk -v n="$name" '$2 == n || $2 == "*" n { print $1; exit }' "$dir/sha256")
    [[ $expected =~ ^[0-9a-f]{64}$ ]] || { warn 'checksum del CI ilegible.'; return 1; }
    actual=$(sha256sum -- "$dir/package.tar.gz" | awk '{print $1}')
    [[ $actual == "$expected" ]] || { warn 'el checksum de la webapp del CI no coincide.'; return 1; }
    install -d -m 700 -o root -g root "$dir/dist" || return 1
    bms_extract_checked "$dir/package.tar.gz" "$dir/dist" -z || { warn 'el paquete de la webapp del CI tiene enlaces, archivos especiales o rutas no permitidas.'; return 1; }
    [[ -f $dir/dist/index.html && -f $dir/dist/webApp.js ]] || { warn 'la webapp del CI esta incompleta.'; return 1; }
    WEB_DIST=$dir/dist
    WEB_DIST_ROOT_OWNED=true
}

# The new release is healthy when it answers HTTP, its log since the start says it verified its PostgreSQL connection
# and has no PostgreSQL permission or authentication error (the startup catch-up connects to every company with the
# provisioning login), and the runtime role and the provisioning login still reach every database (db-roles.sh).
# Without [since] (the rollback's check of the previous release) only HTTP.
backend_answers() {
    local since=${1:-} log
    for _ in {1..30}; do
        if systemctl is-active --quiet "$SERVICE_NAME" &&
            curl --noproxy '*' --fail --silent --show-error --max-time 5 "http://127.0.0.1:$APP_PORT/" >/dev/null; then
            break
        fi
        sleep 3
    done
    systemctl is-active --quiet "$SERVICE_NAME" || return 1
    curl --noproxy '*' --fail --silent --max-time 5 --output /dev/null "http://127.0.0.1:$APP_PORT/" || return 1
    [[ -n $since ]] || return 0
    sleep 15
    log=$(journalctl -u "$SERVICE_NAME" --since "@$since" --no-pager -o cat 2>/dev/null) || log=''
    if ! grep -q 'PostgreSQL connection verified' <<< "$log"; then
        printf 'ERROR: el servidor no registro "PostgreSQL connection verified" al arrancar.\n' >&2
        return 1
    fi
    if grep -Eiq 'permission denied for|must be owner of|password authentication failed|permission denied to set role' <<< "$log"; then
        printf 'ERROR: el servidor registro errores de permisos de PostgreSQL al arrancar:\n' >&2
        grep -Ei 'permission denied for|must be owner of|password authentication failed|permission denied to set role' <<< "$log" | head -n 10 >&2
        return 1
    fi
    bms_verify_role_access "$CONTROL_DB" "$DB_USER" "$PROVISIONER" "$(bms_tenant_databases "$CONTROL_DB" 2>/dev/null)"
}

# The site serves the web app's index.html and sends /auth/login to the backend (400 for an empty login), for [name].
nginx_serves_app() {
    local scheme=$1 domain=$2 port=80 body status
    [[ $scheme == https ]] && port=443
    for _ in {1..5}; do
        body=$(curl --noproxy '*' --silent --max-time 15 --resolve "$domain:$port:127.0.0.1" "$scheme://$domain/" 2>/dev/null) || body=''
        status=$(curl --noproxy '*' --silent --max-time 15 --resolve "$domain:$port:127.0.0.1" --output /dev/null \
            --write-out '%{http_code}' --header 'Content-Type: application/json' --data '{}' "$scheme://$domain/auth/login" 2>/dev/null) || status=000
        if [[ $body == "$(cat "$WEB_RUNTIME_DIR/index.html")" && $status == 400 ]]; then
            return 0
        fi
        sleep 2
    done
    return 1
}

# Every name the site serves answers as the app.
nginx_serves_all() {
    local scheme=$1 names=$2 name
    for name in $names; do
        nginx_serves_app "$scheme" "$name" || { warn "el nombre $name no sirve la app con la configuracion nueva."; return 1; }
    done
}

# A5: the BMS site is written again from nginx-site.sh (every server_name, scheme and certificate of the live one),
# with the limits file and the catch-all server. A live site with lines no installer wrote (local edits) is NOT
# replaced: the proposal and the diff are printed and kept, only the limits file (zones, used by nothing else) is
# added; BMS_NGINX_REPLACE_SITE=yes in the environment replaces it anyway. On a failed nginx -t or any served name
# that no longer serves the app, the previous files come back (the limits file stays: unused zones are harmless).
# Never fatal: the old configuration keeps working.
configure_nginx() {
    local params scheme domain cert names stamp backup new taken ports='' file http3 unknown
    if [[ ! -f $NGINX_SITE || -L $NGINX_SITE ]]; then
        warn "no se encontro $NGINX_SITE (o es un enlace); no se cambia Nginx."
        return 0
    fi
    if ! params=$(bms_nginx_site_params "$NGINX_SITE"); then
        warn "$NGINX_SITE no parece el sitio BMS del instalador; no se cambia Nginx."
        return 0
    fi
    read -r scheme domain cert <<< "$params"
    names=$(bms_nginx_site_names "$NGINX_SITE")
    [[ -n $names ]] || names=$domain
    if [[ $scheme == https && ! -f /etc/letsencrypt/live/$cert/fullchain.pem ]]; then
        warn "no se encontro el certificado /etc/letsencrypt/live/$cert; no se cambia Nginx."
        return 0
    fi
    stamp=$(date -u +%Y%m%dT%H%M%SZ)
    backup=$NGINX_BACKUP_DIR/$stamp
    install -d -m 700 -o root -g root "$backup"
    for file in "$NGINX_SITE" "$BMS_NGINX_LIMITS_FILE" "$BMS_NGINX_CATCHALL_SITE"; do
        [[ -f $file ]] && cp -a -- "$file" "$backup/${file//\//_}"
    done
    [[ -L /etc/nginx/sites-enabled/bms-default ]] && touch "$backup/catchall-enabled"
    new=$backup/proposed-site
    http3=no
    bms_nginx_has_http3 && http3=yes
    BMS_NGINX_HTTP2_DIRECTIVE=no
    bms_nginx_has_http2_directive && BMS_NGINX_HTTP2_DIRECTIVE=yes
    bms_nginx_site "$scheme" "$names" "$cert" "$WEB_RUNTIME_DIR" "$ACME_ROOT" "$APP_PORT" "$http3" > "$new"
    install -d -m 755 -o root -g root "$BMS_NGINX_LOCAL_DIR"
    bms_nginx_limits > "$BMS_NGINX_LIMITS_FILE.tmp"
    chmod 644 "$BMS_NGINX_LIMITS_FILE.tmp"
    mv -f -- "$BMS_NGINX_LIMITS_FILE.tmp" "$BMS_NGINX_LIMITS_FILE"
    if cmp -s "$new" "$NGINX_SITE"; then
        rm -rf -- "$backup"
        nginx -t >/dev/null 2>&1 && systemctl reload nginx
        return 0
    fi
    unknown=$(bms_nginx_unknown_lines "$NGINX_SITE")
    if [[ -n $unknown && ${BMS_NGINX_REPLACE_SITE:-} != yes ]]; then
        warn "$NGINX_SITE tiene lineas que ningun instalador de BMS escribio (cambios locales); NO se reemplaza."
        printf 'Lineas propias:\n%s\n' "$(head -n 40 <<< "$unknown")" >&2
        printf 'Diferencias con la configuracion nueva (propuesta en %s):\n' "$new" >&2
        diff -u "$NGINX_SITE" "$new" | head -n 200 >&2 || true
        printf 'Pasa los cambios locales a %s/*.conf y ejecuta update.sh con BMS_NGINX_REPLACE_SITE=yes para aplicarla.\n' "$BMS_NGINX_LOCAL_DIR" >&2
        if nginx -t >/dev/null 2>&1; then systemctl reload nginx; fi
        return 0
    fi
    # Puertos del servidor por defecto que ya usa otro sitio (por ejemplo el "default" de Ubuntu).
    taken=$(nginx -T 2>/dev/null | bms_nginx_taken_default_ports) || taken='80 443'
    [[ " $taken " == *' 80 '* ]] || ports='80'
    [[ $scheme != https || " $taken " == *' 443 '* ]] || ports+=' 443'
    install -m 644 -o root -g root "$new" "$NGINX_SITE"
    if [[ -n ${ports// /} ]]; then
        bms_nginx_catchall "$ports" > "$BMS_NGINX_CATCHALL_SITE"
        chmod 644 "$BMS_NGINX_CATCHALL_SITE"
        ln -sfn "$BMS_NGINX_CATCHALL_SITE" /etc/nginx/sites-enabled/bms-default
    else
        rm -f /etc/nginx/sites-enabled/bms-default "$BMS_NGINX_CATCHALL_SITE"
    fi
    [[ " $taken " == *' 80 '* ]] &&
        warn 'otro sitio de Nginx ya es default_server en el puerto 80 (normalmente /etc/nginx/sites-enabled/default de Ubuntu): los nombres desconocidos siguen recibiendo ese sitio. Si no lo usas: sudo rm /etc/nginx/sites-enabled/default && sudo bash update.sh'
    if nginx -t >/dev/null 2>&1 && systemctl reload nginx && nginx_serves_all "$scheme" "$names"; then
        printf 'Nginx actualizado (limites de cuerpo, limites por direccion, servidor por defecto, cabeceras) para: %s\n' "$names"
        prune_old "$NGINX_BACKUP_DIR" '20*' "$KEEP_BACKUPS"
        return 0
    fi
    warn "la configuracion nueva de Nginx fallo (nginx -t o la comprobacion de algun nombre); se restaura la anterior ($backup)."
    nginx -t 2>&1 | tail -n 5 >&2 || true
    for file in "$NGINX_SITE" "$BMS_NGINX_CATCHALL_SITE"; do
        if [[ -f $backup/${file//\//_} ]]; then
            cp -a -- "$backup/${file//\//_}" "$file"
        else
            rm -f -- "$file"
        fi
    done
    if [[ -f $backup/catchall-enabled ]]; then
        ln -sfn "$BMS_NGINX_CATCHALL_SITE" /etc/nginx/sites-enabled/bms-default
    else
        rm -f /etc/nginx/sites-enabled/bms-default
    fi
    if ! { nginx -t >/dev/null 2>&1 && systemctl reload nginx; }; then
        warn 'Nginx no acepta ni la configuracion anterior; revisa sudo nginx -t.'
    fi
}

# shellcheck disable=SC2329 # called from the EXIT trap
print_database_restore_help() {
    [[ -n $BACKUP_DIR && -d $BACKUP_DIR/db ]] || return 0
    printf '\nLas bases NO se restauraron (no es automatico). Copia previa a la actualizacion: %s/db\n' "$BACKUP_DIR" >&2
    printf 'Solo si una migracion dejo datos mal (borra lo escrito despues de la copia), con el servicio detenido:\n' >&2
    printf '  sudo systemctl stop bms\n' >&2
    printf '  sudo -u postgres dropdb --force <base>\n' >&2
    printf '  sudo -u postgres pg_restore --create --exit-on-error -d postgres %s/db/<base>.dump\n' "$BACKUP_DIR" >&2
    printf '  (--create vuelve a crear la base con su dueno y sus permisos de CONNECT; los roles deben existir:\n' >&2
    printf '   %s/db/roles.sql tiene todos)\n' "$BACKUP_DIR" >&2
    printf '  sudo systemctl start bms\n' >&2
    printf 'La copia es de antes del cambio de roles: corrige la causa y vuelve a ejecutar update.sh (vuelve a ordenar\n' >&2
    printf 'los roles y migra). Detalle en SELF_HOSTING.md, seccion 15.\n' >&2
}

# Root helpers and their units as they were before this update (L5): the previous release's update requests keep
# going to the helper that release expects. /usr/local/lib/bms holds keys too: only mirror.sh is saved and restored.
readonly HELPER_FILES=(
    /usr/local/sbin/bms-domains-sync /usr/local/sbin/bms-core-update /usr/local/sbin/bms-backup /usr/local/lib/bms/mirror.sh
    /etc/systemd/system/bms-domains.service /etc/systemd/system/bms-domains.timer
    /etc/systemd/system/bms-domains-renew.service /etc/systemd/system/bms-domains-renew.timer
    /etc/systemd/system/bms-core-update.service /etc/systemd/system/bms-core-update.timer
    /etc/systemd/system/bms-backup.service /etc/systemd/system/bms-backup.timer
)
readonly HELPER_DROPINS=(bms-domains.service.d bms-domains-renew.service.d bms-backup.service.d)

backup_helpers() {
    local dir=$1 file
    install -d -m 700 -o root -g root "$dir/helpers"
    for file in "${HELPER_FILES[@]}"; do
        [[ -f $file && ! -L $file ]] && cp -a -- "$file" "$dir/helpers/${file//\//_}"
    done
    for file in "${HELPER_DROPINS[@]}"; do
        [[ -d /etc/systemd/system/$file ]] && cp -a -- "/etc/systemd/system/$file" "$dir/helpers/$file"
    done
    return 0
}

# shellcheck disable=SC2329 # called from the EXIT trap
restore_helpers() {
    local dir=$1 file unit
    [[ -d $dir/helpers ]] || return 0
    for file in "${HELPER_FILES[@]}"; do
        if [[ -f $dir/helpers/${file//\//_} ]]; then
            # A copy and a rename: a running helper (bash reads its script as it goes) keeps its old file.
            cp -a -- "$dir/helpers/${file//\//_}" "$file.rollback" && mv -f -- "$file.rollback" "$file"
        elif [[ -e $file ]]; then
            unit=${file##*/}
            [[ $unit == *.timer ]] && systemctl disable --now "$unit" >/dev/null 2>&1
            rm -f -- "$file"
        fi
    done
    for file in "${HELPER_DROPINS[@]}"; do
        rm -rf --one-file-system -- "/etc/systemd/system/$file"
        [[ -d $dir/helpers/$file ]] && cp -a -- "$dir/helpers/$file" "/etc/systemd/system/$file"
    done
    return 0
}

# The automatic rollback (A7): binaries, server.env, unit settings, root helpers and role memberships of the
# previous release; restart.
# shellcheck disable=SC2329 # called from the EXIT trap
rollback_after_failure() {
    trap - ERR
    set +e
    case $STAGE in
        stopped|swapped) ;;
        *) return 0 ;;
    esac
    printf '\nLa actualizacion fallo; se vuelve a la version anterior...\n' >&2
    systemctl stop "$SERVICE_NAME" >/dev/null 2>&1
    # The flags are set before the first move: a failure between the two moves is restored too.
    if [[ $APP_SWAPPED == true && -d $APP_RUNTIME_DIR.previous ]]; then
        rm -rf --one-file-system -- "$APP_RUNTIME_DIR.failed"
        [[ ! -e $APP_RUNTIME_DIR ]] || mv -T -- "$APP_RUNTIME_DIR" "$APP_RUNTIME_DIR.failed"
        mv -T -- "$APP_RUNTIME_DIR.previous" "$APP_RUNTIME_DIR"
        rm -rf --one-file-system -- "$APP_RUNTIME_DIR.failed"
    fi
    if [[ $WEB_SWAPPED == true && -d $WEB_RUNTIME_DIR.previous ]]; then
        rm -rf --one-file-system -- "$WEB_RUNTIME_DIR.failed"
        [[ ! -e $WEB_RUNTIME_DIR ]] || mv -T -- "$WEB_RUNTIME_DIR" "$WEB_RUNTIME_DIR.failed"
        mv -T -- "$WEB_RUNTIME_DIR.previous" "$WEB_RUNTIME_DIR"
        rm -rf --one-file-system -- "$WEB_RUNTIME_DIR.failed"
    fi
    if [[ -n $BACKUP_DIR && -f $BACKUP_DIR/server.env ]]; then
        install -m 600 -o root -g root "$BACKUP_DIR/server.env" "$CONFIG_FILE"
    fi
    if [[ -n $DROPIN_BACKUP ]]; then
        rm -rf --one-file-system -- "/etc/systemd/system/$SERVICE_NAME.d"
        if [[ -d $DROPIN_BACKUP/dropins ]]; then
            cp -a -- "$DROPIN_BACKUP/dropins" "/etc/systemd/system/$SERVICE_NAME.d"
        fi
        restore_helpers "$DROPIN_BACKUP"
    fi
    systemctl daemon-reload
    if [[ $LAYOUT_CHANGED == true ]]; then
        # The previous release creates companies by SET ROLE from the runtime role: it needs the membership back.
        if bms_restore_runtime_membership "$CONTROL_DB" "$DB_USER"; then
            printf 'El rol %s vuelve a ser miembro del rol del server owner (como en la version anterior).\n' "$DB_USER" >&2
        else
            printf 'ERROR: no se pudo devolver al rol %s su pertenencia al rol del server owner.\n' "$DB_USER" >&2
        fi
    fi
    if [[ -n $PREVIOUS_SOURCE_COMMIT ]]; then
        git_source checkout --quiet --detach "$PREVIOUS_SOURCE_COMMIT" >/dev/null 2>&1 ||
            printf 'AVISO: %s no se pudo dejar en %s.\n' "$SOURCE_DIR" "$PREVIOUS_SOURCE_COMMIT" >&2
    fi
    systemctl start "$SERVICE_NAME"
    if backend_answers; then
        printf 'La version anterior volvio a arrancar y responde.\n' >&2
    else
        printf 'ERROR: la version anterior tampoco responde. Revisa: sudo journalctl -u bms -n 200 --no-pager\n' >&2
        journalctl -u "$SERVICE_NAME" -n 40 --no-pager >&2
    fi
    print_database_restore_help
}

# shellcheck disable=SC2329 # called from the EXIT trap
on_update_exit() {
    local code=$?
    trap - EXIT
    if (( code != 0 )); then
        rollback_after_failure
    fi
    cleanup_update_swap
    [[ -z $PROVISIONER_PASSFILE ]] || rm -f -- "$PROVISIONER_PASSFILE"
    [[ -z $CI_WEB_DIR ]] || rm -rf --one-file-system -- "$CI_WEB_DIR"
    exit "$code"
}

trap 'printf "Actualizacion interrumpida (linea %s).\n" "$LINENO" >&2' ERR
trap on_update_exit EXIT

[[ -d $SOURCE_DIR/.git ]] || die "No se encontro el repositorio en $SOURCE_DIR."
[[ -d $APP_RUNTIME_DIR && -x $APP_RUNTIME_DIR/bin/server ]] || die "No se encontro el servidor instalado en $APP_RUNTIME_DIR."
[[ -d $WEB_RUNTIME_DIR ]] || die "No se encontro la webapp instalada en $WEB_RUNTIME_DIR."
[[ -r $CONFIG_FILE ]] || die "No se encontro $CONFIG_FILE."
id bms >/dev/null 2>&1 || die 'No existe el usuario Linux bms.'
systemctl cat "$SERVICE_NAME" >/dev/null 2>&1 || die "No existe $SERVICE_NAME."

harden_app_dir

cd "$SOURCE_DIR"
CURRENT_REF=$(git_source rev-parse --abbrev-ref HEAD) || CURRENT_REF=HEAD
CURRENT_COMMIT=$(git_source rev-parse --short HEAD) || CURRENT_COMMIT=desconocido
PREVIOUS_SOURCE_COMMIT=$(git_source rev-parse HEAD) || PREVIOUS_SOURCE_COMMIT=''
[[ $PREVIOUS_SOURCE_COMMIT =~ ^[0-9a-f]{40}$ ]] || PREVIOUS_SOURCE_COMMIT=''
# root no se queda con el directorio de trabajo dentro de un arbol que bms puede escribir.
cd /
if [[ $NON_INTERACTIVE == true ]]; then
    GIT_REF=$ARG_COMMIT
    UPDATE_TARGET=$ARG_TARGET
else
    # Tras una actualizacion automatica el repositorio queda en HEAD desacoplado: se sugiere la rama configurada.
    if [[ $CURRENT_REF == HEAD ]]; then
        CURRENT_REF=$(config_value CORE_UPDATE_REF)
        CURRENT_REF=${CURRENT_REF:-master}
    fi
    ask GIT_REF 'Rama, etiqueta o commit para actualizar' "$CURRENT_REF"
    [[ $GIT_REF =~ ^[a-zA-Z0-9][a-zA-Z0-9._/-]*$ && $GIT_REF != *..* ]] || die 'Referencia Git invalida.'
    ask UPDATE_TARGET 'Que deseas actualizar? server, webapp o ambos' ambos
    case "$UPDATE_TARGET" in
        server|webapp|ambos) ;;
        *) die 'Opcion invalida. Usa server, webapp o ambos.' ;;
    esac
fi

printf 'Instalacion actual: %s (%s)\n' "$CURRENT_REF" "$CURRENT_COMMIT"
printf 'Destino: %s\n' "$GIT_REF"
if [[ $NON_INTERACTIVE == true ]]; then
    CONFIRM=si
else
    ask CONFIRM "Continuar con build, respaldo y reemplazo de $UPDATE_TARGET? Escribe si" no
fi
[[ $CONFIRM == si ]] || die 'Cancelado sin cambiar archivos.'

if [[ $NON_INTERACTIVE == true ]]; then
    BUILD_SHA=$ARG_COMMIT
else
    # Modo manual: root sincroniza su espejo con la referencia elegida y todo sale de ahi.
    MIRROR_LIB_FILE=''
    if is_root_private_file "$SCRIPT_DIR/mirror.sh"; then
        MIRROR_LIB_FILE=$(realpath -e -- "$SCRIPT_DIR/mirror.sh")
    elif is_root_private_file /usr/local/lib/bms/mirror.sh; then
        MIRROR_LIB_FILE=$(realpath -e -- /usr/local/lib/bms/mirror.sh)
    fi
    [[ -n $MIRROR_LIB_FILE ]] || die 'No se encontro mirror.sh en un lugar seguro. Descarga update.sh y mirror.sh desde https://raw.githubusercontent.com/alexcalbri/BMS/master/scripts/ubuntu/ a un directorio de root (por ejemplo /root/bms-update) y ejecuta sudo bash /root/bms-update/update.sh.'
    # shellcheck source=mirror.sh
    source "$MIRROR_LIB_FILE"
    printf 'Sincronizando el espejo de root con %s...\n' "$GIT_REF"
    BUILD_SHA=$(sync_mirror "$GIT_REF") || die "No se pudo traer $GIT_REF desde GitHub al espejo $MIRROR_DIR."
    [[ $BUILD_SHA =~ ^[0-9a-f]{40}$ ]] || die 'No se pudo leer el commit del espejo.'
    # Firma del commit (SIGNING.md), cuando el mirror.sh instalado sabe verificarla. Con una llave instalada, un
    # mirror.sh que no sabe verificar no sirve.
    if [[ -e $ALLOWED_SIGNERS || -L $ALLOWED_SIGNERS ]] && ! declare -F verify_mirror_signature >/dev/null; then
        die "Hay una llave de firma en $ALLOWED_SIGNERS pero $MIRROR_LIB_FILE no sabe verificar firmas: descarga el mirror.sh actual junto a update.sh."
    fi
    if declare -F verify_mirror_signature >/dev/null; then
        MANUAL_VERSION=$(sed -nE 's/^version = "([0-9]+\.[0-9]+\.[0-9]+)"$/\1/p' "$MIRROR_DIR/server/build.gradle.kts" | head -n 1)
        SIGNATURE_STATUS=$(verify_mirror_signature "$BUILD_SHA" "$MANUAL_VERSION") ||
            die "El commit $BUILD_SHA no tiene una firma valida de la llave instalada; no se actualiza."
        printf 'Firma del commit: %s\n' "$SIGNATURE_STATUS"
    fi
    TRUSTED_DIR=$MIRROR_DIR
    readonly TRUSTED_DIR
    [[ -f $TRUSTED_DIR/scripts/ubuntu/migrations.sh && -f $TRUSTED_DIR/scripts/ubuntu/core-update.sh ]] ||
        die 'El espejo no contiene los scripts de BMS.'
fi

# Libraries of this release (A1, A5, A6, A4): from the root-owned tree, like migrations.sh.
for library in db-roles.sh nginx-site.sh units.sh dist.sh backup.sh harden-host.sh; do
    [[ -f $TRUSTED_DIR/scripts/ubuntu/$library ]] ||
        die "El arbol $TRUSTED_DIR no tiene scripts/ubuntu/$library: esa referencia es anterior a este update.sh. Usa el update.sh de la misma referencia."
done
# shellcheck source=db-roles.sh
source "$TRUSTED_DIR/scripts/ubuntu/db-roles.sh"
# shellcheck source=nginx-site.sh
source "$TRUSTED_DIR/scripts/ubuntu/nginx-site.sh"
# shellcheck source=units.sh
source "$TRUSTED_DIR/scripts/ubuntu/units.sh"
# shellcheck source=dist.sh
source "$TRUSTED_DIR/scripts/ubuntu/dist.sh"
# shellcheck source=backup.sh
source "$TRUSTED_DIR/scripts/ubuntu/backup.sh"

DB_USER=$(config_value DB_USER)
DB_URL_VALUE=$(config_value DB_URL)
CONTROL_DB=${DB_URL_VALUE##*/}
CONTROL_DB=${CONTROL_DB%%\?*}
[[ -n $DB_USER ]] || die 'DB_USER no existe en /etc/bms/server.env.'
bms_valid_role "$DB_USER" || die 'DB_USER invalido en server.env.'
[[ $CONTROL_DB =~ ^[a-z][a-z0-9_]{0,62}$ ]] || die 'No se pudo inferir la base central desde DB_URL.'

if [[ -n ${JAVA_HOME:-} && -x $JAVA_HOME/bin/java ]]; then
    UPDATE_JAVA_HOME=$JAVA_HOME
else
    UPDATE_JAVA_HOME="/usr/lib/jvm/java-21-openjdk-$(dpkg --print-architecture)"
fi
[[ -x $UPDATE_JAVA_HOME/bin/java ]] || die 'No se encontro Java 21. Instala openjdk-21-jdk-headless o define JAVA_HOME.'
ensure_update_swap
ensure_build_user

# 1. Build and test, from a fresh tree of the root mirror, as the build user. Nothing on the server changes before
# this succeeds.
TARGET_COMMIT=${BUILD_SHA:0:7}
BUILD_DIR=$BUILD_TREE
stop_build_user_processes
rm -rf --one-file-system -- "$BUILD_DIR"
install -d -m 755 -o "$BUILD_USER" -g "$BUILD_USER" "$BUILD_DIR"
git -c core.hooksPath=/dev/null -c core.fsmonitor=false -C "$TRUSTED_DIR" archive "$BUILD_SHA" |
    runuser -u "$BUILD_USER" -- tar -x -C "$BUILD_DIR" --no-same-owner
[[ -f $BUILD_DIR/gradlew && -f $BUILD_DIR/server/build.gradle.kts ]] || die 'El arbol de compilacion esta incompleto.'

cd "$BUILD_DIR"
install -d -m 700 -o root -g root "$BUILD_LOG_DIR"
SERVER_BUILD_LOG="$BUILD_LOG_DIR/server-$TARGET_COMMIT.log"
WEB_BUILD_LOG="$BUILD_LOG_DIR/web-$TARGET_COMMIT.log"

if [[ $UPDATE_TARGET == server || $UPDATE_TARGET == ambos ]]; then
    run_gradle -PserverOnly=true :server:test :server:installDist 2>&1 | tee "$SERVER_BUILD_LOG"
    stop_build_user_processes
    [[ -x $BUILD_DIR/server/build/install/server/bin/server ]] || die 'No se genero la distribucion del servidor.'
fi
if [[ $UPDATE_TARGET == webapp || $UPDATE_TARGET == ambos ]]; then
    WEB_DIST=''
    WEB_FROM_CI=${WEB_DIST_FROM_CI:-$(config_value WEB_DIST_FROM_CI)}
    if [[ $WEB_FROM_CI == true ]]; then
        # Conserva el exito o fallo sin que set -e corte el script: si falla se compila.
        if ! fetch_ci_web_dist "$BUILD_SHA"; then
            warn 'no se pudo usar la webapp del CI; se compilara aqui.'
            WEB_DIST=''
            WEB_DIST_ROOT_OWNED=false
        fi
    fi
    if [[ -z $WEB_DIST ]]; then
        # build/js keeps the yarn.lock of the previous build; when kotlin-js-store/yarn.lock changed,
        # kotlinStoreYarnLock fails with "Lock file was changed". It is only a build cache.
        runuser -u "$BUILD_USER" -- rm -rf -- "$BUILD_DIR/build/js"
        run_gradle -PwebOnly=true :app:webApp:jsBrowserDistribution 2>&1 | tee "$WEB_BUILD_LOG"
        stop_build_user_processes
        WEB_DIST="$BUILD_DIR/app/webApp/build/dist/js/productionExecutable"
    fi
    [[ -f $WEB_DIST/index.html && -f $WEB_DIST/webApp.js ]] || die 'No se genero la distribucion web completa.'
fi
cd /

# 2. Backups: databases (pg_dump, before any migration), binaries, web, configuration, root helpers. Root only.
TIMESTAMP=$(date -u +%Y%m%dT%H%M%SZ)
BACKUP_DIR=$BACKUP_ROOT/$TIMESTAMP
install -d -m 700 -o root -g root "$BACKUP_DIR"
if [[ $UPDATE_TARGET == server || $UPDATE_TARGET == ambos ]]; then
    if [[ ${BMS_UPDATE_SKIP_DB_DUMP:-} == true ]]; then
        warn 'BMS_UPDATE_SKIP_DB_DUMP=true: se actualiza SIN copia previa de las bases.'
    else
        DB_BYTES=$(bms_backup_size "$CONTROL_DB") || die 'No se pudo medir el tamano de las bases.'
        bms_backup_space_ok "$BACKUP_ROOT" "$DB_BYTES" ||
            die "No hay espacio libre suficiente en $BACKUP_ROOT para copiar las bases antes de migrar (unos $DB_BYTES bytes y 2 GiB de margen). Libera espacio (por ejemplo respaldos viejos en $BACKUP_ROOT) y reintenta. No se cambio nada."
        printf 'Copiando las bases de datos (pg_dump) en %s/db...\n' "$BACKUP_DIR"
        bms_dump_databases "$BACKUP_DIR/db" "$CONTROL_DB" || die 'Fallo la copia de las bases; no se cambio nada.'
    fi
fi
cp -a "$APP_RUNTIME_DIR" "$BACKUP_DIR/app"
cp -a "$WEB_RUNTIME_DIR" "$BACKUP_DIR/web"
install -m 600 -o root -g root "$CONFIG_FILE" "$BACKUP_DIR/server.env"
if [[ -d /etc/systemd/system/$SERVICE_NAME.d ]]; then
    cp -a "/etc/systemd/system/$SERVICE_NAME.d" "$BACKUP_DIR/dropins"
fi
backup_helpers "$BACKUP_DIR"
printf '%s\n' "$BUILD_SHA" > "$BACKUP_DIR/source-commit.txt"
[[ -z $PREVIOUS_SOURCE_COMMIT ]] || printf '%s\n' "$PREVIOUS_SOURCE_COMMIT" > "$BACKUP_DIR/previous-commit.txt"

# 3. Server: stop, configuration, roles, migrations, helpers, unit settings; from here a failure rolls back.
if [[ $UPDATE_TARGET == server || $UPDATE_TARGET == ambos ]]; then
    systemctl stop "$SERVICE_NAME"
    STAGE=stopped
    DROPIN_BACKUP=$BACKUP_DIR
    configure_server_env
    configure_service_writable_paths
    ensure_provisioner_role
    migrate_role_layout
    apply_control_migrations
    apply_tenant_migrations
    bash "$TRUSTED_DIR/scripts/ubuntu/domains-sync.sh" --install
    bash "$TRUSTED_DIR/scripts/ubuntu/core-update.sh" --install
    bash "$TRUSTED_DIR/scripts/ubuntu/backup.sh" --install
    configure_service_hardening
fi

# 4. New binaries (with the release's migrations, root-owned: MIGRATIONS_ROOT) and web app. The flags are set before
# the first move so a failure between the two moves is restored as well.
if [[ $UPDATE_TARGET == server || $UPDATE_TARGET == ambos ]]; then
    rm -rf "$APP_RUNTIME_DIR.new"
    install -d -m 755 "$APP_RUNTIME_DIR.new"
    copy_dist_as_bms "$BUILD_DIR/server/build/install/server" "$APP_RUNTIME_DIR.new" "$BUILD_USER"
    install -d -m 755 "$APP_RUNTIME_DIR.new/migrations"
    bms_release_migrations "$TRUSTED_DIR" "$BUILD_SHA" "$APP_RUNTIME_DIR.new/migrations" ||
        die 'No se pudieron copiar las migraciones de la version a la distribucion.'
    chown -R root:root "$APP_RUNTIME_DIR.new"
    chmod -R a+rX "$APP_RUNTIME_DIR.new"
    chmod -R go-w "$APP_RUNTIME_DIR.new"

    rm -rf "$APP_RUNTIME_DIR.previous"
    APP_SWAPPED=true
    STAGE=swapped
    mv "$APP_RUNTIME_DIR" "$APP_RUNTIME_DIR.previous"
    mv "$APP_RUNTIME_DIR.new" "$APP_RUNTIME_DIR"
fi

if [[ $UPDATE_TARGET == webapp || $UPDATE_TARGET == ambos ]]; then
    rm -rf "$WEB_RUNTIME_DIR.new"
    install -d -m 755 "$WEB_RUNTIME_DIR.new"
    if [[ $WEB_DIST_ROOT_OWNED == true ]]; then
        # Already a checked root copy (fetch_ci_web_dist).
        cp -a -- "$WEB_DIST/." "$WEB_RUNTIME_DIR.new/"
    else
        copy_dist_as_bms "$WEB_DIST" "$WEB_RUNTIME_DIR.new" "$BUILD_USER"
    fi
    chown -R root:root "$WEB_RUNTIME_DIR.new"
    chmod -R a+rX "$WEB_RUNTIME_DIR.new"
    chmod -R go-w "$WEB_RUNTIME_DIR.new"
    rm -rf "$WEB_RUNTIME_DIR.previous"
    WEB_SWAPPED=true
    mv "$WEB_RUNTIME_DIR" "$WEB_RUNTIME_DIR.previous"
    mv "$WEB_RUNTIME_DIR.new" "$WEB_RUNTIME_DIR"
fi

# /opt/bms/source is only a reference copy now (the server reads its migrations from the distribution): best effort.
git_source fetch --tags --prune origin || warn "no se pudo actualizar $SOURCE_DIR (fetch)."
git_source checkout --detach "$BUILD_SHA" || warn "no se pudo dejar $SOURCE_DIR en el commit $BUILD_SHA (solo es una copia de referencia)."

# 5. Start and check; a release that does not answer, logs PostgreSQL permission errors or loses database access is
# rolled back (on_update_exit).
if [[ $UPDATE_TARGET == server || $UPDATE_TARGET == ambos ]]; then
    systemctl daemon-reload
    STARTED_AT=$(date +%s)
    systemctl start "$SERVICE_NAME"
    if ! backend_answers "$STARTED_AT"; then
        systemctl status "$SERVICE_NAME" --no-pager >&2 || true
        journalctl -u "$SERVICE_NAME" -n 80 --no-pager >&2 || true
        die 'La version nueva no paso la comprobacion (HTTP, permisos de PostgreSQL o acceso a las bases).'
    fi
    # M3: what the root helper compares the next request with (it refuses anything that is not a later version).
    printf '%s\n' "$BUILD_SHA" > "$DEPLOYED_FILE.tmp"
    chmod 600 "$DEPLOYED_FILE.tmp"
    mv -f -- "$DEPLOYED_FILE.tmp" "$DEPLOYED_FILE"
fi
STAGE='done'

# 6. The deploy is done: nothing from here can fail the update (L8). Nginx (restores itself on failure) and cleanup.
set +e
trap - ERR
configure_nginx || warn 'no se pudo actualizar Nginx; sigue la configuracion anterior.'
rm -rf "$APP_RUNTIME_DIR.previous" "$WEB_RUNTIME_DIR.previous"
cd /
[[ $BUILD_DIR != "$BUILD_TREE" ]] || rm -rf --one-file-system -- "$BUILD_TREE"
prune_old "$BACKUP_ROOT" '20*' "$KEEP_BACKUPS"
# The database copies are the large part: only the last KEEP_DB_DUMPS updates keep theirs.
while IFS= read -r old_dump; do
    rm -rf --one-file-system -- "$old_dump/db"
done < <(find "$BACKUP_ROOT" -mindepth 1 -maxdepth 1 -name '20*' -type d -printf '%f\n' | sort -r | tail -n "+$((KEEP_DB_DUMPS + 1))" | sed "s#^#$BACKUP_ROOT/#")
prune_old "$BUILD_LOG_DIR" '*.log' "$KEEP_LOGS"

if [[ $HARDEN_HOST == true ]]; then
    bash "$TRUSTED_DIR/scripts/ubuntu/harden-host.sh" || warn 'harden-host.sh no termino bien; revisa su salida.'
fi

printf 'BMS actualizado (%s) de %s a %s.\n' "$UPDATE_TARGET" "$CURRENT_COMMIT" "$TARGET_COMMIT"
printf 'Respaldo: %s\n' "$BACKUP_DIR"
printf 'Logs: sudo journalctl -u bms -f\n'
exit 0
