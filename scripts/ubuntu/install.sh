#!/usr/bin/env bash
# First installation on Ubuntu 24.04/26.04 with systemd.
# Download this file, review it, then run: sudo bash install.sh
# Keep this guard POSIX-compatible: `sh install.sh` ignores the shebang.
if [ -z "${BASH_VERSION:-}" ]; then
    if command -v bash >/dev/null 2>&1 && [ -f "$0" ]; then
        exec bash -- "$0" "$@"
    fi
    printf '%s\n' 'Este instalador requiere Bash. Descarga el archivo y ejecuta: sudo bash install.sh' >&2
    exit 1
fi

set -Eeuo pipefail
set +x
umask 077

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
trap 'printf "Instalación interrumpida (línea %s). Se conservan los archivos y datos para diagnóstico.\n" "$LINENO" >&2' ERR

# Read from the terminal even when the script was downloaded via a pipe.
ask() {
    local value
    read -r -p "$2 [$3]: " value </dev/tty
    printf -v "$1" '%s' "${value:-$3}"
}

# Ubuntu's package database distinguishes an installed package from one that
# was removed but still has configuration files. Install only missing packages.
APT_UPDATED=false
ensure_packages() {
    local package status
    local missing=()
    for package in "$@"; do
        status=$(dpkg-query -W -f='${Status}' "$package" 2>/dev/null) || status='missing'
        if [[ $status == 'install ok installed' ]]; then
            printf 'Disponible: %s\n' "$package"
        else
            printf 'Falta: %s (se instalará)\n' "$package"
            missing+=("$package")
        fi
    done
    if (( ${#missing[@]} > 0 )); then
        if [[ $APT_UPDATED == false ]]; then
            apt-get update
            APT_UPDATED=true
        fi
        DEBIAN_FRONTEND=noninteractive apt-get install -y "${missing[@]}"
        for package in "${missing[@]}"; do
            [[ $(dpkg-query -W -f='${Status}' "$package") == 'install ok installed' ]] ||
                die "No se pudo completar la instalación de $package."
        done
    fi
}

[[ $EUID -eq 0 ]] || die 'Ejecuta con sudo bash install.sh.'
# --harden-host: al final instala ufw, fail2ban y unattended-upgrades (harden-host.sh, con confirmacion).
HARDEN_HOST=false
for argument in "$@"; do
    case "$argument" in
        --harden-host) HARDEN_HOST=true ;;
        *) die "Argumento desconocido: $argument" ;;
    esac
done
[[ -r /etc/os-release ]] || die 'No se pudo identificar Ubuntu.'
source /etc/os-release
[[ $ID == ubuntu && ($VERSION_ID == 24.04 || $VERSION_ID == 26.04) ]] ||
    die 'Este instalador requiere Ubuntu 24.04 o 26.04.'
[[ -d /run/systemd/system ]] || die 'Se necesita systemd (no ejecutar dentro de un contenedor).'
[[ -r /dev/tty ]] || die 'Se necesita una terminal interactiva; usa ssh -t.'

# One install or update at a time (update.sh takes the same lock): the script runs again under flock,
# which holds the lock until it ends. -o keeps the lock out of child processes such as a Gradle daemon.
readonly MAINTENANCE_LOCK_FILE=/run/bms-maintenance.lock
readonly MAINTENANCE_LOCK_BUSY=75
if [[ ${BMS_MAINTENANCE_LOCKED:-} != 1 ]]; then
    [[ -f $0 ]] || die 'Descarga install.sh y ejecutalo como archivo: sudo bash install.sh'
    command -v flock >/dev/null 2>&1 || die 'No se encontro flock (paquete util-linux).'
    lock_status=0
    flock -n -o -E "$MAINTENANCE_LOCK_BUSY" "$MAINTENANCE_LOCK_FILE" \
        env BMS_MAINTENANCE_LOCKED=1 bash -- "$0" "$@" || lock_status=$?
    (( lock_status != MAINTENANCE_LOCK_BUSY )) || die 'Ya hay una instalacion o actualizacion de BMS en curso. Espera a que termine.'
    exit "$lock_status"
fi

readonly APP_DIR=/opt/bms
readonly CONFIG_DIR=/etc/bms
readonly SERVICE_FILE=/etc/systemd/system/bms.service
readonly REPO_URL=https://github.com/alexcalbri/BMS.git
readonly REPO_RAW_BASE=https://raw.githubusercontent.com/alexcalbri/BMS
# Files this installer needs from the selected branch. They are checked before the
# server is modified and again after cloning.
readonly REQUIRED_FILES=(
    scripts/ubuntu/migrations.sh
    scripts/ubuntu/db-roles.sh
    scripts/ubuntu/nginx-site.sh
    scripts/ubuntu/units.sh
    scripts/ubuntu/dist.sh
    scripts/ubuntu/backup.sh
    scripts/ubuntu/harden-host.sh
    scripts/ubuntu/domains-sync.sh
    scripts/ubuntu/core-update.sh
    scripts/ubuntu/mirror.sh
    database/core/migrations/V001__create_application_users.sql
    database/core/migrations/V002__create_application_sessions.sql
    database/core/migrations/V003__add_application_user_profile.sql
    database/core/migrations/V004__add_application_user_code.sql
    database/core/migrations/V005__create_notifications.sql
    database/core/migrations/V006__add_application_user_language.sql
    database/control/migrations/V001__create_server_registry.sql
    database/control/migrations/V002__create_provisioning_audit_log.sql
    database/control/migrations/V003__create_server_modules.sql
    database/control/migrations/V004__create_server_settings_and_module_packages.sql
    database/control/migrations/V005__create_public_domains.sql
    database/control/migrations/V006__add_server_workspace.sql
    database/control/migrations/V007__add_audit_actor_identity.sql
    database/control/migrations/V008__add_company_owner_group.sql
    database/control/migrations/V009__add_server_owner_company_sessions.sql
    database/control/migrations/V010__create_core_update_requests.sql
    database/control/migrations/V011__create_extensions.sql
    database/control/migrations/V012__extension_zip_url_sources.sql
    database/control/migrations/V013__create_company_links.sql
    database/control/migrations/V014__add_company_default_language.sql
    database/control/migrations/V015__create_server_admins.sql
    database/control/migrations/V016__core_update_commit_pin.sql
)
# Both services run on this Ubuntu host. No server IP needs to be entered.
readonly APP_HOST=127.0.0.1
readonly APP_PORT=8080
readonly NGINX_SITE=/etc/nginx/sites-available/bms
readonly ACME_ROOT=/var/www/bms-acme
readonly INSTALL_SWAP_FILE=$APP_DIR/install.swap
readonly INSTALL_SWAP_SIZE_MB=4096
readonly GRADLE_JVM_ARGS="-Xmx1536M -Dfile.encoding=UTF-8"
readonly NODE_OPTIONS_VALUE="--max-old-space-size=3072"
readonly COMPANY_BACKUPS_DIR=$APP_DIR/company-backups
# Files modules store per company (CRM attachments...); back it up with the databases.
readonly COMPANY_FILES_DIR=$APP_DIR/company-files
readonly MODULE_PACKAGES_DIR=$APP_DIR/server-modules
# El servidor guarda los paquetes de extension junto a los de modulos (carpeta hermana server-extensions).
readonly EXTENSION_PACKAGES_DIR=$APP_DIR/server-extensions
readonly DEFAULT_MODULE_CATALOG_URL=https://github.com/alexcalbri/BMS/tree/master/modules
# Todo lo que root ejecuta o lee (scripts, SQL) sale de este espejo de root, nunca de /opt/bms/source (de bms).
readonly ROOT_STATE_DIR=/var/lib/bms
readonly MIRROR_DIR=$ROOT_STATE_DIR/mirror
# El codigo se compila como otro usuario que el del servicio (bms): la compilacion no ve el servidor en marcha.
readonly BUILD_USER=bms-build
readonly BUILD_HOME=$APP_DIR/build-home
readonly BUILD_TREE=$APP_DIR/build-tree
# Login propio del aprovisionamiento de empresas (SELF_HOSTING.md, Roles de PostgreSQL).
readonly PROVISIONER=bms_provisioner
INSTALL_SWAP_CREATED=false

cleanup_install_swap() {
    if [[ $INSTALL_SWAP_CREATED == true ]]; then
        swapoff "$INSTALL_SWAP_FILE" >/dev/null 2>&1 || true
        rm -f "$INSTALL_SWAP_FILE" 2>/dev/null || {
            swapoff "$INSTALL_SWAP_FILE" >/dev/null 2>&1 || true
            rm -f "$INSTALL_SWAP_FILE" || true
        }
    fi
}

trap 'cleanup_install_swap; printf "Instalación interrumpida (línea %s). Se conservan los archivos y datos para diagnóstico.\n" "$LINENO" >&2' ERR
trap 'cleanup_install_swap' EXIT

# An interrupted install or update can leave its temporary swap behind, even active (an active swap
# file cannot be deleted). The maintenance lock guarantees none is running, so any swap under
# APP_DIR is stale.
recover_stale_swaps() {
    local swap_file
    while IFS= read -r swap_file; do
        if [[ $swap_file == "$APP_DIR"/* || $swap_file == /var/lib/bms/* ]]; then
            printf 'Desactivando swap temporal de una ejecucion anterior: %s\n' "$swap_file"
            swapoff "$swap_file"
        fi
    done < <(awk 'NR > 1 { print $1 }' /proc/swaps)
    for swap_file in "$INSTALL_SWAP_FILE" "$APP_DIR/update.swap" /var/lib/bms/update.swap; do
        if [[ -e $swap_file ]]; then
            printf 'Eliminando swap temporal de una ejecucion anterior: %s\n' "$swap_file"
            rm -f "$swap_file"
        fi
    done
}

ensure_install_swap() {
    local available_kb total_swap_kb
    # Before measuring: a leftover active swap would count as available and then never be removed.
    recover_stale_swaps
    available_kb=$(awk '/MemAvailable:/ { print $2 }' /proc/meminfo)
    total_swap_kb=$(awk '/SwapTotal:/ { print $2 }' /proc/meminfo)

    if (( available_kb >= 2097152 || total_swap_kb >= 1048576 )); then
        return
    fi

    printf 'Memoria limitada detectada; se creara swap temporal de %s MiB para compilar.\n' "$INSTALL_SWAP_SIZE_MB"
    if command -v fallocate >/dev/null 2>&1; then
        fallocate -l "${INSTALL_SWAP_SIZE_MB}M" "$INSTALL_SWAP_FILE" ||
            dd if=/dev/zero of="$INSTALL_SWAP_FILE" bs=1M count="$INSTALL_SWAP_SIZE_MB" status=none
    else
        dd if=/dev/zero of="$INSTALL_SWAP_FILE" bs=1M count="$INSTALL_SWAP_SIZE_MB" status=none
    fi
    chmod 600 "$INSTALL_SWAP_FILE"
    mkswap "$INSTALL_SWAP_FILE" >/dev/null
    swapon "$INSTALL_SWAP_FILE"
    INSTALL_SWAP_CREATED=true
}

run_gradle() {
    runuser -u "$BUILD_USER" -- env \
        HOME="$BUILD_HOME" \
        GRADLE_USER_HOME="$BUILD_HOME/.gradle" \
        JAVA_HOME="$JAVA_HOME" \
        NODE_OPTIONS="$NODE_OPTIONS_VALUE" \
        bash ./gradlew \
        --no-daemon \
        --no-configuration-cache \
        --max-workers=1 \
        --console=plain \
        "-Dorg.gradle.jvmargs=$GRADLE_JVM_ARGS" \
        "-Pkotlin.compiler.execution.strategy=in-process" \
        "$@"
}

# Root mirror of the repository (the same as mirror.sh, which may not be next to this file): cloned and moved to
# <ref> by root, hooks off; prints the commit.
mirror_git() {
    GIT_TERMINAL_PROMPT=0 git -c core.hooksPath=/dev/null -c core.fsmonitor=false -C "$MIRROR_DIR" "$@"
}
install_sync_mirror() {
    local ref=$1
    install -d -m 700 -o root -g root "$ROOT_STATE_DIR" || return 1
    if [[ ! -d $MIRROR_DIR/.git ]]; then
        rm -rf --one-file-system -- "$MIRROR_DIR" || return 1
        GIT_TERMINAL_PROMPT=0 git -c core.hooksPath=/dev/null -c core.fsmonitor=false clone --quiet "$REPO_URL" "$MIRROR_DIR" || return 1
    fi
    chown root:root "$MIRROR_DIR" && chmod 700 "$MIRROR_DIR" || return 1
    [[ $(mirror_git remote get-url origin) == "$REPO_URL" ]] || return 1
    mirror_git fetch --quiet --no-tags origin "$ref" || return 1
    mirror_git checkout --quiet --force --detach FETCH_HEAD || return 1
    mirror_git clean -ffdxq || return 1
    mirror_git rev-parse HEAD
}

# psql as the PostgreSQL superuser for db-roles.sh; SQL on stdin.
bms_pg_super() {
    local database=$1
    shift
    runuser -u postgres -- psql -X -v ON_ERROR_STOP=1 -d "$database" "$@"
}

# Checks over HTTPS that the selected branch has every file the installer needs, before
# cleaning or changing anything on this server. If it cannot be checked (no curl or no
# network), the same check runs again after cloning.
verify_branch_files() {
    local file status
    local missing=()
    if ! command -v curl >/dev/null 2>&1; then
        printf 'curl no esta instalado; la rama %s se verificara despues de descargarla.\n' "$GIT_REF"
        return
    fi
    printf 'Verificando que la rama %s tenga todo lo necesario...\n' "$GIT_REF"
    for file in "${REQUIRED_FILES[@]}"; do
        status=$(curl --silent --location --output /dev/null --write-out '%{http_code}' --max-time 15 \
            "$REPO_RAW_BASE/$GIT_REF/$file") || status=000
        case "$status" in
            200) ;;
            404) missing+=("$file") ;;
            *)
                printf 'AVISO: no se pudo verificar la rama (HTTP %s); se verificara despues de descargarla.\n' "$status"
                return
                ;;
        esac
    done
    if (( ${#missing[@]} > 0 )); then
        printf 'La rama %s no tiene:\n' "$GIT_REF" >&2
        printf ' - %s\n' "${missing[@]}" >&2
        die "La rama $GIT_REF no sirve para este instalador (version antigua o inexistente). Elige una rama actualizada (normalmente master). No se modifico nada en el servidor."
    fi
}

# `systemctl reload nginx` returns before new workers serve requests, so retry.
# On failure, show what answered instead of the app to make diagnosis possible.
verify_web_index() {
    local scheme=$1 port=$2 response status body expected
    expected=$(cat "$APP_DIR/web/index.html")
    for _ in {1..10}; do
        response=$(curl --noproxy '*' --silent --show-error --max-time 15 \
            --resolve "$DOMAIN:$port:127.0.0.1" --write-out '\n%{http_code}' \
            "$scheme://$DOMAIN/") || response=$'\n000'
        status=${response##*$'\n'}
        body=$(printf '%s' "${response%$'\n'*}")
        [[ $status == 200 && $body == "$expected" ]] && return
        sleep 2
    done
    printf '\nRespuesta recibida para %s://%s/ (HTTP %s):\n' "$scheme" "$DOMAIN" "$status" >&2
    printf '%s\n' "$body" | head -n 15 >&2 || true
    printf '\nSitios Nginx habilitados (listen/server_name/root):\n' >&2
    nginx -T 2>/dev/null | grep -nE '^# configuration file|listen|server_name|root ' >&2 || true
    die "El dominio no esta sirviendo el index.html de la app por ${scheme^^}."
}

verify_acme_challenge() {
    local address=$1
    local resolve_address=$address
    local reply=''
    local delay
    local delays=(0 10 20 30 60)

    [[ $address != *:* ]] || resolve_address="[$address]"
    for delay in "${delays[@]}"; do
        if (( delay > 0 )); then
            printf 'Reintentando verificacion HTTP en %s segundos...\n' "$delay"
            sleep "$delay"
        fi

        if reply=$(curl --noproxy '*' --fail --silent --show-error --max-time 15 \
            --resolve "$DOMAIN:80:$resolve_address" "http://$DOMAIN/.well-known/acme-challenge/$DNS_TOKEN"); then
            [[ $reply == "$DNS_TOKEN" ]] || die "El registro $address responde desde otro sitio. Corrige el DNS."
            return
        fi
    done

    die "No se pudo leer el desafío HTTP por $address:80 despues de varios intentos. Revisa DNS, firewall/NAT y /var/log/nginx/error.log. Corrige la causa y vuelve a ejecutar con el mismo dominio."
}
clean_files_and_services() {
    printf 'Deteniendo servicios de BMS si existen...\n'
    systemctl stop bms.service >/dev/null 2>&1 || true
    systemctl disable bms.service >/dev/null 2>&1 || true
    systemctl stop bms-certbot.timer bms-certbot.service >/dev/null 2>&1 || true
    systemctl disable bms-certbot.timer bms-certbot.service >/dev/null 2>&1 || true
    rm -f "$SERVICE_FILE" /etc/systemd/system/bms-certbot.service /etc/systemd/system/bms-certbot.timer
    systemctl disable --now bms-domains.timer bms-domains-renew.timer >/dev/null 2>&1 || true
    rm -f /etc/systemd/system/bms-domains{,-renew}.{service,timer} /usr/local/sbin/bms-domains-sync
    systemctl disable --now bms-core-update.timer >/dev/null 2>&1 || true
    rm -f /etc/systemd/system/bms-core-update.{service,timer} /usr/local/sbin/bms-core-update
    rm -f /etc/nginx/sites-enabled/bms "$NGINX_SITE"
    rm -f /etc/nginx/sites-enabled/bms-domain-* /etc/nginx/sites-available/bms-domain-*
    rm -f /etc/nginx/sites-enabled/bms-default /etc/nginx/sites-available/bms-default /etc/nginx/conf.d/bms-limits.conf
    systemctl disable --now bms-backup.timer >/dev/null 2>&1 || true
    rm -f /etc/systemd/system/bms-backup.{service,timer} /usr/local/sbin/bms-backup
    rm -rf /etc/systemd/system/bms.service.d /etc/systemd/system/bms-domains.service.d \
        /etc/systemd/system/bms-domains-renew.service.d /etc/systemd/system/bms-backup.service.d /etc/systemd/system/bms-certbot.service.d
    recover_stale_swaps
    # The daily backups (/var/lib/bms/db-backups) are kept: a clean install must not take the last copies with it.
    rm -rf "$APP_DIR" "$CONFIG_DIR" "$ACME_ROOT" /var/log/bms-update
    if [[ -d /var/lib/bms ]]; then
        find /var/lib/bms -mindepth 1 -maxdepth 1 ! -name db-backups -exec rm -rf --one-file-system -- {} +
    fi
    if getent passwd bms >/dev/null; then
        userdel -r bms >/dev/null 2>&1 || userdel bms >/dev/null 2>&1 || true
    fi
    if getent passwd "$BUILD_USER" >/dev/null; then
        userdel "$BUILD_USER" >/dev/null 2>&1 || true
    fi
    systemctl daemon-reload >/dev/null 2>&1 || true
}

clean_postgres_objects() {
    local db_name=$1
    local db_user=$2
    local server_owner=$3
    local tenant_db
    local tenant_role
    local tenant_roles_file
    local tenant_roles_count
    local role_delete_confirm
    tenant_roles_file=$(mktemp)
    if [[ ${CLEAN_TENANT_DATABASES:-no} == si ]]; then
        local tenant_dbs
        tenant_dbs=$(pg_admin -Atc "SELECT datname FROM pg_database WHERE datname LIKE 'bms\_%' AND datname <> '$db_name' ORDER BY datname") ||
            die 'No se pudo listar las bases tenant; se aborta antes de borrar nada.'
        while IFS= read -r tenant_db; do
            [[ -n $tenant_db ]] || continue
            printf 'Leyendo usuarios PostgreSQL de tenant %s...\n' "$tenant_db"
            pg_admin -d "$tenant_db" -Atc "SELECT postgres_role FROM application_users ORDER BY postgres_role" >>"$tenant_roles_file" ||
                printf 'AVISO: no se pudieron leer los usuarios PostgreSQL de %s; sus roles no se detectaran para borrar.\n' "$tenant_db" >&2
            printf 'Borrando base tenant %s...\n' "$tenant_db"
            pg_admin -v tenant_db="$tenant_db" <<'SQL'
SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = :'tenant_db';
DROP DATABASE IF EXISTS :"tenant_db";
SQL
        done <<< "$tenant_dbs"
    fi
    pg_admin -v db_name="$db_name" <<'SQL'
SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = :'db_name';
DROP DATABASE IF EXISTS :"db_name";
SQL
    if [[ $(pg_admin -Atc "SELECT count(*) FROM pg_roles WHERE rolname = '$db_user'") != 0 ]]; then
        pg_admin -v db_user="$db_user" <<'SQL'
DROP OWNED BY :"db_user" CASCADE;
DROP ROLE :"db_user";
SQL
    fi
    if [[ $(pg_admin -Atc "SELECT count(*) FROM pg_roles WHERE rolname = '$server_owner'") != 0 ]]; then
        pg_admin -v server_owner="$server_owner" <<'SQL'
DROP OWNED BY :"server_owner" CASCADE;
DROP ROLE :"server_owner";
SQL
    fi
    if [[ $(pg_admin -Atc "SELECT count(*) FROM pg_roles WHERE rolname = '$PROVISIONER'") != 0 ]]; then
        pg_admin -v provisioner="$PROVISIONER" <<'SQL'
DROP OWNED BY :"provisioner" CASCADE;
DROP ROLE :"provisioner";
SQL
    fi
    if [[ -s $tenant_roles_file ]]; then
        tenant_roles_count=$( (sort -u "$tenant_roles_file" | grep -Ev "^(${db_user}|${server_owner})$" || true) | wc -l | tr -d ' ')
        if [[ $tenant_roles_count != 0 ]]; then
            printf '\nUsuarios PostgreSQL tenant detectados para borrar:\n'
            (sort -u "$tenant_roles_file" | grep -Ev "^(${db_user}|${server_owner})$" || true) | sed 's/^/ - /'
            printf 'Para borrar estos usuarios escribe BORRAR_USUARIOS_POSTGRES: '
            read -r role_delete_confirm
            [[ $role_delete_confirm == BORRAR_USUARIOS_POSTGRES ]] || die 'Cancelado antes de borrar usuarios PostgreSQL tenant.'
            (sort -u "$tenant_roles_file" | grep -Ev "^(${db_user}|${server_owner})$" || true) | while IFS= read -r tenant_role; do
                [[ -n $tenant_role ]] || continue
                if [[ ! $tenant_role =~ ^[a-z][a-z0-9_]{0,62}$ || $tenant_role == pg_* || $tenant_role == postgres ]]; then
                    printf 'Omitiendo rol tenant con nombre no permitido: %s\n' "$tenant_role"
                    continue
                fi
                if [[ $(pg_admin -Atc "SELECT count(*) FROM pg_roles WHERE rolname = '$tenant_role'") != 0 ]]; then
                    printf 'Borrando usuario PostgreSQL tenant %s...\n' "$tenant_role"
                    pg_admin -v tenant_role="$tenant_role" <<'SQL'
DROP ROLE :"tenant_role";
SQL
                fi
            done
        fi
    fi
    rm -f "$tenant_roles_file"
}

assert_no_existing_install() {
    for unit in /etc/systemd/system/bms-certbot.service /etc/systemd/system/bms-certbot.timer; do
        [[ ! -e $unit && ! -L $unit ]] || die 'Ya existe una configuracion de renovacion de BMS. Usa instalacion limpia si quieres borrarla.'
    done
    [[ ! -e $APP_DIR && ! -e $CONFIG_DIR && ! -e $SERVICE_FILE ]] ||
        die 'Ya hay archivos de BMS. Usa instalacion limpia para borrarlos o elimina manualmente antes de continuar.'
    [[ ! -e $NGINX_SITE && ! -e /etc/nginx/sites-enabled/bms ]] ||
        die 'Ya existe configuracion Nginx de BMS. Usa instalacion limpia si quieres reemplazarla.'
    ! getent passwd bms >/dev/null || die 'El usuario Linux bms ya existe. Usa instalacion limpia si pertenece a BMS.'
    ! getent group bms >/dev/null || die 'El grupo Linux bms ya existe. Usa instalacion limpia si pertenece a BMS.'
    ! getent passwd "$BUILD_USER" >/dev/null || die "El usuario Linux $BUILD_USER ya existe. Usa instalacion limpia si pertenece a BMS."
    [[ $(systemctl show bms.service --property=LoadState --value) == not-found ]] ||
        die 'Ya existe un servicio bms. Usa instalacion limpia si quieres reemplazarlo.'
}

printf '\nBMS: instalacion completa del backend en este Ubuntu.\n'
printf 'Se comprobaran Java 21, PostgreSQL, Nginx, Git, curl, Python, DNS y OpenSSL.\n'
printf 'Puedes elegir publicacion HTTPS con Lets Encrypt o HTTP sin certificado.\n'
printf 'Los componentes que falten se instalaran; Gradle descargara Ktor al compilar.\n'
printf 'Repositorio: %s\nLa rama elegida debe incluir este instalador y sus cambios de backend.\n' "$REPO_URL"
ask CLEAN_INSTALL 'Instalacion limpia y borrar restos anteriores de BMS? Escribe si o no' no
[[ $CLEAN_INSTALL == si || $CLEAN_INSTALL == no ]] || die 'Responde si o no.'
ask PUBLIC_SCHEME 'Modo publico: https o http' https
[[ $PUBLIC_SCHEME == https || $PUBLIC_SCHEME == http ]] || die 'Responde https o http.'
if [[ $PUBLIC_SCHEME == http ]]; then
    printf '\nALERTA: HTTP no cifra usuarios, contrasenas ni tokens. Usalo solo para pruebas temporales en un entorno controlado.\n'
    ask HTTP_CONFIRM 'Para confirmar HTTP escribe HTTP' no
    [[ $HTTP_CONFIRM == HTTP ]] || die 'Cancelado antes de publicar por HTTP.'
fi
ask GIT_REF 'Rama o etiqueta de GitHub (master = estable, develop = pruebas)' master
[[ $GIT_REF =~ ^[a-zA-Z0-9][a-zA-Z0-9._/-]*$ && $GIT_REF != *..* ]] || die 'Referencia Git inválida.'
verify_branch_files
ask DOMAIN 'Dominio público que apunta a este Ubuntu (sin https:// ni rutas)' ''
DOMAIN=${DOMAIN,,}
[[ ${#DOMAIN} -le 240 && $DOMAIN == *.* && $DOMAIN =~ ^[a-z0-9.-]+$ && ! $DOMAIN =~ ^[0-9.]+$ ]] ||
    die 'Introduce un dominio válido, por ejemplo api.tuempresa.com.'
IFS='.' read -r -a DOMAIN_LABELS <<< "$DOMAIN"
[[ $DOMAIN != *. ]] || die 'El dominio no debe terminar en punto.'
for label in "${DOMAIN_LABELS[@]}"; do
    [[ ${#label} -le 63 && $label =~ ^[a-z0-9]([a-z0-9-]*[a-z0-9])?$ ]] || die 'Dominio inválido.'
done

if [[ $CLEAN_INSTALL == si ]]; then
    printf '\nAVISO: la instalacion limpia borrara archivos, servicio, usuario Linux y configuracion Nginx de BMS.\n'
    ask CLEAN_CONFIRM 'Para confirmar escribe LIMPIAR' no
    [[ $CLEAN_CONFIRM == LIMPIAR ]] || die 'Cancelado antes de limpiar.'
    clean_files_and_services
else
    assert_no_existing_install
fi

# Resume only the exact temporary site for this domain, before application
# provisioning. Never adopt a live HTTPS site or custom Nginx configuration.
acme_site_config() {
    cat <<NGINX
server {
    listen 80;
    listen [::]:80;
    server_name $DOMAIN;
    location /.well-known/acme-challenge/ { root $ACME_ROOT; }
    location / { return 503; }
}
NGINX
}
RESUME_ACME=false
if [[ $PUBLIC_SCHEME == https ]]; then
    if [[ -e $NGINX_SITE || -L $NGINX_SITE ]]; then
        if ! { [[ -f $NGINX_SITE && ! -L $NGINX_SITE ]] && cmp -s "$NGINX_SITE" <(acme_site_config); }; then
            die 'La configuracion Nginx existente no es el sitio temporal de este dominio; no se modificara.'
        fi
        RESUME_ACME=true
        printf 'Se reanudara la preparacion HTTPS del dominio %s.\n' "$DOMAIN"
    fi
    if [[ -e /etc/nginx/sites-enabled/bms || -L /etc/nginx/sites-enabled/bms ]]; then
        [[ $RESUME_ACME == true && -L /etc/nginx/sites-enabled/bms &&
           $(readlink /etc/nginx/sites-enabled/bms) == "$NGINX_SITE" ]] ||
            die 'El sitio habilitado de BMS no corresponde al sitio temporal esperado.'
    fi
    ask ACME_EMAIL 'Correo para la cuenta de Lets Encrypt' ''
    [[ $ACME_EMAIL =~ ^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$ ]] || die 'Correo invalido.'
    printf 'Los registros A/AAAA deben apuntar a este servidor; los puertos 80 y 443 deben ser accesibles desde Internet.\n'
    printf 'Se solicitara un certificado para https://%s y se aceptaran los terminos de Lets Encrypt.\n' "$DOMAIN"
else
    printf 'Se eligio HTTP. Lets Encrypt se omitira y la app quedara publicada sin certificado.\n'
fi
ask DB_NAME 'Base central nueva de BMS' bms_control
ask DB_USER 'Usuario PostgreSQL nuevo para la aplicación' bms_app
# DB_NAME, DB_USER and SERVER_OWNER are set by ask (printf -v).
# shellcheck disable=SC2153
for identifier in "$DB_NAME" "$DB_USER"; do
    [[ $identifier =~ ^[a-z][a-z0-9_]{0,62}$ && $identifier != pg_* && $identifier != postgres ]] ||
        die 'Usa nombres de hasta 63 caracteres: letras minúsculas, números y guion bajo; no nombres reservados.'
done

while true; do
    read -r -s -p 'Contraseña nueva de PostgreSQL (mínimo 16 caracteres ASCII imprimibles): ' DB_PASSWORD </dev/tty
    printf '\n'
    if [[ ${#DB_PASSWORD} -lt 16 || $DB_PASSWORD == *[!\ -\~]* ]]; then
        printf 'Usa al menos 16 caracteres ASCII imprimibles.\n'
        continue
    fi
    read -r -s -p 'Repite la contraseña: ' DB_PASSWORD_AGAIN </dev/tty
    printf '\n'
    [[ $DB_PASSWORD == "$DB_PASSWORD_AGAIN" ]] && break
    printf 'Las contraseñas no coinciden.\n'
done
unset DB_PASSWORD_AGAIN
ask SERVER_OWNER 'Usuario de login del propietario del servidor' server_owner
# shellcheck disable=SC2153
[[ $SERVER_OWNER =~ ^[a-z][a-z0-9_]{0,62}$ && $SERVER_OWNER != pg_* && $SERVER_OWNER != postgres ]] ||
    die 'Identificador invalido: usa letras minusculas, numeros y guion bajo.'
[[ $SERVER_OWNER != "$DB_USER" ]] || die 'La cuenta del propietario debe ser distinta del usuario interno de la aplicación.'
# Every change is recorded with the owner's name, and the email receives password reset codes.
ask SERVER_OWNER_NAME 'Nombre completo del propietario del servidor' ''
SERVER_OWNER_NAME=$(printf '%s' "$SERVER_OWNER_NAME" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')
[[ ${#SERVER_OWNER_NAME} -ge 2 && ${#SERVER_OWNER_NAME} -le 120 ]] || die 'Escribe un nombre de 2 a 120 caracteres.'
ask SERVER_OWNER_EMAIL 'Correo del propietario del servidor (recuperacion de contrasena)' ''
[[ ${#SERVER_OWNER_EMAIL} -le 254 && $SERVER_OWNER_EMAIL =~ ^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$ ]] || die 'Correo invalido.'
if [[ $CLEAN_INSTALL == si ]]; then
    ask CLEAN_TENANT_DATABASES 'Borrar tambien bases tenant bms_* de intentos anteriores? Escribe si o no' no
    [[ $CLEAN_TENANT_DATABASES == si || $CLEAN_TENANT_DATABASES == no ]] || die 'Responde si o no.'
fi
ask_password() {
    local secret repeated
    while true; do
        read -r -s -p "Contrasena para $2 (minimo 16 caracteres ASCII): " secret </dev/tty
        printf '\n'
        [[ ${#secret} -ge 16 && $secret != *[!\ -\~]* ]] || continue
        read -r -s -p 'Repite la contrasena: ' repeated </dev/tty
        printf '\n'
        [[ $secret == "$repeated" ]] && break
        printf 'Las contrasenas no coinciden.\n'
    done
    printf -v "$1" '%s' "$secret"
}
ask_password SERVER_PASSWORD "$SERVER_OWNER"
printf 'Este usuario podra crear empresas desde la app.\n'
printf '\nSe instalará %s en %s, con base %s, usuario %s y HTTP %s:%s.\n' \
    "$GIT_REF" "$APP_DIR" "$DB_NAME" "$DB_USER" "$APP_HOST" "$APP_PORT"
ask CONFIRM '¿Continuar? Escribe si' no
[[ $CONFIRM == si ]] || die 'Cancelado sin instalar paquetes.'

# 1. Ubuntu packages. Ktor is downloaded by Gradle as an application dependency.
ensure_packages ca-certificates curl git openjdk-21-jdk-headless \
    postgresql postgresql-client python3 bind9-dnsutils nginx openssl openssh-client
for executable in curl git python3 dig nginx openssl psql pg_isready runuser useradd systemd-analyze; do
    command -v "$executable" >/dev/null 2>&1 || die "Falta el ejecutable $executable después de comprobar los paquetes."
done

if [[ $PUBLIC_SCHEME == https ]]; then
    # Check public DNS before installing Certbot or requesting a certificate.
    DOMAIN_IPS=()
    for record in A AAAA; do
        DNS_ANSWER=$(dig @1.1.1.1 +time=5 +tries=2 +short "$DOMAIN" "$record") || die 'No se pudo consultar DNS público.'
        while IFS= read -r address; do
            # Ignore CNAME names; dig also returns their resolved addresses.
            if [[ $address =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ || $address == *:* ]]; then
                DOMAIN_IPS+=("$address")
            fi
        done <<< "$DNS_ANSWER"
    done
    (( ${#DOMAIN_IPS[@]} > 0 )) || die 'El dominio no tiene registros públicos A/AAAA resolubles.'
    NGINX_CONFIG=$(nginx -T 2>&1) || die 'La configuración Nginx existente no es válida.'
    if [[ $RESUME_ACME == true ]]; then
        # Remove only one exact copy; any other occurrence still blocks a conflict.
        NGINX_CONFIG=${NGINX_CONFIG/"$(acme_site_config)"/}
    fi
    if [[ $NGINX_CONFIG == *"$DOMAIN"* ]]; then
        die 'El dominio ya aparece en Nginx. Revisa su configuración antes de instalar; no se sobrescribirá.'
    fi
    install -d -m 755 "$ACME_ROOT" "$ACME_ROOT/.well-known" "$ACME_ROOT/.well-known/acme-challenge"
    acme_site_config > "$NGINX_SITE"
    chmod 644 "$NGINX_SITE"
    rm -f /etc/nginx/sites-enabled/bms
    ln -s "$NGINX_SITE" /etc/nginx/sites-enabled/bms
    nginx -t
    systemctl enable --now nginx
    systemctl reload nginx

    # Check every published address, including IPv6, using an unpredictable token.
    # This also rejects stale DNS entries pointing at a different web server.
    DNS_TOKEN=$(openssl rand -hex 24)
    printf '%s' "$DNS_TOKEN" > "$ACME_ROOT/.well-known/acme-challenge/$DNS_TOKEN"
    chmod 644 "$ACME_ROOT/.well-known/acme-challenge/$DNS_TOKEN"
    for address in "${DOMAIN_IPS[@]}"; do
        printf 'Verificando dominio %s en %s, puerto 80...\n' "$DOMAIN" "$address"
        verify_acme_challenge "$address"
    done
    rm -- "$ACME_ROOT/.well-known/acme-challenge/$DNS_TOKEN"

    # Let's Encrypt is a certificate authority; Certbot is its local ACME client.
    if ! command -v certbot >/dev/null 2>&1; then
        ensure_packages certbot
    else
        printf 'Disponible: Certbot (se reutilizará la instalación existente).\n'
    fi
    CERTBOT_BIN=$(command -v certbot)
    "$CERTBOT_BIN" --version
    CERT_NAME="bms-$DOMAIN"
    "$CERTBOT_BIN" certonly --webroot --webroot-path "$ACME_ROOT" \
        --server https://acme-v02.api.letsencrypt.org/directory \
        --cert-name "$CERT_NAME" --domain "$DOMAIN" --email "$ACME_EMAIL" \
        --agree-tos --non-interactive --keep-until-expiring \
        --deploy-hook 'systemctl reload nginx'

fi
systemctl enable --now postgresql
pg_isready -h 127.0.0.1 -p 5432 || die 'PostgreSQL no responde en 127.0.0.1:5432.'
python3 - "$APP_HOST" "$APP_PORT" <<'PY'
import socket, sys
with socket.socket() as connection:
    try:
        connection.bind((sys.argv[1], int(sys.argv[2])))
    except OSError:
        sys.exit("El puerto HTTP 8080 no está disponible. Revisa qué servicio lo utiliza antes de instalar BMS.")
PY

pg_admin() { runuser -u postgres -- psql -X --dbname=postgres --set=ON_ERROR_STOP=1 "$@"; }
if [[ $CLEAN_INSTALL == si ]]; then
    clean_postgres_objects "$DB_NAME" "$DB_USER" "$SERVER_OWNER"
fi
[[ $(pg_admin -Atc "SELECT count(*) FROM pg_roles WHERE rolname = '$DB_USER'") == 0 ]] ||
    die 'El usuario PostgreSQL ya existe. No se cambiará su contraseña.'
[[ $(pg_admin -Atc "SELECT count(*) FROM pg_database WHERE datname = '$DB_NAME'") == 0 ]] ||
    die 'La base de datos ya existe. No se modificará.'
[[ $(pg_admin -Atc "SELECT count(*) FROM pg_roles WHERE rolname = '$SERVER_OWNER'") == 0 ]] || die "El rol $SERVER_OWNER ya existe."
[[ $(pg_admin -Atc "SELECT count(*) FROM pg_roles WHERE rolname = '$PROVISIONER'") == 0 ]] || die "El rol $PROVISIONER ya existe."
[[ $DB_USER != "$PROVISIONER" && $SERVER_OWNER != "$PROVISIONER" ]] || die "$PROVISIONER es el nombre del rol de aprovisionamiento; elige otro nombre."

# 2. Build without root privileges or Android SDK, as the build user, from a tree of the root mirror (A4: root never
# runs or reads code from /opt/bms/source, which bms can write). The wrapper pins Gradle.
# APP_DIR belongs to root: bms only owns its home and the directories created for it below,
# so it cannot plant symlinks where root writes.
install -d -m 755 -o root -g root "$APP_DIR"
useradd --system --user-group --create-home --home-dir "$APP_DIR/home" --shell /usr/sbin/nologin bms
chmod 700 "$APP_DIR/home"
useradd --system --user-group --no-create-home --home-dir "$BUILD_HOME" --shell /usr/sbin/nologin "$BUILD_USER"
install -d -m 700 -o "$BUILD_USER" -g "$BUILD_USER" "$BUILD_HOME"
chown root:root "$APP_DIR"
chmod 755 "$APP_DIR"
JAVA_HOME="/usr/lib/jvm/java-21-openjdk-$(dpkg --print-architecture)"
[[ -x $JAVA_HOME/bin/java ]] || die 'No se encontró el JDK 21 instalado.'
printf 'Descargando %s al espejo de root %s...\n' "$GIT_REF" "$MIRROR_DIR"
BUILD_SHA=$(install_sync_mirror "$GIT_REF") || die "No se pudo descargar $GIT_REF desde GitHub."
[[ $BUILD_SHA =~ ^[0-9a-f]{40}$ ]] || die 'No se pudo leer el commit descargado.'
readonly TRUSTED_DIR=$MIRROR_DIR
for required_file in "${REQUIRED_FILES[@]}"; do
    [[ -f $TRUSTED_DIR/$required_file ]] || die "La rama descargada no incluye $required_file. Publica los cambios antes de instalar."
done
# shellcheck source=db-roles.sh
source "$TRUSTED_DIR/scripts/ubuntu/db-roles.sh"
# shellcheck source=nginx-site.sh
source "$TRUSTED_DIR/scripts/ubuntu/nginx-site.sh"
# shellcheck source=units.sh
source "$TRUSTED_DIR/scripts/ubuntu/units.sh"
# shellcheck source=dist.sh
source "$TRUSTED_DIR/scripts/ubuntu/dist.sh"
# The server reads company migrations from /opt/bms/source (MIGRATIONS_ROOT): a clone of bms at the same commit.
install -d -m 755 -o bms -g bms "$APP_DIR/source"
runuser -u bms -- git clone --depth 1 --branch "$GIT_REF" -- "$REPO_URL" "$APP_DIR/source"
if [[ $(runuser -u bms -- git -C "$APP_DIR/source" rev-parse HEAD) != "$BUILD_SHA" ]]; then
    { runuser -u bms -- git -C "$APP_DIR/source" fetch --depth 1 origin "$BUILD_SHA" &&
        runuser -u bms -- git -C "$APP_DIR/source" checkout --quiet --detach "$BUILD_SHA"; } ||
        printf 'AVISO: %s no quedo en el commit %s.\n' "$APP_DIR/source" "$BUILD_SHA" >&2
fi
rm -rf --one-file-system -- "$BUILD_TREE"
install -d -m 755 -o "$BUILD_USER" -g "$BUILD_USER" "$BUILD_TREE"
git -c core.hooksPath=/dev/null -c core.fsmonitor=false -C "$TRUSTED_DIR" archive "$BUILD_SHA" |
    runuser -u "$BUILD_USER" -- tar -x -C "$BUILD_TREE" --no-same-owner
cd "$BUILD_TREE"
ensure_install_swap
runuser -u "$BUILD_USER" -- env HOME="$BUILD_HOME" JAVA_HOME="$JAVA_HOME" bash ./gradlew --stop >/dev/null 2>&1 || true
run_gradle -PserverOnly=true :server:tasks --all
run_gradle -PserverOnly=true :server:test :server:installDist
[[ -x server/build/install/server/bin/server ]] || die 'No se encontró la distribución del servidor.'
install -d -m 755 "$APP_DIR/app"
# Read as the build user and checked before root extracts it (dist.sh): no links, no special files.
copy_dist_as_bms "$BUILD_TREE/server/build/install/server" "$APP_DIR/app" "$BUILD_USER"
chown -R root:root "$APP_DIR/app"
chmod -R a+rX "$APP_DIR/app"
chmod -R go-w "$APP_DIR/app"

# Build the browser app without mobile SDKs. Gradle supplies Node and Yarn.
run_gradle -PwebOnly=true :app:webApp:jsBrowserDistribution
WEB_DIST="$BUILD_TREE/app/webApp/build/dist/js/productionExecutable"
[[ -f $WEB_DIST/index.html && -f $WEB_DIST/webApp.js ]] || die 'No se encontro la distribucion web completa.'
install -d -m 755 "$APP_DIR/web"
copy_dist_as_bms "$WEB_DIST" "$APP_DIR/web" "$BUILD_USER"
chown -R root:root "$APP_DIR/web"
chmod -R a+rX "$APP_DIR/web"
chmod -R go-w "$APP_DIR/web"
cd "$TRUSTED_DIR"
rm -rf --one-file-system -- "$BUILD_TREE"

# 3. PostgreSQL roles (SELF_HOSTING.md, Roles de PostgreSQL). Passwords only as SCRAM verifiers, computed from stdin
# and sent inside the SQL on psql's stdin: never an SQL statement in a log or a command argument (db-roles.sh).
printf '%s' "$DB_PASSWORD" | bms_role_set_password "$DB_USER" "$BMS_RUNTIME_ATTRIBUTES" || die "No se pudo crear el rol $DB_USER."
PROVISIONER_PASSWORD=$(openssl rand -hex 32)
printf '%s' "$PROVISIONER_PASSWORD" | bms_role_set_password "$PROVISIONER" "$BMS_PROVISIONER_ATTRIBUTES" || die "No se pudo crear el rol $PROVISIONER."
pg_admin --set=db_user="$DB_USER" --set=db_name="$DB_NAME" --set=provisioner="$PROVISIONER" <<'SQL'
CREATE DATABASE :"db_name";
REVOKE ALL ON DATABASE :"db_name" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"db_name" TO :"db_user", :"provisioner";
SQL
# shellcheck source=migrations.sh
source scripts/ubuntu/migrations.sh
apply_migration "$DB_NAME" core database/core/migrations/V001__create_application_users.sql
apply_migration "$DB_NAME" core database/core/migrations/V002__create_application_sessions.sql
apply_migration "$DB_NAME" core database/core/migrations/V003__add_application_user_profile.sql
apply_migration "$DB_NAME" core database/core/migrations/V004__add_application_user_code.sql
apply_migration "$DB_NAME" core database/core/migrations/V006__add_application_user_language.sql
apply_migration "$DB_NAME" control database/control/migrations/V001__create_server_registry.sql
apply_migration "$DB_NAME" control database/control/migrations/V002__create_provisioning_audit_log.sql
apply_migration "$DB_NAME" control database/control/migrations/V003__create_server_modules.sql
apply_migration "$DB_NAME" control database/control/migrations/V004__create_server_settings_and_module_packages.sql
apply_migration "$DB_NAME" control database/control/migrations/V005__create_public_domains.sql
apply_migration "$DB_NAME" control database/control/migrations/V006__add_server_workspace.sql
apply_migration "$DB_NAME" control database/control/migrations/V007__add_audit_actor_identity.sql
apply_migration "$DB_NAME" control database/control/migrations/V008__add_company_owner_group.sql
apply_migration "$DB_NAME" control database/control/migrations/V009__add_server_owner_company_sessions.sql
apply_migration "$DB_NAME" control database/control/migrations/V010__create_core_update_requests.sql
apply_migration "$DB_NAME" control database/control/migrations/V011__create_extensions.sql
apply_migration "$DB_NAME" control database/control/migrations/V012__extension_zip_url_sources.sql
apply_migration "$DB_NAME" control database/control/migrations/V013__create_company_links.sql
apply_migration "$DB_NAME" control database/control/migrations/V014__add_company_default_language.sql
apply_migration "$DB_NAME" control database/control/migrations/V015__create_server_admins.sql
apply_migration "$DB_NAME" control database/control/migrations/V016__core_update_commit_pin.sql
# The server owner's role owns the company databases and creates them and their business owner roles. The runtime
# role is NOT a member of it; the provisioning login may SET ROLE to it (and to the runtime role), never inherits.
printf '%s' "$SERVER_PASSWORD" | bms_role_set_password "$SERVER_OWNER" 'INHERIT NOSUPERUSER CREATEDB CREATEROLE' ||
    die "No se pudo crear el rol $SERVER_OWNER."
pg_admin -v login_role="$SERVER_OWNER" -v db_name="$DB_NAME" <<'SQL'
GRANT CONNECT ON DATABASE :"db_name" TO :"login_role";
SQL
bms_grant_set_only "$SERVER_OWNER" "$PROVISIONER" || die 'No se pudo dar al aprovisionamiento el rol del server owner.'
bms_grant_set_only "$DB_USER" "$PROVISIONER" || die 'No se pudo dar al aprovisionamiento el rol del runtime.'
runuser -u postgres -- psql -X -d "$DB_NAME" -v ON_ERROR_STOP=1 \
    -v db_user="$DB_USER" -v server_owner="$SERVER_OWNER" -v owner_name="$SERVER_OWNER_NAME" -v owner_email="$SERVER_OWNER_EMAIL" <<'SQL'
BEGIN;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
INSERT INTO application_users (id, postgres_role, is_active, display_name, email)
VALUES (gen_random_uuid(), :'server_owner', true, :'owner_name', :'owner_email');
INSERT INTO server_owners SELECT id FROM application_users WHERE postgres_role = :'server_owner';
GRANT USAGE ON SCHEMA public TO :"db_user";
GRANT SELECT ON application_users, server_owners TO :"db_user";
GRANT UPDATE (display_name, email) ON application_users TO :"db_user";
GRANT SELECT, UPDATE (user_code) ON application_users TO :"db_user";
GRANT UPDATE (language) ON application_users TO :"db_user";
GRANT SELECT, INSERT, UPDATE, DELETE ON password_reset_codes TO :"db_user";
GRANT SELECT, INSERT, UPDATE, DELETE ON companies TO :"db_user";
GRANT SELECT, INSERT ON provisioning_audit_log TO :"db_user";
GRANT SELECT, INSERT, UPDATE, DELETE ON application_sessions TO :"db_user";
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
GRANT INSERT (target_version, from_version, requested_by, target_commit) ON core_update_requests TO :"db_user";
GRANT USAGE ON SEQUENCE core_update_requests_id_seq TO :"db_user";
GRANT SELECT, INSERT, UPDATE ON server_extensions, extension_operations, extension_operation_targets TO :"db_user";
GRANT SELECT, INSERT ON extension_unsigned_authorizations TO :"db_user";
GRANT USAGE ON SEQUENCE extension_unsigned_authorizations_id_seq, extension_operations_id_seq TO :"db_user";
COMMIT;
SQL
bms_check_role_layout "$DB_NAME" "$DB_USER" "$PROVISIONER" || die 'La organizacion de roles de PostgreSQL no quedo como se esperaba.'
PGPASSWORD="$DB_PASSWORD" psql -X -h 127.0.0.1 -U "$DB_USER" -d "$DB_NAME" \
    -v ON_ERROR_STOP=1 -c 'SELECT count(*) AS usuarios_iniciales FROM application_users;'

# Reject passwordless pg_hba configurations before enabling public login.
if PGPASSWORD="$(openssl rand -hex 32)" psql -X -h 127.0.0.1 -U "$SERVER_OWNER" -d "$DB_NAME" -c 'SELECT 1' >/dev/null 2>&1; then
    die 'PostgreSQL acepta una contrasena incorrecta. Configura scram-sha-256 en pg_hba.conf.'
fi

# 4. systemd reads this root-only file; it must never be committed to Git.
install -d -m 700 "$CONFIG_DIR"
{
    printf 'JAVA_HOME=%s\nHOST=%s\nPORT=%s\n' "$JAVA_HOME" "$APP_HOST" "$APP_PORT"
    printf 'DB_URL=jdbc:postgresql://127.0.0.1:5432/%s\nDB_USER=%s\n' "$DB_NAME" "$DB_USER"
    printf '%s' "$DB_PASSWORD" | python3 -c \
        'import json, sys; print("DB_PASSWORD=" + json.dumps(sys.stdin.read()))'
    printf 'DB_POOL_SIZE=10\n'
    # Login used only for creating, migrating and deleting companies (never by the pooled connections).
    printf 'DB_PROVISIONING_USER=%s\nDB_PROVISIONING_PASSWORD=%s\n' "$PROVISIONER" "$PROVISIONER_PASSWORD"
    printf 'TENANT_DATABASES_JSON=[]\n'
    printf 'TENANT_JDBC_URL_PREFIX=jdbc:postgresql://127.0.0.1:5432/\n'
    printf 'MIGRATIONS_ROOT=%s/source\n' "$APP_DIR"
    printf 'COMPANY_BACKUPS_ROOT=%s\n' "$COMPANY_BACKUPS_DIR"
    printf 'COMPANY_FILES_ROOT=%s\n' "$COMPANY_FILES_DIR"
    printf 'MODULE_CATALOG_URL=%s\n' "$DEFAULT_MODULE_CATALOG_URL"
    printf 'MODULE_PACKAGES_ROOT=%s\n' "$MODULE_PACKAGES_DIR"
    # Optional GitHub token (fine-grained, read-only on public repositories) to avoid GitHub's
    # 60 requests/hour limit when listing and installing modules; edit and restart bms.
    printf 'MODULE_GITHUB_TOKEN=\n'
    # Instalar extensiones desde un ZIP o una URL queda apagado por seguridad; pon true y reinicia bms para activarlo.
    printf 'EXTENSION_UPLOAD_ENABLED=false\n'
    # Llave maestra que envuelve la llave de datos de cada empresa (secretos cifrados). Respaldala: nunca se regenera.
    printf 'BMS_MASTER_KEY=%s\n' "$(openssl rand -base64 32)"
    printf 'VERSION_CATALOG_URL=\n'
    # Branch the root update helper follows (bms-core-update).
    printf 'CORE_UPDATE_REF=master\n'
    # Read by the root domain helper (hotspot portals and other module domains).
    printf 'PUBLIC_SCHEME=%s\n' "$PUBLIC_SCHEME"
    # Base publica fija para los metadatos OAuth de agentes; evita confiar en la cabecera Host del cliente.
    printf 'BMS_PUBLIC_BASE_URL=%s://%s\n' "$PUBLIC_SCHEME" "$DOMAIN"
    printf 'ACME_EMAIL=%s\n' "${ACME_EMAIL:-}"
    # Server-wide SMTP used by modules that send email; edit and restart bms to enable.
    printf 'SMTP_HOST=\nSMTP_PORT=587\nSMTP_USERNAME=\nSMTP_PASSWORD=\nSMTP_FROM=\nSMTP_SECURITY=starttls\n'
} > "$CONFIG_DIR/server.env"
chmod 600 "$CONFIG_DIR/server.env"
printf 'Respalda BMS_MASTER_KEY (%s/server.env) fuera del servidor: sin ella no se pueden leer los secretos guardados (SELF_HOSTING.md, seccion 14).\n' "$CONFIG_DIR"
unset DB_PASSWORD PROVISIONER_PASSWORD

# The service runs with ProtectSystem=strict; these are its only writable paths.
install -d -m 700 -o bms -g bms "$COMPANY_BACKUPS_DIR" "$COMPANY_FILES_DIR" "$MODULE_PACKAGES_DIR" "$EXTENSION_PACKAGES_DIR"

cat > "$SERVICE_FILE" <<'UNIT'
[Unit]
Description=BMS Ktor server
Wants=network-online.target postgresql.service
After=network-online.target postgresql.service

[Service]
Type=simple
User=bms
Group=bms
WorkingDirectory=/opt/bms/app
EnvironmentFile=/etc/bms/server.env
ExecStart=/opt/bms/app/bin/server
Restart=on-failure
RestartSec=5
TimeoutStopSec=30
NoNewPrivileges=true
PrivateTmp=true
ProtectHome=true
ProtectSystem=strict
ReadWritePaths=/opt/bms/company-backups /opt/bms/company-files /opt/bms/server-modules /opt/bms/server-extensions
UMask=0077

[Install]
WantedBy=multi-user.target
UNIT
chmod 644 "$SERVICE_FILE"
# A6: sandbox as a drop-in (scripts/ubuntu/units.sh); update.sh keeps it current.
bms_service_hardening false | bms_write_dropin bms.service >/dev/null
systemd-analyze verify "$SERVICE_FILE"
systemctl daemon-reload
systemctl enable --now bms.service

# The HTTP route is installed only after the backend's SELECT 1 succeeds.
BACKEND_READY=false
for _ in {1..30}; do
    if systemctl is-active --quiet bms.service && \
        curl --noproxy '*' --fail --silent --max-time 2 "http://127.0.0.1:$APP_PORT/" >/dev/null; then
        BACKEND_READY=true
        break
    fi
    sleep 2
done
[[ $BACKEND_READY == true ]] || die 'El servidor no respondió. Revisa: sudo journalctl -u bms -n 100 --no-pager'

# Send credentials via stdin, never command arguments or logs. Revoke the
# temporary test sessions immediately; do not display or persist their tokens.
printf '%s' "$SERVER_PASSWORD" | python3 -c '
import json, sys, urllib.request
body = json.dumps({"username": sys.argv[1], "password": sys.stdin.read(),
                   "companyCode": None}).encode()
request = urllib.request.Request("http://127.0.0.1:8080/auth/login", data=body,
    headers={"Content-Type": "application/json"})
with urllib.request.urlopen(request, timeout=20) as response:
    result = json.load(response)
    if response.status != 200 or not result.get("accessToken"):
        sys.exit("Login inicial invalido")
    if result.get("role") != "server_owner" or result.get("companyCode") is not None:
        sys.exit("El login no devuelve el propietario del servidor esperado")
' "$SERVER_OWNER"
unset SERVER_PASSWORD
runuser -u postgres -- psql -X -d "$DB_NAME" -v ON_ERROR_STOP=1 \
    -c 'UPDATE application_sessions SET revoked_at = CURRENT_TIMESTAMP WHERE revoked_at IS NULL;'
printf 'Login del propietario del servidor verificado.\n'

# 5. Public access terminates at Nginx; Ktor is reachable only on loopback.
# A5: one generator for install.sh and update.sh (scripts/ubuntu/nginx-site.sh): 2 MB bodies except the upload
# routes, per-address limits, catch-all server for unknown names, headers and CSP.
BMS_NGINX_HTTP2_DIRECTIVE=no
bms_nginx_has_http2_directive && BMS_NGINX_HTTP2_DIRECTIVE=yes
NGINX_HTTP3=no
bms_nginx_has_http3 && NGINX_HTTP3=yes
install -d -m 755 -o root -g root "$BMS_NGINX_LOCAL_DIR"
bms_nginx_limits > "$BMS_NGINX_LIMITS_FILE"
chmod 644 "$BMS_NGINX_LIMITS_FILE"
# The catch-all only takes the ports no other site already serves as default_server (Ubuntu's "default" site).
write_nginx_catchall() {
    local taken ports=''
    taken=$(nginx -T 2>/dev/null | bms_nginx_taken_default_ports) || taken='80 443'
    [[ " $taken " == *' 80 '* ]] || ports='80'
    [[ $PUBLIC_SCHEME != https || " $taken " == *' 443 '* ]] || ports+=' 443'
    if [[ -n ${ports// /} ]]; then
        bms_nginx_catchall "$ports" > "$BMS_NGINX_CATCHALL_SITE"
        chmod 644 "$BMS_NGINX_CATCHALL_SITE"
        ln -sfn "$BMS_NGINX_CATCHALL_SITE" /etc/nginx/sites-enabled/bms-default
    fi
}
if [[ $PUBLIC_SCHEME == http ]]; then
    bms_nginx_site http "$DOMAIN" - "$APP_DIR/web" "$ACME_ROOT" "$APP_PORT" > "$NGINX_SITE"
    chmod 644 "$NGINX_SITE"
    rm -f /etc/nginx/sites-enabled/bms
    ln -s "$NGINX_SITE" /etc/nginx/sites-enabled/bms
    write_nginx_catchall
    nginx -t
    systemctl enable --now nginx
    systemctl reload nginx

    verify_web_index http 80
    curl --noproxy '*' --fail --silent --show-error --max-time 15 \
        --resolve "$DOMAIN:80:127.0.0.1" "http://$DOMAIN/webApp.js" >/dev/null
    LOGIN_STATUS=$(curl --noproxy '*' --silent --show-error --max-time 15 \
        --resolve "$DOMAIN:80:127.0.0.1" --output /dev/null --write-out '%{http_code}' \
        --header 'Content-Type: application/json' --data '{}' "http://$DOMAIN/auth/login")
    [[ $LOGIN_STATUS == 400 ]] || die 'La ruta HTTP /auth/login no devuelve la validacion esperada (400).'

    printf '\nBMS instalado temporalmente sin HTTPS: http://%s/\n' "$DOMAIN"
    printf 'PostgreSQL, backend y publicacion HTTP verificados. Lets Encrypt fue omitido.\n'
    printf 'Cuando el bloqueo termine, configura HTTPS antes de usarlo en produccion.\n'
else
    bms_nginx_site https "$DOMAIN" "$CERT_NAME" "$APP_DIR/web" "$ACME_ROOT" "$APP_PORT" "$NGINX_HTTP3" > "$NGINX_SITE"
    chmod 644 "$NGINX_SITE"
    rm -f /etc/nginx/sites-enabled/bms
    ln -s "$NGINX_SITE" /etc/nginx/sites-enabled/bms
    write_nginx_catchall
    nginx -t
    systemctl reload nginx

    cat > /etc/systemd/system/bms-certbot.service <<UNIT
[Unit]
Description=Renew the BMS TLS certificate
After=network-online.target nginx.service
Wants=network-online.target
[Service]
Type=oneshot
ExecStart=$CERTBOT_BIN renew --cert-name $CERT_NAME --quiet
UNIT
    cat > /etc/systemd/system/bms-certbot.timer <<'UNIT'
[Unit]
Description=Check BMS TLS certificate twice daily
[Timer]
OnCalendar=*-*-* 00,12:00:00
RandomizedDelaySec=3600
Persistent=true
[Install]
WantedBy=timers.target
UNIT
    chmod 644 /etc/systemd/system/bms-certbot.{service,timer}
    bms_helper_hardening | bms_write_dropin bms-certbot.service >/dev/null
    systemctl daemon-reload
    systemctl enable --now bms-certbot.timer
    verify_web_index https 443
    curl --noproxy '*' --fail --silent --show-error --max-time 15 \
        --resolve "$DOMAIN:443:127.0.0.1" "https://$DOMAIN/webApp.js" >/dev/null
    LOGIN_STATUS=$(curl --noproxy '*' --silent --show-error --max-time 15 \
        --resolve "$DOMAIN:443:127.0.0.1" --output /dev/null --write-out '%{http_code}' \
        --header 'Content-Type: application/json' --data '{}' "https://$DOMAIN/auth/login")
    [[ $LOGIN_STATUS == 400 ]] || die 'La ruta HTTPS /auth/login no devuelve la validacion esperada (400).'
    RENEWAL_VERIFIED=false
    if "$CERTBOT_BIN" renew --cert-name "$CERT_NAME" --dry-run; then
        RENEWAL_VERIFIED=true
    else
        printf 'AVISO: la instalacion funciona por HTTPS, pero no se pudo verificar la renovacion.\n' >&2
        printf 'Revisa /var/log/letsencrypt/letsencrypt.log. Si indica rateLimited o Service busy, espera antes de reintentar.\n' >&2
        printf 'Reintenta solo la prueba: %s renew --cert-name %s --dry-run\n' "$CERTBOT_BIN" "$CERT_NAME" >&2
    fi
    printf '\nBMS instalado: https://%s/\n' "$DOMAIN"
    printf 'PostgreSQL, backend y certificado HTTPS verificados.\n'
    if [[ $RENEWAL_VERIFIED == true ]]; then
        printf 'Renovacion de prueba verificada.\n'
    else
        printf 'Renovacion automatica configurada; prueba de renovacion PENDIENTE. No vuelvas a ejecutar todo el instalador.\n'
    fi
fi
bash "$TRUSTED_DIR/scripts/ubuntu/domains-sync.sh" --install
bash "$TRUSTED_DIR/scripts/ubuntu/core-update.sh" --install
bash "$TRUSTED_DIR/scripts/ubuntu/backup.sh" --install
if [[ $HARDEN_HOST == true ]]; then
    bash "$TRUSTED_DIR/scripts/ubuntu/harden-host.sh"
fi
printf 'Configuracion: /etc/bms/server.env\nLogs: sudo journalctl -u bms -f\n'
printf 'Correo SMTP del servidor: completa SMTP_* en server.env y ejecuta sudo systemctl restart bms.\n'
printf 'Login disponible: POST %s://%s/auth/login\n' "$PUBLIC_SCHEME" "$DOMAIN"
printf 'Aplicacion web disponible: %s://%s/\n' "$PUBLIC_SCHEME" "$DOMAIN"
printf 'Propietario del servidor: %s\n' "$SERVER_OWNER"
printf 'Empresas iniciales: ninguna. Crea la primera desde la app con el usuario server owner.\n'
printf 'Respaldo diario: /var/lib/bms/db-backups (bms-backup.timer). Guarda BMS_MASTER_KEY fuera del servidor.\n'
